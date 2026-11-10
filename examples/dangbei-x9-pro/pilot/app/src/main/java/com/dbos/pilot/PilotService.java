package com.dbos.pilot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * DBOS Pilot resident service.
 *
 *  - Foreground service, independent of any activity's lifecycle.
 *  - HTTP console on :8765 (token-gated): the emergency WebUI plus the
 *    state/config API used by the app itself.
 *  - UDP presence on :9999 ("DBOS?" → identity JSON) and the startup broadcast.
 *  - Maintenance loop (fast at boot, every 5 minutes when settled):
 *      · adbd up (the ROM ships it configured but never started),
 *      · native volume steps (persist.vendor.brand.simple=1),
 *      · stock volume bar suppressed (setVolumeController(null), transaction
 *        92 on this firmware),
 *      · logd up when the user opted in (the ROM ships it stopped),
 *      · the A/B update engine stopped when OTA blocking is on,
 *      · the perceptual curve's master-volume stage re-pinned.
 *    Everything needing a shell identity goes through LocalAdb.
 */
public class PilotService extends Service {

    public static final String TAG = "DBOSPilot";
    public static final int HTTP_PORT = 8765;
    public static final int UDP_PORT = 9999;
    public static final int ANN_PORT = 9998;
    public static final String TOKEN = "S9x7Kp2mQw4";
    public static final String VER = "18.0";
    public static final int NOTIF_ID = 1001;

    /** Maintenance-loop actions invoked from the settings panels via intents. */
    public static final String ACT_APPLY_VOLUME = "apply_volume";
    public static final String ACT_REPAIR_GUARD = "repair_guard";
    public static final String ACT_GUARD_OFF   = "guard_off";
    public static final String ACT_HOME_FIX    = "home_fix";

    public static volatile String lastStatus = "init";
    public static volatile String adbdState = "?";
    public static volatile String volNativeState = "init";
    public static volatile String otaState = "init";

    /** The live service instance (the settings UI reads config through it). */
    static volatile PilotService instance;

    private ExecutorService pool;
    private volatile boolean running = true;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        startForegroundSafe();
        pool = Executors.newFixedThreadPool(8);
        startHttp();
        startUdp();
        scheduleKeepJob();
        // Volume curve correction: a plain VOLUME_CHANGED_ACTION receiver,
        // independent of the accessibility service.
        VolCurve.install(this);

