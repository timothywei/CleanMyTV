package com.cleanmytv.home;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

/**
 * 前台服务：让音量监听在桌面退到后台后仍能工作（避免被系统冻结），
 * 从而在任何应用界面上都能弹出音量浮层。
 *
 * 只做一件事：注册 volume_* 的 ContentObserver，变化时让 VolumeHud 显示。
 * 所有关键步骤都会写 DbgLog，方便重启后排查「服务有没有起来」。
 */
public class VolumeService extends Service {

    private static final String CHANNEL_ID = "cleanyourtv_volume";
    private static final int NOTIF_ID = 1001;

    private VolumeHud hud;
    private ContentObserver observer;

    @Override
    public void onCreate() {
        super.onCreate();
        DbgLog.d(this, "VolumeService onCreate (pid=" + android.os.Process.myPid() + ")");

        // 必须先 startForeground，否则 Android 8+ 会抛异常
        try {
            startForeground(NOTIF_ID, buildNotification());
            DbgLog.d(this, "VolumeService startForeground ok");
        } catch (Throwable t) {
            DbgLog.d(this, "VolumeService startForeground FAILED " + t);
        }

        try {
            boolean canOverlay = false;
            try {
                canOverlay = Settings.canDrawOverlays(this);
            } catch (Throwable ignored) { }
            DbgLog.d(this, "VolumeService canDrawOverlays=" + canOverlay);

            hud = new VolumeHud(this, null);
            DbgLog.d(this, "VolumeService hud created, overlayMode=" + hud.isOverlayMode());
        } catch (Throwable t) {
            DbgLog.d(this, "VolumeService hud init FAILED " + t);
        }

        try {
            observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override public void onChange(boolean selfChange) {
                    try { if (hud != null) hud.show(); } catch (Throwable ignored) { }
                }
            };
            ContentResolver cr = getContentResolver();
            for (String k : new String[]{
                    "volume_music_hdmi_arc", "volume_music",
                    "volume_music_speaker", "volume_music_hdmi"}) {
                cr.registerContentObserver(Settings.System.getUriFor(k), false, observer);
            }
            DbgLog.d(this, "VolumeService observer registered");
        } catch (Throwable t) {
            DbgLog.d(this, "VolumeService observer register FAILED " + t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        DbgLog.d(this, "VolumeService onStartCommand startId=" + startId
                + " from=" + (intent == null ? "null" : "intent"));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        DbgLog.d(this, "VolumeService onDestroy");
        try {
            if (observer != null) getContentResolver().unregisterContentObserver(observer);
        } catch (Throwable ignored) { }
        try {
            if (hud != null) hud.destroy();
        } catch (Throwable ignored) { }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "音量提示", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("在其他应用上调音量时显示数值");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        } catch (Throwable ignored) { }

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("CleanMyTV")
                .setContentText("音量提示运行中")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW);
        return b.build();
    }
}
