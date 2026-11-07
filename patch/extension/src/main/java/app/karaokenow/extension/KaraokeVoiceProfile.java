package app.karaokenow.extension;

/**
 * Voice/song tessitura profile and the semitone decision.
 *
 * The idea (docs/KEYCHANGE.md, and the classic karaoke designs:
 * US 5,296,643 and the 2008 NTU auto-key thesis) is simply:
 *
 *     shift = song's vocal pitch centre - singer's comfortable centre
 *
 * No key/scale snapping is needed: transposing the whole track
 * preserves every interval, so any integer semitone is still
 * musically valid. What IS needed is folding into (-6, +6], because
 * +7 and -5 are the same key and the smaller move sounds better
 * (Sonic is clean to ~ +-4 semitones and thins out past 5).
 *
 * This class is deliberately dependency-free (no JSON, no Android) so
 * the maths can be compiled and tested on its own.
 */
public final class KaraokeVoiceProfile {

    /** p05 / p25 / p75 / p95 of voiced frames, in cents. */
    public static final int MAX_SEMITONES = 6;

    public final double p05;
    public final double p25;
    public final double center;
    public final double p75;
    public final double p95;
    public final int voicedFrames;

    private KaraokeVoiceProfile(double p05, double p25, double center,
            double p75, double p95, int voicedFrames) {
        this.p05 = p05;
        this.p25 = p25;
        this.center = center;
        this.p75 = p75;
        this.p95 = p95;
        this.voicedFrames = voicedFrames;
    }

    /** Build from a known centre (e.g. a profile sent by the server). */
    public static KaraokeVoiceProfile ofCenter(double centerCents) {
        return new KaraokeVoiceProfile(centerCents, centerCents, centerCents,
                centerCents, centerCents, 0);
    }

    /**
     * Build from a cents track (NaN = unvoiced).
     *
     * Robustness: a singer's octave slips would wreck a plain mean, so
     * frames further than 14 semitones from the median are dropped
     * once and the stats recomputed. The centre is then the midpoint
     * of the p25/p75 range, which ignores both a shaky last note and
     * a single bad frame.
     */
    public static KaraokeVoiceProfile fromCentsTrack(double[] track) {
        if (track == null) return null;
        double[] voiced = voicedOnly(track);
        if (voiced.length < 20) return null;      // not enough to judge
        double median = percentile(voiced, 0.5);
        int keep = 0;
        double[] filtered = new double[voiced.length];
        for (double c : voiced) {
            if (Math.abs(c - median) <= 1400) filtered[keep++] = c;
        }
        if (keep < 20) {
            filtered = voiced;
            keep = voiced.length;
        }
        double[] f = new double[keep];
        System.arraycopy(filtered, 0, f, 0, keep);
        double p25 = percentile(f, 0.25);
        double p75 = percentile(f, 0.75);
        return new KaraokeVoiceProfile(
                percentile(f, 0.05), p25, (p25 + p75) / 2.0,
                p75, percentile(f, 0.95), keep);
    }

    private static double[] voicedOnly(double[] track) {
        int n = 0;
        for (double c : track) if (!Double.isNaN(c) && !Double.isInfinite(c)) n++;
        double[] out = new double[n];
        int i = 0;
        for (double c : track) {
            if (!Double.isNaN(c) && !Double.isInfinite(c)) out[i++] = c;
        }
        return out;
    }

    /** Linear-interpolated percentile over a copied, sorted array. */
    static double percentile(double[] values, double p) {
        double[] s = values.clone();
        java.util.Arrays.sort(s);
        if (s.length == 0) return Double.NaN;
        double idx = (s.length - 1) * p;
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        if (lo == hi) return s[lo];
        return s[lo] + (s[hi] - s[lo]) * (idx - lo);
    }

    /**
     * Fold a semitone shift into (-6, +6]: the smallest move that
     * reaches the same key. +7 -> -5, -8 -> +4, +12 -> 0.
     */
    public static int fold(int semitones) {
        int s = semitones;
        while (s > MAX_SEMITONES) s -= 12;
        while (s <= -MAX_SEMITONES) s += 12;
        return s;
    }

    /**
     * The shift that moves the song into the singer's comfortable
     * range, folded and clamped. Returns 0 when either side is
     * unknown (never guess silently).
     */
    public int suggestedSemitones(KaraokeVoiceProfile song) {
        if (song == null) return 0;
        double deltaCents = song.center - this.center;
        int s = (int) Math.round(deltaCents / 100.0);
        return fold(s);
    }

    /** Shift in semitones as a display string, e.g. "+2", "-3", "0". */
    public static String semitoneLabel(int s) {
        if (s > 0) return "+" + s;
        return String.valueOf(s);
    }

    /** Range as a readable line, e.g. "A3 .. D5 (centre C4)". */
    public String rangeText() {
        return KaraokePitchDetector.noteName(p05) + " .. "
                + KaraokePitchDetector.noteName(p95)
                + "  (centre " + KaraokePitchDetector.noteName(center) + ")";
    }

    /** True when the shift is big enough to warn about. */
    public static boolean isLargeShift(int s) {
        return Math.abs(s) >= 5;
    }
}
