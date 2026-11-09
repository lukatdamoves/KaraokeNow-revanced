# PAUSED — pickup note

Written 2026-11-07 for James, because he is stopping here with no
day off in sight. Read this first when you come back. Everything below
is verified, not remembered.

---

## The 30-second version

The key-change feature is **built and shipped as a file**, not released.
It sits in your Download folder. Nothing is half-saved anywhere.

- File: `Download/karaoke-patches-v1.1.0.rvp` (116 KB, md5 `f7ebf69962450c90fe43c61d50fd6137`)
- Built by GitHub Actions run **42403045481**, all 12 verification steps green
- Repo: `github.com/lukatdamoves/KaraokeNow-revanced`, branch `main`, commit `3b4646d`
- NOT published as a GitHub Release. NOT added to `karaoke-patches-bundle.json`.
  So ReVanced Manager still only sees v1.0.0. That was deliberate.

## What it does

Tap **KEY** (next to K and BV). Panel opens:

- **+ / −** — shift the instrumental one semitone. Instant (~85 ms),
  works mid-song, no re-download, no server call.
- **My key** — record 10 s of you singing, it measures your comfortable
  pitch and picks the key. ~11 s total. Fully on-device.
- **Use auto suggestion** — needs the server's `vocal_profile` message.

## What is PROVEN vs what is not

Proven (numbers, not vibes):
- Sonic shifts +2 semitones -> measured **+2.003**. Sample count
  preserved exactly, so sync/chunk tiling cannot drift.
- 0 semitones is a hard bypass: output is **bit-identical** to v1.0.0.
  Turning the feature on cannot regress a normal session.
- Full 18-file javac against android.jar (API 28) = clean, exit 0.
- d8 -> 186 KB dex, every new class present in the built bundle.
- Server YIN lands on the right centre (tested: 3876 cents ~ 516 Hz
  on a synthetic 4-note melody).

NOT proven:
- **Never heard on a real song on the phone.** I cannot listen. This is
  the one thing left to check. Play a song, tap KEY, tap -, and judge it.
  If it sounds thin or metallic past -2, that is the known Sonic limit
  (clean to about +-3..4 semitones) and it is a tuning question, not a
  broken build.
- Auto mode was never exercised end to end, because that needs the
  Modal redeploy below.

## To pick it up again

1. Install `Download/karaoke-patches-v1.1.0.rvp` in Manager, patch
   YouTube as usual.
2. Play a song, tap KEY, tap "-" once. Judge the sound.
3. Only if you want auto mode: run the **Deploy Modal karaoke server**
   workflow (~2 min). That turns on the `vocal_profile` message.
   Without it, "My key" measures your voice but says the song
   reference is not ready, and +/- still works fine.

## Two CI fixes you got along the way (worth not losing)

The build was **already broken before this feature**, in two ways:

1. `android-actions/setup-android` asks for the `tools` SDK package,
   which Google removed. Whole job died before compiling anything.
2. After dropping that action, `sdkmanager` is no longer on PATH, so the
   step must call it by absolute path.

Both fixed in commit `3b4646d`. Any future build depends on this.

## The OTA question, settled

James asked whether the patch can OTA from GitHub forever with one
release. Answer: **no, not fully.**

- Server changes: yes, fully OTA (redeploy, everyone gets it).
- New-patch-discovered: yes (Release + bump `karaoke-patches-bundle.json`,
  Manager offers it, user re-patches).
- Patch *code* itself: **no** - it lives inside the patched YouTube APK.
  A new dex always means re-patch + reinstall.

Because it is not fully OTA, **James decided not to build the OTA
plumbing at all.** Do not add it later "because it would be nice".
He explicitly did not want it.

## Design notes worth keeping

- `docs/KEYCHANGE.md` has the full research: why on-device, why Sonic,
  why the shift is folded into (-6,+6], the rejected options, the risks,
  and the spike list.
- The big insight: `KaraokePlayer` already owns the PCM path
  (`Ogg-Opus -> MediaCodec -> own AudioTrack`), so a DSP stage drops in
  with no protocol change and no extra download.
- Sonic counts **frames**, not interleaved samples. Getting this wrong
  throws an exception that a catch-all hides, and the feature silently
  does nothing. That is why `KaraokeKeyShifter` now logs failures loudly.
- Sonic is NOT in YouTube's dex, so it ships with us, and its package
  must be `app.karaokenow.extension` because the CI d8 glob only dexes
  that package.

## Local scratch (safe to delete, may be big)

`~/tmp` holds ~900 MB of scratch (dex builds, ffmpeg logs, test wavs,
the old APK decompile from October). Nothing there is needed to resume.
