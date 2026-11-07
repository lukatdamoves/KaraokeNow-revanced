package app.karaokenow.extension;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Patch-to-extension bridge. This is the only class the patch calls,
 * and the only class that talks to YouTube's player.
 *
 * How the patch wires it (see KaraokePatch.kt):
 *  - At Laiwx.aB() entry the patch calls setController(this), so
 *    this class always holds the current watch-player controller.
 *  - At Lmjr.mi() exit (after the bottom UI is inflated into
 *    Lmju.r) the patch calls initializeBottomButtons(bottom UI
 *    view), which puts small text buttons beside the
 *    fullscreen button: "K" (karaoke), "BV" (background
 *    vocals toggle), and "LOG" (DEBUG-ONLY clipboard copy of
 *    the recent log, to be removed after debugging).
 *    Containers and the fullscreen button are
 *    resolved BY RESOURCE NAME, never by numeric ID.
 *  - Buttons are 60dp tall (the fullscreen button's height),
 *    so their text centers on the same line as the
 *    fullscreen icon; horizontal padding 12dp + 4dp margins
 *    give even gaps. Active state copies YouTube's CC
 *    button: inactive = white text on transparent
 *    background; active = white-filled rounded background
 *    with black text. K is filled while a karaoke session
 *    is alive, plain when stopped; BV is filled when its
 *    flag is on, plain when off.
 *  - The patch adds synthetic methods to Laiwx (patch_getVolume,
 *    patch_setVolume, patch_getPosition, patch_isPlaying,
 *    patch_getVideoId) that delegate to Laiwx's own obfuscated
 *    methods. This class calls them by REFLECTION on the stored
 *    controller: the extension never names an obfuscated method,
 *    so if YouTube renames one, only the patch's fingerprints
 *    change. Reflection failures are logged with a full trace and
 *    surface as errors, never swallowed.
 *
 * Roles: this singleton implements KaraokeSession's VideoPosition,
 * MuteController, and Listener, so a tap starts exactly one session
 * with mute-first ordering enforced by the session itself.
 *
 * Threading: setController/initializeBottomButtons run on
 * YouTube's main thread (player response + view inflation).
 * Session callbacks run on karaoke threads; Toasts are always
 * posted to the main thread. initializeBottomButtons never throws:
 * a button failure must never crash the player UI thread, so it
 * logs and returns.
 *
 * Failure visibility (no PC needed): toasts carry the actual
 * reason, and a long-press on the K button shares the recent log
 * (KaraokeLog ring buffer) through the system share sheet — no
 * adb, no logcat reader app, no root.
 */
public class KaraokeBridge implements KaraokeSession.VideoPosition,
        KaraokeSession.MuteController, KaraokeSession.Listener {

    private static final KaraokeBridge INSTANCE = new KaraokeBridge();

    /** The current watch-player controller (a Laiwx instance).
     *  Set by the patch; never read from anywhere else. */
    private static volatile Object controller;

    /** Application context, captured from the bottom UI view, for
     *  Toasts and the share sheet only. Never an Activity context
     *  (no leak). */
    private static volatile Context appContext;

    private static volatile KaraokeSession session;

    /** v54 sticky K: once tapped on, karaoke follows every video
     *  (switch/autoplay) until K is tapped again. The K button
     *  shows THIS flag, not transient session state. */
    private static volatile boolean karaokeSticky;
    /** v54 Q14: K taps ignored until this time (3s after any
     *  K-stop) so Modal can't be hammered by tap spam. Auto
     *  restarts (sticky) bypass this — taps only. */
    private static volatile long kCooldownUntilMs;

    /** v65 BYOK: the user's own Modal server endpoint, saved in
     *  app-private prefs on first K tap. No hardcoded server:
     *  without this, K shows the setup dialog and starts nothing. */
    private static final String PREFS_NAME = "karaoke_now_prefs";
    private static final String PREF_ENDPOINT = "modal_server_url";
    /** v67: resumable auto-setup job (survives Hide / process
     *  death; tapping K while it runs reopens the progress bar). */
    private static final String PREF_SETUP_JOB = "setup_job_id";
    private static final String PREF_SETUP_SINCE = "setup_since_ms";
    private static volatile boolean setupPollerActive;
    /** Video paused by the first K tap while setup runs. The
     *  session takes ownership once it starts (consume), or we
     *  resume if setup is cancelled/fails before any session. */
    private static volatile boolean setupPausedVideo;
    private static volatile boolean setupWasPlaying;
    private static volatile boolean setupProgressDialogShowing;
    private static volatile int setupProgressPercent;
    private static volatile String setupStageText = "";
    private static volatile String setupErrorText;

    /** v65: persistent portrait video-align guard. YouTube resets
     *  the video container on background/miniplayer returns, so a
     *  one-shot correction (v64) came back; this poll re-applies
     *  it (cheap: cached container, no hierarchy search). */
    private static Handler videoAlignHandler;
    private static volatile boolean videoAlignGuardActive;
    private static volatile WeakReference<ViewGroup> videoAlignTargetRef;
    private static final Runnable videoAlignGuard = new Runnable() {
        @Override public void run() {
            if (!videoAlignGuardActive) return;
            try {
                WeakReference<View> ref = bottomUiRef;
                View ui = ref != null ? ref.get() : null;
                if (ui == null) {
                    videoAlignGuardActive = false;
                    return;
                }
                fixPlayerInset(ui);
                updateKaraokeSeekbar(ui.getRootView());
                updateKaraokeSeekbarActual(ui.getRootView());
            } catch (Exception e) {
                KaraokeLog.w("video align guard failed", e);
            }
            if (videoAlignGuardActive && videoAlignHandler != null) {
                videoAlignHandler.postDelayed(this, 500);
            }
        }
    };

    /** Background-vocals flag (BV button). Stored here so
     *  sessions started later pick it up; live sessions are
     *  switched instantly by the session's player mix. */
    private static volatile boolean backingVocalsEnabled;

    /** Weak refs to the K/BV buttons so session-state changes can
     *  refresh their active (CC-style filled) look. Weak: the
     *  views belong to YouTube's hierarchy; a strong static ref
     *  would leak the player Activity. */
    private static volatile WeakReference<TextView> kButtonRef;
    private static volatile WeakReference<TextView> bvButtonRef;
    /** KEY button: shows the current shift ("KEY", "KEY -2"). */
    private static volatile WeakReference<TextView> keyButtonRef;
    /** Current key shift in semitones (mirrored into sessions). */
    private static volatile int keySemitones = 0;
    /** Singer's measured profile, kept across songs in this app run. */
    private static volatile KaraokeVoiceProfile userVoiceProfile;
    /** Song profile of the current song (from the server), if any. */
    private static volatile KaraokeVoiceProfile currentSongProfile;
    /** One key test at a time. */
    private static volatile KaraokeKeyTest keyTest;

    /** Weak ref to the last bottom-UI view, so geometry blocks
     *  can also be logged at controller set and session start
     *  (v6 diagnostics). Weak: the views belong to YouTube. */
    private static volatile WeakReference<View> bottomUiRef;

    /** Weak ref to the loading overlay (v13 prepare veil), so
     *  hide can find it even if the anchor views are gone.
     *  Weak: the overlay belongs to YouTube's hierarchy. */
    private static volatile WeakReference<View> loadingOverlayRef;
    /** v62: the video container the decor-hosted veil mirrors
     *  (repositioned every guard tick). */
    private static volatile WeakReference<ViewGroup>
            veilVideoContainerRef;
    /** v64: label box + the YouTube time / K-row views that bound
     *  its slot (label sits between them, never over either). */
    private static volatile WeakReference<LinearLayout> loadingBoxRef;
    private static volatile WeakReference<View> veilTimeRef;
    private static volatile WeakReference<View> veilEndRef;
    private static volatile long lastSlotDiagMs;

    /** YouTube's own centered player loading ProgressBar.
     *  In YouTube 20.40.45 this is @id/player_loading_view_thin
     *  in res/layout/youtube_controls_overlay.xml. We toggle
     *  that actual view instead of drawing a substitute ring. */
    private static volatile WeakReference<ProgressBar>
            youtubeLoadingSpinnerRef;
    private static volatile int youtubeLoadingSpinnerVisibility =
            Integer.MIN_VALUE;

    /** YouTube's central play/pause/replay control. Every
     *  matching view is kept in the hierarchy with its
     *  visibility and layout 100% untouched but forced to
     *  alpha 0 while karaoke loads. Transparency (unlike
     *  INVISIBLE/GONE/detach) cannot reflow Previous/Next
     *  and persists across YouTube's own visibility flips,
     *  so taps during load can't flash the button. A 50 ms
     *  guard re-asserts alpha 0 on fresh inflations and keeps
     *  the veil + YouTube spinner topmost. */
    private static final class RemovedPlayerView {
        final WeakReference<View> view;
        final WeakReference<ViewGroup> parent;
        final int index;
        final ViewGroup.LayoutParams layoutParams;
        final int visibility;
        final float alpha;
        final boolean enabled;
        final boolean clickable;
        final boolean focusable;

        RemovedPlayerView(View view, ViewGroup parent) {
            this.view = new WeakReference<>(view);
            this.parent = new WeakReference<>(parent);
            this.index = parent.indexOfChild(view);
            this.layoutParams = view.getLayoutParams();
            this.visibility = view.getVisibility();
            float a;
            try {
                a = view.getAlpha();
            } catch (Exception ignored) {
                a = 1f;
            }
            this.alpha = a;
            this.enabled = view.isEnabled();
            this.clickable = view.isClickable();
            this.focusable = view.isFocusable();
        }
    }

    private static final ArrayList<RemovedPlayerView>
            removedYoutubePlayPauseViews = new ArrayList<>();
    private static volatile WeakReference<View> youtubeLoadingRootRef;
    private static volatile boolean youtubePlayPauseRemovalActive;
    private static Handler youtubePlayPauseGuardHandler;
    private static final Runnable youtubePlayPauseGuard = new Runnable() {
        @Override public void run() {
            if (!youtubePlayPauseRemovalActive) return;
            try {
                KaraokeSession current = session;
                KaraokeSession.State st = null;
                if (current != null) {
                    try {
                        st = current.state();
                    } catch (Exception ignored) {
                    }
                }
                // v54: the veil also covers mid-play rebuffers/
                // reconnects (state stays PLAYING then).
                boolean veiled = st == KaraokeSession.State.CONNECTING
                        || st == KaraokeSession.State.BUFFERING;
                if (!veiled && current != null) {
                    try {
                        veiled = current.isRebufferingOrReconnecting();
                    } catch (Exception ignored) {
                    }
                }
                if (!veiled) {
                    restoreYoutubePlayPauseButton();
                    return;
                }
                enforceYoutubePlayPauseRemoval();
            } catch (Exception e) {
                KaraokeLog.w("play/pause removal guard failed", e);
            }
            if (youtubePlayPauseRemovalActive
                    && youtubePlayPauseGuardHandler != null) {
                youtubePlayPauseGuardHandler.postDelayed(this, 50);
            }
        }
    };

    private static final Map<String, Method> METHOD_CACHE = new HashMap<>();

    /** Mute bookkeeping: the volume saved before muting, so unmute
     *  restores exactly what the user had. */
    private volatile boolean muted;
    private volatile float savedVolume = 1.0f;
    /** v30: the exact player object that was muted, so unmute
     *  restores THAT player even if the controller has since
     *  been replaced by a video switch. */
    private volatile Object mutedPlayer;

    private volatile boolean speedWarningLogged;

    private KaraokeBridge() {
    }

    // ---- patch entry points (called from injected code) ----

    /** Called by the patch at Laiwx.aB() entry with the controller. */
    /** v31: app context for the Cronet engine (set at button init). */
    static Context appContextOrNull() {
        return appContext;
    }

    public static void setController(Object controllerInstance) {
        controller = controllerInstance;
        KaraokeLog.i("controller set: "
                + (controllerInstance == null ? "null"
                        : controllerInstance.getClass().getName()));
        // v6 diagnostics: geometry at player open (bottom UI may
        // not exist yet — that ordering is itself data).
        logGeometry("controller set");
        // v6 warm-up: wake the Modal container now, so it is warm
        // by tap time. Fire-and-forget; never touches the session.
        KaraokeWarmup.ping(controllerInstance);
        // v30: video-switch stop (controller path). A new watch
        // controller appears when the user opens another video;
        // if the live session belongs to a different video, stop
        // it. Delayed: the new response may not be loaded yet.
        // v54 sticky K: one delayed check per controller set.
        // Not sticky: legacy stop-on-switch for a live session.
        // Sticky: ensure a LIVE session on the CURRENT video
        // (restart on switch, start when none/DONE/ERROR).
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                String newId = INSTANCE.getVideoIdOrNull();
                if (newId == null || newId.isEmpty()) return;
                KaraokeSession cur = session;
                boolean alive = false;
                String oldId = null;
                if (cur != null) {
                    try {
                        KaraokeSession.State st = cur.state();
                        alive = st == KaraokeSession.State.CONNECTING
                                || st == KaraokeSession.State.BUFFERING
                                || st == KaraokeSession.State.PLAYING
                                || st == KaraokeSession.State.PAUSED;
                        oldId = cur.sessionVideoId();
                    } catch (Exception ignored) {
                        alive = false;
                    }
                }
                if (!karaokeSticky) {
                    if (alive && oldId != null && !newId.equals(oldId)) {
                        KaraokeLog.i("video switched " + oldId
                                + " -> " + newId
                                + " (new controller); stopping karaoke");
                        cur.stop();
                        if (session == cur) session = null;
                        refreshKButton();
                    }
                    return;
                }
                if (alive && newId.equals(oldId)) return;
                if (alive) {
                    KaraokeLog.i("video switched " + oldId
                            + " -> " + newId
                            + " (sticky); restarting karaoke");
                    cur.stop();
                    if (session == cur) session = null;
                }
                if (session == null
                        || session.state() == KaraokeSession.State.IDLE
                        || session.state() == KaraokeSession.State.DONE
                        || session.state() == KaraokeSession.State.ERROR) {
                    if (session != null
                            && session.state()
                                    != KaraokeSession.State.IDLE) {
                        session = null;
                    }
                    restartForVideo(newId, "controller-switch");
                }
            } catch (Exception e) {
                KaraokeLog.e("setController switch check failed", e);
            }
        }, 1500);
    }

    /** Geometry block for the last known bottom UI, if any.
     *  Never throws, never affects playback or karaoke. */
    private static void logGeometry(String when) {
        try {
            WeakReference<View> ref = bottomUiRef;
            View ui = ref != null ? ref.get() : null;
            KaraokeGeometry.logBlock(ui, when);
        } catch (Exception e) {
            KaraokeLog.w("logGeometry failed", e);
        }
    }

    /**
     * Called by the patch at Lmjr.mi() exit with the inflated
     * bottom UI view (Lmju.r). Adds small "K", "BV", and
     * DEBUG-ONLY "LOG" text buttons beside the fullscreen
     * button. Never throws.
     *
     * Anchor order (both resolved BY NAME):
     *  1. bottom_end_container — the horizontal row in the bottom
     *     UI sitting immediately left of the fullscreen button.
     *     Appending here is layout-safe (LinearLayout, no
     *     constraints needed) and is the primary placement.
     *  2. The fullscreen button's own parent — insert before the
     *     button with a copy of its LayoutParams, so the new
     *     buttons inherit its size and bar position.
     * If neither anchor exists (e.g. a different bottom layout in
     * fullscreen), no buttons are added and the miss is logged —
     * a misplaced button over the video is worse than none.
     */
    /** v44: if the watch bottom UI is detached for good
     *  (miniplayer removed / watch closed), stop any live
     *  session instead of ghost-playing karaoke audio. A 1s
     *  recheck ignores transient re-parenting. */
    private static volatile WeakReference<View> detachWatchedUi;

    private static void watchWatchUiDetach(View bottomUi) {
        try {
            WeakReference<View> prev = detachWatchedUi;
            if (prev != null && prev.get() == bottomUi) return;
            detachWatchedUi = new WeakReference<>(bottomUi);
            bottomUi.addOnAttachStateChangeListener(
                    new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {}
                @Override public void onViewDetachedFromWindow(View v) {
                    final KaraokeSession s = session;
                    if (s == null) return;
                    KaraokeSession.State st = s.state();
                    if (st != KaraokeSession.State.PLAYING
                            && st != KaraokeSession.State.PAUSED
                            && st != KaraokeSession.State.BUFFERING
                            && st != KaraokeSession.State.CONNECTING) return;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            if (session == s
                                    && !v.isAttachedToWindow()) {
                                // v54 Q27: minimised/background play
                                // detaches the watch UI while the video
                                // keeps playing — that is ALIVE.
                                boolean bgPlaying = false;
                                try {
                                    bgPlaying = INSTANCE.isPlaying();
                                } catch (Exception ignored) {
                                }
                                if (bgPlaying) {
                                    KaraokeLog.i("watch UI gone but video"
                                            + " still playing (background);"
                                            + " karaoke continues");
                                    return;
                                }
                                // v63: paused karaoke START detaches the
                                // watch UI too (transient re-parenting at
                                // player open; the QS pause makes Q() read
                                // false here, which the bg check took as
                                // dead — killed a fresh BUFFERING session).
                                // Loading states are never orphan-killed;
                                // the 30s load watchdog owns their fate.
                                if (st == KaraokeSession.State.CONNECTING
                                        || st
                                            == KaraokeSession.State.BUFFERING) {
                                    KaraokeLog.i("watch UI detached "
                                            + "during load; ignoring (" + st
                                            + ")");
                                    return;
                                }
                                // A detach mid-PLAYING with Q() false can
                                // be a transient player rebuild — re-verify
                                // in 3s before killing anything (background-
                                // keep-alive was proven handling the old one).
                                if (st == KaraokeSession.State.PLAYING
                                        || st == KaraokeSession.State.PAUSED) {
                                    final KaraokeSession fs = s;
                                    new Handler(Looper.getMainLooper())
                                            .postDelayed(() -> {
                                        try {
                                            if (session != fs) return;
                                            KaraokeSession.State st2;
                                            try {
                                                st2 = fs.state();
                                            } catch (Exception ignored) {
                                                return;
                                            }
                                            if (st2 != KaraokeSession.State.PLAYING
                                                    && st2 != KaraokeSession.State.PAUSED
                                                    && st2 != KaraokeSession.State.BUFFERING
                                                    && st2 != KaraokeSession.State.CONNECTING) {
                                                return;
                                            }
                                            boolean stillDetached;
                                            boolean playing2 = false;
                                            try {
                                                View b = bottomUiRef != null
                                                        ? bottomUiRef.get()
                                                        : null;
                                                stillDetached = b == null
                                                        || !b.isAttachedToWindow();
                                            } catch (Exception ignored) {
                                                stillDetached = true;
                                            }
                                            try {
                                                playing2 =
                                                        INSTANCE.isPlaying();
                                            } catch (Exception ignored) {
                                            }
                                            if (stillDetached && !playing2) {
                                                KaraokeLog.i("watch UI gone "
                                                        + "confirmed dead (3s); "
                                                        + "stopping karaoke");
                                                fs.stop();
                                                if (session == fs) {
                                                    session = null;
                                                }
                                                refreshKButton();
                                            } else {
                                                KaraokeLog.i("watch UI detach "
                                                        + "transient; karaoke "
                                                        + "continues");
                                            }
                                        } catch (Exception e) {
                                            KaraokeLog.w("detach "
                                                    + "recheck failed", e);
                                        }
                                    }, 3000);
                                    return;
                                }
                                KaraokeLog.i("watch UI gone "
                                        + "(miniplayer removed?); "
                                        + "stopping karaoke");
                                karaokeSticky = false;
                                s.stop();
                                if (session == s) session = null;
                                refreshKButton();
                            }
                        } catch (Exception e) {
                            KaraokeLog.w("detach stop failed", e);
                        }
                    }, 1000);
                }
            });
        } catch (Exception e) {
            KaraokeLog.w("detach watch failed", e);
        }
    }

    public static void initializeBottomButtons(View bottomUi) {
        try {
            if (bottomUi == null) {
                KaraokeLog.e("initializeBottomButtons: bottom UI is null "
                        + "(Lmju.r was null at mi() exit)");
                return;
            }
            Context ctx = bottomUi.getContext();
            if (ctx != null) {
                appContext = ctx.getApplicationContext();
                // v65: warm-up targets the saved BYOK endpoint.
                KaraokeWarmup.configure(savedEndpoint());
            }
            bottomUiRef = new WeakReference<>(bottomUi);
            watchWatchUiDetach(bottomUi);
            // v72: undo any shift an older build applied in this
            // process, then run the (seek-bar-only) guard.
            restoreVideoAlign();
            startVideoAlignGuard();
            // v6 diagnostics: BEFORE block — the decisive half
            // of the before/after experiment (surface rect
            // before our buttons exist).
            KaraokeGeometry.logBlock(bottomUi, "BEFORE buttons");
            ResourcesLookup res = new ResourcesLookup(ctx);
            boolean kAdded = bottomUi.findViewWithTag("karaoke-button")
                    != null;
            boolean bvAdded = bottomUi.findViewWithTag("karaoke-bv-button")
                    != null;
            boolean keyAdded = bottomUi.findViewWithTag("karaoke-key-button")
                    != null;
            // DEBUG-ONLY: temporary LOG button, remove after debugging.
            boolean logAdded = bottomUi.findViewWithTag(
                    "karaoke-log-button-debug") != null;
            if (kAdded && bvAdded && logAdded) {
                KaraokeLog.d("initializeBottomButtons: buttons present");
                KaraokeGeometry.logBlock(bottomUi, "buttons present");
                refreshBvButton();
                refreshKeyButton();
                refreshKButton();
                return;
            }
            ViewGroup row = null;
            int rowId = res.id("bottom_end_container");
            if (rowId != 0) {
                View r = bottomUi.findViewById(rowId);
                if (r instanceof ViewGroup) row = (ViewGroup) r;
            }
            if (row != null) {
                if (!kAdded) {
                    TextView k = makeButton(ctx, "K", "karaoke-button",
                            v -> onKaraokeButtonClicked(),
                            v -> { showServerSetupDialog(null); return true; });
                    row.addView(k, rowParams(ctx));
                    kButtonRef = new WeakReference<>(k);
                    kAdded = true;
                }
                if (!bvAdded) {
                    TextView bv = makeButton(ctx, "BV",
                            "karaoke-bv-button",
                            v -> onBvButtonClicked(v), null);
                    row.addView(bv, rowParams(ctx));
                    bvButtonRef = new WeakReference<>(bv);
                    bvAdded = true;
                }
                if (!keyAdded) {
                    TextView key = makeButton(ctx, "KEY",
                            "karaoke-key-button",
                            v -> onKeyButtonClicked(v), null);
                    row.addView(key, rowParams(ctx));
                    keyButtonRef = new WeakReference<>(key);
                    keyAdded = true;
                }
                if (!logAdded) {
                    row.addView(makeLogButton(ctx), rowParams(ctx));
                    logAdded = true;
                }
                refreshBvButton();
                refreshKButton();
                bottomUi.post(() -> alignButtonsToFullscreen(bottomUi));
                // v6 diagnostics: AFTER block once layout settles,
                // plus a block on every later layout change
                // (change-only dedupe keeps repeats silent).
                bottomUi.post(() -> KaraokeGeometry.logBlock(bottomUi,
                        "AFTER buttons"));
                WeakReference<View> last = alignListenerUi;
                if (last == null || last.get() != bottomUi) {
                    bottomUi.addOnLayoutChangeListener(
                            (v, left, top, right, bottom,
                                    oldLeft, oldTop, oldRight, oldBottom) -> {
                                alignButtonsToFullscreen(bottomUi);
                                KaraokeGeometry.logBlock(bottomUi, "layout");
                            });
                    alignListenerUi = new WeakReference<>(bottomUi);
                }
                KaraokeLog.i("bottom buttons added via "
                        + "bottom_end_container row");
                return;
            }
            // Row missing: insert before the fullscreen button in
            // its own parent, copying its LayoutParams.
            int fsId = res.id("fullscreen_button");
            View anchor = fsId != 0 ? bottomUi.findViewById(fsId) : null;
            if (anchor != null && anchor.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) anchor.getParent();
                int index = parent.indexOfChild(anchor);
                if (!kAdded) {
                    TextView k = makeButton(ctx, "K", "karaoke-button",
                            v -> onKaraokeButtonClicked(),
                            v -> { showServerSetupDialog(null); return true; });
                    parent.addView(k, index, copyParams(anchor));
                    kButtonRef = new WeakReference<>(k);
                    kAdded = true;
                    index++;
                }
                if (!bvAdded) {
                    TextView bv = makeButton(ctx, "BV",
                            "karaoke-bv-button",
                            v -> onBvButtonClicked(v), null);
                    parent.addView(bv, index, copyParams(anchor));
                    bvButtonRef = new WeakReference<>(bv);
                    bvAdded = true;
                    index++;
                }
                if (!keyAdded) {
                    TextView key = makeButton(ctx, "KEY",
                            "karaoke-key-button",
                            v -> onKeyButtonClicked(v), null);
                    parent.addView(key, index, copyParams(anchor));
                    keyButtonRef = new WeakReference<>(key);
                    keyAdded = true;
                    index++;
                }
                if (!logAdded) {
                    parent.addView(makeLogButton(ctx), index,
                            copyParams(anchor));
                    logAdded = true;
                }
                refreshBvButton();
                refreshKeyButton();
                bottomUi.post(() -> alignButtonsToFullscreen(bottomUi));
                // v6 diagnostics: see primary path (AFTER block +
                // layout-change blocks, change-only deduped).
                bottomUi.post(() -> KaraokeGeometry.logBlock(bottomUi,
                        "AFTER buttons"));
                WeakReference<View> last2 = alignListenerUi;
                if (last2 == null || last2.get() != bottomUi) {
                    bottomUi.addOnLayoutChangeListener(
                            (v, left, top, right, bottom,
                                    oldLeft, oldTop, oldRight, oldBottom) -> {
                                alignButtonsToFullscreen(bottomUi);
                                KaraokeGeometry.logBlock(bottomUi, "layout");
                            });
                    alignListenerUi = new WeakReference<>(bottomUi);
                }
                KaraokeLog.i("bottom buttons added before "
                        + "fullscreen_button (row anchor missing)");
                return;
            }
            KaraokeLog.e("initializeBottomButtons: no anchor found "
                    + "(no bottom_end_container row, no "
                    + "fullscreen_button); buttons not added");
        } catch (Exception e) {
            // Never crash the player UI thread over buttons.
            KaraokeLog.e("initializeBottomButtons failed", e);
        }
    }

    /** Small text button matching the bottom-bar siblings: white
     *  text, transparent background, no grey box. A Button widget
     *  brings the platform grey background — that was the v1 bug
     *  (a big KARAOKE box over the video). TextView has none.
     *  Horizontal padding 12dp + 4dp side margins give even gaps
     *  between K, BV, LOG, and the fullscreen icon (whose own
     *  60dp view centers a ~24dp glyph, i.e. ~18dp of internal
     *  padding on its left). includeFontPadding off so the text
     *  centers cleanly in the 60dp height. */
    private static TextView makeButton(Context ctx, String text, String tag,
            View.OnClickListener click,
            View.OnLongClickListener longClick) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTag(tag);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setIncludeFontPadding(false);
        float density = ctx.getResources().getDisplayMetrics().density;
        int hPad = (int) (12 * density + 0.5f);
        b.setPadding(hPad, 0, hPad, 0);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(click);
        if (longClick != null) b.setOnLongClickListener(longClick);
        return b;
    }

    /** CC-style active look, applied explicitly (setActivated
     *  alone shows nothing without a state-list drawable):
     *  inactive = white text on transparent background (the
     *  CC button's outlined state); active = white-filled
     *  rounded background with black text (the CC button
     *  "fills with white" when toggled on). */
    // ---------------- key change (docs/KEYCHANGE.md) ----------------

    /** Bridge-side host for the KEY panel (see KaraokeKeyUi). */
    private static final KaraokeKeyUi.Host KEY_HOST = new KaraokeKeyUi.Host() {
        @Override
        public int keySemitones() {
            return keySemitones;
        }

        @Override
        public void setKeySemitones(int semitones, String why) {
            applyKey(semitones, why);
        }

        @Override
        public KaraokeVoiceProfile userProfile() {
            return userVoiceProfile;
        }

        @Override
        public void setUserProfile(KaraokeVoiceProfile profile) {
            userVoiceProfile = profile;
            KaraokeSession s = session;
            if (s != null) s.setUserProfile(profile);
        }

        @Override
        public KaraokeVoiceProfile songProfile() {
            KaraokeSession s = session;
            return s != null ? s.getSongProfile() : currentSongProfile;
        }

        @Override
        public void toast(String message, boolean longDuration) {
            KaraokeBridge.toast(message, longDuration);
        }

        @Override
        public void refreshKeyButton() {
            KaraokeBridge.refreshKeyButton();
        }
    };

    /** Push the current shift into the live session (if any). */
    private static void applyKey(int semitones, String why) {
        int folded = KaraokeVoiceProfile.fold(semitones);
        keySemitones = folded;
        KaraokeSession s = session;
        if (s != null) {
            try {
                s.setKeySemitones(folded);
            } catch (Exception e) {
                KaraokeLog.w("applyKey: session call failed", e);
            }
        }
        refreshKeyButton();
        String label = KaraokeVoiceProfile.semitoneLabel(folded);
        toast("Key " + label + ("0".equals(label) ? " (original)" : "")
                + (why == null ? "" : " - " + why));
        KaraokeLog.i("key: applied " + label
                + " (" + (why == null ? "manual" : why) + ")");
    }

    /** KEY label: "KEY" at 0, otherwise "KEY +2" / "KEY -1". */
    private static void refreshKeyButton() {
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                WeakReference<TextView> ref = keyButtonRef;
                TextView b = ref != null ? ref.get() : null;
                if (b == null) return;
                int k = keySemitones;
                b.setText(k == 0 ? "KEY"
                        : "KEY " + KaraokeVoiceProfile.semitoneLabel(k));
                setButtonActive(b, k != 0);
            });
        } catch (Exception e) {
            KaraokeLog.w("refreshKeyButton: post failed", e);
        }
    }

    private static void onKeyButtonClicked(View button) {
        if (!karaokeSticky) {
            toast("Turn on K first");
            return;
        }
        Context ctx = button != null ? button.getContext() : appContext;
        KaraokeKeyUi.showDialog(ctx, KEY_HOST);
    }

    private static void setButtonActive(TextView b, boolean active) {
        if (b == null) return;
        if (active) {
            float density = b.getResources().getDisplayMetrics().density;
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setCornerRadius(8 * density);
            b.setBackground(bg);
            b.setTextColor(Color.BLACK);
        } else {
            b.setBackgroundColor(Color.TRANSPARENT);
            b.setTextColor(Color.WHITE);
        }
    }

    /**
     * Aligns our buttons' TEXT (glyph) centers to the fullscreen
     * icon's center (v4 fix). Measured on the phone (v3): our
     * text sat ~12px above the fullscreen icon and the
     * timestamp, although all views are 60dp tall. Two causes:
     * (1) bottom_end_container has 3dp bottom padding, so
     * CENTER_VERTICAL centers our text ~3dp high inside the
     * row; (2) a TextView's visible glyphs sit above its view
     * center (font box vs cap height), ~4px more. View-center
     * alignment alone cannot fix (2). So this aligns GLYPH
     * centers: each button's ink bounds (Paint.getTextBounds)
     * + baseline, against the fullscreen view's center, via
     * translationY (our views only — never the anchor, never
     * the container). All inputs are layout coords with the
     * current translation subtracted out, so re-running sets
     * the same value instead of accumulating; it only writes
     * (and logs) when the value actually changes. Called once
     * after layout (post) and on every bottom-UI layout change
     * (covers late fullscreen-stub inflation); skips silently
     * when the fullscreen view is missing or not yet laid out.
     */
    private static volatile WeakReference<View> alignListenerUi;

    private static void alignButtonsToFullscreen(View bottomUi) {
        try {
            if (bottomUi == null) return;
            Context ctx = bottomUi.getContext();
            if (ctx == null) return;
            ResourcesLookup res = new ResourcesLookup(ctx);
            int fsId = res.id("fullscreen_button");
            View fs = fsId != 0 ? bottomUi.findViewById(fsId) : null;
            if (fs == null || fs.getHeight() <= 0) return;
            int[] fsLoc = new int[2];
            fs.getLocationOnScreen(fsLoc);
            float fsCenter = fsLoc[1] - fs.getTranslationY()
                    + fs.getHeight() / 2.0f;
            int rowId = res.id("bottom_end_container");
            View rowView = rowId != 0 ? bottomUi.findViewById(rowId) : null;
            int rowH = rowView != null ? rowView.getHeight() : -1;
            String[] tags = {"karaoke-button", "karaoke-bv-button",
                    "karaoke-log-button-debug"};
            for (String tag : tags) {
                View v = bottomUi.findViewWithTag(tag);
                if (!(v instanceof TextView)) continue;
                TextView b = (TextView) v;
                if (b.getHeight() <= 0 || b.getBaseline() < 0) continue;
                CharSequence text = b.getText();
                if (text == null || text.length() == 0) continue;
                android.graphics.Rect ink = new android.graphics.Rect();
                b.getPaint().getTextBounds(text.toString(), 0,
                        text.length(), ink);
                int[] loc = new int[2];
                b.getLocationOnScreen(loc);
                float glyphCenter = loc[1] - b.getTranslationY()
                        + b.getBaseline() + (ink.top + ink.bottom) / 2.0f;
                float delta = fsCenter - glyphCenter;
                if (Math.abs(delta - b.getTranslationY()) > 0.5f) {
                    b.setTranslationY(delta);
                    KaraokeLog.d("alignButtons: " + tag + " delta=" + delta
                            + " rowH=" + rowH
                            + " bottomUiH=" + bottomUi.getHeight());
                }
            }
        } catch (Exception e) {
            KaraokeLog.w("alignButtons failed", e);
        }
    }

    /** v69: the translation WE applied to the video container
     *  (so it can always be undone). v64's one-way shift stayed
     *  applied after YouTube re-laid the surface for aspect,
     *  overshooting it above the frame and leaving black space
     *  at the bottom. v71: also track the original top MARGIN —
     *  a layout-real margin shift moves the SurfaceView's actual
     *  surface (parent translation may not), and it is restored
     *  the same tracked way. */
    private static volatile float videoAlignOriginalTy;
    private static volatile int videoAlignOriginalMargin;
    private static volatile boolean videoAlignMarginKnown;
    private static volatile boolean videoAlignApplied;

    private static void restoreVideoAlign() {
        try {
            if (!videoAlignApplied) return;
            WeakReference<ViewGroup> tref = videoAlignTargetRef;
            ViewGroup video = tref != null ? tref.get() : null;
            if (video != null) {
                video.setTranslationY(videoAlignOriginalTy);
                if (videoAlignMarginKnown
                        && video.getLayoutParams()
                                instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams mlp =
                            (ViewGroup.MarginLayoutParams)
                                    video.getLayoutParams();
                    if (mlp.topMargin != videoAlignOriginalMargin) {
                        mlp.topMargin = videoAlignOriginalMargin;
                        video.setLayoutParams(mlp);
                    }
                }
                KaraokeLog.i("video align: restored "
                        + "(translationY=" + videoAlignOriginalTy
                        + ", topMargin=" + videoAlignOriginalMargin
                        + ")");
            }
        } catch (Exception ignored) {
        }
        videoAlignApplied = false;
    }

    // ---- v70 light-blue karaoke buffered seek bar ----

    /** Light blue for the vocal-removed buffered segment. */
    private static final int KARAOKE_BUFFER_BLUE = 0xFF6EC1FF;
    private static final String TIME_BAR_CLASS =
            "com.google.android.libraries.youtube.player.features"
            + ".overlay.timebar.TimeBar";
    /** Original {bufferedColor, bufferedEndMs} per TimeBar model,
     *  restored when karaoke turns off. Weak keys: models are
     *  recreated on video switches and must GC freely. */
    private static final java.util.Map<Object, long[]>
            karaokeBarOriginals = java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<>());

    private static java.lang.reflect.Field fieldUp(Object target,
            String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field field =
                        type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static ArrayList<View> findTimeBars(View root) {
        ArrayList<View> matches = new ArrayList<>();
        if (root == null) return matches;
        ArrayDeque<View> stack = new ArrayDeque<>();
        stack.push(root);
        int budget = 6000;
        while (!stack.isEmpty() && budget-- > 0) {
            View view = stack.pop();
            if (TIME_BAR_CLASS.equals(view.getClass().getName())
                    && view.getWidth() > 0) {
                matches.add(view);
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = group.getChildCount() - 1; i >= 0; i--) {
                    View child = group.getChildAt(i);
                    if (child != null) stack.push(child);
                }
            }
        }
        return matches;
    }

    /** While karaoke is active, YouTube's buffered segment (the
     *  gray part) shows the VOCAL-REMOVED buffered extent in
     *  light blue — same real TimeBar drawing, repurposed model
     *  values (alja: i=buffered color, b=buffered end ms,
     *  a=duration ms, e=offset ms). gn() re-derives paints and
     *  rects from the model, so the change is persistent and
     *  exact. Restored (color + end) when karaoke turns off. */
    private static void updateKaraokeSeekbar(View root) {
        try {
            KaraokeSession current = session;
            boolean active = karaokeSticky && current != null;
            KaraokeSession.State state = null;
            if (current != null) {
                try {
                    state = current.state();
                } catch (Exception ignored) {
                }
            }
            active = active && (state == KaraokeSession.State.CONNECTING
                    || state == KaraokeSession.State.BUFFERING
                    || state == KaraokeSession.State.PLAYING
                    || state == KaraokeSession.State.PAUSED);
            if (!active) {
                restoreKaraokeSeekbar();
                return;
            }
            if (root == null) return;
            double coverageSec = -1;
            try {
                coverageSec = current.bufferedEndSec();
            } catch (Exception ignored) {
            }
            for (View timeBar : findTimeBars(root)) {
                try {
                    Object model = fieldUp(timeBar, "L").get(timeBar);
                    if (model == null) continue;
                    // Only the standard model carries these fields
                    // (live/other variants are skipped silently).
                    if (!"alja".equals(
                            model.getClass().getName())) continue;
                    java.lang.reflect.Field colorField =
                            fieldUp(model, "i");
                    java.lang.reflect.Field bufferedField =
                            fieldUp(model, "b");
                    java.lang.reflect.Field durationField =
                            fieldUp(model, "a");
                    java.lang.reflect.Field offsetField =
                            fieldUp(model, "e");
                    if (!karaokeBarOriginals.containsKey(model)) {
                        karaokeBarOriginals.put(model, new long[] {
                                colorField.getInt(model),
                                bufferedField.getLong(model)});
                    }
                    colorField.setInt(model, KARAOKE_BUFFER_BLUE);
                    long duration = durationField.getLong(model);
                    long offset = offsetField.getLong(model);
                    if (duration > offset && coverageSec >= 0) {
                        long karaokeEnd = offset
                                + (long) (coverageSec * 1000.0);
                        bufferedField.setLong(model, Math.max(offset,
                                Math.min(duration, karaokeEnd)));
                    }
                    java.lang.reflect.Method gn =
                            timeBar.getClass().getDeclaredMethod("gn");
                    gn.setAccessible(true);
                    gn.invoke(timeBar);
                } catch (Exception e) {
                    KaraokeLog.w("karaoke seekbar tint failed", e);
                }
            }
        } catch (Exception e) {
            KaraokeLog.w("karaoke seekbar update failed", e);
        }
    }

    private static void restoreKaraokeSeekbar() {
        if (karaokeBarOriginals.isEmpty()) return;
        synchronized (karaokeBarOriginals) {
            for (java.util.Map.Entry<Object, long[]> entry
                    : karaokeBarOriginals.entrySet()) {
                Object model = entry.getKey();
                long[] original = entry.getValue();
                if (model == null || original == null) continue;
                try {
                    fieldUp(model, "i").setInt(model, (int) original[0]);
                    fieldUp(model, "b").setLong(model, original[1]);
                } catch (Exception e) {
                    KaraokeLog.w("karaoke seekbar restore failed", e);
                }
            }
            karaokeBarOriginals.clear();
        }
    }

    /** v77: the watch seek bar is `igk`; its buffered segment is
     *  drawn by the bar itself from its model (alja: b = buffered
     *  end ms) and paint bundle (igl). No overlays anymore (they
     *  floated/clamped wrong while scrolling): while karaoke is
     *  active we set the REAL bar's buffered end to the karaoke
     *  coverage end and its buffered paints (alja.m, copied into
     *  igl.i by gn(), plus igl.b) to light blue, then gn() so the
     *  bar redraws itself. The native buffered rect spans from
     *  the bar's left, so behind + ahead are covered and seeking
     *  stays clamped by the bar's own geometry. Restored on off. */
    private static volatile long lastSeekbarDiagMs;
    private static volatile long lastSeekbarFoundDiagMs;

    private static class IgkOriginal {
        final WeakReference<View> view;
        final long bufferedEnd;
        /** Colors of every resolved buffered paint, in order. */
        final java.util.ArrayList<Integer> paintColors;
        IgkOriginal(View view, long bufferedEnd,
                java.util.ArrayList<Integer> paintColors) {
            this.view = new WeakReference<>(view);
            this.bufferedEnd = bufferedEnd;
            this.paintColors = paintColors;
        }
    }

    /** Every buffered paint the bar can draw from: igk's own
     *  bundle (c) plus the renderer's bundle (ign.a / igt.c) —
     *  they are not guaranteed to be the same igl instance, and
     *  painting only one left the future segment red. Distinct
     *  igl.c / igl.e paints, in resolution order. */
    private static java.util.ArrayList<android.graphics.Paint>
            resolveBufferedPaints(View barView) {
        java.util.ArrayList<android.graphics.Paint> out =
                new java.util.ArrayList<>();
        Object[] bundles = new Object[3];
        try {
            bundles[0] = fieldUp(barView, "c").get(barView);
        } catch (Exception ignored) {
        }
        try {
            Object ign = fieldUp(barView, "h").get(barView);
            if (ign != null) bundles[1] = fieldUp(ign, "a").get(ign);
        } catch (Exception ignored) {
        }
        try {
            Object igt = fieldUp(barView, "i").get(barView);
            if (igt != null) bundles[2] = fieldUp(igt, "c").get(igt);
        } catch (Exception ignored) {
        }
        for (Object bundle : bundles) {
            if (bundle == null || !"igl".equals(
                    bundle.getClass().getSimpleName())) continue;
            for (String fieldName : new String[] {"c", "e"}) {
                try {
                    android.graphics.Paint paint =
                            (android.graphics.Paint)
                                    fieldUp(bundle, fieldName).get(bundle);
                    if (paint != null && !out.contains(paint)) {
                        out.add(paint);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    private static final java.util.Map<Object, IgkOriginal>
            karaokeIgkOriginals = java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<>());

    private static void seekbarDiag(String message) {
        long now = System.currentTimeMillis();
        if (now - lastSeekbarDiagMs < 3000) return;
        lastSeekbarDiagMs = now;
        KaraokeLog.d("seekbar diag: " + message);
    }

    private static void updateKaraokeSeekbarActual(View root) {
        try {
            KaraokeSession current = session;
            boolean active = karaokeSticky && current != null;
            KaraokeSession.State state = null;
            if (current != null) {
                try {
                    state = current.state();
                } catch (Exception ignored) {
                }
            }
            active = active && (state == KaraokeSession.State.CONNECTING
                    || state == KaraokeSession.State.BUFFERING
                    || state == KaraokeSession.State.PLAYING
                    || state == KaraokeSession.State.PAUSED);
            if (!active || root == null) {
                restoreKaraokeSeekbarActual();
                return;
            }
            double endSec;
            try {
                endSec = current.bufferedEndSec();
                double[] cached = current.karaokeCachedCoverage();
                if (cached != null) {
                    endSec = Math.max(endSec, cached[1]);
                }
            } catch (Exception e) {
                endSec = -1;
            }
            seekbarDiag("state=" + state + " covEnd="
                    + String.format("%.1f", endSec));
            if (endSec < 0) return;
            // Widest shown igk = the full-width watch bar.
            View barView = null;
            ArrayDeque<View> stack = new ArrayDeque<>();
            stack.push(root);
            int budget = 6000;
            while (!stack.isEmpty() && budget-- > 0) {
                View view = stack.pop();
                if ("igk".equals(view.getClass().getSimpleName())
                        && view.getWidth() > 0 && view.isShown()) {
                    if (barView == null
                            || view.getWidth() > barView.getWidth()) {
                        barView = view;
                    }
                }
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = group.getChildCount() - 1; i >= 0; i--) {
                        View child = group.getChildAt(i);
                        if (child != null) stack.push(child);
                    }
                }
            }
            if (barView == null) {
                if (System.currentTimeMillis()
                        - lastSeekbarFoundDiagMs > 3000) {
                    lastSeekbarFoundDiagMs = System.currentTimeMillis();
                    KaraokeLog.d("seekbar diag: no shown igk seek bar");
                }
                return;
            }
            Object model = fieldUp(barView, "L").get(barView);
            if (model == null || !"alja".equals(
                    model.getClass().getName())) {
                return;
            }
            java.lang.reflect.Field bField = fieldUp(model, "b");
            java.lang.reflect.Field aField = fieldUp(model, "a");
            java.lang.reflect.Field eField = fieldUp(model, "e");
            long duration = aField.getLong(model);
            long offset = eField.getLong(model);
            if (duration <= offset) return;
            // Buffered paints, proven from igq case 1 (the
            // future segment draw): igl.c normally, igl.e in the
            // live state — across every bundle instance the
            // renderer can hold (igk.c vs ign.a/igt.c differ).
            java.util.ArrayList<android.graphics.Paint> paints =
                    resolveBufferedPaints(barView);
            if (!karaokeIgkOriginals.containsKey(model)) {
                java.util.ArrayList<Integer> colors =
                        new java.util.ArrayList<>();
                for (android.graphics.Paint paint : paints) {
                    colors.add(paint.getColor());
                }
                karaokeIgkOriginals.put(model, new IgkOriginal(
                        barView, bField.getLong(model), colors));
                KaraokeLog.i("karaoke seekbar: actual bar hooked "
                        + "(paints=" + paints.size() + ")");
            }
            long karaokeEnd = offset + (long) (endSec * 1000.0);
            bField.setLong(model, Math.max(offset,
                    Math.min(duration, karaokeEnd)));
            for (android.graphics.Paint paint : paints) {
                paint.setColor(KARAOKE_BUFFER_BLUE);
            }
            java.lang.reflect.Method gn =
                    barView.getClass().getDeclaredMethod("gn");
            gn.setAccessible(true);
            gn.invoke(barView);
            barView.invalidate();
            if (System.currentTimeMillis() - lastSeekbarFoundDiagMs
                    > 3000) {
                lastSeekbarFoundDiagMs = System.currentTimeMillis();
                KaraokeLog.d("seekbar diag: actual igk tinted, "
                        + "bufferedEnd=" + bField.getLong(model)
                        + " of " + duration);
            }
        } catch (Exception e) {
            KaraokeLog.w("karaoke seekbar actual failed", e);
        }
    }

    private static void restoreKaraokeSeekbarActual() {
        if (karaokeIgkOriginals.isEmpty()) return;
        synchronized (karaokeIgkOriginals) {
            for (java.util.Map.Entry<Object, IgkOriginal> entry
                    : karaokeIgkOriginals.entrySet()) {
                Object model = entry.getKey();
                IgkOriginal original = entry.getValue();
                if (model == null || original == null) continue;
                try {
                    fieldUp(model, "b").setLong(model,
                            original.bufferedEnd);
                    View view = original.view.get();
                    if (view != null) {
                        try {
                            java.util.ArrayList<android.graphics.Paint>
                                    paints = resolveBufferedPaints(view);
                            for (int i = 0; i < paints.size()
                                    && i < original.paintColors.size();
                                    i++) {
                                paints.get(i).setColor(
                                        original.paintColors.get(i));
                            }
                        } catch (Exception ignored) {
                        }
                        java.lang.reflect.Method gn = view.getClass()
                                .getDeclaredMethod("gn");
                        gn.setAccessible(true);
                        gn.invoke(view);
                        view.invalidate();
                    }
                } catch (Exception e) {
                    KaraokeLog.w("karaoke seekbar restore failed", e);
                }
            }
            karaokeIgkOriginals.clear();
        }
    }

    // ---- v73 player double-inset fix ----

    /** Tracked correction state for the ajjm inset (below). */
    private static volatile WeakReference<View> insetTargetRef;
    private static volatile int insetOriginalMargin;
    private static volatile float insetOriginalTy;
    private static volatile boolean insetApplied;
    private static volatile boolean insetDiagLogged;

    private static void restorePlayerInset() {
        try {
            if (!insetApplied) return;
            WeakReference<View> ref = insetTargetRef;
            View ajjm = ref != null ? ref.get() : null;
            if (ajjm != null) {
                ajjm.setTranslationY(insetOriginalTy);
                if (ajjm.getLayoutParams()
                        instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams mlp =
                            (ViewGroup.MarginLayoutParams)
                                    ajjm.getLayoutParams();
                    if (mlp.topMargin != insetOriginalMargin) {
                        mlp.topMargin = insetOriginalMargin;
                        ajjm.setLayoutParams(mlp);
                    }
                }
                KaraokeLog.i("player inset: restored");
            }
        } catch (Exception ignored) {
        }
        insetApplied = false;
    }

    /** v73: the real pushed-down bug, proven by window-space
     *  geometry: YouTubePlayerView@win(0,115) hosts ajjm@win(0,230)
     *  — the player content is inset a SECOND status-bar height
     *  inside its own container (double inset), overflowing the
     *  container's bottom. Fix at the ajjm level (not ajjl, whose
     *  aspect letterboxing fought back in v64-v71): shift ajjm
     *  up by its in-parent offset, tracked + reversible, only in
     *  portrait and only while ajjm fills its parent. */
    private static void fixPlayerInset(View bottomUi) {
        try {
            if (bottomUi == null) return;
            Context ctx = bottomUi.getContext();
            if (ctx == null) return;
            if (ctx.getResources().getConfiguration().orientation
                    != Configuration.ORIENTATION_PORTRAIT) {
                restorePlayerInset();
                return;
            }
            View root = bottomUi.getRootView();
            if (root == null) return;
            View surface = findVideoSurface(root);
            if (surface == null) return;
            ViewParent p1 = surface.getParent();
            ViewParent p2 = p1 instanceof View ? ((View) p1).getParent() : null;
            ViewParent p3 = p2 instanceof View ? ((View) p2).getParent() : null;
            if (!(p2 instanceof ViewGroup) || !(p3 instanceof View)) return;
            ViewGroup ajjm = (ViewGroup) p2;
            View playerView = (View) p3;
            if (!playerView.getClass().getName()
                    .endsWith("YouTubePlayerViewNotForReflection")) {
                return;
            }
            WeakReference<View> tref = insetTargetRef;
            if (tref == null || tref.get() != ajjm) {
                insetTargetRef = new WeakReference<View>(ajjm);
                insetApplied = false;
                insetOriginalTy = ajjm.getTranslationY();
                insetOriginalMargin = ajjm.getLayoutParams()
                        instanceof ViewGroup.MarginLayoutParams
                        ? ((ViewGroup.MarginLayoutParams)
                                ajjm.getLayoutParams()).topMargin : 0;
                insetDiagLogged = false;
            }
            int[] ajjmLoc = new int[2];
            int[] playerLoc = new int[2];
            ajjm.getLocationInWindow(ajjmLoc);
            playerView.getLocationInWindow(playerLoc);
            int currentMargin = ajjm.getLayoutParams()
                    instanceof ViewGroup.MarginLayoutParams
                    ? ((ViewGroup.MarginLayoutParams)
                            ajjm.getLayoutParams()).topMargin : 0;
            float currentTy = ajjm.getTranslationY();
            int naturalTop = Math.round(ajjmLoc[1]
                    - (currentTy - insetOriginalTy)
                    - (currentMargin - insetOriginalMargin));
            int naturalOffset = naturalTop - playerLoc[1];
            if (!insetDiagLogged) {
                insetDiagLogged = true;
                KaraokeLog.i("player inset diag: ajjmTop=" + ajjmLoc[1]
                        + " playerTop=" + playerLoc[1]
                        + " offset=" + naturalOffset
                        + " topMargin=" + currentMargin
                        + " translationY=" + currentTy
                        + " parentPaddingTop="
                        + playerView.getPaddingTop());
            }
            boolean fillsParent = ajjm.getHeight()
                    >= playerView.getHeight() - 8;
            if (naturalOffset >= 40 && naturalOffset <= 240
                    && fillsParent) {
                if (ajjm.getLayoutParams()
                        instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams mlp =
                            (ViewGroup.MarginLayoutParams)
                                    ajjm.getLayoutParams();
                    int target = insetOriginalMargin - naturalOffset;
                    if (mlp.topMargin != target) {
                        mlp.topMargin = target;
                        ajjm.setLayoutParams(mlp);
                        insetApplied = true;
                        KaraokeLog.i("player inset fixed: offset "
                                + naturalOffset + " via topMargin="
                                + target);
                    }
                } else {
                    float target = insetOriginalTy - naturalOffset;
                    if (Math.abs(currentTy - target) > 0.5f) {
                        ajjm.setTranslationY(target);
                        insetApplied = true;
                        KaraokeLog.i("player inset fixed: offset "
                                + naturalOffset + " via translationY="
                                + target);
                    }
                }
            } else {
                restorePlayerInset();
            }
        } catch (Exception e) {
            KaraokeLog.w("player inset fix failed", e);
        }
    }

    /** Starts the 500ms align poll (idempotent). Runs for the
     *  watch UI's lifetime; stops when the bottom UI is gone. */
    private static void startVideoAlignGuard() {
        if (videoAlignGuardActive) return;
        videoAlignGuardActive = true;
        if (videoAlignHandler == null) {
            videoAlignHandler = new Handler(Looper.getMainLooper());
        }
        videoAlignHandler.removeCallbacks(videoAlignGuard);
        videoAlignHandler.postDelayed(videoAlignGuard, 500);
    }

    /** K button states (v42): LOADING shows a spinner glyph and
     *  ignores taps (no accidental stop/resume mid-load); LIVE is
     *  filled and tapping stops; IDLE is plain K and tapping
     *  starts. Always posts to the main thread. */
    private enum KMode { IDLE, LOADING, LIVE }

    /** v54: K shows the STICKY flag (filled from the tap until
     *  tapped again), not transient session state — so DONE,
     *  ERROR, and between-song gaps keep K on until the user
     *  taps it off. Always posts to the main thread. */
    private static void refreshKButton() {
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                WeakReference<TextView> ref = kButtonRef;
                TextView k = ref != null ? ref.get() : null;
                if (k == null) return;
                k.setText("K");
                setButtonActive(k, karaokeSticky);
            });
        } catch (Exception e) {
            KaraokeLog.w("refreshKButton: post failed", e);
        }
    }

    /** BV is filled when its flag is on, plain when off. Flag
     *  only — v1 audio is unchanged (stage 2 adds the backing
     *  stream). Posted to the main thread like refreshKButton. */
    private static void refreshBvButton() {
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                WeakReference<TextView> ref = bvButtonRef;
                TextView bv = ref != null ? ref.get() : null;
                if (bv == null) return;
                setButtonActive(bv,
                        backingVocalsEnabled && karaokeSticky);
            });
        } catch (Exception e) {
            KaraokeLog.w("refreshBvButton: post failed", e);
        }
    }

    /** DEBUG-ONLY (temporary): LOG button. Tapping it copies the
     *  recent karaoke log to the clipboard — faster than the
     *  long-press-K share sheet when the user just needs to
     *  paste the log into chat. REMOVE AFTER DEBUGGING: delete
     *  this method, its makeLogButton call sites, and the
     *  karaoke-log-button-debug tag checks. */
    private static TextView makeLogButton(Context ctx) {
        return makeButton(ctx, "LOG", "karaoke-log-button-debug",
                v -> copyLogToClipboard(), null);
    }

    /** LayoutParams for the bottom row: wrap content wide, 60dp
     *  tall — the fullscreen button's height (its ViewStub is
     *  60x60dp, bottom-anchored to the same seekbar frame as
     *  this row). Equal height + equal bottom anchor = equal
     *  vertical centers. The v2 bug was WRAP_CONTENT height
     *  (~28dp of text+padding), which sat ~16dp lower than the
     *  fullscreen icon's center. 4dp side margins + 12dp
     *  padding = even ~32dp text-to-text gaps. */
    private static LinearLayout.LayoutParams rowParams(Context ctx) {
        float density = ctx.getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (int) (60 * density + 0.5f));
        p.gravity = Gravity.CENTER_VERTICAL;
        int margin = (int) (4 * density + 0.5f);
        p.setMargins(margin, 0, margin, 0);
        return p;
    }

    /**
     * Copies the anchor's LayoutParams so an inserted sibling
     * inherits its size and bar position, then forces the
     * button shape: WRAP_CONTENT wide (a 60dp-wide copy would
     * leave huge gaps around "K"), 60dp tall like the
     * fullscreen button, 4dp side margins. ConstraintLayout
     * children need their constraint fields too: after copying,
     * point the new view to the LEFT of the anchor (rightToLeft)
     * and copy the anchor's BOTTOM anchor (bottomToTop, the
     * seekbar frame) so the fallback buttons are bottom-aligned
     * with fullscreen instead of floating to the parent top
     * (the old code copied only the horizontal constraint, so
     * a fallback button had no vertical anchor at all).
     * Clear the anchor's own right-side constraint fields so
     * the new view does not also pin to the anchor's right
     * edge. All reflection is best-effort: on failure the
     * plain copy is used and the miss is logged. Only the
     * NEW view's params are ever touched — never the
     * anchor's, never the container's — so this path cannot
     * resize or move the video surface or any other view.
     */
    private static ViewGroup.LayoutParams copyParams(View anchor) {
        ViewGroup.LayoutParams src = anchor.getLayoutParams();
        ViewGroup.LayoutParams copy = null;
        if (src != null) {
            try {
                Constructor<?> c = src.getClass().getConstructor(
                        ViewGroup.LayoutParams.class);
                copy = (ViewGroup.LayoutParams) c.newInstance(src);
            } catch (Exception e) {
                KaraokeLog.w("copyParams: copy-constructor failed, "
                        + "using plain params", e);
            }
        }
        float density = anchor.getResources().getDisplayMetrics().density;
        if (copy == null) {
            copy = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    (int) (60 * density + 0.5f));
        } else {
            copy.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            copy.height = src != null && src.height > 0 ? src.height
                    : (int) (60 * density + 0.5f);
        }
        if (copy instanceof ViewGroup.MarginLayoutParams) {
            int margin = (int) (4 * density + 0.5f);
            ((ViewGroup.MarginLayoutParams) copy).setMargins(
                    margin, 0, margin, 0);
        }
        try {
            // ConstraintLayout fields (support + androidx share
            // these names). rightToLeft = anchor id puts the new
            // view left of the anchor; bottomToTop = the
            // anchor's bottom target keeps it bottom-aligned
            // with fullscreen; clear right-side links so it
            // does not also pin to the anchor's right edge.
            setIntField(copy, "rightToLeft", anchor.getId());
            setIntField(copy, "rightToRight", -1);
            setIntField(copy, "endToEnd", -1);
            if (src != null) {
                try {
                    Field bf = src.getClass().getField("bottomToTop");
                    setIntField(copy, "bottomToTop", bf.getInt(src));
                } catch (Exception ignored) {
                    // Non-ConstraintLayout parent: no-op.
                }
            }
        } catch (Exception e) {
            KaraokeLog.w("copyParams: constraint tweak failed "
                    + "(non-ConstraintLayout parent?)", e);
        }
        return copy;
    }

    private static void setIntField(Object target, String name, int value)
            throws Exception {
        Field f = target.getClass().getField(name);
        f.setInt(target, value);
    }

    /** Resource lookup by NAME. Numeric IDs are never hard-coded:
     *  they shift between YouTube builds; names are stable. */
    private static class ResourcesLookup {
        private final Context ctx;

        ResourcesLookup(Context ctx) {
            this.ctx = ctx;
        }

        int id(String name) {
            if (ctx == null) return 0;
            try {
                return ctx.getResources().getIdentifier(
                        name, "id", ctx.getPackageName());
            } catch (Exception e) {
                KaraokeLog.e("resource lookup failed for '" + name + "'", e);
                return 0;
            }
        }
    }

    // ---- button taps ----

    /** BV button: flips the background-vocals mix. The server
     *  streams a real backing stem (Demucs vocals minus the
     *  karaoke model's predicted lead) and the player mixes it
     *  live, so toggling is instant and mid-song safe. The
     *  button fills white (CC-style) while the mix is on. */
    private static void onBvButtonClicked(View button) {
        // BV only exists inside a karaoke session: never arm it
        // while K is off (it would otherwise silently turn on
        // for the next K session).
        if (!karaokeSticky) {
            backingVocalsEnabled = false;
            KaraokeSession currentOff = session;
            if (currentOff != null) {
                try {
                    currentOff.setBackingVocalsEnabled(false);
                } catch (Exception ignored) {
                }
            }
            if (button instanceof TextView) {
                setButtonActive((TextView) button, false);
            }
            refreshBvButton();
            KaraokeLog.i("BV button blocked: K is off");
            toast("Turn on K first");
            return;
        }
        backingVocalsEnabled = !backingVocalsEnabled;
        if (button instanceof TextView) {
            setButtonActive((TextView) button, backingVocalsEnabled);
        }
        refreshBvButton();
        KaraokeLog.i("BV button tapped: backingVocalsEnabled="
                + backingVocalsEnabled + " (live mix)");
        toast("Background vocals "
                + (backingVocalsEnabled ? "ON" : "OFF"));
        KaraokeSession current = session;
        if (current != null) {
            current.setBackingVocalsEnabled(backingVocalsEnabled);
        }
    }

    /** DEBUG-ONLY (temporary): copies the recent karaoke log to
     *  the clipboard and confirms with a toast, so the user can
     *  paste it straight into chat. No share sheet, no PC, no
     *  adb. REMOVE AFTER DEBUGGING together with makeLogButton.
     *  Never includes keys or full URLs (KaraokeLog.dump
     *  already excludes them). */
    private static void copyLogToClipboard() {
        Context ctx = appContext;
        if (ctx == null) {
            KaraokeLog.e("copyLogToClipboard: no app context yet");
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager)
                    ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                toast("No clipboard available");
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText(
                    "KaraokeNow log", KaraokeLog.dump()));
            KaraokeLog.i("log copied to clipboard");
            toast("Log copied");
        } catch (Exception e) {
            KaraokeLog.e("copyLogToClipboard failed", e);
            toast("Could not copy log: " + shortReason(e));
        }
    }

    /** Shares the recent karaoke log through the system share
     *  sheet (long-press K). No PC, no adb, no root: the user
     *  picks where the text goes (Files, Drive, a message to
     *  himself). Never includes keys or full URLs. */
    private static void shareLog() {
        Context ctx = appContext;
        if (ctx == null) {
            KaraokeLog.e("shareLog: no app context yet");
            return;
        }
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "KaraokeNow log");
            send.putExtra(Intent.EXTRA_TEXT, KaraokeLog.dump());
            send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Intent chooser = Intent.createChooser(send,
                    "Share karaoke log");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(chooser);
            KaraokeLog.i("log shared via chooser");
        } catch (Exception e) {
            KaraokeLog.e("shareLog failed", e);
            toast("Could not share log: " + shortReason(e));
        }
    }

    // ---- v65 BYOK endpoint (no hardcoded server) ----

    private static SharedPreferences prefs() {
        Context ctx = appContext;
        if (ctx == null) return null;
        try {
            return ctx.getSharedPreferences(PREFS_NAME,
                    Context.MODE_PRIVATE);
        } catch (Exception e) {
            KaraokeLog.w("prefs failed", e);
            return null;
        }
    }

    /** Saved user endpoint (normalized wss URL) or null. */
    private static String savedEndpoint() {
        SharedPreferences p = prefs();
        if (p == null) return null;
        String s = p.getString(PREF_ENDPOINT, null);
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static void saveEndpoint(String normalized) {
        SharedPreferences p = prefs();
        if (p == null) return;
        p.edit().putString(PREF_ENDPOINT, normalized).apply();
        KaraokeWarmup.configure(normalized);
        KaraokeLog.i("BYOK endpoint saved: host="
                + KaraokeLog.hostOf(normalized));
    }

    private static String savedSetupJob() {
        SharedPreferences p = prefs();
        if (p == null) return null;
        String s = p.getString(PREF_SETUP_JOB, null);
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static long savedSetupSince() {
        SharedPreferences p = prefs();
        return p == null ? 0 : p.getLong(PREF_SETUP_SINCE, 0);
    }

    private static void saveSetupJob(String jobId, long sinceMs) {
        SharedPreferences p = prefs();
        if (p == null) return;
        p.edit().putString(PREF_SETUP_JOB, jobId)
                .putLong(PREF_SETUP_SINCE, sinceMs).apply();
    }

    private static void clearSetupJob() {
        SharedPreferences p = prefs();
        if (p == null) return;
        p.edit().remove(PREF_SETUP_JOB).remove(PREF_SETUP_SINCE).apply();
    }

    /** Normalizes a pasted endpoint to wss://host/ws. Accepts a
     *  full URL, a bare .modal.run host, or just a Modal workspace
     *  slug (builds the standard revance-karaoke URL). Returns
     *  null for anything else — including raw Modal API tokens
     *  (ak-/as-...), which cannot route to a server by themselves
     *  and are never stored. */
    private static String normalizeEndpoint(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || s.contains(" ")) return null;
        String lower = s.toLowerCase(java.util.Locale.US);
        if (lower.startsWith("ak-") || lower.startsWith("as-")) {
            return null; // Modal token, not a server address
        }
        if (lower.matches("[a-z0-9-]+")) {
            return "wss://" + lower
                    + "--revance-karaoke-api.us-east.modal.run/ws";
        }
        String scheme;
        String rest;
        if (lower.startsWith("wss://")) {
            scheme = "wss";
            rest = s.substring(6);
        } else if (lower.startsWith("ws://")) {
            scheme = "ws";
            rest = s.substring(5);
        } else if (lower.startsWith("https://")) {
            scheme = "wss";
            rest = s.substring(8);
        } else if (lower.startsWith("http://")) {
            scheme = "ws";
            rest = s.substring(7);
        } else if (lower.contains(".modal.run")) {
            scheme = "wss";
            rest = s;
        } else {
            return null;
        }
        int slash = rest.indexOf('/');
        String host = slash >= 0 ? rest.substring(0, slash) : rest;
        String path = slash >= 0 ? rest.substring(slash) : "";
        if (host.isEmpty() || !host.contains(".")) return null;
        if (path.isEmpty() || path.equals("/")) {
            path = "/ws";
        } else if (!path.endsWith("/ws")) {
            return null; // e.g. pasted /health — not the WS base
        }
        return scheme + "://" + host + path;
    }

    /** Setup helper (one-time deploy service; karaoke traffic
     *  never touches it). Open-source in server/setup_app.py. */
    private static final String SETUP_HELPER_URL =
            "https://lukatdamoves--karaoke-setup-api.us-east.modal.run";

    /** Extracts {tokenId, tokenSecret} from a pasted
     *  `modal token set --token-id ak-... --token-secret as-...`
     *  command (the exact string Modal gives users). Also accepts
     *  the two raw tokens in any surrounding text. Profile and
     *  other flags are ignored — the deployed URL comes from the
     *  deploy output, not the profile name. */
    private static String[] parseModalTokenCommand(String text) {
        if (text == null) return null;
        java.util.regex.Matcher idMatcher = java.util.regex.Pattern
                .compile("ak-[A-Za-z0-9_-]+").matcher(text);
        java.util.regex.Matcher secretMatcher = java.util.regex.Pattern
                .compile("as-[A-Za-z0-9_-]+").matcher(text);
        if (!idMatcher.find() || !secretMatcher.find()) return null;
        return new String[] {idMatcher.group(), secretMatcher.group()};
    }

    /** Pauses the video the moment K is tapped, before any
     *  setup dialog/deploy runs (setup can take minutes; the
     *  user should not watch the original keep playing). */
    private static void pauseVideoForSetup(String why) {
        if (setupPausedVideo) return;
        boolean was = false;
        try {
            was = INSTANCE.isPlaying();
        } catch (Exception e) {
            KaraokeLog.w("setup pause: isPlaying read failed", e);
        }
        boolean ok = false;
        try {
            ok = INSTANCE.pause();
        } catch (Exception e) {
            KaraokeLog.e("setup pause: pause() threw", e);
        }
        setupWasPlaying = was;
        setupPausedVideo = ok;
        KaraokeLog.i("setup: video paused at first K tap (" + why
                + ") result=" + ok + " wasPlaying=" + was);
    }

    /** Session start takes over the pause we made for setup. */
    private static void consumeSetupPause() {
        if (setupPausedVideo) {
            KaraokeLog.i("setup: session takes over video pause");
        }
        setupPausedVideo = false;
    }

    /** Setup cancelled/failed before any session: put the
     *  video back how we found it. */
    private static void resumeVideoIfSetupCancelled() {
        if (!setupPausedVideo) return;
        setupPausedVideo = false;
        if (!setupWasPlaying) return;
        try {
            boolean ok = INSTANCE.resume();
            KaraokeLog.i("setup: video resumed after cancel, "
                    + "result=" + ok);
        } catch (Exception e) {
            KaraokeLog.e("setup resume after cancel threw", e);
        }
    }

    /** First-run / edit dialog: paste the `modal token set ...`
     *  command Modal gives you; auto-setup deploys your own
     *  server. (URL paste removed by request — token only.)
     *  Activity context from the player views. onSaved
     *  (nullable) runs after a successful save. */
    private static void showServerSetupDialog(Runnable onSaved) {
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    View anchor = anchorViewForOverlay();
                    Context act = anchor != null
                            ? anchor.getContext() : appContext;
                    if (act == null) {
                        toast("Open a video first, then tap K");
                        return;
                    }
                    float density = act.getResources()
                            .getDisplayMetrics().density;
                    int pad = (int) (16 * density);
                    LinearLayout form = new LinearLayout(act);
                    form.setOrientation(LinearLayout.VERTICAL);
                    form.setPadding(pad, pad / 2, pad, 0);
                    TextView intro = new TextView(act);
                    intro.setText("Create a free Modal account, then "
                            + "paste the whole token command Modal "
                            + "gives you. Your own karaoke server is "
                            + "deployed into YOUR Modal account. The "
                            + "token is used once for that deploy and "
                            + "is never saved on this phone.\n\n"
                            + "Already set up on this account before? "
                            + "Then this is quick — it just reconnects "
                            + "to your existing server (under a "
                            + "minute). Brand-new account? The first "
                            + "build takes 5-10 minutes, once only.");
                    form.addView(intro);
                    EditText tokenInput = new EditText(act);
                    tokenInput.setSingleLine(false);
                    tokenInput.setHint("modal token set "
                            + "--token-id ak-... "
                            + "--token-secret as-...");
                    form.addView(tokenInput);
                    AlertDialog dialog = new AlertDialog.Builder(act)
                            .setTitle("Karaoke setup")
                            .setView(form)
                            .setPositiveButton("Set up", (d, w) -> {
                                String[] pair = parseModalTokenCommand(
                                        tokenInput.getText().toString());
                                if (pair == null) {
                                    if (onSaved != null) {
                                        resumeVideoIfSetupCancelled();
                                    }
                                    toast("Paste the whole "
                                            + "`modal token set ...` "
                                            + "command from Modal");
                                    return;
                                }
                                startAutoSetup(act, pair[0], pair[1],
                                        onSaved);
                            })
                            .setNeutralButton("Get a Modal token", null)
                            .setNegativeButton("Cancel", (d, w) -> {
                                if (onSaved != null) {
                                    resumeVideoIfSetupCancelled();
                                }
                            })
                            .create();
                    dialog.setOnCancelListener(d -> {
                        if (onSaved != null) {
                            resumeVideoIfSetupCancelled();
                        }
                    });
                    dialog.show();
                    // Keep the dialog open while the browser is up.
                    dialog.getButton(
                            android.content.DialogInterface.BUTTON_NEUTRAL)
                            .setOnClickListener(v -> {
                                try {
                                    Intent view = new Intent(
                                            Intent.ACTION_VIEW,
                                            android.net.Uri.parse(
                                                "https://modal.com/settings/tokens"));
                                    view.addFlags(
                                            Intent.FLAG_ACTIVITY_NEW_TASK);
                                    act.startActivity(view);
                                } catch (Exception e) {
                                    toast("Open modal.com, sign up, "
                                            + "then Settings -> API Tokens");
                                }
                            });
                } catch (Exception e) {
                    KaraokeLog.e("server setup dialog failed", e);
                    toast("Could not open server setup");
                }
            });
        } catch (Exception e) {
            KaraokeLog.w("server setup post failed", e);
        }
    }

    /** Auto-setup entry: hand the token to the setup helper once,
     *  save the job, then poll it. Progress is resumable: the job
     *  ID lives in prefs, so tapping K while setup runs reopens
     *  the progress bar (see showSetupProgress). */
    private static void startAutoSetup(Context act, String tokenId,
            String tokenSecret, Runnable onSaved) {
        setupErrorText = null;
        setupProgressPercent = 2;
        setupStageText = "Contacting the setup helper...";
        setupPollerActive = true;
        showSetupProgress(onSaved);
        new Thread(() -> {
            try {
                org.json.JSONObject body = new org.json.JSONObject();
                body.put("token_id", tokenId);
                body.put("token_secret", tokenSecret);
                org.json.JSONObject start = httpJson("POST",
                        SETUP_HELPER_URL + "/setup/start", body);
                if (start == null || !start.has("job_id")) {
                    // Surface the helper's real reason (FastAPI
                    // validation returns {"detail": ...}, our own
                    // errors return {"error": ...}).
                    String err = "no job started";
                    if (start == null) {
                        err = "helper unreachable";
                    } else if (start.has("error")) {
                        err = start.optString("error");
                    } else if (start.has("detail")) {
                        err = start.optString("detail");
                    }
                    throw new Exception(err);
                }
                String jobId = start.getString("job_id");
                saveSetupJob(jobId, System.currentTimeMillis());
                KaraokeLog.i("auto-setup: job started (helper host="
                        + KaraokeLog.hostOf(SETUP_HELPER_URL) + ")");
                pollSetupJob(jobId);
            } catch (Exception e) {
                setupErrorText = shortReason(e);
                setupStageText = "Setup failed";
                setupPollerActive = false;
                clearSetupJob();
                KaraokeLog.e("auto-setup failed", e);
                if (!setupProgressDialogShowing) {
                    toast("Karaoke setup failed — tap K to retry", true);
                }
            }
        }, "karaoke-autosetup").start();
    }

    private static String fmtElapsed(long sec) {
        return (sec / 60) + ":" + String.format("%02d", sec % 60);
    }

    /** Polls one setup job to completion, updating the shared
     *  progress statics the dialog renders. Deploy % comes from
     *  the helper's real stages; warm-up % follows /health. */
    private static void pollSetupJob(String jobId) {
        long since = savedSetupSince();
        if (since <= 0) since = System.currentTimeMillis();
        try {
            String deployedUrl = null;
            while (true) {
                Thread.sleep(4000);
                org.json.JSONObject st = httpJson("GET",
                        SETUP_HELPER_URL + "/setup/status/" + jobId, null);
                if (st == null) {
                    setupStageText = "Waiting for the setup helper...";
                    continue;
                }
                String state = st.optString("state", "running");
                if ("running".equals(state)) {
                    int reported = st.optInt("progress", -1);
                    long elapsedSec =
                            (System.currentTimeMillis() - since) / 1000;
                    int estimate = (int) Math.min(90,
                            5 + 85 * Math.min(1.0, elapsedSec / 600.0));
                    setupProgressPercent = reported >= 0
                            ? Math.min(95, Math.max(reported, estimate))
                            : estimate;
                    String stage = st.optString("stage", "Deploying...");
                    setupStageText = stage
                            + "\n\nAlready have a karaoke server on "
                            + "this Modal account? This just "
                            + "reconnects/updates it — usually under "
                            + "a minute. Brand-new account? The first "
                            + "build (AI models included) takes 5-10 "
                            + "minutes, once only.\n\nElapsed: "
                            + fmtElapsed(elapsedSec)
                            + "\n\nHide keeps setup running — tap K "
                            + "anytime to see this progress again.";
                    continue;
                }
                if ("done".equals(state)) {
                    deployedUrl = st.optString("url", null);
                    break;
                }
                if ("error".equals(state)) {
                    throw new Exception(st.optString("error",
                            "deploy failed"));
                }
                throw new Exception(
                        "setup job lost — tap K to start again");
            }
            if (deployedUrl == null) {
                throw new Exception("no URL returned");
            }
            String normalized = normalizeEndpoint(deployedUrl);
            if (normalized == null) {
                throw new Exception("deployed URL unrecognized");
            }
            saveEndpoint(normalized);
            clearSetupJob();
            KaraokeLog.i("auto-setup: server deployed + saved");
            setupProgressPercent = 96;
            setupStageText = "Server ready. Waking it up — first "
                    + "start after a while loads the AI models "
                    + "(seconds if it is already warm)...";
            String healthUrl = normalized.replace("wss://", "https://")
                    .replace("ws://", "http://");
            if (healthUrl.endsWith("/ws")) {
                healthUrl = healthUrl.substring(
                        0, healthUrl.length() - 3) + "/health";
            }
            for (int i = 0; i < 36; i++) {
                if (httpStatus(healthUrl) == 200) break;
                setupProgressPercent = Math.min(99, 96 + i / 12);
                Thread.sleep(4000);
            }
            setupProgressPercent = 100;
            setupStageText = "Setup complete — your server is ready.";
            setupPollerActive = false;
            if (!setupProgressDialogShowing) {
                toast("Your karaoke server is ready — tap K", true);
            }
        } catch (InterruptedException ie) {
            setupPollerActive = false;
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            setupErrorText = shortReason(e);
            setupStageText = "Setup failed";
            setupPollerActive = false;
            clearSetupJob();
            KaraokeLog.e("auto-setup poll failed", e);
            if (!setupProgressDialogShowing) {
                toast("Karaoke setup failed — tap K to retry", true);
            }
        }
    }

    /** Determinate setup progress bar (deploy % -> warm-up %).
     *  Renders the shared statics; resumes the poller if the
     *  process died mid-setup. onSaved fires on completion when
     *  the dialog is the one watching. */
    private static void showSetupProgress(Runnable onSaved) {
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    if (setupProgressDialogShowing) return;
                    View anchor = anchorViewForOverlay();
                    Context act = anchor != null
                            ? anchor.getContext() : appContext;
                    if (act == null) {
                        toast("Open a video first, then tap K");
                        return;
                    }
                    if (!setupPollerActive && savedEndpoint() == null) {
                        String job = savedSetupJob();
                        if (job != null) {
                            setupPollerActive = true;
                            new Thread(() -> pollSetupJob(job),
                                    "karaoke-autosetup-poll").start();
                        }
                    }
                    float density = act.getResources()
                            .getDisplayMetrics().density;
                    int pad = (int) (20 * density);
                    LinearLayout box = new LinearLayout(act);
                    box.setOrientation(LinearLayout.VERTICAL);
                    box.setPadding(pad, pad, pad, pad);
                    ProgressBar bar = new ProgressBar(act, null,
                            android.R.attr.progressBarStyleHorizontal);
                    bar.setMax(100);
                    box.addView(bar);
                    TextView percent = new TextView(act);
                    box.addView(percent);
                    TextView stage = new TextView(act);
                    box.addView(stage);
                    AlertDialog dialog = new AlertDialog.Builder(act)
                            .setTitle("Setting up your server")
                            .setView(box)
                            .setNegativeButton("Hide", null)
                            .create();
                    dialog.setCancelable(false);
                    final boolean[] showing = {true};
                    final Handler refresher =
                            new Handler(Looper.getMainLooper());
                    final Runnable[] tick = new Runnable[1];
                    tick[0] = () -> {
                        if (!showing[0]) return;
                        if (setupErrorText != null) {
                            percent.setText("Setup failed");
                            stage.setText(setupErrorText
                                    + "\n\nCheck the token (Modal -> "
                                    + "Settings -> API Tokens), Hide, "
                                    + "then tap K to try again.");
                            return;
                        }
                        bar.setProgress(setupProgressPercent);
                        percent.setText(setupProgressPercent + "%");
                        stage.setText(setupStageText);
                        if (savedEndpoint() != null
                                && !setupPollerActive) {
                            showing[0] = false;
                            setupProgressDialogShowing = false;
                            try {
                                dialog.dismiss();
                            } catch (Exception ignored) {
                            }
                            toast("Setup complete — your server "
                                    + "is ready", true);
                            if (onSaved != null) onSaved.run();
                            return;
                        }
                        refresher.postDelayed(tick[0], 500);
                    };
                    dialog.setOnDismissListener(d -> {
                        showing[0] = false;
                        setupProgressDialogShowing = false;
                        if (setupErrorText != null
                                && savedEndpoint() == null) {
                            resumeVideoIfSetupCancelled();
                        }
                    });
                    setupProgressDialogShowing = true;
                    dialog.show();
                    refresher.post(tick[0]);
                } catch (Exception e) {
                    KaraokeLog.e("setup progress dialog failed", e);
                    toast("Could not open setup progress");
                }
            });
        } catch (Exception e) {
            KaraokeLog.w("setup progress post failed", e);
        }
    }

    /** Tiny JSON HTTP helper for the setup helper (never logs
     *  bodies — the request carries a token). */
    private static org.json.JSONObject httpJson(String method, String url,
            org.json.JSONObject body) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection)
                    new java.net.URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = body.toString().getBytes(
                        java.nio.charset.StandardCharsets.UTF_8);
                conn.getOutputStream().write(bytes);
            }
            int code = conn.getResponseCode();
            java.io.InputStream in = code >= 400
                    ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) return null;
            java.io.ByteArrayOutputStream buf =
                    new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
            return new org.json.JSONObject(buf.toString("UTF-8"));
        } catch (Exception e) {
            KaraokeLog.w("setup http failed: " + e.getClass().getSimpleName());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static int httpStatus(String url) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection)
                    new java.net.URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            return conn.getResponseCode();
        } catch (Exception e) {
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void onKaraokeButtonClicked() {
        try {
            long now = System.currentTimeMillis();
            if (now < kCooldownUntilMs) {
                toast("karaoke cooling down, try again");
                return;
            }
            KaraokeSession current = session;
            boolean live = current != null;
            KaraokeSession.State s = null;
            if (live) {
                try {
                    s = current.state();
                } catch (Exception ignored) {
                    live = false;
                }
                live = live && (s == KaraokeSession.State.CONNECTING
                        || s == KaraokeSession.State.BUFFERING
                        || s == KaraokeSession.State.PLAYING
                        || s == KaraokeSession.State.PAUSED);
            }
            if (live) {
                // v54: tap with a live session (loading OR
                // playing) cancels/stops AND unsticks K.
                KaraokeLog.i("karaoke button tapped: stopping session "
                        + "(state=" + s + ")");
                karaokeSticky = false;
                current.stop();
                session = null;
                resetBvToDefault();
                kCooldownUntilMs = now + 3000;
                if (s == KaraokeSession.State.CONNECTING
                        || s == KaraokeSession.State.BUFFERING) {
                    toast("karaoke cancelled");
                }
                refreshKButton();
                return;
            }
            // No live session: stick K on and start (fresh tap
            // or retry after DONE/ERROR). v65: sticky is set only
            // once an endpoint exists (or is saved in the dialog).
            if (controller == null) {
                KaraokeLog.e("karaoke button tapped but no controller "
                        + "has been set yet (player not hooked)");
                toast("Karaoke not ready yet (player not hooked)");
                refreshKButton();
                return;
            }
            String videoId = INSTANCE.getVideoIdOrNull();
            if (videoId == null || videoId.isEmpty()) {
                KaraokeLog.e("karaoke button tapped but video ID is "
                        + "null/empty; sticky stays, starts when readable");
                toast("Karaoke could not read the video");
                refreshKButton();
                return;
            }
            KaraokeLog.i("karaoke button tapped: starting session "
                    + "videoId=" + videoId);
            pauseVideoForSetup("k-tap");
            if (savedEndpoint() == null && savedSetupJob() != null) {
                // v67: setup already running — reopen the actual
                // progress bar instead of the setup form.
                KaraokeLog.i("BYOK: setup in progress — progress bar");
                final String targetVideo = videoId;
                showSetupProgress(() -> {
                    consumeSetupPause();
                    karaokeSticky = true;
                    startSessionForVideo(targetVideo,
                            "k-tap-setup-resume");
                    refreshKButton();
                });
                refreshKButton();
                return;
            }
            if (savedEndpoint() == null) {
                // First run (no hardcoded server): ask for the
                // user's own Modal server, then start.
                KaraokeLog.i("BYOK: no endpoint saved — setup dialog");
                final String targetVideo = videoId;
                showServerSetupDialog(() -> {
                    consumeSetupPause();
                    karaokeSticky = true;
                    startSessionForVideo(targetVideo, "k-tap-setup");
                    refreshKButton();
                });
                refreshKButton();
                return;
            }
            consumeSetupPause();
            karaokeSticky = true;
            startSessionForVideo(videoId, "k-tap");
            refreshKButton();
        } catch (Exception e) {
            KaraokeLog.e("karaoke button tap failed", e);
            toast("Karaoke failed to start: " + shortReason(e));
            refreshKButton();
        }
    }

    /** Starts a session for a video (K tap + sticky restarts). */
    /** v58: BV always returns to its default (OFF) when no live
     *  audio can use it — K-off, errors, song end, and every new
     *  session start. (Q16: default off, never sticky.) */
    private static void resetBvToDefault() {
        if (!backingVocalsEnabled) {
            refreshBvButton();
            return;
        }
        backingVocalsEnabled = false;
        KaraokeSession current = session;
        if (current != null) {
            try {
                current.setBackingVocalsEnabled(false);
            } catch (Exception ignored) {
            }
        }
        refreshBvButton();
        KaraokeLog.i("BV reset to default OFF");
    }

    private static void startSessionForVideo(String videoId, String why) {
        resetBvToDefault();
        // v6 diagnostics: geometry at session start (same
        // numbers as the layout blocks unless something moved).
        logGeometry("session start");
        // v65: the user's saved endpoint only — no fallback,
        // no hardcoded server anywhere in the start path.
        String endpoint = savedEndpoint();
        if (endpoint == null) {
            KaraokeLog.w("session start blocked: no BYOK endpoint");
            showServerSetupDialog(null);
            return;
        }
        KaraokeSettings settings = new KaraokeSettings(endpoint, null,
                0, backingVocalsEnabled);
        KaraokeSession next = new KaraokeSession(
                settings, INSTANCE, INSTANCE, INSTANCE);
        // Carry the key state into the new session: the shifter is
        // per-session, so it must be re-armed with the current value.
        next.setKeySemitones(keySemitones);
        if (userVoiceProfile != null) next.setUserProfile(userVoiceProfile);
        session = next;
        KaraokeLog.i("sticky K: starting karaoke on " + videoId
                + " (" + why + ")");
        next.start(videoId);
        refreshKButton();
    }

    /** v54 sticky restart on a new video: pre-mute the NEW player
     *  immediately (the gate mutes anyway, but this closes the
     *  original-audio window while the new song loads), then
     *  start. Bypasses the tap cooldown (not a tap). */
    private static void restartForVideo(String newVideoId, String why) {
        if (newVideoId == null || newVideoId.isEmpty()) return;
        try {
            INSTANCE.setMuted(true);
        } catch (Exception e) {
            KaraokeLog.w("sticky pre-mute threw", e);
        }
        startSessionForVideo(newVideoId, why);
    }

    /** Short, single-line reason for a toast. Never null, never
     *  multi-line, capped so the toast stays readable. */
    private static String shortReason(Throwable e) {
        if (e == null) return "unknown error";
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
        m = m.replace('\n', ' ').replace('\r', ' ');
        return m.length() > 150 ? m.substring(0, 150) + "…" : m;
    }

    private static String shortReason(String message) {
        if (message == null || message.isEmpty()) return "unknown error";
        String m = message.replace('\n', ' ').replace('\r', ' ');
        return m.length() > 150 ? m.substring(0, 150) + "…" : m;
    }

    private static void toast(String message) {
        toast(message, false);
    }

    private static void toast(String message, boolean longToast) {
        Context ctx = appContext;
        if (ctx == null) return;
        try {
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(ctx, message,
                            longToast ? Toast.LENGTH_LONG
                                    : Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            KaraokeLog.w("toast failed", e);
        }
    }

    // ---- loading overlay (v13 prepare veil) ----

    /** Anchor view for overlay lookup: the K button if alive,
     *  else the bottom UI. Both are attached views while the
     *  player UI exists, so their weak refs stay valid; null
     *  only if the player UI is gone (then no overlay is
     *  possible and the session continues without it). */
    private static View anchorViewForOverlay() {
        WeakReference<TextView> kRef = kButtonRef;
        TextView k = kRef != null ? kRef.get() : null;
        if (k != null) return k;
        WeakReference<View> uiRef = bottomUiRef;
        return uiRef != null ? uiRef.get() : null;
    }

    /** First LAID-OUT video surface under root, found with the
     *  same traversal as KaraokeGeometry (stack DFS, children
     *  pushed reversed). Laid-out only: a 0-size surface means
     *  the video is not on screen yet, and an overlay in its
     *  parent would be invisible. Null if none found. */
    private static View findVideoSurface(View root) {
        try {
            ArrayDeque<View> stack = new ArrayDeque<>();
            stack.push(root);
            int visited = 0;
            while (!stack.isEmpty()) {
                View v = stack.pop();
                visited++;
                if (visited > 5000) return null;
                if (v instanceof SurfaceView || v instanceof TextureView) {
                    if (v.getWidth() > 0 && v.getHeight() > 0) {
                        return v;
                    }
                }
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = g.getChildCount() - 1; i >= 0; i--) {
                        View c = g.getChildAt(i);
                        if (c != null) stack.push(c);
                    }
                }
            }
        } catch (Exception e) {
            KaraokeLog.w("findVideoSurface failed", e);
        }
        return null;
    }

    /** Finds YouTube's actual player loading spinner by its
     *  stable resource NAME (never a hard-coded numeric ID).
     *  The watch player uses player_loading_view_thin; the
     *  non-thin player_loading_view is accepted for other
     *  player surfaces. Returns null if the player overlay has
     *  not been inflated in this hierarchy yet. */
    private static ProgressBar findYoutubeLoadingSpinner(
            View root, Context ctx) {
        if (root == null || ctx == null) return null;
        ResourcesLookup res = new ResourcesLookup(ctx);
        String[] names = {
                "player_loading_view_thin",
                "player_loading_view",
        };
        for (String name : names) {
            int id = res.id(name);
            if (id == 0) continue;
            View found = root.findViewById(id);
            if (found instanceof ProgressBar) {
                KaraokeLog.i("youtube loading spinner: @id/" + name
                        + " visibility=" + found.getVisibility()
                        + " size=" + found.getWidth() + "x"
                        + found.getHeight());
                return (ProgressBar) found;
            }
        }
        KaraokeLog.w("youtube loading spinner not found in hierarchy");
        return null;
    }

    /** Shows YouTube's actual loading spinner and then installs
     *  a transparent tap-blocking veil above the player. The
     *  veil has no substitute ProgressBar: the spinner the user
     *  sees is YouTube's own @id/player_loading_view_thin. */
    private static boolean showYoutubeLoadingSpinner(View root, Context ctx) {
        ProgressBar spinner = findYoutubeLoadingSpinner(root, ctx);
        if (spinner == null) return false;
        if (youtubeLoadingSpinnerRef == null
                || youtubeLoadingSpinnerRef.get() != spinner) {
            youtubeLoadingSpinnerVisibility = spinner.getVisibility();
        }
        youtubeLoadingSpinnerRef = new WeakReference<>(spinner);
        spinner.setIndeterminate(true);
        spinner.setVisibility(View.VISIBLE);
        spinner.bringToFront();
        spinner.requestLayout();
        spinner.invalidate();
        return true;
    }

    /** Restores the visibility YouTube's loading spinner had
     *  before karaoke borrowed it. */
    private static void restoreYoutubeLoadingSpinner() {
        ProgressBar spinner = youtubeLoadingSpinnerRef != null
                ? youtubeLoadingSpinnerRef.get() : null;
        int original = youtubeLoadingSpinnerVisibility;
        youtubeLoadingSpinnerRef = null;
        youtubeLoadingSpinnerVisibility = Integer.MIN_VALUE;
        if (spinner != null && original != Integer.MIN_VALUE) {
            spinner.setVisibility(original);
            spinner.invalidate();
        }
    }

    /** Collects EVERY attached view with the given resource
     *  name. YouTube ships several control-layout variants and
     *  can have duplicate IDs across inflated stubs, so the
     *  first findViewById() match is not sufficient. */
    /** v58: EVERY play/pause control ID mined from YouTube
     *  20.40.45's resources.arsc. Tapping the video reveals
     *  controls BEYOND the center replay button (bottom-bar/tap
     *  variant) which v51 never hid — the persistent button
     *  James saw. Unknown/absent IDs resolve to 0 and are
     *  skipped, so this is safe across layouts. */
    private static final String[] PLAY_PAUSE_ID_NAMES = {
        "player_control_play_pause_replay_button_touch_area",
        "player_control_play_pause_replay_button",
        "player_control_play_pause_button",
        "floaty_play_pause_button",
        "time_bar_play_pause_replay_button",
        "immersive_live_play_pause_button",
    };
    /** Resolved IDs, cached per process (stable for the app's
     *  lifetime; 0 = absent on this layout). */
    private static final Map<String, Integer> playPauseIdCache =
            new HashMap<>();
    private static volatile long lastEnforceDiagMs;

    private static int[] playPauseIds(Context ctx) {
        int[] ids = new int[PLAY_PAUSE_ID_NAMES.length];
        ResourcesLookup lookup = new ResourcesLookup(ctx);
        synchronized (playPauseIdCache) {
            for (int i = 0; i < PLAY_PAUSE_ID_NAMES.length; i++) {
                Integer cached =
                        playPauseIdCache.get(PLAY_PAUSE_ID_NAMES[i]);
                if (cached == null) {
                    cached = lookup.id(PLAY_PAUSE_ID_NAMES[i]);
                    playPauseIdCache.put(PLAY_PAUSE_ID_NAMES[i], cached);
                }
                ids[i] = cached;
            }
        }
        return ids;
    }

    /** Single-pass DFS: hides every view matching ANY play/pause
     *  ID. Returns the match count. One traversal for all IDs
     *  keeps the 50ms guard + layout listener cheap on main. */
    private static int hideMatchingPlayPauseControls(
            View root, Context ctx) {
        if (root == null || ctx == null) return 0;
        int[] ids = playPauseIds(ctx);
        boolean any = false;
        for (int id : ids) {
            if (id != 0) {
                any = true;
                break;
            }
        }
        if (!any) return 0;
        int matched = 0;
        ArrayDeque<View> stack = new ArrayDeque<>();
        stack.push(root);
        int budget = 6000;
        while (!stack.isEmpty() && budget-- > 0) {
            View view = stack.pop();
            int vid = view.getId();
            if (vid != View.NO_ID) {
                for (int id : ids) {
                    if (id != 0 && vid == id) {
                        detachYoutubePlayPauseView(view);
                        matched++;
                        break;
                    }
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = group.getChildCount() - 1; i >= 0; i--) {
                    View child = group.getChildAt(i);
                    if (child != null) stack.push(child);
                }
            }
        }
        return matched;
    }

    private static boolean isRecordedRemovedView(View view) {
        for (RemovedPlayerView record : removedYoutubePlayPauseViews) {
            if (record.view.get() == view) return true;
        }
        return false;
    }

    /** Makes one matching control fully transparent WITHOUT
     *  touching visibility or layout. v51: visibility stays
     *  exactly as YouTube wants it (no INVISIBLE/GONE fight),
     *  so Previous/Next never reflow and YouTube's own
     *  visibility flips can't flash the button — alpha 0
     *  persists across them. Input is disabled; the loading
     *  veil swallows taps. */
    private static boolean detachYoutubePlayPauseView(View view) {
        if (view == null) return false;
        boolean newlyRecorded = false;
        if (!isRecordedRemovedView(view)
                && view.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view.getParent();
            removedYoutubePlayPauseViews.add(
                    new RemovedPlayerView(view, parent));
            newlyRecorded = true;
        }
        try {
            if (view.getAlpha() != 0f) view.setAlpha(0f);
        } catch (Exception ignored) {
        }
        view.setEnabled(false);
        view.setClickable(false);
        view.setFocusable(false);
        return newlyRecorded;
    }

    /** v64: places the loading label BETWEEN YouTube's time
     *  (left) and the K row (right), centered in that free slot
     *  and vertically on the K row — never over either. Bounds
     *  come from the actual views; if they are unavailable or
     *  the slot is too narrow, the below-spinner placement from
     *  show time stays. Main-thread only. */
    private static void positionLoadingLabelInSlot(View vc) {
        try {
            WeakReference<LinearLayout> bref = loadingBoxRef;
            LinearLayout box = bref != null ? bref.get() : null;
            if (box == null) return;
            if (!(box.getLayoutParams()
                    instanceof FrameLayout.LayoutParams)) return;
            float density = box.getResources().getDisplayMetrics().density;
            int gap = (int) (8 * density);
            int[] vxy = new int[2];
            vc.getLocationInWindow(vxy);
            // Right bound: the K button itself if we have it,
            // else its row container.
            View rightView = null;
            WeakReference<TextView> kref = kButtonRef;
            TextView k = kref != null ? kref.get() : null;
            if (k != null && k.getWidth() > 0) {
                rightView = k;
            } else {
                WeakReference<View> eref = veilEndRef;
                View end = eref != null ? eref.get() : null;
                if (end != null && end.getWidth() > 0) rightView = end;
            }
            if (rightView == null) return;
            int[] rxy = new int[2];
            rightView.getLocationInWindow(rxy);
            int slotRight = rxy[0] - gap;
            int centerY = rxy[1] + rightView.getHeight() / 2;
            // Left bound: YouTube's timestamps when measured;
            // else reserve a typical time width so we never
            // sit on the (currently hidden) time text.
            int slotLeft;
            WeakReference<View> tref = veilTimeRef;
            View time = tref != null ? tref.get() : null;
            if (time != null && time.getWidth() > 0) {
                int[] txy = new int[2];
                time.getLocationInWindow(txy);
                slotLeft = txy[0] + time.getWidth() + gap;
            } else {
                slotLeft = vxy[0] + (int) (148 * density);
            }
            int slotWidth = slotRight - slotLeft;
            if (slotWidth < (int) (110 * density)) return;
            FrameLayout.LayoutParams blp =
                    (FrameLayout.LayoutParams) box.getLayoutParams();
            int boxH = box.getHeight();
            if (boxH <= 0) boxH = (int) (42 * density);
            int newLeft = slotLeft - vxy[0];
            int newTop = centerY - vxy[1] - boxH / 2;
            // v69: 3px tolerance — sub-pixel layout jitter was
            // re-setting params (and redrawing) every tick.
            if (Math.abs(blp.width - slotWidth) > 3
                    || Math.abs(blp.leftMargin - newLeft) > 3
                    || Math.abs(blp.topMargin - newTop) > 3
                    || blp.gravity != (Gravity.TOP | Gravity.LEFT)) {
                blp.width = slotWidth;
                blp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                blp.gravity = Gravity.TOP | Gravity.LEFT;
                blp.leftMargin = newLeft;
                blp.topMargin = newTop;
                box.setLayoutParams(blp);
            }
            long now = System.currentTimeMillis();
            if (now - lastSlotDiagMs > 3000) {
                lastSlotDiagMs = now;
                KaraokeLog.d("veil label slot: left=" + newLeft
                        + " width=" + slotWidth + " top=" + newTop);
            }
        } catch (Exception ignored) {
        }
    }

    /** v62: keeps the decor-hosted veil glued to the video
     *  bounds (rotation/scroll-safe). Change-gated to avoid
     *  layout storms. Main-thread only (called from enforce). */
    private static void repositionLoadingOverlay() {
        try {
            View veil = loadingOverlayRef != null
                    ? loadingOverlayRef.get() : null;
            WeakReference<ViewGroup> cref = veilVideoContainerRef;
            ViewGroup vc = cref != null ? cref.get() : null;
            if (veil == null || vc == null) return;
            int[] xy = new int[2];
            vc.getLocationInWindow(xy);
            int w = vc.getWidth();
            int h = vc.getHeight();
            if (w <= 0 || h <= 0) return;
            ViewGroup.LayoutParams lp = veil.getLayoutParams();
            if (!(lp instanceof FrameLayout.LayoutParams)) return;
            FrameLayout.LayoutParams flp =
                    (FrameLayout.LayoutParams) lp;
            if (flp.width != w || flp.height != h
                    || flp.leftMargin != xy[0]
                    || flp.topMargin != xy[1]) {
                flp.width = w;
                flp.height = h;
                flp.leftMargin = xy[0];
                flp.topMargin = xy[1];
                flp.gravity = Gravity.TOP | Gravity.LEFT;
                veil.setLayoutParams(flp);
            }
            positionLoadingLabelInSlot(vc);
        } catch (Exception ignored) {
        }
    }

    private static void enforceYoutubePlayPauseRemoval() {
        View root = youtubeLoadingRootRef != null
                ? youtubeLoadingRootRef.get() : null;
        if (root == null) return;
        Context ctx = root.getContext();
        if (ctx == null) return;

        // v51: a tap during load makes YouTube inflate/show
        // fresh controls ABOVE our veil (the flash). Keep the
        // veil + YouTube's own spinner topmost every tick so
        // taps keep hitting the veil ("Preparing karaoke…")
        // instead of revealing buttons above it.
        try {
            View veil = loadingOverlayRef != null
                    ? loadingOverlayRef.get() : null;
            ProgressBar spinner = youtubeLoadingSpinnerRef != null
                    ? youtubeLoadingSpinnerRef.get() : null;
            if (spinner != null) {
                if (spinner.getVisibility() != View.VISIBLE) {
                    spinner.setVisibility(View.VISIBLE);
                }
                bringToFrontIfNeeded(spinner);
            }
            // v69: bring-to-front ONLY when not already front.
            // The old per-50ms unconditional calls churned the
            // draw order every tick — the rapid label blink.
            bringToFrontIfNeeded(veil);
            bringToFrontIfNeeded(spinner);
        } catch (Exception ignored) {
        }
        repositionLoadingOverlay();

        int before = removedYoutubePlayPauseViews.size();
        int matched = hideMatchingPlayPauseControls(root, ctx);
        int after = removedYoutubePlayPauseViews.size();
        if (after > before) {
            KaraokeLog.i("youtube play/pause controls transparent "
                    + "while loading (matched=" + matched
                    + " tracked=" + after + ")");
        }
        // v58 diagnostic: throttled proof of what each enforce
        // sees (catches tap-inflated variants in the next log).
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastEnforceDiagMs > 3000) {
            lastEnforceDiagMs = nowMs;
            KaraokeLog.d("play/pause enforce: matched=" + matched
                    + " tracked=" + after);
        }
    }

    /** Moves a view to its parent's front only if needed. */
    private static void bringToFrontIfNeeded(View view) {
        if (view == null) return;
        try {
            ViewParent parent = view.getParent();
            if (parent instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) parent;
                if (group.indexOfChild(view)
                        != group.getChildCount() - 1) {
                    view.bringToFront();
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** v58: immediate re-hide on ANY hierarchy change (a tap
     *  can inflate fresh controls between 50ms guard ticks).
     *  Throttled to 150ms; detached with the veil. Runs on
     *  main (layout callbacks are main-thread only). */
    private static volatile WeakReference<View> layoutObservedRoot;
    private static volatile ViewTreeObserver.OnGlobalLayoutListener
            layoutHideListener;
    private static volatile long lastLayoutEnforceMs;

    private static void attachLayoutHideListener(View root) {
        try {
            detachLayoutHideListener();
            if (root == null) return;
            final ViewTreeObserver obs = root.getViewTreeObserver();
            if (obs == null || !obs.isAlive()) return;
            ViewTreeObserver.OnGlobalLayoutListener l =
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    if (!youtubePlayPauseRemovalActive) return;
                    long now = System.currentTimeMillis();
                    if (now - lastLayoutEnforceMs < 150) return;
                    lastLayoutEnforceMs = now;
                    try {
                        enforceYoutubePlayPauseRemoval();
                    } catch (Exception e) {
                        KaraokeLog.w("layout enforce failed", e);
                    }
                }
            };
            layoutHideListener = l;
            layoutObservedRoot = new WeakReference<>(root);
            obs.addOnGlobalLayoutListener(l);
        } catch (Exception e) {
            KaraokeLog.w("layout hide listener attach failed", e);
        }
    }

    private static void detachLayoutHideListener() {
        try {
            WeakReference<View> ref = layoutObservedRoot;
            View root = ref != null ? ref.get() : null;
            ViewTreeObserver.OnGlobalLayoutListener l =
                    layoutHideListener;
            layoutHideListener = null;
            layoutObservedRoot = null;
            if (root != null && l != null) {
                ViewTreeObserver obs = root.getViewTreeObserver();
                if (obs != null && obs.isAlive()) {
                    obs.removeOnGlobalLayoutListener(l);
                }
            }
        } catch (Exception e) {
            KaraokeLog.w("layout hide listener detach failed", e);
        }
    }

    /** Starts transparency hiding + a 50 ms enforcement guard
     *  for the whole CONNECTING/BUFFERING window. */
    private static void hideYoutubePlayPauseButton(View root, Context ctx) {
        if (root == null || ctx == null) return;
        youtubeLoadingRootRef = new WeakReference<>(root);
        youtubePlayPauseRemovalActive = true;
        enforceYoutubePlayPauseRemoval();
        attachLayoutHideListener(root);
        if (youtubePlayPauseGuardHandler == null) {
            youtubePlayPauseGuardHandler =
                    new Handler(Looper.getMainLooper());
        }
        youtubePlayPauseGuardHandler.removeCallbacks(youtubePlayPauseGuard);
        youtubePlayPauseGuardHandler.postDelayed(youtubePlayPauseGuard, 50);
        if (removedYoutubePlayPauseViews.isEmpty()) {
            KaraokeLog.w("youtube play/pause button not found while loading");
        }
    }

    /** Restores every hidden control's prior state. Views
     *  were never detached or re-laid-out in v50/v51, so no
     *  reinsertion is needed (the recorded-parent branch
     *  remains only as a safety net for a view YouTube
     *  itself detached meanwhile). */
    private static void restoreYoutubePlayPauseButton() {
        youtubePlayPauseRemovalActive = false;
        detachLayoutHideListener();
        if (youtubePlayPauseGuardHandler != null) {
            youtubePlayPauseGuardHandler.removeCallbacks(
                    youtubePlayPauseGuard);
        }
        for (int i = removedYoutubePlayPauseViews.size() - 1; i >= 0; i--) {
            RemovedPlayerView record = removedYoutubePlayPauseViews.get(i);
            View view = record.view.get();
            ViewGroup parent = record.parent.get();
            if (view == null) continue;
            if (view.getParent() == null && parent != null) {
                int index = Math.max(0,
                        Math.min(record.index, parent.getChildCount()));
                try {
                    if (record.layoutParams != null) {
                        parent.addView(view, index, record.layoutParams);
                    } else {
                        parent.addView(view, index);
                    }
                } catch (Exception e) {
                    KaraokeLog.w("play/pause restore add failed", e);
                }
            }
            try {
                view.setAlpha(record.alpha);
            } catch (Exception ignored) {
            }
            view.setVisibility(record.visibility);
            view.setEnabled(record.enabled);
            view.setClickable(record.clickable);
            view.setFocusable(record.focusable);
        }
        removedYoutubePlayPauseViews.clear();
        youtubeLoadingRootRef = null;
    }

    /** Shows a SMALL "Preparing karaoke…" label inside the VIDEO
     *  area only, just below YouTube's own spinner. v61: no dim,
     *  no tap-eating, no fullscreen math — a transparent wrapper
     *  pinned to the video surface's parent (which spans exactly
     *  the video), with a centered label dropped below the
     *  spinner by half its measured height + gap. Taps pass
     *  through (the alpha-0 play/pause hide already neuters the
     *  buttons); progress text updates via loadingLabelRef.
     *  Posted to main; deduped by tag; never throws. */
    private static void showLoadingOverlay() {
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    View anchor = anchorViewForOverlay();
                    if (anchor == null) {
                        KaraokeLog.w("loading overlay: no anchor "
                                + "view, skipping");
                        return;
                    }
                    View root = anchor.getRootView();
                    if (root == null) {
                        KaraokeLog.w("loading overlay: no root "
                                + "view, skipping");
                        return;
                    }
                    boolean youtubeSpinnerShown =
                            showYoutubeLoadingSpinner(root, anchor.getContext());
                    hideYoutubePlayPauseButton(root, anchor.getContext());
                    if (root.findViewWithTag("karaoke-loading-overlay")
                            != null) {
                        return; // veil already shown; spinner reasserted above
                    }
                    View surface = findVideoSurface(root);
                    if (surface == null
                            || !(surface.getParent()
                                    instanceof ViewGroup)) {
                        KaraokeLog.w("loading overlay: no laid-out "
                                + "video surface found, skipping");
                        return;
                    }
                    // v62: decor hosts (generic FrameLayout =
                    // renders: v61 proved the custom video container
                    // ajjl swallows unknown children — everything
                    // fired, nothing showed), sized EXACTLY to the
                    // video bounds (video-only, never fullscreen).
                    // Transparent, tap-through, small centered label
                    // below YouTube's spinner. Repositioned every
                    // guard tick (rotation/scroll-safe).
                    ViewGroup container =
                            (ViewGroup) surface.getParent();
                    ViewGroup decorHost = null;
                    try {
                        if (root instanceof ViewGroup) {
                            decorHost = (ViewGroup) root;
                        }
                    } catch (Exception ignored) {
                    }
                    if (decorHost == null) {
                        KaraokeLog.w("loading overlay: no decor host, "
                                + "skipping");
                        return;
                    }
                    KaraokeLog.d("veil host: decor for video "
                            + container.getClass().getName()
                            + " " + container.getWidth()
                            + "x" + container.getHeight());
                    Context ctx = decorHost.getContext();
                    FrameLayout overlay = new FrameLayout(ctx);
                    overlay.setTag("karaoke-loading-overlay");
                    overlay.setClickable(false);
                    overlay.setFocusable(false);
                    LinearLayout box = new LinearLayout(ctx);
                    box.setOrientation(LinearLayout.VERTICAL);
                    box.setGravity(Gravity.CENTER);
                    box.setClickable(false);
                    box.setFocusable(false);
                    // The visible loading animation is YouTube's
                    // actual player_loading_view_thin, already
                    // shown above. Only add a fallback ring if a
                    // future/other player hierarchy lacks it.
                    if (!youtubeSpinnerShown) {
                        ProgressBar fallback = new ProgressBar(ctx, null,
                                android.R.attr.progressBarStyleLarge);
                        fallback.setIndeterminate(true);
                        LinearLayout.LayoutParams sp =
                                new LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT);
                        sp.gravity = Gravity.CENTER_HORIZONTAL;
                        box.addView(fallback, sp);
                    }
                    KaraokeLog.i("loading overlay shown "
                            + "(youtubeSpinner=" + youtubeSpinnerShown + ")");
                    TextView label = new TextView(ctx);
                    label.setText("Preparing karaoke\u2026");
                    // v54: progress + ETA live here (see
                    // setLoadingLabel: load, rebuffer, reconnect).
                    loadingLabelRef = new WeakReference<>(label);
                    label.setTextColor(Color.WHITE);
                    // v63: 20% smaller (was 16sp) and shifted left of
                    // center (off the K/bottom bar zone).
                    label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                    label.setGravity(Gravity.CENTER);
                    try {
                        label.setShadowLayer(6f, 0f, 0f, Color.BLACK);
                        label.setSingleLine(false);
                        label.setClickable(false);
                        label.setFocusable(false);
                    } catch (Exception ignored) {
                    }
                    LinearLayout.LayoutParams llp =
                            new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT);
                    llp.gravity = Gravity.CENTER_HORIZONTAL;
                    box.addView(label, llp);
                    // Drop the box just below YouTube's spinner:
                    // half its measured height + a 12dp gap. The
                    // spinner is already laid out (176x176 in the
                    // 20.40.45 logs); the dp estimate covers the
                    // unmeasured case. No-spinner fallback: dead
                    // center (ring sits mid-video, label under it).
                    float density = ctx.getResources()
                            .getDisplayMetrics().density;
                    int dropPx = 0;
                    if (youtubeSpinnerShown) {
                        int spinnerH = 0;
                        try {
                            ProgressBar spin =
                                    youtubeLoadingSpinnerRef != null
                                            ? youtubeLoadingSpinnerRef.get()
                                            : null;
                            if (spin != null) {
                                spinnerH = spin.getHeight();
                            }
                        } catch (Exception ignored) {
                        }
                        if (spinnerH <= 0) {
                            spinnerH = (int) (72 * density);
                        }
                        dropPx = spinnerH / 2 + (int) (12 * density);
                    }
                    // v63: left-shifted placement (was dead center,
                    // colliding with the K/fullscreen column): the
                    // label sits left-of-center with a small left
                    // inset. Same drop below YouTube's spinner.
                    FrameLayout.LayoutParams blp =
                            new FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    Gravity.CENTER_VERTICAL | Gravity.LEFT);
                    blp.topMargin = dropPx;
                    blp.leftMargin = (int) (12 * density);
                    overlay.addView(box, blp);
                    int[] vxy = new int[2];
                    container.getLocationInWindow(vxy);
                    int vw = container.getWidth();
                    int vh = container.getHeight();
                    FrameLayout.LayoutParams wlp =
                            new FrameLayout.LayoutParams(vw, vh,
                                    Gravity.TOP | Gravity.LEFT);
                    wlp.leftMargin = vxy[0];
                    wlp.topMargin = vxy[1];
                    decorHost.addView(overlay, wlp);
                    veilVideoContainerRef =
                            new WeakReference<>(container);
                    KaraokeLog.d("veil label placed: dropPx=" + dropPx
                            + " video=" + vxy[0] + "," + vxy[1]
                            + " " + vw + "x" + vh);
                    loadingBoxRef = new WeakReference<>(box);
                    // v64: bound the label slot by YouTube's own
                    // views: time (left) and the K row (right).
                    try {
                        ResourcesLookup lr =
                                new ResourcesLookup(ctx);
                        int timeId = lr.id("timestamps_container");
                        View timeView = timeId != 0
                                ? root.findViewById(timeId) : null;
                        if (timeView != null) {
                            veilTimeRef = new WeakReference<>(timeView);
                        }
                        int endId = lr.id("bottom_end_container");
                        View endView = endId != 0
                                ? root.findViewById(endId) : null;
                        if (endView != null) {
                            veilEndRef = new WeakReference<>(endView);
                        }
                    } catch (Exception ignored) {
                    }
                    loadingOverlayRef = new WeakReference<>(overlay);
                    repositionLoadingOverlay();
                    KaraokeLog.i("loading overlay shown");
                } catch (Exception e) {
                    KaraokeLog.w("loading overlay show failed", e);
                }
            });
        } catch (Exception e) {
            KaraokeLog.w("loading overlay show post failed", e);
        }
    }

    /** v45: first ProgressBar that belongs to YouTube itself
     *  (not our overlay) in the hierarchy, depth-capped. This is
     *  the player's own loading spinner; we clone its drawable
     *  for the veil so the loading visual is literally YouTube's.
     *  Logs the resource name once for future pinning. */
    private static boolean ytSpinnerLogged;

    private static android.graphics.drawable.Drawable
            findYoutubeSpinnerDrawable(View root) {
        try {
            java.util.ArrayDeque<View> stack = new java.util.ArrayDeque<>();
            stack.push(root);
            int budget = 6000;
            while (!stack.isEmpty() && budget-- > 0) {
                View v = stack.pop();
                if (v instanceof ProgressBar
                        && !"karaoke-loading-overlay".equals(
                                String.valueOf(v.getTag()))) {
                    ViewParent vp = v.getParent();
                    boolean ours = false;
                    while (vp instanceof View) {
                        if ("karaoke-loading-overlay".equals(
                                String.valueOf(((View) vp).getTag()))) {
                            ours = true;
                            break;
                        }
                        vp = vp.getParent();
                    }
                    if (!ours) {
                        if (!ytSpinnerLogged) {
                            ytSpinnerLogged = true;
                            String res = "?";
                            try {
                                if (v.getId() != View.NO_ID) {
                                    res = v.getResources()
                                            .getResourceName(v.getId());
                                }
                            } catch (Exception ignored) {}
                            KaraokeLog.i("youtube spinner found: "
                                    + res + " vis="
                                    + v.getVisibility());
                        }
                        return ((ProgressBar) v)
                                .getIndeterminateDrawable();
                    }
                }
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        stack.push(g.getChildAt(i));
                    }
                }
            }
        } catch (Exception e) {
            KaraokeLog.w("youtube spinner hunt failed", e);
        }
        return null;
    }

    /** Hides the loading overlay on every session exit path
     *  (PLAYING via onState, ERROR via onState+onError, IDLE
     *  via onState on stop). Posted to main; a missing overlay
     *  is a no-op, never an error. */
    private static void hideLoadingOverlay() {
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    restoreYoutubePlayPauseButton();
                    restoreYoutubeLoadingSpinner();
                    View overlay = loadingOverlayRef != null
                            ? loadingOverlayRef.get() : null;
                    if (overlay == null) {
                        View anchor = anchorViewForOverlay();
                        if (anchor != null
                                && anchor.getRootView() != null) {
                            overlay = anchor.getRootView()
                                    .findViewWithTag(
                                            "karaoke-loading-overlay");
                        }
                    }
                    if (overlay != null
                            && overlay.getParent()
                                    instanceof ViewGroup) {
                        ((ViewGroup) overlay.getParent())
                                .removeView(overlay);
                        KaraokeLog.i("loading overlay hidden");
                    }
                    loadingOverlayRef = null;
                    loadingLabelRef = null;
                    veilVideoContainerRef = null;
                    lastLoadingLabelText = null;
                    loadingBoxRef = null;
                    veilTimeRef = null;
                    veilEndRef = null;
                } catch (Exception e) {
                    KaraokeLog.w("loading overlay hide failed", e);
                }
            });
        } catch (Exception e) {
            KaraokeLog.w("loading overlay hide post failed", e);
        }
    }

    // ---- reflection into Laiwx synthetics ----

    private static Method synthetic(String name, Class<?>... params) {
        synchronized (METHOD_CACHE) {
            Method m = METHOD_CACHE.get(name);
            if (m != null) return m;
            Object c = controller;
            if (c == null) {
                throw new IllegalStateException(
                        "KaraokeBridge: no controller set for " + name);
            }
            try {
                m = c.getClass().getDeclaredMethod(name, params);
                m.setAccessible(true);
                METHOD_CACHE.put(name, m);
                return m;
            } catch (Exception e) {
                throw new IllegalStateException(
                        "KaraokeBridge: synthetic " + name + " missing", e);
            }
        }
    }

    private static Object call(String name, Class<?>[] params, Object[] args) {
        Object c = controller;
        if (c == null) {
            throw new IllegalStateException(
                    "KaraokeBridge: no controller set for " + name);
        }
        // Fix for IllegalStateException: Player is accessed on the wrong thread
        // Laiwx.a()/L() etc enforce main thread. onChunk runs on karaoke-ws-reader.
        // If not on main, post to main and block with timeout.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final Object[] result = new Object[1];
            final Throwable[] holder = new Throwable[1];
            CountDownLatch latch = new CountDownLatch(1);
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() {
                    try {
                        result[0] = synthetic(name, params).invoke(c, args);
                    } catch (Throwable e) {
                        holder[0] = e;
                    } finally {
                        latch.countDown();
                    }
                }
            });
            try {
                if (!latch.await(4, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("KaraokeBridge: call to " + name + " timed out waiting for main thread");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("KaraokeBridge: call to " + name + " interrupted", ie);
            }
            if (holder[0] != null) {
                // v22: log the cause on THIS path too. v14's cause
                // logging below only fires on the direct-invoke path,
                // but gate calls arrive on karaoke-ws-reader and take
                // this posted-to-main path — without this line the
                // phone-side ring can never show why patch_setVolume
                // (or any synthetic) failed.
                Throwable t = holder[0];
                Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException
                        && ((java.lang.reflect.InvocationTargetException) t).getCause() != null)
                        ? ((java.lang.reflect.InvocationTargetException) t).getCause() : t;
                KaraokeLog.e("KaraokeBridge: synthetic " + name
                        + " cause: " + cause.getClass().getName()
                        + ": " + cause.getMessage(), t);
                // v23: the wrapper's stack (Handler/Looper) is useless —
                // log the CAUSE's own frames to find the exact throwing
                // call inside Laiwx.as / Lchg / ExoPlayer.K.
                StackTraceElement[] cf = cause.getStackTrace();
                StringBuilder sb = new StringBuilder(
                        "KaraokeBridge: synthetic " + name + " cause frames:");
                for (int i = 0; i < Math.min(cf.length, 14); i++)
                    sb.append("\n  at ").append(cf[i].toString());
                KaraokeLog.e(sb.toString());
                if (t instanceof RuntimeException) throw (RuntimeException) t;
                if (t instanceof Error) throw (Error) t;
                throw new IllegalStateException("KaraokeBridge: call to " + name + " failed on main thread", t);
            }
            return result[0];
        }
        try {
            return synthetic(name, params).invoke(c, args);
        } catch (Exception e) {
            // Logged here with the trace, exactly once; callers
            // convert it to a session error / refusal.
            KaraokeLog.e("KaraokeBridge: call to synthetic " + name
                    + " failed", e);
            // v14: InvocationTargetException hides the real fault.
            // recordTrace() logs only the wrapper (whose message
            // is usually null) and never walks getCause(), so the
            // phone-side ring never showed what the synthetic
            // itself threw — that is why the v13 mute failure
            // showed only "InvocationTargetException: null".
            // Log the cause explicitly so the LOG button dump
            // reveals it. No behavior change: the same
            // IllegalStateException is still thrown below, and
            // no return value or failure handling changes.
            if (e instanceof java.lang.reflect.InvocationTargetException) {
                Throwable cause =
                        ((java.lang.reflect.InvocationTargetException) e)
                                .getCause();
                if (cause != null) {
                    KaraokeLog.e("KaraokeBridge: synthetic " + name
                            + " cause: " + cause.getClass().getName()
                            + ": " + cause.getMessage(), cause);
                    // v23: same cause-frames logging on the direct path.
                    StackTraceElement[] cf2 = cause.getStackTrace();
                    StringBuilder sb2 = new StringBuilder(
                            "KaraokeBridge: synthetic " + name + " cause frames:");
                    for (int i = 0; i < Math.min(cf2.length, 14); i++)
                        sb2.append("\n  at ").append(cf2[i].toString());
                    KaraokeLog.e(sb2.toString());
                }
            }
            throw new IllegalStateException(
                    "KaraokeBridge: call to " + name + " failed", e);
        }
    }

    /**
     * v24: invoke a method on the underlying Media3 player
     * (Laiwx.g, androidx.media3.exoplayer.ExoPlayer) instead of
     * on Laiwx itself. Proven links: aiwx.as/L set playback SPEED
     * via chg (PlaybackParameters — chg(0.0) throws IAE, so 0.0 can
     * never go through L), while the player impl's O(float)
     * (obfuscated setVolume — clamps 0..1, pushes to renderers,
     * same path N() uses) is the real switch, verified via its
     * ae volume field.
     * Same main-thread dispatch + cause logging as call().
     */
    private static Object playerCall(String method, Class<?>[] params, Object[] args) {
        Object c = controller;
        if (c == null) {
            throw new IllegalStateException(
                    "KaraokeBridge: no controller set for player::" + method);
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final Object[] result = new Object[1];
            final Throwable[] holder = new Throwable[1];
            CountDownLatch latch = new CountDownLatch(1);
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() {
                    try {
                        result[0] = playerInvoke(c, method, params, args);
                    } catch (Throwable e) {
                        holder[0] = e;
                    } finally {
                        latch.countDown();
                    }
                }
            });
            try {
                if (!latch.await(4, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("KaraokeBridge: player::" + method + " timed out waiting for main thread");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("KaraokeBridge: player::" + method + " interrupted", ie);
            }
            if (holder[0] != null) {
                Throwable t = holder[0];
                Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException
                        && ((java.lang.reflect.InvocationTargetException) t).getCause() != null)
                        ? ((java.lang.reflect.InvocationTargetException) t).getCause() : t;
                KaraokeLog.e("KaraokeBridge: player::" + method
                        + " cause: " + cause.getClass().getName()
                        + ": " + cause.getMessage(), t);
                StackTraceElement[] cf = cause.getStackTrace();
                StringBuilder sb = new StringBuilder(
                        "KaraokeBridge: player::" + method + " cause frames:");
                for (int i = 0; i < Math.min(cf.length, 14); i++)
                    sb.append("\n  at ").append(cf[i].toString());
                KaraokeLog.e(sb.toString());
                if (t instanceof RuntimeException) throw (RuntimeException) t;
                if (t instanceof Error) throw (Error) t;
                throw new IllegalStateException("KaraokeBridge: player::" + method + " failed on main thread", t);
            }
            return result[0];
        }
        try {
            return playerInvoke(c, method, params, args);
        } catch (Exception e) {
            KaraokeLog.e("KaraokeBridge: player::" + method + " failed", e);
            if (e instanceof java.lang.reflect.InvocationTargetException) {
                Throwable cause = ((java.lang.reflect.InvocationTargetException) e).getCause();
                if (cause != null) {
                    KaraokeLog.e("KaraokeBridge: player::" + method
                            + " cause: " + cause.getClass().getName()
                            + ": " + cause.getMessage(), cause);
                    StackTraceElement[] cf2 = cause.getStackTrace();
                    StringBuilder sb2 = new StringBuilder(
                            "KaraokeBridge: player::" + method + " cause frames:");
                    for (int i = 0; i < Math.min(cf2.length, 14); i++)
                        sb2.append("\n  at ").append(cf2[i].toString());
                    KaraokeLog.e(sb2.toString());
                }
            }
            throw new IllegalStateException("KaraokeBridge: player::" + method + " failed", e);
        }
    }

    private static Object playerInvoke(Object ctrl, String method, Class<?>[] params, Object[] args) throws Exception {
        java.lang.reflect.Field gField;
        try {
            gField = ctrl.getClass().getField("g");
        } catch (NoSuchFieldException nsf) {
            gField = ctrl.getClass().getDeclaredField("g");
            gField.setAccessible(true);
        }
        Object player = gField.get(ctrl);
        if (player == null) {
            throw new IllegalStateException("KaraokeBridge: player (Laiwx.g) is null");
        }
        java.lang.reflect.Method m = player.getClass().getMethod(method, params);
        return m.invoke(player, args);
    }

    /**
     * v25: read the applied output volume (ctv.ae) on the main
     * thread. ctv exposes no volume getter, so the field is read
     * directly (private — setAccessible; pinned-version name ae,
     * drift guarded by the cause logging below). Field reads have
     * no thread check, but main-thread keeps visibility exact.
     */
    private static float playerGetVolume() {
        final float[] out = new float[1];
        final Throwable[] holder = new Throwable[1];
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final CountDownLatch latch = new CountDownLatch(1);
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() {
                    try {
                        out[0] = readPlayerVolumeField();
                    } catch (Throwable e) {
                        holder[0] = e;
                    } finally {
                        latch.countDown();
                    }
                }
            });
            try {
                if (!latch.await(4, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("KaraokeBridge: player volume read timed out waiting for main thread");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("KaraokeBridge: player volume read interrupted", ie);
            }
        } else {
            try {
                out[0] = readPlayerVolumeField();
            } catch (Throwable e) {
                holder[0] = e;
            }
        }
        if (holder[0] != null) {
            Throwable t = holder[0];
            Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException
                    && ((java.lang.reflect.InvocationTargetException) t).getCause() != null)
                    ? ((java.lang.reflect.InvocationTargetException) t).getCause() : t;
            KaraokeLog.e("KaraokeBridge: player volume read cause: " + cause.getClass().getName()
                    + ": " + cause.getMessage(), t);
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            if (t instanceof Error) throw (Error) t;
            throw new IllegalStateException("KaraokeBridge: player volume read failed", t);
        }
        return out[0];
    }

    private static float readPlayerVolumeField() throws Exception {
        Object c = controller;
        if (c == null) {
            throw new IllegalStateException("KaraokeBridge: no controller set for player volume read");
        }
        java.lang.reflect.Field gField;
        try {
            gField = c.getClass().getField("g");
        } catch (NoSuchFieldException nsf) {
            gField = c.getClass().getDeclaredField("g");
            gField.setAccessible(true);
        }
        Object player = gField.get(c);
        if (player == null) {
            throw new IllegalStateException("KaraokeBridge: player (Laiwx.g) is null");
        }
        java.lang.reflect.Field aeField = player.getClass().getDeclaredField("ae");
        aeField.setAccessible(true);
        return aeField.getFloat(player);
    }

    /**
     * v28: invoke a method directly on the Laiwx controller
     * (for package-private app methods like ar(ZZ)V, which the
     * patch synthetics cannot reach without a patch rebuild).
     * Same main-thread dispatch + cause logging as call().
     */
    private static Object controllerCall(String method, Class<?>[] params, Object[] args) {
        Object c = controller;
        if (c == null) {
            throw new IllegalStateException(
                    "KaraokeBridge: no controller set for controller::" + method);
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final Object[] result = new Object[1];
            final Throwable[] holder = new Throwable[1];
            CountDownLatch latch = new CountDownLatch(1);
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() {
                    try {
                        result[0] = controllerInvoke(c, method, params, args);
                    } catch (Throwable e) {
                        holder[0] = e;
                    } finally {
                        latch.countDown();
                    }
                }
            });
            try {
                if (!latch.await(4, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("KaraokeBridge: controller::" + method + " timed out waiting for main thread");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("KaraokeBridge: controller::" + method + " interrupted", ie);
            }
            if (holder[0] != null) {
                Throwable t = holder[0];
                Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException
                        && ((java.lang.reflect.InvocationTargetException) t).getCause() != null)
                        ? ((java.lang.reflect.InvocationTargetException) t).getCause() : t;
                KaraokeLog.e("KaraokeBridge: controller::" + method
                        + " cause: " + cause.getClass().getName()
                        + ": " + cause.getMessage(), t);
                if (t instanceof RuntimeException) throw (RuntimeException) t;
                if (t instanceof Error) throw (Error) t;
                throw new IllegalStateException("KaraokeBridge: controller::" + method + " failed on main thread", t);
            }
            return result[0];
        }
        try {
            return controllerInvoke(c, method, params, args);
        } catch (Exception e) {
            KaraokeLog.e("KaraokeBridge: controller::" + method + " failed", e);
            if (e instanceof java.lang.reflect.InvocationTargetException) {
                Throwable cause = ((java.lang.reflect.InvocationTargetException) e).getCause();
                if (cause != null) {
                    KaraokeLog.e("KaraokeBridge: controller::" + method
                            + " cause: " + cause.getClass().getName()
                            + ": " + cause.getMessage(), cause);
                }
            }
            throw new IllegalStateException("KaraokeBridge: controller::" + method + " failed", e);
        }
    }

    private static Object controllerInvoke(Object ctrl, String method, Class<?>[] params, Object[] args) throws Exception {
        java.lang.reflect.Method m;
        try {
            m = ctrl.getClass().getMethod(method, params);
        } catch (NoSuchMethodException nsf) {
            m = ctrl.getClass().getDeclaredMethod(method, params);
            m.setAccessible(true);
        }
        return m.invoke(ctrl, args);
    }

    /**
     * v29: extract the app's own audio stream URL from its
     * player response: controller.i (Laiwl) -> .l (Lajcf) ->
     * .z (VideoStreamingData) -> format lists p-u ->
     * FormatStreamModel entries with audio/* mime. The app
     * requested these URLs with its full session context, so
     * range fetches against them are the app-session route
     * (candidate (c) from the 403 investigation). Selection
     * mirrors the server's: highest bitrate, Opus tiebreak,
     * lower itag. Returns {url, bitrateBps} or null. The URL
     * is NEVER logged — host/itag/mime/count only.
     */
    @Override
    public String[] appAudioInfo() {
        try {
            Object c = controller;
            if (c == null) return null;
            Object aiwl = fieldOf(c, "i");
            if (aiwl == null) { KaraokeLog.i("app audio: Laiwl null"); return null; }
            Object ajcf = fieldOf(aiwl, "l");
            if (ajcf == null) { KaraokeLog.i("app audio: Lajcf null"); return null; }
            Object vsd = fieldOf(ajcf, "z");
            if (vsd == null) { KaraokeLog.i("app audio: streaming data null"); return null; }
            String bestUrl = null;
            String bestMime = null;
            int bestItag = 0;
            int bestBitrate = -1;
            int candidates = 0;
            for (String listName : new String[] {"p", "q", "r", "s", "t", "u"}) {
                Object listObj = fieldOf(vsd, listName);
                if (!(listObj instanceof java.util.List)) continue;
                for (Object fmt : (java.util.List<?>) listObj) {
                    if (fmt == null) continue;
                    Object mimeObj = fmt.getClass().getMethod("y").invoke(fmt);
                    if (!(mimeObj instanceof String)) continue;
                    String mime = (String) mimeObj;
                    if (!mime.startsWith("audio/")) continue;
                    Object uri = fieldOf(fmt, "e");
                    if (uri == null) continue;
                    String url = uri.toString();
                    int itag = ((Integer) fmt.getClass().getMethod("e").invoke(fmt)).intValue();
                    java.lang.reflect.Field gField = fmt.getClass().getField("g");
                    int bitrate = gField.getInt(fmt);
                    candidates++;
                    boolean opus = mime.contains("opus");
                    boolean bestOpus = bestMime != null && bestMime.contains("opus");
                    boolean take;
                    if (bestUrl == null) take = true;
                    else if (bitrate != bestBitrate) take = bitrate > bestBitrate;
                    else if (opus != bestOpus) take = opus;
                    else take = itag < bestItag;
                    if (take) {
                        bestUrl = url;
                        bestMime = mime;
                        bestItag = itag;
                        bestBitrate = bitrate;
                    }
                }
            }
            if (bestUrl == null) {
                KaraokeLog.i("app audio: no audio format found (candidates=" + candidates + ")");
                return null;
            }
            // v34: append the session cpn (Laiwx.p() — the same
            // token FormatStreamModel.l() stamps as ?cpn= on the
            // app's own media requests). Our fetches went out
            // without it and died at exactly 1MiB; the app, which
            // stamps cpn, streams whole songs. Never log its value.
            boolean cpnAdded = false;
            try {
                Object cpnObj = controllerCall("p",
                        new Class<?>[0], new Object[0]);
                if (cpnObj instanceof String
                        && !((String) cpnObj).isEmpty()
                        && !bestUrl.contains("cpn=")) {
                    bestUrl = bestUrl
                            + (bestUrl.contains("?") ? "&" : "?")
                            + "cpn=" + android.net.Uri.encode((String) cpnObj);
                    cpnAdded = true;
                }
            } catch (Exception e) {
                KaraokeLog.e("app audio: cpn read failed", e);
            }
            KaraokeLog.i("app audio: candidates=" + candidates
                    + " chosen itag=" + bestItag + " mime=" + bestMime
                    + " bitrate=" + bestBitrate + " host=" + KaraokeLog.hostOf(bestUrl)
                    + " cpnAdded=" + cpnAdded);
            return new String[] { bestUrl, String.valueOf(Math.max(bestBitrate, 0)) };
        } catch (Exception e) {
            KaraokeLog.e("KaraokeBridge: appAudioInfo failed", e);
            return null;
        }
    }

    private static Object fieldOf(Object target, String name) throws Exception {
        java.lang.reflect.Field f;
        try {
            f = target.getClass().getField(name);
        } catch (NoSuchFieldException nsf) {
            f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
        }
        return f.get(target);
    }

    /** v30: resolve Laiwx.g (the player) from the controller. */
    private static Object resolvePlayer() throws Exception {
        Object c = controller;
        if (c == null) {
            throw new IllegalStateException("KaraokeBridge: no controller set");
        }
        Object p = fieldOf(c, "g");
        if (p == null) {
            throw new IllegalStateException("KaraokeBridge: player (Laiwx.g) is null");
        }
        return p;
    }

    private static Object playerInvokeOn(Object player, String method, Class<?>[] params, Object[] args) throws Exception {
        java.lang.reflect.Method m = player.getClass().getMethod(method, params);
        return m.invoke(player, args);
    }

    /** v30: like playerCall, but on an explicit player object. */
    private static Object playerCallOn(final Object player, String method, Class<?>[] params, Object[] args) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final Object[] result = new Object[1];
            final Throwable[] holder = new Throwable[1];
            CountDownLatch latch = new CountDownLatch(1);
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() {
                    try {
                        result[0] = playerInvokeOn(player, method, params, args);
                    } catch (Throwable e) {
                        holder[0] = e;
                    } finally {
                        latch.countDown();
                    }
                }
            });
            try {
                if (!latch.await(4, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("KaraokeBridge: player::" + method + " timed out waiting for main thread");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("KaraokeBridge: player::" + method + " interrupted", ie);
            }
            if (holder[0] != null) {
                Throwable t = holder[0];
                Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException
                        && ((java.lang.reflect.InvocationTargetException) t).getCause() != null)
                        ? ((java.lang.reflect.InvocationTargetException) t).getCause() : t;
                KaraokeLog.e("KaraokeBridge: player::" + method
                        + " cause: " + cause.getClass().getName()
                        + ": " + cause.getMessage(), t);
                if (t instanceof RuntimeException) throw (RuntimeException) t;
                if (t instanceof Error) throw (Error) t;
                throw new IllegalStateException("KaraokeBridge: player::" + method + " failed on main thread", t);
            }
            return result[0];
        }
        try {
            return playerInvokeOn(player, method, params, args);
        } catch (Exception e) {
            KaraokeLog.e("KaraokeBridge: player::" + method + " failed", e);
            throw new IllegalStateException("KaraokeBridge: player::" + method + " failed", e);
        }
    }

    /** v30: read ctv.ae of an explicit player (main thread). */
    private static float playerGetVolumeOf(final Object player) {
        final float[] out = new float[1];
        final Throwable[] holder = new Throwable[1];
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final CountDownLatch latch = new CountDownLatch(1);
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() {
                    try {
                        java.lang.reflect.Field ae = player.getClass().getDeclaredField("ae");
                        ae.setAccessible(true);
                        out[0] = ae.getFloat(player);
                    } catch (Throwable e) {
                        holder[0] = e;
                    } finally {
                        latch.countDown();
                    }
                }
            });
            try {
                if (!latch.await(4, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("KaraokeBridge: player volume read timed out");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("KaraokeBridge: player volume read interrupted", ie);
            }
        } else {
            try {
                java.lang.reflect.Field ae = player.getClass().getDeclaredField("ae");
                ae.setAccessible(true);
                out[0] = ae.getFloat(player);
            } catch (Throwable e) {
                holder[0] = e;
            }
        }
        if (holder[0] != null) {
            Throwable t = holder[0];
            KaraokeLog.e("KaraokeBridge: player volume read cause: " + t.getClass().getName() + ": " + t.getMessage(), t);
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            throw new IllegalStateException("KaraokeBridge: player volume read failed", t);
        }
        return out[0];
    }

    private String getVideoIdOrNull() {
        try {
            return (String) call("patch_getVideoId",
                    new Class<?>[0], new Object[0]);
        } catch (Exception e) {
            KaraokeLog.e("KaraokeBridge: getVideoId failed", e);
            return null;
        }
    }

    // ---- KaraokeSession.VideoPosition ----

    @Override
    public long positionMs() {
        Object r = call("patch_getPosition",
                new Class<?>[0], new Object[0]);
        return ((Long) r).longValue();
    }

    /** v46: detach log dedupe for the videoId poll. */
    private static volatile boolean pollDetachedLogged;

    @Override
    public String videoId() {
        // v43: quiet poll read for the switch watchdog (ticks
        // every 500ms). When the response model is detached
        // mid-switch (Laiwl.l null), the synthetic NPEs — that
        // is an unknown, not an error: skip the synthetic
        // entirely (no NPE, no log spam) and let the watchdog
        // abstain this tick. Tap-time path keeps loud logging.
        // v46: the detached log is logged ONCE per detach, not
        // per tick (it flooded the ring in the v45 session).
        try {
            Object c = controller;
            if (c == null) return null;
            Object i = fieldOf(c, "i");
            Object l = (i == null) ? null : fieldOf(i, "l");
            if (l == null) {
                if (!pollDetachedLogged) {
                    pollDetachedLogged = true;
                    KaraokeLog.d("videoId poll: no response model");
                }
                return null;
            }
            pollDetachedLogged = false;
        } catch (Exception e) {
            KaraokeLog.d("videoId poll precheck unreadable");
            return null;
        }
        return getVideoIdOrNull();
    }

    @Override
    public boolean isPlaying() {
        // v28: real play state = PlaybackInfo playWhenReady via
        // the player's Q() (E.l). The old patch_isPlaying read
        // ctv field A, a mirror only M/ab writes — it read false
        // while the video played, which also disarmed pause.
        Object r = playerCall("Q",
                new Class<?>[0], new Object[0]);
        return ((Boolean) r).booleanValue();
    }

    /** v28: pause via Laiwx.ar(false, true) — the app's own
     *  pauseVideo path (Y() calls ar(false) then logs
     *  "pauseVideo."). The old patch_pause -> M(false) ->
     *  ExoPlayer.ab(false) only wrote ctv field A, which was
     *  already false, so it NO-OP'd and the picture never
     *  paused. ar drives J() -> PlaybackInfo + internal
     *  player + app bookkeeping. Force flag true so a drifted
     *  state flag cannot skip the pause. Never throws: a
     *  pause failure must not fail the session. */
    @Override
    public boolean pause() {
        try {
            controllerCall("ar",
                    new Class<?>[] { Boolean.TYPE, Boolean.TYPE },
                    new Object[] { Boolean.FALSE, Boolean.TRUE });
            return true;
        } catch (Exception e) {
            KaraokeLog.e("KaraokeBridge: pause() failed", e);
            return false;
        }
    }

    /** v28: resume via Laiwx.ar(true, true), the counterpart
     *  of pause() above (the app's play path uses ar(true)).
     *  Same never-throws contract. */
    @Override
    public boolean resume() {
        try {
            controllerCall("ar",
                    new Class<?>[] { Boolean.TYPE, Boolean.TYPE },
                    new Object[] { Boolean.TRUE, Boolean.TRUE });
            return true;
        } catch (Exception e) {
            KaraokeLog.e("KaraokeBridge: resume() failed", e);
            return false;
        }
    }

    @Override
    public float speed() {
        // v30: real playback speed. The synthetic named
        // patch_getVolume actually delegates to Laiwx.a(), which
        // returns PlaybackParameters.speed (chg.b) — proven when
        // the mute hunt decompiled chg. So the speed was on this
        // wire all along, mislabeled. Guarded: any failure -> 1.0.
        try {
            Object r = call("patch_getVolume",
                    new Class<?>[0], new Object[0]);
            float s = ((Float) r).floatValue();
            if (s > 0.0f && s <= 4.0f) {
                if (!speedWarningLogged) {
                    speedWarningLogged = true;
                    KaraokeLog.i("speed(): player speed=" + s
                            + " via patch_getVolume (Laiwx a())");
                }
                return s;
            }
        } catch (Exception e) {
            // call() already logged it; assume 1.0x.
        }
        return 1.0f;
    }

    // ---- KaraokeSession.MuteController ----

    @Override
    public boolean isMuted() {
        return muted;
    }

    @Override
    public boolean setMuted(boolean muted) {
        // v44: a mute flag from a PREVIOUS player (e.g. unmute
        // failed after that player was released at song end)
        // must not short-circuit this player. If the retained
        // muted player differs from the current one, the flag
        // is stale — clear it and mute for real.
        if (muted && this.muted && mutedPlayer != null) {
            try {
                if (resolvePlayer() != mutedPlayer) {
                    KaraokeLog.i("mute: stale flag cleared "
                            + "(player changed since mute)");
                    this.muted = false;
                }
            } catch (Exception e) {
                KaraokeLog.d("mute: stale-flag check skipped: "
                        + e.getMessage());
            }
        }
        if (muted == this.muted) return true;
        try {
            if (muted) {
                // v22: step logging + read-back poll. Every value is
                // logged so the phone-side ring shows exactly which
                // step failed (we were blind here in v21: the posted
                // path never logged the cause).
                float current = playerGetVolume();
                savedVolume = current;
                KaraokeLog.i("mute step: thread=" + Thread.currentThread().getName()
                        + " saved=" + current + " calling setVolume(0.0)");
                playerCall("O",
                        new Class<?>[] { Float.TYPE },
                        new Object[] { Float.valueOf(0.0f) });
                KaraokeLog.i("mute step: setVolume(0.0) returned ok, polling read-back");
                // READ BACK with poll: the player may apply the new
                // volume params asynchronously, so one immediate read
                // can still see the old value. Poll briefly; REFUSE
                // unless ~0 is observed (hard rule: never play both
                // audios at once — a failed mute stays a failure).
                float after = Float.NaN;
                boolean onMain = Looper.myLooper() == Looper.getMainLooper();
                int tries = onMain ? 1 : 10;
                for (int i = 0; i < tries; i++) {
                    after = playerGetVolume();
                    KaraokeLog.i("mute step: read-back try " + i + " = " + after);
                    if (!Float.isNaN(after) && Math.abs(after) <= 0.01f) break;
                    if (i + 1 < tries) {
                        try { Thread.sleep(100); }
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    }
                }
                if (Float.isNaN(after) || Math.abs(after) > 0.01f) {
                    KaraokeLog.e("mute: read-back FAILED (set 0.0, saved "
                            + current + ", read " + after + "); refusing mute");
                    return false;
                }
                this.muted = true;
                // v30: retain the exact muted player for unmute.
                try {
                    mutedPlayer = resolvePlayer();
                } catch (Exception e) {
                    KaraokeLog.e("mute: could not retain player ref", e);
                }
                KaraokeLog.i("mute: saved volume=" + current
                        + ", set 0.0, read back " + after
                        + " (player-internal, Media3 setVolume)");
            } else {
                float restore = savedVolume;
                // v30: unmute the exact player that was muted,
                // even after a controller/video switch.
                Object targetPlayer = mutedPlayer;
                if (targetPlayer != null) {
                    playerCallOn(targetPlayer, "O",
                            new Class<?>[] { Float.TYPE },
                            new Object[] { Float.valueOf(restore) });
                    this.muted = false;
                    float afterT = Float.NaN;
                    for (int i = 0; i < 10; i++) {
                        afterT = playerGetVolumeOf(targetPlayer);
                        if (!Float.isNaN(afterT) && Math.abs(afterT - restore) <= 0.01f) break;
                        try { Thread.sleep(100); }
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    }
                    if (Float.isNaN(afterT)
                            || Math.abs(afterT - restore) > 0.01f) {
                        KaraokeLog.e("unmute: read-back MISMATCH "
                                + "(restored " + restore + ", read "
                                + afterT + "); user may still be muted");
                        return false;
                    }
                    mutedPlayer = null;
                    KaraokeLog.i("unmute: restored volume=" + restore
                            + ", read back " + afterT + " (muted player)");
                    return true;
                }
                playerCall("O",
                        new Class<?>[] { Float.TYPE },
                        new Object[] { Float.valueOf(restore) });
                this.muted = false;
                // v22: same poll treatment on the unmute read-back so
                // cleanup reports the true state instead of one sample.
                float after = Float.NaN;
                boolean onMainU = Looper.myLooper() == Looper.getMainLooper();
                int triesU = onMainU ? 1 : 10;
                for (int i = 0; i < triesU; i++) {
                    after = playerGetVolume();
                    if (!Float.isNaN(after) && Math.abs(after - restore) <= 0.01f) break;
                    if (i + 1 < triesU) {
                        try { Thread.sleep(100); }
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    }
                }
                if (Float.isNaN(after)
                        || Math.abs(after - restore) > 0.01f) {
                    // Cleanup must not throw, but a failed unmute
                    // leaves the user muted: log it LOUDLY and
                    // report false so the session logs it too.
                    KaraokeLog.e("unmute: read-back MISMATCH "
                            + "(restored " + restore + ", read "
                            + after + "); user may still be muted");
                    return false;
                }
                KaraokeLog.i("unmute: restored volume=" + restore
                        + ", read back " + after);
            }
            return true;
        } catch (Exception e) {
            // Trace already logged by call(); this line records the
            // outcome so the session's refusal is understandable.
            KaraokeLog.e("KaraokeBridge: setMuted(" + muted
                    + ") failed; muted stays " + this.muted);
            // v44: a failed UNMUTE must not poison later sessions
            // with a stuck flag (seen at song-end DONE: the old
            // player was already released). Clear flag + ref;
            // the return stays false so cleanup logs honestly.
            if (!muted) {
                this.muted = false;
                mutedPlayer = null;
                KaraokeLog.e("unmute: cleared stale mute flag "
                        + "after failure");
            }
            return false;
        }
    }

    /** v53 mute guard implementation (see MuteController).
     *  Verifies the EXACT muted player still reads ~0: a video
     *  stutter can recreate the internal player (full volume)
     *  or reset the volume, returning the ORIGINAL audio
     *  mid-song while `muted` still says true. Re-mutes on any
     *  drift. Silent when healthy; loud on repair. Never
     *  throws, never fails the session. */
    @Override
    public boolean keepMuted() {
        try {
            if (!muted) {
                KaraokeLog.i("mute guard: flag false during"
                        + " PLAYING, re-muting");
                return setMuted(true);
            }
            Object cur;
            try {
                cur = resolvePlayer();
            } catch (Exception e) {
                KaraokeLog.d("mute guard: player unresolvable,"
                        + " skipping check");
                return true;
            }
            if (cur != mutedPlayer) {
                KaraokeLog.i("mute guard: player recreated"
                        + " (stutter), re-muting");
                muted = false;
                return setMuted(true);
            }
            float v;
            try {
                v = playerGetVolumeOf(cur);
            } catch (Exception e) {
                KaraokeLog.d("mute guard: read-back unavailable,"
                        + " skipping check");
                return true;
            }
            if (Float.isNaN(v) || Math.abs(v) > 0.01f) {
                KaraokeLog.i("mute guard: volume drifted to " + v
                        + ", re-muting");
                muted = false;
                return setMuted(true);
            }
            return true;
        } catch (Exception e) {
            KaraokeLog.w("mute guard: check failed, skipping", e);
            return true;
        }
    }

    // ---- KaraokeSession.Listener ----

    @Override
    public void onState(KaraokeSession.State state) {
        KaraokeLog.i("session state -> " + state);
        refreshKButton();
        // v13 loading overlay (prepare veil): show while
        // loading (CONNECTING/BUFFERING), hide on every exit
        // (PLAYING, ERROR, DONE, IDLE). PAUSED is the mirrored
        // mid-song pause, not loading, so it hides too.
        // Show dedupes by tag, so the BUFFERING re-attempt
        // after CONNECTING is a no-op if already shown.
        if (state == KaraokeSession.State.CONNECTING
                || state == KaraokeSession.State.BUFFERING) {
            showLoadingOverlay();
        } else {
            hideLoadingOverlay();
        }
        if (state == KaraokeSession.State.DONE) {
            resetBvToDefault();
        }
        if (state == KaraokeSession.State.PLAYING) {
            updateSeekbarFromAnchor();
        }
    }

    @Override
    public void onError(String message) {
        KaraokeLog.e("session error: " + message);
        KaraokeLog.setLastFailure(message);
        refreshKButton();
        // ERROR state already hides the overlay via onState;
        // this is belt + suspenders (a second hide is a no-op).
        hideLoadingOverlay();
        resetBvToDefault();
        // v54 Q31: fixed short toast (details stay in the log).
        toast("karaoke failed restart the app", true);
    }

    @Override
    public void onFirstAudio() {
        KaraokeLog.i("first karaoke audio (mute + skip + hold release done)");
        updateSeekbarFromAnchor();
    }

    /** Seek-bar updates from session callbacks (main thread). */
    private static void updateSeekbarFromAnchor() {
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    View anchor = anchorViewForOverlay();
                    View root = anchor != null
                            ? anchor.getRootView() : null;
                    if (root != null) {
                        updateKaraokeSeekbar(root);
                        updateKaraokeSeekbarActual(root);
                    }
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    // ---- v54 progress / rebuffer / sticky callbacks ----

    /** Weak ref to the veil's label, for live progress text. */
    private static volatile WeakReference<TextView> loadingLabelRef;

    /** Sets the veil label if the veil is up (main thread). */
    /** v69: last label text shown (identical updates skipped —
     *  re-setting the same text every chunk caused redraw blink). */
    private static volatile String lastLoadingLabelText;

    private static void setLoadingLabel(String text) {
        if (text != null && text.equals(lastLoadingLabelText)) return;
        lastLoadingLabelText = text;
        try {
            final String flat = String.valueOf(text).replace('\n', ' ');
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    WeakReference<TextView> ref = loadingLabelRef;
                    TextView label = ref != null ? ref.get() : null;
                    // v58: prove the progress chain in the log.
                    KaraokeLog.d("veil label <- \"" + flat + "\" (label "
                            + (label == null ? "GONE" : "alive") + ")");
                    if (label != null) label.setText(text);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception e) {
            KaraokeLog.w("setLoadingLabel post failed", e);
        }
    }

    @Override
    public void onLoadingProgress(String text) {
        setLoadingLabel(text);
    }

    @Override
    public void onColdStart() {
        KaraokeLog.i("server cold start — showing cold text");
        setLoadingLabel("cold start karaoke might take 15 seconds");
    }

    @Override
    public void onRebufferStart(String text) {
        KaraokeLog.i("rebuffer veil shown");
        showLoadingOverlay();
        setLoadingLabel(text);
    }

    @Override
    public void onRebufferProgress(String text) {
        setLoadingLabel(text);
    }

    @Override
    public void onRebufferEnd() {
        KaraokeLog.i("rebuffer veil hidden");
        hideLoadingOverlay();
    }

    @Override
    public void onVideoSwitched(String newVideoId) {
        // The session already stopped itself. Sticky K follows
        // the new video; otherwise stay stopped.
        if (karaokeSticky) {
            KaraokeLog.i("sticky K following switch to " + newVideoId);
            // v58: NEVER start on the caller's thread — the sync
            // switch path calls from the OLD session's dying sync
            // worker (interrupted): every main-latch call in the
            // new start (pause/pre-mute/cpn) failed "interrupted"
            // in the v57 log. A fresh thread is clean.
            final String target = newVideoId;
            new Thread(() -> restartForVideo(target, "sync-switch"),
                    "karaoke-restart").start();
        } else {
            if (session != null) {
                try {
                    if (session.state() == KaraokeSession.State.IDLE) {
                        session = null;
                    }
                } catch (Exception ignored) {
                }
            }
            refreshKButton();
        }
    }
}
