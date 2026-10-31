# Server — Modal streaming app

FastAPI + WebSocket server on Modal that separates vocals from a
YouTube song and streams the instrumental (plus optional backing
vocals) back to the phone as Opus chunks, 7 seconds at a time.

## Files

- `modal_app.py` — Modal app + FastAPI factory. WebSocket `/ws`
  (only endpoint; the old POST `/segment` was deleted — it was an
  unauthenticated GPU oracle). Chunk protocol,
  in-memory replay cache (max 3 songs, TTL 600 s), cold/warm
  header, `resume_from_seq`. GPU **T4**, `scaledown_window=1200`,
  `timeout=900`.
- `pipeline.py` — transport-independent core: windowing (7 s
  windows, 25% overlap, centre-only, HOP 5.25 s), microfade joins,
  Opus via ffmpeg, cache-key helper.
- `fetch.py` — tunnel fetch: visionOS player request, progressive
  512 KiB Range fetches, ffmpeg decode to PCM. A non-206 range
  response raises a specific error and STOPS the song — no retry
  into another source.
- `model_demucs.py` — stage 1: Demucs `htdemucs`, removes all
  vocals (instrumental stem).
- `model_karaoke.py` — stage 2: karaoke BS-RoFormer
  (`roformer-model-bs-roformer-karaoke-by-becruily`, 204 MB,
  SHA-256-verified checkpoint registry). Runs on Demucs' vocal
  stem, predicts the lead vocal; `vocals - lead` is the backing
  stem (harmonies, doubles, reverb tails). Stage-2 failure
  degrades to instrumental-only without killing the stream.
- `setup_app.py` — BYOK setup helper (separate Modal app
  `karaoke-setup`). POST `/setup/start {token_id, token_secret}`
  -> `{job_id}`; GET `/setup/status/{job_id}`. Runs `modal deploy`
  in a subprocess with the token only in the subprocess env —
  never logged, never stored, never written to disk. The deployed
  URL is returned to the phone, which saves it on-device.

## Models (both live — the old "RoFormer on L4" notes are stale)

- Stage 1: Demucs htdemucs (full mix -> instrumental + vocals).
- Stage 2: BS-RoFormer karaoke model (vocals -> lead; backing =
  vocals - lead). Baked into the Modal image (204 MB) with a
  SHA-256-verified download; Demucs (~80 MB) likewise.
- GPU is **T4**, not L4. `/health` reports `model_loaded` and
  `backing_model_loaded`.

## Run the local tests (no torch, no network, no Modal)

    .venv/bin/python -m pytest tests/ -q

Local tests cover: windowing math (offline == streaming), Opus
roundtrip, the WebSocket chunk protocol (order, seq, start, cache
replay, cold/warm header), the tunnel
fetch path (in-memory ranges, failure cases, non-206 stops,
literal 403), the busy guard, malformed first messages, the
20-minute duration cap, and the setup helper (bad-token rejection,
token pre-validation, rate limits, concurrency cap, unknown/expired
jobs, token scrubbing).

Known test gaps (do not claim coverage that is not there): no
concurrent same-video test and no cache-eviction-at-capacity test.

## Tunnel mode (server-driven fetch through the phone, same WebSocket)

- Client's first message: `{"video_id", "mode": "tunnel"}`.
  The server sends `{"type": "fetch", "id", "method", "url",
  "headers", "body_b64"}` — first the player request, then bounded
  audio Range requests in order. The phone executes each from its
  own connection and replies `fetch_response` (status, headers,
  body_b64) or `fetch_error`. Windowing, separation, chunk
  emission, and the replay cache are identical to the server-fetch
  path (one shared emitter in `modal_app.py`).
- The phone NEVER downloads the full audio file: every audio fetch
  is a bounded Range (512 KiB), window by window. A non-206 range
  response, a wrong response id, or any fetch failure surfaces a
  SPECIFIC error and STOPS — no retry into another source.
