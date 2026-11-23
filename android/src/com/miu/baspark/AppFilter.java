package com.miu.baspark;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;

/** 前后台应用判定（白名单用） */
public final class AppFilter {

    private AppFilter() {
    }

    /** 是否已授予「使用情况访问」权限 */
    public static boolean hasUsageAccess(Context c) {
        try {
            AppOpsManager aom = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int mode;
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                mode = aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), c.getPackageName());
            } else {
                mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), c.getPackageName());
            }
            if (mode == AppOpsManager.MODE_DEFAULT) {
                return c.checkCallingOrSelfPermission(
                        android.Manifest.permission.PACKAGE_USAGE_STATS)
                        == PackageManager.PERMISSION_GRANTED;
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /** 最近一个进入前台的应用包名 */
    public static String foreground(Context c) {
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(now - 20000, now);
            if (events == null) return null;
            UsageEvents.Event e = new UsageEvents.Event();
            String last = null;
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                int t = e.getEventType();
                if (t == UsageEvents.Event.MOVE_TO_FOREGROUND
                        || t == UsageEvents.Event.ACTIVITY_RESUMED) {
                    last = e.getPackageName();
                }
            }
            return last;
        } catch (Exception e) {
            return null;
        }
    }

    public static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }
}