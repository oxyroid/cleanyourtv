package com.dbos.scout;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * v18：leanback 遥控面板 —— 每个功能一个卡片行：slug 标识 + 中文名 + 描述。
 *
 * 行为约定（10 米 couch UI，纯 D-pad，无触屏假设）：
 *   ↑↓      上下移动焦点
 *   OK      开关行：切换；预制行：下一个预制；操作行：执行
 *   ←→      better-audio 行内切换预制（步进为 1，OK 也可循环）
 *
 * 功能行（slug → 能力）：
 *   home-guard        桌面守护（无障碍，原厂桌面冒头自动拉回）
 *   home-fix          开机桌面纠偏（am start 备用层）
 *   auto-adb          开机自动拉起 adbd（远程救生通道）
 *   ip-announce       UDP 主动广播 IP（根治 IP 漂移）
 *   log-diagnostics   logd 调试日志（原厂关闭，开启后 Scout 每轮自愈拉起）
 *   better-audio      音量预制曲线（stock / fine100 / step30 / perceptual）
 *   restore-stock     恢复原厂音量（= 切回 stock 预制，OSD/±5 全还原）
 *   reset-scout       重置 Scout 配置（全部开关/预制回默认 fine100）
 *
 * 与 WebUI（/ui）、HTTP /config 改的是同一份配置（SharedPreferences）。
 */
public class MainActivity extends Activity {

    private static final int TYPE_SWITCH = 0;
    private static final int TYPE_PRESET = 1;
    private static final int TYPE_ACTION = 2;

    private static final int IDX_RESTORE_STOCK = 6;
    private static final int IDX_RESET_SCOUT = 7;

    private static final String[] SLUGS = {
            "home-guard", "home-fix", "auto-adb", "ip-announce",
            "log-diagnostics", "better-audio", "restore-stock", "reset-scout"};
    private static final int[] TYPES = {
            TYPE_SWITCH, TYPE_SWITCH, TYPE_SWITCH, TYPE_SWITCH,
            TYPE_SWITCH, TYPE_PRESET, TYPE_ACTION, TYPE_ACTION};
    private static final String[] KEYS = {
            Config.Key.HOMEGUARD, Config.Key.HOMEFIX, Config.Key.ADBD,
            Config.Key.ANNOUNCE, Config.Key.LOGD, null, null, null};
    private static final String[] NAMES = {"桌面守护", "开机桌面纠偏", "开机自动 ADB", "IP 广播",
            "调试日志 logd", "音量增强", "恢复原厂音量", "重置 Scout 配置"};
    private static final String[] DESCS = {
            "原厂桌面冒头时自动切回你的 launcher",
            "开机后确保进入你的 launcher",
            "开机自动拉起 ADB（远程救生通道）",
            "向局域网广播位置，根治 IP 漂移",
            "开启后 Scout 自动拉起系统 logd（出厂关闭，logcat 全靠它）",
            "←→ 切换曲线：stock=原厂 / fine100=百阶精调 / step30=三十阶快调 / perceptual=四十阶感知（夜间低段最细）",
            "切回 stock 预制：±5 步进、无浮层无增益，全部还原（原厂音量条注册需重启设备一次）",
            "全部开关与音量预制恢复 Scout 默认值（better-audio=fine100）"};

    private final TextView[] rows = new TextView[SLUGS.length];
    private int sel = 0;
    private TextView status;
    private final Handler h = new Handler();
    private final Runnable tick = new Runnable() {
        @Override public void run() { refreshStatus(); h.postDelayed(this, 2000); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.TOP);
        root.setPadding(24, 24, 24, 24);

        TextView title = new TextView(this);
        title.setTypeface(Typeface.MONOSPACE);
        title.setTextSize(20);
        title.setTextColor(Color.GREEN);
        title.setText("DBOS Scout v" + ScoutService.VER);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setTypeface(Typeface.MONOSPACE);
        sub.setTextSize(12);
        sub.setTextColor(0xFF8B93A5);
        sub.setText("↑↓ 选择 · OK 执行/切换 · ←→ 换音量曲线 · 浏览器 http://本机IP:8765/ui 也可管理\n");
        root.addView(sub);

        for (int i = 0; i < SLUGS.length; i++) {
            rows[i] = new TextView(this);
            rows[i].setTypeface(Typeface.MONOSPACE);
            rows[i].setTextSize(16);
            rows[i].setPadding(8, 12, 8, 12);
            root.addView(rows[i]);
        }

        status = new TextView(this);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextSize(13);
        status.setTextColor(0xFF8B93A5);
        status.setPadding(8, 24, 8, 8);
        ScrollView sv = new ScrollView(this);
        sv.addView(status, new ScrollView.LayoutParams(-1, -2));
        root.addView(sv, new LinearLayout.LayoutParams(-1, -1));

        setContentView(root);
        drawRows();

        Intent svc = new Intent(this, ScoutService.class);
        startService(svc);
    }

