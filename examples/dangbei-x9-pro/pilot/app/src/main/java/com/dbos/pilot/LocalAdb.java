package com.dbos.pilot;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.zip.CRC32;

/**
 * Minimal ADB client for the local adbd (127.0.0.1:5555).
 *
 * Why it exists: some system operations need the shell's identity —
 * IAudioService.setVolumeController(null) (suppressing the stock volume bar)
 * requires the signature-level STATUS_BAR_SERVICE, and property/service
 * control likewise. The app itself (uid 10xxx) cannot hold those, but the
 * shell can. This ROM lets any app start adbd (setprop ctl.start adbd), and
 * adbd runs with ro.adb.secure=0, so the app can talk ADB to its own device's
 * daemon and run commands as the shell — the one self-contained shell channel
 * on the box, and the piece that lets the delivered app work without a PC.
 *
 * Protocol: CNXN handshake → OPEN "shell:<cmd>" → read WRTE packets (OKAY each)
 * → CLSE. All header fields are little-endian; the command constants are the
 * ASCII four-letter tags read little-endian (writing them big-endian makes
 * adbd silently time out). adbd's TLS server must be off
 * (persist.adb.tls_server.enable=0, factory default here).
 */
public final class LocalAdb {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;   // persist.adb.tcp.port, factory-set

    private static final int A_CNXN = 0x4E584E43;   // "CNXN"
    private static final int A_OPEN = 0x4E45504F;   // "OPEN"
    private static final int A_OKAY = 0x59414B4F;   // "OKAY"
    private static final int A_CLSE = 0x45534C43;   // "CLSE"
    private static final int A_WRTE = 0x45545257;   // "WRTE"
    private static final int A_AUTH = 0x48545541;   // "AUTH"

    private LocalAdb() {}

    /** Run one shell command on the local adbd; returns combined output or "[adb] …" diagnostics. */
    public static String shell(String cmd) { return shell(cmd, 8000); }

    public static String shell(String cmd, int timeoutMs) {
        Socket s = null;
        try {
            s = new Socket(HOST, PORT);
            s.setSoTimeout(timeoutMs);
            OutputStream os = s.getOutputStream();
            InputStream is = s.getInputStream();

            // CNXN handshake: version 0x01000001, announce a 4KB receive cap.
            byte[] id = "host::".getBytes("UTF-8");
            write(os, A_CNXN, 0x01000001, 4096, id);
            int[] m = read(is);
            if (m == null) return "[adb] adbd no answer (not running?)";
            if (m[0] == A_AUTH) return "[adb] AUTH challenge (ro.adb.secure != 0)";
            if (m[0] != A_CNXN) return "[adb] unexpected packet type=" + m[0];
            skip(is, m[3]);   // device identity payload, just drain it

            // OPEN the shell service (output streams to us until CLSE).
            byte[] svc = ("shell:" + cmd).getBytes("UTF-8");
            write(os, A_OPEN, 1, 0, svc);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            for (;;) {
                m = read(is);
                if (m == null) break;
                if (m[0] == A_OKAY) continue;
                if (m[0] == A_WRTE) {
                    byte[] b = new byte[m[3]];
                    readFully(is, b);
                    bos.write(b);
                    write(os, A_OKAY, 1, m[1], new byte[0]);
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

    // ---------- packet primitives ----------

    /** 24-byte header: command arg0 arg1 data_length crc32 magic(command^0xFFFFFFFF), all LE. */
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

    /** Read one packet header (no payload). Returns {cmd,arg0,arg1,len}; null on EOF/timeout. */
    private static int[] read(InputStream is) throws IOException {
        byte[] h = new byte[24];
        readFully(is, h);
        return new int[]{ getLE(h, 0), getLE(h, 4), getLE(h, 8), getLE(h, 12) };
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
