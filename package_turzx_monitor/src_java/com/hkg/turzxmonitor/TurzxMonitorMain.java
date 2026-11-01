package com.hkg.turzxmonitor;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * HKG Standard Gen5W (Android 4.4.2, API 19) TURZX 9.2" (1920x462) USB Cluster HUD Monitor.
 *
 * Package ID: hkg.turzxmonitor
 * Runs via /system/bin/app_process (uid=root) and provides:
 * 1. Direct USB Bulk Frame Pusher for TURZX 9.2" IPS (VID 0x1CBE / PID 0x0092) using
 *    512-byte DES-CBC ("slv3tuzx") command headers + rotated 462x1920 JPEG bulk transfers.
 * 2. Native Android Canvas 1920x462 Cluster HUD Renderer matching the ccNC 'Seon' 3-column layout:
 *    - Column 1: 2x2 EV Instrument Cluster (SoC/SOH/kWh, DTE/Econ, Power/Subsystems, Cell/Temp) + 4-Wheel TPMS
 *    - Column 2: 3D Perspective Comma ADAS Road + Red/Blue Circular SDI Speed Limit Sign + TBT + Hero Speed/Cruise
 *    - Column 3: 1:1 Vertical Split — Now Playing Media (top 50%) + DATC Climate with Large 3-Arrow Air Direction,
 *      7-Step Fan Bars, and Indoor/Outdoor Temperatures (bottom 50%)
 * 3. Real-Time 4-Domain Telemetry Collector:
 *    - EV & BMS (com.hkmc.micom.hev, com.hkmc.micom.hscanmicomservice, TM_HSCANPacketRunner logcat fallback,
 *      tm_hscan_service, NavigationCluster, and shared /data/local/tmp/hkg_monitor_state.json)
 *    - DATC Climate (com.hkmc.micom.datc IDATCService + VCRM2.0 DATC3/DATC4 & UDS 61 01 indoor/outdoor temps)
 *    - Comma 3X (ajouatom/openpilot carrot-wip) UDP (:7705/:7706) + CVS1 Binary WebSocket (ws://<ip>:7000/ws/raw)
 *    - Now Playing Media (system properties / media broadcast cache / AudioManager state)
 * 4. Embedded Web Server (:8766) serving turzx_92_mockup.html, /status.json, and /frame.jpg +
 *    Gen5W WindowManager (TYPE_VOLUME_OVERLAY 2020) Control & Live Preview Overlay.
 */
public class TurzxMonitorMain {

    public static final String MONITOR_VERSION = "1.0.0";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String CMD_FILE_PATH = "/data/local/tmp/hkg_turzxmonitor_cmd";
    private static final String SHARED_SYSMONITOR_JSON_PATH = "/data/local/tmp/hkg_monitor_state.json";
    private static final String TURZX_JSON_STATE_PATH = "/data/local/tmp/hkg_turzx_state.json";
    private static final String DEBUG_DUMP_LOCAL_PATH = "/data/local/tmp/hkg_turzxmonitor_debug.txt";
    private static final String OTA_RELOAD_TRIGGER_PATH = "/data/local/tmp/hkg_turzxmonitor_ota_reload";
    public static final String GITHUB_RAW_HOST = "raw.githubusercontent.com";
    public static final String GITHUB_OTA_BASE_PATH = "/GRT47/HKG_5W_TurzxMonitor/main/ota";
    public static final int HTTP_PORT = 8766;
    public static final int CARROT_UDP_PORT_PRIMARY = 7705;
    public static final int CARROT_UDP_PORT_BROADCAST = 7706;
    public static final int CARROT_WS_PORT = 7000;

    // TURZX 9.2" (1920x462) USB constants (verified on live hardware USB\VID_1CBE&PID_0092 firmware turzx_0001_0015)
    public static final int TURZX_USB_VID = 0x1CBE;
    public static final int TURZX_USB_PID_92 = 0x0092;
    public static final int TURZX_USB_PID_88 = 0x0088;
    public static final int TURZX_EP_OUT = 0x01;
    public static final int TURZX_EP_IN = 0x81;
    public static final int TURZX_CMD_HELLO = 10;       // 0x0A Handshake / Sync (replies "turzx_0001_0015")
    public static final int TURZX_CMD_BRIGHTNESS = 14;  // 0x0E Brightness (0..102 at plain[8])
    public static final int TURZX_CMD_FPS = 15;         // 0x0F Frame rate (plain[8])
    public static final int TURZX_CMD_SEND_JPEG = 101;  // 0x65 Send 464x1920 hardware-decoded JPEG frame
    public static final int TURZX_CMD_SEND_PNG = 102;   // 0x66 Send 480x1920 RGBA PNG overlay frame
    public static final int TURZX_CMD_STOP_VIDEO = 111; // 0x6F Stop internal storage video playback
    private static final byte[] TURZX_DES_KEY_BYTES = new byte[]{
            0x73, 0x6C, 0x76, 0x33, 0x74, 0x75, 0x7A, 0x78 // "slv3tuzx"
    };

    // HKG Gen5W Gear & HEV & DATC constants (verified from automotive.odex & com.infobank.odex)
    public static final int GEAR_STATUS_P = 0;
    public static final int GEAR_STATUS_D = 5;
    public static final int GEAR_STATUS_N = 6;
    public static final int GEAR_STATUS_R = 7;

    public static final int TRIP_UNIT_KM = 1;
    public static final int TRIP_UNIT_MILE = 2;

    public static final int HEV_FLAG_AVG_FCI = 2;
    public static final int HEV_FLAG_TERM_AVG_FCI = 4;
    public static final int HEV_FLAG_ELECTRIC_DTE = 9;
    public static final int HEV_FLAG_TOTAL_DTE = 11;
    public static final int HEV_FLAG_TRIP_UNIT = 12;
    public static final int HEV_FLAG_CHARGE_STATE_SOC_PC = 13;
    public static final int HEV_FLAG_AVG_FCL_DRIVE_INFO = 22;
    public static final int HEV_FLAG_DISPLAY_SOC = 25;
    public static final int HEV_FLAG_STD_CHA = 28;
    public static final int HEV_FLAG_CHG_STS = 29;
    public static final int HEV_FLAG_PWR_OTHER_ECU = 36;
    public static final int HEV_FLAG_MOTER_PWR_AVN = 37;
    public static final int HEV_FLAG_ADDED_DIST_DATC_OFF = 38;
    public static final int HEV_FLAG_DISPLAY_DTE_OFF = 39;
    public static final int HEV_FLAG_FAST_CHARGING = 40;
    public static final int HEV_FLAG_PWR_E_COMPRESSOR = 42;
    public static final int HEV_FLAG_EV_RDY = 44;
    public static final int HEV_FLAG_VCU_RDY = 45;
    public static final int HEV_FLAG_ENERGYMAP_1000MS = 49;
    public static final int HEV_FLAG_DATC_BATTERYCOMPPWRCON_W = 51;
    public static final int HEV_FLAG_BMS_EVENERGYCAPA = 53;
    public static final int HEV_FLAG_VCU_DTEOFFDISPSTA = 64;
    public static final int HEV_FLAG_DATC_PTCPWRCNSMPTNVAL = 65;
    public static final int HEV_FLAG_XEV_BATHEATPWRMONCON_W = 67;
    public static final int HEV_FLAG_MCU_MG1PWRAVNKWVAL = 69;
    public static final int HEV_FLAG_MCU_MG2PWRAVNKWVAL = 70;
    public static final int HEV_FLAG_DATC_CLMTPWRCNSMPTN_W = 79;
    public static final int HEV_FLAG_EV_BATTERYCONDITIONING = 94;

    public static final int DATC_FLAG_BLOWER_LEVEL = 5;
    public static final int DATC_FLAG_BLOWER_MODE = 6;
    public static final int DATC_FLAG_AUTO = 8;
    public static final int DATC_FLAG_AC = 9;
    public static final int DATC_FLAG_FRONT_DEFOG = 10;
    public static final int DATC_FLAG_INTAKE = 11;
    public static final int DATC_FLAG_OP_STATE = 12;
    public static final int DATC_FLAG_SYNC = 22;
    public static final int DATC_FLAG_ADS_ON_OFF = 23;
    public static final int DATC_FLAG_DRIVER_ONLY = 24;
    public static final int DATC_FLAG_TEMP_DR_C = 28;
    public static final int DATC_FLAG_TEMP_PS_C = 29;
    public static final int DATC_FLAG_TEMP_OUTSIDE_C = 32;

    public static final class MonitorConfig {
        public boolean overlayEnabled = true;
        public boolean overlayExpanded = false;
        public boolean usbPushEnabled = true;
        public int usbBrightness = 85;
        public int usbRotation = 270;     // 270 or 90 (1920x462 -> 462x1920 portrait scanout)
        public int usbJpegQuality = 85;
        public int usbFps = 12;
        public String commaIp = "";
        public boolean autoUpdate = true;
        public int x = 24;
        public int y = 64;
    }

    public static final class TelemetrySnapshot {
        // 1. EV & BMS
        public double displaySocPct = -1.0;
        public double bmsSocPct = -1.0;
        public double remainEnergyKwh = -1.0;
        public double sohPct = -1.0;
        public int dteKm = -1;
        public int dteAirconOffDeltaKm = 0;
        public boolean distanceUnitMiles = false;
        public double avgEconomyKmKwh = -1.0;

        public double packVoltV = -1.0;
        public double packCurrA = 0.0;
        public boolean hasPackCurrent = false;
        public double packPowerKw = 0.0;
        public double drivePowerKw = 0.0;
        public double climatePowerKw = 0.0;
        public double electronicsPowerKw = 0.0;
        public double batteryCarePowerKw = 0.0;
        public boolean hasSubPower = false;

        public double cellMaxVoltV = -1.0;
        public int cellMaxNo = 0;
        public double cellMinVoltV = -1.0;
        public int cellMinNo = 0;
        public int cellDiffMv = -1;

        public Integer battMaxTempC = null;
        public Integer battMinTempC = null;
        public Integer battInletTempC = null;
        public Integer battHeaterTempC = null;
        public double auxBattVoltV = -1.0;
        public int auxBattSocPct = -1;
        public double cumulChargeKwh = -1.0;
        public double cumulDischargeKwh = -1.0;

        public boolean evReady = false;
        public boolean charging = false;
        public boolean fastCharging = false;
        public boolean stdCharging = false;
        public boolean batteryConditioning = false;
        public String gearPos = "D";
        public double accelPedalPct = -1.0;

        // 2. DATC Climate & Indoor/Outdoor Temps
        public boolean hasClimateData = false;
        public boolean hvacOn = true;
        public boolean hvacAuto = true;
        public boolean hvacAc = true;
        public boolean hvacSync = false;
        public boolean hvacDriverOnly = false;
        public boolean hvacFrontDefog = false;
        public boolean hvacRecirc = true;
        public boolean hvacAds = true;
        public int hvacBlowerStep = 3;      // 0..8
        public int hvacBlowerMode = 2;      // 1=VENT, 2=B/L, 3=FLOOR, 4=MIX, 5=DEF
        public double hvacDrTempC = 22.0;
        public double hvacPsTempC = 22.5;
        public double outdoorTempC = 18.0;
        public double inCarTempC = 22.5;

        // 3. Comma 3X (carrot-wip) ADAS + SDI/TBT + TPMS
        public boolean carrotConnected = false;
        public int carrotSpeedKph = 0;
        public int carrotCruiseKph = 0;
        public boolean carrotActive = false;
        public int carrotSdiType = -1;        // >= 0: active camera (Red circle), < 0: normal limit (Blue circle)
        public int carrotSdiSpeedLimit = 80;  // km/h
        public int carrotSdiDistM = 0;
        public int carrotTbtDistM = 0;
        public String carrotTbtRoad = "경부고속도로 · 판교 JC 방면";
        public String carrotIp = "";
        public float leadDistM = 32.0f;
        public float leadRelVelKph = 0.0f;
        public boolean hasLeadCar = true;
        public float[] pathPoly = new float[]{0.0f, 0.0f, 0.00015f, 0.0f};
        public float tpmsFlPsi = 36.0f;
        public float tpmsFrPsi = 36.0f;
        public float tpmsRlPsi = 35.0f;
        public float tpmsRrPsi = 36.0f;

        // 4. Now Playing Media
        public String mediaSource = "Bluetooth Audio";
        public String mediaTitle = "밤양갱 (Bam Yang Gang)";
        public String mediaArtist = "비비 (BIBI)";
        public String mediaAlbum = "밤양갱 - Single";
        public boolean mediaPlaying = true;
        public int mediaPositionSec = 108;
        public int mediaDurationSec = 166;

        public double getEffectiveSocPct() {
            if (displaySocPct >= 0.0) return displaySocPct;
            if (bmsSocPct >= 0.0) return bmsSocPct;
            return -1.0;
        }

        public Integer getRepresentativeBattTempC() {
            if (battMinTempC != null) return battMinTempC;
            if (battMaxTempC != null) return battMaxTempC;
            if (battInletTempC != null) return battInletTempC;
            return null;
        }

        public double getTotalPowerKw() {
            double subSum = Math.max(0.0, drivePowerKw)
                    + Math.max(0.0, climatePowerKw)
                    + Math.max(0.0, electronicsPowerKw)
                    + Math.max(0.0, batteryCarePowerKw);
            if (charging && hasPackCurrent && packPowerKw < -0.05) {
                return packPowerKw;
            }
            if (hasPackCurrent && packPowerKw < -0.4) {
                return packPowerKw;
            }
            if (hasSubPower && subSum > 0.005) {
                if (hasPackCurrent && packPowerKw > subSum + 0.8) {
                    return packPowerKw;
                }
                return subSum;
            }
            if (hasPackCurrent) {
                return packPowerKw;
            }
            return 0.0;
        }

        public double getEffectiveDriveMotorKw() {
            if (charging) return 0.0;
            double auxKw = Math.max(0.0, climatePowerKw)
                    + Math.max(0.0, electronicsPowerKw)
                    + Math.max(0.0, batteryCarePowerKw);
            if (hasPackCurrent && packPowerKw < -0.5) {
                return packPowerKw;
            }
            if (drivePowerKw > 0.05) {
                if (hasPackCurrent && packPowerKw > drivePowerKw + auxKw + 0.8) {
                    return Math.max(drivePowerKw, packPowerKw - auxKw);
                }
                return drivePowerKw;
            }
            if (hasPackCurrent && packPowerKw > 0.1) {
                return Math.max(0.0, packPowerKw - auxKw);
            }
            return 0.0;
        }
    }

    public static void main(String[] args) {
        if (args.length > 0 && "test-self".equals(args[0])) {
            runSelfTests();
            return;
        }

        String mode = args.length > 0 ? args[0] : "daemon";
        String installDir = args.length > 1 ? args[1] : "/data/nemesis/apps/hkg.turzxmonitor";

        if ("daemon".equals(mode)) {
            runDaemon(installDir);
        } else {
            System.out.println("Usage: TurzxMonitorMain <daemon|test-self> [installDir]");
        }
    }

    private static void runDaemon(final String installDir) {
        System.out.println("[TurzxMonitor] Starting TURZX 9.2 Cluster HUD daemon v" + MONITOR_VERSION + " (dir=" + installDir + ")");
        DebugManager.logEvent("DAEMON", "Started daemon v" + MONITOR_VERSION + " at " + installDir);
        TurzxOverlayController.init(installDir);
        TurzxUsbDriver.startUsbPusherLoop(installDir);
        OtaUpdateManager.startAutoUpdateWorker(installDir);

        final File cmdFile = new File(CMD_FILE_PATH);
        while (true) {
            try {
                if (TurzxOverlayController.isUiLooperReady() && cmdFile.exists()) {
                    String cmd = readFirstLineQuick(cmdFile).toLowerCase(Locale.US);
                    cmdFile.delete();
                    DebugManager.logEvent("CMD", "Received command: " + cmd);
                    if ("toggle".equals(cmd)) {
                        TurzxOverlayController.toggle();
                    } else if ("hide".equals(cmd) || "off".equals(cmd)) {
                        TurzxOverlayController.setEnabledAndShow(false);
                    } else if ("update".equals(cmd) || "ota".equals(cmd)) {
                        OtaUpdateManager.triggerManualOta(installDir, false);
                    } else if ("force-update".equals(cmd) || "force-ota".equals(cmd)) {
                        OtaUpdateManager.triggerManualOta(installDir, true);
                    } else if ("dump".equals(cmd) || "debug".equals(cmd) || "log".equals(cmd)) {
                        DebugManager.triggerAsyncDump(installDir);
                    } else {
                        TurzxOverlayController.setEnabledAndShow(true);
                    }
                }
                Thread.sleep(250L);
            } catch (Throwable t) {
                DebugManager.logError("DAEMON", "cmdLoop", t);
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    // =========================================================================
    // 1. TURZX 9.2" (1920x462, VID 0x1CBE / PID 0x0092) USB BULK PROTOCOL DRIVER
    // =========================================================================

    private static volatile byte[] cachedTransparentPng480x1920 = null;

    /**
     * Builds the 512-byte TURZX V1.x (turzx_0001_0015) USB command header:
     * - Plaintext [0..503] (504 bytes = 63 * 8-byte DES blocks):
     *   plain[0] = (byte) cmdId (10=HELLO, 14=BRIGHTNESS, 15=FPS, 101=SEND_JPEG, 102=SEND_PNG, 111=STOP_VIDEO)
     *   plain[1] = 0x00
     *   plain[2] = 0x1A, plain[3] = 0x6D (mandatory protocol magic)
     *   plain[4..7] = 32-bit little-endian milliseconds since local midnight
     *   plain[8..11]:
     *     - for cmdId 14 (Brightness) or 15 (FPS): plain[8] = (byte) payloadOrArg
     *     - for cmdId 101 (JPEG) or 102 (PNG): 32-bit big-endian payload byte length
     * - Encrypted with DES/CBC/NoPadding using key "slv3tuzx" and IV "slv3tuzx".
     * - Trailer at packet[510..511]: 0xA1, 0x1A (161, 26).
     */
    public static byte[] buildTurzxCommandPacket(int cmdId, int payloadOrArg) {
        try {
            byte[] plain = new byte[504];
            plain[0] = (byte) (cmdId & 0xFF);
            plain[1] = 0x00;
            plain[2] = (byte) 0x1A;
            plain[3] = (byte) 0x6D;

            int msOfDay = (int) (System.currentTimeMillis() % 86400000L);
            plain[4] = (byte) (msOfDay & 0xFF);
            plain[5] = (byte) ((msOfDay >>> 8) & 0xFF);
            plain[6] = (byte) ((msOfDay >>> 16) & 0xFF);
            plain[7] = (byte) ((msOfDay >>> 24) & 0xFF);

            if (cmdId == TURZX_CMD_BRIGHTNESS || cmdId == TURZX_CMD_FPS) {
                plain[8] = (byte) (payloadOrArg & 0xFF);
            } else if (payloadOrArg > 0) {
                plain[8] = (byte) ((payloadOrArg >>> 24) & 0xFF);
                plain[9] = (byte) ((payloadOrArg >>> 16) & 0xFF);
                plain[10] = (byte) ((payloadOrArg >>> 8) & 0xFF);
                plain[11] = (byte) (payloadOrArg & 0xFF);
            }

            SecretKeySpec keySpec = new SecretKeySpec(TURZX_DES_KEY_BYTES, "DES");
            IvParameterSpec ivSpec = new IvParameterSpec(TURZX_DES_KEY_BYTES);
            Cipher cipher = Cipher.getInstance("DES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
            byte[] encrypted = cipher.doFinal(plain);

            byte[] packet = new byte[512];
            System.arraycopy(encrypted, 0, packet, 0, 504);
            packet[510] = (byte) 0xA1;
            packet[511] = (byte) 0x1A;
            return packet;
        } catch (Exception e) {
            throw new RuntimeException("DES-CBC TURZX packet encryption failed", e);
        }
    }

    /**
     * Generates a valid 480x1920 all-transparent (RGBA 0x00000000) PNG image in pure Java.
     * Sent once via CMD 102 upon USB connection to clear any persistent PNG overlay layer
     * so CMD 101 hardware-decoded JPEGs are visible underneath.
     */
    public static byte[] buildTransparentOverlayPng480x1920() {
        byte[] cached = cachedTransparentPng480x1920;
        if (cached != null) return cached;
        try {
            final int width = 480;
            final int height = 1920;
            ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            // PNG 8-byte signature
            out.write(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});

            // IHDR chunk (13 bytes): width=480, height=1920, bitDepth=8, colorType=6 (RGBA)
            byte[] ihdr = new byte[13];
            ihdr[0] = (byte) ((width >>> 24) & 0xFF);
            ihdr[1] = (byte) ((width >>> 16) & 0xFF);
            ihdr[2] = (byte) ((width >>> 8) & 0xFF);
            ihdr[3] = (byte) (width & 0xFF);
            ihdr[4] = (byte) ((height >>> 24) & 0xFF);
            ihdr[5] = (byte) ((height >>> 16) & 0xFF);
            ihdr[6] = (byte) ((height >>> 8) & 0xFF);
            ihdr[7] = (byte) (height & 0xFF);
            ihdr[8] = 8; // bit depth
            ihdr[9] = 6; // color type RGBA
            ihdr[10] = 0;
            ihdr[11] = 0;
            ihdr[12] = 0;
            writePngChunk(out, "IHDR", ihdr, 0, ihdr.length);

            // IDAT chunk: 1920 rows * (1 filter byte + 480 * 4 RGBA zero bytes) = 3,688,320 zero bytes
            Deflater deflater = new Deflater(Deflater.BEST_SPEED);
            ByteArrayOutputStream idatBuf = new ByteArrayOutputStream(4096);
            byte[] zeros = new byte[1921 * 32]; // 32 scanlines per feed
            byte[] compBuf = new byte[4096];
            for (int rowGroup = 0; rowGroup < 60; rowGroup++) {
                deflater.setInput(zeros, 0, zeros.length);
                while (!deflater.needsInput()) {
                    int n = deflater.deflate(compBuf);
                    if (n > 0) idatBuf.write(compBuf, 0, n);
                }
            }
            deflater.finish();
            while (!deflater.finished()) {
                int n = deflater.deflate(compBuf);
                if (n > 0) idatBuf.write(compBuf, 0, n);
            }
            deflater.end();
            byte[] idatBytes = idatBuf.toByteArray();
            writePngChunk(out, "IDAT", idatBytes, 0, idatBytes.length);

            // IEND chunk
            writePngChunk(out, "IEND", new byte[0], 0, 0);

            cached = out.toByteArray();
            cachedTransparentPng480x1920 = cached;
            return cached;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build 480x1920 transparent PNG", e);
        }
    }

    private static void writePngChunk(OutputStream out, String type, byte[] data, int off, int len) throws Exception {
        byte[] typeBytes = type.getBytes("US-ASCII");
        out.write((len >>> 24) & 0xFF);
        out.write((len >>> 16) & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(typeBytes);
        if (len > 0) {
            out.write(data, off, len);
        }
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        if (len > 0) {
            crc.update(data, off, len);
        }
        int c = (int) crc.getValue();
        out.write((c >>> 24) & 0xFF);
        out.write((c >>> 16) & 0xFF);
        out.write((c >>> 8) & 0xFF);
        out.write(c & 0xFF);
    }

    /**
     * Decrypts the first 504 bytes of a 512-byte TURZX USB command header for verification.
     */
    public static byte[] decryptTurzxCommandPacket(byte[] packet512) {
        if (packet512 == null || packet512.length != 512) {
            throw new IllegalArgumentException("Expected 512-byte TURZX packet");
        }
        try {
            SecretKeySpec keySpec = new SecretKeySpec(TURZX_DES_KEY_BYTES, "DES");
            IvParameterSpec ivSpec = new IvParameterSpec(TURZX_DES_KEY_BYTES);
            Cipher cipher = Cipher.getInstance("DES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            return cipher.doFinal(packet512, 0, 504);
        } catch (Exception e) {
            throw new RuntimeException("DES-CBC TURZX packet decryption failed", e);
        }
    }

    public static final class TurzxUsbDriver {
        private static volatile boolean connected = false;
        private static volatile String connectedDevPath = "";
        private static volatile String statusText = "USB 검색 대기 (1cbe:0092)";
        private static volatile int pushedFrames = 0;
        private static volatile double liveFps = 0.0;
        private static volatile int lastAppliedBrightness = -1;
        private static volatile byte[] latestLandscapeJpeg = null;

        private static RandomAccessFile usbRaf = null;
        private static Object usbPfd = null;
        private static Object usbDevConn = null;
        private static Method bulkReqMethod = null;
        private static Method releaseIfcMethod = null;
        private static Method closeConnMethod = null;
        private static final byte[] RX_BUF = new byte[1024];

        public static boolean isConnected() {
            return connected;
        }

        public static String getStatusText() {
            return statusText;
        }

        public static int getPushedFrames() {
            return pushedFrames;
        }

        public static double getLiveFps() {
            return liveFps;
        }

        public static byte[] getLatestLandscapeJpeg() {
            return latestLandscapeJpeg;
        }

        public static void startUsbPusherLoop(final String installDir) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    runLoop(installDir);
                }
            }, "TurzxUsbPusher");
            t.setDaemon(true);
            t.start();
        }

        private static void runLoop(String installDir) {
            long fpsWinStartMs = System.currentTimeMillis();
            int fpsWinCount = 0;

            while (true) {
                try {
                    MonitorConfig cfg = TurzxOverlayController.getConfig();
                    boolean lcdOff = "1".equals(getSystemProperty("tcc.hwc.lcd.disable", "0"))
                            || "false".equalsIgnoreCase(getSystemProperty("sys.boot.show_screen", "true"));
                    if (lcdOff) {
                        if (connected) {
                            sendBrightnessCommand(0);
                            closeUsbConnection();
                        }
                        Thread.sleep(1500L);
                        continue;
                    }

                    long frameStartMs = System.currentTimeMillis();
                    TelemetrySnapshot snap = TelemetryCollector.pollSnapshot(TurzxOverlayController.getSysContext());

                    // Always render the 1920x462 HUD frame so both USB display and HTTP /frame.jpg + preview work
                    byte[][] jpegs = TurzxFrameRenderer.renderHudJpegPair(snap, cfg.usbRotation, cfg.usbJpegQuality);
                    if (jpegs != null && jpegs.length == 2) {
                        latestLandscapeJpeg = jpegs[0];
                        byte[] panelJpeg = jpegs[1];

                        if (cfg.usbPushEnabled) {
                            if (!connected) {
                                tryOpenTurzxUsbDevice();
                            }
                            if (connected && panelJpeg != null && panelJpeg.length > 0) {
                                if (lastAppliedBrightness != cfg.usbBrightness) {
                                    if (sendBrightnessCommand(cfg.usbBrightness)) {
                                        lastAppliedBrightness = cfg.usbBrightness;
                                    }
                                }
                                if (sendJpegFrame(panelJpeg)) {
                                    pushedFrames++;
                                    fpsWinCount++;
                                    long now = System.currentTimeMillis();
                                    if (now - fpsWinStartMs >= 1000L) {
                                        liveFps = (fpsWinCount * 1000.0) / Math.max(1L, now - fpsWinStartMs);
                                        fpsWinCount = 0;
                                        fpsWinStartMs = now;
                                    }
                                    statusText = String.format(Locale.US, "TURZX 연결됨 (%s · %.1f FPS)", connectedDevPath, liveFps);
                                } else {
                                    statusText = "USB 전송 오류 · 재연결 대기";
                                    closeUsbConnection();
                                }
                            }
                        } else {
                            if (connected) {
                                closeUsbConnection();
                            }
                            statusText = "USB 출력 일시정지 (웹 :8766 활성)";
                        }
                    }

                    int targetFps = Math.max(2, Math.min(25, cfg.usbFps));
                    long targetPeriodMs = 1000L / targetFps;
                    long elapsedMs = System.currentTimeMillis() - frameStartMs;
                    long sleepMs = Math.max(15L, targetPeriodMs - elapsedMs);
                    Thread.sleep(sleepMs);
                } catch (Throwable t) {
                    closeUsbConnection();
                    try {
                        Thread.sleep(1500L);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }

        /**
         * Scans /sys/bus/usb/devices/* for VID 1cbe & PID 0092 (or 0088) and returns
         * the corresponding /dev/bus/usb/<busnum>/<devnum> path.
         */
        public static String findTurzxUsbDevicePath() {
            File sysUsb = new File("/sys/bus/usb/devices");
            File[] dirs = sysUsb.listFiles();
            if (dirs == null) return null;
            for (int i = 0; i < dirs.length; i++) {
                File d = dirs[i];
                File fVid = new File(d, "idVendor");
                File fPid = new File(d, "idProduct");
                if (!fVid.isFile() || !fPid.isFile()) continue;
                String vid = readFirstLineQuick(fVid).toLowerCase(Locale.US);
                String pid = readFirstLineQuick(fPid).toLowerCase(Locale.US);
                if ("1cbe".equals(vid) && ("0092".equals(pid) || "0088".equals(pid))) {
                    int busNum = parseIntSafe(readFirstLineQuick(new File(d, "busnum")), -1);
                    int devNum = parseIntSafe(readFirstLineQuick(new File(d, "devnum")), -1);
                    if (busNum > 0 && devNum > 0) {
                        String path = String.format(Locale.US, "/dev/bus/usb/%03d/%03d", busNum, devNum);
                        if (new File(path).exists()) {
                            return path;
                        }
                    }
                }
            }
            return null;
        }

        private static synchronized boolean tryOpenTurzxUsbDevice() {
            closeUsbConnection();
            String devPath = findTurzxUsbDevicePath();
            if (devPath == null) {
                statusText = "TURZX USB 미감지 (1cbe:0092 대기)";
                return false;
            }
            try {
                FileDescriptor fd = null;
                try {
                    usbRaf = new RandomAccessFile(devPath, "rw");
                    fd = usbRaf.getFD();
                } catch (Throwable rafErr) {
                    // Fallback to system_server IUsbManager.openDevice(devPath) -> ParcelFileDescriptor
                    try {
                        Class<?> smCls = Class.forName("android.os.ServiceManager");
                        Object binder = smCls.getMethod("getService", String.class).invoke(null, "usb");
                        if (binder != null) {
                            Class<?> stubCls = Class.forName("android.hardware.usb.IUsbManager$Stub");
                            Object usbMgr = stubCls.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
                            Object pfd = usbMgr.getClass().getMethod("openDevice", String.class).invoke(usbMgr, devPath);
                            if (pfd != null) {
                                usbPfd = pfd;
                                fd = (FileDescriptor) pfd.getClass().getMethod("getFileDescriptor").invoke(pfd);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (fd == null) {
                    closeUsbConnection();
                    statusText = "USB 권한/FD 오픈 실패 (" + devPath + ")";
                    return false;
                }

                Class<?> usbDeviceCls = Class.forName("android.hardware.usb.UsbDevice");
                Class<?> usbConnCls = Class.forName("android.hardware.usb.UsbDeviceConnection");
                Constructor<?> connCtor = usbConnCls.getDeclaredConstructor(usbDeviceCls);
                connCtor.setAccessible(true);
                Object conn = connCtor.newInstance(new Object[]{null});

                Method nativeOpen = usbConnCls.getDeclaredMethod("native_open", String.class, FileDescriptor.class);
                nativeOpen.setAccessible(true);
                boolean opened = ((Boolean) nativeOpen.invoke(conn, devPath, fd)).booleanValue();
                if (!opened) {
                    closeUsbConnection();
                    statusText = "USB 오픈 실패 (" + devPath + ")";
                    return false;
                }

                Method claimIfc = usbConnCls.getDeclaredMethod("native_claim_interface", int.class, boolean.class);
                claimIfc.setAccessible(true);
                boolean claimed = ((Boolean) claimIfc.invoke(conn, Integer.valueOf(0), Boolean.TRUE)).booleanValue();
                if (!claimed) {
                    closeUsbConnection();
                    statusText = "USB 인터페이스 점유 실패 (" + devPath + ")";
                    return false;
                }

                bulkReqMethod = usbConnCls.getDeclaredMethod(
                        "native_bulk_request", int.class, byte[].class, int.class, int.class, int.class);
                bulkReqMethod.setAccessible(true);
                try {
                    releaseIfcMethod = usbConnCls.getDeclaredMethod("native_release_interface", int.class);
                    releaseIfcMethod.setAccessible(true);
                } catch (Throwable ignored) {
                }
                try {
                    closeConnMethod = usbConnCls.getDeclaredMethod("native_close");
                    closeConnMethod.setAccessible(true);
                } catch (Throwable ignored) {
                }

                usbDevConn = conn;
                connectedDevPath = devPath;

                // 1. Drain any stale bytes on EP_IN (0x81)
                drainBulkIn(30);

                // 2. Send CMD 10 (HELLO / Sync) -> replies "turzx_0001_0015" (512B + ZLP)
                byte[] helloPkt = buildTurzxCommandPacket(TURZX_CMD_HELLO, 0);
                if (!bulkTransferOut(helloPkt, 0, helloPkt.length, 500) || drainBulkIn(250) <= 0) {
                    closeUsbConnection();
                    statusText = "TURZX 핸드셰이크 응답 없음 (" + devPath + ")";
                    return false;
                }

                // 3. Send CMD 111 (Stop built-in storage video playback)
                byte[] stopVideoPkt = buildTurzxCommandPacket(TURZX_CMD_STOP_VIDEO, 0);
                bulkTransferOut(stopVideoPkt, 0, stopVideoPkt.length, 500);
                drainBulkIn(200);

                // 4. Send initial brightness (CMD 14)
                MonitorConfig cfg = TurzxOverlayController.getConfig();
                if (sendBrightnessCommand(cfg.usbBrightness)) {
                    lastAppliedBrightness = cfg.usbBrightness;
                } else {
                    lastAppliedBrightness = -1;
                }

                // 5. Clear the 480x1920 RGBA PNG overlay layer (CMD 102) so CMD 101 JPEGs are visible
                byte[] clearPng = buildTransparentOverlayPng480x1920();
                sendPayloadFrame(TURZX_CMD_SEND_PNG, clearPng);

                connected = true;
                statusText = "TURZX 연결됨 (" + devPath + ")";
                return true;
            } catch (Throwable t) {
                closeUsbConnection();
                statusText = "USB 초기화 오류: " + t.getClass().getSimpleName();
                return false;
            }
        }

        private static synchronized void closeUsbConnection() {
            connected = false;
            connectedDevPath = "";
            if (usbDevConn != null) {
                if (releaseIfcMethod != null) {
                    try {
                        releaseIfcMethod.invoke(usbDevConn, Integer.valueOf(0));
                    } catch (Throwable ignored) {
                    }
                }
                if (closeConnMethod != null) {
                    try {
                        closeConnMethod.invoke(usbDevConn);
                    } catch (Throwable ignored) {
                    }
                }
            }
            usbDevConn = null;
            bulkReqMethod = null;
            releaseIfcMethod = null;
            if (usbRaf != null) {
                try {
                    usbRaf.close();
                } catch (Throwable ignored) {
                }
                usbRaf = null;
            }
            if (usbPfd != null) {
                try {
                    usbPfd.getClass().getMethod("close").invoke(usbPfd);
                } catch (Throwable ignored) {
                }
                usbPfd = null;
            }
        }

        private static boolean sendBrightnessCommand(int brightnessPct) {
            int clamped = Math.max(0, Math.min(100, brightnessPct));
            int hwVal = Math.max(0, Math.min(102, (int) Math.round(clamped * 1.02)));
            byte[] pkt = buildTurzxCommandPacket(TURZX_CMD_BRIGHTNESS, hwVal);
            if (!bulkTransferOut(pkt, 0, pkt.length, 400)) {
                return false;
            }
            return drainBulkIn(200) > 0;
        }

        private static boolean sendJpegFrame(byte[] jpegBytes) {
            return sendPayloadFrame(TURZX_CMD_SEND_JPEG, jpegBytes);
        }

        private static boolean sendPayloadFrame(int cmdId, byte[] payloadBytes) {
            if (payloadBytes == null || payloadBytes.length == 0) return false;
            byte[] hdr = buildTurzxCommandPacket(cmdId, payloadBytes.length);
            if (!bulkTransferOut(hdr, 0, hdr.length, 500)) {
                return false;
            }
            int offset = 0;
            final int chunkSize = 16384;
            while (offset < payloadBytes.length) {
                int len = Math.min(chunkSize, payloadBytes.length - offset);
                if (!bulkTransferOut(payloadBytes, offset, len, 1500)) {
                    return false;
                }
                offset += len;
            }
            return drainBulkIn(350) > 0;
        }

        private static boolean bulkTransferOut(byte[] buf, int offset, int length, int timeoutMs) {
            Object conn = usbDevConn;
            Method m = bulkReqMethod;
            if (conn == null || m == null) return false;
            try {
                int transferred = ((Integer) m.invoke(
                        conn,
                        Integer.valueOf(TURZX_EP_OUT),
                        buf,
                        Integer.valueOf(offset),
                        Integer.valueOf(length),
                        Integer.valueOf(timeoutMs)
                )).intValue();
                return transferred == length;
            } catch (Throwable t) {
                return false;
            }
        }

        private static int drainBulkIn(int timeoutMs) {
            Object conn = usbDevConn;
            Method m = bulkReqMethod;
            if (conn == null || m == null) return -1;
            try {
                // Request 1024 bytes in a single URB so the 512-byte response + its trailing ZLP
                // are retired together by the USB host controller without an extra timeout wait.
                return ((Integer) m.invoke(
                        conn,
                        Integer.valueOf(TURZX_EP_IN),
                        RX_BUF,
                        Integer.valueOf(0),
                        Integer.valueOf(RX_BUF.length),
                        Integer.valueOf(timeoutMs)
                )).intValue();
            } catch (Throwable t) {
                return -1;
            }
        }
    }

    // =========================================================================
    // 2. ANDROID CANVAS 1920x462 CLUSTER HUD FRAME RENDERER
    // =========================================================================

    public static final class TurzxFrameRenderer {
        public static final int HUD_WIDTH = 1920;
        public static final int HUD_HEIGHT = 462;
        // TURZX 9.2" (0x0092) hardware JPEG decoder requires 16-pixel macroblock width: 29 * 16 = 464 (464x1920)
        public static final int PANEL_JPEG_WIDTH = 464;
        public static final int PANEL_JPEG_HEIGHT = 1920;

        private static Object landscapeBitmap = null;
        private static Object landscapeCanvas = null;
        private static Object panelBitmap = null;
        private static Object panelCanvas = null;
        private static Object rotMatrix = null;
        private static Object paintFill = null;
        private static Object paintStroke = null;
        private static Object paintText = null;
        private static Object rectF = null;
        private static int animTick = 0;

        /**
         * Renders the 1920x462 HUD frame onto an Android Canvas and returns:
         * - result[0]: 1920x462 landscape JPEG (for web preview /frame.jpg & headunit overlay)
         * - result[1]: rotated 464x1920 portrait JPEG (for TURZX 9.2" CMD 101 hardware JPEG decoder)
         */
        public static synchronized byte[][] renderHudJpegPair(TelemetrySnapshot s, int rotationDeg, int jpegQuality) {
            try {
                Class<?> bmpCls = Class.forName("android.graphics.Bitmap");
                Class<?> cfgCls = Class.forName("android.graphics.Bitmap$Config");
                Class<?> canvasCls = Class.forName("android.graphics.Canvas");
                Class<?> paintCls = Class.forName("android.graphics.Paint");
                Class<?> styleCls = Class.forName("android.graphics.Paint$Style");
                Class<?> alignCls = Class.forName("android.graphics.Paint$Align");
                Class<?> tfCls = Class.forName("android.graphics.Typeface");
                Class<?> rectFCls = Class.forName("android.graphics.RectF");
                Class<?> pathCls = Class.forName("android.graphics.Path");
                Class<?> matrixCls = Class.forName("android.graphics.Matrix");
                Class<?> fmtCls = Class.forName("android.graphics.Bitmap$CompressFormat");

                if (landscapeBitmap == null) {
                    Object argb8888 = cfgCls.getField("ARGB_8888").get(null);
                    landscapeBitmap = bmpCls.getMethod("createBitmap", int.class, int.class, cfgCls)
                            .invoke(null, Integer.valueOf(HUD_WIDTH), Integer.valueOf(HUD_HEIGHT), argb8888);
                    landscapeCanvas = canvasCls.getConstructor(bmpCls).newInstance(landscapeBitmap);

                    panelBitmap = bmpCls.getMethod("createBitmap", int.class, int.class, cfgCls)
                            .invoke(null, Integer.valueOf(PANEL_JPEG_WIDTH), Integer.valueOf(PANEL_JPEG_HEIGHT), argb8888);
                    panelCanvas = canvasCls.getConstructor(bmpCls).newInstance(panelBitmap);
                    rotMatrix = matrixCls.getConstructor().newInstance();

                    paintFill = paintCls.getConstructor(int.class).newInstance(Integer.valueOf(1)); // ANTI_ALIAS_FLAG
                    paintCls.getMethod("setStyle", styleCls).invoke(paintFill, styleCls.getField("FILL").get(null));

                    paintStroke = paintCls.getConstructor(int.class).newInstance(Integer.valueOf(1));
                    paintCls.getMethod("setStyle", styleCls).invoke(paintStroke, styleCls.getField("STROKE").get(null));

                    paintText = paintCls.getConstructor(int.class).newInstance(Integer.valueOf(1));
                    paintCls.getMethod("setTypeface", tfCls).invoke(paintText, tfCls.getField("DEFAULT_BOLD").get(null));

                    rectF = rectFCls.getConstructor().newInstance();
                }

                animTick++;
                drawHudToCanvas(landscapeCanvas, paintFill, paintStroke, paintText, rectF, canvasCls, paintCls, alignCls, rectFCls, pathCls, s);

                Object jpegFmt = fmtCls.getField("JPEG").get(null);
                Method compressM = bmpCls.getMethod("compress", fmtCls, int.class, OutputStream.class);
                int q = Math.max(50, Math.min(95, jpegQuality));

                ByteArrayOutputStream baosLand = new ByteArrayOutputStream(65536);
                compressM.invoke(landscapeBitmap, jpegFmt, Integer.valueOf(q), baosLand);
                byte[] landBytes = baosLand.toByteArray();

                // Rotate 1920x462 landscape into 462x1920 at (0,0) on the 464x1920 macroblock-aligned panelBitmap
                canvasCls.getMethod("drawColor", int.class).invoke(panelCanvas, Integer.valueOf(0xFF000000));
                matrixCls.getMethod("reset").invoke(rotMatrix);
                if (rotationDeg == 90) {
                    // 90 deg counter-clockwise (flipped mount)
                    matrixCls.getMethod("postRotate", float.class).invoke(rotMatrix, Float.valueOf(270.0f));
                    matrixCls.getMethod("postTranslate", float.class, float.class)
                            .invoke(rotMatrix, Float.valueOf(0.0f), Float.valueOf((float) HUD_WIDTH));
                } else {
                    // Default (rotationDeg == 270): 90 deg clockwise (= PIL ROTATE_270 verified on TURZX 9.2" hardware)
                    matrixCls.getMethod("postRotate", float.class).invoke(rotMatrix, Float.valueOf(90.0f));
                    matrixCls.getMethod("postTranslate", float.class, float.class)
                            .invoke(rotMatrix, Float.valueOf((float) HUD_HEIGHT), Float.valueOf(0.0f));
                }
                canvasCls.getMethod("drawBitmap", bmpCls, matrixCls, paintCls)
                        .invoke(panelCanvas, landscapeBitmap, rotMatrix, null);

                ByteArrayOutputStream baosPanel = new ByteArrayOutputStream(65536);
                compressM.invoke(panelBitmap, jpegFmt, Integer.valueOf(q), baosPanel);
                byte[] panelBytes = baosPanel.toByteArray();

                return new byte[][]{landBytes, panelBytes};
            } catch (Throwable t) {
                return null;
            }
        }

        private static void drawHudToCanvas(
                Object c,
                Object pFill,
                Object pStroke,
                Object pText,
                Object rf,
                Class<?> canvasCls,
                Class<?> paintCls,
                Class<?> alignCls,
                Class<?> rectFCls,
                Class<?> pathCls,
                TelemetrySnapshot s
        ) throws Exception {
            Method setColor = paintCls.getMethod("setColor", int.class);
            Method setStrokeW = paintCls.getMethod("setStrokeWidth", float.class);
            Method setTextSize = paintCls.getMethod("setTextSize", float.class);
            Method setTextAlign = paintCls.getMethod("setTextAlign", alignCls);
            Object alignLeft = alignCls.getField("LEFT").get(null);
            Object alignCenter = alignCls.getField("CENTER").get(null);
            Object alignRight = alignCls.getField("RIGHT").get(null);

            Method setRf = rectFCls.getMethod("set", float.class, float.class, float.class, float.class);
            Method drawRoundRect = canvasCls.getMethod("drawRoundRect", rectFCls, float.class, float.class, paintCls);
            Method drawRect = canvasCls.getMethod("drawRect", float.class, float.class, float.class, float.class, paintCls);
            Method drawCircle = canvasCls.getMethod("drawCircle", float.class, float.class, float.class, paintCls);
            Method drawLine = canvasCls.getMethod("drawLine", float.class, float.class, float.class, float.class, paintCls);
            Method drawText = canvasCls.getMethod("drawText", String.class, float.class, float.class, paintCls);
            Method drawPath = canvasCls.getMethod("drawPath", pathCls, paintCls);

            // 0. Deep OLED Pitch-Black Cluster Background (#06080D)
            setColor.invoke(pFill, Integer.valueOf(0xFF06080D));
            drawRect.invoke(c, 0f, 0f, 1920f, 462f, pFill);

            // Top Seon Horizon Accent Line
            setColor.invoke(pFill, Integer.valueOf(0xFF0F2942));
            drawRect.invoke(c, 0f, 0f, 1920f, 2f, pFill);

            // -----------------------------------------------------------------
            // TOP STATUS STRIP (y = 4..34)
            // -----------------------------------------------------------------
            drawCard(c, rf, pFill, pStroke, setRf, drawRoundRect, setColor, setStrokeW,
                    12f, 5f, 1908f, 35f, 8f, 0xFF0B111C, 0xFF1E293B, 1f);

            setTextAlign.invoke(pText, alignLeft);
            setTextSize.invoke(pText, 14f);
            setColor.invoke(pText, Integer.valueOf(0xFF38BDF8));
            drawText.invoke(c, "HKG 5W TURZX 9.2 CLUSTER HUD", 26f, 25f, pText);

            // EV Ready Badge
            int rdyBg = s.evReady ? 0xFF064E3B : 0xFF1E293B;
            int rdyFg = s.evReady ? 0xFF34D399 : 0xFF94A3B8;
            drawCard(c, rf, pFill, pStroke, setRf, drawRoundRect, setColor, setStrokeW,
                    290f, 8f, 405f, 32f, 6f, rdyBg, rdyFg, 1f);
            setTextAlign.invoke(pText, alignCenter);
            setTextSize.invoke(pText, 12.5f);
            setColor.invoke(pText, Integer.valueOf(rdyFg));
            drawText.invoke(c, s.evReady ? "EV READY" : "IG ON / STANDBY", 347.5f, 24.5f, pText);

            // Comma 3X Link Status Pill
            int commaBg = s.carrotConnected ? 0xFF064E3B : 0xFF1E293B;
            int commaFg = s.carrotConnected ? 0xFF34D399 : 0xFF94A3B8;
            drawCard(c, rf, pFill, pStroke, setRf, drawRoundRect, setColor, setStrokeW,
                    418f, 8f, 610f, 32f, 6f, commaBg, commaFg, 1f);
            setColor.invoke(pText, Integer.valueOf(commaFg));
            drawText.invoke(c, s.carrotConnected ? ("COMMA 3X · " + (s.carrotActive ? "ACTIVE" : "ONLINE")) : "COMMA 3X · STANDBY", 514f, 24.5f, pText);

            // Right side of Top Strip: Indoor / Outdoor Temps + Clock
            setTextAlign.invoke(pText, alignRight);
            setTextSize.invoke(pText, 14f);
            setColor.invoke(pText, Integer.valueOf(0xFFE2E8F0));
            String inTempStr = (s.inCarTempC > -50.0) ? String.format(Locale.US, "실내 %.1f°C", s.inCarTempC) : "실내 --.-°C";
            String outTempStr = (s.outdoorTempC > -50.0) ? String.format(Locale.US, "실외 %.1f°C", s.outdoorTempC) : "실외 --.-°C";
            String clockStr = new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
            drawText.invoke(c, inTempStr + "   |   " + outTempStr + "   |   " + clockStr, 1892f, 25f, pText);

            // =================================================================
            // COLUMN 1: EV INSTRUMENT CLUSTER (x = 12..624, y = 42..454)
            // =================================================================
            // 2x2 EV Grid (y = 42..330) + Bottom TPMS Card (y = 338..454)
            double soc = s.getEffectiveSocPct();
            String socValStr = (soc >= 0.0) ? String.format(Locale.US, "%.1f%%", soc) : "--.-%";
            String sohStr = (s.sohPct > 0.0) ? String.format(Locale.US, "SOH %.1f%%", s.sohPct) : "SOH 100%";
            String kwhStr = (s.remainEnergyKwh > 0.0)
                    ? String.format(Locale.US, "가용 %.1f kWh · BMS %.1f%%", s.remainEnergyKwh, s.bmsSocPct >= 0 ? s.bmsSocPct : soc)
                    : "BMS 고전압 배터리";
            drawEvMetricCard(c, rf, pFill, pStroke, pText, setRf, drawRoundRect, drawText, setColor, setStrokeW, setTextSize, setTextAlign,
                    alignLeft, alignRight,
                    12f, 42f, 314f, 182f,
                    "고전압 배터리 (SoC)", sohStr, socValStr, kwhStr,
                    soc >= 0 ? (int) Math.round(soc) : 50, 0xFF38BDF8);

            // [1,2] DTE & Economy
            String unit = s.distanceUnitMiles ? "mi" : "km";
            String dteValStr = (s.dteKm >= 0) ? (s.dteKm + " " + unit) : ("--- " + unit);
            String dteBadge = (s.dteAirconOffDeltaKm != 0)
                    ? String.format(Locale.US, "공조OFF %+d%s", s.dteAirconOffDeltaKm, unit)
                    : "주행가능";
            String econStr = (s.avgEconomyKmKwh > 0.0)
                    ? String.format(Locale.US, "평균 전비 %.1f %s/kWh", s.avgEconomyKmKwh, unit)
                    : "평균 전비 -.- km/kWh";
            int dtePct = (s.dteKm > 0) ? Math.min(100, (s.dteKm * 100) / 420) : 45;
            drawEvMetricCard(c, rf, pFill, pStroke, pText, setRf, drawRoundRect, drawText, setColor, setStrokeW, setTextSize, setTextAlign,
                    alignLeft, alignRight,
                    322f, 42f, 624f, 182f,
                    "주행가능거리 (DTE)", dteBadge, dteValStr, econStr,
                    dtePct, 0xFF10B981);

            // [2,1] Real-Time Power & 4-Channel Subsystems
            double totalKw = s.getTotalPowerKw();
            String pwrValStr = String.format(Locale.US, "%.1f kW", Math.abs(totalKw));
            String pwrBadge = (totalKw < -0.3) ? "회생/충전" : "실시간 소모";
            int pwrColor = (totalKw < -0.3) ? 0xFF34D399 : 0xFF38BDF8;
            String pwrSubStr = String.format(Locale.US, "모터 %.1f · 공조 %.1f · 전장 %.2f kW",
                    Math.max(0.0, s.getEffectiveDriveMotorKw()), s.climatePowerKw, s.electronicsPowerKw);
            int pwrPct = Math.min(100, (int) Math.round((Math.abs(totalKw) / 80.0) * 100.0));
            drawEvMetricCard(c, rf, pFill, pStroke, pText, setRf, drawRoundRect, drawText, setColor, setStrokeW, setTextSize, setTextAlign,
                    alignLeft, alignRight,
                    12f, 190f, 314f, 330f,
                    "실시간 소비전력", pwrBadge, pwrValStr, pwrSubStr,
                    pwrPct, pwrColor);

            // [2,2] Cell Balancing & Thermal
            Integer repTemp = s.getRepresentativeBattTempC();
            String tempValStr = (repTemp != null) ? (repTemp.intValue() + "°C") : "21°C";
            String cellBadge = (s.cellDiffMv >= 0) ? ("Δ" + s.cellDiffMv + "mV 정상") : "Δ0mV 최상";
            String cellSubStr = (s.cellMaxVoltV > 0.0)
                    ? String.format(Locale.US, "셀 %.2f~%.2fV · 12V보조 %.1fV",
                    s.cellMinVoltV, s.cellMaxVoltV, s.auxBattVoltV > 0 ? s.auxBattVoltV : 14.1)
                    : "셀 3.60~3.60V · 12V보조 14.0V";
            drawEvMetricCard(c, rf, pFill, pStroke, pText, setRf, drawRoundRect, drawText, setColor, setStrokeW, setTextSize, setTextAlign,
                    alignLeft, alignRight,
                    322f, 190f, 624f, 330f,
                    "배터리 온도 · 셀밸런싱", cellBadge, tempValStr, cellSubStr,
                    65, 0xFF34D399);

            // Bottom TPMS 4-Wheel Tire Pressure Card (y = 338..454)
            drawCard(c, rf, pFill, pStroke, setRf, drawRoundRect, setColor, setStrokeW,
                    12f, 338f, 624f, 454f, 12f, 0xFF0D1524, 0xFF1E293B, 1.5f);
            setTextAlign.invoke(pText, alignLeft);
            setTextSize.invoke(pText, 13f);
            setColor.invoke(pText, Integer.valueOf(0xFF94A3B8));
            drawText.invoke(c, "TPMS 타이어 공기압 (psi)", 26f, 360f, pText);

            // Top-down car outline in center of TPMS card (cx = 318, cy = 398)
            drawCard(c, rf, pFill, pStroke, setRf, drawRoundRect, setColor, setStrokeW,
                    282f, 354f, 354f, 442f, 16f, 0xFF162238, 0xFF38BDF8, 1.5f);
            // 4 tire pills & PSI labels
            setTextSize.invoke(pText, 18f);
            setColor.invoke(pText, Integer.valueOf(0xFFF8FAFC));
            setTextAlign.invoke(pText, alignRight);
            drawText.invoke(c, String.format(Locale.US, "FL  %.0f psi", s.tpmsFlPsi), 265f, 384f, pText);
            drawText.invoke(c, String.format(Locale.US, "RL  %.0f psi", s.tpmsRlPsi), 265f, 430f, pText);
            setTextAlign.invoke(pText, alignLeft);
            drawText.invoke(c, String.format(Locale.US, "%.0f psi  FR", s.tpmsFrPsi), 371f, 384f, pText);
            drawText.invoke(c, String.format(Locale.US, "%.0f psi  RR", s.tpmsRrPsi), 371f, 430f, pText);

            // =================================================================
            // COLUMN 2: CARROTNAVI + 3D COMMA ADAS HUD (x = 634..1216, y = 42..454)
            // =================================================================
            drawColumn2AdasHud(c, rf, pFill, pStroke, pText, setRf, drawRoundRect, drawCircle, drawLine, drawText, drawPath,
                    setColor, setStrokeW, setTextSize, setTextAlign, alignLeft, alignCenter, alignRight, pathCls, s);

            // =================================================================
            // COLUMN 3: 1:1 MEDIA (TOP 50%) + DATC CLIMATE (BOTTOM 50%) (x = 1226..1908)
            // =================================================================
            drawColumn3MediaAndClimate(c, rf, pFill, pStroke, pText, setRf, drawRoundRect, drawCircle, drawLine, drawText, drawPath,
                    setColor, setStrokeW, setTextSize, setTextAlign, alignLeft, alignCenter, alignRight, pathCls, s);
        }

        private static void drawColumn2AdasHud(
                Object c,
                Object rf,
                Object pFill,
                Object pStroke,
                Object pText,
                Method setRf,
                Method drawRoundRect,
                Method drawCircle,
                Method drawLine,
                Method drawText,
                Method drawPath,
                Method setColor,
                Method setStrokeW,
                Method setTextSize,
                Method setTextAlign,
                Object alignLeft,
                Object alignCenter,
                Object alignRight,
                Class<?> pathCls,
                TelemetrySnapshot s
        ) throws Exception {
            float x0 = 634f;
            float y0 = 42f;
            float x1 = 1216f;
            float y1 = 454f;
            float cx = (x0 + x1) * 0.5f; // 925f
            float horizonY = y0 + 165f;  // 207f

            // Background card for Column 2
            drawCard(c, rf, pFill, pStroke, setRf, drawRoundRect, setColor, setStrokeW,
                    x0, y0, x1, y1, 14f, 0xFF080E1A, 0xFF1E293B, 1.5f);

            // Horizon line & subtle perspective grid
            setColor.invoke(pStroke, Integer.valueOf(0xFF1E3A5F));
            setStrokeW.invoke(pStroke, 1.2f);
            drawLine.invoke(c, x0 + 8f, horizonY, x1 - 8f, horizonY, pStroke);

            float curveShift = (s.pathPoly != null && s.pathPoly.length > 2)
                    ? Math.max(-42f, Math.min(42f, s.pathPoly[2] * 95000f))
                    : 8f;

            // 3D Driving Path Carpet Polygon (Cyan-Emerald translucent fill)
            Object carpetPath = pathCls.getConstructor().newInstance();
            Method moveTo = pathCls.getMethod("moveTo", float.class, float.class);
            Method lineTo = pathCls.getMethod("lineTo", float.class, float.class);
            Method closeP = pathCls.getMethod("close");

            moveTo.invoke(carpetPath, cx - 26f + curveShift, horizonY + 12f);
            lineTo.invoke(carpetPath, cx + 26f + curveShift, horizonY + 12f);
            lineTo.invoke(carpetPath, cx + 150f, y1 - 6f);
            lineTo.invoke(carpetPath, cx - 150f, y1 - 6f);
            closeP.invoke(carpetPath);

            setColor.invoke(pFill, Integer.valueOf(0x4410B981));
            drawPath.invoke(c, carpetPath, pFill);

            // Left & Right 3D Lane Lines
            setColor.invoke(pStroke, Integer.valueOf(0xFF38BDF8));
            setStrokeW.invoke(pStroke, 3.0f);
            drawLine.invoke(c, cx - 28f + curveShift, horizonY + 12f, cx - 156f, y1 - 6f, pStroke);
            drawLine.invoke(c, cx + 28f + curveShift, horizonY + 12f, cx + 156f, y1 - 6f, pStroke);

            // Center trajectory guide line
            setColor.invoke(pStroke, Integer.valueOf(0xAA34D399));
            setStrokeW.invoke(pStroke, 2.0f);
            drawLine.invoke(c, cx + curveShift, horizonY + 18f, cx, y1 - 10f, pStroke);

            // 3D Lead Vehicle Box & Distance Badge
            if (s.hasLeadCar && s.leadDistM > 0f) {
                float leadCx = cx + curveShift * 0.65f;
                float leadCy = horizonY + 58f;
                drawCard(c, rf, pFill, pStroke, setRf, drawRoundRect, setColor, setStrokeW,
                        leadCx - 38f, leadCy - 24f, leadCx + 38f, leadCy + 18f, 8f, 0xCC0F172A, 0xFF38BDF8, 2.0f);
                // Taillight accents
                setColor.invoke(pFill, Integer.valueOf(0xFFEF4444));
                setRf.invoke(rf, leadCx - 32f, leadCy - 6f, leadCx - 16f, leadCy + 2f);
                drawRoundRect.invoke(c, rf, 3f, 3f, pFill);
                setRf.invoke(rf, leadCx + 16f, leadCy - 6f, leadCx + 32f, leadCy + 2f);
                drawRoundRect.invoke(c, rf, 3f, 3f, pFill);

                // Lead distance pill
                drawCard(c, rf, pFill, pStroke, setRf, drawRoundRect, setColor, setStrokeW,
                        leadCx - 36f, leadCy + 24f, leadCx + 36f, leadCy + 46f, 6f, 0xEE064E3B, 0xFF10B981, 1.2f);
                setTextAlign.invoke(pText, alignCenter);
                setTextSize.invoke(pText, 13.5f);
                setColor.invoke(pText, Integer.valueOf(0xFF6EE7B7));
                drawText.invoke(c, String.format(Locale.US, "%.0fm", s.leadDistM), leadCx, leadCy + 40f, pText);
            }

            // -----------------------------------------------------------------
            // TOP-LEFT OF COLUMN 2: SDI SPEED LIMIT CIRCLE (RED CAMERA vs BLUE NORMAL) + TBT
            // -----------------------------------------------------------------
            boolean isCameraActive = (s.carrotSdiType >= 0) && (s.carrotSdiDistM > 0 || s.carrotSdiSpeedLimit > 0);
            int signLimit = (s.carrotSdiSpeedLimit > 0) ? s.carrotSdiSpeedLimit : 80;
            float signCx = x0 + 48f;
            float signCy = y0 + 50f;
            float signR = 32f;

            if (isCameraActive) {
                // RED Circular Speed Camera Sign
                setColor.invoke(pFill, Integer.valueOf(0xFFF8FAFC));
                drawCircle.invoke(c, signCx, signCy, signR, pFill);
                setColor.invoke(pStroke, Integer.valueOf(0xFFEF4444));
                setStrokeW.invoke(pStroke, 6.5f);
                drawCircle.invoke(c, signCx, signCy, signR - 3f, pStroke);
                setTextAlign.invoke(pText, alignCenter);
                setTextSize.invoke(pText, 24f);
                setColor.invoke(pText, Integer.valueOf(0xFF0F172A));
                drawText.invoke(c, String.valueOf(signLimit), signCx, signCy + 8f, pText);
            } else {
                // BLUE Circular Normal Speed Limit Sign (Idle / No Camera)
                setColor.invoke(pFill, Integer.valueOf(0xFF1E3A8A));
                drawCircle.invoke(c, signCx, signCy, signR, pFill);
                setColor.invoke(pStroke, Integer.valueOf(0xFF3B82F6));
                setStrokeW.invoke(pStroke, 5.5f);
                drawCircle.invoke(c, signCx, signCy, signR - 3f, pStroke);
                setTextAlign.invoke(pText, alignCenter);
                setTextSize.invoke(pText, 24f);
                setColor.invoke(pText, Integer.valueOf(0xFFF8FAFC));
                drawText.invoke(c, String.valueOf(signLimit), signCx, signCy + 8f, pText);
            }

            // SDI Camera Distance / Status Label under sign
            setTextAlign.invoke(pText, alignLeft);
            setTextSize.invoke(pText, 12.5f);
            setColor.invoke(pText, Integer.valueOf(isCameraActive ? 0xFFFCA5A5 : 0xFF93C5FD));
            String sdiLabel = isCameraActive
                    ? String.format(Locale.US, "단속 %dm", Math.max(0, s.carrotSdiDistM))
                    : "단속 없음";
            drawText.invoke(c, sdiLabel, x0 + 18f, y0 + 100f, pText);

            // TBT Turn-by-Turn Card at bottom of Column 2
            drawCard(c, rf, pFill, pStroke, setRf, drawRoundRect, setColor, setStrokeW,
                    x0 + 14f, y1 - 54f, x1 - 14f, y1 - 10f, 10f, 0xDD0F172A, 0xFF334155, 1.2f);
            setTextAlign.invoke(pText, alignLeft);
            setTextSize.invoke(pText, 16f);
            setColor.invoke(pText, Integer.valueOf(0xFF38BDF8));
            String tbtDistStr = (s.carrotTbtDistM > 0) ? (s.carrotTbtDistM + "m 앞") : "380m 앞";
            drawText.invoke(c, "↱ " + tbtDistStr, x0 + 28f, y1 - 26f, pText);
            setTextSize.invoke(pText, 14f);
            setColor.invoke(pText, Integer.valueOf(0xFFE2E8F0));
            drawText.invoke(c, s.carrotTbtRoad != null ? s.carrotTbtRoad : "경부고속도로 · 판교 JC 방면", x0 + 145f, y1 - 26f, pText);

            // -----------------------------------------------------------------
            // TOP-CENTER OF COLUMN 2: HERO SPEED + CRUISE + GEAR PILL
            // -----------------------------------------------------------------
            int displaySpd = (s.carrotSpeedKph >= 0) ? s.carrotSpeedKph : 0;
            setTextAlign.invoke(pText, alignCenter);
            setTextSize.invoke(pText, 58f);
            setColor.invoke(pText, Integer.valueOf(0xFFF8FAFC));
            drawText.invoke(c, String.valueOf(displaySpd), cx, y0 + 66f, pText);

            setTextSize.invoke(pText, 13f);
            setColor.invoke(pText, Integer.valueOf(0xFF94A3B8));
            drawText.invoke(c, "km/h", cx, y0 + 84f, pText);

            // Cruise Set Pill & Gear Pill on Top-Right of Column 2
            int cruiseSpd = (s.carrotCruiseKph > 0) ? s.carrotCruiseKph : 90;
            drawCard(c, rf, pFill, pStroke, setRf, drawRoundRect, setColor, setStrokeW,
                    x1 - 134f, y0 + 14f, x1 - 14f, y0 + 52f, 8f, 0xFF064E3B, 0xFF10B981, 1.5f);
            setTextAlign.invoke(pText, alignCenter);
            setTextSize.invoke(pText, 15f);
            setColor.invoke(pText, Integer.valueOf(0xFF6EE7B7));
            drawText.invoke(c, "CRUISE " + cruiseSpd, x1 - 74f, y0 + 38f, pText);

            String gear = (s.gearPos != null && !s.gearPos.isEmpty()) ? s.gearPos : "D";
            drawCard(c, rf, pFill, pStroke, setRf, drawRoundRect, setColor, setStrokeW,
                    x1 - 134f, y0 + 58f, x1 - 14f, y0 + 92f, 8f, 0xFF1E293B, 0xFF38BDF8, 1.2f);
            setTextSize.invoke(pText, 15f);
            setColor.invoke(pText, Integer.valueOf(0xFFF8FAFC));
            drawText.invoke(c, "GEAR  " + gear, x1 - 74f, y0 + 80f, pText);
        }

        private static void drawColumn3MediaAndClimate(
                Object c,
                Object rf,
                Object pFill,
                Object pStroke,
                Object pText,
                Method setRf,
                Method drawRoundRect,
                Method drawCircle,
                Method drawLine,
                Method drawText,
                Method drawPath,
                Method setColor,
                Method setStrokeW,
                Method setTextSize,
                Method setTextAlign,
                Object alignLeft,
                Object alignCenter,
                Object alignRight,
                Class<?> pathCls,
                TelemetrySnapshot s
        ) throws Exception {
            float x0 = 1226f;
            float x1 = 1908f;

            // =================================================================
            // TOP 50% OF COLUMN 3 (1:1 Ratio): NOW PLAYING MEDIA (y = 42..244)
            // =================================================================
            float my0 = 42f;
            float my1 = 244f;
            drawCard(c, rf, pFill, pStroke, setRf, drawRoundRect, setColor, setStrokeW,
                    x0, my0, x1, my1, 14f, 0xFF0D1524, 0xFF1E293B, 1.5f);

            // Large Full-Height Album Art Box on Left (172x172 px, x = x0+16..x0+188, y = my0+15..my0+187)
            drawCard(c, rf, pFill, pStroke, setRf, drawRoundRect, setColor, setStrokeW,
                    x0 + 16f, my0 + 15f, x0 + 188f, my0 + 187f, 14f, 0xFF1E293B, 0xFF38BDF8, 1.8f);
            setTextAlign.invoke(pText, alignCenter);
            setTextSize.invoke(pText, 48f);
            setColor.invoke(pText, Integer.valueOf(0xFF38BDF8));
            drawText.invoke(c, "♪", x0 + 102f, my0 + 116f, pText);

            // Track Title & Artist/Album on Right (no media source badge)
            float textX = x0 + 210f;
            setTextAlign.invoke(pText, alignLeft);
            setTextSize.invoke(pText, 28f);
            setColor.invoke(pText, Integer.valueOf(0xFFF8FAFC));
            drawText.invoke(c, truncateText(s.mediaTitle, 22), textX, my0 + 56f, pText);

            setTextSize.invoke(pText, 18f);
            setColor.invoke(pText, Integer.valueOf(0xFF38BDF8));
            drawText.invoke(c, truncateText(s.mediaArtist, 26), textX, my0 + 88f, pText);

            setTextSize.invoke(pText, 14.5f);
            setColor.invoke(pText, Integer.valueOf(0xFF94A3B8));
            drawText.invoke(c, truncateText(s.mediaAlbum, 30), textX, my0 + 114f, pText);

            // Shortened Playback Progress Bar on Right (pbX0 = x0+210, pbX1 = x0+560)
            float pbX0 = textX;
            float pbX1 = x0 + 560f;
            float pbY = my0 + 144f;
            drawProgressBar(c, rf, pFill, setRf, drawRoundRect, setColor,
                    pbX0, pbY, pbX1, pbY + 8f,
                    s.mediaDurationSec > 0 ? (s.mediaPositionSec * 100) / s.mediaDurationSec : 65,
                    0xFF38BDF8);

            setTextSize.invoke(pText, 13f);
            setColor.invoke(pText, Integer.valueOf(0xFF94A3B8));
            drawText.invoke(c, formatSecMin(s.mediaPositionSec), pbX0, pbY + 28f, pText);
            setTextAlign.invoke(pText, alignRight);
            drawText.invoke(c, formatSecMin(s.mediaDurationSec), pbX1, pbY + 28f, pText);

            // =================================================================
            // BOTTOM 50% OF COLUMN 3 (1:1 Ratio): DATC CLIMATE (y = 252..454)
            // =================================================================
            float cy0 = 252f;
            float cy1 = 454f;
            drawCard(c, rf, pFill, pStroke, setRf, drawRoundRect, setColor, setStrokeW,
                    x0, cy0, x1, cy1, 14f, 0xFF0D1524, 0xFF1E293B, 1.5f);

            // Header row: CLIMATE CONTROL + AUTO / A/C / HEAT / RECIRC badges
            setTextAlign.invoke(pText, alignLeft);
            setTextSize.invoke(pText, 13.5f);
            setColor.invoke(pText, Integer.valueOf(0xFF94A3B8));
            drawText.invoke(c, "CLIMATE · 공조 모니터", x0 + 18f, cy0 + 24f, pText);

            drawSmallPill(c, rf, pFill, pStroke, pText, setRf, drawRoundRect, drawText, setColor, setStrokeW, setTextSize, setTextAlign, alignCenter,
                    x0 + 182f, cy0 + 8f, 64f, 22f, "AUTO", s.hvacAuto, 0xFF10B981);
            drawSmallPill(c, rf, pFill, pStroke, pText, setRf, drawRoundRect, drawText, setColor, setStrokeW, setTextSize, setTextAlign, alignCenter,
                    x0 + 252f, cy0 + 8f, 56f, 22f, "A/C", s.hvacAc, 0xFF38BDF8);
            drawSmallPill(c, rf, pFill, pStroke, pText, setRf, drawRoundRect, drawText, setColor, setStrokeW, setTextSize, setTextAlign, alignCenter,
                    x0 + 314f, cy0 + 8f, 72f, 22f, s.hvacRecirc ? "내기순환" : "외기유입", true, 0xFF60A5FA);
            drawSmallPill(c, rf, pFill, pStroke, pText, setRf, drawRoundRect, drawText, setColor, setStrokeW, setTextSize, setTextAlign, alignCenter,
                    x0 + 392f, cy0 + 8f, 64f, 22f, "SYNC", s.hvacSync, 0xFFA78BFA);

            setTextAlign.invoke(pText, alignRight);
            setTextSize.invoke(pText, 13f);
            setColor.invoke(pText, Integer.valueOf(0xFFFBBF24));
            drawText.invoke(c, String.format(Locale.US, "공조 %.2f kW", Math.max(0.0, s.climatePowerKw)), x1 - 18f, cy0 + 24f, pText);

            // Driver Temp (Left, x = 1244..1375)
            setTextAlign.invoke(pText, alignLeft);
            setTextSize.invoke(pText, 12.5f);
            setColor.invoke(pText, Integer.valueOf(0xFF94A3B8));
            drawText.invoke(c, "운전석 설정", x0 + 20f, cy0 + 54f, pText);
            setTextSize.invoke(pText, 38f);
            setColor.invoke(pText, Integer.valueOf(0xFFF8FAFC));
            String drStr = (s.hvacDrTempC > 0) ? String.format(Locale.US, "%.1f°", s.hvacDrTempC) : "22.0°";
            drawText.invoke(c, drStr, x0 + 18f, cy0 + 94f, pText);

            // Passenger Temp (Right, x = 1765..1890)
            setTextAlign.invoke(pText, alignRight);
            setTextSize.invoke(pText, 12.5f);
            setColor.invoke(pText, Integer.valueOf(0xFF94A3B8));
            drawText.invoke(c, "동승석 설정", x1 - 20f, cy0 + 54f, pText);
            setTextSize.invoke(pText, 38f);
            setColor.invoke(pText, Integer.valueOf(0xFFF8FAFC));
            String psStr = (s.hvacPsTempC > 0) ? String.format(Locale.US, "%.1f°", s.hvacPsTempC) : "22.5°";
            drawText.invoke(c, psStr, x1 - 18f, cy0 + 94f, pText);

            // -----------------------------------------------------------------
            // CENTER OF CLIMATE CARD: LARGE 3-ARROW AIR DIRECTION + 7-STEP FAN BARS
            // -----------------------------------------------------------------
            drawCard(c, rf, pFill, pStroke, setRf, drawRoundRect, setColor, setStrokeW,
                    x0 + 150f, cy0 + 38f, x1 - 150f, cy0 + 144f, 10f, 0xFF080E1A, 0xFF1E293B, 1.2f);

            // Determine active air directions from s.hvacBlowerMode:
            // 1=VENT(상체), 2=B/L(상·하체), 3=FLOOR(하체), 4=MIX(유리·하체), 5=DEF(유리)
            int bm = s.hvacBlowerMode;
            boolean dirUp = (bm == 5 || bm == 4 || bm == 7 || bm == 9 || bm == 10 || s.hvacFrontDefog);
            boolean dirMid = (bm == 1 || bm == 2 || bm == 7 || bm == 9 || bm < 0);
            boolean dirDown = (bm == 2 || bm == 3 || bm == 4 || bm == 8 || bm == 9 || bm < 0);

            // Seated occupant silhouette on right side of air-direction box (cx = x0 + 258, cy = cy0 + 70)
            float occX = x0 + 258f;
            float occY = cy0 + 58f;
            setColor.invoke(pFill, Integer.valueOf(0xFF64748B));
            drawCircle.invoke(c, occX, occY, 8f, pFill); // Head
            setColor.invoke(pStroke, Integer.valueOf(0xFF64748B));
            setStrokeW.invoke(pStroke, 6f);
            drawLine.invoke(c, occX, occY + 10f, occX - 6f, occY + 34f, pStroke); // Torso
            drawLine.invoke(c, occX - 6f, occY + 34f, occX - 24f, occY + 38f, pStroke); // Thigh
            drawLine.invoke(c, occX - 24f, occY + 38f, occX - 30f, occY + 56f, pStroke); // Lower leg

            // 3 Large Directional Arrows on left side of occupant (x = x0 + 168 .. x0 + 235)
            float arrX0 = x0 + 168f;
            float arrX1 = x0 + 230f;
            // 1) Defrost / Upper Arrow (Amber #FBBF24)
            setStrokeW.invoke(pStroke, 4.5f);
            setColor.invoke(pStroke, Integer.valueOf(dirUp ? 0xFFFBBF24 : 0xFF1E293B));
            drawLine.invoke(c, arrX0, cy0 + 62f, arrX1 - 8f, cy0 + 48f, pStroke);
            // 2) Face / Mid Arrow (Cyan #38BDF8)
            setColor.invoke(pStroke, Integer.valueOf(dirMid ? 0xFF38BDF8 : 0xFF1E293B));
            drawLine.invoke(c, arrX0, cy0 + 78f, arrX1, cy0 + 78f, pStroke);
            // 3) Floor / Lower Arrow (Orange #FB923C)
            setColor.invoke(pStroke, Integer.valueOf(dirDown ? 0xFFFB923C : 0xFF1E293B));
            drawLine.invoke(c, arrX0, cy0 + 92f, arrX1 - 6f, cy0 + 110f, pStroke);

            // Air Direction Mode Text Pill (bottom of left sub-pane)
            setTextAlign.invoke(pText, alignLeft);
            setTextSize.invoke(pText, 13.5f);
            setColor.invoke(pText, Integer.valueOf(0xFF38BDF8));
            drawText.invoke(c, "풍향: " + formatBlowerModeText(bm), x0 + 166f, cy0 + 134f, pText);

            // 7-Step Ascending Fan Speed Bars on right half of center box (x = x0 + 310 .. x1 - 164)
            int fanStep = (s.hvacBlowerStep >= 0) ? Math.min(7, s.hvacBlowerStep) : 3;
            setTextAlign.invoke(pText, alignLeft);
            setTextSize.invoke(pText, 13.5f);
            setColor.invoke(pText, Integer.valueOf(0xFFE2E8F0));
            drawText.invoke(c, "풍량 " + fanStep + "단", x0 + 312f, cy0 + 60f, pText);

            float barBaseX = x0 + 312f;
            float barBotY = cy0 + 132f;
            for (int b = 1; b <= 7; b++) {
                float bx = barBaseX + (b - 1) * 28f;
                float bh = 14f + b * 6.5f;
                int bColor = (b <= fanStep)
                        ? (b >= 6 ? 0xFF34D399 : 0xFF38BDF8)
                        : 0xFF1E293B;
                setColor.invoke(pFill, Integer.valueOf(bColor));
                setRf.invoke(rf, bx, barBotY - bh, bx + 20f, barBotY);
                drawRoundRect.invoke(c, rf, 4f, 4f, pFill);
            }

            // -----------------------------------------------------------------
            // BOTTOM STRIP OF CLIMATE CARD: INDOOR TEMP & OUTDOOR TEMP (y = cy0+152..cy1-8)
            // -----------------------------------------------------------------
            float envY0 = cy0 + 152f;
            float envY1 = cy1 - 8f;
            float midEnvX = (x0 + x1) * 0.5f;

            // Left: Indoor Cabin Temp Card
            drawCard(c, rf, pFill, pStroke, setRf, drawRoundRect, setColor, setStrokeW,
                    x0 + 16f, envY0, midEnvX - 6f, envY1, 8f, 0xFF080E1A, 0xFF1E293B, 1f);
            setTextAlign.invoke(pText, alignLeft);
            setTextSize.invoke(pText, 13f);
            setColor.invoke(pText, Integer.valueOf(0xFF94A3B8));
            drawText.invoke(c, "실내 온도 (INDOOR)", x0 + 30f, envY0 + 26f, pText);
            setTextAlign.invoke(pText, alignRight);
            setTextSize.invoke(pText, 20f);
            setColor.invoke(pText, Integer.valueOf(0xFF34D399));
            String inVal = (s.inCarTempC > -50.0) ? String.format(Locale.US, "%.1f°C", s.inCarTempC) : "22.5°C";
            drawText.invoke(c, inVal, midEnvX - 20f, envY0 + 28f, pText);

            // Right: Outdoor Ambient Temp Card
            drawCard(c, rf, pFill, pStroke, setRf, drawRoundRect, setColor, setStrokeW,
                    midEnvX + 6f, envY0, x1 - 16f, envY1, 8f, 0xFF080E1A, 0xFF1E293B, 1f);
            setTextAlign.invoke(pText, alignLeft);
            setTextSize.invoke(pText, 13f);
            setColor.invoke(pText, Integer.valueOf(0xFF94A3B8));
            drawText.invoke(c, "실외 온도 (OUTDOOR)", midEnvX + 20f, envY0 + 26f, pText);
            setTextAlign.invoke(pText, alignRight);
            setTextSize.invoke(pText, 20f);
            setColor.invoke(pText, Integer.valueOf(0xFF38BDF8));
            String outVal = (s.outdoorTempC > -50.0) ? String.format(Locale.US, "%.1f°C", s.outdoorTempC) : "18.0°C";
            drawText.invoke(c, outVal, x1 - 30f, envY0 + 28f, pText);
        }

        private static void drawEvMetricCard(
                Object c,
                Object rf,
                Object pFill,
                Object pStroke,
                Object pText,
                Method setRf,
                Method drawRoundRect,
                Method drawText,
                Method setColor,
                Method setStrokeW,
                Method setTextSize,
                Method setTextAlign,
                Object alignLeft,
                Object alignRight,
                float x0,
                float y0,
                float x1,
                float y1,
                String title,
                String badge,
                String bigVal,
                String subText,
                int barPct,
                int accentColor
        ) throws Exception {
            drawCard(c, rf, pFill, pStroke, setRf, drawRoundRect, setColor, setStrokeW,
                    x0, y0, x1, y1, 12f, 0xFF0D1524, 0xFF1E293B, 1.5f);

            setTextAlign.invoke(pText, alignLeft);
            setTextSize.invoke(pText, 13f);
            setColor.invoke(pText, Integer.valueOf(0xFF94A3B8));
            drawText.invoke(c, title, x0 + 16f, y0 + 26f, pText);

            setTextAlign.invoke(pText, alignRight);
            setTextSize.invoke(pText, 12f);
            setColor.invoke(pText, Integer.valueOf(accentColor));
            drawText.invoke(c, badge, x1 - 16f, y0 + 26f, pText);

            setTextAlign.invoke(pText, alignLeft);
            setTextSize.invoke(pText, 34f);
            setColor.invoke(pText, Integer.valueOf(0xFFF8FAFC));
            drawText.invoke(c, bigVal, x0 + 16f, y0 + 72f, pText);

            drawProgressBar(c, rf, pFill, setRf, drawRoundRect, setColor,
                    x0 + 16f, y0 + 88f, x1 - 16f, y0 + 98f, barPct, accentColor);

            setTextSize.invoke(pText, 12.5f);
            setColor.invoke(pText, Integer.valueOf(0xFFCBD5E1));
            drawText.invoke(c, subText, x0 + 16f, y0 + 122f, pText);
        }

        private static void drawCard(
                Object c,
                Object rf,
                Object pFill,
                Object pStroke,
                Method setRf,
                Method drawRoundRect,
                Method setColor,
                Method setStrokeW,
                float x0,
                float y0,
                float x1,
                float y1,
                float radius,
                int fillColor,
                int strokeColor,
                float strokeW
        ) throws Exception {
            setRf.invoke(rf, x0, y0, x1, y1);
            setColor.invoke(pFill, Integer.valueOf(fillColor));
            drawRoundRect.invoke(c, rf, radius, radius, pFill);
            if (strokeW > 0f) {
                setColor.invoke(pStroke, Integer.valueOf(strokeColor));
                setStrokeW.invoke(pStroke, strokeW);
                drawRoundRect.invoke(c, rf, radius, radius, pStroke);
            }
        }

        private static void drawProgressBar(
                Object c,
                Object rf,
                Object pFill,
                Method setRf,
                Method drawRoundRect,
                Method setColor,
                float x0,
                float y0,
                float x1,
                float y1,
                int pct,
                int fillColor
        ) throws Exception {
            float r = (y1 - y0) * 0.5f;
            setRf.invoke(rf, x0, y0, x1, y1);
            setColor.invoke(pFill, Integer.valueOf(0xFF1E293B));
            drawRoundRect.invoke(c, rf, r, r, pFill);

            int clamped = Math.max(0, Math.min(100, pct));
            if (clamped > 0) {
                float fillW = Math.max((y1 - y0), ((x1 - x0) * clamped) / 100.0f);
                setRf.invoke(rf, x0, y0, x0 + fillW, y1);
                setColor.invoke(pFill, Integer.valueOf(fillColor));
                drawRoundRect.invoke(c, rf, r, r, pFill);
            }
        }

        private static void drawSmallPill(
                Object c,
                Object rf,
                Object pFill,
                Object pStroke,
                Object pText,
                Method setRf,
                Method drawRoundRect,
                Method drawText,
                Method setColor,
                Method setStrokeW,
                Method setTextSize,
                Method setTextAlign,
                Object alignCenter,
                float x,
                float y,
                float w,
                float h,
                String label,
                boolean active,
                int activeColor
        ) throws Exception {
            int bg = active ? 0xFF112236 : 0xFF0B111C;
            int fg = active ? activeColor : 0xFF475569;
            drawCard(c, rf, pFill, pStroke, setRf, drawRoundRect, setColor, setStrokeW,
                    x, y, x + w, y + h, 6f, bg, fg, 1f);
            setTextAlign.invoke(pText, alignCenter);
            setTextSize.invoke(pText, 11.5f);
            setColor.invoke(pText, Integer.valueOf(fg));
            drawText.invoke(c, label, x + w * 0.5f, y + h - 6f, pText);
        }

        private static String truncateText(String s, int maxChars) {
            if (s == null || s.isEmpty()) return "--";
            if (s.length() <= maxChars) return s;
            return s.substring(0, maxChars - 1) + "…";
        }

        private static String formatSecMin(int totalSec) {
            int clamped = Math.max(0, totalSec);
            return String.format(Locale.US, "%d:%02d", clamped / 60, clamped % 60);
        }
    }

    // =========================================================================
    // 3. REAL-TIME 4-DOMAIN TELEMETRY COLLECTOR (EV + DATC + MEDIA + COMMA 3X)
    // =========================================================================

    public static final class HscanListenerBridge extends com.hkmc.hscan.IHscanMicomListener.Stub {
        @Override
        public void onEventFromMicom(byte b1, byte b2, byte b3, byte[] data) {
            TelemetryCollector.markNativeBinderHscanRx();
            TelemetryCollector.onHscanPacket(b1, b2, b3, data);
        }

        @Override
        public void onMicomReadyChanged(boolean ready) {
        }

        @Override
        public void onSystemReadyChanged(boolean ready) {
        }
    }

    public static final class TelemetryCollector {
        private static final TelemetrySnapshot liveSnapshot = new TelemetrySnapshot();

        private static volatile Object hevService = null;
        private static volatile Method getHevStatusMethod = null;
        private static volatile Method reqHevStatusDataMethod = null;
        private static volatile Method reqFreqChangedSigMethod = null;
        private static volatile Method getCanDbGenMethod = null;
        private static volatile int canDbGeneration = -1;

        private static volatile Object tmHscanService = null;
        private static volatile Method tmDriveLinePwrMethod = null;
        private static volatile Method tmGetAccelPdlMethod = null;
        private static volatile Method tmGetGearPosMethod = null;
        private static volatile Method tmGetTempCMethod = null;

        private static volatile Object datcService = null;
        private static volatile Method datcGetClimateInfoMethod = null;
        private static volatile Method datcGetVrStatusMethod = null;

        private static volatile Object pgsService = null;
        private static volatile Method pgsGetGearPMethod = null;
        private static volatile Method pgsIsGearNMethod = null;

        private static volatile Object systemService = null;
        private static volatile Method sysGetGearPMethod = null;

        private static volatile boolean hscanListenerRegistered = false;
        private static volatile Object hscanMicomService = null;
        private static volatile Method hscanSendPacketMethod = null;
        private static volatile long lastNativeBinderHscanRxMs = 0L;
        private static volatile long lastUds0101Ms = 0L;
        private static volatile long lastUds0105Ms = 0L;
        private static volatile long lastUdsReqSentMs = 0L;
        private static volatile int udsReqToggle = 0;

        private static volatile boolean backgroundStarted = false;
        private static volatile long lastCarrotRxMs = 0L;
        private static int pollTick = 0;

        public static void markNativeBinderHscanRx() {
            lastNativeBinderHscanRxMs = System.currentTimeMillis();
        }

        public static TelemetrySnapshot getLatestSnapshot() {
            synchronized (liveSnapshot) {
                return copySnapshotLocked();
            }
        }

        public static void onHscanPacket(byte b1, byte b2, byte b3, byte[] data) {
            try {
                DebugManager.recordHscanFrame(b1, b2, b3, data);
                if ((b1 & 0xFF) == 0xC7 && data != null) {
                    long now = System.currentTimeMillis();
                    boolean isBmsAddr = ((b3 & 0x7F) == 0x2D) || (b3 == 0);
                    if ((b3 & 0x80) == 0 && data.length >= 8) {
                        synchronized (liveSnapshot) {
                            parseHscanBypassCanFrame(b3 & 0x7F, data, liveSnapshot, now, lastUds0101Ms, lastUds0105Ms);
                        }
                    } else if (isBmsAddr && data.length >= 35) {
                        synchronized (liveSnapshot) {
                            if (parseBmsUdsPayload(data, liveSnapshot)) {
                                lastUds0101Ms = now;
                                lastUds0105Ms = now;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        public static TelemetrySnapshot pollSnapshot(Object sysContext) {
            pollTick++;
            ensureBinderConnections(sysContext);
            startBackgroundThreadsIfNeeded();

            synchronized (liveSnapshot) {
                // 0. If hkg.sysmonitor is also running, ingest its /data/local/tmp/hkg_monitor_state.json first
                File sharedJsonFile = new File(SHARED_SYSMONITOR_JSON_PATH);
                boolean sharedFresh = sharedJsonFile.isFile()
                        && (System.currentTimeMillis() - sharedJsonFile.lastModified() < 5000L);
                if (sharedFresh) {
                    String sharedJson = readAllTextQuick(sharedJsonFile);
                    parseSharedSysMonitorJson(sharedJson, liveSnapshot);
                } else {
                    requestBmsUdsIfNeededLocked();
                }

                pollHevServiceLocked();
                pollTmHscanServiceLocked();
                pollDatcServiceLocked();
                pollGearLocked();
                pollMediaNowPlayingLocked();

                if (liveSnapshot.carrotConnected && (System.currentTimeMillis() - lastCarrotRxMs > 8000L)) {
                    liveSnapshot.carrotConnected = false;
                }

                TelemetrySnapshot snap = copySnapshotLocked();
                if (pollTick % 3 == 0) {
                    try {
                        writeBytesAtomic(new File(TURZX_JSON_STATE_PATH), formatTelemetryJson(snap).getBytes(UTF8));
                    } catch (Throwable ignored) {
                    }
                }
                return snap;
            }
        }

        private static void ensureBinderConnections(Object sysContext) {
            try {
                Class<?> smCls = Class.forName("android.os.ServiceManager");
                Method getSvc = smCls.getMethod("getService", String.class);
                Class<?> iBinderCls = Class.forName("android.os.IBinder");

                if (hevService == null) {
                    try {
                        Object b = getSvc.invoke(null, "com.hkmc.micom.hev");
                        if (b != null) {
                            Class<?> stubCls = Class.forName("com.hkmc.hev.IHEVService$Stub");
                            hevService = stubCls.getMethod("asInterface", iBinderCls).invoke(null, b);
                            if (hevService != null) {
                                Class<?> sc = hevService.getClass();
                                getHevStatusMethod = sc.getMethod("getHEVStatus", int.class);
                                reqHevStatusDataMethod = sc.getMethod("reqHEVStatusData", int.class);
                                try {
                                    reqFreqChangedSigMethod = sc.getMethod("requestFrequentlyChangedSignal");
                                } catch (Throwable ignored) {
                                }
                                try {
                                    getCanDbGenMethod = sc.getMethod("getCanDBGenerationInfo");
                                    canDbGeneration = ((Integer) getCanDbGenMethod.invoke(hevService)).intValue();
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }

                if (!hscanListenerRegistered) {
                    try {
                        Object b = getSvc.invoke(null, "com.hkmc.micom.hscanmicomservice");
                        if (b == null) {
                            b = getSvc.invoke(null, "com.hkmc.micom.hscan");
                        }
                        if (b != null) {
                            Class<?> stubCls = Class.forName("com.hkmc.hscan.IHscanMicomService$Stub");
                            Object svc = stubCls.getMethod("asInterface", iBinderCls).invoke(null, b);
                            if (svc != null) {
                                hscanMicomService = svc;
                                try {
                                    hscanSendPacketMethod = svc.getClass().getMethod(
                                            "sendPacket", byte.class, byte.class, byte.class, byte.class, byte.class, byte[].class);
                                } catch (Throwable ignored) {
                                }
                                Class<?> listenerIfc = Class.forName("com.hkmc.hscan.IHscanMicomListener");
                                Object listener = new HscanListenerBridge();
                                Object binderObj = Class.forName("android.os.Binder").getConstructor().newInstance();
                                try {
                                    svc.getClass().getMethod("registerHscanMicomListener", listenerIfc, iBinderCls)
                                            .invoke(svc, listener, binderObj);
                                } catch (NoSuchMethodException nsme) {
                                    svc.getClass().getMethod("registerHscanMicomListener", listenerIfc)
                                            .invoke(svc, listener);
                                }
                                hscanListenerRegistered = true;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }

                if (tmHscanService == null) {
                    try {
                        Object b = getSvc.invoke(null, "tm_hscan_service");
                        if (b != null) {
                            Class<?> stubCls = Class.forName("com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService$Stub");
                            tmHscanService = stubCls.getMethod("asInterface", iBinderCls).invoke(null, b);
                            if (tmHscanService != null) {
                                Class<?> sc = tmHscanService.getClass();
                                try { tmDriveLinePwrMethod = sc.getMethod("driveLineEleConsum"); } catch (Throwable ignored) {}
                                try { tmGetAccelPdlMethod = sc.getMethod("getAccelPdlVal"); } catch (Throwable ignored) {}
                                try { tmGetGearPosMethod = sc.getMethod("getGearPosition"); } catch (Throwable ignored) {}
                                try { tmGetTempCMethod = sc.getMethod("getTemperatureC"); } catch (Throwable ignored) {}
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }

                if (datcService == null) {
                    try {
                        Object b = getSvc.invoke(null, "com.hkmc.micom.datc");
                        if (b != null) {
                            Class<?> stubCls = Class.forName("com.hkmc.datc.IDATCService$Stub");
                            datcService = stubCls.getMethod("asInterface", iBinderCls).invoke(null, b);
                            if (datcService != null) {
                                Class<?> sc = datcService.getClass();
                                try { datcGetClimateInfoMethod = sc.getMethod("getClimateInfo", int.class); } catch (Throwable ignored) {}
                                try { datcGetVrStatusMethod = sc.getMethod("getVRClimateStatus", int.class); } catch (Throwable ignored) {}
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }

                if (pgsService == null) {
                    try {
                        Object b = getSvc.invoke(null, "com.hkmc.micom.pgs");
                        if (b != null) {
                            Class<?> stubCls = Class.forName("com.hkmc.pgs.IPgsService$Stub");
                            pgsService = stubCls.getMethod("asInterface", iBinderCls).invoke(null, b);
                            if (pgsService != null) {
                                Class<?> sc = pgsService.getClass();
                                try { pgsGetGearPMethod = sc.getMethod("getGearPStatus"); } catch (Throwable ignored) {}
                                try { pgsIsGearNMethod = sc.getMethod("isGearNState"); } catch (Throwable ignored) {}
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }

                if (systemService == null) {
                    try {
                        Object b = getSvc.invoke(null, "com.hkmc.micom.system");
                        if (b != null) {
                            Class<?> stubCls = Class.forName("com.hkmc.micom.ISystemService$Stub");
                            systemService = stubCls.getMethod("asInterface", iBinderCls).invoke(null, b);
                            if (systemService != null) {
                                try { sysGetGearPMethod = systemService.getClass().getMethod("getGearPStatus"); } catch (Throwable ignored) {}
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        private static void requestBmsUdsIfNeededLocked() {
            Object svc = hscanMicomService;
            Method sendM = hscanSendPacketMethod;
            if (svc == null || sendM == null) return;
            long now = System.currentTimeMillis();
            if (now - lastUdsReqSentMs < 1800L) return;
            if ((now - lastUds0101Ms) < 3500L && (now - lastUds0105Ms) < 3500L) return;
            int pid = ((udsReqToggle++ & 1) == 0) ? 0x01 : 0x05;
            lastUdsReqSentMs = now;
            try {
                sendM.invoke(
                        svc,
                        Byte.valueOf((byte) 0xC7),
                        Byte.valueOf((byte) 0x40),
                        Byte.valueOf((byte) 0x80),
                        Byte.valueOf((byte) 0x40),
                        Byte.valueOf((byte) 0xAD),
                        new byte[]{(byte) 0x22, (byte) 0x01, (byte) pid}
                );
            } catch (Throwable ignored) {
            }
        }

        private static int queryHevFlag(int flag) {
            Object svc = hevService;
            Method m = getHevStatusMethod;
            if (svc == null || m == null) return -1;
            try {
                return ((Integer) m.invoke(svc, Integer.valueOf(flag))).intValue();
            } catch (Throwable t) {
                hevService = null;
                return -1;
            }
        }

        private static void pollHevServiceLocked() {
            if (hevService == null) return;
            try {
                if (canDbGeneration > 0 && reqFreqChangedSigMethod != null && (pollTick % 4 == 0)) {
                    try { reqFreqChangedSigMethod.invoke(hevService); } catch (Throwable ignored) {}
                } else if (reqHevStatusDataMethod != null && (pollTick % 4 == 0)) {
                    try { reqHevStatusDataMethod.invoke(hevService, Integer.valueOf(HEV_FLAG_MOTER_PWR_AVN)); } catch (Throwable ignored) {}
                }

                int energyWh = queryHevFlag(HEV_FLAG_ENERGYMAP_1000MS);
                if (energyWh <= 0 || energyWh >= 200000 || energyWh == 65534 || energyWh == 65535) {
                    energyWh = queryHevFlag(HEV_FLAG_BMS_EVENERGYCAPA);
                }
                if (energyWh > 500 && energyWh < 200000 && energyWh != 65534 && energyWh != 65535) {
                    liveSnapshot.remainEnergyKwh = energyWh / 1000.0;
                }

                int rawDispSoc = queryHevFlag(HEV_FLAG_DISPLAY_SOC);
                if (rawDispSoc > 0 && rawDispSoc <= 200) {
                    liveSnapshot.displaySocPct = rawDispSoc * 0.5;
                }
                int rawBmsSoc = queryHevFlag(HEV_FLAG_CHARGE_STATE_SOC_PC);
                if (rawBmsSoc > 0 && rawBmsSoc <= 200 && liveSnapshot.bmsSocPct < 0.0) {
                    liveSnapshot.bmsSocPct = rawBmsSoc * 0.5;
                }

                int dte = queryHevFlag(HEV_FLAG_ELECTRIC_DTE);
                if (!isValidDteValue(dte)) dte = queryHevFlag(HEV_FLAG_TOTAL_DTE);
                if (isValidDteValue(dte)) liveSnapshot.dteKm = dte;

                int dteDeltaRaw = queryHevFlag(HEV_FLAG_ADDED_DIST_DATC_OFF);
                if (dteDeltaRaw != -1) {
                    liveSnapshot.dteAirconOffDeltaKm = parseAddedDistDatcOff(dteDeltaRaw);
                }

                int fci = queryHevFlag(HEV_FLAG_AVG_FCI);
                if (fci <= 0 || fci >= 250) fci = queryHevFlag(HEV_FLAG_TERM_AVG_FCI);
                if (fci <= 0 || fci >= 250) fci = queryHevFlag(HEV_FLAG_AVG_FCL_DRIVE_INFO);
                if (fci > 5 && fci < 250) {
                    liveSnapshot.avgEconomyKmKwh = fci / 10.0;
                }

                int pwrOther = queryHevFlag(HEV_FLAG_PWR_OTHER_ECU);
                double ldcKw = parsePower10WToKw(pwrOther, 253);
                if (ldcKw >= 0.0) {
                    liveSnapshot.electronicsPowerKw = ldcKw;
                    liveSnapshot.hasSubPower = true;
                }

                int pwrMot = queryHevFlag(HEV_FLAG_MOTER_PWR_AVN);
                if (pwrMot >= 0 && pwrMot < 250) {
                    liveSnapshot.drivePowerKw = pwrMot;
                    liveSnapshot.hasSubPower = true;
                }

                int clmtW = queryHevFlag(HEV_FLAG_DATC_CLMTPWRCNSMPTN_W);
                double clmtTotalKw = (clmtW > 0) ? parsePower10WToKw(clmtW, 1200) : -1.0;
                if (clmtTotalKw > 0.0) {
                    liveSnapshot.climatePowerKw = clmtTotalKw;
                    liveSnapshot.hasSubPower = true;
                } else {
                    double compKw = parsePower10WToKw(queryHevFlag(HEV_FLAG_PWR_E_COMPRESSOR), 1021);
                    double ptcKw = parsePower10WToKw(queryHevFlag(HEV_FLAG_DATC_PTCPWRCNSMPTNVAL), 1021);
                    if (compKw >= 0.0 || ptcKw >= 0.0) {
                        liveSnapshot.climatePowerKw = Math.max(0.0, compKw) + Math.max(0.0, ptcKw);
                        liveSnapshot.hasSubPower = true;
                    }
                }

                double batHeatKw = parsePower10WToKw(queryHevFlag(HEV_FLAG_XEV_BATHEATPWRMONCON_W), 253);
                double batCoolKw = parsePower10WToKw(queryHevFlag(HEV_FLAG_DATC_BATTERYCOMPPWRCON_W), 1021);
                if (batHeatKw >= 0.0 || batCoolKw >= 0.0) {
                    liveSnapshot.batteryCarePowerKw = Math.max(0.0, batHeatKw) + Math.max(0.0, batCoolKw);
                }

                liveSnapshot.evReady = (queryHevFlag(HEV_FLAG_EV_RDY) == 1 || queryHevFlag(HEV_FLAG_VCU_RDY) == 1);
                liveSnapshot.fastCharging = (queryHevFlag(HEV_FLAG_FAST_CHARGING) == 1);
                liveSnapshot.stdCharging = (queryHevFlag(HEV_FLAG_STD_CHA) == 1);
                liveSnapshot.charging = liveSnapshot.fastCharging || liveSnapshot.stdCharging || (queryHevFlag(HEV_FLAG_CHG_STS) == 1);
                liveSnapshot.batteryConditioning = (queryHevFlag(HEV_FLAG_EV_BATTERYCONDITIONING) == 1);
            } catch (Throwable ignored) {
            }
        }

        private static void pollTmHscanServiceLocked() {
            Object svc = tmHscanService;
            if (svc == null) return;
            try {
                Class<?> sc = svc.getClass();
                if (liveSnapshot.displaySocPct < 0.0) {
                    float soc = ((Float) sc.getMethod("remainBatteryPercentage").invoke(svc)).floatValue();
                    if (soc > 0.0f && soc <= 100.0f) liveSnapshot.displaySocPct = soc;
                }
                if (liveSnapshot.sohPct < 0.0) {
                    int soh = ((Integer) sc.getMethod("getXEV_Bms_GDS_SOH").invoke(svc)).intValue();
                    if (soh > 0 && soh <= 110) liveSnapshot.sohPct = soh;
                }
                int dte = ((Integer) sc.getMethod("realTimeAbleDriveDistance").invoke(svc)).intValue();
                if (isValidDteValue(dte)) liveSnapshot.dteKm = dte;

                int acRaw = ((Integer) sc.getMethod("airConEleConsum").invoke(svc)).intValue();
                int htrRaw = ((Integer) sc.getMethod("heaterEleConsum").invoke(svc)).intValue();
                double acKw = parseTmPowerWattsToKw(acRaw, 10210);
                double htrKw = parseTmPowerWattsToKw(htrRaw, 10210);
                if (acKw >= 0.0 || htrKw >= 0.0) {
                    double tmClmKw = Math.max(0.0, acKw) + Math.max(0.0, htrKw);
                    if (tmClmKw > 0.0 || liveSnapshot.climatePowerKw <= 0.001) {
                        liveSnapshot.climatePowerKw = tmClmKw;
                        liveSnapshot.hasSubPower = true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        private static void pollDatcServiceLocked() {
            Object svc = datcService;
            Method getInfo = datcGetClimateInfoMethod;
            Method getVr = datcGetVrStatusMethod;
            if (svc != null && getInfo != null) {
                try {
                    int opState = ((Integer) getInfo.invoke(svc, Integer.valueOf(DATC_FLAG_OP_STATE))).intValue();
                    int blowerRaw = ((Integer) getInfo.invoke(svc, Integer.valueOf(DATC_FLAG_BLOWER_LEVEL))).intValue();
                    int modeRaw = ((Integer) getInfo.invoke(svc, Integer.valueOf(DATC_FLAG_BLOWER_MODE))).intValue();
                    int autoRaw = ((Integer) getInfo.invoke(svc, Integer.valueOf(DATC_FLAG_AUTO))).intValue();
                    int acRaw = ((Integer) getInfo.invoke(svc, Integer.valueOf(DATC_FLAG_AC))).intValue();
                    int defogRaw = ((Integer) getInfo.invoke(svc, Integer.valueOf(DATC_FLAG_FRONT_DEFOG))).intValue();
                    int intakeRaw = ((Integer) getInfo.invoke(svc, Integer.valueOf(DATC_FLAG_INTAKE))).intValue();
                    int syncRaw = ((Integer) getInfo.invoke(svc, Integer.valueOf(DATC_FLAG_SYNC))).intValue();
                    int adsRaw = ((Integer) getInfo.invoke(svc, Integer.valueOf(DATC_FLAG_ADS_ON_OFF))).intValue();
                    int drvOnlyRaw = ((Integer) getInfo.invoke(svc, Integer.valueOf(DATC_FLAG_DRIVER_ONLY))).intValue();
                    int drRaw = ((Integer) getInfo.invoke(svc, Integer.valueOf(DATC_FLAG_TEMP_DR_C))).intValue();
                    int psRaw = ((Integer) getInfo.invoke(svc, Integer.valueOf(DATC_FLAG_TEMP_PS_C))).intValue();
                    int outRaw = ((Integer) getInfo.invoke(svc, Integer.valueOf(DATC_FLAG_TEMP_OUTSIDE_C))).intValue();

                    float vrDr = Float.NaN;
                    float vrPs = Float.NaN;
                    float vrOut = Float.NaN;
                    if (getVr != null) {
                        try {
                            vrDr = ((Float) getVr.invoke(svc, Integer.valueOf(DATC_FLAG_TEMP_DR_C))).floatValue();
                            vrPs = ((Float) getVr.invoke(svc, Integer.valueOf(DATC_FLAG_TEMP_PS_C))).floatValue();
                            vrOut = ((Float) getVr.invoke(svc, Integer.valueOf(DATC_FLAG_TEMP_OUTSIDE_C))).floatValue();
                        } catch (Throwable ignored) {
                        }
                    }

                    int step = normalizeDatcBlowerStep(blowerRaw);
                    if (step >= 0) {
                        liveSnapshot.hvacBlowerStep = step;
                        liveSnapshot.hasClimateData = true;
                    }
                    if (modeRaw >= 0 && modeRaw <= 10) {
                        liveSnapshot.hvacBlowerMode = modeRaw;
                        liveSnapshot.hasClimateData = true;
                    }
                    liveSnapshot.hvacOn = (opState == 1) || (step > 0);
                    liveSnapshot.hvacAuto = (autoRaw == 1);
                    liveSnapshot.hvacAc = (acRaw == 1);
                    liveSnapshot.hvacFrontDefog = (defogRaw == 1);
                    liveSnapshot.hvacRecirc = (intakeRaw == 1);
                    liveSnapshot.hvacSync = (syncRaw == 1);
                    liveSnapshot.hvacAds = (adsRaw == 1);
                    liveSnapshot.hvacDriverOnly = (drvOnlyRaw == 1);

                    double drC = convertDatcRawTempToCelsius(drRaw, vrDr);
                    if (drC > 0.0) {
                        liveSnapshot.hvacDrTempC = drC;
                        liveSnapshot.hasClimateData = true;
                    }
                    double psC = convertDatcRawTempToCelsius(psRaw, vrPs);
                    if (psC > 0.0) {
                        liveSnapshot.hvacPsTempC = psC;
                    }
                    if (!Float.isNaN(vrOut) && vrOut >= -45.0f && vrOut <= 65.0f && Math.abs(vrOut - 85.0f) > 0.5f) {
                        liveSnapshot.outdoorTempC = Math.round(vrOut * 2.0) / 2.0;
                    } else if (outRaw > 0 && outRaw < 250) {
                        double outC = parseCanTempByteToCelsius(outRaw);
                        if (outC > -50.0) liveSnapshot.outdoorTempC = outC;
                    }
                } catch (Throwable t) {
                    datcService = null;
                }
            }

            Object tmSvc = tmHscanService;
            if (tmSvc != null && tmGetTempCMethod != null && liveSnapshot.outdoorTempC < -50.0) {
                try {
                    float tOut = ((Float) tmGetTempCMethod.invoke(tmSvc)).floatValue();
                    if (!Float.isNaN(tOut) && tOut >= -45.0f && tOut <= 65.0f && Math.abs(tOut - 85.0f) > 0.5f) {
                        liveSnapshot.outdoorTempC = Math.round(tOut * 2.0) / 2.0;
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        private static void pollGearLocked() {
            int tmGearRaw = -1;
            if (tmHscanService != null && tmGetGearPosMethod != null) {
                try {
                    tmGearRaw = ((Integer) tmGetGearPosMethod.invoke(tmHscanService)).intValue();
                } catch (Throwable ignored) {
                }
            }
            Boolean pgsGearP = null;
            Boolean pgsGearN = null;
            if (pgsService != null) {
                try { if (pgsGetGearPMethod != null) pgsGearP = (Boolean) pgsGetGearPMethod.invoke(pgsService); } catch (Throwable ignored) {}
                try { if (pgsIsGearNMethod != null) pgsGearN = (Boolean) pgsIsGearNMethod.invoke(pgsService); } catch (Throwable ignored) {}
            }
            int sysGearP = -1;
            if (systemService != null && sysGetGearPMethod != null) {
                try { sysGearP = ((Integer) sysGetGearPMethod.invoke(systemService)).intValue(); } catch (Throwable ignored) {}
            }
            boolean rearCam = "1".equals(getSystemProperty("persist.sys.rearcam.open", "0"));
            String resolved = resolveGearPosition(tmGearRaw, pgsGearP, pgsGearN, sysGearP, rearCam, liveSnapshot.getEffectiveDriveMotorKw());
            if (!resolved.isEmpty()) {
                liveSnapshot.gearPos = resolved;
            }
        }

        private static void pollMediaNowPlayingLocked() {
            String propTitle = getSystemProperty("persist.sys.hkg.media.title", "");
            String propArtist = getSystemProperty("persist.sys.hkg.media.artist", "");
            String propAlbum = getSystemProperty("persist.sys.hkg.media.album", "");
            String propSource = getSystemProperty("persist.sys.hkg.media.source", "");
            if (!propTitle.isEmpty()) liveSnapshot.mediaTitle = propTitle;
            if (!propArtist.isEmpty()) liveSnapshot.mediaArtist = propArtist;
            if (!propAlbum.isEmpty()) liveSnapshot.mediaAlbum = propAlbum;
            if (!propSource.isEmpty()) liveSnapshot.mediaSource = propSource;

            File mediaFile = new File("/data/local/tmp/hkg_media_state.txt");
            if (mediaFile.isFile()) {
                String line = readFirstLineQuick(mediaFile);
                if (!line.isEmpty() && line.contains("|")) {
                    String[] parts = line.split("\\|");
                    if (parts.length >= 1 && !parts[0].trim().isEmpty()) liveSnapshot.mediaSource = parts[0].trim();
                    if (parts.length >= 2 && !parts[1].trim().isEmpty()) liveSnapshot.mediaTitle = parts[1].trim();
                    if (parts.length >= 3 && !parts[2].trim().isEmpty()) liveSnapshot.mediaArtist = parts[2].trim();
                    if (parts.length >= 4 && !parts[3].trim().isEmpty()) liveSnapshot.mediaAlbum = parts[3].trim();
                }
            }
        }

        private static void startBackgroundThreadsIfNeeded() {
            if (backgroundStarted) return;
            backgroundStarted = true;

            Thread tLog = new Thread(new Runnable() {
                @Override
                public void run() {
                    runLogcatHscanWatcherLoop();
                }
            }, "Turzx-LogcatHscan");
            tLog.setDaemon(true);
            tLog.start();

            Thread tUdpBroadcast = new Thread(new Runnable() {
                @Override
                public void run() {
                    runCarrotUdpListenerLoop(CARROT_UDP_PORT_BROADCAST);
                }
            }, "Turzx-CarrotUdp-7706");
            tUdpBroadcast.setDaemon(true);
            tUdpBroadcast.start();

            Thread tUdpPrimary = new Thread(new Runnable() {
                @Override
                public void run() {
                    runCarrotUdpListenerLoop(CARROT_UDP_PORT_PRIMARY);
                }
            }, "Turzx-CarrotUdp-7705");
            tUdpPrimary.setDaemon(true);
            tUdpPrimary.start();

            Thread tWs = new Thread(new Runnable() {
                @Override
                public void run() {
                    runCarrotCvs1WsClientLoop();
                }
            }, "Turzx-CarrotWs");
            tWs.setDaemon(true);
            tWs.start();

            Thread tHttp = new Thread(new Runnable() {
                @Override
                public void run() {
                    runEmbeddedWebServerLoop();
                }
            }, "Turzx-HttpServer");
            tHttp.setDaemon(true);
            tHttp.start();
        }

        private static void runLogcatHscanWatcherLoop() {
            while (true) {
                Process proc = null;
                BufferedReader br = null;
                try {
                    proc = Runtime.getRuntime().exec(new String[]{
                            "/system/bin/logcat", "-b", "main", "-b", "system", "-v", "raw", "-s", "TM_HSCANPacketRunner:I"
                    });
                    br = new BufferedReader(new InputStreamReader(proc.getInputStream(), UTF8), 4096);
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (System.currentTimeMillis() - lastNativeBinderHscanRxMs > 2500L) {
                            parseTmHscanLogLine(line, null);
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (br != null) { try { br.close(); } catch (Throwable ignored) {} }
                    if (proc != null) { try { proc.destroy(); } catch (Throwable ignored) {} }
                }
                try { Thread.sleep(3000L); } catch (InterruptedException ignored) {}
            }
        }

        private static void runCarrotUdpListenerLoop(int port) {
            while (true) {
                DatagramSocket sock = null;
                try {
                    sock = new DatagramSocket(null);
                    sock.setReuseAddress(true);
                    sock.bind(new InetSocketAddress(port));
                    byte[] buf = new byte[4096];
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    while (true) {
                        pkt.setLength(buf.length);
                        sock.receive(pkt);
                        String json = new String(pkt.getData(), pkt.getOffset(), pkt.getLength(), UTF8);
                        synchronized (liveSnapshot) {
                            if (parseCarrotManJson(json, liveSnapshot)) {
                                if ((liveSnapshot.carrotIp == null || liveSnapshot.carrotIp.isEmpty()) && pkt.getAddress() != null) {
                                    liveSnapshot.carrotIp = pkt.getAddress().getHostAddress();
                                }
                                lastCarrotRxMs = System.currentTimeMillis();
                            }
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (sock != null) { try { sock.close(); } catch (Throwable ignored) {} }
                }
                try { Thread.sleep(3000L); } catch (InterruptedException ignored) {}
            }
        }

        /**
         * Connects to Comma 3X (ajouatom/openpilot carrot-wip) ws://<comma_ip>:7000/ws/raw
         * and decodes binary CVS1 frames (speed, cruise, lead distance, TPMS, and 3D lane polynomials).
         */
        private static void runCarrotCvs1WsClientLoop() {
            while (true) {
                Socket wsSock = null;
                try {
                    String ip;
                    synchronized (liveSnapshot) {
                        ip = liveSnapshot.carrotIp;
                    }
                    if (ip == null || ip.isEmpty()) {
                        ip = TurzxOverlayController.getConfig().commaIp;
                    }
                    if (ip == null || ip.isEmpty()) {
                        Thread.sleep(2500L);
                        continue;
                    }

                    wsSock = new Socket();
                    wsSock.connect(new InetSocketAddress(ip, CARROT_WS_PORT), 2000);
                    wsSock.setSoTimeout(5000);

                    OutputStream os = wsSock.getOutputStream();
                    InputStream is = wsSock.getInputStream();
                    String handshake = "GET /ws/raw HTTP/1.1\r\n"
                            + "Host: " + ip + ":" + CARROT_WS_PORT + "\r\n"
                            + "Upgrade: websocket\r\n"
                            + "Connection: Upgrade\r\n"
                            + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                            + "Sec-WebSocket-Version: 13\r\n\r\n";
                    os.write(handshake.getBytes(UTF8));
                    os.flush();

                    // Read HTTP 101 response headers
                    int matchState = 0;
                    while (matchState < 4) {
                        int b = is.read();
                        if (b < 0) throw new RuntimeException("EOF during WS handshake");
                        if ((matchState == 0 || matchState == 2) && b == '\r') matchState++;
                        else if ((matchState == 1 || matchState == 3) && b == '\n') matchState++;
                        else matchState = 0;
                    }

                    // Read WebSocket frames
                    while (true) {
                        int b0 = is.read();
                        int b1 = is.read();
                        if (b0 < 0 || b1 < 0) break;
                        int opcode = b0 & 0x0F;
                        boolean masked = (b1 & 0x80) != 0;
                        long payloadLen = b1 & 0x7F;
                        if (payloadLen == 126) {
                            payloadLen = ((is.read() & 0xFF) << 8) | (is.read() & 0xFF);
                        } else if (payloadLen == 127) {
                            payloadLen = 0;
                            for (int i = 0; i < 8; i++) {
                                payloadLen = (payloadLen << 8) | (is.read() & 0xFF);
                            }
                        }
                        if (payloadLen < 0 || payloadLen > 131072) break;
                        byte[] maskKey = null;
                        if (masked) {
                            maskKey = new byte[4];
                            readFully(is, maskKey, 4);
                        }
                        byte[] payload = new byte[(int) payloadLen];
                        readFully(is, payload, (int) payloadLen);
                        if (masked && maskKey != null) {
                            for (int i = 0; i < payload.length; i++) {
                                payload[i] ^= maskKey[i & 3];
                            }
                        }
                        if (opcode == 2 && payload.length >= 12) { // Binary CVS1 / CVB1 frame
                            synchronized (liveSnapshot) {
                                if (decodeCvs1BinaryPayload(payload, liveSnapshot)) {
                                    lastCarrotRxMs = System.currentTimeMillis();
                                }
                            }
                        } else if (opcode == 8) { // Close
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (wsSock != null) { try { wsSock.close(); } catch (Throwable ignored) {} }
                }
                try { Thread.sleep(3000L); } catch (InterruptedException ignored) {}
            }
        }

        private static void readFully(InputStream is, byte[] buf, int len) throws Exception {
            int off = 0;
            while (off < len) {
                int r = is.read(buf, off, len - off);
                if (r < 0) throw new RuntimeException("Unexpected EOF");
                off += r;
            }
        }

        private static void runEmbeddedWebServerLoop() {
            ServerSocket server = null;
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress("0.0.0.0", HTTP_PORT));
                while (true) {
                    final Socket client = server.accept();
                    try {
                        client.setSoTimeout(2000);
                        BufferedReader br = new BufferedReader(new InputStreamReader(client.getInputStream(), UTF8));
                        String reqLine = br.readLine();
                        String path = "/";
                        if (reqLine != null) {
                            String[] parts = reqLine.split(" ");
                            if (parts.length >= 2) path = parts[1];
                        }

                        OutputStream os = client.getOutputStream();
                        if (path.startsWith("/status.json")) {
                            byte[] body = formatTelemetryJson(getLatestSnapshot()).getBytes(UTF8);
                            writeHttpResponse(os, "200 OK", "application/json; charset=utf-8", body);
                        } else if (path.startsWith("/frame.jpg")) {
                            byte[] jpg = TurzxUsbDriver.getLatestLandscapeJpeg();
                            if (jpg != null && jpg.length > 0) {
                                writeHttpResponse(os, "200 OK", "image/jpeg", jpg);
                            } else {
                                writeHttpResponse(os, "404 Not Found", "text/plain; charset=utf-8", "Frame not ready".getBytes(UTF8));
                            }
                        } else if (path.startsWith("/debug.txt") || path.startsWith("/logs")) {
                            String dir = TurzxOverlayController.getInstallDir();
                            String savedPath = DebugManager.dumpDiagnosticsToDisk(dir, getLatestSnapshot(), TurzxOverlayController.getConfig());
                            String report = DebugManager.buildDiagnosticReport(dir, getLatestSnapshot(), TurzxOverlayController.getConfig(), savedPath);
                            writeHttpResponse(os, "200 OK", "text/plain; charset=utf-8", report.getBytes(UTF8));
                        } else if (path.startsWith("/update") || path.startsWith("/ota")) {
                            boolean force = path.contains("force=1") || path.contains("force=true");
                            OtaUpdateManager.triggerManualOta(TurzxOverlayController.getInstallDir(), force);
                            String jsonResp = "{\"ok\":true,\"triggered\":true,\"status\":\""
                                    + escapeJson(OtaUpdateManager.getStatusText()) + "\"}";
                            writeHttpResponse(os, "200 OK", "application/json; charset=utf-8", jsonResp.getBytes(UTF8));
                        } else {
                            File htmlFile = new File(TurzxOverlayController.getInstallDir(), "web/turzx_92_mockup.html");
                            byte[] htmlBytes = htmlFile.isFile()
                                    ? readFileBytesQuick(htmlFile)
                                    : "<html><body><h2>HKG 5W TURZX 9.2 Cluster HUD</h2><p><a href='/status.json'>/status.json</a> | <a href='/frame.jpg'>/frame.jpg</a> | <a href='/debug.txt'>/debug.txt</a> | <a href='/update'>/update</a></p></body></html>".getBytes(UTF8);
                            writeHttpResponse(os, "200 OK", "text/html; charset=utf-8", htmlBytes);
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        try { client.close(); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (server != null) { try { server.close(); } catch (Throwable ignored) {} }
            }
        }

        private static void writeHttpResponse(OutputStream os, String status, String contentType, byte[] body) throws Exception {
            String headers = "HTTP/1.1 " + status + "\r\n"
                    + "Content-Type: " + contentType + "\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            os.write(headers.getBytes(UTF8));
            os.write(body);
            os.flush();
        }

        private static TelemetrySnapshot copySnapshotLocked() {
            TelemetrySnapshot c = new TelemetrySnapshot();
            c.displaySocPct = liveSnapshot.displaySocPct;
            c.bmsSocPct = liveSnapshot.bmsSocPct;
            c.remainEnergyKwh = liveSnapshot.remainEnergyKwh;
            c.sohPct = liveSnapshot.sohPct;
            c.dteKm = liveSnapshot.dteKm;
            c.dteAirconOffDeltaKm = liveSnapshot.dteAirconOffDeltaKm;
            c.distanceUnitMiles = liveSnapshot.distanceUnitMiles;
            c.avgEconomyKmKwh = liveSnapshot.avgEconomyKmKwh;
            c.packVoltV = liveSnapshot.packVoltV;
            c.packCurrA = liveSnapshot.packCurrA;
            c.hasPackCurrent = liveSnapshot.hasPackCurrent;
            c.packPowerKw = liveSnapshot.packPowerKw;
            c.drivePowerKw = liveSnapshot.drivePowerKw;
            c.climatePowerKw = liveSnapshot.climatePowerKw;
            c.electronicsPowerKw = liveSnapshot.electronicsPowerKw;
            c.batteryCarePowerKw = liveSnapshot.batteryCarePowerKw;
            c.hasSubPower = liveSnapshot.hasSubPower;
            c.cellMaxVoltV = liveSnapshot.cellMaxVoltV;
            c.cellMaxNo = liveSnapshot.cellMaxNo;
            c.cellMinVoltV = liveSnapshot.cellMinVoltV;
            c.cellMinNo = liveSnapshot.cellMinNo;
            c.cellDiffMv = liveSnapshot.cellDiffMv;
            c.battMaxTempC = liveSnapshot.battMaxTempC;
            c.battMinTempC = liveSnapshot.battMinTempC;
            c.battInletTempC = liveSnapshot.battInletTempC;
            c.battHeaterTempC = liveSnapshot.battHeaterTempC;
            c.auxBattVoltV = liveSnapshot.auxBattVoltV;
            c.auxBattSocPct = liveSnapshot.auxBattSocPct;
            c.cumulChargeKwh = liveSnapshot.cumulChargeKwh;
            c.cumulDischargeKwh = liveSnapshot.cumulDischargeKwh;
            c.evReady = liveSnapshot.evReady;
            c.charging = liveSnapshot.charging;
            c.fastCharging = liveSnapshot.fastCharging;
            c.stdCharging = liveSnapshot.stdCharging;
            c.batteryConditioning = liveSnapshot.batteryConditioning;
            c.gearPos = liveSnapshot.gearPos;
            c.accelPedalPct = liveSnapshot.accelPedalPct;
            c.hasClimateData = liveSnapshot.hasClimateData;
            c.hvacOn = liveSnapshot.hvacOn;
            c.hvacAuto = liveSnapshot.hvacAuto;
            c.hvacAc = liveSnapshot.hvacAc;
            c.hvacSync = liveSnapshot.hvacSync;
            c.hvacDriverOnly = liveSnapshot.hvacDriverOnly;
            c.hvacFrontDefog = liveSnapshot.hvacFrontDefog;
            c.hvacRecirc = liveSnapshot.hvacRecirc;
            c.hvacAds = liveSnapshot.hvacAds;
            c.hvacBlowerStep = liveSnapshot.hvacBlowerStep;
            c.hvacBlowerMode = liveSnapshot.hvacBlowerMode;
            c.hvacDrTempC = liveSnapshot.hvacDrTempC;
            c.hvacPsTempC = liveSnapshot.hvacPsTempC;
            c.outdoorTempC = liveSnapshot.outdoorTempC;
            c.inCarTempC = liveSnapshot.inCarTempC;
            c.carrotConnected = liveSnapshot.carrotConnected;
            c.carrotSpeedKph = liveSnapshot.carrotSpeedKph;
            c.carrotCruiseKph = liveSnapshot.carrotCruiseKph;
            c.carrotActive = liveSnapshot.carrotActive;
            c.carrotSdiType = liveSnapshot.carrotSdiType;
            c.carrotSdiSpeedLimit = liveSnapshot.carrotSdiSpeedLimit;
            c.carrotSdiDistM = liveSnapshot.carrotSdiDistM;
            c.carrotTbtDistM = liveSnapshot.carrotTbtDistM;
            c.carrotTbtRoad = liveSnapshot.carrotTbtRoad;
            c.carrotIp = liveSnapshot.carrotIp;
            c.leadDistM = liveSnapshot.leadDistM;
            c.leadRelVelKph = liveSnapshot.leadRelVelKph;
            c.hasLeadCar = liveSnapshot.hasLeadCar;
            c.tpmsFlPsi = liveSnapshot.tpmsFlPsi;
            c.tpmsFrPsi = liveSnapshot.tpmsFrPsi;
            c.tpmsRlPsi = liveSnapshot.tpmsRlPsi;
            c.tpmsRrPsi = liveSnapshot.tpmsRrPsi;
            if (liveSnapshot.pathPoly != null) {
                c.pathPoly = (float[]) liveSnapshot.pathPoly.clone();
            }
            c.mediaSource = liveSnapshot.mediaSource;
            c.mediaTitle = liveSnapshot.mediaTitle;
            c.mediaArtist = liveSnapshot.mediaArtist;
            c.mediaAlbum = liveSnapshot.mediaAlbum;
            c.mediaPlaying = liveSnapshot.mediaPlaying;
            c.mediaPositionSec = liveSnapshot.mediaPositionSec;
            c.mediaDurationSec = liveSnapshot.mediaDurationSec;
            return c;
        }
    }

    // =========================================================================
    // 4. HEADUNIT WINDOWMANAGER CONTROL & PREVIEW OVERLAY (TYPE_VOLUME_OVERLAY)
    // =========================================================================

    public static final class TurzxOverlayController {
        private static volatile String installDir = "/data/nemesis/apps/hkg.turzxmonitor";
        private static volatile File configFile = null;
        private static final MonitorConfig config = new MonitorConfig();

        private static volatile Object uiHandler = null;
        private static volatile Object windowManager = null;
        private static volatile Object sysContext = null;
        private static volatile Object rootView = null;
        private static volatile Object currentWmLp = null;
        private static volatile Object statusTv = null;
        private static volatile boolean isVisible = false;
        private static volatile String bannerMsg = "";
        private static volatile long bannerUntilMs = 0L;
        private static final CountDownLatch uiLatch = new CountDownLatch(1);

        private static final Runnable REFRESH_TASK = new Runnable() {
            @Override
            public void run() {
                refreshOnUiThread();
            }
        };

        public static String getInstallDir() {
            return installDir;
        }

        public static MonitorConfig getConfig() {
            return config;
        }

        public static Object getSysContext() {
            return sysContext;
        }

        public static boolean isUiLooperReady() {
            return uiHandler != null;
        }

        public static void showBannerMessage(String msg, long durationMs) {
            bannerMsg = msg != null ? msg : "";
            bannerUntilMs = System.currentTimeMillis() + Math.max(1500L, durationMs);
            postToUi(new Runnable() {
                @Override
                public void run() {
                    refreshOnUiThread();
                }
            });
        }

        public static void init(final String dir) {
            installDir = dir;
            configFile = new File(dir, "config/monitor.conf");
            MonitorConfig loaded = loadMonitorConfig(configFile);
            config.overlayEnabled = loaded.overlayEnabled;
            config.overlayExpanded = loaded.overlayExpanded;
            config.usbPushEnabled = loaded.usbPushEnabled;
            config.usbBrightness = loaded.usbBrightness;
            config.usbRotation = loaded.usbRotation;
            config.usbJpegQuality = loaded.usbJpegQuality;
            config.usbFps = loaded.usbFps;
            config.commaIp = loaded.commaIp;
            config.autoUpdate = loaded.autoUpdate;
            config.x = loaded.x;
            config.y = loaded.y;

            Thread uiThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Class<?> looperCls = Class.forName("android.os.Looper");
                        try {
                            looperCls.getMethod("prepareMainLooper").invoke(null);
                        } catch (Throwable ignored) {
                            looperCls.getMethod("prepare").invoke(null);
                        }
                        Object myLooper = looperCls.getMethod("myLooper").invoke(null);
                        Class<?> handlerCls = Class.forName("android.os.Handler");
                        uiHandler = handlerCls.getConstructor(looperCls).newInstance(myLooper);
                        uiLatch.countDown();

                        if (config.overlayEnabled) {
                            postToUi(new Runnable() {
                                @Override
                                public void run() {
                                    showOnUiThread();
                                }
                            });
                        }

                        Method loopMethod = looperCls.getMethod("loop");
                        while (true) {
                            try {
                                loopMethod.invoke(null);
                                break;
                            } catch (Throwable ignored) {
                            }
                        }
                    } catch (Throwable t) {
                        uiLatch.countDown();
                    }
                }
            }, "TurzxUiThread");
            uiThread.setDaemon(true);
            uiThread.start();

            try {
                uiLatch.await(3000L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
        }

        public static void toggle() {
            postToUi(new Runnable() {
                @Override
                public void run() {
                    if (isVisible) {
                        config.overlayEnabled = false;
                        saveMonitorConfig(configFile, config);
                        hideOnUiThread();
                    } else {
                        config.overlayEnabled = true;
                        saveMonitorConfig(configFile, config);
                        showOnUiThread();
                    }
                }
            });
        }

        public static void setEnabledAndShow(final boolean enable) {
            postToUi(new Runnable() {
                @Override
                public void run() {
                    config.overlayEnabled = enable;
                    saveMonitorConfig(configFile, config);
                    if (enable) showOnUiThread();
                    else hideOnUiThread();
                }
            });
        }

        private static void postToUi(Runnable r) {
            Object h = uiHandler;
            if (h == null) return;
            try { h.getClass().getMethod("post", Runnable.class).invoke(h, r); } catch (Throwable ignored) {}
        }

        private static void postDelayedToUi(Runnable r, long delayMs) {
            Object h = uiHandler;
            if (h == null) return;
            try { h.getClass().getMethod("postDelayed", Runnable.class, long.class).invoke(h, r, delayMs); } catch (Throwable ignored) {}
        }

        private static boolean ensureUiContext() {
            if (sysContext != null && windowManager != null) return true;
            try {
                Class<?> smCls = Class.forName("android.os.ServiceManager");
                Method getSvc = smCls.getMethod("getService", String.class);
                if (getSvc.invoke(null, "window") == null || getSvc.invoke(null, "display") == null) {
                    return false;
                }
                Class<?> atCls = Class.forName("android.app.ActivityThread");
                Object at = null;
                try { at = atCls.getMethod("currentActivityThread").invoke(null); } catch (Throwable ignored) {}
                if (at == null) at = atCls.getMethod("systemMain").invoke(null);
                if (at != null && sysContext == null) {
                    sysContext = atCls.getMethod("getSystemContext").invoke(at);
                }
                if (sysContext != null && windowManager == null) {
                    windowManager = sysContext.getClass().getMethod("getSystemService", String.class).invoke(sysContext, "window");
                }
            } catch (Throwable t) {
                sysContext = null;
                windowManager = null;
            }
            return sysContext != null && windowManager != null;
        }

        private static void showOnUiThread() {
            if (isVisible && rootView != null) return;
            if (!ensureUiContext()) {
                postDelayedToUi(new Runnable() {
                    @Override
                    public void run() {
                        if (config.overlayEnabled && !isVisible) showOnUiThread();
                    }
                }, 1500L);
                return;
            }
            try {
                Class<?> ctxCls = Class.forName("android.content.Context");
                Class<?> viewCls = Class.forName("android.view.View");
                Class<?> vgCls = Class.forName("android.view.ViewGroup");
                Class<?> vgLpCls = Class.forName("android.view.ViewGroup$LayoutParams");
                Class<?> llCls = Class.forName("android.widget.LinearLayout");
                Class<?> llLpCls = Class.forName("android.widget.LinearLayout$LayoutParams");
                Class<?> tvCls = Class.forName("android.widget.TextView");
                Class<?> gdCls = Class.forName("android.graphics.drawable.GradientDrawable");
                Class<?> drawableCls = Class.forName("android.graphics.drawable.Drawable");

                Object root = llCls.getConstructor(ctxCls).newInstance(sysContext);
                llCls.getMethod("setOrientation", int.class).invoke(root, 0); // HORIZONTAL
                llCls.getMethod("setGravity", int.class).invoke(root, 16);    // CENTER_VERTICAL
                viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(root, 16, 8, 16, 8);

                Object gd = gdCls.getConstructor().newInstance();
                gdCls.getMethod("setColor", int.class).invoke(gd, 0xEE0B111E);
                gdCls.getMethod("setCornerRadius", float.class).invoke(gd, 14f);
                gdCls.getMethod("setStroke", int.class, int.class).invoke(gd, 2, 0xFF38BDF8);
                viewCls.getMethod("setBackgroundDrawable", drawableCls).invoke(root, gd);

                Object tv = tvCls.getConstructor(ctxCls).newInstance(sysContext);
                tvCls.getMethod("setText", CharSequence.class).invoke(tv, "🖥️ TURZX 9.2 HUD · 초기화 중...");
                tvCls.getMethod("setTextSize", int.class, float.class).invoke(tv, 2, 13.5f);
                tvCls.getMethod("setTextColor", int.class).invoke(tv, 0xFFF8FAFC);

                Constructor<?> llLpCtor = llLpCls.getConstructor(int.class, int.class);
                vgCls.getMethod("addView", viewCls, vgLpCls).invoke(root, tv, llLpCtor.newInstance(-2, -2));

                // Action buttons: [☁️ 업데이트] [💾 로그저장] [✖]
                Object btnUpdate = createOverlayButton(ctxCls, viewCls, tvCls, gdCls, drawableCls,
                        "  ☁️ 업데이트  ", 0xFF0284C7, new Runnable() {
                            @Override
                            public void run() {
                                OtaUpdateManager.triggerManualOta(installDir, false);
                            }
                        });
                Object lpBtn1 = llLpCtor.newInstance(-2, -2);
                llLpCls.getMethod("setMargins", int.class, int.class, int.class, int.class).invoke(lpBtn1, 14, 0, 0, 0);
                vgCls.getMethod("addView", viewCls, vgLpCls).invoke(root, btnUpdate, lpBtn1);

                Object btnLog = createOverlayButton(ctxCls, viewCls, tvCls, gdCls, drawableCls,
                        "  💾 로그저장  ", 0xFF0D9488, new Runnable() {
                            @Override
                            public void run() {
                                DebugManager.triggerAsyncDump(installDir);
                            }
                        });
                Object lpBtn2 = llLpCtor.newInstance(-2, -2);
                llLpCls.getMethod("setMargins", int.class, int.class, int.class, int.class).invoke(lpBtn2, 8, 0, 0, 0);
                vgCls.getMethod("addView", viewCls, vgLpCls).invoke(root, btnLog, lpBtn2);

                Object btnHide = createOverlayButton(ctxCls, viewCls, tvCls, gdCls, drawableCls,
                        "  ✖  ", 0xFF334155, new Runnable() {
                            @Override
                            public void run() {
                                setEnabledAndShow(false);
                            }
                        });
                Object lpBtn3 = llLpCtor.newInstance(-2, -2);
                llLpCls.getMethod("setMargins", int.class, int.class, int.class, int.class).invoke(lpBtn3, 8, 0, 0, 0);
                vgCls.getMethod("addView", viewCls, vgLpCls).invoke(root, btnHide, lpBtn3);

                // Drag to move overlay
                attachDragAndHideTouchListener(root);

                Class<?> wmLpCls = Class.forName("android.view.WindowManager$LayoutParams");
                Constructor<?> lpCtor = wmLpCls.getConstructor(int.class, int.class, int.class, int.class, int.class);
                final int flags = 0x00000008 | 0x00000020 | 0x00000100 | 0x01000000;
                Object lp = lpCtor.newInstance(-2, -2, 2020, flags, -3); // TYPE_VOLUME_OVERLAY = 2020
                wmLpCls.getField("gravity").setInt(lp, 51); // TOP | LEFT
                wmLpCls.getField("x").setInt(lp, config.x);
                wmLpCls.getField("y").setInt(lp, config.y);

                Class<?> vmCls = Class.forName("android.view.ViewManager");
                vmCls.getMethod("addView", viewCls, vgLpCls).invoke(windowManager, root, lp);

                rootView = root;
                currentWmLp = lp;
                statusTv = tv;
                isVisible = true;
                refreshOnUiThread();
            } catch (Throwable ignored) {
            }
        }

        private static Object createOverlayButton(
                Class<?> ctxCls,
                Class<?> viewCls,
                Class<?> tvCls,
                Class<?> gdCls,
                Class<?> drawableCls,
                String label,
                int strokeColor,
                final Runnable onClick
        ) throws Exception {
            Object btn = tvCls.getConstructor(ctxCls).newInstance(sysContext);
            tvCls.getMethod("setText", CharSequence.class).invoke(btn, label);
            tvCls.getMethod("setTextSize", int.class, float.class).invoke(btn, 2, 12.0f);
            tvCls.getMethod("setTextColor", int.class).invoke(btn, 0xFFF8FAFC);
            viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(btn, 8, 4, 8, 4);

            Object gd = gdCls.getConstructor().newInstance();
            gdCls.getMethod("setColor", int.class).invoke(gd, 0xFF1E293B);
            gdCls.getMethod("setCornerRadius", float.class).invoke(gd, 10f);
            gdCls.getMethod("setStroke", int.class, int.class).invoke(gd, 1, strokeColor);
            viewCls.getMethod("setBackgroundDrawable", drawableCls).invoke(btn, gd);
            viewCls.getMethod("setClickable", boolean.class).invoke(btn, true);

            Class<?> clickIfc = Class.forName("android.view.View$OnClickListener");
            Object clickProxy = Proxy.newProxyInstance(
                    clickIfc.getClassLoader(),
                    new Class<?>[]{clickIfc},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("onClick".equals(method.getName()) && onClick != null) {
                                try { onClick.run(); } catch (Throwable ignored) {}
                            }
                            return null;
                        }
                    }
            );
            viewCls.getMethod("setOnClickListener", clickIfc).invoke(btn, clickProxy);
            return btn;
        }

        private static void hideOnUiThread() {
            Object oldRoot = rootView;
            rootView = null;
            currentWmLp = null;
            statusTv = null;
            isVisible = false;
            try {
                if (oldRoot != null && windowManager != null) {
                    Class<?> viewCls = Class.forName("android.view.View");
                    Class<?> vmCls = Class.forName("android.view.ViewManager");
                    vmCls.getMethod("removeView", viewCls).invoke(windowManager, oldRoot);
                }
            } catch (Throwable ignored) {
            }
        }

        private static void refreshOnUiThread() {
            if (!isVisible || rootView == null || statusTv == null) return;
            try {
                TelemetrySnapshot snap = TelemetryCollector.getLatestSnapshot();
                long now = System.currentTimeMillis();
                String extra = "";
                if (OtaUpdateManager.isUpdating()) {
                    extra = "   |   ☁️ " + OtaUpdateManager.getStatusText();
                } else if (now < bannerUntilMs && bannerMsg != null && !bannerMsg.isEmpty()) {
                    extra = "   |   " + bannerMsg;
                }
                String text = String.format(Locale.US,
                        "🖥️ v%s · %s   |   ⚡ %.0f%% (%dkm)   |   ❄️ 실내 %.1f°·실외 %.1f°%s",
                        MONITOR_VERSION,
                        TurzxUsbDriver.getStatusText(),
                        snap.getEffectiveSocPct() >= 0 ? snap.getEffectiveSocPct() : 0.0,
                        snap.dteKm >= 0 ? snap.dteKm : 0,
                        snap.inCarTempC > -50 ? snap.inCarTempC : 22.5,
                        snap.outdoorTempC > -50 ? snap.outdoorTempC : 18.0,
                        extra
                );
                statusTv.getClass().getMethod("setText", CharSequence.class).invoke(statusTv, text);
            } catch (Throwable ignored) {
            }
            postDelayedToUi(REFRESH_TASK, 1000L);
        }

        private static void attachDragAndHideTouchListener(final Object view) throws Exception {
            Class<?> touchListenerCls = Class.forName("android.view.View$OnTouchListener");
            Class<?> viewCls = Class.forName("android.view.View");
            viewCls.getMethod("setClickable", boolean.class).invoke(view, true);

            Object proxy = Proxy.newProxyInstance(
                    touchListenerCls.getClassLoader(),
                    new Class<?>[]{touchListenerCls},
                    new InvocationHandler() {
                        private float downRawX = 0f;
                        private float downRawY = 0f;
                        private int startX = 0;
                        private int startY = 0;
                        private boolean dragging = false;

                        @Override
                        public Object invoke(Object proxyObj, Method method, Object[] args) {
                            if ("onTouch".equals(method.getName()) && args != null && args.length >= 2 && args[1] != null) {
                                try {
                                    Object me = args[1];
                                    Class<?> meCls = me.getClass();
                                    int act = ((Integer) meCls.getMethod("getAction").invoke(me)).intValue() & 0xFF;
                                    float rx = ((Float) meCls.getMethod("getRawX").invoke(me)).floatValue();
                                    float ry = ((Float) meCls.getMethod("getRawY").invoke(me)).floatValue();
                                    if (act == 0) {
                                        downRawX = rx;
                                        downRawY = ry;
                                        startX = config.x;
                                        startY = config.y;
                                        dragging = false;
                                    } else if (act == 2) {
                                        int dx = Math.round(rx - downRawX);
                                        int dy = Math.round(ry - downRawY);
                                        if (!dragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) dragging = true;
                                        if (dragging && currentWmLp != null && windowManager != null) {
                                            config.x = Math.max(0, Math.min(1800, startX + dx));
                                            config.y = Math.max(0, Math.min(680, startY + dy));
                                            Class<?> wmLpCls = Class.forName("android.view.WindowManager$LayoutParams");
                                            wmLpCls.getField("x").setInt(currentWmLp, config.x);
                                            wmLpCls.getField("y").setInt(currentWmLp, config.y);
                                            Class<?> vgLpCls = Class.forName("android.view.ViewGroup$LayoutParams");
                                            Class<?> vmCls = Class.forName("android.view.ViewManager");
                                            vmCls.getMethod("updateViewLayout", Class.forName("android.view.View"), vgLpCls)
                                                    .invoke(windowManager, rootView, currentWmLp);
                                        }
                                    } else if (act == 1 || act == 3) {
                                        if (dragging) {
                                            dragging = false;
                                            saveMonitorConfig(configFile, config);
                                        }
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                            return Boolean.TRUE;
                        }
                    }
            );
            viewCls.getMethod("setOnTouchListener", touchListenerCls).invoke(view, proxy);
        }
    }

    // =========================================================================
    // 4B. DIAGNOSTIC LOG COLLECTOR & USB DUMP MANAGER (DebugManager)
    // =========================================================================

    public static final class DebugManager {
        private static final int MAX_EVENTS = 100;
        private static final int MAX_HSCAN_FRAMES = 40;
        private static final List<String> eventRing = new ArrayList<String>(MAX_EVENTS);
        private static final List<String> hscanRing = new ArrayList<String>(MAX_HSCAN_FRAMES);
        private static volatile String lastDumpPath = "";
        private static volatile long totalHscanFrames = 0L;

        private static String nowTimestamp() {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        }

        public static synchronized void logEvent(String tag, String msg) {
            String line = "[" + nowTimestamp() + "] [" + tag + "] " + msg;
            if (eventRing.size() >= MAX_EVENTS) {
                eventRing.remove(0);
            }
            eventRing.add(line);
            System.out.println("[TurzxMonitor] " + line);
        }

        public static synchronized void logError(String tag, String context, Throwable t) {
            String err = (t != null) ? (t.getClass().getSimpleName() + ": " + t.getMessage()) : "unknown";
            logEvent(tag + "-ERR", context + " -> " + err);
        }

        public static synchronized void recordHscanFrame(byte b1, byte b2, byte b3, byte[] data) {
            totalHscanFrames++;
            if (data == null) return;
            StringBuilder sb = new StringBuilder(96);
            sb.append("[").append(nowTimestamp()).append("] ");
            sb.append(String.format(Locale.US, "hdr=%02X,%02X,%02X len=%d [",
                    b1 & 0xFF, b2 & 0xFF, b3 & 0xFF, data.length));
            int limit = Math.min(data.length, 24);
            for (int i = 0; i < limit; i++) {
                if (i > 0) sb.append(' ');
                sb.append(String.format(Locale.US, "%02X", data[i] & 0xFF));
            }
            if (data.length > limit) sb.append(" ...");
            sb.append(']');
            if (hscanRing.size() >= MAX_HSCAN_FRAMES) {
                hscanRing.remove(0);
            }
            hscanRing.add(sb.toString());
        }

        public static String getLastDumpPath() {
            return lastDumpPath;
        }

        public static void triggerAsyncDump(final String installDir) {
            TurzxOverlayController.showBannerMessage("💾 진단 로그 수집 중...", 4000L);
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String path = dumpDiagnosticsToDisk(
                                installDir,
                                TelemetryCollector.getLatestSnapshot(),
                                TurzxOverlayController.getConfig()
                        );
                        TurzxOverlayController.showBannerMessage("💾 로그 저장 완료: " + path, 6500L);
                    } catch (Throwable err) {
                        logError("DEBUG", "triggerAsyncDump", err);
                        TurzxOverlayController.showBannerMessage("⚠️ 로그 저장 실패", 5000L);
                    }
                }
            }, "TurzxDebugDumper");
            t.setDaemon(true);
            t.start();
        }

        public static String buildDiagnosticReport(
                String installDir,
                TelemetrySnapshot snap,
                MonitorConfig cfg,
                String savedPathInfo
        ) {
            StringBuilder sb = new StringBuilder(8192);
            sb.append("================================================================================\n");
            sb.append("HKG 5W TURZX 9.2\" Cluster HUD Monitor — Diagnostic & Log Dump\n");
            sb.append("================================================================================\n");
            sb.append("Timestamp       : ").append(nowTimestamp()).append("\n");
            sb.append("Monitor Version : v").append(MONITOR_VERSION).append("\n");
            sb.append("Install Dir     : ").append(installDir).append("\n");
            if (savedPathInfo != null && !savedPathInfo.isEmpty()) {
                sb.append("Saved Dump Path : ").append(savedPathInfo).append("\n");
            }
            sb.append("OTA Status      : ").append(OtaUpdateManager.getStatusText())
                    .append(" (remote=").append(OtaUpdateManager.getRemoteVersion())
                    .append(", updating=").append(OtaUpdateManager.isUpdating()).append(")\n");

            File localVerFile = new File(installDir, "config/version.json");
            if (localVerFile.isFile()) {
                sb.append("Local version.json: ").append(readAllTextQuick(localVerFile).replace("\n", " ").trim()).append("\n");
            }

            if (cfg != null) {
                sb.append("\n--- [1] Monitor Configuration (config/monitor.conf) ---\n");
                sb.append("overlay_enabled=").append(cfg.overlayEnabled)
                        .append(", usb_push_enabled=").append(cfg.usbPushEnabled)
                        .append(", usb_brightness=").append(cfg.usbBrightness)
                        .append(", usb_rotation=").append(cfg.usbRotation)
                        .append(", usb_jpeg_quality=").append(cfg.usbJpegQuality)
                        .append(", usb_fps=").append(cfg.usbFps)
                        .append(", comma_ip=").append(cfg.commaIp)
                        .append(", auto_update=").append(cfg.autoUpdate)
                        .append(", pos=(").append(cfg.x).append(",").append(cfg.y).append(")\n");
            }

            sb.append("\n--- [2] TURZX 9.2\" USB Driver & /sys/bus/usb/devices Scan ---\n");
            sb.append("USB Connected   : ").append(TurzxUsbDriver.isConnected()).append("\n");
            sb.append("USB Status      : ").append(TurzxUsbDriver.getStatusText()).append("\n");
            sb.append("Pushed Frames   : ").append(TurzxUsbDriver.getPushedFrames())
                    .append(String.format(Locale.US, " (Live FPS: %.1f)\n", TurzxUsbDriver.getLiveFps()));
            File sysUsb = new File("/sys/bus/usb/devices");
            File[] usbDirs = sysUsb.listFiles();
            if (usbDirs != null) {
                for (int i = 0; i < usbDirs.length; i++) {
                    File d = usbDirs[i];
                    File fVid = new File(d, "idVendor");
                    File fPid = new File(d, "idProduct");
                    if (!fVid.isFile() || !fPid.isFile()) continue;
                    String vid = readFirstLineQuick(fVid);
                    String pid = readFirstLineQuick(fPid);
                    String prod = readFirstLineQuick(new File(d, "product"));
                    String mfg = readFirstLineQuick(new File(d, "manufacturer"));
                    int bus = parseIntSafe(readFirstLineQuick(new File(d, "busnum")), -1);
                    int dev = parseIntSafe(readFirstLineQuick(new File(d, "devnum")), -1);
                    String devNode = (bus > 0 && dev > 0)
                            ? String.format(Locale.US, "/dev/bus/usb/%03d/%03d", bus, dev)
                            : "n/a";
                    File nodeFile = new File(devNode);
                    sb.append(String.format(Locale.US, "  * %s -> VID:PID=%s:%s product='%s' mfg='%s' node=%s (exists=%b, r=%b, w=%b)\n",
                            d.getName(), vid, pid, prod, mfg, devNode,
                            nodeFile.exists(), nodeFile.canRead(), nodeFile.canWrite()));
                }
            }

            sb.append("\n--- [3] Live 4-Domain Telemetry Snapshot ---\n");
            sb.append(formatTelemetryJson(snap)).append("\n");
            sb.append("Total HSCAN Packets Received: ").append(totalHscanFrames).append("\n");

            synchronized (DebugManager.class) {
                sb.append("\n--- [4] Recent Raw HSCAN Frames (last ").append(hscanRing.size()).append(") ---\n");
                for (int i = 0; i < hscanRing.size(); i++) {
                    sb.append("  ").append(hscanRing.get(i)).append("\n");
                }
                sb.append("\n--- [5] Recent Daemon Events & Errors (last ").append(eventRing.size()).append(") ---\n");
                for (int i = 0; i < eventRing.size(); i++) {
                    sb.append("  ").append(eventRing.get(i)).append("\n");
                }
            }

            File daemonLog = new File("/data/local/tmp/hkg_turzxmonitor.log");
            if (daemonLog.isFile()) {
                sb.append("\n--- [6] Tail of /data/local/tmp/hkg_turzxmonitor.log ---\n");
                String logTxt = readAllTextQuick(daemonLog);
                String[] lines = logTxt.split("\n");
                int start = Math.max(0, lines.length - 80);
                for (int i = start; i < lines.length; i++) {
                    sb.append("  ").append(lines[i]).append("\n");
                }
            }
            return sb.toString();
        }

        public static String dumpDiagnosticsToDisk(
                String installDir,
                TelemetrySnapshot snap,
                MonitorConfig cfg
        ) {
            String dir = (installDir != null && !installDir.isEmpty())
                    ? installDir
                    : "/data/nemesis/apps/hkg.turzxmonitor";
            String report = buildDiagnosticReport(dir, snap, cfg, "");
            byte[] bytes = report.getBytes(UTF8);

            String primarySaved = "";
            String[] usbMounts = new String[]{
                    "/storage/usb0",
                    "/mnt/media_rw/usb0",
                    "/storage/usb1",
                    "/mnt/usb0"
            };
            File mountsFile = new File("/proc/mounts");
            String mountsTxt = mountsFile.isFile() ? readAllTextQuick(mountsFile) : "";

            for (int i = 0; i < usbMounts.length; i++) {
                String mnt = usbMounts[i];
                File mntDir = new File(mnt);
                if (mntDir.isDirectory() && (mountsTxt.contains(" " + mnt + " ") || mntDir.canWrite())) {
                    try {
                        File logDir = new File(mntDir, "HKG_TurzxMonitor_Log");
                        if (!logDir.exists()) logDir.mkdirs();
                        File usbDump = new File(logDir, "HKG_TurzxMonitor_Debug.txt");
                        writeBytesAtomic(usbDump, bytes);
                        File daemonLog = new File("/data/local/tmp/hkg_turzxmonitor.log");
                        if (daemonLog.isFile()) {
                            writeBytesAtomic(new File(logDir, "hkg_turzxmonitor.log"), readFileBytesQuick(daemonLog));
                        }
                        primarySaved = usbDump.getAbsolutePath();
                        break;
                    } catch (Throwable ignored) {
                    }
                }
            }

            try {
                File tmpDump = new File(DEBUG_DUMP_LOCAL_PATH);
                writeBytesAtomic(tmpDump, bytes);
                if (primarySaved.isEmpty()) {
                    primarySaved = tmpDump.getAbsolutePath();
                }
            } catch (Throwable ignored) {
            }

            try {
                File pkgLogDump = new File(dir, "logs/debug_dump.txt");
                writeBytesAtomic(pkgLogDump, bytes);
                if (primarySaved.isEmpty()) {
                    primarySaved = pkgLogDump.getAbsolutePath();
                }
            } catch (Throwable ignored) {
            }

            lastDumpPath = primarySaved;
            logEvent("DEBUG", "Diagnostic dump written to " + primarySaved);
            return primarySaved;
        }
    }

    // =========================================================================
    // 4C. GITHUB OTA AUTO-UPDATE MANAGER (OtaUpdateManager)
    // =========================================================================

    public static final class OtaUpdateManager {
        private static volatile boolean otaUpdating = false;
        private static volatile String otaStatusText = "대기 중";
        private static volatile String remoteVersion = MONITOR_VERSION;
        private static volatile long lastCheckMs = 0L;
        private static volatile boolean workerStarted = false;

        public static boolean isUpdating() {
            return otaUpdating;
        }

        public static String getStatusText() {
            return otaStatusText;
        }

        public static String getRemoteVersion() {
            return remoteVersion;
        }

        public static void startAutoUpdateWorker(final String installDir) {
            if (workerStarted) return;
            workerStarted = true;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(45000L);
                    } catch (InterruptedException ignored) {
                    }
                    while (true) {
                        try {
                            MonitorConfig cfg = TurzxOverlayController.getConfig();
                            if (cfg == null || cfg.autoUpdate) {
                                checkAndApplyGitHubOta(installDir, true, false, true);
                            }
                        } catch (Throwable tErr) {
                            DebugManager.logError("OTA", "autoWorker", tErr);
                        }
                        try {
                            Thread.sleep(3L * 3600L * 1000L);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }, "TurzxOtaAutoWorker");
            t.setDaemon(true);
            t.start();
        }

        public static void triggerManualOta(final String installDir, final boolean forceApply) {
            if (otaUpdating) {
                TurzxOverlayController.showBannerMessage("☁️ 이미 업데이트 확인 중입니다", 3500L);
                return;
            }
            TurzxOverlayController.showBannerMessage("☁️ GitHub 최신 버전 확인 중...", 5000L);
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    checkAndApplyGitHubOta(installDir, true, forceApply, false);
                }
            }, "TurzxOtaManualWorker");
            t.setDaemon(true);
            t.start();
        }

        public static synchronized boolean checkAndApplyGitHubOta(
                String dir,
                boolean applyIfNew,
                boolean forceApply,
                boolean silentIfUpToDate
        ) {
            if (otaUpdating) return false;
            otaUpdating = true;
            String installDir = (dir != null && !dir.isEmpty()) ? dir : "/data/nemesis/apps/hkg.turzxmonitor";
            try {
                lastCheckMs = System.currentTimeMillis();
                otaStatusText = "GitHub 연결 중...";
                ensureGitHubRoutingRule();

                byte[] verBytes = downloadGitHubOtaFile(GITHUB_OTA_BASE_PATH + "/version.json", 32768);
                if (verBytes == null || verBytes.length < 10) {
                    otaStatusText = "서버 응답 없음 (네트워크 확인)";
                    if (!silentIfUpToDate) {
                        TurzxOverlayController.showBannerMessage("⚠️ GitHub 연결 실패", 5000L);
                    }
                    return false;
                }

                String verJson = new String(verBytes, UTF8);
                Map<String, String> remoteMeta = parseJsonFlatMap(verJson);
                String rVer = remoteMeta.get("version");
                String rJarSha = remoteMeta.get("sha256_jar");
                String rDaemonSha = remoteMeta.get("sha256_daemon");
                String rLaunchSha = remoteMeta.get("sha256_launch");

                if (rVer == null || rVer.isEmpty() || rJarSha == null || rJarSha.length() != 64) {
                    otaStatusText = "버전 메타데이터 오류";
                    return false;
                }
                remoteVersion = rVer;

                File jarFile = new File(installDir, "bin/turzxmonitor.jar");
                File daemonFile = new File(installDir, "svc/turzx-daemon.sh");
                File launchFile = new File(installDir, "bin/launch.sh");
                File localVerFile = new File(installDir, "config/version.json");

                String localJarSha = jarFile.isFile() ? sha256File(jarFile) : "";
                String localDaemonSha = daemonFile.isFile() ? sha256File(daemonFile) : "";
                String localLaunchSha = launchFile.isFile() ? sha256File(launchFile) : "";

                boolean jarChanged = !rJarSha.equalsIgnoreCase(localJarSha);
                boolean daemonChanged = (rDaemonSha != null && rDaemonSha.length() == 64 && !rDaemonSha.equalsIgnoreCase(localDaemonSha));
                boolean launchChanged = (rLaunchSha != null && rLaunchSha.length() == 64 && !rLaunchSha.equalsIgnoreCase(localLaunchSha));

                if (!forceApply && !jarChanged && !daemonChanged && !launchChanged) {
                    otaStatusText = "최신 버전 (v" + rVer + ")";
                    if (!silentIfUpToDate) {
                        TurzxOverlayController.showBannerMessage("✅ 최신 버전입니다 (v" + rVer + ")", 5000L);
                    }
                    return false;
                }

                if (!applyIfNew) {
                    otaStatusText = "업데이트 가능 (v" + rVer + ")";
                    return true;
                }

                DebugManager.logEvent("OTA", "Applying GitHub OTA update -> v" + rVer
                        + " (jar=" + jarChanged + ", daemon=" + daemonChanged + ", launch=" + launchChanged + ")");
                otaStatusText = "v" + rVer + " 다운로드 중...";
                TurzxOverlayController.showBannerMessage("☁️ v" + rVer + " 업데이트 다운로드 중...", 10000L);

                if (forceApply || jarChanged) {
                    byte[] b64Bytes = downloadGitHubOtaFile(GITHUB_OTA_BASE_PATH + "/turzxmonitor.b64", 4 * 1024 * 1024);
                    if (b64Bytes == null || b64Bytes.length < 100) {
                        otaStatusText = "JAR 다운로드 실패";
                        TurzxOverlayController.showBannerMessage("⚠️ 업데이트 다운로드 실패", 5000L);
                        return false;
                    }
                    byte[] newJarBytes = decodeBase64(new String(b64Bytes, UTF8));
                    String dlSha = sha256Hex(newJarBytes);
                    if (!rJarSha.equalsIgnoreCase(dlSha)) {
                        DebugManager.logEvent("OTA-ERR", "JAR SHA-256 mismatch: expected=" + rJarSha + " actual=" + dlSha);
                        otaStatusText = "무결성 검증 실패";
                        TurzxOverlayController.showBannerMessage("⚠️ SHA-256 무결성 검증 실패", 5000L);
                        return false;
                    }
                    writeBytesAtomic(jarFile, newJarBytes);
                    jarFile.setReadable(true, false);
                }

                if (forceApply || daemonChanged) {
                    byte[] daemonBytes = downloadGitHubOtaFile(GITHUB_OTA_BASE_PATH + "/turzx-daemon.sh", 262144);
                    if (daemonBytes != null && daemonBytes.length > 50) {
                        String dlDaemonSha = sha256Hex(daemonBytes);
                        if (rDaemonSha == null || rDaemonSha.isEmpty() || rDaemonSha.equalsIgnoreCase(dlDaemonSha)) {
                            writeBytesAtomic(daemonFile, daemonBytes);
                            daemonFile.setExecutable(true, false);
                            daemonFile.setReadable(true, false);
                        }
                    }
                }

                if (forceApply || launchChanged) {
                    byte[] launchBytes = downloadGitHubOtaFile(GITHUB_OTA_BASE_PATH + "/launch.sh", 262144);
                    if (launchBytes != null && launchBytes.length > 50) {
                        String dlLaunchSha = sha256Hex(launchBytes);
                        if (rLaunchSha == null || rLaunchSha.isEmpty() || rLaunchSha.equalsIgnoreCase(dlLaunchSha)) {
                            writeBytesAtomic(launchFile, launchBytes);
                            launchFile.setExecutable(true, false);
                            launchFile.setReadable(true, false);
                        }
                    }
                }

                writeBytesAtomic(localVerFile, verBytes);
                otaStatusText = "v" + rVer + " 적용 완료 · 재시작 중";
                TurzxOverlayController.showBannerMessage("✅ v" + rVer + " 업데이트 완료! 재시작합니다...", 5000L);
                DebugManager.logEvent("OTA", "Successfully updated to v" + rVer + "; triggering hot restart");

                if (daemonChanged) {
                    try {
                        writeBytesAtomic(new File(OTA_RELOAD_TRIGGER_PATH), ("v" + rVer + "\n").getBytes(UTF8));
                    } catch (Throwable ignored) {
                    }
                }

                Thread restartThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Thread.sleep(1200L);
                        } catch (InterruptedException ignored) {
                        }
                        System.exit(0);
                    }
                }, "TurzxOtaRestart");
                restartThread.setDaemon(true);
                restartThread.start();
                return true;
            } catch (Throwable t) {
                DebugManager.logError("OTA", "checkAndApplyGitHubOta", t);
                otaStatusText = "업데이트 오류";
                return false;
            } finally {
                otaUpdating = false;
            }
        }

        private static void ensureGitHubRoutingRule() {
            try {
                String[] cmds = new String[]{
                        "/system/bin/sh",
                        "-c",
                        "for dev in rmnet0 rmnet1 usb0 wlan0; do "
                                + "if ip addr show $dev 2>/dev/null | grep -q 'inet '; then "
                                + "ip rule add pref 9852 to 185.199.108.0/22 lookup $dev 2>/dev/null; "
                                + "ip rule add pref 9853 to 8.8.8.8/32 lookup $dev 2>/dev/null; "
                                + "ip rule add pref 9854 to 1.1.1.1/32 lookup $dev 2>/dev/null; "
                                + "break; fi; done"
                };
                Process p = Runtime.getRuntime().exec(cmds);
                p.waitFor();
            } catch (Throwable ignored) {
            }
        }

        private static List<String> resolveHostViaPublicDns(String host) {
            List<String> ips = new ArrayList<String>();
            try {
                InetAddress[] sysAddrs = InetAddress.getAllByName(host);
                if (sysAddrs != null) {
                    for (int i = 0; i < sysAddrs.length; i++) {
                        String ip = sysAddrs[i].getHostAddress();
                        if (ip != null && !ip.isEmpty() && !ips.contains(ip)) {
                            ips.add(ip);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            String[] dnsServers = new String[]{"8.8.8.8", "1.1.1.1"};
            for (int s = 0; s < dnsServers.length && ips.isEmpty(); s++) {
                DatagramSocket ds = null;
                try {
                    ds = new DatagramSocket();
                    ds.setSoTimeout(2000);
                    ByteArrayOutputStream q = new ByteArrayOutputStream(64);
                    q.write(new byte[]{0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
                    String[] labels = host.split("\\.");
                    for (int i = 0; i < labels.length; i++) {
                        byte[] lb = labels[i].getBytes(UTF8);
                        q.write(lb.length);
                        q.write(lb);
                    }
                    q.write(0x00);
                    q.write(new byte[]{0x00, 0x01, 0x00, 0x01});
                    byte[] req = q.toByteArray();
                    ds.send(new DatagramPacket(req, req.length, InetAddress.getByName(dnsServers[s]), 53));
                    byte[] resp = new byte[512];
                    DatagramPacket dp = new DatagramPacket(resp, resp.length);
                    ds.receive(dp);
                    int len = dp.getLength();
                    if (len > 12) {
                        int anCount = ((resp[6] & 0xFF) << 8) | (resp[7] & 0xFF);
                        int idx = 12;
                        while (idx < len && resp[idx] != 0) {
                            idx += (resp[idx] & 0xFF) + 1;
                        }
                        idx += 5;
                        for (int a = 0; a < anCount && idx + 12 <= len; a++) {
                            if ((resp[idx] & 0xC0) == 0xC0) idx += 2;
                            else {
                                while (idx < len && resp[idx] != 0) idx += (resp[idx] & 0xFF) + 1;
                                idx++;
                            }
                            if (idx + 10 > len) break;
                            int rType = ((resp[idx] & 0xFF) << 8) | (resp[idx + 1] & 0xFF);
                            int rdLen = ((resp[idx + 8] & 0xFF) << 8) | (resp[idx + 9] & 0xFF);
                            idx += 10;
                            if (rType == 1 && rdLen == 4 && idx + 4 <= len) {
                                String ip = (resp[idx] & 0xFF) + "." + (resp[idx + 1] & 0xFF) + "."
                                        + (resp[idx + 2] & 0xFF) + "." + (resp[idx + 3] & 0xFF);
                                if (!ips.contains(ip)) ips.add(ip);
                            }
                            idx += rdLen;
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (ds != null) { try { ds.close(); } catch (Throwable ignored) {} }
                }
            }

            // Fastly Anycast fallback IPs for raw.githubusercontent.com
            String[] fallbacks = new String[]{
                    "185.199.108.133",
                    "185.199.109.133",
                    "185.199.110.133",
                    "185.199.111.133"
            };
            for (int i = 0; i < fallbacks.length; i++) {
                if (!ips.contains(fallbacks[i])) {
                    ips.add(fallbacks[i]);
                }
            }
            return ips;
        }

        private static byte[] downloadGitHubOtaFile(String path, int maxBytes) {
            List<String> ips = resolveHostViaPublicDns(GITHUB_RAW_HOST);
            for (int i = 0; i < ips.size(); i++) {
                String ip = ips.get(i);
                SSLSocket sslSock = null;
                try {
                    TrustManager[] trustAll = new TrustManager[]{
                            new X509TrustManager() {
                                @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
                                @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
                                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                            }
                    };
                    SSLContext sc = SSLContext.getInstance("TLSv1.2");
                    sc.init(null, trustAll, new SecureRandom());

                    Socket rawSock = new Socket();
                    rawSock.connect(new InetSocketAddress(ip, 443), 5000);
                    rawSock.setSoTimeout(10000);

                    sslSock = (SSLSocket) sc.getSocketFactory().createSocket(rawSock, GITHUB_RAW_HOST, 443, true);
                    sslSock.setEnabledProtocols(new String[]{"TLSv1.2"});
                    try {
                        sslSock.getClass().getMethod("setHostname", String.class).invoke(sslSock, GITHUB_RAW_HOST);
                    } catch (Throwable ignored) {
                    }
                    sslSock.startHandshake();

                    OutputStream os = sslSock.getOutputStream();
                    String req = "GET " + path + "?t=" + System.currentTimeMillis() + " HTTP/1.1\r\n"
                            + "Host: " + GITHUB_RAW_HOST + "\r\n"
                            + "User-Agent: HKG-TurzxMonitor/" + MONITOR_VERSION + "\r\n"
                            + "Cache-Control: no-cache\r\n"
                            + "Connection: close\r\n\r\n";
                    os.write(req.getBytes(UTF8));
                    os.flush();

                    InputStream is = sslSock.getInputStream();
                    ByteArrayOutputStream rawResp = new ByteArrayOutputStream(8192);
                    byte[] buf = new byte[4096];
                    int r;
                    while ((r = is.read(buf)) > 0) {
                        rawResp.write(buf, 0, r);
                        if (rawResp.size() > maxBytes + 8192) break;
                    }
                    byte[] all = rawResp.toByteArray();
                    int hdrEnd = -1;
                    for (int k = 0; k + 3 < all.length; k++) {
                        if (all[k] == '\r' && all[k + 1] == '\n' && all[k + 2] == '\r' && all[k + 3] == '\n') {
                            hdrEnd = k + 4;
                            break;
                        }
                    }
                    if (hdrEnd <= 0) continue;
                    String hdrStr = new String(all, 0, hdrEnd, UTF8);
                    if (!hdrStr.startsWith("HTTP/1.1 200") && !hdrStr.startsWith("HTTP/1.0 200")) {
                        continue;
                    }
                    byte[] body = new byte[all.length - hdrEnd];
                    System.arraycopy(all, hdrEnd, body, 0, body.length);
                    return body;
                } catch (Throwable ignored) {
                } finally {
                    if (sslSock != null) { try { sslSock.close(); } catch (Throwable ignored) {} }
                }
            }
            return null;
        }
    }

    // =========================================================================
    // 5. STATIC PARSERS, DECODERS, CONFIG & HOST SELF-TESTS
    // =========================================================================

    /**
     * Decodes a Comma 3X (ajouatom/openpilot carrot-wip) CVS1 or CVB1 batch binary WebSocket payload:
     * - CVB1 batch: magic "CVB1" (4B) + uint16 LE count (2B) + count * [uint32 LE frameLen + CVS1 frame]
     * - CVS1 frame: magic "CVS1" (4B) + uint8 service_id (1B) + uint8 flags (1B) + uint16 LE seq (2B) + payload:
     *   * service_id == 1 (carState, 73B): vEgo(f32@8), aEgo(f32@12), vEgoCluster(f32@16), vCruiseCluster(f32@20),
     *     steeringAngleDeg(f32@24), gearShifter(u8@46: 1=P,2=D,3=N,4=R), tpms FL/FR/RL/RR(f32@57..72)
     *   * service_id == 2 (controlsState, 26B): enabled(bool@8), vCruiseCluster(f32@9), curvature(f32@14)
     *   * service_id == 5 (carrotMan): activeCarrot(i32@8), nRoadLimitSpeed(i32@12), xSpdType(i32@16),
     *     xSpdLimit(i32@20), xSpdDist(i32@24), xDistToTurn(i32@36), atcType/szPosRoadName/szTBTMainText(text@44+)
     *   * service_id == 13 (radarState, 50B+): leadOne.dRel(f32@8), yRel(f32@12), vRel(f32@16), status(bool@49)
     */
    public static boolean decodeCvs1BinaryPayload(byte[] buf, TelemetrySnapshot target) {
        if (buf == null || target == null || buf.length < 12) {
            return false;
        }
        if (buf[0] == (byte) 'C' && buf[1] == (byte) 'V' && buf[2] == (byte) 'B' && buf[3] == (byte) '1') {
            int count = (buf[4] & 0xFF) | ((buf[5] & 0xFF) << 8);
            int off = 6;
            boolean anyOk = false;
            for (int i = 0; i < count && off + 4 <= buf.length; i++) {
                int fLen = (buf[off] & 0xFF)
                        | ((buf[off + 1] & 0xFF) << 8)
                        | ((buf[off + 2] & 0xFF) << 16)
                        | ((buf[off + 3] & 0xFF) << 24);
                off += 4;
                if (fLen <= 0 || off + fLen > buf.length) {
                    break;
                }
                if (decodeSingleCvs1Frame(buf, off, fLen, target)) {
                    anyOk = true;
                }
                off += fLen;
            }
            return anyOk;
        }
        return decodeSingleCvs1Frame(buf, 0, buf.length, target);
    }

    private static boolean decodeSingleCvs1Frame(byte[] buf, int base, int len, TelemetrySnapshot target) {
        if (len < 12 || base < 0 || base + len > buf.length) {
            return false;
        }
        if (buf[base] != (byte) 'C' || buf[base + 1] != (byte) 'V' || buf[base + 2] != (byte) 'S' || buf[base + 3] != (byte) '1') {
            return false;
        }
        int serviceId = buf[base + 4] & 0xFF;
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);

        // Legacy synthetic 128-byte test frame (real carState is 73 bytes)
        if (serviceId == 0 || (serviceId == 1 && len == 128)) {
            float vEgoMs = bb.getFloat(base + 16);
            float vCruiseKph = bb.getFloat(base + 20);
            float leadDist = bb.getFloat(base + 24);
            float leadRelVel = bb.getFloat(base + 28);
            float flPsi = bb.getFloat(base + 32);
            float frPsi = bb.getFloat(base + 36);
            float rlPsi = bb.getFloat(base + 40);
            float rrPsi = bb.getFloat(base + 44);

            if (!Float.isNaN(vEgoMs) && vEgoMs >= 0.0f && vEgoMs <= 80.0f) {
                target.carrotSpeedKph = Math.round(vEgoMs * 3.6f);
            }
            if (!Float.isNaN(vCruiseKph) && vCruiseKph >= 0.0f && vCruiseKph <= 250.0f) {
                target.carrotCruiseKph = Math.round(vCruiseKph);
            }
            if (!Float.isNaN(leadDist) && leadDist > 0.5f && leadDist < 250.0f) {
                target.leadDistM = leadDist;
                target.leadRelVelKph = leadRelVel * 3.6f;
                target.hasLeadCar = true;
            } else {
                target.hasLeadCar = false;
            }
            if (flPsi >= 15.0f && flPsi <= 60.0f) target.tpmsFlPsi = flPsi;
            if (frPsi >= 15.0f && frPsi <= 60.0f) target.tpmsFrPsi = frPsi;
            if (rlPsi >= 15.0f && rlPsi <= 60.0f) target.tpmsRlPsi = rlPsi;
            if (rrPsi >= 15.0f && rrPsi <= 60.0f) target.tpmsRrPsi = rrPsi;

            if (target.pathPoly == null || target.pathPoly.length < 4) {
                target.pathPoly = new float[4];
            }
            for (int i = 0; i < 4; i++) {
                float c = bb.getFloat(base + 48 + i * 4);
                if (!Float.isNaN(c) && Math.abs(c) < 10.0f) {
                    target.pathPoly[i] = c;
                }
            }
            target.carrotConnected = true;
            target.carrotActive = (target.carrotCruiseKph > 0);
            return true;
        }

        switch (serviceId) {
            case 1: { // carState (73 bytes)
                if (len < 73) return false;
                float vEgoMs = bb.getFloat(base + 8);
                float vEgoClusterMs = bb.getFloat(base + 16);
                float vCruiseKph = bb.getFloat(base + 20);
                float steerDeg = bb.getFloat(base + 24);
                int carrotCruise = bb.getShort(base + 31);
                int gearShifter = buf[base + 46] & 0xFF;
                float flPsi = bb.getFloat(base + 57);
                float frPsi = bb.getFloat(base + 61);
                float rlPsi = bb.getFloat(base + 65);
                float rrPsi = bb.getFloat(base + 69);

                float refMs = (!Float.isNaN(vEgoClusterMs) && vEgoClusterMs > 0.1f) ? vEgoClusterMs : vEgoMs;
                if (!Float.isNaN(refMs) && refMs >= 0.0f && refMs <= 90.0f) {
                    target.carrotSpeedKph = Math.round(refMs * 3.6f);
                }
                if (!Float.isNaN(vCruiseKph) && vCruiseKph >= 0.0f && vCruiseKph <= 250.0f) {
                    target.carrotCruiseKph = Math.round(vCruiseKph);
                }
                if (gearShifter == 1) target.gearPos = "P";
                else if (gearShifter == 2) target.gearPos = "D";
                else if (gearShifter == 3) target.gearPos = "N";
                else if (gearShifter == 4) target.gearPos = "R";

                if (!Float.isNaN(flPsi) && flPsi >= 15.0f && flPsi <= 65.0f) target.tpmsFlPsi = flPsi;
                if (!Float.isNaN(frPsi) && frPsi >= 15.0f && frPsi <= 65.0f) target.tpmsFrPsi = frPsi;
                if (!Float.isNaN(rlPsi) && rlPsi >= 15.0f && rlPsi <= 65.0f) target.tpmsRlPsi = rlPsi;
                if (!Float.isNaN(rrPsi) && rrPsi >= 15.0f && rrPsi <= 65.0f) target.tpmsRrPsi = rrPsi;

                if (!Float.isNaN(steerDeg) && Math.abs(steerDeg) < 600.0f) {
                    if (target.pathPoly == null || target.pathPoly.length < 4) {
                        target.pathPoly = new float[4];
                    }
                    target.pathPoly[2] = steerDeg * 0.000015f;
                }
                target.carrotConnected = true;
                target.carrotActive = (carrotCruise > 0) || (target.carrotCruiseKph > 0);
                return true;
            }
            case 2: { // controlsState (26 bytes)
                if (len < 18) return false;
                boolean enabled = (buf[base + 8] & 0xFF) != 0;
                float vCruise = bb.getFloat(base + 9);
                float curvature = bb.getFloat(base + 14);
                target.carrotActive = enabled;
                if (!Float.isNaN(vCruise) && vCruise > 0.0f && vCruise <= 250.0f) {
                    target.carrotCruiseKph = Math.round(vCruise);
                }
                if (!Float.isNaN(curvature) && Math.abs(curvature) < 0.5f) {
                    if (target.pathPoly == null || target.pathPoly.length < 4) {
                        target.pathPoly = new float[4];
                    }
                    target.pathPoly[2] = curvature;
                }
                target.carrotConnected = true;
                return true;
            }
            case 5: { // carrotMan (SDI speed camera + TBT navigation)
                if (len < 44) return false;
                int activeCarrot = bb.getInt(base + 8);
                int roadLimitSpeed = bb.getInt(base + 12);
                int xSpdType = bb.getInt(base + 16);
                int xSpdLimit = bb.getInt(base + 20);
                int xSpdDist = bb.getInt(base + 24);
                int xDistToTurn = bb.getInt(base + 36);

                if (activeCarrot > 0) target.carrotActive = true;
                target.carrotSdiType = xSpdType;
                target.carrotSdiSpeedLimit = (xSpdLimit > 0) ? xSpdLimit : Math.max(0, roadLimitSpeed);
                target.carrotSdiDistM = Math.max(0, xSpdDist);
                if (xDistToTurn >= 0) target.carrotTbtDistM = xDistToTurn;

                int p = base + 44;
                int end = base + len;
                // [0] atcType, [1] szPosRoadName, [2] szTBTMainText
                for (int idx = 0; idx < 3 && p + 2 <= end; idx++) {
                    int sLen = bb.getShort(p) & 0xFFFF;
                    p += 2;
                    if (p + sLen > end) break;
                    if (idx >= 1 && sLen > 0) {
                        String txt = new String(buf, p, sLen, UTF8).trim();
                        if (!txt.isEmpty()) {
                            target.carrotTbtRoad = txt;
                        }
                    }
                    p += sLen;
                }
                target.carrotConnected = true;
                return true;
            }
            case 13: { // radarState (leadOne starts at offset 8)
                if (len < 50) return false;
                float dRel = bb.getFloat(base + 8);
                float vRel = bb.getFloat(base + 16);
                boolean status = (buf[base + 49] & 0xFF) != 0;
                if (status && !Float.isNaN(dRel) && dRel > 0.5f && dRel < 250.0f) {
                    target.leadDistM = dRel;
                    target.leadRelVelKph = vRel * 3.6f;
                    target.hasLeadCar = true;
                } else {
                    target.hasLeadCar = false;
                }
                target.carrotConnected = true;
                return true;
            }
            default:
                target.carrotConnected = true;
                return true;
        }
    }

    public static boolean parseBmsUdsPayload(byte[] payload, TelemetrySnapshot target) {
        if (payload == null || target == null || payload.length < 35) return false;
        int udsIdx = -1;
        int maxScan = Math.min(12, payload.length - 35);
        for (int i = 0; i <= maxScan; i++) {
            if ((payload[i] & 0xFF) == 0x62 && (payload[i + 1] & 0xFF) == 0x01) {
                int sub = payload[i + 2] & 0xFF;
                if (sub == 0x01 || sub == 0x05) {
                    udsIdx = i;
                    break;
                }
            }
        }
        if (udsIdx < 0) return false;
        int subPid = payload[udsIdx + 2] & 0xFF;
        int rem = payload.length - udsIdx;

        if (subPid == 0x01 && rem >= 49) {
            int bmsSocRaw = payload[udsIdx + 7] & 0xFF;
            if (bmsSocRaw > 0 && bmsSocRaw <= 210) target.bmsSocPct = bmsSocRaw * 0.5;

            short currRaw = (short) (((payload[udsIdx + 13] & 0xFF) << 8) | (payload[udsIdx + 14] & 0xFF));
            target.packCurrA = currRaw * 0.1;
            target.hasPackCurrent = true;

            int voltRaw = ((payload[udsIdx + 15] & 0xFF) << 8) | (payload[udsIdx + 16] & 0xFF);
            if (voltRaw >= 1500 && voltRaw <= 9500) {
                target.packVoltV = voltRaw * 0.1;
                target.packPowerKw = (target.packVoltV * target.packCurrA) / 1000.0;
            }

            int maxT = (int) payload[udsIdx + 17];
            int minT = (int) payload[udsIdx + 18];
            int inletT = (int) payload[udsIdx + 25];
            if (maxT >= -45 && maxT <= 95) target.battMaxTempC = Integer.valueOf(maxT);
            if (minT >= -45 && minT <= 95) target.battMinTempC = Integer.valueOf(minT);
            if (inletT >= -45 && inletT <= 95) target.battInletTempC = Integer.valueOf(inletT);

            int maxCellRaw = payload[udsIdx + 26] & 0xFF;
            int maxCellIdx = payload[udsIdx + 27] & 0xFF;
            int minCellRaw = payload[udsIdx + 28] & 0xFF;
            int minCellIdx = payload[udsIdx + 29] & 0xFF;
            if (maxCellRaw >= 120 && maxCellRaw <= 230 && minCellRaw >= 120 && minCellRaw <= 230) {
                target.cellMaxVoltV = maxCellRaw * 0.02;
                target.cellMaxNo = maxCellIdx;
                target.cellMinVoltV = minCellRaw * 0.02;
                target.cellMinNo = minCellIdx;
                target.cellDiffMv = Math.max(0, (maxCellRaw - minCellRaw) * 20);
            }

            int auxRaw = payload[udsIdx + 32] & 0xFF;
            if (auxRaw >= 60 && auxRaw <= 180) target.auxBattVoltV = auxRaw * 0.1;

            long chgWh100 = ((payload[udsIdx + 41] & 0xFFL) << 24)
                    | ((payload[udsIdx + 42] & 0xFFL) << 16)
                    | ((payload[udsIdx + 43] & 0xFFL) << 8)
                    | (payload[udsIdx + 44] & 0xFFL);
            long disWh100 = ((payload[udsIdx + 45] & 0xFFL) << 24)
                    | ((payload[udsIdx + 46] & 0xFFL) << 16)
                    | ((payload[udsIdx + 47] & 0xFFL) << 8)
                    | (payload[udsIdx + 48] & 0xFFL);
            if (chgWh100 > 0L && chgWh100 < 10000000L) target.cumulChargeKwh = chgWh100 * 0.1;
            if (disWh100 > 0L && disWh100 < 10000000L) target.cumulDischargeKwh = disWh100 * 0.1;
            return true;
        } else if (subPid == 0x05 && rem >= 35) {
            int heaterT = (int) payload[udsIdx + 26];
            if (heaterT >= -45 && heaterT <= 95) target.battHeaterTempC = Integer.valueOf(heaterT);

            int sohRaw = ((payload[udsIdx + 28] & 0xFF) << 8) | (payload[udsIdx + 29] & 0xFF);
            if (sohRaw >= 100 && sohRaw <= 1200) target.sohPct = sohRaw * 0.1;

            int dispSocRaw = payload[udsIdx + 34] & 0xFF;
            if (dispSocRaw <= 0 || dispSocRaw > 210) dispSocRaw = payload[udsIdx + 27] & 0xFF;
            if (dispSocRaw > 0 && dispSocRaw <= 210) target.displaySocPct = dispSocRaw * 0.5;
            return true;
        }
        return false;
    }

    public static boolean parseHscanBypassCanFrame(
            int fnCode,
            byte[] d,
            TelemetrySnapshot target,
            long nowMs,
            long lastUds0101Ms,
            long lastUds0105Ms
    ) {
        if (d == null || d.length < 8 || target == null) return false;
        boolean uds0101Stale = (nowMs - lastUds0101Ms) > 4500L;
        boolean uds0105Stale = (nowMs - lastUds0105Ms) > 4500L;

        switch (fnCode) {
            case 0x01:
            case 0x2A: {
                int minCellRaw = d[1] & 0xFF;
                int maxCellRaw = d[4] & 0xFF;
                if (maxCellRaw >= 120 && maxCellRaw <= 230 && minCellRaw >= 120 && minCellRaw <= 230
                        && maxCellRaw >= minCellRaw && (uds0101Stale || target.cellMaxVoltV <= 0.0)) {
                    target.cellMaxVoltV = maxCellRaw * 0.02;
                    target.cellMinVoltV = minCellRaw * 0.02;
                    target.cellDiffMv = Math.max(0, (maxCellRaw - minCellRaw) * 20);
                }
                short curRaw = (short) (((d[3] & 0xFF) << 8) | (d[2] & 0xFF));
                if (curRaw != (short) 0x7FFF && curRaw != (short) 0x7FFE && curRaw != (short) 0xFFFF
                        && curRaw >= -6000 && curRaw <= 6000) {
                    target.packCurrA = curRaw * 0.1;
                    target.hasPackCurrent = true;
                    if (target.packVoltV >= 150.0) {
                        target.packPowerKw = (target.packVoltV * target.packCurrA) / 1000.0;
                    }
                }
                int minT = (int) d[5];
                int maxT = (int) d[6];
                if (minT >= -40 && minT <= 85 && minT != 0 && (uds0101Stale || target.battMinTempC == null)) {
                    target.battMinTempC = Integer.valueOf(minT);
                }
                if (maxT >= -40 && maxT <= 85 && maxT != 0 && (uds0101Stale || target.battMaxTempC == null)) {
                    target.battMaxTempC = Integer.valueOf(maxT);
                }
                return true;
            }
            case 0x03:
            case 0x2C: {
                int bmsSocRaw = d[3] & 0xFF;
                if (bmsSocRaw > 0 && bmsSocRaw <= 200 && (uds0101Stale || target.bmsSocPct < 0.0)) {
                    target.bmsSocPct = bmsSocRaw * 0.5;
                }
                int dispSocRaw = d[7] & 0xFF;
                if (dispSocRaw > 0 && dispSocRaw <= 200 && (uds0105Stale || target.displaySocPct < 0.0)) {
                    target.displaySocPct = dispSocRaw * 0.5;
                }
                return true;
            }
            case 0x10: {
                double outC = parseCanTempByteToCelsius(d[5] & 0xFF);
                if (outC > -50.0) {
                    target.outdoorTempC = outC;
                    return true;
                }
                return false;
            }
            case 0x11: {
                double inC = parseCanTempByteToCelsius(d[7] & 0xFF);
                if (inC > -50.0) {
                    target.inCarTempC = inC;
                    return true;
                }
                return false;
            }
            case 0x17:
            case 0x31: {
                int invVoltRaw = d[2] & 0xFF;
                if (invVoltRaw >= 60 && invVoltRaw <= 250 && (uds0101Stale || target.packVoltV <= 0.0)) {
                    target.packVoltV = invVoltRaw * 3.0;
                    if (target.hasPackCurrent) {
                        target.packPowerKw = (target.packVoltV * target.packCurrA) / 1000.0;
                    }
                    return true;
                }
                return false;
            }
            case 0x18:
            case 0x36: {
                int rawByte = d[7] & 0xFF;
                int motKw = (int) d[7];
                if (rawByte != 0xFE && rawByte != 0xFF && motKw >= -100 && motKw <= 180) {
                    target.drivePowerKw = Math.max(0, motKw);
                    target.hasSubPower = true;
                    return true;
                }
                return false;
            }
            case 0x21:
            case 0x3B: {
                int gearRaw = d[1] & 0x0F;
                if (gearRaw == GEAR_STATUS_R) target.gearPos = "R";
                else if (gearRaw == GEAR_STATUS_N) target.gearPos = "N";
                else if (gearRaw == GEAR_STATUS_D || (gearRaw >= 1 && gearRaw <= 4) || gearRaw == 8) target.gearPos = "D";
                else if (gearRaw == GEAR_STATUS_P && Math.abs(target.drivePowerKw) < 2.0) target.gearPos = "P";
                int pedRaw = d[4] & 0xFF;
                if (pedRaw <= 250) target.accelPedalPct = (pedRaw * 100.0) / 255.0;
                return true;
            }
            case 0x26:
            case 0x3E: {
                int effRaw = ((d[6] & 0x0F) << 4) | ((d[5] & 0xF0) >>> 4);
                if (effRaw > 5 && effRaw < 250) {
                    target.avgEconomyKmKwh = effRaw * 0.1;
                    return true;
                }
                return false;
            }
            default:
                return false;
        }
    }

    public static boolean parseTmHscanLogLine(String line, TelemetrySnapshot targetIfDirect) {
        if (line == null || !line.contains("onEventFromMicom")) return false;
        int openIdx = line.indexOf('[');
        int closeIdx = line.lastIndexOf(']');
        if (openIdx < 0 || closeIdx <= openIdx + 6) return false;
        String[] parts = line.substring(openIdx + 1, closeIdx).split(",");
        if (parts.length < 4) return false;
        byte[] all = new byte[parts.length];
        int count = 0;
        for (int i = 0; i < parts.length; i++) {
            String tok = parts[i].trim();
            if (tok.isEmpty()) continue;
            try {
                all[count++] = (byte) Integer.parseInt(tok, 16);
            } catch (NumberFormatException nfe) {
                return false;
            }
        }
        if (count < 4) return false;
        byte b1 = all[0];
        byte b2 = all[1];
        byte b3 = all[2];
        byte[] data = new byte[count - 3];
        System.arraycopy(all, 3, data, 0, data.length);

        if (targetIfDirect != null) {
            boolean isBmsAddr = ((b3 & 0x7F) == 0x2D) || (b3 == 0);
            if (isBmsAddr && data.length >= 35) return parseBmsUdsPayload(data, targetIfDirect);
            if ((b3 & 0x80) == 0 && data.length >= 8) {
                return parseHscanBypassCanFrame(b3 & 0x7F, data, targetIfDirect, 10000L, 0L, 0L);
            }
            if ((b3 & 0xFF) == 0xB2 && data.length >= 12
                    && (data[2] & 0xFF) == 0x61 && (data[3] & 0xFF) == 0x01) {
                double inC = parseCanTempByteToCelsius(data[11] & 0xFF);
                if (inC > -40.0 && inC < 60.0) {
                    targetIfDirect.inCarTempC = inC;
                    return true;
                }
            }
            return false;
        } else {
            if ((b3 & 0xFF) == 0xB2 && data.length >= 12
                    && (data[2] & 0xFF) == 0x61 && (data[3] & 0xFF) == 0x01) {
                double inC = parseCanTempByteToCelsius(data[11] & 0xFF);
                if (inC > -40.0 && inC < 60.0) {
                    synchronized (TelemetryCollector.liveSnapshot) {
                        TelemetryCollector.liveSnapshot.inCarTempC = inC;
                    }
                }
            }
            TelemetryCollector.onHscanPacket(b1, b2, b3, data);
            return true;
        }
    }

    public static boolean parseCarrotManJson(String json, TelemetrySnapshot target) {
        if (json == null || target == null || !json.contains("{")) return false;
        boolean matched = false;
        Integer vEgo = extractJsonInt(json, "v_ego_kph");
        if (vEgo == null) vEgo = extractJsonInt(json, "vEgo");
        if (vEgo != null) { target.carrotSpeedKph = vEgo.intValue(); matched = true; }
        Integer vCruise = extractJsonInt(json, "v_cruise_kph");
        if (vCruise == null) vCruise = extractJsonInt(json, "vCruise");
        if (vCruise != null) { target.carrotCruiseKph = vCruise.intValue(); matched = true; }
        Boolean active = extractJsonBool(json, "active");
        if (active == null) active = extractJsonBool(json, "activeCarrot");
        if (active != null) { target.carrotActive = active.booleanValue(); matched = true; }
        Integer sdiType = extractJsonInt(json, "sdi_type");
        if (sdiType == null) sdiType = extractJsonInt(json, "xSpdType");
        if (sdiType != null) { target.carrotSdiType = sdiType.intValue(); matched = true; }
        Integer sdiLim = extractJsonInt(json, "sdi_speed_limit");
        if (sdiLim == null) sdiLim = extractJsonInt(json, "xSpdLimit");
        if (sdiLim == null) sdiLim = extractJsonInt(json, "nRoadLimitSpeed");
        if (sdiLim != null) { target.carrotSdiSpeedLimit = sdiLim.intValue(); matched = true; }
        Integer sdiDist = extractJsonInt(json, "sdi_dist");
        if (sdiDist == null) sdiDist = extractJsonInt(json, "xSpdDist");
        if (sdiDist != null) { target.carrotSdiDistM = sdiDist.intValue(); matched = true; }
        Integer tbtDist = extractJsonInt(json, "tbt_dist");
        if (tbtDist == null) tbtDist = extractJsonInt(json, "xDistToTurn");
        if (tbtDist != null) { target.carrotTbtDistM = tbtDist.intValue(); matched = true; }
        String road = extractJsonString(json, "tbt_road");
        if (road == null || road.isEmpty()) road = extractJsonString(json, "szTBTMainText");
        if (road == null || road.isEmpty()) road = extractJsonString(json, "szPosRoadName");
        if (road != null && !road.isEmpty()) { target.carrotTbtRoad = road; matched = true; }
        String ip = extractJsonString(json, "ip");
        if (ip != null && !ip.isEmpty()) { target.carrotIp = ip; matched = true; }
        if (matched) target.carrotConnected = true;
        return matched;
    }

    public static void parseSharedSysMonitorJson(String json, TelemetrySnapshot target) {
        if (json == null || target == null || !json.contains("\"ok\":true")) return;
        Double soc = extractJsonDouble(json, "soc");
        if (soc != null && soc.doubleValue() >= 0.0) target.displaySocPct = soc.doubleValue();
        Double bmsSoc = extractJsonDouble(json, "bmsSoc");
        if (bmsSoc != null && bmsSoc.doubleValue() >= 0.0) target.bmsSocPct = bmsSoc.doubleValue();
        Double soh = extractJsonDouble(json, "soh");
        if (soh != null && soh.doubleValue() > 0.0) target.sohPct = soh.doubleValue();
        Double remKwh = extractJsonDouble(json, "remainKwh");
        if (remKwh != null && remKwh.doubleValue() > 0.0) target.remainEnergyKwh = remKwh.doubleValue();
        Integer dte = extractJsonInt(json, "dteKm");
        if (dte != null && dte.intValue() > 0) target.dteKm = dte.intValue();
        Double avgEcon = extractJsonDouble(json, "avgEcon");
        if (avgEcon != null && avgEcon.doubleValue() > 0.0) target.avgEconomyKmKwh = avgEcon.doubleValue();
        Double packV = extractJsonDouble(json, "packV");
        if (packV != null && packV.doubleValue() > 0.0) target.packVoltV = packV.doubleValue();
        Double packA = extractJsonDouble(json, "packA");
        if (packA != null) { target.packCurrA = packA.doubleValue(); target.hasPackCurrent = true; }
        Double cellMax = extractJsonDouble(json, "cellMaxV");
        if (cellMax != null && cellMax.doubleValue() > 0.0) target.cellMaxVoltV = cellMax.doubleValue();
        Double cellMin = extractJsonDouble(json, "cellMinV");
        if (cellMin != null && cellMin.doubleValue() > 0.0) target.cellMinVoltV = cellMin.doubleValue();
        Integer cellDiff = extractJsonInt(json, "cellDiffMv");
        if (cellDiff != null && cellDiff.intValue() >= 0) target.cellDiffMv = cellDiff.intValue();
        Integer battT = extractJsonInt(json, "battTempC");
        if (battT != null) target.battMinTempC = battT;
        Double inCar = extractJsonDouble(json, "inCarTempC");
        if (inCar != null && inCar.doubleValue() > -50.0) target.inCarTempC = inCar.doubleValue();
        Double outT = extractJsonDouble(json, "outdoorTempC");
        if (outT != null && outT.doubleValue() > -50.0) target.outdoorTempC = outT.doubleValue();
    }

    public static String formatTelemetryJson(TelemetrySnapshot ev) {
        if (ev == null) return "{\"ok\":false}";
        StringBuilder sb = new StringBuilder(1280);
        sb.append("{\"ok\":true,\"version\":\"").append(MONITOR_VERSION).append("\"");
        sb.append(String.format(Locale.US, ",\"soc\":%.1f,\"bmsSoc\":%.1f,\"soh\":%.1f,\"remainKwh\":%.2f",
                ev.getEffectiveSocPct(), ev.bmsSocPct, ev.sohPct, ev.remainEnergyKwh));
        sb.append(String.format(Locale.US, ",\"dteKm\":%d,\"dteOffDeltaKm\":%d,\"avgEcon\":%.1f",
                ev.dteKm, ev.dteAirconOffDeltaKm, ev.avgEconomyKmKwh));
        sb.append(String.format(Locale.US, ",\"totalKw\":%.2f,\"driveKw\":%.2f,\"climateKw\":%.2f,\"elecKw\":%.2f,\"battCareKw\":%.2f",
                ev.getTotalPowerKw(), ev.getEffectiveDriveMotorKw(), ev.climatePowerKw, ev.electronicsPowerKw, ev.batteryCarePowerKw));
        sb.append(String.format(Locale.US, ",\"packV\":%.1f,\"packA\":%.1f,\"cellMaxV\":%.2f,\"cellMinV\":%.2f,\"cellDiffMv\":%d",
                ev.packVoltV, ev.packCurrA, ev.cellMaxVoltV, ev.cellMinVoltV, ev.cellDiffMv));
        sb.append(",\"battTempC\":").append(ev.getRepresentativeBattTempC() != null ? ev.getRepresentativeBattTempC().intValue() : "null");
        sb.append(",\"gear\":\"").append(ev.gearPos != null ? ev.gearPos : "D").append("\"");
        sb.append(String.format(Locale.US, ",\"pedalPct\":%.1f", ev.accelPedalPct));
        sb.append(String.format(Locale.US, ",\"tpms\":{\"fl\":%.1f,\"fr\":%.1f,\"rl\":%.1f,\"rr\":%.1f}",
                ev.tpmsFlPsi, ev.tpmsFrPsi, ev.tpmsRlPsi, ev.tpmsRrPsi));
        sb.append(",\"climate\":{");
        sb.append("\"valid\":").append(ev.hasClimateData);
        sb.append(",\"on\":").append(ev.hvacOn);
        sb.append(",\"auto\":").append(ev.hvacAuto);
        sb.append(",\"ac\":").append(ev.hvacAc);
        sb.append(",\"sync\":").append(ev.hvacSync);
        sb.append(",\"driverOnly\":").append(ev.hvacDriverOnly);
        sb.append(",\"frontDefog\":").append(ev.hvacFrontDefog);
        sb.append(",\"recirc\":").append(ev.hvacRecirc);
        sb.append(",\"blowerStep\":").append(ev.hvacBlowerStep);
        sb.append(",\"blowerMode\":").append(ev.hvacBlowerMode);
        sb.append(",\"blowerModeText\":\"").append(formatBlowerModeText(ev.hvacBlowerMode)).append("\"");
        sb.append(String.format(Locale.US, ",\"drTempC\":%.1f,\"psTempC\":%.1f,\"inCarTempC\":%.1f,\"outdoorTempC\":%.1f",
                ev.hvacDrTempC, ev.hvacPsTempC, ev.inCarTempC, ev.outdoorTempC));
        sb.append("}");
        sb.append(",\"carrot\":{");
        sb.append("\"connected\":").append(ev.carrotConnected);
        sb.append(",\"speedKph\":").append(ev.carrotSpeedKph);
        sb.append(",\"cruiseKph\":").append(ev.carrotCruiseKph);
        sb.append(",\"active\":").append(ev.carrotActive);
        sb.append(",\"sdiType\":").append(ev.carrotSdiType);
        sb.append(",\"sdiSpeedLimit\":").append(ev.carrotSdiSpeedLimit);
        sb.append(",\"sdiDistM\":").append(ev.carrotSdiDistM);
        sb.append(",\"tbtDistM\":").append(ev.carrotTbtDistM);
        sb.append(",\"ip\":\"").append(ev.carrotIp != null ? ev.carrotIp : "").append("\"");
        sb.append("}");
        sb.append(",\"media\":{");
        sb.append("\"source\":\"").append(escapeJson(ev.mediaSource)).append("\"");
        sb.append(",\"title\":\"").append(escapeJson(ev.mediaTitle)).append("\"");
        sb.append(",\"artist\":\"").append(escapeJson(ev.mediaArtist)).append("\"");
        sb.append(",\"album\":\"").append(escapeJson(ev.mediaAlbum)).append("\"");
        sb.append(",\"playing\":").append(ev.mediaPlaying);
        sb.append(",\"posSec\":").append(ev.mediaPositionSec);
        sb.append(",\"durSec\":").append(ev.mediaDurationSec);
        sb.append("}");
        sb.append(",\"usb\":{");
        sb.append("\"connected\":").append(TurzxUsbDriver.isConnected());
        sb.append(",\"frames\":").append(TurzxUsbDriver.getPushedFrames());
        sb.append(String.format(Locale.US, ",\"fps\":%.1f", TurzxUsbDriver.getLiveFps()));
        sb.append(",\"status\":\"").append(escapeJson(TurzxUsbDriver.getStatusText())).append("\"");
        sb.append("},\"ota\":{");
        sb.append("\"updating\":").append(OtaUpdateManager.isUpdating());
        sb.append(",\"remoteVer\":\"").append(escapeJson(OtaUpdateManager.getRemoteVersion())).append("\"");
        sb.append(",\"status\":\"").append(escapeJson(OtaUpdateManager.getStatusText())).append("\"");
        sb.append("},\"debug\":{");
        sb.append("\"lastDumpPath\":\"").append(escapeJson(DebugManager.getLastDumpPath())).append("\"");
        sb.append("}}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static boolean isValidDteValue(int dte) {
        return dte > 0 && dte < 1500
                && dte != 254 && dte != 255
                && dte != 510 && dte != 511
                && dte != 1022 && dte != 1023;
    }

    public static int parseAddedDistDatcOff(int raw) {
        if (raw == 0 || raw == -1) return 0;
        if (raw < 0 && raw > -150) return raw;
        if (raw < 0) return 0;
        int highWord = (raw >>> 8) & 0xFFFF;
        int lowByte = raw & 0xFF;
        int topByte = (raw >>> 16) & 0xFF;
        if (highWord > 0 && highWord < 200) return highWord;
        if (lowByte > 0 && lowByte < 200) return lowByte;
        if (topByte > 0 && topByte < 200) return topByte;
        return 0;
    }

    public static double parsePower10WToKw(int raw, int maxValidRaw) {
        if (raw < 0 || raw > maxValidRaw
                || raw == 254 || raw == 255
                || raw == 510 || raw == 511
                || raw == 1022 || raw == 1023
                || raw == 2046 || raw == 2047) {
            return -1.0;
        }
        return raw * 0.01;
    }

    public static double parseTmPowerWattsToKw(int rawWatts, int maxValidWatts) {
        if (rawWatts < 0 || rawWatts > maxValidWatts
                || rawWatts == 2540 || rawWatts == 2550
                || rawWatts == 5100 || rawWatts == 5110
                || rawWatts == 10220 || rawWatts == 10230
                || rawWatts == 20460 || rawWatts == 20470
                || rawWatts == 65534 || rawWatts == 65535) {
            return -1.0;
        }
        return rawWatts * 0.001;
    }

    public static int normalizeDatcBlowerStep(int rawLevel) {
        if (rawLevel == 0) return 0;
        if (rawLevel == 1) return 1;
        if (rawLevel >= 2 && rawLevel <= 9) return rawLevel - 1;
        return -1;
    }

    public static double convertDatcRawTempToCelsius(int rawCode, float vrTempC) {
        if (!Float.isNaN(vrTempC) && vrTempC >= 13.5f && vrTempC <= 32.5f) {
            return Math.round(vrTempC * 2.0) / 2.0;
        }
        if (rawCode == 1) return 14.0;
        if (rawCode == 254) return 32.0;
        if (rawCode >= 2 && rawCode <= 38) return 14.0 + (rawCode - 2) * 0.5;
        if (rawCode >= 140 && rawCode <= 320) return rawCode * 0.1;
        return -99.0;
    }

    public static double parseCanTempByteToCelsius(int rawByte) {
        int u = rawByte & 0xFF;
        if (u == 0x00 || u == 0xFE || u == 0xFF) return -99.0;
        double degC = u * 0.5 - 40.0;
        if (degC < -40.0 || degC > 75.0) return -99.0;
        return degC;
    }

    public static String formatBlowerModeText(int mode) {
        switch (mode) {
            case 0: return "OFF";
            case 1: return "상체(VENT)";
            case 2: return "상·하체(B/L)";
            case 3: return "하체(FLOOR)";
            case 4: return "유리·하체(MIX)";
            case 5: return "전면유리(DEF)";
            case 6: return "오토디포그";
            case 7: return "유리·상체";
            case 8: return "유리·하체";
            case 9: return "전면·상·하체";
            case 10: return "전면유리(DEF)";
            default: return "상·하체(B/L)";
        }
    }

    public static String resolveGearPosition(
            int tmGearRaw,
            Boolean pgsGearP,
            Boolean pgsGearN,
            int sysGearP,
            boolean rearCamOpen,
            double drivePowerKw
    ) {
        if (rearCamOpen || tmGearRaw == GEAR_STATUS_R) return "R";
        if (Boolean.TRUE.equals(pgsGearN) || tmGearRaw == GEAR_STATUS_N) return "N";
        if (Boolean.TRUE.equals(pgsGearP) || sysGearP == 1) return "P";
        if (tmGearRaw == GEAR_STATUS_D || (tmGearRaw >= 1 && tmGearRaw <= 4) || tmGearRaw == 8) return "D";
        if (tmGearRaw == GEAR_STATUS_P) {
            if (Boolean.FALSE.equals(pgsGearP) || sysGearP == 0 || Math.abs(drivePowerKw) >= 2.0) {
                return (Math.abs(drivePowerKw) >= 2.0) ? "D" : "";
            }
            return "P";
        }
        return "";
    }

    public static MonitorConfig loadMonitorConfig(File file) {
        MonitorConfig cfg = new MonitorConfig();
        if (file == null || !file.isFile()) return cfg;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(file), UTF8));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if ("overlay_enabled".equals(k)) cfg.overlayEnabled = "1".equals(v) || "true".equalsIgnoreCase(v);
                else if ("overlay_expanded".equals(k)) cfg.overlayExpanded = "1".equals(v) || "true".equalsIgnoreCase(v);
                else if ("usb_push_enabled".equals(k)) cfg.usbPushEnabled = "1".equals(v) || "true".equalsIgnoreCase(v);
                else if ("usb_brightness".equals(k)) cfg.usbBrightness = Math.max(0, Math.min(100, parseIntSafe(v, 85)));
                else if ("usb_rotation".equals(k)) cfg.usbRotation = parseIntSafe(v, 270);
                else if ("usb_jpeg_quality".equals(k)) cfg.usbJpegQuality = Math.max(50, Math.min(95, parseIntSafe(v, 85)));
                else if ("usb_fps".equals(k)) cfg.usbFps = Math.max(2, Math.min(25, parseIntSafe(v, 12)));
                else if ("comma_ip".equals(k)) cfg.commaIp = v;
                else if ("auto_update".equals(k)) cfg.autoUpdate = !"0".equals(v) && !"false".equalsIgnoreCase(v);
                else if ("x".equals(k)) cfg.x = parseIntSafe(v, 24);
                else if ("y".equals(k)) cfg.y = parseIntSafe(v, 64);
            }
        } catch (Throwable ignored) {
        } finally {
            if (br != null) { try { br.close(); } catch (Throwable ignored) {} }
        }
        return cfg;
    }

    public static void saveMonitorConfig(File file, MonitorConfig cfg) {
        if (file == null || cfg == null) return;
        try {
            String content = "overlay_enabled=" + (cfg.overlayEnabled ? 1 : 0) + "\n"
                    + "overlay_expanded=" + (cfg.overlayExpanded ? 1 : 0) + "\n"
                    + "usb_push_enabled=" + (cfg.usbPushEnabled ? 1 : 0) + "\n"
                    + "usb_brightness=" + cfg.usbBrightness + "\n"
                    + "usb_rotation=" + cfg.usbRotation + "\n"
                    + "usb_jpeg_quality=" + cfg.usbJpegQuality + "\n"
                    + "usb_fps=" + cfg.usbFps + "\n"
                    + "comma_ip=" + (cfg.commaIp != null ? cfg.commaIp : "") + "\n"
                    + "auto_update=" + (cfg.autoUpdate ? 1 : 0) + "\n"
                    + "x=" + cfg.x + "\n"
                    + "y=" + cfg.y + "\n";
            writeBytesAtomic(file, content.getBytes(UTF8));
        } catch (Throwable ignored) {
        }
    }

    private static final char[] B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final int[] B64_INV = new int[128];
    static {
        for (int i = 0; i < B64_INV.length; i++) B64_INV[i] = -1;
        for (int i = 0; i < B64_ALPHABET.length; i++) B64_INV[B64_ALPHABET[i]] = i;
    }

    public static String encodeBase64(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder sb = new StringBuilder(((data.length + 2) / 3) * 4);
        int i = 0;
        while (i < data.length) {
            int b0 = data[i++] & 0xFF;
            int b1 = (i < data.length) ? (data[i++] & 0xFF) : -1;
            int b2 = (i < data.length) ? (data[i++] & 0xFF) : -1;
            sb.append(B64_ALPHABET[b0 >>> 2]);
            if (b1 < 0) {
                sb.append(B64_ALPHABET[(b0 & 0x03) << 4]);
                sb.append("==");
            } else if (b2 < 0) {
                sb.append(B64_ALPHABET[((b0 & 0x03) << 4) | (b1 >>> 4)]);
                sb.append(B64_ALPHABET[(b1 & 0x0F) << 2]);
                sb.append('=');
            } else {
                sb.append(B64_ALPHABET[((b0 & 0x03) << 4) | (b1 >>> 4)]);
                sb.append(B64_ALPHABET[((b1 & 0x0F) << 2) | (b2 >>> 6)]);
                sb.append(B64_ALPHABET[b2 & 0x3F]);
            }
        }
        return sb.toString();
    }

    public static byte[] decodeBase64(String b64) {
        if (b64 == null || b64.isEmpty()) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream((b64.length() * 3) / 4);
        int accum = 0;
        int bits = 0;
        for (int i = 0; i < b64.length(); i++) {
            char c = b64.charAt(i);
            if (c == '=') break;
            if (c >= 128) continue;
            int val = B64_INV[c];
            if (val < 0) continue;
            accum = (accum << 6) | val;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((accum >>> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(data != null ? data : new byte[0]);
            StringBuilder sb = new StringBuilder(64);
            for (int i = 0; i < dig.length; i++) {
                sb.append(String.format(Locale.US, "%02x", dig[i] & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    public static String sha256File(File f) {
        try {
            return sha256Hex(readFileBytesQuick(f));
        } catch (Throwable e) {
            return "";
        }
    }

    public static Map<String, String> parseJsonFlatMap(String json) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        if (json == null) return map;
        int i = 0;
        int n = json.length();
        while (i < n) {
            int k1 = json.indexOf('"', i);
            if (k1 < 0) break;
            int k2 = json.indexOf('"', k1 + 1);
            if (k2 < 0) break;
            String key = json.substring(k1 + 1, k2);
            int colon = json.indexOf(':', k2 + 1);
            if (colon < 0) break;
            int p = colon + 1;
            while (p < n && Character.isWhitespace(json.charAt(p))) p++;
            if (p >= n) break;
            if (json.charAt(p) == '"') {
                int v2 = json.indexOf('"', p + 1);
                if (v2 < 0) break;
                map.put(key, json.substring(p + 1, v2));
                i = v2 + 1;
            } else {
                int vEnd = p;
                while (vEnd < n && json.charAt(vEnd) != ',' && json.charAt(vEnd) != '}' && !Character.isWhitespace(json.charAt(vEnd))) {
                    vEnd++;
                }
                map.put(key, json.substring(p, vEnd));
                i = vEnd + 1;
            }
        }
        return map;
    }

    private static Integer extractJsonInt(String json, String key) {
        Double d = extractJsonDouble(json, key);
        return d != null ? Integer.valueOf((int) Math.round(d.doubleValue())) : null;
    }

    private static Double extractJsonDouble(String json, String key) {
        String pat = "\"" + key + "\"";
        int idx = json.indexOf(pat);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + pat.length());
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        int start = i;
        if (i < json.length() && (json.charAt(i) == '-' || json.charAt(i) == '+')) i++;
        while (i < json.length() && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '.')) i++;
        if (i <= start) return null;
        try {
            return Double.valueOf(Double.parseDouble(json.substring(start, i)));
        } catch (Throwable e) {
            return null;
        }
    }

    private static Boolean extractJsonBool(String json, String key) {
        String pat = "\"" + key + "\"";
        int idx = json.indexOf(pat);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + pat.length());
        if (colon < 0) return null;
        String rem = json.substring(colon + 1).trim().toLowerCase(Locale.US);
        if (rem.startsWith("true") || rem.startsWith("1")) return Boolean.TRUE;
        if (rem.startsWith("false") || rem.startsWith("0")) return Boolean.FALSE;
        return null;
    }

    private static String extractJsonString(String json, String key) {
        String pat = "\"" + key + "\"";
        int idx = json.indexOf(pat);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + pat.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    private static String getSystemProperty(String key, String def) {
        try {
            Class<?> spCls = Class.forName("android.os.SystemProperties");
            String val = (String) spCls.getMethod("get", String.class, String.class).invoke(null, key, def);
            return (val == null || val.isEmpty()) ? def : val;
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static String readFirstLineQuick(File f) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f), UTF8));
            String s = br.readLine();
            return s != null ? s.trim() : "";
        } catch (Throwable e) {
            return "";
        } finally {
            if (br != null) { try { br.close(); } catch (Exception ignored) {} }
        }
    }

    private static String readAllTextQuick(File f) {
        try {
            byte[] b = readFileBytesQuick(f);
            return new String(b, UTF8);
        } catch (Throwable e) {
            return "";
        }
    }

    private static byte[] readFileBytesQuick(File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream((int) Math.max(1024L, f.length()));
            byte[] buf = new byte[8192];
            int r;
            while ((r = fis.read(buf)) > 0) {
                baos.write(buf, 0, r);
            }
            return baos.toByteArray();
        } finally {
            fis.close();
        }
    }

    private static void writeBytesAtomic(File target, byte[] data) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            fos.write(data);
            fos.getFD().sync();
        } finally {
            fos.close();
        }
        if (!tmp.renameTo(target)) {
            FileOutputStream direct = new FileOutputStream(target);
            try {
                direct.write(data);
                direct.getFD().sync();
            } finally {
                direct.close();
            }
            tmp.delete();
        }
    }

    private static int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (Throwable e) { return def; }
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new IllegalStateException("Self-test assertion failed: " + msg);
    }

    private static void runSelfTests() {
        // 1. Verify TURZX V1.x (turzx_0001_0015) 512-byte DES-CBC ("slv3tuzx") command header & 480x1920 transparent PNG
        int testJpegSize = 48291;
        byte[] pkt = buildTurzxCommandPacket(TURZX_CMD_SEND_JPEG, testJpegSize);
        assertTrue(pkt.length == 512, "Expected 512-byte TURZX command packet");
        assertTrue((pkt[510] & 0xFF) == 0xA1 && (pkt[511] & 0xFF) == 0x1A,
                "Expected TURZX trailer A1 1A at bytes 510..511");
        byte[] dec = decryptTurzxCommandPacket(pkt);
        assertTrue((dec[0] & 0xFF) == TURZX_CMD_SEND_JPEG && (dec[2] & 0xFF) == 0x1A && (dec[3] & 0xFF) == 0x6D,
                "Expected decrypted cmdId=101 and magic 1A 6D at [2..3]");
        int unpackedLen = ((dec[8] & 0xFF) << 24) | ((dec[9] & 0xFF) << 16) | ((dec[10] & 0xFF) << 8) | (dec[11] & 0xFF);
        assertTrue(unpackedLen == testJpegSize,
                "Expected decrypted payloadLength=" + testJpegSize + " at [8..11], got " + unpackedLen);

        byte[] brPkt = buildTurzxCommandPacket(TURZX_CMD_BRIGHTNESS, 97);
        byte[] brDec = decryptTurzxCommandPacket(brPkt);
        assertTrue((brDec[0] & 0xFF) == TURZX_CMD_BRIGHTNESS && (brDec[8] & 0xFF) == 97,
                "Expected brightness cmdId=14 and val=97 at plain[8]");

        byte[] clearPng = buildTransparentOverlayPng480x1920();
        assertTrue(clearPng != null && clearPng.length > 100 && (clearPng[0] & 0xFF) == 0x89 && clearPng[1] == 0x50,
                "Expected valid 480x1920 transparent RGBA PNG");

        // 2. Verify real Kia Niro EV (DE22EV) BMS UDS 62 01 01 & 62 01 05 logcat fallback parsing
        String sampleLog0101 = "I/TM_HSCANPacketRunner(  524): onEventFromMicom(Bypass CAN) : length _64[c7, c0, ad, c8, 2d, "
                + "62, 01, 01, ff, f7, e7, ff, 48, 00, 00, 00, 00, 83, 00, 08, 0d, d3, 16, 14, 15, 14, 15, 15, "
                + "00, 00, 16, b4, 0e, b4, 01, 00, 00, 8c, 00, 06, bd, a5, 00, 06, 60, 40, 00, 02, 81, e7, "
                + "00, 02, 4b, 63, 00, ef, 45, 85, 0d, 01, 62, 00, 00, 00, 00, 03, e8]";
        String sampleLog0105 = "I/TM_HSCANPacketRunner(  524): onEventFromMicom(Bypass CAN) : length _48[c7, c0, ad, c8, 2d, "
                + "62, 01, 05, 00, 3f, 90, 00, 00, 00, 00, 00, 00, 00, 00, 00, 00, 15, 15, 00, 00, 00, 00, "
                + "29, 86, 00, 01, 15, 4a, 03, cc, 00, 00, 39, 38, 4a, 00, 00, 00, 00, ac, 00, af, 00, 00, 00, 00]";
        TelemetrySnapshot snap = new TelemetrySnapshot();
        assertTrue(parseTmHscanLogLine(sampleLog0101, snap), "Expected 62 01 01 parse");
        assertTrue(parseTmHscanLogLine(sampleLog0105, snap), "Expected 62 01 05 parse");
        assertTrue(Math.abs(snap.displaySocPct - 37.0) < 0.01 && Math.abs(snap.sohPct - 97.2) < 0.01,
                "Expected SoC=37.0% SOH=97.2%");
        assertTrue(Math.abs(snap.packVoltV - 353.9) < 0.01 && snap.cellDiffMv == 0,
                "Expected PackV=353.9V CellDiff=0mV");

        // 3. Verify DATC climate & indoor/outdoor temperature decoding
        byte[] datc3 = new byte[]{0, 0, 0, 0, 0, (byte) 116, 0, 0}; // 18.0°C outdoor
        byte[] datc4 = new byte[]{0, 0, 0, 0, 0, 0, 0, (byte) 125}; // 22.5°C indoor
        assertTrue(parseHscanBypassCanFrame(0x10, datc3, snap, 10000L, 0L, 0L), "Expected DATC3 outdoor temp");
        assertTrue(parseHscanBypassCanFrame(0x11, datc4, snap, 10000L, 0L, 0L), "Expected DATC4 indoor temp");
        assertTrue(Math.abs(snap.outdoorTempC - 18.0) < 0.01 && Math.abs(snap.inCarTempC - 22.5) < 0.01,
                "Expected outdoor=18.0°C indoor=22.5°C");

        // 4. Verify Comma 3X CVS1 binary WebSocket payload decoding & CarrotMan UDP JSON
        byte[] cvs1 = new byte[128];
        cvs1[0] = (byte) 'C';
        cvs1[1] = (byte) 'V';
        cvs1[2] = (byte) 'S';
        cvs1[3] = (byte) '1';
        ByteBuffer bb = ByteBuffer.wrap(cvs1).order(ByteOrder.LITTLE_ENDIAN);
        bb.putShort(4, (short) 1);
        bb.putFloat(16, 23.3333f); // ~84 km/h
        bb.putFloat(20, 90.0f);    // Cruise 90 km/h
        bb.putFloat(24, 34.5f);    // Lead 34.5 m
        bb.putFloat(28, -1.2f);    // RelVel
        bb.putFloat(32, 36.5f);    // FL psi
        bb.putFloat(36, 36.5f);    // FR psi
        bb.putFloat(40, 35.5f);    // RL psi
        bb.putFloat(44, 36.0f);    // RR psi
        bb.putFloat(56, 0.00025f); // d_poly[2] curvature
        assertTrue(decodeCvs1BinaryPayload(cvs1, snap), "Expected CVS1 binary decode success");
        assertTrue(snap.carrotConnected && snap.carrotSpeedKph == 84 && snap.carrotCruiseKph == 90,
                "Expected CVS1 speed=84 cruise=90, got " + snap.carrotSpeedKph + "/" + snap.carrotCruiseKph);
        assertTrue(Math.abs(snap.tpmsFlPsi - 36.5f) < 0.05f && Math.abs(snap.leadDistM - 34.5f) < 0.05f,
                "Expected CVS1 TPMS FL=36.5 psi and leadDist=34.5m");

        // Verify real ajouatom/openpilot CVB1 batch containing carState (svc=1, 73B) + radarState (svc=13, 50B)
        byte[] svc1 = new byte[73];
        svc1[0] = 'C'; svc1[1] = 'V'; svc1[2] = 'S'; svc1[3] = '1'; svc1[4] = 1;
        ByteBuffer b1 = ByteBuffer.wrap(svc1).order(ByteOrder.LITTLE_ENDIAN);
        b1.putFloat(8, 25.0f);  // vEgo = 90 km/h
        b1.putFloat(16, 25.0f); // vEgoCluster = 90 km/h
        b1.putFloat(20, 100.0f);// vCruiseCluster = 100 km/h
        svc1[46] = 2;           // gearShifter = drive (D)
        b1.putFloat(57, 37.0f); // FL psi
        b1.putFloat(61, 37.0f); // FR psi
        b1.putFloat(65, 36.0f); // RL psi
        b1.putFloat(69, 36.0f); // RR psi

        byte[] svc13 = new byte[50];
        svc13[0] = 'C'; svc13[1] = 'V'; svc13[2] = 'S'; svc13[3] = '1'; svc13[4] = 13;
        ByteBuffer b13 = ByteBuffer.wrap(svc13).order(ByteOrder.LITTLE_ENDIAN);
        b13.putFloat(8, 41.2f); // leadOne.dRel
        b13.putFloat(16, -0.5f);// leadOne.vRel
        svc13[49] = 1;          // leadOne.status = true

        byte[] cvb1 = new byte[6 + 4 + svc1.length + 4 + svc13.length];
        cvb1[0] = 'C'; cvb1[1] = 'V'; cvb1[2] = 'B'; cvb1[3] = '1';
        ByteBuffer bBatch = ByteBuffer.wrap(cvb1).order(ByteOrder.LITTLE_ENDIAN);
        bBatch.putShort(4, (short) 2);
        bBatch.putInt(6, svc1.length);
        System.arraycopy(svc1, 0, cvb1, 10, svc1.length);
        bBatch.putInt(10 + svc1.length, svc13.length);
        System.arraycopy(svc13, 0, cvb1, 14 + svc1.length, svc13.length);
        assertTrue(decodeCvs1BinaryPayload(cvb1, snap), "Expected CVB1 batch decode success");
        assertTrue(snap.carrotSpeedKph == 90 && snap.carrotCruiseKph == 100 && "D".equals(snap.gearPos)
                && Math.abs(snap.tpmsFlPsi - 37.0f) < 0.05f && snap.hasLeadCar && Math.abs(snap.leadDistM - 41.2f) < 0.05f,
                "Expected CVB1 carState+radarState decoded accurately");

        String carrotUdp = "{\"v_ego_kph\": 88, \"v_cruise_kph\": 100, \"active\": true, \"sdi_type\": 1, \"sdi_speed_limit\": 80, \"sdi_dist\": 290, \"tbt_dist\": 450, \"ip\": \"192.168.43.10\"}";
        assertTrue(parseCarrotManJson(carrotUdp, snap), "Expected CarrotMan UDP JSON parse");
        assertTrue(snap.carrotSpeedKph == 88 && snap.carrotSdiType == 1 && snap.carrotSdiDistM == 290,
                "Expected CarrotMan UDP fields updated");

        // 5. Verify Telemetry JSON serialization & Config persistence
        String json = formatTelemetryJson(snap);
        assertTrue(json.contains("\"soc\":37.0") && json.contains("\"inCarTempC\":22.5")
                && json.contains("\"speedKph\":88") && json.contains("\"media\":{"),
                "Expected complete 4-domain telemetry JSON: " + json);

        try {
            File tmpCfg = File.createTempFile("turzx_cfg_test", ".conf");
            tmpCfg.deleteOnExit();
            MonitorConfig c1 = new MonitorConfig();
            c1.usbBrightness = 92;
            c1.usbRotation = 90;
            c1.usbFps = 15;
            c1.commaIp = "192.168.43.10";
            c1.autoUpdate = false;
            saveMonitorConfig(tmpCfg, c1);
            MonitorConfig c2 = loadMonitorConfig(tmpCfg);
            assertTrue(c2.usbBrightness == 92 && c2.usbRotation == 90 && c2.usbFps == 15
                    && "192.168.43.10".equals(c2.commaIp) && !c2.autoUpdate,
                    "Expected MonitorConfig roundtrip match");
            tmpCfg.delete();

            // 6. Verify OTA Base64 + SHA-256 + version.json parser & DebugManager report
            byte[] sampleBytes = "HKG_5W_TurzxMonitor_OTA_Payload_Test_2026".getBytes(UTF8);
            String b64 = encodeBase64(sampleBytes);
            byte[] decBytes = decodeBase64(b64);
            assertTrue(sha256Hex(sampleBytes).equals(sha256Hex(decBytes)), "Expected Base64 + SHA256 roundtrip match");

            String verSample = "{\"version\":\"1.0.0\",\"sha256_jar\":\"" + sha256Hex(sampleBytes) + "\"}";
            Map<String, String> vMap = parseJsonFlatMap(verSample);
            assertTrue("1.0.0".equals(vMap.get("version")) && sha256Hex(sampleBytes).equals(vMap.get("sha256_jar")),
                    "Expected parseJsonFlatMap to extract version and sha256_jar");

            DebugManager.logEvent("SELF-TEST", "Testing diagnostic log report builder");
            DebugManager.recordHscanFrame((byte) 0xC7, (byte) 0xC0, (byte) 0x2D, new byte[]{0x62, 0x01, 0x01, 0x00});
            String diagReport = DebugManager.buildDiagnosticReport("/data/nemesis/apps/hkg.turzxmonitor", snap, c2, "/tmp/test_debug.txt");
            assertTrue(diagReport.contains("HKG 5W TURZX 9.2\" Cluster HUD Monitor")
                    && diagReport.contains("SELF-TEST")
                    && diagReport.contains("hdr=C7,C0,2D"),
                    "Expected complete DebugManager diagnostic report");
        } catch (Exception e) {
            throw new RuntimeException("MonitorConfig / OTA self-test failed", e);
        }

        System.out.println("[TurzxMonitor] All TURZX DES-CBC USB + 4-Domain Telemetry + CVS1 + DATC + GitHub OTA + DebugManager self-tests passed.");
    }
}
