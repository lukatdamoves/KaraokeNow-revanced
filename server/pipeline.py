"""Streaming separation pipeline — transport-independent core.

Design (from research, see PLAN.md):
- Audio is separated in fixed windows with overlap. Only the clean
  centre of each window is emitted; the model-inaccurate edges are
  discarded. Consecutive centres tile the song exactly, with no gaps.
- For Demucs: WINDOW = 7 s, OVERLAP = 0.25 -> HOP = 5.25 s,
  EDGE = 0.875 s (discarded each side). 7 s fits htdemucs's
  343,980-sample training limit; 10 s windows failed at deploy
  time with ValueError (441,000 > 343,980).
- PCM convention everywhere here: float32 in [-1, 1], shape
  (channels, samples), 44.1 kHz stereo. The fetch stage guarantees
  this (ffmpeg resamples); the model stage assumes it.
- Opus encoding/decoding goes through ffmpeg (libopus). The Modal
  image must include ffmpeg (apt_install in modal_app.py).

This module has NO network, NO model, and NO Modal dependency, so it
is fully testable locally. See tests/test_pipeline.py.
"""

from __future__ import annotations

import subprocess

import numpy as np

SAMPLE_RATE = 44100
CHANNELS = 2

# Demucs window recipe. MDX uses 6 s windows (EDGE 0.75 s, HOP 4.5 s);
# swap the constants if the model changes, the math below is general.
WINDOW_SECONDS = 7.0
OVERLAP = 0.25
WINDOW_SAMPLES = int(WINDOW_SECONDS * SAMPLE_RATE)
HOP_SAMPLES = int(WINDOW_SECONDS * (1.0 - OVERLAP) * SAMPLE_RATE)
EDGE_SAMPLES = (WINDOW_SAMPLES - HOP_SAMPLES) // 2
# Sanity: leading edge + centre + trailing edge == WINDOW, up to a
# 1-sample remainder on the trailing edge when WINDOW - HOP is odd
# (7 s: 38,587 + 231,525 + 38,588 == 308,700). Centres tile exactly
# by HOP regardless; the remainder sits in discarded edge context.
assert WINDOW_SAMPLES - (EDGE_SAMPLES * 2 + HOP_SAMPLES) in (0, 1)

OPUS_BITRATE = "128k"


def iter_windows(pcm: np.ndarray):
    """Yield (start_sample, window_pcm) for a complete song.

    The input is conceptually padded with EDGE zeros at the start, so
    window 0's centre covers the song's first samples exactly (no
    missing intro). Each window is WINDOW_SAMPLES long; the final
    window is zero-padded if the song ends mid-window.
    """
    n = pcm.shape[1]
    padded = np.concatenate(
        [np.zeros((pcm.shape[0], EDGE_SAMPLES), dtype=np.float32), pcm],
        axis=1,
    )
    start = 0
    while start < n:
        window = padded[:, start : start + WINDOW_SAMPLES]
        if window.shape[1] < WINDOW_SAMPLES:
            pad = np.zeros(
                (pcm.shape[0], WINDOW_SAMPLES - window.shape[1]),
                dtype=np.float32,
            )
            window = np.concatenate([window, pad], axis=1)
        yield start, window
        start += HOP_SAMPLES


def centre_of(window_separated: np.ndarray, start: int, total: int) -> np.ndarray:
    """Extract the emitted centre of one separated window.

    `start` is the window's start in ORIGINAL (unpadded) samples,
    `total` the song's total samples. The tail is trimmed so output
    never exceeds the song length.
    """
    centre = window_separated[:, EDGE_SAMPLES : EDGE_SAMPLES + HOP_SAMPLES]
    remaining = total - start
    if remaining < centre.shape[1]:
        centre = centre[:, : max(0, remaining)]
    return centre


