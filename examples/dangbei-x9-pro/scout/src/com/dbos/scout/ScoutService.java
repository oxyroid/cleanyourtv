package com.dbos.scout;

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
 * DBOS Scout v5 常驻服务
 *  - 前台服务，脱离 Activity 生命周期
 *  - HTTP 控制台 :8765（token 校验）
 *  - UDP 发现 :9999（发 "DBOS?" 就回自己的 IP，解决 IP 漂移）
 *  - 开机自启时自动 setprop ctl.start adbd 并重试
 */
public class ScoutService extends Service {

    public static final String TAG = "DBOSScout";
    public static final int HTTP_PORT = 8765;
    public static final int UDP_PORT = 9999;
    public static final int ANN_PORT = 9998;   // 主动广播端口（Mac 侧监听这里）
    public static final String TOKEN = "S9x7Kp2mQw4";
    public static final String VER = "15.0";
    public static final int NOTIF_ID = 1001;

    public static volatile String lastStatus = "init";
    public static volatile String adbdState = "?";

    private ExecutorService pool;
    private volatile boolean running = true;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundSafe();
        pool = Executors.newFixedThreadPool(8);
        startHttp();
        startUdp();
        scheduleKeepJob();
        // 开机等 system_server 起来再拉 adbd，多次重试（受"开机自动ADB"开关控制）
        if (Config.get(this, Config.Key.ADBD)) {
        pool.execute(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(25000); } catch (InterruptedException ignored) {}
                ensureAdbd(0);
            }
        });
        }
        // 主动广播自己的 IP（受"IP广播"开关控制）
        if (Config.get(this, Config.Key.ANNOUNCE)) {
        pool.execute(new Runnable() {
            @Override public void run() { announceLoop(); }
        });
        }
        // v8: 开机桌面纠偏——当贝 SplashActivity 声明了 MAIN+HOME，开机会被 ROM 拉起
        // 占住屏幕。Scout 起来后检查前台，不是用户桌面就显式拉起。
        // v10: 目标桌面动态解析（第一个用户安装的 launcher），不再写死 Kido；
        //      主力守护改为 HomeGuardService（无障碍），本纠偏降级为备用层。
        if (Config.get(this, Config.Key.HOMEFIX)) {
        pool.execute(new Runnable() {
            @Override public void run() {
                sleep(45000);   // 等 system 完全起来、桌面流程走稳
                ensureHome(0);
            }
        });
        }
        // v10: 无障碍守护自检/修复（force-stop 或当贝清理会摘除授权，
        // Job 复活进程后在这里把授权修回来 —— cleanyourtv ServiceRepair 方法）
        if (Config.get(this, Config.Key.HOMEGUARD)) {
        pool.execute(new Runnable() {
            @Override public void run() {
                sleep(8000);
                ensureHomeGuard(0);
            }
        });
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (pool != null) pool.shutdownNow();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------------- 前台通知 ----------------

    private void startForegroundSafe() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(
                    "scout", "DBOS Scout", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, "scout");
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(android.R.drawable.stat_notify_sync)
         .setContentTitle("DBOS Scout 常驻")
         .setContentText("控制台 :" + HTTP_PORT + " · 发现 :" + UDP_PORT)
         .setOngoing(true);
        startForeground(NOTIF_ID, b.build());
    }

    // ---------------- adbd ----------------

    /** 幂等拉起 adbd，失败则退避重试（最多 5 次） */
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

    // ---------------- v8/v10: 桌面纠偏（备用层） ----------------

    /** 前台不是用户桌面就显式拉起它（目标动态解析，不写死包名；主力是无障碍 HomeGuard） */
    public void ensureHome(int attempt) {
        android.content.ComponentName home = HomeLauncher.resolveUserHome(this);
        if (home == null) { lastStatus = "no user launcher installed"; return; }
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

    // ---------------- v10: 无障碍守护授权自检/修复 ----------------

    private static final String GUARD_SVC = "com.dbos.scout/.HomeGuardService";

    /** 干净关闭守护：把无障碍授权列表里的自己摘掉（系统随即解绑服务） */
    static void disableHomeGuard(Context ctx) {
        String cur = shStatic("settings get secure enabled_accessibility_services");
        // 保留其它服务，只摘自己
        java.util.List<String> keep = new java.util.ArrayList<String>();
        for (String s : cur.split(":")) {
            s = s.trim();
            if (!s.isEmpty() && !"null".equals(s) && !s.contains("HomeGuardService")) keep.add(s);
        }
        StringBuilder val = new StringBuilder();
        for (String s : keep) {
            if (val.length() > 0) val.append(':');
            val.append(s);
        }
        shStatic("settings put secure enabled_accessibility_services " + val);
    }

    /** 静态版 shell（给无 Activity 上下文的辅助方法用） */
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

    /** force-stop 会摘除无障碍授权；Job 把进程复活后在这里 remove→add 触发系统重绑 */
    private void ensureHomeGuard(int attempt) {
        if (HomeGuardService.connected) {
            lastStatus = "homeguard connected";
            return;
        }
        String cur = sh("settings get secure enabled_accessibility_services").trim();
        Log.i(TAG, "homeguard repair: cur=[" + cur + "] attempt=" + attempt);
        // 保留其它已启用的服务，只操作自己这一项
        StringBuilder others = new StringBuilder();
        for (String c : cur.split(":")) {
            c = c.trim();
            if (c.isEmpty() || "null".equals(c) || c.contains("HomeGuardService")) continue;
            if (others.length() > 0) others.append(':');
            others.append(c);
        }
        String val = others.length() == 0 ? GUARD_SVC : others + ":" + GUARD_SVC;
        sh("settings put secure enabled_accessibility_services " + val);
        sh("settings put secure accessibility_enabled 1");
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
            lastStatus = "homeguard repair failed; cur=" + cur;
            Log.e(TAG, lastStatus);
        }
    }

    // ---------------- HTTP ----------------

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
            // 排空剩余请求头（以 CRLFCRLF 结束），带超时保护
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

            // v11: WebUI 控制台（页面免 token，操作时带 token）
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
        if ("/".equals(path) || "/data".equals(path)) {
            return whoami();
        }
        if ("/whoami".equals(path)) {
            return whoami();
        }
        if ("/config".equals(path)) {
            // 读写用户配置：/config 读取；/config?homeguard_on=0 写入
            if (p.containsKey(Config.Key.HOMEGUARD))
                Config.set(this, Config.Key.HOMEGUARD, !"0".equals(p.get(Config.Key.HOMEGUARD)));
            if (p.containsKey(Config.Key.HOMEFIX))
                Config.set(this, Config.Key.HOMEFIX, !"0".equals(p.get(Config.Key.HOMEFIX)));
            if (p.containsKey(Config.Key.ADBD))
                Config.set(this, Config.Key.ADBD, !"0".equals(p.get(Config.Key.ADBD)));
            if (p.containsKey(Config.Key.ANNOUNCE))
                Config.set(this, Config.Key.ANNOUNCE, !"0".equals(p.get(Config.Key.ANNOUNCE)));
            if (p.containsKey(Config.Key.FINEVOL))
                Config.set(this, Config.Key.FINEVOL, !"0".equals(p.get(Config.Key.FINEVOL)));
            return Config.toJson(this);
        }
        if ("/vol".equals(path)) {
            // v13: 音量查询/设置。/vol 读；/vol?i=N 设绝对值；/vol?i=+1 /vol?i=-1 增减（1 格）
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
                HomeGuardService.volAnchor = next;   // v14: 同步对冲基线，防按键校正覆盖
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
                        String cur = sh("settings get secure enabled_accessibility_services").trim();
                        StringBuilder others = new StringBuilder();
                        for (String c : cur.split(":")) {
                            c = c.trim();
                            if (c.isEmpty() || "null".equals(c) || c.contains("HomeGuardService")) continue;
                            if (others.length() > 0) others.append(':');
                            others.append(c);
                        }
                        sh("settings put secure enabled_accessibility_services " + others);
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
        if ("/props".equals(path)) {
            return "{\"out\":" + js(sh("getprop")) + "}";
        }
        if ("/settings".equals(path)) {
            String[] keys = {
                "global development_settings_enabled",
                "global adb_enabled",
                "global adb_wifi_enabled",
                "secure adb_enabled",
                "global verifier_verify_adb_installs"
            };
            StringBuilder r = new StringBuilder("{");
            for (int i = 0; i < keys.length; i++) {
                r.append("\"").append(esc(keys[i])).append("\":")
                 .append(js(sh("settings get " + keys[i])));
                if (i < keys.length - 1) r.append(',');
            }
            return r.append('}').toString();
        }
        if ("/wakelock".equals(path)) {
            String on = p.get("on");
            return wakelock("1".equals(on));
        }
        if ("/acts".equals(path)) {
            String f = p.get("q");
            return listActivities(f == null ? "" : f.toLowerCase());
        }
        if ("/pkg".equals(path)) {
            String pk = p.get("n");
            if (pk == null) return "{\"error\":\"need n\"}";
            return pkgActivities(pk);
        }
        if ("/start".equals(path)) {
            String pkg = p.get("pkg"), cls = p.get("cls");
            if (pkg == null || cls == null) return "{\"error\":\"need pkg & cls\"}";
            return startComponent(pkg, cls);
        }
        return "{\"error\":\"unknown path " + esc(path) + "\"}";
    }

    private String whoami() {
        List<String> ips = localIPv4();
        String primary = ips.isEmpty() ? "" : ips.get(0);
        StringBuilder r = new StringBuilder("{");
        r.append("\"app\":\"DBOSScout\",\"ver\":").append(js(VER));
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

    private String wakelock(boolean on) {
        try {
            if (on) {
                if (wakeLock == null) {
                    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DBOSScout::keepalive");
                    wakeLock.setReferenceCounted(false);
                }
                if (!wakeLock.isHeld()) wakeLock.acquire();
            } else {
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            }
        } catch (Exception e) {
            return "{\"error\":" + js(String.valueOf(e)) + "}";
        }
        return "{\"wakelock\":" + (wakeLock != null && wakeLock.isHeld()) + "}";
    }

    // ---------------- 自愈层：JobScheduler 持久任务 ----------------
    // 当贝 ROM 拦了第三方包的 BOOT_COMPLETED 进程拉起（startProcessLocked failed）。
    // v6 的 15 分钟周期 Job 相位不确定，开机后最久要等 15 分钟。
    // v7 关键技巧：持久化的「一次性 Job + deadline」——关机期间 deadline 照样流逝，
    // 开机后 JobSchedulerService 一看 deadline 已过，立刻拉进程跑任务，绕过广播管控。
    //
    // Job 777: 15 分钟周期兜底（保底心跳）
    // Job 778: 90 秒 deadline 的一次性链，每次执行完自己再排下一个 → 任何时刻关机，
    //          手里永远攥着一个"deadline 已过"的持久任务，开机即触发。
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

    /** 排一个 deadline 很短的持久一次性 Job；开机后 deadline 已过期 → 立即触发 */
    static void armBootTrigger(android.app.job.JobScheduler js) {
        js.cancel(778);
        js.schedule(new android.app.job.JobInfo.Builder(778,
                new android.content.ComponentName("com.dbos.scout",
                        "com.dbos.scout.KeepJobService"))
                .setPersisted(true)
                .setMinimumLatency(60L * 1000)
                .setOverrideDeadline(90L * 1000)
                .setRequiresCharging(false)
                .setRequiresBatteryNotLow(false)
                .setRequiresDeviceIdle(false)
                .build());
    }

    // ---------------- 主动广播 ----------------

    /** 开机后持续向局域网广播自己的位置，解决 IP 漂移导致的失联 */
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

    // ---------------- UDP 发现 ----------------

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

    // ---------------- WebUI ----------------

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

    // ---------------- shell（修死锁 + 超时） ----------------

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

    // ---------------- activity 相关 ----------------

    private String listActivities(String filter) {
        StringBuilder r = new StringBuilder("{\"acts\":[");
        int n = 0;
        try {
            List<android.content.pm.PackageInfo> pkgs = getPackageManager()
                    .getInstalledPackages(android.content.pm.PackageManager.GET_ACTIVITIES);
            for (android.content.pm.PackageInfo pi : pkgs) {
                if (pi.activities == null) continue;
                for (android.content.pm.ActivityInfo ai : pi.activities) {
                    String comp = ai.packageName + "/" + ai.name;
                    if (!filter.isEmpty() && !comp.toLowerCase().contains(filter)) continue;
                    if (n > 0) r.append(',');
                    r.append(js(comp));
                    n++;
                    if (n >= 800) break;
                }
                if (n >= 800) break;
            }
        } catch (Exception e) {
            return "{\"error\":" + js(String.valueOf(e)) + "}";
        }
        return r.append("],\"count\":").append(n).append('}').toString();
    }

    private String pkgActivities(String pk) {
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo(pk, android.content.pm.PackageManager.GET_ACTIVITIES);
            StringBuilder r = new StringBuilder("{\"pkg\":").append(js(pk)).append(",\"acts\":[");
            if (pi.activities != null) {
                for (int i = 0; i < pi.activities.length; i++) {
                    r.append(js(pi.activities[i].name));
                    if (i < pi.activities.length - 1) r.append(',');
                }
            }
            return r.append("]}").toString();
        } catch (Exception e) {
            return "{\"error\":" + js(String.valueOf(e)) + "}";
        }
    }

    private String startComponent(String pkg, String cls) {
        try {
            Intent it = new Intent();
            it.setClassName(pkg, cls);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
            return "{\"ok\":true,\"started\":" + js(pkg + "/" + cls) + "}";
        } catch (Exception e) {
            return "{\"error\":" + js(String.valueOf(e)) + "}";
        }
    }

    // ---------------- json helper ----------------

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
