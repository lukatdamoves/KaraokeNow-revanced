# Security and code review — 2026-10-31

This is the full pre-release review of the karaoke patch, written
2026-10-31 and published verbatim except for the redactions noted
below. It is the evidence base for the v1.0.0 fixes: every finding
here was either fixed in v1.0.0, explicitly deferred, or is listed
below as still open.

## Status of this review

The review's verdict ("not ready for a final build or public
release") applied to the code as it stood on 2026-10-31. The fix
list in Parts 1–4 was then applied, and v1.0.0 was built from the
fixed source — see `patch/BUILD_REPORT.md` for the build record
and verification. What changed after the review:

- **S1 (shared endpoint unauthenticated) — still open.** The
  shared Modal endpoint is still live, still has no key check,
  and its URL is in old bundles that were already distributed.
  It must be gated or stopped before this repo goes public.
  Scrubbing the repo does not close this hole.
- **S2 (setup helper) — hardened and deployed 2026-11-01.**
  The helper now validates the token with `modal token info`
  before spawning anything, rate-limits starts (3/hour per IP,
  50/day global, max 2 concurrent deploys), and caps a deploy
  at 30 minutes. Live-tested with junk tokens: rejected in
  ~2 seconds, no deploy spawned. The helper endpoint itself
  still has no key check by design — it is the public BYOK
  setup path, and the limits above are its abuse controls.
- **S3 (endpoint URL already out) — mitigated, not closed.**
  Old bundles were deleted from the repo, and live URLs were
  scrubbed from docs and workflows. The URL remains in
  distributed old bundles, so S1 (stop/gate the endpoint) is
  still required.
- **S4 (hardcoded helper URL) — accepted.** The helper is
  hardened (see S2), so the hardcoded setup-helper URL in the
  client is the intended discovery path for BYOK setup.
- **S5, S6, S7 — fixed in v1.0.0.** The non-tunnel `audio_url`
  SSRF branch and POST `/segment` were deleted; fetches are
  allowlisted to youtube.com / googlevideo.com with loopback
  and private IPs blocked; the client requires wss:// only;
  the WebSocket enforces hostname verification.
- **Known gaps not fixed in v1.0.0** (listed in
  `patch/BUILD_REPORT.md`): duplicate-sequence drop in
  `queueChunk()` (D11), watchdog blind-timeout (L9),
  EncryptedSharedPreferences (S18), dependency pinning (H7).
- **Server tests** have grown since this review: 54 pass
  (the review predates the setup-helper hardening tests).

Decisions from Part 8, all made after the review: fix
everything first and keep the endpoints up for now, with
stopping/gating deferred to the public-release step; v1.0.0
chosen as the release version; the fix list applied and the
final patch built. A fresh phone test (full song, seek,
stop/restore, video switch, setup flow) is still pending.

## Redactions

- One truncated workspace reference removed.
- Personal names replaced with "the maintainer".
- No technical content was changed.

---

