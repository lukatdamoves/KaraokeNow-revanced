# Phase 1 — status (2026-10-26)

> This file is the chronological working log of the build,
> 2026-10-26 to 2026-10-31, published verbatim. Entries are
> dated; later entries supersede earlier ones, and some claims
> below are stale by design (the "Mel-Band RoFormer on L4"
> plan, the v11 freeze, the old ANDROID player client). The
> curated current state is in the root `README.md`,
> `patch/README.md`, and `server/README.md`; the v1.0.0 build
> record is in `patch/BUILD_REPORT.md`. Do not quote this log
> as the current state of the project.

## Written and locally tested

- `server/pipeline.py` — windowing, centre-only tiling, microfade,
  Opus encode/decode. 6 tests pass (offline windowing == streaming
  windowing exactly; Opus roundtrip duration; memory bounded).
- `server/modal_app.py` — FastAPI factory (WebSocket + /segment
  fallback, replay cache, cold/warm header, BYOK placeholder).
  2 protocol tests pass with a stubbed separator and fetcher.
- `server/fetch.py`, `server/model_demucs.py` — written, not yet
  live-tested (see below).
- `spikes/measure_ws.py` — deploy-time measurement client (time to
  header, first chunk, per-chunk gaps, cold flag). Runs only after
  `modal deploy`.
- Total local suite: 8 passed, 0 failed.

## Dropped: local CPU model testing

Tried installing CPU torch + Demucs to prove the Demucs API shape
locally. The install failed (local /tmp space quirk during the wheel
download; home itself has 93 GB free, venv is clean with no partial
torch). James directed: don't use CPU, it takes too much time — do
Modal instead. The CPU path is dead and will not be retried. The
Demucs API usage in `model_demucs.py` is therefore UNVERIFIED until
the Modal deploy runs it on L4.

## Still unproven — needs Modal deploy (token required)

1. Real GPU timing: warm first-chunk time, per-chunk round trip vs
   7.5 s hop, cold-start load time (all measured, not estimated).
2. Demucs window API on L4 (shapes, stems order, speed).
3. Real Googlevideo URL fetch + Range behaviour.
4. Modal transport: WebSocket vs segmented HTTP on the real service
   (150 s timeout, preemption, scaledown behaviour).
5. BYOK auth wiring (user's own deployment vs key-checked endpoint).
6. RoFormer measurement + swap (Phase 2, after this spike passes).

## Deploy gate

No deployment has happened. Deployment needs James's Modal token via
his explicit choice of the CLI-config path, plus his go-ahead. The
Phase 0 cost guardrails (spend alarm, caps) must be set before the
first GPU run.

## Deploy blocked (2026-10-26) — sandbox cannot reach Modal's API

- `modal token set` failed twice: "Could not connect to the Modal
  server" while verifying against api.modal.com and api.modal2.com.
- Evidence: proxy CONNECT succeeds, but Modal's envoy answers 503
  "upstream connect error or disconnect/reset before headers" on
  BOTH api.modal.com and api.modal2.com. Modal's own status page
  says Operational, and modal.com + pypi return 200 from this
  sandbox — so it is the sandbox-to-Modal-API path, not a Modal
  outage and not the token (verification never got far enough to
  judge it).
- Consequence: NO config file was written — the token is NOT stored
  anywhere in the sandbox. It exists only in chat history, and
  James plans to revoke it later (revoking fully kills it, since no
  copy was stored).
- Next step is James's call: retry later, or deploy another way
  (e.g. GitHub Actions with the token as a repo secret). No silent
  fallback per his rule.

## Deploy via GitHub Actions (2026-10-26) — first run FAILED on a
packaging bug, not on Modal

- James approved the Actions route ("if it can actually work").
- Repo created PRIVATE: https://github.com/lukatdamoves/revance-karaoke
  (17 files pushed, commit ec88fba1; workflow is manual-trigger only,
  so pushes never start a deploy).
- Token pair stored as encrypted repo secrets MODAL_TOKEN_ID /
  MODAL_TOKEN_SECRET (verified present by name only; values never in
  code or logs — GitHub masks them as ***).
- Run 40230058375 (workflow_dispatch) FAILED in under a minute:
  `ModuleNotFoundError: No module named 'fastapi'` — the runner only
  installed `modal`, but `modal deploy` imports modal_app.py locally,
  which needs fastapi + httpx + numpy (torch stays lazy, not needed
  locally). The run died before ever reaching Modal, so
  runner-to-Modal connectivity is STILL unproven.
- Fix pushed (commit 3196deac, no run triggered): the workflow now
  installs `modal==1.6.2 fastapi httpx numpy` on the runner.
- Retry awaits James's go — the re-run is what proves runner-to-Modal
  connectivity and the real deploy (image build + endpoint URL).

## Second deploy run (2026-10-26) — FAILED on the app object name

- Run 40230058375's fix worked (runner deps installed, module
  imported), but run 40230283248 failed in ~90s with:
  `module 'modal_app' has no attribute 'app'`.
- Cause: `modal deploy modal_app.py` looks for a module-level object
  named `app` by default, but the code names it `modal_app`
  (line 272: `modal_app = modal.App("revance-karaoke")`). The code is
  correct; the command was wrong.
- Fix pushed and verified on main (commit 225f1fef, no run
  triggered): deploy command is now
  `modal deploy modal_app.py::modal_app`.
- Note the progress: this run got past local import, so the runner
  deps fix is proven. Runner-to-Modal connectivity is STILL unproven
  (the CLI failed before connecting). The next run tests that plus
  the image build.
- Re-run awaits James's go (second failure — stop, log, decide
  together per his rule).

## Research: is `modal_app.py::modal_app` the proper fix? YES (2026-10-26)

James asked for proper research instead of a from-memory fix. Verified
three ways, all against Modal 1.6.2 (the exact version the workflow
installs):

1. CLI source (`modal/cli/import_refs.py` in the installed package):
   `parse_import_ref` splits `file::object` on `::`; when no `::` is
   given, the lookup defaults to `DEFAULT_APP_NAME = "app"` and does
   `getattr(module, "app")` — exactly the error run 40230283248 hit.
   The CLI's own guidance message says: "If your `modal.App` is
   assigned to a different variable name, you must specify it in the
   app ref argument. For example an App variable `app_2 = modal.App()`
   would be specified as `<file>::app_2`." Our variable is
   `modal_app`, so `modal_app.py::modal_app` is precisely the
   prescribed form — not a workaround.
2. Docs pattern match: `@modal.asgi_app()` on a function returning a
   FastAPI app is Modal's documented way to expose a full FastAPI app
   at a public HTTPS endpoint — which is what `api()` does.
3. Local import check (no network needed): importing server/
   modal_app.py here confirms `modal_app` is a real `modal.App`
   named "revance-karaoke" with the `api` function registered.
   Related checks that also hold: the deploy name requirement is met
   by `App("revance-karaoke")` (no `--name` flag needed), and sibling
   imports work because the CLI puts the script's directory on
   sys.path and `App` defaults `include_source=True` (siblings ship
   into the container).
- Conclusion: the pushed fix (commit 225f1fef) stands as-is. The
  alternative — renaming the object to `app` — would also work but
  is only a convention change, not a correction, and would make the
  code more confusing (module-level `app` = Modal App vs the FastAPI
  `app` inside `create_app`). No code change needed.
- Still unproven until a run happens: runner-to-Modal connectivity
  and the image build. Re-run still awaits James's go.

## Deploy succeeded, containers crash-looped (2026-10-26)

- Run 40230685467 SUCCEEDED. Endpoint live:
  <your-workspace>--revance-karaoke-api.us-east.modal.run
  (URL scrubbed 2026-10-31: the live endpoint URL must not
  appear in the published repo. Substitute your own workspace
  slug. Runner-to-Modal connectivity and the image build are
  now PROVEN).
- But Modal emailed: containers in modal_app.api repeatedly fail to
  start. Container logs (fetched via a logs-only Actions run,
  40232345935) show the single cause, repeated:
  `File "/root/modal_app.py", line 58: from fetch import ...` →
  `ModuleNotFoundError: No module named 'fetch'` →
  "Function modal_app.api is crash-looping".
