# Karaoke patch — build report (2026-10-26)

Build task: patch bundle + improved logging, local only. No git push,
no Actions dispatch, no Modal deploy, no APK sent or uploaded anywhere,
no server/ changes, no automatic fallbacks.

## What was built

- Bundle (verified loadable by the CLI):
  `~/workspace/revance-karaoke/patch/build/karaoke-patches.rvp`
  - Patch classes at `app/karaokenow/patch/` (KaraokeFingerprints,
    KaraokePatchKt + extendWith lambdas).
  - Extension dex as `extensions/karaoke.rve` (47,268 bytes, built
    from `patch/build/ext-dex/classes.dex`).
- Patch source:
  `patch/patches/src/main/kotlin/app/karaokenow/patch/KaraokePatch.kt`
- Extension source (17 classes compiled clean):
  `patch/extension/src/main/java/app/karaokenow/extension/`
  (`patch/build/ext-classes/`, `patch/build/ext-dex/classes.dex`).
- Verification-only patched APK (LOCAL ONLY, never sent/uploaded):
  `~/workspace/yt204045-work/patched/youtube-karaoke-patched.apk`
  (31,170,073 bytes, 5,960 entries, CLI-aligned + signed).
  The repo tree never holds Google binaries; the patched APK lives
  outside the repo under `~/workspace/yt204045-work/patched/`.

## CLI verification (ReVanced CLI 6.0.0 jar, stock 20.40.45 base.apk)

`list-patches -p karaoke-patches.rvp -b` lists: Karaoke, enabled,
compatible `com.google.android.youtube` version `20.40.45`.

`patch -p karaoke-patches.rvp -b -o <out> base.apk`:
`"Karaoke" succeeded`, no warnings, all 8 dexes compiled,
resources compiled, APK aligned + signed + saved (run 3 log:
`~/workspace/yt204045-work/patched/patch-run3.log`).

### Match results — all 7 fingerprints matched

The patch throws PatchException naming the fingerprint on the FIRST
miss, so a successful run means every fingerprint below resolved.
(None failed; no fallback hook was tried — there is none.)

| Fingerprint | Target (verified in patched dex) |
|---|---|
| videoId | Laiwx.p() |
| videoPosition | Laiwx.e() (custom check: invokes player long "x", not buffered "w") |
| playerVolume | Laiwx.as(F,Z)V (mute goes through synchronized wrapper L(F)V) |
| seek | Laiwx.H(JLbcrd;)V (resolved as strictness gate; v1 does not call seek) |
| playingState | Laiwx.Q()Z (playWhenReady intent; drift loop covers stalls) |
| controlsInflate | Lmju.C()V (custom check: >=2 ViewStub.inflate() calls) |
| controllerEntry | Laiwx.aB(...)Lajcb; (entry point for the controller handoff) |

### Injection wired (all confirmed by dumping the PATCHED dex)

- Laiwx synthetics (PUBLIC, with bodies verified):
  `patch_getVolume()F` → `a()F`;
  `patch_setVolume(F)V` → `L(F)V`;
  `patch_getPosition()J` → `e()J`;
  `patch_isPlaying()Z` → `Q()Z`;
  `patch_getVideoId()String` → `p()String`.
  No `patch_seek` in v1 (user seeks; karaoke follows via position
  jumps). Duplicate-`patch_` guard throws instead of double-adding.
- Controller handoff: Laiwx.aB() entry, pc 0:
  `invoke-static {v10}, KaraokeBridge->setController(Object)V`
  (aB is PUBLIC|FINAL, not static, so p0 = this = v10).
- Button hook: Lmju.C() exit, immediately before return-void:
  `move-object/16 v0, v22` →
  `iget-object v0, v0, Lmju->o:View` →
  `invoke-static {v0}, KaraokeBridge->initializeButton(View)V`.
  C() has exactly one RETURN_VOID (enforced; any other count stops
  the patch instead of guessing the exit position).
- Extension classes present in the patched APK: KaraokeBridge
  (classes8.dex), KaraokeSession (classes4), KaraokePlayer (classes3),
  KaraokeWsClient (classes2), KaraokeLog (classes3), KaraokeSettings
  (classes2).
- Bridge behavior (runtime, unverified on a phone): setController
  stores the controller; initializeButton resolves
  `controls_button_group_layout` then `player_control_button_wrapper`
  BY RESOURCE NAME (never a numeric ID), falls back to the group
  itself with a warning log, guards duplicates with a tag, never
  throws (a button failure must never crash the player UI thread).
  Tap toggles: active session → stop; no controller → "Karaoke not
  ready yet"; unreadable video ID → "Karaoke could not read the
  video"; else start a session (mute FIRST, then skip to video
  position, then release hold — the mute-first gate).
- MuteController (bridge → synthetics): mute saves `a()F`, calls
  `L(0.0f)`; unmute restores the saved value. Player-internal only:
  never system AudioManager mute, never `N(F)V`.
- VideoPosition: live `e()` on every read, never a cached time;
  isPlaying from `Q()` plus position movement; speed returns 1.0f
  (no player speed source identified — see unverified items).

## Logging (user requirement, 2026-10-26 07:13)

- One tag everywhere: `KaraokeNow`
  (filter: `adb logcat -s KaraokeNow`). New `KaraokeLog.java`
  (d/i/w/e + `hostOf(url)` + `crash(where, snapshot, throwable)`
  that logs the snapshot + full stack trace and never throws itself).
- Session (KaraokeSession): every state transition (`old -> new`,
  videoId); start (videoId + WS host); mute result at first audio;
  skip target; `onHeader` (sampleRate/coldStart/cached); every chunk
  (seq/startSec/opusBytes/bufferedSec); done (chunk count); stop;
  `fail(...)` logs `session FAIL: <message> | <snapshot>` BEFORE
  stopping; `snapshot()` =
  `state= videoId= lastChunkSeq= lastVideoPosMs= ws= muted=
  bufferedSec= audioPosSec=` (every piece guarded, never throws).
- WS (KaraokeWsClient): connect (host + videoId); handshake failure
  (status line / bad accept key) and success; close; every fetch
  request (id + method + HOST ONLY) / response (id + status +
  bytes) / error (id + error); header, chunk, done, server error,
  unexpected message type; reader failures WITH trace before
  `onError`; new `isOpen()` for snapshots.
- Decode/playback (KaraokePlayer): decoder created per chunk seq;
  chunk decoded (seq/startSec/frames); empty-PCM drops as warnings;
  AudioTrack creation (rate/minBuf/bufferBytes/held state);
  pause/resume transitions only; speed-factor changes only;
  skip (`from -> to` sec); stop; reorder starve warnings
  (`chunk gap, skipping missing seq=N after 2000ms`);
  `track.pause()/play()/stop()` IllegalStateExceptions as warnings.
- Drift loop (syncTick): transition-only pause/resume mirroring;
  seek detection (`expected Xms, got Yms -> skip karaoke to Yms`);
  drift corrections with ms values (hold / hold-release / hard-seek /
  speed nudge with factor + video speed). In-band ticks stay silent
  (no 500 ms spam).
- Crash rule: ANY exception on karaoke threads logs a state
  snapshot + full stack trace, then the session stops cleanly.
  Previously swallowed paths now log: connect-thread catch,
  sync-tick catch (was `catch (Exception ignored)`), stopInternal
  unmute catch (was silent — could leave the user muted invisibly),
  write-loop exception, player errors via new
  `KaraokePlayer.Listener.onPlayerError`.
- BEHAVIOR CHANGE (flagged, not silent): a decode failure or a
  write-loop failure now FAILS the session cleanly instead of
  vanishing silently (previously the reorder timeout skipped the
  chunk and playback continued). A single corrupt chunk now ends
  the song instead of being skipped silently — per the
  stop-cleanly rule. If the maintainer wants skip-and-continue instead,
  that is a decision, not a silent revert.
- NEVER logged: the BYOK key, full URLs (googlevideo URLs can carry
  tokens) — video ID and host only. Logcat only: no new
  permissions, no network calls for logs.
- Patch-side: a null fingerprint match fails loudly naming the
  fingerprint (PatchException, no fallback, no silent continue).
- Pre-existing compile error found + fixed (extension had never
  been compiled before): `KaraokeWsClient.connect()` called
  `JSONObject.put(...)` (checked JSONException) without declaring
  it — now wrapped into IOException. Also: `javac -encoding UTF-8`
  required under this shell's US-ASCII locale (sources are UTF-8;
  Gradle defaults to UTF-8, so this is a sandbox detail).

## Confirmed vs assumed (template + CLI jar)

CONFIRMED (javap against revanced-cli-6.0.0-all.jar + compiler
probes + the successful patch run):
- `compatibleWith(Pair<String, Set<String>>...)` exists; the Pair
  form is used (`"com.google.android.youtube" to setOf("20.40.45")`).
  The old vararg form may have meant two packages — not used.
- `extendWith(String)` exists; the `.rvp` zip carries the dex as
  entry `extensions/karaoke.rve` (template convention, proven by
  `list-patches` accepting the bundle and the patch run merging
  the extension classes into the APK).
- `execute {}` and `apply {}` are EQUIVALENT for bytecode patches
  (apply wraps: addExtension first, then the user block).
- Synthetic-method mechanism: `MutableClassDef.methods` is a
  mutable set — `.add()` works (RVX pattern confirmed by reading
  inotia00 VideoInformationPatch.kt directly); this build uses the
  simpler reflection variant (extension never names obfuscated
  methods; patch adds synthetics; bridge calls them by reflection
  with `getDeclaredMethod` + `setAccessible`, cached).
- `fingerprint(int, ...)` int arg = `fuzzyPatternScanThreshold`;
  0 = strictest, used for all seven fingerprints.
- Custom-block AND semantics: `custom { method, classDef -> Bool }`
  runs in addition to opcode/return/param checks (proven by the
  videoPosition `d()` vs `e()` distinction and the controlsInflate
  >=2-inflate check, both of which matched only with the custom
  block in place).
- Patcher resolution API is CONTEXTUAL: the execute block is a
  contextual scope; `Fingerprint.match(classDef)` (throwing) +
  `Match.method` / `Match.classDef` is the public path.
  `matchOrNull()` (no-arg) is INTERNAL and uncallable from patch
  code; `getMethodOrNull`/`getClassDefOrNull` do not resolve from
  Kotlin patch code; `matchOrNull$patcher` is not visible to
  Kotlin. Local helpers inside the execute block do NOT inherit
  its context — resolution lives in top-level functions declared
  `context(ctx: BytecodePatchContext)`. The execute block takes
  NO explicit parameter (`it` does not exist there).
- `MutableMethod(immutable)` constructor works;
  `MutableMethod.toMutable` does NOT resolve from Kotlin patch
  code — do not use it.
- InstructionExtensions API in the jar matches the RVX-used API
  exactly (addInstructions/addInstruction/replace/remove).
- Template (cloned for reference only, NOT built, nothing
  published): `~/workspace/revance-karaoke-template-ref`.
  Its ExamplePatch uses `bytecodePatch { compatibleWith(...);
  extendWith("extensions/extension.rve"); execute {} }` and its
  patches plugin is `app.revanced.patches` 1.0.0-dev.5 (requires
  GitHub Packages registry auth). The template Gradle build was
  NOT run: the plugin version may expect a newer patcher API than
  CLI 6.0.0, and the local Gradle daemon is known-dead for Android
  builds. Manual build (kotlinc + javac + d8 + zip) chosen instead
  and proven by the CLI run. The template Gradle build remains an
  open alternative, not a silent workaround.

ASSUMED / DELIBERATE CHOICES (recorded, not hidden):
- kotlinc 2.3.0 + `-Xskip-prerelease-check`: the CLI jar's patcher
  classes were compiled by a PRE-RELEASE Kotlin, which kotlinc
  2.3.0 refuses by default; the flag is the compiler's own
  documented suppression. The APIs used are stable Java-visible
  signatures verified via javap, and the runtime patch run is the
  real gate (it passed). kotlinc 1.9.25 cannot read the jar's
  Kotlin 2.2/2.3 metadata at all. Compilers live in /tmp
  (ephemeral — re-download if lost).
- `-jvm-target 17` (default 1.8 cannot inline JVM-17 bytecode from
  the CLI jar) and `-Xcontext-parameters` (contextual API).
- The legacy `Fingerprint`/`Match` APIs are DEPRECATED in the jar
  ("use the matching APIs instead") — warnings only; the legacy
  API still works and the patch run proves it. A future migration
  to the new matching API is possible, not required.