FINAL REVIEW — ReVance YouTube karaoke patch
Date: 2026-10-31
Scope: extension Java (8 files, ~7,900 lines) + KaraokePatch.kt (792 lines) + server/*.py + workflows + docs + bundles. Read-only; nothing was built, pushed, or deployed.
Status of the seekbar revert: CLEAN. All 13 deleted identifiers have zero matches left. The video-align guard (fixPlayerInset) is intact. The only remaining "seekbar" mentions are two comments about button placement. javac clean (41 classes). The v12 decoder fix is intact.

VERDICT: NOT ready for a final build or public release yet.
6 critical security holes + ~18 high-severity bugs + ~30 medium + ~25 polish items.
Fix order: (1) security, (2) hard-rule bugs, (3) crash/hang bugs, (4) should-fix, (5) polish + docs, (6) fresh phone test, (7) final build.

================================================================
PART 1 — SECURITY (critical, must fix before the repo goes public)
================================================================

S1. The shared Modal endpoint is live, unauthenticated, unlimited.
   server/modal_app.py:504-505 — /ws accepts any caller, never reads a key.
   The docstring admits modal_key is "ACCEPTS and IGNORES". POST /segment
   (:599) is the same: free GPU inference for any audio, no size cap.
   Impact: anyone with the URL gets unlimited songs separated on the maintainer's
   T4 GPU. ~$0.027/song, so 1,100 songs = a whole $30 credit.
   Fix: shut the shared app down and go BYOK-only (the client already
   refuses to start with no saved endpoint), OR add a real per-user key
   check + quotas + concurrency/duration caps. Delete /segment — the
   client never calls it.

S2. The setup helper is an unauthenticated, billable deploy service.
   server/setup_app.py:180-196 — /setup/start takes any token-shaped
   string (only checks it starts with ak-/as-), then runs `modal deploy`
   for up to ~55 min in the maintainer's workspace. No rate limit, no CAPTCHA.
   Impact: anyone can spam it with junk tokens; every call bills the maintainer
   for container runtime whether the token is real or not.
   Fix: rate-limit per IP + global concurrency cap, validate the token
   cheaply BEFORE spawning the deploy, shorter timeout. Or shut the
   helper down until the public BYOK flow is ready.

S3. The endpoint URL is already out — in old bundles and docs.
   The URL is in old .rvp bundles (v2-v14), in patch/build/v7-diag..v14
   ext-src copies of KaraokeSettings.java, in docs/PHASE1.md:143,
   docs/measurements.md:6, second-opinion-context.txt:83, the handoff
   txt, and .github/workflows/deploy-modal.yml. .gitignore does not
   exclude patch/build/.
   Fix: delete patch/build/ from the repo, scrub the URL from docs and
   workflows, add patch/build/ + *.rvp to .gitignore — AND stop/rotate
   the endpoint anyway, because scrubbing alone cannot un-leak it.
   (Count note: the repo survey listed 65 .rvp files / 5.27 MB; the
   local tree holds 24 .rvp files. Confirm the real count on GitHub.
   Either way, they all go.)

S4. The setup-helper URL is hardcoded in the current patch source.
   KaraokeBridge.java:1377-1378 — SETUP_HELPER_URL, confirmed present
   in the v83 bundle strings. This is the discovery path for the S2
   billing attack. Fix S2 first (then a hardcoded helper URL is
   acceptable), or make it a build-time constant that can rotate.

S5. Server-fetch (non-tunnel) path is an SSRF hole.
   server/modal_app.py:565-583 + server/fetch.py:397-410 — any /ws
   caller can make the maintainer's container fetch ANY url (no host allowlist,
   redirects followed) and ffmpeg-decode it.
   Fix: delete the non-tunnel branch entirely (tunnel mode is the
   shipped path). If kept, allowlist *.googlevideo.com only.

S6. The phone executes arbitrary server-directed fetches; accepts
   plaintext ws://.
   KaraokeWsClient.java:500-505 (comment admits no URL allowlist),
   :589-701 (Cronet + plain fetch, redirects followed). Any URL —
   http:, LAN/private IPs, localhost — body returned to the server.
   All server-sent headers applied verbatim (incl. Host/Cookie).
   KaraokeBridge.java:1331-1373 normalizeEndpoint accepts ws:// and
   http://. Today the only endpoint writer is the maintainer's own helper, so
   this is defense-in-depth, not an open hole.
   Fix: require wss:// only; allowlist fetch hosts (*.youtube.com,
   *.googlevideo.com); block loopback/private IPs; strip
   Host/Authorization/Cookie headers the server sends.

S7. WS has no hostname verification (MITM on public Wi-Fi).
   KaraokeWsClient.java:120-128 — raw SSLSocket, chain validated but
   hostname NOT checked. A network attacker with a valid cert for ANY
   domain can impersonate the Modal host.
   Fix: one line — set SSLParameters endpoint identification "HTTPS"
   on the socket before connect().

S8. Client fetch proxy echoes all response headers incl. Set-Cookie.
   KaraokeWsClient.java:395-410, :604-608, :723-728 — Set-Cookie values
   are put into the fetch_response JSON sent to the server, and a
   cookie jar attaches YouTube cookies to later fetches, unscoped by
   host, never cleared.
   Fix: strip Set-Cookie/Cookie from echoed headers; scope the cookie
   jar by host (or drop it); clear on close. Server: require
   *.googlevideo.com in extract_audio_url (fetch.py:297-298).

S9. Replay cache is keyed by public video ID only.
   server/pipeline.py:230-231 + modal_app.py:248-278 — any caller
   asking for the same video_id gets the separated song replayed free
   for 10 min per container. Low cost risk (replay saves GPU), but
   strangers can download full separated songs free.
   Fix: key the cache per authenticated user once auth exists (S1).
   Acceptable as-is if the shared endpoint is shut down.

S10. Token handling gaps in the setup helper.
   setup_app.py:195 — token passed as plain args to run_deploy.spawn
   (Modal may record function arguments). :164-166 — exception text
   stored in the job record WITHOUT scrubbing, returned by unauthenticated
   /setup/status. :95-98 — /tmp/modal-config-*.toml created, never deleted.
   Jobs in the Modal Dict never expire; /setup/status returns the deployed
   URL to anyone holding the 16-hex job id.
   Fix: scrub ak-/as- patterns from exception text; delete the config
   file in a finally; expire job entries (~1h / after first done read);
   rate-limit /setup/status; return generic "deploy failed" instead of
   the CLI tail.

Other security notes (verified clean):
- No real secret values anywhere (no ak-/as- values, no ghp_, no private
  keys, no Bearer tokens) in source or any bundle. Only secret NAMES.
- Workflows handle GitHub secrets correctly (${{ secrets.* }}, nothing
  echoed). All deploy/stop/log workflows are manual-trigger only;
  test-server.yml runs on push but uses no secrets.
- No exported Android surface (no manifest/activity/service/receiver).
  The bridge is only callable in-process from patched YouTube code.
- Token never stored on-device (in memory only during setup, sent once
  over HTTPS, never written to prefs). Prefs hold only the endpoint URL
  + setup job id, MODE_PRIVATE.
- The hardcoded InnerTube key (modal_app.py:442-444, fetch.py:185-187)
  is YouTube's public web-client key — not a secret, no action needed.
- /health leaks only model names; trim to {"status":"ok"} anyway.

================================================================
PART 2 — BUGS THAT BREAK THE HARD RULE (never play original audio)
================================================================

M1. keepMuted() blind spots; failed re-mute only warns, session plays on.
   KaraokeBridge.java:3730-3770 — returns true when the player is
   unresolvable ("skipping check"), when read-back is unavailable, and
   on any exception. KaraokeSession.java:849-856 — the only caller just
   logs and never fails/pauses/stops.
   Fix: tri-state (healthy/blind/failed); fail the session (stop karaoke,
   then unmute) after 2-3 consecutive failed re-mutes; cap blind ticks.

M2. Failed unmute poisons the mute flag; user stuck muted forever.
   KaraokeBridge.java:3652-3700 — `muted = false` is set BEFORE the
   read-back poll; on mismatch it returns false with the flag already
   false and mutedPlayer nulled. :3596 early-return then makes every
   later setMuted(false) a no-op, and the next setMuted(true) saves
   savedVolume = 0.0.
   Fix: only clear muted/mutedPlayer after a verified read-back; on
   unmute failure keep flag true and retry (bounded); never overwrite
   savedVolume with <= ~0.01 unless the pre-mute read was genuinely low.

M3. Failed mute read-back leaves volume at 0.0 but flag at false.
   KaraokeBridge.java:3629-3633 — mute branch mutes, read-back fails,
   returns false WITHOUT restoring savedVolume. Gate fails -> stop ->
   setMuted(false) hits the :3596 early-return, restores nothing.
   Fix: on mute read-back failure, restore savedVolume inside
   setMuted(true) before returning false; make setMuted(false) verify
   actual volume, not the flag.

M4. Pause failure at start: comment says "keeps playing muted" but
   nothing mutes until the gate. KaraokeSession.java:257-272 — start()
   only calls video.pause(); no setMuted in start(). Same gap in
   pauseVideoForSetup (KaraokeBridge.java:1399-1417): if pause() fails,
   the original plays through a setup dialog that can take minutes.
   Fix: if pause() returns false/throws at start, pre-mute immediately
   (with read-back) or fail honestly; correct the comment.

M5. Gate's mute proof is flag-only on the sticky-restart path.
   KaraokeBridge.java:1990 pre-mute -> :3596 early-return ->
   KaraokeSession.java:505 gate accepts without re-reading volume.
   Fix: gate must force a fresh volume read-back (verifyMuted() /
   setMuted(true, forceVerify)).

M6. Mirror-resume may defeat the drift hold every tick (UNVERIFIED —
   confirm against KaraokePlayer.resume() before shipping).
   KaraokeSession.java:1017 mirror block calls player.resume() every
   tick while playing; :1027-1038 holdingForSync branch resumes only
   when drift is back in band and never re-pauses when still ahead.
   Fix: move the holdingForSync check before the mirror resume, or
   make the holding branch re-pause.

M7. Stop-during-gate race can resurrect PLAYING after the user stopped.
   KaraokeSession.java:483 tryFirstAudioGate() is synchronized, but
   stop()/stopInternal()/fail() are not. Between mute and setState(PLAYING)
   + startSyncLoop(), a K-tap stop can set IDLE — then the gate sets
   PLAYING and starts a fresh sync executor.
   Fix: synchronize stop/stopInternal on the same lock, or re-check
   stopping/state immediately before releaseHold()/setState(PLAYING).

M8. isPlaying() (Q()) is the sole life signal in several watchdogs.
   KaraokeSession.java:825-838 — if positionMs() < 0 and isPlaying()
   true, the tick returns BEFORE the mute guard (background karaoke runs
   with no mute check). If Q() is false while actually playing,
   goneTicks stops karaoke after 2s. :267,386 — wasPlayingFlag from Q()
   decides whether stopInternal() resumes the video.
   Fix: don't treat Q() alone as life — require position advance;
   resume-on-cleanup based on "session paused it and user tapped K";
   keep the mute guard running on the background early-return path.

M9. syncTick throws on transient reflection failures -> whole session
   fails mid-song. KaraokeSession.java:817,858 (positionMs/isPlaying
   unguarded); startSyncLoop :739-750 catches any tick exception and
   calls fail(). call()/playerCall() throw after a 4s main-thread latch
   timeout. Fix: catch per-read in syncTick, skip the tick, fail only
   after 3-4 consecutive failures.

M10. Gate releases karaoke audio before resuming the video; resume
    failure ignored. KaraokeSession.java:537-549. Fix: resume video
    first, then release hold; if resume returns false, keep held and
    retry/fail.

M11. Speed-read fallback (1.0) + speed-following can chatter at 2x.
    KaraokeSession.java:859,1039 — transient speed() failure returns
    1.0 while video is at 2.0; karaoke falls behind ~1s/s and hard-seeks
    every 1.5s. Fix: cache last good speed, use it on read failure;
    skip hard-seek while speed is uncertain.

M12. Rebuffer/reconnect pauses untracked; pause failures swallowed.
    KaraokeSession.java:1115-1125, :867-877 — video.pause() result
    ignored; pausedByKaraoke cleared at the gate, so stopInternal()
    will NOT resume the video on a rebuffer-timeout fail (~60s cap).
    Fix: track rebuffer/reconnect pauses separately and resume on that
    failure path.

================================================================
PART 3 — CRASH / HANG BUGS (high)
================================================================

D1. Unbounded WS frame/message size; Error kills the reader thread
    silently, session hangs. KaraokeWsClient.java:291-330 — no max
    frame/message size; new byte[(int) len] allocates whatever 64-bit
    len declares after (int) truncation; fragments accumulate unbounded.
    Ping handling is recursive (return readMessage, :317) — ping flood
    -> StackOverflowError. readLoop :347-363 catches Exception only,
    so OOM/StackOverflow escape, reader dies, neither onError() nor
    onClosed() called, socket never closed.
    Fix: cap frame + reassembled message (~2 MB; normal chunk ~90 KB),
    reject len < 0 or > cap before allocating, make ping iterative,
    catch Throwable in readLoop, call onError(), closeQuietly() in finally.

D2. No hostname verification (see S7 above) — one-line fix.

D3. Fetch blocks the reader thread: head-of-line stall up to 3 min.
    KaraokeWsClient.java:367-407 dispatch() runs on the reader thread;
    :378 executeFetch inline; :673-676 Cronet latch.await(180_000);
    plain fetch 15s connect / 60s read per-read. While a fetch runs, no
    chunks/header/done/ping are processed and the 60s socket timeout
    can't fire.
    Fix: run fetches on a separate executor; deliver fetch_response /
    fetch_error back to the reader thread (or enforce one total fetch
    deadline ~60s on both paths, cancelling on expiry).

D4. Writer skips past a slow first chunk, then drops it as stale.
    KaraokePlayer.java:970-997 — if the queue is empty for 30s,
    nextExpectedSeq++ (:992-996). When that chunk arrives, seq <
    nextExpectedSeq so it's dropped as stale; writer waits another 30s.
    Fix: never ++ on an empty queue; keep waiting for the same
    nextExpectedSeq while the stream is alive (or jump only when
    streamDone, or to reorder.firstKey() when non-empty).

D5. stop() does not reset the player, does not join/drain threads.
    KaraokePlayer.java:766-787 — clears reorder/backingReorder/opusCache,
    releases the track, but does NOT reset held, paused,
    nextExpectedSeq, streamDone, playheadFloorSec, queuedChunks,
    basePlayedFrames; does not drain decodeTasks/backingDecodeTasks or
    join the three threads — only interrupts them.
    Fix: make stop() a full reset (held = true), drain both decode
    queues, join() all three threads with a timeout, null thread fields
    — or document single-use and guard start() after stop(). (Whether
    KaraokeSession reuses a player across songs was NOT verified — check.)

D6. bufferedFrames / currentChunk.pos races; stale chunk can play
    after a seek. KaraokePlayer.java:158 (volatile long is not atomic),
    :852 (write loop -= no lock), :1009 (+= under reorderLock), :554/558,
    :670/771, :847 vs :559, :667 (seekJump sets currentChunk = null
    under lock while the write loop holds a local cur and keeps writing).
    Fix: guard every bufferedFrames mutation and every currentChunk.pos /
    capFrames mutation under reorderLock (or AtomicLong + generation
    check), clamp bufferedFrames >= 0 after the write-loop decrement,
    re-validate the chunk under lock after a seek generation change.

D7. start() can fail with AudioTrack unusable and poison the player.
    KaraokePlayer.java:326-365 — running.getAndSet(true) happens BEFORE
    AudioTrack.Builder().build(), no try/catch, no STATE_INITIALIZED
    check; track.play() unguarded. If the builder throws, start() throws,
    running stays true, threads never start, retry is a no-op.
    Fix: build track in try/catch, check STATE_INITIALIZED, on failure
    running.set(false), track = null, report via listener.onPlayerError,
    start threads only after the track is proven playable.

D8. trimChunk() can set a negative capFrames -> write-loop crash.
    KaraokePlayer.java:895-906 — nominal = round((next.startSec -
    c.startSec) * OUT_RATE); if successor startSec <= c.startSec,
    capFrames goes negative, framesLeft() negative, writeLoop does
    new short[frames * 2] with negative size -> NegativeArraySizeException.
    Fix: validate at intake (reject seq < 0, start < 0, sampleRate <= 0)
    AND clamp in trimChunk(): nominal = max(0, min(nominal, pcm.length/2));
    drop a zero-length trimmed chunk.

L1. videoAlignGuard runs forever, full decor traversal every 500 ms.
    KaraokeBridge.java:137-160 (re-posts while videoAlignGuardActive),
    :988-996. No stopVideoAlignGuard() anywhere; only exit is
    bottomUiRef going null (:147). Once any watch UI opens, the main
    thread runs fixPlayerInset() -> findVideoSurface() (DFS over the
    whole decor view, up to 5000 views) every 500 ms for the lifetime
    of the YouTube process — karaoke off, player closed, landscape.
    Fix: add stopVideoAlignGuard() (clear flag + removeCallbacks), call
    it on bottom-UI detach, activity death, and when karaoke goes
    inactive; or scope the guard to karaoke-active only.

L2. startSessionForVideo() overwrites a live session with no guard;
    async restarts can resurrect karaoke after K-off.
    KaraokeBridge.java:1959-1984 — creates next, session = next,
    next.start() without stopping/checking any existing session.
    restartForVideo :1987-1996 doesn't re-check karaokeSticky or
    liveness. onVideoSwitched :3874-3889 spawns a fresh thread that
    runs later. A K tap between thread spawn and run starts a new
    session anyway (K shows off while karaoke plays, old session never
    stopped — orphaned WS/player, double mute bookkeeping); a K tap
    between session = next and next.start() creates a second session.
    Fix: guard at the top of startSessionForVideo() (stop any live
    session first or refuse), re-check karaokeSticky + session identity
    on the restart thread immediately before starting (ideally do
    restarts on the main thread).

L3. pollSetupJob() is an unbounded while (true) loop.
    KaraokeBridge.java:1598-1690, loop :1603, Thread.sleep(4000) :1604.
    Exits only on done/error/unknown-state. Null httpJson() (helper
    unreachable) just continues. No elapsed-time cap, no consecutive-
    failure cap, no cancellation; the job stays in prefs, so every later
    K tap reopens progress for a dead job.
    Fix: cap total elapsed time (~15 min) and consecutive nulls, then
    fail cleanly: set error text, clear saved job, resume the video,
    toast "tap K to retry".

K1. Fingerprints gate a pattern, synthetics hardcode a name: a wrong
    delegation passes the build silently. KaraokePatch.kt:558-580 —
    resolves 7 fingerprints, then DISCARDS 5 matches (only videoIdMatch,
    bottomUiMatch, controllerMatch are used). Synthetics :604-680 invoke
    hardcoded names ("a", "L", "e", "Q", "M", "j") — never the matched
    method. Nothing checks the fingerprinted method IS the called method.
    Proof it already bit once: patch_getVolume -> a() passed every gate
    while a() returns speed, not volume. Worse: the videoId fingerprint
    (:172) matches p() (the 16-char token), yet patch_getVideoId
    (:668-680) uses a hardcoded field chain Laiwx.i -> Laiwl.l ->
    Lajcf.j() that NO fingerprint verifies at all.
    Fix: in resolveAndInject(), assert match.method.name equals the
    expected target and throw PatchException on mismatch — or build each
    synthetic's invoke from the matched method's reference. Add patch-time
    existence assertions for fields Laiwx.i:Laiwl;, Laiwl.l:Lajcf; and
    method Lajcf.j()String.

K2. controllerEntry is the weakest fingerprint; a false match kills
    karaoke with a passing build. KaraokePatch.kt:371-390 — no opcodes(),
    no accessFlags(), no exact parameter count — only returns("Lajcb;")
    + first 3 params + class Laiwx;. The header comment says aB has 7
    params and PUBLIC|FINAL (0x12), but the fingerprint checks neither.
    Fix: require the full 7-param list and accessFlags(PUBLIC, FINAL);
    add an opcode anchor from aB's entry if stable. Same hardening for
    fingerprints without declared parameters: videoId (:172),
    videoPosition (:206), playingState (:313).

================================================================
PART 4 — SHOULD-FIX (medium)
================================================================

Decoder/WS:
D9. Disconnect/error handling leaks the socket, can deliver audio after
    an error. KaraokeWsClient.java:347-363 readLoop() never closes the
    socket on exit; :367,455-458 dispatch() catches, calls onError(),
    returns normally, so readLoop() keeps reading and can deliver
    onChunk/onHeader AFTER the session failed, and can call onError()
    repeatedly. Fix: readLoop() finally { closeQuietly(); }, dispatch()
    signals the loop to break after onError(), guard onError() to fire
    once per connection.
D10. Cronet failure falls through to a second (plain) fetch; timeouts
    disagree; Cronet engine never shut down. :507-525, :583-676 (180s
    latch), :685-694 (plain, 60s). If executeCronetFetch() throws, the
    catch falls through to executePlainFetch() — same fetch twice.
    cronetUnavailable permanent after one build failure; static
    cronetEngine/cronetExecutor never shut down on close(). Fix: fall
    back to plain only when the engine is null (not built), never after
    a Cronet request started; unify on one total timeout; shut down the
    Cronet executor on close().
D11. Chunk/header values not validated before use. :411-434 —
    sample_rate = 0 or negative passes through (startSec -> Infinity/
    negative); negative seq/start pass to queueChunk(); duplicate seq
    overwrites reorder but queuedChunks++ runs unconditionally;
    opusCache overwritten without dedup; huge opus_b64 decoded with no
    size check. Fix: in dispatch() reject seq < 0, start < 0,
    sample_rate <= 0, opus_b64 over ~1 MB with onError(); in queueChunk()
    drop a duplicate instrumental seq already in reorder/opusCache.
D12. Decoder drops packets silently, trusts buffer sizes.
    KaraokePlayer.java:1103-1112, :1190-1198, :1221-1229 —
    dequeueInputBuffer(10_000) returning -1 increments inputTimeouts
    and DROPS that packet without retry; ptsUs not advanced. inBuf.put
    (:1107) has no null/capacity check — oversized packet throws
    BufferOverflowException -> queueChunk()'s catch -> fatal playerError
    for the whole session. getOutputBuffer() results dereferenced
    without null check. Fix: retry input buffer a bounded number of
    times (or re-queue the packet); check capacity/null, treat oversized
    packet as a per-chunk skip, not session-fatal; null-check output buffers.
D13. connect() leaks the socket on hello failure; handshake headers
    unbounded. :172-189, :143-151 — sendText(hello) throws IOException,
    but only JSONException is caught — socket never closed, no reader
    thread exists. Handshake header loop appends byte-by-byte with no
    size cap and no total timeout; 60s SO_TIMEOUT resets per byte.
    Fix: wrap post-socket connect() in try/catch calling closeQuietly()
    on any failure; cap handshake headers (~8 KB) + total handshake deadline.
D14. Codec leak on non-IllegalStateException from stop(); Error kills
    decode/write threads silently. KaraokePlayer.java:1147-1155 —
    finally catches only IllegalStateException from codec.stop(); another
    RuntimeException skips codec.release(). decodeLoop() catches only
    InterruptedException, writeLoop() only Exception — an Error (e.g. OOM)
    kills the thread with no playerError. Fix: wrap codec.release() in
    its own try/catch (always run); catch Throwable in both loops,
    report via playerError(), return.
D15. Cookie jar is cross-host, unscoped, never cleared.
    KaraokeWsClient.java:474,483,490-498 — any Set-Cookie from any fetch
    host stored without domain/path/expiry checks, sent to EVERY
    subsequent fetch regardless of host; never cleared on close()/connect().
    Fix: scope cookies by host (or drop the jar and let the server send
    Cookie explicitly per fetch); clear on close().
D16. Unbounded decode-ahead while held/paused; opusCache never evicted.
    KaraokePlayer.java:118-125, :127-129 — decode runs ahead at ~15x
    real-time while writer is held/paused — full song as PCM (~1 MB/chunk
    x 2 stems) plus raw Opus cache, no backpressure/eviction until stop().
    On the 256 MB-heap TECNO KL8 a long song is a real OOM risk.
    Fix: pause decode when reorder is e.g. >10 chunks ahead of the
    writer; cap opusCache (evict played seqs).

Lifecycle/UI:
L4. Setup failure leaves the video paused with no session. The two
    failure catches in startAutoSetup()/pollSetupJob() never call
    resumeVideoIfSetupCancelled() — only called at :1491,1504,1510
    (dialog cancel/invalid-token) and :1772 (progress-dialog dismiss,
    only if setupErrorText != null at dismiss time). Fix: call
    resumeVideoIfSetupCancelled() in both setup-failure catch blocks
    (and on InterruptedException).
L5. Warmup ping's 10-minute gate is consumed before the endpoint check.
    KaraokeWarmup.java:54-66 — lastPinged/lastPingAtMs set at :58-59,
    THEN the endpoint is read and a null endpoint returns at :60-64
    without pinging. Fix: move the gate update to after host validation.
L6. Loading overlay has no independent escape or timeout.
    KaraokeBridge.java:3775-3795, :3796-3808, :3809-3811 — hiding
    depends entirely on a later onState(PLAYING). Detach watcher ignores
    CONNECTING/BUFFERING detaches (:457-463), so a session started as
    the user leaves keeps running until the ~30s load watchdog fails it.
    Fix: give the veil its own timeout tied to the load watchdog
    (hide + fail the session); hide on a confirmed detach.
L7. 50 ms play/pause guard does a full decor DFS on every tick while
    loading. KaraokeBridge.java:245-278, :2371-2420 (budget 6000 views,
    every 50 ms); showLoadingOverlay() (:2564) re-runs the DFS and
    re-attaches the layout listener on every show call. Fix: DFS only
    when the hierarchy actually changed; cache matched views.
L8. BV toggled while sticky but with no live session is silently lost.
    KaraokeBridge.java:1177-1216 — tap flips backingVocalsEnabled, fills
    the button, toasts "Background vocals ON". But startSessionForVideo()
    (:1959) calls resetBvToDefault() first, so the next song starts with
    BV off. Fix: block the tap when there is no live session, or don't
    reset a flag the user explicitly just set.
L9. videoId NPE handling mostly right, two gaps. (a) getVideoIdOrNull()
    (:3444) logs KaraokeLog.e with a full trace on EVERY failure,
    including the benign early case — the log cries "failed" for a normal
    timing window. (b) The setController delayed check has no retry — if
    videoId is unreadable at 1.5s, the sticky restart never happens for
    that video, and the sync watchdog abstains on null, so an old session
    can keep playing the WRONG song's karaoke over the new video while
    blind. Fix: log the precheck-known-null case at debug level; retry
    the delayed switch check a few times; add a blind-timeout to the
    session watchdog (~30s of unreadable videoId -> stop).
L10. setMuted(true) can no-op on a new player when the retain failed.
    KaraokeBridge.java:3578+ — stale-flag check only runs when
    mutedPlayer != null; if the retain failed (player null at mute time),
    the flag stays true and the method returns true immediately without
    touching the new player. Fix: treat mutedPlayer == null as stale
    and force a real mute.
L11. Unmute sleeps on the main thread (up to ~900 ms freeze).
    KaraokeBridge.java:3649-3672 — target-player branch does
    Thread.sleep(100) in a 10-iteration read-back loop with no
    main-thread guard, while the generic unmute branch uses
    triesU = onMain ? 1 : 10. Fix: mirror the onMain guard.
L12. onError toast tells the user to restart the app ("karaoke failed
    restart the app"). Most failures recover with a K tap. Fix: toast
    "karaoke failed — tap K to retry".
L13. "Starts when readable" claim on the K tap path is false.
    KaraokeBridge.java:1839-1935 — videoId-null branch logs "sticky
    stays, starts when readable", but karaokeSticky is never set there
    and nothing retries. Fix: set sticky + schedule a retry, or correct
    the log/toast to "tap K again".

Kotlin patch:
K3. Injected bottom-UI code can NPE inside Lmjr.mi() before the Bridge
    ever runs (premise unverified). KaraokePatch.kt:~730-760 — exit hook
    does iget Lmjr.a:Lmju; then iget Lmju.r:View; with no null check.
    Comment only covers Lmju.r null. It does NOT cover Lmjr.a null —
    then the second iget throws NPE inside mi(), so bottom-UI inflation
    itself throws. Fix: verify from the dex that Lmjr.a is assigned in
    the constructor and never null at mi() exit, or add an if-eqz skip
    around the hook.
K4. Four of seven synthetics are dead, two are traps. Zero call sites:
    patch_setVolume, patch_pause, patch_resume, patch_isPlaying are
    never called. patch_pause/resume -> M(Z)V was PROVEN ineffective as
    pause. patch_setVolume -> L(F)V cannot take 0.0f (L routes through
    as(), which builds Lchg(0.0f), whose constructor throws IAE on 0.0).
    Fix: delete the four dead synthetics (KaraokePatch.kt:612-660) and
    stop resolving their gate fingerprints (playingState :570-572,
    playPause :573-575; playerVolume :566-568 if as() is truly unneeded).
    Keep patch_getPosition, patch_getVideoId, and the speed synthetic.
K5. Revert left orphaned dead code. (a) restoreVideoAlign() can never
    run: videoAlignApplied is never set true anywhere (only set false
    at :848), other four fields never written — restoreVideoAlign()
    (:823-850) always returns at :825, its only caller (:561) is a no-op.
    (b) Seekbar-only coverage chain, now callerless:
    KaraokeSession.java:459 bufferedEndSec() and :466
    karaokeCachedCoverage(); delegates KaraokePlayer.java:723
    coverageEndSec() and :740 cachedCoverageSec(). (c) shareLog() dead:
    KaraokeBridge.java:1246-1266, no caller — K long-press now opens the
    setup dialog (:592, :641). Fix: delete all three groups.
K6. patch_getVolume still lies about what it returns.
    KaraokePatch.kt:139-143, :594-605, :770 describe it as the volume
    wrapper; it delegates to a() (speed). Bridge side is correct
    (speed(), KaraokeBridge.java:3548-3560), so a naming trap, not a
    live bug — the same misnomer caused the v13 mute failure. Fix:
    rename synthetic to patch_getSpeed and update the one call site
    (:3553).
K7. Comments in three files contradict the current code.
    KaraokeSession.java:69-75, :97-100; KaraokeBridge.java:63-65, :85-88,
    :559-560, :2919-2921; KaraokePlayer.java:943-944. Fix: rewrite these
    six comment blocks. No behavior change.

Server (medium, from the server+repo review):
- H1. No cost/abuse controls; long songs can die mid-stream.
  modal_app.py:699 — 900s timeout, no max video duration, no per-
  container concurrency limit (_SEPARATE_LOCK serializes GPU inference,
  so concurrent songs queue and the second can hit the 900s timeout),
  no in-flight dedup. Fix: read lengthSeconds from the player response
  and reject videos over ~15-20 min; per-container busy guard.
- H2. build-patch-v19.yml never builds the Kotlin patch code and uses
  an unpinned android.jar. It installs Kotlin 2.3.0 but never invokes
  kotlinc; classes.dex comes from the repack base v15.rvp (v13-era
  Kotlin). No verification steps at all. Fix: compile KaraokePatch.kt
  in the workflow; pin android.jar to android-35; repack from a known-
  good base; add list-patches / CLI patch run / MultiDexIO probe / dex
  dump verification; stop pushing .rvp to main — upload as workflow
  artifact and let a human attach it to a release.
- H3. Setup helper deploys stale server code. setup_app.py:66-71 —
  the helper image bakes server/ at deploy-setup.yml time. Any
  modal_app.py/fetch.py/pipeline.py change after that is silently
  ignored until deploy-setup.yml is re-run. Fix: re-run deploy-setup.yml
  after every server change (document it), or fetch server code at
  deploy time.
- H4. Setup-helper deploy timeout not enforced when the subprocess goes
  quiet. setup_app.py:112 deadline checked only after each stdout line;
  if modal deploy stalls with no output, the loop blocks on readline
  until the 3600s function timeout. Fix: reader thread + proc.wait
  (timeout=...), always wait() after kill().
- H5. No LICENSE file. PLAN.md:99-100 says GPLv3. Fix: add the GPLv3
  LICENSE file and a short note in README.md.
- H6. Docs are wrong/stale. patch/README.md (says patch changes NO
  bytecode, BV doesn't exist — both false since v13/v56); server/README.md
  (says Mel-Band RoFormer on L4, BYOK placeholder, deploy deferred —
  all stale; omits model_karaoke.py and setup_app.py); docs/PHASE1.md
  (frozen at v11, 2026-10-26); docs/SETUP.md (frozen 2026-10-26);
  patch/BUILD_REPORT.md (frozen at v14); modal_app.py docstring
  (says BYOK is "spike status"); root README.md (4-line stub).
  Fix: rewrite patch/README.md, server/README.md, PHASE1 (append v12-v83
  status or mark historical), SETUP, BUILD_REPORT, modal_app.py docstring;
  root README gets real install/usage (Manager alternative-source steps,
  BYOK setup, pinned YouTube 20.40.45).
- H7. Unpinned dependencies in the Modal image. modal_app.py:671-674 —
  fastapi, httpx, torch, torchaudio, demucs, numpy unpinned;
  bs-roformer-infer from a GitHub main-branch zip (unpinned commit).
  Fix: pin every package to the versions currently deployed; pin the
  bs-roformer-infer zip to a commit SHA.
- M1. Dead server code: fetch.py:75 POT_FREE_TV_CLIENT (unused),
  :119 extract_json_object (unused), :368 parse_range_total (unused),
  :463 range_fetch (zero callers), :473 iter_pcm_from_tunnel
  url_refresher (dead — modal_app.py never passes it), :523-525 dead
  locals t0/bps/seconds_per_range + docstring still claims pacing
  (v45 removed it). modal_app.py:368-370 app_audio_url/app_audio_bitrate
  accepted and ignored while the client still sends them. Fix: delete
  the dead symbols and stale pacing docstring; either wire up or remove
  the app_audio_url plumbing on both ends.
- M3. Silent CPU fallback in both model loaders (model_demucs.py:55,
  model_karaoke.py:59) — a GPU failure silently runs on CPU while
  /health reports model_loaded: true. Fix: fail loudly if CUDA is
  unavailable, or log a warning and surface gpu: false in /health.
- M4. Replay-cache capacity counts expired entries (modal_app.py:254-279).
  Fix: prune expired keys in cache_store before the capacity check.
- M6. No receive timeout on the tunnel fetch round-trip; ffmpeg reap can
  mask errors (modal_app.py:399-419, fetch.py:397-440). Fix: wrap
  receive_json() in asyncio.wait_for(..., timeout=60); wrap proc.wait
  in try/except, prefer the original error.
- M7. video_id never validated, printed raw into logs
  (modal_app.py:510). Fix: validate ^[A-Za-z0-9_-]{11}$ (or cap length,
  strip newlines).
- M8. Deprecated FastAPI startup hook (@app.on_event, modal_app.py:620);
  /health checks little (no GPU/ffmpeg check). Fix: lifespan handler;
  add gpu_available to /health.
- M9. tools/ is gitignored but the docs tell users the CLI lives there.
  Fix: document where to download the CLI (official release URL + SHA-256).
- M10. Versioning: no defined version for the final patch. Repo's latest
  bundle is v79; v80-v83 exist only locally. Fix: one version for the
  public release (recommend v1.0.0, YouTube pin 20.40.45 explicit), build
  once from source, attach the single .rvp to a GitHub Release (not a
  committed file), tag the commit, record version + build hash in
  patch/README.md and patch/BUILD_REPORT.md.

================================================================
PART 5 — POLISH (low, fix if time, not blocking)
================================================================

- PAUSED state is dead: no setState(State.PAUSED) exists anywhere in
  KaraokeSession.java. Either set it when the mirror pauses mid-song or
  remove it.
- Seek debounce is sound (SEEK_JUMP_MS=1000, speed-scaled); forward-
  beyond-coverage correctly parks on the rebuffer veil. No change
  except the speed-fallback fix (M11).
- Video-switch debounce is a fair trade-off (3 mismatches, ~1.5s of
  old-song karaoke over a new video, original still muted). Don't shorten.
- Stale video in setup callback: KaraokeBridge.java:1900-1930 captures
  targetVideo at tap time; setup can take minutes. Re-read the video ID
  at start time instead of trusting the captured one.
- isMuted() reports the flag, not the volume (:3573-3575); snapshot()
  logs "muted=true" even when drifted. Log the read-back value alongside.
- Main-thread single-sample mute/unmute: :3618, :3680 — an async volume
  apply can fail the read-back on main-thread calls. Prefer mute/unmute
  off-main or poll without sleeping the main thread.
- Decoder polish: track.write() return unchecked + track-null race on
  stop (:849-852, :786); close() doesn't cancel an in-flight fetch
  (reader can stay blocked up to 180s after stop); fetch-response memory
  spike — 24 MB body -> ~32 MB base64 + full-frame copy in sendText()
  (~64-96 MB transient on a 256 MB heap); stop() swallows only
  IllegalStateException from track.stop(), track.release() unguarded
  (:781-786); plain fetch reads Set-Cookie case-sensitively while Cronet
  is case-insensitive; readMessage() extended-length reads mask -1 (EOF)
  as 0xFF (:305-310).
- Kotlin polish: unused import BuilderInstruction32x (:19); unreachable
  branch KaraokePlayer.java:942-950; seek fingerprint (:282, resolved
  :569) gates a method nothing uses — drop the gate or document it as an
  intentional tripwire.
- Lifecycle polish: stale "Last failure" in every log share (KaraokeLog
  .java:34,69 — setLastFailure() is the only writer; nothing clears it;
  clear on session start / onState(PLAYING)); dead KMode enum (:1002);
  "buttons present" early return doesn't rebind refs (:576-585); dead code
  in the detach watcher (final s.stop() branch unreachable); geometry
  block does a full DFS before deduping; METHOD_CACHE never cleared on
  controller switch (:282,2862); appAudioInfo() — one bad format kills
  the whole result (no per-format try/catch); pauseVideoForSetup() has no
  fallback (:1399-1418); setup error strings logged/shown verbatim.
- Security polish: /health leaks model fingerprints (return only
  {"status":"ok"}); README doesn't mention BYOK/setup; old smali
  workflow still live (.github/workflows/build-patch.yml, manual trigger,
  commits/pushes .rvp to main with contents: write — DELETE it, keep only
  build-patch-v19.yml); deploy workflows hardcode live URLs and curl
  /health into Actions logs (move URLs to variables/secrets); internal
  working notes in the repo root (second-opinion-context.txt:83,
  karaoke-patches-project-handoff.txt, karaoke-explanation-for-pi-agent.txt
  contain the live endpoint — don't publish); LOG button still shipped
  (comments say "REMOVE AFTER DEBUGGING" — remove it before public);
  KaraokeWarmup pings /health on every player open (fine under BYOK).
- Workflows: build workflows auto-push binaries to main and download
  unsigned tooling without checksums (Kotlin 2.3.0 zip, jadx 1.5.6,
  baksmali/smali 2.5.2). Actions pinned only to major tags, not SHAs.
  Fix: stop committing .rvp from Actions (artifacts/Releases instead);
  verify SHA-256 of downloads; SHA-pin third-party actions; consider
  branch protection on main.
- Tests: only test-server.yml auto-runs on push (pytest only — fine,
  keep it that way; never add a push trigger to a build/deploy workflow).
  Gaps: no test for the literal 403 path; no tests at all for setup_app.py;
  no WS-level test for bad input (missing video_id, non-dict first
  message); test_protocol.py doesn't cover the audio_url SSRF shape
  (moot once the branch is deleted), concurrent same-video requests, or
  cache eviction at capacity 3.
- Repo hygiene: docs/karaoke-patches-project-handoff.txt and
  second-opinion-context.txt are tracked in the repo — internal working
  notes, delete before public. Local-only files that must never be
  pushed: second-opinion-answers.txt, second-opinion-addendum-2026-10-26
  .txt, karaoke-explanation-for-pi-agent.txt, docs/measurements.md,
  docs/tunnel-harness-run-20261026.json/.stderr, patch/build/v*/ext-src/,
  patch/build/v80/-v83/, patch/build/v7-diag/. Add them to .gitignore.
  spikes/ is fine to keep, but spikes/README.md should state they are
  throwaway measurement scripts.

