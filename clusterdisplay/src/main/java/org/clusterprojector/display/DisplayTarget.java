package org.clusterprojector.display;

import android.view.Display;

/**
 * 单个 Display 的只读快照。
 *
 * <p>针对 FX11（吉利博越 L / 银河 NOS 2.3 / Android 11 / ecarx）会优先识别
 * 1920x532@320dpi 的仪表长条屏。
 */
public final class DisplayTarget {

    private final int displayId;
    private final String name;
    private final int width;
    private final int height;
    private final int densityDpi;
    private final int flags;
    private final boolean defaultDisplay;
    private final int score;
    private final String reason;

    public DisplayTarget(int displayId, String name, int width, int height,
            int densityDpi, int flags, boolean defaultDisplay, int score, String reason) {
        this.displayId = displayId;
        this.name = name;
        this.width = width;
        this.height = height;
        this.densityDpi = densityDpi;
        this.flags = flags;
        this.defaultDisplay = defaultDisplay;
        this.score = score;
        this.reason = reason;
    }

    public int getDisplayId() { return displayId; }
    public String getName() { return name; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getDensityDpi() { return densityDpi; }
    public int getFlags() { return flags; }
    public boolean isDefault() { return defaultDisplay; }
    public int getScore() { return score; }
    public String getReason() { return reason; }
    public boolean isPresentation() { return (flags & Display.FLAG_PRESENTATION) != 0; }
    public boolean isSecure() { return (flags & Display.FLAG_SECURE) != 0; }
    public boolean isPrivate() { return (flags & Display.FLAG_PRIVATE) != 0; }

    @Override public String toString() {
        return "Display " + displayId + " | " + name + " | "
                + width + "x" + height + " | " + densityDpi + "dpi | "
                + (defaultDisplay ? "default" : "score=" + score) + " | " + reason;
    }
}