- Extension built with javac + d8 directly (not the template
  Gradle build) — see above.

## Failures during this build (all diagnosed, none papered over)

## Fix 2026-10-26 — Manager EmptyMultiDexContainerException

- Symptom (maintainer's phone): Manager loading the bundle failed
  with `EmptyMultiDexContainerException` on `patches.jar` —
  Manager reads the bundle as a multidex container and found
  no dex inside.
- Root cause: the manual build packed the patch classes as
  JVM `.class` files only. The desktop CLI runs `.class`
  files fine, which is why the CLI test passed — but Manager
  on Android needs the patch code as dex. The standard
  template build dexes patch classes with d8 (buildDexJar)
  and injects `classes.dex` into the jar; the manual build
  dexed only the extension, not the patch classes.
- Fix: `patch/build/karaoke-patches-fixed.rvp` = original
  bundle + `classes.dex` at root (d8 from
  `patch/build/patch-classes`, CLI jar as --lib; dexdump
  confirms KaraokeFingerprints + KaraokePatchKt inside).
  CLI list-patches still loads it (Karaoke, enabled).
  No patch logic changed — packaging only.

## v2 (2026-10-26) — bottom buttons + failure visibility

Bundle: `patch/build/karaoke-patches-v2.rvp` (build dirs under
`patch/build/v2/`; the v1 fixed bundle is untouched). Same
manual build (kotlinc 2.3.0 + javac + d8 + zip) with
classes.dex at the bundle root.

### Task 1 — buttons beside fullscreen (phone screenshot)
- Root cause of the v1 look: a `Button` widget (platform grey
  background) appended to the center `player_control_button_
  wrapper` — a big KARAOKE box over the video. Removed.
- New placement: patch hooks `Lmjr.mi()` exit (new
  `bottomUiInflate` fingerprint: returns Object, opcode run
  iget/invoke-virtual/iget/if-nez + custom check for a call to
  `Lmju.C()V`; mi() has exactly one RETURN_OBJECT) and hands
  `Lmju.r` (bottom UI) to `KaraokeBridge.
  initializeBottomButtons`. Injected code uses dead register
  v1 as scratch and never clobbers v0 (the return value) —
  verified in the patched dex (pc 121-128).
- Bridge anchors, both BY RESOURCE NAME: primary
  `bottom_end_container` (the horizontal row in the bottom UI
  immediately left of the fullscreen button); fallback is the
  `fullscreen_button`'s own parent with a copy of its
  LayoutParams (+ best-effort constraint tweak). No anchor →
  no buttons + a log line, never a misplaced button.
- Buttons are TextViews (white text, transparent background,
  14sp, no grey box): "K" (karaoke start/stop; long-press
  shares the log) and "BV" (background-vocals toggle) beside
  it, in that order. Old `Lmju.C()` center-group hook and
  `initializeButton` are fully removed (0 refs in patched dex).
- Portrait vs fullscreen: NOT proven on a phone (no phone
  here). Static evidence: both anchor ids exist in the bottom
  UI layout (QYO); if fullscreen's bottom UI lacks them, the
  bridge logs which path was taken and skips. the maintainer's retry
  is the proof.
- BV semantics (honest): toggles a real flag (bridge +
  session field + settings at session start) and logs; v1
  audio is UNCHANGED — stage 2 (second model + backing
  stream) is not built and nothing fakes it.

### Task 2 — "failed to start" visibility (no PC needed)
- Toasts now carry the reason: tap failures →
  "Karaoke failed to start: <reason>"; session failures →
  "Karaoke failed: <reason>. Hold K for log". The old
  "check logcat" string is gone.
- Phone log route: KaraokeLog keeps a 200-line ring buffer
  (+ last failure); long-press K opens the system share
  sheet with the log text (ACTION_SEND, no permissions, no
  adb, no root). Logcat tag unchanged.
- Mute gate hardened (clear bug class, not a guess): the old
  setMuted trusted the set call; a silent no-op would have
  played BOTH audios (forbidden). It now reads the volume
  back and refuses unless it is ~0 (tolerance 0.01); unmute
  mismatch logs loudly and reports false.
- No root cause claimed for his failure. Ranked UNVERIFIED
  candidates (see parent report): server player-parse
  failure, WS handshake/connect failure, first-chunk decode
  failure, mute-call throw, null video ID, AudioTrack
  creation failure. His retry's toast + shared log will
  identify it.

### v2 verification (patched dex dumped, not just CLI line)
- CLI list-patches loads v2 (Karaoke, enabled).
- MultiDexIO.readDexFile probe PASSES on v2 (20 classes).
- CLI patch run on stock 20.40.45: "Karaoke" succeeded —
  all 7 fingerprints matched, including bottomUiInflate.
- Patched-dex dump: mi() hook present (pc 121-128, return
  value intact); all 5 synthetics present; all 6 extension
  classes present; initializeButton refs = 0.
- Verification-only patched APK stays local at
  ~/workspace/yt204045-work/patched-v2/ (never sent).

1. Run 1 (String injection): the patched dex showed ONLY the
   `invoke-static` landed in Lmju.C() — the `iget-object` line of
   the two-line smali String was silently dropped by the String
   parser (the run-1 log showed only a warning:
   `[5,0] Invalid register: v22`). The patch still reported
   success, so a verification-only dex dump caught what the CLI
   did not. Lesson: ALWAYS dump the patched dex; never trust the
   "succeeded" line alone for multi-instruction injections.
2. Run 2 (BuilderInstruction22c): failed LOUDLY with
   `PatchException: Invalid register: v22. Must be between v0 and
   v15, inclusive.` at `BuilderInstruction22c.<init>` — this
   diagnosed the real cause: format 22c packs BOTH registers into
   one byte (4 bits each), so C()'s p0 (= v22, registerCount 23)
   cannot appear in a 22c instruction at all. Fix (run 3):
   `move-object/16 v0, v22` (format 32x, 16-bit registers) then
   `iget-object v0, v0, field` (both registers low).
3. Run 2 CLI behavior warning: despite the patch FAILING, the CLI
   still compiled dexes and SAVED an APK. That file was
   overwritten by run 3 and must never be trusted: a saved APK
   proves nothing — only the `"Karaoke" succeeded` line plus a
   patched-dex dump prove the patch.
4. Run 2's APK is NOT the delivered artifact; run 3's APK (fresh
   extraction, fully re-dumped) is the verified one.

## What remains UNVERIFIED (phone-gate + later items)

- Muted video + own audio in sync through pause, seek, and a full
  song on the test phone; no audible drift; never both audios.
- Button-group hook on a real watch page (portrait + fullscreen);
  the bridge's by-name lookup and fallback paths are code-only.
- `e()` frame-accuracy through pause/seek/speed; `Q()` is
  playWhenReady intent, not proven frame advance (drift loop +
  HOLD cover stalls).
- Speed source: no Laiwx speed getter identified; bridge assumes
  1.0x (logged once). If the user changes speed, karaoke drifts
  until a speed source is found.
- Ad breaks (does position freeze? different controller?);
  Bluetooth offset value; whether the muted player still downloads
  original audio (affects the ~7–12 MB/song data math).
- Shorts-preload video-ID behavior (probe item from RVX
  cross-check); the `an()`→player delegation chain (untraced,
  documented as wrapper not role); `Lbcrd` downstream null
  tolerance (prefer a real constant if seek is ever wired);
  the boolean arg semantics of `as(F,Z)V` beyond observed branches.
- Start-offset protocol extension (PROPOSED, not built — would fix
  the ~8s mid-song tap wait); RoFormer (Phase 2); stage-2
  backing-vocal split; BYOK wiring (v1 uses the default endpoint
  with null key; no settings UI yet).
- Manager alternative-sources manifest schema (publishing TODO in
  the skeleton's notes; the ReVanced-form `patches-bundle.json`
  schema IS verified from a live example, Manager 2.x validation
  internals are not).
- The phone-side WS tunnel handler itself (server tunnel mode was
  proven only with the local phone simulator + stub separator).

## v3 (2026-10-26) — CC-style active buttons, alignment, LOG button, video-display verdict

Bundle: `patch/build/karaoke-patches-v3.rvp` (build dirs under
`patch/build/v3/`; v2 untouched). Same manual build
(kotlinc 2.3.0 + javac -encoding UTF-8 + d8 + zip) with
classes.dex at the bundle root. Patch source UNCHANGED
(mi() hook + bottomUiInflate fingerprint already correct);
all changes are in the extension (KaraokeBridge.java).

### Task 1 — CC button research (what "fills with white" means)
Sources (web, 2026-10-26): on mobile, CC OFF = outlined
letters only (transparent fill); CC ON = the button turns
white — the whole button face fills white. Desktop differs
(red underline) and was NOT copied. Replication for K/BV:
inactive = white text on transparent background; active =
white-filled rounded background (8dp corner radius) with
black text. Implemented explicitly in setButtonActive():
the old setActivated() calls showed nothing, because no
state-list drawable ever changed the visuals.

### Task 2 — spacing + alignment (root cause, from the real layout)
Layout evidence (stock 20.40.45, xmltree res/QYO.xml =
youtube_controls_bottom_ui_container): the fullscreen
button's ViewStub is 60x60dp, bottom-anchored to the
seekbar frame; bottom_end_container (the row our buttons
go into) is a horizontal LinearLayout, wrap x wrap,
bottom-anchored to the SAME frame. The v2 bug: our
buttons were WRAP_CONTENT tall (~28dp of 14sp text +
padding), so their center sat ~16dp BELOW the fullscreen
icon's center — exactly the low, uneven look in the
screenshot (grounding: K/BV y=240 vs fullscreen y=230).
Fix: buttons are now 60dp tall (the fullscreen height)
with CENTER text gravity, so centers coincide; 12dp
horizontal padding + 4dp side margins give even ~32dp
text-to-text gaps (K->BV, BV->LOG, LOG->fullscreen's
~18dp internal icon padding). Fallback path hardened too:
it now also copies the anchor's bottom constraint
(bottomToTop), so a fallback button is bottom-aligned
with fullscreen instead of floating to the parent top
(the old code copied only the horizontal constraint and
left fallback buttons vertically unanchored), and forces
WRAP_CONTENT width (a 60dp-wide copy left huge gaps
around "K").

### Task 3 — video display verdict: NOT patch-caused (evidence)
- Measured from the user's screenshot (921x2048): video
  content spans x=187..733 (546px wide, 59% of the
  921px player width), content aspect ~1.3-1.45:1 — NOT
  16:9. A 16:9 video would span the full player width.
  The player fit-scales a non-16:9 video (this STAY video
  is a squarish static-art video), producing the black
  side bars. That is the video's own aspect ratio.
- The patch cannot resize the video: it only adds
  TextViews into bottom_end_container (an overlay row)
  or before fullscreen_button in its parent — both inside
  the bottom-UI overlay (QYO). It never touches the video
  surface, the player view's params, or the overlay root's
  size, and the fallback tweak touches ONLY the new
  view's params (never the anchor's or container's).
  Overlay children cannot resize a sibling surface.
- Corroboration: the v1 screenshot (a 16:9 video) filled
  the player width with the same patch approach — the
  display difference tracks the VIDEO, not the patch.
- Root-height check: the bottom-UI root is wrap_content,
  but its extent is already set by the 60dp fullscreen
  button + seekbar frame on the same bottom anchor; a
  60dp row on that anchor does not extend it.
- NOTHING was changed to "fix" framing, because nothing
  the patch does affects it. If the user compares the
  same video in stock YouTube, it will show the same
  pillarboxing.

### Task 4 — temporary LOG button (DEBUG-ONLY)
- "LOG" beside BV (order K, BV, LOG, then fullscreen).
  Tap -> ClipboardManager copies KaraokeLog.dump()
  (last failure + recent ring lines) -> toast "Log
  copied". Same small text style as K/BV. Marked
  DEBUG-ONLY in code (comment + karaoke-log-button-debug
  tag): delete makeLogButton, its call sites, and the tag
  checks to remove it. Long-press K share sheet is kept.
- BV semantics unchanged and honest: toggles the real
  flag + fills white when on; v1 audio unchanged (stage 2
  adds the backing stream). K fills white while a session
  is alive (CONNECTING/BUFFERING/PLAYING/PAUSED) and goes
  plain on stop/error — refreshed from onState/onError/
  tap paths, always posted to the main thread (session
  callbacks arrive on karaoke threads; views must only be
  touched on the main thread). Button refs are
  WeakReferences so the static bridge never leaks the
  player Activity.

### v3 verification (patched dex dumped, not just CLI line)
- CLI list-patches loads v3 (Karaoke, enabled).
- MultiDexIO.readDexFile probe PASSES on v3 (20 classes)
  — the Manager fix is preserved (classes.dex at root).
- CLI patch run on stock 20.40.45: "Karaoke" succeeded.
- Patched-dex dump (dex.py, trusted decoder): mi() hook
  present at pc 121-128 (iget Lmju.r, invoke-static
  initializeBottomButtons, return-object intact); all 5
  synthetics present; new methods present
  (copyLogToClipboard, refreshKButton, refreshBvButton,
  setButtonActive, makeLogButton, shareLog); old
  initializeButton refs = 0; "karaoke-log-button-debug"
  string present in the extension dex.
- Verification-only patched APK stays local at
  ~/workspace/yt204045-work/patched-v3/ (never sent).
- NOT proven here (no phone): actual portrait +
  fullscreen rendering of the new alignment, and the
  CC-fill appearance on a real screen. The user's retry
  is the proof.

## v4 (2026-10-26) — alignment nudge, video verdict, hostOf fix

### Task 1 — measured alignment (pixels, not assumptions)
- Measured on screenshot 4129 (921x2048), rows 500-600:
  timestamp glyphs x35-139; K x540-555; BV x625-659;
  LOG x731-787; fullscreen icon x859-883. Text rows
  538..557 (center 547.5); fullscreen icon rows 548..572
  (center 560.0); timestamp rows 550..568 (center 559.0).
  => Buttons' text sits ~12.5px (~5dp) too HIGH vs both
  the fullscreen icon and the timestamp.
- Horizontal gaps are EVEN (K->BV 69px, BV->LOG 71px,
  LOG->fullscreen 71px): spacing is fine, do NOT change
  horizontal params. The complaint is vertical only.
- Root cause from stock bottom-UI layout res/QYO.xml
  (ConstraintLayout root): bottom_end_container is a
  horizontal wrap_content LinearLayout with paddingBottom
  3dp, bottom-anchored to the seekbar frame
  (quick_actions_container), right-anchored left of the
  fullscreen stub's inflated id. The fullscreen ViewStub
  is 60x60dp on the same bottom anchor. So: row height =
  60dp + 3dp padding = 63dp; CENTER_VERTICAL centers our
  60dp buttons in the CONTENT area, ~3dp high; plus the
  text glyph's ink center sits ~4px above its font-box
  center (includeFontPadding=false, CENTER gravity centers
  the font box, not the ink). BOTTOM gravity alone would
  NOT fix it (children still sit inside the padding).
- Fix (KaraokeBridge.alignButtonsToFullscreen, extension
  only — patch source untouched): after layout, and on
  every bottom-UI layout change (covers late fullscreen-
  stub inflation), align each button's GLYPH center to
  the fullscreen icon's center via translationY, computed
  from layout coords only: glyphCenter = parentScreenY +
  getTop() + getBaseline() + (inkTop + inkBottom)/2 with
  ink from Paint.getTextBounds(); fsCenter =
  fsParentScreenY + (fsTop + fsBottom)/2; each view's
  screen Y corrected by subtracting its own translationY,
  so re-running sets the same value instead of
  accumulating. Only writes (and logs) when the value
  changes >0.5px. Touches ONLY our own views — never the
  anchor, never the container. Skips + stays silent when
  the fullscreen view is missing or not yet laid out
  (height 0 covers the uninflated stub, whose id differs
  from the inflated fullscreen_button id). A diagnostic
  log line (delta, row height, bottomUi height) is emitted
  on each real correction, so the next retry's log shows
  the layout numbers. rowParams gravity left at
  CENTER_VERTICAL (the nudge corrects the rest); the
  fallback path got the same post + layout listener.
- The user's log confirms the PRIMARY path ran ("bottom
  buttons added via bottom_end_container row"), so the
  fallback was NOT the cause.

### Task 2 — pushed-down video verdict: NOT patch-caused
- Geometry: bottom bar is IDENTICAL in both new shots
  (title grounding y 284 vs 285 -> 581 vs 584 px, within
  noise; content resumes at y=600 in both; 4129 seekbar
  red rows at y=612-615). The page below the player did
  not move.
- Video top differs: 4129 content rises from y~135
  (brightfrac 0.045 at y=135, climbing gradually — a dark
  night scene); 4130 is EXACTLY 0.000 (pure black, no
  video noise) from y=85 through y=235, content starting
  y~245. A true video frame, even a black one, shows
  compression noise, not 150 consecutive rows of exact
  zero — so 4130's top region is genuinely empty layout,
  not video pixels.
- Decisive inversion: in 4129 (buttons VISIBLE, row
  present) the video geometry is normal-ish; in 4130
  (controls HIDDEN, buttons gone) the video is shifted.
  The shift appears when the patch's views are ABSENT —
  so the patch's views cannot be causing it.
- Hierarchy: the patch only adds TextViews into the
  bottom-UI overlay row (or before fullscreen_button in
  its parent), plus read-only player calls (position /
  videoId / playing-state reads) and volume writes. It
  never touches the video surface, the player view's
  params, or the overlay root's size. Overlay children
  cannot resize or shift a sibling surface (the surface
  is sized by the player, not by overlay content). The
  bottom-UI root is wrap_content, but its extent is
  already set by the 60dp fullscreen button + seekbar
  frame on the same bottom anchor; a 60dp row on that
  anchor does not extend it. Nothing the patch adds is
  measured or visible when controls auto-hide (the whole
  bottom UI hides together), and the patch has no code
  in the controls hide/show path.
- Remaining explanations: a YouTube player state at that
  moment (mid-transition frame, swipe/scroll gesture
  state, or the Premium-sheet state) or the video frame
  itself — not the overlay. What would prove otherwise:
  a stock-vs-patched comparison of the same video/moment.
- Nothing was changed to "fix" framing, because nothing
  the patch does affects it. Do NOT "fix" video framing
  by touching the video surface or player params — the
  patch owns no such code; any framing fix must come
  from removing a layout disturbance, and none was found.
  (Earlier v2 "pillarboxing" was likewise the STAY video's
  own squarish aspect, not the patch.)

### Task 3 — hostOf log-display bug
- User's log showed "WS connect: host=unknown-host" and
  "session start: wsHost=unknown-host" although the
  handshake returned 101 ok. Root cause: KaraokeLog.
  hostOf() used java.net.URL, which does NOT support the
  wss scheme (throws "unknown protocol: wss") -> fell
  through to "unknown-host". That matches the evidence
  exactly: fetch-request logs showed host=www.youtube.com
  correctly (https parses fine); only the wss endpoint
  URL failed. Connection itself worked; log accuracy only.
- Fix: scheme-agnostic string parsing (find "://", take
  up to the next /, ?, or #, strip any :port). Returns
  "unknown-host" only when truly unparseable. The
  java.net.URL import was removed; the v4 extension dex
  contains zero refs to java/net/URL.

### v4 verification (patched dex dumped, not just CLI line)
- CLI list-patches loads v4 (Karaoke, enabled).
- MultiDexIO.readDexFile probe PASSES on v4 — the
  Manager fix is preserved (classes.dex at bundle root);
  v1 control bundle still throws
  EmptyMultiDexContainerException (probe discriminates).
  Probe script now lives durably at
  ~/workspace/probe-multidex/Probe2.java (the /tmp copy
  was wiped; note the CLI jar relocates dexlib2 to
  com.android.tools.smali.dexlib2, not org.jf.dexlib2).
- CLI patch run on stock 20.40.45: "Karaoke" succeeded.
- Patched-dex dump (dex.py): mi() hook present at pc
  121-128 (iget Lmju.r, invoke-static
  initializeBottomButtons, return-object intact); all 5
  synthetics present (patch_getVolume body verified:
  delegates to a()F); extension dex contains
  alignButtonsToFullscreen + initializeBottomButtons +
  copyLogToClipboard + new layout-listener/post lambdas.
- Bundle: patch/build/karaoke-patches-v4.rvp (57,684
  bytes; extension rve grew 58,368 -> 61,624 bytes with
  the align code; patch classes byte-identical to v3).
  Verification-only patched APK stays local at
  ~/workspace/yt204045-work/patched-v4/ (never sent).
- NOT proven here (no phone): actual on-screen
  rendering of the nudged alignment, portrait +
  fullscreen, controls shown AND hidden. The user's
  retry is the proof. The new alignButtons log lines
  will show the layout numbers on his retry.
- Still OPEN (not v4): the server "Processing failed.
  Retry." error from his pasted log (tunnel flow failing
  server-side after a successful 200 player fetch of
  5545 bytes) — needs separate server-side diagnosis;
  server code was out of scope for v4.

## v5 (2026-10-26) — true video ID (fixes every session failing)

Bundle: `patch/build/karaoke-patches-v5.rvp` (58,135 bytes;
build dirs under `patch/build/v5/`; v4 untouched).
Patch source ONLY — the extension dex is reused
byte-identical from v4 (61,624 bytes), so v4's
alignButtonsToFullscreen + hostOf fixes are intact
(confirmed: alignButtonsToFullscreen present in the
patched dex). No server changes, no push, no deploy.

### The bug (proven before this build, not re-litigated)
v1-v4 read the video ID via Laiwx.p(), which returns
Laiwx.w — a 16-character per-playback token (chain:
w <- Lajcf.a <- Laivf.h), NOT a video ID. Every
session asked YouTube for a nonexistent video, hence
"Playability: ERROR This video is unavailable" on
every tap. The server player request itself was proven
fine (identical request + real 11-char ID -> OK).

### The new source: Lajcf.j() (VideoStreamingData.e)
patch_getVideoId()String now runs, on the controller
the bridge already holds:
  iget Laiwx.i (Laiwl) -> iget Laiwl.l (Lajcf, the
  volatile current playback state) -> Lajcf.j()String
  -> return. (Laiwl.l is the current state object:
  p() itself reads it in its other branch, and at()
  reads it as the current Lajcf.)
j() returns VideoStreamingData.e, which is set at
player-response parse time from Layhu.f, where Layhu
is the player response's video-metadata proto block
(protobuf-lite dynamicMethod, BUILD_MESSAGE_INFO).
Evidence j() is the stable video identity, not a
title or a token:
- It is RESPONSE-derived (parsed from the player
  response), not client-generated like the token.
- It is used as a lookup key: at() passes j() to
  Laipm.b(String) to release a per-video playback
  resource; Lajaa.a passes it as the identifier into
  a playback component (Lajbb).
- It is stored as the snapshot's id field (Laito.g)
  right next to the token (Laito.f) — two identity
  fields, token + id.
- Lajgh.i() asserts j() equality between old and new
  playback states, alongside token equality and
  streaming-data equality — j() is a state-identity
  key. Titles are displayed, never used as lookup or
  identity keys.
- aB's first String parameter was RULED OUT first:
  all three aB callers pass Laivf.h (the same token),
  so capturing it would have shipped the same bug.
  Laivf has exactly one String field (h); Laiuv has
  none. The ExoPlayer MediaItem route was ruled out
  too: no MediaItem class exists in any dex and the
  ExoPlayer interface here has no media-item accessor
  (YouTube's fork, obfuscated single-letter methods).
- If Laiwl.l is null, the synthetic throws inside
  itself; the bridge catches it and reports "Karaoke
  could not read the video" — clean fail, NO fallback
  to any other source (standing rule).

### Build slip caught by the dex dump (not the CLI line)
The first v5 build's patch_getVideoId called
`Laiwx->j()String` — the invokeVirtual helper
hardcodes Laiwx as the defining class, but j() lives
on Lajcf (Laiwx has no j()String, only j(J)J). The
patcher accepted it, but ART would have thrown
NoSuchMethodError at runtime on every tap. The
patched-dex dump caught it. Fix: new invokeVirtualOn
helper taking the defining class; the synthetic now
calls `Lajcf->j()String` (verified in the dump).
Lesson: a synthetic's method reference must name the
real owner class, not just the right method name.

### v5 verification (patched dex dumped, not just CLI line)
- CLI list-patches loads v5 (Karaoke, enabled).
- MultiDexIO.readDexFile probe PASSES on v5;
  v1 control bundle still throws (probe discriminates).
- CLI patch run on stock 20.40.45: "Karaoke" succeeded.
- Patched-dex dump: patch_getVideoId body is exactly
  iget Laiwx.i -> iget Laiwl.l -> invoke-virtual
  Lajcf.j()String -> return-object; all 5 synthetics
  present; mi() hook at pc 123-128; aB entry hook at
  pc 0 (setController); all 6 extension classes
  present; alignButtonsToFullscreen present (v4 intact).
- Verification-only patched APK stays local at
  ~/workspace/yt204045-work/patched-v5/ (never sent).
- NOT proven here (no phone): that j() returns an
  11-char ID on a real watch page. The phone test is
  the proof: the session-start log line must show an
  11-char videoId, and tapping K must get past
  CONNECTING (no more "This video is unavailable").

## v6 (2026-10-26) — portrait video-position DIAGNOSTICS + warm-up ping

Bundle: `patch/build/karaoke-patches-v6.rvp` (61,208 bytes;
build dirs under `patch/build/v6/`; v5 untouched). Extension
ONLY — patch source unchanged, so the patch-classes dex is
byte-identical to v5 (27,892 bytes) and all v5/v4 behavior
is intact (videoId via Lajcf.j, alignButtons, hostOf).
Extension dex grew 61,624 -> 68,044 bytes (two new classes).
No layout changes, no behavior changes except the warm-up
ping below. No push, no deploy, no server changes.

### Task 1 — geometry logging (numbers, not a fix)
New `KaraokeGeometry.java`, called from KaraokeBridge:
- BEFORE block at initializeBottomButtons entry (before any
  addView) and AFTER block via post once layout settles, in
  BOTH anchor paths; a block on every later bottom-UI
  layout change (same listener as alignButtons); a block
  at controller set (bottom UI may not exist yet — that
  ordering is itself data) and at session start.
- Each block (change-only dedupe: repeats with identical
  numbers stay silent, so no loop spam; ring holds ~15
  lines per block): screen px + density + orientation
  (portrait/landscape); decor rect + window visible
  display frame; EVERY SurfaceView/TextureView under the
  decor view with rect + parent class (DFS, capped at
  5000 views); the ancestor chain from the bottom UI up
  to the decor view (class + rect per level — this shows
  whether the bottom row is an overlay child or a sizing
  sibling of the surface); bottom-UI rect + visibility /
  isShown / alpha / translationY (the controls shown-vs-
  hidden proxy); bottom_end_container, fullscreen_button,
  and K/BV/LOG rects (by resource name / tag, as before).
- The decisive experiment is the BEFORE/AFTER pair: if
  the surface rect is identical before and after our
  buttons are added, the patch cannot be moving it.
- Video-pixel size is NOT logged (deliberate skip, not an
  oversight): this build's ExoPlayer interface has NO
  video-size accessor — verified in the stock dex, no
  VideoSize type exists in any dex and the interface
  exposes only 16 methods, none size-related. A video-size
  synthetic would be a guess, so none was added. Surface
  aspect vs screen aspect from the block still separates
  a layout shift (the surface itself moved) from video
  letterboxing (surface full, bars inside the video).
- No keys, no full URLs (rects + class names only).

### Task 2 — warm-up ping (cold-start fix, from the plan)
New `KaraokeWarmup.java`, called from setController (the
same place the bridge learns the controller):
- Fire-and-forget background daemon thread: one GET to
  the server's /health endpoint, derived from
  KaraokeSettings.DEFAULT_WS_URL via KaraokeLog.hostOf
  ("https://" + host + "/health") — no second hardcoded
  URL, host only in logs, never the full URL.
- Purpose: wake the Modal container (it sleeps when idle;
  a cold start just cost a 60s+ handshake timeout on the
  phone) BEFORE the user taps K.
- Dedupe: one ping per controller identity, plus a
  10-minute re-ping gate for a reused controller after a
  long idle. NO retry loop: a failed ping logs once and
  is done. No toast, at most one short log line per ping
  ("warmup: ping <host> -> HTTP <code>" or "warmup: ping
  <host> failed: <reason>"). 15s connect/read timeouts.
- The WS connect code is untouched; a warm-up failure can
  never affect playback or karaoke (every path guarded).

### v6 verification (patched dex dumped, not just CLI line)
- CLI list-patches loads v6 (Karaoke, enabled).
- MultiDexIO.readDexFile probe PASSES on v6; v1 control
  bundle still throws (probe discriminates). classes.dex
  at bundle root preserved.
- CLI patch run on stock 20.40.45: "Karaoke" succeeded.
- Patched-dex dump: KaraokeGeometry + KaraokeWarmup
  classes present; setController body calls logGeometry
  + Warmup.ping; initializeBottomButtons has 2 direct
  logBlock calls (BEFORE + buttons-present) plus 4 lambda
  logBlock calls (AFTER posts + layout listeners, both
  anchor paths); mi() hook at pc 125 ->
  initializeBottomButtons; aB hook at pc 0 ->
  setController; patch_getVideoId -> Lajcf.j (v5 intact);
  alignButtonsToFullscreen present (v4 intact).
- Build note: this d8 (8.6.2-dev) rejects a bare directory
  as program input ("Unsupported source file type") — pass
  the .class files explicitly (find ... -name '*.class').
  Also: d8 needs java on PATH (jdk17/bin).
- Verification-only patched APK stays local at
  ~/workspace/yt204045-work/patched-v6/ (never sent).
- NOT proven here (no phone): the portrait numbers
  themselves, and that the warm-up removes the cold-start
  timeout. Phone test: open a video in PORTRAIT, controls
  shown AND hidden, tap LOG, paste it — no karaoke tap
  needed for the position numbers. For karaoke: open a
  video, wait a few seconds for the warm-up, then tap K.

## v7-diag (2026-10-26) — stub the controller hook, isolate the band

Bundle: `patch/build/karaoke-patches-v7-diag.rvp` (61,181
bytes; build dirs under `patch/build/v7-diag/`; v6
untouched). Local only: no push, no dispatch, no deploy,
no server changes, no APK sent anywhere.

### The question
Portrait shows a black band above the video (surface
y=230 vs player area y=115, 115px gap) and stock
YouTube does NOT have it (user's word, accepted as the
true baseline). The v6 geometry log proved the BUTTONS
do not cause it (surface rect identical before/after
buttons are added). The only patch work not yet
exonerated is the controller hook: at Laiwx.aB() entry
the patch calls KaraokeBridge.setController(), which
in v6 stored the controller, logged a geometry block,
and fired the warm-up ping. This build isolates exactly
that one call. Correction to the earlier v6 claim: the
v6 BEFORE block was already patched (hooks ran, buttons
not yet added), so BEFORE==AFTER clears the buttons
only, not the whole patch.

### The change (extension only, one method)
KaraokeBridge.setController() in the v7-diag source
copy (`patch/build/v7-diag/ext-src/`, canonical source
untouched — diff vs v6 source is ONLY the stub hunk):
```java
public static void setController(Object controllerInstance) {
    KaraokeLog.i("diag: setController stubbed");
}
```
No controller stored, no geometry log at controller
set, no warm-up ping (grep confirms zero Warmup.ping
callers remain). NOTHING else changed: mi() hook,
buttons, alignment, videoId synthetic, session, WS
client, and all other extension code are exactly v6.
The mi-hook BEFORE/AFTER geometry blocks still run
(they live in initializeBottomButtons, untouched), so
the user can still verify the surface rect via LOG.
Karaoke will NOT start in this build (no controller;
tap K reports "not ready") — expected and intended.
Visual test only.

### v7-diag verification (patched dex dumped, not just CLI line)
- Patch classes byte-identical to v6 (md5-verified:
  KaraokeFingerprints.class, KaraokePatchKt.class, and
  root classes.dex all match v6 exactly).
- Extension dex 67,948 bytes (v6: 68,044 — smaller, as
  expected from the stub body).
- CLI list-patches loads v7-diag (Karaoke, enabled).
- MultiDexIO.readDexFile probe PASSES on v7-diag
  (v6 control still passes; probe classpath must use
  absolute paths — `~` after `:` in -cp is not expanded).
- CLI patch run on stock 20.40.45: "Karaoke" succeeded.
- Patched-dex dump: setController body is exactly
  const-string "diag: setController stubbed" ->
  KaraokeLog.i -> return-void (no store, no
  logGeometry, no Warmup.ping); aB entry hook at pc 0
  -> setController; mi() hook at pc 121-128 ->
  initializeBottomButtons with return-object intact;
  all 5 synthetics present, patch_getVideoId =
  iget Laiwx.i -> iget Laiwl.l -> invoke-virtual
  Lajcf.j()String -> return-object (v5 intact).
- Verification-only patched APK stays local at
  ~/workspace/yt204045-work/patched-v7-diag/ (never sent).
- NOT proven here (no phone): the band verdict itself.
  Phone test: install, patch, open the SAME video in
  portrait, report ONLY whether the black band above
  the video is gone or still there. Band gone => the
  aB hook's setController work (store/geometry/ping
  timing on the main thread during player setup) caused
  it. Band still there => the controller hook is
  exonerated too, and the cause lies elsewhere
  (patched-APK environment or a video/state effect
  that the stock comparison must then re-check).

## v8 (2026-10-26) — WebSocket framing fix (the socket-close root cause)

Bundle: `patch/build/karaoke-patches-v8.rvp` (61,226 bytes;
build dirs under `patch/build/v8/`; v6/v7-diag untouched).
Local only: no push, no dispatch, no deploy, no server
changes, no APK sent anywhere. Extension ONLY — patch
source unchanged, patch classes byte-identical to v6
(root classes.dex md5 58f7977776f925e0cd9a09a3869aee24,
all 4 patch .class files md5-match v6).

### The bug (proven by code read + byte harness, not a guess)
KaraokeWsClient.sendText's 127-form length (payloads
> 65535 bytes) was written from `payload.length`, a Java
int. Java masks int shift distances to 5 bits (JLS 15.19),
so the loop's >>56/48/40/32 silently became
>>24/16/8/0 — the 8-byte length field wrote the 4 length
bytes TWICE. A 267KB fetch_response declared ~1.1
petabytes; the proxy kills such a frame with no error
frame. That is exactly the phone symptom every time:
EOFException "socket closed", no error frame, server
left waiting in receive_json — while small messages
(correct 126-form: hello, fetch request) worked and the
sandbox probe (proper library, correct framing) lived.
The v5 92KB frame also died because 92KB > 65535 also
takes the 127-form. The read side was already correct
(it uses a long len).

### Byte proof (harness replicating the exact loops)
- 267000-byte payload: OLD declares 1,146,756,268,299,000
  bytes (length bytes 00,04,12,F8,00,04,12,F8); NEW
  declares exactly 267000 (00,00,00,00,00,04,12,F8).
- 92000-byte payload: OLD declares 395,136,991,324,000
  (00,01,67,60,00,01,67,60); NEW declares exactly
  92000 (00,00,00,00,00,01,67,60).
Harness: ~/workspace/yt204045-work/frameproof/FrameProof.java.

### The fix (KaraokeWsClient.java only, 2 spots)
1. sendText 127-form: length is now a long
   (`long len = payload.length; ... (len >> (8*i)) & 0xFF`),
   so shifts 56..0 are all real. The <126 and 126 forms
   are untouched (they were correct).
2. sendPong: was 0x8A + raw length + raw payload —
   UNMASKED, but RFC 6455 requires client frames to be
   masked and a strict proxy kills unmasked client frames.
   Now sends a properly masked pong (0x8A, length byte(s)
   with the mask bit, 4-byte random mask key, XORed
   payload; 127-form in pong uses the same long fix).
Nothing else changed: real v6 setController (store +
logGeometry + Warmup.ping — NOT the v7-diag stub), mi()
hook, buttons, alignment, videoId synthetic, session,
WS read side all intact. The fix was also applied to
the CANONICAL extension source (diff canonical vs the
v8 build copy is empty), so the next build from
canonical cannot reintroduce this bug. This differs
deliberately from v7-diag, where the stub correctly
stayed out of canonical because it was diagnostic only.

### v8 verification (patched dex dumped, not just CLI line)
- CLI list-patches loads v8 (Karaoke, enabled).
- MultiDexIO.readDexFile probe PASSES on v8 (v6 control
  still passes; absolute -cp paths used).
- CLI patch run on stock 20.40.45: "Karaoke" succeeded.
  Verification-only patched APK stays local at
  ~/workspace/yt204045-work/patched-v8/ (never sent).
- Patched-dex dump: patch_getVideoId = iget Laiwx.i ->
  iget Laiwl.l -> invoke-virtual Lajcf.j()String ->
  return (v5 intact); all 5 synthetics present; aB entry
  pc 0 -> setController; mi() hook pc 121-128 ->
  initializeBottomButtons, return-object intact;
  setController body = sput controller + "controller
  set:" log + logGeometry("controller set") +
  Warmup.ping + return (REAL, no "diag:" string in the
  extension dex); sendText 127-form = int-to-long +
  shr-long loop (pc 72-93); sendPong = 0x8A + mask-bit
  length + mask key + masked payload.
- Extension dex 68,256 bytes (v6: 68,044 — larger from
  the masked-pong code, as expected).
- NOT proven here (no phone): the phone test is the
  proof. Phone test: patch, open the same video
  (o374TsBOoVw), tap K, and expect handshake 101 ->
  player fetch 200 -> HEADER received -> audio chunks ->
  karaoke playing (NOT "socket closed"). If it still
  fails, paste the log showing where it dies now.

## v9 (2026-10-26) — Opus decoder input fix (BufferOverflowException on chunk seq=0)

Bundle: `patch/build/karaoke-patches-v9.rvp` (61,373 bytes;
build dirs under `patch/build/v9/`; v8 untouched).
Local only: no push, no dispatch, no deploy, no server
changes, no APK sent anywhere. Extension ONLY — patch
source unchanged, patch classes byte-identical to v6/v8
(root classes.dex md5 58f7977776f925e0cd9a09a3869aee24,
patch .class files diff-identical to v8 staging).

### The bug (phone log: v8 got header + chunk seq=0, then died)
`player error: decode failed for chunk seq=0` with
`BufferOverflowException` at `DirectByteBuffer.put`
(KaraokePlayer.java:525) inside the feed loop
(`inBuf.clear(); inBuf.put(packet);`). Two compounding
defects in KaraokePlayer.decodeOggOpusChunk:
1. OggReader merged multiple packets per page: its segment
   loop set packetDone=true at a segment <255 but kept
   appending the page's REMAINING segments into `cur` and
   returned them all as ONE packet. When ffmpeg packs
   several Opus frames on one Ogg page, the reader glued
   them into one oversized pseudo-packet that exceeds the
   codec's input buffer capacity — and feeding concatenated
   frames as one MediaCodec input buffer is invalid anyway.
2. The decoder was configured without KEY_MAX_INPUT_SIZE,
   so MediaCodec could hand out small input buffers.

### The fix (KaraokePlayer.java only, 2 spots)
1. OggReader rewritten: nextPacket() returns exactly ONE
   packet — it stops at the first segment <255 and RETAINS
   the page's remaining segments (segment table + data
   position + next segment index) as reader state, so later
   calls resume mid-page before reading a new page header.
   A packet spanning pages (all segments 255) still joins
   across pages, as before. New fields: segTable, segIdx,
   segDataPos (plus existing cur, data, pos).
