package com.dbos.pilot;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;

import java.util.List;

/**
 * Guard screen: the home-guard switch, the launcher picker (default: the
 * automatic rule — first third-party launcher), the one-tap self-grant that
 * arms the whole app without a PC, guard repair, and the boot home fix.
 */
public class GuardFragment extends PreferenceFragment {

    private final Handler h = new Handler();

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        // All preferences persist into the same store Config reads.
        getPreferenceManager().setSharedPreferencesName("pilot");
        addPreferencesFromResource(R.xml.guard);
    }

    @Override
    public void onResume() {
        super.onResume();

        Preference g = findPreference(Config.Key.HOMEGUARD);
        g.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override public boolean onPreferenceChange(Preference p, Object nv) {
                Config.set(getActivity(), Config.Key.HOMEGUARD, Boolean.TRUE.equals(nv));
                if (!Boolean.TRUE.equals(nv)) {
                    // Clean off: strip the accessibility grant, not just the flag.
                    send(PilotService.ACT_GUARD_OFF);
                }
                mark();
                return true;
            }
        });

        Preference fx = findPreference(Config.Key.HOMEFIX);
        fx.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override public boolean onPreferenceChange(Preference p, Object nv) {
                Config.set(getActivity(), Config.Key.HOMEFIX, Boolean.TRUE.equals(nv));
                mark();
                return true;
            }
        });

        buildLauncherList();

        Preference grant = findPreference("grant");
        grant.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override public boolean onPreferenceClick(final Preference p) {
                p.setSummary("…");
                final Thread t = new Thread(new Runnable() {
                    @Override public void run() {
                        final String r = PilotService.selfGrant(getActivity());
                        h.post(new Runnable() {
                            @Override public void run() {
                                if (!isAdded()) return;
                                p.setSummary("granted".equals(r)
                                        ? getString(R.string.s_grant) : (r + " *"));
                            }
                        });
                    }
                });
                t.setDaemon(true);
                t.start();
                return true;
            }
        });

        findPreference("repair").setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    @Override public boolean onPreferenceClick(Preference p) {
                        send(PilotService.ACT_REPAIR_GUARD);
                        return true;
                    }
                });

        mark();
    }

    /** Launcher picker: Auto (the automatic rule) + every installed HOME candidate. */
    private void buildLauncherList() {
        ListPreference lp = (ListPreference) findPreference(Config.Key.HOMETARGET);
        List<ComponentName> cands = HomeLauncher.listCandidates(getActivity());
        String[] entries = new String[cands.size() + 1];
        String[] values = new String[cands.size() + 1];
        entries[0] = getString(R.string.s_home_target_auto);
        values[0] = HomeLauncher.AUTO;
        for (int i = 0; i < cands.size(); i++) {
            entries[i + 1] = HomeLauncher.labelOf(getActivity(), cands.get(i));
            values[i + 1] = cands.get(i).flattenToString();
        }
        lp.setEntries(entries);
        lp.setEntryValues(values);
        String cur = Config.getStr(getActivity(), Config.Key.HOMETARGET, HomeLauncher.AUTO);
        lp.setValue(cur);
        lp.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override public boolean onPreferenceChange(Preference p, Object nv) {
                Config.setStr(getActivity(), Config.Key.HOMETARGET, String.valueOf(nv));
                mark();
                return true;
            }
        });
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

    private void mark() {
        markOne(findPreference(Config.Key.HOMEGUARD), Config.Key.HOMEGUARD, R.string.t_homeguard);
        markOne(findPreference(Config.Key.HOMEFIX), Config.Key.HOMEFIX, R.string.t_homefix);
        markOne(findPreference(Config.Key.HOMETARGET), Config.Key.HOMETARGET, R.string.t_home_target);
    }

    private void markOne(Preference p, String key, int titleRes) {
        if (p == null) return;
        boolean changed;
        if (Config.Key.HOMETARGET.equals(key)) {
            changed = !HomeLauncher.AUTO.equals(
                    Config.getStr(getActivity(), key, HomeLauncher.AUTO));
        } else {
            changed = Config.changedFromDefault(getActivity(), key);
        }
        String t = getString(titleRes);
        p.setTitle(changed ? t + " *" : t);
    }
}
