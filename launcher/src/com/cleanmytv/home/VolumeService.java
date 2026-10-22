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
import android.util.Log;

/**
 * 前台服务：让音量监听在桌面退到后台后仍能工作（避免被系统冻结），
 * 从而在任何应用界面上都能弹出音量浮层。
 *
 * 只做一件事：注册 volume_* 的 ContentObserver，变化时让 VolumeHud 显示。
 */
public class VolumeService extends Service {

    private static final String TAG = "CleanMyTV";
    private static final String CHANNEL_ID = "cleanyourtv_volume";
    private static final int NOTIF_ID = 1001;

    private VolumeHud hud;
    private ContentObserver observer;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "VolumeService onCreate");

        // 必须先 startForeground，否则 Android 8+ 会抛异常
        try {
            startForeground(NOTIF_ID, buildNotification());
        } catch (Throwable t) {
            Log.d(TAG, "startForeground failed: " + t);
        }

        try {
            hud = new VolumeHud(this, null);
            Log.d(TAG, "VolumeService hud overlay=" + hud.isOverlayMode());
        } catch (Throwable t) {
            Log.d(TAG, "hud init failed: " + t);
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
            Log.d(TAG, "VolumeService observer registered");
        } catch (Throwable t) {
            Log.d(TAG, "observer register failed: " + t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (observer != null) getContentResolver().unregisterContentObserver(observer);
        } catch (Throwable ignored) { }
        try {
            if (hud != null) hud.destroy();
        } catch (Throwable ignored) { }
        Log.d(TAG, "VolumeService onDestroy");
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