2. decodeOggOpusChunk now sets
   `fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)`
   on the audio/opus format (mime, OUT_RATE=48000,
   2 channels, csd-0 unchanged).
Nothing else changed: v8 framing (sendText long length,
masked pong), real setController, buttons, alignment,
videoId synthetic, session, WS client, drainCodec, ptsUs
stepping all intact. The fix was applied to the CANONICAL
extension source (diff canonical vs v9 build copy empty),
as with v8, so the next build cannot reintroduce it.

### Harness proof (real OggReader via reflection, not a copy)
Harness: ~/workspace/yt204045-work/oggproof/OggProof.java.
Synthetic one-page stream (segments [5,4,3], distinct
payload bytes) and a spanning stream (page 1 segments
[255,255], page 2 segments [4,6]):
- v9: stream1 -> exactly 3 packets (5, 4, 3 bytes, exact
  contents); stream2 -> 514-byte joined packet
  (AA*255 + BB*255 + C*4) then 6-byte packet (D*6).
  OVERALL PASS.
- v8 control (old code): stream1 -> 1 merged 12-byte
  packet; stream2 -> 1 merged 520-byte packet. OVERALL
  FAIL — the old merge bug reproduced exactly.

### v9 verification (patched dex dumped, not just CLI line)
- CLI list-patches loads v9 (Karaoke, enabled).
- MultiDexIO.readDexFile probe PASSES on v9.
- CLI patch run on stock 20.40.45: "Karaoke" succeeded.
  Verification-only patched APK stays local at
  ~/workspace/yt204045-work/patched-v9/ (never sent).
