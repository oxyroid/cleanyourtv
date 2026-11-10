package com.dbos.scout;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 开机自启：把常驻服务拉起来，服务里会在 25 秒后自动 setprop ctl.start adbd。
 * 这样设备重启后 ADB 会自动回来，不需要人到机器前用遥控器点图标。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String act = intent == null ? "" : intent.getAction();
        Log.i(ScoutService.TAG, "boot receiver: " + act);
        try {
            Intent svc = new Intent(ctx, ScoutService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(svc);
            } else {
                ctx.startService(svc);
            }
        } catch (Exception e) {
            Log.e(ScoutService.TAG, "boot start failed", e);
        }
    }
}
