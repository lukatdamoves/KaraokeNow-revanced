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
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
 *     (plus optional "key" — the server API key, sent only
 *     when one is saved; keyed servers require it, keyless
 *     servers ignore the extra field)
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
 * sends fetches from a concurrent task). The reader loop hands each
 * fetch to a single-thread fetch executor and keeps processing
 * chunks/header/done/ping while it runs; the fetch thread sends the
 * fetch_response/fetch_error reply back via sendText (synchronized,
 * so frames from either thread stay atomic). Fetches are allowlisted:
 * https only, youtube.com / googlevideo.com hosts only (see
 * validateFetchUrl), server-supplied Host/Authorization/Cookie/
 * Content-Length headers are never applied, redirects are capped at 5
 * and must stay on the same https host.
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
    }

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int FETCH_READ_TIMEOUT_MS = 60_000;
    /** One total deadline for any fetch (Cronet or plain), 60s.
     *  D10: the two paths used to disagree (180s Cronet vs 60s plain). */
    private static final long FETCH_TOTAL_TIMEOUT_MS = 60_000;
    /** Max fetch redirects; each must stay on the same https host. */
    private static final int MAX_FETCH_REDIRECTS = 5;
    /** Fetch-response body cap, 2 MB: a 512 KiB range fetch plus
     *  headers never legitimately exceeds this by much. Anything
     *  bigger is refused with a fetch error instead of being
     *  base64'd into a ~32 MB reply on a 256 MB heap. */
    private static final int MAX_FETCH_RESPONSE_BYTES = 2 * 1024 * 1024;
    /** D1: WS frame/message caps. A normal chunk is ~90 KB of opus
     *  (+ base64), so 2 MB leaves ample headroom. */
    private static final int MAX_WS_FRAME_BYTES = 2 * 1024 * 1024;
    private static final int MAX_WS_MESSAGE_BYTES = 2 * 1024 * 1024;
    /** D11: one opus chunk is ~90 KB; 1 MB decoded is a generous cap. */
    private static final int MAX_OPUS_BYTES = 1024 * 1024;
    /** Base64 text for 1 MB decoded is ~1,398,104 chars; the string
     *  length is checked first, the decoded size is authoritative. */
    private static final int MAX_OPUS_B64_CHARS = 1_400_000;
    /** D13: handshake header cap + absolute handshake deadline. */
    private static final int MAX_HANDSHAKE_BYTES = 8 * 1024;
    private static final long HANDSHAKE_DEADLINE_MS = 60_000;

    private final String wsUrl;
    private final Listener listener;
    private volatile Socket socket;
    private volatile boolean closed;

    /** D9: set by failOnce(); the reader loop exits when set, and
     *  onError() fires at most once per connection (errorFired). */
    private volatile boolean failed;
    private final AtomicBoolean errorFired = new AtomicBoolean(false);

    /** D3: fetches run here, never on the reader thread. Lazily
     *  (re-)created: close() shuts it down, so a later connect() on
     *  the same instance must still be able to fetch. Guarded by
     *  `this` (see fetchExecutor()). */
    private ExecutorService fetchExecutorService;
    /** D3/D10: in-flight fetch transports, cancelled on close(). */
    private volatile HttpURLConnection inFlightPlainConn;
    private volatile org.chromium.net.UrlRequest inFlightCronetRequest;

    /** v54 reconnect hint: when >= 0 the server replays cached
     *  chunks from this seq instead of 0 (faster heal, less
     *  data). Set before connect(); -1 = from the start. */
    public volatile int resumeFromSeq = -1;

    /** Server API key, sent in the WS hello as "key" when set.
     *  Null/empty = keyless. Set before connect(); never logged. */
    public volatile String apiKey = null;

    public KaraokeWsClient(String wsUrl, Listener listener) {
        this.wsUrl = wsUrl;
        this.listener = listener;
    }

    /** Opens the socket, performs the HTTP Upgrade handshake, sends
     *  the first message, and starts the reader thread. */
    public void connect(String videoId, String appAudioUrl,
            int appAudioBitrate) throws IOException {
        // S6: wss only. The only legitimate endpoint is wss:; a
        // ws:/http: URL here is a misconfiguration, not a fallback.
        // (appAudioUrl/appAudioBitrate stay in the signature because
        // KaraokeSession passes them; S10: they are no longer put
        // in the hello, so the signed audio URL is never sent to
        // the endpoint.)
        if (wsUrl == null || !wsUrl.startsWith("wss:")) {
            throw new IOException("WebSocket URL must use wss:");
        }
        // D15: fresh cookie jar per connection. D9: fresh error state.
        clearCookies();
        errorFired.set(false);
        failed = false;
        closed = false;
        long connectStartNanos = System.nanoTime();
        KaraokeLog.i("WS connect: host=" + KaraokeLog.hostOf(wsUrl)
                + " videoId=" + videoId);
        URL url = new URL(wsUrl.replaceFirst("^wss:", "https:"));
        String host = url.getHost();
        if (host == null || host.isEmpty()) {
            throw new IOException("WebSocket URL has no host");
        }
        int port = url.getPort() != -1 ? url.getPort() : 443;
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
        // D13: everything from socket creation on is inside one
        // try: ANY failure closes the socket (the old code only
        // caught JSONException from the hello, so an IOException
        // from sendText leaked the socket with no reader thread).
        Socket s = null;
        try {
            javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket)
                    SSLSocketFactory.getDefault().createSocket();
            s = ssl;
            // D2/S7: hostname verification. Without this the chain
            // is validated but a valid cert for ANY domain would be
            // accepted for the Modal host (MITM on public Wi-Fi).
            javax.net.ssl.SSLParameters params = ssl.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(params);
            ssl.connect(addr, 8000);
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
            // D13: capped at 8 KB total, absolute 60s deadline
            // from connect start — the 60s SO_TIMEOUT resets per
            // byte, so without this a slow-drip server could keep
            // the handshake growing indefinitely.
            StringBuilder headers = new StringBuilder();
            InputStream in = s.getInputStream();
            int prev = -1, cur = -1;
            while ((cur = in.read()) != -1) {
                headers.append((char) cur);
                if (headers.length() > MAX_HANDSHAKE_BYTES) {
                    throw new IOException(
                            "WebSocket handshake headers too large");
                }
                if ((System.nanoTime() - connectStartNanos) / 1_000_000
                        > HANDSHAKE_DEADLINE_MS) {
                    throw new IOException("WebSocket handshake timed out");
                }
                if (prev == '\r' && cur == '\n'
                        && headers.toString().endsWith("\r\n\r\n")) break;
                prev = cur;
            }
            if (cur == -1) {
                throw new IOException("WebSocket handshake: EOF");
            }
            String hs = headers.toString();
            if (!hs.startsWith("HTTP/1.1 101")) {
                KaraokeLog.e("WS handshake failed: "
                        + hs.split("\r\n", 2)[0]);
                throw new IOException("WebSocket handshake failed: "
                        + hs.split("\r\n", 2)[0]);
            }
            String accept = base64Sha1(key
                    + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11");
            if (!hs.contains(accept)) {
                KaraokeLog.e("WS handshake: bad accept key");
                throw new IOException("WebSocket handshake: bad accept key");
            }
            KaraokeLog.i("WS handshake 101 ok, sending tunnel hello");

            JSONObject hello = new JSONObject()
                    .put("video_id", videoId)
                    .put("mode", "tunnel");
            // Server API key (keyed endpoints only): sent in the
            // hello when saved; keyless servers ignore the extra
            // field. Never logged — the hello is never logged.
            if (apiKey != null && !apiKey.isEmpty()) {
                hello.put("key", apiKey);
            }
            // S10: app_audio_url / app_audio_bitrate deliberately
            // NOT sent. The server ignores them in tunnel mode, and
            // the phone's signed audio URL must not be sent to
            // whatever endpoint is configured.
            // v54: reconnect resume point (cached replay starts here).
            if (resumeFromSeq >= 0) {
                hello.put("resume_from_seq", resumeFromSeq);
            }
            sendText(hello.toString());
        } catch (JSONException e) {
            closeSocket(s);
            closeQuietly();
            throw new IOException("WebSocket hello failed", e);
        } catch (IOException | RuntimeException e) {
            closeSocket(s);
            closeQuietly();
            throw e;
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

    /** Lazily (re-)creates the fetch executor. close() shuts it
     *  down, so a later connect() on the same instance must be
     *  able to get a fresh one. */
    private synchronized ExecutorService fetchExecutor() {
        if (fetchExecutorService == null
                || fetchExecutorService.isShutdown()) {
            fetchExecutorService = Executors.newSingleThreadExecutor(
                    r -> new Thread(r, "karaoke-ws-fetch"));
        }
        return fetchExecutorService;
    }

    private synchronized void shutdownFetchExecutor() {
        if (fetchExecutorService != null) fetchExecutorService.shutdownNow();
    }

    private static void closeSocket(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException e) {
            KaraokeLog.d("WS socket close failed (ignored): "
                    + e.getMessage());
        }
    }

    private void closeQuietly() {
        closeSocket(socket);
        // D3: cancel any in-flight fetch transport so the fetch
        // thread cannot stay blocked up to 180s after stop; the
        // fetch executor is shut down (interrupts the fetch thread).
        try {
            HttpURLConnection c = inFlightPlainConn;
            if (c != null) c.disconnect();
        } catch (Exception ignored) {
        }
        try {
            org.chromium.net.UrlRequest req = inFlightCronetRequest;
            if (req != null) req.cancel();
        } catch (Exception ignored) {
        }
        shutdownFetchExecutor();
        // D10: shut down the shared Cronet executor. The engine is
        // nulled too so the next session rebuilds both (a shut-down
        // executor with a live engine would NPE on the next fetch).
        shutdownCronet();
        // D15: the cookie jar is per-connection; never reuse it.
        clearCookies();
    }

    /** D10: shut down the static Cronet executor + engine. */
    private static void shutdownCronet() {
        synchronized (KaraokeWsClient.class) {
            if (cronetExecutor != null) {
                cronetExecutor.shutdownNow();
                cronetExecutor = null;
            }
            cronetEngine = null;
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

    // D3: synchronized like sendText — the reader thread sends pongs
    // while the fetch thread sends replies; both must hold the same
    // lock so two frames can never interleave on the wire.
    private synchronized void sendPong(byte[] payload) {
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

    /** Reads one byte; -1 (EOF) is an error, never a length byte.
     *  The old extended-length reads masked -1 into 0xFF, turning
     *  an EOF mid-header into a garbage length. */
    private static int readByteOrEof(InputStream in) throws IOException {
        int b = in.read();
        if (b == -1) throw new EOFException("socket closed");
        return b;
    }

    /** Reads one message, reassembling fragments. Returns null on close.
     *  D1: a single frame and the reassembled message are each capped
     *  at 2 MB (a normal chunk is ~90 KB); len is validated as a long
     *  BEFORE allocating, so a 64-bit length can neither truncate
     *  through (int) nor allocate unboundedly. Ping is handled
     *  iteratively (continue) — the old `return readMessage(in)`
     *  recursed, so a ping flood StackOverflowed the reader. */
    private String readMessage(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        while (true) {
            int b0 = readByteOrEof(in);
            int b1 = readByteOrEof(in);
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = ((long) readByteOrEof(in) << 8) | readByteOrEof(in);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) {
                    len = (len << 8) | readByteOrEof(in);
                }
            }
            if (len < 0 || len > MAX_WS_FRAME_BYTES) {
                throw new IOException("WS frame too large: " + len);
            }
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(in, mask);
            }
            if (opcode == 0x9) { // ping -> pong, then keep reading
                byte[] payload = new byte[(int) len];
                readFully(in, payload);
                if (masked) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i % 4];
                    }
                }
                sendPong(payload);
                continue;
            }
            if (opcode == 0x8) return null; // close
            if (opcode != 0x1 && opcode != 0x0) {
                throw new IOException("unsupported WS opcode " + opcode);
            }
            if (buf.size() + len > MAX_WS_MESSAGE_BYTES) {
                throw new IOException("WS message too large");
            }
            byte[] payload = new byte[(int) len];
            readFully(in, payload);
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i % 4];
                }
            }
            buf.write(payload, 0, payload.length);
            if (fin) return buf.toString(StandardCharsets.UTF_8.name());
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

    /** D9: onError() fires at most once per connection, and only
     *  while the session is still open (never after close()). */
    private void failOnce(String message) {
        failed = true;
        if (closed) return;
        if (errorFired.compareAndSet(false, true)) {
            try {
                listener.onError(message);
            } catch (Throwable ignored) {
                // A throwing listener must never kill the reader.
            }
        }
    }

    private void readLoop() {
        try {
            InputStream in = socket.getInputStream();
            while (!closed && !failed) {
                String text = readMessage(in);
                if (text == null) break;
                boolean keepGoing;
                try {
                    keepGoing = dispatch(new JSONObject(text));
                } catch (Throwable t) {
                    KaraokeLog.e("WS message parse failed", t);
                    failOnce("WS message parse failed: " + t.getMessage());
                    break;
                }
                if (!keepGoing) break;
            }
            if (!closed && !failed) {
                KaraokeLog.i("WS closed by server");
                try {
                    listener.onClosed();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable e) {
            // D1: Errors (OOM, StackOverflow) used to kill the reader
            // silently with no onError and no socket close, hanging
            // the session. Now every failure reports once and the
            // socket is always closed below.
            if (!closed) {
                KaraokeLog.e("WS reader failed", e);
                failOnce("WebSocket failed: " + e.getMessage());
            }
        } finally {
            // D9: the reader never leaks the socket on exit.
            closeQuietly();
        }
    }

    /** Dispatches one server message. Returns false when the reader
     *  loop must stop after this message (D9: an error was reported,
     *  so no further onChunk/onHeader may be delivered for this
     *  failed session); true to keep reading. */
    private boolean dispatch(JSONObject msg) {
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
                    // D3: the fetch runs on the fetch executor, NOT
                    // the reader thread, so chunks/header/done/ping
                    // keep flowing while a fetch is in flight.
                    // sendText is synchronized, so the fetch thread
                    // can safely send the reply (verified: the
                    // method was already synchronized; no change
                    // needed there).
                    if (!closed && !failed) {
                        final JSONObject fetchMsg = msg;
                        try {
                            fetchExecutor().submit(() -> runFetch(fetchMsg));
                        } catch (RejectedExecutionException e) {
                            // Executor shut down by close(); the
                            // session is ending, the fetch is dropped.
                            KaraokeLog.d(
                                    "WS fetch dropped: executor shut down");
                        }
                    }
                    return true;
                }
                case "header": {
                    // D11: a zero/negative sample rate used to pass
                    // through (startSec -> Infinity/negative).
                    int sampleRate = msg.optInt("sample_rate", 0);
                    if (sampleRate <= 0) {
                        failOnce("WS header: invalid sample_rate "
                                + sampleRate);
                        return false;
                    }
                    KaraokeLog.i("WS header received: sampleRate="
                            + sampleRate
                            + " coldStart=" + msg.optBoolean("cold_start")
                            + " cached=" + msg.optBoolean("cached"));
                    listener.onHeader(sampleRate,
                            msg.getBoolean("cold_start"),
                            msg.getBoolean("cached"));
                    return true;
                }
                case "chunk": {
                    // Two real streams since stage 2: the vocal-
                    // removed instrumental and the backing-vocal
                    // stem. Unknown streams are ignored rather
                    // than played as the wrong audio.
                    String stream = msg.optString("stream");
                    if (!"instrumental".equals(stream)
                            && !"backing".equals(stream)) return true;
                    // D11: validate before queueChunk() is called.
                    // queueChunk() itself is KaraokePlayer's method
                    // (not this file), so the checks live here.
                    int seq = msg.optInt("seq", -1);
                    long start = msg.optLong("start", -1);
                    if (seq < 0 || start < 0) {
                        failOnce("WS chunk: invalid seq/start seq=" + seq
                                + " start=" + start);
                        return false;
                    }
                    String opusB64 = msg.optString("opus_b64", null);
                    if (opusB64 == null
                            || opusB64.length() > MAX_OPUS_B64_CHARS) {
                        failOnce("WS chunk: opus_b64 missing or too large");
                        return false;
                    }
                    byte[] opus = Base64.decode(opusB64, Base64.DEFAULT);
                    if (opus.length > MAX_OPUS_BYTES) {
                        failOnce("WS chunk: opus too large: "
                                + opus.length + " bytes");
                        return false;
                    }
                    KaraokeLog.d("WS chunk received stream=" + stream
                            + " seq=" + seq
                            + " start=" + start
                            + " opusBytes=" + opus.length);
                    listener.onChunk(stream, seq, start, opus);
                    return true;
                }
                case "done":
                    KaraokeLog.i("WS done received: chunks="
                            + msg.optInt("chunks"));
                    listener.onDone(msg.getInt("chunks"));
                    return true;
                case "error":
                    KaraokeLog.e("WS server error: "
                            + msg.optString("message", "unknown error"));
                    failOnce("Server: "
                            + msg.optString("message", "unknown error"));
                    return false;
                default:
                    // Unknown message: stop, do not guess. The server
                    // treats unexpected messages the same way.
                    KaraokeLog.e("WS unexpected message type: " + type);
                    failOnce("Unexpected WS message type: " + type);
                    return false;
            }
        } catch (Throwable e) {
            KaraokeLog.e("WS dispatch failed", e);
            failOnce("WS dispatch failed: " + e.getMessage());
            return false;
        }
    }

    /** D3: runs on the fetch executor. Executes the fetch, absorbs
     *  its cookies (per-host, D15), and sends the reply back to the
     *  server. A per-fetch error is a fetch_error reply, NOT a
     *  session error — the session only fails if the reply itself
     *  cannot be sent. */
    private void runFetch(JSONObject msg) {
        FetchResult r;
        try {
            r = executeFetch(msg);
        } catch (Throwable t) {
            r = new FetchResult();
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        if (closed || failed) return;
        String fetchHost = hostOfUrl(msg.optString("url", ""));
        absorbCookies(r, fetchHost);
        int cookiesHeld = cookieCount();
        if (cookiesHeld > 0) {
            KaraokeLog.d("cookie jar: " + cookiesHeld + " cookies held");
        }
        try {
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
                    if (e.getKey() == null) continue;
                    // S8: never echo Set-Cookie/Cookie back to the server.
                    if ("Set-Cookie".equalsIgnoreCase(e.getKey())
                            || "Cookie".equalsIgnoreCase(e.getKey())) continue;
                    hs.put(e.getKey(), e.getValue());
                }
                reply.put("headers", hs);
                reply.put("body_b64", Base64.encodeToString(
                        r.body, Base64.NO_WRAP));
            }
            sendText(reply.toString());
        } catch (Throwable t) {
            if (!closed) {
                KaraokeLog.e("WS fetch reply failed", t);
                failOnce("WS fetch reply failed: " + t.getMessage());
                closeQuietly();
            }
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
    // D15: scoped per host — a cookie is only ever sent back to
    // the host that set it. The code never parsed domain/path/
    // expiry, so per-host scoping + clearing on close()/connect()
    // is the fix (no new cookie parsing was added).
    private final Map<String, Map<String, String>> cookieJar =
            new HashMap<>();

    private void absorbCookies(FetchResult r, String host) {
        if (r.setCookies == null || host == null || host.isEmpty()) return;
        synchronized (cookieJar) {
            Map<String, String> jar = cookieJar.get(host);
            if (jar == null) {
                jar = new HashMap<>();
                cookieJar.put(host, jar);
            }
            for (String sc : r.setCookies) {
                if (sc == null) continue;
                String pair = sc.split(";", 2)[0];
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    jar.put(pair.substring(0, eq).trim(),
                            pair.substring(eq + 1).trim());
                }
            }
        }
    }

    private String cookieHeader(String host) {
        if (host == null || host.isEmpty()) return null;
        synchronized (cookieJar) {
            Map<String, String> jar = cookieJar.get(host);
            if (jar == null || jar.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : jar.entrySet()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            return sb.toString();
        }
    }

    private int cookieCount() {
        synchronized (cookieJar) {
            int n = 0;
            for (Map<String, String> jar : cookieJar.values()) {
                n += jar.size();
            }
            return n;
        }
    }

    private void clearCookies() {
        synchronized (cookieJar) {
            cookieJar.clear();
        }
    }

    /** S6: fetch URL allowlist. Only https URLs under youtube.com /
     *  googlevideo.com are fetched — never http, never other hosts,
     *  never loopback/private/link-local addresses (the resolved
     *  addresses are checked, so a DNS-rebinding name cannot smuggle
     *  a LAN address past the host suffix check). Returns null when
     *  the URL is allowed, otherwise the fetch error string. */
    private static String validateFetchUrl(String urlStr) {
        if (urlStr == null || urlStr.isEmpty()) return "fetch: missing url";
        URL url;
        try {
            url = new URL(urlStr);
        } catch (Exception e) {
            return "fetch: bad url";
        }
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            return "fetch: only https allowed";
        }
        String host = url.getHost();
        if (host == null || host.isEmpty()) return "fetch: missing host";
        String h = host.toLowerCase(Locale.US);
        if ("localhost".equals(h)) return "fetch: localhost blocked";
        boolean allowed = "youtube.com".equals(h) || h.endsWith(".youtube.com")
                || "googlevideo.com".equals(h) || h.endsWith(".googlevideo.com");
        if (!allowed) return "fetch: host not allowlisted";
        // Runs on the fetch thread, never the reader thread.
        try {
            for (java.net.InetAddress a
                    : java.net.InetAddress.getAllByName(host)) {
                if (a.isLoopbackAddress() || a.isLinkLocalAddress()
                        || a.isSiteLocalAddress() || a.isAnyLocalAddress()) {
                    return "fetch: private/loopback address blocked";
                }
            }
        } catch (Exception e) {
            return "fetch: host resolution failed";
        }
        return null;
    }

    private static String hostOfUrl(String urlStr) {
        try {
            String h = new URL(urlStr).getHost();
            return h == null ? "" : h.toLowerCase(Locale.US);
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean sameHost(URL a, URL b) {
        return a.getHost() != null
                && a.getHost().equalsIgnoreCase(b.getHost());
    }

    /** S6: server-supplied headers that are never applied to the
     *  outgoing request, whatever the server asks for. */
    private static boolean isStrippedFetchHeader(String name) {
        return "Host".equalsIgnoreCase(name)
                || "Authorization".equalsIgnoreCase(name)
                || "Cookie".equalsIgnoreCase(name)
                || "Content-Length".equalsIgnoreCase(name);
    }

    /** Case-insensitive header presence check on a server-supplied
     *  header object (the old code tested only "Cookie"/"cookie"). */
    private static boolean hasHeaderIgnoreCase(JSONObject hs, String name) {
        if (hs == null) return false;
        JSONArray names = hs.names();
        if (names == null) return false;
        for (int i = 0; i < names.length(); i++) {
            if (name.equalsIgnoreCase(names.optString(i))) return true;
        }
        return false;
    }

    /**
     * Executes one server-requested fetch from THIS device's
     * connection. S6: the URL is allowlisted here (https,
     * youtube.com/googlevideo.com only) and server-supplied
     * Host/Authorization/Cookie/Content-Length headers are stripped
     * in both fetch paths — the client no longer trusts the server
     * to pick arbitrary fetch targets. The patch MUST still only
     * open this client to the user's own server session (see
     * KaraokeSession), and the server is the user's own BYOK
     * deployment. Never send this client a URL from any other party.
     */
    private FetchResult executeFetch(JSONObject msg) {
        String urlError = validateFetchUrl(msg.optString("url", ""));
        if (urlError != null) {
            FetchResult r = new FetchResult();
            r.error = urlError;
            return r;
        }
        // S6: a server-supplied Cookie is stripped, never applied.
        // The client's own per-host cookie jar is attached instead
        // (see the fetch paths); the jar is the session state.
        if (hasHeaderIgnoreCase(msg.optJSONObject("headers"), "Cookie")) {
            KaraokeLog.d("WS fetch: server-supplied Cookie stripped");
        }
        // v31: GET fetches (the googlevideo ranges) go through
        // Cronet — the same HTTP stack the app itself uses —
        // instead of HttpURLConnection. Plain-URLConnection range
        // fetches kept dying 403 after 0.5-1MB while the app
        // streams the same files fine.
        // D10: plain serves ONLY when the Cronet engine is null
        // (not built). A throw after the engine exists — including
        // after a Cronet request has started — is reported as a
        // fetch error, never silently re-fetched via plain.
        if ("GET".equals(msg.optString("method", "GET"))) {
            try {
                FetchResult cr = executeCronetFetch(msg);
                if (cr != null) return cr;
            } catch (Exception e) {
                KaraokeLog.e("cronet fetch failed", e);
                FetchResult r = new FetchResult();
                r.error = "cronet: " + e.getClass().getSimpleName()
                        + ": " + e.getMessage();
                return r;
            }
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
        if (closed) {
            FetchResult r = new FetchResult();
            r.error = "fetch canceled";
            return r;
        }
        // D10: the callback executor is captured here — close() can
        // shut it down concurrently, and a null/shut-down executor
        // must not NPE inside newUrlRequestBuilder.
        ExecutorService exec;
        synchronized (KaraokeWsClient.class) {
            exec = cronetExecutor;
        }
        if (exec == null || exec.isShutdown()) {
            FetchResult r = new FetchResult();
            r.error = "cronet: executor shut down";
            return r;
        }
        final FetchResult r = new FetchResult();
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        final CountDownLatch latch = new CountDownLatch(1);
        final String urlStr = msg.getString("url");
        final String fetchHost = hostOfUrl(urlStr);
        final int[] redirectCount = {0};
        org.chromium.net.UrlRequest.Callback cb =
                new org.chromium.net.UrlRequest.Callback() {
            @Override public void onRedirectReceived(
                    org.chromium.net.UrlRequest request,
                    org.chromium.net.UrlResponseInfo info,
                    String newLocationUrl) {
                // S6: max 5 redirects, same host only, https only
                // (and still allowlisted — same host implies it).
                redirectCount[0]++;
                String err = validateFetchUrl(newLocationUrl);
                if (redirectCount[0] > MAX_FETCH_REDIRECTS || err != null
                        || !fetchHost.equalsIgnoreCase(
                                hostOfUrl(newLocationUrl))) {
                    if (r.error == null) {
                        r.error = err != null ? err
                                : "fetch: redirect rejected";
                    }
                    request.cancel();
                    return;
                }
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
                .newUrlRequestBuilder(urlStr, cb, exec);
        b.setHttpMethod("GET");
        JSONObject hs = msg.optJSONObject("headers");
        if (hs != null) {
            JSONArray names = hs.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String k = names.getString(i);
                    // S6: server-supplied Host/Authorization/Cookie/
                    // Content-Length are never applied.
                    if (isStrippedFetchHeader(k)) continue;
                    b.addHeader(k, hs.getString(k));
                }
            }
        }
        // D15: only this fetch host's cookies are attached; a
        // server-supplied Cookie was stripped above and is never
        // a substitute for the jar.
        String ch = cookieHeader(fetchHost);
        if (ch != null) {
            b.addHeader("Cookie", ch);
        }
        org.chromium.net.UrlRequest request = b.build();
        inFlightCronetRequest = request;
        try {
            if (closed) {
                r.error = "fetch canceled";
                return r;
            }
            request.start();
            boolean done;
            try {
                // D10: one 60s total deadline (was 180s).
                done = latch.await(FETCH_TOTAL_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // close() interrupts the fetch thread; cancel the
                // request so the latch cannot stall the shutdown.
                try { request.cancel(); } catch (Exception ignored) {}
                Thread.currentThread().interrupt();
                r.error = "cronet: interrupted";
                return r;
            }
            if (!done) {
                try { request.cancel(); } catch (Exception ignored) {}
                r.error = "cronet: timed out";
                return r;
            }
        } finally {
            inFlightCronetRequest = null;
        }
        if (r.error == null) {
            r.body = buf.toByteArray();
        }
        return r;
    }

    private FetchResult executePlainFetch(JSONObject msg) {
        FetchResult r = new FetchResult();
        String urlStr;
        try {
            urlStr = msg.getString("url");
        } catch (JSONException e) {
            r.error = "fetch: missing url";
            return r;
        }
        String method = msg.optString("method", "GET");
        String bodyB64 = msg.optString("body_b64", null);
        byte[] reqBody = null;
        if (bodyB64 != null && !bodyB64.isEmpty()
                && !bodyB64.equals("null")) {
            try {
                reqBody = Base64.decode(bodyB64, Base64.DEFAULT);
            } catch (Exception e) {
                r.error = "fetch: bad body_b64";
                return r;
            }
        }
        // D10: one 60s total deadline for the whole fetch (connect +
        // redirects + body), not 15s + 60s per read.
        long startNanos = System.nanoTime();
        HttpURLConnection conn = null;
        try {
            for (int redirect = 0; redirect <= MAX_FETCH_REDIRECTS; redirect++) {
                // S6: redirects are followed manually (never
                // setInstanceFollowRedirects(true)): max 5, same
                // host only, https only, still allowlisted.
                URL url = new URL(urlStr);
                long remainingMs = FETCH_TOTAL_TIMEOUT_MS
                        - (System.nanoTime() - startNanos) / 1_000_000;
                if (remainingMs <= 0) {
                    r.error = "fetch timed out";
                    return r;
                }
                if (closed) {
                    r.error = "fetch canceled";
                    return r;
                }
                conn = (HttpURLConnection) url.openConnection();
                inFlightPlainConn = conn;
                conn.setRequestMethod(method);
                conn.setConnectTimeout(
                        (int) Math.min(CONNECT_TIMEOUT_MS, remainingMs));
                conn.setReadTimeout(
                        (int) Math.min(FETCH_READ_TIMEOUT_MS, remainingMs));
                conn.setInstanceFollowRedirects(false);
                JSONObject hs = msg.optJSONObject("headers");
                if (hs != null) {
                    JSONArray names = hs.names();
                    if (names != null) {
                        for (int i = 0; i < names.length(); i++) {
                            String k = names.getString(i);
                            // S6: server-supplied Host/Authorization/
                            // Cookie/Content-Length are never applied.
                            if (isStrippedFetchHeader(k)) continue;
                            conn.setRequestProperty(k, hs.getString(k));
                        }
                    }
                }
                // D15: only this fetch host's cookies are attached.
                String ch = cookieHeader(hostOfUrl(urlStr));
                if (ch != null) {
                    conn.setRequestProperty("Cookie", ch);
                }
                if (reqBody != null) {
                    conn.setDoOutput(true);
                    conn.getOutputStream().write(reqBody);
                }
                r.status = conn.getResponseCode();
                // Headers collected case-insensitively: the old
                // get("Set-Cookie") missed e.g. "set-cookie".
                for (Map.Entry<String, java.util.List<String>> e
                        : conn.getHeaderFields().entrySet()) {
                    if (e.getKey() == null) continue;
                    if ("Set-Cookie".equalsIgnoreCase(e.getKey())) {
                        r.setCookies.addAll(e.getValue());
                    }
                    if (!e.getValue().isEmpty()) {
                        r.headers.put(e.getKey(), e.getValue().get(0));
                    }
                }
                int sc = r.status;
                if (sc == 301 || sc == 302 || sc == 303 || sc == 307
                        || sc == 308) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null) break; // no target: final response
                    if (redirect == MAX_FETCH_REDIRECTS) {
                        r.error = "fetch: too many redirects";
                        return r;
                    }
                    URL next = new URL(url, loc);
                    String nextErr = validateFetchUrl(next.toString());
                    if (nextErr != null) {
                        r.error = nextErr;
                        return r;
                    }
                    if (!sameHost(url, next)) {
                        r.error =
                                "fetch: redirect to different host rejected";
                        return r;
                    }
                    conn.disconnect();
                    inFlightPlainConn = null;
                    conn = null;
                    // 303, and 301/302 for non-GET, become GET.
                    if (sc == 303 || ((sc == 301 || sc == 302)
                            && !"GET".equals(method)
                            && !"HEAD".equals(method))) {
                        method = "GET";
                        reqBody = null;
                    }
                    urlStr = next.toString();
                    continue;
                }
                break; // final response
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
                    if (closed) {
                        r.error = "fetch canceled";
                        return r;
                    }
                    long remainingMs = FETCH_TOTAL_TIMEOUT_MS
                            - (System.nanoTime() - startNanos) / 1_000_000;
                    if (remainingMs <= 0) {
                        r.error = "fetch timed out";
                        return r;
                    }
                    // Shrink the per-read timeout so a slow drip
                    // cannot stretch the fetch past the total.
                    try {
                        conn.setReadTimeout((int) Math.max(1, remainingMs));
                    } catch (Exception ignored) {
                    }
                }
            }
            r.body = buf.toByteArray();
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            inFlightPlainConn = null;
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
