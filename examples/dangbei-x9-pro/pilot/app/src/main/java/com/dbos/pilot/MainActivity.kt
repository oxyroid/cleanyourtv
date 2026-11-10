package com.dbos.pilot

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * Compose-for-TV host. The Android TV docs deprecate the leanback fragment
 * toolkit in favor of Compose (androidx.tv), so the whole UI is Compose; the
 * resident service layer stays Java.
 *
 * Navigation is one level deep (home -> topic screen); back returns home.
 * Global overlays (result card, confirm card) are hoisted here so every
 * screen can trigger them through LocalPilotUi.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the resident service armed (also arms the Job chain on first
        // launch — the JobScheduler persisted chain lives in /data).
        try {
            val svc = Intent(this, PilotService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc)
            else startService(svc)
        } catch (_: Throwable) {}
        setContent { PilotApp() }
    }
}

/** One-level navigation targets. */
sealed class Screen {
    object Home : Screen()
    object Guard : Screen()
    object Audio : Screen()
    object Maintenance : Screen()
    object About : Screen()
    /** Second-level picker, opened from Guard; back returns to Guard. */
    object LauncherPicker : Screen()
}

/** Auto-dismissing feedback card content. */
private class PopupData(val text: String, val ms: Long)

/** Blocking confirm card content. */
private class ConfirmData(val title: String, val message: String, val onOk: () -> Unit)

/** What screens may ask the shell to show. */
class PilotUi internal constructor(
    val popup: (String, Long) -> Unit,
    val confirm: (String, String, () -> Unit) -> Unit,
)

val LocalPilotUi = androidx.compose.runtime.staticCompositionLocalOf<PilotUi> {
    error("PilotUi not provided")
}

@Composable
fun PilotApp() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var popup by remember { mutableStateOf<PopupData?>(null) }
    var confirm by remember { mutableStateOf<ConfirmData?>(null) }

    val ui = remember {
        PilotUi(
            popup = { text, ms -> popup = PopupData(text, ms) },
            confirm = { title, msg, onOk -> confirm = ConfirmData(title, msg, onOk) },
        )
    }

    PilotTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalPilotUi provides ui
            ) {
                when (screen) {
                    Screen.Home -> HomeScreen(onOpen = { screen = it })
                    Screen.Guard -> GuardScreen(onOpen = { screen = it })
                    Screen.Audio -> AudioScreen()
                    Screen.Maintenance -> MaintenanceScreen()
                    Screen.About -> AboutScreen()
                    Screen.LauncherPicker -> LauncherPickerScreen()
                }
            }

            // Back: the picker returns to Guard, any topic screen to home;
            // dialogs own their back key.
            BackHandler(enabled = screen != Screen.Home) {
                screen = if (screen == Screen.LauncherPicker) Screen.Guard else Screen.Home
            }
            popup?.let { data ->
                PopupCard(text = data.text, ms = data.ms, onGone = { popup = null })
            }
            confirm?.let { data ->
                ConfirmCard(
                    title = data.title,
                    message = data.message,
                    onConfirm = { data.onOk(); confirm = null },
                    onDismiss = { confirm = null },
                )
            }
        }
    }
}

@Composable
fun PilotTheme(content: @Composable () -> Unit) {
    // JetStream (the official Compose-for-TV reference sample) palette —
    // the single source of every color in the app; components only read
    // MaterialTheme.colorScheme and never hardcode hex values.
    //   surfaceVariant      cards, dialogs, neutral buttons
    //   secondaryContainer  dialog card
    //   primary/onPrimary   tonal OK button, checked glyphs, focused states
    //   primaryContainer    the recommended-setup card, pressed OK
    //   tertiaryContainer   pressed card
    //   border/borderVariant muted text, off-state glyphs (TV m3 slots)
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFA8C8FF),
            onPrimary = Color(0xFF003062),
            primaryContainer = Color(0xFF00468A),
            onPrimaryContainer = Color(0xFFD6E3FF),
            secondary = Color(0xFFBDC7DC),
            onSecondary = Color(0xFF26323F),
            secondaryContainer = Color(0xFF3E4758),
            onSecondaryContainer = Color(0xFFE3E2E6),
            tertiary = Color(0xFFC6CBD8),
            onTertiary = Color(0xFF2E3135),
            tertiaryContainer = Color(0xFF5A6069),
            onTertiaryContainer = Color(0xFFFFFFFF),
            background = Color(0xFF1A1C1E),
            onBackground = Color(0xFFE3E2E6),
            surface = Color(0xFF1A1C1E),
            onSurface = Color(0xFFE3E2E6),
            surfaceVariant = Color(0xFF43474E),
            onSurfaceVariant = Color(0xFFC4C6CF),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            border = Color(0xFF8A9099),
            borderVariant = Color(0xFF5A5F70),
        ),
        content = content,
    )
}