================================================================
PART 6 — VERIFIED GOOD (no action)
================================================================

- Revert clean; guard intact; decoder v12 fix intact (csd-0 OpusHead,
  csd-1 nativeLong(delayNs), csd-2 nativeLong(80_000_000); OpusTags
  parsed/skipped only). EOS drain cannot hang (hard 2s deadline).
  Codec released on normal/exception paths. Write loop exits on stop.
- Gate (normal path): mute with read-back (tolerance 0.01) -> coversSec
  -> skipToSec -> releaseHold -> PLAYING; mute refusal fails the session
  rather than playing over the original. Reconnect pre-gate guard correct.
  Song-end path unmutes instead of sitting muted. v58 self-stop fix correct.
- WS: malformed JSON / missing fields generally fail cleanly; clean-fail
  behavior correct for TCP EOF (-> onError) and WS close frame
  (-> onClosed); no reconnect by design.
- Lifecycle: double-tap K / K while CONNECTING safe; stop during
  CONNECTING/BUFFERING unwinds correctly. Buttons: double-inflate
  prevented by tag checks; BV with no session blocked with a toast, no
  crash; LOG with empty ring copies header. Warmup: no retry storm
  (one ping per new controller, 10-min gate, daemon thread, 15s timeouts,
  host-only logging). No uncaught crash path found on the main thread.
  KaraokeLog: ring bounded at 800, synchronized, hostOf() never returns
  more than the host, no call site logs a key or full URL. KaraokeGeometry:
  diagnostic-only, never mutates layout; traversals capped.
