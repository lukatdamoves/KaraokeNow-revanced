package app.karaokenow.extension;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import java.util.ArrayDeque;

/**
 * v6 DIAGNOSTIC ONLY — portrait video-position numbers, no layout
 * changes, no behavior changes. Logs one geometry block per
 * change (never in a loop): screen size/density/orientation,
 * decor rect + visible display frame, every video surface
 * (SurfaceView/TextureView) found under the decor view, the
 * ancestor chain from the bottom UI up to the decor view
 * (this shows whether the bottom row is an overlay child or
 * a sizing sibling of the surface), and the rects of the
 * bottom UI, its row, the fullscreen button, and our K/BV/LOG
 * buttons, plus a controls shown/hidden proxy (bottom-UI
 * visibility, isShown, alpha, translationY).
 *
 * The decisive experiment is the BEFORE/AFTER pair around
 * button injection (see KaraokeBridge): if the surface rect
 * is identical before and after our buttons are added, the
 * patch cannot be moving the video.
 *
 * Video-pixel size is NOT logged: this build's ExoPlayer
 * interface has no video-size accessor (verified in the
 * stock dex — no VideoSize type exists in any dex and the
 * interface exposes only 16 methods, none size-related),
 * so a video-size synthetic would be a guess. Surface
 * aspect vs screen aspect from this block still separates
 * a layout shift (the surface itself moved) from video
 * letterboxing (surface full, bars inside the video).
 *
 * Never throws; never logs keys or full URLs (no URLs here
 * at all — rects and class names only).
 */
public final class KaraokeGeometry {

    /** Last logged block signature (without its when-tag), so
     *  repeats with identical numbers stay silent. */
    private static volatile String lastSignature;

    private KaraokeGeometry() {
    }

    /**
     * Logs a geometry block for the given bottom-UI view, or a
     * one-liner when there is no bottom UI yet (ordering info:
     * controller set before bottom-UI inflation). Change-only:
     * a block whose numbers match the last logged block is
     * skipped silently.
     */
    public static void logBlock(View bottomUi, String when) {
        try {
            if (bottomUi == null) {
                logOnce("none", "geom [" + when + "]: no bottom UI yet");
                return;
            }
            StringBuilder sb = new StringBuilder();
            Context ctx = bottomUi.getContext();
            if (ctx != null && ctx.getResources() != null) {
                DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                int orient = ctx.getResources().getConfiguration().orientation;
                String o = orient == Configuration.ORIENTATION_PORTRAIT
                        ? "portrait"
                        : orient == Configuration.ORIENTATION_LANDSCAPE
                                ? "landscape" : "other(" + orient + ")";
                sb.append("screen=").append(dm.widthPixels).append("x")
                        .append(dm.heightPixels)
                        .append(" density=").append(dm.density)
                        .append(" orient=").append(o).append("\n");
            }
            View root = bottomUi.getRootView();
            if (root != null) {
                sb.append("decor=").append(rectOf(root));
                Rect vf = new Rect();
                try {
                    root.getWindowVisibleDisplayFrame(vf);
                    sb.append(" visibleFrame=").append(rectStr(vf));
                } catch (Exception e) {
                    sb.append(" visibleFrame=?");
                }
                sb.append("\n");
                sb.append(surfacesBlock(root));
            } else {
                sb.append("decor=? (no root view)\n");
            }
            sb.append("chain=").append(chainOf(bottomUi)).append("\n");
            sb.append("bottomUi=").append(rectOf(bottomUi))
                    .append(" vis=").append(visName(bottomUi.getVisibility()))
                    .append(" shown=").append(bottomUi.isShown())
                    .append(" alpha=").append(bottomUi.getAlpha())
                    .append(" transY=").append(bottomUi.getTranslationY())
                    .append("\n");
            if (ctx != null) {
                int rowId = resId(ctx, "bottom_end_container");
                View row = rowId != 0 ? bottomUi.findViewById(rowId) : null;
                sb.append("row=").append(row == null ? "missing"
                        : rectOf(row)).append("\n");
                int fsId = resId(ctx, "fullscreen_button");
                View fs = fsId != 0 ? bottomUi.findViewById(fsId) : null;
                sb.append("fullscreen=").append(fs == null ? "missing"
                        : rectOf(fs)).append("\n");
            }
            String[] tags = {"karaoke-button", "karaoke-bv-button",
                    "karaoke-log-button-debug"};
            for (String tag : tags) {
                View v = bottomUi.findViewWithTag(tag);
                sb.append(tag).append("=")
                        .append(v == null ? "missing" : rectOf(v))
                        .append("\n");
            }
            String block = sb.toString();
            // Signature = the numbers only (when-tag excluded),
            // so the same geometry under a new tag stays silent.
            if (block.equals(lastSignature)) return;
            lastSignature = block;
            for (String line : block.split("\n")) {
                if (!line.isEmpty()) {
                    KaraokeLog.i("geom [" + when + "] " + line);
                }
            }
        } catch (Exception e) {
            KaraokeLog.w("geom failed", e);
        }
    }

