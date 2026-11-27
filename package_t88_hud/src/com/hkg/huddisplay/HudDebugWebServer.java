package com.hkg.huddisplay;

import android.content.res.AssetManager;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Locale;

/**
 * Lightweight embedded HTTP test console server on :8768 for live UI/3D verification.
 * Runs in background without affecting HUD render cadence until the browser triggers control APIs.
 */
public final class HudDebugWebServer {

    private static final String TAG = "HudDebugWebServer";
    public static final int PORT = 8768;

    public interface Listener {
        void onApplyAppConfig(String mode, Integer theme, Integer pt, Boolean car3d, Integer carColor, Boolean statusPill);
        void onManualStateUpdated();
        void onCameraPreset(String preset, float yawDeg, float pitchDeg);
        JSONObject readAppConfigJson();
        JSONObject readUpdateStatusJson();
        void onTriggerUpdateAction(String action, String manifestUrl);
    }

    private final AssetManager assets;
    private final Listener listener;
    private final Object lock = new Object();
    private final HudState manual = new HudState();

    private boolean autoScurve = false;
    private boolean autoSteerSweep = false;
    private boolean linkSteer = true;
    private boolean showTraffic = true;
    private boolean trafficAnimate = true;
    private boolean trafficBrake = false;
    private float trafficLeftNearD = 16.0f;
    private float trafficLeftFarD = 48.0f;
    private float trafficRightNearD = 12.0f;
    private float trafficRightFarD = 36.0f;
    private float trafficLaneOffset = 3.5f;
    private boolean manualMode = false;

    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private byte[] cachedHtml;

    public HudDebugWebServer(AssetManager assets, Listener listener) {
        this.assets = assets;
        this.listener = listener;
        initDefaultsLocked();
    }

    private void initDefaultsLocked() {
        manual.hubConnected = true;
        manual.hubIp = "web-sim";
        manual.commaConnected = true;
        manual.commaDirectConnected = true;
        manual.commaIp = "web-sim";
        manual.hubPowertrain = "ev";
        manual.gearPos = "D";
        manual.speedKph = 78;
        manual.cruiseKph = 80;
        manual.cruiseActive = true;
        manual.latActive = true;
        manual.longActive = true;
        manual.headLamp = true;
        manual.brakeLamp = false;
        manual.pbrake = false;
        manual.hubLightTheme = false;
        manual.curveC2 = 0f;
        manual.steerDeg = 0f;
        manual.laneConfPct = 97.5f;
        manual.hasLead = true;
        manual.leadDistM = 36f;
        manual.leadYRightM = 0.2f;
        manual.leadAccelMps2 = 0.1f;

        manual.socPct = 74.5;
        manual.bmsSocPct = 71.8;
        manual.sohPct = 98.1;
        manual.remainKwh = 30.3;
        manual.dteKm = 342;
        manual.avgEconKmKwh = 5.6;
        manual.totalKw = 18.5;
        manual.hasBattTemp = true;
        manual.battTempC = 24.0;
        manual.cellMaxV = 4.02;
        manual.cellMinV = 4.00;
        manual.cellDiffMv = 20;

        manual.fuelPct = 68;
        manual.iceDteKm = 412;
        manual.avgFuelKmL = 14.6;
        manual.instFuelKmL = 16.2;
        manual.coolantC = 88;
        manual.rpm = 2150;
        manual.volt12 = 14.2;
        manual.runSec = 1840;
        manual.idleSec = 210;
        manual.odoKm = 48215.0;
        manual.tripKm = 126.4;

        manual.tpmsFlPsi = 38f;
        manual.tpmsFrPsi = 38f;
        manual.tpmsRlPsi = 39f;
        manual.tpmsRrPsi = 39f;

        manual.hvacValid = true;
        manual.hvacOn = true;
        manual.hvacAuto = true;
        manual.hvacAc = true;
        manual.hvacHeat = false;
        manual.hvacBlowerStep = 3;
        manual.hvacBlowerMode = 2;
        manual.hvacDrTempC = 21.5;
        manual.inCarTempC = 22.5;
        manual.outdoorTempC = 18.0;

        manual.mediaSource = "Bluetooth";
        manual.mediaTitle = "Supernova";
        manual.mediaArtist = "aespa";
        manual.mediaAlbum = "Armageddon";
        manual.mediaPosSec = 105;
        manual.mediaDurSec = 178;
        manual.mediaPlaying = true;

        manual.sdiType = 2;
        manual.sdiSpeedLimit = 80;
        manual.sdiDistM = 1200;
        manual.tbtTurnInfo = 13;
        manual.tbtDistM = 240;
        manual.tbtDistNextM = 450;
        manual.tbtRoad = "판교JC";
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        acceptThread = new Thread(this::runServer, "Hud-DebugWeb");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
            serverSocket = null;
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
    }

