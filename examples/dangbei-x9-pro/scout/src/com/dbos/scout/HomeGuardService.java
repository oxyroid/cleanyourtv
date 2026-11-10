package com.dbos.scout;

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
 * 桌面守护（v10，方法论来自 cleanyourtv HomeKeyService）：
 *
 * 背景：当贝 ROM 会重置 preferred home、极米系守护进程会主动把原厂桌面拉回前台，
 * 一次性接管（set-home-activity / am start）都会被抢回。唯一可靠的路径是
 * 无障碍服务的系统级窗口事件订阅：原厂桌面一冒头就把自己选定的用户桌面拉回来。
 *
 * 双通道：
 *   1. TYPE_WINDOW_STATE_CHANGED 事件 —— 原厂桌面获得前台时立刻触发纠偏
 *   2. 4 秒周期巡检 getWindows() —— 兜住「服务连上时原厂桌面已经在屏上」的开机场景
 *      （此时不会有新的窗口事件，只能主动看）
 *
 * 目标桌面不写死：动态取「第一个用户安装的 launcher」（见 HomeLauncher）。
 */
public class HomeGuardService extends AccessibilityService {

    public static final String TAG = ScoutService.TAG;

    /** 给 ScoutService.whoami() 报告用 */
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
            // v12: 记录前台 Activity 类名（TYPE_WINDOW_STATE_CHANGED 的 className
            // 就是 Activity 全名），用于区分桌面包的「主页」与「次级功能页」
            if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && event.getClassName() != null)
                lastEventClass = String.valueOf(event.getClassName());
            guard("event");
        }
    }

    @Override
    public void onInterrupt() { status = "interrupted"; }

    /**
     * v14: 音量对冲校正（拦截方案被实证无效后的修正）。
     *
     * 实测（2026-11-10 真遥控）：当贝 ROM 在 interceptKeyBeforeQueueing 层直接 +5/按，
     * 发生在无障碍键过滤之前——onKeyEvent 返回 true 也拦不掉（净效果反而 +6）。
     * 改为对冲：按键时记下"目标值 = 基线 ±1"，立即回拨 + 延迟复核，
     * 把 ROM 底层加的 4 格吃掉，净步进 ±1。
     *
     * volAnchor 同时给 ScoutService /vol 同步用（跨组件共享基线）。
     */
    public static volatile int volAnchor = -1;

    private volatile int volPending = -1;

    @Override
    protected boolean onKeyEvent(android.view.KeyEvent event) {
        try {
            int k = event.getKeyCode();
            if (k != android.view.KeyEvent.KEYCODE_VOLUME_UP
                    && k != android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
                return false;                            // 非音量键一律放行
            if (!Config.get(this, Config.Key.FINEVOL))
                return false;                            // 开关关：不干预，原厂 ±5
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN)
                return true;                             // 吞掉 ACTION_UP
            android.media.AudioManager am = (android.media.AudioManager)
                    getSystemService(AUDIO_SERVICE);
            int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            // 基线失步（外部改过音量 / 首次）：以当前读数为准
            if (volAnchor < 0 || Math.abs(cur - volAnchor) > 6) volAnchor = cur;
            int want = k == android.view.KeyEvent.KEYCODE_VOLUME_UP
                    ? Math.min(volAnchor + 1, max)
                    : Math.max(volAnchor - 1, 0);
            volAnchor = want;
            volPending = want;
            if (cur != want)
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, want,
                        android.media.AudioManager.FLAG_SHOW_UI);
            // 双重复核：100ms 先压一次（跳变窗口最小化），300ms 再兜底一次
            h.removeCallbacks(volVerify);
            h.postAtTime(volVerify, android.os.SystemClock.uptimeMillis() + 100);
            h.postAtTime(volVerify, android.os.SystemClock.uptimeMillis() + 300);
            return true;
        } catch (Throwable tr) {
            Log.e(TAG, "onKeyEvent", tr);
            return false;                                // 异常时放行，绝不吞键
        }
    }

    /** 延迟复核：把竞态中多加的 ±5 压回 volAnchor */
    private final Runnable volVerify = new Runnable() {
        @Override public void run() {
            try {
                if (volPending < 0) return;
                android.media.AudioManager am = (android.media.AudioManager)
                        getSystemService(AUDIO_SERVICE);
                int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                if (cur == volPending) return;
                if (Math.abs(cur - volPending) > 6) {    // 外部大幅改动：尊重，不覆盖
                    volAnchor = cur;
                    volPending = -1;
                    return;
                }
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                        volPending, android.media.AudioManager.FLAG_SHOW_UI);
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
        status = "destroyed";
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** 周期巡检：兜住服务连上时原厂桌面已经在屏上的场景（开机最常见的态） */
    private final Runnable periodic = new Runnable() {
        @Override public void run() {
            try { guard("periodic"); } catch (Throwable ignored) {}
            h.postDelayed(this, 4000);
        }
    };

    private void guard(String trigger) {
        // v11: 用户关闭"桌面守护"时只观察不动作（决定权在用户）
        if (!Config.get(this, Config.Key.HOMEGUARD)) {
            status = "disabled (observing " + topWindowPkg() + ")";
            return;
        }
        String pkg = topWindowPkg();
        if (pkg == null || pkg.isEmpty() || "com.dbos.scout".equals(pkg)) return;

        // 缓存 60 秒的解析结果
        long now = System.currentTimeMillis();
        if (stockPkgs == null || now - stockResolvedAt > 60000) {
            stockPkgs = HomeLauncher.resolveStockHomePkgs(this);
            stockHomeClasses = HomeLauncher.resolveStockHomeClasses(this);
            stockResolvedAt = now;
            ComponentName uh = HomeLauncher.resolveUserHome(this);
            target = uh == null ? "(none)" : uh.flattenToShortString();
        }

        if (stockPkgs.contains(pkg)) {
            // v12: 只对桌面包的「主页 Activity」纠偏。次级功能页（应用市场
            // AppSecondaryActivity 等）是用户主动进入的，放行；
            // 类名未知时保守起见仍纠偏（兜住开机场景）。
            String cls = lastEventClass;
            if (cls != null && !cls.isEmpty() && !stockHomeClasses.contains(cls)) {
                status = "allow-secondary(" + cls + ")";
                return;
            }
            long t0 = System.currentTimeMillis();
            if (t0 - lastLaunch < DEBOUNCE_MS) { status = "debounce(" + pkg + ")"; return; }
            ComponentName home = HomeLauncher.resolveUserHome(this);
            if (home == null) { status = "no user launcher installed"; return; }
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
            // 前台是用户桌面或其他正常应用：只观察不动手
            status = "watch(" + pkg + ")";
        }
    }

    /** 取当前活跃窗口的包名（事件拿不到时的主动观察手段） */
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
        // 窗口树拿不到时，退回最近一次窗口事件记录的包名
        return lastEventPkg;
    }

    private volatile String lastEventPkg = null;
    private volatile String lastEventClass = null;
}
