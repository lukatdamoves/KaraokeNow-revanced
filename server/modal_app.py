"""Modal app: stream separated karaoke audio, chunk by chunk.

Transports:
- WebSocket /ws (primary): client sends {video_id, audio_url} (+
  optional modal_key, see BYOK note below). Server downloads and
  decodes the audio as fast as the network allows, separates it in
  windows, and streams Opus centres back in order. First chunk arrives
  after roughly one window of download+decode+inference, NOT after the
  whole song.
- POST /segment (manually-chosen alternative): client sends one window of audio
  (base64, any ffmpeg-readable container) plus start/total/is_last;
  server returns that window's centre as raw Opus. Used only if
  we decide together that WebSocket disappoints at deploy time.

Chunk protocol (WebSocket, JSON messages):
- server -> client header: {"type": "header", "video_id", "sample_rate",
  "cold_start": bool} — cold_start tells the patch whether to show
  the "warming up" loading state.
- server -> client chunk: {"type": "chunk", "stream": "instrumental",
  "seq", "start", "opus_b64"}.
- server -> client done: {"type": "done", "chunks": n}. "done" is the
  stream terminator; chunks carry no is_last flag (it cannot be known
  until the stream ends, and retro-fixing it would lie to clients
  that already received the chunk).
- server -> client error: {"type": "error", "message": plain words}.

Tunnel mode (server-driven fetch through the phone, same WebSocket):
- client -> server first message: {"video_id", "mode": "tunnel"}.
  The existing server-fetch path (first message carries "audio_url")
  is unchanged; tunnel mode is a separate branch, not a fallback.
- server -> client fetch: {"type": "fetch", "id", "method", "url",
  "headers", "body_b64" (null for GET)}. First the player request,
  then bounded audio Range requests, in order, window by window.
- client -> server fetch_response: {"type": "fetch_response", "id",
  "status", "headers", "body_b64"}.
- client -> server fetch_error: {"type": "fetch_error", "id",
  "error"}. Any fetch failure — wrong id, unexpected message, HTTP
  error, non-206 range response, unparseable player response —
  surfaces the SPECIFIC TunnelFetchError message and STOPS that
  song's path (the phone toasts it verbatim, so the user sees the
  actual reason). Unexpected non-tunnel errors still get the
  generic message. There is no automatic retry into another source
  (standing rule).
- After the player response yields an audio URL, windowing,
  separation, chunk emission, and the replay cache are identical to
  the server-fetch path (one shared emitter below).

BYOK note (spike status): the request may carry "modal_key". The
spike ACCEPTS and IGNORES it — it is never logged, never stored, and
never sent anywhere. The verified auth wiring (user's own deployment
+ endpoint URL vs a key-checked shared endpoint) is a Phase 1 spike
item; the patch's prompt/settings UI follows from it. Do NOT build a
"shared key" path: public use must never bill James's workspace.

Cache (spike): per-container in-memory replay cache, keyed by
video_id, capped at 3 songs. Production uses a Modal Volume + the
phone's own cache; the spike proves the replay path, not the store.

Deployment note: deploy from server/ so sibling modules (pipeline,
fetch, model_demucs) are included with the entrypoint. No token is
needed to write or read this file; deployment and GPU measurement
need the Modal token (option 2: deferred by James's choice).

The FastAPI factory create_app() is Modal-independent and is what the
local protocol tests exercise. The Modal wrapper at the bottom only
exists when `modal` imports successfully.
"""

from __future__ import annotations

import asyncio
import base64
import threading
import time
from pathlib import Path

import anyio
import numpy as np
from fastapi import FastAPI, WebSocket
from fastapi.responses import Response
from pydantic import BaseModel

from fetch import (
    VISIONOS_BOOTSTRAP_UA,
    VISIONOS_CLIENT,
    PLAYER_RANGE_HEADERS,
    TunnelFetchError,
    decode_bytes_to_pcm,
    extract_audio_url,
    iter_pcm_from_tunnel,
    iter_pcm_from_url,
    player_fetch_request,
)
from model_demucs import MODEL_NAME, DemucsSeparator
from model_karaoke import (
    MODEL_NAME as KARAOKE_MODEL_NAME,
    KaraokeLeadSeparator,
)
from pipeline import (
    EDGE_SAMPLES,
    HOP_SAMPLES,
    WINDOW_SAMPLES,
    SAMPLE_RATE,
    PROFILE_MIN_SECONDS,
    WindowStreamer,
    cache_key,
    centre_of,
    encode_opus,
    vocal_profile,
)