    @Override protected void onResume() { super.onResume(); h.post(tick); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            sel = (sel + (keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : SLUGS.length - 1)) % SLUGS.length;
            drawRows();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (TYPES[sel] == TYPE_PRESET) {
                cyclePreset(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            activate();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** OK 键：按行类型分派 */
    private void activate() {
        int t = TYPES[sel];
        if (t == TYPE_SWITCH) {
            boolean now = Config.get(this, KEYS[sel]);
            Config.set(this, KEYS[sel], !now);
            // 关桌面守护时同步摘除无障碍授权（干净关闭）
            if (Config.Key.HOMEGUARD.equals(KEYS[sel]) && now) {
                ScoutService.disableHomeGuard(this);
            }
        } else if (t == TYPE_PRESET) {
            cyclePreset(1);
        } else if (sel == IDX_RESTORE_STOCK) {
            Config.applyPreset(this, BetterAudio.STOCK);
        } else if (sel == IDX_RESET_SCOUT) {
            Config.reset(this);
        }
        drawRows();
        refreshStatus();
    }

    /** ←→ / OK 切换 better-audio 预制（阶的重映射不动内部值，音量零变化） */
    private void cyclePreset(int dir) {
        String cur = Config.getStr(this, Config.Key.VOLCURVE, BetterAudio.FINE100);
        int idx = 0;
        for (int i = 0; i < BetterAudio.PRESETS.length; i++)
            if (BetterAudio.PRESETS[i].equals(cur)) idx = i;
        idx = (idx + dir + BetterAudio.PRESETS.length) % BetterAudio.PRESETS.length;
        Config.applyPreset(this, BetterAudio.PRESETS[idx]);
    }

    private void drawRows() {
        for (int i = 0; i < SLUGS.length; i++) {
            StringBuilder line = new StringBuilder();
            int t = TYPES[i];
            if (t == TYPE_SWITCH) {
                boolean on = Config.get(this, KEYS[i]);
                line.append(on ? "[开]" : "[关]").append(' ');
            } else if (t == TYPE_PRESET) {
                BetterAudio.Preset p = BetterAudio.get(
                        Config.getStr(this, Config.Key.VOLCURVE, BetterAudio.FINE100));
                line.append('<').append(p.name).append("> ");
            } else {
                line.append("[▶] ");
            }
            line.append(SLUGS[i]).append(" · ").append(NAMES[i]);
            if (t == TYPE_PRESET) {
                line.append("\n      ").append(BetterAudio.describe(BetterAudio.get(
                        Config.getStr(this, Config.Key.VOLCURVE, BetterAudio.FINE100))));
            }
            line.append("\n      ").append(DESCS[i]);
            rows[i].setText(line.toString());
            rows[i].setTextColor(i == sel ? 0xFF4A9EFF : 0xFFC8D2E0);
            rows[i].setBackgroundColor(i == sel ? 0xFF1A2233 : Color.TRANSPARENT);
        }
    }

    private void refreshStatus() {
        final StringBuilder sb = new StringBuilder();
        sb.append("adbd: ").append(ScoutService.adbdState)
          .append("  |  守护: ").append(HomeGuardService.connected ? "在线" : "离线")
          .append("  |  logd: ").append(ScoutService.shStatic("getprop init.svc.logd").trim())
          .append('\n');
        sb.append("音量链: ").append(ScoutService.volNativeState).append('\n');
        sb.append("守护目标: ").append(HomeGuardService.target).append('\n');
        sb.append("服务: ").append(ScoutService.lastStatus).append('\n');
        sb.append("IP: ");
        for (String ip : ScoutService.localIPv4()) sb.append(ip).append(' ');
        runOnUiThread(new Runnable() {
            @Override public void run() { status.setText(sb); }
        });
    }
}
