package app.karaokenow.extension;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * v6 warm-up ping (cold-start fix, from the project plan).
 *
 * When the player opens (controller set), fire ONE fire-and-
 * forget background GET to the server's /health endpoint so
 * the Modal container wakes from idle BEFORE the user taps K.
 * A cold start just cost a 60s+ handshake timeout on the
 * phone; this ping buys the warm-up time up front.
 *
 * Rules (hard):
 *  - Background daemon thread only. Never blocks the UI thread
 *    or the session path. The WS connect code is untouched.
 *  - The URL comes from the user's saved BYOK endpoint (see
 *    configure): host via KaraokeLog.hostOf + "/health". No
 *    hardcoded server — unconfigured means no ping. Only the
 *    host is ever logged, never the full URL.
 *  - One ping per player open: deduped by controller identity,
 *    plus a 10-minute re-ping gate for a reused controller
 *    after a long idle (Modal sleeps when idle). NO retry
 *    loop: a failed ping logs once and is done — never a
 *    retry storm, never an automatic fallback chain.
 *  - No toast, no log spam: at most one short line per ping.
 *  - Never throws (every path is guarded; a warm-up failure
 *    must never affect playback or karaoke).
 */
public final class KaraokeWarmup {

    /** Re-ping a reused controller after this long idle. */
    private static final long REPING_AFTER_MS = 10 * 60 * 1000L;

    private static volatile Object lastPinged;
    private static volatile long lastPingAtMs;
    /** v65: the user's saved endpoint (wss URL), or null. */
    private static volatile String endpointUrl;

    private KaraokeWarmup() {
    }

    /** Sets the warm-up target from the saved BYOK endpoint. */
    public static void configure(String wsUrl) {
        endpointUrl = wsUrl;
    }

    /** Fire-and-forget warm-up for a newly opened player. */
    public static void ping(Object controllerInstance) {
        try {
            if (controllerInstance == null) return;
            long now = System.currentTimeMillis();
            if (lastPinged == controllerInstance
                    && now - lastPingAtMs < REPING_AFTER_MS) {
                return; // Same player, recently pinged: one is enough.
            }
            String configured = endpointUrl;
            if (configured == null || configured.isEmpty()) {
                return; // No BYOK endpoint yet: nothing to warm.
            }
            String host = KaraokeLog.hostOf(configured);
            if (host == null || host.equals("unknown-host")) {
                KaraokeLog.w("warmup: unknown host, skipping");
                return;
            }
            // L5: only mark as pinged once we have a valid host
            // and are actually about to ping — the gate used to
            // be consumed above, before the endpoint check, so
            // a missing endpoint burned the 10-minute window.
            lastPinged = controllerInstance;
            lastPingAtMs = now;
            final String url = "https://" + host + "/health";
            final String logHost = host;
            Thread t = new Thread(() -> {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(15000);
                    c.setRequestMethod("GET");
                    int code = c.getResponseCode();
                    KaraokeLog.i("warmup: ping " + logHost
                            + " -> HTTP " + code);
                } catch (Exception e) {
                    String m = e.getMessage();
                    if (m == null || m.isEmpty()) {
                        m = e.getClass().getSimpleName();
                    }
                    KaraokeLog.w("warmup: ping " + logHost
                            + " failed: " + m);
                } finally {
                    if (c != null) c.disconnect();
                }
            }, "karaoke-warmup");
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            KaraokeLog.w("warmup: start failed", e);
        }
    }
}
