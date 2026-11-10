package com.dbos.scout;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.zip.CRC32;

/**
 * v17: 本机 adbd mini 客户端。
 *
 * 为什么需要它：AudioService.setVolumeController(null)（掐掉原厂音量条的那一刀）
 * 要求调用方持有签名级 STATUS_BAR_SERVICE —— Scout 自己（uid 10xxx）没有，
 * 但 shell 有。Scout 本来就能 `setprop ctl.start adbd`，adbd 起来后监听 tcp:5555，
 * 那么从 app 进程自己连 127.0.0.1:5555 说过 ADB 协议、以 shell 身份跑一条命令，
 * 就是这台 ROM 上唯一免授权的 shell 通道（ro.adb.secure=0，无 RSA 挑战）。
 *
 * 协议最小实现：CNXN 握手 → OPEN "shell:<cmd>" → 收 WRTE（逐包回 OKAY 确认）→
 * CLSE 结束。不到 150 行，无依赖。
 */
public final class LocalAdb {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;   // persist.adb.tcp.port 出厂即 5555
    // ADB 的 command/magic 字段：常量是 ASCII 四字母的小端读法（序列化后线上字节才
    // 是 "CNXN"），magic = command ^ 0xFFFFFFFF，同样小端。24 字节头全小端。
    private static final int A_CNXN = 0x4E584E43;   // "CNXN"
    private static final int A_OPEN = 0x4E45504F;   // "OPEN"
    private static final int A_OKAY = 0x59414B4F;   // "OKAY"
    private static final int A_CLSE = 0x45534C43;   // "CLSE"
    private static final int A_WRTE = 0x45545257;   // "WRTE"
    private static final int A_AUTH = 0x48545541;   // "AUTH"

    private LocalAdb() {}

    /** 连本机 adbd 执行一条 shell 命令，返回合并输出；失败返回 "[adb] ..." 诊断串 */
    public static String shell(String cmd) { return shell(cmd, 8000); }

    public static String shell(String cmd, int timeoutMs) {
        Socket s = null;
        try {
            s = new Socket(HOST, PORT);
            s.setSoTimeout(timeoutMs);
            OutputStream os = s.getOutputStream();
            InputStream is = s.getInputStream();

            // --- CNXN 握手：版本 0x01000001，声明能收 4KB ---
            byte[] id = "host::".getBytes("UTF-8");
            write(os, A_CNXN, 0x01000001, 4096, id);
            int[] m = read(is);
            if (m == null) return "[adb] adbd 无应答（没起来？）";
            if (m[0] == A_AUTH) return "[adb] 收到 AUTH 挑战（ro.adb.secure 非 0，此路不通）";
            if (m[0] != A_CNXN) return "[adb] 意外包 type=" + m[0];
            // CNXN 自带 payload（设备身份串），读掉即可
            skip(is, m[3]);

            // --- OPEN shell 服务（一次性模式：输出流到命令结束即 CLSE） ---
            byte[] svc = ("shell:" + cmd).getBytes("UTF-8");
            write(os, A_OPEN, 1, 0, svc);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            for (;;) {
                m = read(is);
                if (m == null) break;
                if (m[0] == A_OKAY) continue;                 // arg1=对端流 id，无需记
                if (m[0] == A_WRTE) {
                    byte[] b = new byte[m[3]];
                    readFully(is, b);
                    bos.write(b);
                    write(os, A_OKAY, 1, m[1], new byte[0]);   // 确认对端 WRTE
                    continue;
                }
                if (m[0] == A_CLSE) break;
                skip(is, m[3]);
            }
            return bos.toString("UTF-8").trim();
        } catch (Exception e) {
            return "[adb] " + e;
        } finally {
            try { if (s != null) s.close(); } catch (Exception ignored) {}
        }
    }

    // ---------- 报文原语 ----------

    /** 头 24 字节：command arg0 arg1 data_length data_check(crc32) magic(cmd^-1)，全小端 */
    private static void write(OutputStream os, int cmd, int arg0, int arg1, byte[] data)
            throws IOException {
        if (data == null) data = new byte[0];
        CRC32 crc = new CRC32();
        crc.update(data);
        byte[] h = new byte[24];
        putLE(h, 0, cmd);
        putLE(h, 4, arg0);
        putLE(h, 8, arg1);
        putLE(h, 12, data.length);
        putLE(h, 16, (int) crc.getValue());
        putLE(h, 20, cmd ^ 0xFFFFFFFF);
        os.write(h);
        if (data.length > 0) os.write(data);
        os.flush();
    }

    /** 读一个报文头（不含 payload）。返回 {cmd,arg0,arg1,len}；EOF/超时返回 null */
    private static int[] read(InputStream is) throws IOException {
        byte[] h = new byte[24];
        readFully(is, h);
        return new int[]{
                getLE(h, 0), getLE(h, 4), getLE(h, 8), getLE(h, 12)};
    }

    private static void skip(InputStream is, int n) throws IOException {
        if (n <= 0) return;
        byte[] b = new byte[n];
        readFully(is, b);
    }

    private static void readFully(InputStream is, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            DataInputStream d = new DataInputStream(is);
            int n = d.read(b, off, b.length - off);
            if (n < 0) throw new IOException("EOF");
            off += n;
        }
    }

    private static void putLE(byte[] b, int off, int v) {
        b[off] = (byte) v; b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16); b[off + 3] = (byte) (v >>> 24);
    }

    private static int getLE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }
}
