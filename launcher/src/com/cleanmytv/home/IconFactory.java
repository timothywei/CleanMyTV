package com.cleanmytv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.util.HashMap;

/**
 * 图标统一化：
 * - 满版图标（边缘不透明）→ 圆角裁切
 * - 非满版图标（有透明边/小图标）→ 统一深色圆角底板 + 居中缩放
 * 结果按 key+size 缓存，避免每帧重复处理。
 */
public class IconFactory {

    private static final HashMap<String, Drawable> CACHE = new HashMap<>();
    /** 非满版图标的底衬：半透明黑，避免在壁纸上显得死黑 */
    private static final int PLATE_COLOR = 0x40000000;

    public static Drawable get(Context ctx, String key, Drawable src, int sizePx) {
        if (src == null || sizePx <= 0) return src;
        String k = key + "@" + sizePx;
        Drawable cached = CACHE.get(k);
        if (cached != null) return cached;

        Drawable built = build(ctx, src, sizePx);
        CACHE.put(k, built);
        return built;
    }

    private static Drawable build(Context ctx, Drawable src, int size) {
        Bitmap raw = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas rc = new Canvas(raw);
        try {
            src.setBounds(0, 0, size, size);
            src.draw(rc);
        } catch (Exception ignored) { }

        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float radius = size * 0.23f;

        if (isFullBleed(raw)) {
            Path clip = new Path();
            clip.addRoundRect(new RectF(0, 0, size, size), radius, radius, Path.Direction.CW);
            c.clipPath(clip);
            c.drawBitmap(raw, 0, 0, null);
        } else {
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(PLATE_COLOR);
            c.drawRoundRect(new RectF(0, 0, size, size), radius, radius, bg);

            int inner = (int) (size * 0.86f);
            int off = (size - inner) / 2;
            Rect dst = new Rect(off, off, off + inner, off + inner);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setFilterBitmap(true);
            c.drawBitmap(raw, null, dst, p);
        }

        return new BitmapDrawable(ctx.getResources(), out);
    }

    /** 边缘基本不透明 → 视为满版图标。 */
    private static boolean isFullBleed(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[][] pts = {
                {0, 0}, {w - 1, 0}, {0, h - 1}, {w - 1, h - 1},
                {w / 2, 0}, {w / 2, h - 1}, {0, h / 2}, {w - 1, h / 2}
        };
        int opaque = 0;
        for (int[] pt : pts) {
            try {
                if (Color.alpha(b.getPixel(pt[0], pt[1])) > 200) opaque++;
            } catch (Exception ignored) { }
        }
        return opaque >= 7;
    }
}
