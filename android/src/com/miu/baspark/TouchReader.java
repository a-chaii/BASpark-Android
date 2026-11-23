package com.miu.baspark;

import android.util.Log;
import android.view.Surface;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通过 root 读取 /dev/input/event* 的原始 input_event 流，
 * 把触摸坐标换算成 0~1 的百分比后回调给 WebView。
 */
public class TouchReader {

    private static final String TAG = "BASparkTouch";

    // struct input_event (64 位内核) = 24B: timeval(16) + type(2) + code(2) + value(4)
    private static final int EVENT_SIZE = 24;
    private static final int OFF_TYPE = 16;
    private static final int OFF_CODE = 18;
    private static final int OFF_VALUE = 20;

    private static final int EV_SYN = 0;
    private static final int EV_KEY = 1;
    private static final int EV_ABS = 3;

    private static final int SYN_REPORT = 0;
    private static final int BTN_TOUCH = 0x14a;
    private static final int ABS_MT_SLOT = 0x2f;
    private static final int ABS_MT_POSITION_X = 0x35;
    private static final int ABS_MT_POSITION_Y = 0x36;
    private static final int ABS_MT_TRACKING_ID = 0x39;

    private static final int MAX_SLOTS = 10;

    public interface Sink {
        void onDown(float percentX, float percentY);

        void onMove(float percentX, float percentY);

        void onUp();
    }

    private final Sink sink;
    private volatile boolean running;
    private volatile int rotation = Surface.ROTATION_0;
    /** true = 多指拖拽优化：只让首指牵引拖尾，其他手指只触发爆裂 */
    private volatile boolean primaryOnly = true;
    private Process proc;
    private Thread thread;

    private int maxX = 20224;
    private int maxY = 44480;
    private String device = "/dev/input/event6";
    private String deviceName = "touchpanel";

    public TouchReader(Sink sink) {
        this.sink = sink;
    }

    public void setRotation(int rotation) {
        this.rotation = rotation;
    }

    public void setPrimaryOnly(boolean primaryOnly) {
        this.primaryOnly = primaryOnly;
    }