- Root cause: the container only gets modal_app.py itself. Sibling
  modules (fetch, pipeline, model_demucs) are NOT auto-mounted.
  Correction to the earlier research note: the "siblings ship into
  the container" claim was WRONG — include_source covers the
  entrypoint file, not its sibling imports.
- Fix (verified against Modal 1.6.2's own `Image.add_local_dir`
  source, then pushed, commit b393bd8e, no run triggered):
  `.add_local_dir(Path(__file__).parent, remote_path="/root",
  ignore=["modal_app.py"])` on the image — mounts server/ next to
  the entrypoint (modal_app.py itself excluded: Modal already mounts
  it there). Module re-imported locally clean after the change.
- Redeploy with this fix awaits James's go.

## Redeploy fixed the crash-loop (2026-10-26)

- James approved the redeploy + confirmed GitHub Actions as the
  route for now, and asked for monitoring so it won't crash-loop
  silently again.
- Redeploy run 40233018651 SUCCEEDED. First check after it:
  `GET /health` → HTTP 200 in 1.06 s, body
  `{"status":"ok","model":"htdemucs","model_loaded":false}`.
  The crash-loop is FIXED: sibling modules now mount (the import
  error is gone) and the model is correctly still unloaded (lazy
  load works as designed).
- Monitoring added: (1) a smoke-check step in deploy-modal.yml —
  every deploy now fails loudly if /health never answers 200
  (applies from the next deploy; the 40233018651 run used the
  previous workflow, so its health was verified manually);
  (2) a cron `modal-karaoke-crash-monitor` every ~6 h that fetches
  Modal app logs via Actions (control plane only — never wakes
  the GPU container) and messages James only on new failure
  signals or if the check itself fails. It never auto-redeploys
  or stops anything.
- Streaming measurement (WS, SoundHelix song, via proxy) running
  after the health check; results to be appended to
  docs/measurements.md when it lands.

## First streaming measurement FAILED (2026-10-26)

- Result: header in 8.4 s (cold_start true, cached false), then
  server error "Processing failed. Retry." at 28.45 s, 0 chunks.
  Full JSON in docs/measurements.md (run 1).
- Standing rule applied: no automatic retry, no fallback.
  Server-log diagnosis running (logs run 40233382878) to find
  which step failed (fetch / decode / model load / encode).
- Monitor scope change (James, 2026-10-26): the 6-hour recurring
  log-watch cron is REMOVED — monitoring is testing-only. The
  per-deploy smoke check in deploy-modal.yml stays (it only runs
  at deploy time, not on a schedule).

## First measurement diagnosis (2026-10-26)

- Root cause: Demucs htdemucs rejects our 10 s windows
  (441,000 samples > 343,980 training length ~7.8 s).
  `model_demucs.py` passes split=False deliberately, so Demucs
  never chunks the window itself. Song fetch + decode worked.
- Old `fetch` errors in the same dump are pre-redeploy leftovers
  (last-500 window); new deployment proven clean by /health 200
  and Demucs execution.
- Fix options awaiting James: A) 7 s windows (keeps split=False,
  changes chunk recipe + tests); B) split=True (keeps 10 s,
  overrides deliberate split=False).

## 7 s windows fix implemented + deployed (2026-10-26)

- James approved proceeding ("this sounds good, go do the next
  step") — went with option A (7 s, the recommended one).
- Change: `WINDOW_SECONDS` 10.0 → 7.0 in pipeline.py (HOP 5.25 s,
  EDGE 0.875 s derive automatically). Caught in local testing: 7 s
  makes WINDOW - HOP odd (77,175 samples), so the tiling assert
  needed a 1-sample trailing-edge tolerance (centres still tile
  exactly by HOP; the extra sample sits in discarded edge).
  Also added a loud guard in `DemucsSeparator.separate_window`
  (raises if a window ever exceeds the 343,980-sample limit).
  Local tests: 8/8 pass. Window 308,700 samples, 0.80 s margin.
- Pushed (pipeline, model_demucs, test comment, PLAN, README) +
  verified on main. Deploy run 40234338082 SUCCEEDED, including
  the new smoke-check step (/health 200) — the first deploy to
  self-verify. Re-measurement running (same SoundHelix WS test).
- Actions cleanup (James's ask): deleted the 2 failed runs
  (40230283248, 40230058375 — early deploy failures). Remaining:
  5 runs, all success. Workflows upload ZERO artifacts (0 bytes),
  so storage = run logs only, auto-deleted after 90 days — the
  500 MB limit is effectively a non-issue here. Live minutes
  meter unavailable (GitHub retired the user billing endpoint,
  410); measured run wall-time today is 3.5 min total across all
  5 runs (deploys 0.4–2 min, logs 0.2–0.3 min). Free tier is
  2,000 min/month for private repos, so even at 5 min per deploy
  that's ~400 deploys/month; run count itself is uncapped.

## Re-measurement run 2 STILL FAILED (2026-10-26)

- Same SoundHelix WS test after the 7 s redeploy: header 1.7 s
  (warm, model already loaded), same "Processing failed. Retry."
  at 21.8 s, 0 chunks. The 7 s fix alone did not resolve it.
- Open question for the logs: same length error (stale container
  served the request?) or a new error? Server-log diagnosis
  running (logs run 40234533417). No retry, no code change until
  the logs show the current error.

## Run 2 cause: stale container, not the fix (2026-10-26)

- Logs prove run 2 was served by OLD code: the server error was
  Demucs's own message (441000), not the new guard message.
  New code raises the guard before Demucs runs. Plus
  cold_start=false = leftover pre-redeploy container.
- So the 7 s fix is correct but not live yet. Remedies pending
  with James: A) stop app then redeploy; B) copy=True (bake
  code into the image) then redeploy.

## Stop + redeploy chosen (2026-10-26)

- James picked A (stop the app, then redeploy).
- Stop workflow created (stop-modal.yml) + dispatched, run
  40234809731. Redeploy + re-measurement follow once the stop
  is confirmed.

## Stop confirmed, redeploy succeeded (2026-10-26)

- Stop run 40234809731: success (stale containers terminated).
- Redeploy run 40234844878: success, including the smoke check
  (/health 200). Re-measurement run 3 running against the fresh
  deployment.

## Phase 1 spike: GO (2026-10-26)

- Run 3: 71/71 chunks, full ~6.2 min song in 36.4 s. First
  audio ~12 s cold; steady ~0.35 s per 5.25 s chunk (~15x RT).
  Transport proven end to end.
- Still unproven: real YouTube audio URL fetch, WS under
  preemption, BYOK, RoFormer (Phase 2).

## YouTube fetch blocking research (2026-10-26)

- James's worry: Modal fetching audio directly may get blocked.
  Deep research done (report at research_notes/
  youtube-audio-fetch-blocking-avoidance-20261025-2123/).
- Ranked verdict: (1) cache separated audio by video ID first
  (no fetch on a hit); (2) PHONE RELAY as primary uncached
  path — phone downloads audio on its own connection and
  uploads bytes (never the URL) to Modal, defeating IP binding,
  datacenter blocks, and mostly PO-token checks; ~7-12 MB
  extra user data per song; (3) server yt-dlp via sticky
  residential proxy + bgutil PO provider + client rotation as
  fallback (~$0.007-0.018/song proxy for audio only); (4) direct
  Modal fetch only as a cheap first try (datacenter IPs hit
  first). DO NOT BUILD phone-extracts-URL then server
  downloads: googlevideo URLs embed the requester IP and only
  work from the same subnet/ISP.
- Architecture decision AWAITING James: switch primary fetch
  to phone relay, or keep server fetch primary with relay as
  fallback.

## Device-proxy idea (2026-10-26)

- James's idea: run a very lightweight proxy inside the patched
  YouTube app and use the device proxy as a reverse proxy for
  Modal's fetches.
