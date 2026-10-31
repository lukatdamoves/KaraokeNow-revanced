# KaraokeNow for YouTube

Sing along to any YouTube video — with the vocals removed.

KaraokeNow adds a karaoke button to the YouTube app. Tap **K** on any
video and the singing disappears, leaving just the music, playing in
perfect sync with the video. Tap **BV** to bring the backing vocals
back in, live, mid-song.

No pre-patched app. No account. You run the vocal-removal server
yourself, on your own cloud credits, so nobody else's server and
nobody else's bill is involved.

## What it does

- **Tap K, sing.** The video keeps playing, muted. The music —
  vocals removed — streams in and stays in sync, even when you
  pause, seek, or change speed.
- **Backing vocals on demand.** The **BV** button mixes the backing
  vocals back in while you sing, or takes them out again. No
  reloading, no waiting.
- **Your server, your credits.** The vocal removal runs on a small
  cloud server in your own account. You set it up once with your
  own key, and it stays yours.

## Get it

1. Install **ReVanced Manager** and add this repo as an alternative
   patches source (Settings → Alternative sources).
2. Pick **YouTube 20.40.45** and select the **Karaoke** patch.
   Patch it and install the result. Turn off auto-update for
   YouTube so it stays on this version.
3. Open YouTube, open any video, tap **K**.

That's it. The first song takes a little while to start while the
server warms up (up to about a minute). After that, songs start in
seconds.

## Set up your server

KaraokeNow needs a server to remove the vocals. You run it in your
own Modal account — it costs a few cents per song, on your credits,
and nobody else can use it.

1. Make a free Modal account and create an API token at
   https://modal.com/settings/tokens. Keep that token private —
   it can spend your credits.
2. In YouTube, long-press **K** and paste your token. The app
   deploys your server and saves its address on your phone. The
   token is sent once, over HTTPS, and never stored on the phone.
3. Tap **K** on a video and sing.

Your server is protected by a key, so only your phone can use it.
You can also deploy it yourself from the command line if you
prefer — see `docs/SETUP.md`.

## What you need

- Android, with ReVanced Manager
- YouTube **20.40.45** (the patch targets this version only)
- A Modal account (free to create) for the vocal-removal server

## Questions

**Is it free?**
The patch is free and open source (GPLv3). The vocal removal
runs on your own cloud server, so you pay Modal's usage cost —
a few cents per song. There is no subscription and no shared
server to overload.

**Does it work on any video?**
Any video with music. The server separates the vocals from the
music, so it works on songs, live performances, and covers.

**Does it change the video?**
No. The video plays exactly as normal, just muted, while the
clean music plays alongside it in sync.

**What if the music and video drift apart?**
The app watches the video position and re-syncs on its own.
If it ever fails, it stops and tells you, instead of letting
the original singing leak through.

**Is my key safe?**
Your Modal token is never stored on the phone and never put in
the patch. Your server's key is stored only on your phone and
in your own accounts. Nothing secret is baked into the patch
file — anyone can read the code and confirm that.

**Where do I report a problem?**
Open an issue on this repo. If the app shows a failure message,
include it — it says what went wrong without sharing anything
private.

## What's next

Planned, not promised:

- **Audio key changer** — shift the song's key up or down to
  fit your voice (-2, -1, +1, +2), without changing the speed.
- **Switch Modal accounts** — change which server the app uses,
  from a saved list, without redoing setup.

## For developers

Everything technical lives in the docs, not on this page:

- `docs/SETUP.md` — full setup guide, including manual deploy
- `patch/README.md` — how the patch is built and verified
- `patch/BUILD_REPORT.md` — the v1.0.0 build record
- `docs/PHASE1.md` — the build log, start to finish
- `docs/security-review-2026-10-31.md` — the pre-release
  security review
- `server/README.md` — the server, its API, and its tests

Builds and deploys are manual-trigger only. Nothing is built or
deployed without the maintainer's explicit go, and a failure
stops that path instead of falling back to something else.

## License

GPLv3 — see `LICENSE`.
