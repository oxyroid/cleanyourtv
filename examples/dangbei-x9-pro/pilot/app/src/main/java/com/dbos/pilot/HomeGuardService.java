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
 * Home guard (accessibility service).
 *
 * This ROM's launcher (and some of its sibling processes) keeps
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
 * Volume keys are deliberately not touched: the system's input policy
 * consumes them before any accessibility filter runs (see the onKeyEvent
 * note below and VolumeEngine for the fine-step switch).
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
     * Volume keys are NOT intercepted. The system's input policy
     * (WindowManagerService) consumes them before any accessibility filter
     * could, stepping AudioService directly; the fine-step switch is the ROM
     * prop persist.vendor.brand.simple (see VolumeEngine). This service only
     * watches windows — including the stock volume bar the system itself
     * shows.
     */
    @Override
    protected boolean onKeyEvent(android.view.KeyEvent event) {
        return false;
    }

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
