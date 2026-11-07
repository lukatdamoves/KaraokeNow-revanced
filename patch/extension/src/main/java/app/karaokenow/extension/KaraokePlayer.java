package app.karaokenow.extension;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Karaoke audio player: Ogg-Opus chunks -> MediaCodec -> own AudioTrack.
 *
 * VERIFIED server facts this code depends on (server/pipeline.py,
 * server/modal_app.py):
 *   - Each chunk's opus_b64 decodes to an OGG-wrapped Opus stream
 *     (ffmpeg "-f opus"), NOT raw Opus packets. A naive "feed the
 *     bytes straight to MediaCodec" fails; this player demuxes Ogg
 *     first. This is the one interop catch in the whole protocol.
 *   - Chunk "start" is a SAMPLE index at the header sample_rate
 *     (44100), not seconds. startSeconds = start / sample_rate.
 *   - Chunk centres tile exactly by HOP = 5.25 s (231525 samples
 *     at 44100). Each chunk is an independently encoded stream, so
 *     each has its own OpusHead pre-skip (312 samples at 48 kHz =
 *     6.5 ms). Opus decodes at 48000 Hz regardless of the 44100 Hz
 *     source, so all phone-side position math is in 48000 Hz
 *     samples; only the chunk START conversion uses 44100.
 *
 * Alignment invariant: chunk N's PCM is trimmed to exactly
 * (start[N+1] - start[N]) converted to 48 kHz samples (nominal
 * 252000 = 5.25 s) so chunk boundaries land exactly on their
 * server starts. The final chunk plays as-is (no successor, and a
 * trailing <=20 ms tail is inaudible). Per-chunk pre-skip is
 * dropped by decoding each chunk with a fresh decoder instance
 * (MediaCodec honors OpusHead pre-skip at stream start), which
 * removes the systematic +6.5 ms-per-chunk drift at the source;
 * the session sync loop then only handles residual/network drift.
 * Fresh-decoder cost (~71 creations per song, a few ms each,
 * pipelined on the decode thread while earlier PCM plays) is
 * accepted deliberately; a single-decoder + manual pre-skip-drop
 * optimization is allowed later ONLY if device measurement shows
 * decoder creation causing gaps.
 *
 * Hold gate: the player starts HELD (track playing silence). The
 * session releases the hold only after the original audio is
 * muted and the playhead is skipped to the video position, so no
 * karaoke sample is ever audible before the mute, and no
 * pre-position audio ever plays. Seeking is skip-forward only:
 * skipToSec drops queued PCM before the target and re-baselines
 * the audio clock. Playing audio BACKWARD (karaoke ahead of video)
 * is impossible from a consumed queue, so the session handles that
 * case by pausing karaoke until video catches up, never by seeking.
 */
public class KaraokePlayer {

    /** Fatal player errors (decode/write thread death). The session
     *  fails cleanly on these; a single bad chunk used to vanish
     *  silently, which made crashes impossible to understand. */
    public interface Listener {
        void onPlayerError(String message, Throwable t);
        /** v26: a chunk finished decoding (after its arrival). */
        void onChunkDecoded(int seq);
    }

    public static final int OUT_RATE = 48000;
    private static final int NOMINAL_CHUNK_SAMPLES_48K = 252000; // 5.25 s
    private static final int QUEUE_STARVE_TIMEOUT_MS = 2000;

    /** One decoded chunk, aligned to an absolute start time.
     *  The backing PCM (stage 2) shares the instrumental's seq,
     *  start and play position; it is attached whenever its own
     *  decode lands, including after the chunk was taken. */
    private static class PcmChunk {
        final int seq;
        final double startSec;
        final short[] pcm; // interleaved stereo, 48 kHz
        volatile short[] backingPcm;
        volatile int backingCapFrames;
        int pos; // next sample frame to write
        int capFrames; // trim cap (nominal chunk length); see trimChunk
        PcmChunk(int seq, double startSec, short[] pcm) {
            this.seq = seq;
            this.startSec = startSec;
            this.pcm = pcm;
            this.capFrames = pcm.length / 2;
            this.backingCapFrames = 0;
        }
        double endSec() {
            return startSec + capFrames / (double) OUT_RATE;
        }
        int framesLeft() {
            return capFrames - pos;
        }
    }

    private final Listener listener;

    private final TreeMap<Integer, PcmChunk> reorder = new TreeMap<>();
    /** Backing chunks decoded before the writer takes their
     *  instrumental twin (keyed by the shared seq). */
    private final TreeMap<Integer, PcmChunk> backingReorder = new TreeMap<>();
    /** v54 whole-song Opus cache: every received chunk's raw
     *  bytes, keyed by seq. ~90KB/chunk/stream, ~7MB for a
     *  5-minute song — small enough to keep uncapped, and it
     *  makes backward seeks instant (re-decode from cache in
     *  ~300ms instead of re-fetching). Guarded by reorderLock. */
    private static final class CachedOpus {
        final long startSamples;
        final int sampleRate;
        byte[] instrumental;
        byte[] backing;
        CachedOpus(long startSamples, int sampleRate) {
            this.startSamples = startSamples;
            this.sampleRate = sampleRate;
        }
    }
    private final java.util.HashMap<Integer, CachedOpus> opusCache =
            new java.util.HashMap<>();
    private final Object reorderLock = new Object();
    /** Live BV mix switch, read per write block (instant). */
    private volatile boolean backingVocalsEnabled;

    /** Pitch-only key-change stage (null until start()). */
    private volatile KaraokeKeyShifter keyShifter;
    /** Key chosen before the player exists (applied in start()). */
    private volatile int pendingSemitones = 0;
    private int nextExpectedSeq = 0;
    private long reorderWaitStartMs = -1;
    /** v41: true once the server finished (done) or the socket
     *  closed. Only then may a missing chunk be skipped; while
     *  the stream is alive the writer waits (a hole breaks sync
     *  forever, silence just pauses it). 30s backstop bounds a
     *  silent stall. */
    private volatile boolean streamDone = false;
    private static final long STREAM_ALIVE_WAIT_MS = 30_000;

