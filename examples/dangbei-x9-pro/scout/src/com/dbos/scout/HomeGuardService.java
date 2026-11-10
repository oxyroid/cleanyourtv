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
     * v17: 音量键处理全面重构（对冲校正方案退役）。
     *
     * v14 的对冲校正源于当时的认知缺口：以为 ROM 的 ±5 拦不掉只能对冲。
     * v17 取证（services.jar 反汇编 + 拉起 logd 抓链）发现完整路径：
     *   interceptKeyBeforeQueueing → dispatchDirectAudioEvent
     *     → AudioService.handleVolumeKey → adjustSuggestedStreamVolume
     *       step = rescaleStep(10) → if persist.vendor.brand.simple != 1: step *= 5
     * ×5 是 ROM 自家的"非简化品牌"分支——persist.vendor.brand.simple=1 就是
     * 原厂预留的单格步进开关（persist 属性，重启保留），ScoutService 经
     * 本机 ADB 通道设置它。音量变化从此全是原生行为。
     *
     * 无障碍服务在这里只剩一个职责：旁听音量键、弹自绘浮层显示精确值。
     * 旁听发生在 ROM 处理之后（interceptKeyBeforeQueueing 在 a11y 过滤之前
     * 已把音量调好），所以读到的就是新值。
     *
     * v16: 原厂 OSD 的刻度是内部值÷5，且 ROM 落的 ±5 和我们的回拨各刷一次 OSD，
     * 观感"鬼畜"。对策：我方所有 setStreamVolume 一律不带 FLAG_SHOW_UI
     * （ROM 自己那次 OSD 弹出无法阻止，但不再被我方叠加重绘），
     * 精确值改由自己的浮层提示（如"音量 32/100"）。
     *
     * v16.1: Toast 被实测否决——每按一键 makeText 一个新 Toast，连按几下后
     * 系统的 toast 队列/节流直接丢弃后续（"前几下弹、后面不弹"）。
     * 改为 TYPE_ACCESSIBILITY_OVERLAY 自绘浮层：无障碍服务专有窗口类型，
     * 无需额外权限、无次数限制，连按时只更新文本 + 重置 1.5s 隐藏计时。
     */
    public static volatile int volAnchor = -1;

    /** v16.1: 音量浮层（替代 Toast，绕开系统节流） */
    private android.widget.TextView volView;
    private boolean volViewAdded = false;

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

    private void showVol(int v, int max) {
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
            volView.setText("音量 " + v + "/" + max);
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
            // 连按期间只重置隐藏计时，不重复创建窗口
            h.removeCallbacks(volHide);
            h.postDelayed(volHide, 1500);
        } catch (Throwable ignored) {}
    }

    @Override
    protected boolean onKeyEvent(android.view.KeyEvent event) {
        try {
            int k = event.getKeyCode();
            if (k != android.view.KeyEvent.KEYCODE_VOLUME_UP
                    && k != android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
                return false;                            // 非音量键一律放行
            if (!Config.get(this, Config.Key.FINEVOL))
                return false;                            // 开关关：不显示浮层，纯原生
            android.media.AudioManager am = (android.media.AudioManager)
                    getSystemService(AUDIO_SERVICE);
            int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            volAnchor = cur;
            showVol(cur, max);                           // ROM 已落新值（queueing 层先于 a11y）
            // v17: 夜间增益复核延迟到音量落地后（覆盖 0 静音/DP 挂载失败等场景）
            h.removeCallbacks(volGain);
            h.postDelayed(volGain, 350);
            return false;                                // 纯旁听：一律放行
        } catch (Throwable tr) {
            Log.e(TAG, "onKeyEvent", tr);
            return false;                                // 异常时放行，绝不吞键
        }
    }

    /** v17: 夜间增益延迟复核（音量落地后由 ScoutService 决定衰减档） */
    private final Runnable volGain = new Runnable() {
        @Override public void run() {
            try { ScoutService.applyNightGain(HomeGuardService.this); } catch (Throwable ignored) {}
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
        try { volHide.run(); } catch (Throwable ignored) {}   // v16.1: 撤掉音量浮层
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
