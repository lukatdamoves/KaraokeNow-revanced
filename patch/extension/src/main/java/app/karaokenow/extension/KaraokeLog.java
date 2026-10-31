package app.karaokenow.extension;

import android.util.Log;
import java.util.ArrayDeque;

/**
 * The ONE log tag for all karaoke code: "KaraokeNow".
 *
 * Rules every karaoke class follows:
 *  - Log every state transition, WS event, decode/playback event,
 *    and drift correction (with millisecond values).
 *  - NEVER log the BYOK key or a full URL. Googlevideo URLs can
 *    carry tokens, so only the host is ever logged (see hostOf).
 *    Video IDs are safe to log.
 *  - On any exception in a karaoke thread, log a state snapshot
 *    plus the full stack trace (see crash), then stop cleanly.
 *    Nothing is ever swallowed silently.
 *  - Logs go to logcat AND to a small in-memory ring buffer, so a
 *    failure can be read on the phone itself (see dump): the user
 *    has no PC for adb, so logcat alone is not enough. The ring
 *    holds the last 800 lines plus the last failure message
 *    (v60: 200 evicted the whole load phase within ~10 chunks of
 *    playback, making load bugs undiagnosable from pastes).
 *    No new permissions, no network calls for logs.
 */
public final class KaraokeLog {

    /** The single tag. Filter logcat with: adb logcat -s KaraokeNow */
    public static final String TAG = "KaraokeNow";

    /** Phone-side log: last lines kept for the share sheet. */
    private static final int RING_CAP = 800;
    private static final ArrayDeque<String> RING = new ArrayDeque<>();
    private static volatile String lastFailure;

    private KaraokeLog() {
    }

    /** Appends one line to the phone-side ring. Never throws. */
    private static synchronized void record(String level, String message) {
        try {
            String line = level + ": "
                    + (message == null ? "null"
                            : message.replace('\n', ' '));
            RING.addLast(line);
            while (RING.size() > RING_CAP) RING.removeFirst();
        } catch (Exception ignored) {
            // The ring must never break logging itself.
        }
    }

    private static void recordTrace(Throwable t) {
        if (t == null) return;
        try {
            record("TRACE", t.getClass().getSimpleName() + ": "
                    + t.getMessage());
            StackTraceElement[] frames = t.getStackTrace();
            int n = Math.min(frames.length, 10);
            for (int i = 0; i < n; i++) {
                record("TRACE", "  at " + frames[i].toString());
            }
        } catch (Exception ignored) {
            // See above.
        }
    }

    /** Records the latest session failure (shown in the share). */
    public static void setLastFailure(String message) {
        lastFailure = message;
        record("FAIL", message);
    }

    /**
     * Clears the last-failure slot (call on session start /
     * onState(PLAYING) so a stale failure is not reshown in
     * every later log share). Synchronized like dump(), which
     * reads the slot.
     */
    public static synchronized void clearLastFailure() {
        lastFailure = null;
    }

    /**
     * Phone-readable log: last failure + recent lines. Used by the
     * share sheet (long-press the K button). Never throws, never
     * includes keys or full URLs (callers already obey that rule).
     */
    public static synchronized String dump() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("KaraokeNow log\n");
            sb.append("Last failure: ")
                    .append(lastFailure == null ? "none" : lastFailure)
                    .append("\n--- recent log ---\n");
            for (String line : RING) sb.append(line).append("\n");
            return sb.toString();
        } catch (Exception e) {
            return "KaraokeNow log (dump failed: " + e.getMessage() + ")";
        }
    }

    public static void d(String message) {
        record("D", message);
        Log.d(TAG, message);
    }

    public static void i(String message) {
        record("I", message);
        Log.i(TAG, message);
    }

    public static void w(String message) {
        record("W", message);
        Log.w(TAG, message);
    }

    public static void w(String message, Throwable t) {
        record("W", message);
        recordTrace(t);
        Log.w(TAG, message, t);
    }

    public static void e(String message) {
        record("E", message);
        Log.e(TAG, message);
    }

    public static void e(String message, Throwable t) {
        record("E", message);
        recordTrace(t);
        Log.e(TAG, message, t);
    }

    /**
     * Host part of a URL, for safe logging. Full URLs are NEVER
     * logged: googlevideo URLs can carry auth tokens in the path
     * or query string. Returns "unknown-host" when unparseable.
     * NOTE: do NOT use java.net.URL here — it rejects
     * non-hierarchical schemes, and wss:// throws "unknown
     * protocol", which is why the wss endpoint logged
     * "unknown-host" even though the handshake succeeded.
     * This parses scheme-agnostically instead.
     */
    public static String hostOf(String url) {
        if (url == null) return "unknown-host";
        try {
            String rest = url;
            int scheme = url.indexOf("://");
            if (scheme >= 0) rest = url.substring(scheme + 3);
            int end = rest.length();
            for (int i = 0; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c == '/' || c == '?' || c == '#') {
                    end = i;
                    break;
                }
            }
            String hostPort = rest.substring(0, end);
            int colon = hostPort.indexOf(':');
            String host = colon >= 0
                    ? hostPort.substring(0, colon) : hostPort;
            if (host.startsWith("[") && host.endsWith("]")
                    && host.length() > 2) {
                host = host.substring(1, host.length() - 1);
            }
            return host.isEmpty() ? "unknown-host" : host;
        } catch (Exception e) {
            return "unknown-host";
        }
    }

    /**
     * Crash/understanding log: one snapshot line plus the full
     * stack trace. Never throws itself, so it is safe to call
     * from any catch block, including cleanup paths.
     */
    public static void crash(String where, String snapshot, Throwable t) {
        record("CRASH", where + " | snapshot: " + snapshot);
        recordTrace(t);
        try {
            Log.e(TAG, "CRASH " + where + " | snapshot: " + snapshot, t);
        } catch (Exception ignored) {
            try {
                Log.e(TAG, "CRASH " + where + " (snapshot logging failed)");
            } catch (Exception ignoredAgain) {
                // Nothing left to do: logcat itself is unavailable.
            }
        }
    }
}