- Patched-dex dump: patch_getVideoId = iget Laiwx.i ->
  iget Laiwl.l -> invoke-virtual Lajcf.j()String -> return
  (v5 intact); all 5 synthetics present with correct
  delegates; aB entry pc 0 -> setController; mi() hook ->
  initializeBottomButtons; setController body = sput
  controller + "controller set:" log + logGeometry +
  Warmup.ping + return (REAL, no "diag:" stub); sendText
  127-form = int-to-long + shr-long loop (v8 intact);
  sendPong = 0x8A + mask-bit length + mask key + masked
  payload (v8 intact); OggReader fields = cur, data, pos,
  segDataPos, segIdx, segTable, and nextPacket returns at
  the first segment <255 with mid-page resume; 
  decodeOggOpusChunk has const-string "max-input-size" +
  const 0x10000 (65536) + setInteger right after
  createAudioFormat(48000) and before csd-0.
- Extension dex 68,504 bytes (v8: 68,256 — larger from the
  OggReader rewrite, as expected).
- NOT proven here (no phone): the phone test is the proof.
  Phone test: patch, open a video, tap K, and expect
  header -> chunks -> karaoke AUDIO playing (decoder no
  longer failing on seq=0). If it still fails, paste the
  log showing where it dies now.

## v10 (2026-10-26) — decoder diagnostics + OpusTags header (empty PCM on every chunk)

