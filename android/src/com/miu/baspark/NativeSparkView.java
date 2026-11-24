package com.miu.baspark;

import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.LinearGradient;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 原生 Canvas 版粒子引擎 —— 把上游 index.html 里的那套数学逐段翻译过来。
 *
 * 和 WebView 版的差别：
 *  - 没有 JS 解释、没有 Chromium 合成，内存小很多
 *  - 每帧全屏 clear + 直接绘制（上游用的是离屏缓冲 + 脏矩形，这里先不做，保持可读性）
 *  - 尺寸单位从 CSS px 换成 dp：上游 9px 线宽在这里就是 9dp
 */
public class NativeSparkView extends View {

    // ---- 上游常量 ----
    private static final float R_ADD_RATE = 26f;
    private static final float FILLED_MAX_LIFE = 16f;
    private static final float RINGS_MAX_LIFE = 23f;
    private static final int SEG_NUM = 10;
    private static final float RING_MIN_W = 0.4f;
    private static final float RING_MAX_W = 3.3f;
    private static final float RING_LEN_STOP_ADD = 0.1f;
    private static final float RING_LEN_START_DIM = 0.4f;
    private static final float RING_LEN = (float) (1.1 * Math.PI);
    private static final float[] RING_RS = {0f, 0.03f, 0.06f};
    private static final float[] RING_ROUND = {0f, 1f, 1.5f, 2f};
    private static final int SPARK_COUNT = 4;

    private static final float BASE_FRAME_MS = 1000f / 60f;
    private static final float MAX_DELTA_MS = 100f;

    // ---- 画笔 ----
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sparkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path sparkPath = new Path();
    private Path trailPath;
    private final RectF arcRect = new RectF();

    private final float density;
    private final Random random = new Random();

    // ---- 状态 ----
    private final List<Wave> waves = new ArrayList<>();
    private final List<Spark> sparks = new ArrayList<>();
    private final List<TrailPoint> trail = new ArrayList<>();

    private boolean isDown = false;
    private float lastX, lastY;
    private boolean hasLast = false;

    private float scale = 1.3f;
    private float opacity = 1f;
    private float trailSpeed = 1f;
    private float clickSpeed = 1f;
    private boolean alwaysTrail = false;
    private int targetFps = 0;
    private boolean paused = false;
    private boolean hdr = false;
    private float hdrGain = 1f;

    private int colorR = 45, colorG = 175, colorB = 255;
    private float endR, endG, endB;
    private static final float START_R = 250, START_G = 252, START_B = 252;

    private long lastFrameMs = 0;
    private long lastFpsCheck = 0;
    private int frameCount = 0;
    private float currentFps = 0f;

    private static final int MAX_TRAIL = 16;
    private static final String TAG = "BASparkNative";
    private boolean loggedFirstFrame = false;
    private int drawCount = 0;
    private final boolean debugRect;

    public NativeSparkView(Context context) {
        this(context, safeBool(context, "native_plain", true),
                safeBool(context, "native_debug_rect", false));
    }

