package com.dbos.pilot;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * Home guard (accessibility service) + exact-volume overlay.
 *
 * Home guard: this ROM's launcher (and some of its sibling processes) keeps
 * grabbing the foreground after boot and after its own pages close; one-shot
 * home takeovers get stolen back. The reliable answer is a system-level window
 * subscription: whenever the stock launcher's main HOME activity comes to the
 * front, launch the user's launcher again. Two channels cover the cases:
 *   1. TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOWS_CHANGED events — immediate;
 *   2. a 4-second getWindows() poll — catches "stock launcher was already on
 *      screen when the service connected" (boot), which produces no new event.
 * Only the stock launcher's *main* HOME activity is corrected; its secondary
 * pages (the built-in market etc.) are pages the user opened and pass freely.
 *
 * Volume: the curve logic itself lives in VolCurve (a plain
 * VOLUME_CHANGED_ACTION receiver — no accessibility involved). This service
 * only draws the exact-value overlay: the accessibility window type is the
 * one overlay surface this ROM hands to third parties (SYSTEM_ALERT_WINDOW
 * is never granted), and unlike toasts it does not throttle on rapid
 * presses. When the guard is not connected, VolCurve falls back to a toast.
 */
public class HomeGuardService extends AccessibilityService {

    public static final String TAG = PilotService.TAG;

    /** Reported by PilotService.whoami(). */
    public static volatile boolean connected = false;
    public static volatile String status = "not-connected";
    public static volatile String target = "?";

    /** The connected instance, for the static overlay entry point. */
    private static volatile HomeGuardService instance;

    private static final long DEBOUNCE_MS = 5000;

