package com.miu.baspark;

import android.content.Context;
import android.content.SharedPreferences;

/** 所有可调参数集中在这里，MainActivity 写、OverlayService 读 */
public final class Config {

    public static final String[] COLOR_NAMES = {"星空蓝", "樱粉", "薰衣草", "碧青", "暖金", "月白"};
    public static final String[] COLOR_RGB = {
            "45,175,255", "255,120,180", "170,120,255", "90,230,220", "255,200,90", "235,235,245"
    };
    public static final int[] FPS_OPTIONS = {0, 30, 60, 90, 120, 144, 165};
    public static final String[] FPS_NAMES = {"跟随屏幕", "30", "60", "90", "120", "144", "165"};

    private Config() {
    }

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("baspark", Context.MODE_PRIVATE);
    }

    // ---- 读取 ----
    public static float scale(Context c) {
        return sp(c).getFloat("scale", 1.3f);
    }

    public static float opacity(Context c) {
        return sp(c).getFloat("opacity", 1.0f);
    }

    public static float trailSpeed(Context c) {
        return sp(c).getFloat("trail", 1.0f);
    }

    public static float clickSpeed(Context c) {
        return sp(c).getFloat("click", 1.0f);
    }

    public static int fpsIndex(Context c) {
        return sp(c).getInt("fps", 0);
    }

    public static int fps(Context c) {
        int i = fpsIndex(c);
        if (i < 0 || i >= FPS_OPTIONS.length) return 0;
        return FPS_OPTIONS[i];
    }

    public static int colorIndex(Context c) {
        int i = sp(c).getInt("color", 0);
        if (i < 0 || i >= COLOR_RGB.length) i = 0;
        return i;
    }

    public static String colorRgb(Context c) {
        return COLOR_RGB[colorIndex(c)];
    }

    public static boolean alwaysTrail(Context c) {
        return sp(c).getBoolean("always_trail", false);
    }

    /** 渲染实现：0 = WebView（上游原版引擎），1 = 原生 View */
    public static int renderer(Context c) {
        return sp(c).getInt("renderer", 0);
    }

    /** 实验性 HDR 高光（仅原生渲染生效） */
    public static boolean hdr(Context c) {
        return sp(c).getBoolean("hdr", false);
    }

    /** HDR 高光倍率（0.3 ~ 3.0） */
    public static float hdrGain(Context c) {
        return sp(c).getFloat("hdr_gain", 1f);
    }

    /** 从最近任务卡片里隐藏 */
    public static boolean hideRecents(Context c) {
        return sp(c).getBoolean("hide_recents", false);
    }

    public static boolean autoStart(Context c) {
        return sp(c).getBoolean("autostart", false);
    }

    /** 多指拖拽优化：只让第一根手指牵引拖尾，默认开启 */
    public static boolean multitouchOpt(Context c) {
        return sp(c).getBoolean("multitouch_opt", true);
    }

    /** 触摸来源：0 = root 读 /dev/input，1 = 无障碍点击监听 */
    public static int source(Context c) {
        return sp(c).getInt("source", 0);
    }

    public static java.util.Set<String> whitelist(Context c) {
        java.util.Set<String> s = sp(c).getStringSet("whitelist", null);
        return s == null ? new java.util.HashSet<>() : new java.util.HashSet<>(s);
    }

    public static void setWhitelist(Context c, java.util.Set<String> set) {
        sp(c).edit().putStringSet("whitelist", new java.util.HashSet<>(set)).apply();
    }

    // ---- 写入 ----
    public static void setFloat(Context c, String key, float v) {
        sp(c).edit().putFloat(key, v).apply();
    }

    public static void setInt(Context c, String key, int v) {
        sp(c).edit().putInt(key, v).apply();
    }

    public static void setBool(Context c, String key, boolean v) {
        sp(c).edit().putBoolean(key, v).apply();
    }
}