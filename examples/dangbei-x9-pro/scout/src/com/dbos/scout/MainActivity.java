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
 * v11：遥控器可操作的开关面板 —— 决定权给用户。
 * 上下选择开关，OK 键切换；与 WebUI（/ui）、HTTP /config 改的是同一份配置。
 * 底部滚动状态区；Activity 回收不影响常驻服务。
 */
public class MainActivity extends Activity {

    private static final String[] KEYS = {
            Config.Key.HOMEGUARD, Config.Key.HOMEFIX, Config.Key.ADBD,
            Config.Key.ANNOUNCE, Config.Key.FINEVOL};
    private static final String[] NAMES = {"桌面守护", "开机桌面纠偏", "开机自动 ADB", "IP 广播", "音量细化"};
    private static final String[] DESCS = {
            "原厂桌面冒头时自动切回你的 launcher",
            "开机后确保进入你的 launcher",
            "开机自动拉起 ADB（远程救生通道）",
            "向局域网广播位置，根治 IP 漂移",
            "音量键改为 1 格/按（原厂一次 20 格，夜间太响）"};

    private final TextView[] rows = new TextView[KEYS.length];
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
        sub.setText("↑↓ 选择 · OK 切换开关 · 电脑浏览器打开 http://本机IP:8765/ui 也可管理\n");
        root.addView(sub);

        for (int i = 0; i < KEYS.length; i++) {
            rows[i] = new TextView(this);
            rows[i].setTypeface(Typeface.MONOSPACE);
            rows[i].setTextSize(16);
            rows[i].setPadding(8, 14, 8, 14);
            root.addView(rows[i]);
        }

        status = new TextView(this);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextSize(13);
        status.setTextColor(0xFF8B93A5);
        status.setPadding(8, 28, 8, 8);
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
            sel = (sel + (keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : KEYS.length - 1)) % KEYS.length;
            drawRows();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            boolean now = Config.get(this, KEYS[sel]);
            Config.set(this, KEYS[sel], !now);
            drawRows();
            // 关桌面守护时同步摘除无障碍授权（干净关闭）
            if (Config.Key.HOMEGUARD.equals(KEYS[sel]) && now) {
                ScoutService.disableHomeGuard(this);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void drawRows() {
        for (int i = 0; i < KEYS.length; i++) {
            boolean on = Config.get(this, KEYS[i]);
            String mark = on ? "[开]" : "[关]";
            String line = mark + " " + NAMES[i] + "  —  " + DESCS[i];
            rows[i].setText(line);
            rows[i].setTextColor(i == sel ? 0xFF4A9EFF : (on ? 0xFF3FB96B : 0xFF8B93A5));
            rows[i].setBackgroundColor(i == sel ? 0xFF1A2233 : Color.TRANSPARENT);
        }
    }

    private void refreshStatus() {
        final StringBuilder sb = new StringBuilder();
        sb.append("adbd: ").append(ScoutService.adbdState)
          .append("  |  守护: ").append(HomeGuardService.connected ? "在线" : "离线")
          .append('\n');
        sb.append("守护目标: ").append(HomeGuardService.target).append('\n');
        sb.append("服务: ").append(ScoutService.lastStatus).append('\n');
        sb.append("IP: ");
        for (String ip : ScoutService.localIPv4()) sb.append(ip).append(' ');
        runOnUiThread(new Runnable() {
            @Override public void run() { status.setText(sb); }
        });
    }
}
