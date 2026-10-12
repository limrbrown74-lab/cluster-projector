package org.clusterprojector.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍服务：
 * <ul>
 *   <li>作为系统绑定的高优先级服务，降低被系统回收的概率（后台保活）。</li>
 *   <li>预留窗口变化监听，后续可用于反向控制（仪表盘触摸转发回被投 App）。</li>
 * </ul>
 *
 * <p>用户需在 设置 → 无障碍 → 仪表投屏 中手动开启。
 */
public class CastAccessibilityService extends AccessibilityService {

    private static CastAccessibilityService instance;

    public static boolean isRunning() {
        return instance != null;
    }

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;

        AccessibilityServiceInfo info = getServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        setServiceInfo(info);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Reserved for future reverse-control / window monitoring.
    }

    @Override public void onInterrupt() {
    }

    @Override public void onDestroy() {
        super.onDestroy();
        instance = null;
    }
}
