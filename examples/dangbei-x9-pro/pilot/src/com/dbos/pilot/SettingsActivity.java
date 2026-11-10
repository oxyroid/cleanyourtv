package com.dbos.pilot;

import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;

/**
 * Settings host: the whole app is a preference tree, so it uses the platform's
 * own settings UI (PreferenceFragment) instead of hand-drawn views — standard
 * D-pad navigation, focus highlight, toggles and list dialogs come for free
 * and match the system's look.
 *
 * Plain single-pane Activity with a fragment host: PreferenceActivity's own
 * containers are unreliable on this ROM (its TV customization ships a header
 * layout without the single-pane preference container, which crashes any
 * fragment mounting). Fragment switching is explicit: onCreate mounts the
 * root screen, and onPreferenceStartFragment — the platform callback for
 * preferences carrying an android:fragment target — mounts the next level
 * with a back-stack entry, so every topic gets its own page.
 */
public class SettingsActivity extends Activity
        implements PreferenceFragment.OnPreferenceStartFragmentCallback {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.settings);
        if (b == null) {
            getFragmentManager().beginTransaction()
                    .replace(R.id.prefs_host, new RootFragment())
                    .commit();
        }
        // Ensure the resident service is alive (also arms the Job chain on
        // first launch — the JobScheduler persisted chain lives in /data).
        try {
            Intent svc = new Intent(this, PilotService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Throwable ignored) {}
    }

    /** Platform callback: a preference with android:fragment was clicked. */
    @Override
    public boolean onPreferenceStartFragment(PreferenceFragment caller, Preference pref) {
        String cls = pref.getFragment();
        if (cls == null) return false;
        try {
            Fragment f = Fragment.instantiate(this, cls, pref.getExtras());
            getFragmentManager().beginTransaction()
                    .replace(R.id.prefs_host, f)
                    .addToBackStack(null)
                    .commit();
            setTitle(pref.getTitle());
            return true;
        } catch (Throwable t) {
            android.util.Log.e(PilotService.TAG, "open " + cls, t);
            return false;
        }
    }

    /** Back from a nested page pops it; the root title is restored. */
    @Override
    public void onBackPressed() {
        if (getFragmentManager().getBackStackEntryCount() > 0) {
            getFragmentManager().popBackStack();
            setTitle(R.string.app_name);
            return;
        }
        super.onBackPressed();
    }
}
