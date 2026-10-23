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
import java.util.concurrent.atomic.AtomicBoolean;

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

    /** 同一时刻只允许一个下载线程：onResume 与 30 分钟定时检查都可能触发 */
    private static final AtomicBoolean DOWNLOADING = new AtomicBoolean(false);

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

            // 今天已成功过且缓存文件完好 → 直接跳过，不产生任何网络请求
            String last = p.getString(KEY_DATE, "");
            File cached = file(app);
            boolean cacheOk = cached != null && cached.exists() && cached.length() >= 10000;
            if (today.equals(last) && cacheOk) {
                DbgLog.d(app, "Wallpaper: skip, already ok today " + today);
                return;
            }
            if (today.equals(last)) {
                // 日期记着今天，但文件被清理或损坏了，补下一次
                DbgLog.d(app, "Wallpaper: cache missing/too small, re-download");
            }
            if (!DOWNLOADING.compareAndSet(false, true)) {
                DbgLog.d(app, "Wallpaper: another download is running, skip");
                return;
            }
            DbgLog.d(app, "Wallpaper: check update, today=" + today
                    + " last=" + (last.length() == 0 ? "-" : last));

            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    boolean ok = false;
                    try {
                        ok = download(app);

                        if (ok) {
                            // 先落日期再放锁：否则窗口期内另一次检查会看不到"今天已成功"而重复下载
                            try {
                                p.edit().putString(KEY_DATE, today).apply();
                            } catch (Throwable ignored) { }
                            DbgLog.d(app, "Wallpaper: update ok for " + today);
                            if (onSuccess != null) {
                                try {
                                    new Handler(Looper.getMainLooper()).post(onSuccess);
                                } catch (Throwable ignored) { }
                            }
                        } else {
                            // 不记录日期，下次 onResume / 30 分钟定时检查都会重试
                            DbgLog.d(app, "Wallpaper: update failed, will retry later");
                        }
                    } catch (Throwable t) {
                        DbgLog.d(app, "Wallpaper: update threw " + t);
                    } finally {
                        // 成功、失败、异常都要放开，否则再也拉不到新图
                        DOWNLOADING.set(false);
                    }
                }
            }, "bing-wallpaper");
            try { t.setPriority(Thread.MIN_PRIORITY); } catch (Throwable ignored) { }
            try {
                t.start();
            } catch (Throwable t2) {
                // 线程都没起来，锁必须还给下一次
                DOWNLOADING.set(false);
                DbgLog.d(app, "Wallpaper: cannot start thread " + t2);
            }
        } catch (Throwable ignored) {
            // 绝不让壁纸模块影响桌面启动
        }
    }

    /** 返回 true 表示成功下载并落盘。 */
    private static boolean download(Context app) {
        File dst = file(app);
        if (dst == null) {
            DbgLog.d(app, "Wallpaper: no cache file path");
            return false;
        }

        try {
            String json = httpString(API, 6000, 10000);
            if (json == null || json.length() == 0) {
                DbgLog.d(app, "Wallpaper: api request failed (empty response)");
                return false;
            }

            JSONObject o = new JSONObject(json);
            String rel = o.getJSONArray("images").getJSONObject(0).getString("url");
            if (rel == null || rel.length() == 0) {
                DbgLog.d(app, "Wallpaper: api response has no url");
                return false;
            }

            String full = rel.startsWith("http") ? rel : "https://cn.bing.com" + rel;
            byte[] data = httpBytes(full, 6000, 20000);
            if (data == null) {
                DbgLog.d(app, "Wallpaper: image download failed, " + imageTag(rel));
                return false;
            }
            if (data.length < 20000) {
                DbgLog.d(app, "Wallpaper: image too small, " + data.length + " bytes");
                return false;
            }
            if (!looksLikeImage(data)) {
                // 常见于运营商/路由器的拦截页、代理错误页：不校验就会存成一张坏图
                DbgLog.d(app, "Wallpaper: payload is not an image, " + data.length + " bytes");
                return false;
            }

            File tmp = new File(app.getFilesDir(), FILE_NAME + ".tmp");
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                fos.write(data);
                fos.flush();
            } finally {
                try { fos.close(); } catch (Throwable ignored) { }
            }

            if (dst.exists() && !dst.delete()) {
                DbgLog.d(app, "Wallpaper: cannot delete old file");
                return false;
            }
            boolean saved = tmp.renameTo(dst);
            DbgLog.d(app, "Wallpaper: saved " + data.length + " bytes, "
                    + imageTag(rel) + ", ok=" + saved);
            return saved;
        } catch (Throwable t) {
            DbgLog.d(app, "Wallpaper: download exception " + t);
            return false;
        }
    }

    /** 简单校验下载内容确实是图片（JPEG/PNG/WEBP），避免把拦截页存成壁纸。 */
    private static boolean looksLikeImage(byte[] d) {
        if (d == null || d.length < 12) return false;
        // JPEG: FF D8 FF
        if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) return true;
        // PNG: 89 50 4E 47
        if ((d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return true;
        // WEBP: RIFF....WEBP
        if (d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') return true;
        return false;
    }

    /** 从 api 返回的 url 里取出图片标识（OHR.xxx），只用于日志。 */
    private static String imageTag(String rel) {
        try {
            int i = rel.indexOf("OHR.");
            if (i < 0) return "-";
            int j = rel.indexOf('_', i);
            return j > i ? rel.substring(i, j) : rel.substring(i);
        } catch (Throwable t) {
            return "-";
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
