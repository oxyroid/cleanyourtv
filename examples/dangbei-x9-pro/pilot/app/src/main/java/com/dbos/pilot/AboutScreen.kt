package com.dbos.pilot

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

/**
 * About screen: device identity with the console address and token, the
 * usage hints, the disclaimer, and the global reset (behind a confirm card —
 * it touches every switch).
 */
@Composable
fun AboutScreen() {
    val ctx = LocalContext.current
    val ui = LocalPilotUi.current

    val grabber = rememberFocusGrabber()

    val device = remember {
        Build.MODEL + " · Android " + Build.VERSION.RELEASE
    }
    val ips = remember { PilotService.localIPv4() }
    val ip = if (ips.isEmpty()) "-" else ips[0]

    Page(
        title = stringResource(R.string.screen_about),
        subtitle = stringResource(R.string.screen_about_s),
        grabber = grabber,
    ) {
        item {
            RowCard(
                modifier = Modifier.focusGrabber(grabber),
                title = device,
                summary = "IP: " + ip + "\n" +
                    "Console: http://" + ip + ':' + PilotService.HTTP_PORT + "/ui\n" +
                    "Token: " + PilotService.TOKEN,
                onClick = {},
            )
        }
        item {
            RowCard(
                title = stringResource(R.string.usage_title),
                summary = stringResource(R.string.usage_summary),
                onClick = {},
            )
        }
        item {
            RowCard(
                title = stringResource(R.string.about_legal),
                summary = "",
                onClick = {},
            )
        }
        item {
            RowCard(
                title = stringResource(R.string.t_reset),
                summary = stringResource(R.string.s_reset),
                onClick = {
                    ui.confirm(
                        ctx.getString(R.string.t_reset),
                        ctx.getString(R.string.reset_confirm),
                    ) {
                        Config.reset(ctx)
                        ctx.sendService(PilotService.ACT_APPLY_VOLUME)
                        ui.popup(ctx.getString(R.string.reset_done), 2600)
                    }
                },
            )
        }
    }
}
