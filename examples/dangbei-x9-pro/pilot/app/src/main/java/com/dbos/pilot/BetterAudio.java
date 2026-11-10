package com.dbos.pilot;

/**
 * better-audio: perceptual volume, one switch.
 *
 * Perception background (why this exists at all): human loudness grows as
 * intensity^0.3 (Stevens' power law, the sone scale) — perceived loudness
 * doubles per +10 dB, and the just-noticeable difference is ~0.5-1 dB. A
 * "percent volume" is therefore NOT a percent of anything physical; the
 * perceptually even choices are steps of a constant number of dB.
 *
 * This device's firmware maps the stream index 0..100 through the vendor
 * "MM" curve (default_volume_tables.xml, read off /vendor/etc): index 1 is
 * -59 dB, but the steps are wildly uneven — 3 dB/step at the bottom down to
 * 0.125 dB/step at the top. Two consequences: pressing keys at the top is
 * inaudible, and even index 1 is still too loud at night. Android offers no
 * API or prop to swap in a perceptual mapping — the curve is a vendor XML
 * loaded at boot — so this layer builds the mapping itself:
 *
 *   level 1..100  ->  dB = FLOOR + (level-1) * STEP   (uniform, ≈ JND/step)
 *   level 0       ->  mute (stream index 0)
 *
 * Rendering one target dB is a two-stage multiply:
 *   stage 1  stream index  (vendor MM curve, drives the volume key events)
 *   stage 2  AudioFlinger master volume (a linear multiplier under every
 *            stream, reachable from the shell channel via
 *            `service call media.audio_flinger 7 f <0..1>` — transaction 7
 *            verified on this firmware; the value is stored and also
 *            forwarded to the HAL, see DeviceHalHidl::setMasterVolume).
 *
 * Bottom zone: target dB below MM(2) = -56 dB pins the index at 2 (never 1:
 * pressing vol- from 2 lands on 1, an event, not mute) and the master volume
 * carries everything down to FLOOR. Above -56 dB the index is the smallest k
 * with MM(k) >= target (so master, the correction remainder, stays <= 1) and
 * the master volume interpolates within the vendor's coarse 3 dB..0.125 dB
 * quantization.
 *
 * If the shell channel is down the master stage degrades to no extra
 * attenuation: the bottom zone then renders at index-2 loudness (-56 dB)
 * instead of the target — louder than intended, never broken audio.
 */
public final class BetterAudio {

    public static final String STOCK      = "stock";
    public static final String PERCEPTUAL = "perceptual";
    public static final String[] PRESETS = {STOCK, PERCEPTUAL};

    /** Bottom of the curve, dB below full scale. Stock floor is MM(1) = -59. */
    static final float FLOOR_DB = -80f;
    /** dB per level: 80 dB span over 99 intervals ≈ 0.808 dB — one JND. */
    static final float STEP_DB = (0f - FLOOR_DB) / 99f;

    public static final class Preset {
        public final String name;
        public final int steps;          // level count (UI shows L/steps)
        public final boolean overrideOsd; // stock bar suppressed, overlay shows the value
        Preset(String name, int steps, boolean overrideOsd) {
            this.name = name; this.steps = steps; this.overrideOsd = overrideOsd;
        }
    }

    private static final Preset P_STOCK      = new Preset(STOCK, 100, false);
    private static final Preset P_PERCEPTUAL = new Preset(PERCEPTUAL, 100, true);

    /** Unknown names fall back to perceptual (legacy fine100/step30 migrate). */
    public static Preset get(String name) {
        if (STOCK.equals(name)) return P_STOCK;
        return P_PERCEPTUAL;
    }

    public static boolean isOn(String name) { return !STOCK.equals(name); }

    public static boolean isValid(String name) {
        for (String p : PRESETS) if (p.equals(name)) return true;
        return false;
    }

    // ---------------- vendor MM curve (attenuation in centibels, index 1..100) ----
    // Read from /vendor/etc/default_volume_tables.xml, reference "MM", this unit.
    // Points 13..100 collapse to 0.25 dB steps at the very top; the table's job
    // here is only the inverse map (dB -> index) and external-index adoption.
    private static final int[] MM_CB = {
            // index:      1     2     3     4     5     6     7     8     9    10
            /*  1- 10 */ -5900, -5600, -5300, -5000, -4800, -4600, -4400, -4200, -4000, -3800,
            /* 11- 20 */ -3600, -3400, -3300, -3200, -3100, -3000, -2900, -2800, -2700, -2600,
            /* 21- 30 */ -2500, -2400, -2300, -2200, -2100, -2000, -1900, -1850, -1800, -1750,
            /* 31- 40 */ -1700, -1650, -1600, -1550, -1500, -1450, -1400, -1350, -1300, -1250,
            /* 41- 50 */ -1200, -1150, -1100, -1050, -1000,  -950,  -925,  -900,  -875,  -850,
            /* 51- 60 */  -825,  -800,  -775,  -750,  -725,  -700,  -675,  -650,  -625,  -600,
            /* 61- 70 */  -575,  -550,  -525,  -500,  -475,  -450,  -425,  -400,  -387,  -375,
            /* 71- 80 */  -362,  -350,  -337,  -325,  -312,  -300,  -287,  -275,  -262,  -250,
            /* 81- 90 */  -237,  -225,  -212,  -200,  -187,  -175,  -162,  -150,  -137,  -125,
            /* 91-100 */  -112,  -100,   -87,   -75,   -62,   -50,   -37,   -25,   -15,     0,
    };

    /** Target attenuation of a level, in dB (level 0 is mute). */
    public static float dbFor(int level) {
        if (level <= 0) return Float.NEGATIVE_INFINITY;
        if (level >= 100) return 0f;
        return FLOOR_DB + (level - 1) * STEP_DB;
    }

    /** dB in centibels, floored at the table's resolution. */
    private static int cb(float db) { return Math.round(db * 100f); }

    /**
     * Stream index rendering this level: 0 for mute, pinned 2 while the
     * target sits below MM(2) = -56 dB (never 1: pressing vol- from 2 lands
     * on 1, an event, not mute), then the smallest k whose MM point is at
     * or above the target — the master remainder stays <= 1.
     */
    public static int indexFor(int level) {
        if (level <= 0) return 0;
        if (level >= 100) return 100;
        int t = cb(dbFor(level));
        if (t < MM_CB[1]) return 2;              // t below MM(2): bottom zone
        for (int k = 3; k < 100; k++) {
            if (MM_CB[k - 1] >= t) return k;      // MM_CB is 0-based: index k -> MM_CB[k-1]
        }
        return 100;
    }

    /**
     * Master volume (linear 0..1) carrying the remainder below the index's
     * own attenuation. Always <= 1: the index is chosen at or above target.
     */
    public static float masterFor(int level) {
        if (level <= 0) return 1f;
        int index = indexFor(level);
        if (index == 0) return 1f;
        float indexDb = MM_CB[index - 1] / 100f;
        float db = dbFor(level);
        if (db >= indexDb) return 1f;
        return (float) Math.pow(10, (db - indexDb) / 20f);
    }

    /** External set adoption: vendor index -> nearest level (master goes 1.0). */
    public static int levelForIndex(int index) {
        if (index <= 0) return 0;
        if (index > 100) index = 100;
        float db = MM_CB[index - 1] / 100f;
        int level = Math.round((db - FLOOR_DB) / STEP_DB) + 1;
        if (level < 1) level = 1;
        if (level > 100) level = 100;
        return level;
    }

    private BetterAudio() {}
}
