package com.miu.baspark;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;

/**
 * Material 3 界面。
 * 没有 AndroidX/Material Components 依赖，所以用原生 View 手写 M3 造型：
 * 语义色 token + 全圆角按钮 + 28dp 卡片 + 状态层涟漪 + 120Hz 友好的排版层级。
 */
public class MainActivity extends Activity {

    // ---------- M3 颜色角色 ----------
    private int cPrimary, cOnPrimary, cPrimaryContainer, cOnPrimaryContainer;
    private int cSecondaryContainer, cOnSecondaryContainer;
    private int cSurface, cSurfaceContainer, cSurfaceContainerHigh, cSurfaceVariant;
    private int cOnSurface, cOnSurfaceVariant, cOutline, cOutlineVariant;
    private int cStatusCard, cOnStatusCard;
    private LinearLayout wlList;
    private LinearLayout rendererRow;
    private TextView fgText;

    private float dp;
    private TextView statusText, statusPill;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean polling = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        buildPalette();

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(cSurface);
        scroll.setPadding(0, dp(8), 0, dp(28));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), 0);
        scroll.addView(root);
        setContentView(scroll);

        buildHeader(root);
        buildStatusCard(root);
        buildActionButtons(root);
        buildPermissionCard(root);
        buildRendererCard(root);
        buildInputCard(root);
        buildWhitelistCard(root);
        buildAppearanceCard(root);
        buildMotionCard(root);
        buildPerformanceCard(root);
        buildFooter(root);

        refresh();
    }

    // ================= 构建各区块 =================

    private void buildHeader(LinearLayout root) {
        TextView title = new TextView(this);
        title.setText("BASpark");
        title.setTextSize(34);
        title.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        title.setTextColor(cOnSurface);
        title.setLetterSpacing(-0.02f);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Blue Archive 风格点击特效");
        sub.setTextSize(15);
        sub.setTextColor(cOnSurfaceVariant);
        sub.setPadding(0, dp(2), 0, dp(18));
        root.addView(sub);
    }

    private void buildStatusCard(LinearLayout root) {
        LinearLayout card = card(root, cStatusCard, 28);

        statusText = new TextView(this);
        statusText.setTextColor(cOnStatusCard);
        statusText.setTextSize(14);
        statusText.setLineSpacing(dp(3), 1f);
        card.addView(statusText);

        LinearLayout pillRow = new LinearLayout(this);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        pillRow.setPadding(0, dp(12), 0, 0);

        statusPill = new TextView(this);
        statusPill.setTextSize(12);
        statusPill.setTypeface(Typeface.DEFAULT_BOLD);
        statusPill.setPadding(dp(12), dp(5), dp(12), dp(5));
        pillRow.addView(statusPill);
        card.addView(pillRow);
    }

    private void buildActionButtons(LinearLayout root) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, dp(18));
        root.addView(row);

        TextView start = button("启动特效", BTN_FILLED);
        start.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                toast("请先授予悬浮窗权限");
                return;
            }
            startForegroundService(new Intent(this, OverlayService.class));
            toast("已启动，回到桌面点几下试试～");
            ui.postDelayed(this::refresh, 600);
        });
        row.addView(start, weightParams(1, 0, 0, dp(6)));

        TextView stop = button("停止", BTN_OUTLINED);
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, OverlayService.class));
            ui.postDelayed(this::refresh, 300);
        });
        row.addView(stop, weightParams(1, dp(6), 0, 0));
    }

    private void buildPermissionCard(LinearLayout root) {
        sectionLabel(root, "权限");
        LinearLayout card = card(root, cSurfaceContainer, 24);

        rowItem(card, "悬浮窗权限", "在其他应用上层绘制特效",
                Settings.canDrawOverlays(this) ? "已授予" : "未授予", true,
                v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))));

        divider(card);

        rowItem(card, "通知权限", "前台服务需要常驻通知",
                Build.VERSION.SDK_INT >= 33 ? "申请" : "无需", true,
                v -> {
                    if (Build.VERSION.SDK_INT >= 33) {
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
                    } else {
                        toast("当前系统无需申请");
                    }
                });

        divider(card);

        rowItem(card, "Root 权限", "读取 /dev/input 触摸事件", "检测", false,
                v -> checkRoot());
    }

    private void buildRendererCard(LinearLayout root) {
        sectionLabel(root, "渲染实现");
        LinearLayout card = card(root, cSurfaceContainer, 24);

        TextView tip = new TextView(this);
        tip.setText("WebView 用的是上游原版 Canvas 引擎，保真度最高；"
                + "原生 View 是同一套数学的 Java 重写，省内存、帧率更稳，个别细节做了简化。");
        tip.setTextSize(12);
        tip.setTextColor(cOnSurfaceVariant);
        tip.setLineSpacing(dp(2), 1f);
        tip.setPadding(0, 0, 0, dp(12));
        card.addView(tip);

        rendererRow = new LinearLayout(this);
        rendererRow.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(rendererRow);
        rebuildRendererButtons();
    }

    private void rebuildRendererButtons() {
        if (rendererRow == null) return;
        rendererRow.removeAllViews();
        int cur = Config.renderer(this);

        TextView web = button("WebView", cur == 0 ? BTN_FILLED : BTN_OUTLINED);
        web.setOnClickListener(v -> setRenderer(0));
        rendererRow.addView(web, weightParams(1, 0, 0, dp(6)));

        TextView nat = button("原生 View", cur == 1 ? BTN_FILLED : BTN_OUTLINED);
        nat.setOnClickListener(v -> setRenderer(1));
        rendererRow.addView(nat, weightParams(1, dp(6), 0, dp(6)));
    }

    private void setRenderer(int value) {
        if (Config.renderer(this) == value) return;
        Config.setInt(this, "renderer", value);
        rebuildRendererButtons();
        toast("已切换到" + (value == 1 ? "原生 View" : "WebView") + "，正在重启特效…");
        if (OverlayService.RUNNING) {
            stopService(new Intent(this, OverlayService.class));
            ui.postDelayed(() -> startForegroundService(
                    new Intent(this, OverlayService.class)), 700);
        }
    }

    private void buildInputCard(LinearLayout root) {
        sectionLabel(root, "输入与兼容");
        LinearLayout card = card(root, cSurfaceContainer, 24);

        switchRow(card, "多指拖拽优化", "只让第一根手指牵引尾迹，关掉就是「翻绳」玩法",
                Config.multitouchOpt(this), b -> Config.setBool(this, "multitouch_opt", b != 0));

        divider(card);

        boolean accOn = BasAccessibilityService.INSTANCE != null;
        switchRow(card, "使用无障碍模式", "免 root，但只能拿到控件级点击坐标",
                Config.source(this) == 1,
                b -> Config.setInt(this, "source", b != 0 ? 1 : 0));

        if (!accOn) {
            rowItem(card, "开启无障碍服务", "未开启时该模式不会产生任何效果", "去设置", true,
                    v -> {
                        toast("在「已安装的应用 / 已下载的服务」里找到 BASpark");
                        try {
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        } catch (Exception ignored) {
                        }
                    });
        }
    }

    private void buildWhitelistCard(LinearLayout root) {
        sectionLabel(root, "应用白名单");
        LinearLayout card = card(root, cSurfaceContainer, 24);

        TextView tip = new TextView(this);
        tip.setText("列表为空 = 所有应用都显示特效；一旦添加，就只在这些应用里显示。");
        tip.setTextSize(12);
        tip.setTextColor(cOnSurfaceVariant);
        tip.setPadding(0, 0, 0, dp(6));
        card.addView(tip);

        fgText = new TextView(this);
        fgText.setTextSize(14);
        fgText.setTextColor(cOnSurface);
        fgText.setPadding(0, dp(6), 0, dp(6));
        card.addView(fgText);

        TextView addBtn = button("把当前应用加入 / 移出白名单", BTN_TONAL);
        addBtn.setOnClickListener(v -> toggleForeground());
        card.addView(addBtn);

        wlList = new LinearLayout(this);
        wlList.setOrientation(LinearLayout.VERTICAL);
        wlList.setPadding(0, dp(8), 0, 0);
        card.addView(wlList);

        refreshWhitelist();
    }

    private void toggleForeground() {
        String fg = AppFilter.foreground(this);
        if (fg == null) {
            toast("读不到前台应用，先授予「使用情况访问」");
            return;
        }
        if (getPackageName().equals(fg)) {
            toast("先切到目标应用，再回来点这个按钮哦");
            return;
        }
        java.util.Set<String> wl = Config.whitelist(this);
        if (wl.contains(fg)) {
            wl.remove(fg);
            toast("已移出：" + AppFilter.label(this, fg));
        } else {
            wl.add(fg);
            toast("已加入：" + AppFilter.label(this, fg));
        }
        Config.setWhitelist(this, wl);
        refreshWhitelist();
    }

    private void refreshWhitelist() {
        if (wlList == null) return;
        wlList.removeAllViews();
        java.util.Set<String> wl = Config.whitelist(this);
        if (fgText != null) {
            if (!AppFilter.hasUsageAccess(this)) {
                fgText.setText("尚未授予「使用情况访问」，白名单无法判断前台应用");
            } else {
                String fg = AppFilter.foreground(this);
                fgText.setText("当前前台："
                        + (fg == null ? "未知" : AppFilter.label(this, fg)));
            }
        }
        if (wl.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("（白名单为空）");
            empty.setTextSize(12);
            empty.setTextColor(cOnSurfaceVariant);
            empty.setPadding(dp(4), dp(6), 0, 0);
            wlList.addView(empty);
            return;
        }
        for (String pkg : wl) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(6), dp(4), dp(6));

            TextView t = new TextView(this);
            t.setText(AppFilter.label(this, pkg));
            t.setTextSize(14);
            t.setTextColor(cOnSurface);
            row.addView(t, weightParams(1, 0, 0, 0));

            TextView del = new TextView(this);
            del.setText("移除");
            del.setTextSize(13);
            del.setTextColor(cPrimary);
            del.setPadding(dp(10), dp(6), dp(10), dp(6));
            del.setOnClickListener(v -> {
                java.util.Set<String> cur = Config.whitelist(this);
                cur.remove(pkg);
                Config.setWhitelist(this, cur);
                refreshWhitelist();
            });
            row.addView(del);
            wlList.addView(row);
        }
    }

    private ColorStateList stateColors(int checkedColor, int normalColor) {
        return new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, new int[]{}},
                new int[]{checkedColor, normalColor});
    }

    private void buildAppearanceCard(LinearLayout root) {
        sectionLabel(root, "外观");
        LinearLayout card = card(root, cSurfaceContainer, 24);

        slider(card, "缩放大小", 5, 30, (int) (Config.scale(this) * 10), 10, null,
                p -> Config.setFloat(this, "scale", p / 10f));

        slider(card, "颜色", 0, Config.COLOR_NAMES.length - 1, Config.colorIndex(this), 1,
                Config.COLOR_NAMES, p -> Config.setInt(this, "color", p));

        slider(card, "不透明度", 10, 100, (int) (Config.opacity(this) * 100), 100, null,
                p -> Config.setFloat(this, "opacity", p / 100f));
    }

    private void buildMotionCard(LinearLayout root) {
        sectionLabel(root, "动效");
        LinearLayout card = card(root, cSurfaceContainer, 24);

        slider(card, "拖尾速度", 20, 300, (int) (Config.trailSpeed(this) * 100), 100, null,
                p -> Config.setFloat(this, "trail", p / 100f));

        slider(card, "点击爆裂速度", 20, 300, (int) (Config.clickSpeed(this) * 100), 100, null,
                p -> Config.setFloat(this, "click", p / 100f));

        switchRow(card, "常驻拖尾", "不按下时也保留尾迹", Config.alwaysTrail(this),
                b -> Config.setBool(this, "always_trail", b != 0));
    }

    private void buildPerformanceCard(LinearLayout root) {
        sectionLabel(root, "性能");
        LinearLayout card = card(root, cSurfaceContainer, 24);

        slider(card, "渲染帧率", 0, Config.FPS_OPTIONS.length - 1, Config.fpsIndex(this), 1,
                Config.FPS_NAMES, p -> Config.setInt(this, "fps", p));

        TextView hint = new TextView(this);
        hint.setText("帧率只能限制，不能超过面板刷新率。165Hz 屏设 165 为满帧，设 60 为省电档；"
                + "若实测远低于设定，通常是系统 WebView 被锁在 60Hz。");
        hint.setTextSize(12);
        hint.setTextColor(cOnSurfaceVariant);
        hint.setLineSpacing(dp(2), 1f);
        hint.setPadding(0, 0, 0, dp(14));
        card.addView(hint);

        divider(card);

        switchRow(card, "开机自动启动", "开机后自动恢复悬浮层", Config.autoStart(this),
                b -> Config.setBool(this, "autostart", b != 0));
    }

    private void buildFooter(LinearLayout root) {
        TextView foot = new TextView(this);
        foot.setText("下拉通知栏 → 编辑 → 添加「BASpark」磁贴可一键开关\n"
                + "粒子引擎 © DoomVoss/BASpark · MIT · 图标沿用上游 logo.png");
        foot.setTextSize(11);
        foot.setTextColor(cOnSurfaceVariant);
        foot.setGravity(Gravity.CENTER_HORIZONTAL);
        foot.setLineSpacing(dp(2), 1f);
        foot.setPadding(dp(8), dp(20), dp(8), 0);
        root.addView(foot);
    }

    // ================= M3 组件工厂 =================

    private static final int BTN_FILLED = 0;
    private static final int BTN_TONAL = 1;
    private static final int BTN_OUTLINED = 2;

    private TextView button(String text, int style) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(20), dp(14), dp(20), dp(14));
        b.setTypeface(Typeface.DEFAULT_BOLD);

        int bg, fg, stroke = 0;
        if (style == BTN_FILLED) {
            bg = cPrimary;
            fg = cOnPrimary;
        } else if (style == BTN_TONAL) {
            bg = cSecondaryContainer;
            fg = cOnSecondaryContainer;
        } else {
            bg = Color.TRANSPARENT;
            fg = cPrimary;
            stroke = cOutline;
        }
        b.setTextColor(fg);
        b.setBackground(ripple(rounded(bg, 100), style == BTN_OUTLINED ? cOutline : 0));
        if (style == BTN_OUTLINED) {
            GradientDrawable d = rounded(Color.TRANSPARENT, 100);
            d.setStroke((int) dp(1), stroke);
            b.setBackground(ripple(d, cOutline));
        }
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private LinearLayout card(LinearLayout parent, int color, int radiusDp) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        card.setBackground(rounded(color, radiusDp));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        parent.addView(card, lp);
        return card;
    }

    private void sectionLabel(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.12f);
        t.setTextColor(cPrimary);
        t.setPadding(dp(6), dp(10), 0, dp(8));
        parent.addView(t);
    }

    private void divider(LinearLayout parent) {
        View v = new View(this);
        v.setBackgroundColor(cOutlineVariant);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) dp(0.7f)));
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(6);
        parent.addView(v, lp);
    }

    private final java.util.List<Runnable> rowRefreshers = new java.util.ArrayList<>();

    private void rowItem(LinearLayout parent, String title, String subtitle,
                         String trailing, boolean chevron, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(12), dp(4), dp(12));
        row.setBackground(ripple(rounded(0x00000000, 16), cPrimary));
        row.setClickable(true);
        row.setOnClickListener(click);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, weightParams(1, 0, 0, 0));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(cOnSurface);
        texts.addView(t);

        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextSize(12);
        s.setTextColor(cOnSurfaceVariant);
        s.setPadding(0, dp(2), 0, 0);
        texts.addView(s);

        TextView right = new TextView(this);
        right.setText(trailing);
        right.setTextSize(13);
        right.setTextColor(cPrimary);
        right.setPadding(dp(8), 0, 0, 0);
        row.addView(right);

        if (chevron) {
            TextView c = new TextView(this);
            c.setText("›");
            c.setTextSize(18);
            c.setTextColor(cOnSurfaceVariant);
            c.setPadding(dp(6), 0, 0, 0);
            row.addView(c);
        }
        parent.addView(row);
    }

    private interface ValueSink {
        void onChanged(int value);
    }

    /**
     * @param div   显示除数（10 → 1.3；100 → 1.00 显示为百分比）
     * @param names 非空时用名字替代数值
     */
    private void slider(LinearLayout card, String label, int min, int max, int value,
                        int div, String[] names, ValueSink sink) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(4), 0, dp(10));
        card.addView(box);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(head);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(14);
        name.setTextColor(cOnSurfaceVariant);
        head.addView(name, weightParams(1, 0, 0, 0));

        TextView val = new TextView(this);
        val.setTextSize(14);
        val.setTypeface(Typeface.DEFAULT_BOLD);
        val.setTextColor(cPrimary);
        head.addView(val);

        SeekBar sb = new SeekBar(this);
        styleSlider(sb);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, value - min)));
        box.addView(sb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        Runnable updater = () -> {
            int p = sb.getProgress() + min;
            String shown;
            if (names != null && p >= 0 && p < names.length) {
                shown = names[p];
            } else if (div == 10) {
                shown = String.format(Locale.US, "%.1f×", p / 10f);
            } else if (div == 100) {
                shown = (p) + "%";
            } else {
                shown = String.valueOf(p);
            }
            val.setText(shown);
        };
        updater.run();

        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                updater.run();
                if (fromUser) {
                    sink.onChanged(progress + min);
                    notifyService();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });

        if (sink == null) {
            sb.setEnabled(false);
        }
    }

    /** 给原生 SeekBar 套上 M3 的轨道与拇指 */
    private void styleSlider(SeekBar sb) {
        sb.setSplitTrack(false);
        sb.setThumbOffset(0);

        GradientDrawable track = rounded(cSurfaceVariant, 100);
        track.setSize(0, (int) dp(4));
        GradientDrawable progress = rounded(cPrimary, 100);
        progress.setSize(0, (int) dp(4));

        ClipDrawable clip = new ClipDrawable(progress, Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{track, clip});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        sb.setProgressDrawable(layers);

        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(cPrimary);
        thumb.setSize((int) dp(18), (int) dp(18));
        sb.setThumb(thumb);
        sb.setMinimumHeight((int) dp(30));
    }

    private void switchRow(LinearLayout card, String title, String subtitle,
                           boolean checked, ValueSink onOff) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        card.addView(row);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, weightParams(1, 0, dp(8), 0));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(cOnSurface);
        texts.addView(t);

        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextSize(12);
        s.setTextColor(cOnSurfaceVariant);
        s.setPadding(0, dp(2), 0, 0);
        texts.addView(s);

        android.widget.Switch sw = new android.widget.Switch(this);
        sw.setChecked(checked);
        sw.setThumbTintList(stateColors(cOnPrimary, cOutline));
        sw.setTrackTintList(stateColors(cPrimary, cSurfaceVariant));
        sw.setOnCheckedChangeListener((v, isChecked) -> {
            onOff.onChanged(isChecked ? 1 : 0);
            notifyService();
        });
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ================= 交互与状态 =================

    private void notifyService() {
        if (OverlayService.RUNNING) {
            startService(new Intent(this, OverlayService.class)
                    .setAction(OverlayService.ACTION_UPDATE));
        }
        refresh();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void checkRoot() {
        toast("正在检测 root…");
        new Thread(() -> {
            String result;
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = r.readLine();
                p.waitFor();
                result = (line != null && line.contains("uid=0")) ? "Root 可用 ✓" : "Root 不可用 ✗";
            } catch (Exception e) {
                result = "Root 不可用 ✗";
            }
            final String out = result;
            ui.post(() -> toast(out));
        }).start();
    }

    private void refresh() {
        if (statusText == null) return;
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        int hz = 0;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                hz = Math.round(getDisplay().getRefreshRate());
            } catch (Exception ignored) {
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("屏幕 ").append(w).append(" × ").append(h)
                .append("　·　").append(hz).append(" Hz");
        if (OverlayService.RUNNING) {
            sb.append("\n实测绘制 ")
                    .append(String.format(Locale.US, "%.1f", OverlayService.REAL_FPS))
                    .append(" fps");
            sb.append("\n输入源 ").append(OverlayService.DEVICE_INFO);
        } else {
            sb.append("\n悬浮层未运行");
        }
        statusText.setText(sb.toString());

        boolean running = OverlayService.RUNNING;
        statusPill.setText(running ? "运行中" : "已停止");
        statusPill.setTextColor(running ? cOnPrimary : cOnSurfaceVariant);
        statusPill.setBackground(rounded(running ? cPrimary : cSurfaceVariant, 100));
    }

    // ================= 颜色 / 工具 =================

    private void buildPalette() {
        boolean dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        int dynPrimary = sysColor("system_accent1_600", 0);
        int dynPrimaryContainer = sysColor("system_accent1_100", 0);
        int dynSecondaryContainer = sysColor("system_accent2_100", 0);
        int dynNeutralContainer = sysColor("system_neutral1_50", 0);

        if (dynPrimary == 0) {
            dynPrimary = dark ? 0xFFD0BCFF : 0xFF6750A4;
            dynPrimaryContainer = dark ? 0xFF4F378B : 0xFFEADDFF;
            dynSecondaryContainer = dark ? 0xFF4A4458 : 0xFFE8DEF8;
        }

        cPrimary = dynPrimary;
        cOnPrimary = dark ? 0xFF381E72 : 0xFFFFFFFF;
        cPrimaryContainer = dynPrimaryContainer;
        cOnPrimaryContainer = dark ? 0xFFEADDFF : 0xFF21005D;
        cSecondaryContainer = dynSecondaryContainer;
        cOnSecondaryContainer = dark ? 0xFFE8DEF8 : 0xFF1D192B;

        cSurface = dark ? 0xFF141218 : 0xFFFEF7FF;
        cSurfaceContainer = dark ? 0xFF211F26 : 0xFFF3EDF7;
        cSurfaceContainerHigh = dark ? 0xFF2B2930 : 0xFFECE6F0;
        cSurfaceVariant = dark ? 0xFF49454F : 0xFFE7E0EC;
        cOnSurface = dark ? 0xFFE6E0E9 : 0xFF1D1B20;
        cOnSurfaceVariant = dark ? 0xFFCAC4D0 : 0xFF49454F;
        cOutline = dark ? 0xFF938F99 : 0xFF79747E;
        cOutlineVariant = dark ? 0xFF49454F : 0xFFCAC4D0;
        // 深色下 primaryContainer 是低明度色，直接用会与浅色文字糊在一起，
        // 所以状态卡改用 surfaceContainerHigh + onSurface，保证对比度。
        if (dark) {
            cStatusCard = cSurfaceContainerHigh;
            cOnStatusCard = cOnSurface;
        } else {
            cStatusCard = cPrimaryContainer;
            cOnStatusCard = cOnPrimaryContainer;
        }
        if (dynNeutralContainer == 0) {
            // no-op，保持上面计算好的容器色
        }
    }

    private int sysColor(String name, int fallback) {
        try {
            int id = getResources().getIdentifier(name, "color", "android");
            if (id == 0) return fallback;
            return getResources().getColor(id, getTheme());
        } catch (Exception e) {
            return fallback;
        }
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp >= 100 ? dp(100) : dp(radiusDp));
        return d;
    }

    private RippleDrawable ripple(Drawable content, int rippleColor) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(24));
        int c = rippleColor == 0 ? cPrimary : rippleColor;
        return new RippleDrawable(ColorStateList.valueOf((c & 0x00FFFFFF) | 0x22000000),
                content, mask);
    }

    private LinearLayout.LayoutParams weightParams(float weight, int start, int end, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.setMarginStart(start);
        lp.setMarginEnd(end);
        lp.bottomMargin = bottom;
        return lp;
    }

    private int dp(int v) {
        return (int) (v * dp);
    }

    private int dp(float v) {
        return (int) (v * dp);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        refreshWhitelist();
        polling = true;
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (!polling) return;
                refresh();
                ui.postDelayed(this, 1000);
            }
        });
    }

    @Override
    protected void onPause() {
        polling = false;
        super.onPause();
    }
}