package com.cleanmytv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;

import java.io.File;

/**
 * 给任意根视图铺 Bing 壁纸（居中裁剪 + 暗色遮罩）。
 *
 * 性能：解码/裁剪结果按「源文件 lastModified」缓存，同一张图只解码一次；
 * 文件更新（Bing 换图）后自动失效重解。
 * 任何异常都静默降级为纯色背景。
 */
public class Wallpaper {

    /** 暗色遮罩：约 62% 黑，保证图标/文字可读 */
    public static final int SCRIM = 0x9E000000;

    private static Bitmap sCachedBitmap;
    private static long sCachedStamp = -1L;

    public static void apply(Context ctx, View target) {
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
            }

            BitmapDrawable photo = new BitmapDrawable(ctx.getResources(), crop);
            ColorDrawable scrim = new ColorDrawable(SCRIM);
            target.setBackground(new LayerDrawable(new Drawable[]{photo, scrim}));
        } catch (Throwable ignored) {
            // 保持默认背景
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
            o.inPreferredConfig = Bitmap.Config.RGB_565;
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