    /** One-liner with the same change-only dedupe. */
    private static void logOnce(String signature, String line) {
        if (signature.equals(lastSignature)) return;
        lastSignature = signature;
        KaraokeLog.i(line);
    }

    /** Every SurfaceView/TextureView under root, with rects. */
    private static String surfacesBlock(View root) {
        StringBuilder sb = new StringBuilder();
        int found = 0;
        int visited = 0;
        boolean truncated = false;
        ArrayDeque<View> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            View v = stack.pop();
            visited++;
            if (visited > 5000) {
                truncated = true;
                break;
            }
            if (v instanceof SurfaceView || v instanceof TextureView) {
                found++;
                if (found <= 10) {
                    sb.append("surface[").append(found).append("]=")
                            .append(v.getClass().getName())
                            .append(" ").append(rectOf(v))
                            .append(" parent=")
                            .append(v.getParent() instanceof View
                                    ? ((View) v.getParent()).getClass()
                                            .getName()
                                    : String.valueOf(v.getParent()))
                            .append("\n");
                    // v72: settle the pushed-down question with
                    // WINDOW-space rects + the parent chain (the
                    // on-screen SurfaceView rect proved unreliable
                    // across builds: 115 vs 230 for the same state).
                    if (found == 1) {
                        sb.append("surfaceChain=")
                                .append(surfaceChainOf(v)).append("\n");
                    }
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = g.getChildCount() - 1; i >= 0; i--) {
                    View c = g.getChildAt(i);
                    if (c != null) stack.push(c);
                }
            }
        }
        if (found == 0) {
            sb.append("surface=none found under decor view\n");
        }
        if (truncated) {
            sb.append("surface search truncated at 5000 views\n");
        }
        return sb.toString();
    }

    /** bottomUi up to the decor view: class + rect per level. */
    private static String chainOf(View bottomUi) {
        StringBuilder sb = new StringBuilder();
        View v = bottomUi;
        int levels = 0;
        while (v != null && levels < 30) {
            if (levels > 0) sb.append(" <- ");
            sb.append(v.getClass().getName()).append(" ")
                    .append(rectOf(v));
            levels++;
            ViewParent p = v.getParent();
            v = p instanceof View ? (View) p : null;
        }
        if (v != null) sb.append(" <- ... (chain truncated)");
        return sb.toString();
    }

    /** Surface + ancestors: class@win(x,y WxH)/screen(x,y) per
     *  level, up to 8. Window space is the ground truth for what
     *  the layout actually did (decor top = 0 there). */
    private static String surfaceChainOf(View surface) {
        StringBuilder sb = new StringBuilder();
        View v = surface;
        int levels = 0;
        while (v != null && levels < 8) {
            if (levels > 0) sb.append(" <- ");
            sb.append(v.getClass().getSimpleName())
                    .append("@win(").append(rectInWindowOf(v))
                    .append(")/scr(").append(rectOf(v)).append(")");
            levels++;
            ViewParent p = v.getParent();
            v = p instanceof View ? (View) p : null;
        }
        return sb.toString();
    }

    private static String rectInWindowOf(View v) {
        if (v == null || v.getWidth() <= 0 || v.getHeight() <= 0) {
            return "not-laid-out";
        }
        int[] loc = new int[2];
        try {
            v.getLocationInWindow(loc);
        } catch (Exception e) {
            return "loc-failed";
        }
        return loc[0] + "," + loc[1] + ","
                + v.getWidth() + "x" + v.getHeight();
    }

    /** On-screen rect as "left,top,widthxheight", or a marker
     *  when the view has not been laid out yet. */
    private static String rectOf(View v) {
        if (v == null) return "null";
        if (v.getWidth() <= 0 || v.getHeight() <= 0) {
            return "not-laid-out";
        }
        int[] loc = new int[2];
        try {
            v.getLocationOnScreen(loc);
        } catch (Exception e) {
            return "loc-failed";
        }
        return loc[0] + "," + loc[1] + ","
                + v.getWidth() + "x" + v.getHeight();
    }

    private static String rectStr(Rect r) {
        return r.left + "," + r.top + ","
                + (r.right - r.left) + "x" + (r.bottom - r.top);
    }

    private static String visName(int vis) {
        if (vis == View.VISIBLE) return "VISIBLE";
        if (vis == View.INVISIBLE) return "INVISIBLE";
        if (vis == View.GONE) return "GONE";
        return "vis(" + vis + ")";
    }

    /** Resource id by name (same rule as the bridge: names are
     *  stable, numeric ids shift between YouTube builds). */
    private static int resId(Context ctx, String name) {
        try {
            return ctx.getResources().getIdentifier(
                    name, "id", ctx.getPackageName());
        } catch (Exception e) {
            return 0;
        }
    }
}