    /** 供 helper 进程（systemContext，无 SharedPreferences）使用 */
    public NativeSparkView(Context context, boolean plain, boolean debugRectFlag) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        debugRect = debugRectFlag;
        DLog.i(TAG, "blend=" + (plain ? "SRC_OVER(plain)" : "PLUS"));
        if (!plain) {
            setLayerType(LAYER_TYPE_HARDWARE, null);
        }
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        sparkPaint.setStyle(Paint.Style.FILL);
        // 上游是在缓冲层用 lighter 叠加，这里用 ADD
        if (!plain && Build.VERSION.SDK_INT >= 29) {
            fillPaint.setBlendMode(BlendMode.PLUS);
            strokePaint.setBlendMode(BlendMode.PLUS);
            sparkPaint.setBlendMode(BlendMode.PLUS);
        } else if (!plain) {
            PorterDuffXfermode add = new PorterDuffXfermode(PorterDuff.Mode.ADD);
            fillPaint.setXfermode(add);
            strokePaint.setXfermode(add);
            sparkPaint.setXfermode(add);
        }
        setBackgroundColor(Color.TRANSPARENT);
        setWillNotDraw(false);
        setAlpha(1f);
        DLog.i(TAG, "view constructed, density=" + density);
    }

    private static boolean safeBool(Context c, String key, boolean def) {
        try {
            return Config.sp(c).getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    // ================= 对外接口 =================

    public void updateSettings(float scale, float opacity, float trailSpeed, float clickSpeed,
                               int targetFps, String rgb, boolean alwaysTrail, boolean hdr, float hdrGain) {
        this.hdrGain = Math.max(0.3f, Math.min(3f, hdrGain));
        this.hdr = hdr;
        applyBlend();
        this.scale = Math.max(0.5f, Math.min(3f, scale));
        this.opacity = Math.max(0.1f, Math.min(1f, opacity));
        this.trailSpeed = Math.max(0.2f, Math.min(3f, trailSpeed));
        this.clickSpeed = Math.max(0.2f, Math.min(3f, clickSpeed));
        this.targetFps = targetFps;
        this.alwaysTrail = alwaysTrail;
        try {
            String[] p = rgb.split(",");
            colorR = Integer.parseInt(p[0].trim());
            colorG = Integer.parseInt(p[1].trim());
            colorB = Integer.parseInt(p[2].trim());
        } catch (Exception ignored) {
        }
        endR = (colorR + 255 * 2) / 3f;
        endG = (colorG + 255 * 2) / 3f;
        endB = (colorB + 255 * 2) / 3f;
        invalidate();
    }

    /** HDR 模式用 PLUS 叠加 + 硬件层，普通模式用 SRC_OVER，保证老设备能看见 */
    private void applyBlend() {
        if (hdr && Build.VERSION.SDK_INT >= 26) {
            setLayerType(LAYER_TYPE_HARDWARE, null);
        }
        PorterDuffXfermode add = new PorterDuffXfermode(PorterDuff.Mode.ADD);
        for (Paint p : new Paint[]{fillPaint, strokePaint, sparkPaint}) {
            if (Build.VERSION.SDK_INT >= 29) {
                p.setBlendMode(hdr ? BlendMode.PLUS : BlendMode.SRC_OVER);
            } else {
                p.setXfermode(hdr ? add : null);
            }
        }
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        if (paused) {
            clearAll();
        } else {
            lastFrameMs = SystemClock.uptimeMillis();
            invalidate();
        }
    }

    public float getFps() {
        return currentFps;
    }

    public void onDown(float x, float y) {
        DLog.i(TAG, "onDown " + x + "," + y + " paused=" + paused);
        if (paused) return;
        isDown = true;
        lastX = x;
        lastY = y;
        hasLast = true;
        createEffects(x, y);
        wake();
    }

    public void onMove(float x, float y) {
        if (paused) return;
        if (!isDown && !alwaysTrail) return;
        if (hasLast) {
            float dx = x - lastX;
            float dy = y - lastY;
            float minStep = 2f * density;
            if (dx * dx + dy * dy > minStep * minStep) {
                trail.add(new TrailPoint(x, y, 1f));
                if (trail.size() > MAX_TRAIL) trail.remove(0);
                // 拖尾偶尔甩出小碎片
                if (random.nextFloat() < 0.3f) {
                    double a = random.nextFloat() * Math.PI * 2;
                    float speedAdjust = scale / 1.5f;
                    Spark s = new Spark();
                    s.x = x + (float) Math.cos(a) * 10 * scale;
                    s.y = y + (float) Math.sin(a) * 10 * scale;
                    s.vx = (float) Math.cos(a) * 1.3f * speedAdjust * density;
                    s.vy = (float) Math.sin(a) * 1.3f * speedAdjust * density;
                    s.rot = random.nextFloat() * (float) Math.PI * 2;
                    s.rs = 0.16f;
                    s.size = 9 * scale * density;
                    s.alpha = 0.7f;
                    s.friction = 0.95f;
                    s.fromClick = false;
                    sparks.add(s);
                }
            }
        }
        lastX = x;
        lastY = y;
        hasLast = true;
        wake();
    }

    public void onUp() {
        isDown = false;
        wake();
    }

    private void wake() {
        DLog.i(TAG, "wake w=" + waves.size() + " s=" + sparks.size()
                + " attached=" + isAttachedToWindow());
        if (!paused) {
            lastFrameMs = Math.max(lastFrameMs, SystemClock.uptimeMillis() - 16);
            invalidate();
        }
    }

    private void clearAll() {
        waves.clear();
        sparks.clear();
        trail.clear();
        isDown = false;
        hasLast = false;
        invalidate();
    }

    // ================= 创建 / 更新 =================

    private void createEffects(float x, float y) {
        Wave w = new Wave();
        w.x = x;
        w.y = y;
        w.r = 0;
        w.life = 0;
        w.ang = random.nextFloat() * (float) Math.PI * 2;
        w.rs = RING_RS[random.nextInt(RING_RS.length)];
        w.seg0 = new Seg(0, RING_LEN, RING_ROUND[random.nextInt(RING_ROUND.length)]);
        w.seg1 = new Seg((random.nextFloat() * 3 - 1.5f) * (float) Math.PI,
                RING_LEN, RING_ROUND[random.nextInt(RING_ROUND.length)]);
        waves.add(w);

        float speedAdjust = scale / 1.5f;
        for (int i = 0; i < SPARK_COUNT; i++) {
            double a = random.nextFloat() * Math.PI * 2;
            float speed = (4.8f + random.nextFloat() * 2) * speedAdjust * density;
            Spark s = new Spark();
            s.x = x;
            s.y = y;
            s.vx = (float) Math.cos(a) * speed;
            s.vy = (float) Math.sin(a) * speed;
            s.rot = random.nextFloat() * (float) Math.PI * 2;
            s.rs = (random.nextFloat() - 0.5f) * 0.28f;
            s.size = (4 + random.nextFloat() * 3) * scale * density;
            s.alpha = 1f;
            s.friction = 0.9f;
            s.fromClick = true;
            sparks.add(s);
        }
    }

    /**
     * @param dt 以 60fps 为 1 的时间步长
     */
    private void update(float dt) {
        float trailScale = dt * trailSpeed;
        float clickScale = dt * clickSpeed;

        updateTrail(trailScale);
        updateWaves(clickScale);
        updateSparks(clickScale, trailScale);
    }

    private void updateTrail(float frameScale) {
        int n = trail.size();
        float baseDecay = (alwaysTrail || isDown ? 0.085f : 0.18f) * frameScale;
        for (int i = n - 1; i >= 0; i--) {
            TrailPoint t = trail.get(i);
            float span = Math.max(1, n - 1);
            float along = n > 1 ? i / span : 1f;
            float step = baseDecay * (1.25f - 0.55f * along);
            if (step > 0.42f) step = 0.42f;
            t.life -= step;
            if (t.life <= 0) trail.remove(i);
        }
    }

    private void updateWaves(float clickScale) {
        for (int i = waves.size() - 1; i >= 0; i--) {
            Wave w = waves.get(i);
            w.life += clickScale;
            float waveProg = Math.min(w.life / FILLED_MAX_LIFE, 1f);
            float ringProg = Math.min(w.life / RINGS_MAX_LIFE, 1f);
            float ease = 1 - (float) Math.pow(1 - waveProg, 3);
            w.r = R_ADD_RATE * scale * ease * density;
            w.ringProg = ringProg;
            w.waveProg = waveProg;
            w.ang -= w.rs * clickScale;
            if (ringProg >= 1 && waveProg >= 1) waves.remove(i);
        }
    }

    private void updateSparks(float clickScale, float trailScale) {
        for (int i = sparks.size() - 1; i >= 0; i--) {
            Spark s = sparks.get(i);
            float fs = s.fromClick ? clickScale : trailScale;
            s.x += s.vx * fs;
            s.y += s.vy * fs;
            s.vx *= Math.pow(s.friction, fs);
            s.vy *= Math.pow(s.friction, fs);
            s.rot += s.rs * fs;
            s.alpha -= 0.032f * fs;
            if (s.alpha <= 0) sparks.remove(i);
        }
    }

    // ================= 绘制 =================

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();

        if (debugRect) {
            canvas.drawColor(Color.MAGENTA);
            return;
        }
        if (paused) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            return;
        }

        // 帧率上限：没到时间就跳过这一帧，但仍然安排下一帧
        if (targetFps > 0) {
            float cap = 1000f / targetFps;
            if (now - lastFrameMs < cap * 0.82f) {
                postInvalidateOnAnimation();
                return;
            }
        }

        float delta = Math.min(now - lastFrameMs, MAX_DELTA_MS);
        if (lastFrameMs == 0) delta = BASE_FRAME_MS;
        lastFrameMs = now;
        float dt = delta / BASE_FRAME_MS;

        if (!loggedFirstFrame) {
            loggedFirstFrame = true;
            DLog.i(TAG, "first frame " + getWidth() + "x" + getHeight()
                    + " shown=" + isShown() + " vis=" + getVisibility());
        }
        drawCount++;
        if (drawCount <= 8 || drawCount % 30 == 0) {
            DLog.i(TAG, "draw #" + drawCount + " w=" + waves.size()
                    + " s=" + sparks.size() + " t=" + trail.size());
        }
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        update(dt);
        drawTrail(canvas);
        drawWaves(canvas);
        drawSparks(canvas);

        countFps(now);

        if (!trail.isEmpty() || !waves.isEmpty() || !sparks.isEmpty()) {
            postInvalidateOnAnimation();
        }
    }

    private void countFps(long now) {
        frameCount++;
        if (lastFpsCheck == 0) lastFpsCheck = now;
        if (now - lastFpsCheck >= 1000) {
            currentFps = frameCount * 1000f / (now - lastFpsCheck);
            frameCount = 0;
            lastFpsCheck = now;
        }
    }

    private void drawTrail(Canvas canvas) {
        int n = trail.size();
        if (n == 0) return;

        // 把“当前指针位置”当尾迹端点（和上游一致）
        int count = hasLast ? n + 1 : n;
        if (count < 2) {
            float fade = Math.max(0, trail.get(0).life);
            fillPaint.setColor(Color.rgb(colorR, colorG, colorB));
            fillPaint.setAlpha((int) (255 * clamp01(fade * 0.85f * opacity)));
            canvas.drawCircle(trail.get(0).x, trail.get(0).y,
                    (2.5f + 2 * fade) * density, fillPaint);
            return;
        }

        if (trailPath == null) trailPath = new Path();
        trailPath.reset();
        trailPath.moveTo(trail.get(0).x, trail.get(0).y);
        for (int i = 1; i < n; i++) {
            trailPath.lineTo(trail.get(i).x, trail.get(i).y);
        }
        float headX, headY;
        if (hasLast) {
            headX = lastX;
            headY = lastY;
            trailPath.lineTo(headX, headY);
        } else {
            headX = trail.get(n - 1).x;
            headY = trail.get(n - 1).y;
        }
        float tailX = trail.get(0).x;
        float tailY = trail.get(0).y;

        // 整条路径只描一次，用渐变代替“逐段不同透明度”，接缝就消失了
        float[] widths = {9f, 6f, 4.5f};
        float[] alphaMul = {0.05f, 0.5f, 1f};

        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);

        for (int pass = 0; pass < 3; pass++) {
            int a = (int) (255 * clamp01(alphaMul[pass] * opacity));
            if (a <= 0) continue;
            setTrailShader(tailX, tailY, headX, headY,
                    Color.argb(0, colorR, colorG, colorB),
                    Color.argb(a, colorR, colorG, colorB));
            strokePaint.setStrokeWidth(widths[pass] * density);
            canvas.drawPath(trailPath, strokePaint);
        }

        if (hdr) {
            int a = (int) (255 * clamp01(Math.min(1f, 0.45f * hdrGain) * opacity));
            if (a > 0) {
                setTrailShader(tailX, tailY, headX, headY,
                        Color.argb(0, 255, 255, 255),
                        Color.argb(a, 255, 255, 255));
                strokePaint.setStrokeWidth(1.8f * density);
                canvas.drawPath(trailPath, strokePaint);
            }
        }
        strokePaint.setShader(null);
    }

    private void setTrailShader(float x0, float y0, float x1, float y1, int c0, int c1) {
        if (Math.hypot(x1 - x0, y1 - y0) < 1f) {
            strokePaint.setShader(null);
            strokePaint.setColor(c1);
            return;
        }
        strokePaint.setShader(new LinearGradient(x0, y0, x1, y1,
                new int[]{c0, c1}, null, Shader.TileMode.CLAMP));
    }

    private void drawWaves(Canvas canvas) {
        for (Wave w : waves) {
            // 中间的实心圆
            float alphaFill = Math.max(0, 1 - w.waveProg);
            if (alphaFill > 0) {
                fillPaint.setColor(Color.rgb(colorR, colorG, colorB));
                fillPaint.setAlpha((int) (255 * clamp01(alphaFill * opacity)));
                canvas.drawCircle(w.x, w.y, w.r, fillPaint);
                if (hdr && w.r > 1) {
                    fillPaint.setColor(Color.WHITE);
                    float coreA = clamp01(alphaFill * opacity * 0.35f * hdrGain);
                    fillPaint.setAlpha((int) (255 * coreA));
                    canvas.drawCircle(w.x, w.y, Math.min(w.r * 0.15f, 14f * density), fillPaint);
                }
            }

            float ringProg = w.ringProg;
            float lineWidthMul = Math.min(-0.8f * (ringProg - 0.8f) + 1f, 1f);
            float t = Math.min(1.2f * ringProg, 1f);
            float rr = START_R * (1 - t) + endR * t;
            float gg = START_G * (1 - t) + endG * t;
            float bb = START_B * (1 - t) + endB * t;
            float alphaRing = Math.min(1.1f - 0.3f * ringProg, 1f);

            for (int si = 0; si < 2; si++) {
                Seg seg = si == 0 ? w.seg0 : w.seg1;
                float start, end, len;
                float base = w.ang + seg.off;
                if (ringProg <= RING_LEN_STOP_ADD) {
                    len = seg.len * (ringProg / RING_LEN_STOP_ADD);
                    end = base + seg.len;
                    start = end - len;
                } else if (ringProg > RING_LEN_START_DIM) {
                    len = seg.len * (1 - (ringProg - RING_LEN_START_DIM) / (1 - RING_LEN_START_DIM));
                    start = base;
                    end = start + len;
                } else {
                    len = seg.len;
                    start = base;
                    end = start + len;
                }

                float radius = w.r + seg.rRound * scale * density;
                if (radius <= 0) continue;
                arcRect.set(w.x - radius, w.y - radius, w.x + radius, w.y + radius);

                for (int k = 0; k < SEG_NUM; k++) {
                    float t0 = k / (float) SEG_NUM;
                    float t1 = (k + 1) / (float) SEG_NUM;
                    float a0 = start + (end - start) * t0;
                    float a1 = start + (end - start) * t1;
                    if (Math.abs(a1 - a0) < 0.01f) continue;

                    float wT = Math.min(2 - Math.abs(4 * (t0 - 0.5f)), 1f);
                    float lw = (RING_MIN_W * (1 - wT) + RING_MAX_W * wT) * lineWidthMul;
                    if (lw <= 0.05f) continue;

                    strokePaint.setStrokeWidth(lw * density);
                    strokePaint.setColor(Color.rgb((int) rr, (int) gg, (int) bb));
                    strokePaint.setAlpha((int) (255 * clamp01(alphaRing * opacity)));
                    canvas.drawArc(arcRect,
                            (float) Math.toDegrees(a0),
                            (float) Math.toDegrees(a1 - a0),
                            false, strokePaint);
                }
            }
        }
    }

    private void drawSparks(Canvas canvas) {
        for (Spark s : sparks) {
            float a = clamp01(s.alpha * opacity * (hdr ? Math.min(1.4f, hdrGain) : 1f));
            if (a <= 0) continue;

            canvas.save();
            canvas.translate(s.x, s.y);
            canvas.rotate((float) Math.toDegrees(s.rot));
            sparkPath.reset();
            sparkPath.moveTo(0, -s.size);
            sparkPath.lineTo(s.size * 0.6f, s.size * 0.6f);
            sparkPath.lineTo(-s.size * 0.6f, s.size * 0.6f);
            sparkPath.close();
            sparkPaint.setColor(Color.WHITE);
            sparkPaint.setAlpha((int) (255 * a));
            canvas.drawPath(sparkPath, sparkPaint);
            canvas.restore();
        }
    }

    /** 环的颜色：起点偏白，随进度混到主色 */
    private int blend(float life) {
        return Color.rgb(colorR, colorG, colorB);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    // ================= 数据 =================

    private static class Seg {
        float off, len, rRound;

        Seg(float off, float len, float rRound) {
            this.off = off;
            this.len = len;
            this.rRound = rRound;
        }
    }

    private static class Wave {
        float x, y, r, life;
        float ang, rs;
        float ringProg, waveProg;
        Seg seg0, seg1;
    }

    private static class Spark {
        float x, y, vx, vy, rot, rs, size, alpha, friction;
        boolean fromClick;
    }

    private static class TrailPoint {
        float x, y, life;

        TrailPoint(float x, float y, float life) {
            this.x = x;
            this.y = y;
            this.life = life;
        }
    }
}