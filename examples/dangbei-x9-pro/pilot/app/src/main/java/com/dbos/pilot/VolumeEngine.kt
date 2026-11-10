package com.dbos.pilot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Fine volume = the system's own stepping + an overlay that REPLACES the
 * stock volume bar.
 *
 * Keys are never intercepted or consumed: the input policy steps AudioService
 * before any accessibility filter runs (proven by dumpsys — see the git log of
 * the previous rewrite), so stepping stays 100% native and there is no
 * double-write. What the app owns is presentation and one prop:
 *
 *   persist.vendor.brand.simple — 1 = ±1 per press (0..100), 10 = factory ±5.
 *   service call audio 92       — setVolumeController(null): kills the stock
 *     bar. Irreversible until reboot (SystemUI only registers the controller
 *     at boot) — which is exactly the "replace" semantics the user picked.
 *     With the switch off we stop calling it; after one reboot the stock bar
 *     is back for good.
 *
 * The overlay is painted from two harmless channels:
 *   - accessibility key COPIES (~15ms) arm a follower: each copy pushes a
 *     400ms deadline, a 40ms loop samples the stream until the deadline
 *     lapses. UP never cancels it — a cancel there is what made short
 *     presses wait for the slow channel below;
 *   - the VOLUME_CHANGED receiver is the backstop for external changes
 *     (console API, voice assistants) — ~560ms late on this ROM, far too
 *     slow to drive the UI, hence the follower above.
 */
object VolumeEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var app: Context
    private var started = false
    private var poll: Job? = null

    /** The follower keeps sampling the stream until this uptime deadline. */
    @Volatile private var followUntil = 0L

    @JvmStatic
    fun start(ctx: Context) {
        started = true
        app = ctx.applicationContext
        app.registerReceiver(receiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
        // adbd may not be up at boot; one delayed sync. The maintenance
        // loop re-asserts it every round anyway.
        scope.launch {
            delay(30_000)
            if (started) sync()
        }
    }

    @get:JvmStatic
    val enabled: Boolean
        get() = started && Config.get(app, Config.Key.PERCEPTUAL)

    @JvmStatic
    fun isEnabled(): Boolean = enabled

    @JvmStatic
    fun setEnabled(on: Boolean) {
        if (!started) return
        Config.set(app, Config.Key.PERCEPTUAL, on)
        sync()
    }

    /**
     * Point the prop at the switch; while on, keep the stock bar dead and
     * tame the hold-to-sweep speed.
     *
     * The ROM's AudioService.adjustStreamVolume reads
     * sys.repeated_key_vol_steps as the per-repeat step when a volume key
     * repeats (found in the dex: flags&0x4000 → getInt(prop, default);
     * with the fine-step branch active the default path steps ~10 indices
     * per repeat × ~7 repeats/s ≈ 75/s — a full sweep in ~1.3 s, impossible
     * to stop precisely). 2 ≈ 15/s, a full sweep in ~6 s. Not a persist
     * prop — blanked at boot, re-asserted by every sync().
     */
    @JvmStatic
    fun sync() {
        val want = if (enabled) "1" else "10"
        if (LocalAdb.shell("getprop persist.vendor.brand.simple", 4000)?.trim() != want) {
            LocalAdb.shell("setprop persist.vendor.brand.simple $want", 4000)
        }
        val rep = if (enabled) "2" else ""
        if (LocalAdb.shell("getprop sys.repeated_key_vol_steps", 4000)?.trim() != rep) {
            LocalAdb.shell("setprop sys.repeated_key_vol_steps \"$rep\"", 4000)
        }
        if (enabled) suppressStockBar()
    }

    private fun suppressStockBar() {
        // setVolumeController(null) — transaction 92 on this firmware.
        LocalAdb.shell("service call audio 92 i32 0", 4000)
    }

    /**
     * Accessibility key copies, for display only. Never consumed — the
     * system steps the volume regardless (input policy is first in line).
     */
    @JvmStatic
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!enabled) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {} // else ignore
            else -> return false
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> { // includes hold repeats (repeatCount > 0)
                show(level()) // instant first paint; the step lands right about now
                followUntil = SystemClock.uptimeMillis() + 400
                if (poll?.isActive != true) {
                    poll = scope.launch {
                        while (SystemClock.uptimeMillis() < followUntil) {
                            delay(40)
                            show(level()) // catches the step(s) as they land
                        }
                    }
                }
            }
            // ACTION_UP: deliberately no cancel — the loop lapses 400ms after
            // the last key, so the final step is always caught (killing it
            // here is what made short presses wait for the ~560ms broadcast).
        }
        return false
    }

    /** Backstop: external volume writes repaint the overlay too. */
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                    != AudioManager.STREAM_MUSIC) return
            show(level())
        }
    }

    private fun show(level: Int) {
        if (!enabled || level < 0) return
        HomeGuardService.showLevel(app, level)
    }

    /** Console/API set (`/vol?l=`): the public API, nothing else. */
    @JvmStatic
    fun setLevel(target: Int) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target.coerceIn(0, max), 0)
        show(target.coerceIn(0, max))
    }

    /** The current level — the system's own stream index, nothing else. */
    @JvmStatic
    fun level(): Int {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try { am.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (t: Throwable) { -1 }
    }
}