        // Start adbd once system_server is up (switch-controlled, retried).
        if (Config.get(this, Config.Key.ADBD)) {
            pool.execute(new Runnable() {
                @Override public void run() {
                    try { Thread.sleep(25000); } catch (InterruptedException ignored) {}
                    ensureAdbd(0);
                }
            });
        }
        // Broadcast the address for the first minutes (IP drift).
        if (Config.get(this, Config.Key.ANNOUNCE)) {
            pool.execute(new Runnable() {
                @Override public void run() { announceLoop(); }
            });
        }
        // Boot home fix: the stock launcher's splash answers HOME and stays
        // after boot; the accessibility guard is the main line, this is the
        // fallback. Both resolve the target dynamically.
        if (Config.get(this, Config.Key.HOMEFIX)) {
            pool.execute(new Runnable() {
                @Override public void run() {
                    sleep(45000);
                    ensureHome(0);
                }
            });
        }
        // Guard self-repair: force-stop (and the vendor's cleaner) can strip
        // the accessibility grant; once the Job chain revives the process,
        // this puts the grant back.
        if (Config.get(this, Config.Key.HOMEGUARD)) {
            pool.execute(new Runnable() {
                @Override public void run() {
                    sleep(8000);
                    ensureHomeGuard(0);
                }
            });
        }
        // Maintenance: volume chain, logd, OTA block, master-volume re-pin.
        pool.execute(new Runnable() {
            @Override public void run() { maintainLoop(0); }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            final String act = intent.getAction();
            pool.execute(new Runnable() {
                @Override public void run() {
                    if (ACT_APPLY_VOLUME.equals(act)) {
                        maintainLoop(6);
                    } else if (ACT_REPAIR_GUARD.equals(act)) {
                        ensureHomeGuard(0);
                    } else if (ACT_GUARD_OFF.equals(act)) {
                        disableHomeGuard(PilotService.this);
                    } else if (ACT_HOME_FIX.equals(act)) {
                        ensureHome(0);
                    }
                }
            });
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        if (pool != null) pool.shutdownNow();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------------- foreground notification ----------------

    private void startForegroundSafe() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(
                    "pilot", getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, "pilot");
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(android.R.drawable.stat_notify_sync)
         .setContentTitle(getString(R.string.notif_title))
         .setContentText(getString(R.string.notif_text, HTTP_PORT, UDP_PORT))
         .setOngoing(true);
        startForeground(NOTIF_ID, b.build());
    }

    // ---------------- adbd ----------------

    /** Idempotently start adbd; backs off and retries (up to 5 rounds). */
    public void ensureAdbd(int attempt) {
        String st = sh("getprop init.svc.adbd").trim();
        adbdState = st.isEmpty() ? "(empty)" : st;
        if ("running".equals(st)) {
            lastStatus = "adbd running";
            return;
        }
        sh("setprop ctl.start adbd");
        sleep(1800);
        st = sh("getprop init.svc.adbd").trim();
        adbdState = st.isEmpty() ? "(empty)" : st;
        if ("running".equals(st)) {
            lastStatus = "adbd started (attempt " + attempt + ")";
            Log.i(TAG, lastStatus);
            return;
        }
        lastStatus = "adbd start failed (attempt " + attempt + ", state=" + adbdState + ")";
        if (attempt < 5) {
            sleep(4000);
            ensureAdbd(attempt + 1);
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // ---------------- maintenance loop ----------------
    //
    // Volume chain:
    //  1) native single steps: persist.vendor.brand.simple=1 (factory value
    //     10 → step ×5). A ROM branch in AudioService, verified by
    //     disassembly; persist, survives reboots.
    //  2) stock bar suppression: service call audio 92 =
    //     IAudioService.setVolumeController(null) — the transaction code read
    //     from this firmware's IAudioService$Stub; the shell holds
    //     STATUS_BAR_SERVICE. SystemUI re-registers at every *boot* (not on
    //     restart — tested), so this is re-checked and re-cut here. The checks
    //     must read the controller through the shell channel: the app's own
    //     uid gets an empty dumpsys under this ROM's SELinux policy.
    //
    // logd: ships stopped; brought up when the user opts in.
    //
    // OTA: the update front-end (com.xgimi.upgrade) is a uid-1000 system
    // package — untouchable, disabling it crashes system_server at boot. The
    // A/B payload applier (update_engine, a root init daemon) is the safe
    // lever: ctl.stop is idempotent and fully reversible (ctl.start restores
    // it). Re-applied here each round, so a reboot's window is short.

    private void maintainLoop(int attempt) {
        if (!running) return;
        try {
            // Channel precondition: adbd must be up for anything below.
            String st = sh("getprop init.svc.adbd").trim();
            if (!"running".equals(st)) ensureAdbd(0);

            boolean fine = Config.get(this, Config.Key.FINEVOL);
            boolean osd  = Config.get(this, Config.Key.VOLOSD);

            // 1) native step size
            String simple = sh("getprop persist.vendor.brand.simple").trim();
            if (fine && !"1".equals(simple)) {
                String r = LocalAdb.shell("setprop persist.vendor.brand.simple 1");
                volNativeState = "brand.simple->1 " + (r.isEmpty() ? "ok" : r);
            } else if (!fine && !"10".equals(simple)) {
                String r = LocalAdb.shell("setprop persist.vendor.brand.simple 10");
                volNativeState = "brand.simple->10 " + (r.isEmpty() ? "ok" : r);
            }

            // 2) stock volume bar
            if (osd) {
                String ctrl = LocalAdb.shell("dumpsys audio | grep mVolumeController");
                if (!ctrl.contains("(null")) {
                    String r = LocalAdb.shell("service call audio 92");
                    volNativeState += " | volosd " + (r.contains("00000000") ? "cut" : r);
                }
            } else if (attempt == 0) {
                volNativeState += " | volosd off (stock bar returns after a reboot)";
            }

            // 3) logd
            if (Config.get(this, Config.Key.LOGD)) {
                String logd = sh("getprop init.svc.logd").trim();
                if (!"running".equals(logd)) {
                    LocalAdb.shell("setprop ctl.start logd");
                }
            }

            // 4) OTA block: keep the update engine stopped.
            if (Config.get(this, Config.Key.OTABLOCK)) {
                String eng = sh("getprop init.svc.update_engine").trim();
                if (!"stopped".equals(eng)) {
                    String r = LocalAdb.shell("setprop ctl.stop update_engine");
                    otaState = "update_engine stopped" + (r.isEmpty() ? "" : " " + r);
                }
            } else if (attempt == 0) {
                String eng = sh("getprop init.svc.update_engine").trim();
                if ("stopped".equals(eng)) {
                    LocalAdb.shell("setprop ctl.start update_engine");
                    otaState = "update_engine restored";
                } else {
                    otaState = "update_engine " + eng;
                }
            }

            // 5) master-volume re-pin: heals the audioserver resetting the
            //    master stage to 1.0 behind our back (restart), and restores
            //    the saved level's rendering after a reboot.
            VolCurve.repin(this, true);
        } catch (Throwable t) {
            volNativeState = "maintain err: " + t;
        }
        if (attempt < 8) {
            sleep(attempt < 3 ? 20000 : 60000);
            maintainLoop(attempt + 1);
        } else {
            sleep(5 * 60 * 1000);
            maintainLoop(6);      // steady state: every 5 minutes
        }
    }

    // ---------------- night gain ----------------
    // (removed with the preset radio: the perceptual curve's master-volume
    // stage covers the sub-floor range — see BetterAudio / VolCurve)

    // ---------------- boot home fix (fallback layer) ----------------

    /** Launch the user's launcher if it is not in front. */
    public void ensureHome(int attempt) {
        android.content.ComponentName home = HomeLauncher.resolveUserHome(this);
        if (home == null) { lastStatus = "no launcher installed"; return; }
        String top = sh("dumpsys activity activities | grep topResumedActivity");
        if (top.contains(home.getPackageName())) {
            lastStatus = "home ok (attempt " + attempt + ")";
            return;
        }
        sh("am start -n " + home.flattenToShortString());
        sleep(5000);
        top = sh("dumpsys activity activities | grep topResumedActivity");
        if (top.contains(home.getPackageName())) {
            lastStatus = "home fixed (attempt " + attempt + ")";
            Log.i(TAG, lastStatus);
            return;
        }
        if (attempt < 8) {
            sleep(15000);
            ensureHome(attempt + 1);
        } else {
            lastStatus = "home fix failed; top=" + top.trim();
        }
    }

    // ---------------- accessibility grant self-repair ----------------

    private static final String GUARD_SVC = "com.dbos.pilot/.HomeGuardService";

    /**
     * Read the enabled accessibility services. This ROM denies
     * `settings get secure enabled_accessibility_services` for both the
     * app uid and the shell uid (INTERACT_ACROSS_USERS), but
     * `dumpsys accessibility` prints the same list — read it there.
     * Sections look like `Enabled services:{com.foo/.Bar,com.baz/.Qux}`.
     */
    static java.util.List<String> enabledServicesViaShell() {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        String d = LocalAdb.shell("dumpsys accessibility");
        for (String line : d.split("\n")) {
            int i = line.indexOf("Enabled services:");
            if (i < 0) continue;
            String body = line.substring(i + "Enabled services:".length()).trim();
            if (body.startsWith("{") && body.endsWith("}")) {
                body = body.substring(1, body.length() - 1);
            } else {
                continue;   // not the set-printing line
            }
            if (body.isEmpty()) continue;
            for (String s : body.split(",")) {
                s = s.trim().replace("[", "").replace("]", "");
                if (!s.isEmpty() && !out.contains(s)) out.add(s);
            }
        }
        return out;
    }

    /** Merge the guard into the enabled list and enable accessibility (shell). */
    private static boolean grantGuardViaShell() {
        StringBuilder val = new StringBuilder();
        for (String s : enabledServicesViaShell()) {
            if (s.contains("HomeGuardService")) continue;
            if (val.length() > 0) val.append(':');
            val.append(s);
        }
        if (val.length() > 0) val.append(':');
        val.append(GUARD_SVC);
        String r1 = LocalAdb.shell("settings put secure enabled_accessibility_services "
                + shellQuote(val.toString()));
        String r2 = LocalAdb.shell("settings put secure accessibility_enabled 1");
        return r1.isEmpty() && r2.isEmpty();
    }

    /** Cleanly remove the guard from the enabled-services list (system unbinds it). */
    static void disableHomeGuard(Context ctx) {
        StringBuilder val = new StringBuilder();
        for (String s : enabledServicesViaShell()) {
            if (s.contains("HomeGuardService")) continue;
            if (val.length() > 0) val.append(':');
            val.append(s);
        }
        LocalAdb.shell("settings put secure enabled_accessibility_services " + shellQuote(val.toString()));
        LocalAdb.shell("settings put secure accessibility_enabled "
                + (val.length() == 0 ? 0 : 1));
    }

    /** Stateless shell helper for code paths without a service instance. */
    static String shStatic(String cmd) {
        Process proc = null;
        try {
            proc = new ProcessBuilder("/system/bin/sh", "-c", cmd)
                    .redirectErrorStream(true).start();
            java.io.InputStream is = proc.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = is.read(b)) > 0) bos.write(b, 0, n);
            proc.waitFor();
            return bos.toString("UTF-8").trim();
        } catch (Exception e) {
            return "";
        } finally {
            if (proc != null) try { proc.destroy(); } catch (Exception ignored) {}
        }
    }

    /** Repair the guard's grant: keep other services, re-add this one, rebind. */
    private void ensureHomeGuard(int attempt) {
        if (HomeGuardService.connected) {
            lastStatus = "homeguard connected";
            return;
        }
        // The repair writes through the shell channel: this ROM denies
        // `settings` to the app uid entirely.
        ensureAdbd(0);
        Log.i(TAG, "homeguard repair attempt " + attempt);
        boolean ok = grantGuardViaShell();
        sleep(4000);
        if (HomeGuardService.connected) {
            lastStatus = "homeguard repaired";
            Log.i(TAG, "homeguard repaired (attempt " + attempt + ")");
            return;
        }
        if (attempt < 2) {
            sleep(6000);
            ensureHomeGuard(attempt + 1);
        } else {
            lastStatus = "homeguard repair failed" + (ok ? " (grant written, no connect)"
                    : " (shell write failed)");
            Log.e(TAG, lastStatus);
        }
    }

    // ---------------- self-service grant (no-PC onboarding) ----------------
    //
    // The delivered app has to arm itself without a PC: accessibility grants
    // and WRITE_SECURE_SETTINGS both come from the shell, and the app has its
    // own shell channel (LocalAdb). Flow: start adbd (the ROM lets any app do
    // that), then, as shell: merge this service into the enabled list, set
    // accessibility_enabled, and pm grant the secure-settings permission.

    public static String selfGrant(Context ctx) {
        try {
            // The app uid may run setprop ctl.start adbd directly (factory hole).
            Process p = new ProcessBuilder("/system/bin/sh", "-c",
                    "setprop ctl.start adbd").redirectErrorStream(true).start();
            try { p.waitFor(); } catch (Throwable ignored) {}
            Thread.sleep(2500);
            // Grants go through the shell channel: enabled-services merge +
            // accessibility master switch + the secure-settings permission.
            // (Reading the current list uses dumpsys — see
            // enabledServicesViaShell; `settings get` is denied even for shell.)
            boolean ok = grantGuardViaShell();
            String r3 = LocalAdb.shell("pm grant com.dbos.pilot android.permission.WRITE_SECURE_SETTINGS");
            return ok && r3.isEmpty() ? "granted" : "grant err (list="
                    + enabledServicesViaShell() + " r3=" + r3 + ")";
        } catch (Throwable t) {
            return "err: " + t;
        }
    }

    /** Quote a value for the adb shell line (component lists contain dots, slashes, colons). */
    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // ---------------- HTTP console ----------------
    //
    // Kept deliberately small: the emergency WebUI (state, switches, rescue
    // buttons, shell) for when the app cannot be opened, and the same config
    // API the settings panels use. Forensics routes (activity/package
    // listings) are gone — the shell route covers them.

    private void startHttp() {
        pool.execute(new Runnable() {
            @Override public void run() {
                ServerSocket ss = null;
                try {
                    ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress(HTTP_PORT));
                    lastStatus = "http :" + HTTP_PORT + " up";
                    while (running) {
                        final Socket client = ss.accept();
                        pool.execute(new Runnable() {
                            @Override public void run() { serve(client); }
                        });
                    }
                } catch (Exception e) {
                    lastStatus = "http err: " + e;
                    Log.e(TAG, "http server", e);
                } finally {
                    try { if (ss != null) ss.close(); } catch (Exception ignored) {}
                }
            }
        });
    }

