"""Audio fetching for tunnel mode (server-driven fetch through the
phone): the server never touches YouTube directly. It sends fetch
requests over the karaoke WebSocket (see modal_app.py for the
message types); the phone executes each request from its own
connection and returns the bytes. iter_pcm_from_tunnel() turns
those phone-side range fetches into the PCM block stream the
pipeline consumes. The phone NEVER downloads the full audio file:
every audio fetch is a bounded Range request, window by window.
On ANY fetch failure this raises and the caller stops that path —
there is no automatic retry into another source (standing rule).

There is deliberately NO server-side URL fetcher in this module:
the old server-fetch path (iter_pcm_from_url / range_fetch) was
removed — a caller-supplied URL would have let the container
fetch arbitrary hosts (SSRF). The server only ever asks the phone
to fetch the player + audio URLs it derived itself.

No Modal dependency; testable locally against any HTTP source.
"""

from __future__ import annotations

import asyncio
import json

from pipeline import CHANNELS, SAMPLE_RATE, bytes_to_pcm


class TunnelFetchError(RuntimeError):
    """A tunnel fetch failed. The caller surfaces this and stops —
    it must NOT silently try another source."""


# Player-like headers for audio range fetches: the official YouTube
# Android player fetches the same googlevideo URLs without 403s, while
# plain fetches get flagged after the first range. Match the pinned app
# (20.40.45) so the phone's requests look like the player's.
# Connection: close forces a fresh connection per range fetch, which
# defeats any per-connection byte allowance on the video server.
PLAYER_RANGE_HEADERS = {
    "User-Agent": "com.google.android.youtube/20.40.45 (Linux; U; Android 16; en_US)",
    "Accept": "*/*",
    "Connection": "close",
}
# YouTube player request (ANDROID client). The transport (fetch via
# the phone) is what tunnel mode proves; the response parsing below
# is real youtubei parsing: streamingData -> adaptiveFormats.
PLAYER_URL = "https://www.youtube.com/youtubei/v1/player"

# One audio range fetch per round-trip. 512 KiB keeps each phone-side
# request small and proves progressive windowing (a ~9 MB song takes
# ~18 fetches); the server still windows PCM exactly as before.
TUNNEL_RANGE_SIZE = 512 * 1024


# v40: visionOS client — the one yt-dlp client whose formats
# are BOTH direct-URL (no signatureCipher) AND free of any
# GVS PO-token policy. Verified locally end-to-end: player OK,
# 5 direct audio formats, ranges past 2MiB all 206 (the
# ANDROID/app formats die at exactly 1MiB without a pot).
VISIONOS_CLIENT = {
    "clientName": "VISIONOS",
    "clientVersion": "1.02",
    "deviceMake": "Apple",
    "deviceModel": "RealityDevice17,1",
    "userAgent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) "
                 "AppleWebKit/605.1.15 (KHTML, like Gecko) "
                 "Version/26.0 Safari/605.1.15",
    "osName": "visionOS",
    "osVersion": "26.5.23O471",
    "hl": "en",
    "gl": "US",
    "timeZone": "UTC",
    "utcOffsetMinutes": 0,
}
VISIONOS_BOOTSTRAP_UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/605.1.15 (KHTML, like Gecko) "
    "Version/17.4 Safari/605.1.15"
)
def player_fetch_request(video_id: str, client=None,
                         api_key=None, extra_headers=None,
                         raw_context=None) -> dict:
    """Build the player request the server sends through the tunnel.

    Returns {method, url, headers, body} with body as raw bytes.
    """
    client_ctx = client or {
        "clientName": "ANDROID", "clientVersion": "20.10.0"
    }
    body_obj = {
        "videoId": video_id,
        "context": raw_context if raw_context else {"client": client_ctx},
        "contentCheckOk": True,
        "racyCheckOk": True,
        "playbackContext": {
            "contentPlaybackContext": {
                "html5Preference": "HTML5_PREF_WANTS"
            }
        },
    }
    body = json.dumps(body_obj).encode()
    headers = {"Content-Type": "application/json"}
    if client and client.get("userAgent"):
        headers["User-Agent"] = client["userAgent"]
    if extra_headers:
        headers.update(extra_headers)
    url = PLAYER_URL
    if api_key:
        url = url + "?key=" + api_key + "&prettyPrint=false"
    return {
        "method": "POST",
        "url": url,
        "headers": headers,
        "body": body,
    }


def _log_parse(detail: str) -> None:
    """Concise server-side parse diagnostic (Modal log retrieval).

    Logs itags/mimeTypes/hosts ONLY — never full URLs or query
    strings (googlevideo URLs can carry tokens).
    """
    print(f"karaoke: player parse: {detail}", flush=True)


def _format_summary(fmt: dict) -> str:
    """Short "itag mime-base" summary for one format, for logs."""
    try:
        itag = int(fmt.get("itag"))
    except (TypeError, ValueError):
        itag = fmt.get("itag")
    mime = str(fmt.get("mimeType") or "").split(";")[0].strip()
    return f"{itag} {mime}".strip()