# One song at a time per container for the spike: GPU inference is
# serialized so chunk order and timing stay measurable. Modal spins
# extra containers for concurrent users (each bills separately;
# Starter caps at 10 concurrent GPUs per the research notes).
_SEPARATE_LOCK = threading.Lock()

# v55: replay-cache TTL lives at module level (not in the closure)
# so tests can shrink it with monkeypatch. Production value: 10
# minutes of instant replay per song (James's Q32).
REPLAY_CACHE_TTL_SEC = 600


class SeparatorHandle:
    """Lazy loader with cold/warm reporting for the loading UI.

    Wraps Demucs (stage 1) plus, when supplied, the karaoke
    BS-RoFormer lead separator (stage 2). Both must load before
    a session starts: a backing stream produced by anything
    less than the real stage-2 model would be fake BV audio.
    """

    def __init__(self, separator, lead_separator=None) -> None:
        self._separator = separator
        self._lead_separator = lead_separator
        self._loaded = False

    def ensure_loaded(self) -> bool:
        """Load the models if needed. Returns True on cold load."""
        if self._loaded:
            return False
        self._separator.load()
        if self._lead_separator is not None:
            self._lead_separator.load()
        self._loaded = True
        return True

    def separate_window(self, pcm):
        return self._separator.separate_window(pcm)

    def separate_backing(self, vocals):
        """Real backing stem: vocals minus predicted lead."""
        if self._lead_separator is None:
            raise RuntimeError("backing separator not configured")
        return self._lead_separator.separate_backing(vocals)

    @property
    def has_backing(self) -> bool:
        return self._lead_separator is not None

    @property
    def name(self) -> str:
        if self._lead_separator is not None:
            return f"{MODEL_NAME}+{KARAOKE_MODEL_NAME}"
        return MODEL_NAME

    @property
    def loaded(self) -> bool:
        return self._loaded


def _centre(arr, start, total):
    """Emitted centre of one separated window. `total` is None
    for mid-stream windows (full centre); at flush it is the song
    length, trimming the tail."""
    if total is None:
        return arr[:, EDGE_SAMPLES : EDGE_SAMPLES + HOP_SAMPLES]
    return centre_of(arr, start, total)


def _demucs_emit(handle: SeparatorHandle, window, start, total):
    """Stage 1 (worker thread): Demucs + centre + Opus encode.

    Returns (instrumental_opus_or_None, vocals_pcm_or_None).
    None opus = empty tail centre (no seq consumed)."""
    with _SEPARATE_LOCK:
        t0 = time.monotonic()
        separated = handle.separate_window(window)
        t1 = time.monotonic()
    print(
        f"karaoke: window start={start} "
        f"demucs_ms={int((t1 - t0) * 1000)}",
        flush=True,
    )
    instrumental = separated["instrumental"]
    centre = _centre(instrumental, start, total)
    if centre.shape[1] == 0:
        return None, None
    return encode_opus(centre), separated["vocals"]


def _roformer_emit(handle: SeparatorHandle, vocals, start, total):
    """Stage 2 (worker thread): backing stem + centre + Opus.

    NEVER raises: a RoFormer failure returns None (the song
    plays on, instrumental-only) instead of killing the stream
    — instrumental is core, backing is garnish."""
    try:
        with _SEPARATE_LOCK:
            t0 = time.monotonic()
            if hasattr(handle, "separate_backing"):
                backing_pcm = handle.separate_backing(vocals)
            else:
                # Local protocol stubs only: silence, never a
                # fake backing effect. Production has the model.
                backing_pcm = vocals * 0.0
            t1 = time.monotonic()
        print(
            f"karaoke: window start={start} "
            f"roformer_ms={int((t1 - t0) * 1000)}",
            flush=True,
        )
    except Exception as exc:
        print(
            f"karaoke: roformer failed at start={start} "
            f"({type(exc).__name__}: {exc}); backing skipped",
            flush=True,
        )
        return None
    backing_centre = _centre(backing_pcm, start, total)
    if backing_centre.shape[1] == 0:
        return None
    return encode_opus(backing_centre)


