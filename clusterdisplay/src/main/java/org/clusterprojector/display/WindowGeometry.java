package org.clusterprojector.display;

/**
 * Position and size of the overlay window, in pixels, relative to the top-left
 * corner of the target display.
 */
public final class WindowGeometry {

    public final int x;
    public final int y;
    public final int width;
    public final int height;

    public WindowGeometry(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /** Centered window at roughly half the display size. */
    public static WindowGeometry centered(int displayWidth, int displayHeight) {
        int w = Math.max(240, displayWidth / 2);
        int h = Math.max(160, displayHeight / 2);
        return new WindowGeometry(
                (displayWidth - w) / 2,
                (displayHeight - h) / 2,
                w, h);
    }

    public WindowGeometry withPosition(int newX, int newY) {
        return new WindowGeometry(newX, newY, width, height);
    }

    public WindowGeometry withSize(int newWidth, int newHeight) {
        return new WindowGeometry(x, y, newWidth, newHeight);
    }

    @Override public String toString() {
        return "Bounds{x=" + x + ", y=" + y + ", w=" + width + ", h=" + height + "}";
    }
}
