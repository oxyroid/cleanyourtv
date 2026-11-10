package com.dbos.pilot;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * User configuration. One store behind all three entry points (TV UI, WebUI,
 * HTTP API).
 *
 * Inactive by default where an action would change factory device behavior
 * merely by installing the app (logd ships stopped). OTA blocking is the one
 * exception: updates are what break customized sets.
 */
public final class Config {

    public interface Key {
        String HOMEGUARD  = "homeguard_on";   // accessibility home guard
        String HOMEFIX    = "homefix_on";     // post-boot am start fallback
        String ADBD       = "adbd_on";        // start adbd after boot
        String ANNOUNCE   = "announce_on";    // UDP presence broadcast
        String PERCEPTUAL = "perceptual_on";  // volume keys step 1 level instead of 10
        String LOGD       = "logd_on";        // keep the system log daemon running
        String OTABLOCK   = "ota_block_on";   // keep the system update engine stopped
        String HOMETARGET = "home_target";    // guard target launcher (string)
        // Legacy, migrated away in migrate().
        String OLD_CURVE  = "volcurve";
    }

    /** Titles get a " *" suffix when the value is away from its default. */
    public static boolean defaultOf(String key) {
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
    }

    public static int getInt(Context c, String key, int def) {
        return sp(c).getInt(key, def);
    }

    public static void setInt(Context c, String key, int val) {
        sp(c).edit().putInt(key, val).apply();
    }

    public static boolean changedFromDefault(Context c, String key) {
        return get(c, key) != defaultOf(key);
    }

    /**
     * Old versions stored a preset name ("stock"/"perceptual"/"fine100"/
     * "step30") plus derived switches. Fold that into the single switch once:
     * anything that was not stock was the perceptual chain in some form.
     */
    public static void migrate(Context c) {
        SharedPreferences sp = sp(c);
        if (!sp.contains(Key.OLD_CURVE) || sp.contains(Key.PERCEPTUAL)) return;
        boolean perceptual = !"stock".equals(sp.getString(Key.OLD_CURVE, "stock"));
        sp.edit().putBoolean(Key.PERCEPTUAL, perceptual).remove(Key.OLD_CURVE).apply();
        android.util.Log.i(PilotService.TAG, "config migrated: perceptual=" + perceptual);
    }

    /** Restore defaults (every switch; the volume itself lives in the system). */
    public static void reset(Context c) {
        sp(c).edit().clear().apply();
        android.util.Log.i(PilotService.TAG, "config reset to defaults");
    }

    public static String toJson(Context c) {
        return "{\"" + Key.HOMEGUARD + "\":" + get(c, Key.HOMEGUARD)
             + ",\"" + Key.HOMEFIX + "\":" + get(c, Key.HOMEFIX)
             + ",\"" + Key.ADBD + "\":" + get(c, Key.ADBD)
             + ",\"" + Key.ANNOUNCE + "\":" + get(c, Key.ANNOUNCE)
             + ",\"" + Key.PERCEPTUAL + "\":" + get(c, Key.PERCEPTUAL)
             + ",\"" + Key.LOGD + "\":" + get(c, Key.LOGD)
             + ",\"" + Key.OTABLOCK + "\":" + get(c, Key.OTABLOCK)
             + ",\"" + Key.HOMETARGET + "\":" + js(getStr(c, Key.HOMETARGET, "auto")) + "}";
    }

    /** JSON string literal, package-local copy (PilotService.js is equivalent). */
    static String js(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.append('"').toString();
    }

    private Config() {}
}
