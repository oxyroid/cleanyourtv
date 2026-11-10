package com.dbos.pilot

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

/**
 * Sound screen: the better-audio preset radio (primary entry — it derives
 * the low-level switches), the switches for fine-tuning, and the one-tap
 * factory restore. The ±1 grid and the stock-bar cut go through the shell
 * channel, so while adbd is down these rows grey out with the dependency.
 */
@Composable
fun AudioScreen() {
    val ctx = LocalContext.current
    val ui = LocalPilotUi.current
    val st = rememberStatus()
    val shellLive = st.shell
    val depAdbd = stringResource(R.string.dep_adbd)

    var rev by remember { mutableIntStateOf(0) }
    fun refresh() { rev++ }

    val grabber = rememberFocusGrabber()

    fun curveTitle(name: String, cur: String): String {
        val base = ctx.getString(R.string.t_curve) + " · " + name
        // " *" marks the user having moved the curve off its default.
        return if (name == cur && !BetterAudio.FINE100.equals(cur)) base + " *" else base
    }

    val curveSummaries = mapOf(
        BetterAudio.STOCK to R.string.s_curve_stock,
        BetterAudio.FINE100 to R.string.s_curve_fine100,
        BetterAudio.STEP30 to R.string.s_curve_step30,
        BetterAudio.PERCEPTUAL to R.string.s_curve_perceptual,
    )

    Page(
        title = stringResource(R.string.screen_audio),
        subtitle = stringResource(R.string.screen_audio_s),
        grabber = grabber,
    ) {
        items(BetterAudio.PRESETS.size) { idx ->
            val name = BetterAudio.PRESETS[idx]
            val cur = Config.getStr(ctx, Config.Key.VOLCURVE, BetterAudio.FINE100)
            RowCard(
                modifier = Modifier.focusGrabber(grabber),
                title = curveTitle(name, cur),
                summary = ctx.getString(curveSummaries[name] ?: R.string.s_curve_fine100),
                state = name == cur,
                enabled = shellLive || name == cur,
                onClick = {
                    Config.applyPreset(ctx, name)
                    ctx.sendService(PilotService.ACT_APPLY_VOLUME)
                    refresh()
                },
            )
        }

        items(
            listOf(
                Triple(Config.Key.FINEVOL, R.string.t_finevol, R.string.s_finevol),
                Triple(Config.Key.VOLOSD, R.string.t_volosd, R.string.s_volosd),
                Triple(Config.Key.NIGHTGAIN, R.string.t_nightgain, R.string.s_nightgain),
            )
        ) { (key, tRes, sRes) ->
            RowCard(
                title = marked(ctx, tRes, Config.changedFromDefault(ctx, key)),
                summary = if (shellLive) ctx.getString(sRes) else depAdbd,
                state = Config.get(ctx, key),
                enabled = shellLive,
                onClick = {
                    Config.set(ctx, key, !Config.get(ctx, key))
                    ctx.sendService(PilotService.ACT_APPLY_VOLUME)
                    refresh()
                },
            )
        }

        item {
            RowCard(
                title = stringResource(R.string.t_restore_stock),
                summary = if (shellLive) stringResource(R.string.s_restore_stock) else depAdbd,
                enabled = shellLive,
                onClick = {
                    Config.applyPreset(ctx, BetterAudio.STOCK)
                    ctx.sendService(PilotService.ACT_APPLY_VOLUME)
                    ui.popup(ctx.getString(R.string.restore_done), 2600)
                    refresh()
                },
            )
        }
    }
}
