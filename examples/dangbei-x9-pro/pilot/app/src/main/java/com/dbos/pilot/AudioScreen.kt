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
 * Sound screen: one switch.
 *
 * On  = volume keys step by 1 through the system's own 0–100 range and
 *       Pilot shows the level; long-press auto-repeats.
 * Off = factory behavior: the keys go straight to the system.
 */
@Composable
fun AudioScreen() {
    val ctx = LocalContext.current

    var rev by remember { mutableIntStateOf(0) }
    fun refresh() { rev++ }

    val grabber = rememberFocusGrabber()
    val cs = androidx.tv.material3.MaterialTheme.colorScheme
    // remember(rev) reads rev, so refresh() (rev++) really recomposes.
    val on = remember(rev) { Config.get(ctx, Config.Key.PERCEPTUAL) }

    Page(
        title = stringResource(R.string.screen_audio),
        subtitle = stringResource(R.string.screen_audio_s),
        grabber = grabber,
    ) {
        item {
            RowCard(
                modifier = Modifier.focusGrabber(grabber),
                title = marked(ctx, R.string.t_perc_vol, !on),
                summary = if (on) stringResource(R.string.s_perc_on)
                          else stringResource(R.string.s_perc_off),
                state = on,
                onClick = {
                    VolumeEngine.setEnabled(!on)
                    refresh()
                },
            )
        }

        item {
            Spacer(Modifier.height(6.dp))
            BasicText(
                stringResource(R.string.s_perc_detail),
                style = TextStyle(color = cs.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            )
        }
    }
}