def _playability_reason(ps: dict) -> str | None:
    """Best-effort reason string from a playabilityStatus object."""
    reason = ps.get("reason")
    if isinstance(reason, str) and reason:
        return reason
    try:
        es = ps.get("errorScreen") or {}
        renderer = es.get("playerErrorMessageRenderer") or {}
        text = (renderer.get("reason") or {}).get("simpleText")
        if isinstance(text, str) and text:
            return text
    except Exception:
        pass
    return None


def extract_audio_url(player_body: bytes) -> tuple:
    """Extract the audio URL from a real youtubei player response.

    Returns (url, bitrate_bps, length_seconds); bitrate is 0 when
    the response omits it; length_seconds is None when videoDetails
    omits it (unknown length is allowed — the duration cap only
    fires on a known over-long video).

    Parses streamingData -> adaptiveFormats and picks an audio-only
    format with a DIRECT `url`. Selection: highest bitrate first;
    ties break toward Opus, then lower itag (deterministic). A
    format carrying only `signatureCipher` is signature-protected
    and is never used — deciphering is out of scope, so if NO
    direct-URL audio format exists the error says so plainly.

    Any failure raises TunnelFetchError with a SPECIFIC message
    (the phone toasts it verbatim). Nothing here guesses a URL or
    falls back to another source.
    """
    try:
        data = json.loads(player_body.decode())
    except Exception as exc:
        raise TunnelFetchError(
            f"player response is not JSON: {exc}"
        ) from exc
    if not isinstance(data, dict):
        raise TunnelFetchError("player response is not a JSON object")

    # videoDetails.lengthSeconds (string) bounds the song for the
    # duration cap in modal_app.py; absent/unparseable = unknown.
    length_seconds = None
    vd = data.get("videoDetails")
    if isinstance(vd, dict):
        try:
            length_seconds = int(vd.get("lengthSeconds"))
        except (TypeError, ValueError):
            length_seconds = None

    # playabilityStatus gates everything: an ERROR/LOGIN_REQUIRED/
    # age-gate response carries no usable streams, and its reason is
    # the most useful thing the phone can show.
    ps = data.get("playabilityStatus")
    status = ps.get("status") if isinstance(ps, dict) else None
    if status is not None and status != "OK":
        reason = _playability_reason(ps) if isinstance(ps, dict) else None
        _log_parse(
            f"playability status={status} reason={reason!r} "
            f"top-level keys={sorted(data.keys())}"
        )
        msg = f"Playability: {status}"
        if reason:
            msg += f" {reason}"
        raise TunnelFetchError(msg)

    sd = data.get("streamingData")
    if not isinstance(sd, dict):
        _log_parse(
            f"no streamingData; playability={status}; "
            f"top-level keys={sorted(data.keys())}"
        )
        raise TunnelFetchError(
            "Player response has no streamingData (no audio streams listed)"
        )
    formats = sd.get("adaptiveFormats")
    if not isinstance(formats, list):
        _log_parse(
            f"adaptiveFormats missing/not a list; "
            f"streamingData keys={sorted(sd.keys())}"
        )
        raise TunnelFetchError(
            "Player response has no adaptiveFormats (no audio streams listed)"
        )

    audio = [
        f for f in formats
        if isinstance(f, dict)
        and str(f.get("mimeType") or "").startswith("audio/")
    ]
    direct = [
        f for f in audio
        if isinstance(f.get("url"), str) and f["url"].startswith("http")
    ]
    ciphered = [
        f for f in audio
        if f not in direct and isinstance(f.get("signatureCipher"), str)
    ]
    _log_parse(
        f"formats={len(formats)} audio={len(audio)} "
        f"direct_url={len(direct)} signatureCipher={len(ciphered)} "
        f"seen=[{', '.join(_format_summary(f) for f in audio)}]"
    )
    if direct:
        def rank(fmt: dict):
            try:
                bitrate = int(fmt.get("bitrate") or 0)
            except (TypeError, ValueError):
                bitrate = 0
            mime = str(fmt.get("mimeType") or "")
            opus = 0 if "opus" in mime else 1
            try:
                itag = int(fmt.get("itag") or 0)
            except (TypeError, ValueError):
                itag = 0
            return (-bitrate, opus, itag)

        best = sorted(direct, key=rank)[0]
        _log_parse(
            f"selected itag={best.get('itag')} "
            f"mime={str(best.get('mimeType') or '').split(';')[0]} "
            f"bitrate={best.get('bitrate')}"
        )
        try:
            bitrate_bps = int(best.get("bitrate") or 0)
        except (TypeError, ValueError):
            bitrate_bps = 0
        return best["url"], bitrate_bps, length_seconds
    if ciphered:
        raise TunnelFetchError(
            "Audio formats are signature-protected, cannot fetch "
            "(no direct URL)"
        )
    if audio:
        raise TunnelFetchError(
            "Player response has no audio stream URL "
            "(audio formats present but no usable URL)"
        )
    raise TunnelFetchError(
        "Player response has no audio stream URL (no audio formats listed)"
    )


