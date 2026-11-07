package app.karaokenow.extension;

import android.content.Context;
import android.os.Looper;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSocketFactory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Minimal WebSocket client for the karaoke tunnel protocol, zero
 * third-party dependencies (no OkHttp version-clash risk with
 * YouTube's own HTTP stack).
 *
 * Protocol (VERIFIED against server/modal_app.py):
 *   client -> server first: {"video_id", "mode": "tunnel"}
 *   server -> client fetch: {"type":"fetch","id","method","url",
 *     "headers","body_b64"}  (body_b64 null when no body)
 *   client -> server: {"type":"fetch_response","id","status",
 *     "headers","body_b64"} or {"type":"fetch_error","id","error"}
 *   server -> client header: {"type":"header","video_id",
 *     "sample_rate","cold_start","cached"}
 *   server -> client chunk: {"type":"chunk","stream":"instrumental",
 *     "seq","start","opus_b64"}
 *       NOTE: "start" is a SAMPLE index at the header sample_rate
 *       (44100), NOT seconds. startSeconds = start / sample_rate.
 *   server -> client done: {"type":"done","chunks":n}
 *   server -> client error: {"type":"error","message":...}
 *
 * Fetch requests and chunks INTERLEAVE on one socket (the server
 * sends fetches from a concurrent task). This client runs a single
 * reader loop: fetch messages are executed synchronously (the
 * server sends fetches one at a time and awaits each reply), chunk
 * messages are handed to the listener without blocking.
 *
 * Failure policy (standing rule): any protocol violation, socket
 * failure, or server error stops the session with onError. There
 * is NO automatic retry, reconnect loop, or alternate source here.
 * Resume is an explicit, user/system-triggered operation owned by
 * KaraokeSession, not a silent retry inside this client.
 */
public class KaraokeWsClient {

    public interface Listener {
        void onHeader(int sampleRate, boolean coldStart, boolean cached);
        void onChunk(String stream, int seq, long startSamples,
                byte[] opusOggBytes);
        void onDone(int chunkCount);
        /** v41: server closed the socket. NOT an error by
         *  itself — after done, or mid-song with queued audio,
         *  playback continues until the queue drains. */
        void onClosed();
        void onError(String message);
        /** Optional song tessitura from the server (auto key).
         *  Default no-op so existing listeners need no change. */
        default void onVocalProfile(double centerCents) {
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int FETCH_READ_TIMEOUT_MS = 60_000;
    private static final int MAX_FETCH_RESPONSE_BYTES = 24 * 1024 * 1024;

    private final String wsUrl;
    private final Listener listener;
    private volatile Socket socket;
    private volatile boolean closed;

    /** v54 reconnect hint: when >= 0 the server replays cached
     *  chunks from this seq instead of 0 (faster heal, less
     *  data). Set before connect(); -1 = from the start. */
    public volatile int resumeFromSeq = -1;

    public KaraokeWsClient(String wsUrl, Listener listener) {
        this.wsUrl = wsUrl;
        this.listener = listener;
    }

    /** Opens the socket, performs the HTTP Upgrade handshake, sends
     *  the first message, and starts the reader thread. */
    public void connect(String videoId, String appAudioUrl,
            int appAudioBitrate) throws IOException {
        KaraokeLog.i("WS connect: host=" + KaraokeLog.hostOf(wsUrl)
                + " videoId=" + videoId);
        URL url = new URL(wsUrl.replaceFirst("^wss:", "https:")
                .replaceFirst("^ws:", "http:"));
        String host = url.getHost();
        int port = url.getPort() != -1 ? url.getPort()
                : (wsUrl.startsWith("wss:") ? 443 : 80);
        String path = url.getPath().isEmpty() ? "/" : url.getPath();
        if (url.getQuery() != null) path += "?" + url.getQuery();

        // v55: fail-fast connect (8s). The one-shot factory call
        // can hang indefinitely on a blackholed route, which
        // would stall a reconnect attempt (and its thread)
        // forever instead of backing off to the next attempt.
        // (The TLS handshake rides on first I/O under the 60s
        // read timeout below.)
        java.net.InetSocketAddress addr =
                new java.net.InetSocketAddress(host, port);
        Socket s;
        if (wsUrl.startsWith("wss:")) {
            javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket)
                    SSLSocketFactory.getDefault().createSocket();
            ssl.connect(addr, 8000);
            s = ssl;
        } else {
            s = new Socket();
            s.connect(addr, 8000);
        }
        s.setSoTimeout(READ_TIMEOUT_MS);
        socket = s;

        byte[] keyBytes = new byte[16];
        new Random().nextBytes(keyBytes);
        String key = Base64.encodeToString(keyBytes, Base64.NO_WRAP);
        String req = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + ":" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(req.getBytes(StandardCharsets.UTF_8));
        out.flush();

        // Read headers until the blank line; expect 101.
        StringBuilder headers = new StringBuilder();
        InputStream in = s.getInputStream();
        int prev = -1, cur;
        while ((cur = in.read()) != -1) {
            headers.append((char) cur);
            if (prev == '\r' && cur == '\n'
                    && headers.toString().endsWith("\r\n\r\n")) break;
            prev = cur;
        }
        String hs = headers.toString();
        if (!hs.startsWith("HTTP/1.1 101")) {
            closeQuietly();
            KaraokeLog.e("WS handshake failed: "
                    + hs.split("\r\n", 2)[0]);
            throw new IOException("WebSocket handshake failed: "
                    + hs.split("\r\n", 2)[0]);
        }
        String accept = base64Sha1(key
                + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11");
        if (!hs.contains(accept)) {
            closeQuietly();
            KaraokeLog.e("WS handshake: bad accept key");
            throw new IOException("WebSocket handshake: bad accept key");
        }
        KaraokeLog.i("WS handshake 101 ok, sending tunnel hello");

        try {
            JSONObject hello = new JSONObject()
                    .put("video_id", videoId)
                    .put("mode", "tunnel");
            // v29: app's own stream URL (never logged here).
            if (appAudioUrl != null) {
                hello.put("app_audio_url", appAudioUrl);
                hello.put("app_audio_bitrate", appAudioBitrate);
            }
            // v54: reconnect resume point (cached replay starts here).
            if (resumeFromSeq >= 0) {
                hello.put("resume_from_seq", resumeFromSeq);
            }
            sendText(hello.toString());
        } catch (JSONException e) {
            closeQuietly();
            throw new IOException("WebSocket hello failed", e);
        }
        new Thread(this::readLoop, "karaoke-ws-reader").start();
    }

    public void close() {
        KaraokeLog.i("WS close requested");
        closed = true;
        // v45: socket close on the main thread trips StrictMode
        // (NetworkOnMainThreadException) and the close silently
        // fails. Always close off-main.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            new Thread(this::closeQuietly, "karaoke-ws-close").start();
        } else {
            closeQuietly();
        }
    }

    /** True while the socket is up. Used by session snapshots. */
    public boolean isOpen() {
        return !closed && socket != null && !socket.isClosed();
    }

    private void closeQuietly() {
        try {
            if (socket != null) socket.close();
        } catch (IOException e) {
            KaraokeLog.d("WS socket close failed (ignored): "
                    + e.getMessage());
        }
    }

    // ---- framing (RFC 6455, client frames are masked) ----

    private synchronized void sendText(String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x81); // FIN + text opcode
        byte[] mask = new byte[4];
        new Random().nextBytes(mask);
        if (payload.length < 126) {
            frame.write(0x80 | payload.length);
        } else if (payload.length <= 0xFFFF) {
            frame.write(0x80 | 126);
            frame.write((payload.length >> 8) & 0xFF);
            frame.write(payload.length & 0xFF);
        } else {
            frame.write(0x80 | 127);
            // v8 fix: length MUST be a long. payload.length is an int,
            // and Java masks int shift distances to 5 bits, so the old
            // loop's >>56/48/40/32 silently became >>24/16/8/0 and
            // wrote the 4 length bytes TWICE (a 267KB frame declared
            // ~1.1 petabytes, which the proxy kills with no error
            // frame). With a long, shifts 56..0 are all real.
            long len = payload.length;
            for (int i = 7; i >= 0; i--) {
                frame.write((int) ((len >> (8 * i)) & 0xFF));
            }
        }
        frame.write(mask, 0, 4);
        for (int i = 0; i < payload.length; i++) {
            frame.write(payload[i] ^ mask[i % 4]);
        }
        out.write(frame.toByteArray());
        out.flush();
    }

    private void sendPong(byte[] payload) {
        try {
            // v8 fix: client frames MUST be masked (RFC 6455). The old
            // code sent an unmasked pong, which a strict proxy kills.
            OutputStream out = socket.getOutputStream();
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x8A); // FIN + pong opcode
            byte[] mask = new byte[4];
            new Random().nextBytes(mask);
            if (payload.length < 126) {
                frame.write(0x80 | payload.length);
            } else if (payload.length <= 0xFFFF) {
                frame.write(0x80 | 126);
                frame.write((payload.length >> 8) & 0xFF);
                frame.write(payload.length & 0xFF);
            } else {
                frame.write(0x80 | 127);
                long len = payload.length;
                for (int i = 7; i >= 0; i--) {
                    frame.write((int) ((len >> (8 * i)) & 0xFF));
                }
            }
            frame.write(mask, 0, 4);
            for (int i = 0; i < payload.length; i++) {
                frame.write(payload[i] ^ mask[i % 4]);
            }
            out.write(frame.toByteArray());
            out.flush();
        } catch (IOException e) {
            // Pong is best-effort; the reader loop surfaces a dead
            // socket on its next read. Logged, not silent.
            KaraokeLog.d("WS pong failed: " + e.getMessage());
        }
    }

    /** Reads one message, reassembling fragments. Returns null on close. */
    private String readMessage(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        boolean fin = false;
        while (!fin) {
            int b0 = in.read();
            int b1 = in.read();
            if (b0 == -1 || b1 == -1) throw new EOFException("socket closed");
            fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xFF);
            }
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(in, mask);
            }
            if (opcode == 0x9) { // ping -> pong, then continue reading
                byte[] payload = new byte[(int) len];
                readFully(in, payload);
                sendPong(payload);
                return readMessage(in);
            }
            if (opcode == 0x8) return null; // close
            if (opcode != 0x1 && opcode != 0x0) {
                throw new IOException("unsupported WS opcode " + opcode);
            }
            byte[] payload = new byte[(int) len];
            readFully(in, payload);
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i % 4];
                }
            }
            buf.write(payload, 0, payload.length);
        }
        return buf.toString(StandardCharsets.UTF_8.name());
    }

    /** Shared: pull vocal_profile out of any server message. */
    private void forwardVocalProfile(JSONObject msg) {
        try {
            JSONObject vp = msg.optJSONObject("vocal_profile");
            if (vp == null) return;
            double c = vp.optDouble("center_cents", Double.NaN);
            if (Double.isNaN(c)) return;
            KaraokeLog.i("WS vocal_profile centre=" + Math.round(c)
                    + " cents frames=" + vp.optInt("voiced_frames"));
            listener.onVocalProfile(c);
        } catch (Exception e) {
            KaraokeLog.w("WS vocal_profile parse failed", e);
        }
    }

    private static void readFully(InputStream in, byte[] buf)
            throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n == -1) throw new EOFException("socket closed mid-frame");
            off += n;
        }
    }

    // ---- reader loop / protocol dispatch ----

    private void readLoop() {
        try {
            InputStream in = socket.getInputStream();
            while (!closed) {
                String text = readMessage(in);
                if (text == null) break;
                dispatch(new JSONObject(text));
            }
            if (!closed) {
                KaraokeLog.i("WS closed by server");
                listener.onClosed();
            }
        } catch (Exception e) {
            if (!closed) {
                KaraokeLog.e("WS reader failed", e);
                listener.onError("WebSocket failed: " + e.getMessage());
            }
        }
    }

    private void dispatch(JSONObject msg) {
        try {
            String type = msg.optString("type", "");
            switch (type) {
                case "fetch": {
                    // Log method + host ONLY. The full URL can
                    // carry tokens and is never logged.
                    KaraokeLog.d("WS fetch request id=" + msg.optInt("id")
                            + " method=" + msg.optString("method", "GET")
                            + " host="
                            + KaraokeLog.hostOf(msg.optString("url")));
                    FetchResult r = executeFetch(msg);
                    absorbCookies(r);
                    if (!cookieJar.isEmpty()) {
                        KaraokeLog.d("cookie jar: " + cookieJar.size()
                                + " cookies held");
                    }
                    JSONObject reply = new JSONObject();
                    if (r.error != null) {
                        KaraokeLog.w("WS fetch error id=" + msg.optInt("id")
                                + " error=" + r.error);
                        reply.put("type", "fetch_error");
                        reply.put("id", msg.getInt("id"));
                        reply.put("error", r.error);
                    } else {
                        KaraokeLog.d("WS fetch response id="
                                + msg.optInt("id") + " status=" + r.status
                                + " bytes=" + r.body.length);
                        reply.put("type", "fetch_response");
                        reply.put("id", msg.getInt("id"));
                        reply.put("status", r.status);
                        JSONObject hs = new JSONObject();
                        for (Map.Entry<String, String> e
                                : r.headers.entrySet()) {
                            hs.put(e.getKey(), e.getValue());
                        }
                        reply.put("headers", hs);
                        reply.put("body_b64", Base64.encodeToString(
                                r.body, Base64.NO_WRAP));
                    }
                    sendText(reply.toString());
                    break;
                }
                case "header":
                    KaraokeLog.i("WS header received: sampleRate="
                            + msg.optInt("sample_rate")
                            + " coldStart=" + msg.optBoolean("cold_start")
                            + " cached=" + msg.optBoolean("cached"));
                    listener.onHeader(msg.getInt("sample_rate"),
                            msg.getBoolean("cold_start"),
                            msg.getBoolean("cached"));
                    // The song's vocal centre may ride on the header
                    // (cached songs) or arrive later on its own.
                    forwardVocalProfile(msg);
                    break;
                case "vocal_profile":
                    // Sent once enough of the vocal stem has been
                    // analysed (docs/KEYCHANGE.md).
                    forwardVocalProfile(msg);
                    break;
                case "chunk": {
                    // Two real streams since stage 2: the vocal-
                    // removed instrumental and the backing-vocal
                    // stem. Unknown streams are ignored rather
                    // than played as the wrong audio.
                    String stream = msg.optString("stream");
                    if (!"instrumental".equals(stream)
                            && !"backing".equals(stream)) break;
                    byte[] opus = Base64.decode(
                            msg.getString("opus_b64"), Base64.DEFAULT);
                    KaraokeLog.d("WS chunk received stream=" + stream
                            + " seq=" + msg.optInt("seq")
                            + " start=" + msg.optLong("start")
                            + " opusBytes=" + opus.length);
                    listener.onChunk(stream, msg.getInt("seq"),
                            msg.getLong("start"), opus);
                    break;
                }
                case "done":
                    KaraokeLog.i("WS done received: chunks="
                            + msg.optInt("chunks"));
                    listener.onDone(msg.getInt("chunks"));
                    break;
                case "error":
                    KaraokeLog.e("WS server error: "
                            + msg.optString("message", "unknown error"));
                    listener.onError("Server: "
                            + msg.optString("message", "unknown error"));
                    break;
                default:
                    // Unknown message: stop, do not guess. The server
                    // treats unexpected messages the same way.
                    KaraokeLog.e("WS unexpected message type: " + type);
                    listener.onError("Unexpected WS message type: " + type);
                    break;
            }
        } catch (Exception e) {
            KaraokeLog.e("WS dispatch failed", e);
            listener.onError("WS dispatch failed: " + e.getMessage());
        }
    }

    private static class FetchResult {
        int status;
        Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];
        String error;
        java.util.List<String> setCookies = new java.util.ArrayList<>();
    }

    // v38 session cookie jar: yt-dlp carries cookies from the
    // /tv bootstrap page into the player request; our fetches
    // were stateless, so the visitor session never linked and
    // playability stayed bot-gated. Names/values never logged
    // (count only). Per-WS-session, youtube fetches only.
    private final Map<String, String> cookieJar = new HashMap<>();

    private void absorbCookies(FetchResult r) {
        if (r.setCookies == null) return;
        for (String sc : r.setCookies) {
            if (sc == null) continue;
            String pair = sc.split(";", 2)[0];
            int eq = pair.indexOf('=');
            if (eq > 0) {
                cookieJar.put(pair.substring(0, eq).trim(),
                        pair.substring(eq + 1).trim());
            }
        }
    }

    private String cookieHeader() {
        if (cookieJar.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookieJar.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * Executes one server-requested fetch from THIS device's
     * connection. No URL allowlist is enforced here because the
     * server drives the URLs; the patch MUST only open this client
     * to the user's own server session (see KaraokeSession), and
     * the server is the user's own BYOK deployment. Never send this
     * client a URL from any other party.
     */
    private FetchResult executeFetch(JSONObject msg) {
        // v31: GET fetches (the googlevideo ranges) go through
        // Cronet — the same HTTP stack the app itself uses —
        // instead of HttpURLConnection. Plain-URLConnection range
        // fetches kept dying 403 after 0.5-1MB while the app
        // streams the same files fine. If the Cronet engine
        // cannot be built at all, that fact is logged loudly
        // and the plain path serves (capability detection, not
        // a failure retry: a failed Cronet FETCH still stops).
        try {
            if ("GET".equals(msg.optString("method", "GET"))) {
                FetchResult cr = executeCronetFetch(msg);
                if (cr != null) return cr;
            }
        } catch (Exception e) {
            KaraokeLog.e("cronet fetch setup threw", e);
        }
        return executePlainFetch(msg);
    }

    private static volatile org.chromium.net.CronetEngine cronetEngine;
    private static volatile boolean cronetUnavailable;
    private static ExecutorService cronetExecutor;

    private static org.chromium.net.CronetEngine cronet() {
        if (cronetEngine != null) return cronetEngine;
        if (cronetUnavailable) return null;
        synchronized (KaraokeWsClient.class) {
            if (cronetEngine != null) return cronetEngine;
            if (cronetUnavailable) return null;
            try {
                Context ctx = KaraokeBridge.appContextOrNull();
                if (ctx == null) {
                    KaraokeLog.i("cronet: no app context yet");
                    return null;
                }
                // v33: force YouTube's EMBEDDED native provider.
                // The default Builder picked the GMS provider,
                // whose impl is broken in the patched app
                // (NativeCronetEngineBuilderImpl construct
                // failure in the v31/v32 logs). NativeCronet-
                // Provider ships inside YouTube's own dex.
                Class<?> providerClass = Class.forName(
                        "org.chromium.net.impl.NativeCronetProvider");
                Object provider = providerClass
                        .getConstructor(Context.class)
                        .newInstance(ctx);
                Object builder = providerClass
                        .getMethod("createBuilder")
                        .invoke(provider);
                for (String opt : new String[] {
                        "enableHttp2", "enableQuic", "enableBrotli"}) {
                    try {
                        builder.getClass()
                                .getMethod(opt, boolean.class)
                                .invoke(builder, Boolean.TRUE);
                    } catch (Exception ignored) {
                        // Option absent on this Cronet version.
                    }
                }
                cronetEngine = (org.chromium.net.CronetEngine)
                        builder.getClass().getMethod("build")
                                .invoke(builder);
                cronetExecutor = Executors.newSingleThreadExecutor();
                KaraokeLog.i("cronet: native engine built");
            } catch (Throwable t) {
                cronetUnavailable = true;
                KaraokeLog.e("cronet: engine build failed "
                        + "(plain fetch serves, logged)", t);
                return null;
            }
            return cronetEngine;
        }
    }

    /** Synchronous Cronet GET. Returns null only when no engine. */
    private FetchResult executeCronetFetch(JSONObject msg) throws Exception {
        org.chromium.net.CronetEngine engine = cronet();
        if (engine == null) return null;
        final FetchResult r = new FetchResult();
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        final CountDownLatch latch = new CountDownLatch(1);
        final String urlStr = msg.getString("url");
        org.chromium.net.UrlRequest.Callback cb =
                new org.chromium.net.UrlRequest.Callback() {
            @Override public void onRedirectReceived(
                    org.chromium.net.UrlRequest request,
                    org.chromium.net.UrlResponseInfo info,
                    String newLocationUrl) {
                request.followRedirect();
            }
            @Override public void onResponseStarted(
                    org.chromium.net.UrlRequest request,
                    org.chromium.net.UrlResponseInfo info) {
                r.status = info.getHttpStatusCode();
                for (Map.Entry<String, List<String>> e
                        : info.getAllHeaders().entrySet()) {
                    if (e.getKey() != null && "Set-Cookie".equalsIgnoreCase(e.getKey())) {
                        r.setCookies.addAll(e.getValue());
                    }
                    if (e.getKey() != null && !e.getValue().isEmpty()) {
                        r.headers.put(e.getKey(), e.getValue().get(0));
                    }
                }
                request.read(ByteBuffer.allocateDirect(32 * 1024));
            }
            @Override public void onReadCompleted(
                    org.chromium.net.UrlRequest request,
                    org.chromium.net.UrlResponseInfo info,
                    ByteBuffer byteBuffer) {
                byteBuffer.flip();
                int n = byteBuffer.remaining();
                byte[] tmp = new byte[n];
                byteBuffer.get(tmp);
                buf.write(tmp, 0, n);
                if (buf.size() > MAX_FETCH_RESPONSE_BYTES) {
                    r.error = "fetch response too large";
                    request.cancel();
                    latch.countDown();
                    return;
                }
                byteBuffer.clear();
                request.read(byteBuffer);
            }
            @Override public void onSucceeded(
                    org.chromium.net.UrlRequest request,
                    org.chromium.net.UrlResponseInfo info) {
                latch.countDown();
            }
            @Override public void onFailed(
                    org.chromium.net.UrlRequest request,
                    org.chromium.net.UrlResponseInfo info,
                    org.chromium.net.CronetException error) {
                r.error = "cronet: " + error.getClass().getSimpleName()
                        + ": " + error.getMessage();
                latch.countDown();
            }
            @Override public void onCanceled(
                    org.chromium.net.UrlRequest request,
                    org.chromium.net.UrlResponseInfo info) {
                if (r.error == null) r.error = "cronet: canceled";
                latch.countDown();
            }
        };
        org.chromium.net.UrlRequest.Builder b = engine
                .newUrlRequestBuilder(urlStr, cb, cronetExecutor);
        b.setHttpMethod("GET");
        JSONObject hs = msg.optJSONObject("headers");
        if (hs != null) {
            JSONArray names = hs.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String k = names.getString(i);
                    b.addHeader(k, hs.getString(k));
                }
            }
        }
        JSONObject hs2 = msg.optJSONObject("headers");
        boolean serverSetCookie = hs2 != null
                && (hs2.has("Cookie") || hs2.has("cookie"));
        String ch = cookieHeader();
        if (ch != null && !serverSetCookie) {
            b.addHeader("Cookie", ch);
        }
        org.chromium.net.UrlRequest request = b.build();
        request.start();
        if (!latch.await(180_000,
                TimeUnit.MILLISECONDS)) {
            try { request.cancel(); } catch (Exception ignored) {}
            r.error = "cronet: timed out";
            return r;
        }
        if (r.error == null) {
            r.body = buf.toByteArray();
        }
        return r;
    }

    private FetchResult executePlainFetch(JSONObject msg) {
        FetchResult r = new FetchResult();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(msg.getString("url"));
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(msg.optString("method", "GET"));
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(FETCH_READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            JSONObject hs = msg.optJSONObject("headers");
            if (hs != null) {
                JSONArray names = hs.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String k = names.getString(i);
                        conn.setRequestProperty(k, hs.getString(k));
                    }
                }
            }
            String bodyB64 = msg.optString("body_b64", null);
            {
                JSONObject hs3 = msg.optJSONObject("headers");
                boolean serverSetCookie2 = hs3 != null
                        && (hs3.has("Cookie") || hs3.has("cookie"));
                String ch2 = cookieHeader();
                if (ch2 != null && !serverSetCookie2) {
                    conn.setRequestProperty("Cookie", ch2);
                }
            }
            if (bodyB64 != null && !bodyB64.isEmpty()
                    && !bodyB64.equals("null")) {
                byte[] body = Base64.decode(bodyB64, Base64.DEFAULT);
                conn.setDoOutput(true);
                conn.getOutputStream().write(body);
            }
            r.status = conn.getResponseCode();
            java.util.List<String> scList =
                    conn.getHeaderFields().get("Set-Cookie");
            if (scList != null) r.setCookies.addAll(scList);
            for (Map.Entry<String, java.util.List<String>> e
                    : conn.getHeaderFields().entrySet()) {
                if (e.getKey() != null && !e.getValue().isEmpty()) {
                    r.headers.put(e.getKey(), e.getValue().get(0));
                }
            }
            InputStream in = r.status >= 400
                    ? conn.getErrorStream() : conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            if (in != null) {
                byte[] tmp = new byte[8192];
                int n;
                while ((n = in.read(tmp)) != -1) {
                    buf.write(tmp, 0, n);
                    if (buf.size() > MAX_FETCH_RESPONSE_BYTES) {
                        r.error = "fetch response too large";
                        return r;
                    }
                }
            }
            r.body = buf.toByteArray();
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
        return r;
    }

    private static String base64Sha1(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(digest, Base64.NO_WRAP);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
