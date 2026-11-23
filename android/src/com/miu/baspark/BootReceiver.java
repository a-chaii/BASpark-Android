package com.miu.baspark;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** 开机自启：只在用户打开开关且已有悬浮窗权限时才拉起来 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(a)) {
            return;
        }
        if (!Config.autoStart(context)) return;
        if (!Settings.canDrawOverlays(context)) return;
        try {
            context.startForegroundService(new Intent(context, OverlayService.class));
        } catch (Exception ignored) {
        }
    }
}