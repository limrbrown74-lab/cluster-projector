package org.clusterprojector.display;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

import java.util.ArrayList;
import java.util.List;

/**
 * Enumerates the {@link Display}s available on the device so the caller can pick
 * which one is the instrument cluster / secondary screen.
 *
 * <p>On a normal phone only the default display is visible; on an AAOS head unit
 * the cluster usually shows up as a secondary display under the
 * {@link DisplayManager#DISPLAY_CATEGORY_PRESENTATION} category.
 *
 * <p>All methods are reflection-free and use only the public Android framework, so
 * the library compiles and runs on a stock phone.
 */
public final class DisplayEnumerator {

    private final Context app;

    public DisplayEnumerator(Context context) {
        this.app = context.getApplicationContext();
    }

    /**
     * @return all displays this process is allowed to see, including the default one.
     */
    public List<DisplayTarget> listDisplays() {
        DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
        List<DisplayTarget> out = new ArrayList<>();
        if (dm == null) return out;

        Display[] displays = dm.getDisplays();
        for (Display d : displays) {
            int id = d.getDisplayId();
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            d.getMetrics(metrics);
            out.add(new DisplayTarget(
                    id,
                    buildName(d),
                    metrics.widthPixels,
                    metrics.heightPixels,
                    metrics.densityDpi,
                    id == Display.DEFAULT_DISPLAY));
        }
        return out;
    }

    /**
     * @return displays that are suitable for projecting onto (secondary displays),
     *         excluding the default main screen. Empty on a phone without a cluster.
     */
    public List<DisplayTarget> listProjectableDisplays() {
        List<DisplayTarget> all = listDisplays();
        List<DisplayTarget> out = new ArrayList<>();
        for (DisplayTarget info : all) {
            if (!info.isDefault()) out.add(info);
        }
        return out;
    }

    /**
     * Find a display by id.
     *
     * @return the display info, or {@code null} if it is not visible to this process.
     */
    public DisplayTarget findById(int displayId) {
        for (DisplayTarget info : listDisplays()) {
            if (info.getDisplayId() == displayId) return info;
        }
        return null;
    }

    private static String buildName(Display d) {
        // Display.getName() is API 17+ but hidden on some OEM builds; guard via reflection.
        try {
            Object name = Display.class.getMethod("getName").invoke(d);
            if (name != null) return name.toString();
        } catch (Throwable ignored) {
        }
        return "Display " + d.getDisplayId();
    }
}
