package com.dbos.pilot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults

/**
 * Home: live status strip, the one-tap recommended setup (with the diff
 * confirm card), and the four topic screens.
 */
@Composable
fun HomeScreen(onOpen: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val ui = LocalPilotUi.current
    val st = rememberStatus()

    val consoleLine = stringResource(R.string.usage_summary)
    val entryGuard = stringResource(R.string.screen_guard) to
            (stringResource(R.string.screen_guard_s) to Screen.Guard)
    val entryAudio = stringResource(R.string.screen_audio) to
            (stringResource(R.string.screen_audio_s) to Screen.Audio)
    val entryMaintenance = stringResource(R.string.screen_maintenance) to
            (stringResource(R.string.screen_maintenance_s) to Screen.Maintenance)
    val entryAbout = stringResource(R.string.screen_about) to
            (stringResource(R.string.screen_about_s) to Screen.About)

    val grabber = rememberFocusGrabber()
    val cs = androidx.tv.material3.MaterialTheme.colorScheme

    Page(title = stringResource(R.string.app_name), subtitle = stringResource(R.string.splash_tag),
        grabber = grabber) {

        item {
            // Status strip: compact dot lines, refreshed by rememberStatus.
            Surface(
                colors = SurfaceDefaults.colors(
                    containerColor = cs.onSurface.copy(alpha = 0.06f),
                    contentColor = cs.onSurface,
                ),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp)) {
                    BasicText(
                        st.ip + " · http://" + st.ip + ':' + PilotService.HTTP_PORT,
                        style = TextStyle(color = cs.border, fontSize = 14.sp),
                    )
                    Spacer(Modifier.height(4.dp))
                    val guardS = stringResource(R.string.st_guard)
                    val shellS = stringResource(R.string.st_shell)
                    val otaS = stringResource(R.string.st_ota)
                    val curveS = stringResource(R.string.st_curve, st.curve)
                    BasicText(
                        buildString {
                            append(if (st.guard) "● " else "○ ").append(guardS)
                            append("   ")
                            append(if (st.shell) "● " else "○ ").append(shellS)
                            append("   ")
                            append(if (st.otaBlocked) "● " else "○ ").append(otaS)
                            append("   ").append(curveS)
                        },
                        style = TextStyle(color = cs.onSurfaceVariant, fontSize = 14.sp),
                    )
                }
            }
        }

        item {
            // Recommended setup: the primary card — JetStream's primaryContainer
            // pair (high contrast) and it takes the initial focus.
            Surface(
                onClick = { askRecommend(ctx, ui.popup, ui.confirm) },
                modifier = Modifier.focusGrabber(grabber),
                shape = ClickableSurfaceDefaults.shape(),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                border = jetFocusBorder(),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = cs.primaryContainer,
                    contentColor = cs.onPrimaryContainer,
                    focusedContainerColor = cs.primaryContainer,
                    focusedContentColor = cs.onPrimaryContainer,
                    pressedContainerColor = cs.primary,
                    pressedContentColor = cs.onPrimary,
                ),
            ) {
                Row(
                    Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        BasicText(
                            stringResource(R.string.t_recommend),
                            style = TextStyle(
                                color = cs.onPrimaryContainer, fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        Spacer(Modifier.height(2.dp))
                        BasicText(
                            stringResource(R.string.s_recommend),
                            style = TextStyle(
                                color = cs.onPrimaryContainer.copy(alpha = 0.78f),
                                fontSize = 14.sp,
                            ),
                        )
                    }
                }
            }
        }

        items(listOf(entryGuard, entryAudio, entryMaintenance, entryAbout)) { (title, entry) ->
            val (subtitle, screen) = entry
            RowCard(title = title, summary = subtitle, onClick = { onOpen(screen) })
        }

        item {
            Spacer(Modifier.height(8.dp))
            BasicText(
                consoleLine,
                style = TextStyle(color = cs.borderVariant, fontSize = 13.sp, lineHeight = 18.sp),
            )
        }
    }
}

/** Diff the current state against the recommended state, then confirm. */
private fun askRecommend(
    ctx: android.content.Context,
    popup: (String, Long) -> Unit,
    confirm: (String, String, () -> Unit) -> Unit,
) {
    val change = StringBuilder()
    val keep = StringBuilder()
    if (!Config.get(ctx, Config.Key.PERCEPTUAL)) change.append(ctx.getString(R.string.rec_vol)).append('\n')
    else keep.append(ctx.getString(R.string.rec_vol_already)).append('\n')
    if (!Config.get(ctx, Config.Key.HOMEGUARD)) change.append(ctx.getString(R.string.rec_guard)).append('\n')
    else keep.append(ctx.getString(R.string.rec_guard_already)).append('\n')
    if (!Config.get(ctx, Config.Key.HOMEFIX)) change.append(ctx.getString(R.string.rec_boot)).append('\n')
    else keep.append(ctx.getString(R.string.rec_boot_already)).append('\n')
    if (!Config.get(ctx, Config.Key.OTABLOCK)) change.append(ctx.getString(R.string.rec_ota)).append('\n')
    else keep.append(ctx.getString(R.string.rec_ota_already)).append('\n')
    if (!HomeGuardService.connected) change.append(ctx.getString(R.string.rec_grant)).append('\n')
    else keep.append(ctx.getString(R.string.rec_grant_already)).append('\n')

    if (change.isEmpty()) {
        popup(ctx.getString(R.string.rec_nothing), 2600)
        return
    }
    val msg = buildString {
        append(ctx.getString(R.string.rec_will)).append("\n\n").append(change)
        if (keep.isNotEmpty()) {
            append('\n').append(ctx.getString(R.string.rec_keeps)).append('\n').append(keep)
        }
    }
    confirm(ctx.getString(R.string.rec_title), msg) { applyRecommend(ctx, popup) }
}

/** After confirmation: apply, self-grant if needed, report the outcome. */
private fun applyRecommend(ctx: android.content.Context, popup: (String, Long) -> Unit) {
    VolumeEngine.setEnabled(true)
    Config.set(ctx, Config.Key.HOMEGUARD, true)
    Config.set(ctx, Config.Key.HOMEFIX, true)
    Config.set(ctx, Config.Key.OTABLOCK, true)
    if (!HomeGuardService.connected) {
        popup(ctx.getString(R.string.grant_wait), 2000)
        runAndPopup(ctx, { PilotService.selfGrant(ctx) }, { t, ms ->
            popup(
                if ("granted" == t) ctx.getString(R.string.rec_grant_hint)
                else ctx.getString(R.string.rec_grant_fail) + "\n" + t,
                ms,
            )
        }, 3000, 5000)
    } else {
        popup(ctx.getString(R.string.rec_done), 2600)
    }
}