    private AudioTrack track;
    private Thread decodeThread;
    private Thread backingDecodeThread;
    private Thread writeThread;
    /** v52 writer diagnostics (one I-line per ~5s). */
    private long writeDiagBlocks;
    private long writeDiagFrames;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean paused;
    /** True until the session releases after mute + skip. */
    private volatile boolean held = true;
    /** Playhead floor set by skipToSec; chunks at/before it are
     *  dropped when taken. -1 when no floor is active. */
    private volatile double playheadFloorSec = -1;
    private volatile PcmChunk currentChunk;

    // Audio clock: frames actually played (48 kHz domain).
    private long basePlayedFrames;
    private long trackStartHeadFrames;

    private volatile int queuedChunks;
    private volatile long bufferedFrames;
    /** Last setSpeedFactor value, for change-only logs. */
    private volatile float lastSpeedFactor = 1.0f;

    public KaraokePlayer(Listener listener) {
        this.listener = listener;
    }

    private void playerError(String message, Throwable t) {
        KaraokeLog.e("player error: " + message, t);
        if (listener != null) {
            try {
                listener.onPlayerError(message, t);
            } catch (Exception e) {
                KaraokeLog.e("player error listener threw", e);
            }
        }
    }

    /** v52: separate decode lanes. Stage 2 doubled decode work
     *  (instrumental + backing per seq) on ONE thread, so the
     *  writer starved waiting for the next instrumental while
     *  the thread was busy on a backing decode — head stalled,
     *  drift grew, sync hard-seeked in circles, ~silence.
     *  Instrumental now has its own thread and can never wait
     *  behind a backing decode; backing lands in parallel and
     *  attaches (possibly mid-chunk) when ready. */
    private final BlockingQueue<Runnable> decodeTasks =
            new LinkedBlockingQueue<>();
    private final BlockingQueue<Runnable> backingDecodeTasks =
            new LinkedBlockingQueue<>();

    /** Enqueue one server chunk. Called from the WS reader thread;
     *  decode happens on that stream's decode thread. Never
     *  blocks the reader beyond a queue offer. Safe to call
     *  before start(): tasks run once the threads start. */
    public void queueChunk(String stream, int seq, long startSamples,
            int sampleRate, byte[] opusOggBytes) {
        final boolean isBacking = "backing".equals(stream);
        // v54: cache raw bytes for instant backward seeks
        // (cheap ref hold on the WS reader thread; the decode
        // task below does the expensive work on its lane).
        synchronized (reorderLock) {
            CachedOpus cached = opusCache.get(seq);
            if (cached == null) {
                cached = new CachedOpus(startSamples, sampleRate);
                opusCache.put(seq, cached);
            }
            if (isBacking) {
                cached.backing = opusOggBytes;
            } else {
                cached.instrumental = opusOggBytes;
            }
        }
        Runnable task = () -> {
            try {
                // v54 dup-drop: reconnect replays (and any dup)
                // resend seqs the writer already passed — decode
                // would burn ~300ms each for audio that can never
                // play. Instrumental older than next-expected can
                // never be taken; backing gets one extra seq so
                // the CURRENT chunk's late backing still lands.
                synchronized (reorderLock) {
                    int cutoff = isBacking ? nextExpectedSeq - 1
                            : nextExpectedSeq;
                    if (seq < cutoff) {
                        KaraokeLog.d("player: stale "
                                + (isBacking ? "backing" : "chunk")
                                + " seq=" + seq + " dropped (writer at "
                                + nextExpectedSeq + ")");
                        return;
                    }
                }
                long t0 = System.currentTimeMillis();
                double startSec = startSamples / (double) sampleRate;
                short[] pcm = decodeOggOpusChunk(seq, opusOggBytes);
                long decodeMs = System.currentTimeMillis() - t0;
                if (pcm == null || pcm.length == 0) {
                    if (!isBacking) {
                        KaraokeLog.w("player: chunk seq=" + seq
                                + " decoded to empty PCM, dropped");
                        return;
                    }
                    // A silent backing chunk must not stall the
                    // pair-wait: synthesize the nominal silence.
                    pcm = new short[NOMINAL_CHUNK_SAMPLES_48K * 2];
                    KaraokeLog.w("player: backing seq=" + seq
                            + " empty, using silence");
                }
                // v52 diagnosis: RMS proves the PCM is real
                // audio, not silence (music ~500-8000,
                // silence ~0-10). Sampled 1/64 to stay cheap.
                double sumSq = 0;
                int samples = 0;
                for (int i = 0; i < pcm.length; i += 64) {
                    int s = pcm[i];
                    sumSq += (double) s * s;
                    samples++;
                }
                int rms = samples > 0
                        ? (int) Math.sqrt(sumSq / samples) : 0;
                PcmChunk c = new PcmChunk(seq, startSec, pcm);
                boolean paired = false;
                synchronized (reorderLock) {
                    if (isBacking) {
                        PcmChunk current = currentChunk;
                        PcmChunk queued = reorder.get(seq);
                        PcmChunk target = (current != null
                                && current.seq == seq) ? current : queued;
                        if (target != null) {
                            target.backingPcm = pcm;
                            target.backingCapFrames = pcm.length / 2;
                            paired = true;
                        } else {
                            backingReorder.put(seq, c);
                        }
                    } else {
                        reorder.put(seq, c);
                        queuedChunks++;
                        paired = true;
                    }
                    reorderLock.notifyAll();
                }
                KaraokeLog.d("player: chunk decoded stream=" + stream
                        + " seq=" + seq
                        + " startSec=" + String.format("%.2f", startSec)
                        + " frames=" + (pcm.length / 2)
                        + " rms=" + rms
                        + " decodeMs=" + decodeMs);
                // v26: wake the gate — decode finishes after
                // arrival, and arrivals may stall (403 wall).
                // Guarded: listener trouble never kills decode.
                if (paired) {
                    try {
                        if (listener != null) listener.onChunkDecoded(seq);
                    } catch (Exception e) {
                        KaraokeLog.e("player: onChunkDecoded threw", e);
                    }
                }
            } catch (Exception e) {
                // A decode failure used to vanish silently. Now it
                // logs with the trace and fails the session: a bad
                // chunk means the stream itself is broken, and a
                // silent gap would be impossible to explain.
                playerError("decode failed for chunk seq=" + seq, e);
            }
        };
        if (isBacking) {
            backingDecodeTasks.offer(task);
        } else {
            decodeTasks.offer(task);
        }
    }