class WindowStreamer:
    """Turn an incoming PCM stream into complete windows.

    Feed real PCM blocks with feed(); complete windows come out of
    ready() in order. Call flush() at end-of-stream for the tail.
    The windowing matches iter_windows() exactly (same padding rule),
    so offline and streaming results agree.

    Bookkeeping (all positions absolute, original-sample indices):
    - _fed: total real samples ever fed (never decreases).
    - _buf_base: absolute index of _buf[:, 0].
    - _next_start: start of the next window to emit.
    A window starting at s needs padded samples [s, s+WINDOW), i.e.
    real samples [s-EDGE, s-EDGE+WINDOW) (negative indices are the
    leading zero pad). It is complete once _fed covers that range.
    """

    def __init__(self) -> None:
        self._buf = np.zeros((CHANNELS, 0), dtype=np.float32)
        self._buf_base = 0
        self._fed = 0
        self._next_start = 0

    def feed(self, pcm: np.ndarray) -> list:
        if pcm.shape[1] == 0:
            return []
        self._buf = np.concatenate([self._buf, pcm], axis=1)
        self._fed += pcm.shape[1]
        return self._drain(final=False)

    @property
    def fed(self) -> int:
        """Total real samples fed so far (song length at flush)."""
        return self._fed

    def flush(self) -> list:
        return self._drain(final=True)

    def _drain(self, final: bool) -> list:
        out = []
        while True:
            s = self._next_start
            if not final and self._fed < s - EDGE_SAMPLES + WINDOW_SAMPLES:
                break
            if final and s >= self._fed:
                break
            window = np.zeros((CHANNELS, WINDOW_SAMPLES), dtype=np.float32)
            real_lo = s - EDGE_SAMPLES
            real_hi = real_lo + WINDOW_SAMPLES
            src_lo = max(real_lo, self._buf_base)
            src_hi = min(real_hi, self._fed)
            if src_hi > src_lo:
                dst_lo = src_lo - real_lo
                window[:, dst_lo : dst_lo + (src_hi - src_lo)] = self._buf[
                    :, src_lo - self._buf_base : src_hi - self._buf_base
                ]
            out.append((s, window))
            self._next_start += HOP_SAMPLES
            # Drop real samples no future window needs (bounded buf).
            keep_from = self._next_start - EDGE_SAMPLES
            if keep_from > self._buf_base:
                drop = keep_from - self._buf_base
                self._buf = self._buf[:, drop:]
                self._buf_base = keep_from
        return out


def join_centres(centres: list, crossfade_samples: int = 0) -> np.ndarray:
    """Concatenate centres; optional tiny fade kills join clicks.

    Default crossfade is 0 (exact tiling). A small value (e.g. 441
    samples = 10 ms) applies a microfade: the tail of one centre fades
    to zero and the head of the next fades in from zero. Total length
    is preserved (no samples removed); the dip at each join is
    inaudible at 10 ms and removes clicks from model-boundary steps.
    """
    if not centres:
        return np.zeros((CHANNELS, 0), dtype=np.float32)
    if crossfade_samples <= 0:
        return np.concatenate(centres, axis=1)
    out = centres[0].astype(np.float32).copy()
    for nxt in centres[1:]:
        n = min(crossfade_samples, out.shape[1], nxt.shape[1])
        if n > 0:
            fade_out = np.linspace(1.0, 0.0, n, dtype=np.float32)
            fade_in = np.linspace(0.0, 1.0, n, dtype=np.float32)
            out[:, -n:] = out[:, -n:] * fade_out
            head = nxt[:, :n] * fade_in
            out = np.concatenate([out, head, nxt[:, n:]], axis=1)
        else:
            out = np.concatenate([out, nxt], axis=1)
    return out


def _run_ffmpeg(args: list, data: bytes) -> bytes:
    proc = subprocess.run(
        ["ffmpeg", "-v", "error", *args],
        input=data,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=120,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"ffmpeg failed: {proc.stderr.decode()[-500:]}")
    return proc.stdout


def pcm_to_bytes(pcm: np.ndarray) -> bytes:
    """float32 [-1, 1] (channels, samples) -> interleaved s16le bytes."""
    clipped = np.clip(pcm, -1.0, 1.0)
    interleaved = clipped.T.reshape(-1)
    return (interleaved * 32767.0).astype("<i2").tobytes()


def bytes_to_pcm(data: bytes) -> np.ndarray:
    """Interleaved s16le bytes -> float32 (channels, samples)."""
    samples = np.frombuffer(data, dtype="<i2").astype(np.float32) / 32768.0
    if samples.size % CHANNELS:
        samples = samples[: -(samples.size % CHANNELS)]
    return samples.reshape(-1, CHANNELS).T.copy()


def encode_opus(pcm: np.ndarray, bitrate: str = OPUS_BITRATE) -> bytes:
    """Encode float32 PCM to one Opus stream (bytes)."""
    return _run_ffmpeg(
        [
            "-f", "s16le", "-ac", str(CHANNELS), "-ar", str(SAMPLE_RATE),
            "-i", "pipe:0",
            "-c:a", "libopus", "-b:a", bitrate, "-f", "opus", "pipe:1",
        ],
        pcm_to_bytes(pcm),
    )


def decode_opus(data: bytes) -> np.ndarray:
    """Decode Opus bytes back to float32 PCM (channels, samples)."""
    raw = _run_ffmpeg(
        ["-i", "pipe:0", "-f", "s16le", "-ac", str(CHANNELS),
         "-ar", str(SAMPLE_RATE), "pipe:1"],
        data,
    )
    return bytes_to_pcm(raw)


def cache_key(video_id: str, model_name: str) -> str:
    return f"{video_id}:{model_name}"


