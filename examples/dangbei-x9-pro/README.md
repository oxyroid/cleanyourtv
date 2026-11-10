# Dangbei X9 Pro projector

Dangbei X9 Pro (device `horse_popular`, MediaTek MT9681 / mt5877, 3 GB), Android 14, Dangbei's DBOS 2.6 UI on an
XGIMI GMUI base, firmware v6.9.15 (`Dangbei/Product_xiaopao/horse_popular:14/UQ1A.240105.004.A1/v6.9.15:user/test-keys`),
China market, no GMS: there is no Play Store and no Google package on the set. Dangbei's own apps live under
`com.dangbei.*` (the Lerad launcher family is `com.dangbei.leard.*` — the "leard" spelling is in the package name
itself), and the projector hardware stack is `com.xgimi.*`.

The debloat list is `debloat.txt`: nine packages, removed from user 0 on 2026-11-10 and checked across three
reboots the same day. Focus, keystone, HDMI input, casting and the Bluetooth remote kept working.

## ADB: there are no Developer options

The old Dangbei trick (install an "Android Settings" app, tap the build number) is dead on DBOS 2.6:
`com.android.settings` does not exist and the DBOS settings page unlocks nothing. But the ROM ships adbd
*configured* and never *started*: `ro.adb.secure=0` and `persist.adb.tcp.port=5555` are factory set, adbd just
is not running, and neither `persist` property makes it start at boot.

What works, and this is the one real hole in the firmware: **an ordinary sideloaded app is allowed to run
`setprop ctl.start adbd`** — no permission dialog, a plain `Runtime.exec` from any installed app does it. So:

1. Sideload any app that can execute a shell command on the TV and run `setprop ctl.start adbd` in it.
2. `adb connect <ip>:5555` from the computer. No pairing prompt; `ro.adb.secure` is 0.

After every reboot adbd is off again and the line has to be re-run. A third-party *app* cannot simply bring
itself back to do that either: on this ROM `BOOT_COMPLETED` is delivered to third-party receivers but the system
refuses to start their processes for it (`startProcessLocked failed` in the logs) — a JobScheduler persisted job
is the one self-start path that works, because the system's own JobSchedulerService pulls the process.

## Disabling does not survive a reboot

About three minutes into a cold boot the ROM restores the enabled state of `/vendor/priv-app` packages: every
`pm disable-user` of those comes back enabled at the next boot. Eight of the nine entries in the list sit in
`/vendor/priv-app`, so the list's Apply line (`tools/disable.sh examples/dangbei-x9-pro/debloat.txt`) is a
reversible way to try them for an evening, and what actually sticks is

```
adb shell pm uninstall --user 0 <package>
```

which keeps the APK on disk and removes the package only for user 0. The undo is
`adb shell cmd package install-existing <package>`, and a factory reset restores every user-0 removal as well.
`tools/restore.sh examples/dangbei-x9-pro/debloat.txt` is still the undo for the Apply line above.

## The uid 1000 crash

`com.xgimi.datareporter` and `com.xgimi.bugreportsender` share `android.uid.system`. On this ROM the shared-uid
line in `dumpsys package` reads `sharedUser=` rather than `userId=`, so a quick `grep userId` check misses them
— grep for both. Disabling `datareporter` took system_server down 45 seconds into every boot, with the set
auto-rebooting into the same crash each time (Wi-Fi and ADB stayed alive; they run below the app layer).
`cmd package install-existing com.xgimi.datareporter` fixed it. Nothing that reports `sharedUser=android.uid.system`
belongs in any list for this firmware — `com.dangbei.lerad.etna` is the same uid and also hosts the focus and
keystone overlay UI, so it is doubly untouchable.

## Home, keys and inputs

- Stock launcher: `com.dangbei.leard.leradlauncher`, whose `SplashActivity` answers HOME. `cmd package
  set-home-activity` accepted a third-party launcher on this unit (checked after reboots); the reference launcher
  itself was not tried on this set. Boot lands on the stock launcher's splash and stays there until a HOME press,
  so a home-key redirect helper is what covers the gap on this ROM.
