package com.dbos.pilot;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * User configuration. Every automated feature has its own switch; all three
 * entry points (settings panels, WebUI, HTTP API) write the same store.
 *
 * Defaults are chosen so the app never changes factory device behavior just
 * by being installed: logd ships stopped on this ROM, so it stays off unless
 * the user turns it on. OTA blocking is the one exception — updates are what
 * break customized sets, so blocking is the safer default.
 */
public final class Config {

    public interface Key {
        String HOMEGUARD  = "homeguard_on";  // accessibility home guard
        String HOMEFIX    = "homefix_on";    // post-boot am start fallback
        String ADBD       = "adbd_on";       // start adbd after boot
        String ANNOUNCE   = "announce_on";   // UDP presence broadcast
        String FINEVOL    = "finevol_on";    // volume keys: overlay + curve correction
        String VOLOSD     = "volosd_on";     // suppress the stock volume bar
        String NIGHTGAIN = "nightgain_on";  // DynamicsProcessing attenuation at vol 1-3
        String LOGD       = "logd_on";       // keep the system log daemon running
        String OTABLOCK   = "ota_block_on";  // keep the system update engine stopped
        String VOLCURVE   = "volcurve";      // better-audio preset (string)
        String HOMETARGET = "home_target";   // guard target launcher (string)
    }

    /** Marker meaning: not a real key; titles get a " *" suffix when the user
     *  has moved this value away from its default. */
    public static boolean defaultOf(String key) {
        // Everything defaults to on except logd (factory-shipped stopped).
        return !Key.LOGD.equals(key);
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("pilot", Context.MODE_PRIVATE);
    }

    public static boolean get(Context c, String key) {
        return sp(c).getBoolean(key, defaultOf(key));
    }

    public static void set(Context c, String key, boolean val) {
        sp(c).edit().putBoolean(key, val).apply();
        android.util.Log.i(PilotService.TAG, "config " + key + " -> " + val);
    }

    public static String getStr(Context c, String key, String def) {
        return sp(c).getString(key, def);
    }

    public static void setStr(Context c, String key, String val) {
        sp(c).edit().putString(key, val).apply();
        android.util.Log.i(PilotService.TAG, "config " + key + " -> " + val);
    }

    /** Current value differs from the default → the UI marks the row. */
    public static boolean changedFromDefault(Context c, String key) {
        return get(c, key) != defaultOf(key);
    }

    /**
     * Apply a better-audio preset: store the name and derive the three
     * low-level switches. The preset is the single recommended entry point;
     * the low-level switches stay available for fine-tuning. Re-selecting a
     * preset restores that preset's full defaults.
     */
    public static void applyPreset(Context c, String name) {
        BetterAudio.Preset p = BetterAudio.get(name);
        setStr(c, Key.VOLCURVE, p.name);
        boolean stock = BetterAudio.STOCK.equals(p.name);
        set(c, Key.FINEVOL, !stock);
        set(c, Key.VOLOSD, p.overrideOsd);
        set(c, Key.NIGHTGAIN, p.nightDp);
    }

    /** Restore Pilot's default configuration (all switches and presets). */
    public static void reset(Context c) {
        sp(c).edit().clear().apply();
        android.util.Log.i(PilotService.TAG, "config reset to defaults");
    }

    /** JSON view: {"homeguard_on":true,...,"volcurve":"fine100","home_target":"auto"} */
    public static String toJson(Context c) {
        return "{\"" + Key.HOMEGUARD + "\":" + get(c, Key.HOMEGUARD)
             + ",\"" + Key.HOMEFIX + "\":" + get(c, Key.HOMEFIX)
             + ",\"" + Key.ADBD + "\":" + get(c, Key.ADBD)
             + ",\"" + Key.ANNOUNCE + "\":" + get(c, Key.ANNOUNCE)
             + ",\"" + Key.FINEVOL + "\":" + get(c, Key.FINEVOL)
             + ",\"" + Key.VOLOSD + "\":" + get(c, Key.VOLOSD)
             + ",\"" + Key.NIGHTGAIN + "\":" + get(c, Key.NIGHTGAIN)
             + ",\"" + Key.LOGD + "\":" + get(c, Key.LOGD)
             + ",\"" + Key.OTABLOCK + "\":" + get(c, Key.OTABLOCK)
             + ",\"" + Key.VOLCURVE + "\":" + PilotService.js(getStr(c, Key.VOLCURVE, BetterAudio.FINE100))
             + ",\"" + Key.HOMETARGET + "\":" + PilotService.js(getStr(c, Key.HOMETARGET, "auto")) + "}";
    }
}
