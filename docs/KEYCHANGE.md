# Automatic Key Change ("my key") — design + research

Status: research complete, design proposed, not built.
Date: 2026-11-07 (Asia/Manila)
Feature by: James. Wording of the ask: "automatic key changer based on
users voice record. user will sing their comfortable key for the
selected song for like 10 seconds. then it will get analyzed and
automatic select key based on that. if possible local processing no
need for modal."

---

## 1. The answer in one paragraph

Pitch shifting can be **100% on the phone, in real time, with no Modal
work and no extra download**, because the patch already owns the whole
PCM path: `Ogg-Opus bytes -> MediaCodec -> own AudioTrack`. We insert a
Sonic (PICOLA/WSOLA) pitch shifter between decode and `AudioTrack.write`.
Pitch-only shifting **does not change duration**, so video/audio sync,
the chunk tiling, and the drift loop all stay exactly as they are. The
only thing the server has to add is one small number: the song's vocal
range, measured for free from the vocal stem Demucs already produces.
The user's 10 s recording is analysed entirely on-device with YIN.
Nothing about this feature needs GPU or a second song download.

---

## 2. Research findings (all verified, sources at the end)

### 2.1 We already own the audio path — this is the whole trick
`KaraokePlayer.java` header comment, verified in code:

```
Ogg-Opus chunks -> MediaCodec -> own AudioTrack
```

The player decodes each chunk to PCM at 48 kHz and writes it to its own
`AudioTrack`. There is no YouTube player involvement. So a DSP stage can
be dropped in with no protocol change, no server change and no re-fetch.

### 2.2 The right pitch shifter: Sonic
- `androidx.media3.common.audio.SonicAudioProcessor` exposes
  `setSpeed()` and `setPitch()` **independently**.
- Underneath is `Sonic.java` — PICOLA/WSOLA, **integer-only math**
  (no FFT), so it is cheap enough for a phone in real time. Apache-2.0.
- **Verified against the actual APK: `Sonic` is NOT present in
  YouTube's dex.** `androidx/media3/common/audio/*` is absent entirely
  (only `media3/exoplayer`, `media3/ui`, `media3/decoder` are there).
  → We must bundle `Sonic.java` as a source file in the extension.
  Your build is `d8` straight from `.java`, so this is just one more
  file. No gradle, no dependency, no version clash (same pattern as the
  existing zero-dependency `KaraokeWsClient`).
- Cost of the shift: `pitch = 2^(semitones/12)`, `speed = 1.0`.
  Output frame count ≈ input frame count → **duration preserved**.

### 2.3 Quality limit — this drives the design
Sonic-class time-domain shifting is clean to about **±3–4 semitones**.
Past ~4 semitones you get phasiness and smeared transients; past ~5 on
voices you get chipmunking without formant correction. Conclusion:

- Fold every shift into **(-6, +6] semitones** (the smallest possible
  move — an octave up and an octave down are the same musical key).
- Soft-guard at **±4**: allowed, but shown as "large shift — may sound
  thin", with the manual +/- buttons right there.
- We never need more than that, because folding already guarantees ≤6.

### 2.4 The detection half is a solved, patented problem
The idea is exactly the classic karaoke auto-key design:
- **US 5,296,643** "Automatic musical key adjustment system for karaoke
  equipment": transpose the accompaniment so its key coincides with the
  **analysed vocal range of the singer**.
- **NTU MSc thesis (2008), "An Automatic Music Key Shifting System for
  Karaoke Machine"**: record the singer, **compare their pitch to the
  original singer's pitch, take the pitch difference, transpose the
  accompaniment** to fit.

So: compare the user's comfortable pitch centre to the song's vocal
pitch centre, take the difference, shift. That is the whole feature.

### 2.5 Song side: free, because Demucs already gives us the vocals
`modal_app.py` already keeps the vocal stem per window
(`SeparatedWindow` returns `vocals`); stage 2 uses it for the backing
split. So a melody profile costs **zero extra GPU** — it is pure numpy
on a mono stem. `librosa.pyin` is the textbook tool, but the Modal image
does not have librosa (deps are fastapi/httpx/torch/torchaudio/demucs/
numpy) and librosa drags numba+llvmlite. **We write YIN in numpy
(~40 lines) instead**: no new dependency, milliseconds per window.

### 2.6 User side: RECORD_AUDIO is already allowed
Verified in `yt-base.apk`'s manifest: `android.permission.RECORD_AUDIO`
and `android.permission.MODIFY_AUDIO_SETTINGS` are **already declared**
(YouTube voice search). `android.media.AudioRecord` and
`android.media.audiofx.AcousticEchoCanceler` are also already in the
app's dex. → No manifest patch, no new permission entry. Only the
normal runtime "allow mic" prompt.

