# Karaoke patch — ReVance CLI 6.0.0, YouTube 20.40.45 (pinned)

A ReVance patch that adds a karaoke button to the official YouTube
app. The video plays muted; vocal-removed audio streams in from a
cloud model (Modal) chunk by chunk over a WebSocket, in sync with the
video. Includes a background-vocal (BV) toggle and a cold-start
loading screen.

**The patch is real and it changes bytecode.** It injects
3 synthetic methods into the player controller, hooks the
controller entry and the bottom-UI inflation, and adds K / BV
buttons to the player bar. The "source-only skeleton" this file
used to describe is long gone — see `patch/BUILD_REPORT.md` for
the build record through v14 and the v15–v83 appendix, and
`docs/PHASE1.md` for the running log.

## Pinned target (do not drift)

- Patcher API: ReVance CLI 6.0.0. Download it from the official
  release (do NOT commit the jar — `tools/` is gitignored):
  `https://github.com/ReVanced/revanced-cli/releases/download/v6.0.0/revanced-cli-6.0.0-all.jar`
  SHA-256: `c25549bc17d59d2eb94fa5f86e60e9b77a02772ca88f7050f8f1276f923a9958`
  (verified 2026-10-31: the official release asset hashes exactly
  to the local copy). Verified runnable with JDK 17.
