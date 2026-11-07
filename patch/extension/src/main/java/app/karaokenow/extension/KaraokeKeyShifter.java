package app.karaokenow.extension;

/**
 * Key changer: pitch-only DSP stage between decode and AudioTrack.
 *
 * WHY THIS SHAPE (research, docs/KEYCHANGE.md):
 *   - Sonic is PICOLA/WSOLA with integer-only math. Pitch-only
 *     shifting (speed stays 1.0) leaves the sample count virtually
 *     unchanged, so chunk tiling, the drift loop and video sync are
 *     untouched. That is the whole reason this can live on the phone.
 *   - Quality is clean to about +-3..4 semitones and thins out past
 *     5, so the semitone value is FOLDED into (-6, +6] before it ever
 *     gets here (an octave up and an octave down are the same key).
 *   - ONE instance per session, fed continuously. Per-chunk instances
 *     would insert ~70 boundary discontinuities per song. Sonic's
 *     internal latency is therefore a single constant offset, padded
 *     at the very start only, never a per-chunk error.
 *
 * Bypass: semitones == 0 returns immediately, leaving the PCM
 * bit-identical to a non-key-change build. Turning the feature on can
 * never regress a normal session.
 */
public final class KaraokeKeyShifter {

    /** Hard musical limit; setSemitones clamps here. */
    public static final int MAX_SEMITONES = 6;

    private final int sampleRate;
    private final int channels;
    private Sonic sonic;
    private volatile int semitones = 0;
    /** Interleaved staging buffer for the read-back. */
    private short[] out = new short[4096];
    /** Sonic's read API has no offset parameter, so it fills this. */
    private short[] tmp = new short[4096];
    /** Counted so a DSP fault can never be silently invisible. */
    private long failures = 0;
    /** Samples Sonic has not produced yet (start-up latency only). */
    private long paddedSamples = 0;

    public KaraokeKeyShifter(int sampleRate, int channels) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.sonic = new Sonic(sampleRate, channels);
        this.sonic.setSpeed(1.0f);
        this.sonic.setPitch(1.0f);
    }

    /** Current shift in semitones, 0 = original key. */
    public int getSemitones() {
        return semitones;
    }

    /** True when the stage is transparent (no DSP at all). */
    public boolean isBypassed() {
        return semitones == 0;
    }

    /**
     * Set the shift. Safe to call any time, including mid-song: Sonic
     * picks the new pitch period up on the next block. Clamped to
     * +-MAX_SEMITONES and folded by the caller (KaraokeVoiceProfile).
     */
    public void setSemitones(int s) {
        if (s > MAX_SEMITONES) s = MAX_SEMITONES;
        if (s < -MAX_SEMITONES) s = -MAX_SEMITONES;
        if (s == semitones) return;
        int previous = semitones;
        semitones = s;
        try {
            float factor = (float) Math.pow(2.0, s / 12.0);
            sonic.setPitch(factor);
            KaraokeLog.i("key: semitones " + previous + " -> " + s
                    + " (pitch factor " + factor + ")");
        } catch (Throwable t) {
            KaraokeLog.e("key: setSemitones failed", t);
        }
    }

    /**
     * Shift one block of interleaved PCM in place.
     *
     * Always returns exactly {@code length} samples: Sonic's start-up
     * latency is zero-filled once, so the caller's sample accounting
     * (chunk trim, audio clock) never drifts. The pad is constant, not
     * cumulative.
     */
    public void process(short[] pcm, int length) {
        if (semitones == 0 || length <= 0) return; // bypass: bit-identical
        if (sonic == null) return;
        if (out.length < length) {
            out = new short[length];
            tmp = new short[length];
        }
        try {
            // Sonic counts FRAMES, not interleaved samples: its move()
            // multiplies by numChannels internally. Passing a sample
            // count here over-reads the array (found the hard way).
            int frames = length / channels;
            sonic.writeShortToStream(pcm, frames);
            int gotFrames = 0;
            for (int guard = 0; guard < 8 && gotFrames < frames; guard++) {
                int n = sonic.readShortFromStream(tmp, frames - gotFrames);
                if (n <= 0) {
                    if (sonic.samplesAvailable() <= 0) break;
                } else {
                    System.arraycopy(tmp, 0, out, gotFrames * channels,
                            n * channels);
                    gotFrames += n;
                }
            }
            int got = gotFrames * channels;
            System.arraycopy(out, 0, pcm, 0, got);
            if (got < length) {
                // Only happens while Sonic is still filling its
                // window (session start, or right after a flush).
                for (int i = got; i < length; i++) pcm[i] = 0;
                paddedSamples += length - got;
                if (paddedSamples < 20000) {
                    KaraokeLog.d("key: padded " + (length - got)
                            + " samples (start-up latency)");
                }
            }
        } catch (Throwable t) {
            // Never let a DSP fault kill playback: fall back to the
            // unshifted block that is still in pcm. But make it
            // LOUD once - a silent bypass looks exactly like "the
            // key button does nothing".
            if (failures++ == 0) {
                KaraokeLog.e("key: process failed, BYPASSING key change", t);
            }
        }
    }

    /** Number of blocks that fell back to unshifted (0 = healthy). */
    public long getFailures() {
        return failures;
    }

    /** Total zero-padded samples so far (diagnostics only). */
    public long getPaddedSamples() {
        return paddedSamples;
    }

    /** Drop state (new song, seek restart, or a live key change). */
    public void reset() {
        try {
            if (sonic != null) sonic.flushStream();
        } catch (Throwable ignored) {
        }
        paddedSamples = 0;
    }
}