### 2.7 Options rejected, and why
| Option | Why rejected |
|---|---|
| `AudioTrack.setPlaybackParams(pitch)` | Supported, but it is a black box that also fights the existing speed handling, quality is undocumented, and we cannot span a live key change cleanly. Sonic gives us the same thing with control. |
| Server-side shift (librosa / rubber band) per chunk | Melodically worse: per-5.25 s-chunk phase-vocoder with crossfade smears. Plus every semitone value becomes a new cache entry (11× cache and 11× GPU work), and it needs a network round-trip before you can hear the new key. Rejected. |
| Server-side shift once per song, cached | 11 variants per song is unbounded cache growth for something the phone can do for free. Keep as a *quality escape hatch* only if Sonic disappoints (see §9). |
| Rubber Band / SoundTouch native lib | Best quality, but needs an `.so` per ABI inside the extension, which is a much bigger packaging risk than one Java file. Rejected for v1. |
| Web Audio / JS DSP | There is no web view in this path; the patch is Java in the YouTube process. Not applicable. |
| Ask Modal for the melody | Only used for the *song profile* (a few numbers), not for the shift. Kept, cheap, see §4. |

---

## 3. Architecture

```
                 ┌──────────── BEFORE (works today) ────────────┐
  Modal ──opus──►│ KaraokeWsClient ► KaraokePlayer              │
                 │   demux ► MediaCodec ► PCM 48k ► AudioTrack  │
                 └──────────────────────────────────────────────┘

                 ┌──────────── AFTER (this feature) ────────────┐
  Modal ──opus──►│         [ NEW: KaraokeKeyShifter ]           │
   + profile     │  PCM 48k ► Sonic(pitch=2^s/12, speed=1) ► PCM│
                 │            (duration unchanged)              │
                 │                     ► AudioTrack            │
                 └──────────────────────────────────────────────┘

  Mic ► AudioRecord 16k ► [ NEW: KaraokePitchDetector (YIN) ]
                              ► user profile (cents)
                              ► compare with song profile
                              ► s = semitones, folded to (-6,+6]
                              ► KaraokeKeyShifter.setSemitones(s)
```

Everything new is on the phone except one small message.

---

## 4. Protocol change (the only server work)

Header already carries `sample_rate`, `cold_start`, `cached`. Add one
optional message, sent once enough of the vocal stem has been seen
(first ~30–45 s of the song — ready long before a user finishes
a chorus):

```json
{"type":"vocal_profile",
 "version":1,
 "center_cents": 6743.0,     // (p25+p75)/2 of voiced f0
 "p05_cents": 6120.0,        // range, for the UI meter
 "p95_cents": 7280.0,
 "keys": [{"tonic":"A","mode":"minor","score":0.71}],  // optional, for the label
 "voiced_ratio": 0.62}
```

- Computed in numpy YIN on the vocal stem windows already in hand.
- Cached next to the existing replay cache keyed by `video_id`.
- Costs no GPU. Client treats it as optional: if it never arrives, the
  key test falls back to a no-reference mode (§5.4).

---

## 5. The algorithm

### 5.1 Pitch detector (same code both sides, ~150 lines)
- Mono, 16 kHz (phone) / 22.05 kHz (server), frame 2048, hop 320 (20 ms).
- **YIN**: difference function via FFT, cumulative mean normalised
  difference, absolute threshold 0.15, parabolic interpolation of the
  minimum. (de Cheveigné & Kawahara, 2002.)
- Search range **65–1000 Hz** (C2–B5), covers bass to soprano.
- Voiced only if: YIN confidence passes **and** frame RMS above a
  −45 dBFS gate. → no pitch invented from silence or breath.
- Output per frame: f0 in Hz, or "unvoiced". Convert to **cents**
  (`1200*log2(f/55)`) so all averaging is musical, not linear in Hz.

### 5.2 User profile (on-device, from the 10 s recording)
- Take all voiced frames → cents.
- Drop obvious octave slips: keep frames within ±14 semitones of the
  median, recompute (one pass, cheap).
- **`center` = (p25 + p75) / 2.** Median-ish, robust to a shaky last
  note and to one bad frame.
- Also keep `p05/p95` to show the user their range and to sanity-check
  ("that's very low, try again?" if the spread is absurd).
- 10 s a cappella at 20 ms hop = ~500 frames, ~300+ voiced typically.
  Well over enough.

