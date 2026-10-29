package org.clusterprojector.display;

/**
 * FX11 车机显示参数。
 *
 * <p>来源：本机 bugreport / DisplayManagerService dump：
 * <ul>
 *     <li>Display 0：中控竖屏 1440x1920@160</li>
 *     <li>Display 1：内置小屏 800x480@320，需要排除</li>
 *     <li>Display 2：副驾/娱乐屏 2880x1080@160，需要排除</li>
 *     <li>Display 3/4：仪表长条屏 1920x532@320，实车确认优先 Display 4（备选 3）</li>
 * </ul>
 */
public final class Fx11DisplayProfile {

    public static final int CENTER_DISPLAY_ID = 0;
    public static final int PREFERRED_CLUSTER_DISPLAY_ID = 4;
    public static final int SECONDARY_CLUSTER_DISPLAY_ID = 3;

    public static final int CLUSTER_WIDTH = 1920;
    public static final int CLUSTER_HEIGHT = 532;
    public static final int CLUSTER_DENSITY_DPI = 320;

    public static final int CENTER_WIDTH = 1440;
    public static final int CENTER_HEIGHT = 1920;
    public static final int CENTER_DENSITY_DPI = 160;

    private Fx11DisplayProfile() {}

    public static WindowGeometry defaultDirectBounds() {
        // v1.2.6 诊断版先全屏验证 Presentation 是否能进入仪表物理合成层；
        // 确认可见后，用户仍可通过界面把窗口调小并移动到指定区域。
        return new WindowGeometry(0, 0, CLUSTER_WIDTH, CLUSTER_HEIGHT);
    }

    public static WindowGeometry defaultMirrorBounds() {
        // 完整 contain 中控竖屏 1440x1920，在仪表全高 532 下宽度约 400。
        int width = Math.round(CLUSTER_HEIGHT * (CENTER_WIDTH / (float) CENTER_HEIGHT));
        int x = (CLUSTER_WIDTH - width) / 2;
        return new WindowGeometry(x, 0, width, CLUSTER_HEIGHT);
    }

    public static boolean isClusterSize(int width, int height) {
        return width == CLUSTER_WIDTH && height == CLUSTER_HEIGHT;
    }

    public static boolean isCenterSize(int width, int height) {
        return width == CENTER_WIDTH && height == CENTER_HEIGHT;
    }

    public static int scoreDisplay(int displayId, int width, int height,
            int densityDpi, int flags) {
        if (displayId == CENTER_DISPLAY_ID) return 0;

        int score = 0;
        if (isClusterSize(width, height)) {
            score = 100;
            if (densityDpi == CLUSTER_DENSITY_DPI) score += 10;
            if (displayId == PREFERRED_CLUSTER_DISPLAY_ID) score += 5;
            if (displayId == SECONDARY_CLUSTER_DISPLAY_ID) score += 4;
            if ((flags & android.view.Display.FLAG_PRESENTATION) != 0) score += 2;
            if ((flags & android.view.Display.FLAG_PRIVATE) != 0) score += 1;
            return score;
        }

        // 其他长条屏也作为候选，但优先级低于 FX11 实测参数。
        if (width >= 1600 && height > 0 && height <= 700
                && (float) width / height >= 3.0f) {
            score = 70;
            if ((flags & android.view.Display.FLAG_PRESENTATION) != 0) score += 5;
            return score;
        }

        if (width == 800 && height == 480) return 5;       // HUD/小屏，不是目标仪表
        if (width == 2880 && height == 1080) return 10;    // PSD/副驾娱乐屏
        if (width > 0 && height > 0) return 30;
        return 0;
    }

    public static String describe(int displayId, int width, int height, int densityDpi) {
        if (displayId == CENTER_DISPLAY_ID && isCenterSize(width, height)) {
            return "FX11 中控屏，不作为仪表目标";
        }
        if (isClusterSize(width, height)) {
            if (displayId == PREFERRED_CLUSTER_DISPLAY_ID) {
                return "FX11 首选仪表长条屏 (Display 4)";
            }
            if (displayId == SECONDARY_CLUSTER_DISPLAY_ID) {
                return "FX11 备选仪表长条屏 (Display 3)";
            }
            return "FX11 仪表长条屏";
        }
        if (width == 800 && height == 480) return "FX11 内置小屏/HUD，已降权";
        if (width == 2880 && height == 1080) return "FX11 副驾/娱乐屏，已降权";
        if (width >= 1600 && height <= 700) return "疑似长条仪表屏";
        return "普通副屏";
    }
}
