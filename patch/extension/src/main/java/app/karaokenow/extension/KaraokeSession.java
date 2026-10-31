package app.karaokenow.extension;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * One karaoke session: WS tunnel client + karaoke player + mute gate
 * + drift loop. This is the only class the patch calls (via
 * KaraokeBridge).
 *
 * Anti-overlap rule (hard, from the plan): original audio and
 * karaoke audio are NEVER on at the same time. Ordering at start:
 * the original keeps playing during load; the moment the first
 * karaoke chunk is ready, the session mutes the original FIRST and
 * then starts karaoke playback at the video's current position, in
 * the same call sequence. On stop or error, karaoke stops and the
 * original is unmuted (cleanup, not a fallback: it restores the
 * user's audio, it does not try another karaoke source).
 *
 * Failure policy (standing rule): v54 auto-reconnects a cut
 * socket (play out the buffer, replay from the resume seq,
 * dup-drop overlap, resume flawlessly). Only an exhausted
 * reconnect (or a pre-playback failure, mute refusal, or load
 * stall) puts the session in ERROR and stops. There is NO
 * alternate source — one server, one honest path.
 *
 * Cache: the server replays cached songs automatically (header
 * cached=true, then chunks, then done). The session treats a cached
 * stream exactly like a live one; no client-side cache logic exists
 * in v1.
 *
 * Logging (tag "KaraokeNow", see KaraokeLog): every state
 * transition, the mute/unmute outcome, every WS event (via the
 * client), every drift correction with millisecond values, and on
 * any exception a state snapshot plus the full stack trace. No
 * exception in a karaoke thread is ever swallowed silently: the
 * sync loop and the connect thread log a snapshot and fail the
 * session cleanly. Video IDs are logged; full URLs and the BYOK
 * key are never logged.
 */
public class KaraokeSession implements KaraokePlayer.Listener {

    public enum State {
        IDLE, CONNECTING, BUFFERING, PLAYING, PAUSED, ERROR, DONE
    }

    public interface Listener {
        void onState(State state);
        void onError(String message);
        void onFirstAudio();
        /** v54: loading veil text (progress / ETA). */
        void onLoadingProgress(String text);
        /** v54: server cold start — show the cold-start text. */
        void onColdStart();
        /** v54: mid-play rebuffer veil (forward seek beyond
         *  coverage). Text carries the target. */
        void onRebufferStart(String text);
        /** v54: rebuffer veil progress. */
        void onRebufferProgress(String text);
        /** v54: rebuffer done — hide the veil. */
        void onRebufferEnd();
        /** v54: playing session saw a new video. The session
         *  already stopped itself; the bridge restarts on the
         *  new video when K is sticky, else stays stopped. */
        void onVideoSwitched(String newVideoId);
    }

    /** Video-side facts the patch wires to real player hooks.
     *  Wired by KaraokeBridge to Laiwx synthetics: position via
     *  patch_getPosition = e(), playing state via
     *  patch_isPlaying = Q(), speed via patch_getSpeed = a()
     *  (PlaybackParameters.speed — the old patch_getVolume name
     *  lied; a() returns speed, not volume). pause()/resume()
     *  drive the app's own pause/resume path via Laiwx.ar(Z, Z)
     *  — ar(false, true) pauses, ar(true, true) resumes. The
     *  v13 patch_pause/patch_resume -> M(Z)V synthetics only
     *  wrote a field that was already false, so they no-op'd
     *  and are dead. Each returns true if the call went
     *  through, false if it could not be made (never throws). */
    public interface VideoPosition {
        long positionMs();
        boolean isPlaying();
        /** YouTube speed setting, 1.0 = normal. */
        float speed();
        /** Pause playback (playWhenReady false). */
        boolean pause();
        /** Resume playback (playWhenReady true). */
        boolean resume();
        /** v29: the app's own audio stream URL + bitrate from
         *  its player response (Lajcf.z format lists), as
         *  {url, bitrateBps} or null if unavailable. The URL
         *  itself is never logged (host + itag only). */
        String[] appAudioInfo();
        /** v30: current 11-char video ID, or null if unreadable.
         *  Polled by the sync loop as a video-switch watchdog. */
        String videoId();
    }

    /** Mute control for YouTube's own player. Wired by
     *  KaraokeBridge to the player itself: volume is set via
     *  the player's O(float) method and read back from its
     *  `ae` field. (The old patch_getVolume = a() /
     *  patch_setVolume = L() wiring was wrong — a() returns
     *  speed, and L() routes through as(), which cannot take
     *  0.0f.) A controller that reports false blocks karaoke
     *  start by design (safe default: no mute proof, no
     *  karaoke audio, never both audios). */
    public interface MuteController {
        boolean isMuted();
        /** Returns true if the mute actually took effect. */
        boolean setMuted(boolean muted);
        /** v53: verify the mute still holds and repair it.
         *  The video stutter/rebuffer path can hand karaoke a
         *  NEW internal player (full volume) or reset the
         *  volume — either returns the ORIGINAL audio mid-song.
         *  syncTick calls this every 500ms during PLAYING.
         *  Returns a KaraokeBridge tri-state:
         *  KEEP_MUTED_OK (verified ~0), KEEP_MUTED_BLIND
         *  (player unresolvable or read-back unavailable —
         *  could not verify), KEEP_MUTED_FAILED (verified NOT
         *  muted / re-mute failed). Never throws. The session
         *  fails after 2 consecutive FAILED results or 20
         *  consecutive BLIND ticks. */
        int keepMuted();
    }

    // Drift loop, from the plan: poll ~2x/s; <80 ms ignore;
    // 80-500 ms nudge speed; >500 ms hard-seek.
    private static final long SYNC_PERIOD_MS = 500;
    // v44: NO playback-rate nudges — setPlaybackRate pitch-
    // shifts the karaoke (sounds off-key). Inside 350ms we do
    // nothing; beyond it we jump (sample-accurate, pitch-pure).
    private static final long IGNORE_MS = 350;
    private static final long SEEK_MS = 500;
    private static final long SEEK_JUMP_MS = 1000;
    /** v53: two-chunk gate. One 5.25s chunk barely covers the
     *  ~4s next-chunk decode on mid-range SoCs, so the writer
     *  underran 1-2s at every start. 6.0s needs the 2nd chunk
     *  decoded before the hold releases — flawless from the
     *  first frame (costs ~1 extra loading second now that
     *  decode is 4x faster). */
    private static final double START_BUFFER_SEC = 6.0;

    private final KaraokeSettings settings;
    private final VideoPosition video;
    private final MuteController mute;
    private final Listener listener;

    private KaraokeWsClient client;
    private KaraokePlayer player;
    private ScheduledExecutorService syncExec;
    private ScheduledFuture<?> syncTask;
    /** v58: the sync worker (self-stop must not interrupt it). */
    private volatile Thread syncThread;
    private volatile State state = State.IDLE;
    private volatile String videoId;
    private volatile int sampleRate = 44100;
    private volatile boolean firstAudioFired;
    private volatile boolean stopping;

    private long lastVideoPosMs = -1;
    private long lastPollMs = -1;
    /** Last chunk seq received from the server (-1 = none yet). */
    private int lastChunkSeq = -1;
    /** True if this session paused the video at start and has
     *  not resumed it yet (v13 loading behaviour). Only then
     *  does stop/gate resume it — never touch a video the
     *  session did not pause. */
    private boolean pausedByKaraoke;
    /** v27: what the (unreliable) isPlaying flag said at start.
     *  Diagnostic only now (logged at start): cleanup resumes
     *  on pausedByKaraoke, not on this flag — Q() proved
     *  unreliable (false while playing), so gating cleanup on
     *  it could strand a video the session had paused. The
     *  gate still always resumes once karaoke is ready (a K
     *  tap means play karaoke). */
    private boolean wasPlayingFlag;
    /** True while karaoke is held (paused) because it runs ahead
     *  of the video; cleared once video catches up. Seeking audio
     *  backward is impossible from a consumed queue, so holding is
     *  the only correct response to karaoke-ahead drift. */
    private boolean holdingForSync;
    /** Last mirrored video playing state, for transition-only logs. */
    private Boolean lastMirroredPlaying;
    /** M1: consecutive sync ticks where keepMuted() reported
     *  FAILED (verified not muted) / BLIND (unverifiable).
     *  The session fails after 2 FAILED or 20 BLIND in a row;
     *  either counter resets on OK (a tick of the other kind
     *  also breaks the streak). */
    private int consecutiveMuteFailures;
    private int consecutiveMuteBlindTicks;
    /** M9: consecutive sync ticks aborted by a failed
     *  position/playing-state read. Fails after 4. */
    private int consecutiveTickFailures;
    /** M11: last plausible speed read (1.0 = normal). A failed
     *  or implausible speed() read reuses this instead of
     *  falling back to 1.0; speedUncertain suppresses the
     *  hard-seek while the read is bad. */
    private float lastGoodSpeed = 1.0f;
    private boolean speedUncertain;
    /** M12: true while this session paused the video for a
     *  rebuffer or a drained reconnect (separate from
     *  pausedByKaraoke, which only covers the start pause).
     *  stopInternal() resumes the video when it is set. */
    private boolean pausedForRebuffer;
    /** M8: last positionMs() value seen by syncTick (-2 = no
     *  reading yet) and the tick it last changed on. A
     *  "playback gone" tick only counts when the position is
     *  also unchanged/negative — Q() == false alone never
     *  stops karaoke. */
    private long lastPositionMs = -2;
    private long lastPositionChangeTick = -1;
    private long syncTickCount;

    /** Stage-2 toggle: the server streams a real backing-
     *  vocal stem beside the instrumental, and the player mixes
     *  it live when this is on. Stored here so the player can be
     *  switched mid-song with no re-request. */
    private volatile boolean backingVocalsEnabled;

    public KaraokeSession(KaraokeSettings settings, VideoPosition video,
            MuteController mute, Listener listener) {
        this.settings = settings;
        this.video = video;
        this.mute = mute;
        this.listener = listener;
        this.backingVocalsEnabled = settings.backingVocalsEnabled;
    }

    /** v30: video ID this session is for (watchdog checks). */
    public String sessionVideoId() {
        return videoId;
    }

    public State state() {
        return state;
    }

    private void setState(State s) {
        State old = state;
        state = s;
        KaraokeLog.i("session state: " + old + " -> " + s
                + " (videoId=" + videoId + ")");
        listener.onState(s);
    }

    public void start(String videoId) {
        if (state != State.IDLE && state != State.ERROR
                && state != State.DONE) {
            KaraokeLog.w("session start ignored: already " + state);
            return;
        }
        KaraokeLog.clearLastFailure();
        stopInternal(false);
        this.videoId = videoId;
        this.firstAudioFired = false;
        this.stopping = false;
        this.holdingForSync = false;
        this.lastVideoPosMs = -1;
        this.lastPollMs = -1;
        this.lastChunkSeq = -1;
        this.lastMirroredPlaying = null;
        this.serverDone = false;
        this.goneTicks = 0;
        this.lastSeenVPosMs = -1;
        this.frozenTicks = 0;
        this.videoStalled = false;
        this.blindTicks = 0;
        this.consecutiveMuteFailures = 0;
        this.consecutiveMuteBlindTicks = 0;
        this.consecutiveTickFailures = 0;
        this.lastGoodSpeed = 1.0f;
        this.speedUncertain = false;
        this.pausedForRebuffer = false;
        this.lastPositionMs = -2;
        this.lastPositionChangeTick = -1;
        this.syncTickCount = 0;
        this.lastHardSeekMs = 0;
        this.streamCut = false;
        this.videoMismatchTicks = 0;
        this.pausedByKaraoke = false;
        this.wasPlayingFlag = false;
        this.lastGateQueuedSec = 0;
        this.pendingSeekValueMs = -1;
        this.pendingWasPlaying = false;
        this.rebuffering = false;
        this.reconnecting = false;
        this.reconnectGen++;
        this.reconnectThread = null;
        this.reconnectVeilShown = false;
        this.wsGen = 0;
        KaraokeLog.i("session start: videoId=" + videoId
                + " wsHost=" + KaraokeLog.hostOf(settings.wsUrl));
        setState(State.CONNECTING);
        loadStartMs = System.currentTimeMillis();
        lastGateGrowthMs = loadStartMs;
        lastProgressMs = 0;
        try {
            loadTargetSec = video.positionMs() / 1000.0;
        } catch (Exception e) {
            loadTargetSec = 0;
        }
        if (loadTargetSec < 0) loadTargetSec = 0;
        // v13 loading behaviour (like the extension's prepare
        // veil): pause the video now, show the loading overlay
        // (via onState(CONNECTING) in the bridge), and resume
        // ONLY when vocal-removed audio is ready (the
        // first-audio gate). M4: a pause failure is NOT
        // silently tolerated — if pause() returns false or
        // throws, pre-mute immediately (with read-back) so the
        // original cannot play during load; if the pre-mute
        // also fails, the session fails honestly instead of
        // starting unmuted.
        // v27: ALWAYS attempt pause (result James wants: picture
        // stops at tap, resumes with karaoke). The isPlaying flag
        // proved unreliable (false while playing), so the ACTION
        // no longer depends on it — ar(false) no-ops if already
        // paused. wasPlayingFlag only records what Q() said
        // (diagnostic); cleanup resumes on pausedByKaraoke.
        try {
            try {
                wasPlayingFlag = video.isPlaying();
            } catch (Exception ie) {
                wasPlayingFlag = false;
                KaraokeLog.e("session start: isPlaying read threw", ie);
            }
            pausedByKaraoke = video.pause();
            KaraokeLog.i("session start: pause result=" + pausedByKaraoke
                    + " (flag said playing=" + wasPlayingFlag + ")");
        } catch (Exception e) {
            pausedByKaraoke = false;
            KaraokeLog.e("session start: pause threw", e);
        }
        if (!pausedByKaraoke) {
            // M4: video still playing (or pause state unknown)
            // — mute NOW so the load cannot play the original.
            boolean preMuted = false;
            try {
                preMuted = mute != null && mute.setMuted(true);
            } catch (Exception e) {
                KaraokeLog.e("session start: pre-mute threw", e);
            }
            KaraokeLog.i("session start: pause failed, pre-mute result="
                    + preMuted);
            if (!preMuted) {
                fail("Video would not pause and pre-mute failed; "
                        + "karaoke refused rather than risk the "
                        + "original audio during load");
                return;
            }
        }
        player = new KaraokePlayer(this);
        player.setBackingVocalsEnabled(backingVocalsEnabled);
        // Track + threads up immediately, HELD (silence) until the
        // Track + threads up immediately, HELD (silence) until the
        // first-audio gate: mute, then skip, then release, in that
        // order. Nothing audible plays before the mute.
        player.start();
        client = new KaraokeWsClient(settings.wsUrl, new WsListener(wsGen));
        // Server API key (keyed endpoints only): sent in the WS
        // hello as "key" when saved; null = keyless. Never logged.
        client.apiKey = settings.modalKey;
        // v29: hand the server the app's own audio URL (route
        // selection at start, visible in the log; a failed range
        // on it still stops — no silent second source).
        String appUrl = null;
        int appBitrate = 0;
        try {
            String[] info = video.appAudioInfo();
            if (info != null && info.length >= 2) {
                appUrl = info[0];
                appBitrate = Integer.parseInt(info[1]);
            }
        } catch (Exception e) {
            KaraokeLog.e("app audio info failed", e);
        }
        if (appUrl == null) {
            KaraokeLog.i("app audio: URL unavailable, "
                    + "server uses its player request");
        }
        final String fAppUrl = appUrl;
        final int fAppBitrate = appBitrate;
        new Thread(() -> {
            try {
                client.connect(videoId, fAppUrl, fAppBitrate);
            } catch (Exception e) {
                KaraokeLog.crash("connect thread", snapshot(), e);
                fail("Connect failed: " + e.getMessage());
            }
        }, "karaoke-connect").start();
    }

    /** Explicit resume after a preemption (container killed
     *  mid-song). Reconnects the SAME path and seeks to the video
     *  position. Caller-triggered only, never automatic. */
    public void resume(String videoId) {
        KaraokeLog.i("session resume requested: videoId=" + videoId);
        start(videoId);
    }

    public synchronized void stop() {
        KaraokeLog.i("session stop requested (state=" + state + ")");
        stopInternal(true);
        setState(State.IDLE);
    }

    private synchronized void stopInternal(boolean unmute) {
        stopping = true;
        stopReconnect();
        if (syncTask != null) syncTask.cancel(false);
        if (syncExec != null) {
            // v58: stopping FROM the sync thread must not
            // shutdownNow (it interrupts THIS thread: every latch
            // below — unmute included — failed "interrupted" in
            // the v57 switch log, stranding the user muted).
            // shutdown() lets this tick finish; the cancelled
            // future means the worker exits with no leak.
            if (Thread.currentThread() == syncThread) {
                syncExec.shutdown();
            } else {
                syncExec.shutdownNow();
            }
        }
        syncTask = null;
        syncExec = null;
        syncThread = null;
        if (client != null) {
            // v28: close() can throw on the main thread (socket
            // close trips StrictMode) — that abort left player,
            // unmute and video-restore unrun in the v27 log.
            // Guard it so cleanup ALWAYS completes.
            try {
                client.close();
            } catch (Exception e) {
                KaraokeLog.e("session stop: ws close threw "
                        + "(cleanup continues)", e);
            }
            client = null;
        }
        if (player != null) {
            player.stop();
            player = null;
        }
        // Restore the user's original audio. This is cleanup, not a
        // fallback: karaoke is over, so exactly one audio (the
        // original) plays again.
        if (unmute && mute != null) {
            try {
                boolean ok = mute.setMuted(false);
                KaraokeLog.i("session cleanup unmute: result=" + ok);
            } catch (Exception e) {
                // Cleanup must not mask the original failure, but it
                // must not be silent either: the user may be left
                // muted, and that has to be visible in logcat.
                KaraokeLog.e("session cleanup unmute threw", e);
            }
        }
        // M8/M12: resume the video iff THIS session paused it —
        // the start pause (pausedByKaraoke) or a rebuffer /
        // drained-reconnect pause (pausedForRebuffer). The old
        // gate used wasPlayingFlag, a Q() snapshot taken at
        // start; Q() proved unreliable (false while playing),
        // so it could strand a video the session had paused.
        // (The success gate always resumes: a K tap means play.)
        if (pausedByKaraoke || pausedForRebuffer) {
            pausedByKaraoke = false;
            pausedForRebuffer = false;
            try {
                boolean resumed = video.resume();
                KaraokeLog.i("session cleanup video resume: result="
                        + resumed);
            } catch (Exception e) {
                KaraokeLog.e("session cleanup video resume threw", e);
            }
        }
    }

    private synchronized void fail(String message) {
        if (stopping) return;
        KaraokeLog.e("session FAIL: " + message + " | " + snapshot());
        stopInternal(true);
        setState(State.ERROR);
        listener.onError(message);
    }

    /** State snapshot for crash logs. Never throws: every piece is
     *  guarded, so a broken accessor cannot hide the original
     *  failure. Video ID only, never URLs or keys. */
    private String snapshot() {
        String wsState;
        try {
            wsState = client == null ? "null"
                    : (client.isOpen() ? "open" : "closed");
        } catch (Exception e) {
            wsState = "?";
        }
        String muteState;
        try {
            muteState = mute == null ? "null"
                    : String.valueOf(mute.isMuted());
        } catch (Exception e) {
            muteState = "?";
        }
        String buffered;
        String audioPos;
        try {
            buffered = player == null ? "null"
                    : String.format("%.2f", player.bufferedSec());
            audioPos = player == null ? "null"
                    : String.format("%.2f", player.audioPositionSec());
        } catch (Exception e) {
            buffered = "?";
            audioPos = "?";
        }
        return "state=" + state + " videoId=" + videoId
                + " lastChunkSeq=" + lastChunkSeq
                + " lastVideoPosMs=" + lastVideoPosMs
                + " ws=" + wsState + " muted=" + muteState
                + " bufferedSec=" + buffered + " audioPosSec=" + audioPos;
    }

    /** Stage-2 toggle: flips the player's live BV mix. The
     *  backing stem is already decoded beside the instrumental,
     *  so this is instant and mid-song safe. */
    public void setBackingVocalsEnabled(boolean enabled) {
        backingVocalsEnabled = enabled;
        KaraokePlayer p = player;
        if (p != null) p.setBackingVocalsEnabled(enabled);
        KaraokeLog.i("backing vocals set to " + enabled
                + " (live mix)" );
    }

    public boolean isBackingVocalsEnabled() {
        return backingVocalsEnabled;
    }

    /**
     * v26: first-audio gate, extracted from onChunk so decode
     * completions can re-fire it. Decode finishes AFTER arrival
     * (MediaCodec trails the network burst), and arrivals can
     * stall entirely (403 wall) — checking only in onChunk then
     * deadlocks mid-song taps with audio ready but gate asleep.
     * Safe to call any time: no-ops unless !firstAudioFired and
     * enough is queued. Mute re-call is cheap (already-muted
     * guard returns immediately). Synchronized: ws-reader and
     * decode threads can arrive together; neither is the main
     * thread, so the main-thread latch inside cannot deadlock.
     */
    private synchronized void tryFirstAudioGate() {
        if (stopping || player == null) return; // v28: never fire after stop
        // v54 load watchdog: 30s with zero queue growth means
        // the stream is dead (user refuses 30s+ loads). Growth
        // resets the clock, so slow-but-alive mid-song taps
        // survive as long as audio keeps arriving.
        if (!firstAudioFired) {
            double q = player.queuedSec();
            long now = System.currentTimeMillis();
            if (q > lastGateQueuedSec + 0.01) {
                lastGateQueuedSec = q;
                lastGateGrowthMs = now;
            } else if (now - lastGateGrowthMs > 30000) {
                fail("Karaoke load stalled 30s with no audio");
                return;
            }
        }
        if (!firstAudioFired
                && player.queuedSec() >= START_BUFFER_SEC) {
            firstAudioFired = true;
            boolean muted = false;
            try {
                muted = mute != null && mute.setMuted(true);
            } catch (Exception e) {
                KaraokeLog.e("first-audio mute call threw", e);
            }
            // M5: flag-only is not proof — force a fresh volume
            // read-back (a sticky restart can reach here with a
            // stale muted flag while the volume is actually up).
            boolean verified = false;
            try {
                verified = KaraokeBridge.verifyMuted();
            } catch (Exception e) {
                KaraokeLog.e("first-audio mute verify threw", e);
            }
            KaraokeLog.i("first-audio gate: mute result=" + muted
                    + " verified=" + verified);
            if (!muted || !verified) {
                fail("Mute did not take effect (verified="
                        + verified + "); karaoke refused "
                        + "to start rather than play over the "
                        + "original audio.");
                return;
            }
            double atSec;
            try {
                atSec = video.positionMs() / 1000.0;
            } catch (Exception e) {
                KaraokeLog.crash("first-audio position read",
                        snapshot(), e);
                fail("Video position read failed at first audio: "
                        + e.getMessage());
                return;
            }
            if (!player.coversSec(atSec)) {
                KaraokeLog.d("first-audio gate: waiting for "
                        + "coverage at " + String.format("%.2f",
                                atSec) + "s (queued "
                        + String.format("%.2f",
                                player.queuedSec()) + "s)");
                firstAudioFired = false;
                return;
            }
            KaraokeLog.i("first-audio gate: skipping karaoke to "
                    + String.format("%.2f", atSec) + "s");
            if (atSec > 0) player.skipToSec(atSec);
            // M10: resume the VIDEO before releasing karaoke
            // audio — releasing first plays karaoke over a
            // still-paused video, and a failed resume used to
            // be ignored. A failed resume keeps the hold and
            // fails the session instead.
            if (pausedByKaraoke) {
                boolean resumed = false;
                try {
                    resumed = video.resume();
                    KaraokeLog.i("first-audio gate: video resume "
                            + "result=" + resumed);
                } catch (Exception e) {
                    KaraokeLog.e("first-audio video resume threw",
                            e);
                }
                if (!resumed) {
                    fail("Video resume failed at first audio; "
                            + "karaoke held, not released");
                    return;
                }
                pausedByKaraoke = false;
            }
            // M7: re-check before releasing — a stop() between
            // the mute and here must not resurrect PLAYING.
            if (stopping || player == null
                    || (state != State.CONNECTING
                            && state != State.BUFFERING)) {
                KaraokeLog.i("first-audio gate: stopped while "
                        + "gating (state=" + state
                        + "); hold not released");
                return;
            }
            player.releaseHold();
            setState(State.PLAYING);
            listener.onFirstAudio();
            startSyncLoop();
        }
    }

    @Override
    public void onChunkDecoded(int seq) {
        // v26: a decode finished after its arrival — re-run the
        // gate. Only pre-PLAYING matters; afterwards the gate
        // no-ops on firstAudioFired anyway.
        if (state == State.BUFFERING) {
            tryFirstAudioGate();
        }
        emitLoadProgress();
        checkRebufferComplete();
        checkReconnectComplete();
    }

    @Override
    public void onPlayerError(String message, Throwable t) {
        // The player already logged the trace; fail() adds the
        // session snapshot and stops cleanly (unmutes).
        fail("Player error: " + message);
    }

    private class WsListener implements KaraokeWsClient.Listener {
        private final int gen;
        WsListener(int gen) {
            this.gen = gen;
        }
        /** v55: stale-client guard (see wsGen). */
        private boolean stale() {
            return gen != wsGen;
        }
        @Override
        public void onHeader(int sr, boolean coldStart, boolean cached) {
            if (stale()) return;
            sampleRate = sr;
            KaraokeLog.i("WS header: sampleRate=" + sr
                    + " coldStart=" + coldStart + " cached=" + cached);
            // v54: a reconnect header must NOT flip PLAYING back
            // to BUFFERING (the buffer is still playing out).
            if (reconnecting) return;
            if (coldStart && !cached) {
                try {
                    listener.onColdStart();
                } catch (Exception e) {
                    KaraokeLog.w("onColdStart threw", e);
                }
            }
            setState(State.BUFFERING);
        }

        @Override
        public void onChunk(String stream, int seq,
                long startSamples, byte[] opus) {
            if (stale()) return;
            lastChunkSeq = Math.max(lastChunkSeq, seq);
            if (player == null || stopping) return;
            player.queueChunk(stream, seq, startSamples, sampleRate, opus);
            KaraokeLog.d("WS chunk stream=" + stream + " seq=" + seq + " startSec="
                    + String.format("%.2f",
                            startSamples / (double) sampleRate)
                    + " opusBytes=" + opus.length
                    + " bufferedSec=" + String.format("%.2f",
                            player.bufferedSec())
                    + " queuedSec=" + String.format("%.2f",
                            player.queuedSec()));
            // v13 gate metric: queuedSec, NOT bufferedSec (see
            // tryFirstAudioGate; v26 re-checks on every decode too,
            // because decode finishes after arrival).
            tryFirstAudioGate();
            emitLoadProgress();
            checkRebufferComplete();
            checkReconnectComplete();
        }

        @Override
        public void onDone(int chunkCount) {
            if (stale()) return;
            serverDone = true;
            if (player != null) player.setStreamDone(true);
            KaraokeLog.i("WS done: server sent " + chunkCount
                    + " chunks; queued PCM still plays until drained");
            // Playback continues until the queue drains; the writer
            // thread ends the track when the player is stopped.
            // DONE here means the server finished, not that audio
            // finished playing.
            if (state == State.PLAYING || state == State.PAUSED) {
                // Nothing to do: remaining queued PCM still plays.
            }
        }

        @Override
        public void onClosed() {
            // v54: a clean done already received means the
            // server's normal socket close afterwards — never a
            // failure in ANY state (queued chunks still decode,
            // gate, and drain).
            if (stale()) return;
            KaraokeLog.i("WS closed by server (state=" + state + ")");
            if (serverDone) {
                if (player != null) player.setStreamDone(true);
                return;
            }
            // A cut WITH audio seen: play out the buffer while a
            // reconnect loop replays from the resume seq. Only a
            // pre-playback close (nothing ever arrived) fails.
            if (state == State.PLAYING || state == State.PAUSED
                    || ((state == State.BUFFERING
                            || state == State.CONNECTING)
                            && lastChunkSeq >= 0)) {
                streamCut = true;
                startReconnect("socket closed");
            } else {
                onError("WebSocket closed by server");
            }
        }

        @Override
        public void onError(String message) {
            if (stale()) return;
            // v54: mid-play errors reconnect like cuts; only
            // pre-playback errors fail immediately.
            if (!serverDone && !stopping
                    && (state == State.PLAYING
                            || state == State.PAUSED
                            || ((state == State.BUFFERING
                                    || state == State.CONNECTING)
                                    && lastChunkSeq >= 0))) {
                streamCut = true;
                startReconnect("error: " + message);
                return;
            }
            fail(message);
        }
    }

    private boolean serverDone;
    /** v44: consecutive position-1 ticks (playback gone) and
     *  last hard-seek time (seek cooldown). */
    private int goneTicks;
    private long lastHardSeekMs;
    /** v46: frozen-video stall guard state. */
    private long lastSeenVPosMs = -1;
    private int frozenTicks;
    private boolean videoStalled;
    private int blindTicks;
    /** v41: socket closed before done arrived (mid-song cut).
     *  Queued audio still plays out, but the ending reports
     *  the cut instead of a clean DONE. */
    private boolean streamCut;
    /** v30: consecutive sync ticks seeing a different video. */
    private int videoMismatchTicks;
    // ---- v54 seek / rebuffer / reconnect state ----
    /** Load watchdog: fail only on 30s WITHOUT growth (an
     *  absolute 30s would kill every mid-song tap, which
     *  legitimately needs ~60s of coverage). */
    private long loadStartMs;
    private double loadTargetSec;
    private double lastGateQueuedSec;
    private long lastGateGrowthMs;
    private long lastProgressMs;
    /** Debounced user seek: candidate pos + when it settled.
     *  -1 when no seek is pending. */
    private long pendingSeekValueMs = -1;
    private long pendingSeekSinceMs;
    /** v57: was the video playing when the pending seek was set?
     *  Distinguishes seek-while-paused (Q10: auto-resume ~1s
     *  after release) from paused-mid-settle (wait, don't steal
     *  the user's pause). Only read while a seek is pending. */
    private boolean pendingWasPlaying;
    /** Forward seek beyond coverage: veil + paused until the
     *  stream produces the target. */
    private boolean rebuffering;
    private double rebufferTargetSec;
    private long rebufferStartMs;
    /** Cut-socket recovery: buffer plays out while a reconnect
     *  loop replays from the resume seq. */
    private volatile boolean reconnecting;
    private volatile int reconnectGen;
    /** v55: WS generation — callbacks from a client superseded
     *  by reconnect are ignored (a stale done/errors could
     *  otherwise set serverDone early or spin a phantom loop). */
    private volatile int wsGen;
    private Thread reconnectThread;
    private boolean reconnectVeilShown;

    private void startSyncLoop() {
        syncExec = Executors.newSingleThreadScheduledExecutor();
        syncTask = syncExec.scheduleAtFixedRate(() -> {
            syncThread = Thread.currentThread();
            try {
                syncTick();
            } catch (Exception e) {
                // A sync tick must never kill the session SILENTLY.
                // Log the snapshot + trace, then stop cleanly.
                KaraokeLog.crash("sync tick", snapshot(), e);
                fail("Sync tick failed: " + e.getMessage());
            }
        }, SYNC_PERIOD_MS, SYNC_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    private void syncTick() {
        if (player == null || state != State.PLAYING) return;
        // v30 watchdog: video switched under a playing session
        // (new controller or same-page next video) -> stop the
        // old karaoke. 3 consecutive mismatches (~1.5s) so a
        // transient null/ad flicker cannot kill it.
        try {
            String curId = video.videoId();
            if (curId != null && videoId != null
                    && !curId.equals(videoId)) {
                if (++videoMismatchTicks >= 3) {
                    KaraokeLog.i("sync: video switched " + videoId
                            + " -> " + curId + "; stopping karaoke");
                    stop();
                    // v54 sticky K: the bridge restarts on the new
                    // video when K is sticky, else stays stopped.
                    try {
                        listener.onVideoSwitched(curId);
                    } catch (Exception e) {
                        KaraokeLog.w("onVideoSwitched threw", e);
                    }
                    return;
                }
            } else if (curId != null) {
                // Readable and unchanged: not a switch.
                videoMismatchTicks = 0;
            }
            // curId null (detached model mid-switch): unknown,
            // abstain — keep the count, never false-stop.
        } catch (Exception e) {
            KaraokeLog.e("sync: video-switch check threw", e);
        }
        // v46 blindness diagnostic: response model detached for
        // a long stretch means the switch watchdog cannot see.
        // Log once per 10s of blindness, never stop on it.
        try {
            if (video.videoId() == null) {
                if (++blindTicks == 20) {
                    KaraokeLog.w("sync: watchdog blind 10s "
                            + "(response model detached)");
                }
            } else {
                blindTicks = 0;
            }
        } catch (Exception e) {
            // videoId() never throws by contract; ignore.
        }
        // v41 finish: server done + queue drained -> end the
        // session (unmute, DONE) instead of sitting in
        // PLAYING with the video muted forever. A mid-song
        // cut still plays out the queue first, then fails
        // loudly instead of claiming a clean finish.
        if (serverDone && player.isDrained()) {
            if (streamCut) {
                fail("Server connection lost before the song finished");
            } else {
                KaraokeLog.i("sync: karaoke finished (queue drained)");
                stopInternal(true);
                setState(State.DONE);
            }
            return;
        }
        long nowMs = System.currentTimeMillis();
        // M1/M8: mute guard runs FIRST on every tick, before any
        // early return below — background play (position -1)
        // used to return before the guard, leaving karaoke
        // running with no mute check at all. v53: a video
        // stutter can recreate the internal player (full
        // volume) or reset its volume, returning the ORIGINAL
        // audio mid-song. Tri-state: OK resets both counters;
        // FAILED fails after 2 in a row; BLIND (could not
        // verify) fails after 20 in a row (~10s).
        if (mute != null) {
            int guardResult;
            try {
                guardResult = mute.keepMuted();
            } catch (Exception e) {
                KaraokeLog.w("sync: mute guard threw", e);
                guardResult = KaraokeBridge.KEEP_MUTED_BLIND;
            }
            if (guardResult == KaraokeBridge.KEEP_MUTED_OK) {
                consecutiveMuteFailures = 0;
                consecutiveMuteBlindTicks = 0;
            } else if (guardResult
                    == KaraokeBridge.KEEP_MUTED_FAILED) {
                consecutiveMuteBlindTicks = 0;
                if (++consecutiveMuteFailures >= 2) {
                    KaraokeLog.e("sync: mute guard failed re-mute "
                            + consecutiveMuteFailures
                            + " ticks in a row");
                    fail("Original audio unmuted itself and could "
                            + "not be re-muted");
                    return;
                }
                KaraokeLog.w("sync: mute guard re-mute failed ("
                        + consecutiveMuteFailures + " in a row)");
            } else {
                // BLIND (or any unexpected value): unverifiable.
                consecutiveMuteFailures = 0;
                if (++consecutiveMuteBlindTicks >= 20) {
                    KaraokeLog.e("sync: mute guard blind "
                            + consecutiveMuteBlindTicks
                            + " ticks in a row");
                    fail("Original-audio mute state unverifiable "
                            + "for 10s");
                    return;
                }
                KaraokeLog.w("sync: mute guard blind ("
                        + consecutiveMuteBlindTicks + " in a row)");
            }
        }
        // M9: guard each reflection read — a transient failure
        // skips the tick instead of failing the whole session
        // (the startSyncLoop catch used to fail on any throw).
        syncTickCount++;
        long vPos;
        try {
            vPos = video.positionMs();
        } catch (Exception e) {
            KaraokeLog.w("sync: position read threw", e);
            if (++consecutiveTickFailures >= 4) {
                fail("Video position unreadable 4 ticks in a row");
            }
            return;
        }
        // v44 playback-gone watchdog: position -1 means the
        // response model/player is gone (miniplayer removed,
        // watch closed). Sustained 2s -> stop instead of
        // ghost-playing audio over the feed forever.
        if (vPos < 0) {
            // v54 Q27: background/minimised play keeps POSITION
            // unreadable (-1) while isPlaying stays true — that
            // is ALIVE, keep karaoke going (no position logic
            // this tick). Only a -1 that is also NOT playing
            // (closed/released player) stops after 2s.
            // M8: Q() == false alone never stops karaoke — the
            // position must also be unchanged (still -1, not a
            // first -1 tick after valid positions, which is a
            // transient detach and does not count).
            boolean posUnchanged = (vPos == lastPositionMs);
            if (!posUnchanged) {
                lastPositionMs = vPos;
                lastPositionChangeTick = syncTickCount;
                goneTicks = 0;
                return;
            }
            boolean bgPlaying = false;
            try {
                bgPlaying = video.isPlaying();
            } catch (Exception e) {
                KaraokeLog.w("sync: isPlaying read threw", e);
                if (++consecutiveTickFailures >= 4) {
                    fail("Video playing-state unreadable "
                            + "4 ticks in a row");
                }
                return;
            }
            if (bgPlaying) {
                goneTicks = 0;
                return;
            }
            if (++goneTicks >= 4) {
                KaraokeLog.i("sync: playback context gone "
                        + "(position -1, unchanged); stopping karaoke");
                stop();
            }
            return;
        }
        if (vPos != lastPositionMs) {
            lastPositionMs = vPos;
            lastPositionChangeTick = syncTickCount;
        }
        goneTicks = 0;
        boolean vPlaying;
        try {
            vPlaying = video.isPlaying();
        } catch (Exception e) {
            KaraokeLog.w("sync: isPlaying read threw", e);
            if (++consecutiveTickFailures >= 4) {
                fail("Video playing-state unreadable 4 ticks in a row");
            }
            return;
        }
        // M11: cache the last good speed — a transient read
        // failure used to fall back to 1.0, which made karaoke
        // fall behind ~1s/s at 2x and hard-seek every 1.5s.
        // An implausible value (<= 0 or > 4x) is treated as a
        // failed read; the cache only updates on good reads.
        float vSpeed;
        try {
            float s = video.speed();
            if (s > 0 && s <= 4.0f) {
                vSpeed = s;
                lastGoodSpeed = s;
                speedUncertain = false;
            } else {
                vSpeed = lastGoodSpeed;
                speedUncertain = true;
                KaraokeLog.w("sync: implausible speed " + s
                        + ", using last good " + lastGoodSpeed);
            }
        } catch (Exception e) {
            vSpeed = lastGoodSpeed;
            speedUncertain = true;
            KaraokeLog.w("sync: speed read threw, using last good "
                    + lastGoodSpeed, e);
        }
        consecutiveTickFailures = 0;
        // v54 rebuffer / reconnect pre-emption (before stall
        // mirror and drift: positions diverge by design here).
        if (rebuffering) {
            checkRebufferComplete();
            lastVideoPosMs = vPos;
            lastPollMs = nowMs;
            return;
        }
        if (reconnecting) {
            if (player.isDrained()) {
                // Buffer ran out: park the video on the veil
                // until coverage returns.
                showReconnectVeil();
                // M12: track the pause (flag) and log a
                // failure — a false return used to be swallowed.
                try {
                    boolean paused = video.pause();
                    pausedForRebuffer = paused;
                    if (!paused) {
                        KaraokeLog.w("sync: reconnect drained "
                                + "video pause returned false");
                    }
                } catch (Exception e) {
                    pausedForRebuffer = false;
                    KaraokeLog.w("sync: reconnect drained "
                            + "video pause threw", e);
                }
                player.pause();
                emitReconnectProgress();
                lastVideoPosMs = vPos;
                lastPollMs = nowMs;
                return;
            }
            checkReconnectComplete();
            // Not drained: the buffer keeps playing AND the
            // drift loop below keeps it aligned.
        }
        // v46 frozen-video stall guard: video position not
        // advancing while it claims to be playing (rebuffer,
        // stuck page) must freeze KARAOKE too — racing minutes
        // ahead of a stuck picture is the ghost-audio failure.
        // 6 ticks (~3s) at <50ms movement = stalled. While
        // stalled we hold; movement resumes mirroring.
        if (vPlaying) {
            if (lastSeenVPosMs >= 0
                    && Math.abs(vPos - lastSeenVPosMs) < 50) {
                frozenTicks++;
            } else {
                if (videoStalled) {
                    KaraokeLog.i("sync: video moving again at "
                            + vPos + "ms -> karaoke resume");
                }
                frozenTicks = 0;
                videoStalled = false;
            }
            lastSeenVPosMs = vPos;
            if (frozenTicks >= 6 && !videoStalled) {
                videoStalled = true;
                KaraokeLog.i("sync: video stalled at " + vPos
                        + "ms -> karaoke hold");
            }
            if (videoStalled) {
                player.pause();
                lastVideoPosMs = vPos;
                lastPollMs = nowMs;
                return;
            }
        }

        // v57: debounce runs BEFORE the mirror so paused seeks
        // are caught too (the mirror's paused-path used to return
        // early and swallow them, stranding karaoke; Q10: seeks
        // always resume via executeSeekJump).
        // v54 debounced seek: bar drags fire jump after jump —
        // karaoke waits until the bar RELEASES (pos stable ~1s)
        // then jumps ONCE. While a seek is pending, karaoke
        // pauses (no wrong audio) and the drift loop is skipped.
        // Runs for paused video too (Q10: seeks always resume).
        if (lastVideoPosMs >= 0 && lastPollMs > 0) {
            long elapsed = nowMs - lastPollMs;
            long expected = lastVideoPosMs
                    + (long) (elapsed * vSpeed);
            if (Math.abs(vPos - expected) > SEEK_JUMP_MS) {
                if (pendingSeekValueMs < 0) {
                    KaraokeLog.i("sync: seek started (expected "
                            + expected + "ms, got " + vPos
                            + "ms) — waiting for release");
                    player.pause();
                }
                pendingSeekValueMs = vPos;
                pendingSeekSinceMs = nowMs;
                pendingWasPlaying = vPlaying;
                lastVideoPosMs = vPos;
                lastPollMs = nowMs;
                return;
            }
        }
        if (pendingSeekValueMs >= 0) {
            // v57: the old "still scrubbing" test (|vPos -
            // candidate| > 800) was tripped by NORMAL playback
            // (~500ms/tick): the candidate followed the playing
            // video forever, stability never arrived, and the
            // seek NEVER executed (video moved, karaoke stayed).
            // Correct test: only a DEVIATION from 1x playback
            // out of the landing means still scrubbing.
            // v57: paused mid-settle freezes the clock (the
            // user's pause wins); a seek that STARTED paused
            // runs the clock so Q10 auto-resume still fires.
            if (pendingWasPlaying && !vPlaying) {
                pendingSeekSinceMs = nowMs;
                lastVideoPosMs = vPos;
                lastPollMs = nowMs;
                return;
            }
            long pendingElapsed = nowMs - pendingSeekSinceMs;
            long expectedFromCandidate = pendingSeekValueMs
                    + (vPlaying ? (long) (pendingElapsed * vSpeed) : 0);
            if (Math.abs(vPos - expectedFromCandidate) > 2000) {
                // Still scrubbing — follow the candidate.
                pendingSeekValueMs = vPos;
                pendingSeekSinceMs = nowMs;
                lastVideoPosMs = vPos;
                lastPollMs = nowMs;
                return;
            }
            if (pendingElapsed < 1000) {
                lastVideoPosMs = vPos;
                lastPollMs = nowMs;
                return;
            }
            // Released: execute exactly one jump at the CURRENT
            // video pos (the candidate is ~1s stale by now).
            double targetSec = vPos / 1000.0;
            pendingSeekValueMs = -1;
            executeSeekJump(targetSec);
            try {
                lastVideoPosMs = video.positionMs();
            } catch (Exception e) {
                lastVideoPosMs = vPos;
            }
            lastPollMs = System.currentTimeMillis();
            return;
        }
        lastVideoPosMs = vPos;
        lastPollMs = nowMs;

        // Mirror pause/resume (transition-only logs).
        if (!vPlaying) {
            if (lastMirroredPlaying == null || lastMirroredPlaying) {
                KaraokeLog.i("sync: video paused at " + vPos
                        + "ms -> karaoke pause");
            }
            lastMirroredPlaying = false;
            player.pause();
            lastVideoPosMs = vPos;
            lastPollMs = nowMs;
            return;
        } else {
            if (lastMirroredPlaying != null && !lastMirroredPlaying) {
                KaraokeLog.i("sync: video resumed at " + vPos
                        + "ms -> karaoke resume");
            }
            lastMirroredPlaying = true;
            // v57: never undo a pending-seek pause — the mirror
            // resumed karaoke 0.5s after every seek started,
            // playing stale audio at the old position.
            // M6: never undo a drift hold either. Verified
            // against KaraokePlayer.resume(): it is a plain
            // unpause (paused=false + track.play(), no hold
            // check), so resuming here every tick defeated the
            // hold below; the holding branch re-pauses while
            // still ahead.
            if (pendingSeekValueMs < 0 && !holdingForSync) {
                player.resume();
            }
        }

        // Drift loop (asymmetric: karaoke-ahead cannot seek back).
        double aPosSec = player.audioPositionSec();
        long driftMs = (vPos + settings.bluetoothOffsetMs)
                - (long) (aPosSec * 1000);
        long adrift = Math.abs(driftMs);
        if (holdingForSync) {
            // Karaoke is ahead: hold it paused until video catches
            // up. Resume once drift is back inside the ignore band.
            if (driftMs >= -IGNORE_MS) {
                KaraokeLog.i("sync: drift " + driftMs
                        + "ms back in band -> resume karaoke");
                holdingForSync = false;
                player.resume();
            } else {
                // M6: still ahead — re-assert the hold. The
                // mirror above runs before this check each tick.
                player.pause();
            }
            return;
        }
        // v44: pitch-pure sync. Track rate follows the VIDEO
        // speed only (never a drift nudge — rate changes pitch).
        player.setSpeedFactor(vSpeed);
        if (adrift <= IGNORE_MS) {
            // Inside 350ms: leave it — karaoke backing at this
            // offset is not worth a jump cut.
        } else if (driftMs > IGNORE_MS) {
            // Karaoke behind: jump forward to the video position
            // (dropping samples keeps pitch exact). Cooldown so
            // back-to-back seeks cannot chatter while the track
            // buffer still holds pre-skip audio.
            // M11: never hard-seek while the speed is uncertain —
            // a stale 1.0 fallback at 2x made karaoke fall
            // behind ~1s/s and seek every 1.5s.
            if (!speedUncertain
                    && (nowMs - lastHardSeekMs >= 1500
                            || driftMs > 1200)) {
                KaraokeLog.i("sync: drift +" + driftMs
                        + "ms behind -> hard-seek karaoke to " + vPos + "ms");
                player.skipToSec(vPos / 1000.0);
                lastHardSeekMs = nowMs;
            } else if (speedUncertain) {
                KaraokeLog.d("sync: drift +" + driftMs
                        + "ms behind but speed uncertain — "
                        + "skipping hard-seek");
            }
        } else if (driftMs < -SEEK_MS) {
            // Karaoke ahead: hold paused until video catches up.
            // (Drift-hold only — USER backward seeks rewind via
            // the whole-song Opus cache in seekJump.)
            KaraokeLog.i("sync: drift " + driftMs
                    + "ms ahead -> hold karaoke");
            holdingForSync = true;
            player.pause();
        }
    }

    // ---- v54 seek / rebuffer / reconnect ----

    /** True while a veil owned by seek/reconnect is up (the
     *  Bridge guard consults this; CONNECTING/BUFFERING alone
     *  no longer covers every loading veil). */
    public boolean isRebufferingOrReconnecting() {
        return rebuffering || (reconnecting && reconnectVeilShown);
    }

    /** Executes one debounced user seek. Forward within
     *  coverage and ANY backward jump are instant (cache);
     *  forward beyond coverage parks on the rebuffer veil
     *  until the stream produces the target. Always resumes
     *  video + audio afterwards (Q10), even from pause. */
    private void executeSeekJump(double targetSec) {
        KaraokeLog.i("sync: seek released at "
                + String.format("%.2f", targetSec)
                + "s — jumping karaoke");
        holdingForSync = false;
        double aPos = -1;
        try {
            aPos = player.audioPositionSec();
        } catch (Exception ignored) {
        }
        // 2s grace: the writer's current chunk + track buffer
        // are real coverage that coversSec() cannot see.
        if (targetSec > aPos && !player.coversSec(targetSec)
                && targetSec - aPos >= 2.0) {
            enterRebuffer(targetSec, "seek");
            return;
        }
        player.seekJump(targetSec);
        resumeVideoAndAudio("seek");
    }

    /** v54 Q10: a completed seek ALWAYS resumes video + karaoke,
     *  even when the video was paused before the seek. */
    private void resumeVideoAndAudio(String why) {
        // M10: video first; a failed video resume must NOT
        // unpause karaoke audio underneath a stuck video —
        // keep it paused and fail honestly instead.
        boolean resumed = false;
        try {
            resumed = video.resume();
            KaraokeLog.i("sync: " + why + " resume video result=" + resumed);
        } catch (Exception e) {
            KaraokeLog.e("sync: " + why + " video resume threw", e);
        }
        if (!resumed) {
            fail("Video resume failed after " + why
                    + "; karaoke kept paused rather than released");
            return;
        }
        pausedForRebuffer = false;
        lastMirroredPlaying = true;
        player.resume();
    }

    private void enterRebuffer(double targetSec, String why) {
        rebuffering = true;
        rebufferTargetSec = targetSec;
        rebufferStartMs = System.currentTimeMillis();
        // M12: track the pause (flag) and log a failure — a
        // false return used to be swallowed, and the flag is
        // what resumes the video on the 60s-timeout fail path.
        boolean paused = false;
        try {
            paused = video.pause();
            KaraokeLog.i("sync: rebuffer video pause result=" + paused);
            if (!paused) {
                KaraokeLog.w("sync: rebuffer video pause returned false");
            }
        } catch (Exception e) {
            KaraokeLog.w("rebuffer video pause threw", e);
        }
        pausedForRebuffer = paused;
        player.pause();
        KaraokeLog.i("sync: rebuffer (" + why + ") waiting for "
                + String.format("%.2f", targetSec) + "s");
        try {
            listener.onRebufferStart("Rebuffering\u2026\n"
                    + String.format("%.0f", targetSec) + "s");
        } catch (Exception e) {
            KaraokeLog.w("onRebufferStart threw", e);
        }
    }

    /** Completes a rebuffer once the target is decoded (called
     *  from syncTick + every chunk/decode; throttled progress
     *  otherwise). 60s cap, then an honest failure. */
    private void checkRebufferComplete() {
        if (!rebuffering || player == null || stopping) return;
        if (System.currentTimeMillis() - rebufferStartMs > 60000) {
            rebuffering = false;
            try {
                listener.onRebufferEnd();
            } catch (Exception ignored) {
            }
            // M12: fail() -> stopInternal() resumes the video
            // if pausedForRebuffer is set.
            fail("Rebuffer timed out waiting for audio");
            return;
        }
        double aPos = -1;
        try {
            aPos = player.audioPositionSec();
        } catch (Exception ignored) {
        }
        if (player.coversSec(rebufferTargetSec)
                || rebufferTargetSec - aPos < 2.0) {
            KaraokeLog.i("sync: rebuffer target "
                    + String.format("%.2f", rebufferTargetSec)
                    + "s covered — jumping");
            player.seekJump(rebufferTargetSec);
            rebuffering = false;
            try {
                listener.onRebufferEnd();
            } catch (Exception e) {
                KaraokeLog.w("onRebufferEnd threw", e);
            }
            resumeVideoAndAudio("rebuffer");
        } else {
            emitRebufferProgress();
        }
    }

    /** Loading-veil progress (initial gate): "42s of 63s" +
     *  ETA from the rolling arrival rate. Throttled to 2Hz. */
    private void emitLoadProgress() {
        if (firstAudioFired || player == null || stopping) return;
        long now = System.currentTimeMillis();
        if (now - lastProgressMs < 500) return;
        lastProgressMs = now;
        double q = player.queuedSec();
        double target = loadTargetSec > 1 ? loadTargetSec : 6.0;
        String text;
        if (loadTargetSec > 1) {
            double elapsed = Math.max(1,
                    (now - loadStartMs) / 1000.0);
            double rate = q / elapsed;
            String eta = "";
            if (rate > 0.2 && q < target) {
                long left = (long) ((target - q) / rate);
                eta = " \u2022 ~" + left + "s left";
            }
            text = "Preparing karaoke\u2026\n"
                    + String.format("%.0f", Math.min(q, target))
                    + "s of " + String.format("%.0f", target)
                    + "s" + eta;
        } else {
            text = "Preparing karaoke\u2026\n"
                    + String.format("%.0f", q) + "s ready";
        }
        // v58: prove the session side of the progress chain.
        KaraokeLog.d("load progress -> veil: " + text.replace('\n', ' '));
        try {
            listener.onLoadingProgress(text);
        } catch (Exception e) {
            KaraokeLog.w("onLoadingProgress threw", e);
        }
    }

    private void emitRebufferProgress() {
        if (!rebuffering || player == null || stopping) return;
        long now = System.currentTimeMillis();
        if (now - lastProgressMs < 500) return;
        lastProgressMs = now;
        double q = player.queuedSec();
        try {
            listener.onRebufferProgress("Rebuffering\u2026\n"
                    + String.format("%.0f", q) + "s buffered \u2022 need "
                    + String.format("%.0f", rebufferTargetSec) + "s");
        } catch (Exception e) {
            KaraokeLog.w("onRebufferProgress threw", e);
        }
    }

    private void emitReconnectProgress() {
        if (!reconnecting || player == null || stopping) return;
        long now = System.currentTimeMillis();
        if (now - lastProgressMs < 500) return;
        lastProgressMs = now;
        double q = player.queuedSec();
        try {
            listener.onRebufferProgress("Reconnecting\u2026\n"
                    + String.format("%.0f", q) + "s buffered");
        } catch (Exception e) {
            KaraokeLog.w("onRebufferProgress threw", e);
        }
    }

    private void showReconnectVeil() {
        if (reconnectVeilShown) return;
        reconnectVeilShown = true;
        try {
            listener.onRebufferStart("Reconnecting\u2026");
        } catch (Exception e) {
            KaraokeLog.w("onRebufferStart threw", e);
        }
    }

    /** Starts the cut-socket recovery loop (idempotent). The
     *  buffer keeps playing; a background thread replays from
     *  the resume seq with backoff. Give up after 8 attempts
     *  (~40s) and fail honestly. */
    private synchronized void startReconnect(String why) {
        if (stopping || reconnecting) return;
        reconnecting = true;
        reconnectVeilShown = false;
        pendingSeekValueMs = -1;
        final int gen = ++reconnectGen;
        KaraokeLog.i("sync: connection cut (" + why
                + ") — playing out buffer + reconnecting");
        reconnectThread = new Thread(() -> reconnectLoop(gen),
                "karaoke-reconnect");
        reconnectThread.start();
    }

    private void stopReconnect() {
        reconnectGen++;
        reconnecting = false;
        reconnectThread = null;
    }

    private void reconnectLoop(int gen) {
        int[] backoff = {2000, 2000, 4000, 4000, 8000, 8000, 8000, 8000};
        for (int attempt = 0; attempt < backoff.length; attempt++) {
            if (stopping || gen != reconnectGen) return;
            if (!reconnecting || serverDone) return;
            try {
                Thread.sleep(backoff[attempt]);
            } catch (InterruptedException e) {
                return;
            }
            if (stopping || gen != reconnectGen) return;
            if (!reconnecting || serverDone) return;
            int resumeSeq = 0;
            try {
                if (player != null) resumeSeq = player.resumptionSeq();
            } catch (Exception ignored) {
            }
            KaraokeLog.i("sync: reconnect attempt " + (attempt + 1)
                    + " from seq=" + resumeSeq);
            KaraokeWsClient next = new KaraokeWsClient(
                    settings.wsUrl, new WsListener(++wsGen));
            next.apiKey = settings.modalKey;
            next.resumeFromSeq = resumeSeq;
            try {
                next.connect(videoId, null, 0);
            } catch (Exception e) {
                KaraokeLog.w("sync: reconnect attempt "
                        + (attempt + 1) + " failed: " + e.getMessage());
                continue;
            }
            if (stopping || gen != reconnectGen) {
                try {
                    next.close();
                } catch (Exception ignored) {
                }
                return;
            }
            KaraokeWsClient prev = client;
            client = next;
            // v55: close the dead client (FD hygiene); its late
            // callbacks are ignored via the gen guard above.
            if (prev != null) {
                try {
                    prev.close();
                } catch (Exception ignored) {
                }
            }
            KaraokeLog.i("sync: reconnect connected"
                    + " (replay from seq=" + resumeSeq + ")");
            return; // reader thread owns it now; completion
            // is detected via coverage below.
        }
        if (stopping || gen != reconnectGen) return;
        reconnecting = false;
        serverDone = true;
        if (player != null) player.setStreamDone(true);
        fail("Server connection lost before the song finished");
    }

    /** Reconnect completes once live coverage reaches the video
     *  position again (checked on every chunk/decode/tick).
     *  A transient blip heals invisibly (no veil ever shown). */
    private void checkReconnectComplete() {
        if (!reconnecting || player == null || stopping) return;
        // v55: pre-gate cuts must NOT complete here — the gate
        // owns mute + hold-release. Completing first would resume
        // the video UNMUTED with karaoke still held.
        if (!firstAudioFired) return;
        double vSec = -1;
        try {
            long vPos = video.positionMs();
            if (vPos >= 0) vSec = vPos / 1000.0;
        } catch (Exception ignored) {
        }
        if (vSec < 0) return;
        double aPos = -1;
        try {
            aPos = player.audioPositionSec();
        } catch (Exception ignored) {
        }
        if (!player.coversSec(vSec) && vSec - aPos >= 2.0) return;
        KaraokeLog.i("sync: reconnect coverage at "
                + String.format("%.2f", vSec) + "s — resuming");
        player.seekJump(vSec);
        reconnecting = false;
        streamCut = false;
        if (reconnectVeilShown) {
            reconnectVeilShown = false;
            try {
                listener.onRebufferEnd();
            } catch (Exception e) {
                KaraokeLog.w("onRebufferEnd threw", e);
            }
        }
        resumeVideoAndAudio("reconnect");
    }
}