    private void serve(Socket client) {
        try {
            client.setSoTimeout(6000);
            InputStream in = client.getInputStream();
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') break;
                if (c != '\r') line.append((char) c);
            }
            // Drain the remaining request headers (CRLFCRLF).
            int seen = 0;
            while ((c = in.read()) != -1) {
                if ((seen == 0 && c == '\r') || (seen == 1 && c == '\n')
                        || (seen == 2 && c == '\r') || (seen == 3 && c == '\n')) {
                    seen++;
                    if (seen == 4) break;
                } else if (seen == 2 && c == '\n') {
                    break;
                } else {
                    seen = (c == '\r') ? 1 : 0;
                }
            }

            String[] parts = line.toString().split(" ");
            String pathQ = parts.length > 1 ? parts[1] : "/";
            int qm = pathQ.indexOf('?');
            String path = qm < 0 ? pathQ : pathQ.substring(0, qm);
            Map<String, String> params = parseQuery(qm < 0 ? "" : pathQ.substring(qm + 1));

            if ("/ui".equals(path)) {
                byte[] page = readRaw(R.raw.webui);
                String head = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/html; charset=utf-8\r\n"
                        + "Cache-Control: no-store\r\n"
                        + "Content-Length: " + page.length + "\r\n"
                        + "Connection: close\r\n\r\n";
                OutputStream out = client.getOutputStream();
                out.write(head.getBytes("UTF-8"));
                out.write(page);
                out.flush();
                client.close();
                return;
            }

            String body;
            if (!TOKEN.equals(params.get("k"))) {
                body = "{\"error\":\"forbidden\"}";
            } else {
                body = route(path, params);
            }

            byte[] payload = body.getBytes("UTF-8");
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "Content-Length: " + payload.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = client.getOutputStream();
            out.write(head.getBytes("UTF-8"));
            out.write(payload);
            out.flush();
            client.close();
        } catch (Exception ignored) {
            try { client.close(); } catch (Exception ignored2) {}
        }
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<String, String>();
        if (q == null || q.isEmpty()) return m;
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            try {
                if (eq < 0) {
                    m.put(URLDecoder.decode(pair, "UTF-8"), "");
                } else {
                    m.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                          URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
                }
            } catch (Exception ignored) {}
        }
        return m;
    }

    private String route(String path, Map<String, String> p) {
        if ("/".equals(path) || "/data".equals(path) || "/whoami".equals(path)) {
            return whoami();
        }
        if ("/config".equals(path)) {
            for (String k : new String[] {
                    Config.Key.HOMEGUARD, Config.Key.HOMEFIX, Config.Key.ADBD,
                    Config.Key.ANNOUNCE, Config.Key.FINEVOL, Config.Key.VOLOSD,
                    Config.Key.LOGD, Config.Key.OTABLOCK}) {
                if (p.containsKey(k))
                    Config.set(this, k, !"0".equals(p.get(k)));
            }
            boolean curveChanged = false;
            if (p.containsKey("volcurve")) {
                String v = p.get("volcurve");
                if (BetterAudio.isValid(v)) {
                    Config.applyPreset(this, v);
                    curveChanged = true;
                }
            }
            if (p.containsKey(Config.Key.HOMETARGET)) {
                Config.setStr(this, Config.Key.HOMETARGET, p.get(Config.Key.HOMETARGET));
            }
            // Volume-chain changes take effect immediately, not next round.
            if (curveChanged || p.containsKey(Config.Key.FINEVOL) || p.containsKey(Config.Key.VOLOSD)) {
                pool.execute(new Runnable() {
                    @Override public void run() { maintainLoop(6); }
                });
            }
            return Config.toJson(this);
        }
        if ("/reset".equals(path)) {
            Config.reset(this);
            pool.execute(new Runnable() {
                @Override public void run() { maintainLoop(6); }
            });
            return Config.toJson(this);
        }
        if ("/vol".equals(path)) {
            android.media.AudioManager am = (android.media.AudioManager)
                    getSystemService(AUDIO_SERVICE);
            int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            String i = p.get("i");
            if (i != null && !i.isEmpty()) {
                int next = cur;
                if (i.startsWith("+")) next = cur + Integer.parseInt(i.substring(1));
                else if (i.startsWith("-")) next = cur - Integer.parseInt(i.substring(1));
                else next = Integer.parseInt(i);
                int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
                if (next < 0) next = 0;
                if (next > max) next = max;
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, next, 0);
                cur = next;
            }
            return "{\"vol\":" + cur + ",\"max\":"
                    + am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) + "}";
        }
        if ("/adbstart".equals(path)) {
            ensureAdbd(0);
            return "{\"ok\":true,\"adbd\":" + js(sh("getprop init.svc.adbd").trim()) + "}";
        }
        if ("/homefix".equals(path)) {
            pool.execute(new Runnable() {
                @Override public void run() { ensureHome(0); }
            });
            return "{\"ok\":true,\"started\":\"ensureHome\"}";
        }
        if ("/homeguard".equals(path)) {
            final String action = p.get("action");
            pool.execute(new Runnable() {
                @Override public void run() {
                    if ("off".equals(action)) {
                        disableHomeGuard(PilotService.this);
                    } else {
                        ensureHomeGuard(0);
                    }
                }
            });
            return "{\"ok\":true,\"action\":" + js(action == null ? "repair" : action) + "}";
        }
        if ("/sh".equals(path)) {
            String cmd = p.get("c");
            if (cmd == null || cmd.isEmpty()) return "{\"error\":\"missing c\"}";
            return "{\"cmd\":" + js(cmd) + ",\"out\":" + js(sh(cmd)) + "}";
        }
        return "{\"error\":\"unknown path " + esc(path) + "\"}";
    }

    private String whoami() {
        List<String> ips = localIPv4();
        String primary = ips.isEmpty() ? "" : ips.get(0);
        StringBuilder r = new StringBuilder("{");
        r.append("\"app\":\"DBOSPilot\",\"ver\":").append(js(VER));
        r.append(",\"http\":").append(HTTP_PORT);
        r.append(",\"udp\":").append(UDP_PORT);
        r.append(",\"ip\":").append(js(primary));
        r.append(",\"ips\":[");
        for (int i = 0; i < ips.size(); i++) {
            r.append(js(ips.get(i)));
            if (i < ips.size() - 1) r.append(',');
        }
        r.append("],\"model\":").append(js(Build.MODEL));
        r.append(",\"device\":").append(js(Build.DEVICE));
        r.append(",\"sdk\":").append(Build.VERSION.SDK_INT);
        r.append(",\"android\":").append(js(Build.VERSION.RELEASE));
        r.append(",\"adbd\":").append(js(sh("getprop init.svc.adbd").trim()));
        r.append(",\"adbPort\":").append(js(sh("getprop service.adb.tcp.port").trim()));
        r.append(",\"status\":").append(js(lastStatus));
        r.append(",\"guard\":").append(HomeGuardService.connected);
        r.append(",\"guardStatus\":").append(js(HomeGuardService.status));
        r.append(",\"guardTarget\":").append(js(HomeGuardService.target));
        r.append(",\"volNative\":").append(js(volNativeState));
        r.append(",\"brandSimple\":").append(js(sh("getprop persist.vendor.brand.simple").trim()));
        r.append(",\"volCurve\":").append(js(Config.getStr(this, Config.Key.VOLCURVE, BetterAudio.PERCEPTUAL)));
        r.append(",\"curveSteps\":").append(BetterAudio.get(
                Config.getStr(this, Config.Key.VOLCURVE, BetterAudio.PERCEPTUAL)).steps);
        r.append(",\"homeTarget\":").append(js(Config.getStr(this, Config.Key.HOMETARGET, HomeLauncher.AUTO)));
        r.append(",\"logd\":").append(js(sh("getprop init.svc.logd").trim()));
        r.append(",\"ota\":").append(js(otaState));
        r.append(",\"updateEngine\":").append(js(sh("getprop init.svc.update_engine").trim()));
        r.append(",\"wakelock\":").append(wakeLock != null && wakeLock.isHeld());
        r.append(",\"uptime\":").append(js(sh("cat /proc/uptime").trim()));
        r.append('}');
        return r.toString();
    }

    public static List<String> localIPv4() {
        List<String> out = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        out.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {}
        Collections.sort(out);
        return out;
    }

    // ---------------- self-healing: JobScheduler persisted chain ----------------
    //
    // The ROM refuses to start third-party processes for BOOT_COMPLETED
    // (startProcessLocked failed). A persisted JobScheduler job is the one
    // self-start path that survives: the system's own JobSchedulerService
    // pulls the process.
    //
    // Job 777: 15-minute periodic heartbeat.
    // Job 778: a one-shot persisted job with a 90-second deadline; every run
    //           re-arms the next, so at any power-off moment the system holds
    //           an already-due job that fires soon after the next boot.

    private void scheduleKeepJob() {
        try {
            android.app.job.JobScheduler js =
                    (android.app.job.JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE);
            if (js == null) { lastStatus = "job: no scheduler"; return; }
            if (js.getPendingJob(777) == null) {
                js.schedule(new android.app.job.JobInfo.Builder(777,
                        new android.content.ComponentName(this, KeepJobService.class))
                        .setPersisted(true)
                        .setPeriodic(15L * 60 * 1000)
                        .setRequiresCharging(false)
                        .setRequiresBatteryNotLow(false)
                        .setRequiresDeviceIdle(false)
                        .build());
            }
            armBootTrigger(js);
            Log.i(TAG, "keep jobs armed (777 periodic + 778 boot-trigger chain)");
        } catch (Throwable t) {
            lastStatus = "job err: " + t;
            Log.e(TAG, "scheduleKeepJob", t);
        }
    }

    /** Arm a short-deadline persisted one-shot; an expired deadline fires at boot. */
    static void armBootTrigger(android.app.job.JobScheduler js) {
        js.cancel(778);
        js.schedule(new android.app.job.JobInfo.Builder(778,
                new android.content.ComponentName("com.dbos.pilot",
                        "com.dbos.pilot.KeepJobService"))
                .setPersisted(true)
                .setMinimumLatency(60L * 1000)
                .setOverrideDeadline(90L * 1000)
                .setRequiresCharging(false)
                .setRequiresBatteryNotLow(false)
                .setRequiresDeviceIdle(false)
                .build());
    }

    // ---------------- presence broadcast ----------------

    /** Broadcast the address for the first minutes after boot (IP drift). */
    private void announceLoop() {
        for (int i = 0; i < 12 && running; i++) {
            try {
                List<String> ips = localIPv4();
                if (!ips.isEmpty()) {
                    String msg = "DBOS6 " + whoami();
                    byte[] data = msg.getBytes("UTF-8");
                    DatagramSocket ds = new DatagramSocket();
                    ds.setBroadcast(true);
                    ds.send(new DatagramPacket(data, data.length,
                            InetAddress.getByName("255.255.255.255"), ANN_PORT));
                    ds.close();
                    if (i == 0) Log.i(TAG, "announced to :" + ANN_PORT);
                }
            } catch (Throwable ignored) {}
            sleep(15000);
        }
    }

    // ---------------- UDP discovery ----------------

    private void startUdp() {
        pool.execute(new Runnable() {
            @Override public void run() {
                DatagramSocket ds = null;
                try {
                    ds = new DatagramSocket(null);
                    ds.setReuseAddress(true);
                    ds.bind(new InetSocketAddress(UDP_PORT));
                    byte[] buf = new byte[256];
                    while (running) {
                        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                        ds.receive(pkt);
                        String msg = new String(pkt.getData(), 0, pkt.getLength()).trim();
                        if (msg.startsWith("DBOS")) {
                            byte[] r = whoami().getBytes("UTF-8");
                            ds.send(new DatagramPacket(r, r.length,
                                    pkt.getAddress(), pkt.getPort()));
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "udp", e);
                } finally {
                    try { if (ds != null) ds.close(); } catch (Exception ignored) {}
                }
            }
        });
    }

    // ---------------- embedded resources ----------------

    private byte[] readRaw(int resId) {
        InputStream is = null;
        try {
            is = getResources().openRawResource(resId);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = is.read(b)) > 0) bos.write(b, 0, n);
            return bos.toByteArray();
        } catch (Exception e) {
            try { return ("webui load err: " + e).getBytes("UTF-8"); }
            catch (Exception ignored) { return new byte[0]; }
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignored) {}
        }
    }

    // ---------------- shell (deadlock-safe, with timeout) ----------------

    public String sh(String cmd) {
        Process proc = null;
        try {
            proc = new ProcessBuilder("/system/bin/sh", "-c", cmd)
                    .redirectErrorStream(true)
                    .start();
            final InputStream is = proc.getInputStream();
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            Thread reader = new Thread(new Runnable() {
                @Override public void run() {
                    byte[] b = new byte[8192];
                    int n;
                    try {
                        while ((n = is.read(b)) > 0) bos.write(b, 0, n);
                    } catch (Exception ignored) {}
                }
            });
            reader.start();
            if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                proc.destroy();
                reader.join(800);
                return "[TIMEOUT] " + bos.toString("UTF-8").trim();
            }
            reader.join(1200);
            return bos.toString("UTF-8").trim();
        } catch (Exception e) {
            return "EXC: " + e;
        } finally {
            if (proc != null) try { proc.destroy(); } catch (Exception ignored) {}
        }
    }

    // ---------------- JSON helpers ----------------

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }

    static String js(String s) { return "\"" + esc(s) + "\""; }
}