- Assessment: sound, and a refinement of phone relay. Key
  correction: Modal cannot dial into the phone (carrier NAT),
  so the phone must dial OUT and hold a tunnel open — the
  existing karaoke WebSocket IS that tunnel. Lightest form is
  not a proxy server at all: a WS "fetch this for me" handler
  (Modal sends URL+headers, phone runs the request, streams
  bytes back). Keeps extraction logic server-side (updatable
  without a new patch) while the phone stays dumb. Same
  block-beating as relay (real user IP, same IP for player +
  download). Must-haves: background threads only, stream
  don't buffer, separate HTTP client, host allowlist (YouTube
  hosts only) + authenticated WS for public release, each user
  self-proxies only their own songs. Decision awaiting James.

## Round 2 research: server-side-only bypasses (2026-10-26)

- James REJECTED phone relay/tunnel (extra user data) and
  asked for deeper research on server-side-only bypasses.
- Bottom line CONFIRMED and strengthened: NO free, reliable,
  server-side-only bypass for datacenter IPs exists. Only
  reliable server-side answer under zero user data = paid
  residential/static-ISP proxy supplied per-user (BYOK).
  Only free alternative remains phone relay (rejected).
- Evidence: Mar 2026 case study — identical yt-dlp+cookies
  worked residential, failed on Alibaba Cloud; 7 methods
  tried, only residential proxy worked. IP reputation is
  decisive; fingerprint mimicry cannot rescue a flagged
  datacenter IP. PO tokens (bgutil) do NOT bypass IP blocks
  (its own docs say so) — token checks only. No client works
  reliably from datacenter IPs token-free/cookie-free.
- Cheapest PAYG found: Evomi $0.49/GB, DataImpulse $1/GB,
  SocksNode $0.90/GB entry (prices vary across sources,
  verify at checkout); ~$0.0025-0.005/song at 5MB billable.
  Static ISP ~$2.50-3.50/IP/mo unlimited = best flat fee for
  heavy users. Free tiers are datacenter-only (plumbing test
  only, not a bypass). Sticky session mandatory (player +
  media same exit IP).
- BYOK ranking: user's PAYG residential URL > user's static
  ISP > free hardened yt-dlp+bgutil layers > throwaway
  cookies (optional) > Invidious/Piped fallback > own API key
  > home proxy. Developer-funded pool/API/cookies must never
  ship in the public patches source.
- Page "YouTube Audio Fetch Options" updated with round 2.
  Decision awaiting James.

## Fetch decision LOCKED: WS server-driven fetch (2026-10-26)

- James: "ok lets just proceed with this" after the WS vs relay
  comparison and the 2x data clarification.
- Locked design: cache first; on a miss, server-driven fetch
  through the phone over the karaoke WebSocket (server sends
  fetch requests, phone executes them from its own connection,
  streams bytes back window by window). No automatic fallback: on
  WS fetch failure, stop, log, decide together (candidates: BYOK
  proxy, direct try). Round-2 zero-data rule LIFTED by this
  approval; ~7-12 MB extra per song accepted.
- Phone NEVER downloads the full audio file: progressive
  range-fetch, windows handed over as the buffer fills (mirrors
  the proven server pipeline). Memory per window ~0.14 MB.
- Next: spec the WS fetch messages + server tunnel mode
  (server side first, harness-testable); patch handler follows
  after Phase 0 YouTube-version pinning.

## Phase 0 PINNED by James (2026-10-26)

- James uses ReVanced Manager + YouTube 20.40.45. Morphe
  research dropped. Patch targets the ReVanced patch API +
  20.40.45 bytecode; deliverable = his own patches source for
  his Manager (not a pre-patched APK).

## Patch skeleton + mute+sync spike (2026-10-26, source only)

- Skeleton written, NOT compiled or built: patch/ (KaraokePatch.kt
  with fingerprints matching nothing by design, WS tunnel client,
  Opus player, session state machine, settings holder) +
  patch/SPIKE_MUTE_SYNC.md.
- Spike verdict: mute STRUCTURALLY feasible, exact method
  UNVERIFIED. Player controller Laiwx identified in 20.40.45 dex
  (holds the ExoPlayer); the standard media3 Player volume /
  position / seek route is CLOSED in this build (held interface
  has none of those methods) — mute/position must go through
  Laiwx's own obfuscated methods (volume candidates L/N/as;
  seven ()J position candidates; roles unverified). Button
  injection point UNVERIFIED (resource names obfuscated).
  Sync design built (drift loop: ignore <80ms, nudge 80-500ms,
  hard-seek >500ms); phone proof still required.
- Gap found: mid-song taps — v1 protocol streams from song
  start, so a tap at 2 min waits ~8s for earlier chunks. The
  wait must be muted/held (never original audio, his hard
  rule); clean fix proposed (not built): start-offset
  parameter so the server starts from the tap position.
- Next: opcode-level method identification + button injection
  (resources.arsc decoding); fingerprints filled only from
  verified identifications.

## Patch pattern research (2026-10-26, RVX/inotia00 source)

- No existing patch holds YouTube's ExoPlayer or calls media3
  setVolume/position/seek. Proven pattern: hook the obfuscated
  player controller, stash it in a static extension field, add
  synthetic wrapper methods delegating to its own methods.
  Position via videoTimeHook (time-reporting method anchor);
  video ID via PlayerResponseModel. This confirms the Laiwx
  direction.
- Buttons: find controls inflate via ViewStub.inflate()
  fingerprint; look up buttons by resource NAME, never numeric
  ID. Overlay-buttons resource patch merges XML into the
  bottom container layout.
- Mute warning: RVX MuteVolumeButton mutes the WHOLE device
  music stream (would mute karaoke audio too) — wrong
  primitive for us. Player-internal mute has no demonstrated
  patch; hunt plan = enumerate volume-like methods on the
  controller class + synthetic patch_setVolume/patch_setMuted.
- Publishing: patches-bundle.json {version, .rvp URL, .rvp.asc
  URL}; patch must declare compatibleWith 20.40.45 or Manager
  won't offer it; Morphe .mpp won't load in ReVanced Manager.
- Caveats: RVX examples target up to 20.05.46, NOT 20.40.45 —
  every pattern must be revalidated on 20.40.45, not copied.
  Position from the time-hook lags playback (up to ~1000ms at
  1.0x) — not sample-accurate; sync must re-sync on seeks.
  "v6 patch API" label unmatched to a public API version —
  confirm DSL against revanced-patches-template.
- Patterns forwarded to the opcode work; full patterns page
  building.

## Opcode identification DONE (2026-10-26, source only)

- All six fingerprints filled in KaraokePatch.kt from opcode
  evidence on stock 20.40.45 dex (verified by reading the
  file: zero non-matching placeholders left): video ID p(),
  position e() (d() is buffered), volume as(F,Z)V via sync
  wrapper L(F)V (mute = L(0.0f), unmute = L(saved); N(F)V is
  the normalizer, never use), seek H(JLbcrd;)V (v1 doesn't
  call it), playing Q()Z (playWhenReady INTENT, not proven
  frame advance — drift loop covers stalls), controls
  inflate Lmju.C()V (exit hook; button group layout found
  by resource NAME). Apply block still TODO-only — patching
  changes no bytecode today; patcher match run is a build
  gate, and DSL/template confirmation is still open.
- Correction: spike's "no player API at all" was wrong in
  letter — superinterface Lchl declares the full player API.
  Consequence unchanged: go through Laiwx wrappers (they
  carry app bookkeeping), never direct player calls.
- Incident: /tmp wiped mid-task; APK re-obtained byte-identical
  to spike's; scratch now in ~/workspace/yt204045-work/.
- Still open: speed() source not identified; whether muted
  player still downloads original audio (affects data math);
  button hook on a real watch page; start-offset proposal.
- Next: patch build + Modal tunnel deploy — both need
  James's go.
- Deploy DONE (2026-10-26): tunnel code pushed (commit
  c0cdc001), stop run 40243928031 success, deploy run
  40243986287 success incl. /health smoke check. Live
  endpoint now runs tunnel-mode code. Still unproven live:
  real YouTube fetch via phone, real Demucs in tunnel mode
  — those need the patch + phone test.
