package com.dbos.pilot;

import android.content.Intent;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;

/**
 * About screen: device identity with the console address and token, the
 * usage card (icon-style key hints), and the global reset.
 */
public class AboutFragment extends PreferenceFragment {

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        // All preferences persist into the same store Config reads.
        getPreferenceManager().setSharedPreferencesName("pilot");
        addPreferencesFromResource(R.xml.about);
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
        findPreference("reset").setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    @Override public boolean onPreferenceClick(Preference p) {
                        Config.reset(getActivity());
                        send(PilotService.ACT_APPLY_VOLUME);
                        refresh();
                        return true;
                    }
                });
    }

    private void refresh() {
        Preference dev = findPreference("device");
        if (dev == null || getActivity() == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(android.os.Build.MODEL).append(" · Android ")
          .append(android.os.Build.VERSION.RELEASE).append('\n');
        java.util.List<String> ips = PilotService.localIPv4();
        sb.append("IP: ").append(ips.isEmpty() ? "-" : ips.get(0)).append('\n');
        sb.append("Console: http://").append(ips.isEmpty() ? "<ip>" : ips.get(0))
          .append(':').append(PilotService.HTTP_PORT).append("/ui\n");
        sb.append("Token: ").append(PilotService.TOKEN);
        dev.setSummary(sb.toString());
    }

    private void send(String action) {
        try {
            Intent it = new Intent(getActivity(), PilotService.class);
            it.setAction(action);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                getActivity().startForegroundService(it);
            } else {
                getActivity().startService(it);
            }
        } catch (Throwable ignored) {}
    }
}
