package com.cleanmytv.home;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 调试日志：同时写 logcat 和外部文件。
 *
 * 落盘很重要 —— 电视重启后 logcat 缓冲区会被系统日志冲掉，
 * 只有文件能留下「重启那一刻到底发生了什么」。
 *
 * 位置：Android/data/com.ts.launcher/files/debug.log
 * 读取：adb shell cat /sdcard/Android/data/com.ts.launcher/files/debug.log
 */
public class DbgLog {

    private static final String TAG = "CleanMyTV";
    private static final String NAME = "debug.log";
    private static final long MAX_BYTES = 256 * 1024;

    public static void d(Context ctx, String msg) {
        Log.d(TAG, msg);
        if (ctx == null) return;
        try {
            File f = logFile(ctx);
            if (f == null) return;

            // 超过上限时轮转，避免无限增长
            if (f.length() > MAX_BYTES) {
                File old = new File(f.getParentFile(), NAME + ".old");
                if (old.exists()) old.delete();
                f.renameTo(old);
            }

            String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date()) + "  " + msg + "\n";
            FileOutputStream fos = new FileOutputStream(f, true);
            try {
                fos.write(line.getBytes("UTF-8"));
                fos.flush();
            } finally {
                fos.close();
            }
        } catch (Throwable ignored) {
            // 日志本身绝不能影响主流程
        }
    }

    public static File logFile(Context ctx) {
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) dir = ctx.getFilesDir();
            if (dir == null) return null;
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, NAME);
        } catch (Throwable t) {
            return null;
        }
    }
}
