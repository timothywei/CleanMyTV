package com.cleanmytv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;

import java.io.File;

/**
 * 给任意根视图铺 Bing 壁纸（居中裁剪，原色显示）。
 *
 * 不额外叠暗色遮罩：暗化由文字自带的半透明描边（OutlinedTextView）负责，
 * 这样屏幕中部的壁纸能保持原始亮度。
 *
 * 性能：解码/裁剪结果按「源文件 lastModified」缓存，同一张图只解码一次；
 * 文件更新（Bing 换图）后自动失效重解。
 * 任何异常都静默降级为纯色背景。
 */
public class Wallpaper {

    /** 壁纸平均亮度回调：桌面据此决定卡片/胶囊用浅色还是深色玻璃 */
    public interface LumaListener {
        /** @param luma 0-255，-1 表示取不到 */
        void onLuma(int luma);
    }

    private static Bitmap sCachedBitmap;
    private static long sCachedStamp = -1L;
    private static int sCachedLuma = -1;

    public static void apply(Context ctx, View target) {
        apply(ctx, target, null);
    }

    public static void apply(Context ctx, View target, LumaListener listener) {
        if (ctx == null || target == null) return;
        try {
            File f = BingWallpaper.file(ctx);
            if (f == null || !f.exists() || f.length() < 10000) return;

            long stamp = f.lastModified();
            Bitmap crop = sCachedBitmap;
            if (crop == null || stamp != sCachedStamp) {
                crop = decodeAndCrop(ctx, f);
                if (crop == null) return;
                sCachedBitmap = crop;
                sCachedStamp = stamp;
                sCachedLuma = averageLuma(crop);   // 每张图只算一次，随缓存复用
            }

            target.setBackground(new BitmapDrawable(ctx.getResources(), crop));
            if (listener != null && sCachedLuma >= 0) listener.onLuma(sCachedLuma);
        } catch (Throwable ignored) {
            // 保持默认背景
        }
    }

    /**
     * 采样计算平均亮度（每 8 像素取一个点），够用来区分明暗壁纸。
     *
     * 只统计下部 40%：卡片、图标、文字全压在屏幕底部那一条带上，
     * 上半部再亮再暗都不影响它们的可见性，算进来反而会把平均值带偏。
     */
    static int averageLuma(Bitmap b) {
        try {
            int w = b.getWidth();
            int h = b.getHeight();
            if (w <= 0 || h <= 0) return -1;

            final int step = 8;
            int y0 = (int) (h * 0.6f);
            if (y0 >= h) y0 = 0;   // 图太小时退回全图，避免一个点都不采

            long sum = 0;
            long n = 0;
            for (int y = y0; y < h; y += step) {
                for (int x = 0; x < w; x += step) {
                    int c = b.getPixel(x, y);
                    int r = (c >> 16) & 0xFF;
                    int g = (c >> 8) & 0xFF;
                    int bl = c & 0xFF;
                    sum += (299L * r + 587L * g + 114L * bl) / 1000L;
                    n++;
                }
            }
            return n == 0 ? -1 : (int) (sum / n);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static Bitmap decodeAndCrop(Context ctx, File f) {
        try {
            int w = ctx.getResources().getDisplayMetrics().widthPixels;
            int h = ctx.getResources().getDisplayMetrics().heightPixels;

            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), probe);
            if (probe.outWidth <= 0 || probe.outHeight <= 0) return null;

            BitmapFactory.Options o = new BitmapFactory.Options();
            // ARGB_8888：保留 8bit/通道，避免 RGB_565 在暗部产生色带
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inSampleSize = calcSampleSize(probe.outWidth, probe.outHeight, w, h);

            Bitmap bmp = BitmapFactory.decodeFile(f.getPath(), o);
            if (bmp == null) return null;

            return centerCrop(bmp, w, h);
        } catch (Throwable t) {
            return null;
        }
    }

    static int calcSampleSize(int srcW, int srcH, int dstW, int dstH) {
        int sample = 1;
        while ((srcW / (sample * 2)) >= dstW && (srcH / (sample * 2)) >= dstH) sample *= 2;
        return sample;
    }

    static Bitmap centerCrop(Bitmap src, int w, int h) {
        int sw = src.getWidth(), sh = src.getHeight();
        if (sw <= 0 || sh <= 0) return src;
        float scale = Math.max((float) w / sw, (float) h / sh);
        int nw = Math.max(w, Math.round(sw * scale));
        int nh = Math.max(h, Math.round(sh * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(src, nw, nh, true);
        int x = Math.max(0, (nw - w) / 2);
        int y = Math.max(0, (nh - h) / 2);
        return Bitmap.createBitmap(scaled, x, y, w, h);
    }
}
