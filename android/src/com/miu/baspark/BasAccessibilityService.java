package com.miu.baspark;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 降级方案：不开 root 时，用无障碍的点击事件当作触摸来源。
 * 只能拿到控件级坐标（点击中心点），也没有连续拖拽，但聊胜于无。
 */
public class BasAccessibilityService extends AccessibilityService {

    public static volatile BasAccessibilityService INSTANCE;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_VIEW_CLICKED) return;
        AccessibilityNodeInfo src = event.getSource();
        if (src == null) return;
        Rect r = new Rect();
        src.getBoundsInScreen(r);
        if (r.width() <= 0 || r.height() <= 0) return;

        float w = getResources().getDisplayMetrics().widthPixels;
        float h = getResources().getDisplayMetrics().heightPixels;
        if (w <= 0 || h <= 0) return;

        float px = Math.max(0f, Math.min(1f, r.centerX() / w));
        float py = Math.max(0f, Math.min(1f, r.centerY() / h));
        OverlayService.dispatchExternalClick(px, py);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        INSTANCE = null;
        super.onDestroy();
    }
}