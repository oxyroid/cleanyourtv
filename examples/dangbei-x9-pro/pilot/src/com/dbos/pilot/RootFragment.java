package com.dbos.pilot;

import android.os.Bundle;
import android.os.Handler;
import android.preference.Preference;
import android.preference.PreferenceFragment;

/**
 * Status page: live device identity (IP included — the console address is
 * derived from it), guard/channel states, and the key-hint card.
 */
public class RootFragment extends PreferenceFragment {

    private final Handler h = new Handler();
    private Preference status;

    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); h.postDelayed(this, 2000); }
    };

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        // All preferences persist into the same store Config reads.
        getPreferenceManager().setSharedPreferencesName("pilot");
        addPreferencesFromResource(R.xml.root);
    }

    @Override
    public void onResume() {
        super.onResume();
        status = findPreference("status");
        h.post(tick);
    }

    @Override
    public void onPause() {
        super.onPause();
        h.removeCallbacksAndMessages(null);
    }

    private void refresh() {
        if (status == null || getActivity() == null) return;
        StringBuilder sb = new StringBuilder();
        java.util.List<String> ips = PilotService.localIPv4();
        String ip = ips.isEmpty() ? "-" : ips.get(0);
        sb.append("IP: ").append(ip).append('\n');
        sb.append("ADB: ").append(PilotService.adbdState)
          .append(" · guard: ").append(HomeGuardService.connected ? "on" : "off").append('\n');
        sb.append("logd: ").append(PilotService.shStatic("getprop init.svc.logd").trim());
        sb.append(" · update engine: ").append(
                PilotService.shStatic("getprop init.svc.update_engine").trim()).append('\n');
        sb.append("volume: ").append(PilotService.volNativeState).append('\n');
        sb.append("console: http://").append(ip).append(':')
          .append(PilotService.HTTP_PORT).append("/ui");
        status.setSummary(sb.toString());
    }
}