Bundle: `patch/build/karaoke-patches-v10.rvp` (61,777
bytes; build dirs under `patch/build/v10/`; v9
untouched). Local only: no push, no dispatch, no
deploy, no server changes, no APK sent anywhere.
Extension ONLY — patch source unchanged, patch
classes byte-identical to v6/v8/v9 (root classes.dex
md5 58f7977776f925e0cd9a09a3869aee24, patch .class
files diff-identical to v9 staging).

### The problem (phone logs: v9, two videos, same pattern)
Every chunk (seq 0-9) decodes to empty PCM, each with
its own decoder created, no decode error. Proven
before this build and NOT re-litigated: the server's
chunk Opus stream is valid (sandbox ffmpeg decodes
chunk seq=0 to exactly 5.25s PCM) and the v9 OggReader
is byte-perfect on real chunk bytes (0 mismatches /
265 packets vs an independent parser, sizes AND
SHA-256 content). So the fault is inside the phone's
MediaCodec decoder interaction, and this build must
OBSERVE it precisely while also trying the most
plausible config completion.

### The change (KaraokePlayer.java only, decode path)
1. csd-1 (OpusTags): decodeOggOpusChunk previously
   captured OpusHead into opusHead but SKIPPED the
   OpusTags packet. It now stores it (opusTags, same
   pattern) and, when creating the decoder, sets
   csd-1 to the stored OpusTags packet when present,
   in addition to the existing csd-0 (OpusHead).
   Format otherwise identical: mime audio/opus,
   OUT_RATE 48000, 2 channels, KEY_MAX_INPUT_SIZE
   65536.
2. Decoder logging (diagnostic, exactly ONE summary
   line per chunk, via KaraokeLog.d, never throws):
   at the end of decodeOggOpusChunk (after the
   try/finally, so it runs whenever decode completes
   without exception), it logs: packets fed count,
   input-buffer timeout count (dequeueInputBuffer
   returned < 0), output buffer count, total output
   bytes, and the decoder's output format string
   (captured via codec.getOutputFormat() BEFORE the
   finally releases the codec, wrapped in try/catch;
   "unknown" if it throws or codec is null). A new
   DrainStats holder carries the output counts out
   of drainCodec; drainCodec's behaviour is unchanged,
   only counting added. The existing "decoder created
   for chunk seq=N" line is kept.
Nothing else changed: v8 framing (sendText long
length, masked pong), v9 OggReader, real
setController, buttons, alignment, videoId
synthetic, session, WS client, drainCodec behaviour,
ptsUs stepping all intact. The fix was applied to
the CANONICAL extension source (v10 build copy is
a verbatim copy of canonical), as with v8/v9, so
the next build cannot reintroduce it.

### v10 verification (patched dex dumped, not just CLI line)
- CLI list-patches loads v10 (Karaoke, enabled).
- MultiDexIO.readDexFile probe PASSES on v10 (v9
  control still passes; absolute -cp paths used).
- CLI patch run on stock 20.40.45: "Karaoke"
  succeeded, APK saved locally (31,006,233 bytes).
  Verification-only patched APK stays local at
  ~/workspace/yt204045-work/patched-v10/ (never sent).
- Patched-dex dump (extension split across dexes by
  the patcher: KaraokePlayer in classes4.dex,
  KaraokeWsClient in classes2.dex, OggReader in
  classes7.dex): patch_getVideoId = iget Laiwx.i ->
  iget Laiwl.l -> invoke-virtual Lajcf.j()String ->
  return (v5 intact); all 5 synthetics present; aB
  entry pc 0 -> setController; mi() hook pc 125 ->
  initializeBottomButtons with return intact;
  sendText 127-form = int-to-long + shr-long loop
  (v8 intact); sendPong = 0x8A + mask-bit length +
  mask key + masked payload (v8 intact); OggReader
  fields = cur, data, pos, segDataPos, segIdx,
  segTable (v9 intact); decodeOggOpusChunk has
  const-string "max-input-size" (v9 intact) +
  "csd-0" + "csd-1" (v10 new) + DrainStats
  instantiation + drainCodec with DrainStats param
  (both call sites) + getOutputFormat() call +
  outBufs/outBytes igets + const-string "player:
  decode summary seq=" (v10 new); strings check:
  zero "diag: setController stubbed" (real
  setController, not the v7-diag stub), "controller
  set:" present, "player: decode summary seq="
  present, "csd-1" present.
- Extension dex 69,492 bytes (v9: 68,504 — larger
  from DrainStats + csd-1 + summary log, as
  expected).
- NOT proven here (no phone): whether csd-1 fixes
  the empty PCM, or what the summary lines reveal.
  Phone test: patch, open a video, tap K, paste
  the log — the per-chunk summary lines show WHERE
  decode fails (outBufs=0/outBytes=0 = decoder
  outputs nothing; inTimeouts>0 = inputs dropped;
  outFormat = what the decoder reports). If chunks
  decode non-empty, karaoke audio plays.

## v11 (2026-10-26) — codec-config buffer + persistent EOS drain (systematic empty PCM)

Bundle: `patch/build/karaoke-patches-v11.rvp` (62,142
bytes; build dirs under `patch/build/v11/`; v10
untouched). Local only: no push, no dispatch, no
deploy, no server changes, no APK sent anywhere.
Extension ONLY — patch source unchanged, patch
classes byte-identical to v6/v8/v9/v10 (root
classes.dex md5 58f7977776f925e0cd9a09a3869aee24,
all 4 patch .class files cmp-identical to v10
staging).

### The problem (v10 phone log, video IXl2odcx_d0)
EVERY chunk: fed=263 inTimeouts=0 outBufs=0
outBytes=0 outFormat={sample-rate=48000,
mime=audio/raw, channel-count=2}. The decoder
accepts all 263 packets, drops none, reports a
valid raw output format, and emits ZERO output
buffers. Stream valid and reader byte-perfect
(proven before v10, not re-litigated), so the
fault is the phone's MediaCodec interaction, and
the two remaining mechanisms are: (a) the decoder
never received its setup as an input buffer, and
(b) the end-of-chunk drain gives up too early.

