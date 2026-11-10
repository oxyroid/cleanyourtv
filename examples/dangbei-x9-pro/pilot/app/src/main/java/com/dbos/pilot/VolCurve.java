package com.dbos.pilot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Volume curve, without the accessibility layer.
 *
 * On this ROM the volume keys are consumed far below the accessibility
 * layer, so key observation races the native +/-1 step. This module does not
 * observe keys at all: AudioService broadcasts VOLUME_CHANGED_ACTION for
 * every landed change — including the native keypress steps — and reacting
 * to the *landed* value removes the guesswork:
 *
 *   value == last + 1  -> one level up from the last landed value
 *   value == last - 1  -> one level down
 *   anything else      -> external change (console, other app): adopt its
 *                          level for display, release the master stage
 *
 * Each level renders through two stages (see BetterAudio): the stream
 * index (written here, app-level API) and the AudioFlinger master volume
 * (written through the shell channel:
 *   service call media.audio_flinger 7 f <0..1>
 * — transaction 7, verified against the Android-14 IAudioFlinger enum and
 * live-tested on this firmware: the value stores and is forwarded to the
 * HAL). The master write is idempotent and last-write-wins, so press bursts
 * converge even if a write lands late.
 *
 * Degradation: with the shell channel down the master stage cannot be
 * written; the bottom zone then renders at index-2 loudness (-56 dB)
 * instead of down to -80 dB — louder than intended, never broken audio.
 * Restoring the channel re-pins the master stage on the next press or
 * maintenance round.
 */
final class VolCurve {

    /** Last internal index this module landed on (post-native or our own). */
    private static volatile int shown = -1;

    /** Current perceptual level (0..100); -1 until the first adoption. */
    private static volatile int level = -1;

    /** The overlay's instant key-copy prediction reads the current state. */
    static int shownValue() { return shown; }

    /** Predict (level, index) for one keypress of dir without writing. */
    static int[] predict(int dir) {
        int l = level < 0 ? 0 : level + dir;
        if (l < 0) l = 0;
        if (l > 100) l = 100;
        return new int[]{l, BetterAudio.indexFor(l)};
    }

    private static final String ACTION       = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_VALUE   = "android.media.EXTRA_VOLUME_STREAM_VALUE";

    private static final Handler h = new Handler(Looper.getMainLooper());

    /**
     * On this ROM the VOLUME_CHANGED broadcast reaches apps ~0.5 s after the
     * volume landed, so press trains outrun broadcast-driven corrections and
     * the bottom zone plays a half-second native blip before settling. The
     * accessibility key *copy* arrives instantly, so it kicks a 100 ms poll
     * that consumes the native step as soon as it lands. The late broadcast
     * for that step is then ignored (pendingNative), keeping VolCurve the
     * single writer with the broadcast as the no-guard fallback path.
     */
    private static volatile int pendingNative = -1;
    private static volatile int pollDir = 0;
    private static long pollUntil = 0;

    /** Kicked by the key copy: consume the native step fast, not at broadcast pace. */
    static void kick(Context ctx, int dir) {
        if (shown < 0) return;                    // nothing landed yet: broadcast leads
        android.util.Log.i(PilotService.TAG, "kick dir=" + dir + " shown=" + shown);
        pollDir = dir;
        pollUntil = System.currentTimeMillis() + 3000;
        h.removeCallbacks(poll);
        h.post(poll);
    }

    private static final Runnable poll = new Runnable() {
        @Override public void run() {
            Context ctx = PilotService.instance;
            if (ctx == null || System.currentTimeMillis() > pollUntil) return;
            int cur = current(ctx);
            if (shown >= 0 && cur == shown + pollDir && cur >= 0) {
                android.util.Log.i(PilotService.TAG, "poll consumed cur=" + cur);
                pendingNative = cur;              // broadcast for this step → ignored
                onNativeLanded(ctx, pollDir);
            }
            h.postDelayed(this, 100);
        }
    };

    /** Master-stage writes go through the shell: keep the main thread free. */
    private static final ExecutorService masterPool = Executors.newSingleThreadExecutor();

    /** Last master value handed to the shell channel (1.0 = untouched). */
    private static volatile float masterCur = 1f;

    /** Probed once: whether the shell channel can reach setMasterVolume. */
    private static final AtomicBoolean masterOk = new AtomicBoolean(false);

    /** Registered by PilotService at startup (runtime receiver, any API level). */
    static void install(Context ctx) {
        IntentFilter f = new IntentFilter(ACTION);
        ctx.registerReceiver(receiver, f, null, h);
    }

