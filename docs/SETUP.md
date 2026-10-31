# Development setup

How to set up a machine to build the patch and run the server
tests. Nothing here deploys anything: all build and deploy
workflows are manual-trigger only.

## Toolchain

- **JDK 17.** Required by the ReVance CLI and d8. Use
  `JAVA_HOME` pointing at the JDK; `java` need not be on PATH.
- **Android SDK, API 35.** `platforms/android-35`
  (`android.jar`) and `build-tools/35.0.0` (d8, dexdump).
  d8 flags are pinned: `d8 --lib android.jar --debug`, no
  `--min-api` — that exact invocation reproduces prior dex
  output byte-identically.
- **Kotlin 2.2.21 (pinned).** The kotlinc recipe in
  `patch/README.md` reproduces the v14 `classes.dex`
  byte-identically with 2.2.21 (MD5
  `4e25db7af92e7d76093c75360e715342`). Do not use a different
  kotlinc version without re-running the byte-identity check
  first — a different compiler version produces different
  bytecode. The CI workflow pins 2.2.21 by SHA-256.
- **ReVance CLI 6.0.0.** Download the official release asset
  (`revanced-cli-6.0.0-all.jar`) and verify its SHA-256
  (`c25549bc17d59d2eb94fa5f86e60e9b77a02772ca88f7050f8f1276f923a9958`).
  The jar is gitignored (`tools/`) — never commit it.
- **Python 3.12 + pytest.** Server tests run in a venv
  (`.venv`): `python -m pytest tests/ -q`. The tests need no
  torch, no network, and no Modal access. System Python may
  lack httpx — always use the venv.
- **Modal CLI 1.6.2.** Installed in the venv. Only needed for
  deploys, which are manual (see below).
- **ffmpeg 8.x.** Used for audio chunking and test
  conversions.

## Stock YouTube APK (pinned 20.40.45)

The stock APK is Google's binary and is NOT stored in this
repo. Get YouTube 20.40.45 (version code 1557278144) from
ReVance Manager on the phone (it fetches the APK itself) or
an APK extractor. Verification-only patched APKs stay local
and are never sent or uploaded.

## Server tests

    python -m pytest tests/ -q

54 tests pass (1 pre-existing Starlette deprecation warning).
See `server/README.md` for what is covered and what is not.

## Deploy flow (manual only)

Deploys happen through GitHub Actions, manual trigger only:

1. Push the change to `main`.
2. For the karaoke server: dispatch `stop-modal.yml`
   (stop-first clears stale containers), then
   `deploy-modal.yml`. Watch the run; the workflow
   smoke-checks `/health` 40 times and fails loudly if
   it never returns 200.
3. For the setup helper: dispatch `deploy-setup.yml`.

**Redeploy-helper rule (do not skip):** the setup helper
(`server/setup_app.py`) bakes `server/` into its image at
the time `deploy-setup.yml` runs. Any `modal_app.py` /
`fetch.py` / `pipeline.py` / `model_*.py` change after that
is silently ignored by the helper until `deploy-setup.yml`
is re-run — users would get an old server with old bugs.
Re-run `deploy-setup.yml` after every server change.

## Notes

- The sandbox this project was developed in cannot reach
  Modal's API directly (gRPC ignores the proxy), so GitHub
  Actions is the only route for Modal API work (deploys,
  stops, logs). The sandbox can curl public HTTPS.
- APKMirror returns 403 to automation; do not count on a
  direct download of the stock APK.
- The historical shared Modal endpoint is unauthenticated
  and its URL leaked into old bundles and docs. It must be
  gated or stopped before the repo goes public — see
  `docs/security-review-2026-10-31.md`.
