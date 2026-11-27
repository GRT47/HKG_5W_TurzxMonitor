package com.hkg.huddisplay;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.wifi.WifiManager;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Locale;

/**
 * Connects in parallel to:
 * 1) Headunit UDP beacon (:8767) for zero-config IP discovery
 * 2) Headunit HTTP telemetry (:8766 /status.json + /albumart.jpg)
 * 3) Comma 3X compact_state WebSocket (ws://<comma>:7000/ws/compact_state) for 20-33 Hz zero-latency driving viz
 */
public final class TelemetryClient {

    public static final int HUB_DISCOVERY_PORT = 8767;
    public static final int HUB_HTTP_PORT = 8766;
    public static final int CARROT_WS_PORT = 7000;

    private static final int RADAR_LEAD_LISTS = 6;
    private static final int RADAR_LEAD_SIZE = 63;
    private static final int TRAFFIC_STRIDE = 4;

    private final Context context;
    private final Object stateLock = new Object();
    private final HudState state = new HudState();

    private volatile boolean running = false;
    private volatile boolean demoMode = false;
    private volatile boolean commaDirectEnabled = true;
    private volatile String configuredHubIp = "";
    private volatile String configuredCommaIp = "";
    private volatile String discoveredHubIp = "";
    private volatile String discoveredCommaIp = "";

    private String loadedTrackKey = "";
    private Thread tBeacon, tHttp, tWs;
    private WifiManager.MulticastLock multicastLock;

    public TelemetryClient(Context ctx) {
        this.context = ctx.getApplicationContext();
    }

    public void configure(String hubIp, String commaIp, boolean commaDirect, boolean demo) {
        this.configuredHubIp = hubIp != null ? hubIp.trim() : "";
        this.configuredCommaIp = commaIp != null ? commaIp.trim() : "";
        this.commaDirectEnabled = commaDirect;
        this.demoMode = demo;
        synchronized (stateLock) {
            state.hubIp = activeHubIp();
            if (!configuredCommaIp.isEmpty()) state.commaIp = configuredCommaIp;
        }
    }

    public HudState snapshot() {
        long now = System.currentTimeMillis();
        synchronized (stateLock) {
            if (state.hubConnected && (now - state.hubLastRxMs) > 3500L) {
                state.hubConnected = false;
                state.hubHz = 0.0;
            }
            if (state.commaDirectConnected && (now - state.commaDirectLastRxMs) > 2500L) {
                state.commaDirectConnected = false;
                state.commaHz = 0.0;
            }
            if (state.commaConnected && !state.commaDirectConnected && (now - state.commaLastRxMs) > 3500L) {
                state.commaConnected = false;
            }
            return state.copy();
        }
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                multicastLock = wm.createMulticastLock("HkgHudBeacon");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Throwable ignored) {
        }

        tBeacon = new Thread(this::runDiscoveryLoop, "Hud-UdpBeacon");
        tBeacon.setDaemon(true);
        tBeacon.start();

        tHttp = new Thread(this::runHeadunitHttpLoop, "Hud-HttpPoll");
        tHttp.setDaemon(true);
        tHttp.start();

