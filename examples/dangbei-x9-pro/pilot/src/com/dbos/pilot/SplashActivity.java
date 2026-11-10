package com.dbos.pilot;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Splash: shown once at every launch while the app starts in the
 * background. Two jobs, both informational only — nothing here blocks
 * entering the settings:
 *
 *  1. Device check: the whole app is tuned against one specific set
 *     (the Dangbei X9 Pro on the tested firmware build). A different
 *     model or OS build is *not* refused — the shell commands and volume
 *     internals differ between builds, so the risk is called out
 *     ("this set was tuned on X9 Pro / Android 14; features may misbehave
 *     or be inert") and the user proceeds at their own risk.
 *  2. Disclaimer: this software is for learning purposes only; the user
 *     agrees to delete it within 24 hours.
 *
 * The check runs instantly (property reads); the splash auto-advances
 * after a short hold so a TV remote without focus still gets through.
 */
public class SplashActivity extends Activity {

    /** The exact device+build combination this app was tuned and tested on. */
    private static final String TESTED_DEVICE   = "horse_popular";
    private static final String TESTED_MODEL    = "X9 Pro";
    private static final String TESTED_RELEASE  = "14";
    private static final String TESTED_DISPLAY  = "UQ1A.240105.004.A1";

    private static final long AUTO_MS = 3000;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildView());

        // Start the resident service while the card is on screen.
        try {
            Intent svc = new Intent(this, PilotService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
        } catch (Throwable ignored) {}

        findViewById(android.R.id.content).postDelayed(new Runnable() {
            @Override public void run() { enter(); }
        }, AUTO_MS);
    }

    private void enter() {
        startActivity(new Intent(this, SettingsActivity.class));
        finish();
    }

    private View buildView() {
        int pad = dp(32);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101018);
        root.setPadding(pad, pad, pad, pad);

        TextView app = new TextView(this);
        app.setText(R.string.app_name);
        app.setTextColor(0xFFFFFFFF);
        app.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 32);

        TextView sub = new TextView(this);
        sub.setText(R.string.splash_tag);
        sub.setTextColor(0xFF9AA0B5);
        sub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        sub.setPadding(0, dp(4), 0, dp(20));

        // Device check — informational only, never blocking.
        boolean match = deviceMatches();
        TextView check = new TextView(this);
        check.setText(getString(R.string.splash_device,
                android.os.Build.MODEL + " · Android " + android.os.Build.VERSION.RELEASE
                        + (match ? "" : " (" + android.os.Build.DISPLAY + ")"),
                TESTED_MODEL + " · Android " + TESTED_RELEASE));
        check.setTextColor(match ? 0xFF7BD88F : 0xFFE6A44C);
        check.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        check.setPadding(0, 0, 0, dp(14));

        TextView warn = new TextView(this);
        warn.setText(match ? R.string.splash_ok : R.string.splash_warn);
        warn.setTextColor(0xFFC6CBD8);
        warn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        warn.setLineSpacing(dp(2), 1f);
        warn.setPadding(0, 0, 0, dp(18));

        TextView legal = new TextView(this);
        legal.setText(R.string.splash_legal);
        legal.setTextColor(0xFF8A90A3);
        legal.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        legal.setLineSpacing(dp(2), 1f);
        legal.setPadding(0, 0, 0, dp(22));

        TextView enter = new TextView(this);
        enter.setText(getString(R.string.splash_enter, " OK "));
        enter.setTextColor(0xFFFFFFFF);
        enter.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF27427A);
        bg.setCornerRadius(dp(10));
        enter.setBackground(bg);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        enter.setPadding(dp(40), dp(12), dp(40), dp(12));
        enter.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { enter(); }
        });
        enter.setFocusable(true);
        enter.requestFocus();

        root.addView(app);
        root.addView(sub);
        root.addView(check);
        root.addView(warn);
        root.addView(legal);
        root.addView(enter);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    /** Device + OS fingerprint match against the tested combination. */
    private boolean deviceMatches() {
        return TESTED_DEVICE.equals(android.os.Build.DEVICE)
                && TESTED_MODEL.equals(android.os.Build.MODEL)
                && TESTED_RELEASE.equals(android.os.Build.VERSION.RELEASE)
                && android.os.Build.DISPLAY != null
                && android.os.Build.DISPLAY.contains(TESTED_DISPLAY);
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        enter();                       // any key skips the splash
        return true;
    }

    private int dp(int d) {
        return Math.round(d * getResources().getDisplayMetrics().density);
    }
}
