# Pilot — the way in for DBOS projectors

**This is not part of the debloat lists.** It lives beside them because every Dangbei DBOS firmware
shares one problem: there is no way in. No Developer options, no `com.android.settings`, no adbd at
boot — the ROM ships adbd fully configured (`ro.adb.secure=0`, `persist.adb.tcp.port=5555`) but never
starts it, and no stock screen lets you start it either. Until you have a shell, no list on
[examples/](../../README.md) can be applied at all.

Pilot is that way in: a ~75 KB sideloaded APK, plain Java, no dependencies, no AndroidX, that runs
on the TV and opens the door from the inside — and then keeps the set manageable without ever
needing a computer again. Copy [dist/DBOSPilot.apk](dist/DBOSPilot.apk) to a USB stick, install it
with the stock file manager, open it once. Everything else is optional and switchable inside the app.

Built for, and tuned on, a Dangbei X9 Pro (Android 14). The app checks the model and OS build at
every launch and says so — a different set is *not* refused, but the splash calls out the risk,
since the shell commands and volume internals below are firmware-specific. The software is for
learning purposes only; the splash and the About screen carry the disclaimer.

## What the app is

The whole app is a preference tree (the platform's own `PreferenceFragment` UI — standard D-pad
navigation, toggles and list dialogs match the system look), localized in English, 简体中文 and
繁體中文:

- **Splash** — device check (model + OS build against the tested combination, informational only,
  never blocking) and the disclaimer. Any key or 3 seconds moves on.
- **Device** — live status in compact lines (IP + console URL, home guard / shell / update-block
  states, current curve) and **Use recommended setup**: one tap applies the tuned combination.
  It first shows exactly what will change and what already matches; confirm applies, cancel keeps
  everything as-is.
- **Home guard** — the guard switch, the launcher picker, self-grant, repair, and the boot-launch
  fallback.
- **Sound** — the volume curve preset, the low-level switches, night gain, factory restore.
- **Remote & maintenance** — adbd at boot, IP broadcast, logd, system-update block.
- **About** — device identity, console address + token, key hints, reset (behind a confirm).

Rows the user moved away from the default get a `*` after the title. Rows whose underlying
capability is unavailable are greyed out, with the missing dependency named in the summary —
for example the shell-acting switches while ADB is down, or the guard rows before the
accessibility grant exists.

## Why it exists

- **No entry point.** DBOS has no Developer options and no Settings app, but the ROM lets *any
  installed app* run `setprop ctl.start adbd` — a plain `Runtime.exec`, no permission dialog.
- **No self-start for ordinary apps.** The ROM delivers `BOOT_COMPLETED` to third-party receivers
  but refuses to start their processes for it. A JobScheduler **persisted** job is the one
  self-start path that survives, because the system's own JobSchedulerService pulls the process.
  Pilot arms one on first launch.
- **IP drift.** The TV broadcasts its address over UDP so a computer can find it after a DHCP change.

## Zero-PC onboarding

The guards need two grants that only the shell can hand out. Pilot does it without a computer:
the **Self-grant permissions** row starts adbd by itself, then drives the local ADB channel
(a ~150-line ADB client speaking the wire protocol directly) to write the accessibility grant
and `pm grant` the secure-settings permission. A floating card reports the result — success or
the raw failure reason — and the guard connects within seconds.

Note what this ROM denies: the `settings` command is refused to the *app* uid entirely, and
`settings get` is refused even to the shell uid (`INTERACT_ACROSS_USERS`). So Pilot reads the
enabled-services list from `dumpsys accessibility` (shell-readable) and writes through the shell
channel. Repair — when the ROM's cleaner strips the grant — uses the same path and re-arms
automatically at process start.

## The volume chain (no accessibility involved)

DBOS handles volume keys *below* every app layer — `interceptKeyBeforeQueueing` →
`dispatchDirectAudioEvent` → `AudioService` steps the volume before anything can filter it.
Three pieces make the steps usable anyway:

1. **Native ±1 steps**: `AudioService.adjustSuggestedStreamVolume` reads
   `persist.vendor.brand.simple` (factory 10, step ×5). Pilot sets it to 1 over the shell
   channel — a persistent property, so this survives reboots.
