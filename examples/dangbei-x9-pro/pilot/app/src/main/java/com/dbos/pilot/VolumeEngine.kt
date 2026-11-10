package com.dbos.pilot

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Fine volume, done with one system switch: the ROM's AudioService branches
 * its native volume-key stepping on the prop persist.vendor.brand.simple —
 * 1 = one index per press (0..100 fine steps), 10 = the factory default.
 * That prop drives the keys, the long-press repeat and the stock volume bar;
 * everything stays system behavior, nothing for us to intercept or repaint.
 *
 *   on  — prop 1: ±1 per press.
 *   off — prop 10: factory ±5, exactly as shipped.
 *
 * The only other job is the console API (`/vol?l=`), which writes the stream
 * index through the public AudioManager API.
 */
object VolumeEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var app: Context
    private var started = false

    @JvmStatic
    fun start(ctx: Context) {
        started = true
        app = ctx.applicationContext
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

    /** Point the ROM's native stepping at the switch. Safe to call often. */
    @JvmStatic
    fun sync() {
        val want = if (enabled) "1" else "10"
        if (LocalAdb.shell("getprop persist.vendor.brand.simple", 4000)?.trim() != want) {
            LocalAdb.shell("setprop persist.vendor.brand.simple $want", 4000)
        }
    }

    /** Console/API set (`/vol?l=`): the public API, nothing else. */
    @JvmStatic
    fun setLevel(target: Int) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target.coerceIn(0, max), 0)
    }

    /** The current level — the system's own stream index, nothing else. */
    @JvmStatic
    fun level(): Int {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try { am.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (t: Throwable) { -1 }
    }
}
