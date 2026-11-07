package app.karaokenow.extension;

/**
 * YIN fundamental-frequency detector for singing (pure Java, no DSP
 * dependency). Same algorithm as the verified spike
 * (spikes/yin_spike.js), which read 220/440/165 Hz test tones to
 * A3/A4/E3 exactly.
 *
 * YIN: de Cheveigne & Kawahara, "YIN, a fundamental frequency
 * estimator for speech and music", JASA 2002.
 *
 * Design notes:
 *   - Direct (not FFT) difference function. At 16 kHz, 2048-sample
 *     frames and a 65 Hz floor this is ~0.4 M multiply-adds per
 *     frame; a 10 s capture is ~500 frames, i.e. well under a second
 *     on the phone. It runs ONCE, on a background thread, never in
 *     the playback path. Simplicity beats speed here.
 *   - Everything is expressed in CENTS, not Hz, so that averaging is
 *     musical. A1 = 55 Hz = 0 cents = MIDI 33.
 *   - Unvoiced frames return NaN. Silence and breath must never look
 *     like a note, so there is an RMS gate as well as the YIN
 *     confidence threshold.
 */
public final class KaraokePitchDetector {

    public static final int FRAME = 2048;
    public static final int HOP = 320;              // 20 ms at 16 kHz
    public static final int MIN_HZ = 65;            // C2
    public static final int MAX_HZ = 1000;          // B5
    private static final double YIN_THRESHOLD = 0.15;
    private static final double RMS_GATE = 0.0055;  // ~ -45 dBFS

    private KaraokePitchDetector() {
    }

    /** Hz -> cents, A1 = 55 Hz = 0. */
    public static double hzToCents(double hz) {
        return 1200.0 * (Math.log(hz / 55.0) / Math.log(2.0));
    }

    /** cents -> Hz. */
    public static double centsToHz(double cents) {
        return 55.0 * Math.pow(2.0, cents / 1200.0);
    }

    /** cents -> MIDI note number (A4 = 69). */
    public static double centsToMidi(double cents) {
        return 33.0 + cents / 100.0;
    }

    /** Scientific pitch name, e.g. "A3", "F#4 -12c". */
    public static String noteName(double cents) {
        double midi = centsToMidi(cents);
        int r = (int) Math.round(midi);
        int dev = (int) Math.round((midi - r) * 100.0);
        String[] names = {"C", "C#", "D", "D#", "E", "F",
                "F#", "G", "G#", "A", "A#", "B"};
        int pc = ((r % 12) + 12) % 12;
        int octave = (int) Math.floor(r / 12.0) - 1;
        String s = names[pc] + octave;
        if (dev > 0) s += " +" + dev + "c";
        else if (dev < 0) s += " " + dev + "c";
        return s;
    }

    /**
     * Analyse a mono PCM capture. Returns one entry per frame in
     * cents, NaN where unvoiced. Length is (pcm.length - FRAME) / HOP + 1.
     *
     * @param pcm        mono samples, range -1..1 after shortToFloat
     * @param sampleRate must match how the capture was taken
     */
    public static double[] centsTrack(float[] pcm, int sampleRate) {
        if (pcm == null || pcm.length < FRAME) return new double[0];
        int frames = (pcm.length - FRAME) / HOP + 1;
        double[] out = new double[frames];
        for (int f = 0; f < frames; f++) {
            int start = f * HOP;
            if (rms(pcm, start, FRAME) < RMS_GATE) {
                out[f] = Double.NaN;
                continue;
            }
            double hz = yin(pcm, start, FRAME, sampleRate);
            out[f] = hz > 0 ? hzToCents(hz) : Double.NaN;
        }
        return out;
    }

    /** Convenience: 16-bit mono PCM in, cents track out. */
    public static double[] centsTrackFromShorts(short[] pcm, int sampleRate) {
        if (pcm == null) return new double[0];
        float[] f = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) f[i] = pcm[i] / 32768.0f;
        return centsTrack(f, sampleRate);
    }

    private static double rms(float[] x, int start, int n) {
        double s = 0;
        for (int i = 0; i < n; i++) {
            double v = x[start + i];
            s += v * v;
        }
        return Math.sqrt(s / n);
    }

    /**
     * YIN f0 for one frame, or -1 when unvoiced.
     */
    static double yin(float[] x, int start, int n, int sampleRate) {
        int minLag = sampleRate / MAX_HZ;
        int maxLag = Math.min(sampleRate / MIN_HZ, n / 2);
        int w = n - maxLag;
        if (w <= 0 || minLag < 1) return -1;

        double[] d = new double[maxLag + 1];
        for (int lag = minLag; lag <= maxLag; lag++) {
            double sum = 0;
            for (int j = 0; j < w; j++) {
                double diff = x[start + j] - x[start + j + lag];
                sum += diff * diff;
            }
            d[lag] = sum;
        }

        // cumulative mean normalised difference
        double[] cmnd = new double[maxLag + 1];
        double running = 0;
        cmnd[0] = 1;
        for (int lag = 1; lag <= maxLag; lag++) {
            running += d[lag];
            cmnd[lag] = running == 0 ? 1 : (d[lag] * lag) / running;
        }

        int lagEst = -1;
        for (int lag = minLag; lag <= maxLag; lag++) {
            if (cmnd[lag] < YIN_THRESHOLD) {
                while (lag + 1 <= maxLag && cmnd[lag + 1] < cmnd[lag]) lag++;
                lagEst = lag;
                break;
            }
        }
        if (lagEst < 0) {
            int best = minLag;
            for (int lag = minLag; lag <= maxLag; lag++) {
                if (cmnd[lag] < cmnd[best]) best = lag;
            }
            if (cmnd[best] > 0.4) return -1;   // no clear period: unvoiced
            lagEst = best;
        }

        // parabolic interpolation of the minimum
        double refined = lagEst;
        if (lagEst > minLag && lagEst < maxLag) {
            double a = cmnd[lagEst - 1], b = cmnd[lagEst], c = cmnd[lagEst + 1];
            double denom = 2.0 * (2.0 * b - a - c);
            if (denom != 0) refined = lagEst + (c - a) / denom;
        }
        if (refined <= 0) return -1;
        double hz = sampleRate / refined;
        return (hz >= MIN_HZ && hz <= MAX_HZ) ? hz : -1;
    }
}
