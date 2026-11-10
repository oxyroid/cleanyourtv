package com.dbos.pilot;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.Window;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Shared UI helpers for the settings screens: one consistent way to show
 * feedback (a centered floating card), confirmations (the dpad-navigable
 * system dialog), disabled rows that explain their missing dependency, and
 * the changed-from-default "*" title marking.
 */
final class Ui {

    private static Dialog card;      // shared auto-dismissing feedback card
    private static TextView cardText;

    // ---------------- feedback popup ----------------

    /**
     * Centered floating result card: dark rounded card over a dimmed screen,
     * no buttons, auto-dismissed after ms (OK/back dismisses it early too).
     * Needs an Activity context; anything else falls back to a toast.
     */
    static void popup(final Context ctx, final CharSequence text, int ms) {
        try {
            if (!(ctx instanceof Activity)) {
                Toast.makeText(ctx, text, Toast.LENGTH_LONG).show();
                return;
            }
            final Activity act = (Activity) ctx;
            act.runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        dismissCard();
                        cardText = new TextView(act);
                        cardText.setTextColor(0xFFF2F2F2);
                        cardText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17);
                        android.graphics.drawable.GradientDrawable bg =
                                new android.graphics.drawable.GradientDrawable();
                        bg.setColor(0xF21B1B22);
                        bg.setCornerRadius(dp(act, 14));
                        cardText.setBackground(bg);
                        int pad = dp(act, 28);
                        cardText.setPadding(pad, dp(act, 20), pad, dp(act, 20));
                        cardText.setMaxWidth(dp(act, 480));
                        cardText.setText(text);
                        final Dialog d = card = new Dialog(act);
                        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
                        d.setContentView(cardText);
                        Window w = d.getWindow();
                        if (w != null) {
                            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
                            w.setDimAmount(0.35f);
                        }
                        d.show();
                        final TextView tv = cardText;
                        tv.postDelayed(new Runnable() {
                            @Override public void run() {
                                if (card == d) { try { d.dismiss(); } catch (Throwable ignored) {} card = null; }
                            }
                        }, ms);
                    } catch (Throwable t) {
                        try { Toast.makeText(act, text, Toast.LENGTH_LONG).show(); }
                        catch (Throwable ignored) {}
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    /** Dismiss a previous card before showing a new one. */
    private static void dismissCard() {
        if (card != null) {
            try { card.dismiss(); } catch (Throwable ignored) {}
            card = null;
        }
    }

    // ---------------- confirm dialog (blocking) ----------------

    /** Classic dpad-navigable confirm; the callback runs on the main thread. */
    static void confirm(Context act, CharSequence title, CharSequence msg,
                        final Runnable onOk) {
        new AlertDialog.Builder(act)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { onOk.run(); }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---------------- disabled rows ----------------

    /**
     * Grey out a row while its dependency is missing and explain why in the
     * summary; call with available=true and the normal summary to restore.
     */
    static void gate(Context ctx, android.preference.Preference p, boolean available,
                     CharSequence normalSummary, CharSequence missingSummary) {
        if (p == null) return;
        p.setEnabled(available);
        p.setSummary(available ? normalSummary : missingSummary);
    }

    /** True when the guard's accessibility grant is live. */
    static boolean guardLive() {
        return HomeGuardService.connected;
    }

    /** True when adbd is up (the app's shell channel works). */
    static boolean shellLive() {
        return "running".equals(PilotService.adbdState);
    }

    // ---------------- "*" marking ----------------

    /** Title + " *" when the value moved away from its default. */
    static String marked(Context ctx, int titleRes, boolean changed) {
        String t = ctx.getString(titleRes);
        return changed ? t + " *" : t;
    }

    private static int dp(Context c, int d) {
        return Math.round(d * c.getResources().getDisplayMetrics().density);
    }

    private Ui() {}
}