    public String getDeviceInfo() {
        return deviceName + " · max " + maxX + "x" + maxY;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "baspark-touch");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (proc != null) {
            try {
                proc.destroy();
            } catch (Exception ignored) {
            }
            proc = null;
        }
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private void loop() {
        discoverDevice();
        while (running) {
            try {
                proc = new ProcessBuilder("su", "-c", "cat " + device).start();
                DataInputStream in = new DataInputStream(
                        new BufferedInputStream(proc.getInputStream(), 16384));
                parseStream(in);
            } catch (Exception e) {
                Log.w(TAG, "reader stopped: " + e);
                if (!running) break;
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }
    }

    /** 解析 getevent -p，自动找到触摸屏设备与 ABS 范围 */
    private void discoverDevice() {
        try {
            Process p = new ProcessBuilder("su", "-c", "getevent -p").start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            List<Dev> list = new ArrayList<>();
            Dev cur = null;
            String line;
            Pattern addPat = Pattern.compile("add device\\s+\\d+:\\s+(/dev/input/\\S+)");
            Pattern namePat = Pattern.compile("name:\\s+\"(.*)\"");
            Pattern rangePat = Pattern.compile("^\\s*([0-9a-f]{4})\\s*:.*max\\s+(\\d+)");
            while ((line = r.readLine()) != null) {
                Matcher m = addPat.matcher(line);
                if (m.find()) {
                    cur = new Dev();
                    cur.path = m.group(1);
                    list.add(cur);
                    continue;
                }
                if (cur == null) continue;
                Matcher nm = namePat.matcher(line);
                if (nm.find()) {
                    cur.name = nm.group(1);
                    continue;
                }
                if (line.contains("INPUT_PROP_DIRECT")) cur.direct = true;
                Matcher rp = rangePat.matcher(line);
                if (rp.find()) {
                    int code = Integer.parseInt(rp.group(1), 16);
                    int max = Integer.parseInt(rp.group(2));
                    if (code == ABS_MT_POSITION_X) cur.maxX = max;
                    if (code == ABS_MT_POSITION_Y) cur.maxY = max;
                }
            }
            p.waitFor();

            Dev best = null;
            for (Dev d : list) {
                if (d.maxX <= 0 || d.maxY <= 0) continue;
                String n = d.name == null ? "" : d.name.toLowerCase();
                if (n.contains("uinput") || n.contains("jack")) continue;
                if (best == null) best = d;
                else if (d.direct && !best.direct) best = d;
                else if (d.direct == best.direct && n.contains("touch")
                        && !best.name.toLowerCase().contains("touch")) best = d;
            }
            if (best != null) {
                device = best.path;
                deviceName = best.name;
                maxX = best.maxX;
                maxY = best.maxY;
                Log.i(TAG, "touch device = " + device + " " + deviceName
                        + " max=" + maxX + "x" + maxY);
            }
        } catch (Exception e) {
            Log.w(TAG, "discover failed, fallback to " + device, e);
        }
    }

    private void parseStream(DataInputStream in) throws Exception {
        ByteBuffer buf = ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        byte[] raw = new byte[EVENT_SIZE];

        int curSlot = 0;
        int[] xs = new int[MAX_SLOTS];
        int[] ys = new int[MAX_SLOTS];
        boolean[] down = new boolean[MAX_SLOTS];
        boolean[] fresh = new boolean[MAX_SLOTS];
        long[] lastMoveNs = new long[MAX_SLOTS];
        int[] lastX = new int[MAX_SLOTS];
        int[] lastY = new int[MAX_SLOTS];
        boolean[] hasLast = new boolean[MAX_SLOTS];
        int primarySlot = -1;

        while (running) {
            in.readFully(raw);
            buf.clear();
            buf.put(raw);
            buf.flip();
            int type = buf.getShort(OFF_TYPE) & 0xffff;
            int code = buf.getShort(OFF_CODE) & 0xffff;
            int value = buf.getInt(OFF_VALUE);

            switch (type) {
                case EV_ABS:
                    if (code == ABS_MT_SLOT) {
                        if (value >= 0 && value < MAX_SLOTS) curSlot = value;
                    } else if (code == ABS_MT_POSITION_X) {
                        if (curSlot < MAX_SLOTS) xs[curSlot] = value;
                    } else if (code == ABS_MT_POSITION_Y) {
                        if (curSlot < MAX_SLOTS) ys[curSlot] = value;
                    } else if (code == ABS_MT_TRACKING_ID) {
                        if (curSlot < MAX_SLOTS) {
                            if (value == -1) {
                                if (down[curSlot]) {
                                    down[curSlot] = false;
                                    hasLast[curSlot] = false;
                                    if (primarySlot == curSlot) primarySlot = -1;
                                    sink.onUp();
                                }
                            } else {
                                down[curSlot] = true;
                                fresh[curSlot] = true;
                                if (primarySlot < 0) primarySlot = curSlot;
                            }
                        }
                    }
                    break;
                case EV_KEY:
                    if (code == BTN_TOUCH && value == 0) {
                        for (int i = 0; i < MAX_SLOTS; i++) {
                            if (down[i]) {
                                down[i] = false;
                                hasLast[i] = false;
                                sink.onUp();
                            }
                        }
                        primarySlot = -1;
                    }
                    break;
                case EV_SYN:
                    if (code == SYN_REPORT) {
                        int rot = rotation;
                        for (int i = 0; i < MAX_SLOTS; i++) {
                            if (!down[i]) continue;
                            float rx = clamp01(xs[i] / (float) maxX);
                            float ry = clamp01(ys[i] / (float) maxY);
                            float px = mapX(rx, ry, rot);
                            float py = mapY(rx, ry, rot);

                            if (fresh[i]) {
                                fresh[i] = false;
                                hasLast[i] = true;
                                lastX[i] = xs[i];
                                lastY[i] = ys[i];
                                lastMoveNs[i] = System.nanoTime();
                                Log.d(TAG, "DOWN slot=" + i + " raw=(" + xs[i] + "," + ys[i]
                                        + ") pct=(" + px + "," + py + ") rot=" + rot);
                                sink.onDown(px, py);
                            } else {
                                // 多指优化：非首指只负责爆裂，不拖拽同一条尾迹，避免“翻绳”
                                if (primaryOnly && i != primarySlot) continue;
                                long now = System.nanoTime();
                                if (now - lastMoveNs[i] < 16_000_000L) continue;
                                int dx = xs[i] - lastX[i];
                                int dy = ys[i] - lastY[i];
                                if (hasLast[i] && dx * dx + dy * dy < 64) continue;
                                lastMoveNs[i] = now;
                                lastX[i] = xs[i];
                                lastY[i] = ys[i];
                                hasLast[i] = true;
                                sink.onMove(px, py);
                            }
                        }
                    }
                    break;
                default:
                    break;
            }
        }
    }

    /** 原始面板坐标（固定竖屏空间）→ 当前屏幕方向下的归一化 X */
    private static float mapX(float rx, float ry, int rot) {
        switch (rot) {
            case Surface.ROTATION_90:
                return ry;
            case Surface.ROTATION_180:
                return 1f - rx;
            case Surface.ROTATION_270:
                return 1f - ry;
            default:
                return rx;
        }
    }

    /** 原始面板坐标 → 当前屏幕方向下的归一化 Y */
    private static float mapY(float rx, float ry, int rot) {
        switch (rot) {
            case Surface.ROTATION_90:
                return 1f - rx;
            case Surface.ROTATION_180:
                return 1f - ry;
            case Surface.ROTATION_270:
                return rx;
            default:
                return ry;
        }
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    private static class Dev {
        String path;
        String name = "";
        int maxX = -1;
        int maxY = -1;
        boolean direct = false;
    }
}