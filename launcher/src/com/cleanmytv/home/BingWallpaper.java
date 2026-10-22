package com.cleanmytv.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Bing 每日壁纸。
 *
 * 设计原则：网络/解析/落盘的任何失败都不得影响桌面运行——
 * 所有入口与线程体均以 catch (Throwable) 兜底，失败静默、下次启动重试。
 *
 * - 打开桌面时尝试拉取一次；同一天内成功拉过就跳过
 * - 只有下载并保存成功才记录日期
 * - 图片缓存在应用私有目录 bing_wallpaper.jpg
 */
public class BingWallpaper {

    private static final String PREF = "bing_wallpaper";
    private static final String KEY_DATE = "last_success_date";
    private static final String FILE_NAME = "bing_wallpaper.jpg";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    /** 国内可直连的接口；返回 images[0].url */
    private static final String API =
            "https://cn.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=zh-CN";

    public static File file(Context ctx) {
        try {
            return new File(ctx.getFilesDir(), FILE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    public static String lastDate(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_DATE, "");
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 每天最多成功拉取一次。onSuccess 在主线程回调（用于刷新背景）。
     * 本方法保证不向外抛任何异常。
     */
    public static void updateIfNeeded(final Context ctx, final Runnable onSuccess) {
        try {
            final Context app = ctx.getApplicationContext();
            if (app == null) return;

            final String today = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
            final SharedPreferences p = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);

            // 今天已成功过 → 直接跳过，不产生任何网络请求
            if (today.equals(p.getString(KEY_DATE, ""))) return;

            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    boolean ok = false;
                    try {
                        ok = download(app);
                    } catch (Throwable ignored) {
                        ok = false;
                    }

                    if (ok) {
                        try {
                            p.edit().putString(KEY_DATE, today).apply();
                        } catch (Throwable ignored) { }
                        if (onSuccess != null) {
                            try {
                                new Handler(Looper.getMainLooper()).post(onSuccess);
                            } catch (Throwable ignored) { }
                        }
                    }
                }
            }, "bing-wallpaper");
            try { t.setPriority(Thread.MIN_PRIORITY); } catch (Throwable ignored) { }
            t.start();
        } catch (Throwable ignored) {
            // 绝不让壁纸模块影响桌面启动
        }
    }

    /** 返回 true 表示成功下载并落盘。 */
    private static boolean download(Context app) {
        File dst = file(app);
        if (dst == null) return false;

        try {
            String json = httpString(API, 6000, 10000);
            if (json == null || json.length() == 0) return false;

            JSONObject o = new JSONObject(json);
            String rel = o.getJSONArray("images").getJSONObject(0).getString("url");
            if (rel == null || rel.length() == 0) return false;

            String full = rel.startsWith("http") ? rel : "https://cn.bing.com" + rel;
            byte[] data = httpBytes(full, 6000, 20000);
            if (data == null || data.length < 20000) return false;

            File tmp = new File(app.getFilesDir(), FILE_NAME + ".tmp");
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                fos.write(data);
                fos.flush();
            } finally {
                try { fos.close(); } catch (Throwable ignored) { }
            }

            if (dst.exists() && !dst.delete()) return false;
            return tmp.renameTo(dst);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String httpString(String url, int connectMs, int readMs) {
        try {
            byte[] b = httpBytes(url, connectMs, readMs);
            return b == null ? null : new String(b, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] httpBytes(String url, int connectMs, int readMs) {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setRequestProperty("User-Agent", UA);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code != 200) return null;

            in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) { }
        }
    }
}
