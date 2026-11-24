package com.miu.baspark;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 调试日志：logcat 被刷得太快时，写到应用外部目录里便于排查 */
public final class DLog {

    private static File file;

    private DLog() {
    }

    public static void init(Context c) {
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) {
                dir = new File(Environment.getExternalStorageDirectory(), "Android/data/"
                        + c.getPackageName() + "/files");
            }
            if (dir != null && !dir.exists()) dir.mkdirs();
            file = new File(dir, "baspark.log");
            if (file.exists() && file.length() > 256 * 1024) {
                // 太大就清空，避免无限增长
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        } catch (Exception ignored) {
        }
    }

    public static void i(String tag, String msg) {
        android.util.Log.i(tag, msg);
        if (file == null) return;
        try (FileWriter w = new FileWriter(file, true)) {
            String t = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
            w.write(t + " [" + tag + "] " + msg + "\n");
        } catch (Exception ignored) {
        }
    }
}