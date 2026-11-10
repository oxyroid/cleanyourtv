package com.dbos.pilot

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Shared TV building blocks, following JetStream (the official
 * Compose-for-TV reference sample). Rule: components never hardcode
 * colors — everything comes from [PilotTheme] (MainActivity.kt):
 *
 *   - cards / dialogs  = surfaceVariant-family containers;
 *   - focus            = a bright 3dp onSurface border, NO scale zoom;
 *   - primary (OK)     = primary/onPrimary pair;
 *   - muted text       = onSurfaceVariant; off states = borderVariant;
 *   - disabled         = onSurface at reduced alpha.
 */

/** JetStream focus border: 3dp, bright onSurface over the dark background. */
@Composable
internal fun jetFocusBorder() = ClickableSurfaceDefaults.border(
    focusedBorder = Border(
        border = BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface),
        shape = RoundedCornerShape(4.dp),
    ),
)

/** Title with the " *" changed-from-default marker. */
fun marked(ctx: Context, res: Int, changed: Boolean): String =
    (if (changed) ctx.getString(res) + " *" else ctx.getString(res))

@Composable
private fun titleStyle(enabled: Boolean) = TextStyle(
    color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    fontSize = 20.sp, fontWeight = FontWeight.Medium,
)

@Composable
private fun summaryStyle(enabled: Boolean) = TextStyle(
    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    fontSize = 14.sp, lineHeight = 19.sp,
)

/**
 * A full-width focusable row card.
 *
 * state == null  -> plain action row
 * state != null  -> row with a trailing on/off glyph (toggle or radio)
 */
@Composable
fun RowCard(
    title: String,
    summary: String,
    state: Boolean? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = jetFocusBorder(),
        colors = ClickableSurfaceDefaults.colors(
            // The card stays surfaceVariant in every state — focus is carried
            // by the 3dp bright border alone (JetStream pattern), so text
            // keeps high contrast on top of the focused card.
            containerColor = cs.surfaceVariant,
            contentColor = cs.onSurface,
            focusedContainerColor = cs.surfaceVariant,
            focusedContentColor = cs.onSurface,
            pressedContainerColor = cs.tertiaryContainer,
            pressedContentColor = cs.onTertiaryContainer,
            disabledContainerColor = cs.onSurface.copy(alpha = 0.08f),
            disabledContentColor = cs.onSurface.copy(alpha = 0.38f),
        ),
    ) {
        Row(
            Modifier.padding(start = 24.dp, end = 20.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                BasicText(title, style = titleStyle(enabled))
                if (summary.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    BasicText(summary, style = summaryStyle(enabled))
                }
            }
            if (state != null) {
                SwitchGlyph(on = state, enabled = enabled,
                    modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
}

/**
 * A drawn on/off switch: pill track + sliding thumb. Both position and color
 * animate on state change so on vs off is unmistakable at TV distance — the
 * old "●"/"○" text glyph read as the same small circle from the couch.
 */
@Composable
private fun SwitchGlyph(on: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val track by animateColorAsState(
        when {
            !enabled -> cs.onSurface.copy(alpha = 0.10f)
            on       -> cs.primary
            else     -> cs.onSurface.copy(alpha = 0.22f)
        },
        label = "track",
    )
    val thumb by animateColorAsState(
        when {
            !enabled -> cs.onSurface.copy(alpha = 0.30f)
            on       -> cs.onPrimary
            else     -> cs.onSurfaceVariant
        },
        label = "thumb",
    )
    val x by animateDpAsState(if (on) 23.dp else 3.dp, label = "thumbX")
    Box(
        modifier
            .size(width = 46.dp, height = 24.dp)
            .background(track, RoundedCornerShape(12.dp))
    ) {
        Box(
            Modifier
                .offset(x = x, y = 3.dp)
                .size(18.dp)
                .background(thumb, CircleShape)
        )
    }
}

/**
 * A radio-list option item for second-level picker screens. Deliberately
 * unlike [RowCard]: no resting card background, a leading radio glyph and a
 * smaller indented label — it reads as "choose one", not "toggle this".
 */
@Composable
fun OptionRow(
    title: String,
    selected: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = jetFocusBorder(),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = cs.onSurface,
            focusedContainerColor = cs.surfaceVariant,
            focusedContentColor = cs.onSurface,
            pressedContainerColor = cs.tertiaryContainer,
            pressedContentColor = cs.onTertiaryContainer,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = cs.onSurface.copy(alpha = 0.38f),
        ),
    ) {
        Row(
            Modifier.padding(start = 40.dp, end = 20.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                if (selected) "◉" else "○",
                style = TextStyle(
                    color = if (selected) cs.primary else cs.borderVariant,
                    fontSize = 19.sp,
                ),
                modifier = Modifier.padding(end = 18.dp),
            )
            BasicText(
                title,
                style = TextStyle(
                    color = if (enabled) cs.onSurface
                            else cs.onSurface.copy(alpha = 0.38f),
                    fontSize = 17.sp,
                ),
            )
        }
    }
}

/**
 * Initial-focus plumbing. A bare `requestFocus()` from LaunchedEffect is
 * racy: on back-navigation the recomposition can outrun focus-node
 * attachment and the single request is lost — leaving the screen with no
 * focus node and a dead d-pad. The grabber retries until the focus event
 * is actually observed on the target node (onFocusChanged), then stops, so
 * it never fights the user's own navigation.
 */
class FocusGrabber internal constructor() {
    val requester = FocusRequester()
    internal var landed = false
}

@Composable
fun rememberFocusGrabber(): FocusGrabber {
    val g = remember { FocusGrabber() }
    LaunchedEffect(g) {
        repeat(20) {
            if (g.landed) return@LaunchedEffect
            runCatching { g.requester.requestFocus() }
            delay(120)
        }
    }
    return g
}

fun Modifier.focusGrabber(g: FocusGrabber): Modifier = this
    .onFocusChanged { g.landed = it.isFocused }
    .focusRequester(g.requester)

/**
 * Screen scaffold: page title + scrollable column of rows.
 */
@Composable
fun Page(title: String, subtitle: String, grabber: FocusGrabber, content: LazyListScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 28.dp)
    ) {
        BasicText(
            title,
            style = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 32.sp, fontWeight = FontWeight.Bold,
            ),
        )
        if (subtitle.isNotEmpty()) {
            BasicText(
                subtitle,
                style = TextStyle(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                ),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** Scrim + centered card — the shared dialog shell. The dialog window is
 *  forced full-screen (default shrinks to content width), so the scrim
 *  covers the whole display. */
@Composable
private fun DialogCard(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            colors = SurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            content()
        }
    }
}

