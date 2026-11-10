package com.dbos.pilot

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

/**
 * Second-level picker: choose which launcher the guard restores. Radio-list
 * styling (OptionRow) — visually distinct from the toggle rows above it.
 * The first entry is always the automatic rule.
 */
@Composable
fun LauncherPickerScreen() {
    val ctx = LocalContext.current

    // Re-read the candidates and the stored choice after any pick.
    var rev by remember { mutableIntStateOf(0) }
    val candidates = remember(rev) { HomeLauncher.listCandidates(ctx) }

    val grabber = rememberFocusGrabber()

    Page(
        title = stringResource(R.string.t_home_target),
        subtitle = stringResource(R.string.pick_launcher_sub),
        grabber = grabber,
    ) {
        item {
            val cur = Config.getStr(ctx, Config.Key.HOMETARGET, HomeLauncher.AUTO)
            OptionRow(
                modifier = Modifier.focusGrabber(grabber),
                title = stringResource(R.string.s_home_target_auto),
                selected = HomeLauncher.AUTO == cur,
                onClick = {
                    Config.setStr(ctx, Config.Key.HOMETARGET, HomeLauncher.AUTO)
                    rev++
                },
            )
        }

        items(candidates) { cn ->
            val cur = Config.getStr(ctx, Config.Key.HOMETARGET, HomeLauncher.AUTO)
            OptionRow(
                title = HomeLauncher.labelOf(ctx, cn),
                selected = cur == cn.flattenToString(),
                onClick = {
                    Config.setStr(ctx, Config.Key.HOMETARGET, cn.flattenToString())
                    rev++
                },
            )
        }
    }
}
