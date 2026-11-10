package com.dbos.pilot;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.preference.Preference;
import android.preference.PreferenceFragment;

import java.util.List;

/**
 * Status page: live device status as compact icon lines (one line per
 * state, refreshed every 2s), the one-tap recommended setup, and the
 * topic screens below.
 *
 * Recommended setup: applies the tested combination (perceptual curve +
 * full home guard chain + OTA block) in one tap. The confirm dialog lists
 * exactly what will change and what stays untouched; cancel keeps
 * everything as-is.
 */
public class RootFragment extends PreferenceFragment {

    private final Handler h = new Handler();
    private Preference status;
    private Preference recommend;

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
        recommend = findPreference("recommend");
        recommend.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override public boolean onPreferenceClick(Preference p) {
                askRecommend();
                return true;
            }
        });
        h.post(tick);
    }

    @Override
    public void onPause() {
        super.onPause();
        h.removeCallbacksAndMessages(null);
    }

    /** One tap → show what would change → confirm or cancel. */
    private void askRecommend() {
        final android.content.Context ctx = getActivity();
        if (ctx == null) return;

        // Diff the current state against the recommended state.
        StringBuilder change = new StringBuilder();
        StringBuilder keep = new StringBuilder();
        String curve = Config.getStr(ctx, Config.Key.VOLCURVE, BetterAudio.FINE100);
        if (!BetterAudio.PERCEPTUAL.equals(curve)) {
            change.append(getString(R.string.rec_vol)).append('\n');
        } else {
            keep.append(getString(R.string.rec_vol_already)).append('\n');
        }
        if (!Config.get(ctx, Config.Key.HOMEGUARD)) change.append(getString(R.string.rec_guard)).append('\n');
        else keep.append(getString(R.string.rec_guard_already)).append('\n');
        if (!Config.get(ctx, Config.Key.HOMEFIX)) change.append(getString(R.string.rec_boot)).append('\n');
        else keep.append(getString(R.string.rec_boot_already)).append('\n');
        if (!Config.get(ctx, Config.Key.OTABLOCK)) change.append(getString(R.string.rec_ota)).append('\n');
        else keep.append(getString(R.string.rec_ota_already)).append('\n');
        if (!HomeGuardService.connected) change.append(getString(R.string.rec_grant)).append('\n');
        else keep.append(getString(R.string.rec_grant_already)).append('\n');
        if (change.length() == 0) {
            Ui.popup(ctx, getString(R.string.rec_nothing), 2600);
            return;
        }
        StringBuilder msg = new StringBuilder();
        msg.append(getString(R.string.rec_will)).append("\n\n").append(change);
        if (keep.length() > 0) msg.append('\n').append(getString(R.string.rec_keeps)).append('\n').append(keep);
        Ui.confirm(ctx, getString(R.string.rec_title), msg, new Runnable() {
            @Override public void run() { applyRecommend(); }
        });
    }

    /** After confirmation: apply, then a result card says what landed. */
    private void applyRecommend() {
        final android.content.Context ctx = getActivity();
        if (ctx == null) return;
        Config.applyPreset(ctx, BetterAudio.PERCEPTUAL);
        Config.set(ctx, Config.Key.HOMEGUARD, true);
        Config.set(ctx, Config.Key.HOMEFIX, true);
        Config.set(ctx, Config.Key.OTABLOCK, true);
        sendService(PilotService.ACT_APPLY_VOLUME);
        final String grantNeeded = !HomeGuardService.connected
                ? getString(R.string.rec_grant_hint) : "";
        // The self-grant runs only when the guard is not already live.
        if (!HomeGuardService.connected) {
            new Thread(new Runnable() {
                @Override public void run() {
                    final String r = PilotService.selfGrant(ctx);
                    h.post(new Runnable() {
                        @Override public void run() {
                            if (!isAdded()) return;
                            boolean ok = "granted".equals(r);
                            Ui.popup(getActivity(),
                                    getString(ok ? R.string.rec_done : R.string.rec_grant_fail)
                                            + (grantNeeded.isEmpty() ? "" : "\n" + grantNeeded),
                                    ok ? 3000 : 5000);
                        }
                    });
                }
            }).start();
        } else {
            Ui.popup(ctx, getString(R.string.rec_done), 2600);
        }
    }

    private void sendService(String action) {
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

    /** Compact icon lines: each state one line, tick or cross, no prose. */
    private void refresh() {
        if (status == null || getActivity() == null) return;
        List<String> ips = PilotService.localIPv4();
        String ip = ips.isEmpty() ? "-" : ips.get(0);
        boolean guard = HomeGuardService.connected;
        boolean shell = Ui.shellLive();
        String eng = PilotService.shStatic("getprop init.svc.update_engine").trim();
        String curve = Config.getStr(getActivity(), Config.Key.VOLCURVE, BetterAudio.FINE100);

        StringBuilder sb = new StringBuilder();
        sb.append(ip).append(" · http://").append(ip).append(':')
          .append(PilotService.HTTP_PORT).append("/ui").append('\n');
        sb.append((guard ? "● " : "○ ") + getString(R.string.st_guard))
          .append((shell ? "  ● " : "  ○ ") + getString(R.string.st_shell)).append('\n');
        sb.append(("stopped".equals(eng) ? "● " : "○ ") + getString(R.string.st_ota))
          .append("  " + getString(R.string.st_curve, curve)).append('\n');
        status.setSummary(sb.toString());
    }
}