async def iter_pcm_from_tunnel(
    url: str,
    fetch,
    range_size: int = TUNNEL_RANGE_SIZE,
    block_samples: int = SAMPLE_RATE,
):
    """Yield float32 PCM blocks from phone-side range fetches.

    `fetch(method, url, headers, body)` is an async callable supplied
    by the WebSocket handler: it sends one fetch request to the phone
    and returns (status, headers, body_bytes). This function requests
    bounded ranges in order ([0, range_size), then the next, ...) and
    feeds the bytes into one ffmpeg decode process, so windows come
    out progressively — the caller never waits for the whole file.

    No pacing: ranges are fetched full speed (v45 removed the
    burst-then-real-time pacing — it was anti-403 for pot-gated
    tickets; visionOS direct URLs need none). Fetching ahead keeps
    chunks minutes ahead so the player never starves.

    Range discipline (the whole point of tunnel mode):
    - Every audio request carries a bounded Range header.
    - A non-206 response (host ignores Range, HTTP error) raises
      TunnelFetchError and the caller stops. No silent acceptance of
      a full-body 200, no retry into another source.
    - End-of-stream is a short read (fewer bytes than requested) or
      an empty body. Content-Range totals are advisory only.
    """
    proc = await asyncio.create_subprocess_exec(
        "ffmpeg", "-v", "error",
        "-i", "pipe:0",
        "-f", "s16le", "-ac", str(CHANNELS), "-ar", str(SAMPLE_RATE),
        "pipe:1",
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    err_box: dict = {}

    async def writer():
        try:
            start = 0
            while True:
                # v45: NO pacing. Fetch full speed so chunks land
                # minutes ahead and the player never starves
                # (starvation showed as constant ~1.5s drift +
                # seek-chatter in the v44 phone log).
                end = start + range_size
                status, _headers, body = await fetch(
                    "GET", url,
                    {"Range": f"bytes={start}-{end - 1}",
                     **PLAYER_RANGE_HEADERS},
                    None,
                )
                if status != 206:
                    raise TunnelFetchError(
                        f"audio range fetch returned HTTP {status} "
                        f"at range start={start} (expected 206 Partial "
                        f"Content) — stopping, no other source tried"
                    )
                if len(body) > range_size:
                    raise TunnelFetchError(
                        f"audio range fetch returned {len(body)} bytes "
                        f"for a {range_size}-byte range — stopping"
                    )
                if body:
                    proc.stdin.write(body)
                    await proc.stdin.drain()
                if len(body) < range_size:
                    break  # short read: end of stream
                start += len(body)
        except Exception as exc:  # noqa: BLE001 - surfaced via err_box
            err_box["error"] = exc
        finally:
            try:
                proc.stdin.close()
            except Exception:
                pass

    write_task = asyncio.create_task(writer())
    reader_error = None
    eof = False
    try:
        block_bytes = block_samples * CHANNELS * 2
        while True:
            buf = b""
            while len(buf) < block_bytes:
                chunk = await proc.stdout.read(block_bytes - len(buf))
                if not chunk:
                    break
                buf += chunk
            if not buf:
                eof = True
                break
            yield bytes_to_pcm(buf)
    except BaseException as exc:  # noqa: BLE001 - re-raised after cleanup
        reader_error = exc
    finally:
        # Stop the writer (it may be awaiting a phone response) and
        # reap ffmpeg. A healthy ffmpeg that reached EOF is NOT
        # killed — killing it would fake a decode failure.
        if not write_task.done():
            write_task.cancel()
            try:
                await write_task
            except asyncio.CancelledError:
                pass
        else:
            await write_task
        if not eof and proc.returncode is None:
            try:
                proc.kill()
            except ProcessLookupError:
                pass
        # M6: reap with a deadline so a hung ffmpeg cannot stall
        # the handler forever; the writer/download error (err_box)
        # is the root cause and is raised below in preference to
        # ffmpeg's complaints about the truncated stream.
        try:
            await asyncio.wait_for(proc.wait(), timeout=10)
        except asyncio.TimeoutError:
            try:
                proc.kill()
            except ProcessLookupError:
                pass
            await proc.wait()
    if reader_error is not None:
        raise reader_error
    # Writer errors are the root cause; ffmpeg complaints about the
    # resulting truncated stream are downstream noise.
    if "error" in err_box:
        raise err_box["error"]
    if proc.returncode != 0:
        err = b""
        try:
            err = await proc.stderr.read()
        except Exception:
            pass
        raise RuntimeError(f"ffmpeg decode failed: {err.decode()[-500:]}")