# NOTE: SegmentRequest lives at module level on purpose. modal_app.py
# uses `from __future__ import annotations`, so FastAPI resolves endpoint
# annotations from module globals — a class nested inside create_app()
# would not resolve and FastAPI would misread it as a query param (422).
class SegmentRequest(BaseModel):
    video_id: str
    window_b64: str
    start: int
    total: int = 0


def create_app(handle: SeparatorHandle, fetcher):
    """Build the FastAPI app. `fetcher(url)` yields PCM blocks."""
    app = FastAPI()
    # v54: 10-minute TTL per song (instant replay within 10
    # min). Entries are {"chunks", "ts"}; expiry is enforced
    # on read. Cap stays 3 songs.
    replay_cache: dict = {}
    cache_order: list = []

    def cache_get(key: str):
        entry = replay_cache.get(key)
        if entry is None:
            return None
        if time.time() - entry["ts"] > REPLAY_CACHE_TTL_SEC:
            replay_cache.pop(key, None)
            if key in cache_order:
                cache_order.remove(key)
            print(
                f"karaoke: replay cache expired {key}",
                flush=True,
            )
            return None
        return entry["chunks"]

    def cache_store(video_id: str, chunks: list) -> None:
        key = cache_key(video_id, handle.name)
        if key in replay_cache:
            return
        replay_cache[key] = {
            "chunks": chunks,
            "ts": time.time(),
        }
        cache_order.append(key)
        while len(cache_order) > 3:
            replay_cache.pop(cache_order.pop(0), None)

    @app.get("/health")
    async def health():
        return {
            "status": "ok",
            "model": handle.name,
            "model_loaded": handle.loaded,
            "backing_model_loaded": bool(
                getattr(handle, "has_backing", False) and handle.loaded
            ),
        }

    async def _emit_chunks(ws, handle, blocks, send_lock, t_start):
        """Window PCM blocks, separate, stream chunks. Shared by BOTH
        WS paths so chunk bytes, seq, and starts are identical.

        v59: streams emit INDEPENDENTLY — instrumental the moment
        Demucs finishes (~250ms), backing after RoFormer (~2s).
        Time-to-first-audio skips the whole stage-2 cost, and the
        phone (seamless BV) attaches backing whenever it lands.
        Same seq pairing and order as before.

        `blocks` is an async iterator of PCM blocks. `t_start` is
        the accept timestamp, for the first-chunk milestone.
        Returns (chunks, seq). Sends go through send_lock: in
        tunnel mode the ffmpeg writer task also sends (fetch
        requests) concurrently, and two tasks must never
        interleave frames on one socket.
        """
        streamer = WindowStreamer()
        chunks: list = []
        seq = 0
        first_emit_logged = False
        # Auto key change: accumulate the vocal stem and send the
        # song's tessitura once (docs/KEYCHANGE.md). Zero extra GPU:
        # this is numpy on a stem we already have.
        vocal_accum: list = []
        vocal_samples = 0
        profile_sent = False

        async def maybe_emit_profile(vocals):
            nonlocal vocal_samples, profile_sent
            if profile_sent or vocals is None:
                return
            vocal_accum.append(vocals)
            vocal_samples += vocals.shape[-1]
            if vocal_samples < int(PROFILE_MIN_SECONDS * SAMPLE_RATE):
                return
            try:
                pcm = np.concatenate(vocal_accum, axis=-1)
                prof = await anyio.to_thread.run_sync(
                    lambda: vocal_profile(pcm)
                )
            except Exception as exc:  # never break playback for this
                profile_sent = True
                print(f"karaoke: vocal_profile failed: {exc}", flush=True)
                return
            vocal_accum.clear()
            if prof is None:
                return
            profile_sent = True
            async with send_lock:
                await ws.send_json({"type": "vocal_profile", **prof})
            print(
                "karaoke: vocal_profile centre="
                f"{prof['center_cents']:.0f} cents "
                f"frames={prof['voiced_frames']}",
                flush=True,
            )

        async def emit_msg(stream, chunk_start, chunk_seq, opus):
            nonlocal first_emit_logged
            msg = {
                "type": "chunk",
                "stream": stream,
                "seq": chunk_seq,
                "start": chunk_start,
                "opus_b64": base64.b64encode(opus).decode(),
            }
            chunks.append(msg)
            async with send_lock:
                await ws.send_json(msg)
            if not first_emit_logged:
                first_emit_logged = True
                print(
                    "karaoke: first chunk at "
                    f"{int((time.monotonic() - t_start) * 1000)}ms "
                    "after accept",
                    flush=True,
                )

        async def handle_window(window, start, total):
            nonlocal seq
            inst_opus, vocals = await anyio.to_thread.run_sync(
                _demucs_emit, handle, window, start, total
            )
            if inst_opus is None:
                return
            await emit_msg("instrumental", start, seq, inst_opus)
            await maybe_emit_profile(vocals)
            backing_opus = await anyio.to_thread.run_sync(
                _roformer_emit, handle, vocals, start, total
            )
            if backing_opus is not None:
                await emit_msg("backing", start, seq, backing_opus)
            seq += 1

        async for block in blocks:
            for start, window in streamer.feed(block):
                await handle_window(window, start, None)
        for start, window in streamer.flush():
            await handle_window(window, start, streamer.fed)
        return chunks, seq

    async def _async_wrap(sync_gen):
        """Adapt a sync PCM-block generator to an async iterator.

        The existing server-fetch path iterates its fetcher inline;
        wrapping it keeps one emitter for both paths. Blocking reads
        behave exactly as before (the spike already iterated the sync
        fetcher on the handler task).
        """
        for block in sync_gen:
            yield block

    async def _tunnel_flow(ws, video_id, send_lock, t_accept,
                           app_audio_url=None, app_audio_bitrate=0):
        """Server-driven fetch through the phone. Raises on ANY
        failure; the caller surfaces the TunnelFetchError message
        verbatim and stops — no retry, no other source (standing
        rule)."""
        fetch_id = 0

        async def tunnel_fetch(method, url, headers, body):
            """One fetch round-trip: send, await the matching reply.

            Returns (status, headers, body_bytes). Raises
            TunnelFetchError on phone-side failure, id mismatch, or
            any unexpected message.
            """
            nonlocal fetch_id
            fid = fetch_id
            fetch_id += 1
            async with send_lock:
                await ws.send_json(
                    {
                        "type": "fetch",
                        "id": fid,
                        "method": method,
                        "url": url,
                        "headers": headers,
                        "body_b64": (
                            base64.b64encode(body).decode()
                            if body
                            else None
                        ),
                    }
                )
            msg = await ws.receive_json()
            if not isinstance(msg, dict):
                raise TunnelFetchError(f"fetch {fid}: non-JSON response")
            if msg.get("type") == "fetch_response" and msg.get("id") == fid:
                return (
                    msg["status"],
                    msg.get("headers", {}),
                    base64.b64decode(msg["body_b64"]),
                )
            if msg.get("type") == "fetch_error" and msg.get("id") == fid:
                raise TunnelFetchError(
                    f"phone fetch failed: {msg.get('error', '')}"
                )
            raise TunnelFetchError(
                f"fetch {fid}: unexpected message "
                f"(type={msg.get('type')}, id={msg.get('id')})"
            )

        # v40: visionOS route (locally proven end-to-end).
        # yt-dlp's tables: visionos formats carry NO GVS
        # PO-token policy and arrive as DIRECT urls (unlike
        # TVHTML5, which is now SABR-only/ciphered). Bootstrap
        # a visitor session from the main page, ask as
        # VISIONOS, download ITS audio URL via phone ranges.
        tv_status, _th, tv_body = await tunnel_fetch(
            "GET", "https://www.youtube.com/",
            {"User-Agent": VISIONOS_BOOTSTRAP_UA}, None,
        )
        if tv_status != 200:
            raise TunnelFetchError(
                f"session bootstrap returned HTTP {tv_status} — stopping"
            )
        import re as _re
        tv_text = tv_body.decode("utf-8", "ignore")
        m_vis = _re.search(r'"visitorData":"([^"]+)"', tv_text) \
            or _re.search(r'"VISITOR_DATA"\s*:\s*"([^"]+)"', tv_text)
        if not m_vis:
            raise TunnelFetchError(
                "session bootstrap found no visitorData — stopping"
            )
        visitor_data = m_vis.group(1)
        m_key = _re.search(r'"INNERTUBE_API_KEY":"([^"]+)"', tv_text)
        tv_api_key = m_key.group(1) if m_key else \
            "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        print(
            "karaoke: session bootstrapped "
            f"visitor_len={len(visitor_data)} "
            f"at +{int((time.monotonic() - t_accept) * 1000)}ms",
            flush=True,
        )
        preq = player_fetch_request(
            video_id,
            client=VISIONOS_CLIENT,
            api_key=tv_api_key,
            extra_headers={
                "X-Goog-Visitor-Id": visitor_data,
                "X-YouTube-Client-Name": "101",
                "X-YouTube-Client-Version": "1.02",
                "Origin": "https://www.youtube.com",
            },
        )
        status, _headers, player_body = await tunnel_fetch(
            preq["method"], preq["url"], preq["headers"], preq["body"]
        )
        if status != 200:
            raise TunnelFetchError(
                f"player request returned HTTP {status} — stopping"
            )
        audio_url, audio_bitrate = extract_audio_url(player_body)
        print(
            "karaoke: tunnel audio via visionOS client "
            f"at +{int((time.monotonic() - t_accept) * 1000)}ms",
            flush=True,
        )
        # 2. Header (model load reported exactly as the server path).
        cold = handle.ensure_loaded()
        async with send_lock:
            await ws.send_json(
                {
                    "type": "header",
                    "video_id": video_id,
                    "sample_rate": SAMPLE_RATE,
                    "cold_start": cold,
                    "cached": False,
                }
            )
        print(
            "karaoke: header sent "
            f"at +{int((time.monotonic() - t_accept) * 1000)}ms",
            flush=True,
        )
        # 3. Range download through the phone (proven shape:
        # visionOS direct URL, ranges past 2MiB all 206).
        chunks, _seq = await _emit_chunks(
            ws, handle,
            iter_pcm_from_tunnel(
                audio_url, tunnel_fetch, audio_bitrate_bps=audio_bitrate
            ),
            send_lock,
            t_accept,
        )
        return chunks

    @app.websocket("/ws")
    async def ws_endpoint(ws: WebSocket):
        await ws.accept()
        t_accept = time.monotonic()
        try:
            req = await ws.receive_json()
            video_id = req["video_id"]
            # BYOK placeholder: accepted, never logged, never stored,
            # never forwarded anywhere in the spike. See module docs.
            key = cache_key(video_id, handle.name)
            cached_chunks = cache_get(key)
            if cached_chunks is not None:
                # v54 reconnect: resume_from_seq skips replaying
                # chunks the phone already played (faster heal,
                # less data). Absent/0 = full replay.
                resume_from = req.get("resume_from_seq") or 0
                try:
                    resume_from = int(resume_from)
                except (TypeError, ValueError):
                    resume_from = 0
                await ws.send_json(
                    {
                        "type": "header",
                        "video_id": video_id,
                        "sample_rate": SAMPLE_RATE,
                        "cold_start": False,
                        "cached": True,
                    }
                )
                replayed = 0
                for chunk in cached_chunks:
                    if chunk["seq"] >= resume_from:
                        await ws.send_json(chunk)
                        replayed += 1
                print(
                    f"karaoke: replay {video_id} from seq="
                    f"{resume_from} ({replayed} msgs)",
                    flush=True,
                )
                await ws.send_json(
                    {
                        "type": "done",
                        "chunks": len({
                            chunk["seq"] for chunk in cached_chunks
                        }),
                    }
                )
                return
            send_lock = asyncio.Lock()
            if req.get("mode") == "tunnel":
                chunks = await _tunnel_flow(
                    ws, video_id, send_lock, t_accept,
                    app_audio_url=req.get("app_audio_url"),
                    app_audio_bitrate=req.get("app_audio_bitrate") or 0,
                )
            else:
                audio_url = req["audio_url"]
                cold = handle.ensure_loaded()
                await ws.send_json(
                    {
                        "type": "header",
                        "video_id": video_id,
                        "sample_rate": SAMPLE_RATE,
                        "cold_start": cold,
                        "cached": False,
                    }
                )
                chunks, _seq = await _emit_chunks(
                    ws, handle, _async_wrap(fetcher(audio_url)), send_lock,
                    t_accept,
                )
            cache_store(video_id, chunks)
            await ws.send_json({
                "type": "done",
                "chunks": len({chunk["seq"] for chunk in chunks}),
            })
        except Exception as exc:  # noqa: BLE001 - surfaced to client
            # Tunnel failures carry their own specific message (the
            # phone toasts it verbatim); unexpected errors stay
            # generic so internals never leak to the client.
            if isinstance(exc, TunnelFetchError):
                message = str(exc)
            else:
                print(
                    f"karaoke: unexpected error "
                    f"{type(exc).__name__}: {exc}",
                    flush=True,
                )
                message = "Processing failed. Retry."
            try:
                await ws.send_json({"type": "error", "message": message})
            except Exception:
                pass
            raise

    @app.post("/segment")
    async def segment(req: SegmentRequest):
        pcm = decode_bytes_to_pcm(base64.b64decode(req.window_b64))
        handle.ensure_loaded()
        # v59: instrumental-only endpoint skips RoFormer now that
        # the stages are split (was _process_window's pair).
        inst_opus, _vocals = await anyio.to_thread.run_sync(
            _demucs_emit,
            handle,
            pcm,
            req.start,
            req.total if req.total > 0 else None,
        )
        if inst_opus is None:
            return Response(content=b"", media_type="audio/ogg")
        return Response(
            content=inst_opus,
            media_type="audio/ogg",
            headers={"X-Chunk-Start": str(req.start)},
        )

    @app.on_event("startup")
    def _eager_load_model() -> None:
        # Eager load: the model must be warm BEFORE any WS connects.
        # A lazy first-tap load leaves ~10s of WS silence while the
        # model loads, and idle connections get dropped in that
        # window (proven by the phone's socket-closed failures vs
        # the sandbox probe surviving the same flow). Loading here
        # moves that cost to container startup, so the first tap's
        # header arrives immediately. The lazy ensure_loaded() calls
        # stay as the safety net (no-ops once loaded).
        cold = handle.ensure_loaded()
        print(
            f"karaoke: model eager-loaded (cold={cold})",
            flush=True,
        )
        # v59: CUDA/kernel warmup — the first window otherwise pays
        # ~2.6s Demucs cold kernels (Modal-measured) inside the
        # first song's load. A dummy window moves that into boot.
        # Never fatal: models are already loaded at this point.
        try:
            dummy = np.zeros((2, WINDOW_SAMPLES), dtype=np.float32)
            t0 = time.monotonic()
            warmed = handle.separate_window(dummy)
            if hasattr(handle, "separate_backing"):
                try:
                    handle.separate_backing(warmed["vocals"])
                except Exception as w_exc:
                    print(
                        f"karaoke: warmup roformer skipped: {w_exc}",
                        flush=True,
                    )
            print(
                "karaoke: kernels warmed in "
                f"{int((time.monotonic() - t0) * 1000)}ms",
                flush=True,
            )
        except Exception as exc:
            print(
                f"karaoke: warmup failed (non-fatal): {exc}",
                flush=True,
            )

    return app


