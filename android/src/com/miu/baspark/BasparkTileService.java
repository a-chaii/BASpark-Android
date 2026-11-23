package com.miu.baspark;

import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** 下拉通知栏里的「BASpark」磁贴 */
public class BasparkTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (OverlayService.RUNNING) {
            stopService(new Intent(this, OverlayService.class));
        } else if (!Settings.canDrawOverlays(this)) {
            // 没有悬浮窗权限，直接把用户送到设置页
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= 34) {
                startActivity(i);
            } else {
                startActivityAndCollapse(i);
            }
        } else {
            startForegroundService(new Intent(this, OverlayService.class));
        }
        // 服务状态切换需要一点时间，稍后再刷新磁贴
        new android.os.Handler(getMainLooper()).postDelayed(this::updateTile, 400);
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(OverlayService.RUNNING ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel("BASpark");
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(OverlayService.RUNNING ? "特效运行中" : "已关闭");
        }
        try {
            tile.setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher));
        } catch (Exception ignored) {
        }
        tile.updateTile();
    }
}