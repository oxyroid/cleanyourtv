package com.dbos.pilot;

import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;

/**
 * Remote & maintenance page: the switches whose defaults are chosen so the
 * app never changes factory behavior by merely being installed, except OTA
 * blocking — a system update is what breaks a customized set, so blocking is
 * the safer default. Rows moved away from their defaults get a " *" title.
 *
 * Disabled rows: logd and the OTA block both act through the shell channel
 * (LocalAdb), so while adbd is down they are greyed out with the missing
 * dependency in the summary. The adbd switch itself stays selectable —
 * otherwise the user could never re-enable it.
 */
public class MaintenanceFragment extends PreferenceFragment {

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        // All preferences persist into the same store Config reads.
        getPreferenceManager().setSharedPreferencesName("pilot");
        addPreferencesFromResource(R.xml.maintenance);
    }

    @Override
    public void onResume() {
        super.onResume();
        bind(Config.Key.ADBD, R.string.t_adbd);
        bind(Config.Key.ANNOUNCE, R.string.t_announce);
        bind(Config.Key.LOGD, R.string.t_logd);
        bind(Config.Key.OTABLOCK, R.string.t_ota);
        refreshGates();
    }

    /** The shell-acting switches grey out when adbd is down. */
    private void refreshGates() {
        boolean shell = Ui.shellLive();
        Ui.gate(getActivity(), findPreference(Config.Key.LOGD), shell,
                getString(R.string.s_logd),
                getString(R.string.dep_adbd));
        Ui.gate(getActivity(), findPreference(Config.Key.OTABLOCK), shell,
                getString(R.string.s_ota),
                getString(R.string.dep_adbd));
    }

    /** Switches write through Config so /config defaults stay consistent. */
    private void bind(final String key, final int titleRes) {
        Preference p = findPreference(key);
        if (p == null) return;
        p.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override public boolean onPreferenceChange(Preference pr, Object nv) {
                Config.set(getActivity(), key, Boolean.TRUE.equals(nv));
                markOne(pr, key, titleRes);
                refreshGates();
                return true;   // we persisted it ourselves
            }
        });
        markOne(p, key, titleRes);
    }

    private void markOne(Preference p, String key, int titleRes) {
        boolean changed = Config.changedFromDefault(getActivity(), key);
        p.setTitle(Ui.marked(getActivity(), titleRes, changed));
    }
}
