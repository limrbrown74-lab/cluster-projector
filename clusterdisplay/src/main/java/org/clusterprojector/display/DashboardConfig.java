package org.clusterprojector.display;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * 仪表盘元素配置（可见性 + 位置偏移）与"投屏叠加仪表"总开关。
 *
 * <p>持久化到 SharedPreferences；{@link #reload(Context)} 重新载入，
 * 供仪表盘 View 和管理页共享同一份内存缓存。
 */
public final class DashboardConfig {

    public static final String PREFS_NAME = "cluster_dashboard_prefs";
    public static final String KEY_MERGE_ENABLED = "merge_enabled";

    /** 元素定义：id 用于持久化 key，label 用于管理页展示。 */
    public static final class Element {
        public final String id;
        public final String label;

        Element(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    public static final String EL_TELLTALES = "telltales";
    public static final String EL_TOPBAR_RIGHT = "topbar_right";
    public static final String EL_SPEED = "speed";
    public static final String EL_GEAR = "gear";
    public static final String EL_RANGE = "range";
    public static final String EL_ODOMETER = "odometer";
    public static final String EL_MAP = "map";
    public static final String EL_RPM = "rpm";
    public static final String EL_FUEL = "fuel";
    public static final String EL_COOLANT = "coolant";

    public static final Element[] ELEMENTS = {
            new Element(EL_TELLTALES, "指示灯条（转向/灯光/驻车/安全带）"),
            new Element(EL_TOPBAR_RIGHT, "顶栏信息（时间/温度/ACC/歌曲/信号）"),
            new Element(EL_SPEED, "车速大字"),
            new Element(EL_GEAR, "档位 P/R/N/D"),
            new Element(EL_RANGE, "续航卡片"),
            new Element(EL_ODOMETER, "总里程卡片"),
            new Element(EL_MAP, "地图区域"),
            new Element(EL_RPM, "转速 + 进度条"),
            new Element(EL_FUEL, "油量表"),
            new Element(EL_COOLANT, "水温表"),
    };

    // 内存缓存
    private static boolean loaded;
    private static boolean mergeEnabled = true;
    private static final boolean[] VISIBLE = new boolean[ELEMENTS.length];
    private static final int[] DX = new int[ELEMENTS.length];
    private static final int[] DY = new int[ELEMENTS.length];

    private DashboardConfig() {}

    public static synchronized void init(Context context) {
        if (loaded) return;
        reload(context);
    }

    public static synchronized void reload(Context context) {
        if (context == null) return;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            mergeEnabled = sp.getBoolean(KEY_MERGE_ENABLED, true);
            for (int i = 0; i < ELEMENTS.length; i++) {
                Element el = ELEMENTS[i];
                VISIBLE[i] = sp.getBoolean("el_" + el.id + "_visible", true);
                DX[i] = sp.getInt("el_" + el.id + "_dx", 0);
                DY[i] = sp.getInt("el_" + el.id + "_dy", 0);
            }
            loaded = true;
            ClusterLog.i("DashboardConfig", "reloaded merge=" + mergeEnabled
                    + " elements=" + ELEMENTS.length);
        } catch (Throwable t) {
            ClusterLog.w("DashboardConfig", "reload failed", t);
        }
    }

    public static synchronized boolean isMergeEnabled() {
        return mergeEnabled;
    }

    /** 位置偏移：百分比（-100..100）相对默认锚点。 */
    public static synchronized float elementDxPct(String id) {
        return elementField(id, DX);
    }

    public static synchronized float elementDyPct(String id) {
        return elementField(id, DY);
    }

    public static synchronized boolean elementVisible(String id) {
        int idx = indexOf(id);
        return idx < 0 || VISIBLE[idx];
    }

    private static float elementField(String id, int[] arr) {
        int idx = indexOf(id);
        return idx < 0 ? 0 : arr[idx];
    }

    private static int indexOf(String id) {
        for (int i = 0; i < ELEMENTS.length; i++) {
            if (ELEMENTS[i].id.equals(id)) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 管理页写入
    // ------------------------------------------------------------------

    public static synchronized void setMergeEnabled(Context context, boolean enabled) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_MERGE_ENABLED, enabled).apply();
        mergeEnabled = enabled;
    }

    public static synchronized void setElement(Context context, String id,
            boolean visible, int dx, int dy) {
        dx = clamp(dx);
        dy = clamp(dy);
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = sp.edit();
        e.putBoolean("el_" + id + "_visible", visible);
        e.putInt("el_" + id + "_dx", dx);
        e.putInt("el_" + id + "_dy", dy);
        e.apply();
        int idx = indexOf(id);
        if (idx >= 0) {
            VISIBLE[idx] = visible;
            DX[idx] = dx;
            DY[idx] = dy;
        }
    }

    public static synchronized boolean elementVisibleOf(int idx) {
        return idx >= 0 && idx < VISIBLE.length && VISIBLE[idx];
    }

    public static synchronized int elementDxOf(int idx) {
        return idx >= 0 && idx < DX.length ? DX[idx] : 0;
    }

    public static synchronized int elementDyOf(int idx) {
        return idx >= 0 && idx < DY.length ? DY[idx] : 0;
    }

    private static int clamp(int v) {
        return Math.max(-100, Math.min(100, v));
    }

    /** 位置偏移转画布像素。 */
    public static float offsetX(float pct, float base) {
        return pct / 100f * base;
    }

    public static float offsetY(float pct, float base) {
        return pct / 100f * base;
    }

    public static String prefsKeyOf(String id, String suffix) {
        return String.format(Locale.US, "el_%s_%s", id, suffix);
    }
}
