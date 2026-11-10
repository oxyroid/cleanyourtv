package com.dbos.scout;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 用户配置（v11）：决定权交给用户——所有自动化功能都可独立开关。
 * 存储：SharedPreferences("scout")，WebUI / 手机端 / 遥控器端三个入口改的都是这一份。
 *
 * v18：新增
 *  - VOLCURVE（字符串）：better-audio 预制（stock / fine100 / step30 / perceptual），
 *    切换预制会同步派生 FINEVOL/VOLOSD/NIGHTGAIN 三个底层开关；
 *  - LOGD（布尔，默认关）：系统 logd 出厂被 ROM 关闭，开启后 Scout 每轮自愈
 *    复核时顺手拉起（setprop ctl.start logd，经 LocalAdb 以 shell 执行）；
 *  - reset()：一键恢复 Scout 默认配置（含 better-audio=fine100）。
 */
public final class Config {

    public interface Key {
        String HOMEGUARD = "homeguard_on";   // 无障碍桌面守护（原厂桌面冒头自动切回）
        String HOMEFIX   = "homefix_on";     // 开机 am start 桌面纠偏（备用层）
        String ADBD      = "adbd_on";        // 开机自动拉起 adbd
        String ANNOUNCE  = "announce_on";   // UDP 主动广播 IP
        String FINEVOL   = "finevol_on";    // 音量链总开关（旁听+浮层+曲线校正）
        String VOLOSD    = "volosd_on";     // 隐藏原厂音量条（经本机 ADB 置空 VolumeController）
        String NIGHTGAIN = "nightgain_on";  // 夜间微音量（1-3 档输出混音 -18/-12/-6dB 衰减）
        String LOGD      = "logd_on";       // v18: 调试日志 logd 拉起（默认关，保持原厂行为）
        String VOLCURVE  = "volcurve";      // v18: better-audio 预制（字符串）
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("scout", Context.MODE_PRIVATE);
    }

    public static boolean get(Context c, String key) {
        // 默认全开；logd 例外——出厂关闭是原厂行为，不主动改变
        boolean def = !Key.LOGD.equals(key);
        return sp(c).getBoolean(key, def);
    }

    public static void set(Context c, String key, boolean val) {
        sp(c).edit().putBoolean(key, val).apply();
        android.util.Log.i(ScoutService.TAG, "config " + key + " -> " + val);
    }

    public static String getStr(Context c, String key, String def) {
        return sp(c).getString(key, def);
    }

    public static void setStr(Context c, String key, String val) {
        sp(c).edit().putString(key, val).apply();
        android.util.Log.i(ScoutService.TAG, "config " + key + " -> " + val);
    }

    /**
     * v18: 应用 better-audio 预制——写 VOLCURVE 并派生三个底层开关。
     * 预制是音量功能的唯一推荐入口；三个底层开关保留给高级微调（例如
     * 在 step30 下单独关夜间增益）。重新选择同一预制 = 恢复该预制的完整默认。
     */
    public static void applyPreset(Context c, String name) {
        BetterAudio.Preset p = BetterAudio.get(name);
        setStr(c, Key.VOLCURVE, p.name);
        boolean stock = BetterAudio.STOCK.equals(p.name);
        set(c, Key.FINEVOL, !stock);    // stock=原厂 ±5，其余全走单格底座
        set(c, Key.VOLOSD, p.overrideOsd);
        set(c, Key.NIGHTGAIN, p.nightDp);
    }

    /** v18: 一键恢复 Scout 默认配置（清空 prefs，全部回默认值） */
    public static void reset(Context c) {
        sp(c).edit().clear().apply();
        android.util.Log.i(ScoutService.TAG, "config reset to defaults");
    }

    /** 返回 JSON 字符串 {"homeguard_on":true,...,"volcurve":"fine100"} */
    public static String toJson(Context c) {
        return "{\"" + Key.HOMEGUARD + "\":" + get(c, Key.HOMEGUARD)
             + ",\"" + Key.HOMEFIX + "\":" + get(c, Key.HOMEFIX)
             + ",\"" + Key.ADBD + "\":" + get(c, Key.ADBD)
             + ",\"" + Key.ANNOUNCE + "\":" + get(c, Key.ANNOUNCE)
             + ",\"" + Key.FINEVOL + "\":" + get(c, Key.FINEVOL)
             + ",\"" + Key.VOLOSD + "\":" + get(c, Key.VOLOSD)
             + ",\"" + Key.NIGHTGAIN + "\":" + get(c, Key.NIGHTGAIN)
             + ",\"" + Key.LOGD + "\":" + get(c, Key.LOGD)
             + ",\"" + Key.VOLCURVE + "\":" + ScoutService.js(getStr(c, Key.VOLCURVE, BetterAudio.FINE100)) + "}";
    }
}
