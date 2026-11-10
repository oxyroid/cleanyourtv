package com.dbos.pilot

import android.content.ComponentName
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

/**
 * Guard screen: the home-guard switch, the boot-launch fallback, one entry
 * into the launcher picker (second level), the one-tap self-grant with a
 * result card, and guard repair.
 *
 * While the accessibility grant is missing, the grant-dependent rows grey
 * out with the dependency named; enabled rows never carry warning text.
 */
@Composable
fun GuardScreen(onOpen: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val ui = LocalPilotUi.current
    val st = rememberStatus()
    val guardLive = st.guard

    // Bump to re-read after any write.
    var rev by remember { mutableIntStateOf(0) }

    fun refresh() { rev++ }

    val grabber = rememberFocusGrabber()

    Page(
        title = stringResource(R.string.screen_guard),
        subtitle = stringResource(R.string.screen_guard_s),
        grabber = grabber,
    ) {
        item {
            val depGrant = stringResource(R.string.dep_grant)
            val changed = Config.changedFromDefault(ctx, Config.Key.HOMEGUARD)
            RowCard(
                modifier = Modifier.focusGrabber(grabber),
                title = marked(ctx, R.string.t_homeguard, changed),
                summary = if (guardLive) stringResource(R.string.s_homeguard) else depGrant,
                state = Config.get(ctx, Config.Key.HOMEGUARD),
                enabled = guardLive,
                onClick = {
                    val nv = !Config.get(ctx, Config.Key.HOMEGUARD)
                    Config.set(ctx, Config.Key.HOMEGUARD, nv)
                    if (!nv) ctx.sendService(PilotService.ACT_GUARD_OFF)
                    refresh()
                },
            )
        }

        item {
            RowCard(
                title = marked(ctx, R.string.t_homefix, Config.changedFromDefault(ctx, Config.Key.HOMEFIX)),
                summary = stringResource(R.string.s_homefix),
                state = Config.get(ctx, Config.Key.HOMEFIX),
                onClick = {
                    Config.set(ctx, Config.Key.HOMEFIX, !Config.get(ctx, Config.Key.HOMEFIX))
                    refresh()
                },
            )
        }

        item {
            // Launcher choice lives on its own second-level page; this row
            // just shows the current selection.
            val cur = Config.getStr(ctx, Config.Key.HOMETARGET, HomeLauncher.AUTO)
            val label = if (HomeLauncher.AUTO == cur) stringResource(R.string.s_home_target_auto)
                        else HomeLauncher.labelOf(ctx, ComponentName.unflattenFromString(cur))
            RowCard(
                title = marked(ctx, R.string.t_home_target, !HomeLauncher.AUTO.equals(cur)),
                summary = label,
                onClick = { onOpen(Screen.LauncherPicker) },
            )
        }

        item {
            RowCard(
                title = stringResource(R.string.t_grant),
                summary = stringResource(R.string.s_grant),
                onClick = {
                    ui.popup(ctx.getString(R.string.grant_wait), 2000)
                    runAndPopup(ctx, { PilotService.selfGrant(ctx) }, { t, ms ->
                        ui.popup(if ("granted" == t) ctx.getString(R.string.grant_ok)
                                 else ctx.getString(R.string.grant_fail) + "\n" + t, ms)
                        refresh()
                    }, 3000, 5000)
                },
            )
        }

        item {
            val depGrant = stringResource(R.string.dep_grant)
            RowCard(
                title = stringResource(R.string.t_repair),
                summary = if (guardLive) stringResource(R.string.s_repair) else depGrant,
                enabled = guardLive,
                onClick = {
                    ctx.sendService(PilotService.ACT_REPAIR_GUARD)
                    ui.popup(ctx.getString(R.string.repair_wait), 3500)
                    refresh()
                },
            )
        }
    }
}
