package com.dbos.pilot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

/**
 * Volume curve, without the accessibility layer.
 *
 * On this ROM the volume keys are consumed far below the accessibility
 * layer (interceptKeyBeforeQueueing -> dispatchDirectAudioEvent), so the
 * old key-observer only ever saw a copy of the press and raced the native
 * +/-1 step: at the bottom edge the snap could land before the native step,
 * and the next press then jumped two levels.
 *
 * This module does not observe keys at all. AudioService broadcasts
 * VOLUME_CHANGED_ACTION for every landed change — including the native
 * keypress steps (visible in this ROM's own dumpsys). Reacting to the
 * *landed* value removes the guesswork:
 *
 *   value == last + 1  -> one curve level up from the last landed value
 *   value == last - 1  -> one curve level down
 *   anything else      -> external change (console, other app): display only
 *
 * Our own correction echoes back as another broadcast and is ignored by
 * comparing against the value we just landed on. Rapid presses deliver in
 * order and each native step walks exactly one curve level, so a held key
 * keeps re-snapping — no separate long-press poll is needed anymore.
 *
 * Works with or without the accessibility service: the exact-value overlay
 * needs the accessibility window type (this ROM never grants
 * SYSTEM_ALERT_WINDOW to third parties), so without the guard a plain
 * toast is used instead.
 */
final class VolCurve {

    /** Last internal value this module landed on (post-native or our own). */
    private static volatile int shown = -1;

    /** The overlay's instant key-copy prediction reads the current landed value. */
    static int shownValue() { return shown; }

    private static final String ACTION     = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_VALUE  = "android.media.EXTRA_VOLUME_STREAM_VALUE";

    private static final Handler h = new Handler(Looper.getMainLooper());

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
        BetterAudio.Preset p = BetterAudio.get(
                Config.getStr(ctx, Config.Key.VOLCURVE, BetterAudio.FINE100));
        if (!Config.get(ctx, Config.Key.FINEVOL) || BetterAudio.STOCK.equals(p.name)) {
            shown = val;                        // passthrough mode: observe only
            return;
        }
        int last = shown;
        if (val == last) return;                // our own correction echoed back

        int level;
        if (last >= 0 && val == last + 1) {
            level = BetterAudio.levelFor(last, p) + 1;      // one level up
        } else if (last >= 0 && val == last - 1) {
            level = BetterAudio.levelFor(last, p) - 1;      // one level down
        } else {
            // External set (console, another app): never snap, just display.
            shown = val;
            HomeGuardService.showVolume(ctx, BetterAudio.levelFor(val, p), val, p);
            return;
        }
        if (level < 0) level = 0;
        if (level > p.steps) level = p.steps;
        int target = BetterAudio.internalFor(level, p);
        shown = target;                         // set before the write: our echo is ignored
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            // Never FLAG_SHOW_UI: the overlay is the only display.
            if (target != val) am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        } catch (Throwable ignored) {}
        HomeGuardService.showVolume(ctx, level, target, p);
        // Night-gain recheck once the new volume has settled.
        h.removeCallbacks(gain);
        h.postDelayed(gain, 350);
    }

    private static final Runnable gain = new Runnable() {
        @Override public void run() {
            // Service context keeps the DynamicsProcessing effect attached.
            Context svc = PilotService.instance;
            if (svc != null) PilotService.applyNightGain(svc);
        }
    };

    private VolCurve() {}
}
