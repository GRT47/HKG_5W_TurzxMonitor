package com.hkg.turzxmonitor.app;

import android.app.Activity;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.os.Process;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Trampoline activity shown in the Gen5W all-apps menu and star-key actions via HMG-API.
 * Triggers hmgapid manual root service `open-ui` over the abstract `hmgapid` LocalSocket
 * (falling back to /dev/hkg_tmp/hkg_turzxmonitor_cmd) and exits immediately so RAM stays 0 MB.
 */
public final class LaunchActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean sent = callHmgapidServiceStart();
                if (!sent) {
                    writeFallbackCmd();
                }
                try {
                    Thread.sleep(120L);
                } catch (InterruptedException ignored) {
                }
                Process.killProcess(Process.myPid());
            }
        }, "HkgTurzxTrigger");
        t.start();

        finish();
        overridePendingTransition(0, 0);
    }

    private static boolean callHmgapidServiceStart() {
        LocalSocket sock = null;
        try {
            sock = new LocalSocket();
            sock.connect(new LocalSocketAddress("hmgapid", LocalSocketAddress.Namespace.ABSTRACT));
            sock.setSoTimeout(3000);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bos.write(new byte[] { 'L', 'F', 'D', '1' });
            writeU32LE(bos, 2);
            writeArg(bos, "service-start");
            writeArg(bos, "open-ui");

            OutputStream os = sock.getOutputStream();
            os.write(bos.toByteArray());
            os.flush();

            DataInputStream dis = new DataInputStream(sock.getInputStream());
            byte[] lenBuf = new byte[4];
            int exitCode = -1;
            while (true) {
                int tag = dis.read();
                if (tag < 0) {
                    break;
                }
                dis.readFully(lenBuf);
                int len = (lenBuf[0] & 0xff)
                        | ((lenBuf[1] & 0xff) << 8)
                        | ((lenBuf[2] & 0xff) << 16)
                        | ((lenBuf[3] & 0xff) << 24);
                if (len > 0 && len <= 65536) {
                    byte[] payload = new byte[len];
                    dis.readFully(payload);
                    if (tag == 88 && len >= 4) { // 'X' exit status
                        exitCode = (payload[0] & 0xff)
                                | ((payload[1] & 0xff) << 8)
                                | ((payload[2] & 0xff) << 16)
                                | ((payload[3] & 0xff) << 24);
                        break;
                    }
                } else if (tag == 88) {
                    break;
                }
            }
            return exitCode == 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (sock != null) {
                try {
                    sock.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void writeArg(ByteArrayOutputStream bos, String s) throws Exception {
        byte[] b = s.getBytes("UTF-8");
        writeU32LE(bos, b.length);
        bos.write(b);
    }

    private static void writeU32LE(ByteArrayOutputStream bos, int v) {
        bos.write(v & 0xff);
        bos.write((v >>> 8) & 0xff);
        bos.write((v >>> 16) & 0xff);
        bos.write((v >>> 24) & 0xff);
    }

    private static void writeFallbackCmd() {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream("/dev/hkg_tmp/hkg_turzxmonitor_cmd", false);
            fos.write("show\n".getBytes("UTF-8"));
            fos.flush();
        } catch (Throwable ignored) {
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
