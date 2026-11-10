package com.dbos.scout;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 用户配置（v11）：决定权交给用户——所有自动化功能都可独立开关。
 * 存储：SharedPreferences("scout")，WebUI / 手机端 / 遥控器端三个入口改的都是这一份。
 */
public final class Config {

    public interface Key {
        String HOMEGUARD = "homeguard_on";   // 无障碍桌面守护（原厂桌面冒头自动切回）
        String HOMEFIX   = "homefix_on";     // 开机 am start 桌面纠偏（备用层）
        String ADBD      = "adbd_on";        // 开机自动拉起 adbd
        String ANNOUNCE  = "announce_on";   // UDP 主动广播 IP
        String FINEVOL   = "finevol_on";    // v13: 音量键细化为 1 格/按（原厂遥控一次跳 20 格）
        String VOLOSD    = "volosd_on";     // v17: 隐藏原厂音量条（经本机 ADB 置空 VolumeController）
        String NIGHTGAIN = "nightgain_on";  // v17: 夜间微音量（1-3 档输出混音 -18/-12/-6dB 衰减）
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("scout", Context.MODE_PRIVATE);
    }

    public static boolean get(Context c, String key) {
        return sp(c).getBoolean(key, true);   // 默认全开
    }

    public static void set(Context c, String key, boolean val) {
        sp(c).edit().putBoolean(key, val).apply();
        android.util.Log.i(ScoutService.TAG, "config " + key + " -> " + val);
    }

    /** 返回 JSON 字符串 {"homeguard_on":true,...} */
    public static String toJson(Context c) {
        return "{\"" + Key.HOMEGUARD + "\":" + get(c, Key.HOMEGUARD)
             + ",\"" + Key.HOMEFIX + "\":" + get(c, Key.HOMEFIX)
             + ",\"" + Key.ADBD + "\":" + get(c, Key.ADBD)
             + ",\"" + Key.ANNOUNCE + "\":" + get(c, Key.ANNOUNCE)
             + ",\"" + Key.FINEVOL + "\":" + get(c, Key.FINEVOL)
             + ",\"" + Key.VOLOSD + "\":" + get(c, Key.VOLOSD)
             + ",\"" + Key.NIGHTGAIN + "\":" + get(c, Key.NIGHTGAIN) + "}";
    }
}