    public void setManualModeFlag(boolean enabled) {
        synchronized (lock) {
            manualMode = enabled;
        }
    }

    public HudState buildManualSnapshot(double tSec) {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            HudState s = manual.copy();
            s.hubLastRxMs = now;
            s.commaLastRxMs = now;
            s.commaDirectLastRxMs = now;
            s.tbtTurnInfoMs = now;
            boolean parked = "P".equals(s.gearPos);
            s.hubLightTheme = !s.headLamp;
            if (autoScurve && !parked) {
                s.curveC2 = (float) (Math.sin(tSec * 0.45) * 0.0010);
                s.steerDeg = -s.curveC2 * 24000f;
            }
            if (autoSteerSweep) {
                s.steerDeg = (float) (Math.sin(tSec * 1.1) * 500.0);
            }
            if (showTraffic && !parked) {
                float w1 = trafficAnimate ? (float) (Math.sin(tSec * 0.45) * 2.2) : 0f;
                float w2 = trafficAnimate ? (float) (Math.cos(tSec * 0.35) * 2.0) : 0f;
                float aAll = trafficBrake ? -2.4f : 0.0f;
                s.trafficLeads = new float[]{
                        trafficLeftNearD + w1, -trafficLaneOffset, aAll, 22f,
                        trafficLeftFarD + w2, -trafficLaneOffset, aAll, 23f,
                        trafficRightNearD + w2, trafficLaneOffset, aAll, 21f,
                        trafficRightFarD - w1 * 0.5f, trafficLaneOffset, aAll, 20f
                };
                s.trafficMs = now;
            } else {
                s.trafficLeads = null;
            }
            return s;
        }
    }

    public static String getLocalIpv4() {
        String fallback = "127.0.0.1";
        try {
            Enumeration<NetworkInterface> nifs = NetworkInterface.getNetworkInterfaces();
            while (nifs != null && nifs.hasMoreElements()) {
                NetworkInterface nif = nifs.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs != null && addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (nif.getName().startsWith("wlan")) return ip;
                        fallback = ip;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private void runServer() {
        while (running) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(PORT));
                serverSocket = ss;
                Log.i(TAG, "Listening on http://" + getLocalIpv4() + ":" + PORT);
                while (running) {
                    Socket sock = ss.accept();
                    handleClient(sock);
                }
            } catch (Throwable e) {
                if (running) {
                    Log.w(TAG, "Server accept loop error: " + e.getMessage());
                    try { Thread.sleep(1000L); } catch (InterruptedException ignored) { break; }
                }
            } finally {
                if (serverSocket != null) {
                    try { serverSocket.close(); } catch (IOException ignored) {}
                    serverSocket = null;
                }
            }
        }
    }

    private void handleClient(Socket sock) {
        try {
            sock.setSoTimeout(2500);
            InputStream is = sock.getInputStream();
            OutputStream os = sock.getOutputStream();

            String reqLine = readLine(is);
            if (reqLine == null || reqLine.isEmpty()) return;
            String[] parts = reqLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].trim().toUpperCase(Locale.US);
            String path = parts[1].trim();
            int qIdx = path.indexOf('?');
            if (qIdx >= 0) path = path.substring(0, qIdx);

            int contentLen = 0;
            String hdr;
            while ((hdr = readLine(is)) != null && !hdr.isEmpty()) {
                int col = hdr.indexOf(':');
                if (col > 0) {
                    String k = hdr.substring(0, col).trim().toLowerCase(Locale.US);
                    String v = hdr.substring(col + 1).trim();
                    if ("content-length".equals(k)) {
                        try { contentLen = Integer.parseInt(v); } catch (NumberFormatException ignored) {}
                    }
                }
            }

            if ("OPTIONS".equals(method)) {
                sendResponse(os, 204, "text/plain", new byte[0]);
                return;
            }

            byte[] bodyBytes = new byte[0];
            if (contentLen > 0 && contentLen <= 65536) {
                bodyBytes = new byte[contentLen];
                int off = 0;
                while (off < contentLen) {
                    int n = is.read(bodyBytes, off, contentLen - off);
                    if (n < 0) break;
                    off += n;
                }
            }

            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                byte[] html = loadHtml();
                sendResponse(os, 200, "text/html; charset=utf-8", html);
            } else if ("GET".equals(method) && "/api/state".equals(path)) {
                sendJson(os, 200, buildStateJson());
            } else if ("POST".equals(method) && "/api/control".equals(path)) {
                String txt = new String(bodyBytes, StandardCharsets.UTF_8);
                JSONObject j = txt.trim().isEmpty() ? new JSONObject() : new JSONObject(txt);
                applyControlJson(j);
                sendJson(os, 200, buildStateJson());
            } else if ("POST".equals(method) && "/api/preset".equals(path)) {
                String txt = new String(bodyBytes, StandardCharsets.UTF_8);
                JSONObject j = txt.trim().isEmpty() ? new JSONObject() : new JSONObject(txt);
                applyPreset(j.optString("name", ""));
                sendJson(os, 200, buildStateJson());
            } else if ("POST".equals(method) && "/api/camera".equals(path)) {
                String txt = new String(bodyBytes, StandardCharsets.UTF_8);
                JSONObject j = txt.trim().isEmpty() ? new JSONObject() : new JSONObject(txt);
                applyCamera(j);
                JSONObject ok = new JSONObject();
                ok.put("ok", true);
                sendJson(os, 200, ok);
            } else if ("GET".equals(method) && "/api/update".equals(path)) {
                JSONObject u = listener != null ? listener.readUpdateStatusJson() : new JSONObject();
                sendJson(os, 200, u);
            } else if ("POST".equals(method) && "/api/update".equals(path)) {
                String txt = new String(bodyBytes, StandardCharsets.UTF_8);
                JSONObject j = txt.trim().isEmpty() ? new JSONObject() : new JSONObject(txt);
                String action = j.optString("action", "check");
                String manifestUrl = j.optString("manifestUrl", "");
                if (listener != null) listener.onTriggerUpdateAction(action, manifestUrl);
                JSONObject u = listener != null ? listener.readUpdateStatusJson() : new JSONObject();
                sendJson(os, 200, u);
            } else {
                sendResponse(os, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable e) {
            Log.w(TAG, "Client error: " + e.getMessage());
        } finally {
            try { sock.close(); } catch (IOException ignored) {}
        }
    }

    private void applyControlJson(JSONObject j) throws Exception {
        String mode = j.has("mode") ? j.optString("mode", null) : null;
        Integer theme = parseTheme(j);
        Integer pt = parsePt(j);
        Boolean car3d = j.has("car3d") ? Boolean.valueOf(j.optBoolean("car3d")) : null;
        Integer carColor = j.has("carColor") ? Integer.valueOf(j.optInt("carColor")) : null;
        Boolean statusPill = j.has("statusPill") ? Boolean.valueOf(j.optBoolean("statusPill")) : null;

        boolean simChanged = false;
        synchronized (lock) {
            if (j.has("gearPos")) {
                String g = j.optString("gearPos", "D").toUpperCase(Locale.US);
                if ("P".equals(g) || "R".equals(g) || "N".equals(g) || "D".equals(g)) {
                    manual.gearPos = g;
                    simChanged = true;
                }
            }
            if (j.has("speedKph")) { manual.speedKph = Math.max(0, Math.min(240, j.optInt("speedKph"))); simChanged = true; }
            if (j.has("cruiseKph")) { manual.cruiseKph = Math.max(0, Math.min(200, j.optInt("cruiseKph"))); simChanged = true; }
            if (j.has("cruiseActive")) { manual.cruiseActive = j.optBoolean("cruiseActive"); simChanged = true; }
            if (j.has("latActive")) { manual.latActive = j.optBoolean("latActive"); simChanged = true; }
            if (j.has("curveC2")) {
                manual.curveC2 = (float) Math.max(-0.005, Math.min(0.005, j.optDouble("curveC2")));
                simChanged = true;
            }
            if (j.has("steerDeg")) {
                manual.steerDeg = (float) Math.max(-540.0, Math.min(540.0, j.optDouble("steerDeg")));
                simChanged = true;
            }
            if (j.has("autoScurve")) {
                autoScurve = j.optBoolean("autoScurve");
                if (autoScurve) autoSteerSweep = false;
                simChanged = true;
            }
            if (j.has("autoSteerSweep")) {
                autoSteerSweep = j.optBoolean("autoSteerSweep");
                if (autoSteerSweep) autoScurve = false;
                simChanged = true;
            }
            if (j.has("linkSteer")) { linkSteer = j.optBoolean("linkSteer"); simChanged = true; }
            if (j.has("showTraffic")) { showTraffic = j.optBoolean("showTraffic"); simChanged = true; }
            if (j.has("trafficAnimate")) { trafficAnimate = j.optBoolean("trafficAnimate"); simChanged = true; }
            if (j.has("trafficBrake")) { trafficBrake = j.optBoolean("trafficBrake"); showTraffic = true; simChanged = true; }
            if (j.has("trafficLeftNearD")) {
                trafficLeftNearD = (float) Math.max(-25.0, Math.min(80.0, j.optDouble("trafficLeftNearD")));
                showTraffic = true; simChanged = true;
            }
            if (j.has("trafficLeftFarD")) {
                trafficLeftFarD = (float) Math.max(-25.0, Math.min(95.0, j.optDouble("trafficLeftFarD")));
                showTraffic = true; simChanged = true;
            }
            if (j.has("trafficRightNearD")) {
                trafficRightNearD = (float) Math.max(-25.0, Math.min(80.0, j.optDouble("trafficRightNearD")));
                showTraffic = true; simChanged = true;
            }
            if (j.has("trafficRightFarD")) {
                trafficRightFarD = (float) Math.max(-25.0, Math.min(95.0, j.optDouble("trafficRightFarD")));
                showTraffic = true; simChanged = true;
            }
            if (j.has("trafficLaneOffset")) {
                trafficLaneOffset = (float) Math.max(2.4, Math.min(5.0, j.optDouble("trafficLaneOffset")));
                showTraffic = true; simChanged = true;
            }

            if (j.has("headLamp")) { manual.headLamp = j.optBoolean("headLamp"); simChanged = true; }
            if (j.has("brakeLamp")) { manual.brakeLamp = j.optBoolean("brakeLamp"); simChanged = true; }
            if (j.has("leftBlinker")) { manual.leftBlinker = j.optBoolean("leftBlinker"); simChanged = true; }
            if (j.has("rightBlinker")) { manual.rightBlinker = j.optBoolean("rightBlinker"); simChanged = true; }
            if (j.has("leftBlindspot")) { manual.leftBlindspot = j.optBoolean("leftBlindspot"); simChanged = true; }
            if (j.has("rightBlindspot")) { manual.rightBlindspot = j.optBoolean("rightBlindspot"); simChanged = true; }
            if (j.has("pbrake")) { manual.pbrake = j.optBoolean("pbrake"); simChanged = true; }
            if (j.has("milOn")) { manual.milOn = j.optBoolean("milOn"); simChanged = true; }
            if (j.has("screenOff")) { manual.screenOff = j.optBoolean("screenOff"); simChanged = true; }
            if (j.has("doors")) {
                JSONArray da = j.optJSONArray("doors");
                if (da != null) {
                    for (int i = 0; i < 6 && i < da.length(); i++) manual.doors[i] = da.optBoolean(i);
                    simChanged = true;
                }
            }

            if (j.has("hasLead")) { manual.hasLead = j.optBoolean("hasLead"); simChanged = true; }
            if (j.has("leadDistM")) { manual.leadDistM = (float) j.optDouble("leadDistM"); simChanged = true; }
            if (j.has("leadYRightM")) { manual.leadYRightM = (float) j.optDouble("leadYRightM"); simChanged = true; }
            if (j.has("leadAccelMps2")) { manual.leadAccelMps2 = (float) j.optDouble("leadAccelMps2"); simChanged = true; }
            if (j.has("laneConfPct")) { manual.laneConfPct = (float) j.optDouble("laneConfPct"); simChanged = true; }

            if (j.has("sdiType")) { manual.sdiType = j.optInt("sdiType"); simChanged = true; }
            if (j.has("sdiSpeedLimit")) { manual.sdiSpeedLimit = j.optInt("sdiSpeedLimit"); simChanged = true; }
            if (j.has("sdiDistM")) { manual.sdiDistM = j.optInt("sdiDistM"); simChanged = true; }
            if (j.has("tbtTurnInfo")) { manual.tbtTurnInfo = j.optInt("tbtTurnInfo"); simChanged = true; }
            if (j.has("tbtDistM")) { manual.tbtDistM = j.optInt("tbtDistM"); simChanged = true; }
            if (j.has("tbtDistNextM")) { manual.tbtDistNextM = j.optInt("tbtDistNextM"); simChanged = true; }
            if (j.has("tbtRoad")) { manual.tbtRoad = j.optString("tbtRoad", ""); simChanged = true; }

            if (j.has("socPct")) {
                manual.socPct = Math.max(0.0, Math.min(100.0, j.optDouble("socPct")));
                manual.bmsSocPct = Math.max(0.0, manual.socPct - 2.5);
                manual.remainKwh = Math.round(manual.socPct * 0.406 * 10.0) / 10.0;
                simChanged = true;
            }
            if (j.has("dteKm")) { manual.dteKm = j.optInt("dteKm"); simChanged = true; }
            if (j.has("totalKw")) { manual.totalKw = j.optDouble("totalKw"); simChanged = true; }
            if (j.has("battTempC")) { manual.battTempC = j.optDouble("battTempC"); manual.hasBattTemp = true; simChanged = true; }

            if (j.has("fuelPct")) { manual.fuelPct = Math.max(0, Math.min(100, j.optInt("fuelPct"))); simChanged = true; }
            if (j.has("iceDteKm")) { manual.iceDteKm = j.optInt("iceDteKm"); simChanged = true; }
            if (j.has("rpm")) { manual.rpm = Math.max(0, j.optInt("rpm")); simChanged = true; }
            if (j.has("coolantC")) { manual.coolantC = j.optInt("coolantC"); simChanged = true; }

            if (j.has("tpmsFlPsi")) { manual.tpmsFlPsi = (float) j.optDouble("tpmsFlPsi"); simChanged = true; }
            if (j.has("tpmsFrPsi")) { manual.tpmsFrPsi = (float) j.optDouble("tpmsFrPsi"); simChanged = true; }
            if (j.has("tpmsRlPsi")) { manual.tpmsRlPsi = (float) j.optDouble("tpmsRlPsi"); simChanged = true; }
            if (j.has("tpmsRrPsi")) { manual.tpmsRrPsi = (float) j.optDouble("tpmsRrPsi"); simChanged = true; }

            if (j.has("hvacOn")) { manual.hvacOn = j.optBoolean("hvacOn"); manual.hvacValid = true; simChanged = true; }
            if (j.has("hvacAuto")) { manual.hvacAuto = j.optBoolean("hvacAuto"); simChanged = true; }
            if (j.has("hvacAc")) { manual.hvacAc = j.optBoolean("hvacAc"); simChanged = true; }
            if (j.has("hvacHeat")) { manual.hvacHeat = j.optBoolean("hvacHeat"); simChanged = true; }
            if (j.has("hvacDrTempC")) { manual.hvacDrTempC = j.optDouble("hvacDrTempC"); simChanged = true; }
            if (j.has("hvacBlowerStep")) { manual.hvacBlowerStep = Math.max(0, Math.min(7, j.optInt("hvacBlowerStep"))); simChanged = true; }

            if (mode == null && simChanged) {
                mode = "manual";
            }
            if (mode != null) {
                manualMode = "manual".equals(mode);
            }
        }

        if (listener != null) {
            if (mode != null || theme != null || pt != null || car3d != null || carColor != null || statusPill != null) {
                listener.onApplyAppConfig(mode, theme, pt, car3d, carColor, statusPill);
            }
            if (simChanged) {
                listener.onManualStateUpdated();
            }
        }
    }

    private void applyPreset(String name) {
        synchronized (lock) {
            if ("live".equals(name)) {
                manualMode = false;
                if (listener != null) listener.onApplyAppConfig("live", null, null, null, null, null);
                return;
            }
            manualMode = true;
            autoScurve = false;
            autoSteerSweep = false;
            manual.screenOff = false;
            manual.milOn = false;
            manual.leftBlinker = false;
            manual.rightBlinker = false;
            manual.leftBlindspot = false;
            manual.rightBlindspot = false;
            for (int i = 0; i < 6; i++) manual.doors[i] = false;

            Integer wantTheme = null;
            Integer wantPt = null;
            if ("park_night".equals(name)) {
                manual.gearPos = "P"; manual.speedKph = 0; manual.pbrake = true;
                manual.headLamp = true; manual.brakeLamp = true;
                manual.cruiseActive = false; manual.latActive = false;
                manual.steerDeg = -220f;
                manual.totalKw = 1.2;
                wantTheme = Integer.valueOf(HudState.THEME_DARK);
            } else if ("park_doors".equals(name)) {
                manual.gearPos = "P"; manual.speedKph = 0; manual.pbrake = true;
                manual.headLamp = true; manual.brakeLamp = false;
                manual.cruiseActive = false; manual.latActive = false;
                for (int i = 0; i < 6; i++) manual.doors[i] = true;
                wantTheme = Integer.valueOf(HudState.THEME_LIGHT);
            } else if ("drive_right".equals(name)) {
                manual.gearPos = "D"; manual.speedKph = 84; manual.pbrake = false;
                manual.cruiseActive = true; manual.cruiseKph = 85; manual.latActive = true;
                manual.headLamp = true; manual.brakeLamp = false;
                manual.curveC2 = 0.0011f; manual.steerDeg = -26f;
                manual.hasLead = true; manual.leadDistM = 34f; manual.leadYRightM = 0.1f; manual.leadAccelMps2 = 0.1f;
                wantTheme = Integer.valueOf(HudState.THEME_DARK);
            } else if ("drive_left".equals(name)) {
                manual.gearPos = "D"; manual.speedKph = 82; manual.pbrake = false;
                manual.cruiseActive = true; manual.cruiseKph = 85; manual.latActive = true;
                manual.headLamp = true; manual.brakeLamp = false;
                manual.curveC2 = -0.0011f; manual.steerDeg = 26f;
                manual.hasLead = true; manual.leadDistM = 34f; manual.leadYRightM = -0.1f; manual.leadAccelMps2 = 0.1f;
                wantTheme = Integer.valueOf(HudState.THEME_DARK);
            } else if ("drive_brake".equals(name)) {
                manual.gearPos = "D"; manual.speedKph = 52; manual.pbrake = false;
                manual.cruiseActive = true; manual.cruiseKph = 80; manual.latActive = true;
                manual.headLamp = true; manual.brakeLamp = true;
                manual.curveC2 = 0f; manual.steerDeg = 0f; manual.totalKw = -28.0;
                manual.hasLead = true; manual.leadDistM = 10f; manual.leadYRightM = 0f; manual.leadAccelMps2 = -2.8f;
                wantTheme = Integer.valueOf(HudState.THEME_DARK);
            } else if ("reverse".equals(name)) {
                manual.gearPos = "R"; manual.speedKph = 8; manual.pbrake = false;
                manual.cruiseActive = false; manual.latActive = false;
                manual.headLamp = true; manual.brakeLamp = false;
                manual.curveC2 = 0f; manual.steerDeg = 42f;
                wantTheme = Integer.valueOf(HudState.THEME_DARK);
            } else if ("ice_day".equals(name)) {
                manual.gearPos = "D"; manual.speedKph = 96; manual.pbrake = false;
                manual.cruiseActive = true; manual.cruiseKph = 100; manual.latActive = true;
                manual.headLamp = false; manual.brakeLamp = false;
                manual.curveC2 = 0.0007f; manual.steerDeg = -17f;
                manual.rpm = 2450; manual.fuelPct = 64;
                wantTheme = Integer.valueOf(HudState.THEME_LIGHT);
                wantPt = Integer.valueOf(HudState.PT_ICE);
            } else if ("traffic_close".equals(name)) {
                manual.gearPos = "D"; manual.speedKph = 42; manual.pbrake = false;
                manual.cruiseActive = true; manual.cruiseKph = 60; manual.latActive = true;
                manual.headLamp = true; manual.brakeLamp = false;
                manual.curveC2 = 0.0004f; manual.steerDeg = -10f;
                manual.hasLead = true; manual.leadDistM = 12f; manual.leadYRightM = 0f; manual.leadAccelMps2 = 0f;
                showTraffic = true;
                trafficAnimate = false;
                trafficBrake = false;
                trafficLeftNearD = -1.5f;
                trafficRightNearD = 3.5f;
                trafficLeftFarD = 15.0f;
                trafficRightFarD = -10.0f;
                trafficLaneOffset = 3.4f;
                wantTheme = Integer.valueOf(HudState.THEME_DARK);
            } else if ("bsd_warn".equals(name)) {
                manual.gearPos = "D"; manual.speedKph = 76; manual.pbrake = false;
                manual.cruiseActive = true; manual.cruiseKph = 80; manual.latActive = true;
                manual.headLamp = true; manual.brakeLamp = false;
                manual.leftBlinker = true; manual.leftBlindspot = true;
                manual.curveC2 = 0f; manual.steerDeg = 0f;
                showTraffic = true;
                trafficAnimate = false;
                trafficBrake = false;
                trafficLeftNearD = -1.5f;
                trafficRightNearD = 18.0f;
                trafficLeftFarD = 26.0f;
                trafficRightFarD = 42.0f;
                trafficLaneOffset = 3.4f;
                wantTheme = Integer.valueOf(HudState.THEME_DARK);
            }
            if (listener != null) {
                listener.onApplyAppConfig("manual", wantTheme, wantPt, null, null, null);
                listener.onManualStateUpdated();
            }
        }
    }

    private void applyCamera(JSONObject j) {
        if (listener == null) return;
        String preset = j.optString("preset", "");
        float yaw = (float) j.optDouble("yaw", 0.0);
        float pitch = (float) j.optDouble("pitch", 0.0);
        listener.onCameraPreset(preset, yaw, pitch);
    }

    private static Integer parseTheme(JSONObject j) {
        if (!j.has("theme")) return null;
        String t = j.optString("theme", "auto").toLowerCase(Locale.US);
        if ("dark".equals(t)) return Integer.valueOf(HudState.THEME_DARK);
        if ("light".equals(t)) return Integer.valueOf(HudState.THEME_LIGHT);
        return Integer.valueOf(HudState.THEME_AUTO);
    }

    private static Integer parsePt(JSONObject j) {
        if (!j.has("pt")) return null;
        String p = j.optString("pt", "auto").toLowerCase(Locale.US);
        if ("ev".equals(p)) return Integer.valueOf(HudState.PT_EV);
        if ("ice".equals(p)) return Integer.valueOf(HudState.PT_ICE);
        return Integer.valueOf(HudState.PT_AUTO);
    }

    private JSONObject buildStateJson() throws Exception {
        JSONObject root = new JSONObject();
        JSONObject app = listener != null ? listener.readAppConfigJson() : new JSONObject();
        app.put("serverIp", getLocalIpv4());
        app.put("serverPort", PORT);
        root.put("app", app);

        JSONObject sim = new JSONObject();
        synchronized (lock) {
            sim.put("hubConnected", manual.hubConnected);
            sim.put("gearPos", manual.gearPos);
            sim.put("speedKph", manual.speedKph);
            sim.put("cruiseKph", manual.cruiseKph);
            sim.put("cruiseActive", manual.cruiseActive);
            sim.put("latActive", manual.latActive);
            sim.put("curveC2", manual.curveC2);
            sim.put("steerDeg", manual.steerDeg);
            sim.put("autoScurve", autoScurve);
            sim.put("autoSteerSweep", autoSteerSweep);
            sim.put("linkSteer", linkSteer);
            sim.put("showTraffic", showTraffic);
            sim.put("trafficAnimate", trafficAnimate);
            sim.put("trafficBrake", trafficBrake);
            sim.put("trafficLeftNearD", trafficLeftNearD);
            sim.put("trafficLeftFarD", trafficLeftFarD);
            sim.put("trafficRightNearD", trafficRightNearD);
            sim.put("trafficRightFarD", trafficRightFarD);
            sim.put("trafficLaneOffset", trafficLaneOffset);

            sim.put("headLamp", manual.headLamp);
            sim.put("brakeLamp", manual.brakeLamp);
            sim.put("leftBlinker", manual.leftBlinker);
            sim.put("rightBlinker", manual.rightBlinker);
            sim.put("leftBlindspot", manual.leftBlindspot);
            sim.put("rightBlindspot", manual.rightBlindspot);
            sim.put("pbrake", manual.pbrake);
            sim.put("milOn", manual.milOn);
            sim.put("screenOff", manual.screenOff);
            JSONArray da = new JSONArray();
            for (int i = 0; i < 6; i++) da.put(manual.doors[i]);
            sim.put("doors", da);

            sim.put("hasLead", manual.hasLead);
            sim.put("leadDistM", manual.leadDistM);
            sim.put("leadYRightM", manual.leadYRightM);
            sim.put("leadAccelMps2", manual.leadAccelMps2);
            sim.put("laneConfPct", manual.laneConfPct);

            sim.put("sdiType", manual.sdiType);
            sim.put("sdiSpeedLimit", manual.sdiSpeedLimit);
            sim.put("sdiDistM", manual.sdiDistM);
            sim.put("tbtTurnInfo", manual.tbtTurnInfo);
            sim.put("tbtDistM", manual.tbtDistM);

            sim.put("socPct", manual.socPct);
            sim.put("totalKw", manual.totalKw);
            sim.put("fuelPct", manual.fuelPct);
            sim.put("rpm", manual.rpm);

            sim.put("tpmsFlPsi", manual.tpmsFlPsi);
            sim.put("tpmsFrPsi", manual.tpmsFrPsi);
            sim.put("tpmsRlPsi", manual.tpmsRlPsi);
            sim.put("tpmsRrPsi", manual.tpmsRrPsi);

            sim.put("hvacOn", manual.hvacOn);
            sim.put("hvacAuto", manual.hvacAuto);
            sim.put("hvacAc", manual.hvacAc);
            sim.put("hvacHeat", manual.hvacHeat);
            sim.put("hvacDrTempC", manual.hvacDrTempC);
            sim.put("hvacBlowerStep", manual.hvacBlowerStep);
        }
        root.put("sim", sim);
        return root;
    }

    private byte[] loadHtml() throws IOException {
        if (cachedHtml != null) return cachedHtml;
        try (InputStream is = assets.open("debug_web.html");
             ByteArrayOutputStream baos = new ByteArrayOutputStream(16384)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            cachedHtml = baos.toByteArray();
            return cachedHtml;
        }
    }

    private static String readLine(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(128);
        int c;
        boolean any = false;
        while ((c = is.read()) != -1) {
            any = true;
            if (c == '\n') break;
            if (c != '\r') baos.write(c);
            if (baos.size() > 4096) break;
        }
        if (!any) return null;
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void sendJson(OutputStream os, int code, JSONObject json) throws IOException {
        sendResponse(os, code, "application/json; charset=utf-8", json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void sendResponse(OutputStream os, int code, String ctype, byte[] body) throws IOException {
        String status = code == 200 ? "OK" : (code == 204 ? "No Content" : "Not Found");
        String hdr = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + ctype + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: Content-Type\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        os.write(hdr.getBytes(StandardCharsets.US_ASCII));
        if (body.length > 0) os.write(body);
        os.flush();
    }
}
