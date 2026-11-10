package com.dbos.pilot;

import android.content.Intent;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;

/**
 * Sound screen: the better-audio preset (primary entry point — it derives the
 * three low-level switches), the switches themselves for fine-tuning, and the
 * one-tap factory restore.
 */
public class AudioFragment extends PreferenceFragment {

    private ListPreference curve;

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        // All preferences persist into the same store Config reads.
        getPreferenceManager().setSharedPreferencesName("pilot");
        addPreferencesFromResource(R.xml.audio);
    }

    @Override
    public void onResume() {
        super.onResume();
        curve = (ListPreference) findPreference(Config.Key.VOLCURVE);
        curve.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override public boolean onPreferenceChange(Preference p, Object nv) {
                String name = String.valueOf(nv);
                Config.applyPreset(getActivity(), name);
                applyVolume();
                updateCurve(name);
                mark();
                return true;   // we already persisted it ourselves
            }
        });
        String cur = Config.getStr(getActivity(), Config.Key.VOLCURVE, BetterAudio.FINE100);
        curve.setValue(cur);
        updateCurve(cur);

        bind(Config.Key.FINEVOL, R.string.t_finevol);
        bind(Config.Key.VOLOSD, R.string.t_volosd);
        bind(Config.Key.NIGHTGAIN, R.string.t_nightgain);
        mark();
    }

    /** Switch → Config + immediate maintenance pass. */
    private void bind(final String key, final int titleRes) {
        Preference p = findPreference(key);
        if (p == null) return;
        p.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override public boolean onPreferenceChange(Preference pr, Object nv) {
                Config.set(getActivity(), key, Boolean.TRUE.equals(nv));
                applyVolume();
                markOne(pr, key, titleRes);
                return true;
            }
        });
    }

    private void applyVolume() {
        try {
            Intent it = new Intent(getActivity(), PilotService.class);
            it.setAction(PilotService.ACT_APPLY_VOLUME);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                getActivity().startForegroundService(it);
            } else {
                getActivity().startService(it);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen screen, Preference p) {
        if ("restore_stock".equals(p.getKey())) {
            Config.applyPreset(getActivity(), BetterAudio.STOCK);
            applyVolume();
            if (curve != null) {
                curve.setValue(BetterAudio.STOCK);
                updateCurve(BetterAudio.STOCK);
            }
            mark();
            return true;
        }
        return super.onPreferenceTreeClick(screen, p);
    }

    private void updateCurve(String name) {
        int res;
        if (BetterAudio.STOCK.equals(name)) res = R.string.s_curve_stock;
        else if (BetterAudio.STEP30.equals(name)) res = R.string.s_curve_step30;
        else if (BetterAudio.PERCEPTUAL.equals(name)) res = R.string.s_curve_perceptual;
        else res = R.string.s_curve_fine100;
        curve.setSummary(res);
    }

    private void mark() {
        markOne(findPreference(Config.Key.FINEVOL), Config.Key.FINEVOL, R.string.t_finevol);
        markOne(findPreference(Config.Key.VOLOSD), Config.Key.VOLOSD, R.string.t_volosd);
        markOne(findPreference(Config.Key.NIGHTGAIN), Config.Key.NIGHTGAIN, R.string.t_nightgain);
        markOne(curve, Config.Key.VOLCURVE, R.string.t_curve);
    }

    private void markOne(Preference p, String key, int titleRes) {
        if (p == null) return;
        boolean changed;
        if (Config.Key.VOLCURVE.equals(key)) {
            changed = !BetterAudio.FINE100.equals(
                    Config.getStr(getActivity(), key, BetterAudio.FINE100));
        } else {
            changed = Config.changedFromDefault(getActivity(), key);
        }
        String t = getString(titleRes);
        p.setTitle(changed ? t + " *" : t);
    }
}