2. **Curve correction without key events**: key-event observers only ever see a *copy* of the
   press and race the native step (at the bottom edge the correction could land before the
   native step and the next press jumped two levels — a real, reproducible bug). Pilot instead
   listens to `VOLUME_CHANGED_ACTION`, the broadcast AudioService sends for every landed change:
   `last+1` means one curve level up from the last landed value, `last-1` one down, anything
   else is an external set and only displayed. Reacting to the *landed* value removes the race
   by construction; our own correction echoes back as another broadcast and is ignored. This
   works with the accessibility service off entirely.
3. **The display**: the stock OSD's scale cannot show single steps, so Pilot suppresses it —
   `service call audio 92` is `IAudioService.setVolumeController(null)` on this firmware, run
   over the shell channel where the shell holds `STATUS_BAR_SERVICE` — and draws the exact
   value in its own overlay. The overlay surface a third party may use here is the
   accessibility window type; without the guard connected a toast is used instead. SystemUI
   re-registers its controller at every boot, so the suppression is re-checked every 5 minutes;
   getting the stock bar back needs one reboot.

**Curve presets** (`volcurve`): `stock` (factory ±5, stock OSD), `fine100` (100 steps 1:1, zero
correction — the default), `step30` (30 linear steps), `perceptual` (40 steps on a quadratic
curve pinned at f(1)=1 and f(40)=100: one internal value per step at the quiet end, ~6 at the
top). All presets keep ≥20 steps and strict monotonicity, so no press is ever a no-op. Switching
presets re-maps the level only — the loudness you hear does not jump.

**Night gain**: below 1 there is only mute, so a `DynamicsProcessing` effect on the global output
mix attenuates 18/12/6 dB while the volume sits at 1/2/3. If the effect cannot attach, the
feature degrades to no attenuation, never to broken audio.

## Home guard

The stock launcher grabs the foreground at boot and after some of its own pages close; one-shot
takeovers get stolen back. The guard is an accessibility service subscribed to window events
plus a 4-second poll: when the stock launcher's *main* HOME activity comes to the front, it
launches the user's launcher again. Secondary stock pages (the built-in market and friends) pass
freely, and the target is resolved dynamically — **the launcher picker** defaults to "auto: first
third-party launcher" and accepts any installed HOME activity.

**Launch my launcher at boot** is the shell-free fallback: after boot, if the stock desktop is
still in front, it starts the user's launcher. Turn it on when a restart lands you on the stock
desktop.

## Block system updates

The update front-end (`com.xgimi.upgrade`) is a uid-1000 system package — untouchable, and
disabling it crashes `system_server` at boot. The safe lever is the A/B payload applier:
`update_engine` is a root init daemon and `ctl.stop` is idempotent and fully reversible. With
the switch on, Pilot keeps the engine stopped (re-checked every 5 minutes, so a reboot's
exposure window is short); off restores updates.

## HTTP console

Pilot serves port 8765 on the TV. `http://<tv-ip>:8765/ui` is the emergency WebUI for when the
app cannot be opened; state routes are open, action routes need the token as `?k=<token>`
(printed in the app and in `/whoami`).

| Route | What it does |
|---|---|
| `/whoami` | Status: IPs, adbd, guard, volume chain, preset, update engine |
| `/ui` | The WebUI page |
| `/config` | Read/write the switches; `?volcurve=<preset>` switches the curve |
| `/vol` | Volume read/set (internal 0–100 values) |
| `/adbstart` | `setprop ctl.start adbd` on demand |
| `/homeguard` | Guard repair / off |
| `/homefix` | Run the boot-launch fallback now |
| `/sh?c=<command>` | Run a shell command, return output |
| `/reset` | Restore Pilot's default configuration |

## Build

```
python3 examples/dangbei-x9-pro/pilot/build.py
```

Needs the Android SDK: build-tools 34+ and the android-34/35 platform jar; override with
SCOUT_BT and SCOUT_PLATFORM. No Gradle, no dependencies: aapt2, javac, d8, zipalign, apksigner.
Output lands in `out/` and `dist/`. A missing debug.keystore is generated on the fly; installing
over a copy signed with a different key needs an uninstall first.

## Safety notes

- The HTTP console is a remote shell on your TV, token-gated. On a trusted LAN that is the
  point; on a hostile network it is a hole. Turn what you do not need off.
- `ro.adb.secure=0` means no auth handshake for ADB — the firmware's doing, not Pilot's.
- Pilot never disables, uninstalls or hides anything by itself. It is transport and guard only;
  what you take off the TV is between you and [the lists](../../README.md).
- This software is for learning purposes only; please remove it within 24 hours.
