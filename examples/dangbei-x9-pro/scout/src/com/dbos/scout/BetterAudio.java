package com.dbos.scout;

/**
 * v18: better-audio —— 音量预制曲线。
 *
 * 背景：系统音量内部 0..100（STREAM_MUSIC），原厂 UI 一次 ±5（persist.vendor.brand.simple
 * 出厂 10），设 1 后原生一次 ±1。但"内部值 1 格 = 音量跳一格"在听感上并不均匀：
 * 1 还是偏响、20..100 的后半段几乎听不出差别。
 *
 * 方案：在"原生 ±1"之上叠加一层「阶（level）↔ 内部值」映射。
 * 无障碍服务旁听到音量键后，按曲线把内部值吸附到最近的阶上（setStreamVolume
 * 不带 SHOW_UI，瞬时落地），浮层显示的是阶而不是内部值。
 *
 * 预制（Steps 最大阶数约定 ≥ 20：原厂 UI 也有 20 阶，低于 20 每按一下变化过大）：
 *   stock       —— 原厂行为：brand.simple=10（±5）、原厂 OSD、无浮层、无 DP
 *   fine100     —— 100 阶线性 = 内部值 1:1（等价 v17 的原生单格，零校正开销）
 *   step30      —— 30 阶线性（每按 ±3~4 内部值，快速调）
 *   perceptual  —— 40 阶感知曲线：f(l) = a·l² + b·l，f(1)=1、f(40)=100
 *                  低段每阶恰 1 内部值（夜间可用的最细粒度），高段步长渐增至 ~6
 *
 * 曲线数学上受两个硬约束：
 *   1) 内部值粒度是 1，任何曲线在 0..4 区间最多只有 5 个可区分点 →
 *      "1 和 0 之间再细分"只有 DynamicsProcessing 能做到（见 applyNightGain）；
 *   2) f 必须严格单调递增，否则相邻阶映射同一内部值，按键会"没反应"。
 *      perceptual 用 f(1)=1、f(N)=maxInternal 反解系数保证单调（步长下限 >1）。
 */
public final class BetterAudio {

    public static final String STOCK      = "stock";
    public static final String FINE100    = "fine100";
    public static final String STEP30     = "step30";
    public static final String PERCEPTUAL = "perceptual";

    /** 预制顺序（遥控器左右键循环切换用） */
    public static final String[] PRESETS = {STOCK, FINE100, STEP30, PERCEPTUAL};

    public static final class Preset {
        public final String name;
        public final int steps;         // 最大阶数（UI 显示 L/steps）
        public final boolean linear;    // true=线性；false=感知幂曲线
        public final boolean overrideOsd; // true=掐原厂音量条，Scout 浮层接管显示
        public final boolean nightDp;   // true=启用 DynamicsProcessing 夜间微音量
        public final int maxInternal;   // 最大内部音量（本机 STREAM_MUSIC max=100）

        Preset(String name, int steps, boolean linear,
                boolean overrideOsd, boolean nightDp, int maxInternal) {
            this.name = name; this.steps = steps; this.linear = linear;
            this.overrideOsd = overrideOsd; this.nightDp = nightDp; this.maxInternal = maxInternal;
        }
    }

    private static final Preset P_STOCK = new Preset(STOCK, 20, true, false, false, 100);
    private static final Preset P_FINE100 = new Preset(FINE100, 100, true, true, true, 100);
    private static final Preset P_STEP30 = new Preset(STEP30, 30, true, true, true, 100);
    private static final Preset P_PERCEPTUAL = new Preset(PERCEPTUAL, 40, false, true, true, 100);

    /** 未知名兜底 fine100（保持既有 v17 行为） */
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

    /** 感知曲线系数：f(1)=1, f(N)=100 → a=99/(N²-N), b=1-a（N=40 时 a≈0.03846） */
    private static float perfA(int steps, int max) {
        return (max - steps) / (float) (steps * steps - steps);
    }

    /** 阶 → 内部值（严格单调递增；level=0 恒为 0 = 静音） */
    public static int internalFor(int level, Preset p) {
        if (level <= 0) return 0;
        if (level >= p.steps) return p.maxInternal;
        if (p.linear) return Math.round(level * (float) p.maxInternal / p.steps);
        float a = perfA(p.steps, p.maxInternal);
        float b = 1f - a;
        return Math.round(a * level * level + b * level);
    }

    /** 内部值 → 阶：最大的 l 使 internalFor(l) <= internal（切换预制时用它换算
     *  current——阶重映射、内部值不动，实际音量零变化） */
    public static int levelFor(int internal, Preset p) {
        int l = p.steps;
        while (l > 0 && internalFor(l, p) > internal) l--;
        return l;
    }

    /** 预制的人类可读摘要（遥控器面板/WebUI 显示用） */
    public static String describe(Preset p) {
        String curve = p.linear ? "线性" : "感知";
        String osd = p.overrideOsd ? "覆盖系统UI" : "原厂UI";
        String dp = p.nightDp ? "夜间DP增益" : "无DP";
        return p.steps + "阶·" + curve + "·" + osd + "·" + dp;
    }

    private BetterAudio() {}
}