- Target app: `com.google.android.youtube` version **20.40.45**
  (version code 1557278144, universal variant, min API 28).
  The stock APK is NOT stored in this repo (Google's binary).
  Get it from ReVance Manager on the phone (it fetches the APK
  itself) or an APK extractor — APKMirror returns 403 to
  automation, so do not count on a direct download.
- Distribution goal (unchanged): the user's own patches source for
  ReVanced Manager (alternative-sources add), NOT a merge into
  official ReVanced and NOT a pre-patched APK.
- Final release version: **v1.0.0** (YouTube pin 20.40.45
  explicit). The single `.rvp` is attached to a GitHub Release,
  never committed — `patch/build/` is gitignored and the build
  workflow uploads an artifact instead of pushing to main.

## Layout

```
patch/
  README.md                  this file
  BUILD_REPORT.md            build record (v1–v14 + v15–v83 appendix)
  patches/src/main/kotlin/app/karaokenow/patch/
    KaraokePatch.kt          patch definition: 4 fingerprints,
                             3 synthetics, controller + bottom-UI
                             hooks. Compatible with 20.40.45 only.
  extension/src/main/java/app/karaokenow/extension/
    KaraokeBridge.java       buttons (K/BV), controller handoff,
                             mute/pause/volume, setup dialog, overlay
    KaraokeSession.java      session state machine: first-audio gate,
                             drift loop, pause/seek/speed mirroring
    KaraokePlayer.java       Ogg-Opus demux + MediaCodec -> own
                             AudioTrack, chunk alignment, hold gate
    KaraokeWsClient.java     zero-dependency WS client + tunnel
                             protocol (fetch execute/reply, chunk
                             dispatch, interleaved messages)
    KaraokeLog.java          logcat tag KaraokeNow + 800-line ring
                             buffer + last-failure slot
    KaraokeSettings.java     endpoint holder (the karaoke WS URL is
                             saved on-device by BYOK setup; the
                             setup-helper URL is a constant in
                             KaraokeBridge — it is a public service;
                             the Modal key is never stored)
    KaraokeGeometry.java     diagnostic-only layout measurements
    KaraokeWarmup.java       warm-up ping on player open
    org/chromium/net/        Cronet compile-only stubs (4 files)
```

## What the patch does (verified against 20.40.45 dex)

- **Buttons:** K (karaoke on/off), BV (background vocals).
  The LOG debug button was removed in v1.0.0. CC-style toggle:
  inactive = white text on transparent; active = white-filled
  rounded background + black text.
- **Mute (v28, on the app's own paths):** pause via
  `Laiwx.ar(false/true)`; volume via the player field `ae` +
  method `O` with a read-back check (tolerance 0.01) — the gate
  REFUSES to start rather than risk playing the original audio
  alongside karaoke. Hard rule: never play original audio when
  karaoke is enabled.
  - Trap, documented so nobody re-introduces it: `Laiwx.a()`
    returns PlaybackParameters.speed, NOT volume. The v13 mute
    read called it as volume and could never pass. The synthetic
    was renamed `patch_getSpeed` in v1.0.0 for exactly this
    reason (it was `patch_getVolume` before, and the name lied).
- **Pause/resume:** `Laiwx.M(Z)V` was proven ineffective as a
  pause (it only wrote an already-false flag); v28 uses `ar`.
- **Video ID:** `Laiwx.i (Laiwl)` -> `Laiwl.l (Lajcf)` ->
  `Lajcf.j()String`. (`Laiwx.p()` is a 16-char per-playback
  token, NOT a video ID.)
- **Position:** `e()J` is live position; `d()J` is buffered.
- **Seekbar:** stock. A blue-tint feature (v70–v83) was tried,
  phone-tested, and REVERTED 2026-10-31 — the controls bar's red
  comes from theme-fed paints/drawables the model fields cannot
  reach. Do not re-add seekbar tinting without a new dex-proven
  mechanism.

## Protocol the patch speaks (VERIFIED from server/modal_app.py)

- First message: `{"video_id", "mode": "tunnel"}` (+ optional
  `resume_from_seq`).
- Server fetch: `{"type":"fetch","id","method","url","headers",
  "body_b64"}`; phone replies `fetch_response` (status, headers,
  body_b64) or `fetch_error`. Audio fetches are bounded 512 KiB
  ranges; the phone NEVER downloads the full audio file. The
  server drives the URLs (player request, then audio ranges);
  the phone executes them over its own connection.
- Server header: `{"type":"header","video_id","sample_rate",
  "cold_start","cached"}`. Cache replay is automatic (cached
  header, then chunks, then done).
- Server chunk: `{"type":"chunk","stream":"instrumental","seq",
  "start","opus_b64"}` (and a parallel `"backing"` stream when BV
  is on). **"start" is a SAMPLE index at the header sample_rate
  (44100), not seconds.** Chunks are OGG-wrapped Opus (ffmpeg
  `-f opus`), not raw packets — the player demuxes Ogg first.
  Each chunk is an independent stream with its own OpusHead
  pre-skip; centres tile by HOP = 5.25 s (7 s windows, 25%
  overlap, centre-only).
- Server done: `{"type":"done","chunks":n}`. Server error:
  `{"type":"error","message":"Processing failed. Retry."}`.
- The server's player request goes through the tunnel as a
  **visionOS** client (bootstrap visitor session from youtube.com
  HTML, `X-YouTube-Client-Name: 101`). The old ANDROID 20.10.0
  shape is superseded. Phone logs 2026-10-31 (v79, v82) show no
  403 on audio ranges since the visionOS route landed — but the
  route has no test or log in this repo proving it end to end;
  treat it as phone-observed, not repo-proven.

## BV (background vocals) — real since v56

Stage 2 runs a karaoke BS-RoFormer on Demucs' vocal stem and
streams a second, independent backing-vocal chunk stream; the
phone mixes it live with the instrumental stream. Toggle OFF =
instrumental only; toggle ON = instrumental + backing. Toggle is
instant, mid-song, no re-request. If stage 2 fails, the stream
degrades to instrumental-only without killing the song (backing
is garnish by design). See `server/model_karaoke.py`.

## Decoder (v12 fix — do not regress)

Android's Opus decoder does NOT take OpusTags in csd-1. Per
MediaCodec Opus config: csd-0 = OpusHead (19 bytes), csd-1 =
pre-skip in NANOSECONDS as an 8-byte native-order long, csd-2 =
seek pre-roll in NANOSECONDS as an 8-byte native-order long
(80,000,000 ns). v10/v11 put ASCII "OpusTags" in csd-1, so the
decoder trimmed EVERY sample and produced zero output with no
error. Phone-confirmed fixed 2026-10-26 (decoder
`c2.android.opus.decoder`, every chunk decoded).

## Build (from source — the only supported path)

The dexes are compiled from source; there is no repack base
in the build workflow. The old smali workflow (rebuilt from a
stale v15 `.rvp`) was deleted 2026-10-31, and the v19 workflow
used to ship the v13-era Kotlin dex from that base. Both
failure modes are gone. The v1.0.0 release bundle itself was
assembled locally by substituting the recompiled dexes and
class files into the v14 container — see
`patch/BUILD_REPORT.md` for the exact record.

Local build (same recipe the Actions workflow runs):

```
# 1. Kotlin patch code — PROVEN recipe (2026-10-31). Plain kotlinc
#    fails on the patcher API; these flags are required. With
#    kotlinc 2.2.21 this reproduces the v14 classes.dex
#    byte-identically (md5 4e25db7af92e7d76093c75360e715342).
#    Do NOT bump the kotlinc version without re-running the
#    byte-identity check first.
kotlinc -Xskip-prerelease-check -Xcontext-parameters -jvm-target 17 \
  -cp tools/revanced-cli-6.0.0-all.jar \
  -d /tmp/build/patch-classes \
  patch/patches/src/main/kotlin/app/karaokenow/patch/KaraokePatch.kt

# 2. Extension dex
javac -encoding UTF-8 -cp $ANDROID_JAR -d /tmp/build/ext-classes \
  $(find patch/extension/src/main/java -name "*.java")
d8 --lib $ANDROID_JAR --debug --output /tmp/build/dex-ext \
  /tmp/build/ext-classes/app/karaokenow/extension/*.class

# 3. Kotlin dex (d8, twice — outputs must be byte-identical)
d8 --lib $ANDROID_JAR --debug --output /tmp/build/dex-patch \
  /tmp/build/patch-classes/app/karaokenow/patch/*.class

# 4. Assemble the .rvp (zip from the staging dir, absolute output)
#    classes.dex + META-INF/main.kotlin_module +
#    app/karaokenow/patch/*.class + extensions/karaoke.rve
```

`$ANDROID_JAR` is API 35 (`platforms/android-35/android.jar`),
`d8` is from build-tools 35.0.0, `--debug`, NO `--min-api`.
See `patch/BUILD_REPORT.md` for the full local recipe.

GitHub Actions: `.github/workflows/build-patch-v19.yml`
(manual trigger only) runs the same steps, verifies the bundle
(list-patches load, dexdump confirms all 3 synthetics + the
extension code, MultiDexIO probe, optional full CLI patch run
against a supplied stock APK), and uploads the `.rvp` as a
workflow artifact. It never commits or pushes.

## Verification bar (every build, local or Actions)

1. javac clean (no errors).
2. d8 deterministic (two runs, identical md5s).
3. `list-patches -p <rvp> -b` lists Karaoke, enabled.
4. CLI patch run on stock 20.40.45: `"Karaoke" succeeded`,
   all dexes compiled, APK aligned + signed + saved.
   (Verification-only patched APKs stay LOCAL under
   `~/workspace/yt204045-work/patched-*/` — never sent/uploaded.
   The repo tree never holds Google binaries.)
5. MultiDexIO probe passes (single-dex + container mode).
6. Patched-dex dump confirms the fix and all 3 synthetics
   intact; zero `"setController stubbed"` strings in any dex.
   (The multidex split shifts per build — locate classes by
   string search across all dexes, never assume a dex number.)

## Security — BYOK only, no shared endpoint

- There is NO shared Modal endpoint in the public design. The
  client has no hardcoded karaoke endpoint: the WS URL is saved
  on-device by BYOK setup and the patch refuses to start with
  no saved endpoint. (The setup-helper URL is a constant in
  the client — the helper is a public service by design; it
  validates tokens and rate-limits before spawning anything.
  See `server/README.md`.) Each user deploys the server into
  their own Modal account with their own API key (see
  `server/README.md` and `server/setup_app.py`).
- The Modal key is never stored on-device (in memory only during
  setup, sent once over HTTPS, never written to prefs) and never
  logged. Prefs hold only the endpoint URL + setup job id
  (`MODE_PRIVATE`).
- No secret is ever baked into the patch or the bundle. The
  historical shared endpoint was unauthenticated and its URL
  leaked into old bundles and docs — it must be stopped/rotated
  before the repo goes public; scrubbing the repo alone does not
  close that hole. See the full pre-release review:
  `docs/security-review-2026-10-31.md`.

## Standing rules applied here

- On any failure: surface the error and stop that path. No
  automatic fallback chains — not between fetch sources, not
  between transports, not between mute mechanisms. Alternatives
  are chosen after a failure is logged.
- Every hook, fingerprint, and mechanism claim in this directory
  is labeled VERIFIED (checked against 20.40.45 bytecode or the
  real CLI jar, with evidence cited) or UNVERIFIED (reasoned from
  public patterns or the plan, not checked). Never present an
  unverified hook as working.
- No APK build, bundle delivery, GitHub push, Actions dispatch,
  or Modal deploy without the maintainer's explicit go in the
  current exchange. A push to main auto-triggers nothing here (all
  build/deploy workflows are manual-trigger only), but the rule
  still stands: ask first.
