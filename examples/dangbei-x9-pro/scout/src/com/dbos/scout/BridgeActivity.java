package com.dbos.scout;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Intent 桥（v11）：Kido 等通用 launcher 的按钮发的是 Android 标准 intent，
 * 而当贝 ROM 没有实现任何标准设置入口（实测 android.settings.SETTINGS /
 * WIFI_SETTINGS 全部无法解析，弹 "Can't open that here"）。
 * 本 Activity 注册这些标准 intent，接到后转发到这台机器上真实存在的界面：
 *
 *   android.settings.SETTINGS / WIFI_SETTINGS / WIRELESS_SETTINGS
 *       -> com.dangbei.lerad.etna/.MainActivity（设置面板宿主，uid1000 悬浮窗）
 *   ACTION_VIEW + vnd.android.cursor.item/channel（MTK 电视框架信号源）
 *       -> com.xgimi.xhplayer/.InputActivity（HDMI/信号源选择器，已实测可拉起）
 *
 * Activity 是透明的、noHistory，用户只看到目标界面一闪而出。
 */
public class BridgeActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String target = null;
        try {
            Intent in = getIntent();
            String action = in != null ? in.getAction() : null;

            if (Intent.ACTION_VIEW.equals(action)) {
                // 信号源 / HDMI / TV 频道选择
                target = "com.xgimi.xhplayer/.InputActivity";
            } else {
                // android.settings.SETTINGS / WIFI_SETTINGS / WIRELESS_SETTINGS
                target = "com.dangbei.lerad.etna/.MainActivity";
            }
        } catch (Throwable ignored) {}

        try {
            if (target != null) {
                Intent it = new Intent();
                it.setComponent(android.content.ComponentName.unflattenFromString(target));
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
            }
        } catch (Throwable ignored) {
            // 目标打不开就算了，别把异常抛给用户
        }
        finish();
    }
}