- Player parsing is real youtubei parsing: the server bootstraps
  a visionOS visitor session from youtube.com HTML (visitorData +
  INNERTUBE_API_KEY), calls the player API as VISIONOS
  (`X-YouTube-Client-Name: 101`), and `extract_audio_url()` reads
  streamingData -> adaptiveFormats, picking the highest-bitrate
  audio-only format with a direct `url` (Opus wins ties). It fails
  specifically on playability errors or signatureCipher-only
  formats (never deciphered, never guessed). Parse diagnostics
  (itags/mimeTypes/hosts only, never full URLs) go to the server
  log. The public YouTube web-client InnerTube key is used only
  as a fallback if the page parse misses — it is a public key
  shipped in YouTube's own pages, not a secret.
- Local proof (stub separator = identity, NOT separation quality):
  unit tests in `tests/test_tunnel.py` (in-memory ranges, failure
  cases) plus the real-network harness below. Real model testing
  happens on Modal after a deploy the maintainer approves.

    .venv/bin/python spikes/phone_harness.py

  The harness simulates the phone (player leg stubbed with a canned
  response pointing at SoundHelix by default; audio leg executed for
  real via httpx) and prints a JSON report with fetch/chunk counts,
  timings, and assertions. Exit code 0 only if all pass. Last full
  local run (2026-10-26, SoundHelix-Song-1, stub separator):
  19 fetches (1 player + 18 audio ranges), 71 chunks, first chunk
  ~2.0 s, full song in ~20-28 s, all 11 assertions pass. These are
  LOCAL stub numbers, NOT Modal performance.

- Phase 1 server proof (Modal, 2026-10-26, tunnel mode): 71/71
  chunks of a ~6.2-min song in 36.4 s, first audio ~12 s cold,
  ~15x real-time. Phone logs 2026-10-31 (v79, v82) show the same
  path live with no 403 on audio ranges since the visionOS route
  landed.

## Deploy

    cd server && modal deploy modal_app.py::modal_app

Deploy from server/ so sibling modules are included. Deployment
needs a Modal token (via the CLI-config path). GitHub Actions
(`.github/workflows/deploy-modal.yml`, manual trigger only) does
the same deploy and then smoke-checks `/health` 40 times — a
deploy can succeed while containers crash-loop, so a broken
deploy must fail the run loudly here. Deploys are manual only:
nothing deploys on push.

**Redeploy-helper rule (do not skip):** the setup helper
(`setup_app.py`) bakes `server/` into its image at the time
`deploy-setup.yml` runs. Any `modal_app.py` / `fetch.py` /
`pipeline.py` / `model_*.py` change after that is silently
ignored by the helper until `deploy-setup.yml` is re-run — users
would get an old server with old bugs. Re-run
`deploy-setup.yml` after EVERY server change.

## Security — read this before going public

- **The shared endpoint has NO auth, NO rate limit, and NO per-user
  quota.** Anyone with the URL gets unlimited songs separated on
  the maintainer's T4 GPU (videos over 20 minutes are rejected, but there is
  no per-user cap). The URL already leaked into old bundles and docs,
  so scrubbing the repo alone does NOT close the hole: the shared
  app must be gated or stopped (`modal app stop revance-karaoke`)
  before the repo goes public.
- **The setup helper is likewise unauthenticated and billable:**
  POST `/setup/start` runs `modal deploy` (up to ~30 min of
  container runtime) for any token-shaped input. It now validates
  the token with `modal token info` before spawning, rate-limits
  starts (3/hour/IP, 50/day global, max 2 concurrent deploys),
  and returns 429 when a limit is hit — but the endpoint itself
  still has no key check, so keep it behind the BYOK flow only.
- **BYOK is the public model:** each user deploys the server into
  their own Modal account with their own API key via the setup
  helper (or manually: `modal deploy modal_app.py::modal_app`).
  The maintainer's credits are for the maintainer's own use and
  testing only. No shared key is ever baked into the patch, and
  no user's key is ever sent to the maintainer — the helper passes
  the token only via the deploy subprocess environment.
- **Do not revive the non-tunnel `audio_url` branch** for public
  use: it lets any WS caller make the container fetch an
  arbitrary URL (SSRF). Tunnel mode is the shipped path.
- Dependencies in the Modal image are unpinned (`modal_app.py`
  pip_install list, plus `bs-roformer-infer` from a GitHub
  `main`-branch zip). Pin every package to the versions currently
  deployed, and pin the RoFormer helper zip to a commit SHA,
  before the public release — an upstream release can otherwise
  break deploys with no code change here.