    /** Live background-vocal mix switch. Read by the writer
     *  for every block, so toggling BV mid-song takes effect
     *  on the next ~4096 frames with no restart or re-request. */
    public void setBackingVocalsEnabled(boolean enabled) {
        if (backingVocalsEnabled != enabled) {
            KaraokeLog.i("player: backing vocals "
                    + (enabled ? "ON (mixed live)" : "OFF"));
        }
        backingVocalsEnabled = enabled;
    }

    public boolean isBackingVocalsEnabled() {
        return backingVocalsEnabled;
    }

    // ---- key change (instrumental transposition) ----

    /**
     * Shift the instrumental pitch by {@code semitones}, keeping
     * tempo. 0 = original key (DSP fully bypassed). Safe to call
     * live: the shift takes effect within one block (~85 ms) and the
     * sample count never changes, so sync and chunk tiling are
     * untouched. See KaraokeKeyShifter and docs/KEYCHANGE.md.
     */
    public void setKeySemitones(int semitones) {
        KaraokeKeyShifter ks = keyShifter;
        if (ks == null) {
            pendingSemitones = semitones;
            return;
        }
        ks.setSemitones(semitones);
    }

    public int getKeySemitones() {
        KaraokeKeyShifter ks = keyShifter;
        return ks == null ? pendingSemitones : ks.getSemitones();
    }

    /** Diagnostics: DSP faults so far (0 = healthy). */
    public long getKeyFailures() {
        KaraokeKeyShifter ks = keyShifter;
        return ks == null ? 0 : ks.getFailures();
    }