- Kotlin patch: register layouts of all 7 synthetics verified correct;
  matchOrFail and classOrFail throw PatchException naming the fingerprint;
  duplicate-patch_* check, single-RETURN_OBJECT check, p0-register range
  check all fail loudly; compatibleWith pins YouTube 20.40.45.
- Server: WS protocol shapes match the docstring and tests; 7s-window
  pipeline correct (WINDOW=308,700, HOP=231,525, EDGE=38,587; centres
  tile exactly by HOP); 403 handling raises TunnelFetchError and stops
  the song (no silent fallback anywhere in the server path); replay
  cache 3-song cap, 600s TTL enforced on read; Demucs window limit
  enforced; RoFormer loads from the SHA-256-verified registry, runs on
  the vocal stem with vocals - lead for backing, and stage-2 failure
  degrades to instrumental-only without killing the stream; logging
  hygiene good (itags/hosts only, never full URLs); setup-helper
  subprocess is injection-safe (arg list, no shell=True; token via env).
- Workflows handle GitHub secrets correctly. No auto-build or
  auto-deploy exists anywhere.

================================================================
PART 7 — COULD NOT VERIFY (stated, not assumed)
================================================================

- Whether KaraokePlayer.resume() respects the drift hold (M6).
- Whether Q() ever misreads on the current build (no phone log in scope).
- Whether KaraokeSession reuses a KaraokePlayer across songs (determines
  whether D5 is user-visible or latent); whether it calls WsClient.close()
  after onError()/onClosed(); whether it validates sample_rate before
  queueChunk().
