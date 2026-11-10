# Scout — the rescue channel for DBOS projectors

**This is not part of the debloat lists.** It lives beside them because every Dangbei DBOS firmware
shares one problem: there is no way in. No Developer options, no `com.android.settings`, no adbd at
boot — the ROM ships adbd fully configured (`ro.adb.secure=0`, `persist.adb.tcp.port=5555`) but never
starts it, and no stock screen lets you start it either. Until you have a shell, no list on
[examples/](../../README.md) can be applied at all.

Scout is that way in: a ~37 KB sideloaded APK, plain Java, no dependencies, no AndroidX, that runs
on the TV and opens the door from the inside. Copy [dist/DBOSScout.apk](dist/DBOSScout.apk) to a USB stick, install it
with the stock file manager, open it once — and ADB is up for the session. Everything else is
optional.

It was built for, and tested on, a Dangbei X9 Pro (DBOS 2.6, Android 14). The DBOS family shares the
launcher and the boot-control behaviour, so it should apply to other DBOS 2.x sets; nothing in it is
X9-Pro-specific except the settings-bridge table, which you extend per model.

## Why it exists

- **No entry point.** DBOS 2.6 has no Developer options; the old "install Android Settings, tap the
  build number" trick is dead (`com.android.settings` is absent). But the ROM lets *any installed
  app* run `setprop ctl.start adbd` — a plain `Runtime.exec`, no permission dialog. Scout is a
  one-tap wrapper around that line.
- **No self-start for ordinary apps.** The ROM delivers `BOOT_COMPLETED` to third-party receivers
  but refuses to start their processes for it. A JobScheduler **persisted** job is the one
  self-start path that survives, because the system's own JobSchedulerService pulls the process.
  Scout arms one on first launch.
- **IP drift.** TVs don't keep their DHCP address. Scout broadcasts its address over UDP so the
  computer finds the set without guessing.

## Install and first run

```
adb install dist/DBOSScout.apk        # or sideload from USB before ADB exists
adb shell am start -n com.dbos.scout/.MainActivity
```

Opening the app once:

1. starts the foreground service (the persistent notification is deliberate — it keeps the process
   alive and tells you it is running),
2. runs `setprop ctl.start adbd` (25 s after every boot, retried 5 times),
3. arms the persisted Job 778 self-continuing chain — each run reschedules the next, so at any
   moment the system holds an already-due job that fires soon after the next boot,
4. starts announcing itself on UDP so you can find the set after an IP change.

**Important:** the Job chain lives in `/data`. A firmware reflash wipes it — after any reflash,
install Scout and open it once again.

### Finding the TV

Scout answers `DBOS?` on UDP port 9999 and also broadcasts `DBOS6 {json}` to 255.255.255.255:9998
for the first three minutes after every boot:

```
python3 - <<'EOF'
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
s.bind(("", 9998))
print(s.recvfrom(65535))
EOF
```

### HTTP console

Scout serves an HTTP console on port 8765 of the TV. `http://<tv-ip>:8765/ui` is the WebUI (status
cards, feature switches, quick buttons, remote shell); state endpoints are open, action endpoints
need the token as `?k=<token>` (the token is printed in the app and in `/whoami`). Main routes:

| Route | What it does |
|---|---|
| `/whoami` | Status: IPs, adbd state, guard status, versions |
| `/ui` | The WebUI page |
| `/sh?c=<command>&k=…` | Run a shell command, return output |
| `/adbstart` | `setprop ctl.start adbd` on demand |
| `/config` | Read/write the feature switches (POST) |
| `/pkg` | Installed packages |
| `/vol` | Volume anchor for the fine-steps feature |

## The optional guards (each can be switched off)

All of this is off-able from the WebUI, the app's own remote-friendly settings panel, or `/config`.
Defaults are on, because the guards are why a headless set stays manageable:

- **adbd self-start** (`adbd_on`): the 25 s post-boot `ctl.start adbd` retry loop. Without it, ADB
  is off after every reboot until you open the app again.
- **Home guard** (`homeguard_on`): an accessibility service that watches for a *system* launcher's
  HOME activity coming to the front and switches back to the user-installed one — the DBOS launcher
  grabs focus at boot and after some of its own pages close. The target is resolved dynamically
  (the first non-system HOME candidate), never hardcoded, and only the stock launcher's main HOME
  activity is corrected; its own secondary pages (the built-in app market and friends) are allowed.
  Needs the accessibility grant below.
- **Volume fine-steps** (`finevol_on`): DBOS handles volume keys *below* the accessibility layer —
  `interceptKeyBeforeQueueing` steps the internal volume by 5 before any service can see the key,
  so no key filter can soften it. Scout counters instead: on each press it sets the stream volume
  to `anchor ± 1` and re-checks at 100 ms and 300 ms to press the ROM's ±5 back down to a net ±1.
  The lowest non-zero step drops from 5/100 to 1/100, which is the difference between "fine at
  night" and "not fine". The 100 ms window shows a brief ±5 flicker; that is the ROM landing first.
- **Announce** (`announce_on`): the UDP broadcasts above.
- **Bridge activity**: DBOS resolves no standard Android Settings intents, so any third-party
  launcher's Settings button dies with "Can't open that here". Scout registers a transparent
  activity for the standard intents and forwards them to Dangbei's real screens (settings →
  `com.dangbei.lerad.etna/.MainActivity`, the HDMI source picker → `com.xgimi.xhplayer/.InputActivity`
  on the X9 Pro). Extend the table in `BridgeActivity.java` for other models.

## Permissions to grant once (optional, for the guards)

```
adb shell settings put secure enabled_accessibility_services com.dbos.scout/.HomeGuardService
adb shell settings put secure accessibility_enabled 1
adb shell pm grant com.dbos.scout android.permission.WRITE_SECURE_SETTINGS
```

The last one lets Scout repair its own accessibility grant if the ROM clears it. All three are
optional: without them Scout still starts adbd and answers HTTP.

## Build

```
python3 examples/dangbei-x9-pro/scout/build.py
```

Needs the Android SDK: build-tools 34 or newer and the android-34 (or 35) platform jar; override
with SCOUT_BT and SCOUT_PLATFORM. No Gradle, no dependencies: aapt2, javac, d8, zipalign and
apksigner, mirroring the repo's reference-launcher philosophy of staying small. The output lands in
`out/` beside the script and in `dist/`. A missing out/debug.keystore is generated on the fly;
installing over a copy signed with a different key needs an uninstall first.

## Safety notes

- The HTTP console listens on `0.0.0.0:8765` with a token gate on action routes. That is a remote
  shell on your TV. On a trusted LAN, behind a firewall, this is the point; on a hostile network
  it is a hole. Turn the service off (`/config`, or the app panel) when you do not need it.
- `ro.adb.secure=0` means no auth handshake for ADB — that is the firmware's doing, not Scout's.
- Scout never disables, uninstalls or hides anything by itself. It is transport and guard only;
  what you take off the TV is between you and [the lists](../../README.md).