        tWs = new Thread(this::runCommaWsLoop, "Hud-CommaWs");
        tWs.setDaemon(true);
        tWs.start();
    }

    public synchronized void stop() {
        running = false;
        if (tBeacon != null) tBeacon.interrupt();
        if (tHttp != null) tHttp.interrupt();
        if (tWs != null) tWs.interrupt();
        try {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        } catch (Throwable ignored) {
        }
    }

    private String activeHubIp() {
        if (!configuredHubIp.isEmpty()) return configuredHubIp;
        return discoveredHubIp;
    }

    private String activeCommaIp() {
        if (!configuredCommaIp.isEmpty()) return configuredCommaIp;
        synchronized (stateLock) {
            if (isValidIp(state.commaIp)) return state.commaIp;
        }
        return discoveredCommaIp;
    }

    private static boolean isValidIp(String ip) {
        if (ip == null) return false;
        String s = ip.trim();
        return !s.isEmpty() && !s.startsWith("127.") && !"0.0.0.0".equals(s) && !s.endsWith(".255");
    }

    private void runDiscoveryLoop() {
        byte[] probeBytes = "DISCOVER_HKG_HUD".getBytes(StandardCharsets.UTF_8);
        byte[] rxBuf = new byte[1024];
        while (running) {
            DatagramSocket sock = null;
            try {
                sock = new DatagramSocket(null);
                sock.setReuseAddress(true);
                sock.setBroadcast(true);
                sock.bind(new InetSocketAddress(HUB_DISCOVERY_PORT));
                sock.setSoTimeout(1500);
                long lastProbeMs = 0L;
                while (running) {
                    long now = System.currentTimeMillis();
                    boolean needProbe = !state.hubConnected && (now - lastProbeMs >= 2500L);
                    if (needProbe && !demoMode) {
                        lastProbeMs = now;
                        try {
                            sock.send(new DatagramPacket(probeBytes, probeBytes.length,
                                    InetAddress.getByName("255.255.255.255"), HUB_DISCOVERY_PORT));
                        } catch (Throwable ignored) {}
                        try {
                            Enumeration<NetworkInterface> nifs = NetworkInterface.getNetworkInterfaces();
                            while (nifs != null && nifs.hasMoreElements()) {
                                NetworkInterface nif = nifs.nextElement();
                                if (!nif.isUp() || nif.isLoopback()) continue;
                                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                                    InetAddress bc = ia.getBroadcast();
                                    if (bc instanceof Inet4Address) {
                                        try {
                                            sock.send(new DatagramPacket(probeBytes, probeBytes.length, bc, HUB_DISCOVERY_PORT));
                                        } catch (Throwable ignored) {}
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                    try {
                        DatagramPacket pkt = new DatagramPacket(rxBuf, rxBuf.length);
                        sock.receive(pkt);
                        String txt = new String(pkt.getData(), pkt.getOffset(), pkt.getLength(), StandardCharsets.UTF_8).trim();
                        if (txt.contains("hkg_turzx_hub")) {
                            JSONObject j = new JSONObject(txt);
                            String sender = pkt.getAddress() != null ? pkt.getAddress().getHostAddress() : "";
                            if (isValidIp(sender)) {
                                discoveredHubIp = sender;
                            }
                            String cip = j.optString("carrotIp", "");
                            if (isValidIp(cip)) {
                                discoveredCommaIp = cip.trim();
                            }
                            synchronized (stateLock) {
                                if (configuredHubIp.isEmpty() && isValidIp(sender)) {
                                    state.hubIp = sender;
                                }
                                if (configuredCommaIp.isEmpty() && isValidIp(cip)) {
                                    state.commaIp = cip.trim();
                                }
                                state.screenOff = j.optBoolean("screenOff", state.screenOff);
                            }
                        }
                    } catch (SocketTimeoutException ignored) {
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (sock != null) try { sock.close(); } catch (Throwable ignored) {}
            }
            sleepQuiet(2000L);
        }
    }

    private void runHeadunitHttpLoop() {
        long rateWinStart = System.currentTimeMillis();
        int rateCount = 0;
        while (running) {
            if (demoMode) {
                sleepQuiet(250L);
                continue;
            }
            String ip = activeHubIp();
            if (ip == null || ip.isEmpty()) {
                sleepQuiet(400L);
                continue;
            }
            long t0 = System.currentTimeMillis();
            try {
                String json = httpGetText("http://" + ip + ":" + HUB_HTTP_PORT + "/status.json", 1200);
                if (json != null && json.contains("\"ok\":true")) {
                    long now = System.currentTimeMillis();
                    String wantTrack = null;
                    synchronized (stateLock) {
                        state.hubIp = ip;
                        state.mergeHeadunitJson(json, now);
                        if (state.hasAlbumArt && state.trackKey != null && !state.trackKey.isEmpty()
                                && !state.trackKey.equals(loadedTrackKey)) {
                            wantTrack = state.trackKey;
                        } else if (!state.hasAlbumArt) {
                            loadedTrackKey = "";
                        }
                    }
                    rateCount++;
                    long dtW = now - rateWinStart;
                    if (dtW >= 2000L) {
                        double hz = (rateCount * 1000.0) / dtW;
                        rateCount = 0;
                        rateWinStart = now;
                        synchronized (stateLock) {
                            state.hubHz = hz;
                        }
                    }
                    if (wantTrack != null) {
                        byte[] img = httpGetBytes("http://" + ip + ":" + HUB_HTTP_PORT + "/albumart.jpg", 2000);
                        if (img != null && img.length > 100) {
                            Bitmap bmp = BitmapFactory.decodeByteArray(img, 0, img.length);
                            if (bmp != null) {
                                synchronized (stateLock) {
                                    if (wantTrack.equals(state.trackKey)) {
                                        state.albumArt = bmp;
                                        loadedTrackKey = wantTrack;
                                    }
                                }
                            }
                        }
                    }
                    long elapsed = System.currentTimeMillis() - t0;
                    sleepQuiet(Math.max(15L, 95L - elapsed));
                    continue;
                }
            } catch (Throwable ignored) {
            }
            sleepQuiet(600L);
        }
    }

    private void runCommaWsLoop() {
        long rateWinStart = System.currentTimeMillis();
        int rateCount = 0;
        while (running) {
            if (demoMode || !commaDirectEnabled) {
                sleepQuiet(350L);
                continue;
            }
            String ip = activeCommaIp();
            if (!isValidIp(ip)) {
                sleepQuiet(1000L);
                continue;
            }
            Socket wsSock = null;
            try {
                wsSock = new Socket();
                wsSock.connect(new InetSocketAddress(ip, CARROT_WS_PORT), 2000);
                wsSock.setSoTimeout(5000);
                OutputStream os = wsSock.getOutputStream();
                InputStream is = wsSock.getInputStream();
                String handshake = "GET /ws/compact_state?services=carState,controlsState,carrotMan,radarState,selfdriveState,longitudinalPlan,modelV2,carControl HTTP/1.1\r\n"
                        + "Host: " + ip + ":" + CARROT_WS_PORT + "\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                        + "Sec-WebSocket-Version: 13\r\n\r\n";
                os.write(handshake.getBytes(StandardCharsets.UTF_8));
                os.flush();

                StringBuilder hdr = new StringBuilder(256);
                int match = 0;
                while (match < 4) {
                    int b = is.read();
                    if (b < 0) throw new RuntimeException("EOF handshake");
                    if (hdr.length() < 512) hdr.append((char) (b & 0xFF));
                    if ((match == 0 || match == 2) && b == '\r') match++;
                    else if ((match == 1 || match == 3) && b == '\n') match++;
                    else match = 0;
                }
                if (!hdr.toString().contains("101")) throw new RuntimeException("WS rejected");
                wsSock.setSoTimeout(10000);

                while (running && !demoMode && commaDirectEnabled) {
                    int b0 = is.read();
                    int b1 = is.read();
                    if (b0 < 0 || b1 < 0) break;
                    int opcode = b0 & 0x0F;
                    boolean masked = (b1 & 0x80) != 0;
                    long len = b1 & 0x7F;
                    if (len == 126) {
                        len = ((is.read() & 0xFF) << 8) | (is.read() & 0xFF);
                    } else if (len == 127) {
                        len = 0;
                        for (int i = 0; i < 8; i++) len = (len << 8) | (is.read() & 0xFF);
                    }
                    if (len < 0 || len > 131072) break;
                    byte[] maskKey = null;
                    if (masked) {
                        maskKey = new byte[4];
                        readFully(is, maskKey, 4);
                    }
                    byte[] payload = new byte[(int) len];
                    readFully(is, payload, (int) len);
                    if (masked && maskKey != null) {
                        for (int i = 0; i < payload.length; i++) payload[i] ^= maskKey[i & 3];
                    }
                    if (opcode == 2 && payload.length >= 12) {
                        long now = System.currentTimeMillis();
                        synchronized (stateLock) {
                            if (decodeCvs1Payload(payload, state, now)) {
                                state.commaConnected = true;
                                state.commaDirectConnected = true;
                                state.commaLastRxMs = now;
                                state.commaDirectLastRxMs = now;
                                state.commaIp = ip;
                            }
                        }
                        rateCount++;
                        long dtW = now - rateWinStart;
                        if (dtW >= 2000L) {
                            double hz = (rateCount * 1000.0) / dtW;
                            rateCount = 0;
                            rateWinStart = now;
                            synchronized (stateLock) {
                                state.commaHz = hz;
                            }
                        }
                    } else if (opcode == 9) {
                        int pl = Math.min(payload.length, 125);
                        byte[] pong = new byte[6 + pl];
                        pong[0] = (byte) 0x8A;
                        pong[1] = (byte) (0x80 | pl);
                        pong[2] = 0x12; pong[3] = 0x34; pong[4] = 0x56; pong[5] = 0x78;
                        for (int i = 0; i < pl; i++) pong[6 + i] = (byte) (payload[i] ^ pong[2 + (i & 3)]);
                        os.write(pong);
                        os.flush();
                    } else if (opcode == 8) {
                        break;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (wsSock != null) try { wsSock.close(); } catch (Throwable ignored) {}
            }
            sleepQuiet(2000L);
        }
    }

    private static void readFully(InputStream is, byte[] buf, int len) throws Exception {
        int off = 0;
        while (off < len) {
            int r = is.read(buf, off, len - off);
            if (r < 0) throw new RuntimeException("EOF");
            off += r;
        }
    }

    private static boolean decodeCvs1Payload(byte[] buf, HudState target, long nowMs) {
        if (buf.length < 12) return false;
        if (buf[0] == 'C' && buf[1] == 'V' && buf[2] == 'B' && buf[3] == '1') {
            int count = (buf[4] & 0xFF) | ((buf[5] & 0xFF) << 8);
            int off = 6;
            boolean ok = false;
            for (int i = 0; i < count && off + 4 <= buf.length; i++) {
                int fLen = (buf[off] & 0xFF) | ((buf[off + 1] & 0xFF) << 8)
                        | ((buf[off + 2] & 0xFF) << 16) | ((buf[off + 3] & 0xFF) << 24);
                off += 4;
                if (fLen <= 0 || off + fLen > buf.length) break;
                if (decodeSingleCvs1Frame(buf, off, fLen, target, nowMs)) ok = true;
                off += fLen;
            }
            return ok;
        }
        return decodeSingleCvs1Frame(buf, 0, buf.length, target, nowMs);
    }

    private static boolean decodeSingleCvs1Frame(byte[] buf, int base, int len, HudState target, long nowMs) {
        if (len < 9 || base < 0 || base + len > buf.length) return false;
        if (buf[base] != 'C' || buf[base + 1] != 'V' || buf[base + 2] != 'S' || buf[base + 3] != '1') return false;
        int svc = buf[base + 4] & 0xFF;
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        switch (svc) {
            case 1: { // carState (73 bytes)
                if (len < 73) return false;
                float vEgoMs = bb.getFloat(base + 8);
                float vEgoClusterMs = bb.getFloat(base + 16);
                float vCruiseKph = bb.getFloat(base + 20);
                float steerDeg = bb.getFloat(base + 24);
                int carrotCruise = bb.getShort(base + 31);
                boolean brakeLights = buf[base + 39] != 0 || buf[base + 28] != 0;
                int gearShifter = buf[base + 46] & 0xFF;
                target.brakeLamp = brakeLights;
                float fl = bb.getFloat(base + 57), fr = bb.getFloat(base + 61);
                float rl = bb.getFloat(base + 65), rr = bb.getFloat(base + 69);

                float refMs = (!Float.isNaN(vEgoClusterMs) && vEgoClusterMs > 0.1f) ? vEgoClusterMs : vEgoMs;
                if (!Float.isNaN(refMs) && refMs >= 0f && refMs <= 90f) {
                    target.speedKph = Math.round(refMs * 3.6f);
                }
                if (gearShifter == 1) target.gearPos = "P";
                else if (gearShifter == 2) target.gearPos = "D";
                else if (gearShifter == 3) target.gearPos = "N";
                else if (gearShifter == 4) target.gearPos = "R";

                String g = target.gearPos != null ? target.gearPos.trim().toUpperCase(Locale.US) : "";
                boolean nonDrive = "P".equals(g) || "R".equals(g) || "N".equals(g);
                if (!Float.isNaN(vCruiseKph) && vCruiseKph > 0f && vCruiseKph < 250f && !nonDrive) {
                    target.cruiseRawKph = Math.round(vCruiseKph);
                } else {
                    target.cruiseRawKph = 0;
                }
                if (nonDrive) {
                    target.cruiseActive = false;
                    target.cruiseKph = 0;
                } else if (target.enabledKnown) {
                    target.cruiseKph = (target.cruiseActive && target.cruiseRawKph > 0) ? target.cruiseRawKph : 0;
                } else {
                    target.cruiseKph = target.cruiseRawKph;
                    target.cruiseActive = (carrotCruise > 0) || (target.cruiseKph > 0);
                }
                if (!Float.isNaN(fl) && fl >= 15f && fl <= 65f) target.tpmsFlPsi = fl;
                if (!Float.isNaN(fr) && fr >= 15f && fr <= 65f) target.tpmsFrPsi = fr;
                if (!Float.isNaN(rl) && rl >= 15f && rl <= 65f) target.tpmsRlPsi = rl;
                if (!Float.isNaN(rr) && rr >= 15f && rr <= 65f) target.tpmsRrPsi = rr;
                if (!Float.isNaN(steerDeg) && Math.abs(steerDeg) < 600f) {
                    target.steerDeg = steerDeg;
                }
                return true;
            }
            case 2: { // controlsState
                if (len < 18) return false;
                boolean enabled = (buf[base + 8] & 0xFF) != 0;
                float vCruise = bb.getFloat(base + 9);
                float curvature = bb.getFloat(base + 14);
                target.enabledKnown = true;
                if (!target.selfdriveSeen) target.cruiseActive = enabled;
                target.cruiseRawKph = (!Float.isNaN(vCruise) && vCruise > 0f && vCruise < 250f) ? Math.round(vCruise) : 0;
                String g = target.gearPos != null ? target.gearPos.trim().toUpperCase(Locale.US) : "";
                boolean nonDrive = "P".equals(g) || "R".equals(g) || "N".equals(g);
                target.cruiseKph = (target.cruiseActive && !nonDrive && target.cruiseRawKph > 0) ? target.cruiseRawKph : 0;
                if (!Float.isNaN(curvature) && Math.abs(curvature) < 0.5f) target.curveC2 = curvature;
                return true;
            }
            case 5: { // carrotMan
                if (len < 44) return false;
                int roadLimitSpeed = bb.getInt(base + 12);
                int xSpdType = bb.getInt(base + 16);
                int xSpdLimit = bb.getInt(base + 20);
                int xSpdDist = bb.getInt(base + 24);
                int xTurnInfo = bb.getInt(base + 32);
                int xDistToTurn = bb.getInt(base + 36);
                target.sdiType = xSpdType;
                target.sdiSpeedLimit = xSpdLimit > 0 ? xSpdLimit : Math.max(0, roadLimitSpeed);
                target.sdiDistM = Math.max(0, xSpdDist);
                target.tbtTurnInfo = xTurnInfo;
                if (xTurnInfo >= 0) target.tbtTurnInfoMs = nowMs;
                if (xDistToTurn >= 0) target.tbtDistM = xDistToTurn;
                if (xDistToTurn == 0) {
                    target.tbtTurnInfo = -1;
                    target.tbtTurnType = -1;
                }
                int p = base + 44, end = base + len;
                for (int idx = 0; idx < 3 && p + 2 <= end; idx++) {
                    int sLen = bb.getShort(p) & 0xFFFF;
                    p += 2;
                    if (p + sLen > end) break;
                    String txt = sLen > 0 ? new String(buf, p, sLen, StandardCharsets.UTF_8).trim() : "";
                    if (idx == 0) target.tbtAtcType = txt;
                    else if (!txt.isEmpty()) target.tbtRoad = txt;
                    p += sLen;
                }
                return true;
            }
            case 6: { // selfdriveState
                if (len < 9) return false;
                boolean enabled = (buf[base + 8] & 0xFF) != 0;
                target.selfdriveSeen = true;
                target.enabledKnown = true;
                target.cruiseActive = enabled;
                String g = target.gearPos != null ? target.gearPos.trim().toUpperCase(Locale.US) : "";
                boolean nonDrive = "P".equals(g) || "R".equals(g) || "N".equals(g);
                target.cruiseKph = (enabled && !nonDrive && target.cruiseRawKph > 0) ? target.cruiseRawKph : 0;
                return true;
            }
            case 14: { // carControl
                if (len < 10) return false;
                target.carControlSeen = true;
                target.latActive = (buf[base + 8] & 0xFF) != 0;
                target.longActive = (buf[base + 9] & 0xFF) != 0;
                return true;
            }
            case 9: { // modelV2
                int end = base + len, p = base + 16;
                if (p + 2 > end) return false;
                int nx = bb.getShort(p) & 0xFFFF; p += 2;
                if (nx <= 0 || nx > 64 || p + nx * 2 + 2 > end) return false;
                int xStart = p; p += nx * 2;
                int ny = bb.getShort(p) & 0xFFFF; p += 2;
                if (ny <= 0 || ny > 64 || p + ny * 2 > end) return false;
                int yStart = p; p += ny * 2;
                int n = Math.min(nx, ny);
                if (n >= 6) {
                    int k1 = Math.max(1, n / 3), k2 = Math.max(k1 + 1, (n * 2) / 3);
                    float x1 = (bb.getShort(xStart + k1 * 2) & 0xFFFF) * 0.01f;
                    float x2 = (bb.getShort(xStart + k2 * 2) & 0xFFFF) * 0.01f;
                    float y0 = bb.getShort(yStart) * 0.001f;
                    float dy1 = (bb.getShort(yStart + k1 * 2) * 0.001f) - y0;
                    float dy2 = (bb.getShort(yStart + k2 * 2) * 0.001f) - y0;
                    if (x1 > 2f && x2 > x1 + 2f) {
                        float c2 = ((dy2 / x2) - (dy1 / x1)) / (x2 - x1);
                        if (!Float.isNaN(c2) && Math.abs(c2) < 0.05f) target.curveC2 = c2;
                    }
                }
                for (int s = 0; s < 2 && p + 2 <= end; s++) {
                    int cnt = bb.getShort(p) & 0xFFFF; p += 2 + cnt * 2;
                }
                if (p + 2 <= end) {
                    int lineCnt = bb.getShort(p) & 0xFFFF; p += 2;
                    for (int li = 0; li < lineCnt && li < 8 && p + 2 <= end; li++) {
                        for (int ax = 0; ax < 3 && p + 2 <= end; ax++) {
                            int pts = bb.getShort(p) & 0xFFFF; p += 2 + pts * 2;
                        }
                    }
                    if (p + 2 <= end) {
                        int probCnt = bb.getShort(p) & 0xFFFF; p += 2;
                        if (probCnt >= 3 && p + probCnt * 4 <= end) {
                            float best = Math.max(bb.getFloat(p + 4), bb.getFloat(p + 8));
                            if (!Float.isNaN(best) && best >= 0f && best <= 1f) target.laneConfPct = Math.round(best * 100f);
                        }
                    }
                }
                return true;
            }
            case 13: { // radarState
                if (len < 50) return false;
                float dRel = bb.getFloat(base + 8), yRel = bb.getFloat(base + 12), aLead = bb.getFloat(base + 28);
                boolean status = (buf[base + 49] & 0xFF) != 0;
                if (status && !Float.isNaN(dRel) && dRel > 0.5f && dRel < 250f) {
                    target.leadDistM = dRel;
                    target.leadYRightM = (!Float.isNaN(yRel) && Math.abs(yRel) < 12f) ? -yRel : 0f;
                    target.leadAccelMps2 = (!Float.isNaN(aLead) && Math.abs(aLead) < 20f) ? aLead : 0f;
                    target.hasLead = true;
                } else {
                    target.hasLead = false;
                }
                int leadSize = radarLeadStructSize(buf, base, len);
                if (leadSize > 0) {
                    target.trafficLeads = parseRadarSideLeads(buf, bb, base, len, leadSize);
                    target.trafficMs = nowMs;
                }
                return true;
            }
            default:
                return false;
        }
    }

    private static int walkRadarLeadLists(byte[] buf, int base, int end, int size) {
        int off = base + 8 + 4 * size;
        for (int i = 0; i < RADAR_LEAD_LISTS; i++) {
            if (off >= end) return -1;
            int n = buf[off] & 0xFF;
            off += 1 + n * size;
            if (off > end) return -1;
        }
        return off;
    }

    private static int radarLeadStructSize(byte[] buf, int base, int len) {
        int end = base + len;
        if (walkRadarLeadLists(buf, base, end, RADAR_LEAD_SIZE) == end) return RADAR_LEAD_SIZE;
        for (int s = 42; s <= 96; s++) {
            if (s != RADAR_LEAD_SIZE && walkRadarLeadLists(buf, base, end, s) == end) return s;
        }
        int w = walkRadarLeadLists(buf, base, end, RADAR_LEAD_SIZE);
        return (w > 0 && w <= end) ? RADAR_LEAD_SIZE : 0;
    }

    private static float[] parseRadarSideLeads(byte[] buf, ByteBuffer bb, int base, int len, int size) {
        int end = base + len, off = base + 8 + 4 * size;
        float[] out = new float[12 * TRAFFIC_STRIDE];
        int n = 0;
        for (int li = 0; li < RADAR_LEAD_LISTS && off < end; li++) {
            int cnt = buf[off] & 0xFF;
            off += 1;
            boolean side = li == 0 || li == 2 || li == 3 || li == 4;
            for (int k = 0; k < cnt && off + size <= end; k++, off += size) {
                if (!side || n >= 12) continue;
                float d = bb.getFloat(off), y = bb.getFloat(off + 4), a = bb.getFloat(off + 20);
                float v = size >= 36 ? bb.getFloat(off + 32) : (size >= 20 ? bb.getFloat(off + 16) : Float.NaN);
                boolean st = size <= 41 || buf[off + 41] != 0;
                if (!st || Float.isNaN(d) || Float.isNaN(y) || d < 1f || d > 160f || Math.abs(y) > 15f) continue;
                int o = n * TRAFFIC_STRIDE;
                out[o] = d; out[o + 1] = -y; out[o + 2] = Float.isNaN(a) ? 0f : a; out[o + 3] = v;
                n++;
            }
        }
        if (n == out.length / TRAFFIC_STRIDE) return out;
        float[] res = new float[n * TRAFFIC_STRIDE];
        System.arraycopy(out, 0, res, 0, res.length);
        return res;
    }

    private static String httpGetText(String urlStr, int timeoutMs) throws Exception {
        byte[] bytes = httpGetBytes(urlStr, timeoutMs);
        return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null;
    }

    private static byte[] httpGetBytes(String urlStr, int timeoutMs) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) return null;
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