- Whether Modal records run_deploy function arguments (the token) in
  dashboard/invocation logs; whether a fake-token /setup/start fails fast
  or only after expensive image-build work (testing it would itself spend
  the maintainer's credits — not tried); whether Modal containers expose any
  internal metadata endpoint (SSRF severity); whether /health resets
  Modal's scaledown timer; live Modal billing attribution of the
  setup-helper image build.
- Whether ReVanced Fingerprint.match() throws on multiple matches (affects
  K2 severity); whether Lmjr.a can be null at mi() exit (K3 premise);
  whether any other Laiwx method shares the short opcode runs.
- Whether the live deployed endpoint is still serving (code proves it
  WOULD serve unauthenticated requests if running; the security review
  did probe /health live from the sandbox: 200, model loaded).
- Whether the visionOS route still works end-to-end against live YouTube
  (code comment claims local verification; no test or log in the repo).
- Whether the Modal token secrets in the repo are still valid or have
  been rotated.
- The exact GPU memory headroom of Demucs + RoFormer together on T4.
- What is actually committed on GitHub — the workspace is not a git
  repo, so only local files were reviewed. The .rvp count differs
  between the repo survey (65 files / 5.27 MB) and the local tree
  (24 files). Confirm on GitHub before publishing.
- One internal check inside the code review errored (exit code 1) — the
  report was still delivered in full; treat "verified" claims with the
  usual caution and re-verify the fix list against the final build.

================================================================
PART 8 — DECISIONS NEEDED FROM THE MAINTAINER
================================================================

1. Stop the shared endpoint + setup helper NOW (before the repo goes
   public)? This is the single highest-leverage action. The maintainer's phone
   already uses the maintainer's own BYOK endpoint (maintainer workspace), so the maintainer's own
   testing still works. The only thing lost is auto-setup for NEW
   phones until the helper is hardened. The URLs are already in
   distributed bundles (v80-v83 were sent out), so stopping is the only
   thing that actually closes the hole — scrubbing the repo alone does not.
2. Final patch version: recommend v1.0.0 for the public release
   (YouTube pin 20.40.45 explicit), single .rvp attached to a GitHub
   Release, not a committed file.
3. Go ahead with the full fix list (Parts 1-4), then a fresh phone test
   (full song, seek, stop/restore, video switch, setup flow), then build
   the final patch with the same verification bar (javac clean, d8
   deterministic, CLI load, MultiDexIO probe, CLI patch run, patched-dex
   dump, synthetics intact)?
