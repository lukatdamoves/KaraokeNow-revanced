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