try:
    import modal

    _image = (
        modal.Image.debian_slim(python_version="3.12")
        .apt_install("ffmpeg")
        .pip_install(
            "fastapi", "httpx", "torch", "torchaudio", "demucs", "numpy",
            "bs-roformer-infer @ https://github.com/openmirlab/bs-roformer-infer/archive/refs/heads/main.zip",
        )
        # Bake BOTH checkpoints into the image (SHA-256 verified)
        # so cold starts never download weights before the first
        # song can load (v59: Demucs ~80MB also baked; RoFormer
        # 204MB was already baked).
        .run_commands(
            "python -c \"from demucs.pretrained import get_model; "
            "get_model('htdemucs')\"",
            "python -c \"from bs_roformer import ensure_model_assets; "
            "ensure_model_assets('roformer-model-bs-roformer-karaoke-by-becruily')\""
        )
        # Sibling modules are NOT auto-mounted: the container only gets
        # modal_app.py itself (proven by the crash-loop:
        # ModuleNotFoundError: No module named 'fetch'). Mount the
        # server/ dir at /root so fetch/pipeline/model_demucs resolve
        # next to it. modal_app.py itself is excluded: Modal already
        # mounts the entrypoint there.
        .add_local_dir(
            Path(__file__).parent, remote_path="/root",
            ignore=["modal_app.py"],
        )
    )
    modal_app = modal.App("revance-karaoke")

    @modal_app.function(
        image=_image, gpu="T4", scaledown_window=1200, timeout=900
    )
    @modal.asgi_app()
    def api():
        handle = SeparatorHandle(
            DemucsSeparator(), KaraokeLeadSeparator()
        )
        return create_app(handle, iter_pcm_from_url)

except ImportError:
    modal = None
