package com.dbos.pilot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Revival receiver. The ROM refuses to start processes for BOOT_COMPLETED,
 * but these broadcasts arrive minutes after boot, past the control window:
 * NTP time sync (auto_time=1 on this set), midnight, timezone changes, alarm
 * changes, and any package install/update/remove. Whichever arrives first
 * revives the process; the Job chain (KeepJobService) remains the primary
 * mechanism, this receiver is redundancy.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            Intent svc = new Intent(ctx, PilotService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(svc);
            } else {
                ctx.startService(svc);
            }
            Log.i(PilotService.TAG, "revived by " + (intent != null ? intent.getAction() : "?"));
        } catch (Throwable t) {
            Log.e(PilotService.TAG, "revive failed", t);
        }
    }
}
