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
 * Remote & maintenance screen. Defaults are chosen so the app never changes
 * factory behavior by merely being installed — except the OTA block: a system
 * update is what breaks a customized set, so blocking is the safer default.
 * The shell-acting switches grey out while adbd is down; the adbd switch
 * itself stays selectable (otherwise it could never be re-enabled).
 */
@Composable
fun MaintenanceScreen() {
    val ctx = LocalContext.current
    val st = rememberStatus()
    val shellLive = st.shell
    val depAdbd = stringResource(R.string.dep_adbd)

    var rev by remember { mutableIntStateOf(0) }
    fun refresh() { rev++ }

    val grabber = rememberFocusGrabber()

    val toggles = listOf(
        Triple(Config.Key.ADBD, R.string.t_adbd, R.string.s_adbd),
        Triple(Config.Key.ANNOUNCE, R.string.t_announce, R.string.s_announce),
        Triple(Config.Key.LOGD, R.string.t_logd, R.string.s_logd),
        Triple(Config.Key.OTABLOCK, R.string.t_ota, R.string.s_ota),
    )

    Page(
        title = stringResource(R.string.screen_maintenance),
        subtitle = stringResource(R.string.screen_maintenance_s),
        grabber = grabber,
    ) {
        items(toggles.size) { idx ->
            val (key, tRes, sRes) = toggles[idx]
            // logd and the OTA block act through the shell channel; adbd and
            // the UDP announce act as the app itself.
            val viaShell = Config.Key.LOGD == key || Config.Key.OTABLOCK == key
            val enabled = !viaShell || shellLive
            RowCard(
                modifier = Modifier.focusGrabber(grabber),
                title = marked(ctx, tRes, Config.changedFromDefault(ctx, key)),
                summary = if (enabled) ctx.getString(sRes) else depAdbd,
                state = Config.get(ctx, key),
                enabled = enabled,
                onClick = {
                    Config.set(ctx, key, !Config.get(ctx, key))
                    refresh()
                },
            )
        }
    }
}
