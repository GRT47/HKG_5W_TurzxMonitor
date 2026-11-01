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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
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

    public static final String MONITOR_VERSION = "1.2.2";
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

    public static final int DATC_FLAG_TEMP = 2;
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
    public static final int DATC_FLAG_TEMP_DR_C = 2;
    public static final int DATC_FLAG_TEMP_DR_C_LEGACY = 28;
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
        public boolean hvacHeat = false;
        public boolean hvacSync = true;
        public boolean hvacDriverOnly = false;
        public boolean hvacFrontDefog = false;
        public boolean hvacRecirc = true;
        public boolean hvacAds = true;
        public int hvacBlowerStep = 3;      // 0..8
        public int hvacBlowerMode = 2;      // 1=VENT, 2=B/L, 3=FLOOR, 4=MIX, 5=DEF
        public double hvacDrTempC = 21.5;
        public double hvacPsTempC = 21.5;
        public double outdoorTempC = 18.0;
        public double inCarTempC = 22.5;

        // 3. Comma 3X (carrot-wip) ADAS + SDI/TBT + TPMS
        public boolean carrotConnected = false;
        public int carrotSpeedKph = 0;
        public int carrotCruiseKph = 80;
        public boolean carrotActive = false;
        public int carrotSdiType = 0;         // >= 0: active camera (Red circle), < 0: normal limit (Blue circle)
        public int carrotSdiSpeedLimit = 80;  // km/h
        public int carrotSdiDistM = 2300;
        public int carrotTbtDistM = 280;
        public String carrotTbtRoad = "판교JC";
        public String carrotIp = "";
        public float leadDistM = 34.0f;
        public float leadRelVelKph = 0.0f;
        public boolean hasLeadCar = true;
        public float[] pathPoly = new float[]{0.0f, 0.008f, 0.00048f, 0.0f};
        public float tpmsFlPsi = 38.0f;
        public float tpmsFrPsi = 38.0f;
        public float tpmsRlPsi = 39.0f;
        public float tpmsRrPsi = 39.0f;

        // 4. Now Playing Media
        public String mediaSource = "Bluetooth Audio";
        public String mediaTitle = "미디어 재생 대기 중";
        public String mediaArtist = "Bluetooth · USB · Radio";
        public String mediaAlbum = "";
        public boolean mediaPlaying = false;
        public int mediaPositionSec = 0;
        public int mediaDurationSec = 0;
        public Object albumArtBitmap = null;

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
        private static Object paintShader = null;
        private static Object paintPublicBold = null;
        private static Object paintPublicMed = null;
        private static Object paintSysBold = null;
        private static Object paintSysMed = null;
        private static Object rectF = null;
        private static Object srcRect = null;
        private static Object dstRect = null;

        // Pre-rendered Chromium 1:1 layer sprites from turzx_92_mockup.html
        private static Object bmpBaseShell = null;
        private static Object bmpActiveShell = null;
        private static Object bmpCol2Section = null;
        private static Object bmpCol2Traffic = null;
        private static Object bmpCol2None = null;
        private static boolean assetsLoaded = false;

        private static float roadPhase = 0.0f;

        private static byte[] readJarAssetBytes(String assetName) {
            String relPath = "assets/" + assetName;
            InputStream is = null;
            try {
                is = TurzxMonitorMain.class.getResourceAsStream("/" + relPath);
                if (is == null && TurzxMonitorMain.class.getClassLoader() != null) {
                    is = TurzxMonitorMain.class.getClassLoader().getResourceAsStream(relPath);
                }
                if (is != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream(65536);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
                    return baos.toByteArray();
                }
            } catch (Throwable ignored) {
            } finally {
                if (is != null) { try { is.close(); } catch (Throwable ignored) {} }
            }

            String cp = System.getProperty("java.class.path", "");
            String[] candJars = new String[]{
                    cp,
                    "/data/nemesis/packages/hkg.turzxmonitor/bin/turzxmonitor.jar",
                    "/data/local/tmp/turzxmonitor.jar"
            };
            for (int i = 0; i < candJars.length; i++) {
                String path = candJars[i];
                if (path == null || path.isEmpty()) continue;
                int sep = path.indexOf(':');
                if (sep > 0) path = path.substring(0, sep);
                File f = new File(path);
                if (!f.isFile()) continue;
                ZipFile zf = null;
                InputStream zis = null;
                try {
                    zf = new ZipFile(f);
                    ZipEntry ze = zf.getEntry(relPath);
                    if (ze != null) {
                        zis = zf.getInputStream(ze);
                        ByteArrayOutputStream baos = new ByteArrayOutputStream((int) Math.max(4096L, ze.getSize()));
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = zis.read(buf)) > 0) baos.write(buf, 0, n);
                        return baos.toByteArray();
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (zis != null) { try { zis.close(); } catch (Throwable ignored) {} }
                    if (zf != null) { try { zf.close(); } catch (Throwable ignored) {} }
                }
            }
            return null;
        }

        private static Object decodeAssetBitmap(Method decodeByteArray, String assetName) {
            try {
                byte[] raw = readJarAssetBytes(assetName);
                if (raw != null && raw.length > 0) {
                    return decodeByteArray.invoke(null, raw, Integer.valueOf(0), Integer.valueOf(raw.length));
                }
            } catch (Throwable t) {
                DebugManager.logError("RENDER", "decodeAssetBitmap(" + assetName + ")", t);
            }
            return null;
        }

        private static Object loadBundledTypeface(Class<?> tfCls, String assetName, String tmpFileName, Object fallbackTf) {
            try {
                byte[] raw = readJarAssetBytes(assetName);
                if (raw != null && raw.length > 1024) {
                    File out = new File("/data/local/tmp/" + tmpFileName);
                    if (!out.isFile() || out.length() != raw.length) {
                        writeBytesAtomic(out, raw);
                        out.setReadable(true, false);
                    }
                    Object tf = tfCls.getMethod("createFromFile", File.class).invoke(null, out);
                    if (tf != null) return tf;
                }
            } catch (Throwable t) {
                DebugManager.logError("RENDER", "loadBundledTypeface(" + assetName + ")", t);
            }
            return fallbackTf;
        }

        private static void ensureAssetsLoaded(Class<?> tfCls, Class<?> paintCls) {
            if (assetsLoaded) return;
            assetsLoaded = true;
            try {
                Class<?> bfCls = Class.forName("android.graphics.BitmapFactory");
                Method decodeByteArray = bfCls.getMethod("decodeByteArray", byte[].class, int.class, int.class);
                bmpBaseShell = decodeAssetBitmap(decodeByteArray, "base_shell.png");
                bmpActiveShell = decodeAssetBitmap(decodeByteArray, "active_shell.png");
                bmpCol2Section = decodeAssetBitmap(decodeByteArray, "col2_hud_section.png");
                bmpCol2Traffic = decodeAssetBitmap(decodeByteArray, "col2_hud_traffic.png");
                bmpCol2None = decodeAssetBitmap(decodeByteArray, "col2_hud_none.png");

                Object sysBoldTf = tfCls.getField("DEFAULT_BOLD").get(null);
                Object sysMedTf = tfCls.getField("SANS_SERIF").get(null);
                Object pubBoldTf = loadBundledTypeface(tfCls, "PublicSans-Bold.ttf", "hkg_public_sans_bold.ttf", sysBoldTf);
                Object pubMedTf = loadBundledTypeface(tfCls, "PublicSans-Medium.ttf", "hkg_public_sans_medium.ttf", sysMedTf);

                Method setTf = paintCls.getMethod("setTypeface", tfCls);
                setTf.invoke(paintPublicBold, pubBoldTf);
                setTf.invoke(paintPublicMed, pubMedTf);
                setTf.invoke(paintSysBold, sysBoldTf);
                setTf.invoke(paintSysMed, sysMedTf);

                DebugManager.logEvent("RENDER", "Chromium 1:1 HUD layers loaded: base=" + (bmpBaseShell != null)
                        + " active=" + (bmpActiveShell != null)
                        + " col2=" + (bmpCol2Section != null));
            } catch (Throwable t) {
                DebugManager.logError("RENDER", "ensureAssetsLoaded", t);
            }
        }

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
                Class<?> rectCls = Class.forName("android.graphics.Rect");
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

                    Object fillStyle = styleCls.getField("FILL").get(null);
                    Object strokeStyle = styleCls.getField("STROKE").get(null);

                    paintFill = paintCls.getConstructor(int.class).newInstance(Integer.valueOf(1));
                    paintCls.getMethod("setStyle", styleCls).invoke(paintFill, fillStyle);

                    paintStroke = paintCls.getConstructor(int.class).newInstance(Integer.valueOf(1));
                    paintCls.getMethod("setStyle", styleCls).invoke(paintStroke, strokeStyle);

                    paintShader = paintCls.getConstructor(int.class).newInstance(Integer.valueOf(1));
                    paintCls.getMethod("setStyle", styleCls).invoke(paintShader, fillStyle);

                    // 129 = ANTI_ALIAS_FLAG (1) | SUBPIXEL_TEXT_FLAG (128)
                    paintPublicBold = paintCls.getConstructor(int.class).newInstance(Integer.valueOf(129));
                    paintPublicMed = paintCls.getConstructor(int.class).newInstance(Integer.valueOf(129));
                    paintSysBold = paintCls.getConstructor(int.class).newInstance(Integer.valueOf(129));
                    paintSysMed = paintCls.getConstructor(int.class).newInstance(Integer.valueOf(129));

                    rectF = rectFCls.getConstructor().newInstance();
                    srcRect = rectCls.getConstructor().newInstance();
                    dstRect = rectCls.getConstructor().newInstance();

                    ensureAssetsLoaded(tfCls, paintCls);
                }

                int spd = (s.carrotSpeedKph > 0) ? s.carrotSpeedKph : 0;
                if (spd > 0) {
                    roadPhase = (roadPhase + (spd / 180.0f)) % 36.0f;
                }

                drawHudToCanvas(landscapeCanvas, paintFill, paintStroke, paintShader,
                        paintPublicBold, paintPublicMed, paintSysBold, paintSysMed,
                        rectF, srcRect, dstRect,
                        bmpCls, canvasCls, paintCls, alignCls, rectFCls, rectCls, pathCls, s);

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
                    matrixCls.getMethod("postRotate", float.class).invoke(rotMatrix, Float.valueOf(270.0f));
                    matrixCls.getMethod("postTranslate", float.class, float.class)
                            .invoke(rotMatrix, Float.valueOf(0.0f), Float.valueOf((float) HUD_WIDTH));
                } else {
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

        private static boolean hasHangulOrCjk(String s) {
            if (s == null) return false;
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) >= 0x1100) return true;
            }
            return false;
        }

        private static float drawTextSmart(
                Object c,
                String text,
                float x,
                float y,
                float sizePx,
                int color,
                boolean bold,
                Object alignObj,
                Object pPubBold,
                Object pPubMed,
                Object pSysBold,
                Object pSysMed,
                Method setColor,
                Method setTextSize,
                Method setTextAlign,
                Method measureText,
                Method drawText
        ) throws Exception {
            if (text == null || text.isEmpty()) return 0f;
            Object p = hasHangulOrCjk(text) ? (bold ? pSysBold : pSysMed) : (bold ? pPubBold : pPubMed);
            setTextSize.invoke(p, Float.valueOf(sizePx));
            setColor.invoke(p, Integer.valueOf(color));
            setTextAlign.invoke(p, alignObj);
            drawText.invoke(c, text, Float.valueOf(x), Float.valueOf(y), p);
            return ((Float) measureText.invoke(p, text)).floatValue();
        }

        private static void drawValuePlusUnit(
                Object c,
                String valStr,
                String unitStr,
                float x,
                float y,
                float valSize,
                int valColor,
                float unitSize,
                int unitColor,
                float gapPx,
                Object alignLeft,
                Object pPubBold,
                Object pPubMed,
                Object pSysBold,
                Object pSysMed,
                Method setColor,
                Method setTextSize,
                Method setTextAlign,
                Method measureText,
                Method drawText
        ) throws Exception {
            float w = drawTextSmart(c, valStr, x, y, valSize, valColor, true, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
            if (unitStr != null && !unitStr.isEmpty()) {
                drawTextSmart(c, unitStr, x + w + gapPx, y, unitSize, unitColor, true, alignLeft,
                        pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
            }
        }

        private static void copySpriteRect(
                Object c,
                Object srcBmp,
                Object sRect,
                Object dRect,
                Method setRect,
                Method drawBmpRect,
                int x0,
                int y0,
                int x1,
                int y1
        ) throws Exception {
            if (srcBmp == null) return;
            setRect.invoke(sRect, Integer.valueOf(x0), Integer.valueOf(y0), Integer.valueOf(x1), Integer.valueOf(y1));
            setRect.invoke(dRect, Integer.valueOf(x0), Integer.valueOf(y0), Integer.valueOf(x1), Integer.valueOf(y1));
            drawBmpRect.invoke(c, srcBmp, sRect, dRect, null);
        }

        private static void drawHudToCanvas(
                Object c,
                Object pFill,
                Object pStroke,
                Object pShader,
                Object pPubBold,
                Object pPubMed,
                Object pSysBold,
                Object pSysMed,
                Object rf,
                Object sRect,
                Object dRect,
                Class<?> bmpCls,
                Class<?> canvasCls,
                Class<?> paintCls,
                Class<?> alignCls,
                Class<?> rectFCls,
                Class<?> rectCls,
                Class<?> pathCls,
                TelemetrySnapshot s
        ) throws Exception {
            Method setColor = paintCls.getMethod("setColor", int.class);
            Method setStrokeW = paintCls.getMethod("setStrokeWidth", float.class);
            Method setTextSize = paintCls.getMethod("setTextSize", float.class);
            Method setTextAlign = paintCls.getMethod("setTextAlign", alignCls);
            Method measureText = paintCls.getMethod("measureText", String.class);
            Object alignLeft = alignCls.getField("LEFT").get(null);
            Object alignCenter = alignCls.getField("CENTER").get(null);
            Object alignRight = alignCls.getField("RIGHT").get(null);

            Method setRf = rectFCls.getMethod("set", float.class, float.class, float.class, float.class);
            Method setRect = rectCls.getMethod("set", int.class, int.class, int.class, int.class);
            Method drawRoundRect = canvasCls.getMethod("drawRoundRect", rectFCls, float.class, float.class, paintCls);
            Method drawRect = canvasCls.getMethod("drawRect", float.class, float.class, float.class, float.class, paintCls);
            Method drawOval = canvasCls.getMethod("drawOval", rectFCls, paintCls);
            Method drawLine = canvasCls.getMethod("drawLine", float.class, float.class, float.class, float.class, paintCls);
            Method drawText = canvasCls.getMethod("drawText", String.class, float.class, float.class, paintCls);
            Method drawPath = canvasCls.getMethod("drawPath", pathCls, paintCls);
            Method drawBmpXY = canvasCls.getMethod("drawBitmap", bmpCls, float.class, float.class, paintCls);
            Method drawBmpRect = canvasCls.getMethod("drawBitmap", bmpCls, rectCls, rectCls, paintCls);
            Method drawBmpRectF = canvasCls.getMethod("drawBitmap", bmpCls, rectCls, rectFCls, paintCls);

            // =================================================================
            // 1. BASE CHROMIUM LAYER (1920x462) FROM turzx_92_mockup.html
            // =================================================================
            if (bmpBaseShell != null) {
                drawBmpXY.invoke(c, bmpBaseShell, Float.valueOf(0f), Float.valueOf(0f), null);
            } else {
                setColor.invoke(pFill, Integer.valueOf(0xFF171719));
                drawRect.invoke(c, 0f, 0f, 1920f, 462f, pFill);
            }

            // =================================================================
            // 2. COLUMN 1: 2x2 EV INSTRUMENT CELLS + TPMS STRIP
            // =================================================================
            // Cell [1,1] 배터리 잔량
            double soc = s.getEffectiveSocPct();
            String socStr = (soc >= 0.0) ? String.format(Locale.US, "%.1f", soc) : "78.4";
            float socPct = (soc >= 0.0) ? (float) Math.max(0.0, Math.min(100.0, soc)) : 78.4f;
            drawValuePlusUnit(c, socStr, "%", 30f, 152f, 52f, 0xFFFFFFFF, 24f, 0xFF8A8C92, 4f,
                    alignLeft, pPubBold, pPubMed, pSysBold, pSysMed,
                    setColor, setTextSize, setTextAlign, measureText, drawText);
            drawBarFillOnly(c, rf, pFill, setRf, drawRoundRect, setColor, 30f, 208f, 163f, 8f, socPct, 0xFF4A8AD6);

            // Cell [1,2] 주행가능거리 + 전비
            String distUnit = s.distanceUnitMiles ? "mi" : "km";
            int effectiveDte = s.dteKm;
            if (!isValidDteValue(effectiveDte) && soc > 0.0) {
                double econ = (s.avgEconomyKmKwh > 1.0) ? s.avgEconomyKmKwh : 6.2;
                double kwh = (s.remainEnergyKwh > 0.5) ? s.remainEnergyKwh : (soc * 0.64);
                effectiveDte = (int) Math.round(kwh * econ);
            }
            String dteStr = isValidDteValue(effectiveDte) ? String.valueOf(effectiveDte) : "384";
            drawValuePlusUnit(c, dteStr, distUnit, 227f, 138f, 52f, 0xFFFFFFFF, 24f, 0xFF8A8C92, 4f,
                    alignLeft, pPubBold, pPubMed, pSysBold, pSysMed,
                    setColor, setTextSize, setTextAlign, measureText, drawText);

            String effStr = (s.avgEconomyKmKwh > 0.0)
                    ? String.format(Locale.US, "%.1f %s/kWh", s.avgEconomyKmKwh, distUnit)
                    : ("5.8 " + distUnit + "/kWh");
            drawTextSmart(c, effStr, 227f, 204f, 24f, 0xFFBDBDC0, false, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            // Cell [2,1] 실시간 전력 + 누적 에너지
            double totalKw = s.getTotalPowerKw();
            boolean hasLivePwr = s.hasPackCurrent || s.hasSubPower || Math.abs(totalKw) > 0.05;
            double dispKw = hasLivePwr ? totalKw : 18.4;
            boolean isRegen = dispKw < -0.05;
            String pwrStr = isRegen
                    ? String.format(Locale.US, "-%.1f", Math.abs(dispKw))
                    : String.format(Locale.US, "+%.1f", Math.abs(dispKw));
            drawValuePlusUnit(c, pwrStr, "kW", 30f, 338f, 44f, 0xFFFFFFFF, 24f, 0xFF8A8C92, 4f,
                    alignLeft, pPubBold, pPubMed, pSysBold, pSysMed,
                    setColor, setTextSize, setTextAlign, measureText, drawText);

            float pwrPct = (float) Math.min(100.0, Math.max(8.0, (Math.abs(dispKw) / 75.0) * 100.0));
            drawBarFillOnly(c, rf, pFill, setRf, drawRoundRect, setColor,
                    30f, 370.3f, 163f, 8f, pwrPct, isRegen ? 0xFF44A282 : 0xFF4A8AD6);

            double cumVal = (s.remainEnergyKwh > 0.0) ? s.remainEnergyKwh
                    : ((s.cumulDischargeKwh > 0.0) ? s.cumulDischargeKwh : 12.8);
            String cumStr = (cumVal >= 100.0)
                    ? String.format(Locale.US, "%.0f kWh", cumVal)
                    : String.format(Locale.US, "%.1f kWh", cumVal);
            drawTextSmart(c, cumStr, 78f, 424f, 24f, 0xFFBDBDC0, true, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            // Cell [2,2] 배터리 온도 + 최저~최고 온도
            Integer repTemp = s.getRepresentativeBattTempC();
            double batTemp = (repTemp != null) ? repTemp.doubleValue() : 24.0;
            String batTempStr = String.format(Locale.US, "%.1f", batTemp);
            drawValuePlusUnit(c, batTempStr, "°C", 227f, 354f, 44f, 0xFFFFFFFF, 24f, 0xFF8A8C92, 4f,
                    alignLeft, pPubBold, pPubMed, pSysBold, pSysMed,
                    setColor, setTextSize, setTextAlign, measureText, drawText);

            double minBatT = (s.battMinTempC != null) ? s.battMinTempC.doubleValue() : (batTemp - 1.0);
            double maxBatT = (s.battMaxTempC != null) ? s.battMaxTempC.doubleValue() : (batTemp + 1.0);
            String batMinMaxStr = String.format(Locale.US, "%.1f~%.1f°C", minBatT, maxBatT);
            drawTextSmart(c, batMinMaxStr, 227f, 424f, 24f, 0xFFBDBDC0, false, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            // Right TPMS Strip (x = 408..596)
            boolean warnFl = s.tpmsFlPsi > 0f && s.tpmsFlPsi < 31.0f;
            boolean warnFr = s.tpmsFrPsi > 0f && s.tpmsFrPsi < 31.0f;
            boolean warnRl = s.tpmsRlPsi > 0f && s.tpmsRlPsi < 31.0f;
            boolean warnRr = s.tpmsRrPsi > 0f && s.tpmsRrPsi < 31.0f;
            boolean anyTpmsWarn = warnFl || warnFr || warnRl || warnRr;
            if (anyTpmsWarn && bmpActiveShell != null) {
                copySpriteRect(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, 520, 24, 586, 62);
                if (warnFl) copySpriteRect(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, 480, 166, 491, 197);
                if (warnFr) copySpriteRect(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, 513, 166, 524, 197);
                if (warnRl) copySpriteRect(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, 480, 255, 491, 286);
                if (warnRr) copySpriteRect(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, 513, 255, 524, 286);
            }

            drawTextSmart(c, String.format(Locale.US, "%.0f", s.tpmsFlPsi), 424f, 170f, 36f,
                    warnFl ? 0xFFD9A441 : 0xFFFFFFFF, true, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
            drawTextSmart(c, String.format(Locale.US, "%.0f", s.tpmsFrPsi), 580f, 170f, 36f,
                    warnFr ? 0xFFD9A441 : 0xFFFFFFFF, true, alignRight,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
            drawTextSmart(c, String.format(Locale.US, "%.0f", s.tpmsRlPsi), 424f, 330f, 36f,
                    warnRl ? 0xFFD9A441 : 0xFFFFFFFF, true, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
            drawTextSmart(c, String.format(Locale.US, "%.0f", s.tpmsRrPsi), 580f, 330f, 36f,
                    warnRr ? 0xFFD9A441 : 0xFFFFFFFF, true, alignRight,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            String tpmsFootStr;
            if (anyTpmsWarn) {
                String lowPos = warnRr ? "RR" : (warnRl ? "RL" : (warnFr ? "FR" : "FL"));
                tpmsFootStr = lowPos + " 저압주의";
            } else {
                int tBase = (int) Math.round(s.outdoorTempC > -40.0 ? s.outdoorTempC + 8.0 : 26.0);
                tpmsFootStr = tBase + "~" + (tBase + 1) + "°C";
            }
            drawTextSmart(c, tpmsFootStr, 502f, 423f, 24f, 0xFF8A8C92, false, alignCenter,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            // =================================================================
            // 3. COLUMN 2: 1:1 3D PERSPECTIVE ADAS ROAD + CHROMIUM HUD OVERLAY
            // =================================================================
            drawCommaAdasRoad3D(c, rf, pFill, pStroke, pShader, pPubBold, pPubMed, pSysBold, pSysMed,
                    setRf, drawRoundRect, drawRect, drawOval, drawLine, drawPath, drawText,
                    setColor, setStrokeW, setTextSize, setTextAlign, measureText, alignCenter,
                    canvasCls, paintCls, pathCls, s);

            // Determine SDI mode: "section" (구간단속), "traffic" (신호·과속), or "none" (일반 제한속도)
            String sdiMode;
            if (s.carrotSdiType < 0 && s.carrotSdiDistM <= 0) {
                sdiMode = "none";
            } else if (s.carrotSdiType == 2 || s.carrotSdiType == 3 || s.carrotSdiType == 4 || s.carrotSdiDistM >= 800) {
                sdiMode = "section";
            } else if (s.carrotSdiType >= 0) {
                sdiMode = (s.carrotSdiType == 0) ? "section" : "traffic";
            } else {
                sdiMode = "section";
            }

            Object col2Overlay = "section".equals(sdiMode) ? bmpCol2Section
                    : ("traffic".equals(sdiMode) ? bmpCol2Traffic : bmpCol2None);
            if (col2Overlay != null) {
                drawBmpXY.invoke(c, col2Overlay, Float.valueOf(608f), Float.valueOf(12f), null);
            }

            // Speed-stamp numbers
            String gear = (s.gearPos != null && !s.gearPos.isEmpty()) ? s.gearPos : "D";
            drawTextSmart(c, gear, 655f, 69f, 30f, 0xFF4A8AD6, true, alignCenter,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            int egoSpd = (s.carrotSpeedKph > 0) ? s.carrotSpeedKph : (s.carrotConnected ? s.carrotSpeedKph : 78);
            drawTextSmart(c, String.valueOf(Math.max(0, egoSpd)), 732f, 88f, 60f, 0xFFFFFFFF, true, alignCenter,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            int cruiseSpd = (s.carrotCruiseKph > 0) ? s.carrotCruiseKph : 80;
            drawTextSmart(c, String.valueOf(cruiseSpd), 800f, 90f, 32f, 0xFF44A282, true, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            // SDI speed sign numbers (calibrated per mode to match Chromium circle center)
            int signLimit = (s.carrotSdiSpeedLimit > 0) ? s.carrotSdiSpeedLimit : 80;
            float limitNumY = "section".equals(sdiMode) ? 195f : ("traffic".equals(sdiMode) ? 183f : 158f);
            drawTextSmart(c, String.valueOf(signLimit), 680.8f, limitNumY, 36f, 0xFF131217, true, alignCenter,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            if (!"none".equals(sdiMode)) {
                int sdiDist = (s.carrotSdiDistM > 0) ? s.carrotSdiDistM : 2300;
                String sdiDistStr = (sdiDist >= 1000)
                        ? String.format(Locale.US, "%.1f km", sdiDist / 1000.0)
                        : (sdiDist + " m");
                float distY = "section".equals(sdiMode) ? 245f : 233f;
                drawTextSmart(c, sdiDistStr, 680.8f, distY, 24f, 0xFFFFFFFF, true, alignCenter,
                        pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
            }
            if ("section".equals(sdiMode)) {
                int avgSpd = (egoSpd > 10) ? egoSpd : signLimit;
                drawTextSmart(c, String.valueOf(avgSpd), 785f, 204f, 40f, 0xFF44A282, true, alignCenter,
                        pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
            }

            // TBT compact stamp on top-right
            int tbtDist = (s.carrotTbtDistM > 0) ? s.carrotTbtDistM : 280;
            String tbtDistStr = (tbtDist >= 1000)
                    ? String.format(Locale.US, "%.1fkm", tbtDist / 1000.0)
                    : (tbtDist + "m");
            drawTextSmart(c, tbtDistStr, 1034.2f, 72f, 40f, 0xFFFFFFFF, true, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            String roadName = (s.carrotTbtRoad != null && !s.carrotTbtRoad.isEmpty()) ? s.carrotTbtRoad : "판교JC";
            if (roadName.contains("·")) {
                String[] parts = roadName.split("·");
                roadName = parts[parts.length - 1].trim();
            }
            drawTextSmart(c, truncateText(roadName, 6), 984.2f, 115f, 24f, 0xFFBDBDC0, true, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            drawTextSmart(c, "+450m", 1154f, 114f, 24f, 0xFF4A8AD6, true, alignRight,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            // Bottom corner chips: "차선 98%" & "앞차 42m · +2.4°"
            drawTextSmart(c, s.carrotActive ? "99%" : "98%", 706f, 423f, 24f, 0xFFBDBDC0, false, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            float leadM = (s.hasLeadCar && s.leadDistM > 0f) ? s.leadDistM : 42f;
            float steerDeg = (s.pathPoly != null && s.pathPoly.length > 2) ? (s.pathPoly[2] * 5000f) : 2.4f;
            String leadText = String.format(Locale.US, "%.0fm", leadM);
            String steerText = String.format(Locale.US, " · %+.1f°", steerDeg);
            float wLead = drawTextSmart(c, leadText, 1017.2f, 421f, 24f, 0xFFFFFFFF, true, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
            drawTextSmart(c, steerText, 1017.2f + wLead, 421f, 24f, 0xFFBDBDC0, false, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            // =================================================================
            // 4. COLUMN 3 TOP: NOW PLAYING MEDIA (x = 1202..1908, y = 12..225)
            // =================================================================
            if (s.albumArtBitmap != null && bmpCls.isInstance(s.albumArtBitmap)) {
                try {
                    int bw = ((Integer) bmpCls.getMethod("getWidth").invoke(s.albumArtBitmap)).intValue();
                    int bh = ((Integer) bmpCls.getMethod("getHeight").invoke(s.albumArtBitmap)).intValue();
                    if (bw > 0 && bh > 0) {
                        setRf.invoke(rf, 1234f, 32f, 1406f, 204f);
                        drawBmpRectF.invoke(c, s.albumArtBitmap, null, rf, null);
                    }
                } catch (Throwable ignored) {
                }
            }

            boolean hasRealMedia = s.mediaTitle != null
                    && !s.mediaTitle.isEmpty()
                    && !"미디어 재생 대기 중".equals(s.mediaTitle);
            String mTitle = hasRealMedia ? s.mediaTitle : "Supernova";
            String mSub;
            if (hasRealMedia) {
                mSub = (s.mediaArtist != null && !s.mediaArtist.isEmpty()) ? s.mediaArtist : "";
                if (s.mediaAlbum != null && !s.mediaAlbum.isEmpty() && !s.mediaAlbum.equals(s.mediaArtist)) {
                    mSub = mSub.isEmpty() ? s.mediaAlbum : (mSub + " · " + s.mediaAlbum);
                }
                if (mSub.isEmpty()) mSub = "Bluetooth Audio";
            } else {
                mSub = "aespa · Armageddon";
            }

            drawTextSmart(c, truncateText(mTitle, 16), 1436f, 81f, 48f, 0xFFFFFFFF, true, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
            drawTextSmart(c, truncateText(mSub, 24), 1436f, 131f, 30f, 0xFFBDBDC0, false, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            int curSec = (s.mediaDurationSec > 0 || s.mediaPositionSec > 0) ? s.mediaPositionSec : 84;
            int durSec = (s.mediaDurationSec > 0) ? s.mediaDurationSec : 178;
            float mediaPct = (durSec > 0) ? Math.min(100f, (curSec * 100f) / durSec) : 47.2f;
            drawBarFillOnly(c, rf, pFill, setRf, drawRoundRect, setColor,
                    1436f, 154.5f, 388.9f, 10f, mediaPct, 0xFFFFFFFF);

            drawTextSmart(c, formatSecMinTwoDigit(curSec), 1436f, 196f, 26f, 0xFFFFFFFF, true, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
            drawTextSmart(c, formatSecMinTwoDigit(durSec), 1824.9f, 195f, 26f, 0xFF8A8C92, false, alignRight,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            // =================================================================
            // 5. COLUMN 3 BOTTOM: DATC CLIMATE (x = 1202..1908, y = 237..450)
            // =================================================================
            // Driver set temperature (1228, 297) + Indoor temperature after static "실내 " (1275.5, 337)
            String drNum = !s.hvacOn ? "OFF" : formatHvacTempNumberOnly(s.hvacDrTempC);
            String drDeg = (!s.hvacOn || "LO".equals(drNum) || "HI".equals(drNum)) ? "" : "°";
            int drColor = s.hvacOn ? 0xFFFFFFFF : 0xFF8A8C92;
            drawValuePlusUnit(c, drNum, drDeg, 1228f, 297f, 48f, drColor, 26f, 0xFF8A8C92, 1f,
                    alignLeft, pPubBold, pPubMed, pSysBold, pSysMed,
                    setColor, setTextSize, setTextAlign, measureText, drawText);

            String inStr = (s.inCarTempC > -50.0)
                    ? String.format(Locale.US, "%.1f°", s.inCarTempC)
                    : "22.5°";
            drawTextSmart(c, inStr, 1275.5f, 337f, 24f, 0xFFBDBDC0, true, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            // Passenger set temperature (right-aligned at 1882, 297) + Outdoor temperature after static "실외 "
            String psNum = !s.hvacOn ? "OFF" : (s.hvacDriverOnly ? "DRV" : formatHvacTempNumberOnly(s.hvacPsTempC));
            boolean psHasDeg = s.hvacOn && !s.hvacDriverOnly && !"LO".equals(psNum) && !"HI".equals(psNum);
            int psColor = (s.hvacOn && !s.hvacDriverOnly) ? 0xFFFFFFFF : 0xFF8A8C92;
            if (psHasDeg) {
                float wDeg = drawTextSmart(c, "°", 1882f, 297f, 26f, 0xFF8A8C92, true, alignRight,
                        pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
                drawTextSmart(c, psNum, 1882f - wDeg - 1f, 297f, 48f, psColor, true, alignRight,
                        pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
            } else {
                drawTextSmart(c, psNum, 1882f, 297f, 48f, psColor, true, alignRight,
                        pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
            }

            String outStr = (s.outdoorTempC > -50.0)
                    ? String.format(Locale.US, "%.1f°", s.outdoorTempC)
                    : "18.0°";
            drawTextSmart(c, outStr, 1882f, 337f, 24f, 0xFFBDBDC0, true, alignRight,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            // Center Airflow Arrows (composite active arrows from bmpActiveShell)
            int bm = s.hvacBlowerMode;
            boolean dirUp = s.hvacOn && (bm == 5 || bm == 4 || bm == 7 || bm == 9 || bm == 10 || s.hvacFrontDefog);
            boolean dirMid = s.hvacOn && (bm == 1 || bm == 2 || bm == 7 || bm == 9 || bm < 0);
            boolean dirDown = s.hvacOn && (bm == 2 || bm == 3 || bm == 4 || bm == 8 || bm == 9 || bm < 0);
            if (bmpActiveShell != null) {
                if (dirUp) {
                    copySpriteRect(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, 1396, 262, 1425, 287);
                    copySpriteRect(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, 1425, 262, 1445, 282);
                }
                if (dirMid) {
                    copySpriteRect(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, 1396, 288, 1425, 302);
                    copySpriteRect(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, 1425, 282, 1446, 305);
                }
                if (dirDown) {
                    copySpriteRect(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, 1396, 305, 1445, 331);
                }
            }

            // Center Fan Step & 7-Bar Rail
            int fanStep = !s.hvacOn ? 0 : ((s.hvacBlowerStep >= 0) ? Math.min(7, s.hvacBlowerStep) : 3);
            if (fanStep > 0) {
                drawTextSmart(c, String.valueOf(fanStep), 1687f, 287f, 32f, 0xFFFFFFFF, true, alignRight,
                        pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
                if (bmpActiveShell != null) {
                    int barRightX = Math.round(1539.4f + (fanStep - 1) * 26.3f + 20f);
                    copySpriteRect(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, 1538, 298, barRightX, 336);
                }
            } else {
                if (bmpActiveShell != null) {
                    copySpriteRect(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, 1650, 256, 1720, 292);
                }
                drawTextSmart(c, "OFF", 1715f, 287f, 32f, 0xFF8A8C92, true, alignRight,
                        pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
            }

            // Bottom 5 HVAC Dock Buttons (composite active buttons from bmpActiveShell)
            if (bmpActiveShell != null) {
                if (s.hvacOn && s.hvacAuto) {
                    copySpriteRect(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, 1226, 374, 1353, 436);
                }
                if (s.hvacOn && s.hvacAc) {
                    copySpriteRect(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, 1359, 374, 1486, 436);
                }
                if (s.hvacOn && s.hvacHeat) {
                    copySpriteRect(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, 1492, 374, 1618, 436);
                }
                if (s.hvacRecirc) {
                    copySpriteRect(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, 1624, 374, 1751, 436);
                }
                if (s.hvacOn && s.hvacSync) {
                    copySpriteRect(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, 1757, 374, 1884, 436);
                }
            }
        }

        /**
         * 1:1 port of renderCommaAdasCanvas() from turzx_92_mockup.html (lines 2264-2750).
         * Renders into Column 2 at (ox=610, oy=14, W=578, H=434).
         */
        private static void drawCommaAdasRoad3D(
                Object c,
                Object rf,
                Object pFill,
                Object pStroke,
                Object pShader,
                Object pPubBold,
                Object pPubMed,
                Object pSysBold,
                Object pSysMed,
                Method setRf,
                Method drawRoundRect,
                Method drawRect,
                Method drawOval,
                Method drawLine,
                Method drawPath,
                Method drawText,
                Method setColor,
                Method setStrokeW,
                Method setTextSize,
                Method setTextAlign,
                Method measureText,
                Object alignCenter,
                Class<?> canvasCls,
                Class<?> paintCls,
                Class<?> pathCls,
                TelemetrySnapshot s
        ) throws Exception {
            Method saveM = canvasCls.getMethod("save");
            Method restoreToCountM = canvasCls.getMethod("restoreToCount", int.class);
            Method clipRectM = canvasCls.getMethod("clipRect", float.class, float.class, float.class, float.class);

            Class<?> shaderCls = Class.forName("android.graphics.Shader");
            Class<?> tileCls = Class.forName("android.graphics.Shader$TileMode");
            Class<?> lgCls = Class.forName("android.graphics.LinearGradient");
            Class<?> rgCls = Class.forName("android.graphics.RadialGradient");
            Object clampMode = tileCls.getField("CLAMP").get(null);
            Method setShaderM = paintCls.getMethod("setShader", shaderCls);
            Constructor<?> lgTwoCtor = lgCls.getConstructor(
                    float.class, float.class, float.class, float.class, int.class, int.class, tileCls);
            Constructor<?> lgMultiCtor = lgCls.getConstructor(
                    float.class, float.class, float.class, float.class, int[].class, float[].class, tileCls);
            Constructor<?> rgMultiCtor = rgCls.getConstructor(
                    float.class, float.class, float.class, int[].class, float[].class, tileCls);

            Method resetP = pathCls.getMethod("reset");
            Method moveTo = pathCls.getMethod("moveTo", float.class, float.class);
            Method lineTo = pathCls.getMethod("lineTo", float.class, float.class);
            Method quadTo = pathCls.getMethod("quadTo", float.class, float.class, float.class, float.class);
            Method closeP = pathCls.getMethod("close");
            Object path = pathCls.getConstructor().newInstance();

            int saveCount = ((Integer) saveM.invoke(c)).intValue();
            clipRectM.invoke(c, 609f, 13f, 1189f, 449f);

            float ox = 610f;
            float oy = 14f;
            float W = 578f;
            float H = 434f;
            float horizonY = 130f;

            // 1. Sky, Atmospheric Horizon Glow & Ground Plane Base
            Object skyGrad = lgTwoCtor.newInstance(
                    ox, oy, ox, oy + horizonY, Integer.valueOf(0xFF07080C), Integer.valueOf(0xFF0F131D), clampMode);
            setShaderM.invoke(pShader, skyGrad);
            drawRect.invoke(c, ox, oy, ox + W, oy + horizonY, pShader);

            Object groundGrad = lgMultiCtor.newInstance(
                    ox, oy + horizonY, ox, oy + H,
                    new int[]{0xFF0E1118, 0xFF11131A, 0xFF0B0C10},
                    new float[]{0f, 0.45f, 1f},
                    clampMode);
            setShaderM.invoke(pShader, groundGrad);
            drawRect.invoke(c, ox, oy + horizonY, ox + W, oy + H, pShader);

            // Camera intrinsics in 578x434 pixel space
            float u0 = W * 0.565f;
            float v0 = horizonY;
            float fx = 395f;
            float fy = 950f;
            float camH = 1.35f;

            float leadDistM = (s.hasLeadCar && s.leadDistM > 0f) ? s.leadDistM : 34.0f;
            float visualLeadX = 6.2f + (float) Math.pow(Math.max(6.0, leadDistM), 0.62) * 1.05f;

            float curveC2 = 0.00048f;
            float curveC1 = 0.008f;
            if (s.pathPoly != null && s.pathPoly.length >= 3) {
                if (Math.abs(s.pathPoly[1]) > 0.00001f || Math.abs(s.pathPoly[2]) > 0.00001f) {
                    curveC1 = Math.max(-0.04f, Math.min(0.04f, s.pathPoly[1]));
                    curveC2 = Math.max(-0.0025f, Math.min(0.0025f, s.pathPoly[2]));
                }
            }

            // Subtle radial horizon glow around vanishing point
            float vpY75 = roadCenterLat(75f, curveC1, curveC2);
            float vpU = projU(ox, u0, fx, 75f, vpY75);
            Object hGlow = rgMultiCtor.newInstance(
                    vpU, oy + horizonY + 12f, 310f,
                    new int[]{0x294A8AD6, 0x0F44A282, 0x000B0C10},
                    new float[]{0f, 0.5f, 1f},
                    clampMode);
            setShaderM.invoke(pShader, hGlow);
            drawRect.invoke(c, ox, oy, ox + W, oy + H, pShader);

            // 2. Horizon Azimuth Rule & Subtle Sensor Ticks
            setColor.invoke(pStroke, Integer.valueOf(0xFF1F2430));
            setStrokeW.invoke(pStroke, 1.5f);
            drawLine.invoke(c, ox, oy + horizonY, ox + W, oy + horizonY, pStroke);

            for (int lat = -12; lat <= 12; lat += 2) {
                float px = projU(ox, u0, fx, 60f, (float) lat);
                setColor.invoke(pStroke, Integer.valueOf(lat == 0 ? 0xFF3A4152 : 0xFF1C2029));
                drawLine.invoke(c, px, oy + horizonY - 4f, px, oy + horizonY + 4f, pStroke);
            }

            // 3. Multi-Lane Asphalt Roadbed Surface (3 Lanes: Y = -5.4m to +5.4m, 38 steps)
            float[] xSteps = new float[39];
            for (int i = 0; i <= 38; i++) {
                float t = i / 38.0f;
                xSteps[i] = 3.85f + 82.0f * (t * t);
            }

            Object roadSurfGrad = lgMultiCtor.newInstance(
                    ox, oy + horizonY, ox, oy + H,
                    new int[]{0xFF12151E, 0xFF161922, 0xFF101218},
                    new float[]{0f, 0.4f, 1f},
                    clampMode);
            setShaderM.invoke(pShader, roadSurfGrad);
            resetP.invoke(path);
            for (int i = 0; i < xSteps.length; i++) {
                float x = xSteps[i];
                float cy = roadCenterLat(x, curveC1, curveC2) - 5.4f;
                float pu = projU(ox, u0, fx, x, cy);
                float pv = projV(oy, v0, fy, camH, x, 0f);
                if (i == 0) moveTo.invoke(path, pu, pv);
                else lineTo.invoke(path, pu, pv);
            }
            for (int i = xSteps.length - 1; i >= 0; i--) {
                float x = xSteps[i];
                float cy = roadCenterLat(x, curveC1, curveC2) + 5.4f;
                float pu = projU(ox, u0, fx, x, cy);
                float pv = projV(oy, v0, fy, camH, x, 0f);
                lineTo.invoke(path, pu, pv);
            }
            closeP.invoke(path);
            drawPath.invoke(c, path, pShader);

            // Ego Lane Subtle Surface Contrast (Y = -1.82m to +1.82m)
            Object egoSurfGrad = lgMultiCtor.newInstance(
                    ox, oy + horizonY, ox, oy + H,
                    new int[]{0xFF161A24, 0xFF1B202C, 0xFF141821},
                    new float[]{0f, 0.5f, 1f},
                    clampMode);
            setShaderM.invoke(pShader, egoSurfGrad);
            resetP.invoke(path);
            for (int i = 0; i < xSteps.length; i++) {
                float x = xSteps[i];
                float cy = roadCenterLat(x, curveC1, curveC2) - 1.82f;
                float pu = projU(ox, u0, fx, x, cy);
                float pv = projV(oy, v0, fy, camH, x, 0f);
                if (i == 0) moveTo.invoke(path, pu, pv);
                else lineTo.invoke(path, pu, pv);
            }
            for (int i = xSteps.length - 1; i >= 0; i--) {
                float x = xSteps[i];
                float cy = roadCenterLat(x, curveC1, curveC2) + 1.82f;
                float pu = projU(ox, u0, fx, x, cy);
                float pv = projV(oy, v0, fy, camH, x, 0f);
                lineTo.invoke(path, pu, pv);
            }
            closeP.invoke(path);
            drawPath.invoke(c, path, pShader);

            // 4. Perspective Distance Grid Lines on Roadbed
            float[] gridDists = new float[]{5.0f, 6.8f, 9.5f, 13.5f, 19.5f, 29.0f, 45.0f};
            setStrokeW.invoke(pStroke, 1.0f);
            for (int i = 0; i < gridDists.length; i++) {
                float dist = gridDists[i];
                float alpha = Math.min(0.09f, Math.max(0.025f, 0.45f / dist));
                setColor.invoke(pStroke, Integer.valueOf(argbFromAlphaRgb(alpha, 255, 255, 255)));
                float cLat = roadCenterLat(dist, curveC1, curveC2);
                float uL = projU(ox, u0, fx, dist, cLat - 5.4f);
                float uR = projU(ox, u0, fx, dist, cLat + 5.4f);
                float vG = projV(oy, v0, fy, camH, dist, 0f);
                drawLine.invoke(c, uL, vG, uR, vG, pStroke);
            }

            // 5. Road Shoulder Edges (-5.4m, +5.4m) & Adjacent Dashed Lane Lines (-3.6m, +3.6m)
            drawTaperedStrip3D(c, path, pFill, resetP, moveTo, lineTo, closeP, setColor, drawPath,
                    xSteps, ox, oy, u0, v0, fx, fy, camH, curveC1, curveC2,
                    -5.4f, 0.06f, 74, 82, 98, 0.65f, false, roadPhase);
            drawTaperedStrip3D(c, path, pFill, resetP, moveTo, lineTo, closeP, setColor, drawPath,
                    xSteps, ox, oy, u0, v0, fx, fy, camH, curveC1, curveC2,
                    5.4f, 0.06f, 74, 82, 98, 0.65f, false, roadPhase);
            drawTaperedStrip3D(c, path, pFill, resetP, moveTo, lineTo, closeP, setColor, drawPath,
                    xSteps, ox, oy, u0, v0, fx, fy, camH, curveC1, curveC2,
                    -3.6f, 0.055f, 148, 163, 184, 0.38f, true, roadPhase);
            drawTaperedStrip3D(c, path, pFill, resetP, moveTo, lineTo, closeP, setColor, drawPath,
                    xSteps, ox, oy, u0, v0, fx, fy, camH, curveC1, curveC2,
                    3.6f, 0.055f, 148, 163, 184, 0.38f, true, roadPhase);

            // 6. Comma modelV2 Predicted Steering Trajectory Carpet (34 steps)
            float pathMaxX = Math.max(6.8f, visualLeadX - 0.6f);
            float pathHalfW = 0.76f;
            int pathSteps = 34;
            boolean isCloseLead = leadDistM <= 18f;
            boolean isCriticalLead = leadDistM <= 12f;
            int carpR = isCriticalLead ? 229 : (isCloseLead ? 217 : 68);
            int carpG = isCriticalLead ? 86 : (isCloseLead ? 164 : 162);
            int carpB = isCriticalLead ? 111 : (isCloseLead ? 65 : 130);

            for (int i = 0; i < pathSteps; i++) {
                float t0 = i / (float) pathSteps;
                float t1 = (i + 1) / (float) pathSteps;
                float x0 = 3.85f + (pathMaxX - 3.85f) * (t0 * t0);
                float x1 = 3.85f + (pathMaxX - 3.85f) * (t1 * t1);
                float w0 = pathHalfW * (1f - t0 * 0.16f);
                float w1 = pathHalfW * (1f - t1 * 0.16f);
                float c0 = roadCenterLat(x0, curveC1, curveC2);
                float c1 = roadCenterLat(x1, curveC1, curveC2);

                float v0y = projV(oy, v0, fy, camH, x0, 0f);
                float v1y = projV(oy, v0, fy, camH, x1, 0f);
                resetP.invoke(path);
                moveTo.invoke(path, projU(ox, u0, fx, x0, c0 - w0), v0y);
                lineTo.invoke(path, projU(ox, u0, fx, x0, c0 + w0), v0y);
                lineTo.invoke(path, projU(ox, u0, fx, x1, c1 + w1), v1y);
                lineTo.invoke(path, projU(ox, u0, fx, x1, c1 - w1), v1y);
                closeP.invoke(path);

                float alpha = (1f - t0 * 0.65f) * 0.38f;
                setColor.invoke(pFill, Integer.valueOf(argbFromAlphaRgb(alpha, carpR, carpG, carpB)));
                drawPath.invoke(c, path, pFill);
            }

            // Trajectory Left & Right 3D Edge Rails
            int railR = isCriticalLead ? 255 : (isCloseLead ? 245 : 95);
            int railG = isCriticalLead ? 133 : (isCloseLead ? 194 : 195);
            int railB = isCriticalLead ? 152 : (isCloseLead ? 96 : 156);
            int[] sides = new int[]{-1, 1};
            for (int sIdx = 0; sIdx < sides.length; sIdx++) {
                int side = sides[sIdx];
                for (int i = 0; i < pathSteps; i++) {
                    float t0 = i / (float) pathSteps;
                    float t1 = (i + 1) / (float) pathSteps;
                    float x0 = 3.85f + (pathMaxX - 3.85f) * (t0 * t0);
                    float x1 = 3.85f + (pathMaxX - 3.85f) * (t1 * t1);
                    float w0 = pathHalfW * (1f - t0 * 0.16f);
                    float w1 = pathHalfW * (1f - t1 * 0.16f);
                    float lat0 = roadCenterLat(x0, curveC1, curveC2) + side * w0;
                    float lat1 = roadCenterLat(x1, curveC1, curveC2) + side * w1;
                    float wRail = 0.045f;

                    float v0y = projV(oy, v0, fy, camH, x0, 0f);
                    float v1y = projV(oy, v0, fy, camH, x1, 0f);
                    resetP.invoke(path);
                    moveTo.invoke(path, projU(ox, u0, fx, x0, lat0 - wRail), v0y);
                    lineTo.invoke(path, projU(ox, u0, fx, x0, lat0 + wRail), v0y);
                    lineTo.invoke(path, projU(ox, u0, fx, x1, lat1 + wRail), v1y);
                    lineTo.invoke(path, projU(ox, u0, fx, x1, lat1 - wRail), v1y);
                    closeP.invoke(path);

                    float fade = Math.max(0.22f, 1f - t0 * 0.72f);
                    setColor.invoke(pFill, Integer.valueOf(argbFromAlphaRgb(fade, railR, railG, railB)));
                    drawPath.invoke(c, path, pFill);
                }
            }

            // Trajectory Internal Chevron Rungs
            float rungSpacing = 2.4f;
            int chevR = isCriticalLead ? 255 : (isCloseLead ? 253 : 167);
            int chevG = isCriticalLead ? 180 : (isCloseLead ? 224 : 243);
            int chevB = isCriticalLead ? 192 : (isCloseLead ? 142 : 208);
            for (float d = 4.4f; d < pathMaxX - 0.8f; d += rungSpacing) {
                float xRung = d - ((roadPhase * 0.45f) % rungSpacing);
                if (xRung < 4.0f || xRung > pathMaxX - 0.7f) continue;
                float t = (xRung - 3.85f) / Math.max(1.0f, pathMaxX - 3.85f);
                float w = pathHalfW * (1f - t * 0.16f) * 0.76f;
                float cMid = roadCenterLat(xRung, curveC1, curveC2);
                float cTip = roadCenterLat(xRung + 0.55f, curveC1, curveC2);

                float uL = projU(ox, u0, fx, xRung, cMid - w);
                float uR = projU(ox, u0, fx, xRung, cMid + w);
                float vLR = projV(oy, v0, fy, camH, xRung, 0f);
                float uM = projU(ox, u0, fx, xRung + 0.55f, cTip);
                float vM = projV(oy, v0, fy, camH, xRung + 0.55f, 0f);

                float fade = Math.max(0.18f, 1f - t * 0.75f);
                setColor.invoke(pStroke, Integer.valueOf(argbFromAlphaRgb(fade * 0.63f, chevR, chevG, chevB)));
                setStrokeW.invoke(pStroke, Math.max(1.5f, 3.6f * fade));
                drawLine.invoke(c, uL, vLR, uM, vM, pStroke);
                drawLine.invoke(c, uM, vM, uR, vLR, pStroke);
            }

            // Safe Following Distance Threshold Bar & Ego Sensor Shield Arc
            float safeBufferX = Math.min(pathMaxX - 0.3f, 6.4f);
            float sbC = roadCenterLat(safeBufferX, curveC1, curveC2);
            float sbV = projV(oy, v0, fy, camH, safeBufferX, 0f);
            setColor.invoke(pStroke, Integer.valueOf(isCriticalLead ? 0xC7E5566F : 0x734A8AD6));
            setStrokeW.invoke(pStroke, 2.0f);
            drawLine.invoke(c, projU(ox, u0, fx, safeBufferX, sbC - 0.92f), sbV,
                    projU(ox, u0, fx, safeBufferX, sbC + 0.92f), sbV, pStroke);

            float egoArcC = roadCenterLat(4.15f, curveC1, curveC2);
            float egoArcV = projV(oy, v0, fy, camH, 4.15f, 0f);
            float egoArcMV = projV(oy, v0, fy, camH, 4.42f, 0f);
            resetP.invoke(path);
            moveTo.invoke(path, projU(ox, u0, fx, 4.15f, egoArcC - 1.28f), egoArcV);
            quadTo.invoke(path, projU(ox, u0, fx, 4.42f, egoArcC), egoArcMV - 6f,
                    projU(ox, u0, fx, 4.15f, egoArcC + 1.28f), egoArcV);
            setColor.invoke(pStroke, Integer.valueOf(0x8578BCFF));
            setStrokeW.invoke(pStroke, 2.2f);
            drawPath.invoke(c, path, pStroke);

            // 7. Ego Left & Right Recognized Lane Lines (-1.82m, +1.82m) with Outer Halo + Crisp Core
            drawTaperedStrip3D(c, path, pFill, resetP, moveTo, lineTo, closeP, setColor, drawPath,
                    xSteps, ox, oy, u0, v0, fx, fy, camH, curveC1, curveC2,
                    -1.82f, 0.19f, 74, 138, 214, 0.25f, false, roadPhase);
            drawTaperedStrip3D(c, path, pFill, resetP, moveTo, lineTo, closeP, setColor, drawPath,
                    xSteps, ox, oy, u0, v0, fx, fy, camH, curveC1, curveC2,
                    1.82f, 0.19f, 74, 138, 214, 0.25f, false, roadPhase);
            drawTaperedStrip3D(c, path, pFill, resetP, moveTo, lineTo, closeP, setColor, drawPath,
                    xSteps, ox, oy, u0, v0, fx, fy, camH, curveC1, curveC2,
                    -1.82f, 0.068f, 120, 188, 255, 0.96f, false, roadPhase);
            drawTaperedStrip3D(c, path, pFill, resetP, moveTo, lineTo, closeP, setColor, drawPath,
                    xSteps, ox, oy, u0, v0, fx, fy, camH, curveC1, curveC2,
                    1.82f, 0.068f, 120, 188, 255, 0.96f, false, roadPhase);

            // 7b. Surround Traffic: 3D Ghost Vehicle in Right Adjacent Lane
            float ghostX = 16.5f;
            float ghostLat = roadCenterLat(ghostX, curveC1, curveC2) + 3.6f;
            float gcx = projU(ox, u0, fx, ghostX, ghostLat);
            float gby = projV(oy, v0, fy, camH, ghostX, 0f);
            float gLeft = projU(ox, u0, fx, ghostX, ghostLat - 0.88f);
            float gRight = projU(ox, u0, fx, ghostX, ghostLat + 0.88f);
            float gW = Math.abs(gRight - gLeft);
            float gH = gW * 0.66f;
            if (gW > 20f) {
                float rx = gW * 0.52f;
                float ry = Math.max(3f, gH * 0.12f);
                setRf.invoke(rf, gcx - rx, gby + 2f - ry, gcx + rx, gby + 2f + ry);
                setColor.invoke(pFill, Integer.valueOf(0x73000000));
                drawOval.invoke(c, rf, pFill);

                resetP.invoke(path);
                moveTo.invoke(path, gcx - gW * 0.48f, gby - 3f);
                lineTo.invoke(path, gcx - gW * 0.48f, gby - gH * 0.58f);
                lineTo.invoke(path, gcx - gW * 0.34f, gby - gH * 0.94f);
                lineTo.invoke(path, gcx + gW * 0.34f, gby - gH * 0.94f);
                lineTo.invoke(path, gcx + gW * 0.48f, gby - gH * 0.58f);
                lineTo.invoke(path, gcx + gW * 0.48f, gby - 3f);
                closeP.invoke(path);
                setColor.invoke(pFill, Integer.valueOf(0x9E222732));
                drawPath.invoke(c, path, pFill);
                setColor.invoke(pStroke, Integer.valueOf(0x618A94A6));
                setStrokeW.invoke(pStroke, 1.2f);
                drawPath.invoke(c, path, pStroke);

                setColor.invoke(pFill, Integer.valueOf(0x7AE5566F));
                float tlY = gby - gH * 0.52f;
                float tlH = Math.max(2f, gH * 0.08f);
                drawRect.invoke(c, gcx - gW * 0.42f, tlY, gcx - gW * 0.20f, tlY + tlH, pFill);
                drawRect.invoke(c, gcx + gW * 0.20f, tlY, gcx + gW * 0.42f, tlY + tlH, pFill);
            }

            // 8. 3D-Projected Lead Vehicle Model + CarrotPilot Radar Lock
            float leadX = visualLeadX;
            float leadLat = roadCenterLat(leadX, curveC1, curveC2);
            float cx = projU(ox, u0, fx, leadX, leadLat);
            float by = projV(oy, v0, fy, camH, leadX, 0f);
            float pLeftEdge = projU(ox, u0, fx, leadX, leadLat - 0.96f);
            float pRightEdge = projU(ox, u0, fx, leadX, leadLat + 0.96f);
            float rawCarW = Math.abs(pRightEdge - pLeftEdge);
            float carW = Math.max(72f, Math.min(146f, rawCarW));
            float carH = carW * 0.68f;

            // (a) Ground Occlusion Shadow
            float sRx = carW * 0.56f;
            float sRy = Math.max(5f, carH * 0.14f);
            setRf.invoke(rf, cx - sRx, by + 2f - sRy, cx + sRx, by + 2f + sRy);
            setColor.invoke(pFill, Integer.valueOf(0xB8000000));
            drawOval.invoke(c, rf, pFill);

            // (b) Left & Right Rear Tires
            float tireW = Math.max(9f, carW * 0.17f);
            float tireH = Math.max(11f, carH * 0.28f);
            drawCard(c, rf, pFill, pStroke, setRf, drawRoundRect, setColor, setStrokeW,
                    cx - carW * 0.46f, by - tireH, cx - carW * 0.46f + tireW, by, 2.5f, 0xFF090A0F, 0xFF2F3340, 1.2f);
            drawCard(c, rf, pFill, pStroke, setRf, drawRoundRect, setColor, setStrokeW,
                    cx + carW * 0.46f - tireW, by - tireH, cx + carW * 0.46f, by, 2.5f, 0xFF090A0F, 0xFF2F3340, 1.2f);

            // (c) Main Lower Vehicle Body
            float bodyTopY = by - carH * 0.62f;
            float roofTopY = by - carH * 0.96f;
            Object bodyGrad = lgMultiCtor.newInstance(
                    cx, roofTopY, cx, by,
                    new int[]{0xFF2D3240, 0xFF1F232E, 0xFF12141C},
                    new float[]{0f, 0.45f, 1f},
                    clampMode);
            setShaderM.invoke(pShader, bodyGrad);
            resetP.invoke(path);
            moveTo.invoke(path, cx - carW * 0.48f, by - tireH * 0.42f);
            lineTo.invoke(path, cx - carW * 0.49f, bodyTopY);
            lineTo.invoke(path, cx - carW * 0.34f, roofTopY);
            lineTo.invoke(path, cx + carW * 0.34f, roofTopY);
            lineTo.invoke(path, cx + carW * 0.49f, bodyTopY);
            lineTo.invoke(path, cx + carW * 0.48f, by - tireH * 0.42f);
            closeP.invoke(path);
            drawPath.invoke(c, path, pShader);
            setColor.invoke(pStroke, Integer.valueOf(0xFF525868));
            setStrokeW.invoke(pStroke, 1.6f);
            drawPath.invoke(c, path, pStroke);

            // (d) Rear Windshield Glass
            Object glassGrad = lgTwoCtor.newInstance(
                    cx, roofTopY, cx, bodyTopY, Integer.valueOf(0xFF1E293B), Integer.valueOf(0xFF0F1117), clampMode);
            setShaderM.invoke(pShader, glassGrad);
            resetP.invoke(path);
            moveTo.invoke(path, cx - carW * 0.38f, bodyTopY - 1f);
            lineTo.invoke(path, cx - carW * 0.29f, roofTopY + carH * 0.07f);
            lineTo.invoke(path, cx + carW * 0.29f, roofTopY + carH * 0.07f);
            lineTo.invoke(path, cx + carW * 0.38f, bodyTopY - 1f);
            closeP.invoke(path);
            drawPath.invoke(c, path, pShader);
            setColor.invoke(pStroke, Integer.valueOf(0xFF383D4A));
            setStrokeW.invoke(pStroke, 1.0f);
            drawPath.invoke(c, path, pStroke);

            // (e) Lower Rear Metallic Diffuser
            drawCard(c, rf, pFill, pStroke, setRf, drawRoundRect, setColor, setStrokeW,
                    cx - carW * 0.38f, by - carH * 0.28f, cx + carW * 0.38f, by - carH * 0.14f,
                    2f, 0xFF161821, 0xFF2E323D, 1.0f);

            // (f) Full-Width Parametric LED Tail Lightbar + Soft Brake Glow
            float lampY = bodyTopY + carH * 0.04f;
            float lampH = Math.max(4.5f, carH * 0.095f);
            Object tailGlow = rgMultiCtor.newInstance(
                    cx, lampY + lampH * 0.5f, carW * 0.54f,
                    new int[]{0x73E5566F, 0x00E5566F},
                    new float[]{0.15f, 1.0f},
                    clampMode);
            setShaderM.invoke(pShader, tailGlow);
            drawRect.invoke(c, cx - carW * 0.55f, lampY - lampH * 1.5f, cx + carW * 0.55f, lampY + lampH * 2.5f, pShader);

            setColor.invoke(pFill, Integer.valueOf(0xFFE5566F));
            setRf.invoke(rf, cx - carW * 0.45f, lampY, cx + carW * 0.45f, lampY + lampH);
            drawRoundRect.invoke(c, rf, 2f, 2f, pFill);

            setColor.invoke(pFill, Integer.valueOf(0xFFFFB3C0));
            float coreH = Math.max(2f, lampH - 2f);
            drawRect.invoke(c, cx - carW * 0.43f, lampY + 1f, cx - carW * 0.21f, lampY + 1f + coreH, pFill);
            drawRect.invoke(c, cx + carW * 0.21f, lampY + 1f, cx + carW * 0.43f, lampY + 1f + coreH, pFill);

            // (g) Radar Lock Chevron & Target Brackets
            int radarColor = (leadDistM <= 20f) ? 0xFFE5566F : 0xFFD9A441;
            float bx0 = cx - carW * 0.55f;
            float bx1 = cx + carW * 0.55f;
            float by0 = roofTopY - 5f;
            float by1 = by + 3f;
            float brk = Math.max(7f, carW * 0.11f);

            setColor.invoke(pStroke, Integer.valueOf(radarColor));
            setStrokeW.invoke(pStroke, 2.0f);
            drawLine.invoke(c, bx0, by0 + brk, bx0, by0, pStroke);
            drawLine.invoke(c, bx0, by0, bx0 + brk, by0, pStroke);
            drawLine.invoke(c, bx1 - brk, by0, bx1, by0, pStroke);
            drawLine.invoke(c, bx1, by0, bx1, by0 + brk, pStroke);
            drawLine.invoke(c, bx0, by1 - brk, bx0, by1, pStroke);
            drawLine.invoke(c, bx0, by1, bx0 + brk, by1, pStroke);
            drawLine.invoke(c, bx1 - brk, by1, bx1, by1, pStroke);
            drawLine.invoke(c, bx1, by1, bx1, by1 - brk, pStroke);

            float chevY = by + 8f;
            resetP.invoke(path);
            moveTo.invoke(path, cx, chevY);
            lineTo.invoke(path, cx - 12f, chevY + 10f);
            lineTo.invoke(path, cx - 5f, chevY + 10f);
            lineTo.invoke(path, cx, chevY + 5f);
            lineTo.invoke(path, cx + 5f, chevY + 10f);
            lineTo.invoke(path, cx + 12f, chevY + 10f);
            closeP.invoke(path);
            setColor.invoke(pFill, Integer.valueOf(radarColor));
            drawPath.invoke(c, path, pFill);

            float tagW = 62f;
            float tagH = 24f;
            float tagX = cx - tagW * 0.5f;
            float tagY = Math.max(oy + 8f, by0 - tagH - 5f);
            drawCard(c, rf, pFill, pStroke, setRf, drawRoundRect, setColor, setStrokeW,
                    tagX, tagY, tagX + tagW, tagY + tagH, 5f, 0xEB131217, radarColor, 1.6f);
            float dispLead = (s.hasLeadCar && s.leadDistM > 0f && s.carrotConnected) ? s.leadDistM : 42f;
            drawTextSmart(c, String.format(Locale.US, "%.0fm", dispLead), cx, tagY + 17.5f, 16f, 0xFFFFFFFF, true, alignCenter,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            restoreToCountM.invoke(c, Integer.valueOf(saveCount));
        }

        private static float roadCenterLat(float x, float curveC1, float curveC2) {
            float dx = Math.max(0f, x - 4.0f);
            return curveC2 * (dx * dx) * 2.2f + curveC1 * dx;
        }

        private static float projU(float ox, float u0, float fx, float x, float y) {
            float safeX = Math.max(2.5f, x);
            return ox + u0 + fx * (y / safeX);
        }

        private static float projV(float oy, float v0, float fy, float camH, float x, float z) {
            float safeX = Math.max(2.5f, x);
            return oy + v0 + fy * ((camH - z) / safeX);
        }

        private static int argbFromAlphaRgb(float alpha01, int r, int g, int b) {
            int a = Math.max(0, Math.min(255, Math.round(alpha01 * 255f)));
            return (a << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
        }

        private static void drawTaperedStrip3D(
                Object c,
                Object path,
                Object pFill,
                Method resetP,
                Method moveTo,
                Method lineTo,
                Method closeP,
                Method setColor,
                Method drawPath,
                float[] xSteps,
                float ox,
                float oy,
                float u0,
                float v0,
                float fx,
                float fy,
                float camH,
                float curveC1,
                float curveC2,
                float latOffset,
                float halfWidthM,
                int r,
                int g,
                int b,
                float baseAlpha,
                boolean dashed,
                float phase
        ) throws Exception {
            for (int i = 0; i < xSteps.length - 1; i++) {
                float x0 = xSteps[i];
                float x1 = xSteps[i + 1];
                if (dashed) {
                    int seg = (int) Math.floor((x0 + phase) / 3.0f);
                    if ((seg & 1) != 0) continue;
                }
                float c0 = roadCenterLat(x0, curveC1, curveC2) + latOffset;
                float c1 = roadCenterLat(x1, curveC1, curveC2) + latOffset;
                float v0y = projV(oy, v0, fy, camH, x0, 0f);
                float v1y = projV(oy, v0, fy, camH, x1, 0f);

                resetP.invoke(path);
                moveTo.invoke(path, projU(ox, u0, fx, x0, c0 - halfWidthM), v0y);
                lineTo.invoke(path, projU(ox, u0, fx, x0, c0 + halfWidthM), v0y);
                lineTo.invoke(path, projU(ox, u0, fx, x1, c1 + halfWidthM), v1y);
                lineTo.invoke(path, projU(ox, u0, fx, x1, c1 - halfWidthM), v1y);
                closeP.invoke(path);

                float fade = Math.max(0.08f, 1.0f - (x0 / 88.0f));
                setColor.invoke(pFill, Integer.valueOf(argbFromAlphaRgb(fade * baseAlpha, r, g, b)));
                drawPath.invoke(c, path, pFill);
            }
        }

        private static void drawBarFillOnly(
                Object c,
                Object rf,
                Object pFill,
                Method setRf,
                Method drawRoundRect,
                Method setColor,
                float x0,
                float y0,
                float maxW,
                float h,
                float pct,
                int fillColor
        ) throws Exception {
            float clamped = Math.max(0f, Math.min(100f, pct));
            if (clamped <= 0.1f) return;
            float r = h * 0.5f;
            float fillW = Math.max(h, (maxW * clamped) / 100.0f);
            setRf.invoke(rf, x0, y0, x0 + fillW, y0 + h);
            setColor.invoke(pFill, Integer.valueOf(fillColor));
            drawRoundRect.invoke(c, rf, r, r, pFill);
        }

        private static String formatHvacTempNumberOnly(double tempC) {
            if (tempC <= 14.0 && tempC > 0.0) return "LO";
            if (tempC >= 32.0) return "HI";
            if (tempC > 14.0 && tempC < 32.0) return String.format(Locale.US, "%.1f", tempC);
            return "21.5";
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
            if ((fillColor >>> 24) != 0) {
                setColor.invoke(pFill, Integer.valueOf(fillColor));
                drawRoundRect.invoke(c, rf, radius, radius, pFill);
            }
            if (strokeW > 0f) {
                setColor.invoke(pStroke, Integer.valueOf(strokeColor));
                setStrokeW.invoke(pStroke, strokeW);
                drawRoundRect.invoke(c, rf, radius, radius, pStroke);
            }
        }

        private static String truncateText(String s, int maxChars) {
            if (s == null || s.isEmpty()) return "--";
            if (s.length() <= maxChars) return s;
            return s.substring(0, maxChars - 1) + "…";
        }

        private static String formatSecMinTwoDigit(int totalSec) {
            int clamped = Math.max(0, totalSec);
            return String.format(Locale.US, "%02d:%02d", clamped / 60, clamped % 60);
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

    public static final class DatcListenerBridge extends com.hkmc.datc.IDATCListener.Stub {
        @Override
        public void onDatcDiagState(int a, int b) {
        }

        @Override
        public void onDatcStatus(com.hkmc.datc.DatcStatus status) {
            TelemetryCollector.onDatcStatusCallback(status);
        }

        @Override
        public void onError(int err) {
        }

        @Override
        public void onIG3StatusChanged(boolean ig3) {
        }

        @Override
        public void onNotifyError(int err) {
        }

        @Override
        public void onTemperatureInformation(
                float drC, int drStateC, float drF, int drStateF,
                float psC, int psStateC, float psF, int psStateF,
                float ambC, int ambStateC, float ambF, int ambStateF,
                float rearC, int rearStateC, float rearF, int rearStateF
        ) {
            TelemetryCollector.onDatcTemperatureCallback(drC, drStateC, psC, psStateC, ambC, ambStateC);
        }

        @Override
        public void onTunFuncOffADSStatus(int status) {
        }
    }

    public static final class RemoteControlDisplayBridge extends android.media.IRemoteControlDisplay.Stub {
        @Override
        public void setCurrentClientId(int clientGeneration, android.app.PendingIntent clientMediaIntent, boolean clearing) {
            TelemetryCollector.onRemoteControlClientChanged(clientGeneration, clientMediaIntent, clearing);
            if (clearing) {
                TelemetryCollector.onRemoteControlCleared();
            }
        }

        @Override
        public void setEnabled(boolean enabled) {
        }

        @Override
        public void setPlaybackState(int generationId, int state, long stateChangeTimeMs, long currentPosMs, float speed) {
            TelemetryCollector.onRemoteControlPlaybackState(state, currentPosMs);
        }

        @Override
        public void setTransportControlInfo(int generationId, int transportControlFlags, int posCapabilities) {
        }

        @Override
        public void setMetadata(int generationId, android.os.Bundle metadata) {
            TelemetryCollector.onRemoteControlMetadata(metadata);
        }

        @Override
        public void setArtwork(int generationId, android.graphics.Bitmap artwork) {
            TelemetryCollector.onRemoteControlArtwork(artwork);
        }

        @Override
        public void setAllMetadata(int generationId, android.os.Bundle metadata, android.graphics.Bitmap artwork) {
            TelemetryCollector.onRemoteControlMetadata(metadata);
            TelemetryCollector.onRemoteControlArtwork(artwork);
        }
    }

    public static final class TelemetryCollector {
        private static final TelemetrySnapshot liveSnapshot = new TelemetrySnapshot();

        private static volatile Object hevService = null;
        private static volatile Method getHevStatusMethod = null;
        private static volatile Method reqHevStatusDataMethod = null;
        private static volatile Method reqFreqChangedSigMethod = null;
        private static volatile Method getCanDbGenMethod = null;
        private static volatile Method getRealSchedInfoMethod = null;
        private static volatile int canDbGeneration = -1;

        private static volatile Object navClusterObj = null;
        private static volatile Method navClusterReqBattMethod = null;
        private static volatile Field navClusterBattRespField = null;

        private static volatile Object tmHscanService = null;
        private static volatile Method tmDriveLinePwrMethod = null;
        private static volatile Method tmGetAccelPdlMethod = null;
        private static volatile Method tmGetGearPosMethod = null;
        private static volatile Method tmGetTempCMethod = null;

        private static volatile Object datcService = null;
        private static volatile Method datcGetClimateInfoMethod = null;
        private static volatile Method datcGetVrStatusMethod = null;
        private static volatile boolean datcListenerRegistered = false;
        private static volatile long lastDatcCallbackMs = 0L;
        private static volatile Object lastRawDatcStatusObj = null;
        private static volatile String lastRawDatcTempCallback = "none";

        private static volatile boolean rcDisplayRegistered = false;
        private static volatile Object rcDisplayBridgeObj = null;
        private static volatile String lastRawRcClientInfo = "none";
        private static volatile String lastRawRcStateInfo = "none";
        private static volatile String lastRawRcBundleDump = "none";
        private static volatile Object clusterService = null;
        private static volatile Method clusterGetLastPlaytimeMethod = null;
        private static volatile int lastClusterMediaMode = -1;
        private static volatile long lastMediaPosUpdateMs = 0L;

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

        public static void onDatcStatusCallback(Object statusObj) {
            if (statusObj == null) return;
            lastRawDatcStatusObj = statusObj;
            synchronized (liveSnapshot) {
                if (applyDatcStatusObject(statusObj, liveSnapshot)) {
                    lastDatcCallbackMs = System.currentTimeMillis();
                }
            }
        }

        public static void onDatcTemperatureCallback(
                float drC, int drStateC,
                float psC, int psStateC,
                float ambC, int ambStateC
        ) {
            lastRawDatcTempCallback = String.format(Locale.US,
                    "drC=%.1f(st=%d), psC=%.1f(st=%d), ambC=%.1f(st=%d)",
                    drC, drStateC, psC, psStateC, ambC, ambStateC);
            synchronized (liveSnapshot) {
                double drResolved = resolveDatcTempWithState(drC, drStateC);
                if (drResolved > 0.0) {
                    liveSnapshot.hvacDrTempC = drResolved;
                    liveSnapshot.hasClimateData = true;
                }
                double psResolved = resolveDatcTempWithState(psC, psStateC);
                if (psResolved > 0.0) {
                    liveSnapshot.hvacPsTempC = psResolved;
                } else if (liveSnapshot.hvacSync && liveSnapshot.hvacDrTempC > 0.0) {
                    liveSnapshot.hvacPsTempC = liveSnapshot.hvacDrTempC;
                }
                if (ambStateC != 0 && !Float.isNaN(ambC) && ambC >= -45.0f && ambC <= 65.0f && Math.abs(ambC - 85.0f) > 0.5f) {
                    liveSnapshot.outdoorTempC = Math.round(ambC * 2.0) / 2.0;
                }
                lastDatcCallbackMs = System.currentTimeMillis();
            }
        }

        public static void onRemoteControlClientChanged(int gen, Object pendingIntentObj, boolean clearing) {
            String pkg = "null";
            if (pendingIntentObj != null) {
                try {
                    Class<?> pc = pendingIntentObj.getClass();
                    try {
                        pkg = String.valueOf(pc.getMethod("getTargetPackage").invoke(pendingIntentObj));
                    } catch (Throwable t1) {
                        pkg = String.valueOf(pc.getMethod("getCreatorPackage").invoke(pendingIntentObj));
                    }
                } catch (Throwable ignored) {
                }
            }
            lastRawRcClientInfo = "gen=" + gen + ", pkg=" + pkg + ", clearing=" + clearing;
        }

        public static void onRemoteControlCleared() {
            synchronized (liveSnapshot) {
                liveSnapshot.mediaPlaying = false;
            }
        }

        public static void onRemoteControlPlaybackState(int state, long currentPosMs) {
            lastRawRcStateInfo = "state=" + state + ", posMs=" + currentPosMs;
            synchronized (liveSnapshot) {
                // MediaFocusControl / RemoteControlClient: PLAYSTATE_PLAYING = 3
                liveSnapshot.mediaPlaying = (state == 3);
                if (currentPosMs >= 0L && currentPosMs < 36000000L) {
                    liveSnapshot.mediaPositionSec = (int) (currentPosMs / 1000L);
                    lastMediaPosUpdateMs = System.currentTimeMillis();
                }
            }
        }

        private static String readBundleStringAny(Object bundleObj, Method getStr, Method getObj, String... keys) {
            for (int i = 0; i < keys.length; i++) {
                String k = keys[i];
                try {
                    if (getStr != null) {
                        String s = (String) getStr.invoke(bundleObj, k);
                        if (s != null && !s.trim().isEmpty()) return s.trim();
                    }
                } catch (Throwable ignored) {
                }
                try {
                    if (getObj != null) {
                        Object v = getObj.invoke(bundleObj, k);
                        if (v != null) {
                            String s = String.valueOf(v).trim();
                            if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) return s;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            return "";
        }

        private static long readBundleLongAny(Object bundleObj, Method getLong, Method getObj, String... keys) {
            for (int i = 0; i < keys.length; i++) {
                String k = keys[i];
                try {
                    if (getLong != null) {
                        long v = ((Long) getLong.invoke(bundleObj, k, Long.valueOf(-1L))).longValue();
                        if (v > 0L) return v;
                    }
                } catch (Throwable ignored) {
                }
                try {
                    if (getObj != null) {
                        Object o = getObj.invoke(bundleObj, k);
                        if (o instanceof Number) {
                            long v = ((Number) o).longValue();
                            if (v > 0L) return v;
                        } else if (o != null) {
                            long v = Long.parseLong(String.valueOf(o).trim());
                            if (v > 0L) return v;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            return -1L;
        }

        public static void onRemoteControlMetadata(Object bundleObj) {
            if (bundleObj == null) return;
            try {
                Class<?> bc = bundleObj.getClass();
                Method getStr = null;
                Method getLong = null;
                Method getObj = null;
                try { getStr = bc.getMethod("getString", String.class); } catch (Throwable ignored) {}
                try { getLong = bc.getMethod("getLong", String.class, long.class); } catch (Throwable ignored) {}
                try { getObj = bc.getMethod("get", String.class); } catch (Throwable ignored) {}

                try {
                    Object keySetObj = bc.getMethod("keySet").invoke(bundleObj);
                    if (keySetObj instanceof java.util.Set) {
                        StringBuilder bsb = new StringBuilder(256);
                        bsb.append("{");
                        boolean first = true;
                        for (Object kObj : (java.util.Set<?>) keySetObj) {
                            String k = String.valueOf(kObj);
                            Object val = (getObj != null) ? getObj.invoke(bundleObj, k) : (getStr != null ? getStr.invoke(bundleObj, k) : null);
                            if (!first) bsb.append(", ");
                            first = false;
                            bsb.append(k).append("=").append(val).append("(").append(val != null ? val.getClass().getSimpleName() : "null").append(")");
                        }
                        bsb.append("}");
                        lastRawRcBundleDump = bsb.toString();
                    } else {
                        lastRawRcBundleDump = String.valueOf(bundleObj);
                    }
                } catch (Throwable tDump) {
                    lastRawRcBundleDump = String.valueOf(bundleObj);
                }

                // MediaMetadataRetriever keys used by RemoteControlClient.MetadataEditor:
                // 7 = METADATA_KEY_TITLE, 2 = METADATA_KEY_ARTIST, 13 = METADATA_KEY_ALBUMARTIST,
                // 1 = METADATA_KEY_ALBUM, 9 = METADATA_KEY_DURATION
                String title = readBundleStringAny(bundleObj, getStr, getObj, "7", "title", "android.media.metadata.TITLE");
                String artist = readBundleStringAny(bundleObj, getStr, getObj, "2", "13", "artist", "android.media.metadata.ARTIST");
                String album = readBundleStringAny(bundleObj, getStr, getObj, "1", "album", "android.media.metadata.ALBUM");
                long durMs = readBundleLongAny(bundleObj, getLong, getObj, "9", "duration", "android.media.metadata.DURATION");

                synchronized (liveSnapshot) {
                    if (!title.isEmpty()) {
                        liveSnapshot.mediaTitle = title;
                        liveSnapshot.mediaPlaying = true;
                    }
                    if (!artist.isEmpty()) {
                        liveSnapshot.mediaArtist = artist;
                    }
                    if (album != null) {
                        liveSnapshot.mediaAlbum = album;
                    }
                    if (durMs > 0L && durMs < 36000000L) {
                        liveSnapshot.mediaDurationSec = (int) (durMs > 36000L ? (durMs / 1000L) : durMs);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        public static void onRemoteControlArtwork(Object bitmapObj) {
            synchronized (liveSnapshot) {
                if (bitmapObj != null) {
                    liveSnapshot.albumArtBitmap = bitmapObj;
                }
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
                                try {
                                    getRealSchedInfoMethod = sc.getMethod("getRealSchedInfo");
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }

                if (navClusterObj == null && sysContext != null) {
                    try {
                        Class<?> ctxCls = Class.forName("android.content.Context");
                        Class<?> navCls = Class.forName("com.hkmc.cluster.NavigationCluster");
                        navClusterObj = navCls.getConstructor(ctxCls).newInstance(sysContext);
                        try {
                            navClusterReqBattMethod = navCls.getMethod("requestBatteryInfoReq");
                        } catch (Throwable ignored) {
                        }
                        try {
                            Field f = navCls.getDeclaredField("mBatteryInfoResp");
                            f.setAccessible(true);
                            navClusterBattRespField = f;
                        } catch (Throwable ignored) {
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
                                if (!datcListenerRegistered) {
                                    try {
                                        Class<?> datcIfc = Class.forName("com.hkmc.datc.IDATCListener");
                                        Object listener = new DatcListenerBridge();
                                        Object binderObj = Class.forName("android.os.Binder").getConstructor().newInstance();
                                        try {
                                            sc.getMethod("registerDATCListener", datcIfc, iBinderCls)
                                                    .invoke(datcService, listener, binderObj);
                                        } catch (NoSuchMethodException nsme) {
                                            sc.getMethod("registerDATCListener", datcIfc)
                                                    .invoke(datcService, listener);
                                        }
                                        datcListenerRegistered = true;
                                        DebugManager.logEvent("DATC", "Registered IDATCListener on com.hkmc.micom.datc");
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }

                if (!rcDisplayRegistered) {
                    try {
                        Object b = getSvc.invoke(null, "audio");
                        if (b != null) {
                            Class<?> stubCls = Class.forName("android.media.IAudioService$Stub");
                            Object audioSvc = stubCls.getMethod("asInterface", iBinderCls).invoke(null, b);
                            if (audioSvc != null) {
                                Class<?> rcdIfc = Class.forName("android.media.IRemoteControlDisplay");
                                Object bridge = new RemoteControlDisplayBridge();
                                rcDisplayBridgeObj = bridge;
                                Class<?> asc = audioSvc.getClass();
                                try {
                                    Method regM = asc.getMethod("registerRemoteControlDisplay", rcdIfc, int.class, int.class);
                                    regM.invoke(audioSvc, bridge, Integer.valueOf(280), Integer.valueOf(280));
                                    rcDisplayRegistered = true;
                                } catch (NoSuchMethodException nsme) {
                                    Method regM = asc.getMethod("registerRemoteControlDisplay", rcdIfc);
                                    regM.invoke(audioSvc, bridge);
                                    rcDisplayRegistered = true;
                                }
                                if (rcDisplayRegistered) {
                                    DebugManager.logEvent("MEDIA", "Registered IRemoteControlDisplay (280x280) with AudioService");
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }

                if (clusterService == null) {
                    try {
                        Object b = getSvc.invoke(null, "com.hkmc.micom.cluster");
                        if (b != null) {
                            Class<?> stubCls = Class.forName("com.hkmc.cluster.IClusterService$Stub");
                            clusterService = stubCls.getMethod("asInterface", iBinderCls).invoke(null, b);
                            if (clusterService != null) {
                                try {
                                    clusterGetLastPlaytimeMethod = clusterService.getClass().getMethod("getLastPlaytime", int.class);
                                } catch (Throwable ignored) {
                                }
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
                if (!isValidDteValue(dte) && getRealSchedInfoMethod != null) {
                    try {
                        int[] sched = (int[]) getRealSchedInfoMethod.invoke(hevService);
                        int schedDte = extractDteFromRealSchedInfo(sched);
                        if (isValidDteValue(schedDte)) {
                            dte = schedDte;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (isValidDteValue(dte)) liveSnapshot.dteKm = dte;

                // Fallback / supplement from NavigationCluster (mBatteryInfoResp[3] = DTE, [0] = SoC, [2] = BattMinTemp, [8] = EnergyWh)
                if (navClusterObj != null) {
                    try {
                        if (navClusterReqBattMethod != null && (pollTick % 6 == 1)) {
                            navClusterReqBattMethod.invoke(navClusterObj);
                        }
                        if (navClusterBattRespField != null) {
                            int[] battResp = (int[]) navClusterBattRespField.get(navClusterObj);
                            if (battResp != null && battResp.length >= 4) {
                                if (!isValidDteValue(liveSnapshot.dteKm) && isValidDteValue(battResp[3])) {
                                    liveSnapshot.dteKm = battResp[3];
                                }
                                if (liveSnapshot.displaySocPct < 0.0 && battResp[0] > 0 && battResp[0] <= 200) {
                                    liveSnapshot.displaySocPct = battResp[0] * 0.5;
                                }
                                if (liveSnapshot.battMinTempC == null && battResp[2] > 0 && battResp[2] < 250) {
                                    liveSnapshot.battMinTempC = Integer.valueOf((int) Math.round(battResp[2] * 0.5 - 40.0));
                                }
                                if (battResp.length >= 9 && liveSnapshot.remainEnergyKwh <= 0.0
                                        && battResp[8] > 500 && battResp[8] < 200000
                                        && battResp[8] != 65534 && battResp[8] != 65535) {
                                    liveSnapshot.remainEnergyKwh = battResp[8] / 1000.0;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }

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
                        if (ptcKw > 0.15 && (System.currentTimeMillis() - lastDatcCallbackMs > 5000L)) {
                            liveSnapshot.hvacHeat = true;
                        }
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
                    if (htrKw > 0.15 && (System.currentTimeMillis() - lastDatcCallbackMs > 5000L)) {
                        liveSnapshot.hvacHeat = true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        private static float queryDatcVrOrInfo(Object svc, Method getVr, Method getInfo, int flag) {
            if (getVr != null) {
                try {
                    float v = ((Float) getVr.invoke(svc, Integer.valueOf(flag))).floatValue();
                    if (!Float.isNaN(v) && v != -1.0f) return v;
                } catch (Throwable ignored) {
                }
            }
            if (getInfo != null) {
                try {
                    int iv = ((Integer) getInfo.invoke(svc, Integer.valueOf(flag))).intValue();
                    if (iv != -1) return (float) iv;
                } catch (Throwable ignored) {
                }
            }
            return -1.0f;
        }

        private static void pollDatcServiceLocked() {
            Object svc = datcService;
            Method getInfo = datcGetClimateInfoMethod;
            Method getVr = datcGetVrStatusMethod;
            boolean callbackFresh = (System.currentTimeMillis() - lastDatcCallbackMs) < 4000L;

            if (svc != null && !callbackFresh && (getVr != null || getInfo != null)) {
                try {
                    int opState = Math.round(queryDatcVrOrInfo(svc, getVr, getInfo, DATC_FLAG_OP_STATE));
                    int blowerRaw = Math.round(queryDatcVrOrInfo(svc, getVr, getInfo, DATC_FLAG_BLOWER_LEVEL));
                    int modeRaw = Math.round(queryDatcVrOrInfo(svc, getVr, getInfo, DATC_FLAG_BLOWER_MODE));
                    int autoRaw = Math.round(queryDatcVrOrInfo(svc, getVr, getInfo, DATC_FLAG_AUTO));
                    int acRaw = Math.round(queryDatcVrOrInfo(svc, getVr, getInfo, DATC_FLAG_AC));
                    int defogRaw = Math.round(queryDatcVrOrInfo(svc, getVr, getInfo, DATC_FLAG_FRONT_DEFOG));
                    int intakeRaw = Math.round(queryDatcVrOrInfo(svc, getVr, getInfo, DATC_FLAG_INTAKE));
                    int syncRaw = Math.round(queryDatcVrOrInfo(svc, getVr, getInfo, DATC_FLAG_SYNC));
                    int adsRaw = Math.round(queryDatcVrOrInfo(svc, getVr, getInfo, DATC_FLAG_ADS_ON_OFF));
                    int drvOnlyRaw = Math.round(queryDatcVrOrInfo(svc, getVr, getInfo, DATC_FLAG_DRIVER_ONLY));

                    // In DATCService.getVRClimateStatus, flag 2 (DATCFlag.TEMP) is Driver Temp and flag 29 (DATCFlag.TEMP_PS_C) is Passenger Temp
                    float vrDr = queryDatcVrOrInfo(svc, getVr, getInfo, DATC_FLAG_TEMP_DR_C);
                    if (vrDr <= 0.0f) {
                        vrDr = queryDatcVrOrInfo(svc, getVr, getInfo, DATC_FLAG_TEMP_DR_C_LEGACY);
                    }
                    float vrPs = queryDatcVrOrInfo(svc, getVr, getInfo, DATC_FLAG_TEMP_PS_C);
                    float vrOut = queryDatcVrOrInfo(svc, getVr, getInfo, DATC_FLAG_TEMP_OUTSIDE_C);

                    int step = normalizeDatcBlowerStep(blowerRaw);
                    if (step >= 0) {
                        liveSnapshot.hvacBlowerStep = step;
                        liveSnapshot.hasClimateData = true;
                    }
                    if (modeRaw >= 0 && modeRaw <= 10) {
                        liveSnapshot.hvacBlowerMode = modeRaw;
                        liveSnapshot.hasClimateData = true;
                    }
                    if (opState == 0 || opState == 1) {
                        liveSnapshot.hvacOn = (opState == 1) || (step > 0);
                    }
                    if (autoRaw == 0 || autoRaw == 1) liveSnapshot.hvacAuto = (autoRaw == 1);
                    if (acRaw == 0 || acRaw == 1) liveSnapshot.hvacAc = (acRaw == 1);
                    if (defogRaw == 0 || defogRaw == 1) liveSnapshot.hvacFrontDefog = (defogRaw == 1);
                    if (intakeRaw == 0 || intakeRaw == 1) liveSnapshot.hvacRecirc = (intakeRaw == 1);
                    if (syncRaw == 0 || syncRaw == 1) liveSnapshot.hvacSync = (syncRaw == 1);
                    if (adsRaw == 0 || adsRaw == 1) liveSnapshot.hvacAds = (adsRaw == 1);
                    if (drvOnlyRaw == 0 || drvOnlyRaw == 1) liveSnapshot.hvacDriverOnly = (drvOnlyRaw == 1);

                    double drC = convertDatcRawTempToCelsius(Math.round(vrDr), vrDr);
                    if (drC > 0.0) {
                        liveSnapshot.hvacDrTempC = drC;
                        liveSnapshot.hasClimateData = true;
                    }
                    double psC = convertDatcRawTempToCelsius(Math.round(vrPs), vrPs);
                    if (psC > 0.0) {
                        liveSnapshot.hvacPsTempC = psC;
                    } else if (liveSnapshot.hvacSync && liveSnapshot.hvacDrTempC > 0.0) {
                        liveSnapshot.hvacPsTempC = liveSnapshot.hvacDrTempC;
                    }
                    if (!Float.isNaN(vrOut) && vrOut >= -45.0f && vrOut <= 65.0f && Math.abs(vrOut - 85.0f) > 0.5f && vrOut != -1.0f) {
                        liveSnapshot.outdoorTempC = Math.round(vrOut * 2.0) / 2.0;
                    }
                } catch (Throwable t) {
                    datcService = null;
                    datcListenerRegistered = false;
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

            // Query ClusterService.getLastPlaytime(mode) if available
            if (clusterService != null && clusterGetLastPlaytimeMethod != null && lastClusterMediaMode >= 0) {
                try {
                    int playSec = ((Integer) clusterGetLastPlaytimeMethod.invoke(clusterService, Integer.valueOf(lastClusterMediaMode))).intValue();
                    if (playSec >= 0 && playSec < 36000) {
                        liveSnapshot.mediaPositionSec = playSec;
                        lastMediaPosUpdateMs = System.currentTimeMillis();
                    }
                } catch (Throwable ignored) {
                }
            } else if (liveSnapshot.mediaPlaying && lastMediaPosUpdateMs > 0L) {
                long now = System.currentTimeMillis();
                if (now - lastMediaPosUpdateMs >= 1000L) {
                    int deltaSec = (int) ((now - lastMediaPosUpdateMs) / 1000L);
                    lastMediaPosUpdateMs += deltaSec * 1000L;
                    int nextPos = liveSnapshot.mediaPositionSec + deltaSec;
                    if (liveSnapshot.mediaDurationSec > 0) {
                        liveSnapshot.mediaPositionSec = Math.min(liveSnapshot.mediaDurationSec, nextPos);
                    } else {
                        liveSnapshot.mediaPositionSec = nextPos;
                    }
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
                            "/system/bin/logcat", "-b", "main", "-b", "system", "-v", "brief",
                            "TM_HSCANPacketRunner:I", "ClusterService:I", "*:S"
                    });
                    br = new BufferedReader(new InputStreamReader(proc.getInputStream(), UTF8), 4096);
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.contains("ClusterService")) {
                            synchronized (liveSnapshot) {
                                int mode = parseClusterMediaLogLine(line, liveSnapshot);
                                if (mode >= 0) {
                                    lastClusterMediaMode = mode;
                                }
                            }
                        } else if (System.currentTimeMillis() - lastNativeBinderHscanRxMs > 2500L) {
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
            c.hvacHeat = liveSnapshot.hvacHeat;
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
            c.albumArtBitmap = liveSnapshot.albumArtBitmap;
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
        private static volatile String lastStatusText = "";
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
                viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(tv, 4, 6, 10, 6);

                Constructor<?> llLpCtor = llLpCls.getConstructor(int.class, int.class);

                // Place action buttons FIRST on the left so 1Hz statusTv text updates never shift button coordinates!
                Object btnUpdate = createOverlayButton(ctxCls, viewCls, tvCls, gdCls, drawableCls,
                        " ☁️ 업데이트 ", 0xFF0284C7, new Runnable() {
                            @Override
                            public void run() {
                                OtaUpdateManager.triggerManualOta(installDir, false);
                            }
                        });
                Object lpBtn1 = llLpCtor.newInstance(-2, -2);
                llLpCls.getMethod("setMargins", int.class, int.class, int.class, int.class).invoke(lpBtn1, 0, 0, 8, 0);
                vgCls.getMethod("addView", viewCls, vgLpCls).invoke(root, btnUpdate, lpBtn1);

                Object btnLog = createOverlayButton(ctxCls, viewCls, tvCls, gdCls, drawableCls,
                        " 💾 로그저장 ", 0xFF0D9488, new Runnable() {
                            @Override
                            public void run() {
                                DebugManager.triggerAsyncDump(installDir);
                            }
                        });
                Object lpBtn2 = llLpCtor.newInstance(-2, -2);
                llLpCls.getMethod("setMargins", int.class, int.class, int.class, int.class).invoke(lpBtn2, 0, 0, 12, 0);
                vgCls.getMethod("addView", viewCls, vgLpCls).invoke(root, btnLog, lpBtn2);

                // Status text in the middle (drag handle)
                vgCls.getMethod("addView", viewCls, vgLpCls).invoke(root, tv, llLpCtor.newInstance(-2, -2));

                Object btnHide = createOverlayButton(ctxCls, viewCls, tvCls, gdCls, drawableCls,
                        " ✖ ", 0xFF334155, new Runnable() {
                            @Override
                            public void run() {
                                setEnabledAndShow(false);
                            }
                        });
                Object lpBtn3 = llLpCtor.newInstance(-2, -2);
                llLpCls.getMethod("setMargins", int.class, int.class, int.class, int.class).invoke(lpBtn3, 8, 0, 0, 0);
                vgCls.getMethod("addView", viewCls, vgLpCls).invoke(root, btnHide, lpBtn3);

                // Attach drag listener to status text view so button touches are never intercepted by drag!
                attachDragAndHideTouchListener(tv);

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
                lastStatusText = "";
                isVisible = true;
                refreshOnUiThread();
            } catch (Throwable ignored) {
            }
        }

        private static Object createOverlayButton(
                Class<?> ctxCls,
                Class<?> viewCls,
                Class<?> tvCls,
                final Class<?> gdCls,
                Class<?> drawableCls,
                String label,
                final int strokeColor,
                final Runnable onClick
        ) throws Exception {
            final Object btn = tvCls.getConstructor(ctxCls).newInstance(sysContext);
            tvCls.getMethod("setText", CharSequence.class).invoke(btn, label);
            tvCls.getMethod("setTextSize", int.class, float.class).invoke(btn, 2, 13.0f);
            tvCls.getMethod("setTextColor", int.class).invoke(btn, 0xFFF8FAFC);
            viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(btn, 16, 9, 16, 9);

            final Object gd = gdCls.getConstructor().newInstance();
            gdCls.getMethod("setColor", int.class).invoke(gd, 0xFF1E293B);
            gdCls.getMethod("setCornerRadius", float.class).invoke(gd, 10f);
            gdCls.getMethod("setStroke", int.class, int.class).invoke(gd, 2, strokeColor);
            viewCls.getMethod("setBackgroundDrawable", drawableCls).invoke(btn, gd);
            viewCls.getMethod("setClickable", boolean.class).invoke(btn, true);

            // Handle touch directly on ACTION_DOWN / ACTION_UP so 1Hz layout refreshes never cancel clicks!
            Class<?> touchIfc = Class.forName("android.view.View$OnTouchListener");
            Object touchProxy = Proxy.newProxyInstance(
                    touchIfc.getClassLoader(),
                    new Class<?>[]{touchIfc},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("onTouch".equals(method.getName()) && args != null && args.length >= 2 && args[1] != null) {
                                try {
                                    Object me = args[1];
                                    int act = ((Integer) me.getClass().getMethod("getAction").invoke(me)).intValue() & 0xFF;
                                    if (act == 0) { // ACTION_DOWN: instant highlight feedback
                                        gdCls.getMethod("setColor", int.class).invoke(gd, strokeColor);
                                        btn.getClass().getMethod("invalidate").invoke(btn);
                                    } else if (act == 1 || act == 3) { // ACTION_UP / ACTION_CANCEL
                                        gdCls.getMethod("setColor", int.class).invoke(gd, 0xFF1E293B);
                                        btn.getClass().getMethod("invalidate").invoke(btn);
                                        if (act == 1 && onClick != null) {
                                            onClick.run();
                                        }
                                    }
                                } catch (Throwable ignored) {
                                }
                                return Boolean.TRUE;
                            }
                            return Boolean.FALSE;
                        }
                    }
            );
            viewCls.getMethod("setOnTouchListener", touchIfc).invoke(btn, touchProxy);
            return btn;
        }

        private static void hideOnUiThread() {
            Object oldRoot = rootView;
            rootView = null;
            currentWmLp = null;
            statusTv = null;
            lastStatusText = "";
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
                if (now < bannerUntilMs && bannerMsg != null && !bannerMsg.isEmpty()) {
                    extra = "   |   " + bannerMsg;
                } else if (OtaUpdateManager.isUpdating()) {
                    extra = "   |   ☁️ " + OtaUpdateManager.getStatusText();
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
                if (!text.equals(lastStatusText)) {
                    lastStatusText = text;
                    statusTv.getClass().getMethod("setText", CharSequence.class).invoke(statusTv, text);
                }
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

            appendRawBinderAndSystemDiagnostics(sb);

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

        private static void appendRawBinderAndSystemDiagnostics(StringBuilder sb) {
            try {
                sb.append("\n--- [3A] Raw Binder Services & Vehicle Environment Scan ---\n");
                sb.append("  hevService          : ").append(TelemetryCollector.hevService != null ? TelemetryCollector.hevService.getClass().getName() : "null")
                        .append(" (canDbGen=").append(TelemetryCollector.canDbGeneration).append(")\n");
                sb.append("  navClusterObj       : ").append(TelemetryCollector.navClusterObj != null ? TelemetryCollector.navClusterObj.getClass().getName() : "null").append("\n");
                sb.append("  tmHscanService      : ").append(TelemetryCollector.tmHscanService != null ? TelemetryCollector.tmHscanService.getClass().getName() : "null").append("\n");
                sb.append("  datcService         : ").append(TelemetryCollector.datcService != null ? TelemetryCollector.datcService.getClass().getName() : "null")
                        .append(" (listenerReg=").append(TelemetryCollector.datcListenerRegistered).append(")\n");
                sb.append("  hscanMicomService   : ").append(TelemetryCollector.hscanMicomService != null ? TelemetryCollector.hscanMicomService.getClass().getName() : "null")
                        .append(" (listenerReg=").append(TelemetryCollector.hscanListenerRegistered).append(")\n");
                sb.append("  clusterService      : ").append(TelemetryCollector.clusterService != null ? TelemetryCollector.clusterService.getClass().getName() : "null")
                        .append(" (lastMode=").append(TelemetryCollector.lastClusterMediaMode).append(")\n");
                sb.append("  rcDisplayRegistered : ").append(TelemetryCollector.rcDisplayRegistered).append("\n");

                // 3B. Raw IHEVService flag scan (0..200) + getRealSchedInfo + NavigationCluster + tm_hscan_service
                sb.append("\n--- [3B] Raw HEV / EV / DTE Scan (com.hkmc.micom.hev & tm_hscan_service) ---\n");
                Object hev = TelemetryCollector.hevService;
                Method getHev = TelemetryCollector.getHevStatusMethod;
                if (hev != null && getHev != null) {
                    StringBuilder hevFlags = new StringBuilder(512);
                    for (int f = 0; f <= 200; f++) {
                        try {
                            int v = ((Integer) getHev.invoke(hev, Integer.valueOf(f))).intValue();
                            if (v != -1) {
                                hevFlags.append("  [").append(f).append("]=").append(v);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    sb.append("  getHEVStatus(0..200) non-(-1):").append(hevFlags.length() > 0 ? hevFlags.toString() : " (none)").append("\n");
                }
                if (hev != null && TelemetryCollector.getRealSchedInfoMethod != null) {
                    try {
                        int[] sched = (int[]) TelemetryCollector.getRealSchedInfoMethod.invoke(hev);
                        sb.append("  getRealSchedInfo()  : ").append(formatIntArray(sched)).append("\n");
                    } catch (Throwable ignored) {
                    }
                }
                if (hev != null) {
                    appendZeroArgMethodDump(sb, "  hevService.0arg     : ", hev);
                }
                if (TelemetryCollector.navClusterObj != null) {
                    appendObjectFieldsDump(sb, "  navCluster fields   : ", TelemetryCollector.navClusterObj);
                    appendZeroArgMethodDump(sb, "  navCluster.0arg     : ", TelemetryCollector.navClusterObj);
                }
                if (TelemetryCollector.tmHscanService != null) {
                    appendZeroArgMethodDump(sb, "  tmHscanService.0arg : ", TelemetryCollector.tmHscanService);
                }

                // 3C. Raw IDATCService flag scan (0..80) + DatcStatus reflection dump
                sb.append("\n--- [3C] Raw DATC Climate Scan (com.hkmc.micom.datc) ---\n");
                sb.append("  lastRawDatcTempCb   : ").append(TelemetryCollector.lastRawDatcTempCallback).append("\n");
                if (TelemetryCollector.lastRawDatcStatusObj != null) {
                    appendObjectFieldsDump(sb, "  DatcStatus fields   : ", TelemetryCollector.lastRawDatcStatusObj);
                    appendZeroArgMethodDump(sb, "  DatcStatus getters  : ", TelemetryCollector.lastRawDatcStatusObj);
                } else {
                    sb.append("  DatcStatus object   : null (no callback received yet)\n");
                }
                Object datc = TelemetryCollector.datcService;
                if (datc != null) {
                    Method getInfo = TelemetryCollector.datcGetClimateInfoMethod;
                    Method getVr = TelemetryCollector.datcGetVrStatusMethod;
                    StringBuilder infoSb = new StringBuilder(512);
                    StringBuilder vrSb = new StringBuilder(512);
                    for (int f = 0; f <= 80; f++) {
                        if (getInfo != null) {
                            try {
                                int iv = ((Integer) getInfo.invoke(datc, Integer.valueOf(f))).intValue();
                                if (iv != -1) infoSb.append(" [").append(f).append("]=").append(iv);
                            } catch (Throwable ignored) {}
                        }
                        if (getVr != null) {
                            try {
                                float fv = ((Float) getVr.invoke(datc, Integer.valueOf(f))).floatValue();
                                if (!Float.isNaN(fv) && fv != -1.0f) {
                                    vrSb.append(String.format(Locale.US, " [%d]=%.1f", f, fv));
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                    sb.append("  getClimateInfo(0..80):").append(infoSb.length() > 0 ? infoSb.toString() : " (none)").append("\n");
                    sb.append("  getVRClimate(0..80)  :").append(vrSb.length() > 0 ? vrSb.toString() : " (none)").append("\n");
                    appendZeroArgMethodDump(sb, "  datcService.0arg    : ", datc);
                }

                // 3D. Raw Media / RemoteControlDisplay / ClusterService / dumpsys
                sb.append("\n--- [3D] Raw Media & Broadcast Scan (AudioService / ClusterService) ---\n");
                sb.append("  lastRcClient        : ").append(TelemetryCollector.lastRawRcClientInfo).append("\n");
                sb.append("  lastRcPlaybackState : ").append(TelemetryCollector.lastRawRcStateInfo).append("\n");
                sb.append("  lastRcBundle        : ").append(TelemetryCollector.lastRawRcBundleDump).append("\n");
                if (TelemetryCollector.clusterService != null) {
                    appendZeroArgMethodDump(sb, "  clusterService.0arg : ", TelemetryCollector.clusterService);
                    appendObjectFieldsDump(sb, "  clusterService flds : ", TelemetryCollector.clusterService);
                }
                if (new File("/system/bin/sh").isFile()) {
                    sb.append("  [dumpsys audio (RemoteControl / Focus)]:\n");
                    sb.append(runQuickShellCmd("dumpsys audio 2>/dev/null | grep -iE -A 2 -B 1 'RemoteControl|Focus|Client|Package|Media|Player' | head -n 60", "    "));
                    sb.append("  [Sticky & Recent Media/Vehicle Broadcasts]:\n");
                    sb.append(runQuickShellCmd("dumpsys activity broadcasts 2>/dev/null | grep -iE -A 4 'hkmc|media|music|avrcp|bt|cluster|datc|hev|title|artist|play' | head -n 90", "    "));
                    sb.append("  [Binder Services (hkmc / media / audio / cluster)]:\n");
                    sb.append(runQuickShellCmd("service list 2>/dev/null | grep -iE 'hkmc|micom|datc|hev|hscan|cluster|audio|media|tm_' | head -n 50", "    "));
                }
            } catch (Throwable ignored) {
            }
        }

        private static String formatIntArray(int[] arr) {
            if (arr == null) return "null";
            StringBuilder sb = new StringBuilder(arr.length * 6 + 4);
            sb.append("[");
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(i).append(":").append(arr[i]);
            }
            sb.append("]");
            return sb.toString();
        }

        private static void appendObjectFieldsDump(StringBuilder sb, String prefix, Object obj) {
            if (obj == null) return;
            try {
                Field[] fields = obj.getClass().getDeclaredFields();
                StringBuilder fsb = new StringBuilder(256);
                for (int i = 0; i < fields.length; i++) {
                    Field f = fields[i];
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (fsb.length() > 0) fsb.append(", ");
                    fsb.append(f.getName()).append("=");
                    if (v instanceof int[]) {
                        fsb.append(formatIntArray((int[]) v));
                    } else {
                        fsb.append(v);
                    }
                }
                sb.append(prefix).append(fsb.toString()).append("\n");
            } catch (Throwable ignored) {
            }
        }

        private static void appendZeroArgMethodDump(StringBuilder sb, String prefix, Object obj) {
            if (obj == null) return;
            try {
                Method[] methods = obj.getClass().getDeclaredMethods();
                StringBuilder msb = new StringBuilder(512);
                for (int i = 0; i < methods.length; i++) {
                    Method m = methods[i];
                    if (m.getParameterTypes().length != 0) continue;
                    String name = m.getName();
                    if ("asBinder".equals(name) || "toString".equals(name) || "hashCode".equals(name)
                            || "getInterfaceDescriptor".equals(name) || name.startsWith("req")
                            || name.startsWith("set") || name.startsWith("clear") || name.startsWith("reset")
                            || name.startsWith("send") || name.startsWith("stop") || name.startsWith("start")) {
                        continue;
                    }
                    try {
                        m.setAccessible(true);
                        Object v = m.invoke(obj);
                        if (msb.length() > 0) msb.append(", ");
                        msb.append(name).append("()=");
                        if (v instanceof int[]) {
                            msb.append(formatIntArray((int[]) v));
                        } else {
                            msb.append(v);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                sb.append(prefix).append(msb.toString()).append("\n");
            } catch (Throwable ignored) {
            }
        }

        private static String runQuickShellCmd(String shellCmd, String indent) {
            Process p = null;
            BufferedReader br = null;
            StringBuilder out = new StringBuilder(512);
            try {
                p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", shellCmd});
                br = new BufferedReader(new InputStreamReader(p.getInputStream(), UTF8), 2048);
                String line;
                int count = 0;
                while ((line = br.readLine()) != null && count < 100) {
                    out.append(indent).append(line).append("\n");
                    count++;
                }
            } catch (Throwable ignored) {
            } finally {
                if (br != null) { try { br.close(); } catch (Throwable ignored) {} }
                if (p != null) { try { p.destroy(); } catch (Throwable ignored) {} }
            }
            return out.length() > 0 ? out.toString() : (indent + "(empty)\n");
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
        private static volatile long lastManualTriggerMs = 0L;
        private static volatile boolean workerStarted = false;
        private static volatile int otaRunGen = 0;
        private static volatile Socket activeRawSocket = null;

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
                            if ((cfg == null || cfg.autoUpdate) && !otaUpdating) {
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
            long now = System.currentTimeMillis();
            if (otaUpdating && (now - lastManualTriggerMs) < 2500L) {
                TurzxOverlayController.showBannerMessage("☁️ GitHub 최신 버전 확인 중...", 4000L);
                return;
            }
            lastManualTriggerMs = now;
            // Preempt any stuck or slow background check immediately
            otaRunGen++;
            Socket s = activeRawSocket;
            if (s != null) {
                try {
                    s.close();
                } catch (Throwable ignored) {
                }
            }
            otaUpdating = false;
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

        public static boolean checkAndApplyGitHubOta(
                String dir,
                boolean applyIfNew,
                boolean forceApply,
                boolean silentIfUpToDate
        ) {
            final int myGen = ++otaRunGen;
            otaUpdating = true;
            String installDir = (dir != null && !dir.isEmpty()) ? dir : "/data/nemesis/apps/hkg.turzxmonitor";
            try {
                lastCheckMs = System.currentTimeMillis();
                otaStatusText = "GitHub 연결 중...";
                ensureGitHubRoutingRule();

                byte[] verBytes = downloadGitHubOtaFile(GITHUB_OTA_BASE_PATH + "/version.json", 32768, myGen);
                if (myGen != otaRunGen) return false;
                if (verBytes == null || verBytes.length < 10) {
                    otaStatusText = "서버 응답 없음 (네트워크 확인)";
                    if (!silentIfUpToDate) {
                        TurzxOverlayController.showBannerMessage("⚠️ GitHub 연결 실패 (네트워크 확인)", 5000L);
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
                    if (!silentIfUpToDate) {
                        TurzxOverlayController.showBannerMessage("⚠️ 버전 메타데이터 오류", 5000L);
                    }
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
                    byte[] b64Bytes = downloadGitHubOtaFile(GITHUB_OTA_BASE_PATH + "/turzxmonitor.b64", 4 * 1024 * 1024, myGen);
                    if (myGen != otaRunGen) return false;
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
                    byte[] daemonBytes = downloadGitHubOtaFile(GITHUB_OTA_BASE_PATH + "/turzx-daemon.sh", 262144, myGen);
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
                    byte[] launchBytes = downloadGitHubOtaFile(GITHUB_OTA_BASE_PATH + "/launch.sh", 262144, myGen);
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
                if (myGen == otaRunGen) {
                    otaStatusText = "업데이트 오류";
                }
                return false;
            } finally {
                if (myGen == otaRunGen) {
                    otaUpdating = false;
                }
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
                    ds.setSoTimeout(1200);
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

        private static byte[] downloadGitHubOtaFile(String path, int maxBytes, int myGen) {
            List<String> ips = resolveHostViaPublicDns(GITHUB_RAW_HOST);
            for (int i = 0; i < ips.size(); i++) {
                if (myGen != otaRunGen) return null;
                String ip = ips.get(i);
                SSLSocket sslSock = null;
                Socket rawSock = null;
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

                    rawSock = new Socket();
                    activeRawSocket = rawSock;
                    rawSock.connect(new InetSocketAddress(ip, 443), 3000);
                    rawSock.setSoTimeout(7000);

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
                    if (activeRawSocket == rawSock) {
                        activeRawSocket = null;
                    }
                    if (sslSock != null) { try { sslSock.close(); } catch (Throwable ignored) {} }
                    else if (rawSock != null) { try { rawSock.close(); } catch (Throwable ignored) {} }
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
        sb.append(",\"heat\":").append(ev.hvacHeat);
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
                && dte != 1022 && dte != 1023
                && dte != 65534 && dte != 65535;
    }

    public static int extractDteFromRealSchedInfo(int[] sched) {
        if (sched == null || sched.length < 7) return -1;
        if (isValidDteValue(sched[6])) return sched[6];
        if (sched.length >= 8 && isValidDteValue(sched[7])) return sched[7];
        return -1;
    }

    public static double resolveDatcTempWithState(float tempC, int stateC) {
        // DatcStatus temperature state: 1 = TEMP_LOW ("LO"), 2 = TEMP_HIGH ("HI"), 3 = TEMP_NORMAL
        if (stateC == 1) return 14.0;
        if (stateC == 2) return 32.0;
        if (!Float.isNaN(tempC) && tempC >= 13.5f && tempC <= 32.5f) {
            return Math.round(tempC * 2.0) / 2.0;
        }
        return -99.0;
    }

    private static int readObjIntAny(Object obj, int defVal, String... names) {
        if (obj == null) return defVal;
        Class<?> sc = obj.getClass();
        for (int i = 0; i < names.length; i++) {
            String n = names[i];
            try {
                Object v = sc.getMethod(n).invoke(obj);
                if (v instanceof Number) return ((Number) v).intValue();
                if (v instanceof Boolean) return ((Boolean) v).booleanValue() ? 1 : 0;
            } catch (Throwable ignored) {
            }
            try {
                Field f = sc.getDeclaredField(n);
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v instanceof Number) return ((Number) v).intValue();
                if (v instanceof Boolean) return ((Boolean) v).booleanValue() ? 1 : 0;
            } catch (Throwable ignored) {
            }
        }
        return defVal;
    }

    private static float readObjFloatAny(Object obj, float defVal, String... names) {
        if (obj == null) return defVal;
        Class<?> sc = obj.getClass();
        for (int i = 0; i < names.length; i++) {
            String n = names[i];
            try {
                Object v = sc.getMethod(n).invoke(obj);
                if (v instanceof Number) return ((Number) v).floatValue();
            } catch (Throwable ignored) {
            }
            try {
                Field f = sc.getDeclaredField(n);
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v instanceof Number) return ((Number) v).floatValue();
            } catch (Throwable ignored) {
            }
        }
        return defVal;
    }

    public static boolean applyDatcStatusObject(Object statusObj, TelemetrySnapshot target) {
        if (statusObj == null || target == null) return false;
        try {
            int op = readObjIntAny(statusObj, -1, "getOperationStatus", "getOpStatus", "mOperationStatus", "operationStatus");
            int rawLevel = readObjIntAny(statusObj, -1, "getMainBlowerLevel", "getBlowerLevel", "mMainBlowerLevel", "mainBlowerLevel");
            int autoStep = readObjIntAny(statusObj, 0, "getAutoStep", "mAutoStep", "autoStep");
            int mode = readObjIntAny(statusObj, -1, "getModeDrive", "getBlowerMode", "mModeDrive", "modeDrive");
            int auto = readObjIntAny(statusObj, -1, "getAuto", "getAutoStatus", "mAuto", "auto");
            int ac = readObjIntAny(statusObj, -1, "getAirconOnOff", "getAcOnOff", "getAC", "mAirconOnOff");
            int heat = readObjIntAny(statusObj, -1, "getHeatOnOff", "getHeaterOnOff", "mHeatOnOff");
            int defog = readObjIntAny(statusObj, -1, "getFrontDefog", "mFrontDefog", "frontDefog");
            int intake = readObjIntAny(statusObj, -1, "getIntake", "getIntakeMode", "mIntake", "intake");
            int sync = readObjIntAny(statusObj, -1, "getSyncDisp", "getSync", "mSyncDisp", "sync");
            int dual = readObjIntAny(statusObj, -1, "getDual", "mDual", "dual");
            int drvOnly = readObjIntAny(statusObj, -1, "getDriverOnly", "mDriverOnly", "driverOnly");
            int ads = readObjIntAny(statusObj, -1, "getADSOnOffStatus", "getAdsStatus", "mADSOnOffStatus");

            float drC = readObjFloatAny(statusObj, Float.NaN, "getDrTempC", "getDriverTempC", "mDrTempC", "drTempC");
            int drStateC = readObjIntAny(statusObj, 3, "getDrTempStateC", "mDrTempStateC");
            float psC = readObjFloatAny(statusObj, Float.NaN, "getPsTempC", "getPassengerTempC", "mPsTempC", "psTempC");
            int psStateC = readObjIntAny(statusObj, 3, "getPsTempStateC", "mPsTempStateC");
            float ambC = readObjFloatAny(statusObj, Float.NaN, "getAmbientTempC", "getOutdoorTempC", "mAmbientTempC");
            int ambStateC = readObjIntAny(statusObj, 1, "getAmbientTempStateC", "mAmbientTempStateC");

            int step = normalizeDatcBlowerStep(rawLevel);
            if (step < 0 && autoStep >= 1 && autoStep <= 3) {
                step = autoStep * 2;
            }
            if (step >= 0) {
                target.hvacBlowerStep = step;
            }
            if (mode >= 0 && mode <= 10) {
                target.hvacBlowerMode = mode;
            }
            if (op == 0 || op == 1 || step >= 0) {
                target.hvacOn = (op == 1) || (step > 0);
            }
            if (auto == 0 || auto == 1) target.hvacAuto = (auto == 1);
            if (ac == 0 || ac == 1) target.hvacAc = (ac == 1);
            if (heat == 0 || heat == 1) target.hvacHeat = (heat == 1);
            if (defog == 0 || defog == 1) target.hvacFrontDefog = (defog == 1);
            if (intake == 0 || intake == 1) target.hvacRecirc = (intake == 1);
            if (sync == 0 || sync == 1 || dual == 0 || dual == 1) {
                target.hvacSync = (sync == 1) || (dual == 1);
            }
            if (drvOnly == 0 || drvOnly == 1) target.hvacDriverOnly = (drvOnly == 1);
            if (ads == 0 || ads == 1) target.hvacAds = (ads == 1);

            double drResolved = resolveDatcTempWithState(drC, drStateC);
            if (drResolved > 0.0) {
                target.hvacDrTempC = drResolved;
            }
            double psResolved = resolveDatcTempWithState(psC, psStateC);
            if (psResolved > 0.0) {
                target.hvacPsTempC = psResolved;
            } else if (target.hvacSync && target.hvacDrTempC > 0.0) {
                target.hvacPsTempC = target.hvacDrTempC;
            }
            if (ambStateC != 0 && !Float.isNaN(ambC) && ambC >= -45.0f && ambC <= 65.0f && Math.abs(ambC - 85.0f) > 0.5f) {
                target.outdoorTempC = Math.round(ambC * 2.0) / 2.0;
            }
            target.hasClimateData = true;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static int parseClusterMediaLogLine(String line, TelemetrySnapshot target) {
        if (line == null || target == null || !line.contains("ClusterService")) return -1;
        try {
            if (line.contains("updateClusterWithArtistInfo") || line.contains("updateClusterInfoWithArtistForMusicStreaming")) {
                int mode = extractClusterKeyInt(line, "mode =", -1);
                String title = extractClusterKeyBetween(line, "title =", ", artist =");
                String artist = extractClusterKeyTail(line, "artist =");
                if (title != null && !title.isEmpty()) {
                    target.mediaTitle = title;
                    target.mediaPlaying = true;
                }
                if (artist != null && !artist.isEmpty()) {
                    target.mediaArtist = artist;
                }
                if (mode >= 0) {
                    target.mediaSource = mapClusterModeToSourceName(mode);
                }
                return mode;
            } else if (line.contains("updateClusterWithFolderInfo")) {
                int mode = extractClusterKeyInt(line, "mode =", -1);
                String folder = extractClusterKeyBetween(line, "folder =", ", file =");
                String file = extractClusterKeyTail(line, "file =");
                if (file != null && !file.isEmpty()) {
                    target.mediaTitle = file;
                    target.mediaPlaying = true;
                }
                if (folder != null && !folder.isEmpty()) {
                    target.mediaArtist = folder;
                }
                if (mode >= 0) {
                    target.mediaSource = mapClusterModeToSourceName(mode);
                }
                return mode;
            } else if (line.contains("updateClusterInfo") && line.contains("info1 =")) {
                int mode = extractClusterKeyInt(line, "mode =", -1);
                String info1 = extractClusterKeyBetween(line, "info1 =", ", info2 =");
                String info2 = extractClusterKeyTail(line, "info2 =");
                if (info1 != null && !info1.isEmpty()) {
                    target.mediaTitle = info1;
                    target.mediaPlaying = true;
                }
                if (info2 != null && !info2.isEmpty()) {
                    target.mediaArtist = info2;
                }
                if (mode >= 0) {
                    target.mediaSource = mapClusterModeToSourceName(mode);
                }
                return mode;
            } else if (line.contains("updateClusterRadioInfo") && line.contains("freq =")) {
                int mode = extractClusterKeyInt(line, "mode =", -1);
                String freq = extractClusterKeyTail(line, "freq =");
                int comma = (freq != null) ? freq.indexOf(',') : -1;
                if (comma > 0) freq = freq.substring(0, comma).trim();
                if (freq != null && !freq.isEmpty()) {
                    String srcName = mapClusterModeToSourceName(mode);
                    target.mediaSource = srcName;
                    target.mediaTitle = srcName + " " + freq;
                    target.mediaArtist = "라디오 방송 수신 중";
                    target.mediaAlbum = "";
                    target.mediaPlaying = true;
                }
                return mode;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static int extractClusterKeyInt(String line, String key, int def) {
        int idx = line.indexOf(key);
        if (idx < 0) return def;
        int p = idx + key.length();
        while (p < line.length() && Character.isWhitespace(line.charAt(p))) p++;
        int start = p;
        if (p < line.length() && line.charAt(p) == '-') p++;
        while (p < line.length() && Character.isDigit(line.charAt(p))) p++;
        if (p <= start) return def;
        return parseIntSafe(line.substring(start, p), def);
    }

    private static String extractClusterKeyBetween(String line, String startKey, String endKey) {
        int i1 = line.indexOf(startKey);
        if (i1 < 0) return "";
        int vStart = i1 + startKey.length();
        int i2 = line.indexOf(endKey, vStart);
        if (i2 < 0) return line.substring(vStart).trim();
        return line.substring(vStart, i2).trim();
    }

    private static String extractClusterKeyTail(String line, String startKey) {
        int i1 = line.indexOf(startKey);
        if (i1 < 0) return "";
        return line.substring(i1 + startKey.length()).trim();
    }

    private static String mapClusterModeToSourceName(int mode) {
        switch (mode) {
            case 1: return "FM";
            case 2: return "AM";
            case 3: return "DRM";
            case 6: return "USB Music";
            case 7: return "USB Video";
            case 9: return "Bluetooth Audio";
            case 12: return "DMB";
            case 13: return "iPod";
            case 14: return "AUX";
            case 23: return "DAB";
            case 33: return "Apple CarPlay";
            case 35: return "Android Auto";
            default: return "AVN Media";
        }
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
        if (rawLevel >= 11 && rawLevel <= 13) return (rawLevel - 10) * 2; // BLOWER_AUTO_STEP1..3 -> 2, 4, 6
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

            // 7. Verify DE22EV getRealSchedInfo() DTE extraction, DatcStatus decoding, and ClusterService media log parsing
            int[] de22Sched = new int[]{7, 31, 63, 7, 31, 63, 198, 0, 1};
            assertTrue(extractDteFromRealSchedInfo(de22Sched) == 198,
                    "Expected DTE=198 km from DE22EV getRealSchedInfo()");
            assertTrue(!isValidDteValue(65535) && !isValidDteValue(65534),
                    "Expected 65535/65534 to be rejected by isValidDteValue");

            com.hkmc.datc.DatcStatus ds = new com.hkmc.datc.DatcStatus();
            ds.mOperationStatus = 1;
            ds.mMainBlowerLevel = 5; // step 4
            ds.mModeDrive = 2;       // B/L
            ds.mAuto = 1;
            ds.mAirconOnOff = 0;
            ds.mHeatOnOff = 1;
            ds.mIntake = 1;
            ds.mSyncDisp = 1;
            ds.mDrTempC = 23.5f;
            ds.mDrTempStateC = 3;
            ds.mPsTempC = 24.0f;
            ds.mPsTempStateC = 3;
            assertTrue(applyDatcStatusObject(ds, snap), "Expected DatcStatus decode success");
            assertTrue(snap.hvacOn && snap.hvacBlowerStep == 4 && snap.hvacHeat && !snap.hvacAc
                    && Math.abs(snap.hvacDrTempC - 23.5) < 0.01 && Math.abs(snap.hvacPsTempC - 24.0) < 0.01,
                    "Expected DatcStatus fields applied accurately");

            String clusterBtLine = "I/ClusterService( 1120): updateClusterWithArtistInfo : mode = 9, title = Supernova, artist = aespa";
            int parsedMode = parseClusterMediaLogLine(clusterBtLine, snap);
            assertTrue(parsedMode == 9 && "Supernova".equals(snap.mediaTitle) && "aespa".equals(snap.mediaArtist)
                    && "Bluetooth Audio".equals(snap.mediaSource) && snap.mediaPlaying,
                    "Expected ClusterService BT Audio log line parsed");

            // 8. Verify Chromium 1:1 pre-rendered HUD PNG layers + Public Sans TTF fonts are bundled and readable
            String[] requiredAssets = new String[]{
                    "base_shell.png",
                    "active_shell.png",
                    "col2_hud_section.png",
                    "col2_hud_traffic.png",
                    "col2_hud_none.png",
                    "PublicSans-Bold.ttf",
                    "PublicSans-Medium.ttf"
            };
            for (int i = 0; i < requiredAssets.length; i++) {
                byte[] assetBytes = TurzxFrameRenderer.readJarAssetBytes(requiredAssets[i]);
                assertTrue(assetBytes != null && assetBytes.length > 8192,
                        "Expected bundled HUD asset " + requiredAssets[i] + " (>8KB)");
            }
        } catch (Exception e) {
            throw new RuntimeException("MonitorConfig / OTA self-test failed", e);
        }

        System.out.println("[TurzxMonitor] All TURZX DES-CBC USB + Chromium 1:1 HUD Assets + 4-Domain Telemetry + CVS1 + DATC + Media + GitHub OTA + DebugManager self-tests passed.");
    }
}