### The change (KaraokePlayer.java only, decode path)
1. Codec-config buffer: after codec.start() and
   BEFORE the first audio packet, dequeue an input
   buffer and queue the stored OpusHead packet with
   BUFFER_FLAG_CODEC_CONFIG (offset 0, size
   opusHead.length, pts 0). Some vendor decoders
   ignore csd-0 and wait for exactly this buffer.
   A dequeue timeout logs ("player: codec-config
   buffer timeout for chunk seq=") and any
   exception logs ("...skipped...") — the chunk
   NEVER fails over this. csd-0 (OpusHead) and
   csd-1 (OpusTags) stay as in v10.
2. Persistent EOS drain: new drainCodecUntilEos,
   used ONLY for the drain after the EOS input
   buffer, and only when EOS was actually queued
   (otherwise the normal drain runs, so a missing
   input buffer cannot cost a spurious 5s wait).
   It loops dequeueOutputBuffer(10ms) until the
   output EOS flag or ~5s total — it does NOT
   break on the first output gap, unlike the
   per-packet drain (unchanged). Counts feed the
   same DrainStats, so the v10 summary line is
   unchanged in form.
Nothing else changed: v8 framing, v9 OggReader,
v10 csd-1 + summary logging, real setController,
buttons, alignment, videoId synthetic, session,
WS client, ptsUs stepping all intact. The fix was
applied to the CANONICAL extension source (v11
build copy is a verbatim copy of canonical), so
the next build cannot reintroduce it.

### v11 verification (patched dex dumped, not just CLI line)
- CLI list-patches loads v11 (Karaoke, enabled).
- MultiDexIO.readDexFile probe PASSES on v11 (v10
  control still passes; absolute -cp paths used).
- CLI patch run on stock 20.40.45: "Karaoke"
  succeeded, APK saved locally (31,231,513
  bytes). Verification-only patched APK stays
  local at ~/workspace/yt204045-work/patched-v11/
  (never sent).
- d8 calibration: d8 on the v10 ext-classes with
  the same flags reproduces the v10 ext-dex
  byte-identically (md5 007458171bba0665c714ca8b7ecf87d6),
  so the v11 dex was built with the proven flags.
- Patched-dex dump (extension split across dexes
  by the patcher: KaraokePlayer in classes3.dex,
  KaraokeWsClient in classes.dex, OggReader in
  classes7.dex): patch_getVideoId = iget Laiwx.i ->
  iget Laiwl.l -> invoke-virtual Lajcf.j()String ->
  return (v5 intact); aB entry pc 0 ->
  setController (real); mi() hook pc 121-128 ->
  initializeBottomButtons with return intact;
  sendText 127-form = int-to-long + shr-long loop
  (v8 intact); sendPong = 0x8A + mask-bit length +
  random 4-byte mask key (v8 intact); OggReader
  fields include segTable (v9 intact);
  decodeOggOpusChunk has const-string
  "max-input-size" (v9) + "csd-0" + "csd-1" (v10)
  + queueInputBuffer with flags const 0x2
  (BUFFER_FLAG_CODEC_CONFIG, v11 new) right
  after start() + "player: codec-config buffer
  timeout/skipped for chunk seq=" strings (v11
  new) + EOS branch calling drainCodecUntilEos
  when EOS queued, drainCodec otherwise (v11 new)
  + drainCodecUntilEos body (deadline now+5000,
  dequeue loop, EOS flag & 4 break, DrainStats
  counts) + getOutputFormat() + "player: decode
  summary seq=" (v10); strings check: zero
  "diag: setController stubbed" (real
  setController, not the v7-diag stub).
- Extension rve 70,128 bytes (v10: 69,492 —
  larger from the config-buffer code + EOS drain,
  as expected).
- NOT proven here (no phone): whether v11 fixes
  the empty PCM. Phone test: patch, open a video,
  tap K, paste the log — the per-chunk summary
  lines show it (outBytes > 0 = decoding; still
  0 = neither the config buffer nor the
  persistent drain was the cause).

## v12 (2026-10-26) — correct Opus csd-1/csd-2 (the silent-decoder root cause)

Bundle: `patch/build/karaoke-patches-v12.rvp` (62,225
bytes; build dirs under `patch/build/v12/`; v11
untouched). Local only: no push, no dispatch, no
deploy, no server changes, no APK sent anywhere.
Extension ONLY — patch source unchanged, patch
classes byte-identical to v6/v8/v9/v10/v11 (root
classes.dex md5 58f7977776f925e0cd9a09a3869aee24,
all 4 patch .class files cmp-identical to v11
staging).

### The bug (Claude second opinion, confirmed by code read)
Android's Opus decoder does NOT take OpusTags in
csd-1. Per the MediaCodec Opus config: csd-0 =
OpusHead, csd-1 = pre-skip (codec delay) in
NANOSECONDS as an 8-byte native-order long, csd-2 =
seek pre-roll in nanoseconds. v10/v11 put the ASCII
bytes "OpusTags" in csd-1, which the decoder reads
as a ~8e18 ns delay, so it trims EVERY sample as
delay and emits nothing with no error. That matches
the phone logs exactly (fed=263, inTimeouts=0,
valid raw output format, outBytes=0, only the EOS
marker out). Honest wrinkle, recorded not hidden:
v9 (csd-0 only, no csd-1) was ALSO empty, so if v12
still shows empty there is a second cause — which
is why the codec-name log below matters.

### The change (KaraokePlayer.java only, decode path)
1. Correct csd-1/csd-2: preSkip read from the
   OpusHead packet as little-endian bytes 10-11:
   preSkip = (opusHead[10] & 0xFF) |
   ((opusHead[11] & 0xFF) << 8); delayNs = preSkip
   * 1_000_000_000L / 48_000L (312 -> 6,500,000);
   preRollNs = 80_000_000L. csd-0 = OpusHead as-is
   (unchanged), csd-1 = 8-byte native-order
   ByteBuffer holding delayNs, csd-2 = 8-byte
   native-order ByteBuffer holding preRollNs.
   Helper nativeLong(v) = ByteBuffer.allocate(8)
   .order(ByteOrder.nativeOrder()).putLong(0, v).
   Guard: opusHead.length < 12 -> delayNs = 0
   (never crashes the chunk). OpusTags is still
   parsed/skipped by the OggReader loop but is NO
   LONGER used for csd-1.
2. REVERTED v11's codec-config buffer: the block
   queuing OpusHead as a BUFFER_FLAG_CODEC_CONFIG
   input buffer (and its timeout/skipped log lines)
   is removed — configure() already delivers
   csd-0/1/2.
3. KEPT: persistent EOS drain (drainCodecUntilEos,
   only when EOS was queued) and the v10 per-chunk
   summary logging (fed / inTimeouts / outBufs /
   outBytes / outFormat), unchanged.
4. NEW: one guarded log line after configure/start:
   "player: decoder name=<codec.getName()> csd
   sizes=<opusHead.length>/8/8 for chunk seq=N"
   (expected 19/8/8). Identifies which decoder
   implementation the framework picked (software
   vs vendor) for the next diagnosis round if v12
   still outputs nothing. Logging never breaks
   decode (try/catch around it, as with the
   summary).
Nothing else changed: v8 framing, v9 OggReader,
v10 summary logging, v11 EOS drain, real
setController, buttons, alignment, videoId
synthetic, session, WS client, ptsUs stepping all
intact. The fix was applied to the CANONICAL
extension source (v12 build copy is a verbatim
copy of canonical — diff empty), so the next
build cannot reintroduce it.

### v12 verification (patched dex dumped, not just CLI line)
- CLI list-patches loads v12 (Karaoke, enabled).
- MultiDexIO.readDexFile probe PASSES on v12 (v11
  control still passes; absolute -cp paths used).
- CLI patch run on stock 20.40.45: "Karaoke"
  succeeded, APK saved locally (31,055,385
  bytes). Verification-only patched APK stays
  local at ~/workspace/yt204045-work/patched-v12/
  (never sent).
- d8 calibration: d8 on the v11 ext-classes with
  `--lib android.jar --debug` (NO --min-api flag)
  reproduces the v11 ext-dex byte-identically
  (md5 a720f3c11c221c507c294c4c4ee0fc21), so the
  v12 dex was built with the proven flags. (Every
  --min-api value tried 21-35 and --release all
  missed; --debug with no min-api is the match.
  Earlier agents' "same flags" claim is now pinned
  down to this exact invocation.)
- Patched-dex dump (extension split across dexes
  by the patcher: KaraokePlayer in classes3.dex,
  KaraokeWsClient in classes.dex, OggReader in
  classes7.dex): patch_getVideoId = iget Laiwx.i ->
  iget Laiwl.l -> invoke-virtual Lajcf.j()String ->
  return (v5 intact); all 5 synthetics present; aB
  entry pc 0 -> setController (real); mi() hook
  pc 121-128 -> initializeBottomButtons with
  return-object intact; sendText 127-form =
  int-to-long + shr-long loop (v8 intact);
  sendPong = 0x8A + mask-bit length + random
  4-byte mask key (v8 intact); OggReader fields
  = cur, data, pos, segDataPos, segIdx, segTable
  (v9 intact); decodeOggOpusChunk has
  const-string "max-input-size" (v9) + "csd-0" +
  "csd-1" = nativeLong(delayNs) + "csd-2" =
  nativeLong(80000000) (v12 new) + preSkip read
  (aget-byte 10 & 255, aget-byte 11 & 255 << 8,
  or, int-to-long, length>=12 guard else 0,
  *1000000000/48000) + nativeLong body
  (allocate/nativeOrder/putLong) + getName() +
  "player: decoder name=" + " csd sizes=" +
  "/8/8 for chunk seq=" (v12 new) +
  drainCodecUntilEos (v11) + getOutputFormat() +
  "player: decode summary seq=" (v10); queue
  sites in the method are flags 0 (audio) and
  flags 4 (EOS) ONLY — no 0x2 codec-config queue
  (v11 reverted); strings check: zero "diag:
  setController stubbed" (real setController,
  not the v7-diag stub), zero "codec-config
  buffer", "player: decoder name=" present,
  "csd-2" present.
- Extension rve 70,240 bytes (v11: 70,128 — net
  +112 from csd-2 + name log + nativeLong minus
  the removed config-buffer block, as expected).
- NOT proven here (no phone): whether the correct
  csd-1/csd-2 fixes the empty PCM. Phone test:
  patch, open a video, tap K, paste the log —
  the per-chunk summary lines are the verdict:
  outBytes around 1,008,000 (delay trim applied)
  or around 1,009,200 (trim not applied) both
  mean audio plays (bufferedSec > 0, hold
  releases); outBytes still 0 means a second
  cause remains, and the new codec-name log line
  says which decoder to investigate next.

## v13 (2026-10-26) — fix Bug C deadlock + pause video +
  loading overlay (user request: "pause the video like
  have a loading screen just like the karaoke now and
  play it only when there is available vocal removed
  audio")

Bundle: `patch/build/karaoke-patches-v13.rvp` (65,312
bytes; build dirs under `patch/build/v13/`; v12
untouched). Local only: no push, no dispatch, no
deploy, no server change. Verification-only patched
APK stays local at `~/workspace/yt204045-work/
patched-v13/` (never sent).

Problem: Bug C (see PHASE1) — the first-audio gate
needed bufferedSec() >= 1.0s, but bufferedFrames
only grows in nextChunk(), which never runs while
the writer is held. No version could reach PLAYING.
Plus the user's new request: pause the video and
show a loading screen until karaoke audio is ready,
instead of the video playing muted during loading.

Changes (extension source, canonical — the next
build keeps them):
- KaraokePlayer.queuedSec(): sum of reorder
  framesLeft / OUT_RATE under reorderLock. Does
  NOT change bufferedFrames accounting (no
  increment in queueChunk, nextChunk increment
  untouched).
- KaraokePlayer.coversSec(double sec): true if any
  reorder chunk has startSec <= sec < endSec()
  under reorderLock; empty -> false. Gate-time
  endSec is the TRUE full end: PcmChunk.endSec()
  = startSec + capFrames / OUT_RATE, capFrames is
  only lowered by trimChunk(), which runs ONLY in
  nextChunk() (writer path, never while held), and
  skipToSec() changes pos, not capFrames. So a
  mid-song tap's position is covered exactly when
  the chunk containing it arrives.
- Session gate (WsListener.onChunk): condition is
  now queuedSec() >= START_BUFFER_SEC (1.0s), log
  line keeps bufferedSec AND adds queuedSec. After
  the mute proof and the position read, if
  !coversSec(atSec) the gate WAITS: it logs
  "first-audio gate: waiting for coverage at ..."
  and resets firstAudioFired = false before
  returning. The reset is critical: the wait path
  is NOT terminal (unlike mute-fail and
  position-read-fail, which call fail()), so
  without it the gate would never re-check and
  the session would stall in BUFFERING forever.
  Mute already happened before this check; it
  stays muted while waiting, and fail/stop
  cleanup unmutes. On the firing path the order
  is unchanged: mute -> skipToSec -> releaseHold
  -> setState(PLAYING) -> resume-if-paused ->
  onFirstAudio() -> startSyncLoop().
- Pause/resume: VideoPosition gained pause() and
  resume() (never throw; false = call could not
  be made). Session start() records wasPlaying =
  video.isPlaying() (on throw: log, do NOT pause
  — cannot restore reliably); if playing, calls
  video.pause() and sets pausedByKaraoke. After
  releaseHold + PLAYING, if pausedByKaraoke it
  sets the flag false and calls video.resume()
  (try/catch, logged). stopInternal() restores
  the video unconditionally (not gated on
  unmute) if pausedByKaraoke, so a paused video
  is never left behind across sessions (start()
  calls stopInternal(false) first). A pause
  failure is NOT a session failure.
- Loading overlay (KaraokeBridge): onState()
  CONNECTING or BUFFERING -> showLoadingOverlay();
  any other state (PLAYING, ERROR, DONE, IDLE,
  and PAUSED — the mirrored mid-song pause, not
  loading) -> hideLoadingOverlay(); onError()
  also hides (belt + suspenders). show finds the
  first LAID-OUT (width>0, height>0) SurfaceView/
  TextureView from the anchor root (same DFS as
  KaraokeGeometry, 5000-view cap), adds a
  FrameLayout to the surface's PARENT (video area
  only, NOT the bottom bar, so K/BV stay visible
  and tappable) with tag "karaoke-loading-overlay",
  background 0xCC000000, clickable=true (video-area
  taps cannot manually unpause/seek mid-load),
  centered TextView "Preparing karaoke…" (white,
  18sp), LayoutParams COPIED from the surface's
  own params (same copyParams pattern — a base
  LayoutParams would crash RelativeLayout's
  measure) with width/height forced MATCH_PARENT.
  Show dedupes by tag; show is re-attempted on
  BUFFERING after CONNECTING, so a surface that
  was not laid out yet gets covered on the second
  attempt. hide removes by weak ref, else by tag
  search; missing overlay is a no-op. All posts
  go to the main looper, all try/catch, never
  crash the UI thread. Anchor = K button weak
  ref if alive, else bottomUi weak ref, else
  null -> skip with a log, session continues.
  Adding a MATCH_PARENT child last to a
  RelativeLayout parent does not resize siblings
  (no rules reference the new child); other
  views' params are never touched.
