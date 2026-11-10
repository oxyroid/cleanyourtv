package com.dbos.pilot;

import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;

/**
 * Remote & maintenance page: the switches whose defaults are chosen so the
 * app never changes factory behavior by merely being installed, except OTA
 * blocking — a system update is what breaks a customized set, so blocking is
 * the safer default. Rows moved away from their defaults get a " *" title.
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
    }

    /** Switches write through Config so /config defaults stay consistent. */
    private void bind(final String key, final int titleRes) {
        Preference p = findPreference(key);
        if (p == null) return;
        p.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override public boolean onPreferenceChange(Preference pr, Object nv) {
                Config.set(getActivity(), key, Boolean.TRUE.equals(nv));
                markOne(pr, key, titleRes);
                return true;   // we persisted it ourselves
            }
        });
        markOne(p, key, titleRes);
    }

    private void markOne(Preference p, String key, int titleRes) {
        boolean changed = Config.changedFromDefault(getActivity(), key);
        String t = getString(titleRes);
        p.setTitle(changed ? t + " *" : t);
    }
}
