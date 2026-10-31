"""Modal app: stream separated karaoke audio, chunk by chunk.

Transport: WebSocket /ws only. The client sends
{"video_id", "mode": "tunnel"} and the server drives fetches
through the phone (see "Tunnel mode" below). There is no
server-side audio fetch and no /segment endpoint: the old
server-fetch path (first message carrying "audio_url") and the
POST /segment endpoint were removed — a caller-supplied URL let
the container fetch arbitrary hosts (SSRF), and /segment was an
unauthenticated GPU oracle the client never used.

Chunk protocol (WebSocket, JSON messages):
- server -> client header: {"type": "header", "video_id", "sample_rate",
  "cold_start": bool, "cached": bool} — cold_start tells the patch
  whether to show the "warming up" loading state.
- server -> client chunk: {"type": "chunk", "stream": "instrumental",
  "seq", "start", "opus_b64"}.
- server -> client done: {"type": "done", "chunks": n}. "done" is the
  stream terminator; chunks carry no is_last flag (it cannot be known
  until the stream ends, and retro-fixing it would lie to clients
  that already received the chunk).
- server -> client error: {"type": "error", "message": plain words}.

Tunnel mode (server-driven fetch through the phone, same WebSocket):
- client -> server first message: {"video_id", "mode": "tunnel"}
  (plus an optional "key" — the API key, sent only when the
  phone has one saved; keyed servers require it, keyless
  servers ignore the extra field).
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
  separation, chunk emission, and the replay cache are identical
  for every song (one shared emitter below). Videos over 20
  minutes are rejected with a plain error (MAX_VIDEO_SECONDS);
  only one separation runs per container at a time (a second
  caller gets a plain "busy" error instead of queueing behind
  the first).

BYOK note: public use is bring-your-own-Modal-key via the
per-user deploys in setup_app.py — each user deploys this app
into their own Modal account and the patch talks to their own
endpoint. The request may carry "modal_key"; it is ACCEPTED and
IGNORED — never logged, never stored, never sent anywhere.

API-key auth (2026-11-01): the shared endpoint is keyed. When
the KARAOKE_API_KEY environment variable is set (from the
"karaoke-api-key" Modal Secret), the first WS message must
carry a matching "key" field, or the connection is closed
immediately (code 4401) with no GPU work and no fetch. When
the variable is NOT set — the normal BYOK case, where each
user's own deployment has no key configured — the hello is
accepted with or without a key, so keyless BYOK keeps working
unchanged. The configured key and any received key are never
logged (only "key accepted/rejected"). /health stays public
(no key): it reveals no billing surface. Defense in depth:
max_containers=1 (so the per-container separation lock is a
global cap), a shared in-flight lease so a second separation
anywhere is rejected with a plain error, and a per-IP rate
limit on WS handshake attempts.

Cache: per-container in-memory replay cache, keyed by video_id,
capped at 3 songs, 600 s TTL (expired entries are pruned on
store as well as on read).

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
import hmac
import os
import re
import threading
import time
from contextlib import asynccontextmanager
from pathlib import Path

import anyio
import numpy as np
from fastapi import FastAPI, WebSocket

from fetch import (
    VISIONOS_BOOTSTRAP_UA,
    VISIONOS_CLIENT,
    PLAYER_RANGE_HEADERS,
    TunnelFetchError,
    extract_audio_url,
    iter_pcm_from_tunnel,
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
    WindowStreamer,
    cache_key,
    centre_of,
    encode_opus,
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

# H1: songs longer than this are rejected with a plain error (the
# 900 s function timeout would otherwise kill long songs
# mid-stream). 20 minutes covers essentially every karaoke song.
MAX_VIDEO_SECONDS = 1200

# M7: video_id is only ever a cache key and a log label, so it is
# validated loosely — YouTube ids are 11 chars, but the local
# harness and future clients may use other short ids. What matters
# is that it is short, newline-free, and charset-safe (no log
# injection, no megabyte-long cache keys).
_VIDEO_ID_RE = re.compile(r"[A-Za-z0-9_-]{1,64}")


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

    @property
    def gpu_available(self) -> bool:
        """True when the stage-1 separator runs on CUDA.

        Before load this probes torch directly; after load it
        reports the device the model actually landed on. CPU-only
        runs report False (and load() now refuses them outright).
        """
        device = getattr(self._separator, "device", None)
        if device is not None:
            return device == "cuda"
        try:
            import torch

            return bool(torch.cuda.is_available())
        except Exception:
            return False


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


# API-key auth (2026-11-01): read once at startup from the
# environment (the "karaoke-api-key" Modal Secret provides it).
# Set + non-empty = the server is keyed; unset/empty = keyless
# (the BYOK default). Never log the value — only accept/reject.
def _configured_api_key() -> str | None:
    key = os.environ.get("KARAOKE_API_KEY") or None
    return key


# Handshake rate limit: 10 attempts per minute per IP. In-memory
# per container is fine here — max_containers=1 makes it global,
# and it only gates the handshake (no GPU work is behind it).
HANDSHAKE_RATE_LIMIT = 10
HANDSHAKE_RATE_WINDOW_SEC = 60

# Global in-flight lease: one separation at a time anywhere.
# The lease is a timestamped claim in a shared Dict; it self-heals
# (a claim older than the function timeout is stale, e.g. a
# container that died mid-song). The per-container separation
# lock is the primary enforcement (max_containers=1 makes it
# global); the lease is defense in depth for rollout overlap,
# when an old and a new container briefly coexist.
INFLIGHT_LEASE_TTL_SEC = 900


def create_app(handle: SeparatorHandle, api_key: str | None = None,
               inflight=None):
    """Build the FastAPI app (tunnel mode only; no server fetch).

    api_key: the API key this server requires (None = read
    KARAOKE_API_KEY from the environment; an explicit value
    overrides it, which is how tests inject a fake key).
    inflight: an optional shared dict-like (Modal Dict) holding
    the global in-flight lease; None = local lock only.
    """
    if api_key is None:
        api_key = _configured_api_key()
    # Per-app handshake-attempt counters: {ip: (count, window_start)}.
    handshake_attempts: dict = {}

    def _handshake_allow(ip: str) -> bool:
        """Fixed-window handshake gate. True = allowed (counted)."""
        now = time.time()
        for old_ip in [
            k for k, (_, start) in handshake_attempts.items()
            if now - start >= HANDSHAKE_RATE_WINDOW_SEC
        ]:
            handshake_attempts.pop(old_ip, None)
        count, start = handshake_attempts.get(ip, (0, now))
        if now - start >= HANDSHAKE_RATE_WINDOW_SEC:
            count, start = 0, now
        if count >= HANDSHAKE_RATE_LIMIT:
            return False
        handshake_attempts[ip] = (count + 1, start)
        return True

    def _global_claim() -> bool:
        """Claim the global in-flight slot. True = claimed."""
        if inflight is None:
            return True  # no shared state: local lock is the cap
        try:
            claim = inflight.get("claim")
            now = time.time()
            if isinstance(claim, dict) \
                    and now - claim.get("ts", 0) < INFLIGHT_LEASE_TTL_SEC:
                return False
            inflight["claim"] = {"ts": now}
            return True
        except Exception:
            # A Dict outage must not break the service: fail open
            # to the per-container lock (max_containers=1 still
            # caps globally). The lease is defense in depth, and
            # the read-modify-write is not atomic — a simultaneous
            # claim from two containers can both succeed, which
            # the lock + max_containers backstop.
            return True

    def _global_release() -> None:
        if inflight is None:
            return
        try:
            inflight.pop("claim", None)
        except Exception:
            pass
    # v54: 10-minute TTL per song (instant replay within 10
    # min). Entries are {"chunks", "ts"}; expiry is enforced
    # on read and pruned on store. Cap stays 3 songs.
    replay_cache: dict = {}
    cache_order: list = []
    # H1: one separation at a time per container. A second caller
    # gets a plain "busy" error instead of queueing silently
    # behind the first (the old queueing could hit the 900 s
    # function timeout mid-song).
    separation_lock = asyncio.Lock()

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

    @asynccontextmanager
    async def lifespan(app):
        # M8: lifespan replaces the deprecated @app.on_event("startup").
        # The load runs in a worker thread: it blocks for seconds and
        # must not stall the event loop.
        await anyio.to_thread.run_sync(_eager_load_model)
        yield

    app = FastAPI(lifespan=lifespan)

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
        # M4: prune expired keys first — cache_order can hold
        # expired entries, which would otherwise eat capacity.
        now = time.time()
        for old_key in list(cache_order):
            entry = replay_cache.get(old_key)
            if entry is None or now - entry["ts"] > REPLAY_CACHE_TTL_SEC:
                replay_cache.pop(old_key, None)
                cache_order.remove(old_key)
        replay_cache[key] = {
            "chunks": chunks,
            "ts": now,
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
            "gpu_available": getattr(handle, "gpu_available", False),
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

    async def _tunnel_flow(ws, video_id, send_lock, t_accept):
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
            # M6: a silent phone must not hang the handler until
            # the 900s function timeout.
            try:
                msg = await asyncio.wait_for(ws.receive_json(), timeout=60)
            except asyncio.TimeoutError:
                raise TunnelFetchError(
                    f"fetch {fid}: phone did not answer within 60s "
                    "— stopping"
                ) from None
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
        audio_url, audio_bitrate, length_seconds = extract_audio_url(
            player_body
        )
        # H1: reject over-long videos with a plain error BEFORE
        # the model loads — a 2-hour video would otherwise die
        # mid-stream at the 900s function timeout.
        if length_seconds is not None and length_seconds > MAX_VIDEO_SECONDS:
            raise TunnelFetchError(
                "Video is over 20 minutes long — karaoke supports "
                "videos up to 20 minutes"
            )
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
            iter_pcm_from_tunnel(audio_url, tunnel_fetch),
            send_lock,
            t_accept,
        )
        return chunks

    @app.websocket("/ws")
    async def ws_endpoint(ws: WebSocket):
        # Handshake gate (before accept): per-IP rate limit on
        # connection attempts. Rejected handshakes close with
        # 1008 (policy violation) and never reach the app.
        _client = getattr(ws, "client", None)
        _ip = _client.host if _client is not None \
            and hasattr(_client, "host") else "unknown"
        if not _handshake_allow(_ip):
            print(
                f"karaoke: ws handshake rate-limited ip={_ip}",
                flush=True,
            )
            await ws.close(code=1008)
            return
        await ws.accept()
        t_accept = time.monotonic()
        try:
            req = await ws.receive_json()
            # M7: video_id is a cache key and a log label — reject
            # anything that is not a short, charset-safe id before
            # it can reach a log line or the cache.
            if not isinstance(req, dict):
                await ws.send_json({
                    "type": "error",
                    "message": "invalid video id",
                })
                return
            # API-key auth (keyed servers only): the hello must
            # carry a matching "key". Checked BEFORE any GPU work,
            # any fetch, and the cache — a cached replay is still
            # gated. Wrong/missing key: plain error, then close
            # 4401 (unauthorized). The key value is never logged
            # (only accept/reject); comparison is constant-time.
            if api_key is not None:
                provided = req.get("key")
                ok = isinstance(provided, str) and bool(provided) \
                    and hmac.compare_digest(
                        provided.encode("utf-8", "ignore"),
                        api_key.encode("utf-8", "ignore"),
                    )
                if not ok:
                    print(
                        "karaoke: ws auth rejected (key missing "
                        "or wrong)",
                        flush=True,
                    )
                    try:
                        await ws.send_json({
                            "type": "error",
                            "message": "This server requires a "
                            "valid API key.",
                        })
                    except Exception:
                        pass
                    try:
                        await ws.close(code=4401)
                    except Exception:
                        pass
                    return
                print("karaoke: ws auth accepted", flush=True)
            video_id = req.get("video_id")
            if not isinstance(video_id, str) or not _VIDEO_ID_RE.fullmatch(
                video_id
            ):
                await ws.send_json({
                    "type": "error",
                    "message": "invalid video id",
                })
                return
            # BYOK placeholder: accepted, never logged, never stored,
            # never forwarded anywhere. See module docs.
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
            # S5: tunnel mode is the ONLY path now. A request
            # without mode=tunnel is still served by the tunnel
            # flow (the phone fetches); there is no server fetch.
            # H1: one separation per container — a second caller
            # gets a plain error instead of queueing silently.
            if separation_lock.locked():
                await ws.send_json({
                    "type": "error",
                    "message": "Another song is already being prepared "
                    "on this server — try again in a moment.",
                })
                return
            # Global in-flight lease (defense in depth): with
            # max_containers=1 the lock above is already global;
            # the lease also covers rollout overlap, when an old
            # and a new container briefly coexist.
            if not _global_claim():
                await ws.send_json({
                    "type": "error",
                    "message": "Another song is already being prepared "
                    "on this server — try again in a moment.",
                })
                return
            try:
                async with separation_lock:
                    chunks = await _tunnel_flow(
                        ws, video_id, send_lock, t_accept
                    )
                    cache_store(video_id, chunks)
                    await ws.send_json({
                        "type": "done",
                        "chunks": len({chunk["seq"] for chunk in chunks}),
                    })
            finally:
                _global_release()
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

    # Shared in-flight lease (see _global_claim): a Dict shared
    # across the app's containers. Only exists in the Modal
    # wrapper — create_app() runs without it in tests.
    _inflight = modal.Dict.from_name(
        "karaoke-inflight", create_if_missing=True
    )

    @modal_app.function(
        image=_image, gpu="T4", scaledown_window=1200, timeout=900,
        # Defense in depth: one container max, so the
        # per-container separation lock is a global cap.
        max_containers=1,
        # API-key auth: the "karaoke-api-key" Modal Secret
        # provides KARAOKE_API_KEY. from_name auto-creates an
        # EMPTY secret when it does not exist, so a missing
        # secret never breaks the deploy — the env var is
        # simply absent and the server runs keyless (the BYOK
        # default). deploy-modal.yml populates the secret from
        # the repo secret KARAOKE_API_KEY when it is set.
        secrets=[modal.Secret.from_name("karaoke-api-key")],
    )
    @modal.asgi_app()
    def api():
        handle = SeparatorHandle(
            DemucsSeparator(), KaraokeLeadSeparator()
        )
        return create_app(handle, inflight=_inflight)

except ImportError:
    modal = None