- Patch bundle BUILT + CLI-verified (2026-10-26, local
  only): patch/build/karaoke-patches.rvp (extension dex
  extensions/karaoke.rve). All 7 fingerprints matched on
  stock 20.40.45; injection confirmed by patched-dex dump:
  5 synthetics on Laiwx, controller handoff at aB() entry,
  button hook at Lmju.C() exit, by-name lookup, mute-first
  gate, live e() position. Logging added (tag KaraokeNow;
  state/WS/decode/drift events; crash snapshot + trace;
  never keys or full URLs). Build lessons: run 1 partial
  injection reported success — dex dump is the proof, not
  the CLI line; run 3 clean. Behavior flag: a corrupt chunk
  now ends the song (was silently skipped) — skip-and-
  continue is James's call, not a silent revert.
- Next: publish the patches source for Manager (public
  repo + bundle JSON + signature) — needs James's go.
  Then phone gate: muted video + own audio in sync
  through pause/seek/full song.
- v2 bundle (2026-10-26, user-tested v1 issues): K + BV
  moved to bottom bar beside fullscreen (small white
  TextViews, grey Button box gone; hook moved from
  Lmju.C() center group to Lmjr.mi() bottom UI, new
  bottomUiInflate fingerprint; old controlsInflate
  removed). BV toggles flag + toast (audio unchanged
  until stage 2, stated honestly). Toasts now carry the
  failure reason; long-press K shares the phone log
  (ring buffer, no PC/adb needed). Mute gate hardened:
  verifies mute read-back ~0, refuses rather than
  risking both audios. Verified: CLI loads v2, classes
  .dex at root (Manager fix kept), dex dump confirms
  mi() hook + synthetics + new methods. Still unproven:
  placement in portrait + fullscreen, and the original
  "failed to start" root cause (candidates: AudioTrack
  start, server player-parse, WS handshake, first-chunk
  decode, mute refusal — the surfaced reason + shared
  log will identify it on retry).
- v3 bundle (2026-10-26, user-tested v2 issues):
  alignment root cause = buttons ~28dp tall vs
  fullscreen's 60dp, centers ~16dp off; now 60dp tall,
  centered, even spacing; fallback path copies bottom
  constraint too. CC behavior replicated: off = white
  text on transparent, on = white-filled rounded bg
  with black text (K filled while session alive, BV
  filled when flag on). Temporary LOG button beside BV
  copies log dump to clipboard + "Log copied" toast
  (marked DEBUG-ONLY, remove after debugging).
  Video-display verdict: NOT patch-caused — STAY video
  is squarish (~1.3:1) so the player pillarboxes it;
  patch only adds overlay TextViews, never touches
  the surface. Verified: CLI loads v3, classes.dex at
  root, dex dump confirms mi() hook + synthetics +
  new methods. Still unproven on phone: actual
  rendering of alignment + CC fill.
- Server player parsing FIXED locally (2026-10-26,
  NOT deployed yet): phone log proved the failure —
  player fetch 200/5545 bytes, then server choked on
  spike-grade audio_url parsing. fetch.py now parses
  real youtubei streamingData -> adaptiveFormats
  (audio-only, direct URL, highest bitrate, Opus
  tiebreak); playability gate first (ERROR/LOGIN_
  REQUIRED with reason); signatureCipher-only audio
  fails plainly (no deciphering, no fabricated URL).
  modal_app.py surfaces TunnelFetchError verbatim to
  the phone toast; generic retry kept only for
  unexpected non-tunnel errors. Tests: 24 passed
  (incl. 12 new player-parse tests). Still unproven:
  which shape the phone's 5545-byte body was (never
  captured) — next phone attempt's specific toast
  settles it; if it shows LOGIN_REQUIRED/age-gate,
  the player REQUEST is the next fix, not the parser.
  Deploy pending James's go (push + stop + deploy
  with smoke check).
- Server fix DEPLOYED (2026-10-26): push commit
  a8bb2cb8, stop run 40253927038 success, deploy run
  40253974911 success incl. /health smoke check.
  Live endpoint now runs real youtubei parsing +
  specific errors. Next phone retry settles which
  shape the 5545-byte body was.

## Stop confirmed, redeploy succeeded (2026-10-26)

- Stop run 40234809731: success (stale containers terminated).
- Redeploy run 40234844878: success, including the smoke check
  (/health 200). Re-measurement run 3 running against the fresh
  deployment.

## Why the sandbox cannot reach Modal's API — deep analysis (2026-10-26)

Question: why can't the sandbox talk to api.modal.com directly?
Answer: TWO independent barriers, both proven by tests.

Barrier 1 — the CLI cannot use the proxy at all.
- Modal's client speaks gRPC (grpclib), not plain HTTPS. Its client
  code contains zero proxy handling (no trust_env, no HTTP_PROXY).
  gRPC clients do not read proxy env vars.
- Proof: with a deliberately DEAD proxy (127.0.0.1:9), `modal token
  set` still took 58.7 s and failed the same way. A proxy-honoring
  client would fail instantly against a dead proxy. The 58.7 s is
  direct-connection attempts timing out (two hosts x ~30 s).
- And direct TCP egress from this sandbox is blocked (direct
  openssl to a Modal host hangs with no output). So the CLI's
  direct gRPC connections can never succeed from here, no matter
  what proxy env vars are set.

Barrier 2 — even THROUGH the proxy, api.modal.com is broken.
- curl (which does use the proxy) gets envoy 503 "upstream connect
  error... remote reset" on api.modal.com AND api.modal2.com —
  identical across forced IPv4, forced IPv6, HTTP/1.1, and even
  plain port 80. So it is not IP-version, HTTP-version, or
  TLS-version specific.
- The same proxy reaches modal.com (200), modal.run endpoints
  (real Modal behavior: the __modal_attempt_token cold-start
  redirect), github, and pypi fine. And the SAME token + API work
  from GitHub Actions runners (deploy + logs both succeeded).
  So the API is healthy; this egress path to Modal's API edge
  specifically is what resets.