    private final Handler h = new Handler(Looper.getMainLooper());
    private long lastLaunch = 0;
    private List<String> stockPkgs = null;
    private List<String> stockHomeClasses = null;
    private long stockResolvedAt = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        instance = this;
        status = "connected";
        Log.i(TAG, "HomeGuard connected");
        // The XML declares canRequestFilterKeyEvents; the flag itself must
        // be armed at runtime or no key copies ever reach onKeyEvent.
        try {
            android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
            info.flags |= android.accessibilityservice.AccessibilityServiceInfo
                    .FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        } catch (Throwable ignored) {}
        h.postDelayed(periodic, 3000);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int t = event.getEventType();
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || t == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (event.getPackageName() != null)
                lastEventPkg = String.valueOf(event.getPackageName());
            // The foreground activity's class name distinguishes the stock
            // launcher's main HOME page from its secondary pages.
            if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && event.getClassName() != null)
                lastEventClass = String.valueOf(event.getClassName());
            guard("event");
        }
    }

    @Override
    public void onInterrupt() { status = "interrupted"; }

    /**
     * Volume-key copies: display-only. On this ROM VOLUME_CHANGED broadcasts
     * reach apps ~0.4s after the volume lands, which made the overlay feel
     * sluggish. The key *copy* arrives instantly, so the overlay paints the
     * predicted level right away; the broadcast correction follows and
     * repaints the final value. The copy is never trusted for correction —
     * return false always, VolCurve stays the single writer.
     */
    @Override
    protected boolean onKeyEvent(android.view.KeyEvent event) {
        try {
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            int dir;
            if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_VOLUME_UP) dir = 1;
            else if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) dir = -1;
            else return false;
            if (!Config.get(this, Config.Key.FINEVOL)) return false;
            if (!BetterAudio.isOn(Config.getStr(this, Config.Key.VOLCURVE, BetterAudio.PERCEPTUAL)))
                return false;
            if (VolCurve.shownValue() < 0) return false;   // nothing landed yet: let the broadcast lead
            int[] pl = VolCurve.predict(dir);
            showVolume(this, pl[0], pl[1],
                    BetterAudio.get(Config.getStr(this, Config.Key.VOLCURVE, BetterAudio.PERCEPTUAL)));
            // The key copy also kicks the 100 ms consume-poll: corrections
            // settle in ~0.1 s instead of at broadcast pace (~0.5 s here).
            VolCurve.kick(this, dir);
        } catch (Throwable ignored) {}
        return false;                               // never intercept
    }

    // ---------------- exact-volume overlay ----------------

    private android.widget.TextView volView;
    private boolean volViewAdded = false;

    private int dp(int d) {
        return Math.round(d * getResources().getDisplayMetrics().density);
    }

    /**
     * Display one volume value: the accessibility overlay when the guard is
     * connected, a plain toast otherwise (called by VolCurve; also used by
     * this service for boot-time display). Safe from any thread.
     */
    static void showVolume(Context ctx, int level, int internal, BetterAudio.Preset p) {
        HomeGuardService svc = instance;
        if (svc != null && connected) {
            svc.showVolInner(level, p);
        } else {
            try {
                String txt = ctx.getString(R.string.vol_line, level, p.steps);
                android.widget.Toast.makeText(ctx, txt, android.widget.Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        }
    }

    private void showVolInner(int level, BetterAudio.Preset p) {
        try {
            if (volView == null) {
                volView = new android.widget.TextView(this);
                volView.setTextColor(0xFFFFFFFF);
                volView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
                android.graphics.drawable.GradientDrawable bg =
                        new android.graphics.drawable.GradientDrawable();
                bg.setColor(0xE6202020);
                bg.setCornerRadius(dp(10));
                volView.setBackground(bg);
                volView.setPadding(dp(18), dp(10), dp(18), dp(10));
            }
            // One number only: the perceptual level. The internal index and
            // master stage are rendering details, never user-facing.
            volView.setText(getString(R.string.vol_line, level, p.steps));
            if (!volViewAdded) {
                android.view.WindowManager.LayoutParams lp =
                        new android.view.WindowManager.LayoutParams(
                                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                        | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                                android.graphics.PixelFormat.TRANSLUCENT);
                lp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
                lp.y = dp(40);
                ((android.view.WindowManager) getSystemService(WINDOW_SERVICE))
                        .addView(volView, lp);
                volViewAdded = true;
            }
            // Rapid presses only reset the hide timer; the window is reused.
            h.removeCallbacks(volHide);
            h.postDelayed(volHide, 1500);
        } catch (Throwable ignored) {}
    }

    private final Runnable volHide = new Runnable() {
        @Override public void run() {
            try {
                if (volViewAdded) {
                    ((android.view.WindowManager) getSystemService(WINDOW_SERVICE))
                            .removeView(volView);
                    volViewAdded = false;
                }
            } catch (Throwable ignored) {}
        }
    };

    @Override
    public boolean onUnbind(Intent intent) {
        connected = false;
        status = "unbound";
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        connected = false;
        instance = null;
        status = "destroyed";
        try { volHide.run(); } catch (Throwable ignored) {}
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---------------- home guard ----------------

    /** Periodic sweep: catches the stock launcher already on screen at connect time. */
    private final Runnable periodic = new Runnable() {
        @Override public void run() {
            try { guard("periodic"); } catch (Throwable ignored) {}
            h.postDelayed(this, 4000);
        }
    };

    private void guard(String trigger) {
        // User disabled the guard: observe only. The choice is theirs.
        if (!Config.get(this, Config.Key.HOMEGUARD)) {
            status = "disabled (observing " + topWindowPkg() + ")";
            return;
        }
        String pkg = topWindowPkg();
        if (pkg == null || pkg.isEmpty() || "com.dbos.pilot".equals(pkg)) return;

        // Cache the resolution for 60 seconds.
        long now = System.currentTimeMillis();
        if (stockPkgs == null || now - stockResolvedAt > 60000) {
            stockPkgs = HomeLauncher.resolveStockHomePkgs(this);
            stockHomeClasses = HomeLauncher.resolveStockHomeClasses(this);
            stockResolvedAt = now;
            ComponentName uh = HomeLauncher.resolveUserHome(this);
            target = uh == null ? "(none)" : uh.flattenToShortString();
        }

        if (stockPkgs.contains(pkg)) {
            // Correct only the stock launcher's main HOME activity; unknown
            // class names are still corrected (boot case) to stay safe.
            String cls = lastEventClass;
            if (cls != null && !cls.isEmpty() && !stockHomeClasses.contains(cls)) {
                status = "allow-secondary(" + cls + ")";
                return;
            }
            long t0 = System.currentTimeMillis();
            if (t0 - lastLaunch < DEBOUNCE_MS) { status = "debounce(" + pkg + ")"; return; }
            ComponentName home = HomeLauncher.resolveUserHome(this);
            if (home == null) { status = "no launcher installed"; return; }
            lastLaunch = t0;
            status = "redirect " + pkg + " -> " + home.flattenToShortString();
            Log.i(TAG, status + " [" + trigger + "]");
            Intent it = new Intent(Intent.ACTION_MAIN);
            it.addCategory(Intent.CATEGORY_HOME);
            it.setComponent(home);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(it);
            } catch (Throwable tr) {
                status = "redirect err: " + tr;
                Log.e(TAG, "redirect", tr);
            }
        } else {
            // User launcher or a normal app in front: observe only.
            status = "watch(" + pkg + ")";
        }
    }

    /** The active window's package; falls back to the last event's record. */
    private String topWindowPkg() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                for (AccessibilityWindowInfo w : ws) {
                    if (w.isActive() || w.isFocused()) {
                        android.view.accessibility.AccessibilityNodeInfo root = w.getRoot();
                        if (root != null) {
                            CharSequence p = root.getPackageName();
                            if (p != null) return String.valueOf(p);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return lastEventPkg;
    }

    private volatile String lastEventPkg = null;
    private volatile String lastEventClass = null;
}
