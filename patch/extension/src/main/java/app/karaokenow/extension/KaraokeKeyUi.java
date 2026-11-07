package app.karaokenow.extension;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

/**
 * KEY button: manual semitone steps and the 10 s "My key" voice test.
 *
 * Kept out of KaraokeBridge on purpose - that file is 4.3k lines of
 * obfuscation-hook juggling and this is self-contained UI plus a
 * background capture. Only the pieces the bridge must own (the static
 * key value, the live session, the toast/refresh helpers) are passed
 * in.
 *
 * Nothing here re-requests audio: the shift is applied inside the
 * existing playback path (KaraokeKeyShifter), so it is instant and
 * mid-song safe.
 */
final class KaraokeKeyUi {

    /** The bridge's current state, mirrored back into the bridge. */
    interface Host {
        int keySemitones();
        void setKeySemitones(int semitones, String why);
        KaraokeVoiceProfile userProfile();
        void setUserProfile(KaraokeVoiceProfile profile);
        KaraokeVoiceProfile songProfile();
        void toast(String message, boolean longDuration);
        void refreshKeyButton();
    }

    private KaraokeKeyUi() {
    }

    /** The 10 s voice test, one at a time. */
    private static volatile KaraokeKeyTest running;

    static void showDialog(final Context ctx, final Host host) {
        if (ctx == null || host == null) return;
        final KaraokeVoiceProfile user = host.userProfile();
        final KaraokeVoiceProfile song = host.songProfile();
        final int current = host.keySemitones();

        StringBuilder msg = new StringBuilder();
        msg.append("Current key: ")
                .append(KaraokeVoiceProfile.semitoneLabel(current))
                .append(current == 0 ? " (original)" : "")
                .append("\n\nYour voice: ")
                .append(user == null ? "not measured yet" : user.rangeText())
                .append("\nThis song: ")
                .append(song == null ? "vocal range not available yet"
                        : KaraokePitchDetector.noteName(song.center));
        if (user != null && song != null) {
            int s = user.suggestedSemitones(song);
            msg.append("\nAuto suggestion: ")
                    .append(KaraokeVoiceProfile.semitoneLabel(s));
            if (KaraokeVoiceProfile.isLargeShift(s)) {
                msg.append(" (large - may sound thin)");
            }
        } else if (user == null) {
            msg.append("\n\nTap \"My key\" and sing for 10 seconds.");
        }

        try {
            new AlertDialog.Builder(ctx)
                    .setTitle("Karaoke key")
                    .setMessage(msg.toString())
                    .setItems(new CharSequence[]{
                            "My key - sing 10 s",
                            "Use auto suggestion",
                            "+1 semitone",
                            "-1 semitone",
                            "Reset to original"}, (d, which) -> {
                        switch (which) {
                            case 0:
                                startTest(ctx, host);
                                break;
                            case 1:
                                if (host.userProfile() == null) {
                                    host.toast("Tap \"My key\" first", false);
                                } else if (host.songProfile() == null) {
                                    host.toast("Song reference not ready yet - "
                                            + "use +/- for now", false);
                                } else {
                                    host.setKeySemitones(
                                            host.userProfile()
                                                    .suggestedSemitones(
                                                            host.songProfile()),
                                            "auto");
                                }
                                break;
                            case 2:
                                host.setKeySemitones(
                                        host.keySemitones() + 1, null);
                                break;
                            case 3:
                                host.setKeySemitones(
                                        host.keySemitones() - 1, null);
                                break;
                            default:
                                host.setKeySemitones(0, null);
                                break;
                        }
                    })
                    .show();
        } catch (Exception e) {
            KaraokeLog.e("keyui: dialog failed", e);
            host.toast("Could not open the key panel", false);
        }
    }

    private static void startTest(final Context ctx, final Host host) {
        if (running != null && running.isRunning()) {
            host.toast("Still listening...", false);
            return;
        }
        host.toast("Sing for 10 seconds - any song, any key", true);
        final KaraokeKeyTest test = new KaraokeKeyTest(
                KaraokeKeyTest.DEFAULT_SECONDS);
        running = test;
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                test.run(new KaraokeKeyTest.Callback() {
                    @Override
                    public void onLevel(double level) {
                        // Level meter is intentionally not drawn: this
                        // overlay would sit on YouTube's player UI.
                    }

                    @Override
                    public void onState(String state) {
                        KaraokeLog.i("keyui: " + state);
                    }

                    @Override
                    public void onResult(KaraokeVoiceProfile profile,
                            String error) {
                        if (profile == null) {
                            main.post(() -> host.toast(
                                    error == null ? "Key test failed" : error,
                                    true));
                            return;
                        }
                        host.setUserProfile(profile);
                        String line = "Your voice: "
                                + KaraokePitchDetector.noteName(profile.center);
                        KaraokeVoiceProfile song = host.songProfile();
                        if (song != null) {
                            int s = profile.suggestedSemitones(song);
                            main.post(() -> {
                                host.setKeySemitones(s, "auto");
                                host.toast(line + " -> key "
                                        + KaraokeVoiceProfile
                                                .semitoneLabel(s), true);
                            });
                        } else {
                            main.post(() -> host.toast(line
                                    + " (song reference not ready - use +/-)",
                                    true));
                        }
                    }
                });
            } finally {
                running = null;
            }
        }, "karaoke-keytest").start();
    }
}
