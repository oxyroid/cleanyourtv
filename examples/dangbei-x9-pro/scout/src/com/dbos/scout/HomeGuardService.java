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
     * v17→v18: 音量键链路演进。
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
     * v18: 单格步进之上叠加 better-audio 曲线（见 BetterAudio.java）——
     * 旁听键 → 按"阶"吸附内部值（setStreamVolume 不带 SHOW_UI，瞬时落地）。
     * 与 v14 对冲的本质区别：底座已是原生 ±1，校正只在曲线预制（step30/
     * perceptual）下发生且是绝对值吸附，fine100 下零校正纯旁听。
     *
     * 无障碍服务在这里的职责：旁听音量键 → 曲线校正 → 弹自绘浮层显示精确值。
     * 旁听发生在 ROM 处理之后（interceptKeyBeforeQueueing 在 a11y 过滤之前
     * 已把音量调好），所以读到的就是新值。
     *
     * v16: 我方所有 setStreamVolume 一律不带 FLAG_SHOW_UI（原厂 OSD 由
     * volosd 开关经 setVolumeController(null) 整体掐掉，不存在叠加重绘）。
     *
     * v16.1: Toast 被实测否决——每按一键 makeText 一个新 Toast，连按几下后
     * 系统的 toast 队列/节流直接丢弃后续（"前几下弹、后面不弹"）。
     * 改为 TYPE_ACCESSIBILITY_OVERLAY 自绘浮层：无障碍服务专有窗口类型，
     * 无需额外权限、无次数限制，连按时只更新文本 + 重置 1.5s 隐藏计时。
     *
     * v18: 长按实测缺陷——长按音量键时后续 repeat DOWN 并非每次都能送达
     * onKeyEvent（浮层停在第一次按下的值，直到松手再按才刷新）。对策：
     * 按键期间挂 120ms 轮询（volPoll），读到内部值变化就按推断方向重算
     * 吸附 + 刷浮层；900ms 无新键事件自动停。同时 better-audio 曲线校正
     * （snap）也跑在同一条路上：短按由 onKeyEvent 驱动，长按由轮询兜底。
     */
    public static volatile int volAnchor = -1;

    /** v16.1: 音量浮层（替代 Toast，绕开系统节流） */
    private android.widget.TextView volView;
    private boolean volViewAdded = false;

    /** v18: 长按轮询状态 */
    private int volShown = -1;            // 浮层/校正最近一次落的内部值
    private long lastVolKeyAt = 0;        // 最近一次收到音量键事件的时间
    private int volPollTicks = 0;         // 轮询防呆计数

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
            // fine100 下阶==内部值，显示 "音量 32/100"；曲线预制补充内部值
            String txt = "音量 " + level + "/" + p.steps;
            if (internal != level) txt += " · " + internal + "/100";
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
            // 连按/长按期间只重置隐藏计时，不重复创建窗口
            h.removeCallbacks(volHide);
            h.postDelayed(volHide, 1500);
        } catch (Throwable ignored) {}
    }

    /** v18: 曲线吸附——从"按键前的内部值"出发，沿曲线走一阶并落值。
     *  fine100 下 internalFor(levelFor(v)) == v，天然退化为零校正纯旁听。 */
    private void snap(int dir) {
        try {
            BetterAudio.Preset p = BetterAudio.get(
                    Config.getStr(this, Config.Key.VOLCURVE, BetterAudio.FINE100));
            android.media.AudioManager am = (android.media.AudioManager)
                    getSystemService(AUDIO_SERVICE);
            int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            int pre = Math.max(0, cur - dir);                 // ROM 原生 ±1 已落，先还原
            int level = BetterAudio.levelFor(pre, p) + dir;   // 沿曲线走一阶
            if (level < 0) level = 0;
            if (level > p.steps) level = p.steps;
            int target = BetterAudio.internalFor(level, p);
            if (target != cur) {
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0);
            }
            volShown = target;
            volAnchor = target;
            showVol(level, target, p);
            // 夜间增益复核延迟到音量落地后
            h.removeCallbacks(volGain);
            h.postDelayed(volGain, 350);
        } catch (Throwable ignored) {}
    }

    /** v18: 长按轮询——兜住 repeat DOWN 不达 onKeyEvent 的场景。
     *  检测到内部值变化且按键窗口活跃时，按推断方向吸附并刷新浮层；
     *  900ms 无新键自动停，另设 400 tick 防呆上限。 */
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
                return false;                            // 非音量键一律放行
            int action = event.getAction();
            if (action != android.view.KeyEvent.ACTION_DOWN) {
                // 松手（UP）/ MULTIPLE 不校正；长按收尾由 volPoll 的 900ms 窗口兜住
                return false;
            }
            // 此后只剩 ACTION_DOWN（含长按 repeat）
            lastVolKeyAt = System.currentTimeMillis();
            BetterAudio.Preset p = BetterAudio.get(
                    Config.getStr(this, Config.Key.VOLCURVE, BetterAudio.FINE100));
            if (!Config.get(this, Config.Key.FINEVOL) || BetterAudio.STOCK.equals(p.name))
                return false;                            // 原厂模式/开关关：纯放行，不弹不改
            int dir = (k == android.view.KeyEvent.KEYCODE_VOLUME_UP) ? 1 : -1;
            snap(dir);                                   // 旁听后按曲线校正 + 弹浮层
            // v18: 启动长按轮询（每次按键刷新窗口与防呆计数）
            volPollTicks = 400;
            h.removeCallbacks(volPoll);
            h.post(volPoll);
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