- No tuner: no Live TV app exists. The HDMI source UI is `com.xgimi.xhplayer/.InputActivity`, reachable by an
  explicit intent (verified); the stock `VIEW` on the passthrough channel URI does nothing here, as on the retail
  XGIMI sets.
- The ROM resolves no standard Settings intents (`ACTION_SETTINGS`, `ACTION_WIFI_SETTINGS`): third-party launchers
  need a bridge to Dangbei's real settings (`com.dangbei.lerad.etna/.MainActivity`) or their settings button dies
  with "Can't open that here".
- Volume keys are handled by the ROM *below* the accessibility layer: `interceptKeyBeforeQueueing`
  → `dispatchDirectAudioEvent` → `AudioService`, five internal steps per press, so no key-filtering
  service can soften them. But the ×5 is a ROM branch, not hardware: `adjustSuggestedStreamVolume`
  multiplies the step by 5 when `persist.vendor.brand.simple != 1` (factory value 10). Setting the
  property to 1 — from the shell, once, it survives reboots — makes the keys natively step ±1.
  The stock volume OSD (`VolumeDialog`, registered by the XGIMI SystemUI) has no settings toggle;
  it can only be silenced by unregistering it (`IAudioService.setVolumeController(null)`,
  transaction 92 in this firmware), which needs the shell-held `STATUS_BAR_SERVICE`. Scout's
  local-ADB channel does both; see [scout/README.md](scout/README.md).

## App store

The launcher's own "应用市场" is not an app: it is the Lerad launcher's `AppSecondaryActivity`, opened by
`VIEW db://applist`. On this firmware its download/install chain is dead server-side — the installer process runs,
but the market never creates an install session and DownloadManager stays empty, so installs hang forever. The
standalone Dangbei Market 6.0.6 (`com.dangbeimarket`) works fine; its APK is not published anywhere official, the
direct download link `app.znds.com/update/dbmarket.apk` still answers.

## The two apps this set runs

Both live under this folder, each with its own README, and neither is part of the debloat list:
they are the way in and the home screen, not something the list removes.

- [scout/](scout/README.md) — **DBOS Scout**, the rescue channel. A ~37 KB sideloaded APK whose only
  irreplaceable job is running `setprop ctl.start adbd` from inside the TV, plus a persisted-job
  self-start chain, a UDP presence broadcast, an HTTP console, a home guard and a volume fine-steps
  counter. Without it, ADB is a one-shot: `pm disable-user` a package and reboot, and you have no way
  back to the shell unless you walk to the TV with a USB stick again. Prebuilt:
  `examples/dangbei-x9-pro/scout/dist/DBOSScout.apk`.
- [kido/](kido/DBOS.md) — **the Kido launcher fork** this set uses as Home, with the DBOS-specific
  changes (dual LEANBACK+LAUNCHER scan, the package-visibility `<queries>` fix, explicit launches,
  the Lerad launcher on the hidden list) and the build traps. Prebuilt:
  `examples/dangbei-x9-pro/kido/dist/kido-dbos.apk`. It is a git submodule on the fork's `dbos`
  branch, so upstream Kido updates land with a `git fetch upstream && git merge upstream/main`
  inside it (see [kido/DBOS.md](kido/DBOS.md)); clone this repository with
  `--recurse-submodules` to get it.

## If it will not boot

Two ways back, both tested on this unit:

- **Recovery factory reset** — restores every disabled and user-0-removed package to the factory state.
- **U-disk rescue flash**: FAT32 (MBR) stick, the firmware image alone in the root, stick in one of the two USB
  ports (on this unit only one of them is seen), power cable out, hold the projector's power button, plug the
  power back in while holding, release when the LED blinks. The rescue screen checks for the update package and
  flashes it; it also boots normally when it finds nothing. The X9 Pro image is not published anywhere — Dangbei
  support (400-000-3505) sends it on request. The flash wipes `/data`: every sideloaded app is gone afterwards.
