package com.dbos.pilot

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Sound screen: one switch. Perceptual volume on = the Pilot overlay + a
 * 100-level, even-dB curve (0.8 dB/step, floor at −80 dB via the AudioFlinger
 * master-volume stage); off = factory behavior. Turning it on needs the
 * shell channel (native ±1 steps, stock-bar cut, master writes), so the row
 * greys out with the dependency while adbd is down.
 */
@Composable
fun AudioScreen() {
    val ctx = LocalContext.current
    val st = rememberStatus()
    val shellLive = st.shell
    val depAdbd = stringResource(R.string.dep_adbd)

    var rev by remember { mutableIntStateOf(0) }
    fun refresh() { rev++ }

    val grabber = rememberFocusGrabber()
    val cs = androidx.tv.material3.MaterialTheme.colorScheme
    // remember(rev) reads rev, so refresh() (rev++) really recomposes.
    val curveOn = remember(rev) {
        BetterAudio.isOn(Config.getStr(ctx, Config.Key.VOLCURVE, BetterAudio.PERCEPTUAL))
    }

    Page(
        title = stringResource(R.string.screen_audio),
        subtitle = stringResource(R.string.screen_audio_s),
        grabber = grabber,
    ) {
        item {
            RowCard(
                modifier = Modifier.focusGrabber(grabber),
                title = marked(ctx, R.string.t_perc_vol,
                    Config.getStr(ctx, Config.Key.VOLCURVE, BetterAudio.PERCEPTUAL) != BetterAudio.PERCEPTUAL),
                summary = when {
                    !shellLive -> depAdbd
                    curveOn -> stringResource(R.string.s_perc_on)
                    else -> stringResource(R.string.s_perc_off)
                },
                state = curveOn,
                enabled = shellLive,
                onClick = {
                    Config.applyPreset(ctx, if (curveOn) BetterAudio.STOCK else BetterAudio.PERCEPTUAL)
                    ctx.sendService(PilotService.ACT_APPLY_VOLUME)
                    refresh()
                },
            )
        }

        item {
            Spacer(Modifier.height(6.dp))
            BasicText(
                if (curveOn) stringResource(R.string.s_perc_curve_on) else stringResource(R.string.s_perc_curve_off),
                style = TextStyle(color = cs.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            )
        }
    }
}
