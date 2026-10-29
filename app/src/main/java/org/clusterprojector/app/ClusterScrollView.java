package org.clusterprojector.app;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;

/**
 * 首页滚动容器：指定区域内（应用列表）不拦截触摸，交给子视图（ListView）自身滚动；
 * 其余区域保持 ScrollView 正常滚动。解决 ScrollView 嵌套固定高度 ListView 的滚动冲突。
 */
public class ClusterScrollView extends ScrollView {

    private View excludedChild;

    public ClusterScrollView(Context context) {
        super(context);
    }

    public ClusterScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ClusterScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** 设置需要自身滚动的子视图（触摸落在其范围内时不拦截）。 */
    public void setExcludedChild(View child) {
        this.excludedChild = child;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (excludedChild != null && excludedChild.isShown()) {
            try {
                int[] loc = new int[2];
                excludedChild.getLocationOnScreen(loc);
                Rect rect = new Rect(loc[0], loc[1],
                        loc[0] + excludedChild.getWidth(),
                        loc[1] + excludedChild.getHeight());
                int x = (int) ev.getRawX();
                int y = (int) ev.getRawY();
                if (rect.contains(x, y)) {
                    // 列表区域：不拦截，交给 ListView 自己滚动
                    return false;
                }
            } catch (Throwable ignored) {
                // 坐标获取失败时回退默认行为
            }
        }
        return super.onInterceptTouchEvent(ev);
    }
}
