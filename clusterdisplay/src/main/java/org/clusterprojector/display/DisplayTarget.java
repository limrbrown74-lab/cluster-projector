package org.clusterprojector.display;

import android.view.Display;

/**
 * Immutable snapshot of a {@link Display} that the user can project onto.
 *
 * <p>Wraps the fields callers actually need: id, human-readable name and size.
 *
 * <p>本模块由 AI 辅助原创开发。
 */
public final class DisplayTarget {

    private final int displayId;
    private final String name;
    private final int width;
    private final int height;
    private final float density;
    private final boolean isDefault;

    public DisplayTarget(int displayId, String name, int width, int height,
                              float density, boolean isDefault) {
        this.displayId = displayId;
        this.name = name;
        this.width = width;
        this.height = height;
        this.density = density;
        this.isDefault = isDefault;
    }

    public int getDisplayId() { return displayId; }
    public String getName() { return name; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public float getDensity() { return density; }
    public boolean isDefault() { return isDefault; }

    @Override public String toString() {
        return "DisplayTarget{id=" + displayId + ", '" + name + "', "
                + width + "x" + height + (isDefault ? " (default)" : "") + "}";
    }
}