### 5.3 Choosing the shift
```
delta_semitones = (song.center_cents - user.center_cents) / 100
s = round(delta_semitones)
fold: while (s >  6) s -= 12;
      while (s <= -6) s += 12;      // smallest move wins
```
- Folding is what makes this musical: shifting up 7 = shifting down 5.
- No snapping to the "nearest key" is needed — a whole-track transposition
  preserves every interval, so any integer semitone is still musically
  valid. Snapping would only be needed if we shifted notes individually.
- Clamp to ±6 always (fold already guarantees it); UI flags |s| ≥ 5.

### 5.4 If the song profile never arrives
Fallback: assume the song sits around A4-ish centre and shift to put the
user's centre in the middle of a comfortable singing band; effectively
"no reference, just pick 0". We prefer to show "key detection
unavailable — use +/-" rather than guess. No silent wrong answer.

### 5.5 Why the user sings a cappella (not along with the song)
If the song plays through the speaker while recording, the mic hears
both and the detector locks onto the *song*, not the user. Three ways
out: headphones, AEC, or silence. **Silence is free and always works**
(`AcousticEchoCanceler` is available as a later improvement for the
headphone-free "sing along" mode). So v1: video is muted for the 10 s,
user sings the chorus (or any phrase, or just hums) in whatever key
feels natural — we are measuring *their* comfortable tessitura, which is
independent of the song. That is exactly what the patent claims cover.

---

## 6. Code touch points (file by file)

New files in `patch/extension/src/main/java/app/karaokenow/extension/`:
| File | Lines (est.) | Job |
|---|---|---|
| `Sonic.java` | ~1,900 | verbatim from `platform/external/sonic` (Apache-2.0), package unchanged, header kept |
| `KaraokeKeyShifter.java` | ~200 | wraps Sonic: `setSemitones(int)`, `process(short[],int)`, crossfade on live change, fixed-latency output |
| `KaraokePitchDetector.java` | ~220 | YIN + voicing, streaming API, `cents[]` out |
| `KaraokeVoiceProfile.java` | ~120 | percentile/centre math, JSON in/out, folding |
| `KaraokeKeyTest.java` | ~350 | the 10 s capture flow: AudioRecord, level meter, countdown, result |

Edits to existing files:
| File | Change |
|---|---|
| `KaraokePlayer.java` | after decode + before `track.write`: run PCM through `KaraokeKeyShifter`. Trim/pad chunk to the nominal 252000 samples so tiling is untouched. |
| `KaraokeSession.java` | hold `volatile int semitones`; expose `setKey(int)`; pass profile from header to the UI. |
| `KaraokeWsClient.java` | handle `type == "vocal_profile"`, forward to listener. ~10 lines. |
| `KaraokeBridge.java` | UI: "My key" button → key test sheet; +/- buttons; label "Key -2" / "Key +1"; persist the last choice per video id. |
| `server/modal_app.py` | emit `vocal_profile` once ~30 s of vocal stem is collected; add to replay cache. |
| `server/pipeline.py` | new `vocal_profile(vocals_pcm)` — numpy YIN + percentiles. ~60 lines. |

---

## 7. Real-time DSP constraints (the parts that bite)

1. **One persistent Sonic instance per session, not one per chunk.**
   Sonic has internal latency and needs a continuous stream. Feeding it
   chunk after chunk in order means its latency is a single constant
   delay (< ~100 ms), not a per-chunk error. Per-chunk instances would
   insert 70 boundary discontinuities.
2. **Trim after shifting.** The player trims each chunk to exactly
   `start[N+1]-start[N]` (nominal 252000 samples) so boundaries land on
   server starts. Keep that trim *after* the shifter, and account for
   Sonic's constant delay in the audio clock baseline once at start.
3. **Live key change** (`+`/`-` mid-song): Sonic must be flushed and
   re-primed (`flushStream()`), which costs ~40 ms of silence. Crossfade
   the last 20 ms in/out. One change per second maximum; no re-request,
   no re-download, no desync.
4. **CPU**: Sonic is O(n) with small integer ops; on 8 cores at 48 kHz
   stereo it is well under 5 % of one core. It runs on the existing
   decode thread, so no new thread and no new jitter.
5. **Two streams**: instrumental and backing are mixed into one PCM
   before writing (already the case). Shift **after** the mix — one
   instance, both streams move together.
6. **Don't shift the video.** Video stays at original speed; only audio
   pitch changes. Duration identical → nothing to re-sync.

---

## 8. UX flow

```
[Karaoke] tap
   └─ Key: 0  (song's original key)     ← always the default
        [ My key ]
             ├─ "Sing the chorus in whatever key is comfortable. 10 s."
             ├─ video mutes, 3-2-1 countdown, level meter, [Stop]
             ├─ "Listening…"  (bars move with your voice)
             ├─ result: "Your key: -2  (song moves down 2)"
             │     [ Keep ]  [ Try again ]  [ Manual ]
             └─ [ Manual ]:  −  [ Key −1 ]  +   (1 semitone steps)
```
- Result is applied to audio already flowing — takes effect in ~40 ms.
- Saved per video id, so a second play of the same song starts in your
  key. "Reset to original" always one tap.
