package org.clusterprojector.display;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 枚举当前进程可见的 Display，并按 FX11 仪表特征排序。 */
public final class DisplayEnumerator {

    private static final String TAG = "ClusterDisplay";

    private final Context app;

    public DisplayEnumerator(Context context) {
        this.app = context.getApplicationContext();
    }

    public List<DisplayTarget> listDisplays() {
        DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
        List<DisplayTarget> out = new ArrayList<>();
        if (dm == null) return out;

        // 同时枚举普通 Display 与 Presentation 类别，部分车机只在后者暴露仪表屏。
        java.util.LinkedHashMap<Integer, Display> unique = new java.util.LinkedHashMap<>();
        addDisplays(unique, dm.getDisplays());
        addDisplays(unique, dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION));
        for (Display d : unique.values()) {
            DisplayMetrics metrics = new DisplayMetrics();
            d.getRealMetrics(metrics);
            int id = d.getDisplayId();
            int score = Fx11DisplayProfile.scoreDisplay(
                    id, metrics.widthPixels, metrics.heightPixels,
                    metrics.densityDpi, d.getFlags());
            String reason = Fx11DisplayProfile.describe(
                    id, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi);
            DisplayTarget target = new DisplayTarget(
                    id,
                    d.getName(),
                    metrics.widthPixels,
                    metrics.heightPixels,
                    metrics.densityDpi,
                    d.getFlags(),
                    id == Display.DEFAULT_DISPLAY,
                    score,
                    reason);
            out.add(target);
            ClusterLog.i(TAG, "display=" + target + " name=" + d.getName()
                    + " state=" + d.getState() + " rotation=" + d.getRotation()
                    + " flags=0x" + Integer.toHexString(d.getFlags())
                    + " metrics=" + metrics.widthPixels + "x" + metrics.heightPixels
                    + "@" + metrics.densityDpi);
        }

        Collections.sort(out, new Comparator<DisplayTarget>() {
            @Override public int compare(DisplayTarget a, DisplayTarget b) {
                if (a.getScore() != b.getScore()) return b.getScore() - a.getScore();
                return a.getDisplayId() - b.getDisplayId();
            }
        });
        return out;
    }

    /** @return FX11 首选仪表屏；如果系统暂未向本进程开放，则返回 null。 */
    public DisplayTarget findRecommendedDisplay() {
        for (DisplayTarget info : listDisplays()) {
            if (!info.isDefault() && info.getScore() >= 80) return info;
        }
        return null;
    }

    public DisplayTarget findById(int displayId) {
        for (DisplayTarget info : listDisplays()) {
            if (info.getDisplayId() == displayId) return info;
        }
        return null;
    }

    public Display resolveDisplay(DisplayTarget target) {
        if (target == null) return null;
        DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) return null;
        Display direct = dm.getDisplay(target.getDisplayId());
        if (direct != null) return direct;

        java.util.LinkedHashMap<Integer, Display> unique = new java.util.LinkedHashMap<>();
        addDisplays(unique, dm.getDisplays());
        addDisplays(unique, dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION));
        return unique.get(target.getDisplayId());
    }

    private static void addDisplays(java.util.Map<Integer, Display> out, Display[] displays) {
        if (displays == null) return;
        for (Display display : displays) {
            if (display != null) out.put(display.getDisplayId(), display);
        }
    }
}
