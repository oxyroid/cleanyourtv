package com.dbos.pilot;

/**
 * better-audio: volume preset curves.
 *
 * The stream volume is an internal 0..100 integer; with the native single-step
 * base (persist.vendor.brand.simple=1) the remote steps ±1 internal value per
 * press — but "one internal value" is not perceptually even: value 1 is still
 * loud at night, and steps above ~40 are barely distinguishable. This layer
 * maps a user-facing "level" onto internal values:
 *
 *   stock       factory behavior: ±5 steps, stock OSD, no overlay, no DP
 *   fine100     100 levels, 1:1 with internal values (zero correction)
 *   step30      30 linear levels (±3–4 internal per press)
 *   perceptual  40 levels on a quadratic curve pinned at f(1)=1 and f(40)=100:
 *               exactly one internal value per level at the quiet end (the
 *               finest granularity the internal scale allows), growing to ~6
 *               at the top
 *
 * Constraints honored by the math:
 *   - f is strictly increasing, so no two levels map to the same internal
 *     value (a keypress never becomes a no-op);
 *   - every preset keeps >= 20 levels (the stock bar itself shows 20);
 *   - the internal value has granularity 1, so no curve can add steps
 *     between 0 and 1 — that gap is what DynamicsProcessing night-gain
 *     covers (see PilotService.applyNightGain).
 *
 * Switching presets re-maps only the level (levelFor on the unchanged
 * internal value); the loudness the user hears does not move.
 */
public final class BetterAudio {

    public static final String STOCK      = "stock";
    public static final String FINE100    = "fine100";
    public static final String STEP30     = "step30";
    public static final String PERCEPTUAL = "perceptual";

    public static final String[] PRESETS = {STOCK, FINE100, STEP30, PERCEPTUAL};

    public static final class Preset {
        public final String name;
        public final int steps;          // level count (UI shows L/steps)
        public final boolean linear;      // true = linear; false = perceptual
        public final boolean overrideOsd; // true = stock bar suppressed, overlay shows the value
        public final boolean nightDp;    // true = DynamicsProcessing night attenuation
        public final int maxInternal;    // stream max volume on this device

        Preset(String name, int steps, boolean linear,
                boolean overrideOsd, boolean nightDp, int maxInternal) {
            this.name = name; this.steps = steps; this.linear = linear;
            this.overrideOsd = overrideOsd; this.nightDp = nightDp; this.maxInternal = maxInternal;
        }
    }

    private static final Preset P_STOCK      = new Preset(STOCK, 20, true, false, false, 100);
    private static final Preset P_FINE100   = new Preset(FINE100, 100, true, true, true, 100);
    private static final Preset P_STEP30    = new Preset(STEP30, 30, true, true, true, 100);
    private static final Preset P_PERCEPTUAL = new Preset(PERCEPTUAL, 40, false, true, true, 100);

    /** Unknown names fall back to fine100. */
    public static Preset get(String name) {
        if (STOCK.equals(name)) return P_STOCK;
        if (STEP30.equals(name)) return P_STEP30;
        if (PERCEPTUAL.equals(name)) return P_PERCEPTUAL;
        return P_FINE100;
    }

    public static boolean isValid(String name) {
        for (String p : PRESETS) if (p.equals(name)) return true;
        return false;
    }

    /** Quadratic coefficients: f(1)=1, f(N)=max → a=(max-N)/(N²-N), b=1-a. */
    private static float perfA(int steps, int max) {
        return (max - steps) / (float) (steps * steps - steps);
    }

    /** Level → internal value. Strictly increasing; level 0 is always 0 (mute). */
    public static int internalFor(int level, Preset p) {
        if (level <= 0) return 0;
        if (level >= p.steps) return p.maxInternal;
        if (p.linear) return Math.round(level * (float) p.maxInternal / p.steps);
        float a = perfA(p.steps, p.maxInternal);
        float b = 1f - a;
        return Math.round(a * level * level + b * level);
    }

    /** Internal value → level: the greatest l with internalFor(l) <= internal. */
    public static int levelFor(int internal, Preset p) {
        int l = p.steps;
        while (l > 0 && internalFor(l, p) > internal) l--;
        return l;
    }

    private BetterAudio() {}
}