- Pause verification (opcode, NOT a guess):
  Laiwx.M(Z)V body is exactly iget-object
  ExoPlayer g; invoke-interface ExoPlayer.ab(Z)V
  with the arg passed through; return-void.
  Lctv.ab(Z)V (classes.dex) is the playWhenReady
  setter: no-op if flag A already equals the arg,
  else sets A, notifies, dispatches event 23 —
  the same event the real UI pause path fires, so
  UI state stays consistent. Laiwx.Q()Z
  (patch_isPlaying) reads the SAME Lctv.A flag,
  so M(false) = pause, M(true) = resume.
  Polarity confirmed; M is the ONLY (Z)V method
  on Laiwx. Patch source adds a playPause
  fingerprint (returns V, params Z, opcodes
  IGET_OBJECT + INVOKE_INTERFACE, custom block
  pins class Laiwx AND the ab() call on
  androidx/media3/exoplayer/ExoPlayer) resolved
  via matchOrFail (build stops loudly if M's
  shape changes), plus patch_pause()V (const/4
  v0, 0; invoke-virtual M(Z)V) and patch_resume
  ()V (const/4 v0, 1; invoke-virtual M(Z)V).
  Patch classes therefore DIFFER from v12 by
  design (new fingerprint member + new code in
  resolveAndInject + 2 synthetics); the 5 old
  synthetics are intact with identical bodies
  (verified in the patched-dex dump below).
  The original "patch classes byte-identical"
  claim does NOT carry to v13 — do not repeat it.

Build notes:
- d8 calibration FIRST: rebuilding v12 ext-classes
  with the pinned flags reproduces the v12 dex
  byte-identically (md5
  69ce0d6b6f2d0026acacee63d29bba9a) before
  trusting them for v13. Pinned flags: d8 --lib
  android.jar --debug, NO --min-api.
