package app.karaokenow.extension;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * The "My key" test: record ~10 s of the singer, measure their
 * comfortable pitch, and hand back a KaraokeVoiceProfile.
 *
 * Why the song is muted during the test (docs/KEYCHANGE.md S5):
 * if the song is playing through the speaker, the microphone hears
 * BOTH the song and the singer, and the detector locks onto whichever
 * is louder — usually the song. Muting costs nothing and always
 * works. AcousticEchoCanceler is the later upgrade for a "sing along"
 * mode; it is not needed for the plain test.
 *
 * Everything runs on a background thread. Nothing here touches the
 * playback path, so a slow analysis can never cause an audio glitch.
 *
 * Permissions: YouTube already declares android.permission.RECORD_AUDIO
 * (voice search), so this needs only the normal runtime grant, never a
 * manifest patch.
 */
public final class KaraokeKeyTest {

    /** Capture sample rate. 16 kHz is plenty for f0 up to 1 kHz. */
    public static final int RATE = 16000;
    /** Default capture length. */
    public static final int DEFAULT_SECONDS = 10;

    public interface Callback {
        /** Live input level 0..1 for the meter (main thread). */
        void onLevel(double level);
        /** Result, or null with a plain-words reason. */
        void onResult(KaraokeVoiceProfile profile, String error);
        void onState(String state);
    }

    private final int seconds;
    private volatile boolean cancelled;
    private volatile boolean running;

    public KaraokeKeyTest(int seconds) {
        this.seconds = seconds <= 0 ? DEFAULT_SECONDS : seconds;
    }

    public boolean isRunning() {
        return running;
    }

    public void cancel() {
        cancelled = true;
    }

    /**
     * Blocking. Call from a background thread.
     */
    public void run(Callback cb) {
        if (running) return;
        running = true;
        AudioRecord rec = null;
        try {
            int minBuf = AudioRecord.getMinBufferSize(RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) minBuf = RATE; // 0.5 s fallback
            int bufBytes = Math.max(minBuf, RATE / 2 * 2);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufBytes * 2);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                fail(cb, "Microphone unavailable — check the app's mic "
                        + "permission, then try again");
                return;
            }
            cb.onState("listening");
            rec.startRecording();

            int totalSamples = RATE * seconds;
            short[] all = new short[totalSamples];
            int filled = 0;
            short[] block = new short[1600]; // 100 ms
            long lastLevelAt = 0;
            while (filled < totalSamples && !cancelled) {
                int n = rec.read(block, 0, block.length);
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_INVALID_OPERATION
                            || n == AudioRecord.ERROR_BAD_VALUE) break;
                    continue;
                }
                System.arraycopy(block, 0, all, filled, n);
                filled += n;
                long now = System.currentTimeMillis();
                if (now - lastLevelAt >= 80) {
                    lastLevelAt = now;
                    double rms = rms(block, n);
                    cb.onLevel(Math.min(1.0, rms * 6.0));
                }
            }
            try {
                rec.stop();
            } catch (Exception ignored) {
            }

            if (cancelled) {
                cb.onState("cancelled");
                return;
            }
            cb.onState("analysing");
            if (filled < RATE) {
                fail(cb, "Too short — hold the notes a bit longer");
                return;
            }
            short[] pcm = new short[filled];
            System.arraycopy(all, 0, pcm, 0, filled);
            double[] track = KaraokePitchDetector.centsTrackFromShorts(pcm, RATE);
            KaraokeVoiceProfile p = KaraokeVoiceProfile.fromCentsTrack(track);
            if (p == null) {
                fail(cb, "Couldn't hear a steady note — sing louder, or "
                        + "hold one note for a second or two");
                return;
            }
            KaraokeLog.i("keytest: sung " + p.voicedFrames + " voiced frames, "
                    + "centre " + Math.round(p.center) + " cents "
                    + p.rangeText());
            cb.onResult(p, null);
        } catch (SecurityException se) {
            KaraokeLog.e("keytest: mic permission denied", se);
            fail(cb, "Microphone permission is off — allow it for YouTube "
                    + "in Android settings, then try again");
        } catch (Throwable t) {
            KaraokeLog.e("keytest: failed", t);
            fail(cb, "Recording failed: " + t.getClass().getSimpleName());
        } finally {
            running = false;
            if (rec != null) {
                try {
                    rec.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void fail(Callback cb, String why) {
        cb.onResult(null, why);
    }

    private static double rms(short[] s, int n) {
        if (n <= 0) return 0;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double v = s[i] / 32768.0;
            sum += v * v;
        }
        return Math.sqrt(sum / n);
    }
}
