package com.miu.baspark;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Locale;

public class OverlayService extends Service implements TouchReader.Sink {

    private static final String TAG = "BASparkOverlay";
    private static final String CHANNEL_ID = "baspark_overlay";
    private static final int NOTI_ID = 1001;

    public static final String ACTION_UPDATE = "com.miu.baspark.UPDATE";
    public static final String ACTION_STOP = "com.miu.baspark.STOP";
    /** 调试用：从 adb 或 shell 发一个中心爆裂，验证绘制链路 */
    public static final String ACTION_TEST = "com.miu.baspark.TEST";

    public static volatile boolean RUNNING = false;
    public static volatile float REAL_FPS = 0f;
    public static volatile String DEVICE_INFO = "-";

    private WindowManager wm;
    private WebView web;
    private NativeSparkView nativeView;
    private View overlayView;
    private TouchReader reader;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean pageReady = false;
    private static volatile OverlayService sRef;
    private boolean suppressed = false;

    // 悬浮窗的真实边界：状态栏/导航栏会把 MATCH_PARENT 挤小，百分比必须按这个算
    private int dispW, dispH;
    private int webLeft, webTop, webW, webH;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                setRenderingPaused(true);
            } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                setRenderingPaused(false);
            } else if (Intent.ACTION_CONFIGURATION_CHANGED.equals(a)) {
                onDisplayChanged();
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        RUNNING = true;
        sRef = this;
        DLog.init(getApplicationContext());
        DLog.i(TAG, "service onCreate");
        startForegroundCompat();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        createOverlay();

        if (Config.source(this) == 0) {
            reader = new TouchReader(this);
            reader.setRotation(currentRotation());
            reader.setPrimaryOnly(Config.multitouchOpt(this));
            reader.start();
        }
        ui.postDelayed(this::whitelistTick, 1200);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        registerReceiver(screenReceiver, f);

        ui.postDelayed(this::afterBoot, 800);
        ui.postDelayed(this::startFpsPolling, 1200);
        DLog.i(TAG, "overlay service started, rotation=" + currentRotation());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ACTION_TEST.equals(action)) {
                onDown(0.5f, 0.5f);
                ui.postDelayed(this::onUp, 200);
                ui.postDelayed(() -> DLog.i(TAG, "test burst fps=" + REAL_FPS), 1600);
                return START_STICKY;
            }
            if (ACTION_UPDATE.equals(action)) {
                applySettings();
                return START_STICKY;
            }
        }
        return START_STICKY;
    }

    private void afterBoot() {
        applySettings();
        updateNotification();
    }

    // ---------------- 设置注入 ----------------

    private void applySettings() {
        if (reader != null) reader.setPrimaryOnly(Config.multitouchOpt(this));
        if (nativeView != null) {
            nativeView.updateSettings(Config.scale(this), Config.opacity(this),
                    Config.trailSpeed(this), Config.clickSpeed(this),
                    Config.fps(this), Config.colorRgb(this), Config.alwaysTrail(this));
            return;
        }
        if (!pageReady) return;
        boolean alwaysTrail = Config.alwaysTrail(this);
        // 上游逻辑里常驻拖尾只在 mouse 模式下生效，这里顺着它的判定走
        String mode = alwaysTrail ? "mouse" : "touch";
        String js = "window.setInputContext('" + mode + "'," + alwaysTrail + ");"
                + "window.updateColor('" + Config.colorRgb(this) + "');"
                + "window.updateEffectSettings("
                + Config.scale(this) + ","
                + Config.opacity(this) + ","
                + Config.trailSpeed(this) + ","
                + Config.clickSpeed(this) + ");"
                + "window.setTargetFps(" + Config.fps(this) + ");";
        eval(js);
        Log.i(TAG, "settings applied: " + js);
    }

    private void startFpsPolling() {
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!RUNNING) return;
                readFps();
                ui.postDelayed(this, 2000);
            }
        }, 2000);
    }

    private void readFps() {
        if (nativeView != null) {
            REAL_FPS = nativeView.getFps();
            return;
        }
        if (web == null || !pageReady) return;
        web.evaluateJavascript("window.__basRealFps || 0", value -> {
            if (value == null) return;
            try {
                String v = value.replace("\"", "").trim();
                REAL_FPS = Float.parseFloat(v);
            } catch (Exception ignored) {
            }
        });
        if (reader != null) DEVICE_INFO = reader.getDeviceInfo();
    }

    // ---------------- 悬浮窗 ----------------

    private int currentRotation() {
        try {
            Display d = wm.getDefaultDisplay();
            return d.getRotation();
        } catch (Exception e) {
            return Surface.ROTATION_0;
        }
    }

    private void onDisplayChanged() {
        int rot = currentRotation();
        if (reader != null) reader.setRotation(rot);
        ui.postDelayed(this::updateMetrics, 400);
        if (overlayView != null) {
            overlayView.requestLayout();
            web.postDelayed(() -> eval("window.dispatchEvent(new Event('resize'));"), 120);
        }
        Log.i(TAG, "display changed, rotation=" + rot);
    }

    private void createOverlay() {
        if (Config.renderer(this) == 1) {
            createNativeOverlay();
        } else {
            createWebOverlay();
        }
    }

    private WindowManager.LayoutParams overlayParams() {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                flags,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        return lp;
    }

    private void createNativeOverlay() {
        nativeView = new NativeSparkView(getApplicationContext());
        overlayView = nativeView;
        nativeView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            updateMetrics();
            nativeView.postInvalidateOnAnimation();
        });
        try {
            wm.addView(nativeView, overlayParams());
        } catch (Exception e) {
            Log.e(TAG, "addView(native) failed", e);
            stopSelf();
            return;
        }
        nativeView.updateSettings(Config.scale(this), Config.opacity(this),
                Config.trailSpeed(this), Config.clickSpeed(this),
                Config.fps(this), Config.colorRgb(this), Config.alwaysTrail(this));
        DLog.i(TAG, "native overlay attached");
        ui.postDelayed(this::updateMetrics, 400);
        ui.postDelayed(() -> { if (nativeView != null) nativeView.postInvalidateOnAnimation(); }, 800);
    }

    private void createWebOverlay() {
        web = new WebView(getApplicationContext());
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setFocusable(false);
        web.setFocusableInTouchMode(false);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setDomStorageEnabled(false);
        s.setMediaPlaybackRequiresUserGesture(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(FPS_LIMITER_JS, null);
                pageReady = true;
                applySettings();
                Log.i(TAG, "page ready");
            }
        });
        web.loadUrl("file:///android_asset/index.html");

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                flags,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }

        web.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateMetrics());

        try {
            overlayView = web;
            wm.addView(web, lp);
        } catch (Exception e) {
            Log.e(TAG, "addView failed", e);
            stopSelf();
        }
        ui.postDelayed(this::updateMetrics, 400);
    }

    /** 重新量一次：屏幕真实尺寸 + 悬浮窗在屏幕上的位置/尺寸 */
    private void updateMetrics() {
        try {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            dispW = dm.widthPixels;
            dispH = dm.heightPixels;
            if (overlayView != null) {
                int[] loc = new int[2];
                overlayView.getLocationOnScreen(loc);
                webLeft = loc[0];
                webTop = loc[1];
                webW = overlayView.getWidth();
                webH = overlayView.getHeight();
            }
            DLog.i(TAG, "metrics screen=" + dispW + "x" + dispH
                    + " overlay=" + webW + "x" + webH + " @" + webLeft + "," + webTop);
        } catch (Exception e) {
            Log.w(TAG, "updateMetrics failed", e);
        }
    }

    /** 屏幕百分比 → 悬浮窗内百分比 */
    private float toWebX(float p) {
        if (webW <= 0 || dispW <= 0) return p;
        return clamp01((p * dispW - webLeft) / (float) webW);
    }

    private float toWebY(float p) {
        if (webH <= 0 || dispH <= 0) return p;
        return clamp01((p * dispH - webTop) / (float) webH);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    // ---------------- 通知 ----------------

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "BASpark 特效", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent stop = new Intent(this, OverlayService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("BASpark 特效运行中")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.mipmap.ic_launcher),
                        "停止", pi).build())
                .build();
    }

    private void startForegroundCompat() {
        Notification n = buildNotification("点击屏幕即触发粒子效果");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTI_ID, n);
        }
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        String text = (reader != null ? reader.getDeviceInfo() : "")
                + "  ·  " + Config.FPS_NAMES[Config.fpsIndex(this)] + " fps";
        nm.notify(NOTI_ID, buildNotification(text));
    }

    // ---------------- TouchReader.Sink ----------------

    /** 无障碍服务回调入口 */
    static void dispatchExternalClick(float px, float py) {
        OverlayService s = sRef;
        if (s != null) s.onDown(px, py);
    }

    /** 白名单闸门：列表为空表示全局生效，否则只对列表内应用放行 */
    private void whitelistTick() {
        if (!RUNNING) return;
        try {
            java.util.Set<String> wl = Config.whitelist(this);
            boolean allowed = true;
            if (!wl.isEmpty()) {
                String fg = AppFilter.foreground(this);
                allowed = fg == null
                        || wl.contains(fg)
                        || getPackageName().equals(fg)
                        || fg.contains("systemui")
                        || fg.contains("launcher");
            }
            if (allowed && suppressed) {
                suppressed = false;
                setRenderingPaused(false);
            } else if (!allowed && !suppressed) {
                suppressed = true;
                setRenderingPaused(true);
            }
        } catch (Exception ignored) {
        }
        ui.postDelayed(this::whitelistTick, 900);
    }

    private void setRenderingPaused(boolean paused) {
        if (nativeView != null) {
            nativeView.setPaused(paused);
        } else {
            eval("window.setRenderingPaused(" + paused + ");");
        }
    }

    private float toViewX(float p) {
        return toWebX(p) * (webW > 0 ? webW : getResources().getDisplayMetrics().widthPixels);
    }

    private float toViewY(float p) {
        return toWebY(p) * (webH > 0 ? webH : getResources().getDisplayMetrics().heightPixels);
    }

    @Override
    public void onDown(float px, float py) {
        if (suppressed) return;
        if (nativeView != null) {
            nativeView.onDown(toViewX(px), toViewY(py));
            return;
        }
        eval("window.externalBoom(" + fmt(toWebX(px)) + "," + fmt(toWebY(py)) + ");");
    }

    @Override
    public void onMove(float px, float py) {
        if (suppressed) return;
        if (nativeView != null) {
            nativeView.onMove(toViewX(px), toViewY(py));
            return;
        }
        eval("window.externalMove(" + fmt(toWebX(px)) + "," + fmt(toWebY(py)) + ");");
    }

    @Override
    public void onUp() {
        if (suppressed) return;
        if (nativeView != null) {
            nativeView.onUp();
            return;
        }
        eval("window.externalUp();");
    }

    private static String fmt(float v) {
        return String.format(Locale.US, "%.4f", v);
    }

    private void eval(final String js) {
        if (web == null) return;
        ui.post(() -> {
            if (web == null) return;
            try {
                web.evaluateJavascript(js, null);
            } catch (Exception ignored) {
            }
        });
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        onDisplayChanged();
    }

    @Override
    public void onDestroy() {
        RUNNING = false;
        sRef = null;
        ui.removeCallbacksAndMessages(null);
        REAL_FPS = 0f;
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {
        }
        if (reader != null) {
            reader.stop();
            reader = null;
        }
        if (overlayView != null) {
            try {
                wm.removeView(overlayView);
            } catch (Exception ignored) {
            }
            if (web != null) {
                try {
                    web.destroy();
                } catch (Exception ignored) {
                }
            }
            web = null;
            nativeView = null;
            overlayView = null;
        }
        stopForeground(true);
        Log.i(TAG, "overlay service destroyed");
        super.onDestroy();
    }

    /**
     * 帧率限制器：上游动画是按 deltaTime 推进的，所以跳帧只影响顺滑度，不会改变速度。
     * 不修改上游 index.html，纯注入。
     */
    private static final String FPS_LIMITER_JS =
            "(function(){"
                    + "if(window.__basInstalled)return;window.__basInstalled=true;"
                    + "var capMs=0,last=performance.now(),poll=0,frames=0,statStart=performance.now();"
                    + "window.__basRealFps=0;"
                    + "window.setTargetFps=function(fps){fps=Number(fps)||0;"
                    + "capMs=(fps>=20&&fps<=240)?(1000/fps):0;};"
                    + "var orig=window.scheduleNextAnimationFrame;"
                    + "window.scheduleNextAnimationFrame=function(){"
                    + "if(!capMs){last=performance.now();return orig();}"
                    + "var now=performance.now();"
                    + "if(now-last>=capMs*0.82){last=now;return orig();}"
                    + "if(poll)return;"
                    + "poll=requestAnimationFrame(function(){poll=0;window.scheduleNextAnimationFrame();});"
                    + "};"
                    + "if(window.spark&&window.spark._renderToMain){"
                    + "var rm=window.spark._renderToMain.bind(window.spark);"
                    + "window.spark._renderToMain=function(r){"
                    + "frames++;var t=performance.now();"
                    + "if(t-statStart>=1000){window.__basRealFps=frames*1000/(t-statStart);"
                    + "frames=0;statStart=t;}"
                    + "return rm(r);};}"
                    + "})();";
}