# --------------------------------------------------------------------
# Vocal profile (auto key change, docs/KEYCHANGE.md)
# --------------------------------------------------------------------
# The separated vocal stem is already in hand, so measuring the song's
# vocal tessitura costs no extra GPU: this is pure numpy on a mono
# downmix. YIN is implemented here rather than pulling librosa, which
# would drag numba+llvmlite into the Modal image for one function.
#
# Everything is reported in CENTS (A1 = 55 Hz = 0) so the phone can
# compare its own measurement directly, and the shift is chosen as
# song_centre - singer_centre.

PROFILE_RATE = 16000
PROFILE_FRAME = 2048
PROFILE_HOP = 320
PROFILE_MIN_HZ = 65.0
PROFILE_MAX_HZ = 1000.0
YIN_THRESHOLD = 0.15
PROFILE_RMS_GATE = 0.0055
# Enough of the song to judge a tessitura. Chunks arrive in 5.25 s
# steps, so this is ~9 windows and lands well before a user has
# finished a first chorus.
PROFILE_MIN_SECONDS = 45.0


def hz_to_cents(hz: float) -> float:
    return 1200.0 * float(np.log2(hz / 55.0))


def _mono(pcm: np.ndarray) -> np.ndarray:
    if pcm.ndim > 1:
        return pcm.mean(axis=0)
    return pcm


def _downsample(x: np.ndarray, sr: int, target: int) -> np.ndarray:
    if sr == target:
        return x
    factor = int(round(sr / target))
    if factor <= 1:
        return x
    n = len(x) // factor * factor
    return x[:n].reshape(-1, factor).mean(axis=1)


def _yin_f0(frame: np.ndarray, sr: int) -> float:
    """YIN f0 for one frame, or 0.0 when unvoiced."""
    min_lag = int(sr / PROFILE_MAX_HZ)
    max_lag = min(int(sr / PROFILE_MIN_HZ), len(frame) // 2)
    if max_lag <= min_lag:
        return 0.0
    w = len(frame) - max_lag
    if w <= 0:
        return 0.0
    lags = np.arange(min_lag, max_lag + 1)
    # difference function, vectorised over lags
    d = np.empty(len(lags), dtype=np.float64)
    for i, lag in enumerate(lags):
        diff = frame[:w] - frame[lag:lag + w]
        d[i] = float(np.dot(diff, diff))
    # cumulative mean normalised difference
    running = np.cumsum(d)
    cmnd = np.ones_like(d)
    idx = np.arange(1, len(d))
    with np.errstate(divide="ignore", invalid="ignore"):
        cmnd[idx] = np.where(running[idx] > 0,
                             d[idx] * lags[idx] / running[idx], 1.0)
    below = np.nonzero(cmnd < YIN_THRESHOLD)[0]
    if len(below):
        i = int(below[0])
        while i + 1 < len(cmnd) and cmnd[i + 1] < cmnd[i]:
            i += 1
    else:
        i = int(np.argmin(cmnd))
        if cmnd[i] > 0.4:
            return 0.0
    refined = float(lags[i])
    if 0 < i < len(cmnd) - 1:
        a, b, c = cmnd[i - 1], cmnd[i], cmnd[i + 1]
        denom = 2.0 * (2.0 * b - a - c)
        if denom != 0:
            refined += (c - a) / denom
    if refined <= 0:
        return 0.0
    hz = sr / refined
    return hz if PROFILE_MIN_HZ <= hz <= PROFILE_MAX_HZ else 0.0


def vocal_profile(pcm: np.ndarray, sr: int = SAMPLE_RATE) -> dict | None:
    """Song vocal tessitura from a vocal stem window set.

    `pcm` is float32 (channels, samples) at `sr`. Returns None when
    there is not enough pitched material to be honest about it.
    """
    x = _downsample(_mono(pcm).astype(np.float64), sr, PROFILE_RATE)
    if len(x) < PROFILE_FRAME:
        return None
    cents = []
    for start in range(0, len(x) - PROFILE_FRAME + 1, PROFILE_HOP):
        frame = x[start:start + PROFILE_FRAME]
        rms = float(np.sqrt(np.mean(frame * frame)))
        if rms < PROFILE_RMS_GATE:
            continue
        hz = _yin_f0(frame, PROFILE_RATE)
        if hz > 0:
            cents.append(hz_to_cents(hz))
    if len(cents) < 40:
        return None
    arr = np.array(cents)
    # drop octave slips against the median, once
    med = float(np.median(arr))
    arr = arr[np.abs(arr - med) <= 1400.0]
    if len(arr) < 40:
        return None
    p05, p25, p75, p95 = (float(np.percentile(arr, q)) for q in (5, 25, 75, 95))
    return {
        "version": 1,
        "center_cents": (p25 + p75) / 2.0,
        "p05_cents": p05,
        "p95_cents": p95,
        "p25_cents": p25,
        "p75_cents": p75,
        "voiced_frames": int(len(arr)),
        "voiced_ratio": round(float(len(cents) / max(1, len(x) // PROFILE_HOP)), 3),
    }
