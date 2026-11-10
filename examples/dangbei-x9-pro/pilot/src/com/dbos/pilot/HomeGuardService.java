package com.dbos.pilot;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
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
 * Volume: on this ROM the volume keys are handled below the accessibility
 * layer (interceptKeyBeforeQueueing → dispatchDirectAudioEvent →
 * AudioService) — no key filter can change them, and the native step size is
 * controlled by persist.vendor.brand.simple (PilotService sets it to 1). This
 * service therefore only observes and, on top of the native ±1 steps, applies
 * the better-audio level curve (BetterAudio): after the ROM lands the new
 * value, it snaps to the nearest curve level and shows it in a
 * TYPE_ACCESSIBILITY_OVERLAY window (no toast — the system throttles those
 * after a few rapid presses; the overlay has no such limit).
 *
 * Long-press: repeat key events do not reliably reach the accessibility
 * layer, so a held volume key would freeze the overlay at its first value.
 * During a keypress window the service polls the stream volume every 120 ms,
 * re-snaps along the inferred direction and refreshes the overlay; the poll
 * self-stops 900 ms after the last key event.
 */
public class HomeGuardService extends AccessibilityService {

    public static final String TAG = PilotService.TAG;

    /** Reported by PilotService.whoami(). */
    public static volatile boolean connected = false;
    public static volatile String status = "not-connected";
    public static volatile String target = "?";

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
        status = "connected";
        Log.i(TAG, "HomeGuard connected");
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

    // ---------------- volume: curve snap + overlay ----------------

    public static volatile int volAnchor = -1;

    private android.widget.TextView volView;
    private boolean volViewAdded = false;

    private int volShown = -1;            // last internal value the overlay/snap landed on
    private long lastVolKeyAt = 0;        // last volume-key event time
    private int volPollTicks = 0;        // poll runaway guard

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

    private int dp(int d) {
        return Math.round(d * getResources().getDisplayMetrics().density);
    }

    private void showVol(int level, int internal, BetterAudio.Preset p) {
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
            String txt;
            if (internal == level) {
                txt = getString(R.string.vol_line, level, p.steps);
            } else {
                txt = getString(R.string.vol_line_detail, level, p.steps, internal);
            }
            volView.setText(txt);
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

    /**
     * Curve snap: walk one level along the preset curve from the pre-press
     * internal value and land it. With fine100 (level == internal value) the
     * snap is a no-op — pure observation.
     */
    private void snap(int dir) {
        try {
            BetterAudio.Preset p = BetterAudio.get(
                    Config.getStr(this, Config.Key.VOLCURVE, BetterAudio.FINE100));
            android.media.AudioManager am = (android.media.AudioManager)
                    getSystemService(AUDIO_SERVICE);
            int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            int pre = Math.max(0, cur - dir);                 // undo the native ±1
            int level = BetterAudio.levelFor(pre, p) + dir;   // one curve level
            if (level < 0) level = 0;
            if (level > p.steps) level = p.steps;
            int target = BetterAudio.internalFor(level, p);
            if (target != cur) {
                // Never FLAG_SHOW_UI: the stock bar is suppressed via the
                // controller, the overlay is the only display.
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0);
            }
            volShown = target;
            volAnchor = target;
            showVol(level, target, p);
            h.removeCallbacks(volGain);
            h.postDelayed(volGain, 350);
        } catch (Throwable ignored) {}
    }

    /** Long-press poll: refresh the snap and overlay while the key is held. */
    private final Runnable volPoll = new Runnable() {
        @Override public void run() {
            try {
                if (volPollTicks-- <= 0) return;
                if (System.currentTimeMillis() - lastVolKeyAt > 900) return;
                android.media.AudioManager am = (android.media.AudioManager)
                        getSystemService(AUDIO_SERVICE);
                int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                if (volShown >= 0 && cur != volShown) {
                    snap(cur > volShown ? 1 : -1);
                }
            } catch (Throwable ignored) {}
            h.postDelayed(this, 120);
        }
    };

    @Override
    protected boolean onKeyEvent(android.view.KeyEvent event) {
        try {
            int k = event.getKeyCode();
            if (k != android.view.KeyEvent.KEYCODE_VOLUME_UP
                    && k != android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
                return false;                       // non-volume keys: always pass
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN)
                return false;                       // UP/MULTIPLE: the poll window closes it
            lastVolKeyAt = System.currentTimeMillis();
            BetterAudio.Preset p = BetterAudio.get(
                    Config.getStr(this, Config.Key.VOLCURVE, BetterAudio.FINE100));
            if (!Config.get(this, Config.Key.FINEVOL) || BetterAudio.STOCK.equals(p.name))
                return false;                       // stock mode / switch off: pure pass-through
            int dir = (k == android.view.KeyEvent.KEYCODE_VOLUME_UP) ? 1 : -1;
            snap(dir);
            volPollTicks = 400;
            h.removeCallbacks(volPoll);
            h.post(volPoll);
            return false;                            // observation only, never consume
        } catch (Throwable tr) {
            Log.e(TAG, "onKeyEvent", tr);
            return false;                           // on error, pass the key through
        }
    }

    /** Night-gain recheck once the new volume has landed. */
    private final Runnable volGain = new Runnable() {
        @Override public void run() {
            try { PilotService.applyNightGain(HomeGuardService.this); } catch (Throwable ignored) {}
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
