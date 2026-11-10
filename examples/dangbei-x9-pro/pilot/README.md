# Pilot — the way in for DBOS projectors

**This is not part of the debloat lists.** It lives beside them because every Dangbei DBOS firmware
shares one problem: there is no way in. No Developer options, no `com.android.settings`, no adbd at
boot — the ROM ships adbd fully configured (`ro.adb.secure=0`, `persist.adb.tcp.port=5555`) but never
starts it, and no stock screen lets you start it either. Until you have a shell, no list on
[examples/](../../README.md) can be applied at all.

Pilot is that way in: a sideloaded APK that runs on the TV and opens the door from the inside — and
then keeps the set manageable without ever needing a computer again. Install it with the stock file
manager, open it once, tap **Self-grant**. Everything else is optional and switchable inside the app.

Built for, and tuned on, a Dangbei X9 Pro (Android 14). The app checks the model and OS build at
every launch and says so — a different set is *not* refused, but the splash calls out the risk,
since the shell commands and volume internals below are firmware-specific. The software is for
learning purposes only; the splash and the About screen carry the disclaimer.

## What the app is

- **Resident service** (plain Java, `PilotService`): opens the built-in HTTP/UDP console on port
  8765, keeps `logd` alive, holds `update_engine` stopped (OTA block), applies the night gain,
  re-applies the volume curve, and repairs the accessibility grant through the shell channel.
- **Compose-for-TV UI** (`MainActivity` + screens in Kotlin): a JetStream-styled dark theme, one
  home screen with the status strip and a one-tap **Recommended setup** (diff confirm dialog), and
  second-level screens for guard, sound, maintenance and about. Rows whose dependencies are missing
  grey out and name what they need.
- **Splash** (Java): device/OS fingerprint check — informational, never blocking — plus the
  learning-purpose disclaimer.
- **Guard** (`HomeGuardService`, accessibility): restores your chosen launcher when the stock
  desktop grabs the screen, and draws the exact-volume overlay. The launcher choice lives on its own
  second-level radio-list page (default: automatic — first third-party launcher).
- **Volume** (`VolCurve` + `BetterAudio`): reacts to the system `VOLUME_CHANGED` broadcast and
  corrects each landed step onto the selected curve — no key-event observation, no race with the
  native stepper. Presets: stock, fine100, step30, perceptual.
- **Self-grant**: starts adbd through the factory hole (`setprop ctl.start adbd` is writable from
  the app uid on this ROM) and drives a minimal in-process ADB client (`LocalAdb`) to grant the
  accessibility settings — no computer involved. The enabled-services list is read back from
  `dumpsys accessibility` because this ROM denies the `settings` command to the app uid entirely.

The UI is localized in English, 简体中文 and 繁體中文. Rows the user moved away from the default
get a `*` after the title.

## Why it exists

- **No entry point.** DBOS has no Developer options and no Settings app, but the ROM lets *any
  installed app* run `setprop ctl.start adbd` — a plain `Runtime.exec`, no permission dialog.
- **No self-start for ordinary apps.** The ROM delivers `BOOT_COMPLETED` to third-party receivers
  but refuses to start their processes for it. A JobScheduler **persisted** job is the one
  self-start path that survives, because the system's own JobSchedulerService pulls the process.
  Pilot arms one on first launch.
- **IP drift.** The TV broadcasts its address over UDP so a computer can find it after a DHCP change.

## Building

Standard Android Gradle project — no wrapper is committed; use any recent Gradle (8.x) with the
Android SDK (compileSdk 34, minSdk 26):

```
gradle :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

`app/debug.keystore` is committed so everyone signs with the same key and installs upgrade in
place. `local.properties` (SDK path) is machine-local and not committed.

## Layout

```
app/src/main/java/com/dbos/pilot/
  MainActivity.kt      Compose host + navigation + PilotTheme (the only color source)
  HomeScreen.kt        status strip, recommended setup, entries
  GuardScreen.kt       guard toggle, boot launch, launcher entry, self-grant, repair
  LauncherPickerScreen.kt  second-level radio list of HOME candidates
  AudioScreen.kt       curve radios, fine keys, stock-OSD hide, night gain
  MaintenanceScreen.kt adbd, announce, logd, OTA block
  AboutScreen.kt       device info, disclaimer, reset
  Ui.kt                RowCard / OptionRow / dialogs / FocusGrabber building blocks
  SplashActivity.java  device check + disclaimer
  PilotService.java    resident service, HTTP console, shell paths
  HomeGuardService.java  accessibility: home guard + volume overlay
  VolCurve.java        broadcast-driven volume correction
  BetterAudio.java     the four curves (pure math)
  HomeLauncher.java    launcher resolution + candidates
  LocalAdb.java        minimal ADB client (CNXN/OPEN/WRTE)
  Config.java          the persisted settings
```