    private static final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent it) {
            try {
                if (it == null || it.getExtras() == null) return;
                int stream = it.getIntExtra(EXTRA_STREAM, -1);
                int val = it.getIntExtra(EXTRA_VALUE, -1);
                if (stream != AudioManager.STREAM_MUSIC || val < 0) return;
                onChanged(ctx, val);
            } catch (Throwable ignored) {}
        }
    };

    private static void onChanged(Context ctx, int val) {
        if (!Config.get(ctx, Config.Key.FINEVOL)
                || !BetterAudio.isOn(Config.getStr(ctx, Config.Key.VOLCURVE, BetterAudio.PERCEPTUAL))) {
            shown = val;                        // passthrough mode: observe only
            return;
        }
        int last = shown;
        if (val == last) return;                // our own correction echoed back
        if (val == pendingNative) {              // already consumed by the poll
            pendingNative = -1;
            return;
        }

        if (last >= 0 && (val == last + 1 || val == last - 1)) {
            // Native keypress step: one level in the same direction.
            onNativeLanded(ctx, val > last ? 1 : -1);
            return;
        }
        // External set (console, another app): adopt its level, release the
        // master stage so what the external writer asked for is what plays.
        shown = val;
        level = BetterAudio.levelForIndex(val);
        writeMaster(1f, false);
        HomeGuardService.showVolume(ctx, level, val,
                BetterAudio.get(Config.getStr(ctx, Config.Key.VOLCURVE, BetterAudio.PERCEPTUAL)));
    }

    /** A native +/-1 step landed (via the fast poll or the late broadcast). */
    private static void onNativeLanded(Context ctx, int dir) {
        int l = level >= 0 ? level + dir : BetterAudio.levelForIndex(shown) + dir;
        apply(ctx, l);
    }

    /**
     * Render one level: set the index (app-level API, instant) and hand the
     * master stage to the shell channel. Also the re-pin path after an
     * audioserver restart or a boot: adopt the level if unknown first.
     */
    static void apply(Context ctx, int l) {
        if (l < 0) l = 0;
        if (l > 100) l = 100;
        level = l;
        int target = BetterAudio.indexFor(l);
        shown = target;                         // our own echo is ignored
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            // Never FLAG_SHOW_UI: the overlay is the only display.
            if (target != current(ctx)) am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        } catch (Throwable ignored) {}
        writeMaster(BetterAudio.masterFor(l), false);
        HomeGuardService.showVolume(ctx, l, target,
                BetterAudio.get(Config.getStr(ctx, Config.Key.VOLCURVE, BetterAudio.PERCEPTUAL)));
    }

    /** Boot/repair path: adopt the persisted index as a level, re-pin master. */
    static void repin(Context ctx, boolean force) {
        try {
            if (!Config.get(ctx, Config.Key.FINEVOL)
                    || !BetterAudio.isOn(Config.getStr(ctx, Config.Key.VOLCURVE, BetterAudio.PERCEPTUAL))) {
                writeMaster(1f, force);          // feature off: master untouched
                return;
            }
            int cur = current(ctx);
            if (shown != cur) {                  // state we did not write
                shown = cur;
                level = BetterAudio.levelForIndex(cur);
                // The persisted index may not be the one our level renders
                // through (e.g. index 1, never ours) — snap it, no overlay.
                int target = BetterAudio.indexFor(level);
                if (target != cur) {
                    AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
                    shown = target;
                }
            }
            if (level >= 0) writeMaster(BetterAudio.masterFor(level), force);
        } catch (Throwable ignored) {}
    }

    private static int current(Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            return am.getStreamVolume(AudioManager.STREAM_MUSIC);
        } catch (Throwable t) { return -1; }
    }

    /**
     * Master-volume write through the shell channel. Idempotent; skipped
     * when unchanged and the channel is proven good (unless forced — the
     * maintenance round force-writes, which heals an audioserver restart
     * that silently reset the master volume to 1.0). Probed on the first
     * write; a dead channel only degrades the bottom zone (see class doc).
     */
    private static void writeMaster(float v, boolean force) {
        if (v < 0f) v = 0f;
        if (v > 1f) v = 1f;
        final float target = v;
        if (!force && Math.abs(target - masterCur) < 0.0005f && masterOk.get()) return;
        masterCur = target;
        masterPool.execute(new Runnable() {
            @Override public void run() {
                try {
                    // The binder payload is the %.5f string: compare against
                    // the float that string parses back to, not the original.
                    String num = String.format(Locale.US, "%.5f", target);
                    String cmd = "service call media.audio_flinger 7 f " + num;
                    String out = LocalAdb.shell(cmd, 5000);
                    boolean wrote = out != null && out.startsWith("Result: Parcel(")
                            && out.contains("00000000");
                    if (!masterOk.get()) {       // first write: verify by reading back
                        String back = LocalAdb.shell("service call media.audio_flinger 9", 5000);
                        int bits = Float.floatToIntBits(Float.parseFloat(num));
                        String h0 = String.format(Locale.US, "%08x", bits);
                        String h1 = String.format(Locale.US, "%08x", bits - 1);
                        String h2 = String.format(Locale.US, "%08x", bits + 1);
                        boolean ok = back != null
                                && (back.contains(h0) || back.contains(h1) || back.contains(h2));
                        masterOk.set(ok);
                        if (!ok) PilotService.volNativeState =
                                "master-vol unavailable (probe=" + h0 + " back=" + back + ")";
                    } else if (!wrote) {
                        PilotService.volNativeState = "master-vol write: " + out;
                    }
                } catch (Throwable t) {
                    masterOk.set(false);
                }
            }
        });
    }

    static void resetProbe() { masterOk.set(false); }

    private VolCurve() {}
}
