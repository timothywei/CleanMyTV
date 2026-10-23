package com.cleanmytv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/**
 * 带半透明黑色描边的 TextView。
 *
 * 壁纸去掉暗色遮罩后按原色显示，白色文字直接压在亮色照片上会看不清，
 * 这里给文字轮廓再描一圈半透明黑边线来保证可读性。
 *
 * 实现要点：TextView.onDraw 会把 paint 颜色强制同步成文字色，
 * 因此不能通过改 paint 颜色来画描边；这里在正常填充完成之后，
 * 把同一个 paint 临时切成 STROKE 再走一遍 Layout.draw，最后恢复现场。
 */
public class OutlinedTextView extends TextView {

    /** 默认描边：约 60% 黑 */
    private static final int DEFAULT_STROKE_COLOR = 0x99000000;
    /** 默认描边线宽（dp） */
    private static final float DEFAULT_STROKE_DP = 1.5f;

    private int strokeColor = DEFAULT_STROKE_COLOR;
    private float strokeWidthDp = DEFAULT_STROKE_DP;

    public OutlinedTextView(Context context) {
        super(context);
    }

    /** 描边颜色（含透明度）与线宽（dp）；widthDp <= 0 表示关闭描边。 */
    public void setStroke(int color, float widthDp) {
        strokeColor = color;
        strokeWidthDp = widthDp;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // 先描边、后填充：描边必须垫在字符下面。
        // 反过来的话描边会盖住笔画边缘，字会变细、发虚，像空心字。
        drawStroke(canvas);
        super.onDraw(canvas);
    }

    private void drawStroke(Canvas canvas) {
        final Layout layout = getLayout();
        if (layout == null || strokeWidthDp <= 0f) return;

        final float width = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                strokeWidthDp, getResources().getDisplayMetrics());
        if (width <= 0f) return;

        final TextPaint paint = getPaint();
        final int oldColor = paint.getColor();
        final Paint.Style oldStyle = paint.getStyle();
        final float oldWidth = paint.getStrokeWidth();
        final Paint.Join oldJoin = paint.getStrokeJoin();
        try {
            paint.setColor(strokeColor);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width);
            paint.setStrokeJoin(Paint.Join.ROUND);

            canvas.save();
            canvas.translate(getCompoundPaddingLeft(),
                    getCompoundPaddingTop() + vOffset(layout));
            layout.draw(canvas);
            canvas.restore();
        } catch (Throwable ignored) {
            // 描边只是可读性增强，任何异常都不能影响文字本身
        } finally {
            paint.setColor(oldColor);
            paint.setStyle(oldStyle);
            paint.setStrokeWidth(oldWidth);
            paint.setStrokeJoin(oldJoin);
        }
    }

    /** 文本在可用高度内的垂直偏移，与 TextView 自身的 gravity 规则保持一致。 */
    private int vOffset(Layout layout) {
        int avail = getHeight() - getCompoundPaddingTop() - getCompoundPaddingBottom();
        int gravity = getGravity() & Gravity.VERTICAL_GRAVITY_MASK;
        if (gravity == Gravity.BOTTOM) return avail - layout.getHeight();
        if (gravity == Gravity.CENTER_VERTICAL) return (avail - layout.getHeight()) / 2;
        return 0;
    }
}
