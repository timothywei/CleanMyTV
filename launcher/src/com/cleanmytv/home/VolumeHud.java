package com.cleanmytv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 音量浮层。双模式：
 *  - 有 SYSTEM_ALERT_WINDOW 权限 → 用 WindowManager 悬浮窗，在其他应用界面上也能显示
 *  - 没有权限或添加失败 → 回退到桌面 Activity 内的 View
 *
 * 数据来源 AudioManager.getStreamVolume（跟随当前输出设备）。
 */
public class VolumeHud {

    private static final String TAG = "CleanMyTV";

    private final Context ctx;
    private final FrameLayout container;
    private final LinearLayout panel;
    private final TextView valueText;
    private final Bar bar;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private WindowManager wm;
    private boolean overlayMode = false;
    private boolean overlayAdded = false;

    private final Runnable hideTask = new Runnable() {
        @Override public void run() { setVisible(false); }
    };

    public VolumeHud(Context ctx, FrameLayout container) {
        this.ctx = ctx;
        this.container = container;

        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(18), dp(12), dp(20), dp(12));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xD9000000);
        bg.setCornerRadius(dp(14));
        panel.setBackground(bg);

        TextView label = new TextView(ctx);
        label.setText("音量");
        label.setTextColor(0xFFEDEDED);
        label.setTextSize(14);
        panel.addView(label);

        valueText = new TextView(ctx);
        valueText.setTextColor(0xFFFFFFFF);
        valueText.setTextSize(18);
        valueText.setPadding(dp(10), 0, dp(16), 0);
        panel.addView(valueText);

        bar = new Bar(ctx);
        panel.addView(bar, new LinearLayout.LayoutParams(dp(180), dp(8)));

        panel.setVisibility(View.GONE);

        // 优先悬浮窗
        if (canDrawOverlays()) {
            try {
                wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    wm.addView(panel, overlayParams());
                    overlayAdded = true;
                    overlayMode = true;
                    DbgLog.d(ctx, "VolumeHud: overlay mode OK");
                }
            } catch (Throwable t) {
                DbgLog.d(ctx, "VolumeHud: overlay failed -> fallback, " + t);
                overlayMode = false;
                overlayAdded = false;
            }
        } else {
            DbgLog.d(ctx, "VolumeHud: no overlay permission -> activity mode");
        }

        // 回退：放进 Activity 容器（container 为 null 表示由 Service 使用，只支持悬浮窗）
        if (!overlayMode) {
            if (container != null) {
                try {
                    panel.setVisibility(View.GONE);
                    container.addView(panel, containerParams());
                } catch (Throwable ignored) { }
            } else {
                DbgLog.d(ctx, "VolumeHud: no overlay permission and no container -> disabled");
            }
        }
    }

    /** 读取当前音量并刷新显示。 */
    public void show() {
        int cur = 0, max = 100;
        boolean ok = false;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                ok = true;
            }
        } catch (Throwable ignored) { }

        if (!ok) {
            try {
                int arc = Settings.System.getInt(ctx.getContentResolver(), "volume_music_hdmi_arc", -1);
                if (arc >= 0 && arc <= 100) {
                    cur = arc;
                    max = 100;
                }
            } catch (Throwable ignored) { }
        }

        int pct = max <= 0 ? 0 : Math.round(cur * 100f / max);
        try {
            valueText.setText(cur + " / " + max + "   (" + pct + "%)");
            bar.setProgress(cur, max);
        } catch (Throwable ignored) { }

        setVisible(true);
        handler.removeCallbacks(hideTask);
        handler.postDelayed(hideTask, 2500);
    }

    public void hide() {
        handler.removeCallbacks(hideTask);
        setVisible(false);
    }

    /**
     * 释放资源：把视图从 WindowManager / 容器上移除。
     * Service 销毁时必须调用，否则 WindowManager 会一直持有这个 View（泄漏）。
     */
    public void destroy() {
        handler.removeCallbacks(hideTask);
        try {
            if (overlayAdded && wm != null) {
                wm.removeView(panel);
            }
        } catch (Throwable ignored) {
        } finally {
            overlayAdded = false;
            overlayMode = false;
        }
        try {
            if (container != null && panel.getParent() == container) {
                container.removeView(panel);
            }
        } catch (Throwable ignored) { }
    }

    /** 当前是否走全局悬浮窗模式。 */
    public boolean isOverlayMode() {
        return overlayMode && overlayAdded;
    }

    private void setVisible(boolean visible) {
        try {
            panel.setVisibility(visible ? View.VISIBLE : View.GONE);
        } catch (Throwable ignored) { }
    }

    private boolean canDrawOverlays() {
        try {
            return Settings.canDrawOverlays(ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    private WindowManager.LayoutParams overlayParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(92);
        return lp;
    }

    private FrameLayout.LayoutParams containerParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(92);
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    /** 圆角进度条 */
    private static class Bar extends View {
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float ratio = 0f;

        Bar(Context ctx) {
            super(ctx);
            bgPaint.setColor(0x33FFFFFF);
            fgPaint.setColor(0xFF2F6BFF);
        }

        void setProgress(int cur, int max) {
            ratio = max <= 0 ? 0f : Math.max(0f, Math.min(1f, (float) cur / max));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float r = getHeight() / 2f;
            rect.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(rect, r, r, bgPaint);
            rect.set(0, 0, getWidth() * ratio, getHeight());
            canvas.drawRoundRect(rect, r, r, fgPaint);
        }
    }
}