private val FullWindow = androidx.compose.ui.window.DialogProperties(
    usePlatformDefaultWidth = false,
)

/** Auto-dismissing centered result card (its own window; back closes it). */
@Composable
fun PopupCard(text: String, ms: Long, onGone: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onGone, properties = FullWindow) {
        DialogCard {
            BasicText(
                text,
                style = TextStyle(
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontSize = 17.sp, lineHeight = 24.sp,
                ),
                modifier = Modifier
                    .defaultMinSize(minWidth = 280.dp)
                    .padding(horizontal = 32.dp, vertical = 22.dp),
            )
        }
    }
    LaunchedEffect(text, ms) { delay(ms); onGone() }
}

/**
 * Dialog action button. The OK button is tonal (primary/onPrimary); the
 * cancel button is neutral (surfaceVariant). Focus is carried by the same
 * bright 3dp onSurface border as the cards — no state flips to a bright
 * container, so contrast never breaks.
 */
@Composable
private fun TvActionButton(text: String, primary: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val container = if (primary) cs.primary else cs.surfaceVariant
    val content = if (primary) cs.onPrimary else cs.onSurface
    Button(
        onClick = onClick,
        colors = ButtonDefaults.colors(
            containerColor = container,
            contentColor = content,
            focusedContainerColor = container,
            focusedContentColor = content,
            pressedContainerColor = if (primary) cs.primaryContainer else cs.tertiaryContainer,
            pressedContentColor = if (primary) cs.onPrimaryContainer else cs.onTertiaryContainer,
        ),
        border = ButtonDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, cs.onSurface), 0.dp),
        ),
    ) {
        // BasicText does NOT pick up LocalContentColor (unlike Material's
        // Text) — pair the style color with the container explicitly.
        BasicText(
            text,
            style = TextStyle(
                color = content,
                fontSize = 16.sp, fontWeight = FontWeight.Medium,
            ),
        )
    }
}

/** Blocking centered confirm card with OK / Cancel (own window; back closes). */
@Composable
fun ConfirmCard(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        DialogCard {
            Column(
                Modifier
                    .defaultMinSize(minWidth = 420.dp)
                    .padding(horizontal = 32.dp, vertical = 24.dp)
            ) {
                BasicText(
                    title,
                    style = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(Modifier.height(12.dp))
                BasicText(
                    message,
                    style = TextStyle(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp, lineHeight = 24.sp,
                    ),
                )
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TvActionButton(
                        stringResource(android.R.string.ok).uppercase(),
                        primary = true,
                        onClick = onConfirm,
                    )
                    TvActionButton(
                        stringResource(android.R.string.cancel),
                        primary = false,
                        onClick = onDismiss,
                    )
                }
            }
        }
    }
}

/** Live device status, polled off the main thread every 2s. */
class Status(
    val ip: String,
    val guard: Boolean,
    val shell: Boolean,
    val otaBlocked: Boolean,
    val curve: String,
    val adbdOn: Boolean,
)

@Composable
fun rememberStatus(): Status {
    var status by remember { mutableStateOf(Status("-", false, false, false, "-", false)) }
    LaunchedEffect(Unit) {
        while (true) {
            val s = withContext(Dispatchers.Default) {
                val ctx = PilotService.instance
                val ips = PilotService.localIPv4()
                val eng = PilotService.shStatic("getprop init.svc.update_engine").trim()
                Status(
                    ip = if (ips.isEmpty()) "-" else ips[0],
                    guard = HomeGuardService.connected,
                    shell = "running".equals(PilotService.adbdState),
                    otaBlocked = "stopped".equals(eng),
                    curve = if (Config.get(ctx, Config.Key.PERCEPTUAL)) ctx.getString(R.string.vol_mode_perc)
                            else ctx.getString(R.string.vol_mode_stock),
                    adbdOn = Config.get(ctx, Config.Key.ADBD),
                )
            }
            status = s
            delay(2000)
        }
    }
    return status
}

/** Fire-and-forget service action (mirrors the old Java fragments). */
fun Context.sendService(action: String) {
    try {
        val it = Intent(this, PilotService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(it)
        else startService(it)
    } catch (_: Throwable) {}
}

/** Run a blocking op off the main thread, then show a popup card. */
fun runAndPopup(
    ctx: Context,
    block: () -> String,
    show: (String, Long) -> Unit,
    okMs: Long,
    failMs: Long,
) {
    Thread {
        val r = block()
        Handler(Looper.getMainLooper()).post {
            show(r, if ("granted" == r) okMs else failMs)
        }
    }.start()
}