    /** Creates the track and threads. The track plays silence until
     *  releaseHold() is called (see class doc). */
    public void start() {
        if (running.getAndSet(true)) return;
        // Pitch-only key-change stage. Created before the writer
        // thread starts so the first block already sees it.
        keyShifter = new KaraokeKeyShifter(OUT_RATE, 2);
        keyShifter.setSemitones(pendingSemitones);
        int minBuf = AudioTrack.getMinBufferSize(OUT_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(OUT_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                // v44: ~0.36s track buffer, not 1.0s — the big
                // buffer delayed speaker output (constant drift)
                // and made seeks play out stale audio first.
                .setBufferSizeInBytes(Math.max(minBuf * 3, OUT_RATE))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        basePlayedFrames = 0;
        trackStartHeadFrames = 0;
        try {
            track.setVolume(1.0f);
        } catch (Exception e) {
            KaraokeLog.w("player: setVolume(1.0) failed", e);
        }
        track.play();
        KaraokeLog.i("player: AudioTrack created rate=" + OUT_RATE
                + " minBuf=" + minBuf + " bufferBytes="
                + Math.max(minBuf * 3, OUT_RATE)
                + " state=" + track.getState()
                + " playState=" + track.getPlayState()
                + " (playing-held until hold release)");
        decodeThread = new Thread(() -> decodeLoop(decodeTasks),
                "karaoke-decode");
        backingDecodeThread = new Thread(
                () -> decodeLoop(backingDecodeTasks),
                "karaoke-decode-bv");
        writeThread = new Thread(this::writeLoop, "karaoke-write");
        decodeThread.start();
        backingDecodeThread.start();
        writeThread.start();
    }

    /** Lets queued PCM reach the speaker. The session calls this
     *  only after mute + skip, in that order. */
    public void releaseHold() {
        if (held) {
            KaraokeLog.i("player: hold released, audio audible");
        }
        held = false;
        // v63: fail-loud if the track is not actually PLAYING — an
        // idle/not-initialized track would otherwise hold silence
        // in the fire-and-forget start path (matches the cold-
        // open silent-start corner of the video-down symptom).
        if (track != null) {
            try {
                int playState = track.getPlayState();
                if (playState
                        != android.media.AudioTrack.PLAYSTATE_PLAYING) {
                    track.play();
                    KaraokeLog.i("player: track playState=" + playState
                            + " at hold release -> play() issued");
                }
            } catch (Exception e) {
                KaraokeLog.w("player: hold-release track check failed", e);
            }
        }
    }

    public void pause() {
        if (!paused) {
            KaraokeLog.i("player: pause (track paused)");
        }
        paused = true;
        if (track != null) {
            try {
                track.pause();
            } catch (IllegalStateException e) {
                KaraokeLog.w("player: track.pause() failed", e);
            }
        }
    }

    public void resume() {
        if (paused) {
            KaraokeLog.i("player: resume (track playing)");
        }
        paused = false;
        if (track != null) {
            try {
                track.play();
            } catch (IllegalStateException e) {
                KaraokeLog.w("player: track.play() failed", e);
            }
        }
    }

    /** Position of the audio actually coming out of the speaker,
     *  seconds, 48 kHz domain. */
    public double audioPositionSec() {
        if (track == null) return basePlayedFrames / (double) OUT_RATE;
        int head = track.getPlaybackHeadPosition();
        return (basePlayedFrames + (head - trackStartHeadFrames))
                / (double) OUT_RATE;
    }

    /** Seconds of decoded PCM queued ahead of the play head. */
    public double bufferedSec() {
        return bufferedFrames / (double) OUT_RATE;
    }

    /** Seconds of decoded PCM waiting in the reorder queue
     *  (decoded but not yet taken by the writer). Unlike
     *  bufferedSec() — which only counts PCM the writer has
     *  taken, and therefore reads 0.00 while held — this
     *  grows as soon as chunks decode, so it is the gate
     *  metric. v13: fixes the playback-start deadlock
     *  (Bug C): the writer sleeps while held, so
     *  bufferedSec() can never reach the gate threshold,
     *  but queuedSec() can. Empty queue -> 0.0. */
    public double queuedSec() {
        synchronized (reorderLock) {
            long frames = 0;
            for (PcmChunk c : reorder.values()) {
                frames += c.framesLeft();
            }
            return frames / (double) OUT_RATE;
        }
    }

    /** True if any queued chunk covers sec (startSec <= sec <
     *  endSec). At gate time no chunk has been taken by the
     *  writer yet (held), so capFrames is still the full
     *  decoded length and endSec is the chunk's true end:
     *  trimChunk (nextChunk) and skipToSec (post-gate) have
     *  not run yet. Empty queue -> false. */
    public boolean coversSec(double sec) {
        synchronized (reorderLock) {
            for (PcmChunk c : reorder.values()) {
                if (c.startSec <= sec && sec < c.endSec()) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Speed nudge, 1.0 = normal. Plan band is +/-2-5%; the session
     *  passes values in that band. Slight pitch shift is accepted
     *  by the plan. */
    public void setSpeedFactor(float factor) {
        if (factor != lastSpeedFactor) {
            KaraokeLog.d("player: speed factor " + lastSpeedFactor
                    + " -> " + factor);
            lastSpeedFactor = factor;
        }
        if (track != null) {
            try {
                track.setPlaybackRate((int) (OUT_RATE * factor));
            } catch (IllegalStateException e) {
                KaraokeLog.w("player: setPlaybackRate failed", e);
            }
        }
    }

    /**
     * Skip the playhead forward to targetSec. Drops queued PCM at
     * or before the target, partial-drops the chunk containing it,
     * and re-baselines the audio clock so audioPositionSec()
     * reports the target immediately. Never moves the playhead
     * backward: if targetSec is behind the current audio position,
     * this is a no-op and the session must pause instead (see
     * class doc). Safe to call before releaseHold().
     */
    public void skipToSec(double targetSec) {
        double fromSec = -1;
        try {
            fromSec = audioPositionSec();
        } catch (Exception ignored) {
            // Clock read is best-effort for the log line only.
        }
        KaraokeLog.i("player: skipToSec " + String.format("%.2f", fromSec)
                + "s -> " + String.format("%.2f", targetSec) + "s");
        if (track != null
                && targetSec <= audioPositionSec()
                && basePlayedFrames > 0) {
            return; // backward skip refused; session pauses instead
        }
        synchronized (reorderLock) {
            // Drop or trim queued chunks. v52: queued (not yet
            // taken) chunks were NEVER added to bufferedFrames
            // (nextChunk adds on take), so subtracting them
            // drove bufferedSec negative without end. Only the
            // taken currentChunk adjusts the counter below.
            java.util.Iterator<java.util.Map.Entry<Integer, PcmChunk>> it =
                    reorder.entrySet().iterator();
            while (it.hasNext()) {
                PcmChunk c = it.next().getValue();
                if (c.endSec() <= targetSec) {
                    it.remove();
                } else if (c.startSec < targetSec) {
                    int skip = (int) ((targetSec - c.startSec) * OUT_RATE);
                    c.pos = Math.min(skip, c.capFrames);
                }
            }
            // v55: recount (landings ++, takes --; drops bypass
            // both, so recount after any drop). reorder holds
            // instrumentals only, matching the counter.
            queuedChunks = reorder.size();
            // Drop backing chunks at/before the target too;
            // partial ones need no trim (the writer indexes
            // backing PCM by the instrumental's position).
            java.util.Iterator<java.util.Map.Entry<Integer, PcmChunk>> bit =
                    backingReorder.entrySet().iterator();
            while (bit.hasNext()) {
                PcmChunk b = bit.next().getValue();
                if (b.endSec() <= targetSec) bit.remove();
            }
            // Adjust the chunk currently being written, if any.
            PcmChunk cur = currentChunk;
            if (cur != null) {
                if (cur.endSec() <= targetSec) {
                    bufferedFrames -= cur.framesLeft();
                    cur.pos = cur.capFrames;
                } else if (cur.startSec < targetSec) {
                    int skip = (int) ((targetSec - cur.startSec) * OUT_RATE);
                    bufferedFrames -= Math.min(skip, cur.framesLeft());
                    cur.pos = Math.min(skip, cur.capFrames);
                }
            }
            // v52: dropped queued chunks must not strand the
            // writer: nextExpectedSeq still pointed at a dropped
            // seq, so nextChunk waited 30s for audio that would
            // never come (head stalled, drift grew, more seeks,
            // worse). Jump it to the first surviving chunk.
            if (!reorder.isEmpty()
                    && nextExpectedSeq < reorder.firstKey()) {
                KaraokeLog.i("player: skip advances nextExpectedSeq "
                        + nextExpectedSeq + " -> " + reorder.firstKey());
                nextExpectedSeq = reorder.firstKey();
                reorderWaitStartMs = -1;
            }
            if (bufferedFrames < 0) bufferedFrames = 0;
            playheadFloorSec = targetSec;
            reorderLock.notifyAll();
        }
        // Re-baseline the clock: position becomes the target plus
        // whatever plays after this point.
        if (track != null) {
            basePlayedFrames = (long) (targetSec * OUT_RATE);
            trackStartHeadFrames = track.getPlaybackHeadPosition();
        } else {
            basePlayedFrames = (long) (targetSec * OUT_RATE);
        }
    }

    /** v54 reconnect hint: first seq the writer still needs
     *  (server replays from here; older seqs are dup-dropped). */
    public int resumptionSeq() {
        synchronized (reorderLock) {
            return Math.max(0, nextExpectedSeq - 1);
        }
    }

    /** Discards the ~0.36s of already-queued track audio so a
     *  jump cuts clean instead of playing stale samples first.
     *  Pause-flush-play ordering per AudioTrack docs. */
    private void flushTrack() {
        if (track == null) return;
        try {
            boolean stayQuiet = held || paused;
            track.pause();
            track.flush();
            if (!stayQuiet) track.play();
            KaraokeLog.d("player: track flushed for jump");
        } catch (Exception e) {
            KaraokeLog.w("player: track flush failed", e);
            try {
                track.play();
            } catch (Exception ignored) {
            }
        }
    }

    /** v54 user-seek jump (debounced bar release), BOTH
     *  directions. Forward within coverage: skip + flush the
     *  stale track buffer so the cut is sample-clean.
     *  Backward: true rewind — drop the writer state, jump
     *  nextExpected back, re-decode from the whole-song Opus
     *  cache (~300ms/chunk, both lanes in parallel, so
     *  playback resumes in ~half a second and the tail
     *  rebuilds while playing). Always re-baselines the clock
     *  and flushes. Forward beyond coverage must NOT call
     *  this — the session rebuffers until covered first. */
    public void seekJump(double targetSec) {
        double fromSec;
        try {
            fromSec = audioPositionSec();
        } catch (Exception ignored) {
            fromSec = -1;
        }
        KaraokeLog.i("player: seekJump " + String.format("%.2f", fromSec)
                + "s -> " + String.format("%.2f", targetSec) + "s");
        if (targetSec >= fromSec) {
            skipToSec(targetSec);
            flushTrack();
            return;
        }
        // Backward: find the cached seq containing the target.
        int targetSeq = -1;
        synchronized (reorderLock) {
            for (java.util.Map.Entry<Integer, CachedOpus> e
                    : opusCache.entrySet()) {
                CachedOpus co = e.getValue();
                if (co.instrumental == null) continue;
                double s = co.startSamples / (double) co.sampleRate;
                double end = s + 5.26; // nominal length + slack
                if (s <= targetSec && targetSec < end) {
                    targetSeq = e.getKey();
                    break;
                }
            }
        }
        if (targetSeq < 0) {
            // Before everything cached (cache starts at 0, so
            // this means a corrupt target) — hold via the floor
            // instead of inventing audio.
            KaraokeLog.w("player: seekJump backward target "
                    + String.format("%.2f", targetSec)
                    + "s not in cache; holding");
            skipToSec(targetSec);
            flushTrack();
            return;
        }
        synchronized (reorderLock) {
            currentChunk = null;
            reorder.clear();
            backingReorder.clear();
            bufferedFrames = 0;
            queuedChunks = 0;
            nextExpectedSeq = targetSeq;
            reorderWaitStartMs = -1;
            playheadFloorSec = targetSec;
            reorderLock.notifyAll();
        }
        if (track != null) {
            basePlayedFrames = (long) (targetSec * OUT_RATE);
            try {
                trackStartHeadFrames = track.getPlaybackHeadPosition();
            } catch (Exception ignored) {
            }
        } else {
            basePlayedFrames = (long) (targetSec * OUT_RATE);
        }
        flushTrack();
        // Re-decode everything cached from the target onward.
        // Both lanes run in parallel; the writer resumes as
        // soon as the first chunk lands (~600ms), the tail
        // rebuilds (~300ms per 5.25s audio) far ahead of play.
        java.util.TreeMap<Integer, CachedOpus> tail =
                new java.util.TreeMap<>();
        synchronized (reorderLock) {
            for (java.util.Map.Entry<Integer, CachedOpus> e
                    : opusCache.entrySet()) {
                if (e.getKey() >= targetSeq) {
                    tail.put(e.getKey(), e.getValue());
                }
            }
        }
        int requeued = 0;
        for (java.util.Map.Entry<Integer, CachedOpus> e
                : tail.entrySet()) {
            CachedOpus co = e.getValue();
            if (co.instrumental != null) {
                queueChunk("instrumental", e.getKey(), co.startSamples,
                        co.sampleRate, co.instrumental);
                requeued++;
            }
            if (co.backing != null) {
                queueChunk("backing", e.getKey(), co.startSamples,
                        co.sampleRate, co.backing);
            }
        }
        KaraokeLog.i("player: seekJump rewound to seq=" + targetSeq
                + " (requeued " + requeued + " cached chunks)");
    }

    /** End (seconds, song timeline) of the furthest decoded
     *  karaoke audio currently held — the vocal-removed
     *  "buffered" extent the seek bar shows light blue while
     *  karaoke is active. -1 when nothing is decoded yet. */
    public double coverageEndSec() {
        synchronized (reorderLock) {
            double end = -1;
            PcmChunk cur = currentChunk;
            if (cur != null) end = Math.max(end, cur.endSec());
            for (PcmChunk c : reorder.values()) {
                end = Math.max(end, c.endSec());
            }
            return end;
        }
    }

    /** Whole instant-karaoke range {startSec, endSec} from the
     *  Opus cache (v54): every cached chunk re-decodes in ~0.5s,
     *  so this whole span is "instant" on the seek bar —
     *  including the already-played part behind the playhead.
     *  Null when nothing is cached yet. */
    public double[] cachedCoverageSec() {
        synchronized (reorderLock) {
            if (opusCache.isEmpty()) return null;
            double start = Double.MAX_VALUE;
            double end = -1;
            for (CachedOpus co : opusCache.values()) {
                if (co.instrumental == null) continue;
                double s = co.startSamples / (double) co.sampleRate;
                start = Math.min(start, s);
                end = Math.max(end, s + 5.25);
            }
            if (end < 0) return null;
            return new double[] {start, end};
        }
    }

    /** True when nothing is left to play (v30 song-end check). */
    public boolean isDrained() {
        synchronized (reorderLock) {
            PcmChunk cur = currentChunk;
            return reorder.isEmpty()
                    && (cur == null || cur.framesLeft() == 0);
        }
    }

    public void stop() {
        KaraokeLog.i("player: stop (threads interrupted, track released)");
        // Remember the key so a restart keeps it, then drop the DSP
        // state (a new session primes a fresh shifter).
        pendingSemitones = getKeySemitones();
        keyShifter = null;
        running.set(false);
        if (decodeThread != null) decodeThread.interrupt();
        if (backingDecodeThread != null) backingDecodeThread.interrupt();
        if (writeThread != null) writeThread.interrupt();
        bufferedFrames = 0;
        synchronized (reorderLock) {
            reorder.clear();
            backingReorder.clear();
            opusCache.clear();
            reorderLock.notifyAll();
        }
        currentChunk = null;
        if (track != null) {
            try {
                track.stop();
            } catch (IllegalStateException e) {
                KaraokeLog.w("player: track.stop() failed", e);
            }
            track.release();
            track = null;
        }
    }

    private void decodeLoop(BlockingQueue<Runnable> queue) {
        while (running.get()) {
            try {
                Runnable t = queue.take();
                t.run();
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void writeLoop() {
        while (running.get()) {
            try {
                if (held || paused) {
                    Thread.sleep(20);
                    continue;
                }
                if (currentChunk == null || currentChunk.framesLeft() == 0) {
                    currentChunk = nextChunk();
                    if (currentChunk == null) continue;
                }
                PcmChunk cur = currentChunk;
                int frames = Math.min(cur.framesLeft(), 4096);
                short[] buf = new short[frames * 2];
                // Live BV mix: instrumental + backing (gain 1.0
                // preserves the stems' original mix balance;
                // clamped to s16). Backing may attach mid-chunk
                // when its decode lands after the writer started.
                // v51: buf is a fresh 4096-frame block, so the
                // destination index is frame*2 — NOT src (the
                // chunk-absolute index). v50 wrote buf[src] and
                // crashed with AIOOBE on the second block of
                // every chunk ("write loop failed", no audio).
                // BV-off fast path skips the per-sample loop.
                short[] backing = cur.backingPcm;
                int backingCap = cur.backingCapFrames;
                boolean mixBacking = backingVocalsEnabled
                        && backing != null;
                if (!mixBacking) {
                    System.arraycopy(cur.pcm, cur.pos * 2,
                            buf, 0, frames * 2);
                } else {
                    for (int frame = 0; frame < frames; frame++) {
                        int srcFrame = cur.pos + frame;
                        int src = srcFrame * 2;
                        int dst = frame * 2;
                        int left = cur.pcm[src];
                        int right = cur.pcm[src + 1];
                        if (srcFrame < backingCap
                                && src + 1 < backing.length) {
                            left += backing[src];
                            right += backing[src + 1];
                        }
                        buf[dst] = (short) Math.max(-32768,
                                Math.min(32767, left));
                        buf[dst + 1] = (short) Math.max(-32768,
                                Math.min(32767, right));
                    }
                }
                cur.pos += frames;
                // Key change: pitch-only, same sample count. Last
                // DSP step, immediately before the speaker, so the
                // BV mix above is shifted as one signal.
                KaraokeKeyShifter ks = keyShifter;
                if (ks != null) ks.process(buf, buf.length);
                track.write(buf, 0, buf.length);
                bufferedFrames -= frames;
                // v52 diagnosis: prove the writer reaches the
                // speaker (head advance + underruns + PCM level).
                // One I-line per ~5s; cheap sampled RMS.
                writeDiagFrames += frames;
                if (++writeDiagBlocks % 60 == 0) {
                    try {
                        double wSum = 0;
                        int wN = 0;
                        for (int i = 0; i < buf.length; i += 64) {
                            int s = buf[i];
                            wSum += (double) s * s;
                            wN++;
                        }
                        int wRms = wN > 0
                                ? (int) Math.sqrt(wSum / wN) : 0;
                        KaraokeLog.i("player: writer alive frames="
                                + writeDiagFrames + " head="
                                + track.getPlaybackHeadPosition()
                                + " underruns=" + track.getUnderrunCount()
                                + " blockRms=" + wRms
                                + " bv=" + backingVocalsEnabled);
                    } catch (Exception e) {
                        KaraokeLog.w("player: writer diag failed", e);
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                // The writer thread died: log with the trace and
                // fail the session instead of spinning silently.
                currentChunk = null;
                playerError("write loop failed", e);
                return;
            }
        }
    }

    private void trimChunk(PcmChunk c) {
        // Nominal 5.25 s unless the successor's start is already
        // known (chunks arrive ~15x real-time, so it usually is).
        int nominal = NOMINAL_CHUNK_SAMPLES_48K;
        synchronized (reorderLock) {
            PcmChunk next = reorder.get(c.seq + 1);
            if (next != null) {
                nominal = (int) Math.round(
                        (next.startSec - c.startSec) * OUT_RATE);
            }
        }
        if (c.pcm.length / 2 > nominal) {
            c.capFrames = nominal;
        }
        if (c.backingPcm != null && c.backingCapFrames > nominal) {
            c.backingCapFrames = nominal;
        }
    }

    /** Applies the playhead floor to a freshly taken chunk.
     *  Returns true if the chunk still has playable frames.
     *  v53: must NOT touch bufferedFrames — the chunk is
     *  counted by nextChunk AFTER this returns (post-floor
     *  framesLeft), so subtracting here leaked -2s per floored
     *  chunk (the negative bufferedSec in the logs). */
    private boolean applyFloor(PcmChunk c) {
        double floor = playheadFloorSec;
        if (floor < 0) return true;
        if (c.endSec() <= floor) {
            return false;
        }
        if (c.startSec < floor) {
            int skip = (int) ((floor - c.startSec) * OUT_RATE);
            c.pos = Math.min(skip, c.capFrames);
        }
        playheadFloorSec = -1; // floor consumed by this chunk
        return c.framesLeft() > 0;
    }

    /** v41: the stream ended (done/close) — missing chunks may
     *  now be skipped instead of waited on. */
    public void setStreamDone(boolean done) {
        streamDone = done;
        synchronized (reorderLock) {
            reorderLock.notifyAll();
        }
    }

    private PcmChunk nextChunk() throws InterruptedException {
        synchronized (reorderLock) {
            while (running.get()) {
                PcmChunk c = null;
                if (nextExpectedSeq == Integer.MAX_VALUE) {
                    // Post-seek fallback path (unused after the
                    // skipToSec redesign; kept for safety).
                    if (!reorder.isEmpty()) {
                        Integer first = reorder.firstKey();
                        c = reorder.remove(first);
                        nextExpectedSeq = first + 1;
                        queuedChunks--;
                    }
                } else if (reorder.containsKey(nextExpectedSeq)) {
                    PcmChunk inst = reorder.get(nextExpectedSeq);
                    PcmChunk backing = backingReorder.get(nextExpectedSeq);
                    if (inst.backingPcm == null && backing != null) {
                        inst.backingPcm = backing.pcm;
                        inst.backingCapFrames = backing.pcm.length / 2;
                        backingReorder.remove(nextExpectedSeq);
                    }
                    // v54 seamless BV: NEVER wait for backing
                    // (play instrumental immediately, mix backing
                    // in whenever it lands — even mid-chunk, which
                    // the writer already supports). Any pair-wait
                    // could only stall starts and seeks.
                    c = reorder.remove(nextExpectedSeq);
                    nextExpectedSeq++;
                    queuedChunks--;
                    reorderWaitStartMs = -1;
                } else {
                    if (reorderWaitStartMs < 0) {
                        reorderWaitStartMs = System.currentTimeMillis();
                    } else if (System.currentTimeMillis()
                            - reorderWaitStartMs > (streamDone
                                    ? QUEUE_STARVE_TIMEOUT_MS
                                    : STREAM_ALIVE_WAIT_MS)) {
                        // v55 gap jump: skip to the first AVAILABLE
                        // chunk, not +1. A hole of N missing seqs cost
                        // N x 30s of silence before (each +1 waited
                        // out the full backstop again); the hole is
                        // skipped either way and the drift loop heals
                        // it, so skip it at once. An EMPTY queue means
                        // the next chunk is genuinely in flight, so
                        // only then step +1 and keep waiting.
                        if (!reorder.isEmpty()) {
                            int first = reorder.firstKey();
                            KaraokeLog.w("player: chunk gap, jumping "
                                    + nextExpectedSeq + " -> " + first
                                    + (streamDone ? ""
                                            : " (stream alive)"));
                            nextExpectedSeq = first;
                        } else {
                            KaraokeLog.w("player: chunk gap, waiting for "
                                    + "in-flight seq=" + nextExpectedSeq
                                    + (streamDone ? ""
                                            : " (stream alive,"
                                            + " 30s backstop)"));
                            nextExpectedSeq++;
                        }
                        reorderWaitStartMs = -1;
                        continue;
                    }
                }
                if (c != null) {
                    // Trim to the successor's start when known, so
                    // boundaries land exactly on server starts.
                    trimChunk(c);
                    if (!applyFloor(c)) {
                        continue; // fully before the floor: next chunk
                    }
                    bufferedFrames += c.framesLeft();
                    return c;
                }
                reorderLock.wait(50);
            }
            return null;
        }
    }

    // ---- Ogg-Opus demux + MediaCodec decode (one chunk) ----

    /** v12: 8-byte native-order long for Opus csd-1/csd-2. */
    private static ByteBuffer nativeLong(long v) {
        return ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
                .putLong(0, v);
    }

    /** Decodes one Ogg-Opus chunk to interleaved stereo PCM16.
     *  Returns null on malformed input. A fresh decoder per chunk
     *  is deliberate (see class doc): each chunk is an independent
     *  stream with its own pre-skip. */
    private short[] decodeOggOpusChunk(int seq, byte[] ogg) throws Exception {
        OggReader reader = new OggReader(ogg);
        byte[] opusHead = null;
        byte[] opusTags = null;
        ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
        MediaCodec codec = null;
        int packetsFed = 0;
        int inputTimeouts = 0;
        DrainStats stats = new DrainStats();
        String outFormat = "unknown";
        try {
            byte[] packet;
            long ptsUs = 0;
            while ((packet = reader.nextPacket()) != null) {
                if (packet.length >= 8
                        && new String(packet, 0, 8, "US-ASCII")
                                .equals("OpusHead")) {
                    opusHead = packet;
                    continue;
                }
                if (packet.length >= 8
                        && new String(packet, 0, 8, "US-ASCII")
                                .equals("OpusTags")) {
                    opusTags = packet;
                    continue;
                }
                if (opusHead == null) continue; // no header yet
                if (codec == null) {
                    MediaFormat fmt = MediaFormat.createAudioFormat(
                            "audio/opus", OUT_RATE, 2);
                    fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536);
                    // v12: Android's Opus decoder does NOT take
                    // OpusTags in csd-1. Per the MediaCodec Opus
                    // config: csd-0 = OpusHead, csd-1 = pre-skip
                    // (codec delay) in NANOSECONDS as an 8-byte
                    // native-order long, csd-2 = seek pre-roll
                    // in nanoseconds. v10/v11 put ASCII
                    // "OpusTags" in csd-1, which the decoder
                    // read as a ~8e18 ns delay and trimmed
                    // EVERY sample as delay — valid format,
                    // 263 packets accepted, zero output, no
                    // error. OpusHead bytes 10-11 (LE) are the
                    // pre-skip in samples (312 -> 6,500,000 ns).
                    fmt.setByteBuffer("csd-0",
                            ByteBuffer.wrap(opusHead));
                    long preSkip = 0;
                    if (opusHead.length >= 12) {
                        preSkip = (opusHead[10] & 0xFF)
                                | ((opusHead[11] & 0xFF) << 8);
                    }
                    long delayNs = preSkip * 1_000_000_000L / 48_000L;
                    fmt.setByteBuffer("csd-1",
                            nativeLong(delayNs));
                    fmt.setByteBuffer("csd-2",
                            nativeLong(80_000_000L));
                    KaraokeLog.d("player: decoder created for chunk seq="
                            + seq);
                    codec = MediaCodec.createDecoderByType("audio/opus");
                    codec.configure(fmt, null, null, 0);
                    codec.start();
                    // v12: log which decoder the framework picked
                    // (software vs vendor) + the csd sizes, so
                    // the next diagnosis round knows what ran.
                    // Guarded: logging never breaks decode.
                    try {
                        KaraokeLog.d("player: decoder name="
                                + codec.getName()
                                + " csd sizes=" + opusHead.length
                                + "/8/8 for chunk seq=" + seq);
                    } catch (Exception e) {
                        // Logging must never break decode.
                    }
                }
                int inIdx = codec.dequeueInputBuffer(10_000);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                    inBuf.clear();
                    inBuf.put(packet);
                    codec.queueInputBuffer(inIdx, 0, packet.length,
                            ptsUs, 0);
                    ptsUs += 20_000; // Opus frames are 20 ms
                    packetsFed++;
                } else {
                    inputTimeouts++;
                }
                // v53: NON-BLOCKING mid-feed drain. The old 10ms
                // timeout waited the full 10ms after ~every one of
                // the 263 packets (~2.6s/chunk of pure timeout) —
                // the bulk of the 4s decodeMs that starved every
                // song start. Output ready now is collected; the
                // rest arrives in later iterations / final drain.
                drainCodec(codec, pcmOut, stats, 0);
            }
            if (codec != null) {
                boolean eosQueued = false;
                int inIdx = codec.dequeueInputBuffer(10_000);
                if (inIdx >= 0) {
                    codec.queueInputBuffer(inIdx, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    eosQueued = true;
                }
                // v11: only use the persistent EOS drain when EOS
                // was actually queued; otherwise fall back to the
                // normal drain so a missing input buffer cannot
                // cost a spurious 5s wait on every chunk.
                if (eosQueued) {
                    drainCodecUntilEos(codec, pcmOut, stats);
                } else {
                    drainCodec(codec, pcmOut, stats, 10_000);
                }
                try {
                    outFormat = String.valueOf(codec.getOutputFormat());
                } catch (Exception e) {
                    outFormat = "unknown";
                }
            }
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (IllegalStateException e) {
                    KaraokeLog.w("player: decoder stop failed for chunk "
                            + "seq=" + seq, e);
                }
                codec.release();
            }
        }
        byte[] raw = pcmOut.toByteArray();
        try {
            KaraokeLog.d("player: decode summary seq=" + seq
                    + " fed=" + packetsFed
                    + " inTimeouts=" + inputTimeouts
                    + " outBufs=" + stats.outBufs
                    + " outBytes=" + stats.outBytes
                    + " outFormat=" + outFormat);
        } catch (Exception e) {
            // Logging must never break decode.
        }
        short[] pcm = new short[raw.length / 2];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) ((raw[2 * i] & 0xFF)
                    | (raw[2 * i + 1] << 8));
        }
        return pcm;
    }

    /** Counting holder for drainCodec; behaviour unchanged, only counts. */
    private static class DrainStats {
        int outBufs;
        int outBytes;
    }

    /** Drains whatever output is ready. timeoutUs=0 polls
     *  (mid-feed: never wait); >0 waits once for the tail
     *  (final drain). Behaviour otherwise unchanged. */
    private void drainCodec(MediaCodec codec,
            ByteArrayOutputStream out, DrainStats stats,
            long timeoutUs) throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int outIdx = codec.dequeueOutputBuffer(info, timeoutUs);
            if (outIdx >= 0) {
                ByteBuffer buf = codec.getOutputBuffer(outIdx);
                byte[] tmp = new byte[info.size];
                buf.get(tmp);
                out.write(tmp, 0, tmp.length);
                stats.outBufs++;
                stats.outBytes += tmp.length;
                codec.releaseOutputBuffer(outIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            } else {
                break;
            }
        }
    }

    /** v11: persistent end-of-stream drain. Unlike the per-packet
     *  drain, this does NOT stop on the first output gap: a
     *  decoder that batches its output may need time to flush
     *  after EOS. Polls until the output EOS flag or ~2s total
     *  (v53: was 5s/10ms polls — the flush lands in ms, the
     *  rest was timeout burn inside the 4s decodeMs), then
     *  stops. Output timeouts and format changes (< 0) just
     *  keep polling until the deadline. */
    private void drainCodecUntilEos(MediaCodec codec,
            ByteArrayOutputStream out, DrainStats stats) throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            int outIdx = codec.dequeueOutputBuffer(info, 2_000);
            if (outIdx >= 0) {
                ByteBuffer buf = codec.getOutputBuffer(outIdx);
                byte[] tmp = new byte[info.size];
                buf.get(tmp);
                out.write(tmp, 0, tmp.length);
                stats.outBufs++;
                stats.outBytes += tmp.length;
                codec.releaseOutputBuffer(outIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }
    }

    /** Minimal Ogg page reader: yields exactly one packet per call,
     *  joining packets that span pages. The current page's segment
     *  table, next segment index, and data position are kept as
     *  reader state across calls, so a page holding several packets
     *  is consumed one packet at a time instead of glued into one
     *  oversized blob (which both exceeds the codec's input buffer
     *  and is invalid as a single MediaCodec input buffer). */
    private static class OggReader {
        private final byte[] data;
        private int pos; // offset of the next page header to load
        private ByteArrayOutputStream cur = new ByteArrayOutputStream();
        private int[] segTable = new int[0];
        private int segIdx;
        private int segDataPos;

        OggReader(byte[] data) {
            this.data = data;
        }

        /** Returns the next packet, or null at end of stream. */
        byte[] nextPacket() {
            while (true) {
                if (segIdx < segTable.length) {
                    int segLen = segTable[segIdx++];
                    if (segDataPos + segLen > data.length) return null;
                    cur.write(data, segDataPos, segLen);
                    segDataPos += segLen;
                    if (segLen < 255) {
                        byte[] pkt = cur.toByteArray();
                        cur.reset();
                        return pkt;
                    }
                    // else: packet continues — next segment, or the
                    // next page once this page's segments run out
                    // (cur is retained across pages).
                    continue;
                }
                // No segments left on the current page: load the
                // next page header at pos.
                if (pos + 27 > data.length) return null;
                if (!(data[pos] == 'O' && data[pos + 1] == 'g'
                        && data[pos + 2] == 'g'
                        && data[pos + 3] == 'S')) {
                    return null; // not an Ogg stream
                }
                int nSeg = data[pos + 26] & 0xFF;
                if (pos + 27 + nSeg > data.length) return null;
                segTable = new int[nSeg];
                int total = 0;
                for (int i = 0; i < nSeg; i++) {
                    segTable[i] = data[pos + 27 + i] & 0xFF;
                    total += segTable[i];
                }
                segDataPos = pos + 27 + nSeg;
                if (segDataPos + total > data.length) return null;
                pos = segDataPos + total;
                segIdx = 0;
            }
        }
    }
}