- javac clean (20 classes, same set as v12).
  kotlinc clean (only the known deprecation
  warnings). d8 patch-classes exit 0; the Kotlin
  metadata warnings (kotlinc 2.3.0 newer than
  d8 8.6.2's reader) are non-fatal and the dex
  reproduces byte-identically on re-run.
- Bundle layout unchanged: root classes.dex
  (29,908 bytes) + app/karaokenow/patch/*.class
  + extensions/karaoke.rve (74,732 bytes, v12:
  70,240 — larger from queuedSec/coversSec/
  overlay/pause-resume, as expected). Zip
  staging trap: zip from INSIDE rvp-staging
  writes to v13/, NOT build/ — the delivered
  bundle is the COPY at patch/build/
  karaoke-patches-v13.rvp.

Verification (patched-dex dump, patched-v13):
- CLI list-patches loads v13: Karaoke, enabled.
- MultiDexIO readDexFile probe passes (OK:
  MultiDexContainerBackedDexFile, absolute -cp
  paths).
- CLI patch run on stock 20.40.45: "Karaoke"
  succeeded (the playPause fingerprint resolving
  is itself build-time proof M matched the pin).
  Patched APK local only, 31,170,073 bytes.
- Laiwx synthetics, all 7, bodies verified:
  patch_getVolume -> a()F, patch_setVolume ->
  L(F)V, patch_getPosition -> e()J,
  patch_isPlaying -> Q()Z (all four identical
  to v12), patch_getVideoId -> iget Laiwx.i ->
  iget Laiwl.l -> Lajcf.j() (v5 intact),
  patch_pause -> const/4 v0, 0 -> M(Z)V,
  patch_resume -> const/4 v0, 1 -> M(Z)V.
- aB hook at pc 0 -> real setController;
  mi() hook at pc 121-128 ->
  initializeBottomButtons with return intact.
- v8 framing intact: sendText 127-form (shr-long
  loop), sendPong 0x8A + Random mask key.
- v9 OggReader intact: nextPacket packet-split
  body (segIdx/segTable/segDataPos/data) in
  classes7.dex — note the multidex split
  scatters inner classes (Player in classes3,
  OggReader in classes7); a missing class in one
  dex is NOT missing from the APK.
- v10/v12 intact: "player: decode summary seq=",
  "player: decoder name=" strings; csd-1 =
  nativeLong(delayNs), csd-2 =
  nativeLong(80000000), "max-input-size".
- v13 gate: onChunk calls queuedSec() for the
  condition (pc 101) and the log (pc 193),
  setMuted, coversSec(D) (pc 299), wait line
  (pc 345), skipToSec (pc 437), releaseHold
  (pc 446) -> setState(PLAYING) -> pausedByKaraoke
  check -> flag false + VideoPosition.resume()
  (try/catch, "first-audio gate: video resume
  result=") -> onFirstAudio() -> startSyncLoop();
  firstAudioFired writes at pc 205 (set true)
  AND pc 380 (reset false in the wait path —
  the critical reset, present).
- v13 overlay: "Preparing karaoke…",
  "karaoke-loading-overlay", "loading overlay
  shown/hidden" strings present; onState
  CONNECTING/BUFFERING -> showLoadingOverlay,
  else -> hideLoadingOverlay; onError ->
  hideLoadingOverlay; stopInternal -> pausedBy-
  Karaoke check -> flag false -> resume().
  Session start: CONNECTING -> isPlaying() ->
  pause(). Bridge pause()/resume() -> call
  ("patch_pause")/call("patch_resume"),
  return true, exception -> false.
- Strings check: zero "diag: setController
  stubbed" in all 8 dexes (proper `strings`
  check — dex.py has NO --strings mode; an
  earlier empty result was the tool erroring,
  not an absence).
- NOT proven here (no phone): whether pause +
  overlay + gate behave on the real player.
  Phone test: patch, open a video, tap K ->
  video PAUSES + "Preparing karaoke…" shows ->
  when audio is ready, video RESUMES with
  karaoke audible and the original muted.
  Paste the log: WS chunk lines (queuedSec
  values), "first-audio gate: mute result=",
  "first-audio gate: skipping karaoke to ...",
  hold released, PLAYING, "first-audio gate:
  video resume result=". Even with the 403 wall
  still present, v13 should yield first audible
  karaoke (~63s, 12 chunks) before the wall
  kills the session — hearing audio for the
  first minute is the milestone, separate from
  the still-open 403.

## v14 (2026-10-26) — log the synthetic-call CAUSE (diagnostic
  only; NO behavior change; user go: "ok do it then")

Bundle: `patch/build/karaoke-patches-v14.rvp` (65,453
bytes; md5 b4e713f3215794faf65d477ce739838d; build
dirs under `patch/build/v14/`; v13 untouched).
Local only: no push, no dispatch, no deploy, no
server change. Verification-only patched APK stays
local at `~/workspace/yt204045-work/patched-v14/`
(31,198,745 bytes, never sent).

Problem: in the v13 phone test the mute's volume
read failed with only "KaraokeBridge: call to
synthetic patch_getVolume failed" + "TRACE:
InvocationTargetException: null" — the log never
showed what the invoked method itself threw.
Root cause of the blindness (verified by code
read, not guessed): KaraokeLog.recordTrace() logs
only the throwable's own class/message + first
10 frames and never walks getCause(). The
wrapper's message is null, so the ring buffer —
the only log readable on the phone, no
adb — showed nothing useful. (Logcat's Log.e
would print the "Caused by:" chain, but that
does not reach the phone.)

Change (canonical extension source ONLY, one
block in KaraokeBridge.call()'s catch, after
the existing e() log and before the same
IllegalStateException rethrow):
- If the caught exception is an
  InvocationTargetException with a non-null
  cause, log one more line via KaraokeLog.e:
  "KaraokeBridge: synthetic <name> cause:
  <cause class fully-qualified>: <cause
  message>" with the cause as the throwable, so
  the cause's class/message AND its stack
  frames reach BOTH logcat and the 200-line
  ring (LOG button dump).
- NOTHING else changes: same exception type,
  message, and cause chain rethrown; same
  return values; mute gate, setMuted, session
  gate, pause/resume, overlay, decoder,
  buttons, WS client all exactly as v13. The
  mute read itself is NOT fixed here — v14
  exists only to reveal the cause on the next
  phone test, so the fix is chosen from
  evidence, not a guess.
- Applies to the synthetic call path generally
  (call() is the single Method.invoke site in
  KaraokeBridge — verified by grep, line 990
  only), so every synthetic benefits, not just
  getVolume.

Build notes:
- d8 calibration FIRST: rebuilding v13
  ext-classes with the pinned flags (d8 --lib
  android.jar --debug, NO --min-api) reproduces
  the v13 ext-dex byte-identically (md5
  037b43b92aa709e6978d4aa09107f104) before
  trusting them for v14.
- Canonical diff vs v13 build copy: ONLY the
  v14 cause block (verified by diff — no other
  source change).
- javac clean (20 classes, same set as v13).
  kotlinc needs -Xcontext-parameters
  -jvm-target 17 plus -Xskip-prerelease-check
  (without them: context-parameters errors
  and a JVM-target-17 inline error); with
  them, clean except the known deprecation
  warnings, same 5 output files as v13.
  d8 patch-classes exit 0 (Kotlin metadata
  warnings non-fatal, as in v13).
- Patch dex byte-identical to v13 (md5
  4e25db7af92e7d76093c75360e715342 — patch
  source unchanged, as expected). Extension
  rve 74,984 bytes (v13: 74,732 — +252 from
  the cause block, as expected). Bundle layout
  identical to v13 (12 files; root classes.dex
  29,908 bytes; 4 patch classes; META-INF).
  Zip staging trap avoided: zip run from
  inside rvp-staging with an ABSOLUTE output
  path, delivered bundle verified at
  patch/build/karaoke-patches-v14.rvp.
- Patch classes therefore DIFFER from v13 by
  design ONLY in the extension dex (the cause
  block); the patch-side classes.dex is
  byte-identical. Do NOT claim the whole
  bundle is byte-identical to v13.

Verification (patched-dex dump, patched-v14):
- CLI list-patches loads v14: Karaoke, enabled.
- MultiDexIO probe passes, both modes:
  Probe2 as written on a single patched
  classes.dex -> OK:
  lanchon.multidexlib2.MultiDexContainerBackedDexFile;
  container-mode variant on the full patched
  APK -> OK: same class. (Note: Probe2 as
  written passes false, which throws
  MultiDexDetectedException on the multi-dex
  APK by design — that is the probe's flag,
  not a corrupt APK; the container-mode run
  is the matching check and it passes.)
- CLI patch run on stock 20.40.45: "Karaoke"
  succeeded, 8 dexes compiled, APK saved
  locally (31,198,745 bytes).
- Patched-dex dump (patched-v14, 8 dexes):
  all 7 Laiwx synthetics in classes2.dex with
  exact v13 bodies (getVolume -> a()F,
  setVolume -> L(F)V, getPosition -> e()J,
  isPlaying -> Q()Z, getVideoId -> iget i ->
  iget l -> Lajcf.j()String, pause const 0 ->
  M(Z)V, resume const 1 -> M(Z)V); aB pc 0 ->
  real setController; mi pc 125 ->
  initializeBottomButtons with return-object
  at pc 128 intact; v13 gate sequence in
  WsListener.onChunk (classes3.dex) at the
  same pcs as v13 (queuedSec pc 101, gate
  queuedSec pc 193, firstAudioFired set pc
  205, setMuted pc 223, coversSec pc 299,
  wait line pc 345, firstAudioFired reset pc
  380, skipToSec pc 437, releaseHold pc 446,
  PLAYING pc 451, resume pc 475, resume-result
  log pc 484, onFirstAudio pc 514,
  startSyncLoop pc 519); NEW cause code in
  KaraokeBridge.call (classes8.dex): pc 42
  instance-of InvocationTargetException, pc 49
  getCause(), pc 56-67 cause getClass/
  getName/getMessage, pc 73-106 build
  "KaraokeBridge: synthetic " + name +
  " cause: " + class + ": " + message, pc 107
  KaraokeLog.e(msg, cause), pc 110+ same
  IllegalStateException rethrow as v13;
  strings check: zero "diag: setController
  stubbed" in all 8 dexes (proper strings
  check); v12/v13 strings intact
  (csd-2, decode summary, decoder name in
  classes3.dex; "Preparing karaoke…" in
  classes8.dex).
- NOT proven here (no phone): what the mute
  read actually threw. Phone test: patch, tap
  K, paste the log — a synthetic failure will
  now show a second block: "E: KaraokeBridge:
  synthetic patch_getVolume cause:
  <class>: <message>" + "TRACE: <class>:
  <message>" + the cause's frames (e.g. the
  Laiwx.a() frame if it is an NPE inside the
  volume read). That block is the verdict v14
  exists to produce.

## Appendix — v15–v83 (added 2026-10-31; the body above is frozen at v14)

The per-version detail below is a summary. The full review that
drove the finalization is published as
`docs/security-review-2026-10-31.md`. All v80–v83 builds were
local only (javac + d8 + zip, no push, no Actions build) and
delivered as attachments.

### v15–v18 (2026-10-30, second agent — smali patches, UNVERIFIED)

- Built by decompiling the v13 `.rve` with baksmali, patching
  smali, reassembling, repacking (`.github/workflows/build-patch.yml`,
  since deleted). Base = v15 `.rvp` if present else v13.
- Analysis 2026-10-30 (verified against canonical source): the
  smali patch targeted a call site that does not exist (the single
  `Method.invoke` lives in generic `call()`, the `"patch_getVolume"`
  strings sit in `setMuted()`); verification was vacuous
  (`grep -c` on a binary dex); the `safeGetVolume`/`safeSetVolume`
  helpers faked the mute read-back (returning 0 / true without
  muting — a hard-rule violation); the CookieManager "403 fix"
  was the wrong implementation (YouTube uses Cronet, not the
  WebView cookie store). v15/v16/v16-fixed are byte-identical.
- Status: SUPERSEDED. Never treat v15–v18 as current. The smali
  workflow and `scripts/patch_v17.py` were deleted 2026-10-31.

### v19–v79 (2026-10-30/31, source-built)

- Source-built from v19 (`.github/workflows/build-patch-v19.yml`,
  manual only). `KaraokePatch.kt` byte-identical to v14 throughout
  — all change is extension-Java-only.
- v28: mute/pause rebuilt on the app's own paths. Pause via
  `Laiwx.ar(false/true)` (`M(false)` no-op'd — flag A was already
  false); volume via player field `ae` + method `O` with read-back;
  isPlaying via player `Q()`. CORRECTION to the v13/v14 model,
  verified against stock dex 2026-10-31: `Laiwx.a()` returns
  PlaybackParameters.speed, NOT volume — the v13 mute read was
  reading speed (~1.0), which also explains its failed read-back.
- BV real (v56+): BS-RoFormer stage 2 (`server/model_karaoke.py`,
  `roformer-model-bs-roformer-karaoke-by-becruily`, 204 MB baked,
  SHA-256-verified; Demucs stays stage 1), independent backing
  stream, live mix.
- Server: GPU L4 -> T4; replay cache TTL 600 s; `resume_from_seq`;
  boot warmup; `/health` reports `backing_model_loaded`. BYOK real
  via per-user deploys (`server/setup_app.py`); client has NO
  hardcoded karaoke endpoint (`DEFAULT_WS_URL` deleted; `wsUrl`
  saved on-device at first K tap).
- Fetch: v36 TVHTML5 client, v40 visionOS route (bootstrap visitor
  session from youtube.com HTML, player API as VISIONOS with
  `X-YouTube-Client-Name: 101`). v32 URL rotation is DEAD code;
  app-URL params are DEAD (server never reads them). v45 removed
  pacing ("NO pacing… Fetch full speed"). VisionOS "verified
  locally" is a code comment only — no test/log in the repo.
- Robustness growth: 30 s load watchdog (v54), playback-gone
  watchdog (v44), frozen-video stall guard (v46), rebuffer /
  reconnect pre-emption (v54), `stopInternal` shutdown fix (v58),
  stale-client guard `wsGen` (v55), `keepMuted()` re-asserts mute
  every 500 ms (v53).
- v79 phone log (2026-10-31, oq3dP-MFcpw, 212 s): FIRST
  END-TO-END PROOF — PLAYING reached; mute 0.62878186 -> 0.0
  read-back; pause on first K tap; BYOK auto-setup to the maintainer's own
  Modal host; chunks to seq 19 (~99.8 s); BV live; no 403 (WS
  fetch id=2–6 all 206 x 524,288 B). Full song, seek, and
  stop/restore not yet shown.

### v80–v83 (2026-10-31, seekbar tint — REVERTED 2026-10-31)

- v80 (md5 `468e7eb412f067fd8a3a795b2abc7af8`): set model field
  `m` (buffered color) before `gn()` + tinted resolved paints.
  Phone test: blue only when controls hidden.
- v81 (md5 `71b4e48ee4d3733b3f26453f2b105f58`): set BOTH `l`
  (played) and `m` (buffered) blue, 1 s re-assert loop. Phone
  test: still red when controls show. Log proved the tint WAS
  landing on the one igk found (`l=m=0xff6ec1ff`, `b` growing) —
  the bar seen on the phone was not that igk.
- v82 (md5 `73f6e9f1b60f610b04ad16ce12ea11bf`): unified scan of
  ALL Laliy bars, set `l`/`m` blue + `b` AND `c` to karaoke
  coverage. Phone test (LYEHEjaoHFk, 132 s): karaoke worked
  (PLAYING, mute 0.489 -> 0.0, chunks to seq 24, no 403); model
  tint landed (`l=m=0xff6ec1ff`, `b=c=coverage` to 126,003/
  132,000) but the controls bar was still red on screen.
  Screenshots pixel-checked: ambient bar IS blue (#74bbef);
  controls bar red played #e80a39 + red dot #fd0031, zero blue.
  Dex root cause: `igl.l` scrubber paint holds theme red
  (0x7f040af4, set once in the constructor, no model field feeds
  it) and Lanvg F/G/H/I are red-gradient drawables (gradient
  factory uses theme red; `setBounds` rebuilds the shader every
  draw; `setColorFilter` throws) — no model change could fix it.
- v83 (md5 `30fa0120ebc8bd6f482afb70368c3aac`; d8 deterministic
  md5 `5390c0f4fa0e5713a00eb169ae0e2fc8`): tinted `igl.l` blue
  directly + set F/G/H/I inner-paint alpha to 0 + per-bar diag
  logging. Sent 2026-10-31 17:08.
  NEVER phone-tested — the seekbar work was ended first.
- 2026-10-31 17:25 — decision: done with the seekbar, revert it,
  don't touch it, finalize one final patch. Reverted ~17:30:
  the whole v70–v83 seekbar section (522 lines) deleted from
  `KaraokeBridge.java` plus all call sites (videoAlignGuard,
  onState PLAYING, onFirstAudio, updateSeekbarFromAnchor); javac
  clean (41 classes); seekbar is stock again (red played, gray
  buffered). Do not re-add seekbar tinting without a new
  dex-proven mechanism.

### Final release — v1.0.0 (BUILT 2026-10-31, awaiting phone test)

- Version: v1.0.0 (YouTube pin 20.40.45 explicit). Single `.rvp`
  attached to a GitHub Release, never a committed file.
- Bundle: `patch/build/karaoke-patches-v1.0.0.rvp` (108,938 B,
  md5 `3593ab3a8d74382e0eb355be1d47a265`; 12 files). Built locally
  from the review-fixed source: seekbar revert + all Part 1-4 fixes from the pre-release review
  (`docs/security-review-2026-10-31.md`).
- Build: fully from source. javac exit 0 (41 classes); kotlinc
  2.3.0 exit 0 (4 classes) with `-Xskip-prerelease-check
  -Xcontext-parameters -jvm-target 17 -cp <cli-jar>` (local kotlinc
  is 2.3.0; the workflow pins 2.2.21 — both were verified to
  reproduce the v14 classes.dex byte-identically, md5
  `4e25db7af92e7d76093c75360e715342`); d8 deterministic both dexes
  (ext `345d98a6cbcfd83617628d9eccd1a297`, 168,680 B; patch
  `3083b4d2fb6682ab3b0319a9abbea659`, 27,524 B). Repack base v14.rvp
  with classes.dex + extensions/karaoke.rve replaced AND the 4
  stale .class files replaced with the new ones.
- Verification (all local, 2026-10-31): CLI list-patches loads v1.0.0
  (Karaoke, enabled); CLI patch run "Karaoke" succeeded, all 8 dexes,
  APK saved at `~/workspace/yt204045-work/patched-v1.0.0/patched.apk`
  (31,804,953 B); MultiDexIO probe passes both modes; patched-dex
  dump: 3 synthetics intact with exact bodies (patch_getSpeed ->
  a()F, patch_getPosition -> e()J, patch_getVideoId -> iget i ->
  iget l -> Lajcf.j()String) in classes4.dex; deleted synthetics
  (patch_getVolume, patch_setVolume, patch_pause, patch_resume,
  patch_isPlaying) zero everywhere; fix markers present in the
  patched dex (verifyMuted, KEEP_MUTED, "tap K to retry" x3,
  stopVideoAlignGuard, setEndpointIdentificationAlgorithm,
  MAX_WS_MESSAGE_BYTES/FRAME_BYTES, validateFetchUrl); LOG-button
  methods zero; zero "setController stubbed" / "restart the app" /
  seekbar strings. Server tests: 54 passed, 1 warning (pre-existing
  Starlette httpx deprecation; verified 2026-11-01 — the count
  grew after the setup-helper hardening tests were added).
- Known gaps (from the fix reports, NOT blockers for the phone test
  but listed plainly): D11 duplicate-seq drop in queueChunk() not
  implemented (dispatch-side validation only); L9 watchdog
  blind-timeout (~30s unreadable videoId -> stop) not implemented
  (retry + debug logging only); S18 EncryptedSharedPreferences
  skipped (no androidx.security in workspace); H7 dep pinning
  skipped (no reliable deployed-version source); M8 trade-off
  (pre-paused video may resume on failure paths); D6 residual (one
  <=4096-frame stale block possible in the revalidation-to-write
  window); D10 static Cronet-engine sharing hazard; D16 backward
  seek range now ~84s. Thresholds (2 failed / 20 blind / 4 tick
  failures / 15-min setup cap / 2 MB frame cap / 60s fetch deadline)
  are untested on device. D2 hostname enforcement needs the phone
  test to confirm it actually enforces at handshake time.
- Before public release: fresh phone test (full song, seek,
  stop/restore, video switch, setup flow), stop or gate the shared
  Modal endpoint, delete `patch/build/` from the repo, push the
  client/docs fixes (the server fixes were pushed 2026-11-01,
  commit 4f57f153, and the hardened setup helper deployed the
  same day).
