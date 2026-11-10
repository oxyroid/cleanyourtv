package com.dbos.pilot;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Intent bridge: generic launchers send standard Android Settings intents,
 * but this ROM implements none of them (the settings app does not exist) —
 * the button dies with "Can't open that here". This transparent activity
 * registers the standard intents and forwards to the screens that do exist:
 *
 *   android.settings.SETTINGS / WIFI_SETTINGS / WIRELESS_SETTINGS
 *       -> com.dangbei.lerad.etna/.MainActivity (the settings host)
 *   ACTION_VIEW + vnd.android.cursor.item/channel (MTK TV channel URI)
 *       -> com.xgimi.xhplayer/.InputActivity (HDMI/source picker)
 *
 * Extend the table for other models. Failures are swallowed: a broken button
 * is better than a crash in a launcher's settings path.
 */
public class BridgeActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String target = null;
        try {
            Intent in = getIntent();
            String action = in != null ? in.getAction() : null;

            if (Intent.ACTION_VIEW.equals(action)) {
                target = "com.xgimi.xhplayer/.InputActivity";
            } else {
                target = "com.dangbei.lerad.etna/.MainActivity";
            }
        } catch (Throwable ignored) {}

        try {
            if (target != null) {
                Intent it = new Intent();
                it.setComponent(android.content.ComponentName.unflattenFromString(target));
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
            }
        } catch (Throwable ignored) {}
        finish();
    }
}