- Extra fact: the sandbox proxy MITMs all TLS (certs presented for
  api.modal.com AND modal.com are issued by "Hatch Sandbox Egress
  CA"). So the proxy terminates and re-originates every TLS
  connection — the reset happens on the proxy-to-API-edge leg.

Fix options, ranked:
1. GitHub Actions runners (PROVEN, already in use): direct
   internet, no proxy, no MITM — deploy and logs both work from
   there. Use Actions for ALL Modal API interactions. This is the
   working fix.
2. A direct-egress permission in Muse settings (UNCERTAIN): the
   only sandbox-side fix that could work, since the CLI needs
   direct TCP. The earlier other_tcp permission did NOT unblock
   direct connections, so this would need a different setting —
   worth checking, not guaranteed.
3. Forcing the CLI through the proxy (e.g. proxychains) — RULED
   OUT: it would still land on the broken proxy-to-API leg
   (barrier 2), and still go through the MITM.
4. Modal-side or Hatch-side change (IP/fingerprint allowlist) —
   not actionable by us.
- Bottom line: from this sandbox, the Modal API is unreachable by
  design (gRPC + blocked direct egress + broken proxy leg).
  Actions is the route that works.

## v4 patch update (2026-10-26, local only)
- Bundle patch/build/karaoke-patches-v4.rvp: alignment
  nudge (glyph centers matched to the fullscreen icon
  via translationY; measured ~12.5px high on v3), video
  verdict (NOT patch-caused — shift appears when the
  patch's views are hidden, and the patch never touches
  the video surface), hostOf fix (wss URLs now log the
  real host; java.net.URL rejected the wss scheme).
- Verified: list-patches loads it, MultiDexIO probe
  passes, CLI patch run "Karaoke" succeeded, mi() hook +
  synthetics + alignButtonsToFullscreen confirmed in the
  dex dumps. Verification APK stays local (patched-v4/).
- Still open: phone retry of K (tests v4 alignment +
  the already-deployed server parsing fix together).
- v4 PHONE RESULT (2026-10-26): log worked perfectly
  (alignButtons delta=6.5 on all 3 buttons, real
  wsHost). Server error now SPECIFIC: "Playability:
  ERROR This video is unavailable" for 2 videos.
  ROOT CAUSE FOUND (not the server): the patch's
  videoId source Laiwx.p() returns a 16-char token,
  not a video ID. Proof: all 3 IDs in his logs are
  16 chars; real YouTube video IDs are 11 chars;
  the IDENTICAL server player request with a real
  11-char ID returns OK + 4 audio formats; the
  16-char ID fails oEmbed (does not exist). Chain
  traced: p() -> field w -> Lajcf.a -> Laivf.h
  (a per-playback token, almost certainly the CPN).
  Server player request, client, key all fine — no
  server change needed. Fix = point the patch at
  the true 11-char video ID (candidate sources:
  Laivf/Laiuv String fields, VideoStreamingData
  videoDetails, ExoPlayer mediaId) + phone test.
  Awaiting James's go for the fix bundle.
- v5 PHONE RESULT (2026-10-26): videoId fix
  CONFIRMED — session log shows videoId=7RjYhk6Y1GM
  (11 chars). Past the "unavailable" error. New
  failure: WS connect read timeout (>60s, client
  READ_TIMEOUT_MS=60s at KaraokeWsClient.connect
  handshake read). Endpoint /health took 14.2s just
  now = waking from idle (Modal sleeps when idle).
  Most likely cold start, not a code bug. Next fix
  candidate: warm-up ping on player open (already
  in the plan) so the container is warm before he
  taps K; retry now may already connect (endpoint
  warmed by his tap + this health check).
- v6 TASK (2026-10-26, James: "ADD LOGGING TO
  UNDERSTAND WHAT THE ISSUE IS"): diagnostic
  logging ONLY for the portrait video-position
  issue — surface rect, container rects, ancestor
  chain, before/after buttons added, controls
  shown/hidden, video size vs surface size if
  safely readable. No layout changes. Agent
  running; bundle local only.
- v5 PHONE RESULT 2 (2026-10-26, videoId
  u2jFFU9dV-w, 11 chars): handshake 101 ok,
  player fetch 200 with 92,323 bytes (real
  response), then server socket closed before
  any chunk — phone EOF, no error frame. Server
  except block DOES send an error frame for any
  Python exception, so a silent close is not an
  app exception. Fresh Modal logs (run
  40261821891): GPU_L4 capacity wait lines
  ("waiting to be scheduled on a GPU_L4 worker...
  acquiring more capacity") + server-side WS
  disconnects (1005) mid-fetch; NO Demucs/model
  traceback for the new session. Verdict:
  infrastructure-level (Modal GPU scheduling),
  not app code. Retry may work when capacity
  frees; T4 GPU (more plentiful) is a candidate
  server-config change if L4 capacity keeps
  blocking — needs James's go, never CPU.
- v6 PHONE RESULT (2026-10-26, videoId
  o374TsBOoVw): warmup ping HTTP 200 WORKS.
  Handshake 101 ok, player fetch 200 with
  199,480 bytes, then socket closed again, no
  header, no error frame — 3rd identical
  failure at the same step (after player parse,
  during/after model load). Warm-up did NOT fix
  it, so it is not cold start. Deterministic
  same-step death + no error frame (server
  except DOES send one for Python exceptions)
  points to process death (native crash on
  model load) or worker pulled mid-load, not
  app logic. Fresh Modal logs pulled (run
  40262415403) to distinguish.
- GEOMETRY VERDICT (v6 log, 2026-10-26):
  surface rect IDENTICAL before/after buttons
  (SurfaceView 0,230,1080x607 both times;
  getLocationOnScreen, same coordinate space)
  => the patch does NOT move the video, proven
  with numbers. Surface sits 115px below the
  overlay container (0,115,1080x607) WITH and
  WITHOUT our buttons — that offset is
  YouTube's own layout for this video/state,
  not ours. Supporting: bottomUiH varies
  245<->347 across videos while our row stays
  153 => YouTube's own UI changes size by
  itself. Stock comparison (same video, stock
  app) remains the final confirmation.
- LIVE PROBE (2026-10-26, sandbox playing the
  phone's role vs live endpoint, videoId
  o374TsBOoVw): WORKS. Header at 11.8s
  (cold_start=True, model loaded in ~10s),
  chunks seq 0-11 streamed (real Demucs
  instrumental Opus). Server + model proven
  fine. Phone's WS send framing checked and
  CORRECT (126/127 lengths, masking). Phone
  dies in the ~10s model-load silence while
  the sandbox survives the same silence =>
  break is the phone connection going idle
  (mobile network dropping the idle WS),
  not server code, not phone send code.
  NOTE: sandbox hit a 403 on audio range 3
  (datacenter IP artifact; phone's residential
  IP should not hit it; phone never reached
  ranges yet). FIX CANDIDATE: eager model
  load at server startup (no 10s silence, no
  phone update needed) + redeploy. Warm-up
  ping warms the CONTAINER only, not the
  model — that is why v6 still hit the gap.
- BAND, corrected (2026-10-26): James says
  stock does NOT have the band. Accepted as
  the true baseline. Correction to the
  earlier claim: the v6 BEFORE block was
  already patched (hooks ran, buttons not
  yet added) — so BEFORE==AFTER clears the
  BUTTONS only, not the whole patch.
  Pre-BEFORE patch code verified inert:
  setController only stores the controller,
  reads views (try/catch), fires warm-up
  ping; aB hook only adds a setController
  call at entry, mi hook only adds buttons
  at exit; original bodies run unchanged;
  no view writes, no layout params, no
  player calls before BEFORE. So no patch
  code path found that moves the surface —
  yet stock lacks the band. Remaining
  suspects: hook TIMING effects on the main
  thread during player setup. Decisive test:
  a diagnostic build with hooks stubbed
  (band gone => hooks/timing cause it).
  Awaiting go for that test build.
- SERVER, fresh logs (run 40263805071):
  eager load CONFIRMED on server
  ("model eager-loaded (cold=True)").
  Server parsed a player response OK
  (formats=26 audio=4 direct_url=4,
  selected itag=140). Phone sessions still
  end disconnected (server waiting at
  player fetch / 1005s). Server works
  through parse; break remains transport
  between phone and server for the phone's
  sessions.
- CORRECTION (2026-10-26): James is on WiFi,
  stable — not mobile data. Carrier theory
  dropped. Phone code re-checked: NO timer
  during CONNECTING, NO close path (close()
  only from user stop/cleanup), framing
  correct, and readLoop's closed flag was
  FALSE at failure => the close came from
  OUTSIDE the phone process. Server code also
  exonerated by the live probe. Remaining
  common factor in all 4 phone failures: the
  ~10s model-load silence (server sends
  nothing). Eager model load at startup still
  removes that window entirely, regardless of
  which transport element drops idle
  connections. Awaiting go for eager load +
  redeploy.
- EAGER LOAD DEPLOYED (2026-10-26): push
  b14409ae, stop run 40263144518 success,
  deploy run 40263174441 success incl.
  /health smoke check. Live probe verifies
  it: header at 3.55s (was 11.83s),
  cold_start=False, first chunk at 9.88s.
  The ~10s silence window is gone (player
  response -> header now 0.74s). No phone
  update needed — v6 bundle already has
  everything else. Next: James taps K.
- SCREENSHOT (2026-10-26, portrait): black
  band above the video CONFIRMED visible, and
  K/BV/LOG now aligned with the timestamp
  (alignment fixed). Band = the 115px gap
  (surface top 230 vs player area top 115),
  present BEFORE buttons too => not caused
  by our buttons (proven twice). Still needs
  the stock comparison (same video, stock
  app) to say whether YouTube itself lays
  this video out with that gap.
- SOCKET, post-eager-load (2026-10-26,
  o374TsBOoVw, 2 attempts): STILL socket
  closed after player fetch_response, before
  header — eager load did NOT fix the phone
  (gap now <1s, so idle-silence theory is
  dead). Phone send PROVEN complete: no "WS
  dispatch failed" line, so fetch_response
  left the phone cleanly; break is downstream
  (network/proxy/server). Fresh server logs
  (run 40263805071) pulled to check whether
  the server received the 11:22 attempts.
- v5 BUILT (2026-10-26, local only): bundle
  patch/build/karaoke-patches-v5.rvp. New videoId
  source = Lajcf.j() (VideoStreamingData.e, the
  player-response videoId), read via a chain synthetic
  on Laiwx (i -> Laiwl.l -> j()). aB's String param
  ruled out (same 16-char token in all callers);
  MediaItem route ruled out (no MediaItem class in
  any dex). Dex-dump verified incl. a caught slip
  (first build referenced Laiwx->j instead of
  Lajcf->j; fixed). Phone check: session-start log
  must show an 11-char videoId; K must get past
  CONNECTING.
- v6 BUILT (2026-10-26, local only): bundle
  patch/build/karaoke-patches-v6.rvp. DIAGNOSTIC
  LOGGING ONLY for portrait video position (surface
  rect, decor/visible frame, ancestor chain,
  bottom-UI/row/fullscreen/K/BV/LOG rects, controls
  shown/hidden proxy; BEFORE/AFTER buttons added;
  change-only, no loop spam) + WARM-UP PING (one
  fire-and-forget GET to /health on controller set,
  derived from DEFAULT_WS_URL, no retry, no toast,
  WS code untouched). Video-size synthetic SKIPPED:
  no VideoSize API exists in this build's ExoPlayer
  fork. Extension only; patch dex byte-identical to
  v5. Dex-dump verified (geometry + warmup calls,
  mi()/aB hooks, videoId via Lajcf.j, alignButtons
  intact). Phone test: portrait, controls shown AND
  hidden, tap LOG, paste it (no K tap needed for
  the numbers); for karaoke, wait a few seconds
  after opening a video, then tap K.
- v7-diag BUILT (2026-10-26, local only): bundle
  patch/build/karaoke-patches-v7-diag.rvp (61,181
  bytes). DIAGNOSTIC ONLY: KaraokeBridge.
  setController() stubbed to log "diag:
  setController stubbed" + return — no controller
  stored, no geometry log at controller set, no
  warm-up ping (zero Warmup.ping callers left).
  Everything else v6-identical: mi() hook,
  buttons, alignment, videoId synthetic, session,
  WS client untouched; patch classes byte-identical
  to v6 (md5-verified). Karaoke will NOT start
  (no controller) — expected; visual test only.
  Dex-dump verified: setController = const-string
  + log + return only; aB pc 0 -> setController;
  mi pc 121-128 -> initializeBottomButtons;
  patch_getVideoId = i -> Laiwl.l -> Lajcf.j().
  CLI loads (Karaoke, enabled), MultiDexIO probe
  passes, CLI patch run "Karaoke" succeeded.
  Verification APK local only at
  ~/workspace/yt204045-work/patched-v7-diag/.
  Phone test: patch, open the SAME video in
  portrait, report ONLY whether the black band
  above the video is gone or still there. Band
  gone => the aB hook's setController work caused
  it. Band still there => the controller hook is
  exonerated too, and the cause is elsewhere.
- v7-diag VERDICT (2026-10-26): band STILL
  there with setController stubbed (surface
  still 0,230,1080x607, before AND after
  buttons). Controller hook EXONERATED.
  Combined with buttons exonerated
  (BEFORE==AFTER), the patch's runtime
  work does NOT push the video down.
  BAND PARKED per James ("focus on making
  karaoke work first") — revisit only with
  a stock screenshot of the same video.
- SOCKET ROOT CAUSE FOUND (2026-10-26,
  KaraokeWsClient.sendText 127-form):
  payload.length is a Java int, and Java
  masks int shifts to 5 bits, so the
  8-byte length field writes the length
  TWICE (>>56/48/40/32 become >>24/16/8/0).
  A 267KB fetch_response declares ~1.1
  petabytes -> proxy kills the frame with
  no error frame -> phone EOF, server left
  waiting in receive_json. Fits every fact:
  small messages (hello) use the correct
  126-form and work; fetch_response is the
  FIRST phone->server message >64KB and
  dies; v5 92KB also >64KB, also died;
  sandbox probe (correct framing) lived.
  Read side is fine (long len). Second bug
  in same file: sendPong sends UNMASKED
  pong (client frames must be masked) —
  would kill the connection if a ping ever
  arrives. V8 FIX (extension only): long
  length in sendText + masked pong.
  Awaiting go for v8.
- v8 BUILT (2026-10-26, local only): bundle
  patch/build/karaoke-patches-v8.rvp (61,226 bytes).
  Extension only, patch classes byte-identical to v6.
  Fix 1: sendText 127-form length is now a long (old
  int-shift code wrote the length bytes twice: a 267KB
  fetch_response declared ~1.1 petabytes, proxy killed
  it with no error frame — the socket-close root
  cause). Fix 2: sendPong now masked (was unmasked, a
  second kill vector). Real v6 setController restored
  (NOT the v7-diag stub). Byte proof: harness shows
  OLD 267000 -> declares 1146756268299000, NEW ->
  exactly 267000. CLI loads, probe passes, CLI patch
  "Karaoke" succeeded, patched-dex dump confirms all
  hooks + synthetics + shr-long sendText + masked pong
  + real setController. Fix also applied to canonical
  extension source (diff vs build copy empty).
  Phone test: same video o374TsBOoVw, tap K, expect
  101 -> fetch 200 -> HEADER -> chunks -> playing.
- v9 BUILT (2026-10-26, local only): bundle
  patch/build/karaoke-patches-v9.rvp (61,373 bytes).
  Extension only, patch classes byte-identical to v6/v8.
  Fix: OggReader rewritten to return exactly ONE packet
  per call (old code glued a page's packets into one
  oversized blob -> BufferOverflowException at feed time
  on chunk seq=0); decoder now sets KEY_MAX_INPUT_SIZE
  65536. Harness proof: v9 returns 3 exact packets +
  joins a spanning packet (514B) with mid-page resume;
  v8 control merges them (12B / 520B). CLI loads, probe
  passes, CLI patch "Karaoke" succeeded, patched-dex dump
  confirms all hooks + synthetics + v8 framing + new
  OggReader + max-input-size. Phone test: tap K, expect
  header -> chunks -> AUDIO playing.
- v9 PHONE TEST (2026-10-26, tGFAyn2eAWw,
  x2 identical): DECODER FIX CONFIRMED —
  no overflow, decoder created, chunks
  seq 0-4 streamed (0-21.00s). NEW
  BLOCKER, deterministic: 2nd audio range
  fetch (id=2, bytes 524288-1048575) -> HTTP
  403 from googlevideo, server stops by
  design ("no other source tried"). 1st
  range (id=1, 0-524287) -> 206 fine.
  Empty-PCM warning is downstream of the
  kill (decode cut short), not a separate
  bug. Phone executeFetch applies ALL
  server-sent headers, so a header fix is
  SERVER-ONLY (no phone build). Leading
  theory: plain HttpURLConnection fetches
  get flagged as downloader after the
  first; official player fetches the same
  URLs fine. CANDIDATE (not a chain):
  player-like headers on range fetches.
  Others if that fails: different audio
  format/host, single full-file fetch —
  decided together. DEADLINE 12:00 MISSED
  for end-to-end playback; 403 is the last
  blocker seen.
- HEADER FIX DEPLOYED (2026-10-26): push
  4d326cd8 (fetch.py: PLAYER_RANGE_HEADERS
  = YouTube Android 20.40.45 UA + Accept
  */* on audio range fetches only; player
  fetch untouched) -> stop run 40266917202
  success -> deploy run 40267084553
  success incl. /health smoke check;
  live /health verified 200. No phone
  build needed (v9 applies server headers
  via executeFetch). Next: James retests
  v9, same video, tap K — 2nd range fetch
  must return 206 now.
- 403 DEEP ANALYSIS (2026-10-26, post-
  header test tGxnxmIcOOQ): headers
  HELPED but did not fix: fetch id=2 now
  206 (was 403); wall moved to id=3
  (3rd range fetch). Session reached
  chunk 10 (52.5s) vs chunk 4 (26.25s).
  Phone post-headers AND sandbox probe
  both die at 3rd range fetch (id=3);
  phone pre-headers died at 2nd (id=2).
  RULED OUT: expiry (seconds apart, URL
  valid hours); bad ranges (contiguous
  512KB, first ones 206); phone code
  (same request shape, only Range
  changes); video-specific (2 videos, 2
  edges, same pattern). BEST EXPLANATION:
  googlevideo abuse scoring of the fetch
  PATTERN — anonymous, audio-only,
  sequential fixed 512KB windows, ~15x
  playback speed. Player differs in 4
  ways: app identity/session, real-time
  fetch pace, video+audio together with
  variable ranges, rotated connections.
  UA match bought exactly 1 more fetch;
  (ii)-(iv) still mark us downloader.
  CANDIDATES (pick one, not a chain):
  (a) fresh connection per fetch
  ("Connection: close", server-only, 1
  header, cheapest); (b) pace fetches to
  near real-time (server-only design
  change; first fetch immediate so
  startup stays fast). Weaker: different
  format/host (does not fix pattern);
  phone-side cookies (Cronet store not
  accessible to extension).
- SECOND PROBLEM (same log): chunk seq=0
  decodes to EMPTY PCM (bufferedSec stays
  0.00, playback never starts even before
  403). Server Opus is standard (44.1kHz
  stereo 128k libopus, 20ms frames), so
  next diagnostic = decode a REAL server
  chunk offline with sandbox ffmpeg: if
  ffmpeg decodes it, phone decoder path
  is at fault; if ffmpeg also empty,
  server encoding is at fault.
- EMPTY-PCM DIAGNOSTIC RESULT (2026-10-26):
  captured REAL chunk seq=0 (97,169 bytes,
  same bytes the phone got for tGxnxmIcOOQ)
  via sandbox probe; sandbox ffmpeg decodes
  it to EXACTLY 1,008,000 bytes PCM = 5.25s
  stereo 48kHz. Stream is VALID. Phone
  decoder path is at fault for empty PCM.
  Leading suspect now: v9 OggReader
  splitting on REAL ffmpeg pages (harness
  used synthetic pages only) — next check:
  run the real chunk bytes through the v9
  reader and compare packet boundaries
  against ffmpeg's.
- PACING DEPLOYED (2026-10-26, James
  picked pace): fetch.py writer paces
  range fetches to near real-time —
  first range immediate, later ranges no
  earlier than 80% of the previous range's
  audio time elapsed (bitrate plumbed
  from extract_audio_url, now returns
  (url, bitrate_bps); fallback 128k).
  Math at itag 140 (130,559 bps): 32.1s
  per 512KB range -> fetch2 ~25.7s,
  fetch3 ~51.4s after session start.
  Push 07907cbb, stop run 40267833753
  running, deploy next. No phone build
  (server-only). Retest: v9, same video,
  tap K — fetches must now stay 206 past
  id=3.
- READER VERDICT (2026-10-26): v9 OggReader
  on REAL chunk0.opus bytes = 265 packets
  (OpusHead 19, OpusTags 62, 263 audio);
  audio packets match ffprobe EXACTLY
  (263/263, zero mismatches). READER
  EXONERATED. Stream valid (ffmpeg -> full
  5.25s PCM) + reader valid => empty PCM
  is in the phone's MediaCodec decoder
  path (config/drain), NOT parsing or
  encoding. Pacing retest will distinguish
  seq=0-only quirk (later chunks decode
  fine) from systematic decoder failure
  (all chunks empty) — v10 logging only
  if systematic.
- PACING DEPLOY LIVE (2026-10-26): push
  07907cbb -> stop run 40267833753
  success -> deploy run 40268028450
  success incl. /health smoke check;
  live /health verified 200. Retest: v9,
  same video, tap K, KEEP SESSION RUNNING
  ~90s+ (fetches now spaced: id=2 ~26s,
  id=3 ~51s) — must stay 206 past id=3,
  and watch whether later chunks decode
  non-empty (decoder question).
- BYTE-LEVEL READER VERDICT (2026-10-26):
  v9 OggReader vs independent Python Ogg
  parser on real chunk0.opus: 0 mismatches
  / 265 packets (sizes AND SHA-256
  content). READER FULLY EXONERATED.
- PACING VERDICT (2026-10-26, oq3dP-MFcpw):
  wall STILL at fetch id=3 (403) despite
  ~26s/51s spacing. Speed theory
  weakened; cap looks like ~1MB per URL
  for our client type. Fresh-connection
  per fetch still UNTESTED (per-connection
  byte limit is a live mechanism pacing
  cannot address) — next 403 test,
  server-only.
- DECODER NOW SYSTEMATIC (same log):
  ALL 10 chunks (seq 0-9) decoded to empty
  PCM, each with own decoder created.
  Stream valid + reader byte-perfect =>
  phone MediaCodec interaction is the
  fault. Next: v10 with decoder logging
  (packets fed, input timeouts, output
  buffers/bytes, output format) + csd-1
  (OpusTags) added to decoder config
  (additive, some vendor decoders want
  it). Awaiting go for fresh-connection
  server change + v10 build together.
- v10 BUILT (2026-10-26, local only): bundle
  patch/build/karaoke-patches-v10.rvp (61,777
  bytes). Extension only: csd-1 (OpusTags)
  added to decoder config + one summary log
  line per chunk (fed/inTimeouts/outBufs/
  outBytes/outFormat). Patch classes
  byte-identical to v6/v8/v9; CLI loads
  (Karaoke, enabled); probe passes; CLI patch
  run "Karaoke" succeeded; patched-dex dump
  confirms csd-1 + summary log + all v8/v9
  fixes intact; zero diag-stub strings.
  Phone test: patch, tap K, paste log —
  summary lines show WHERE decode fails,
  and whether csd-1 fixed empty PCM.
- FRESH-CONNECTION DEPLOY LIVE (2026-10-26):
  push 7331aa4a (Connection: close on range
  fetches) -> stop run 40268760983 success
  -> deploy run 40268924527 success incl.
  /health smoke check; live /health
  verified 200. v10 ALSO READY (bundle
  delivered). Combined retest: v10, tap K,
  keep session ~90s+ (pacing still active)
  — decoder summaries + fetch statuses
  past id=3 both in one log.
- v10 LOG VERDICT (2026-10-26, IXl2odcx_d0):
  EVERY chunk: fed=263 inTimeouts=0
  outBufs=0 outBytes=0 outFormat={sample-
  rate=48000, mime=audio/raw, channel-
  count=2}. Decoder accepts all 263
  packets, drops none, reports valid raw
  output format, emits ZERO output
  buffers. Points to: missing codec-
  config buffer (OpusHead never fed as a
  BUFFER_FLAG_CODEC_CONFIG input — some
  vendor decoders ignore csd-0 and wait
  for it) and/or EOS drain giving up on
  first 10ms gap before a batched flush.
  Next v11: feed OpusHead as codec-config
  buffer first + persistent EOS drain
  (loop to EOS flag or 5s total, not
  first gap). Summary logging kept.
- 403 VERDICT (same log): fresh-
  connection did NOT fix it — still id=3
  (403). Headers + pacing + fresh-
  connection ALL failed; wall stubbornly
  ~1MB / 3rd range across videos/edges.
  Remaining 403 paths: (a) burst-then-
  1.0x-pace (initial 1MB burst for 64s
  buffer, then exact real-time — tests a
  sliding 1MB/60s window; 0.8x continuous
  pace was still 1.25x real-time, possibly
  just over the line); (b) residential
  proxy rotation per request (paid BYOK,
  research candidate ~$0.0025-0.005/song)
  if (a) fails; (c) architecture rethink
  (server-side has the same cap).
  Awaiting go for v11 + burst-pace.
- v11 BUILT (2026-10-26, local only): bundle
  patch/build/karaoke-patches-v11.rvp (62,142
  bytes). Extension only: OpusHead fed as a
  BUFFER_FLAG_CODEC_CONFIG input buffer before
  the first audio packet (never fails the chunk
  over it) + persistent EOS drain (polls to EOS
  flag or 5s, not first gap; only when EOS was
  queued). Patch classes byte-identical to
  v6/v8/v9/v10; CLI loads; probe passes; CLI
  patch run "Karaoke" succeeded; patched-dex
  dump confirms config buffer + EOS drain +
  all v8/v9/v10 fixes intact; zero diag-stub
  strings. Phone test: patch, tap K, paste log
  — summary lines show whether chunks now
  decode non-empty (outBytes > 0, audio plays).
- BURST-PACE DEPLOY LIVE (2026-10-26):
  push cfbbc821 (ranges 0-1 immediate,
  >=2 at exact 1.0x) -> stop run
  40270047819 success -> deploy run
  40270208732 success incl. /health
  smoke check; live /health verified 200.
  v11 ALSO READY (bundle delivered).
  Combined retest: v11, tap K, keep
  session ~2min (fetch3 lands ~64s,
  fetch4 ~96s under burst-pace) —
  decoder summaries + fetch statuses
  past id=3 both in one log.
- v11 LOG VERDICT (2026-10-26, o374TsBOoVw):
  decoder: fed=263 inTimeouts=0
  outBufs=1 outBytes=0 — the ONE buffer
  is the EOS marker only. Config buffer
  + persistent EOS drain did NOT fix it;
  decoder outputs zero audio frames.
  403: still id=3 despite burst-then-
  1.0x (fetch3 ~69s, burst fetches ~69s
  old) — sliding-window theory DEAD.
  Wall is a hard ~1MB per URL for
  anonymous clients. James asked for a
  second-opinion context file:
  second-opinion-context.txt (513
  lines, no secrets) covering goal,
  architecture, server+patch details,
  what works, both bugs with full
  attempt history + ruled-out theories,
  and 4 questions for the other AI.
- CLAUDE SECOND OPINION (2026-10-26):
  Bug B: csd-1 is NOT OpusTags — per
  Android Opus docs csd-1 = pre-skip
  in ns (8-byte native long), csd-2 =
  seek pre-roll in ns. v10 put ASCII
  "OpusTags" in csd-1 -> decoder read
  ~8e18 ns delay -> trims EVERY sample
  -> zero output, no error. Fits v10/
  v11 exactly. WRINKLE: v9 (csd-0 only,
  no csd-1) was ALSO empty, so if v12
  still empty there is a second cause;
  codec-name log will point at it.
  Bug A: Claude proposed a PO-token
  POSITIONAL gate (~1,007,959 bytes,
  from a RustyPipe web-player note;
  Android application was his
  inference). SANDBOX PROBE REFUTED it
  for our shape: fresh itag 251 URL,
  control range 0-524287 -> 206 /
  524288 bytes; probe range
  2000000-2050000 (first request on
  that URL) -> 206 / 50001 bytes.
  A hard positional gate would have
  403'd the 2M range. Cumulative
  per-URL+IP ~1MB is back as best
  explanation; proxy rotation NOT
  ruled out by this probe (but still
  paid). Claude declined PO-token
  minting / impersonation / IP
  rotation help; candidate (c) (app's
  own session, Lajcf siblings /
  media cache) needs no forging.
  NEXT (awaiting go): v12 = correct
  csd-1 (preSkip LE bytes 10-11 ->
  ns; 312 -> 6,500,000) + csd-2
  (80,000,000 ns) + revert v11
  codec-config buffer + keep
  persistent EOS drain + codec-name
  + csd-size log + clearer server
  403 text (range start, not "may
  not honour Range").

## Status v12–v83 (appended 2026-10-31 — this log was frozen at v11)

Everything below happened after this file stopped being updated.
It is a summary, not a full log: the per-version detail lives in
`patch/BUILD_REPORT.md` (v1–v14 + v15–v83 appendix) and the
2026-10-31 full review (`final-review-2026-10-31.txt`, local only).

- v12 (2026-10-26): the decoder fix. csd-1 = pre-skip in ns
  (8-byte native long), csd-2 = seek pre-roll 80,000,000 ns;
  OpusTags parsed/skipped, never used as csd-1. PHONE-CONFIRMED
  2026-10-26 13:49: decoder `c2.android.opus.decoder`, every
  chunk seq 0–11 decoded (fed=263, outBufs=264, outBytes=1,008,672).
- v13 (2026-10-26): playback-start gate on `queuedSec()` (fixes
  the Bug C deadlock — no version before v13 could ever have
  reached PLAYING), opcode-verified pause via `Laiwx.M(Z)V`,
  loading overlay. Gate CONFIRMED on phone 14:17 (queuedSec=5.25,
  gate fired). But the mute read failed: `patch_getVolume` called
  `Laiwx.a()`, which returns PlaybackParameters.speed, NOT volume.
- v14 (2026-10-26): diagnostic only — `KaraokeBridge.call()`
  logs the InvocationTargetException cause. Last verified bundle
  before the v79 phone log.
- v15–v18 (2026-10-30, second agent on Pi/Termux): smali patches
  on a decompiled v13 base. UNVERIFIED and superseded — analysis
  2026-10-30 found the smali patch targeted a call site that does
  not exist, the verification was vacuous, and the "mute fix"
  helpers faked the read-back. Do not treat v15–v18 as current.
- v19–v79 (2026-10-30/31, source-built): v28 rebuilt mute/pause
  on the app's own paths (pause via `Laiwx.ar(false/true)`,
  volume via player field `ae` + method `O`, isPlaying via `Q()`).
  BV became real (BS-RoFormer stage 2, v56+, independent backing
  stream, live mix). BYOK became real (`server/setup_app.py`:
  per-user Modal deploys; client has no hardcoded karaoke
  endpoint). Server moved L4 -> T4. Fetch route became visionOS
  (v40). Watchdogs added (30 s load watchdog, playback-gone
  watchdog, stall guard, rebuffer/reconnect, `keepMuted()`
  re-assert every 500 ms).
- v79 phone log (2026-10-31, video oq3dP-MFcpw, 212 s): FIRST
  END-TO-END PROOF. Session reached PLAYING; mute worked (saved
  0.62878186, set 0.0, read back 0.0); pause worked on first K
  tap; BYOK auto-setup deployed to James's own Modal host; chunks
  streamed to seq 19 (~99.8 s); BV toggled live; writer alive,
  underruns=0; NO 403 (WS fetch id=2–6 all 206 x 524,288 B).
  Full song, seek, and stop/restore NOT yet shown.
- v80–v83 (2026-10-31, seekbar tint — REVERTED, see below):
  four attempts to turn the buffered (gray) and played (red)
  seekbar segments light blue for karaoke coverage. v80 set
  model field `m`; v81 set `l` + `m` with a 1 s re-assert loop;
  v82 scanned all Laliy bars and set `l`/`m`/`b`/`c`; v83 also
  tinted the scrubber paint and zeroed the red-gradient drawable
  alphas. Phone tests: the ambient bar turned blue, but the
  controls bar stayed red when controls were showing. Dex root
  cause: the scrubber paint holds theme red (set once in the
  constructor, no model field feeds it) and red-gradient drawables
  are rebuilt on every draw — no model change could fix it.
  v83 was sent but never phone-tested.
- 2026-10-31 17:25 — James: done with the seekbar. Revert it,
  don't touch it, finalize one final patch with a thorough review
  first (no bugs, security: Modal abuse). Seekbar reverted
  ~17:30: the whole v70–v83 section (522 lines) deleted from
  `KaraokeBridge.java` plus all call sites; javac clean
  (41 classes); seekbar is stock again.
- Full review (2026-10-31): 6 critical security holes (shared
  endpoint unauthenticated, setup helper unauthenticated, URLs
  leaked in old bundles/docs, SSRF branch, client fetch
  allowlist, WS hostname verification) + ~18 high-severity bugs
  (mute-flag trust, unmute poisoning, WS frame caps, fetch
  blocking the reader thread, player reset, AudioTrack, guard
  loops) + ~30 medium + ~25 polish. See the review file.
- 2026-10-31 17:39 — James: "Fix everything first, keep
  endpoints up for now." Fixes proceed with the shared endpoint
  + setup helper left live; stopping them is deferred to the
  public-release step.
- Still unproven (2026-10-31): full song, seek, stop/restore on
  the phone; WS under preemption; visionOS route end-to-end
  against live YouTube (phone logs show no 403 since it landed,
  but no test/log in the repo proves the route itself).
