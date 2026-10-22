package com.cleanmytv.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 开机完成后拉起音量前台服务。
 *
 * 桌面 Activity 在开机时也会启动服务，但那一刻系统还没完全就绪，
 * startForegroundService 有可能失败。BOOT_COMPLETED 更晚、更稳定，作为第二道保险。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? "null" : intent.getAction();
        DbgLog.d(ctx, "BootReceiver onReceive: " + action);

        try {
            Intent svc = new Intent(ctx, VolumeService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(svc);
            } else {
                ctx.startService(svc);
            }
            DbgLog.d(ctx, "BootReceiver: startForegroundService ok");
        } catch (Throwable t) {
            DbgLog.d(ctx, "BootReceiver: startForegroundService FAILED " + t);
        }
    }
}