- |s| ≥ 5 shows a small "large shift, may sound thin" note.

---

## 9. Risks and mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| Sonic quality at ±5/6 is audibly thin | Medium | Fold to ≤±6; flag ≥5; manual override; escape hatch = cached server-side high-quality shift for the 4 most common values if it really disappoints |
| YIN locks to the wrong octave on breathy singers | Medium | ±14 semitone outlier rejection + p25/p75 centre (octave errors move p95 not the centre); show the detected range so the user sees if it's wrong |
| Mic picks up the song (if not muted) | High if ignored | v1 mutes for the test; AEC later for a "sing along" mode |
| Switch to a non-default audio path shifts sync | Low | pitch-only, duration preserved, constant delay baseline |
| Live change causes a click | Low | 20 ms crossfade + 1/sec limit |
| Bundling Sonic adds ~1,900 lines to the dex | Low | +~40 KB dex; no dependency, package-private, no clash |
| User sings something unrelated | Medium | we measure tessitura, not melody, so *any* comfortable singing works. UI says so plainly |
| Per-video saved key drifts if we later change the algorithm | Low | version the profile (`version` field) |

---

## 10. Spikes (prove before building, per the project rule)

**S1 — YIN on a real phone recording (no patch, runs today)**
Record 10 s with `termux-microphone-record`, decode to 16 kHz mono,
run the YIN code, print cents + percentiles. Done when: pitch tracks
your actual notes, stays within a couple of cents while holding a note,
and the centre matches what a tuner app says.
→ *Can be run on this device right now.*

**S2 — Sonic in isolation (Java, on-device)**
Tiny harness: read a WAV, shift +3 / −3, write WAV. Done when: durations
match to within a few ms and no clicks at ±3.

**S3 — Sonic inside KaraokePlayer**
Patch build with the shifter hardcoded to −2. Done when: a full song
plays with no drift, no boundary clicks, and the sync loop reports the
same residual drift as a normal session.

**S4 — Song profile from the vocal stem**
Run the numpy YIN over a cached song's vocal stem, eyeball the centre
against the song's known range. Done when: centre lands within ~1
semitone of where it should be for 3 songs.

**S5 — End to end**: sing, get a key, hear the change mid-song.

**S6 — Manual +/- buttons** (free byproduct of the shifter).

Build order: S1 → S2 → S3 → S4 → S5 → S6.

---

## 11. Cost

- GPU: **zero additional.** No new Modal runs, no new model.
- Data: **zero additional.** Same audio, shifted locally.
- Cache: the profile is a few hundred bytes next to the existing replay
  cache entry.
- Phone: one integer-heavy DSP pass and one mic capture. This is the
  cheapest feature in the whole project.

---

## Sources
- androidx SonicAudioProcessor (independent pitch/speed):
  https://developer.android.com/reference/androidx/media3/common/audio/SonicAudioProcessor
- Sonic.java source, PICOLA, integer math:
  https://android.googlesource.com/platform/external/sonic/+/refs/heads/master/Sonic.java
- Sonic docs: https://android.googlesource.com/platform/external/sonic/+show/refs/heads/main/doc/index.md
- AudioTrack/PlaybackParams pitch without speed (rejected option):
  https://developer.android.com/reference/android/media/PlaybackParams
- Shift-size quality limits: https://soundgen.ai/blog/how-to-change-the-key-of-a-song ,
  https://lordreverb.com/plugins/pitch-shifter-plugins-guide/
- Auto key by singer vocal range (patent): https://exa.ai/library/legal/patent/803ybm1yzqp
  (US 5,296,643) ; https://exa.ai/library/legal/patent/lq6vgsd657j (US 11,580,944, AIROHA)
- Compare singer pitch to original singer, transpose accompaniment (thesis):
  https://tdr.lib.ntu.edu.tw/jspui/handle/123456789/27307
- pYIN for f0 contour from a vocal stem:
  https://librosa.org/doc/latest/generated/librosa.pyin.html
- YIN: de Cheveigné & Kawahara, "YIN, a fundamental frequency estimator
  for speech and music", JASA 2002.
- Local facts verified in this repo/device: `KaraokePlayer.java` (PCM
  path), `modal_app.py` (vocal stem in hand), `yt-base.apk` manifest
  (RECORD_AUDIO already declared), dex scan (no `media3/common/audio`,
  Sonic must be bundled).
