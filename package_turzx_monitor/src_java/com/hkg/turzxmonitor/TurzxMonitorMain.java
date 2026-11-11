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
import java.util.HashMap;
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
 *    - Comma 3X (ajouatom/openpilot carrot-wip) UDP (:7705/:7706) + CVS1/CVB1 Binary WebSocket (ws://<ip>:7000/ws/compact_state)
 *    - Now Playing Media (ActivityManagerNative IIntentReceiver.Stub + system properties / AudioManager state)
 * 4. Embedded Web Server (:8766) serving turzx_92_mockup.html, /status.json, and /frame.jpg +
 *    Gen5W WindowManager (TYPE_VOLUME_OVERLAY 2020) Control & Live Preview Overlay.
 */
public class TurzxMonitorMain {

    public static final String MONITOR_VERSION = "1.5.1";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String CMD_FILE_PATH = "/data/local/tmp/hkg_turzxmonitor_cmd";
    private static final String SHARED_SYSMONITOR_JSON_PATH = "/data/local/tmp/hkg_monitor_state.json";
    private static final String SHARED_CARROTBRIDGE_JSON_PATH = "/data/local/tmp/carrotbridge_status.json";
    private static final String SHARED_CARROTBRIDGE_PID_PATH = "/data/local/tmp/carrotbridge_java.pid";
    private static final String TURZX_JSON_STATE_PATH = "/data/local/tmp/hkg_turzx_state.json";
    private static final String USB_LOG_SYNC_REQUEST_PATH = "/data/local/tmp/hkg_turzxmonitor_usbsync_req";
    private static final String DEBUG_DUMP_LOCAL_PATH = "/data/local/tmp/hkg_turzxmonitor_debug.txt";
    private static final String OTA_RELOAD_TRIGGER_PATH = "/data/local/tmp/hkg_turzxmonitor_ota_reload";
    public static final String GITHUB_RAW_HOST = "raw.githubusercontent.com";
    public static final String GITHUB_OTA_BASE_PATH = "/GRT47/HKG_5W_TurzxMonitor/main/ota";
    public static final int HTTP_PORT = 8766;
    public static final int CARROT_UDP_PORT_PRIMARY = 7705;
    public static final int CARROT_UDP_PORT_BROADCAST = 7706;
    public static final int CARROT_WS_PORT = 7000;

    // TURZX USB secondary monitor constants (9.2" 0x0092 1920x462, 8.8" 0x0088 1920x480, 12.3" 0x0123 1920x720)
    public static final int TURZX_USB_VID = 0x1CBE;
    public static final int TURZX_USB_PID_92 = 0x0092;
    public static final int TURZX_USB_PID_88 = 0x0088;
    public static final int TURZX_USB_PID_123 = 0x0123;

    public static boolean isSupportedTurzxPid(String pidHex) {
        if (pidHex == null) return false;
        String p = pidHex.trim().toLowerCase(Locale.US);
        return "0092".equals(p) || "0088".equals(p) || "0123".equals(p);
    }
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
    // Genuine HKMC_Navi_ExSL (com.mnsoft.navi.b.i.getVehicleIntakeStatus) & DATCService.getClimateInfo(5) / sendIntentForClimate(3, 12, val):
    // Queries DatcBypassStatus.getIntakeStatus() (0 = INTAKE_REC_OFF / Fresh Air, 1 = INTAKE_REC_ON / Recirculation)
    public static final int DATC_CLIMATE_INFO_INTAKE_STATUS = 5;
    public static final int DATC_OSD_FLAG_INTAKE_STATUS = 12;

    public static final class MonitorConfig {
        public boolean overlayEnabled = true;
        public boolean overlayExpanded = false;
        public boolean usbPushEnabled = true;
        public int usbBrightness = 85;
        public int usbRotation = 270;     // 270 or 90 (1920x462 -> 462x1920 portrait scanout)
        public int usbJpegQuality = 70;
        public int usbFps = 12;
        // TURZX CMD 0x0F (frame rate) sent once on connect; 0 = never send (panel firmware default)
        public int usbPanelFps = 0;
        // Linux key code that toggles the full-screen dashboard on a 3s long press; 0 = auto (MAP key from .kl)
        public int overlayHotkeyCode = 0;
        // Column 2 3D ADAS road (largest render cost); false = static background only
        public boolean hudRoadEnabled = true;
        public boolean usbIncrementalEncode = true; // v1.4.5: re-encode only changed MCU rows (RST-per-row JPEG)
        public boolean renderParallel = false;      // v1.4.8 default: single-core (render then encode); true = parallel
        public boolean hudDesignTesla = true;       // v1.5.0: Tesla-style HUD (false = classic Chromium 1:1 HUD)
        // Telemetry (Binder/CAN) poll period of the separate poll thread
        public int telemetryPollMs = 200;
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
        public boolean hvacRearDefog = false;
        public boolean hvacSteerHeat = false;
        public boolean hvacRecirc = false;
        public boolean hvacAds = true;
        public int hvacBlowerStep = 3;      // 0..8
        public int hvacBlowerMode = 2;      // 1=VENT, 2=B/L, 3=FLOOR, 4=MIX, 5=DEF
        public double hvacDrTempC = 21.5;
        public double hvacPsTempC = 21.5;
        public double outdoorTempC = 18.0;
        public double inCarTempC = 22.5;

        // 3. Comma 3X (carrot-wip) ADAS + Headunit CAN Steering/Speed + SDI/TBT + TPMS
        public boolean carrotConnected = false;
        public int carrotSpeedKph = 0;
        public int carrotCruiseKph = 0;
        public boolean carrotActive = false;
        public int carrotSdiType = -1;        // >= 0: active camera (Red circle), < 0: normal limit (Blue circle)
        public int carrotSdiSpeedLimit = 0;   // km/h (0 = none/unknown)
        public int carrotSdiDistM = 0;
        public int carrotTbtDistM = 0;
        public int carrotTbtDistNextM = 0;
        public String carrotTbtRoad = "";
        public String carrotIp = "";
        public float leadDistM = 0.0f;
        public float leadRelVelKph = 0.0f;
        public boolean hasLeadCar = false;
        public float steeringAngleDeg = 0.0f;
        public float laneConfidencePct = 0.0f;
        public float[] pathPoly = new float[]{0.0f, 0.0f, 0.0f, 0.0f};
        public float tpmsFlPsi = 38.0f;
        public float tpmsFrPsi = 38.0f;
        public float tpmsRlPsi = 39.0f;
        public float tpmsRrPsi = 39.0f;
        public int headLampStatus = -1;       // tmHscanService getAFLS11_HeadLampStatus() raw (v1.5.0)

        // 4. Now Playing Media
        public String mediaSource = "Bluetooth Audio";
        public String mediaTitle = "미디어 재생 대기 중";
        public String mediaArtist = "Bluetooth · USB · Radio";
        public String mediaAlbum = "";
        public boolean mediaPlaying = false;
        public int mediaPositionSec = 0;
        public int mediaDurationSec = 0;
        public Object albumArtBitmap = null;
        public String albumArtSource = "none";
        public String albumArtTrackKey = "";

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
            double auxSum = Math.max(0.0, climatePowerKw)
                    + Math.max(0.0, electronicsPowerKw)
                    + Math.max(0.0, batteryCarePowerKw);
            double subSum = drivePowerKw + auxSum;
            if (charging && hasPackCurrent && packPowerKw < -0.05) {
                return packPowerKw;
            }
            if (hasPackCurrent && packPowerKw < -0.4) {
                return packPowerKw;
            }
            if (hasSubPower && drivePowerKw < -0.05) {
                return subSum;
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
            if (charging || "P".equals(gearPos) || "N".equals(gearPos)) return 0.0;
            double auxKw = Math.max(0.0, climatePowerKw)
                    + Math.max(0.0, electronicsPowerKw)
                    + Math.max(0.0, batteryCarePowerKw);
            if (drivePowerKw < -0.05) {
                return drivePowerKw;
            }
            if (hasPackCurrent && packPowerKw < -0.5) {
                return packPowerKw;
            }
            if (drivePowerKw > 0.05) {
                if (hasPackCurrent && packPowerKw > drivePowerKw + auxKw + 0.8) {
                    return Math.max(drivePowerKw, packPowerKw - auxKw);
                }
                return drivePowerKw;
            }
            if (!hasSubPower && hasPackCurrent && packPowerKw > 0.5) {
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
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    TurzxUsbDriver.blankDisplayAndClose();
                }
            }, "TurzxShutdownBlank"));
        } catch (Throwable ignored) {
        }
        TurzxOverlayController.init(installDir);
        TurzxUsbDriver.startUsbPusherLoop(installDir);
        HotkeyWatcher.start();
        OtaUpdateManager.startAutoUpdateWorker(installDir);

        final File cmdFile = new File(CMD_FILE_PATH);
        while (true) {
            try {
                if (TurzxOverlayController.isUiLooperReady() && cmdFile.exists()) {
                    String cmd = readFirstLineQuick(cmdFile).toLowerCase(Locale.US);
                    cmdFile.delete();
                    DebugManager.logEvent("CMD", "Received command: " + cmd);
                    if ("toggle".equals(cmd)) {
                        TurzxUsbDriver.requestImmediateReconnectIfDisconnected();
                        TurzxOverlayController.toggle();
                    } else if ("hide".equals(cmd) || "off".equals(cmd)) {
                        TurzxOverlayController.setEnabledAndShow(false);
                    } else if ("usb-reset".equals(cmd)) {
                        TurzxUsbDriver.requestImmediateReconnectIfDisconnected();
                    } else if ("update".equals(cmd) || "ota".equals(cmd)) {
                        OtaUpdateManager.triggerManualOta(installDir, false);
                    } else if ("force-update".equals(cmd) || "force-ota".equals(cmd)) {
                        OtaUpdateManager.triggerManualOta(installDir, true);
                    } else if ("dump".equals(cmd) || "debug".equals(cmd) || "log".equals(cmd)) {
                        DebugManager.triggerAsyncDump(installDir);
                    } else {
                        TurzxUsbDriver.requestImmediateReconnectIfDisconnected();
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
    private static volatile byte[] cachedTransparentPng720x1920 = null;

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
    private static final Object TURZX_CIPHER_LOCK = new Object();
    private static Cipher turzxEncryptCipher = null;

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

            byte[] encrypted;
            synchronized (TURZX_CIPHER_LOCK) {
                // Cipher.getInstance() is a provider lookup (several ms on Dalvik) -> created once and reused.
                // doFinal() resets the cipher to its init state (same key + IV), so every packet encrypts identically.
                if (turzxEncryptCipher == null) {
                    Cipher cipher = Cipher.getInstance("DES/CBC/NoPadding");
                    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(TURZX_DES_KEY_BYTES, "DES"),
                            new IvParameterSpec(TURZX_DES_KEY_BYTES));
                    turzxEncryptCipher = cipher;
                }
                encrypted = turzxEncryptCipher.doFinal(plain);
            }

            byte[] packet = new byte[512];
            System.arraycopy(encrypted, 0, packet, 0, 504);
            packet[510] = (byte) 0xA1;
            packet[511] = (byte) 0x1A;
            return packet;
        } catch (Exception e) {
            synchronized (TURZX_CIPHER_LOCK) {
                turzxEncryptCipher = null; // rebuild on next packet
            }
            throw new RuntimeException("DES-CBC TURZX packet encryption failed", e);
        }
    }

    /**
     * Generates a valid width x height all-transparent (RGBA 0x00000000) PNG image in pure Java.
     * Sent once via CMD 102 upon USB connection to clear any persistent PNG overlay layer
     * so CMD 101 hardware-decoded JPEGs are visible underneath.
     */
    public static byte[] buildTransparentOverlayPng(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid PNG dimensions: " + width + "x" + height);
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            // PNG 8-byte signature
            out.write(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});

            // IHDR chunk (13 bytes): width, height, bitDepth=8, colorType=6 (RGBA)
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

            // IDAT chunk: height rows * (1 filter byte + width * 4 RGBA zero bytes)
            int rowBytes = 1 + width * 4;
            int batchRows = 32;
            byte[] zeros = new byte[rowBytes * batchRows];
            byte[] compBuf = new byte[4096];
            Deflater deflater = new Deflater(Deflater.BEST_SPEED);
            ByteArrayOutputStream idatBuf = new ByteArrayOutputStream(8192);
            int remainingRows = height;
            while (remainingRows > 0) {
                int rowsThisFeed = Math.min(batchRows, remainingRows);
                deflater.setInput(zeros, 0, rowsThisFeed * rowBytes);
                while (!deflater.needsInput()) {
                    int n = deflater.deflate(compBuf);
                    if (n > 0) idatBuf.write(compBuf, 0, n);
                }
                remainingRows -= rowsThisFeed;
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
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build " + width + "x" + height + " transparent PNG", e);
        }
    }

    public static byte[] buildTransparentOverlayPng480x1920() {
        byte[] cached = cachedTransparentPng480x1920;
        if (cached != null) return cached;
        cached = buildTransparentOverlayPng(480, 1920);
        cachedTransparentPng480x1920 = cached;
        return cached;
    }

    public static byte[] buildTransparentOverlayPng720x1920() {
        byte[] cached = cachedTransparentPng720x1920;
        if (cached != null) return cached;
        cached = buildTransparentOverlayPng(720, 1920);
        cachedTransparentPng720x1920 = cached;
        return cached;
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
        private static volatile int connectedPid = TURZX_USB_PID_92;
        private static volatile String connectedFirmware = "";
        private static volatile String statusText = "USB 검색 대기 (1cbe:0092/0123)";
        private static volatile int pushedFrames = 0;
        private static volatile double liveFps = 0.0;
        private static volatile int lastAppliedBrightness = -1;
        private static volatile byte[] latestLandscapeJpeg = null;
        private static volatile long latestLandscapeJpegMs = 0L;
        private static volatile long lastLandscapeDemandMs = 0L;
        private static volatile long lastOpenAttemptMs = 0L;
        private static final long LANDSCAPE_DEMAND_WINDOW_MS = 5000L;

        private static final long driverStartMs = System.currentTimeMillis();
        private static volatile long lastUsbHostNudgeMs = 0L;
        private static volatile int usbHostNudgeCount = 0;
        private static volatile String lastObservedDevPath = "";
        private static volatile long lastObservedDevPathFirstSeenMs = 0L;
        private static volatile int consecutiveOpenFailures = 0;

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

        public static int getConnectedPid() {
            return connectedPid;
        }

        public static String getConnectedPidHex() {
            return String.format(Locale.US, "%04x", connectedPid & 0xFFFF);
        }

        public static String getConnectedFirmware() {
            return connectedFirmware;
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

        /**
         * Called by the /frame.jpg handler: enables landscape encoding for the next few seconds and waits
         * briefly for a fresh frame, since landscape JPEGs are no longer encoded when nobody is watching.
         */
        public static byte[] awaitFreshLandscapeJpeg(long maxWaitMs) {
            long requestMs = System.currentTimeMillis();
            lastLandscapeDemandMs = requestMs;
            long deadline = requestMs + maxWaitMs;
            while (latestLandscapeJpegMs < requestMs - 1500L && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(40L);
                } catch (InterruptedException e) {
                    break;
                }
            }
            return latestLandscapeJpeg;
        }

        public static void requestImmediateReconnectIfDisconnected() {
            if (!connected) {
                lastUsbHostNudgeMs = 0L;
                consecutiveOpenFailures = 0;
            }
        }

        private static void writeSysfsQuick(String path, String value) {
            FileOutputStream fos = null;
            try {
                File f = new File(path);
                if (!f.exists()) return;
                fos = new FileOutputStream(f);
                fos.write(value.getBytes(UTF8));
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

        private static boolean isOtherUsbDeviceOrStoragePresent() {
            try {
                String mounts = readAllTextQuick(new File("/proc/mounts"));
                if (mounts.contains(" /storage/usb0 ")
                        || mounts.contains(" /mnt/media_rw/usb0 ")
                        || mounts.contains(" /storage/usb1 ")
                        || mounts.contains(" /mnt/usb0 ")) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            File sysUsb = new File("/sys/bus/usb/devices");
            File[] dirs = sysUsb.listFiles();
            if (dirs != null) {
                for (int i = 0; i < dirs.length; i++) {
                    File fVid = new File(dirs[i], "idVendor");
                    if (!fVid.isFile()) continue;
                    String vid = readFirstLineQuick(fVid).toLowerCase(Locale.US);
                    if (!vid.isEmpty() && !"1d6b".equals(vid) && !"1cbe".equals(vid)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static void cycleUsbHostVbus(String reason) {
            if (isOtherUsbDeviceOrStoragePresent()) {
                return;
            }
            DebugManager.logEvent("USB", "Cycling Telechips DWC_OTG host VBUS (" + reason + ")");
            statusText = "TURZX USB 포트 재인식 중...";
            writeSysfsQuick("/sys/module/tcc_dwc_otg/parameters/vbus_control_enable", "1");
            writeSysfsQuick("/sys/devices/dwc_otg/vbus", "off");
            writeSysfsQuick("/sys/devices/ehci_tcc.0/vbus", "off");
            writeSysfsQuick("/sys/devices/ohci_tcc.0/vbus", "off");
            try {
                Thread.sleep(850L);
            } catch (InterruptedException ignored) {
            }
            writeSysfsQuick("/sys/devices/dwc_otg/drdmode", "host");
            writeSysfsQuick("/sys/devices/dwc_otg/phystatus", "on");
            writeSysfsQuick("/sys/devices/dwc_otg/vbus", "on");
            writeSysfsQuick("/sys/devices/ehci_tcc.0/vbus", "on");
            writeSysfsQuick("/sys/devices/ohci_tcc.0/vbus", "on");
            try {
                Thread.sleep(1150L);
            } catch (InterruptedException ignored) {
            }
            lastObservedDevPath = "";
            lastObservedDevPathFirstSeenMs = 0L;
        }

        private static void nudgeUsbHostIfTurzxMissing() {
            long now = System.currentTimeMillis();
            if (now - driverStartMs < 3000L) {
                return;
            }
            if (usbHostNudgeCount >= 8) {
                return;
            }
            long cooldownMs = (usbHostNudgeCount == 0) ? 3500L : 22000L;
            if (lastUsbHostNudgeMs > 0L && (now - lastUsbHostNudgeMs) < cooldownMs) {
                return;
            }
            String vbusProp = getSystemProperty("sys.boot.vbus", "on");
            if ("0".equals(vbusProp) || "off".equalsIgnoreCase(vbusProp)) {
                return;
            }
            lastUsbHostNudgeMs = now;
            usbHostNudgeCount++;
            cycleUsbHostVbus("cold-boot/missing 1cbe:(0092/0088/0123) nudge #" + usbHostNudgeCount);
        }

        private static void resetTurzxUsbDeviceInSysfs() {
            File sysUsb = new File("/sys/bus/usb/devices");
            File[] dirs = sysUsb.listFiles();
            if (dirs == null) return;
            for (int i = 0; i < dirs.length; i++) {
                File d = dirs[i];
                File fVid = new File(d, "idVendor");
                File fPid = new File(d, "idProduct");
                if (!fVid.isFile() || !fPid.isFile()) continue;
                String vid = readFirstLineQuick(fVid).toLowerCase(Locale.US);
                String pid = readFirstLineQuick(fPid).toLowerCase(Locale.US);
                if ("1cbe".equals(vid) && isSupportedTurzxPid(pid)) {
                    File authFile = new File(d, "authorized");
                    if (authFile.exists()) {
                        DebugManager.logEvent("USB", "Toggling sysfs authorized (0->1) on " + d.getName());
                        writeSysfsQuick(authFile.getAbsolutePath(), "0");
                        try {
                            Thread.sleep(350L);
                        } catch (InterruptedException ignored) {
                        }
                        writeSysfsQuick(authFile.getAbsolutePath(), "1");
                        try {
                            Thread.sleep(750L);
                        } catch (InterruptedException ignored) {
                        }
                        lastObservedDevPath = "";
                        lastObservedDevPathFirstSeenMs = 0L;
                    }
                    break;
                }
            }
        }

        // ---- Pipeline (v1.4.3) ----
        // The old single loop ran poll -> render -> encode -> USB send -> panel ACK back to back, so the frame time was
        // the SUM of all stages (~200-250ms = 4-5 FPS). Now four threads:
        //   Turzx-TelemetryPoll : Binder/CAN polling, publishes the latest snapshot (off the frame path)
        //   TurzxUsbPusher      : Canvas render of frame N+2 into a free landscape buffer
        //   TurzxJpegEncoder    : JPEG encode of frame N+1 (v1.4.4: was serial with render; 4x Cortex-A53)
        //   TurzxUsbSender      : USB connect, bulk send of frame N and panel ACK wait
        // One-slot mailboxes with back-pressure link the stages, so throughput ~ the SLOWEST stage
        // (max(render, encode, send) instead of render + encode) and no frame is rendered just to be dropped.
        private static final long TELEMETRY_POLL_PERIOD_SCREEN_OFF_MS = 400L;
        private static final Object FRAME_SLOT_LOCK = new Object();
        private static byte[] pendingPanelJpeg = null; // guarded by FRAME_SLOT_LOCK

        // Render -> encode handoff of landscape buffers (TurzxFrameRenderer.LANDSCAPE_BUFFERS = 2)
        private static final Object ENCODE_SLOT_LOCK = new Object();
        private static int queuedEncodeBuf = -1;          // guarded by ENCODE_SLOT_LOCK: drawn, waiting for the encoder
        private static boolean queuedNeedPanel = false;    // guarded by ENCODE_SLOT_LOCK
        private static boolean queuedNeedLandscape = false; // guarded by ENCODE_SLOT_LOCK
        private static int encodingBuf = -1;              // guarded by ENCODE_SLOT_LOCK: being read by the encoder
        private static volatile double avgEncodeWaitMs = 0.0;
        private static volatile double lastAckMs = 0.0;
        private static volatile TelemetrySnapshot latestPolledSnap = null;
        private static volatile boolean screenOffActive = false;

        // Per-stage timing (EMA, ms) for /status.json + debug report
        private static volatile double avgPollMs = 0.0;
        private static volatile double avgTxMs = 0.0;
        private static volatile double avgAckMs = 0.0;
        private static volatile double avgJpegKb = 0.0;
        private static volatile double avgSlotWaitMs = 0.0;

        public static double getAvgPollMs() {
            return avgPollMs;
        }

        public static double getAvgTxMs() {
            return avgTxMs;
        }

        public static double getAvgAckMs() {
            return avgAckMs;
        }

        public static double getAvgJpegKb() {
            return avgJpegKb;
        }

        public static double getAvgSlotWaitMs() {
            return avgSlotWaitMs;
        }

        /** How long the render thread waited for the encoder to accept its previous frame (EMA, ms). */
        public static double getAvgEncodeWaitMs() {
            return avgEncodeWaitMs;
        }

        /**
         * Render thread: waits until the encoder has picked up the previously queued frame, then returns a landscape
         * buffer the encoder is not reading. Returns -1 on timeout (encoder stuck); nothing is reserved then.
         */
        static int acquireRenderBuffer(long maxWaitMs) throws InterruptedException {
            long t0 = System.currentTimeMillis();
            int buf = -1;
            synchronized (ENCODE_SLOT_LOCK) {
                long deadline = t0 + maxWaitMs;
                while (queuedEncodeBuf >= 0) {
                    long remain = deadline - System.currentTimeMillis();
                    if (remain <= 0L) break;
                    ENCODE_SLOT_LOCK.wait(remain);
                }
                if (queuedEncodeBuf < 0) {
                    buf = pickFreeBuffer(encodingBuf, -1, TurzxFrameRenderer.LANDSCAPE_BUFFERS);
                }
            }
            avgEncodeWaitMs = emaMs(avgEncodeWaitMs, System.currentTimeMillis() - t0);
            return buf;
        }

        /** First buffer index in [0, count) that is neither busy index; -1 if none. */
        static int pickFreeBuffer(int busyA, int busyB, int count) {
            for (int i = 0; i < count; i++) {
                if (i != busyA && i != busyB) return i;
            }
            return -1;
        }

        /** Render thread: hands a drawn buffer to the encoder (the slot is empty after acquireRenderBuffer). */
        static void queueEncodeJob(int buf, boolean needPanel, boolean needLandscape) {
            synchronized (ENCODE_SLOT_LOCK) {
                queuedEncodeBuf = buf;
                queuedNeedPanel = needPanel;
                queuedNeedLandscape = needLandscape;
                ENCODE_SLOT_LOCK.notifyAll();
            }
        }

        /** Encoder thread: blocks for the next drawn buffer and marks it as being encoded. Returns {buf, panel, land}. */
        static int[] takeEncodeJob(long maxWaitMs) throws InterruptedException {
            synchronized (ENCODE_SLOT_LOCK) {
                if (queuedEncodeBuf < 0 && maxWaitMs > 0L) {
                    ENCODE_SLOT_LOCK.wait(maxWaitMs);
                }
                if (queuedEncodeBuf < 0) return null;
                int[] job = new int[]{queuedEncodeBuf, queuedNeedPanel ? 1 : 0, queuedNeedLandscape ? 1 : 0};
                encodingBuf = queuedEncodeBuf;
                queuedEncodeBuf = -1;
                ENCODE_SLOT_LOCK.notifyAll();
                return job;
            }
        }

        /** Render thread (single-core mode): blocks until the encoder has no queued or running job. */
        static void waitEncodeIdle(long maxWaitMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + maxWaitMs;
            synchronized (ENCODE_SLOT_LOCK) {
                while (queuedEncodeBuf >= 0 || encodingBuf >= 0) {
                    long remain = deadline - System.currentTimeMillis();
                    if (remain <= 0L) break;
                    ENCODE_SLOT_LOCK.wait(remain);
                }
            }
        }

        /** Encoder thread: the buffer may be drawn again. */
        static void finishEncodeJob() {
            synchronized (ENCODE_SLOT_LOCK) {
                encodingBuf = -1;
                ENCODE_SLOT_LOCK.notifyAll();
            }
        }

        private static double emaMs(double prev, double sample) {
            return (prev <= 0.0) ? sample : (prev * 0.9 + sample * 0.1);
        }

        public static void startUsbPusherLoop(final String installDir) {
            Thread poll = new Thread(new Runnable() {
                @Override
                public void run() {
                    runTelemetryPollLoop();
                }
            }, "Turzx-TelemetryPoll");
            poll.setDaemon(true);
            poll.start();

            Thread sender = new Thread(new Runnable() {
                @Override
                public void run() {
                    runSenderLoop();
                }
            }, "TurzxUsbSender");
            sender.setDaemon(true);
            sender.start();

            Thread encoder = new Thread(new Runnable() {
                @Override
                public void run() {
                    runEncoderLoop();
                }
            }, "TurzxJpegEncoder");
            encoder.setDaemon(true);
            encoder.start();

            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    runLoop(installDir);
                }
            }, "TurzxUsbPusher");
            t.setDaemon(true);
            t.start();
        }

        private static void runTelemetryPollLoop() {
            while (true) {
                try {
                    long t0 = System.currentTimeMillis();
                    TelemetrySnapshot snap = TelemetryCollector.pollSnapshot(TurzxOverlayController.getSysContext());
                    long dt = System.currentTimeMillis() - t0;
                    avgPollMs = emaMs(avgPollMs, dt);
                    FpsBench.addPoll(dt);
                    if (snap != null) {
                        latestPolledSnap = snap;
                    }
                    long period = TelemetryCollector.isVehicleScreenOff()
                            ? Math.max(TELEMETRY_POLL_PERIOD_SCREEN_OFF_MS, LiveTuning.pollMs) : LiveTuning.pollMs;
                    Thread.sleep(Math.max(20L, period - dt));
                } catch (Throwable t) {
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }

        /** Render thread: blocks until the sender has taken the previous frame (it is being transmitted now). */
        private static void waitForFrameSlotFree(long maxWaitMs) throws InterruptedException {
            long t0 = System.currentTimeMillis();
            synchronized (FRAME_SLOT_LOCK) {
                long deadline = t0 + maxWaitMs;
                while (pendingPanelJpeg != null) {
                    long remain = deadline - System.currentTimeMillis();
                    if (remain <= 0L) break; // sender stuck: the next offer simply replaces the stale frame
                    FRAME_SLOT_LOCK.wait(remain);
                }
            }
            avgSlotWaitMs = emaMs(avgSlotWaitMs, System.currentTimeMillis() - t0);
        }

        private static void offerPanelFrame(byte[] jpeg) {
            synchronized (FRAME_SLOT_LOCK) {
                pendingPanelJpeg = jpeg;
                FRAME_SLOT_LOCK.notifyAll();
            }
        }

        private static byte[] takePanelFrame(long maxWaitMs) throws InterruptedException {
            synchronized (FRAME_SLOT_LOCK) {
                if (pendingPanelJpeg == null && maxWaitMs > 0L) {
                    FRAME_SLOT_LOCK.wait(maxWaitMs);
                }
                byte[] f = pendingPanelJpeg;
                if (f != null) {
                    pendingPanelJpeg = null;
                    FRAME_SLOT_LOCK.notifyAll();
                }
                return f;
            }
        }

        private static void clearPendingFrame() {
            synchronized (FRAME_SLOT_LOCK) {
                pendingPanelJpeg = null;
                FRAME_SLOT_LOCK.notifyAll();
            }
        }

        /** Render/encode producer. Never touches the USB device except the synchronized blank/close paths. */
        private static void runLoop(String installDir) {
            while (true) {
                try {
                    MonitorConfig cfg = TurzxOverlayController.getConfig();
                    long frameStartMs = System.currentTimeMillis();
                    TelemetrySnapshot snap = latestPolledSnap;
                    if (snap == null) {
                        Thread.sleep(50L); // first telemetry poll still running
                        continue;
                    }

                    boolean screenOff = "1".equals(getSystemProperty("tcc.hwc.lcd.disable", "0"))
                            || "false".equalsIgnoreCase(getSystemProperty("sys.boot.show_screen", "true"))
                            || TelemetryCollector.isVehicleScreenOff();
                    screenOffActive = screenOff;
                    if (screenOff) {
                        if (connected) {
                            DebugManager.logEvent("USB", "Vehicle ignition/screen OFF detected -> blanking TURZX panel and setting brightness=0");
                            clearPendingFrame();
                            blankDisplayAndClose();
                        }
                        statusText = "시동 OFF · 화면 절전 대기 중";
                        Thread.sleep(350L);
                        continue;
                    }

                    if (!cfg.usbPushEnabled) {
                        if (connected) {
                            closeUsbConnection();
                        }
                        statusText = "USB 출력 일시정지 (웹 :8766 활성)";
                    }

                    // Only render/encode what has a consumer: the panel when connected, the landscape JPEG
                    // only while someone polls /frame.jpg (the web viewer itself uses /status.json).
                    boolean needPanel = cfg.usbPushEnabled && connected;
                    boolean needLandscape = (System.currentTimeMillis() - lastLandscapeDemandMs) < LANDSCAPE_DEMAND_WINDOW_MS;
                    if (needPanel || needLandscape) {
                        // Back-pressure: wait until the encoder took the previous frame, then draw into the other buffer
                        int buf = acquireRenderBuffer(1000L);
                        if (buf >= 0) {
                            // Freshest telemetry after the (possible) wait above
                            TelemetrySnapshot fresh = latestPolledSnap;
                            if (TurzxFrameRenderer.renderHudFrame(fresh != null ? fresh : snap, buf)) {
                                queueEncodeJob(buf, cfg.usbPushEnabled && connected, needLandscape);
                                if (!LiveTuning.parallelPipeline) {
                                    // Single-core mode: no overlap, render N+1 only after frame N is encoded
                                    waitEncodeIdle(1500L);
                                }
                            }
                        }
                    }

                    int targetFps = Math.max(2, Math.min(25, LiveTuning.targetFps));
                    long targetPeriodMs = 1000L / targetFps;
                    long elapsedMs = System.currentTimeMillis() - frameStartMs;
                    long sleepMs = Math.max(5L, targetPeriodMs - elapsedMs);
                    Thread.sleep(sleepMs);
                } catch (Throwable t) {
                    clearPendingFrame();
                    closeUsbConnection();
                    try {
                        Thread.sleep(1500L);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }

        /** JPEG encoder: compresses frames drawn by the render thread and hands panel JPEGs to the sender. */
        private static void runEncoderLoop() {
            while (true) {
                int[] job = null;
                try {
                    job = takeEncodeJob(1000L);
                    if (job == null) continue;
                    boolean needPanel = job[1] != 0 && connected && !screenOffActive;
                    boolean needLandscape = job[2] != 0;
                    if (needPanel) {
                        // Do not encode a frame the sender cannot take yet (it would only be replaced)
                        waitForFrameSlotFree(600L);
                        needPanel = connected && !screenOffActive;
                    }
                    if (!needPanel && !needLandscape) continue;
                    byte[][] jpegs = TurzxFrameRenderer.encodeHudFrame(
                            job[0], LiveTuning.panelRotation(), LiveTuning.jpegQuality, needPanel, needLandscape);
                    if (jpegs != null && jpegs.length == 2) {
                        if (jpegs[0] != null) {
                            latestLandscapeJpeg = jpegs[0];
                            latestLandscapeJpegMs = System.currentTimeMillis();
                        }
                        byte[] panelJpeg = jpegs[1];
                        if (needPanel && connected && !screenOffActive && panelJpeg != null && panelJpeg.length > 0) {
                            offerPanelFrame(panelJpeg);
                        }
                    }
                } catch (Throwable t) {
                    try {
                        Thread.sleep(200L);
                    } catch (InterruptedException ignored) {
                    }
                } finally {
                    if (job != null) finishEncodeJob();
                }
            }
        }

        /** USB owner: connects to the panel and transmits frames handed over by the encoder thread. */
        private static void runSenderLoop() {
            long fpsWinStartMs = System.currentTimeMillis();
            int fpsWinCount = 0;
            while (true) {
                try {
                    FpsBench.tick(System.currentTimeMillis());
                    MonitorConfig cfg = TurzxOverlayController.getConfig();
                    if (cfg.usbPushEnabled && !connected && !screenOffActive && latestPolledSnap != null) {
                        long now = System.currentTimeMillis();
                        if ((now - lastOpenAttemptMs) >= 500L) {
                            lastOpenAttemptMs = now;
                            tryOpenTurzxUsbDevice();
                        }
                    }

                    byte[] frame = takePanelFrame(connected ? 500L : 250L);
                    int rstPhase = connected ? PanelRstTest.phase(System.currentTimeMillis()) : -1;
                    if (rstPhase >= 0) {
                        byte[] testFrame = PanelRstTest.frameFor(rstPhase);
                        if (testFrame != null) frame = testFrame;
                    }
                    if (frame == null) {
                        continue;
                    }

                    boolean sent = false;
                    boolean attempted = false;
                    synchronized (TurzxUsbDriver.class) {
                        if (connected) {
                            attempted = true;
                            if (LiveTuning.panelFpsDirty) {
                                LiveTuning.panelFpsDirty = false;
                                sendPanelFpsCommand(LiveTuning.panelFps);
                            }
                            if (lastAppliedBrightness != cfg.usbBrightness) {
                                if (sendBrightnessCommand(cfg.usbBrightness)) {
                                    lastAppliedBrightness = cfg.usbBrightness;
                                }
                            }
                            sent = sendJpegFrame(frame);
                            if (!sent) {
                                statusText = "USB 전송 오류 · 재연결 대기";
                                closeUsbConnection();
                            }
                        }
                    }
                    if (rstPhase >= 0 && attempted) {
                        PanelRstTest.record(rstPhase, sent, lastAckMs);
                    }
                    if (attempted && sent) {
                        pushedFrames++;
                        fpsWinCount++;
                        long now = System.currentTimeMillis();
                        if (now - fpsWinStartMs >= 1000L) {
                            liveFps = (fpsWinCount * 1000.0) / Math.max(1L, now - fpsWinStartMs);
                            fpsWinCount = 0;
                            fpsWinStartMs = now;
                        }
                        statusText = String.format(Locale.US, "TURZX %s 연결됨 (%s · %.1f FPS)",
                                TurzxFrameRenderer.getActivePanelLabel(), connectedDevPath, liveFps);
                    } else if (attempted) {
                        fpsWinCount = 0;
                        fpsWinStartMs = System.currentTimeMillis();
                    }
                } catch (Throwable t) {
                    try {
                        Thread.sleep(200L);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }

        /**
         * Scans /sys/bus/usb/devices/* for VID 1cbe & PID 0092 (9.2"), 0088 (8.8"), or 0123 (12.3")
         * and returns { "/dev/bus/usb/<busnum>/<devnum>", "<pidHex>" }.
         */
        public static String[] findTurzxUsbDevice() {
            File sysUsb = new File("/sys/bus/usb/devices");
            File[] dirs = sysUsb.listFiles();
            if (dirs == null) return null;
            for (int i = 0; i < dirs.length; i++) {
                File d = dirs[i];
                File fVid = new File(d, "idVendor");
                File fPid = new File(d, "idProduct");
                if (!fVid.isFile() || !fPid.isFile()) continue;
                String vid = readFirstLineQuick(fVid).trim().toLowerCase(Locale.US);
                String pid = readFirstLineQuick(fPid).trim().toLowerCase(Locale.US);
                if ("1cbe".equals(vid) && isSupportedTurzxPid(pid)) {
                    int busNum = parseIntSafe(readFirstLineQuick(new File(d, "busnum")), -1);
                    int devNum = parseIntSafe(readFirstLineQuick(new File(d, "devnum")), -1);
                    if (busNum > 0 && devNum > 0) {
                        String path = String.format(Locale.US, "/dev/bus/usb/%03d/%03d", busNum, devNum);
                        if (new File(path).exists()) {
                            return new String[]{path, pid};
                        }
                    }
                }
            }
            return null;
        }

        public static String findTurzxUsbDevicePath() {
            String[] found = findTurzxUsbDevice();
            return found != null ? found[0] : null;
        }

        private static void onUsbOpenFailure(String devPath, String msg) {
            closeUsbConnection();
            statusText = msg;
            consecutiveOpenFailures++;
            if (consecutiveOpenFailures == 3) {
                resetTurzxUsbDeviceInSysfs();
            } else if (consecutiveOpenFailures >= 6) {
                consecutiveOpenFailures = 0;
                cycleUsbHostVbus("repeated handshake/open failure on " + devPath);
            }
        }

        private static synchronized boolean tryOpenTurzxUsbDevice() {
            closeUsbConnection();
            String[] found = findTurzxUsbDevice();
            if (found == null) {
                nudgeUsbHostIfTurzxMissing();
                found = findTurzxUsbDevice();
                if (found == null) {
                    statusText = "TURZX USB 미감지 (1cbe:0092/0123 대기)";
                    return false;
                }
            }
            String devPath = found[0];
            String pidHex = found[1];
            if (!devPath.equals(lastObservedDevPath)) {
                lastObservedDevPath = devPath;
                lastObservedDevPathFirstSeenMs = System.currentTimeMillis();
            }
            long sinceFirstSeen = System.currentTimeMillis() - lastObservedDevPathFirstSeenMs;
            if (sinceFirstSeen < 700L) {
                statusText = "TURZX USB 초기화 대기 중 (" + devPath + ")";
                try {
                    Thread.sleep(700L - sinceFirstSeen);
                } catch (InterruptedException ignored) {
                }
                found = findTurzxUsbDevice();
                if (found == null) {
                    statusText = "TURZX USB 미감지 (1cbe:0092/0123 대기)";
                    return false;
                }
                devPath = found[0];
                pidHex = found[1];
                lastObservedDevPath = devPath;
            }
            int pidInt = TURZX_USB_PID_92;
            try {
                pidInt = Integer.parseInt(pidHex, 16);
            } catch (NumberFormatException ignored) {
            }
            try {
                try {
                    File devFile = new File(devPath);
                    devFile.setReadable(true, false);
                    devFile.setWritable(true, false);
                } catch (Throwable ignored) {
                }
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
                    onUsbOpenFailure(devPath, "USB 권한/FD 오픈 실패 (" + devPath + ")");
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
                    onUsbOpenFailure(devPath, "USB 오픈 실패 (" + devPath + ")");
                    return false;
                }

                Method claimIfc = usbConnCls.getDeclaredMethod("native_claim_interface", int.class, boolean.class);
                claimIfc.setAccessible(true);
                boolean claimed = ((Boolean) claimIfc.invoke(conn, Integer.valueOf(0), Boolean.TRUE)).booleanValue();
                if (!claimed) {
                    onUsbOpenFailure(devPath, "USB 인터페이스 점유 실패 (" + devPath + ")");
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
                connectedPid = pidInt;
                TurzxFrameRenderer.setActivePanelByPid(pidInt);

                // 1. Send CMD 10 (HELLO / Sync) with up to 3 attempts -> replies "turzx_0001_0015" / "turzx_0001_0026"
                byte[] helloPkt = buildTurzxCommandPacket(TURZX_CMD_HELLO, 0);
                boolean helloOk = false;
                String fwVer = "";
                for (int attempt = 1; attempt <= 3; attempt++) {
                    drainBulkIn(40);
                    if (bulkTransferOut(helloPkt, 0, helloPkt.length, 600)) {
                        int nRx = drainBulkIn(600);
                        if (nRx > 0) {
                            helloOk = true;
                            if (nRx >= 23) {
                                StringBuilder fwSb = new StringBuilder(16);
                                for (int k = 8; k < Math.min(nRx, 28); k++) {
                                    int ch = RX_BUF[k] & 0xFF;
                                    if (ch >= 0x20 && ch <= 0x7E) fwSb.append((char) ch);
                                    else break;
                                }
                                fwVer = fwSb.toString().trim();
                            }
                            break;
                        }
                    }
                    try {
                        Thread.sleep(180L);
                    } catch (InterruptedException ignored) {
                    }
                }
                connectedFirmware = fwVer;

                // 2. Send CMD 111 (Stop built-in storage video playback)
                byte[] stopVideoPkt = buildTurzxCommandPacket(TURZX_CMD_STOP_VIDEO, 0);
                boolean stopOk = bulkTransferOut(stopVideoPkt, 0, stopVideoPkt.length, 600);
                drainBulkIn(250);

                // 2b. Optional CMD 15 (frame rate) — experimental, only when usb_panel_fps > 0
                MonitorConfig cfg = TurzxOverlayController.getConfig();
                LiveTuning.panelFpsDirty = false;
                sendPanelFpsCommand(LiveTuning.panelFps);

                // 3. Send initial brightness (CMD 14)
                boolean brOk = sendBrightnessCommand(cfg.usbBrightness);
                if (brOk) {
                    lastAppliedBrightness = cfg.usbBrightness;
                } else {
                    lastAppliedBrightness = -1;
                }

                if (!helloOk && !brOk && !stopOk) {
                    onUsbOpenFailure(devPath, "TURZX 핸드셰이크 응답 없음 (" + devPath + ")");
                    return false;
                }

                // 4. Clear the RGBA PNG overlay layer (CMD 102: 720x1920 for 12.3", 480x1920 for 9.2"/8.8")
                byte[] clearPng = (pidInt == TURZX_USB_PID_123)
                        ? buildTransparentOverlayPng720x1920()
                        : buildTransparentOverlayPng480x1920();
                sendPayloadFrame(TURZX_CMD_SEND_PNG, clearPng);

                consecutiveOpenFailures = 0;
                usbHostNudgeCount = 0;
                connected = true;
                String panelLabel = TurzxFrameRenderer.getActivePanelLabel();
                statusText = "TURZX " + panelLabel + " 연결됨 (" + devPath + ")";
                DebugManager.logEvent("USB", "Connected to TURZX " + panelLabel + " (1cbe:" + pidHex
                        + ", " + TurzxFrameRenderer.getActiveResolutionString() + ") at " + devPath
                        + " (hello=" + helloOk + ", fw='" + fwVer + "', br=" + brOk + ")");
                return true;
            } catch (Throwable t) {
                onUsbOpenFailure(devPath, "USB 초기화 오류: " + t.getClass().getSimpleName());
                return false;
            }
        }

        public static synchronized void blankDisplayAndClose() {
            if (!connected && usbDevConn == null) return;
            try {
                byte[] blackJpeg = TurzxFrameRenderer.renderSolidBlackPanelJpeg();
                if (blackJpeg != null && blackJpeg.length > 0) {
                    sendJpegFrame(blackJpeg);
                }
            } catch (Throwable ignored) {
            }
            try {
                sendBrightnessCommand(0);
                sendBrightnessCommand(0);
            } catch (Throwable ignored) {
            }
            lastAppliedBrightness = -1;
            closeUsbConnection();
        }

        private static synchronized void closeUsbConnection() {
            connected = false;
            connectedDevPath = "";
            liveFps = 0.0; // was left at the last value after a disconnect
            clearPendingFrame();
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

        /** CMD 0x0F frame rate (experimental). 0 = not sent (a previously sent value stays until reconnect). */
        private static void sendPanelFpsCommand(int fps) {
            if (fps <= 0) return;
            byte[] fpsPkt = buildTurzxCommandPacket(TURZX_CMD_FPS, fps);
            boolean fpsOk = bulkTransferOut(fpsPkt, 0, fpsPkt.length, 400);
            int fpsReply = drainBulkIn(200);
            DebugManager.logEvent("USB", "Sent TURZX CMD 0x0F frame rate=" + fps
                    + " (out=" + fpsOk + ", reply=" + fpsReply + "B)");
        }

        private static boolean sendJpegFrame(byte[] jpegBytes) {
            return sendPayloadFrame(TURZX_CMD_SEND_JPEG, jpegBytes);
        }

        private static boolean sendPayloadFrame(int cmdId, byte[] payloadBytes) {
            if (payloadBytes == null || payloadBytes.length == 0) return false;
            long tTx0 = System.nanoTime();
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
            long tAck0 = System.nanoTime();
            boolean acked = drainBulkIn(350) > 0;
            if (acked) {
                lastAckMs = (System.nanoTime() - tAck0) / 1e6;
            }
            if (cmdId == TURZX_CMD_SEND_JPEG && acked) {
                avgTxMs = emaMs(avgTxMs, (tAck0 - tTx0) / 1e6);
                avgAckMs = emaMs(avgAckMs, (System.nanoTime() - tAck0) / 1e6);
                avgJpegKb = emaMs(avgJpegKb, payloadBytes.length / 1024.0);
                FpsBench.addFrame((tAck0 - tTx0) / 1e6, (System.nanoTime() - tAck0) / 1e6, payloadBytes.length / 1024.0);
            }
            return acked;
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
    // 1B. NATIVE libjpeg-turbo (NEON) JPEG ENCODER (libturzxjpeg.so, bundled in jar assets)
    // =========================================================================

    public static final class NativeJpeg {
        private static final String ASSET_NAME = "libturzxjpeg_armv7.so";
        private static final int MAX_CONSECUTIVE_FAILURES = 3;

        private static volatile boolean loadAttempted = false;
        private static volatile boolean available = false;
        private static volatile String info = "not loaded";
        private static int consecutiveFailures = 0;

        // ---- Crash guard ----
        // A SIGSEGV in native code kills the whole process (no Java catch). While the library is loaded and for
        // the first WARMUP_ENCODES frames an "in-flight" marker (hash:attempt) exists; if the process dies in that
        // window the marker survives. After MAX_GUARD_ATTEMPTS consecutive unfinished attempts with the same .so,
        // native encoding is disabled for that .so hash (Skia fallback) so the HUD and OTA keep working.
        // A new .so (different hash) is tried again. Re-enable manually: rm /data/local/tmp/hkg_turzxjpeg_disabled_*
        private static final String GUARD_DIR = "/data/local/tmp";
        private static final String INFLIGHT_NAME = "hkg_turzxjpeg_inflight";
        private static final String DISABLED_PREFIX = "hkg_turzxjpeg_disabled_";
        private static final int WARMUP_ENCODES = 50;
        static final int MAX_GUARD_ATTEMPTS = 2;
        private static int warmupRemaining = 0;

        /** Number of previous unfinished attempts for this .so hash, given the in-flight marker content (or null). */
        static int previousGuardAttempts(String inflightContent, String soHash) {
            if (inflightContent == null) return 0;
            String c = inflightContent.trim();
            int sep = c.lastIndexOf(':');
            if (sep <= 0 || !c.substring(0, sep).equals(soHash)) return 0;
            return parseIntSafe(c.substring(sep + 1), 0) + 1;
        }

        private static void clearInflight() {
            new File(GUARD_DIR, INFLIGHT_NAME).delete();
        }

        static native byte[] nEncode(Object bitmap, int rotation, int padWidth, int quality);

        static native String nInfo();

        static native byte[] nEncodeIncremental(Object bitmap, int rotation, int padWidth, int quality,
                                                boolean forceFull, int[] stats);

        private static volatile boolean incrementalAvailable = true;
        private static int incrementalFailures = 0;

        public static boolean isIncrementalAvailable() {
            return available && incrementalAvailable;
        }

        /**
         * v1.4.5: portrait JPEG with a restart marker after every MCU row, re-encoding only the rows whose landscape
         * strip changed since the previous call (output identical to a full RESTARTROWS=1 encode).
         * stats[0] = rows re-encoded, [1] = encoder calls, [2] = total rows. Null on failure (caller encodes in full).
         */
        public static byte[] encodeIncremental(Object bitmap, int rotation, int padWidth, int quality,
                                               boolean forceFull, int[] stats) {
            if (!isIncrementalAvailable()) return null;
            byte[] out = null;
            try {
                out = nEncodeIncremental(bitmap, rotation, padWidth, quality, forceFull, stats);
            } catch (UnsatisfiedLinkError e) {
                incrementalAvailable = false; // older .so without the symbol
                DebugManager.logEvent("RENDER", "Incremental JPEG encoder unavailable (" + e.getMessage() + ") -> full encode");
                return null;
            } catch (Throwable t) {
                DebugManager.logError("RENDER", "NativeJpeg.encodeIncremental", t);
            }
            if (out == null || out.length == 0) {
                if (++incrementalFailures >= MAX_CONSECUTIVE_FAILURES) {
                    incrementalAvailable = false;
                    DebugManager.logEvent("RENDER", "Incremental JPEG encoder disabled after " + incrementalFailures
                            + " failures -> full encode");
                }
                return null;
            }
            incrementalFailures = 0;
            countWarmupEncode();
            return out;
        }

        public static boolean isAvailable() {
            return available;
        }

        public static String getInfo() {
            return info;
        }

        /** Extracts the bundled .so (content-hash named, so OTA never overwrites a mapped file) and loads it once. */
        public static synchronized boolean ensureLoaded() {
            if (loadAttempted) return available;
            loadAttempted = true;
            try {
                String arch = System.getProperty("os.arch", "");
                if (!arch.startsWith("arm")) {
                    info = "skipped (os.arch=" + arch + ")";
                    return false;
                }
                byte[] so = TurzxFrameRenderer.readJarAssetBytesPublic(ASSET_NAME);
                if (so == null || so.length < 1024) {
                    info = "asset missing";
                    return false;
                }
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] dig = md.digest(so);
                StringBuilder hex = new StringBuilder(12);
                for (int i = 0; i < 6; i++) hex.append(String.format(Locale.US, "%02x", dig[i] & 0xFF));

                File disabledMarker = new File(GUARD_DIR, DISABLED_PREFIX + hex);
                if (disabledMarker.isFile()) {
                    info = "disabled by crash guard (" + hex + ")";
                    DebugManager.logEvent("RENDER", "Native JPEG encoder " + info + " -> Skia fallback");
                    return false;
                }
                File inflight = new File(GUARD_DIR, INFLIGHT_NAME);
                int attempts = previousGuardAttempts(inflight.isFile() ? readAllTextQuick(inflight) : null, hex.toString());
                if (attempts >= MAX_GUARD_ATTEMPTS) {
                    writeBytesNoSync(disabledMarker, ("process died " + attempts + "x during native load/warmup\n").getBytes(UTF8));
                    clearInflight();
                    info = "disabled by crash guard (" + hex + ", " + attempts + " crashes)";
                    DebugManager.logEvent("RENDER", "Native JPEG encoder " + info + " -> Skia fallback");
                    return false;
                }
                writeBytesNoSync(inflight, (hex + ":" + attempts).getBytes(UTF8));
                warmupRemaining = WARMUP_ENCODES;

                String installDir = TurzxOverlayController.getInstallDir();
                File dir = (installDir != null && !installDir.isEmpty() && new File(installDir, "bin").isDirectory())
                        ? new File(installDir, "bin")
                        : new File("/data/local/tmp");
                File target = new File(dir, "libturzxjpeg-" + hex + ".so");
                if (!target.isFile() || target.length() != so.length) {
                    writeBytesAtomic(target, so);
                }
                target.setReadable(true, false);
                target.setExecutable(true, false);
                File[] olds = dir.listFiles();
                if (olds != null) {
                    for (int i = 0; i < olds.length; i++) {
                        String n = olds[i].getName();
                        if (n.startsWith("libturzxjpeg-") && n.endsWith(".so") && !n.equals(target.getName())) {
                            olds[i].delete();
                        }
                    }
                }
                System.load(target.getAbsolutePath());
                info = nInfo();
                available = true;
                DebugManager.logEvent("RENDER", "Native JPEG encoder loaded: " + info + " (" + target.getAbsolutePath() + ")");
            } catch (Throwable t) {
                available = false;
                info = "load failed: " + t.getClass().getSimpleName() + ": " + t.getMessage();
                DebugManager.logError("RENDER", "NativeJpeg.ensureLoaded", t);
            }
            return available;
        }

        /** Returns JPEG bytes or null (caller falls back to Skia). Disables itself after repeated failures. */
        public static byte[] encode(Object bitmap, int rotation, int padWidth, int quality) {
            if (!available) return null;
            byte[] out = null;
            try {
                out = nEncode(bitmap, rotation, padWidth, quality);
            } catch (Throwable t) {
                DebugManager.logError("RENDER", "NativeJpeg.encode", t);
            }
            if (out == null || out.length == 0) {
                if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    available = false;
                    clearInflight();  // clean failure, not a crash
                    info = "disabled after " + consecutiveFailures + " encode failures";
                    DebugManager.logEvent("RENDER", "Native JPEG encoder " + info + " -> Skia fallback");
                }
                return null;
            }
            consecutiveFailures = 0;
            countWarmupEncode();
            return out;
        }

        /** Crash guard: the in-flight marker is cleared after WARMUP_ENCODES successful native encodes (any entry point). */
        private static synchronized void countWarmupEncode() {
            if (warmupRemaining > 0 && --warmupRemaining == 0) {
                clearInflight();
                DebugManager.logEvent("RENDER", "Native JPEG encoder passed " + WARMUP_ENCODES + "-frame warmup; crash guard cleared");
            }
        }
    }

    // =========================================================================
    // 2. ANDROID CANVAS 1920x462 / 1920x720 CLUSTER HUD FRAME RENDERER
    // =========================================================================

    public static final class TurzxFrameRenderer {
        public static final int HUD_WIDTH = 1920;
        public static final int HUD_HEIGHT = 462;
        public static final int HUD_HEIGHT_720 = 720;
        // TURZX 9.2" (0x0092) hardware JPEG decoder requires 16-pixel macroblock width: 29 * 16 = 464 (464x1920)
        // TURZX 8.8" (0x0088) uses 480x1920; TURZX 12.3" (0x0123) uses native 720x1920 (45 * 16 = 720).
        public static final int PANEL_JPEG_WIDTH = 464;
        public static final int PANEL_JPEG_WIDTH_88 = 480;
        public static final int PANEL_JPEG_WIDTH_123 = 720;
        public static final int PANEL_JPEG_HEIGHT = 1920;

        private static volatile int activePid = TURZX_USB_PID_92;
        private static volatile int activeHudHeight = HUD_HEIGHT;
        private static volatile int activePanelJpegWidth = PANEL_JPEG_WIDTH;
        private static int allocatedHudHeight = -1;
        private static int allocatedPanelJpegWidth = -1;
        private static int loadedSpriteHeight = -1;

        public static synchronized void setActivePanelByPid(int pid) {
            int p = pid & 0xFFFF;
            int newHudH;
            int newPadW;
            if (p == TURZX_USB_PID_123) {
                newHudH = HUD_HEIGHT_720;
                newPadW = PANEL_JPEG_WIDTH_123;
            } else if (p == TURZX_USB_PID_88) {
                newHudH = HUD_HEIGHT;
                newPadW = PANEL_JPEG_WIDTH_88;
            } else {
                p = TURZX_USB_PID_92;
                newHudH = HUD_HEIGHT;
                newPadW = PANEL_JPEG_WIDTH;
            }
            activePid = p;
            if (newHudH != activeHudHeight || newPadW != activePanelJpegWidth) {
                activeHudHeight = newHudH;
                activePanelJpegWidth = newPadW;
                cachedSolidBlackPanelJpeg = null;
            }
        }

        public static int getActivePid() {
            return activePid;
        }

        public static int getActiveHudHeight() {
            return activeHudHeight;
        }

        public static int getActivePanelJpegWidth() {
            return activePanelJpegWidth;
        }

        public static String getActivePanelLabel() {
            int p = activePid;
            if (p == TURZX_USB_PID_123) return "12.3\"";
            if (p == TURZX_USB_PID_88) return "8.8\"";
            return "9.2\"";
        }

        public static String getActiveResolutionString() {
            return HUD_WIDTH + "x" + activeHudHeight;
        }

        // v1.4.4: two landscape buffers so the render thread draws frame N+1 while the encoder thread
        // compresses frame N (TurzxUsbDriver hands buffers over; a buffer is never drawn and encoded at once).
        public static final int LANDSCAPE_BUFFERS = 2;
        private static final Object[] landscapeBitmaps = new Object[LANDSCAPE_BUFFERS];
        private static final Object[] landscapeCanvases = new Object[LANDSCAPE_BUFFERS];
        // Guards the encoder-side state (panelBitmap/panelCanvas/rotMatrix for the Skia fallback)
        private static final Object ENCODE_LOCK = new Object();
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

        // Pre-rendered Chromium 1:1 layer sprites from turzx_92_mockup.html (lazy-loaded per active panel height)
        private static Object bmpBaseShell = null;
        private static Object bmpActiveShell = null;
        private static Object bmpCol2Section = null;
        private static Object bmpCol2Traffic = null;
        private static Object bmpCol2None = null;
        private static boolean typefacesLoaded = false;

        private static float roadPhase = 0.0f;

        /** Exact 2x calibrated coordinates for 1920x462 (9.2"/8.8") vs 1920x720 (12.3") HUD layouts. */
        static final class HudLayout {
            final int hudH;
            // Column 1: EV cells
            final float socY, evBigFs, socBarY, evBarH;
            final float dteY, effY;
            final float pwrY, evMidFs, pwrBarY, cumY;
            final float batTempY, batMinMaxY;
            // Column 1: TPMS
            final int[] tpmsTag, tireFl, tireFr, tireRl, tireRr;
            final float tpmsFrontY, tpmsRearY, tpmsValFs, tpmsFootY;
            // Column 2: 3D Road + HUD stamps
            final float roadBottomY, roadClipBottomY, roadH, roadHorizonY, roadFy;
            final float gearX, gearY, gearFs;
            final float egoX, egoY, egoFs;
            final float cruiseX, cruiseY, cruiseFs;
            final float sdiSecX, sdiTrafX, sdiNoneX;
            final float sdiSecY, sdiTrafY, sdiNoneY, sdiLimitFs;
            final float sdiDistSecY, sdiDistTrafY;
            final float sdiAvgX, sdiAvgY, sdiAvgFs;
            final float tbtDistX, tbtDistY, tbtDistFs;
            final float tbtRoadX, tbtRoadY, tbtRoadFs;
            final float tbtNextX, tbtNextY, tbtNextFs;
            final float laneChipX, laneChipY, laneChipFs;
            final float leadChipX, leadChipY, leadChipFs;
            // Column 3: Media
            final float artX0, artY0, artX1, artY1, artRad;
            final float mediaTextX;
            final int titleMaxChars, subMaxChars;
            final float mediaTitleY, mediaTitleFs;
            final float mediaSubY, mediaSubFs;
            final float mediaBarY, mediaBarW, mediaBarH;
            final float mediaCurTimeY, mediaDurTimeX, mediaDurTimeY, mediaTimeFs;
            // Climate (Column 3 bottom on 9.2"/8.8", full-width bottom taskbar on 12.3")
            final float drvTempX, inTempX;
            final boolean showPasTemp;
            final float pasTempX, outTempX;
            final boolean outTempAlignRight;
            final float hvacTempY, hvacEnvY;
            final int[] dirUpA, dirUpB, dirMidA, dirMidB, dirDownA, dirDownB;
            final float fanStepX, fanStepY;
            final int[] fanOffClear;
            final float fanOffX;
            final float fanBarBase, fanBarStep, fanBarSegW;
            final int fanBarCopyX0, fanBarCopyY0, fanBarCopyY1;
            final int[][] hvacBtns;

            HudLayout(
                    int hudH,
                    float socY, float evBigFs, float socBarY, float evBarH,
                    float dteY, float effY,
                    float pwrY, float evMidFs, float pwrBarY, float cumY,
                    float batTempY, float batMinMaxY,
                    int[] tpmsTag, int[] tireFl, int[] tireFr, int[] tireRl, int[] tireRr,
                    float tpmsFrontY, float tpmsRearY, float tpmsValFs, float tpmsFootY,
                    float roadBottomY, float roadClipBottomY, float roadH, float roadHorizonY, float roadFy,
                    float gearX, float gearY, float gearFs,
                    float egoX, float egoY, float egoFs,
                    float cruiseX, float cruiseY, float cruiseFs,
                    float sdiSecX, float sdiTrafX, float sdiNoneX,
                    float sdiSecY, float sdiTrafY, float sdiNoneY, float sdiLimitFs,
                    float sdiDistSecY, float sdiDistTrafY,
                    float sdiAvgX, float sdiAvgY, float sdiAvgFs,
                    float tbtDistX, float tbtDistY, float tbtDistFs,
                    float tbtRoadX, float tbtRoadY, float tbtRoadFs,
                    float tbtNextX, float tbtNextY, float tbtNextFs,
                    float laneChipX, float laneChipY, float laneChipFs,
                    float leadChipX, float leadChipY, float leadChipFs,
                    float artX0, float artY0, float artX1, float artY1, float artRad,
                    float mediaTextX, int titleMaxChars, int subMaxChars,
                    float mediaTitleY, float mediaTitleFs,
                    float mediaSubY, float mediaSubFs,
                    float mediaBarY, float mediaBarW, float mediaBarH,
                    float mediaCurTimeY, float mediaDurTimeX, float mediaDurTimeY, float mediaTimeFs,
                    float drvTempX, float inTempX,
                    boolean showPasTemp, float pasTempX, float outTempX, boolean outTempAlignRight,
                    float hvacTempY, float hvacEnvY,
                    int[] dirUpA, int[] dirUpB, int[] dirMidA, int[] dirMidB, int[] dirDownA, int[] dirDownB,
                    float fanStepX, float fanStepY, int[] fanOffClear, float fanOffX,
                    float fanBarBase, float fanBarStep, float fanBarSegW,
                    int fanBarCopyX0, int fanBarCopyY0, int fanBarCopyY1,
                    int[][] hvacBtns
            ) {
                this.hudH = hudH;
                this.socY = socY; this.evBigFs = evBigFs; this.socBarY = socBarY; this.evBarH = evBarH;
                this.dteY = dteY; this.effY = effY;
                this.pwrY = pwrY; this.evMidFs = evMidFs; this.pwrBarY = pwrBarY; this.cumY = cumY;
                this.batTempY = batTempY; this.batMinMaxY = batMinMaxY;
                this.tpmsTag = tpmsTag; this.tireFl = tireFl; this.tireFr = tireFr; this.tireRl = tireRl; this.tireRr = tireRr;
                this.tpmsFrontY = tpmsFrontY; this.tpmsRearY = tpmsRearY; this.tpmsValFs = tpmsValFs; this.tpmsFootY = tpmsFootY;
                this.roadBottomY = roadBottomY; this.roadClipBottomY = roadClipBottomY;
                this.roadH = roadH; this.roadHorizonY = roadHorizonY; this.roadFy = roadFy;
                this.gearX = gearX; this.gearY = gearY; this.gearFs = gearFs;
                this.egoX = egoX; this.egoY = egoY; this.egoFs = egoFs;
                this.cruiseX = cruiseX; this.cruiseY = cruiseY; this.cruiseFs = cruiseFs;
                this.sdiSecX = sdiSecX; this.sdiTrafX = sdiTrafX; this.sdiNoneX = sdiNoneX;
                this.sdiSecY = sdiSecY; this.sdiTrafY = sdiTrafY; this.sdiNoneY = sdiNoneY; this.sdiLimitFs = sdiLimitFs;
                this.sdiDistSecY = sdiDistSecY; this.sdiDistTrafY = sdiDistTrafY;
                this.sdiAvgX = sdiAvgX; this.sdiAvgY = sdiAvgY; this.sdiAvgFs = sdiAvgFs;
                this.tbtDistX = tbtDistX; this.tbtDistY = tbtDistY; this.tbtDistFs = tbtDistFs;
                this.tbtRoadX = tbtRoadX; this.tbtRoadY = tbtRoadY; this.tbtRoadFs = tbtRoadFs;
                this.tbtNextX = tbtNextX; this.tbtNextY = tbtNextY; this.tbtNextFs = tbtNextFs;
                this.laneChipX = laneChipX; this.laneChipY = laneChipY; this.laneChipFs = laneChipFs;
                this.leadChipX = leadChipX; this.leadChipY = leadChipY; this.leadChipFs = leadChipFs;
                this.artX0 = artX0; this.artY0 = artY0; this.artX1 = artX1; this.artY1 = artY1; this.artRad = artRad;
                this.mediaTextX = mediaTextX; this.titleMaxChars = titleMaxChars; this.subMaxChars = subMaxChars;
                this.mediaTitleY = mediaTitleY; this.mediaTitleFs = mediaTitleFs;
                this.mediaSubY = mediaSubY; this.mediaSubFs = mediaSubFs;
                this.mediaBarY = mediaBarY; this.mediaBarW = mediaBarW; this.mediaBarH = mediaBarH;
                this.mediaCurTimeY = mediaCurTimeY; this.mediaDurTimeX = mediaDurTimeX;
                this.mediaDurTimeY = mediaDurTimeY; this.mediaTimeFs = mediaTimeFs;
                this.drvTempX = drvTempX; this.inTempX = inTempX;
                this.showPasTemp = showPasTemp; this.pasTempX = pasTempX;
                this.outTempX = outTempX; this.outTempAlignRight = outTempAlignRight;
                this.hvacTempY = hvacTempY; this.hvacEnvY = hvacEnvY;
                this.dirUpA = dirUpA; this.dirUpB = dirUpB;
                this.dirMidA = dirMidA; this.dirMidB = dirMidB;
                this.dirDownA = dirDownA; this.dirDownB = dirDownB;
                this.fanStepX = fanStepX; this.fanStepY = fanStepY;
                this.fanOffClear = fanOffClear; this.fanOffX = fanOffX;
                this.fanBarBase = fanBarBase; this.fanBarStep = fanBarStep; this.fanBarSegW = fanBarSegW;
                this.fanBarCopyX0 = fanBarCopyX0; this.fanBarCopyY0 = fanBarCopyY0; this.fanBarCopyY1 = fanBarCopyY1;
                this.hvacBtns = hvacBtns;
            }
        }

        static final HudLayout LAYOUT_462 = new HudLayout(
                462,
                152f, 52f, 208f, 8f,
                138f, 204f,
                338f, 44f, 370.3f, 424f,
                354f, 424f,
                new int[]{520, 24, 586, 62},
                new int[]{480, 166, 491, 197}, new int[]{513, 166, 524, 197},
                new int[]{480, 255, 491, 286}, new int[]{513, 255, 524, 286},
                170f, 330f, 36f, 423f,
                448f, 449f, 434f, 130f, 950f,
                655f, 69f, 30f,
                732f, 88f, 60f,
                800f, 90f, 32f,
                680.8f, 680.8f, 680.8f,
                195f, 183f, 158f, 36f,
                245f, 233f,
                785f, 204f, 40f,
                1034.2f, 72f, 40f,
                984.2f, 115f, 24f,
                1154f, 114f, 24f,
                706f, 423f, 24f,
                1017.2f, 421f, 24f,
                1234f, 32f, 1406f, 204f, 18f,
                1436f, 16, 24,
                81f, 48f,
                131f, 30f,
                154.5f, 388.9f, 10f,
                196f, 1824.9f, 195f, 26f,
                1228f, 1275.5f,
                true, 1882f, 1882f, true,
                297f, 337f,
                new int[]{1396, 260, 1425, 286}, new int[]{1425, 260, 1445, 284},
                new int[]{1396, 287, 1425, 304}, new int[]{1425, 285, 1446, 307},
                new int[]{1396, 305, 1425, 331}, new int[]{1425, 308, 1445, 331},
                1687f, 287f, new int[]{1650, 256, 1720, 292}, 1715f,
                1539.4f, 26.3f, 20f,
                1538, 298, 336,
                new int[][]{
                        {1226, 374, 1315, 436}, {1320, 374, 1411, 436}, {1416, 374, 1505, 436},
                        {1510, 374, 1599, 436}, {1604, 374, 1693, 436}, {1698, 374, 1789, 436},
                        {1794, 374, 1884, 436}
                }
        );

        static final HudLayout LAYOUT_720 = new HudLayout(
                720,
                188.9f, 58f, 269f, 11f,
                176.3f, 269f,
                436.7f, 48f, 486.5f, 560f,
                462.7f, 560f,
                new int[]{520, 32, 586, 72},
                new int[]{475, 221, 489, 259}, new int[]{515, 221, 529, 259},
                new int[]{475, 329, 489, 367}, new int[]{515, 329, 529, 367},
                210.3f, 426.3f, 37f, 557.5f,
                590f, 591f, 576f, 172f, 1180f,
                659.9f, 80.9f, 33f,
                743.3f, 101.7f, 68f,
                816.9f, 101.5f, 34f,
                682.8f, 682.8f, 674.0f,
                217.5f, 205.5f, 180.3f, 38f,
                270.8f, 258.8f,
                787.1f, 227.5f, 42f,
                1018.2f, 83.4f, 44f,
                968.2f, 128.1f, 25f,
                1150f, 127.0f, 25f,
                713.1f, 555.2f, 25f,
                1005.9f, 553.1f, 25f,
                1409f, 46f, 1701f, 338f, 18f,
                1240f, 25, 36,
                408.5f, 48f,
                460.5f, 30f,
                509f, 630f, 12f,
                555.3f, 1870f, 554.3f, 27f,
                36f, 83.5f,
                false, -1f, 251.5f, false,
                660f, 704f,
                new int[]{439, 627, 468, 653}, new int[]{468, 627, 488, 651},
                new int[]{439, 653, 468, 672}, new int[]{468, 652, 489, 674},
                new int[]{439, 673, 468, 698}, new int[]{468, 675, 488, 698},
                850.4f, 655f, new int[]{810, 624, 883, 662}, 879f,
                582.0f, 43.57f, 36.6f,
                580, 666, 702,
                new int[][]{
                        {933, 621, 1060, 707}, {1070, 621, 1198, 707}, {1208, 621, 1335, 707},
                        {1345, 621, 1473, 707}, {1483, 621, 1610, 707}, {1620, 621, 1748, 707},
                        {1758, 621, 1885, 707}
                }
        );

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

        static byte[] readJarAssetBytesPublic(String assetName) {
            return readJarAssetBytes(assetName);
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

        private static void recycleBitmapQuietly(Class<?> bmpCls, Object bmp) {
            if (bmp == null || bmpCls == null) return;
            try {
                bmpCls.getMethod("recycle").invoke(bmp);
            } catch (Throwable ignored) {
            }
        }

        private static void ensureTypefacesLoaded(Class<?> tfCls, Class<?> paintCls) {
            if (typefacesLoaded) return;
            typefacesLoaded = true;
            try {
                Object sysBoldTf = tfCls.getField("DEFAULT_BOLD").get(null);
                Object sysMedTf = tfCls.getField("SANS_SERIF").get(null);
                Object pubBoldTf = loadBundledTypeface(tfCls, "PublicSans-Bold.ttf", "hkg_public_sans_bold.ttf", sysBoldTf);
                Object pubMedTf = loadBundledTypeface(tfCls, "PublicSans-Medium.ttf", "hkg_public_sans_medium.ttf", sysMedTf);

                Method setTf = paintCls.getMethod("setTypeface", tfCls);
                setTf.invoke(paintPublicBold, pubBoldTf);
                setTf.invoke(paintPublicMed, pubMedTf);
                setTf.invoke(paintSysBold, sysBoldTf);
                setTf.invoke(paintSysMed, sysMedTf);
            } catch (Throwable t) {
                DebugManager.logError("RENDER", "ensureTypefacesLoaded", t);
            }
        }

        private static void ensurePanelSpritesLoaded(Class<?> bmpCls, int hudH) {
            if (loadedSpriteHeight == hudH && bmpBaseShell != null) return;
            try {
                recycleBitmapQuietly(bmpCls, bmpBaseShell);
                recycleBitmapQuietly(bmpCls, bmpActiveShell);
                recycleBitmapQuietly(bmpCls, bmpCol2Section);
                recycleBitmapQuietly(bmpCls, bmpCol2Traffic);
                recycleBitmapQuietly(bmpCls, bmpCol2None);
                bmpBaseShell = null;
                bmpActiveShell = null;
                bmpCol2Section = null;
                bmpCol2Traffic = null;
                bmpCol2None = null;

                Class<?> bfCls = Class.forName("android.graphics.BitmapFactory");
                Method decodeByteArray = bfCls.getMethod("decodeByteArray", byte[].class, int.class, int.class);
                String suffix = (hudH == HUD_HEIGHT_720) ? "_720" : "";
                bmpBaseShell = decodeAssetBitmap(decodeByteArray, "base_shell" + suffix + ".png");
                bmpActiveShell = decodeAssetBitmap(decodeByteArray, "active_shell" + suffix + ".png");
                bmpCol2Section = decodeAssetBitmap(decodeByteArray, "col2_hud_section" + suffix + ".png");
                bmpCol2Traffic = decodeAssetBitmap(decodeByteArray, "col2_hud_traffic" + suffix + ".png");
                bmpCol2None = decodeAssetBitmap(decodeByteArray, "col2_hud_none" + suffix + ".png");
                loadedSpriteHeight = hudH;

                DebugManager.logEvent("RENDER", "Chromium 1:1 HUD layers loaded (" + HUD_WIDTH + "x" + hudH + "): base="
                        + (bmpBaseShell != null) + " active=" + (bmpActiveShell != null)
                        + " col2=" + (bmpCol2Section != null));
            } catch (Throwable t) {
                DebugManager.logError("RENDER", "ensurePanelSpritesLoaded(" + hudH + ")", t);
            }
        }

        private static Class<?>[] gfxClasses = null;
        private static volatile String lastEncoderName = "-";
        private static volatile double avgRenderMs = 0.0;
        private static volatile double avgEncodeMs = 0.0;

        public static String getLastEncoderName() {
            return lastEncoderName;
        }

        public static double getAvgRenderMs() {
            return avgRenderMs;
        }

        public static double getAvgEncodeMs() {
            return avgEncodeMs;
        }

        private static void recordEncodeMs(double ms) {
            avgEncodeMs = ema(avgEncodeMs, ms);
            FpsBench.addEncode(ms);
        }

        private static double ema(double prev, double sample) {
            return (prev <= 0.0) ? sample : (prev * 0.9 + sample * 0.1);
        }

        private static volatile double avgRoadMs = 0.0;
        private static boolean teslaFailureLogged = false;

        public static double getAvgRoadMs() {
            return avgRoadMs;
        }

        // v1.4.5 incremental encoder stats (encoder thread writes, UI/report read)
        private static final int[] incStats = new int[3];
        private static volatile double avgDirtyRows = 0.0;
        private static volatile int lastTotalRows = 0;
        private static volatile boolean incrementalActive = false;

        public static boolean isIncrementalActive() {
            return incrementalActive;
        }

        /** "37/120" style average re-encoded MCU rows, or "" when the full-frame encoder is in use. */
        public static String getDirtyRowsText() {
            return (incrementalActive && lastTotalRows > 0)
                    ? String.format(Locale.US, "%.0f/%d", avgDirtyRows, lastTotalRows) : "";
        }

        /** Lazily creates or resizes bitmaps, canvases, paints, and sprites for the active TURZX panel. */
        private static void ensureRenderResources() throws Exception {
            if (gfxClasses == null) {
                String[] names = new String[]{
                        "android.graphics.Bitmap", "android.graphics.Bitmap$Config", "android.graphics.Canvas",
                        "android.graphics.Paint", "android.graphics.Paint$Style", "android.graphics.Paint$Align",
                        "android.graphics.Typeface", "android.graphics.RectF", "android.graphics.Rect",
                        "android.graphics.Path", "android.graphics.Matrix", "android.graphics.Bitmap$CompressFormat"
                };
                Class<?>[] loaded = new Class<?>[names.length];
                for (int i = 0; i < names.length; i++) loaded[i] = Class.forName(names[i]);
                gfxClasses = loaded;
            }
            int targetHudH = activeHudHeight;
            int targetPadW = activePanelJpegWidth;
            if (landscapeBitmaps[LANDSCAPE_BUFFERS - 1] != null
                    && allocatedHudHeight == targetHudH
                    && allocatedPanelJpegWidth == targetPadW
                    && loadedSpriteHeight == targetHudH) {
                return;
            }

            Class<?> bmpCls = gfxClasses[0];
            Class<?> cfgCls = gfxClasses[1];
            Class<?> canvasCls = gfxClasses[2];
            Class<?> paintCls = gfxClasses[3];
            Class<?> styleCls = gfxClasses[4];
            Class<?> tfCls = gfxClasses[6];
            Class<?> rectFCls = gfxClasses[7];
            Class<?> rectCls = gfxClasses[8];
            Class<?> matrixCls = gfxClasses[10];

            Object argb8888 = cfgCls.getField("ARGB_8888").get(null);
            Method createBitmap = bmpCls.getMethod("createBitmap", int.class, int.class, cfgCls);

            if (paintFill == null) {
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
            }

            ensureTypefacesLoaded(tfCls, paintCls);
            ensurePanelSpritesLoaded(bmpCls, targetHudH);

            if (panelBitmap == null || allocatedPanelJpegWidth != targetPadW) {
                synchronized (ENCODE_LOCK) {
                    recycleBitmapQuietly(bmpCls, panelBitmap);
                    panelBitmap = createBitmap.invoke(null, Integer.valueOf(targetPadW), Integer.valueOf(PANEL_JPEG_HEIGHT), argb8888);
                    panelCanvas = canvasCls.getConstructor(bmpCls).newInstance(panelBitmap);
                    if (rotMatrix == null) {
                        rotMatrix = matrixCls.getConstructor().newInstance();
                    }
                    allocatedPanelJpegWidth = targetPadW;
                }
            }

            if (landscapeBitmaps[LANDSCAPE_BUFFERS - 1] == null || allocatedHudHeight != targetHudH) {
                synchronized (ENCODE_LOCK) {
                    for (int i = 0; i < LANDSCAPE_BUFFERS; i++) {
                        recycleBitmapQuietly(bmpCls, landscapeBitmaps[i]);
                        Object bmp = createBitmap.invoke(null, Integer.valueOf(HUD_WIDTH), Integer.valueOf(targetHudH), argb8888);
                        landscapeCanvases[i] = canvasCls.getConstructor(bmpCls).newInstance(bmp);
                        landscapeBitmaps[i] = bmp;
                    }
                }
                recycleBitmapQuietly(bmpCls, roadBgBitmap);
                roadBgBitmap = null;
                roadBgCanvas = null;
                roadBgKey = Integer.MIN_VALUE;
                roadSurfShaders = null;
                roadStripShaders = null;
                roadTrajShaders = null;
                roadTrajKey = 0;
                artShader = null;
                artShaderBitmap = null;
                allocatedHudHeight = targetHudH;
            }
        }

        /**
         * Render thread: draws the 1920x462 or 1920x720 HUD frame into landscape buffer {@code buf}.
         * The caller guarantees the encoder is not reading that buffer at the same time.
         */
        public static synchronized boolean renderHudFrame(TelemetrySnapshot s, int buf) {
            if (s == null || buf < 0 || buf >= LANDSCAPE_BUFFERS) return false;
            try {
                ensureRenderResources();
                Class<?> bmpCls = gfxClasses[0];
                Class<?> canvasCls = gfxClasses[2];
                Class<?> paintCls = gfxClasses[3];
                Class<?> alignCls = gfxClasses[5];
                Class<?> rectFCls = gfxClasses[7];
                Class<?> rectCls = gfxClasses[8];
                Class<?> pathCls = gfxClasses[9];

                int spd = (s.carrotSpeedKph > 0) ? s.carrotSpeedKph : 0;
                if (spd > 0) {
                    roadPhase = (roadPhase + (spd / 180.0f)) % 36.0f;
                }

                long tRender0 = System.nanoTime();
                boolean drawn = false;
                if (LiveTuning.teslaDesign) {
                    // v1.5.0 Tesla-style design; any failure falls back to the classic HUD for this frame
                    try {
                        TeslaHud.draw(landscapeCanvases[buf], s);
                        drawn = true;
                    } catch (Throwable t) {
                        if (!teslaFailureLogged) {
                            teslaFailureLogged = true;
                            DebugManager.logError("RENDER", "TeslaHud.draw -> classic HUD", t);
                        }
                    }
                }
                if (!drawn) {
                    drawHudToCanvas(landscapeCanvases[buf], paintFill, paintStroke, paintShader,
                            paintPublicBold, paintPublicMed, paintSysBold, paintSysMed,
                            rectF, srcRect, dstRect,
                            bmpCls, canvasCls, paintCls, alignCls, rectFCls, rectCls, pathCls, s);
                }
                double ms = (System.nanoTime() - tRender0) / 1e6;
                avgRenderMs = ema(avgRenderMs, ms);
                FpsBench.addRender(ms);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        /**
         * Encoder thread: compresses landscape buffer {@code buf} (already drawn by {@link #renderHudFrame}) into:
         * - result[0]: 1920x462 or 1920x720 landscape JPEG (web /frame.jpg) — only when needLandscape, else null
         * - result[1]: rotated 464x1920 / 480x1920 / 720x1920 portrait JPEG (TURZX CMD 101) — only when needPanel, else null
         * Encoding uses libjpeg-turbo NEON (rotation done in native code); Skia is the fallback.
         */
        public static byte[][] encodeHudFrame(int buf, int rotationDeg, int jpegQuality,
                                              boolean needPanel, boolean needLandscape) {
            if (!needPanel && !needLandscape) return null;
            if (buf < 0 || buf >= LANDSCAPE_BUFFERS || gfxClasses == null) return null;
            synchronized (ENCODE_LOCK) {
                // Read the buffer under the lock: a panel switch re-creates (and recycles) the buffers under ENCODE_LOCK
                Object src = landscapeBitmaps[buf];
                if (src == null) return null;
                try {
                    Class<?> bmpCls = gfxClasses[0];
                    Class<?> canvasCls = gfxClasses[2];
                    Class<?> paintCls = gfxClasses[3];
                    Class<?> matrixCls = gfxClasses[10];
                    Class<?> fmtCls = gfxClasses[11];

                    long tEncode0 = System.nanoTime();
                    int q = Math.max(50, Math.min(95, jpegQuality));
                    int nativeRot = (rotationDeg == 90) ? 90 : 270;
                    int padW = activePanelJpegWidth;
                    byte[] landBytes = null;
                    byte[] panelBytes = null;

                    if (NativeJpeg.ensureLoaded()) {
                        boolean ok = true;
                        if (needPanel) {
                            boolean inc = false;
                            if (LiveTuning.incrementalEncode && NativeJpeg.isIncrementalAvailable()) {
                                int[] st = incStats;
                                panelBytes = NativeJpeg.encodeIncremental(src, nativeRot, padW, q, false, st);
                                if (panelBytes != null && st[2] > 0) {
                                    inc = true;
                                    avgDirtyRows = ema(avgDirtyRows, st[0]);
                                    lastTotalRows = st[2];
                                }
                            }
                            if (!inc) {
                                panelBytes = NativeJpeg.encode(src, nativeRot, padW, q);
                                avgDirtyRows = 0.0;
                                lastTotalRows = 0;
                            }
                            incrementalActive = inc;
                            ok = panelBytes != null;
                        }
                        if (ok && needLandscape) {
                            landBytes = NativeJpeg.encode(src, 0, 0, q);
                            ok = landBytes != null;
                        }
                        if (ok) {
                            lastEncoderName = NativeJpeg.getInfo() + (incrementalActive ? " +incremental" : "");
                            recordEncodeMs((System.nanoTime() - tEncode0) / 1e6);
                            return new byte[][]{landBytes, panelBytes};
                        }
                    }

                    // ---- Skia Bitmap.compress() fallback ----
                    lastEncoderName = "skia";
                    Object jpegFmt = fmtCls.getField("JPEG").get(null);
                    Method compressM = bmpCls.getMethod("compress", fmtCls, int.class, OutputStream.class);

                    if (needLandscape) {
                        ByteArrayOutputStream baosLand = new ByteArrayOutputStream(65536);
                        compressM.invoke(src, jpegFmt, Integer.valueOf(q), baosLand);
                        landBytes = baosLand.toByteArray();
                    }
                    if (!needPanel || panelCanvas == null) {
                        recordEncodeMs((System.nanoTime() - tEncode0) / 1e6);
                        return new byte[][]{landBytes, null};
                    }

                    int srcH = ((Integer) bmpCls.getMethod("getHeight").invoke(src)).intValue();
                    canvasCls.getMethod("drawColor", int.class).invoke(panelCanvas, Integer.valueOf(0xFF000000));
                    matrixCls.getMethod("reset").invoke(rotMatrix);
                    if (rotationDeg == 90) {
                        matrixCls.getMethod("postRotate", float.class).invoke(rotMatrix, Float.valueOf(270.0f));
                        matrixCls.getMethod("postTranslate", float.class, float.class)
                                .invoke(rotMatrix, Float.valueOf(0.0f), Float.valueOf((float) HUD_WIDTH));
                    } else {
                        matrixCls.getMethod("postRotate", float.class).invoke(rotMatrix, Float.valueOf(90.0f));
                        matrixCls.getMethod("postTranslate", float.class, float.class)
                                .invoke(rotMatrix, Float.valueOf((float) srcH), Float.valueOf(0.0f));
                    }
                    canvasCls.getMethod("drawBitmap", bmpCls, matrixCls, paintCls)
                            .invoke(panelCanvas, src, rotMatrix, null);

                    ByteArrayOutputStream baosPanel = new ByteArrayOutputStream(65536);
                    compressM.invoke(panelBitmap, jpegFmt, Integer.valueOf(q), baosPanel);
                    panelBytes = baosPanel.toByteArray();

                    recordEncodeMs((System.nanoTime() - tEncode0) / 1e6);
                    return new byte[][]{landBytes, panelBytes};
                } catch (Throwable t) {
                    return null;
                }
            }
        }

        private static volatile byte[] cachedSolidBlackPanelJpeg = null;

        public static synchronized byte[] renderSolidBlackPanelJpeg() {
            if (cachedSolidBlackPanelJpeg != null && cachedSolidBlackPanelJpeg.length > 0) {
                return cachedSolidBlackPanelJpeg;
            }
            try {
                Class<?> bmpCls = Class.forName("android.graphics.Bitmap");
                Class<?> bmpCfgCls = Class.forName("android.graphics.Bitmap$Config");
                Class<?> fmtCls = Class.forName("android.graphics.Bitmap$CompressFormat");
                Class<?> canvasCls = Class.forName("android.graphics.Canvas");
                Object argb8888 = bmpCfgCls.getField("ARGB_8888").get(null);
                Object jpegFmt = fmtCls.getField("JPEG").get(null);
                Method createBmp = bmpCls.getMethod("createBitmap", int.class, int.class, bmpCfgCls);
                Method compressM = bmpCls.getMethod("compress", fmtCls, int.class, OutputStream.class);

                int padW = activePanelJpegWidth;
                Object blkBmp = createBmp.invoke(null, Integer.valueOf(padW), Integer.valueOf(PANEL_JPEG_HEIGHT), argb8888);
                Object blkCanvas = canvasCls.getConstructor(bmpCls).newInstance(blkBmp);
                canvasCls.getMethod("drawColor", int.class).invoke(blkCanvas, Integer.valueOf(0xFF000000));
                ByteArrayOutputStream baos = new ByteArrayOutputStream(16384);
                compressM.invoke(blkBmp, jpegFmt, Integer.valueOf(85), baos);
                try {
                    bmpCls.getMethod("recycle").invoke(blkBmp);
                } catch (Throwable ignored) {
                }
                cachedSolidBlackPanelJpeg = baos.toByteArray();
                return cachedSolidBlackPanelJpeg;
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

        private static void copySpriteBox(
                Object c,
                Object srcBmp,
                Object sRect,
                Object dRect,
                Method setRect,
                Method drawBmpRect,
                int[] box
        ) throws Exception {
            if (srcBmp == null || box == null || box.length < 4) return;
            copySpriteRect(c, srcBmp, sRect, dRect, setRect, drawBmpRect, box[0], box[1], box[2], box[3]);
        }

        // Render-thread-only reflection caches (renderHudFrame is synchronized)
        private static Method[] hudMethods = null;
        private static Object[] hudAligns = null;
        private static Method[] roadMethods = null;
        private static Constructor<?>[] roadCtors = null;
        private static Object roadClampMode = null;
        private static Object roadPath = null;
        // v1.4.4 road caches: static backdrop bitmap + shaders whose geometry does not change per frame
        private static Object roadBgBitmap = null;
        private static Object roadBgCanvas = null;
        private static int roadBgKey = Integer.MIN_VALUE;
        private static Object[] roadSurfShaders = null;
        private static Object[] roadStripShaders = null;
        private static Object[] roadTrajShaders = null;
        private static int roadTrajKey = 0;
        private static Object artShaderBitmap = null;
        private static Object artShader = null;
        private static int artBmpW = 0;
        private static int artBmpH = 0;

        // =================================================================
        // v1.5.0 Tesla-style HUD (design: turzx_redesign_tesla.html -> tools/make_tesla_assets.py -> design_tesla/)
        // Static layer = tesla_base*.png, ON states are copied from tesla_active*.png, text anchors / rects come from
        // tesla_layout_*.txt (panel pixels). Dynamic parts: road + cars, park photo + lamp glows, numbers, bars, chips.
        // =================================================================
        static final class TeslaHud {
            static final class Field {
                String key;
                float x, y, size, maxW, unitSize, gap;
                boolean bold, unitBold;
                int color, unitColor;
                char align = 'L';
                String unit = "";
            }

            static final class Layout {
                final HashMap<String, Field> fields = new HashMap<String, Field>();
                final HashMap<String, float[]> rects = new HashMap<String, float[]>();
                final HashMap<String, Float> sizes = new HashMap<String, Float>();

                float[] rect(String name) {
                    return rects.get(name);
                }
            }

            /** Parses tesla_layout_*.txt (tab separated: F:key / R:name / S:name lines). Unknown lines are ignored. */
            static Layout parseLayout(String text) {
                Layout l = new Layout();
                if (text == null) return l;
                String[] lines = text.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i];
                    if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] t = line.split("\t", -1);
                    try {
                        if (t[0].startsWith("F:") && t.length >= 13) {
                            Field f = new Field();
                            f.key = t[0].substring(2);
                            f.x = Float.parseFloat(t[1]);
                            f.y = Float.parseFloat(t[2]);
                            f.size = Float.parseFloat(t[3]);
                            f.bold = "1".equals(t[4]);
                            f.color = (int) Long.parseLong(t[5], 16);
                            f.align = t[6].isEmpty() ? 'L' : t[6].charAt(0);
                            f.maxW = Float.parseFloat(t[7]);
                            f.unit = t[8];
                            f.unitSize = Float.parseFloat(t[9]);
                            f.unitBold = "1".equals(t[10]);
                            f.unitColor = (int) Long.parseLong(t[11], 16);
                            f.gap = Float.parseFloat(t[12]);
                            l.fields.put(f.key, f);
                        } else if (t[0].startsWith("R:") && t.length >= 5) {
                            float[] r = new float[t.length - 1];
                            for (int k = 1; k < t.length; k++) r[k - 1] = Float.parseFloat(t[k]);
                            l.rects.put(t[0].substring(2), r);
                        } else if (t[0].startsWith("S:") && t.length >= 2) {
                            l.sizes.put(t[0].substring(2), Float.valueOf(Float.parseFloat(t[1])));
                        }
                    } catch (RuntimeException ignored) {
                        // malformed line: skip
                    }
                }
                return l;
            }

            /** Brake lamp estimate (no pedal signal yet): decelerating, strong regeneration, or stopped in D/R. */
            static boolean estimateBrake(String gear, int speedKph, double decelMps2, double totalKw) {
                if (!"D".equals(gear) && !"R".equals(gear)) return false;
                if (speedKph <= 0) return true;
                return decelMps2 > 1.3 || totalKw < -15.0;
            }

            /** Headlamp status from tmHscanService getAFLS11_HeadLampStatus(): 0 = off, > 0 = lamps on (raw shown in the report). */
            static boolean headLampsOn(int status) {
                return status > 0;
            }

            // Sprite metrics (make_lamp_sprites.py / make_tesla_assets.py)
            static final float EGO_CAR_W = 560f, EGO_CAR_H = 555f, EGO_MARGIN = 54.19f, EGO_IMG_W = 668f, EGO_IMG_H = 664f;
            static final float[][] PARK_HEAD = {{345f, 210f}, {390f, 202f}};
            static final float[] PARK_HEAD_FAR = {42f, 192f};
            static final float[][] PARK_DRL = {{348f, 303f}, {356f, 348f}};
            static final float[] PARK_REAR = {938f, 145f};

            private static int loadedH = -1;
            private static Layout L = null;
            private static Object base, active, egoOff, egoTail, egoBrake, park, lead;
            private static float parkW = 960f, parkH = 470f;
            private static Object pFill, pStroke, pBmp, pAdd, pShade, rf, rs, rd, path;
            private static Method mDrawBmpXY, mDrawBmpRR, mDrawBmpRRF, mDrawRoundRect, mDrawRect, mDrawOval, mDrawPath,
                    mSave, mRestore, mClipPath, mRotate, mTranslate, mScale, mSetColor, mSetStrokeW, mSetShader,
                    mSetRf, mSetRect, mPReset, mPMove, mPLine, mPClose, mPAddRRect, mMeasure, mSetTextSize, mSetTextAlign, mDrawText;
            private static Constructor<?> cLinear, cRadial;
            private static Object clamp, dirCW, alignLeft;
            private static Object[] lineShaders = null;
            private static float phase = 0f;
            private static long lastNs = 0L;
            private static int lastSpeed = -1;
            private static long lastSpeedNs = 0L;
            private static double decel = 0.0;

            static boolean isReady() {
                return L != null && base != null;
            }

            private static boolean ensure(int hudH) throws Exception {
                if (loadedH == hudH && base != null) return true;
                loadedH = hudH;
                base = active = egoOff = egoTail = egoBrake = park = lead = null;
                lineShaders = null;
                String sfx = (hudH == HUD_HEIGHT_720) ? "_720" : "";
                byte[] lay = readJarAssetBytes("tesla_layout_" + (hudH == HUD_HEIGHT_720 ? "123" : "92") + ".txt");
                L = parseLayout(lay != null ? new String(lay, UTF8) : null);
                Class<?> bfCls = Class.forName("android.graphics.BitmapFactory");
                Method dec = bfCls.getMethod("decodeByteArray", byte[].class, int.class, int.class);
                base = decodeAssetBitmap(dec, "tesla_base" + sfx + ".png");
                active = decodeAssetBitmap(dec, "tesla_active" + sfx + ".png");
                egoOff = decodeAssetBitmap(dec, "tesla_ego_off.png");
                egoTail = decodeAssetBitmap(dec, "tesla_ego_tail.png");
                egoBrake = decodeAssetBitmap(dec, "tesla_ego_brake.png");
                park = decodeAssetBitmap(dec, "tesla_park.png");
                lead = decodeAssetBitmap(dec, "tesla_lead.png");
                if (park != null) {
                    Class<?> bmpCls = gfxClasses[0];
                    parkW = ((Integer) bmpCls.getMethod("getWidth").invoke(park)).intValue();
                    parkH = ((Integer) bmpCls.getMethod("getHeight").invoke(park)).intValue();
                }
                if (mDrawBmpXY == null) initReflection();
                DebugManager.logEvent("RENDER", "Tesla HUD assets (" + HUD_WIDTH + "x" + hudH + "): layout fields="
                        + L.fields.size() + " rects=" + L.rects.size() + " base=" + (base != null) + " active=" + (active != null)
                        + " ego=" + (egoOff != null) + " park=" + (park != null));
                return base != null && !L.fields.isEmpty();
            }

            private static void initReflection() throws Exception {
                Class<?> bmpCls = gfxClasses[0], canvasCls = gfxClasses[2], paintCls = gfxClasses[3], styleCls = gfxClasses[4],
                        alignCls = gfxClasses[5], rectFCls = gfxClasses[7], rectCls = gfxClasses[8], pathCls = gfxClasses[9];
                Class<?> shaderCls = Class.forName("android.graphics.Shader");
                Class<?> tileCls = Class.forName("android.graphics.Shader$TileMode");
                Class<?> dirCls = Class.forName("android.graphics.Path$Direction");
                Class<?> xferCls = Class.forName("android.graphics.Xfermode");
                Class<?> pdCls = Class.forName("android.graphics.PorterDuff$Mode");
                Class<?> pdxCls = Class.forName("android.graphics.PorterDuffXfermode");
                mDrawBmpXY = canvasCls.getMethod("drawBitmap", bmpCls, float.class, float.class, paintCls);
                mDrawBmpRR = canvasCls.getMethod("drawBitmap", bmpCls, rectCls, rectCls, paintCls);
                mDrawBmpRRF = canvasCls.getMethod("drawBitmap", bmpCls, rectCls, rectFCls, paintCls);
                mDrawRoundRect = canvasCls.getMethod("drawRoundRect", rectFCls, float.class, float.class, paintCls);
                mDrawRect = canvasCls.getMethod("drawRect", float.class, float.class, float.class, float.class, paintCls);
                mDrawOval = canvasCls.getMethod("drawOval", rectFCls, paintCls);
                mDrawPath = canvasCls.getMethod("drawPath", pathCls, paintCls);
                mDrawText = canvasCls.getMethod("drawText", String.class, float.class, float.class, paintCls);
                mSave = canvasCls.getMethod("save");
                mRestore = canvasCls.getMethod("restoreToCount", int.class);
                mClipPath = canvasCls.getMethod("clipPath", pathCls);
                mRotate = canvasCls.getMethod("rotate", float.class, float.class, float.class);
                mTranslate = canvasCls.getMethod("translate", float.class, float.class);
                mScale = canvasCls.getMethod("scale", float.class, float.class);
                mSetColor = paintCls.getMethod("setColor", int.class);
                mSetStrokeW = paintCls.getMethod("setStrokeWidth", float.class);
                mSetShader = paintCls.getMethod("setShader", shaderCls);
                mMeasure = paintCls.getMethod("measureText", String.class);
                mSetTextSize = paintCls.getMethod("setTextSize", float.class);
                mSetTextAlign = paintCls.getMethod("setTextAlign", alignCls);
                mSetRf = rectFCls.getMethod("set", float.class, float.class, float.class, float.class);
                mSetRect = rectCls.getMethod("set", int.class, int.class, int.class, int.class);
                mPReset = pathCls.getMethod("reset");
                mPMove = pathCls.getMethod("moveTo", float.class, float.class);
                mPLine = pathCls.getMethod("lineTo", float.class, float.class);
                mPClose = pathCls.getMethod("close");
                mPAddRRect = pathCls.getMethod("addRoundRect", rectFCls, float.class, float.class, dirCls);
                cLinear = Class.forName("android.graphics.LinearGradient").getConstructor(
                        float.class, float.class, float.class, float.class, int[].class, float[].class, tileCls);
                cRadial = Class.forName("android.graphics.RadialGradient").getConstructor(
                        float.class, float.class, float.class, int[].class, float[].class, tileCls);
                clamp = tileCls.getField("CLAMP").get(null);
                dirCW = dirCls.getField("CW").get(null);
                alignLeft = alignCls.getField("LEFT").get(null);
                Object fill = styleCls.getField("FILL").get(null), stroke = styleCls.getField("STROKE").get(null);
                Method setStyle = paintCls.getMethod("setStyle", styleCls);
                Constructor<?> pc = paintCls.getConstructor(int.class);
                pFill = pc.newInstance(Integer.valueOf(1));
                setStyle.invoke(pFill, fill);
                pShade = pc.newInstance(Integer.valueOf(1));
                setStyle.invoke(pShade, fill);
                pStroke = pc.newInstance(Integer.valueOf(1));
                setStyle.invoke(pStroke, stroke);
                pBmp = pc.newInstance(Integer.valueOf(3)); // ANTI_ALIAS | FILTER_BITMAP
                pAdd = pc.newInstance(Integer.valueOf(1));
                setStyle.invoke(pAdd, fill);
                Object add = pdCls.getField("ADD").get(null);
                paintCls.getMethod("setXfermode", xferCls).invoke(pAdd, pdxCls.getConstructor(pdCls).newInstance(add));
                rf = rectFCls.getConstructor().newInstance();
                rs = rectCls.getConstructor().newInstance();
                rd = rectCls.getConstructor().newInstance();
                path = pathCls.getConstructor().newInstance();
            }

            // ---- small drawing helpers ----
            private static void copyActive(Object c, float[] r) throws Exception {
                if (active == null || r == null) return;
                int x0 = Math.round(r[0]) - 1, y0 = Math.round(r[1]) - 1, x1 = Math.round(r[0] + r[2]) + 1, y1 = Math.round(r[1] + r[3]) + 1;
                mSetRect.invoke(rs, x0, y0, x1, y1);
                mSetRect.invoke(rd, x0, y0, x1, y1);
                mDrawBmpRR.invoke(c, active, rs, rd, null);
            }

            private static void roundRect(Object c, float x0, float y0, float x1, float y1, float rad, int color) throws Exception {
                mSetShader.invoke(pFill, new Object[]{null});
                mSetColor.invoke(pFill, color);
                mSetRf.invoke(rf, x0, y0, x1, y1);
                mDrawRoundRect.invoke(c, rf, rad, rad, pFill);
            }

            private static void glow(Object c, float x, float y, float r, int rgb, float a) throws Exception {
                if (r <= 0.5f) return;
                int a0 = Math.max(0, Math.min(255, Math.round(a * 255)));
                int a1 = Math.max(0, Math.min(255, Math.round(a * 0.45f * 255)));
                Object sh = cRadial.newInstance(x, y, r, new int[]{(a0 << 24) | rgb, (a1 << 24) | rgb, rgb & 0x00FFFFFF},
                        new float[]{0f, 0.35f, 1f}, clamp);
                mSetShader.invoke(pAdd, sh);
                mDrawRect.invoke(c, x - r, y - r, x + r, y + r, pAdd);
                mSetShader.invoke(pAdd, new Object[]{null});
            }

            private static void shadow(Object c, float cx, float cy, float rx, float squash, float alpha) throws Exception {
                int a = Math.round(alpha * 255);
                Object sh = cRadial.newInstance(0f, 0f, rx, new int[]{a << 24, 0}, new float[]{0f, 1f}, clamp);
                int sc = ((Integer) mSave.invoke(c)).intValue();
                mTranslate.invoke(c, cx, cy);
                mScale.invoke(c, 1f, squash);
                mSetShader.invoke(pShade, sh);
                mDrawRect.invoke(c, -rx, -rx, rx, rx, pShade);
                mSetShader.invoke(pShade, new Object[]{null});
                mRestore.invoke(c, sc);
            }

            private static void drawImage(Object c, Object bmp, float x, float y, float w, float h) throws Exception {
                if (bmp == null) return;
                mSetRf.invoke(rf, x, y, x + w, y + h);
                mDrawBmpRRF.invoke(c, bmp, null, rf, pBmp);
            }

            private static Object textPaint(String t, boolean bold) {
                return hasHangulOrCjk(t) ? (bold ? paintSysBold : paintSysMed) : (bold ? paintPublicBold : paintPublicMed);
            }

            private static float measure(String t, float size, boolean bold) throws Exception {
                if (t == null || t.isEmpty()) return 0f;
                Object p = textPaint(t, bold);
                mSetTextSize.invoke(p, size);
                return ((Float) mMeasure.invoke(p, t)).floatValue();
            }

            private static float text(Object c, String t, float x, float y, float size, boolean bold, int color) throws Exception {
                if (t == null || t.isEmpty()) return 0f;
                Object p = textPaint(t, bold);
                mSetTextSize.invoke(p, size);
                mSetTextAlign.invoke(p, alignLeft);
                mSetColor.invoke(p, color);
                mDrawText.invoke(c, t, x, y, p);
                return ((Float) mMeasure.invoke(p, t)).floatValue();
            }

            private static String fit(String t, float size, boolean bold, float maxW) throws Exception {
                if (t == null || maxW <= 0f || measure(t, size, bold) <= maxW) return t;
                String s = t;
                while (s.length() > 1 && measure(s + "…", size, bold) > maxW) s = s.substring(0, s.length() - 1);
                return s + "…";
            }

            /** Draws a layout field (value + optional unit) honouring its alignment; color 0 = layout colour. */
            private static void field(Object c, String key, String value, int color) throws Exception {
                Field f = L.fields.get(key);
                if (f == null || value == null || value.isEmpty()) return;
                String v = fit(value, f.size, f.bold, f.maxW);
                float wv = measure(v, f.size, f.bold);
                boolean hasUnit = f.unit != null && !f.unit.isEmpty();
                float wu = hasUnit ? measure(f.unit, f.unitSize, f.unitBold) : 0f;
                float total = wv + (hasUnit ? f.gap + wu : 0f);
                float x = (f.align == 'R') ? f.x - total : (f.align == 'C' ? f.x - total / 2f : f.x);
                text(c, v, x, f.y, f.size, f.bold, color != 0 ? color : f.color);
                if (hasUnit) text(c, f.unit, x + wv + f.gap, f.y, f.unitSize, f.unitBold, f.unitColor);
            }

            private static void barFill(Object c, float[] r, float x0, float x1, int color) throws Exception {
                if (r == null || x1 - x0 < 0.5f) return;
                roundRect(c, x0, r[1], x1, r[1] + r[3], r[3] / 2f, color);
            }

            // ---- frame ----
            static void draw(Object c, TelemetrySnapshot s) throws Exception {
                if (!ensure(activeHudHeight)) throw new IllegalStateException("Tesla HUD assets missing");
                long now = System.nanoTime();
                float dt = (lastNs == 0L) ? 0f : Math.min(0.2f, (now - lastNs) / 1e9f);
                lastNs = now;
                int spd = Math.max(0, s.carrotSpeedKph);
                phase = (phase + (spd / 3.6f) * dt) % 7000f;
                if (lastSpeed >= 0 && now - lastSpeedNs > 150_000_000L) {
                    double dv = (lastSpeed - spd) / 3.6, dts = (now - lastSpeedNs) / 1e9;
                    decel = decel * 0.6 + (dv / dts) * 0.4;
                    lastSpeed = spd;
                    lastSpeedNs = now;
                } else if (lastSpeed < 0) {
                    lastSpeed = spd;
                    lastSpeedNs = now;
                }
                String gear = (s.gearPos != null && !s.gearPos.isEmpty()) ? s.gearPos : "D";
                boolean head = headLampsOn(s.headLampStatus);
                double totalKw = s.getTotalPowerKw();
                boolean brake = estimateBrake(gear, spd, decel, totalKw);

                mDrawBmpXY.invoke(c, base, 0f, 0f, null);

                // 1. Driving visualisation
                float[] vz = L.rect("viz");
                boolean parked = "P".equals(gear);
                if (vz != null) {
                    int sc = ((Integer) mSave.invoke(c)).intValue();
                    mPReset.invoke(path);
                    mSetRf.invoke(rf, vz[0], vz[1], vz[0] + vz[2], vz[1] + vz[3]);
                    float rad = vz.length > 4 ? vz[4] : 28f;
                    mPAddRRect.invoke(path, rf, rad, rad, dirCW);
                    mClipPath.invoke(c, path);
                    if (parked) drawParked(c, vz, head, brake);
                    else drawRoad(c, s, vz, gear, spd, head, brake);
                    mRestore.invoke(c, sc);
                }
                drawVizOverlays(c, s, vz, gear, spd, parked);
                drawBattery(c, s, totalKw);
                drawMedia(c, s);
                drawClimate(c, s);
            }

            private static void drawRoad(Object c, TelemetrySnapshot s, float[] vz, String gear, int spd,
                                         boolean head, boolean brake) throws Exception {
                float vx = vz[0], vy = vz[1], vw = vz[2], vh = vz[3];
                final float horizon = vy + vh * 0.46f, u0 = vx + vw * 0.52f, kx = vw * 0.58f, near = 5.2f, bottom = vy + vh;
                float curve = 0f;
                if (s.pathPoly != null && s.pathPoly.length >= 3) curve = Math.max(-0.0025f, Math.min(0.0025f, s.pathPoly[2]));
                if (Math.abs(curve) < 1e-6f && Math.abs(s.steeringAngleDeg) > 0.05f) {
                    curve = Math.max(-0.0025f, Math.min(0.0025f, -s.steeringAngleDeg * 0.000025f));
                }
                float[] xs = new float[41];
                for (int i = 0; i <= 40; i++) xs[i] = near + 90f * (i / 40f) * (i / 40f);
                if (lineShaders == null) {
                    // distance fade = vertical gradient (rows only depend on the panel geometry)
                    int[][] rgbA = {{160, 164, 172, 140}, {230, 232, 236, 140}, {245, 245, 247, 230}};
                    lineShaders = new Object[3];
                    for (int k = 0; k < 3; k++) {
                        int col = (rgbA[k][0] << 16) | (rgbA[k][1] << 8) | rgbA[k][2];
                        lineShaders[k] = cLinear.newInstance(0f, bottom, 0f, horizon,
                                new int[]{(rgbA[k][3] << 24) | col, (Math.round(rgbA[k][3] * 0.08f) << 24) | col},
                                new float[]{0f, 1f}, clamp);
                    }
                }
                float[][] styles = {{5.4f, 0.06f, 0f}, {3.6f, 0.055f, 1f}, {1.8f, 0.07f, 0f}};
                for (int k = 0; k < 3; k++) {
                    mPReset.invoke(path);
                    boolean dashed = styles[k][2] > 0f;
                    for (int side = -1; side <= 1; side += 2) {
                        float lat = side * styles[k][0], w = styles[k][1];
                        for (int i = 0; i < xs.length - 1; i++) {
                            float x0 = xs[i], x1 = xs[i + 1];
                            if (dashed && (((int) Math.floor((x0 + phase) / 3.5f)) & 1) != 0) continue;
                            float c0 = curve * x0 * x0 * 0.5f, c1 = curve * x1 * x1 * 0.5f;
                            float v0 = horizon + (bottom - horizon) * (near / x0), v1 = horizon + (bottom - horizon) * (near / x1);
                            mPMove.invoke(path, u0 + kx * ((lat - w + c0) / x0), v0);
                            mPLine.invoke(path, u0 + kx * ((lat + w + c0) / x0), v0);
                            mPLine.invoke(path, u0 + kx * ((lat + w + c1) / x1), v1);
                            mPLine.invoke(path, u0 + kx * ((lat - w + c1) / x1), v1);
                            mPClose.invoke(path);
                        }
                    }
                    mSetShader.invoke(pFill, lineShaders[k]);
                    mDrawPath.invoke(c, path, pFill);
                }
                mSetShader.invoke(pFill, new Object[]{null});

                boolean hasLead = s.hasLeadCar && s.leadDistM > 0.5f;
                float leadX = hasLead ? 6.2f + (float) Math.pow(Math.max(6.0, s.leadDistM), 0.62) * 1.05f + 4f : 0f;
                boolean crit = hasLead && s.leadDistM <= 12f, close = hasLead && s.leadDistM <= 18f;
                if ("D".equals(gear) || "R".equals(gear)) {
                    float end = hasLead ? leadX - 1.5f : 60f;
                    int rgb = crit ? 0xE31937 : (close ? 0xF5A623 : (s.carrotCruiseKph > 0 ? 0x3E6AE1 : 0xC8CDD7));
                    int a0 = (s.carrotCruiseKph > 0 || close) ? 140 : 46;
                    float vEnd = horizon + (bottom - horizon) * (near / end);
                    mPReset.invoke(path);
                    for (int i = 0; i < xs.length && xs[i] <= end; i++) {
                        float x = xs[i], cc = curve * x * x * 0.5f, v = horizon + (bottom - horizon) * (near / x);
                        if (i == 0) mPMove.invoke(path, u0 + kx * ((-1.65f + cc) / x), v);
                        else mPLine.invoke(path, u0 + kx * ((-1.65f + cc) / x), v);
                    }
                    for (int i = xs.length - 1; i >= 0; i--) {
                        if (xs[i] > end) continue;
                        float x = xs[i], cc = curve * x * x * 0.5f, v = horizon + (bottom - horizon) * (near / x);
                        mPLine.invoke(path, u0 + kx * ((1.65f + cc) / x), v);
                    }
                    mPClose.invoke(path);
                    mSetShader.invoke(pFill, cLinear.newInstance(0f, bottom, 0f, vEnd,
                            new int[]{(a0 << 24) | rgb, rgb}, new float[]{0f, 1f}, clamp));
                    mDrawPath.invoke(c, path, pFill);
                    mSetShader.invoke(pFill, new Object[]{null});
                }
                if (hasLead && lead != null) {
                    float cc = curve * leadX * leadX * 0.5f;
                    float cx = u0 + kx * (cc / leadX), by = horizon + (bottom - horizon) * (near / leadX);
                    float w = Math.abs(kx * (1.9f / leadX));
                    float fullW = w * (200f / 172f), fullH = fullW * 0.8f;
                    drawImage(c, lead, cx - fullW / 2f, by - fullH * (158f / 160f), fullW, fullH);
                    String tag = Math.round(s.leadDistM) + " m";
                    float size = vw / 30f;
                    float tw = measure(tag, size, true);
                    text(c, tag, cx - tw / 2f, by - fullH * (137f / 160f) - vw / 90f, size, true,
                            crit ? 0xFFFF4D63 : (close ? 0xFFF5A623 : 0xFFC9CBD1));
                }
                // ego car: lamp variant
                Object ego = brake ? egoBrake : (head ? egoTail : egoOff);
                if (ego != null) {
                    float w = Math.min(vw * 0.165f, vh * 0.27f), k = (w * 1.13f) / EGO_CAR_W;
                    float cx = u0, by = vy + vh * 0.985f, bodyH = EGO_CAR_H * k;
                    shadow(c, cx, by - bodyH * 0.02f, EGO_CAR_W * k * 0.58f, 0.14f, 0.55f);
                    drawImage(c, ego, cx - (EGO_CAR_W / 2f + EGO_MARGIN) * k, by - (EGO_CAR_H + EGO_MARGIN) * k, EGO_IMG_W * k, EGO_IMG_H * k);
                }
            }

            private static void drawParked(Object c, float[] vz, boolean head, boolean brake) throws Exception {
                float vx = vz[0], vy = vz[1], vw = vz[2], vh = vz[3];
                glow(c, vx + vw * 0.58f, vy + vh * 0.9f, vw * 0.55f, 0xFFFFFF, 0.10f);
                if (park == null) return;
                float w = Math.min(vw * 0.78f, vh * 1.36f), h = w * parkH / parkW;
                float cx = vx + vw * 0.58f, bottom = vy + vh * 0.96f, left = cx - w / 2f, top = bottom - h, k = w / parkW;
                shadow(c, cx, bottom - h * 0.05f, w * 0.52f, 0.12f, 0.65f);
                if (head) {
                    int sc = ((Integer) mSave.invoke(c)).intValue();
                    mTranslate.invoke(c, left + 120f * k, bottom - 6f * k);
                    mScale.invoke(c, 1f, 0.22f);
                    glow(c, 0f, 0f, w * 0.5f, 0xDCEBFF, 0.22f);
                    mRestore.invoke(c, sc);
                }
                drawImage(c, park, left, top, w, h);
                if (head) {
                    for (int i = 0; i < PARK_HEAD.length; i++) {
                        float x = left + PARK_HEAD[i][0] * k, y = top + PARK_HEAD[i][1] * k;
                        glow(c, x, y, 70f * k, 0xCDE1FF, 0.35f);
                        glow(c, x, y, 20f * k, 0xF5FAFF, 0.95f);
                    }
                    glow(c, left + PARK_HEAD_FAR[0] * k, top + PARK_HEAD_FAR[1] * k, 42f * k, 0xCDE1FF, 0.35f);
                    glow(c, left + PARK_HEAD_FAR[0] * k, top + PARK_HEAD_FAR[1] * k, 13f * k, 0xF5FAFF, 0.9f);
                    for (int i = 0; i < PARK_DRL.length; i++) {
                        glow(c, left + PARK_DRL[i][0] * k, top + PARK_DRL[i][1] * k, 40f * k, 0xBEE1FF, 0.4f);
                    }
                }
                float rx = left + PARK_REAR[0] * k, ry = top + PARK_REAR[1] * k;
                if (brake) {
                    glow(c, rx, ry, 110f * k, 0xFF192D, 0.55f);
                    glow(c, rx, ry, 45f * k, 0xFF3246, 0.85f);
                    glow(c, rx, ry, 16f * k, 0xFFAAB4, 1.0f);
                } else if (head) {
                    glow(c, rx, ry, 42f * k, 0xFF1E32, 0.22f);
                    glow(c, rx, ry, 16f * k, 0xFF4655, 0.45f);
                }
            }

            private static void drawVizOverlays(Object c, TelemetrySnapshot s, float[] vz, String gear, int spd, boolean parked)
                    throws Exception {
                copyActive(c, L.rect("prnd" + gear));
                field(c, "speed", String.valueOf(spd), 0);
                // speed limit: camera event -> red ring, otherwise the current road limit -> blue ring
                boolean camera = !(s.carrotSdiType < 0 && s.carrotSdiDistM <= 0);
                int limit = Math.max(0, s.carrotSdiSpeedLimit);
                float[] lr = L.rect("limit");
                if (limit > 0 && lr != null) {
                    float cx = lr[0] + lr[2] / 2f, cy = lr[1] + lr[3] / 2f, r = lr[2] / 2f;
                    mSetRf.invoke(rf, cx - r, cy - r, cx + r, cy + r);
                    mSetShader.invoke(pFill, new Object[]{null});
                    mSetColor.invoke(pFill, 0xFFFFFFFF);
                    mDrawOval.invoke(c, rf, pFill);
                    float sw = r * 0.23f;
                    mSetStrokeW.invoke(pStroke, sw);
                    mSetColor.invoke(pStroke, camera ? 0xFFE31937 : 0xFF3E6AE1);
                    mSetRf.invoke(rf, cx - r + sw / 2f, cy - r + sw / 2f, cx + r - sw / 2f, cy + r - sw / 2f);
                    mDrawOval.invoke(c, rf, pStroke);
                    field(c, "limit", String.valueOf(limit), 0);
                }
                // steering wheel: rotates with the real steering angle (+ = left = counter-clockwise)
                float[] wr = L.rect("wheel");
                if (wr != null) {
                    float cx = wr[0] + wr[2] / 2f, cy = wr[1] + wr[3] / 2f, u = wr[2] / 24f;
                    int col = s.carrotCruiseKph > 0 ? 0xFF6E95FF : 0xFF5A5D64;
                    int sc = ((Integer) mSave.invoke(c)).intValue();
                    mRotate.invoke(c, Math.max(-540f, Math.min(540f, -s.steeringAngleDeg)), cx, cy);
                    mSetColor.invoke(pStroke, col);
                    mSetStrokeW.invoke(pStroke, 2.2f * u);
                    mSetRf.invoke(rf, cx - 9 * u, cy - 9 * u, cx + 9 * u, cy + 9 * u);
                    mDrawOval.invoke(c, rf, pStroke);
                    mSetRf.invoke(rf, cx - 2.2f * u, cy - 2.2f * u, cx + 2.2f * u, cy + 2.2f * u);
                    mDrawOval.invoke(c, rf, pStroke);
                    mPReset.invoke(path);
                    mPMove.invoke(path, cx - 8.5f * u, cy - 1.5f * u);
                    mPLine.invoke(path, cx - 2.2f * u, cy - 1.5f * u);
                    mPMove.invoke(path, cx + 2.2f * u, cy - 1.5f * u);
                    mPLine.invoke(path, cx + 8.5f * u, cy - 1.5f * u);
                    mPMove.invoke(path, cx, cy + 2.2f * u);
                    mPLine.invoke(path, cx, cy + 9f * u);
                    mDrawPath.invoke(c, path, pStroke);
                    mRestore.invoke(c, sc);
                }
                if (s.carrotCruiseKph > 0) field(c, "cruise", String.valueOf(s.carrotCruiseKph), 0);
                if (camera && s.carrotSdiDistM > 0) {
                    int d = s.carrotSdiDistM;
                    String ds = d >= 1000 ? String.format(Locale.US, "%.1f km", d / 1000.0) : (d + " m");
                    boolean section = s.carrotSdiType == 2 || s.carrotSdiType == 3 || s.carrotSdiType == 4 || d >= 800;
                    field(c, "sect", section ? ("구간단속 " + ds + " · 평균 " + Math.max(spd, limit)) : ("과속카메라 " + ds), 0);
                }
                // turn-by-turn card (hidden without a destination)
                boolean tbt = s.carrotTbtDistM > 0 && vz != null;
                if (tbt) drawTbt(c, s, vz);
                drawTpms(c, s, vz, parked && !tbt);
                if (!parked) {
                    Field lf = L.fields.get("lane");
                    String lane = s.laneConfidencePct > 0f ? String.format(Locale.US, "%.0f%%", s.laneConfidencePct)
                            : (s.carrotConnected ? (s.carrotActive ? "ON" : "대기") : "--");
                    if (lf != null) {
                        float pw = measure("차선 ", lf.size, false);
                        text(c, "차선 ", lf.x - pw, lf.y, lf.size, false, 0xFF5A5D64);
                        field(c, "lane", lane, 0);
                    }
                }
            }

            private static void drawTbt(Object c, TelemetrySnapshot s, float[] vz) throws Exception {
                float[] tr = L.rect("tbt");
                Field roadF = L.fields.get("navRoad");
                Float ds = L.sizes.get("tbtDist");
                if (tr == null || roadF == null || ds == null) return;
                float distSize = ds.floatValue(), roadSize = roadF.size;
                int d = s.carrotTbtDistM;
                String num = d >= 1000 ? String.format(Locale.US, "%.1f", d / 1000.0) : String.valueOf(d);
                String unit = d >= 1000 ? "km" : "m";
                String road = s.carrotTbtRoad != null ? s.carrotTbtRoad : "";
                if (road.contains("·")) {
                    String[] parts = road.split("·");
                    road = parts[parts.length - 1].trim();
                }
                road = fit(road, roadSize, false, distSize * 6f);
                String next = s.carrotTbtDistNextM > 0 ? (s.carrotTbtDistNextM >= 1000
                        ? String.format(Locale.US, "+%.1fkm", s.carrotTbtDistNextM / 1000.0) : ("+" + s.carrotTbtDistNextM + "m")) : "";
                float icon = distSize * 1.3f, pad = distSize * 0.45f;
                float wNum = measure(num, distSize, true) + distSize * 0.08f + measure(unit, distSize * 0.55f, false);
                float wRoad = measure(road, roadSize, false) + (next.isEmpty() ? 0f : roadSize * 0.4f + measure(next, roadSize, true));
                float wText = Math.max(wNum, wRoad);
                float h = tr[3], x1 = vz[0] + vz[2] - 20f, y0 = vz[1] + 20f, x0 = x1 - (pad * 2f + icon + pad * 0.8f + wText);
                roundRect(c, x0, y0, x1, y0 + h, tr.length > 4 ? tr[4] : 24f, 0xDB0E0F12);
                mSetStrokeW.invoke(pStroke, 2f);
                mSetColor.invoke(pStroke, 0xFF2A2C31);
                mSetRf.invoke(rf, x0, y0, x1, y0 + h);
                float rr = tr.length > 4 ? tr[4] : 24f;
                mDrawRoundRect.invoke(c, rf, rr, rr, pStroke);
                // generic navigation arrow (turn direction is not available from the TBT feed)
                float ix = x0 + pad + icon / 2f, iy = y0 + h / 2f, u = icon / 2f;
                mPReset.invoke(path);
                mPMove.invoke(path, ix, iy - u * 0.9f);
                mPLine.invoke(path, ix + u * 0.7f, iy + u * 0.8f);
                mPLine.invoke(path, ix, iy + u * 0.4f);
                mPLine.invoke(path, ix - u * 0.7f, iy + u * 0.8f);
                mPClose.invoke(path);
                mSetShader.invoke(pFill, new Object[]{null});
                mSetColor.invoke(pFill, 0xFFF5F5F7);
                mDrawPath.invoke(c, path, pFill);
                float tx = ix + icon / 2f + pad * 0.8f;
                float base1 = y0 + h * 0.52f, base2 = y0 + h * 0.84f;
                float w1 = text(c, num, tx, base1, distSize, true, 0xFFF5F5F7);
                text(c, unit, tx + w1 + distSize * 0.08f, base1, distSize * 0.55f, false, 0xFF8E9099);
                float w2 = text(c, road, tx, base2, roadSize, false, 0xFF8E9099);
                if (!next.isEmpty()) text(c, next, tx + w2 + roadSize * 0.4f, base2, roadSize, true, 0xFFF5F5F7);
            }

            private static void drawTpms(Object c, TelemetrySnapshot s, float[] vz, boolean top) throws Exception {
                float[] tr = L.rect("tpms");
                Float ts = L.sizes.get("tpms");
                if (tr == null || ts == null || vz == null) return;
                float w = tr[2], h = tr[3], x0 = tr[0], y0 = top ? vz[1] + 20f : tr[1], rad = tr.length > 4 ? tr[4] : 20f;
                roundRect(c, x0, y0, x0 + w, y0 + h, rad, 0xDB0E0F12);
                mSetStrokeW.invoke(pStroke, 2f);
                mSetColor.invoke(pStroke, 0xFF2A2C31);
                mSetRf.invoke(rf, x0, y0, x0 + w, y0 + h);
                mDrawRoundRect.invoke(c, rf, rad, rad, pStroke);
                float size = ts.floatValue(), cx = x0 + w / 2f, carW = size * 0.95f, carH = h * 0.5f, carTop = y0 + h * 0.12f;
                mSetColor.invoke(pStroke, 0xFF8E9099);
                mSetStrokeW.invoke(pStroke, 2.4f);
                mSetRf.invoke(rf, cx - carW / 2f, carTop, cx + carW / 2f, carTop + carH);
                mDrawRoundRect.invoke(c, rf, carW * 0.42f, carW * 0.42f, pStroke);
                float[] psi = {s.tpmsFlPsi, s.tpmsFrPsi, s.tpmsRlPsi, s.tpmsRrPsi};
                float gap = size * 0.3f, row1 = carTop + carH * 0.42f, row2 = carTop + carH * 0.95f;
                for (int i = 0; i < 4; i++) {
                    String t = String.format(Locale.US, "%.0f", psi[i]);
                    boolean warn = psi[i] > 0f && psi[i] < 31f;
                    float tw = measure(t, size, true);
                    float x = (i % 2 == 0) ? cx - carW / 2f - gap - tw : cx + carW / 2f + gap;
                    text(c, t, x, i < 2 ? row1 : row2, size, true, warn ? 0xFFF5A623 : 0xFFF5F5F7);
                }
                float pw = measure("psi", size * 0.36f, false);
                text(c, "psi", cx - pw / 2f, y0 + h - size * 0.3f, size * 0.36f, false, 0xFF5A5D64);
            }

            private static void drawBattery(Object c, TelemetrySnapshot s, double totalKw) throws Exception {
                double soc = s.getEffectiveSocPct();
                if (s.remainEnergyKwh > 0.0) field(c, "remain", String.format(Locale.US, "· 잔여 %.1f kWh", s.remainEnergyKwh), 0);
                if (soc >= 0.0) field(c, "soc", String.format(Locale.US, "%.1f", soc), soc < 20.0 ? 0xFFF5A623 : 0);
                int dte = s.dteKm;
                if (isValidDteValue(dte)) field(c, "dte", String.valueOf(dte), 0);
                if (s.avgEconomyKmKwh > 0.0) field(c, "eff", String.format(Locale.US, "%.1f km/kWh", s.avgEconomyKmKwh), 0);
                float[] pr = L.rect("pill");
                if (pr != null && soc >= 0.0) barFill(c, pr, pr[0], pr[0] + pr[2] * (float) Math.min(1.0, soc / 100.0), soc < 20.0 ? 0xFFF5A623 : 0xFFF5F5F7);
                if (s.bmsSocPct >= 0.0) field(c, "bms", String.format(Locale.US, "%.1f", s.bmsSocPct), 0);
                if (s.sohPct >= 0.0) field(c, "soh", String.format(Locale.US, "%.1f", s.sohPct), 0);
                Integer bt = s.getRepresentativeBattTempC();
                if (bt != null) field(c, "batT", String.format(Locale.US, "%.1f", bt.doubleValue()), 0);
                if (s.cellMaxVoltV > 0.0) field(c, "cellMax", String.format(Locale.US, "%.2f", s.cellMaxVoltV), 0);
                if (s.cellMinVoltV > 0.0) field(c, "cellMin", String.format(Locale.US, "%.2f", s.cellMinVoltV), 0);
                if (s.cellDiffMv >= 0) field(c, "cellDiff", String.valueOf(s.cellDiffMv), 0);
                boolean regen = totalKw < -0.05;
                String kw = (totalKw > 0.05 ? "+" : (regen ? "−" : "")) + String.format(Locale.US, "%.1f", Math.abs(totalKw));
                field(c, "kw", kw, regen ? 0xFF3DD68C : 0);
                field(c, "pwrLbl", regen ? "회생 제동" : (totalKw > 0.05 ? "출력" : "대기"), 0);
                float[] wr = L.rect("pwr");
                if (wr != null) {
                    float zero = wr[0] + wr[2] * 0.28f;
                    if (totalKw >= 0.0) barFill(c, wr, zero, zero + (wr[2] * 0.72f) * (float) Math.min(1.0, totalKw / 150.0), 0xFFF5F5F7);
                    else {
                        float x0 = zero - (wr[2] * 0.28f) * (float) Math.min(1.0, -totalKw / 60.0);
                        barFill(c, wr, x0, zero, 0xFF3DD68C);
                    }
                    roundRect(c, zero - 2f, wr[1] - 6f, zero + 2f, wr[1] + wr[3] + 6f, 2f, 0xFFC9CBD1);
                }
            }

            private static void drawMedia(Object c, TelemetrySnapshot s) throws Exception {
                float[] ar = L.rect("art");
                if (ar != null && s.albumArtBitmap != null && gfxClasses[0].isInstance(s.albumArtBitmap)) {
                    int sc = ((Integer) mSave.invoke(c)).intValue();
                    mPReset.invoke(path);
                    mSetRf.invoke(rf, ar[0], ar[1], ar[0] + ar[2], ar[1] + ar[3]);
                    float rad = ar.length > 4 ? ar[4] : 16f;
                    mPAddRRect.invoke(path, rf, rad, rad, dirCW);
                    mClipPath.invoke(c, path);
                    drawImage(c, s.albumArtBitmap, ar[0], ar[1], ar[2], ar[3]);
                    mRestore.invoke(c, sc);
                }
                boolean real = s.mediaTitle != null && !s.mediaTitle.isEmpty() && !"미디어 재생 대기 중".equals(s.mediaTitle);
                field(c, "src", s.mediaSource, 0);
                field(c, "title", real ? s.mediaTitle : "미디어 재생 대기 중", 0);
                String sub = (s.mediaArtist != null) ? s.mediaArtist : "";
                if (s.mediaAlbum != null && !s.mediaAlbum.isEmpty() && !s.mediaAlbum.equals(s.mediaArtist)) {
                    sub = sub.isEmpty() ? s.mediaAlbum : (sub + " · " + s.mediaAlbum);
                }
                field(c, "artist", real ? sub : "", 0);
                int pos = real ? Math.max(0, s.mediaPositionSec) : 0, dur = real ? Math.max(0, s.mediaDurationSec) : 0;
                float[] pr = L.rect("prog");
                if (pr != null && dur > 0) barFill(c, pr, pr[0], pr[0] + pr[2] * Math.min(1f, pos / (float) dur), 0xFFF5F5F7);
                field(c, "pos", (pos / 60) + ":" + String.format(Locale.US, "%02d", pos % 60), 0);
                field(c, "dur", dur > 0 ? ("-" + ((dur - pos) / 60) + ":" + String.format(Locale.US, "%02d", Math.max(0, dur - pos) % 60)) : "--:--", 0);
            }

            private static void drawClimate(Object c, TelemetrySnapshot s) throws Exception {
                boolean on = s.hvacOn;
                int tone = !on ? 0xFF5A5D64 : (s.hvacHeat ? 0xFFFF9A5C : (s.hvacAc ? 0xFF7EA6FF : 0xFFF5F5F7));
                field(c, "dr", on ? String.format(Locale.US, "%.1f°", s.hvacDrTempC) : "OFF", tone);
                if (on && s.hvacAuto) copyActive(c, L.rect("auto"));
                int bm = s.hvacBlowerMode;
                if (on && (bm == 1 || bm == 2)) copyActive(c, L.rect("airFace"));
                if (on && (bm == 2 || bm == 3 || bm == 4)) copyActive(c, L.rect("airFoot"));
                int step = on ? Math.max(0, Math.min(7, s.hvacBlowerStep)) : 0;
                for (int i = 0; i < step; i++) copyActive(c, L.rect("fan" + i));
                field(c, "fanNum", on ? String.valueOf(step) : "-", 0);
                field(c, "inT", String.format(Locale.US, "%.1f°", s.inCarTempC), 0);
                field(c, "outT", String.format(Locale.US, "%.1f°", s.outdoorTempC), 0);
                if (on && s.hvacAc) copyActive(c, L.rect("chip_ac"));
                if (on && s.hvacHeat) copyActive(c, L.rect("chip_heat"));
                if (on && (s.hvacFrontDefog || bm == 5)) copyActive(c, L.rect("chip_fdef"));
                if (s.hvacRearDefog) copyActive(c, L.rect("chip_rdef"));
                if (s.hvacRecirc) copyActive(c, L.rect("chip_recirc"));
            }
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
            if (hudMethods == null) {
                // Resolved once: these ~20 getMethod() lookups used to run on every frame
                hudAligns = new Object[]{
                        alignCls.getField("LEFT").get(null),
                        alignCls.getField("CENTER").get(null),
                        alignCls.getField("RIGHT").get(null)
                };
                hudMethods = new Method[]{
                        paintCls.getMethod("setColor", int.class),
                        paintCls.getMethod("setStrokeWidth", float.class),
                        paintCls.getMethod("setTextSize", float.class),
                        paintCls.getMethod("setTextAlign", alignCls),
                        paintCls.getMethod("measureText", String.class),
                        rectFCls.getMethod("set", float.class, float.class, float.class, float.class),
                        rectCls.getMethod("set", int.class, int.class, int.class, int.class),
                        canvasCls.getMethod("drawRoundRect", rectFCls, float.class, float.class, paintCls),
                        canvasCls.getMethod("drawRect", float.class, float.class, float.class, float.class, paintCls),
                        canvasCls.getMethod("drawOval", rectFCls, paintCls),
                        canvasCls.getMethod("drawLine", float.class, float.class, float.class, float.class, paintCls),
                        canvasCls.getMethod("drawText", String.class, float.class, float.class, paintCls),
                        canvasCls.getMethod("drawPath", pathCls, paintCls),
                        canvasCls.getMethod("drawBitmap", bmpCls, float.class, float.class, paintCls),
                        canvasCls.getMethod("drawBitmap", bmpCls, rectCls, rectCls, paintCls),
                        canvasCls.getMethod("drawBitmap", bmpCls, rectCls, rectFCls, paintCls)
                };
            }
            Method setColor = hudMethods[0];
            Method setStrokeW = hudMethods[1];
            Method setTextSize = hudMethods[2];
            Method setTextAlign = hudMethods[3];
            Method measureText = hudMethods[4];
            Object alignLeft = hudAligns[0];
            Object alignCenter = hudAligns[1];
            Object alignRight = hudAligns[2];

            Method setRf = hudMethods[5];
            Method setRect = hudMethods[6];
            Method drawRoundRect = hudMethods[7];
            Method drawRect = hudMethods[8];
            Method drawOval = hudMethods[9];
            Method drawLine = hudMethods[10];
            Method drawText = hudMethods[11];
            Method drawPath = hudMethods[12];
            Method drawBmpXY = hudMethods[13];
            Method drawBmpRect = hudMethods[14];
            Method drawBmpRectF = hudMethods[15];

            HudLayout L = (activeHudHeight == HUD_HEIGHT_720) ? LAYOUT_720 : LAYOUT_462;

            // =================================================================
            // 1. BASE CHROMIUM LAYER (1920x462 OR 1920x720) FROM turzx_92_mockup.html
            // =================================================================
            if (bmpBaseShell != null) {
                drawBmpXY.invoke(c, bmpBaseShell, Float.valueOf(0f), Float.valueOf(0f), null);
            } else {
                setColor.invoke(pFill, Integer.valueOf(0xFF171719));
                drawRect.invoke(c, 0f, 0f, 1920f, (float) L.hudH, pFill);
            }

            // =================================================================
            // 2. COLUMN 1: 2x2 EV INSTRUMENT CELLS + TPMS STRIP
            // =================================================================
            // Cell [1,1] 배터리 잔량
            double soc = s.getEffectiveSocPct();
            String socStr = (soc >= 0.0) ? String.format(Locale.US, "%.1f", soc) : "78.4";
            float socPct = (soc >= 0.0) ? (float) Math.max(0.0, Math.min(100.0, soc)) : 78.4f;
            drawValuePlusUnit(c, socStr, "%", 30f, L.socY, L.evBigFs, 0xFFFFFFFF, 24f, 0xFF8A8C92, 4f,
                    alignLeft, pPubBold, pPubMed, pSysBold, pSysMed,
                    setColor, setTextSize, setTextAlign, measureText, drawText);
            drawBarFillOnly(c, rf, pFill, setRf, drawRoundRect, setColor, 30f, L.socBarY, 163f, L.evBarH, socPct, 0xFF4A8AD6);

            // Cell [1,2] 주행가능거리 + 전비
            String distUnit = s.distanceUnitMiles ? "mi" : "km";
            int effectiveDte = s.dteKm;
            if (!isValidDteValue(effectiveDte) && soc > 0.0) {
                double econ = (s.avgEconomyKmKwh > 1.0) ? s.avgEconomyKmKwh : 6.2;
                double kwh = (s.remainEnergyKwh > 0.5) ? s.remainEnergyKwh : (soc * 0.64);
                effectiveDte = (int) Math.round(kwh * econ);
            }
            String dteStr = isValidDteValue(effectiveDte) ? String.valueOf(effectiveDte) : "384";
            drawValuePlusUnit(c, dteStr, distUnit, 227f, L.dteY, L.evBigFs, 0xFFFFFFFF, 24f, 0xFF8A8C92, 4f,
                    alignLeft, pPubBold, pPubMed, pSysBold, pSysMed,
                    setColor, setTextSize, setTextAlign, measureText, drawText);

            String effStr = (s.avgEconomyKmKwh > 0.0)
                    ? String.format(Locale.US, "%.1f %s/kWh", s.avgEconomyKmKwh, distUnit)
                    : ("5.8 " + distUnit + "/kWh");
            drawTextSmart(c, effStr, 227f, L.effY, 24f, 0xFFBDBDC0, false, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            // Cell [2,1] 실시간 전력 + 누적 에너지
            double totalKw = s.getTotalPowerKw();
            boolean hasLivePwr = s.hasPackCurrent || s.hasSubPower || Math.abs(totalKw) > 0.05;
            double dispKw = hasLivePwr ? totalKw : 18.4;
            boolean isRegen = dispKw < -0.05;
            String pwrStr = isRegen
                    ? String.format(Locale.US, "-%.1f", Math.abs(dispKw))
                    : String.format(Locale.US, "+%.1f", Math.abs(dispKw));
            drawValuePlusUnit(c, pwrStr, "kW", 30f, L.pwrY, L.evMidFs, 0xFFFFFFFF, 24f, 0xFF8A8C92, 4f,
                    alignLeft, pPubBold, pPubMed, pSysBold, pSysMed,
                    setColor, setTextSize, setTextAlign, measureText, drawText);

            float pwrPct = (float) Math.min(100.0, Math.max(8.0, (Math.abs(dispKw) / 75.0) * 100.0));
            drawBarFillOnly(c, rf, pFill, setRf, drawRoundRect, setColor,
                    30f, L.pwrBarY, 163f, L.evBarH, pwrPct, isRegen ? 0xFF44A282 : 0xFF4A8AD6);

            double cumVal = (s.remainEnergyKwh > 0.0) ? s.remainEnergyKwh
                    : ((s.cumulDischargeKwh > 0.0) ? s.cumulDischargeKwh : 12.8);
            String cumStr = (cumVal >= 100.0)
                    ? String.format(Locale.US, "%.0f kWh", cumVal)
                    : String.format(Locale.US, "%.1f kWh", cumVal);
            drawTextSmart(c, cumStr, 78f, L.cumY, 24f, 0xFFBDBDC0, true, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            // Cell [2,2] 배터리 온도 + 최저~최고 온도
            Integer repTemp = s.getRepresentativeBattTempC();
            double batTemp = (repTemp != null) ? repTemp.doubleValue() : 24.0;
            String batTempStr = String.format(Locale.US, "%.1f", batTemp);
            drawValuePlusUnit(c, batTempStr, "°C", 227f, L.batTempY, L.evMidFs, 0xFFFFFFFF, 24f, 0xFF8A8C92, 4f,
                    alignLeft, pPubBold, pPubMed, pSysBold, pSysMed,
                    setColor, setTextSize, setTextAlign, measureText, drawText);

            double minBatT = (s.battMinTempC != null) ? s.battMinTempC.doubleValue() : (batTemp - 1.0);
            double maxBatT = (s.battMaxTempC != null) ? s.battMaxTempC.doubleValue() : (batTemp + 1.0);
            String batMinMaxStr = String.format(Locale.US, "%.1f~%.1f°C", minBatT, maxBatT);
            drawTextSmart(c, batMinMaxStr, 227f, L.batMinMaxY, 24f, 0xFFBDBDC0, false, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            // Right TPMS Strip (x = 408..596)
            boolean warnFl = s.tpmsFlPsi > 0f && s.tpmsFlPsi < 31.0f;
            boolean warnFr = s.tpmsFrPsi > 0f && s.tpmsFrPsi < 31.0f;
            boolean warnRl = s.tpmsRlPsi > 0f && s.tpmsRlPsi < 31.0f;
            boolean warnRr = s.tpmsRrPsi > 0f && s.tpmsRrPsi < 31.0f;
            boolean anyTpmsWarn = warnFl || warnFr || warnRl || warnRr;
            if (anyTpmsWarn && bmpActiveShell != null) {
                copySpriteBox(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, L.tpmsTag);
                if (warnFl) copySpriteBox(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, L.tireFl);
                if (warnFr) copySpriteBox(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, L.tireFr);
                if (warnRl) copySpriteBox(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, L.tireRl);
                if (warnRr) copySpriteBox(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, L.tireRr);
            }

            drawTextSmart(c, String.format(Locale.US, "%.0f", s.tpmsFlPsi), 424f, L.tpmsFrontY, L.tpmsValFs,
                    warnFl ? 0xFFD9A441 : 0xFFFFFFFF, true, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
            drawTextSmart(c, String.format(Locale.US, "%.0f", s.tpmsFrPsi), 580f, L.tpmsFrontY, L.tpmsValFs,
                    warnFr ? 0xFFD9A441 : 0xFFFFFFFF, true, alignRight,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
            drawTextSmart(c, String.format(Locale.US, "%.0f", s.tpmsRlPsi), 424f, L.tpmsRearY, L.tpmsValFs,
                    warnRl ? 0xFFD9A441 : 0xFFFFFFFF, true, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
            drawTextSmart(c, String.format(Locale.US, "%.0f", s.tpmsRrPsi), 580f, L.tpmsRearY, L.tpmsValFs,
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
            drawTextSmart(c, tpmsFootStr, 502f, L.tpmsFootY, 24f, 0xFF8A8C92, false, alignCenter,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            // =================================================================
            // 3. COLUMN 2: 1:1 3D PERSPECTIVE ADAS ROAD + CHROMIUM HUD OVERLAY
            // =================================================================
            if (LiveTuning.roadEnabled) {
                long tRoad0 = System.nanoTime();
                drawCommaAdasRoad3D(c, rf, pFill, pStroke, pShader, pPubBold, pPubMed, pSysBold, pSysMed,
                        setRf, drawRoundRect, drawRect, drawOval, drawLine, drawPath, drawText,
                        setColor, setStrokeW, setTextSize, setTextAlign, measureText, alignCenter,
                        canvasCls, paintCls, pathCls, L, s);
                avgRoadMs = ema(avgRoadMs, (System.nanoTime() - tRoad0) / 1e6);
            } else {
                avgRoadMs = 0.0;
                // Experiment: plain dark column instead of the ~400-path 3D road (measures its render cost)
                setColor.invoke(pFill, Integer.valueOf(0xFF0E1118));
                drawRect.invoke(c, 610f, 14f, 1188f, L.roadBottomY, pFill);
            }

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
            drawTextSmart(c, gear, L.gearX, L.gearY, L.gearFs, 0xFF4A8AD6, true, alignCenter,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            int egoSpd = Math.max(0, s.carrotSpeedKph);
            drawTextSmart(c, String.valueOf(egoSpd), L.egoX, L.egoY, L.egoFs, 0xFFFFFFFF, true, alignCenter,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            String cruiseStr = (s.carrotCruiseKph > 0) ? String.valueOf(s.carrotCruiseKph) : "--";
            int cruiseColor = (s.carrotCruiseKph > 0) ? 0xFF44A282 : 0xFF8A8C92;
            drawTextSmart(c, cruiseStr, L.cruiseX, L.cruiseY, L.cruiseFs, cruiseColor, true, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            // SDI speed sign numbers (calibrated per mode to match Chromium circle center)
            int signLimit = (s.carrotSdiSpeedLimit > 0) ? s.carrotSdiSpeedLimit : 0;
            String signLimitStr = (signLimit > 0) ? String.valueOf(signLimit) : "--";
            float sdiX = "section".equals(sdiMode) ? L.sdiSecX : ("traffic".equals(sdiMode) ? L.sdiTrafX : L.sdiNoneX);
            float limitNumY = "section".equals(sdiMode) ? L.sdiSecY : ("traffic".equals(sdiMode) ? L.sdiTrafY : L.sdiNoneY);
            drawTextSmart(c, signLimitStr, sdiX, limitNumY, L.sdiLimitFs, 0xFF131217, true, alignCenter,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            if (!"none".equals(sdiMode) && s.carrotSdiDistM > 0) {
                int sdiDist = s.carrotSdiDistM;
                String sdiDistStr = (sdiDist >= 1000)
                        ? String.format(Locale.US, "%.1f km", sdiDist / 1000.0)
                        : (sdiDist + " m");
                float distY = "section".equals(sdiMode) ? L.sdiDistSecY : L.sdiDistTrafY;
                drawTextSmart(c, sdiDistStr, sdiX, distY, 24f, 0xFFFFFFFF, true, alignCenter,
                        pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
            }
            if ("section".equals(sdiMode)) {
                int avgSpd = (egoSpd > 0) ? egoSpd : signLimit;
                String avgStr = (avgSpd > 0) ? String.valueOf(avgSpd) : "--";
                drawTextSmart(c, avgStr, L.sdiAvgX, L.sdiAvgY, L.sdiAvgFs, 0xFF44A282, true, alignCenter,
                        pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
            }

            // TBT compact stamp on top-right
            String tbtDistStr;
            if (s.carrotTbtDistM > 0) {
                tbtDistStr = (s.carrotTbtDistM >= 1000)
                        ? String.format(Locale.US, "%.1fkm", s.carrotTbtDistM / 1000.0)
                        : (s.carrotTbtDistM + "m");
            } else {
                tbtDistStr = "--m";
            }
            drawTextSmart(c, tbtDistStr, L.tbtDistX, L.tbtDistY, L.tbtDistFs, 0xFFFFFFFF, true, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            String roadName = (s.carrotTbtRoad != null && !s.carrotTbtRoad.isEmpty()) ? s.carrotTbtRoad : "주행 중";
            if (roadName.contains("·")) {
                String[] parts = roadName.split("·");
                roadName = parts[parts.length - 1].trim();
            }
            drawTextSmart(c, truncateText(roadName, 6), L.tbtRoadX, L.tbtRoadY, L.tbtRoadFs, 0xFFBDBDC0, true, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            String nextTbtStr;
            if (s.carrotTbtDistNextM > 0) {
                nextTbtStr = (s.carrotTbtDistNextM >= 1000)
                        ? String.format(Locale.US, "+%.1fkm", s.carrotTbtDistNextM / 1000.0)
                        : ("+" + s.carrotTbtDistNextM + "m");
            } else {
                nextTbtStr = s.carrotConnected ? "COMMA" : "CAN";
            }
            drawTextSmart(c, nextTbtStr, L.tbtNextX, L.tbtNextY, L.tbtNextFs, 0xFF4A8AD6, true, alignRight,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            // Bottom corner chips: "차선" & "앞차 · 조향각"
            String laneChipStr;
            if (s.laneConfidencePct > 0f) {
                laneChipStr = String.format(Locale.US, "%.0f%%", s.laneConfidencePct);
            } else if (s.carrotConnected) {
                laneChipStr = s.carrotActive ? "ON" : "대기";
            } else {
                laneChipStr = "--%";
            }
            drawTextSmart(c, laneChipStr, L.laneChipX, L.laneChipY, L.laneChipFs, 0xFFBDBDC0, false, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            String leadText = (s.hasLeadCar && s.leadDistM > 0.5f)
                    ? String.format(Locale.US, "%.0fm", s.leadDistM)
                    : "--m";
            float steerDeg = (Math.abs(s.steeringAngleDeg) > 0.05f)
                    ? s.steeringAngleDeg
                    : ((s.pathPoly != null && s.pathPoly.length > 2) ? (-s.pathPoly[2] * 40000f) : 0.0f);
            String steerText = String.format(Locale.US, " · %+.1f°", steerDeg);
            float wLead = drawTextSmart(c, leadText, L.leadChipX, L.leadChipY, L.leadChipFs, 0xFFFFFFFF, true, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
            drawTextSmart(c, steerText, L.leadChipX + wLead, L.leadChipY, L.leadChipFs, 0xFFBDBDC0, false, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            // =================================================================
            // 4. COLUMN 3 TOP: NOW PLAYING MEDIA
            // =================================================================
            if (s.albumArtBitmap != null && bmpCls.isInstance(s.albumArtBitmap)) {
                try {
                    if (s.albumArtBitmap != artShaderBitmap) {
                        // New artwork: size it once; the rounded-corner shader below is built once per bitmap
                        artShaderBitmap = s.albumArtBitmap;
                        artShader = null;
                        artBmpW = ((Integer) bmpCls.getMethod("getWidth").invoke(s.albumArtBitmap)).intValue();
                        artBmpH = ((Integer) bmpCls.getMethod("getHeight").invoke(s.albumArtBitmap)).intValue();
                    }
                    int bw = artBmpW;
                    int bh = artBmpH;
                    if (bw > 0 && bh > 0) {
                        float ax0 = L.artX0, ay0 = L.artY0, ax1 = L.artX1, ay1 = L.artY1, aRad = L.artRad;
                        float dstW = ax1 - ax0;
                        float dstH = ay1 - ay0;
                        setRf.invoke(rf, ax0, ay0, ax1, ay1);
                        boolean drawnRounded = false;
                        try {
                            Class<?> shaderCls = Class.forName("android.graphics.Shader");
                            if (artShader == null) {
                                Class<?> tileCls = Class.forName("android.graphics.Shader$TileMode");
                                Class<?> bsCls = Class.forName("android.graphics.BitmapShader");
                                Class<?> matCls = Class.forName("android.graphics.Matrix");
                                Object clamp = tileCls.getField("CLAMP").get(null);
                                Object bs = bsCls.getConstructor(bmpCls, tileCls, tileCls)
                                        .newInstance(s.albumArtBitmap, clamp, clamp);
                                Object mat = matCls.getConstructor().newInstance();
                                float scale = Math.max(dstW / (float) bw, dstH / (float) bh);
                                float dx = ax0 + (dstW - bw * scale) * 0.5f;
                                float dy = ay0 + (dstH - bh * scale) * 0.5f;
                                matCls.getMethod("setScale", float.class, float.class)
                                        .invoke(mat, Float.valueOf(scale), Float.valueOf(scale));
                                matCls.getMethod("postTranslate", float.class, float.class)
                                        .invoke(mat, Float.valueOf(dx), Float.valueOf(dy));
                                shaderCls.getMethod("setLocalMatrix", matCls).invoke(bs, mat);
                                artShader = bs;
                            }
                            Object bs = artShader;
                            Method setShaderM = roadMethods != null ? roadMethods[3] : paintCls.getMethod("setShader", shaderCls);
                            setColor.invoke(pShader, Integer.valueOf(0xFFFFFFFF));
                            setShaderM.invoke(pShader, bs);
                            drawRoundRect.invoke(c, rf, Float.valueOf(aRad), Float.valueOf(aRad), pShader);
                            setShaderM.invoke(pShader, new Object[]{null});
                            setColor.invoke(pStroke, Integer.valueOf(0x33FFFFFF));
                            setStrokeW.invoke(pStroke, Float.valueOf(1.5f));
                            drawRoundRect.invoke(c, rf, Float.valueOf(aRad), Float.valueOf(aRad), pStroke);
                            drawnRounded = true;
                        } catch (Throwable ignoredShader) {
                        }
                        if (!drawnRounded) {
                            drawBmpRectF.invoke(c, s.albumArtBitmap, null, rf, null);
                        }
                    }
                } catch (Throwable ignored) {
                }
            } else if (artShaderBitmap != null) {
                artShaderBitmap = null; // release the cached artwork shader once the art is gone
                artShader = null;
            }

            boolean hasRealMedia = s.mediaTitle != null
                    && !s.mediaTitle.isEmpty()
                    && !"미디어 재생 대기 중".equals(s.mediaTitle);
            String mTitle = (s.mediaTitle != null && !s.mediaTitle.isEmpty()) ? s.mediaTitle : "미디어 재생 대기 중";
            String mSub;
            if (hasRealMedia) {
                mSub = (s.mediaArtist != null && !s.mediaArtist.isEmpty()) ? s.mediaArtist : "";
                if (s.mediaAlbum != null && !s.mediaAlbum.isEmpty() && !s.mediaAlbum.equals(s.mediaArtist)) {
                    mSub = mSub.isEmpty() ? s.mediaAlbum : (mSub + " · " + s.mediaAlbum);
                }
                if (mSub.isEmpty()) {
                    mSub = (s.mediaSource != null && !s.mediaSource.isEmpty()) ? s.mediaSource : "Bluetooth Audio";
                }
            } else {
                mSub = (s.mediaSource != null && !s.mediaSource.isEmpty()) ? s.mediaSource : "Bluetooth · USB · Radio";
            }

            drawTextSmart(c, truncateText(mTitle, L.titleMaxChars), L.mediaTextX, L.mediaTitleY, L.mediaTitleFs,
                    0xFFFFFFFF, true, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
            drawTextSmart(c, truncateText(mSub, L.subMaxChars), L.mediaTextX, L.mediaSubY, L.mediaSubFs,
                    0xFFBDBDC0, false, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            int curSec = hasRealMedia ? Math.max(0, s.mediaPositionSec) : 0;
            int durSec = hasRealMedia ? Math.max(0, s.mediaDurationSec) : 0;
            float mediaPct = (durSec > 0) ? Math.min(100f, (curSec * 100f) / durSec) : 0f;
            drawBarFillOnly(c, rf, pFill, setRf, drawRoundRect, setColor,
                    L.mediaTextX, L.mediaBarY, L.mediaBarW, L.mediaBarH, mediaPct, 0xFFFFFFFF);

            drawTextSmart(c, formatSecMinTwoDigit(curSec), L.mediaTextX, L.mediaCurTimeY, L.mediaTimeFs,
                    0xFFFFFFFF, true, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
            drawTextSmart(c, formatSecMinTwoDigit(durSec), L.mediaDurTimeX, L.mediaDurTimeY, L.mediaTimeFs,
                    0xFF8A8C92, false, alignRight,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            // =================================================================
            // 5. DATC CLIMATE (COLUMN 3 BOTTOM ON 9.2" / PERSISTENT BOTTOM TASKBAR ON 12.3")
            // =================================================================
            String drNum = !s.hvacOn ? "OFF" : formatHvacTempNumberOnly(s.hvacDrTempC);
            String drDeg = (!s.hvacOn || "LO".equals(drNum) || "HI".equals(drNum)) ? "" : "°";
            int drColor = s.hvacOn ? 0xFFFFFFFF : 0xFF8A8C92;
            drawValuePlusUnit(c, drNum, drDeg, L.drvTempX, L.hvacTempY, 48f, drColor, 26f, 0xFF8A8C92, 1f,
                    alignLeft, pPubBold, pPubMed, pSysBold, pSysMed,
                    setColor, setTextSize, setTextAlign, measureText, drawText);

            String inStr = (s.inCarTempC > -50.0)
                    ? String.format(Locale.US, "%.1f°", s.inCarTempC)
                    : "22.5°";
            drawTextSmart(c, inStr, L.inTempX, L.hvacEnvY, 24f, 0xFFBDBDC0, true, alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            if (L.showPasTemp) {
                String psNum = !s.hvacOn ? "OFF" : (s.hvacDriverOnly ? "DRV" : formatHvacTempNumberOnly(s.hvacPsTempC));
                boolean psHasDeg = s.hvacOn && !s.hvacDriverOnly && !"LO".equals(psNum) && !"HI".equals(psNum);
                int psColor = (s.hvacOn && !s.hvacDriverOnly) ? 0xFFFFFFFF : 0xFF8A8C92;
                if (psHasDeg) {
                    float wDeg = drawTextSmart(c, "°", L.pasTempX, L.hvacTempY, 26f, 0xFF8A8C92, true, alignRight,
                            pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
                    drawTextSmart(c, psNum, L.pasTempX - wDeg - 1f, L.hvacTempY, 48f, psColor, true, alignRight,
                            pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
                } else {
                    drawTextSmart(c, psNum, L.pasTempX, L.hvacTempY, 48f, psColor, true, alignRight,
                            pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
                }
            }

            String outStr = (s.outdoorTempC > -50.0)
                    ? String.format(Locale.US, "%.1f°", s.outdoorTempC)
                    : "18.0°";
            drawTextSmart(c, outStr, L.outTempX, L.hvacEnvY, 24f, 0xFFBDBDC0, true,
                    L.outTempAlignRight ? alignRight : alignLeft,
                    pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);

            // Center Airflow Arrows (composite active arrows from bmpActiveShell)
            int bm = s.hvacBlowerMode;
            boolean dirUp = s.hvacOn && (bm == 5 || bm == 4 || bm == 7 || bm == 9 || bm == 10 || s.hvacFrontDefog);
            boolean dirMid = s.hvacOn && (bm == 1 || bm == 2 || bm == 7 || bm == 9 || bm < 0);
            boolean dirDown = s.hvacOn && (bm == 2 || bm == 3 || bm == 4 || bm == 8 || bm == 9 || bm < 0);
            if (bmpActiveShell != null) {
                if (dirUp) {
                    copySpriteBox(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, L.dirUpA);
                    copySpriteBox(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, L.dirUpB);
                }
                if (dirMid) {
                    copySpriteBox(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, L.dirMidA);
                    copySpriteBox(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, L.dirMidB);
                }
                if (dirDown) {
                    copySpriteBox(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, L.dirDownA);
                    copySpriteBox(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, L.dirDownB);
                }
            }

            // Center Fan Step & 7-Bar Rail
            int fanStep = !s.hvacOn ? 0 : ((s.hvacBlowerStep >= 0) ? Math.min(7, s.hvacBlowerStep) : 3);
            if (fanStep > 0) {
                drawTextSmart(c, String.valueOf(fanStep), L.fanStepX, L.fanStepY, 32f, 0xFFFFFFFF, true, alignRight,
                        pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
                if (bmpActiveShell != null) {
                    int barRightX = Math.round(L.fanBarBase + (fanStep - 1) * L.fanBarStep + L.fanBarSegW);
                    copySpriteRect(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect,
                            L.fanBarCopyX0, L.fanBarCopyY0, barRightX, L.fanBarCopyY1);
                }
            } else {
                if (bmpActiveShell != null) {
                    copySpriteBox(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, L.fanOffClear);
                }
                drawTextSmart(c, "OFF", L.fanOffX, L.fanStepY, 32f, 0xFF8A8C92, true, alignRight,
                        pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
            }

            // Bottom 7 HVAC Dock Buttons (composite active buttons from bmpActiveShell)
            // [1:AUTO] [2:A/C] [3:HEAT] [4:FRONT DEFOG] [5:REAR DEFOG/MIRROR] [6:RECIRC] [7:SYNC]
            if (bmpActiveShell != null) {
                if (s.hvacOn && s.hvacAuto) {
                    copySpriteBox(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, L.hvacBtns[0]);
                }
                if (s.hvacOn && s.hvacAc) {
                    copySpriteBox(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, L.hvacBtns[1]);
                }
                if (s.hvacOn && s.hvacHeat) {
                    copySpriteBox(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, L.hvacBtns[2]);
                }
                if (s.hvacOn && (s.hvacFrontDefog || dirUp)) {
                    copySpriteBox(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, L.hvacBtns[3]);
                }
                if (s.hvacRearDefog) {
                    copySpriteBox(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, L.hvacBtns[4]);
                }
                if (s.hvacRecirc) {
                    copySpriteBox(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, L.hvacBtns[5]);
                }
                if (s.hvacOn && s.hvacSync) {
                    copySpriteBox(c, bmpActiveShell, sRect, dRect, setRect, drawBmpRect, L.hvacBtns[6]);
                }
            }
        }

        /**
         * 1:1 port of renderCommaAdasCanvas() from turzx_92_mockup.html.
         * Renders into Column 2 at (ox=610, oy=14, W=578, H=434 or 692).
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
                HudLayout L,
                TelemetrySnapshot s
        ) throws Exception {
            if (roadMethods == null) {
                Class<?> shaderCls = Class.forName("android.graphics.Shader");
                Class<?> tileCls = Class.forName("android.graphics.Shader$TileMode");
                Class<?> lgCls = Class.forName("android.graphics.LinearGradient");
                Class<?> rgCls = Class.forName("android.graphics.RadialGradient");
                Class<?> roadBmpCls = Class.forName("android.graphics.Bitmap");
                roadClampMode = tileCls.getField("CLAMP").get(null);
                roadCtors = new Constructor<?>[]{
                        lgCls.getConstructor(
                                float.class, float.class, float.class, float.class, int.class, int.class, tileCls),
                        lgCls.getConstructor(
                                float.class, float.class, float.class, float.class, int[].class, float[].class, tileCls),
                        rgCls.getConstructor(
                                float.class, float.class, float.class, int[].class, float[].class, tileCls)
                };
                roadMethods = new Method[]{
                        canvasCls.getMethod("save"),
                        canvasCls.getMethod("restoreToCount", int.class),
                        canvasCls.getMethod("clipRect", float.class, float.class, float.class, float.class),
                        paintCls.getMethod("setShader", shaderCls),
                        pathCls.getMethod("reset"),
                        pathCls.getMethod("moveTo", float.class, float.class),
                        pathCls.getMethod("lineTo", float.class, float.class),
                        pathCls.getMethod("quadTo", float.class, float.class, float.class, float.class),
                        pathCls.getMethod("close"),
                        canvasCls.getMethod("drawBitmap", roadBmpCls, float.class, float.class, paintCls),
                        canvasCls.getMethod("translate", float.class, float.class),
                        roadBmpCls.getMethod("setHasAlpha", boolean.class)
                };
                roadPath = pathCls.getConstructor().newInstance();
            }
            Method saveM = roadMethods[0];
            Method restoreToCountM = roadMethods[1];
            Method clipRectM = roadMethods[2];
            Object clampMode = roadClampMode;
            Method setShaderM = roadMethods[3];
            Constructor<?> lgTwoCtor = roadCtors[0];
            Constructor<?> lgMultiCtor = roadCtors[1];
            Constructor<?> rgMultiCtor = roadCtors[2];

            Method resetP = roadMethods[4];
            Method moveTo = roadMethods[5];
            Method lineTo = roadMethods[6];
            Method quadTo = roadMethods[7];
            Method closeP = roadMethods[8];
            Object path = roadPath;

            int saveCount = ((Integer) saveM.invoke(c)).intValue();
            clipRectM.invoke(c, 609f, 13f, 1189f, L.roadClipBottomY);

            float ox = 610f;
            float oy = 14f;
            float W = 578f;
            float H = L.roadH;
            float horizonY = L.roadHorizonY;

            // Camera intrinsics in 578xH pixel space
            float u0 = W * 0.565f;
            float v0 = horizonY;
            float fx = 395f;
            float fy = L.roadFy;
            float camH = 1.35f;

            boolean hasRealLead = s.hasLeadCar && s.leadDistM > 0.5f;
            float leadDistM = hasRealLead ? s.leadDistM : 65.0f;
            float visualLeadX = 6.2f + (float) Math.pow(Math.max(6.0, leadDistM), 0.62) * 1.05f;

            float curveC2 = 0.0f;
            float curveC1 = 0.0f;
            if (s.pathPoly != null && s.pathPoly.length >= 3
                    && (Math.abs(s.pathPoly[1]) > 0.00001f || Math.abs(s.pathPoly[2]) > 0.00001f)) {
                curveC1 = Math.max(-0.04f, Math.min(0.04f, s.pathPoly[1]));
                curveC2 = Math.max(-0.0025f, Math.min(0.0025f, s.pathPoly[2]));
            } else if (Math.abs(s.steeringAngleDeg) > 0.05f) {
                curveC1 = Math.max(-0.04f, Math.min(0.04f, -s.steeringAngleDeg * 0.0006f));
                curveC2 = Math.max(-0.0025f, Math.min(0.0025f, -s.steeringAngleDeg * 0.000025f));
            }

            // 1+2. Sky, ground plane, horizon glow, horizon rule and sensor ticks: an opaque backdrop cached in a
            // 578x434 bitmap (v1.4.4). Only the glow centre follows the path curvature; it is quantized to 4px, so
            // steady driving reuses the cache instead of re-shading three full-area gradients every frame.
            float vpY75 = roadCenterLat(75f, curveC1, curveC2);
            int bgKey = Math.round(projU(ox, u0, fx, 75f, vpY75) / 4f);
            if (roadBgBitmap == null || bgKey != roadBgKey) {
                if (roadBgBitmap == null) {
                    Class<?> bmpCls = Class.forName("android.graphics.Bitmap");
                    Class<?> cfgCls = Class.forName("android.graphics.Bitmap$Config");
                    roadBgBitmap = bmpCls.getMethod("createBitmap", int.class, int.class, cfgCls)
                            .invoke(null, Integer.valueOf((int) W), Integer.valueOf((int) H), cfgCls.getField("ARGB_8888").get(null));
                    roadBgCanvas = canvasCls.getConstructor(bmpCls).newInstance(roadBgBitmap);
                    // Road-local coordinates: draw with the same (ox, oy) based numbers as the main canvas
                    roadMethods[10].invoke(roadBgCanvas, Float.valueOf(-ox), Float.valueOf(-oy));
                }
                Object bc = roadBgCanvas;
                float vpU = bgKey * 4f;

                Object skyGrad = lgTwoCtor.newInstance(
                        ox, oy, ox, oy + horizonY, Integer.valueOf(0xFF07080C), Integer.valueOf(0xFF0F131D), clampMode);
                setShaderM.invoke(pShader, skyGrad);
                drawRect.invoke(bc, ox, oy, ox + W, oy + horizonY, pShader);

                Object groundGrad = lgMultiCtor.newInstance(
                        ox, oy + horizonY, ox, oy + H,
                        new int[]{0xFF0E1118, 0xFF11131A, 0xFF0B0C10},
                        new float[]{0f, 0.45f, 1f},
                        clampMode);
                setShaderM.invoke(pShader, groundGrad);
                drawRect.invoke(bc, ox, oy + horizonY, ox + W, oy + H, pShader);

                // Subtle radial horizon glow around vanishing point
                Object hGlow = rgMultiCtor.newInstance(
                        vpU, oy + horizonY + 12f, 310f,
                        new int[]{0x294A8AD6, 0x0F44A282, 0x000B0C10},
                        new float[]{0f, 0.5f, 1f},
                        clampMode);
                setShaderM.invoke(pShader, hGlow);
                drawRect.invoke(bc, ox, oy, ox + W, oy + H, pShader);
                setShaderM.invoke(pShader, new Object[]{null});

                // Horizon Azimuth Rule & Subtle Sensor Ticks
                setColor.invoke(pStroke, Integer.valueOf(0xFF1F2430));
                setStrokeW.invoke(pStroke, 1.5f);
                drawLine.invoke(bc, ox, oy + horizonY, ox + W, oy + horizonY, pStroke);

                for (int lat = -12; lat <= 12; lat += 2) {
                    float px = projU(ox, u0, fx, 60f, (float) lat);
                    setColor.invoke(pStroke, Integer.valueOf(lat == 0 ? 0xFF3A4152 : 0xFF1C2029));
                    drawLine.invoke(bc, px, oy + horizonY - 4f, px, oy + horizonY + 4f, pStroke);
                }
                try {
                    roadMethods[11].invoke(roadBgBitmap, Boolean.FALSE); // opaque -> plain row copy when blitted
                } catch (Throwable ignored) {
                }
                roadBgKey = bgKey;
            }
            roadMethods[9].invoke(c, roadBgBitmap, Float.valueOf(ox), Float.valueOf(oy), null);

            // 3. Multi-Lane Asphalt Roadbed Surface (3 Lanes: Y = -5.4m to +5.4m, 38 steps)
            float[] xSteps = ROAD_X_STEPS;

            if (roadSurfShaders == null) {
                // Constant geometry -> built once (was re-created, with its native SkShader, on every frame)
                roadSurfShaders = new Object[]{
                        lgMultiCtor.newInstance(
                                ox, oy + horizonY, ox, oy + H,
                                new int[]{0xFF12151E, 0xFF161922, 0xFF101218},
                                new float[]{0f, 0.4f, 1f},
                                clampMode),
                        lgMultiCtor.newInstance(
                                ox, oy + horizonY, ox, oy + H,
                                new int[]{0xFF161A24, 0xFF1B202C, 0xFF141821},
                                new float[]{0f, 0.5f, 1f},
                                clampMode)
                };
            }
            setShaderM.invoke(pShader, roadSurfShaders[0]);
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
            setShaderM.invoke(pShader, roadSurfShaders[1]);
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

            // Strip fades (alpha by distance) as vertical gradients: distance maps 1:1 to screen y, so one shader per
            // strip style replaces a per-segment paint colour, and each strip is a single path (v1.4.4: ~370 -> ~11
            // anti-aliased path fills per frame).
            if (roadStripShaders == null) {
                roadStripShaders = new Object[]{
                        stripFadeShader(lgMultiCtor, clampMode, xSteps, oy, v0, fy, camH, 74, 82, 98, 0.65f),
                        stripFadeShader(lgMultiCtor, clampMode, xSteps, oy, v0, fy, camH, 148, 163, 184, 0.38f),
                        stripFadeShader(lgMultiCtor, clampMode, xSteps, oy, v0, fy, camH, 74, 138, 214, 0.25f),
                        stripFadeShader(lgMultiCtor, clampMode, xSteps, oy, v0, fy, camH, 120, 188, 255, 0.96f)
                };
            }

            // 5. Road Shoulder Edges (-5.4m, +5.4m) & Adjacent Dashed Lane Lines (-3.6m, +3.6m)
            drawTaperedStrip3D(c, path, pShader, setShaderM, roadStripShaders[0], resetP, moveTo, lineTo, closeP, drawPath,
                    xSteps, ox, oy, u0, v0, fx, fy, camH, curveC1, curveC2, 0.06f, false, roadPhase, -5.4f, 5.4f);
            drawTaperedStrip3D(c, path, pShader, setShaderM, roadStripShaders[1], resetP, moveTo, lineTo, closeP, drawPath,
                    xSteps, ox, oy, u0, v0, fx, fy, camH, curveC1, curveC2, 0.055f, true, roadPhase, -3.6f, 3.6f);

            // 6. Comma modelV2 Predicted Steering Trajectory Carpet (34 steps)
            float pathMaxX = Math.max(6.8f, visualLeadX - 0.6f);
            float pathHalfW = 0.76f;
            int pathSteps = 34;
            boolean isCloseLead = hasRealLead && leadDistM <= 18f;
            boolean isCriticalLead = hasRealLead && leadDistM <= 12f;
            int carpR = isCriticalLead ? 229 : (isCloseLead ? 217 : 68);
            int carpG = isCriticalLead ? 86 : (isCloseLead ? 164 : 162);
            int carpB = isCriticalLead ? 111 : (isCloseLead ? 65 : 130);
            int railR = isCriticalLead ? 255 : (isCloseLead ? 245 : 95);
            int railG = isCriticalLead ? 133 : (isCloseLead ? 194 : 195);
            int railB = isCriticalLead ? 152 : (isCloseLead ? 96 : 156);

            float[] pxs = new float[pathSteps + 1];
            float[] pws = new float[pathSteps + 1];
            for (int i = 0; i <= pathSteps; i++) {
                float t = i / (float) pathSteps;
                pxs[i] = 3.85f + (pathMaxX - 3.85f) * (t * t);
                pws[i] = pathHalfW * (1f - t * 0.16f);
            }
            int trajKey = Float.floatToIntBits(pathMaxX) * 31 + (isCriticalLead ? 2 : (isCloseLead ? 1 : 0));
            if (roadTrajShaders == null || trajKey != roadTrajKey) {
                // Alpha of segment i was (1 - t0*0.65)*0.38 (carpet) / max(0.22, 1 - t0*0.72) (rails), t0 = i/steps
                float[] carpA = new float[pathSteps + 1];
                float[] railA = new float[pathSteps + 1];
                for (int i = 0; i <= pathSteps; i++) {
                    float t = Math.min(i, pathSteps - 1) / (float) pathSteps;
                    carpA[i] = (1f - t * 0.65f) * 0.38f;
                    railA[i] = Math.max(0.22f, 1f - t * 0.72f);
                }
                roadTrajShaders = new Object[]{
                        fadeShader(lgMultiCtor, clampMode, pxs, carpA, oy, v0, fy, camH, carpR, carpG, carpB),
                        fadeShader(lgMultiCtor, clampMode, pxs, railA, oy, v0, fy, camH, railR, railG, railB)
                };
                roadTrajKey = trajKey;
            }

            // Carpet: one polygon (left edge out, right edge back)
            resetP.invoke(path);
            for (int i = 0; i <= pathSteps; i++) {
                float cLat = roadCenterLat(pxs[i], curveC1, curveC2);
                float u = projU(ox, u0, fx, pxs[i], cLat - pws[i]);
                float v = projV(oy, v0, fy, camH, pxs[i], 0f);
                if (i == 0) moveTo.invoke(path, u, v);
                else lineTo.invoke(path, u, v);
            }
            for (int i = pathSteps; i >= 0; i--) {
                float cLat = roadCenterLat(pxs[i], curveC1, curveC2);
                lineTo.invoke(path, projU(ox, u0, fx, pxs[i], cLat + pws[i]), projV(oy, v0, fy, camH, pxs[i], 0f));
            }
            closeP.invoke(path);
            setShaderM.invoke(pShader, roadTrajShaders[0]);
            drawPath.invoke(c, path, pShader);

            // Trajectory Left & Right 3D Edge Rails: both rails as sub-paths of one path
            float wRail = 0.045f;
            resetP.invoke(path);
            for (int side = -1; side <= 1; side += 2) {
                for (int i = 0; i <= pathSteps; i++) {
                    float lat = roadCenterLat(pxs[i], curveC1, curveC2) + side * pws[i];
                    float u = projU(ox, u0, fx, pxs[i], lat - wRail);
                    float v = projV(oy, v0, fy, camH, pxs[i], 0f);
                    if (i == 0) moveTo.invoke(path, u, v);
                    else lineTo.invoke(path, u, v);
                }
                for (int i = pathSteps; i >= 0; i--) {
                    float lat = roadCenterLat(pxs[i], curveC1, curveC2) + side * pws[i];
                    lineTo.invoke(path, projU(ox, u0, fx, pxs[i], lat + wRail), projV(oy, v0, fy, camH, pxs[i], 0f));
                }
                closeP.invoke(path);
            }
            setShaderM.invoke(pShader, roadTrajShaders[1]);
            drawPath.invoke(c, path, pShader);
            setShaderM.invoke(pShader, new Object[]{null});

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
            drawTaperedStrip3D(c, path, pShader, setShaderM, roadStripShaders[2], resetP, moveTo, lineTo, closeP, drawPath,
                    xSteps, ox, oy, u0, v0, fx, fy, camH, curveC1, curveC2, 0.19f, false, roadPhase, -1.82f, 1.82f);
            drawTaperedStrip3D(c, path, pShader, setShaderM, roadStripShaders[3], resetP, moveTo, lineTo, closeP, drawPath,
                    xSteps, ox, oy, u0, v0, fx, fy, camH, curveC1, curveC2, 0.068f, false, roadPhase, -1.82f, 1.82f);
            setShaderM.invoke(pShader, new Object[]{null});

            // 8. 3D-Projected Lead Vehicle Model + CarrotPilot Radar Lock (only when real lead vehicle is detected)
            if (hasRealLead) {
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
                drawTextSmart(c, String.format(Locale.US, "%.0fm", s.leadDistM), cx, tagY + 17.5f, 16f, 0xFFFFFFFF, true, alignCenter,
                        pPubBold, pPubMed, pSysBold, pSysMed, setColor, setTextSize, setTextAlign, measureText, drawText);
            }

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

        /**
         * Draws tapered 3D strips (one per lateral offset) as ONE path filled with a distance-fade gradient shader.
         * Dashed strips keep the 3m on/off pattern as separate sub-paths of the same path.
         */
        private static void drawTaperedStrip3D(
                Object c,
                Object path,
                Object pShader,
                Method setShaderM,
                Object fadeShader,
                Method resetP,
                Method moveTo,
                Method lineTo,
                Method closeP,
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
                float halfWidthM,
                boolean dashed,
                float phase,
                float... latOffsets
        ) throws Exception {
            resetP.invoke(path);
            int[] runs = stripRuns(xSteps, dashed, phase);
            for (int li = 0; li < latOffsets.length; li++) {
                float lat = latOffsets[li];
                for (int r = 0; r + 1 < runs.length; r += 2) {
                    int a = runs[r];
                    int b = runs[r + 1];
                    for (int k = a; k <= b; k++) {
                        float cl = roadCenterLat(xSteps[k], curveC1, curveC2) + lat;
                        float u = projU(ox, u0, fx, xSteps[k], cl - halfWidthM);
                        float v = projV(oy, v0, fy, camH, xSteps[k], 0f);
                        if (k == a) moveTo.invoke(path, u, v);
                        else lineTo.invoke(path, u, v);
                    }
                    for (int k = b; k >= a; k--) {
                        float cl = roadCenterLat(xSteps[k], curveC1, curveC2) + lat;
                        lineTo.invoke(path, projU(ox, u0, fx, xSteps[k], cl + halfWidthM),
                                projV(oy, v0, fy, camH, xSteps[k], 0f));
                    }
                    closeP.invoke(path);
                }
            }
            setShaderM.invoke(pShader, fadeShader);
            drawPath.invoke(c, path, pShader);
        }

        /**
         * Visible runs of a strip as {firstPoint, lastPoint} pairs over xs. Segment i spans points i..i+1; a dashed
         * strip hides segment i when floor((xs[i] + phase) / 3) is odd (3m dashes, same rule as the per-segment code).
         */
        static int[] stripRuns(float[] xs, boolean dashed, float phase) {
            int n = xs.length;
            int[] tmp = new int[Math.max(2, n + 1)];
            int cnt = 0;
            int runStart = -1;
            for (int i = 0; i < n - 1; i++) {
                boolean on = !dashed || ((((int) Math.floor((xs[i] + phase) / 3.0f)) & 1) == 0);
                if (on && runStart < 0) runStart = i;
                if (runStart >= 0 && (!on || i == n - 2)) {
                    tmp[cnt++] = runStart;
                    tmp[cnt++] = on ? i + 1 : i;
                    runStart = -1;
                }
            }
            int[] out = new int[cnt];
            System.arraycopy(tmp, 0, out, 0, cnt);
            return out;
        }

        /** Road sample distances (m) for the roadbed and lane strips: 39 points, denser near the car. */
        static final float[] ROAD_X_STEPS = buildRoadXSteps();

        private static float[] buildRoadXSteps() {
            float[] xs = new float[39];
            for (int i = 0; i <= 38; i++) {
                float t = i / 38.0f;
                xs[i] = 3.85f + 82.0f * (t * t);
            }
            return xs;
        }

        /** Lane-strip fade: alpha = max(0.08, 1 - x/88) * baseAlpha at each sample distance. */
        private static Object stripFadeShader(Constructor<?> lgMultiCtor, Object clampMode, float[] xs,
                                              float oy, float v0, float fy, float camH,
                                              int r, int g, int b, float baseAlpha) throws Exception {
            float[] alphas = new float[xs.length];
            for (int i = 0; i < xs.length; i++) {
                alphas[i] = Math.max(0.08f, 1.0f - (xs[i] / 88.0f)) * baseAlpha;
            }
            return fadeShader(lgMultiCtor, clampMode, xs, alphas, oy, v0, fy, camH, r, g, b);
        }

        /**
         * Vertical LinearGradient whose alpha follows {@code alphas[i]} at the screen row of distance {@code xs[i]}
         * (xs ascending -> rows ascending up the screen). Colours are (alpha, r, g, b).
         */
        private static Object fadeShader(Constructor<?> lgMultiCtor, Object clampMode, float[] xs, float[] alphas,
                                         float oy, float v0, float fy, float camH,
                                         int r, int g, int b) throws Exception {
            float[] pos = fadeStopPositions(xs, oy, v0, fy, camH);
            int[] colors = new int[xs.length];
            for (int i = 0; i < xs.length; i++) {
                colors[i] = argbFromAlphaRgb(alphas[i], r, g, b);
            }
            float vNear = projV(oy, v0, fy, camH, xs[0], 0f);
            float vFar = projV(oy, v0, fy, camH, xs[xs.length - 1], 0f);
            return lgMultiCtor.newInstance(0f, vNear, 0f, vFar, colors, pos, clampMode);
        }

        /** Gradient stop positions (0 = nearest row, 1 = farthest row), clamped and non-decreasing. */
        static float[] fadeStopPositions(float[] xs, float oy, float v0, float fy, float camH) {
            float vNear = projV(oy, v0, fy, camH, xs[0], 0f);
            float vFar = projV(oy, v0, fy, camH, xs[xs.length - 1], 0f);
            float span = vNear - vFar;
            float[] pos = new float[xs.length];
            float prev = 0f;
            for (int i = 0; i < xs.length; i++) {
                float p = (span > 0.0001f) ? (vNear - projV(oy, v0, fy, camH, xs[i], 0f)) / span : (i / (float) Math.max(1, xs.length - 1));
                p = Math.max(prev, Math.max(0f, Math.min(1f, p)));
                pos[i] = p;
                prev = p;
            }
            pos[0] = 0f;
            pos[xs.length - 1] = 1f;
            return pos;
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
            TelemetryCollector.onDatcIg3StatusCallback(ig3);
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

    public static final class BcmListenerBridge extends com.hkmc.bcm.IBCMListener.Stub {
        @Override public void onACCChanged(int a) {
            TelemetryCollector.onBcmAccChangedCallback(a);
        }
        @Override public void onAMPCallSystemStatusChanged(int a) {}
        @Override public void onAccInputCheckChanged(int a) {}
        @Override public void onAirbagStatusChanged(int a) {}
        @Override public void onAstSeatHeaterStatusChanged(int a) {}
        @Override public void onBatEvtAlarmChanged(int a) {}
        @Override public void onBatSocStatus(int a) {}
        @Override public void onBatSocStatus_M(int a) {}
        @Override public void onBattChargeDelay(int a, int b) {}
        @Override public void onBatteryPreconditioning(int a) {}
        @Override public void onCCUDiagStatus(int a) {}
        @Override public void onCDMChanged(int a, int b, int c) {}
        @Override public void onCanNetworkStatus(int a) {}
        @Override public void onChangedBattAutoCutPreWrng(int a) {}
        @Override public void onChangedBattMngAlert(int a) {}
        @Override public void onDPFLampStatChanged(int a) {}
        @Override public void onDigitalKeyDataChanged(byte[] data) {}
        @Override public void onDoorAllStateChanged(int a, int b, int c, int d, int e, int f, int g, int h, int i, int j) {}
        @Override public void onDoorLockStateChanged(int a) {}
        @Override public void onDriveModeChanged(int a) {}
        @Override public void onDrvSeatHeaterStatusChanged(int a) {}
        @Override public void onEVSEChanged(long a, long b) {}
        @Override public void onEcsStatusChanged(int a, int b, int c, int d, int e, int f, int g, int h, int i) {}
        @Override public void onEngineIdleNotiAlarm(byte a) {}
        @Override public void onEngineStatusChanged(boolean a) {}
        @Override public void onFuelWarnStatusChanged(boolean a) {}
        @Override public void onGearStatusInfoChanged(int a, int b) {}
        @Override public void onIgnitionChanged(int a) {
            TelemetryCollector.onBcmIgnitionChangedCallback(a);
        }
        @Override public void onLampStatusChanged(int a, int b, int c, int d) {}
        @Override public void onModemInfoChanged(byte[] data) {}
        @Override public void onPanic(int a) {}
        @Override public void onParkBrakeStatusChanged(int a) {}
        @Override public void onParkCrashChanged(int a) {}
        @Override public void onRLSeatHeaterStatusChanged(int a) {}
        @Override public void onRRSeatHeaterStatusChanged(int a) {}
        @Override public void onRctaAlarmChanged(int a, int b) {}
        @Override public void onRearStatusInfoChanged(int a) {}
        @Override public void onRemoteEngingStatusChanged(int a) {}
        @Override
        public void onRemoteTeleHeatingStatusChanged(int stWheelHeatState, int defoggerState) {
            TelemetryCollector.onBcmTeleHeatingCallback(stWheelHeatState, defoggerState);
        }
        @Override public void onSBCMChanged(int a, int b, int c) {}
        @Override public void onSeatHeaterStatusChanged(int a, int b, int c, int d) {}
        @Override public void onSeatVentilationStatusChanged(int a, int b, int c, int d) {}
        @Override public void onSeatbeltStatusChanged(int a, int b) {}
        @Override public void onSideMirrorStatusChanged(int a) {}
        @Override public void onSmartKeyBatteryWarning(int a) {}
        @Override public void onSocAlaramStatusChanged(int a) {}
        @Override public void onSpeedUnitChanged(int a) {}
        @Override public void onStolen(int a) {}
        @Override public void onSysUpdateStatusAlarm(int a) {}
        @Override public void onVehicleStatusDeliveryMode(int a) {}
        @Override public void onWindowActStateChanged(int a, int b, int c, int d) {}
        @Override public void onWindowDetailOpenStateChanged(int a, int b, int c, int d) {}
        @Override public void onWindowOpenStateChanged(int a, int b, int c, int d) {}
        @Override public void onbreakOilStatus(int a) {}
        @Override public void onsunroofOpen(int a) {}
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

    public static final class MediaIntentReceiverBridge extends android.content.IIntentReceiver.Stub {
        @Override
        public void performReceive(
                android.content.Intent intent,
                int resultCode,
                String data,
                android.os.Bundle extras,
                boolean ordered,
                boolean sticky,
                int sendingUser
        ) {
            TelemetryCollector.onMediaWidgetBroadcast(intent, extras);
            if (ordered && !sticky) {
                TelemetryCollector.finishOrderedBroadcastReceiver(this, resultCode, data, extras);
            }
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
        private static volatile Method getEconChargeTimeMethod = null;
        private static volatile Method getCo2RdcMethod = null;
        private static volatile int canDbGeneration = -1;
        private static volatile long lastEvHistoryDbPollMs = 0L;

        private static volatile Object navClusterObj = null;
        private static volatile Method navClusterReqBattMethod = null;
        private static volatile Field navClusterBattRespField = null;

        private static volatile Object tmHscanService = null;
        private static volatile Method tmDriveLinePwrMethod = null;
        private static volatile Method tmGetAccelPdlMethod = null;
        private static volatile Method tmGetGearPosMethod = null;
        private static volatile Method tmGetTempCMethod = null;
        private static volatile Method tmGetSasAngleMethod = null;

        private static volatile Object datcService = null;
        private static volatile Method datcGetClimateInfoMethod = null;
        private static volatile Method datcGetVrStatusMethod = null;
        private static volatile Method datcIsIgn3Method = null;
        private static volatile Boolean lastDatcIg3State = null;
        private static volatile boolean datcListenerRegistered = false;
        private static volatile long lastDatcCallbackMs = 0L;
        private static volatile Object lastRawDatcStatusObj = null;
        private static volatile String lastRawDatcTempCallback = "none";
        private static volatile String lastRawDatcIntakeInfo = "none";

        private static volatile Object bcmService = null;
        private static volatile Method bcmGetSpeedKmhMethod = null;
        private static volatile boolean bcmListenerRegistered = false;
        private static volatile String lastRawBcmHeatingCallback = "none";
        private static volatile int lastBcmIgnCallbackState = -1;
        private static volatile int lastBcmAccCallbackState = -1;

        private static volatile Object powerService = null;
        private static volatile Method powerGetStatusMethod = null;
        private static volatile Method powerIsPrepOffMethod = null;
        private static volatile Method powerIsDispOffMethod = null;
        private static volatile Object blackScreenService = null;
        private static volatile Method blackIsKeepScreenOffMethod = null;
        private static volatile Object modeService = null;
        private static volatile Method modeGetCurrentMethod = null;
        private static volatile int lastPowerStatus = 0;
        private static volatile boolean vehicleScreenOff = false;
        private static volatile String lastRawPowerStateInfo = "none";
        private static volatile String lastRawMediaLogInfo = "none";

        private static volatile boolean rcDisplayRegistered = false;
        private static volatile Object rcDisplayBridgeObj = null;
        private static volatile String lastRawRcClientInfo = "none";
        private static volatile String lastRawRcStateInfo = "none";
        private static volatile String lastRawRcBundleDump = "none";
        private static volatile boolean mediaBroadcastRegistered = false;
        private static volatile Object mediaReceiverObj = null;
        private static volatile long lastMediaWidgetReqMs = 0L;
        private static volatile long lastTrackChangeMs = 0L;
        private static volatile long lastNativeAlbumArtRxMs = 0L;
        private static volatile long lastDiskArtProbeMs = 0L;
        private static volatile int trackWidgetReqCount = 0;
        private static volatile byte[] latestAlbumArtBytes = null;
        private static volatile String lastRawMediaBroadcastDump = "none";
        private static volatile Object clusterService = null;
        private static volatile Method clusterGetLastPlaytimeMethod = null;
        private static volatile int lastClusterMediaMode = -1;
        private static volatile long lastMediaPosUpdateMs = 0L;

        // ---- v1.4.2 media source / playback-time bookkeeping ----
        private static volatile String modeSourceName = "";          // ModeManager-derived current source
        private static volatile long modeSourceMs = 0L;
        private static String lastExternalPropSig = null;             // persist.sys.hkg.media.* (null = no baseline yet)
        private static long lastMediaStateFileMtime = -1L;           // /data/local/tmp/hkg_media_state.txt
        private static final Map<String, Boolean> broadcastUnitMsBySource = new HashMap<String, Boolean>();
        private static volatile long lastPosAdvanceMs = 0L;          // last time an external position moved forward
        private static volatile long lastExternalPosMs = 0L;         // last external position sample time
        private static volatile int lastExternalPosSec = -1;
        private static volatile boolean periodicPosReporter = false; // current source reports position >= every 2.5s
        private static volatile int periodicAdvanceStreak = 0;       // consecutive advancing samples <= 2.5s apart
        private static volatile boolean playbackStalled = false;
        private static volatile long clusterRawPlaytime = -1L;
        private static volatile Boolean clusterUnitMs = null;
        private static volatile int clusterLastSec = -1;
        private static volatile int clusterFirstSecThisTrack = -1;
        private static volatile boolean clusterAdvancedThisTrack = false;
        private static volatile String clusterPlaytimeInfo = "none";
        static final long STALL_MS = 3500L;
        static final long EXTRAPOLATE_NO_DURATION_MAX_MS = 20L * 60L * 1000L;

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
        private static volatile long lastFatcUdsMs = 0L;
        private static volatile long lastUdsReqSentMs = 0L;
        private static volatile int udsReqToggle = 0;

        private static volatile boolean backgroundStarted = false;
        private static volatile long lastCarrotRxMs = 0L;
        private static int pollTick = 0;
        private static long lastStateJsonWriteMs = 0L;

        private static final String[] MEDIA_WIDGET_ACTIONS = new String[]{
                "com.hkmc.audio.btaudio.widgetupdate",
                "com.hkmc.dipoaudio.widgetupdate",
                "com.hkmc.gpmaudio.widgetupdate",
                "com.hkmc.audio.usb.widgetupdate",
                "com.hkmc.audio.mymusic.widgetupdate",
                "com.hkmc.radio.widgetupdate",
                "com.hkmc.radio.dab.widgetupdate",
                "com.hkmc.av.app.radioservice.statuschanged",
                "com.hkmc.video.dmb.widgetupdate",
                "com.hkmc.video.usb.widgetupdate",
                "com.hkmc.nature_sound.widgetupdate",
                "com.hkmc.genie_music.widgetupdate",
                "com.hkmc.melon_music.widgetupdate",
                "com.hkmc.audio.timeupdate",
                "com.hkmc.audiocd.timeupdate",
                "com.hkmc.pandora.widgetupdate",
                "com.hkmc.pandora.timeupdate",
                "com.hkmc.qqmusic.widgetupdate",
                "com.hkmc.kaolafm.widgetupdate",
                "com.hkmc.audio.ipod.widgetupdate",
                "com.hkmc.audio.mp3cd.widgetupdate",
                "com.hkmc.audio.audiocd.widgetupdate",
                "com.hkmc.audio.carlife.widgetupdate",
                "com.hkmc.audio.mirrorlink.widgetupdate",
                "com.hkmc.audio.mconnect.widgetupdate",
                "com.hkmc.default.widgetupdate",
                "appwidgetupdate",
                "com.android.music.metachanged",
                "com.android.music.playstatechanged",
                "com.broadcom.bt.avrcp.profile.action.PLAY_INFO",
                "com.broadcom.bt.avrcp.profile.action.CURRENT_PLAY_POSITION",
                "com.broadcom.bt.avrcp.profile.action.ACTION_CURRENT_PLAY_INFO_CBCOUNT_ERROR",
                "com.hkmc.intent.action.CLIMATE_CHANGE",
                "com.hkmc.intent.action.ACTION_ACC_STATUS_CHANGED"
        };

        public static boolean isVehicleScreenOff() {
            return vehicleScreenOff;
        }

        public static void onDatcIg3StatusCallback(boolean ig3) {
            lastDatcIg3State = Boolean.valueOf(ig3);
            if (ig3) {
                lastBcmIgnCallbackState = 1;
                lastBcmAccCallbackState = 1;
            }
            refreshVehicleScreenOffFast();
            if (vehicleScreenOff) {
                TurzxUsbDriver.blankDisplayAndClose();
            }
        }

        public static void onBcmIgnitionChangedCallback(int ign) {
            if (ign == 0 || ign == 1) {
                lastBcmIgnCallbackState = ign;
                if (ign == 0) {
                    lastDatcIg3State = Boolean.FALSE;
                }
                refreshVehicleScreenOffFast();
                if (vehicleScreenOff) {
                    TurzxUsbDriver.blankDisplayAndClose();
                }
            }
        }

        public static void onBcmAccChangedCallback(int acc) {
            if (acc == 0 || acc == 1) {
                lastBcmAccCallbackState = acc;
                refreshVehicleScreenOffFast();
                if (vehicleScreenOff) {
                    TurzxUsbDriver.blankDisplayAndClose();
                }
            }
        }

        private static void refreshVehicleScreenOffFast() {
            boolean charging;
            synchronized (liveSnapshot) {
                charging = liveSnapshot.charging;
            }
            vehicleScreenOff = evaluateVehicleScreenOffState(
                    lastPowerStatus, false, false, false,
                    lastDatcIg3State, lastBcmIgnCallbackState, lastBcmAccCallbackState, charging);
        }

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
                int intakeInfo = readDatcClimateInfoInt(datcService, DATC_CLIMATE_INFO_INTAKE_STATUS, -1);
                if (intakeInfo == 0 || intakeInfo == 1) {
                    liveSnapshot.hvacRecirc = (intakeInfo == 1);
                    lastRawDatcIntakeInfo = "getClimateInfo(5)=" + intakeInfo;
                }
            }
        }

        public static void onBcmTeleHeatingCallback(int stWheelHeatState, int defoggerState) {
            lastRawBcmHeatingCallback = "stWheelHeat=" + stWheelHeatState + ", defogger(C_DefoggerRly)=" + defoggerState;
            synchronized (liveSnapshot) {
                if (stWheelHeatState == 0 || stWheelHeatState == 1) {
                    liveSnapshot.hvacSteerHeat = (stWheelHeatState == 1);
                }
                if (defoggerState == 0 || defoggerState == 1) {
                    liveSnapshot.hvacRearDefog = (defoggerState == 1);
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
                } else if (liveSnapshot.hvacDrTempC > 0.0) {
                    // Single-Zone FATC (psStateC == 1 STATE_NOT_DISPLAY) or Sync active
                    liveSnapshot.hvacPsTempC = liveSnapshot.hvacDrTempC;
                }
                if (ambStateC != 1 && ambStateC != 255 && !Float.isNaN(ambC)
                        && ambC >= -45.0f && ambC <= 65.0f && Math.abs(ambC - 85.0f) > 0.5f) {
                    liveSnapshot.outdoorTempC = Math.round(ambC * 2.0) / 2.0;
                }
                lastDatcCallbackMs = System.currentTimeMillis();
            }
        }

        public static void onMediaWidgetBroadcast(Object intentObj) {
            onMediaWidgetBroadcast(intentObj, null);
        }

        /** Single entry point for every external playback-position sample (broadcast, RCD, logcat, ClusterService). */
        static void notePositionUpdate(TelemetrySnapshot target, int sec) {
            if (target == null || sec < 0 || sec >= 36000) return;
            target.mediaPositionSec = sec;
            if (target != liveSnapshot) return;
            long now = System.currentTimeMillis();
            lastMediaPosUpdateMs = now;
            boolean advancing = lastExternalPosSec >= 0 && sec > lastExternalPosSec && (sec - lastExternalPosSec) <= 5;
            if (advancing && lastExternalPosMs > 0L && (now - lastExternalPosMs) <= 2500L) {
                // Needs 2 consecutive advancing samples: a metadata+time burst at track start is not "periodic"
                if (++periodicAdvanceStreak >= 2) periodicPosReporter = true;
            } else if (lastExternalPosMs > 0L && (now - lastExternalPosMs) > 2500L) {
                periodicAdvanceStreak = 0;
            }
            if (advancing) {
                lastPosAdvanceMs = now;
                if (playbackStalled) {
                    playbackStalled = false;
                    target.mediaPlaying = true;
                }
            } else if (lastExternalPosSec < 0 || sec != lastExternalPosSec) {
                lastPosAdvanceMs = now; // first sample or seek: restart the stall window
            }
            lastExternalPosSec = sec;
            lastExternalPosMs = now;
        }

        /** New track: position 0, duration unknown, restart stall / ClusterService tracking. */
        static void resetPlaybackTimeForNewTrack(TelemetrySnapshot target) {
            if (target == null) return;
            target.mediaPositionSec = 0;
            target.mediaDurationSec = 0;
            if (target != liveSnapshot) return;
            long now = System.currentTimeMillis();
            lastMediaPosUpdateMs = now;
            lastPosAdvanceMs = now;
            lastExternalPosMs = 0L;
            lastExternalPosSec = -1;
            periodicPosReporter = false;
            periodicAdvanceStreak = 0;
            playbackStalled = false;
            clusterFirstSecThisTrack = -1;
            clusterLastSec = -1;
            clusterAdvancedThisTrack = false;
        }

        /** ModeManager says a non-Bluetooth source is active (fresh reading). */
        static boolean isNonBtModeSourceActive() {
            String m = modeSourceName;
            return m != null && !m.isEmpty() && !"Bluetooth Audio".equals(m)
                    && (System.currentTimeMillis() - modeSourceMs) < 5000L;
        }

        /**
         * getLastPlaytime() unit is not documented: values > 36000 can only be ms; otherwise pick the reading
         * (seconds vs ms) closest to the current position estimate, unless the unit was already locked.
         */
        static int chooseClusterPlaytimeSec(long raw, int estimateSec, Boolean unitMs) {
            if (raw < 0L) return -1;
            if (Boolean.TRUE.equals(unitMs) || raw > 36000L) return (int) (raw / 1000L);
            if (Boolean.FALSE.equals(unitMs)) return (int) raw;
            int asSec = (int) raw;
            int asMsSec = (int) (raw / 1000L);
            int est = Math.max(0, estimateSec);
            return (Math.abs(asSec - est) <= Math.abs(asMsSec - est)) ? asSec : asMsSec;
        }

        /** Broadcast time unit: a duration > 36000 can only be ms; learned per source for time-only updates. Default ms. */
        static boolean resolveBroadcastUnitMs(String sourceKey, long totRaw) {
            synchronized (broadcastUnitMsBySource) {
                if (totRaw > 0L) {
                    boolean ms = totRaw > 36000L;
                    broadcastUnitMsBySource.put(sourceKey, Boolean.valueOf(ms));
                    return ms;
                }
                Boolean learned = broadcastUnitMsBySource.get(sourceKey);
                return learned == null || learned.booleanValue();
            }
        }

        /** Disk artwork must have been written for the current track, not left over from the previous one. */
        static boolean isDiskArtFreshForTrack(long mtimeMs, long trackChangeMs, long nowMs) {
            if (trackChangeMs > 0L) return mtimeMs >= trackChangeMs - 3000L;
            return (nowMs - mtimeMs) < 600000L;
        }

        public static void onMediaWidgetBroadcast(Object intentObj, Object explicitExtrasObj) {
            if (intentObj == null && explicitExtrasObj == null) return;
            try {
                String action = "";
                Object extrasObj = explicitExtrasObj;
                if (intentObj != null) {
                    Class<?> ic = intentObj.getClass();
                    try {
                        Object a = ic.getMethod("getAction").invoke(intentObj);
                        if (a != null) action = String.valueOf(a);
                    } catch (Throwable ignored) {
                    }
                    if (extrasObj == null) {
                        try {
                            extrasObj = ic.getMethod("getExtras").invoke(intentObj);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                synchronized (liveSnapshot) {
                    applyMediaWidgetBroadcastToSnapshot(action, extrasObj, liveSnapshot);
                }
            } catch (Throwable ignored) {
            }
        }

        public static void finishOrderedBroadcastReceiver(
                android.os.IBinder who,
                int resultCode,
                String resultData,
                Object resultExtras
        ) {
            try {
                Class<?> amnCls = Class.forName("android.app.ActivityManagerNative");
                Object am = amnCls.getMethod("getDefault").invoke(null);
                if (am == null) return;
                Class<?> iBinderCls = Class.forName("android.os.IBinder");
                Class<?> bundleCls = Class.forName("android.os.Bundle");
                try {
                    Method finM = am.getClass().getMethod(
                            "finishReceiver", iBinderCls, int.class, String.class, bundleCls, boolean.class, int.class);
                    finM.invoke(am, who, Integer.valueOf(resultCode), resultData, resultExtras, Boolean.FALSE, Integer.valueOf(0));
                } catch (NoSuchMethodException nsme) {
                    Method finM = am.getClass().getMethod(
                            "finishReceiver", iBinderCls, int.class, String.class, bundleCls, boolean.class);
                    finM.invoke(am, who, Integer.valueOf(resultCode), resultData, resultExtras, Boolean.FALSE);
                }
            } catch (Throwable ignored) {
            }
        }

        public static boolean applyMediaWidgetBroadcastToSnapshot(String action, Object bundleObj, TelemetrySnapshot target) {
            if (target == null) return false;
            try {
                if ("com.hkmc.intent.action.ACTION_ACC_STATUS_CHANGED".equals(action)) {
                    if (bundleObj == null) return false;
                    Class<?> bc = bundleObj.getClass();
                    int accStatus = -1;
                    String[] accKeys = new String[]{"android.ibox.mode.EXTRA_ACC_STATUS", "EXTRA_ACC_STATUS"};
                    for (int k = 0; k < accKeys.length && accStatus < 0; k++) {
                        try {
                            Method getInt = bc.getMethod("getInt", String.class, int.class);
                            accStatus = ((Integer) getInt.invoke(bundleObj, accKeys[k], Integer.valueOf(-1))).intValue();
                        } catch (Throwable t1) {
                            try {
                                Method getObj = bc.getMethod("get", String.class);
                                Object oAcc = getObj.invoke(bundleObj, accKeys[k]);
                                if (oAcc instanceof Number) accStatus = ((Number) oAcc).intValue();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    if (accStatus >= 0) {
                        lastPowerStatus = accStatus;
                        if (accStatus == 0 || accStatus == 5) {
                            lastBcmAccCallbackState = 1;
                            lastBcmIgnCallbackState = 1;
                        }
                        refreshVehicleScreenOffFast();
                        if (vehicleScreenOff) {
                            TurzxUsbDriver.blankDisplayAndClose();
                        }
                        return true;
                    }
                    return false;
                }

                if ("com.hkmc.intent.action.CLIMATE_CHANGE".equals(action)) {
                    if (bundleObj == null) return false;
                    Class<?> bc = bundleObj.getClass();
                    int osdFlag = -1;
                    int val = -1;
                    try {
                        Method getInt = bc.getMethod("getInt", String.class, int.class);
                        osdFlag = ((Integer) getInt.invoke(bundleObj, "OSD_FLAG", Integer.valueOf(-1))).intValue();
                        val = ((Integer) getInt.invoke(bundleObj, "VALUE", Integer.valueOf(-1))).intValue();
                    } catch (Throwable t1) {
                        try {
                            Method getObj = bc.getMethod("get", String.class);
                            Object oFlag = getObj.invoke(bundleObj, "OSD_FLAG");
                            Object oVal = getObj.invoke(bundleObj, "VALUE");
                            if (oFlag instanceof Number) osdFlag = ((Number) oFlag).intValue();
                            if (oVal instanceof Number) val = ((Number) oVal).intValue();
                        } catch (Throwable ignored) {
                        }
                    }
                    if (osdFlag == DATC_OSD_FLAG_INTAKE_STATUS && (val == 0 || val == 1)) {
                        target.hvacRecirc = (val == 1);
                        target.hasClimateData = true;
                        lastRawDatcIntakeInfo = "CLIMATE_CHANGE(OSD_FLAG=12, VALUE=" + val + ")";
                        return true;
                    }
                    return false;
                }

                String src = mapWidgetBroadcastActionToSource(action);
                if (!src.isEmpty()) {
                    target.mediaSource = src;
                }
                if (bundleObj == null) return !src.isEmpty();

                Class<?> bc = bundleObj.getClass();
                Method getStr = null;
                Method getLong = null;
                Method getInt = null;
                Method getBool = null;
                Method getByteArr = null;
                Method getObj = null;
                try { getStr = bc.getMethod("getString", String.class); } catch (Throwable ignored) {}
                try { getLong = bc.getMethod("getLong", String.class, long.class); } catch (Throwable ignored) {}
                try { getInt = bc.getMethod("getInt", String.class, int.class); } catch (Throwable ignored) {}
                try { getBool = bc.getMethod("getBoolean", String.class, boolean.class); } catch (Throwable ignored) {}
                try { getByteArr = bc.getMethod("getByteArray", String.class); } catch (Throwable ignored) {}
                try { getObj = bc.getMethod("get", String.class); } catch (Throwable ignored) {}

                try {
                    Object keySetObj = bc.getMethod("keySet").invoke(bundleObj);
                    if (keySetObj instanceof java.util.Set) {
                        StringBuilder bsb = new StringBuilder(256);
                        bsb.append(action).append(" {");
                        boolean first = true;
                        for (Object kObj : (java.util.Set<?>) keySetObj) {
                            String k = String.valueOf(kObj);
                            if (!first) bsb.append(", ");
                            first = false;
                            if ("artwork_raw_image".equals(k)) {
                                bsb.append(k).append("=[byte[]]");
                            } else {
                                Object val = (getObj != null) ? getObj.invoke(bundleObj, k) : (getStr != null ? getStr.invoke(bundleObj, k) : null);
                                bsb.append(k).append("=").append(val);
                            }
                        }
                        bsb.append("}");
                        lastRawMediaBroadcastDump = bsb.toString();
                    }
                } catch (Throwable ignored) {
                }

                boolean isTimeOnly = action != null
                        && (action.endsWith(".timeupdate") || action.endsWith(".CURRENT_PLAY_POSITION"));
                if (!isTimeOnly) {
                    String title = readBundleStringAny(bundleObj, getStr, getObj,
                            "filename", "CURRENT_TITLE", "title", "track", "channel_name", "station_name",
                            "service_name", "content_name", "program_name", "frequency", "freq");
                    String artist = readBundleStringAny(bundleObj, getStr, getObj,
                            "artist_name", "CURRENT_ARTIST", "artist", "bluetooth_device_name", "pty_name", "band", "folder_name");
                    String album = readBundleStringAny(bundleObj, getStr, getObj,
                            "album_name", "CURRENT_ALBUM", "album", "bluetooth_device_name");

                    onMediaTrackUpdated(target, title, artist, album);
                    if (!title.isEmpty() && !"Not Provided".equalsIgnoreCase(title)) {
                        target.mediaPlaying = true;
                    }

                    if (getInt != null) {
                        try {
                            int ps = ((Integer) getInt.invoke(bundleObj, "playstate", Integer.valueOf(-1))).intValue();
                            if (ps == -1) {
                                ps = ((Integer) getInt.invoke(bundleObj, "play_state", Integer.valueOf(-1))).intValue();
                            }
                            if (ps >= 0) {
                                // Genuine BTAudio / AvWidgetMainView: 1 = playing, 0/2 = paused/stopped
                                target.mediaPlaying = (ps == 1 || ps == 3);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    if (getBool != null && getObj != null) {
                        try {
                            if (getObj.invoke(bundleObj, "playing") instanceof Boolean) {
                                target.mediaPlaying = ((Boolean) getBool.invoke(bundleObj, "playing", Boolean.FALSE)).booleanValue();
                            } else if (getObj.invoke(bundleObj, "PLAYING") instanceof Boolean) {
                                target.mediaPlaying = ((Boolean) getBool.invoke(bundleObj, "PLAYING", Boolean.FALSE)).booleanValue();
                            }
                        } catch (Throwable ignored) {
                        }
                    }

                    boolean gotNativeArt = false;
                    if (getByteArr != null && !isBtaudioPlaceholderBundle(action, bundleObj, getInt, getObj)) {
                        try {
                            byte[] rawArt = (byte[]) getByteArr.invoke(bundleObj, "artwork_raw_image");
                            if (rawArt != null && rawArt.length > 64) {
                                Object bmp = decodeAlbumArtBytes(rawArt);
                                if (bmp != null) {
                                    target.albumArtBitmap = bmp;
                                    target.albumArtSource = "broadcast_bytes";
                                    gotNativeArt = true;
                                    if (target == liveSnapshot) {
                                        lastNativeAlbumArtRxMs = System.currentTimeMillis();
                                        latestAlbumArtBytes = rawArt;
                                    }
                                    AlbumArtResolver.putCachedArt(target.mediaTitle, target.mediaArtist, bmp);
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }

                    if (!gotNativeArt) {
                        String artUri = readBundleStringAny(bundleObj, getStr, getObj,
                                "artwork_uri", "album_art_uri", "artwork_path", "albumart_uri", "uri");
                        if (!artUri.isEmpty() && !"null".equalsIgnoreCase(artUri)) {
                            Object bmp = loadBitmapFromUriOrPath(artUri);
                            if (bmp != null) {
                                target.albumArtBitmap = bmp;
                                target.albumArtSource = "broadcast_uri";
                                gotNativeArt = true;
                                if (target == liveSnapshot) {
                                    lastNativeAlbumArtRxMs = System.currentTimeMillis();
                                }
                                AlbumArtResolver.putCachedArt(target.mediaTitle, target.mediaArtist, bmp);
                            }
                        }
                    }

                    if (!gotNativeArt && getObj != null) {
                        String[] bmpKeys = new String[]{"albumArt", "artwork", "bitmap", "album_art"};
                        for (int bk = 0; bk < bmpKeys.length && !gotNativeArt; bk++) {
                            try {
                                Object v = getObj.invoke(bundleObj, bmpKeys[bk]);
                                if (v instanceof byte[]) {
                                    Object bmp = decodeAlbumArtBytes((byte[]) v);
                                    if (bmp != null) {
                                        target.albumArtBitmap = bmp;
                                        target.albumArtSource = "broadcast_bytes";
                                        gotNativeArt = true;
                                        if (target == liveSnapshot) {
                                            lastNativeAlbumArtRxMs = System.currentTimeMillis();
                                            latestAlbumArtBytes = (byte[]) v;
                                        }
                                        AlbumArtResolver.putCachedArt(target.mediaTitle, target.mediaArtist, bmp);
                                    }
                                } else if (v != null && v.getClass().getName().equals("android.graphics.Bitmap")) {
                                    target.albumArtBitmap = v;
                                    target.albumArtSource = "broadcast_bitmap";
                                    gotNativeArt = true;
                                    if (target == liveSnapshot) {
                                        lastNativeAlbumArtRxMs = System.currentTimeMillis();
                                    }
                                    AlbumArtResolver.putCachedArt(target.mediaTitle, target.mediaArtist, v);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }

                    if (target == liveSnapshot && target.albumArtBitmap == null) {
                        AlbumArtResolver.requestOnlineLookupIfNeeded(target);
                    }
                }

                long totRaw = readBundleLongAny(bundleObj, getLong, getObj,
                        "audio_total_time", "LENGTH", "duration", "total_time");
                // Position 0 is a real value, but only trusted on time-only updates (metadata bundles often carry a
                // stale 0); a new track already resets the position to 0 in onMediaTrackUpdated().
                long curRaw = isTimeOnly
                        ? readBundleLongAnyAllowZero(bundleObj, getLong, getObj,
                                "audio_current_time", "POSITION", "position", "current_time", "play_time")
                        : readBundleLongAny(bundleObj, getLong, getObj,
                                "audio_current_time", "POSITION", "position", "current_time", "play_time");
                String unitKey = !src.isEmpty() ? src : (target.mediaSource != null ? target.mediaSource : "");
                boolean unitMs = resolveBroadcastUnitMs(unitKey, totRaw);
                if (totRaw > 0L && totRaw < 36000000L) {
                    int durSec = (int) (unitMs ? (totRaw / 1000L) : totRaw);
                    if (durSec > 0 && durSec < 36000) target.mediaDurationSec = durSec;
                }
                if (curRaw >= 0L && curRaw < 36000000L) {
                    notePositionUpdate(target, (int) (unitMs ? (curRaw / 1000L) : curRaw));
                }
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        public static void requestMediaWidgetUpdateAsync() {
            long now = System.currentTimeMillis();
            if (now - lastMediaWidgetReqMs < 800L) return;
            lastMediaWidgetReqMs = now;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    requestMediaWidgetUpdate();
                }
            }, "Turzx-WidgetUpdateReq");
            t.setDaemon(true);
            t.start();
        }

        private static void requestMediaWidgetUpdate() {
            try {
                Class<?> intentCls = Class.forName("android.content.Intent");
                Object reqIntent = intentCls.getConstructor(String.class).newInstance("com.hkmc.request.widgetupdate");
                try {
                    intentCls.getMethod("putExtra", String.class, boolean.class)
                            .invoke(reqIntent, "update", Boolean.TRUE);
                } catch (Throwable ignored) {
                }
                Object sysCtx = TurzxOverlayController.getSysContext();
                if (sysCtx != null) {
                    try {
                        sysCtx.getClass().getMethod("sendBroadcast", intentCls).invoke(sysCtx, reqIntent);
                        lastMediaWidgetReqMs = System.currentTimeMillis();
                    } catch (Throwable ignored) {
                    }
                }
                Class<?> amnCls = Class.forName("android.app.ActivityManagerNative");
                Object am = amnCls.getMethod("getDefault").invoke(null);
                if (am != null) {
                    Class<?> iAppThreadCls = Class.forName("android.app.IApplicationThread");
                    Class<?> iirCls = Class.forName("android.content.IIntentReceiver");
                    Class<?> bundleCls = Class.forName("android.os.Bundle");
                    Method bcM = am.getClass().getMethod(
                            "broadcastIntent",
                            iAppThreadCls,
                            intentCls,
                            String.class,
                            iirCls,
                            int.class,
                            String.class,
                            bundleCls,
                            String.class,
                            int.class,
                            boolean.class,
                            boolean.class,
                            int.class
                    );
                    bcM.invoke(
                            am,
                            null,
                            reqIntent,
                            null,
                            null,
                            Integer.valueOf(0),
                            null,
                            null,
                            null,
                            Integer.valueOf(-1),
                            Boolean.FALSE,
                            Boolean.FALSE,
                            Integer.valueOf(0)
                    );
                    lastMediaWidgetReqMs = System.currentTimeMillis();
                }
            } catch (Throwable ignored) {
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
                if (state == 3) playbackStalled = false;
                if (currentPosMs >= 0L && currentPosMs < 36000000L) {
                    notePositionUpdate(liveSnapshot, (int) (currentPosMs / 1000L)); // RemoteControlClient API: ms
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

        /** Like readBundleLongAny but 0 is a valid value (playback position at track start); -1 = absent. */
        private static long readBundleLongAnyAllowZero(Object bundleObj, Method getLong, Method getObj, String... keys) {
            for (int i = 0; i < keys.length; i++) {
                String k = keys[i];
                try {
                    if (getObj != null) {
                        Object o = getObj.invoke(bundleObj, k);
                        if (o instanceof Number) {
                            long v = ((Number) o).longValue();
                            if (v >= 0L) return v;
                        } else if (o != null) {
                            long v = Long.parseLong(String.valueOf(o).trim());
                            if (v >= 0L) return v;
                        }
                    } else if (getLong != null) {
                        long v = ((Long) getLong.invoke(bundleObj, k, Long.valueOf(-1L))).longValue();
                        if (v >= 0L) return v;
                    }
                } catch (Throwable ignored) {
                }
            }
            return -1L;
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
                    onMediaTrackUpdated(liveSnapshot, title, artist, album);
                    if (!title.isEmpty()) {
                        liveSnapshot.mediaPlaying = true;
                    }
                    if (durMs > 0L && durMs < 36000000L) {
                        liveSnapshot.mediaDurationSec = (int) (durMs / 1000L); // METADATA_KEY_DURATION: ms
                    }
                    if (liveSnapshot.albumArtBitmap == null) {
                        AlbumArtResolver.requestOnlineLookupIfNeeded(liveSnapshot);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        public static void onRemoteControlArtwork(Object bitmapObj) {
            synchronized (liveSnapshot) {
                if (bitmapObj != null) {
                    liveSnapshot.albumArtBitmap = bitmapObj;
                    liveSnapshot.albumArtSource = "rc_display";
                    lastNativeAlbumArtRxMs = System.currentTimeMillis();
                    AlbumArtResolver.putCachedArt(liveSnapshot.mediaTitle, liveSnapshot.mediaArtist, bitmapObj);
                }
            }
        }

        public static void onHscanPacket(byte b1, byte b2, byte b3, byte[] data) {
            try {
                DebugManager.recordHscanFrame(b1, b2, b3, data);
                if ((b1 & 0xFF) == 0xC7 && data != null) {
                    long now = System.currentTimeMillis();
                    boolean isBmsAddr = ((b3 & 0x7F) == 0x2D) || (b3 == 0);
                    boolean isFatcAddr = ((b3 & 0x7F) == 0x2B);
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
                    } else if (isFatcAddr && data.length >= 12) {
                        synchronized (liveSnapshot) {
                            if (parseFatcUdsPayload(data, liveSnapshot)) {
                                lastFatcUdsMs = now;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        public static TelemetrySnapshot pollSnapshot(Object sysContext) {
            pollTick++;
            // Service (re)connection: absent services cost a ServiceManager IPC per call, so at most 1Hz
            long ensureNowMs = System.currentTimeMillis();
            if (lastEnsureBinderMs == 0L || ensureNowMs - lastEnsureBinderMs >= 1000L || ensureNowMs < lastEnsureBinderMs) {
                lastEnsureBinderMs = ensureNowMs;
                ensureBinderConnections(sysContext);
            }
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
                pollPowerAndIgnLocked();
                pollTmHscanServiceLocked();
                pollDatcServiceLocked();
                pollGearLocked();
                pollMediaNowPlayingLocked();
                pollCarrotBridgeAndArpLocked();

                long nowMs = System.currentTimeMillis();
                String curTitle = liveSnapshot.mediaTitle != null ? liveSnapshot.mediaTitle : "";
                boolean waitingForMedia = curTitle.isEmpty() || "미디어 재생 대기 중".equals(curTitle);
                if (waitingForMedia) {
                    // Discovery heartbeat: ask media apps to re-announce what is playing
                    if (nowMs - lastMediaWidgetReqMs > 15000L) {
                        requestMediaWidgetUpdate();
                    }
                } else if (liveSnapshot.albumArtBitmap == null) {
                    // Artwork re-requests: at most 4 right after a track change (counter reset in onMediaTrackUpdated),
                    // never for sources that have no artwork (radio/DMB/...)
                    if (trackWidgetReqCount < 4
                            && isLookupEligibleTrack(liveSnapshot.mediaSource, curTitle)
                            && nowMs - lastMediaWidgetReqMs >= 2500L) {
                        trackWidgetReqCount++;
                        requestMediaWidgetUpdate();
                    }
                    if (nowMs - lastDiskArtProbeMs >= 1500L) {
                        lastDiskArtProbeMs = nowMs;
                        probeOnDiskAlbumArtLocked(liveSnapshot);
                    }
                    if (liveSnapshot.albumArtBitmap == null) {
                        AlbumArtResolver.requestOnlineLookupIfNeeded(liveSnapshot);
                    }
                }

                if (liveSnapshot.carrotConnected && (System.currentTimeMillis() - lastCarrotRxMs > 8000L)) {
                    liveSnapshot.carrotConnected = false;
                }

                TelemetrySnapshot snap = copySnapshotLocked();
                // Shared state JSON: 1Hz, tmp+rename without fsync (was every 3rd frame with fsync on the render thread)
                if (nowMs - lastStateJsonWriteMs >= 1000L) {
                    lastStateJsonWriteMs = nowMs;
                    try {
                        writeBytesNoSync(new File(TURZX_JSON_STATE_PATH), formatTelemetryJson(snap).getBytes(UTF8));
                    } catch (Throwable ignored) {
                    }
                }
                return snap;
            }
        }

        private static long lastEnsureBinderMs = 0L;

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
                                try {
                                    getEconChargeTimeMethod = sc.getMethod("getLastEconomicChargeTime");
                                } catch (Throwable ignored) {
                                }
                                try {
                                    getCo2RdcMethod = sc.getMethod("getCO2Rdc");
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

                if (!mediaBroadcastRegistered) {
                    try {
                        Class<?> filterCls = Class.forName("android.content.IntentFilter");
                        Object filter = filterCls.getConstructor().newInstance();
                        Method addAct = filterCls.getMethod("addAction", String.class);
                        for (int i = 0; i < MEDIA_WIDGET_ACTIONS.length; i++) {
                            addAct.invoke(filter, MEDIA_WIDGET_ACTIONS[i]);
                        }
                        Object receiver = new MediaIntentReceiverBridge();
                        mediaReceiverObj = receiver;
                        Class<?> amnCls = Class.forName("android.app.ActivityManagerNative");
                        Object am = amnCls.getMethod("getDefault").invoke(null);
                        Class<?> appThreadCls = Class.forName("android.app.IApplicationThread");
                        Class<?> iirCls = Class.forName("android.content.IIntentReceiver");
                        Object stickyIntent = null;
                        try {
                            Method regM = am.getClass().getMethod(
                                    "registerReceiver",
                                    appThreadCls, String.class, iirCls, filterCls, String.class, int.class);
                            stickyIntent = regM.invoke(am, null, "android", receiver, filter, null, Integer.valueOf(0));
                        } catch (NoSuchMethodException nsme) {
                            Method regM = am.getClass().getMethod(
                                    "registerReceiver",
                                    appThreadCls, String.class, iirCls, filterCls, String.class);
                            stickyIntent = regM.invoke(am, null, "android", receiver, filter, null);
                        }
                        mediaBroadcastRegistered = true;
                        if (stickyIntent != null) {
                            onMediaWidgetBroadcast(stickyIntent, null);
                        }
                        DebugManager.logEvent("MEDIA", "Registered MediaIntentReceiverBridge via IActivityManager (" + MEDIA_WIDGET_ACTIONS.length + " actions) & sent widgetupdate");
                        requestMediaWidgetUpdate();
                    } catch (Throwable t) {
                        DebugManager.logError("MEDIA", "registerReceiver", t);
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
                                try { tmGetSasAngleMethod = sc.getMethod("getSasAngleVal"); } catch (Throwable ignored) {}
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
                                try { datcIsIgn3Method = sc.getMethod("isIGN3Status"); } catch (Throwable ignored) {}
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

                if (bcmService == null) {
                    try {
                        Object b = getSvc.invoke(null, "com.hkmc.micom.bcm");
                        if (b != null) {
                            Class<?> stubCls = Class.forName("com.hkmc.bcm.IBCMService$Stub");
                            bcmService = stubCls.getMethod("asInterface", iBinderCls).invoke(null, b);
                            if (bcmService != null) {
                                Class<?> sc = bcmService.getClass();
                                try { bcmGetSpeedKmhMethod = sc.getMethod("getSpeed_KMHUnit"); } catch (Throwable ignored) {}
                                if (!bcmListenerRegistered) {
                                    try {
                                        Class<?> bcmIfc = Class.forName("com.hkmc.bcm.IBCMListener");
                                        Object listener = new BcmListenerBridge();
                                        Object binderObj = Class.forName("android.os.Binder").getConstructor().newInstance();
                                        try {
                                            sc.getMethod("registerBCMListener", bcmIfc, iBinderCls)
                                                    .invoke(bcmService, listener, binderObj);
                                        } catch (NoSuchMethodException nsme) {
                                            sc.getMethod("registerBCMListener", bcmIfc)
                                                    .invoke(bcmService, listener);
                                        }
                                        bcmListenerRegistered = true;
                                        DebugManager.logEvent("BCM", "Registered IBCMListener on com.hkmc.micom.bcm (C_DefoggerRly + getSpeed_KMHUnit)");
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
                                try {
                                    Method bmpSizeM = asc.getMethod("remoteControlDisplayUsesBitmapSize", rcdIfc, int.class, int.class);
                                    bmpSizeM.invoke(audioSvc, bridge, Integer.valueOf(280), Integer.valueOf(280));
                                } catch (Throwable ignored) {
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

                if (powerService == null) {
                    try {
                        Object b = getSvc.invoke(null, "com.hkmc.micom.power");
                        if (b != null) {
                            Class<?> stubCls = Class.forName("com.hkmc.power.IMicomPowerService$Stub");
                            powerService = stubCls.getMethod("asInterface", iBinderCls).invoke(null, b);
                            if (powerService != null) {
                                Class<?> sc = powerService.getClass();
                                try { powerGetStatusMethod = sc.getMethod("getPowerStatus"); } catch (Throwable ignored) {}
                                try { powerIsPrepOffMethod = sc.getMethod("isPreparingSystemOff"); } catch (Throwable ignored) {}
                                try { powerIsDispOffMethod = sc.getMethod("isDisplayOffMode"); } catch (Throwable ignored) {}
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }

                if (blackScreenService == null) {
                    try {
                        Object b = getSvc.invoke(null, "com.hkmc.micom.blackscreen");
                        if (b != null) {
                            Class<?> stubCls = Class.forName("com.hkmc.power.IBlackScreenService$Stub");
                            blackScreenService = stubCls.getMethod("asInterface", iBinderCls).invoke(null, b);
                            if (blackScreenService != null) {
                                try { blackIsKeepScreenOffMethod = blackScreenService.getClass().getMethod("isKeepScreenOff"); } catch (Throwable ignored) {}
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }

                if (modeService == null) {
                    try {
                        Object b = getSvc.invoke(null, "com.hkmc.micom.mode");
                        if (b != null) {
                            Class<?> stubCls = Class.forName("com.hkmc.mode.IModeManager$Stub");
                            modeService = stubCls.getMethod("asInterface", iBinderCls).invoke(null, b);
                            if (modeService != null) {
                                try { modeGetCurrentMethod = modeService.getClass().getMethod("getCurrentMode"); } catch (Throwable ignored) {}
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
            boolean bmsFresh = (now - lastUds0101Ms) < 3500L && (now - lastUds0105Ms) < 3500L;
            boolean fatcFresh = (now - lastFatcUdsMs) < 10000L;
            if (bmsFresh && fatcFresh) return;

            lastUdsReqSentMs = now;
            int step = udsReqToggle++;
            // Periodically request TP_DEVICE_FATC (0xAB) UDS 22 01 00 -> 62 01 00 for inCarTempC & outdoorTempC
            if (!fatcFresh && (bmsFresh || (step % 3) == 2)) {
                try {
                    sendM.invoke(
                            svc,
                            Byte.valueOf((byte) 0xC7),
                            Byte.valueOf((byte) 0x40),
                            Byte.valueOf((byte) 0x80),
                            Byte.valueOf((byte) 0x40),
                            Byte.valueOf((byte) 0xAB),
                            new byte[]{(byte) 0x22, (byte) 0x01, (byte) 0x00}
                    );
                } catch (Throwable ignored) {
                }
                return;
            }

            int pid = ((step & 1) == 0) ? 0x01 : 0x05;
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
                if (pollTick % 4 == 0) {
                    if (reqFreqChangedSigMethod != null) {
                        try { reqFreqChangedSigMethod.invoke(hevService); } catch (Throwable ignored) {}
                    }
                    if (reqHevStatusDataMethod != null) {
                        try { reqHevStatusDataMethod.invoke(hevService, Integer.valueOf(HEV_FLAG_MOTER_PWR_AVN)); } catch (Throwable ignored) {}
                    }
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

                // Genuine HKMC_TAppEV3 (f.g): Current DTE is packed in bits 8..23 of HEV_FLAG_ADDED_DIST_DATC_OFF (38),
                // and climate-off delta DTE is packed in bits 0..7!
                int raw38 = queryHevFlag(HEV_FLAG_ADDED_DIST_DATC_OFF);
                int dte = extractCurrentDteFromFlag38(raw38);
                if (raw38 != -1) {
                    liveSnapshot.dteAirconOffDeltaKm = parseAddedDistDatcOff(raw38);
                }
                if (!isValidDteValue(dte)) dte = queryHevFlag(HEV_FLAG_ELECTRIC_DTE);
                if (!isValidDteValue(dte)) dte = queryHevFlag(HEV_FLAG_TOTAL_DTE);
                if (isValidDteValue(dte)) {
                    liveSnapshot.dteKm = dte;
                }

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

                int fci = queryHevFlag(HEV_FLAG_AVG_FCL_DRIVE_INFO);
                if (fci <= 5 || fci >= 999) fci = queryHevFlag(HEV_FLAG_AVG_FCI);
                if (fci <= 5 || fci >= 999) fci = queryHevFlag(HEV_FLAG_TERM_AVG_FCI);
                if (fci > 5 && fci < 999) {
                    liveSnapshot.avgEconomyKmKwh = fci / 10.0;
                } else if (liveSnapshot.avgEconomyKmKwh <= 0.0 && (System.currentTimeMillis() - lastEvHistoryDbPollMs > 15000L)) {
                    lastEvHistoryDbPollMs = System.currentTimeMillis();
                    double histEcon = queryEvHistoryDbAvgEconomy();
                    if (histEcon > 0.5 && histEcon < 99.9) {
                        liveSnapshot.avgEconomyKmKwh = histEcon;
                    }
                }

                // Genuine HKMC_TAppEV3 (f.cu): Electronics, Motor (signed 10-bit), Climate, and Battery Care power
                int pwrOther = queryHevFlag(HEV_FLAG_PWR_OTHER_ECU);
                double ldcKw = parsePower10WToKw(pwrOther, 253);
                if (ldcKw >= 0.0) {
                    liveSnapshot.electronicsPowerKw = ldcKw;
                    liveSnapshot.hasSubPower = true;
                }

                if (canDbGeneration == 3) {
                    int mg1 = parseSigned10BitKw(queryHevFlag(HEV_FLAG_MCU_MG1PWRAVNKWVAL));
                    int mg2 = parseSigned10BitKw(queryHevFlag(HEV_FLAG_MCU_MG2PWRAVNKWVAL));
                    liveSnapshot.drivePowerKw = mg1 + mg2;
                    liveSnapshot.hasSubPower = true;
                } else {
                    int pwrMotRaw = queryHevFlag(HEV_FLAG_MOTER_PWR_AVN);
                    if (pwrMotRaw >= 0 && pwrMotRaw <= 1021) {
                        liveSnapshot.drivePowerKw = parseSigned10BitKw(pwrMotRaw);
                        liveSnapshot.hasSubPower = true;
                    }
                }

                int clmtW = queryHevFlag(HEV_FLAG_DATC_CLMTPWRCNSMPTN_W);
                double clmtTotalKw = (clmtW > 0) ? parsePower10WToKw(clmtW, 1200) : -1.0;
                if (clmtTotalKw > 0.0) {
                    liveSnapshot.climatePowerKw = clmtTotalKw;
                    liveSnapshot.hasSubPower = true;
                } else {
                    double compKw = parsePower10WToKw(queryHevFlag(HEV_FLAG_PWR_E_COMPRESSOR), 1021);
                    double ptcKw = parsePower10WToKw(queryHevFlag(HEV_FLAG_DATC_PTCPWRCNSMPTNVAL), 1021);
                    if (ptcKw < 0.0 && getEconChargeTimeMethod != null) {
                        try {
                            int[] econ = (int[]) getEconChargeTimeMethod.invoke(hevService);
                            if (econ != null && econ.length > 10) {
                                ptcKw = parsePower10WToKw(econ[10], 1021);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    if (compKw >= 0.0 || ptcKw >= 0.0) {
                        liveSnapshot.climatePowerKw = Math.max(0.0, compKw) + Math.max(0.0, ptcKw);
                        liveSnapshot.hasSubPower = true;
                        if (ptcKw > 0.15 && (System.currentTimeMillis() - lastDatcCallbackMs > 5000L)) {
                            liveSnapshot.hvacHeat = true;
                        }
                    }
                }

                double batHeatKw = parsePower10WToKw(queryHevFlag(HEV_FLAG_XEV_BATHEATPWRMONCON_W), 253);
                if (batHeatKw < 0.0 && getCo2RdcMethod != null) {
                    try {
                        int[] co2 = (int[]) getCo2RdcMethod.invoke(hevService);
                        if (co2 != null && co2.length > 2) {
                            batHeatKw = parsePower10WToKw(co2[2], 253);
                        }
                    } catch (Throwable ignored) {
                    }
                }
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

        private static void pollPowerAndIgnLocked() {
            boolean prepOff = false;
            boolean dispOff = false;
            boolean keepOff = false;
            int prevPowerStatus = lastPowerStatus;
            if (powerService != null) {
                try {
                    if (powerGetStatusMethod != null) {
                        int st = ((Integer) powerGetStatusMethod.invoke(powerService)).intValue();
                        if (st >= 0) {
                            lastPowerStatus = st;
                        }
                    }
                    if (powerIsPrepOffMethod != null) {
                        prepOff = ((Boolean) powerIsPrepOffMethod.invoke(powerService)).booleanValue();
                    }
                    if (powerIsDispOffMethod != null) {
                        dispOff = ((Boolean) powerIsDispOffMethod.invoke(powerService)).booleanValue();
                    }
                } catch (Throwable t) {
                    powerService = null;
                }
            }
            if (blackScreenService != null && blackIsKeepScreenOffMethod != null) {
                try {
                    keepOff = ((Boolean) blackIsKeepScreenOffMethod.invoke(blackScreenService)).booleanValue();
                } catch (Throwable t) {
                    blackScreenService = null;
                }
            }
            if (datcService != null && datcIsIgn3Method != null) {
                try {
                    Boolean ig3 = (Boolean) datcIsIgn3Method.invoke(datcService);
                    if (ig3 != null) {
                        lastDatcIg3State = ig3;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (((lastPowerStatus == 0 || lastPowerStatus == 5) && prevPowerStatus != lastPowerStatus)
                    || Boolean.TRUE.equals(lastDatcIg3State)
                    || liveSnapshot.evReady) {
                lastBcmIgnCallbackState = 1;
                if (lastPowerStatus == 0 || lastPowerStatus == 5) {
                    lastBcmAccCallbackState = 1;
                }
            }
            vehicleScreenOff = evaluateVehicleScreenOffState(
                    lastPowerStatus,
                    prepOff,
                    dispOff,
                    keepOff,
                    lastDatcIg3State,
                    lastBcmIgnCallbackState,
                    lastBcmAccCallbackState,
                    liveSnapshot.charging
            );
            lastRawPowerStateInfo = String.format(Locale.US,
                    "pwr=%d(0=ACCON,2=ADM,5=READY,7=PREOFF), prepOff=%b, dispOff=%b, keepOff=%b, ig3=%s, bcmIgn=%d, bcmAcc=%d -> screenOff=%b",
                    lastPowerStatus, prepOff, dispOff, keepOff, String.valueOf(lastDatcIg3State),
                    lastBcmIgnCallbackState, lastBcmAccCallbackState, vehicleScreenOff);
        }

        private static double queryEvHistoryDbAvgEconomy() {
            Object db = null;
            Object cursor = null;
            try {
                File dbFile = new File("/securedata/com.hkmc.daudio.app.ev3/History.db");
                if (!dbFile.isFile()) return -1.0;
                Class<?> sqlCls = Class.forName("android.database.sqlite.SQLiteDatabase");
                Class<?> cfCls = Class.forName("android.database.sqlite.SQLiteDatabase$CursorFactory");
                Method openDb = sqlCls.getMethod("openDatabase", String.class, cfCls, int.class);
                db = openDb.invoke(null, dbFile.getAbsolutePath(), null, Integer.valueOf(1)); // OPEN_READONLY = 1
                if (db == null) return -1.0;
                Method rawQuery = sqlCls.getMethod("rawQuery", String.class, String[].class);
                cursor = rawQuery.invoke(db,
                        "SELECT average_energy_consumption FROM history WHERE average_energy_consumption > 5 AND average_energy_consumption < 999 ORDER BY _id DESC LIMIT 1",
                        null);
                if (cursor != null) {
                    Class<?> curCls = cursor.getClass();
                    boolean hasRow = ((Boolean) curCls.getMethod("moveToFirst").invoke(cursor)).booleanValue();
                    if (hasRow) {
                        int val = ((Integer) curCls.getMethod("getInt", int.class).invoke(cursor, Integer.valueOf(0))).intValue();
                        if (val > 5 && val < 999) {
                            return val / 10.0;
                        }
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (cursor != null) {
                    try { cursor.getClass().getMethod("close").invoke(cursor); } catch (Throwable ignored) {}
                }
                if (db != null) {
                    try { db.getClass().getMethod("close").invoke(db); } catch (Throwable ignored) {}
                }
            }
            return -1.0;
        }

        // getMethod() per poll was a per-frame reflection lookup; Method objects are cached per class + name.
        private static final HashMap<String, Method> NO_ARG_METHOD_CACHE = new HashMap<String, Method>();

        private static Method cachedNoArgMethod(Class<?> cls, String name) throws NoSuchMethodException {
            String key = cls.getName() + "#" + name;
            synchronized (NO_ARG_METHOD_CACHE) {
                Method m = NO_ARG_METHOD_CACHE.get(key);
                if (m == null) {
                    m = cls.getMethod(name);
                    NO_ARG_METHOD_CACHE.put(key, m);
                }
                return m;
            }
        }

        private static void pollTmHscanServiceLocked() {
            Object svc = tmHscanService;
            if (svc == null) return;
            try {
                Class<?> sc = svc.getClass();
                if (liveSnapshot.displaySocPct < 0.0) {
                    float soc = ((Float) cachedNoArgMethod(sc, "remainBatteryPercentage").invoke(svc)).floatValue();
                    if (soc > 0.0f && soc <= 100.0f) liveSnapshot.displaySocPct = soc;
                }
                if (liveSnapshot.sohPct < 0.0) {
                    int soh = ((Integer) cachedNoArgMethod(sc, "getXEV_Bms_GDS_SOH").invoke(svc)).intValue();
                    if (soh > 0 && soh <= 110) liveSnapshot.sohPct = soh;
                }
                if (!isValidDteValue(liveSnapshot.dteKm)) {
                    int dte = ((Integer) cachedNoArgMethod(sc, "realTimeAbleDriveDistance").invoke(svc)).intValue();
                    if (isValidDteValue(dte)) liveSnapshot.dteKm = dte;
                }

                int acRaw = ((Integer) cachedNoArgMethod(sc, "airConEleConsum").invoke(svc)).intValue();
                int htrRaw = ((Integer) cachedNoArgMethod(sc, "heaterEleConsum").invoke(svc)).intValue();
                try {
                    liveSnapshot.headLampStatus = ((Integer) cachedNoArgMethod(sc, "getAFLS11_HeadLampStatus").invoke(svc)).intValue();
                } catch (Throwable ignored) {
                }
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

                if (tmGetSasAngleMethod != null) {
                    try {
                        float sas = ((Float) tmGetSasAngleMethod.invoke(svc)).floatValue();
                        if (!Float.isNaN(sas) && Math.abs(sas) < 720.0f && !liveSnapshot.carrotConnected) {
                            liveSnapshot.steeringAngleDeg = sas;
                        }
                    } catch (Throwable ignored) {
                    }
                }

                if (bcmService != null && bcmGetSpeedKmhMethod != null) {
                    try {
                        float spdKmh = ((Float) bcmGetSpeedKmhMethod.invoke(bcmService)).floatValue();
                        if (!Float.isNaN(spdKmh) && spdKmh >= 0.0f && spdKmh <= 260.0f && !liveSnapshot.carrotConnected) {
                            liveSnapshot.carrotSpeedKph = Math.round(spdKmh);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        private static void pollDatcServiceLocked() {
            Object svc = datcService;
            if (svc != null) {
                // On DE22EV, DatcStatus.mIntake (mIntakeDisp) is 3 (BaseDisp.INVALID), whereas actual recirculation
                // state is stored in DatcBypassStatus.mIntakeStatus and exposed via IDATCService.getClimateInfo(5)
                // (0 = INTAKE_REC_OFF / Fresh Air, 1 = INTAKE_REC_ON / Recirculation).
                int intakeInfo = readDatcClimateInfoInt(svc, DATC_CLIMATE_INFO_INTAKE_STATUS, -1);
                if (intakeInfo == 0 || intakeInfo == 1) {
                    liveSnapshot.hvacRecirc = (intakeInfo == 1);
                    lastRawDatcIntakeInfo = "getClimateInfo(5)=" + intakeInfo;
                }
                if (lastRawDatcStatusObj == null) {
                    if (!applyDatcStatusObject(svc, liveSnapshot)) {
                        datcService = null;
                        datcListenerRegistered = false;
                    }
                } else if (pollTick % 12 == 0) {
                    try {
                        svc.getClass().getMethod("getTemperatureUnit").invoke(svc);
                    } catch (Throwable t) {
                        datcService = null;
                        datcListenerRegistered = false;
                        lastRawDatcStatusObj = null;
                    }
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
            String resolved = resolveGearPosition(tmGearRaw, pgsGearP, pgsGearN, sysGearP, rearCam, liveSnapshot.drivePowerKw);
            if (!resolved.isEmpty()) {
                liveSnapshot.gearPos = resolved;
            }
        }

        private static void pollMediaNowPlayingLocked() {
            long now = System.currentTimeMillis();
            if (modeService != null && modeGetCurrentMethod != null) {
                try {
                    int mode = ((Integer) modeGetCurrentMethod.invoke(modeService)).intValue();
                    String modeSrc = mapModeManagerModeToSourceName(mode);
                    if (!modeSrc.isEmpty()) {
                        if (!modeSrc.equals(modeSourceName)) {
                            // Source switch: the ClusterService mode learned from logcat belongs to the old source
                            lastClusterMediaMode = -1;
                            clusterUnitMs = null;
                            clusterLastSec = -1;
                            clusterFirstSecThisTrack = -1;
                            clusterAdvancedThisTrack = false;
                            periodicPosReporter = false;
                            periodicAdvanceStreak = 0;
                            playbackStalled = false;
                        }
                        modeSourceName = modeSrc;
                        modeSourceMs = now;
                        liveSnapshot.mediaSource = modeSrc;
                    }
                } catch (Throwable t) {
                    modeService = null;
                }
            }

            if (pollTick % 8 == 2 && ("Radio".equals(liveSnapshot.mediaSource) || "FM".equals(liveSnapshot.mediaSource)
                    || "AM".equals(liveSnapshot.mediaSource) || "DAB".equals(liveSnapshot.mediaSource)
                    || "미디어 재생 대기 중".equals(liveSnapshot.mediaTitle))) {
                pollStickyMediaIntentLocked("com.hkmc.av.app.radioservice.statuschanged");
            }

            // External injection channels (no writer in this package). Applied only when they CHANGE: persist.* props
            // survive reboot, and re-applying a stale value every frame used to override live broadcasts.
            if (pollTick % 4 == 0) {
                String propTitle = getSystemProperty("persist.sys.hkg.media.title", "");
                String propArtist = getSystemProperty("persist.sys.hkg.media.artist", "");
                String propAlbum = getSystemProperty("persist.sys.hkg.media.album", "");
                String propSource = getSystemProperty("persist.sys.hkg.media.source", "");
                String sig = propSource + "\u0001" + propTitle + "\u0001" + propArtist + "\u0001" + propAlbum;
                if (lastExternalPropSig == null) {
                    lastExternalPropSig = sig; // baseline: whatever survived the last reboot is not current
                } else if (!sig.equals(lastExternalPropSig)) {
                    lastExternalPropSig = sig;
                    if (!propSource.isEmpty()) liveSnapshot.mediaSource = propSource;
                    if (!propTitle.isEmpty() || !propArtist.isEmpty() || !propAlbum.isEmpty()) {
                        onMediaTrackUpdated(liveSnapshot, propTitle, propArtist, propAlbum);
                    }
                }

                File mediaFile = new File("/data/local/tmp/hkg_media_state.txt");
                long mtime = mediaFile.isFile() ? mediaFile.lastModified() : -1L;
                if (mtime > 0L && mtime != lastMediaStateFileMtime && (now - mtime) < 60000L) {
                    lastMediaStateFileMtime = mtime;
                    String line = readFirstLineQuick(mediaFile);
                    if (!line.isEmpty() && line.contains("|")) {
                        String[] parts = line.split("\\|");
                        if (parts.length >= 1 && !parts[0].trim().isEmpty()) liveSnapshot.mediaSource = parts[0].trim();
                        String fTitle = parts.length >= 2 ? parts[1].trim() : "";
                        String fArtist = parts.length >= 3 ? parts[2].trim() : "";
                        String fAlbum = parts.length >= 4 ? parts[3].trim() : "";
                        onMediaTrackUpdated(liveSnapshot, fTitle, fArtist, fAlbum);
                    }
                } else if (mtime > 0L) {
                    lastMediaStateFileMtime = mtime;
                }
            }

            // ClusterService.getLastPlaytime(mode): only for the mode of the currently active source, and only once it
            // has been seen moving for this track (a frozen value from another/stale mode must not pin the position).
            if (clusterService != null && clusterGetLastPlaytimeMethod != null && lastClusterMediaMode >= 0) {
                String clusterSrc = mapClusterModeToSourceName(lastClusterMediaMode);
                boolean modeKnown = !modeSourceName.isEmpty() && (now - modeSourceMs) < 5000L;
                if (!modeKnown || clusterSrc.equals(modeSourceName)) {
                    try {
                        Object rawPlay = clusterGetLastPlaytimeMethod.invoke(clusterService, Integer.valueOf(lastClusterMediaMode));
                        if (rawPlay instanceof Number) {
                            long raw = ((Number) rawPlay).longValue();
                            clusterRawPlaytime = raw;
                            if (raw > 36000L && raw < 36000000L) clusterUnitMs = Boolean.TRUE;
                            int sec = chooseClusterPlaytimeSec(raw, liveSnapshot.mediaPositionSec, clusterUnitMs);
                            clusterPlaytimeInfo = "mode=" + lastClusterMediaMode + " raw=" + raw + " -> " + sec + "s"
                                    + " unitMs=" + clusterUnitMs + " advanced=" + clusterAdvancedThisTrack;
                            if (sec >= 0 && sec < 36000) {
                                if (clusterFirstSecThisTrack < 0) {
                                    clusterFirstSecThisTrack = sec;
                                } else if (sec != clusterFirstSecThisTrack) {
                                    clusterAdvancedThisTrack = true;
                                }
                                if (clusterAdvancedThisTrack && sec != clusterLastSec) {
                                    notePositionUpdate(liveSnapshot, sec);
                                }
                                clusterLastSec = sec;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                } else {
                    clusterPlaytimeInfo = "skipped: cluster mode " + lastClusterMediaMode + " (" + clusterSrc
                            + ") != active " + modeSourceName;
                }
            }

            // Pause detection for sources that report position periodically: no forward movement for STALL_MS.
            if (liveSnapshot.mediaPlaying && periodicPosReporter && lastPosAdvanceMs > 0L
                    && (now - lastPosAdvanceMs) > STALL_MS) {
                liveSnapshot.mediaPlaying = false;
                playbackStalled = true;
            }

            // Local extrapolation between samples (and for sources that only report on track change).
            if (liveSnapshot.mediaPlaying && !playbackStalled && lastMediaPosUpdateMs > 0L
                    && (now - lastMediaPosUpdateMs) >= 1000L) {
                boolean durKnown = liveSnapshot.mediaDurationSec > 0;
                long sinceExternal = now - (lastExternalPosMs > 0L ? lastExternalPosMs : lastTrackChangeMs);
                if (durKnown || sinceExternal < EXTRAPOLATE_NO_DURATION_MAX_MS) {
                    int deltaSec = (int) ((now - lastMediaPosUpdateMs) / 1000L);
                    lastMediaPosUpdateMs += deltaSec * 1000L;
                    int nextPos = liveSnapshot.mediaPositionSec + deltaSec;
                    liveSnapshot.mediaPositionSec = durKnown ? Math.min(liveSnapshot.mediaDurationSec, nextPos) : nextPos;
                }
            }
        }

        private static void pollCarrotBridgeAndArpLocked() {
            // 1. Co-existence with HKG_5W_CarrotNavi (carrotnavi.bridge -> /data/local/tmp/carrotbridge_status.json)
            File cbFile = new File(SHARED_CARROTBRIDGE_JSON_PATH);
            if (cbFile.isFile() && (System.currentTimeMillis() - cbFile.lastModified() < 12000L)) {
                String cbJson = readAllTextQuick(cbFile);
                if (!cbJson.isEmpty()) {
                    parseCarrotBridgeStatusJson(cbJson, liveSnapshot);
                }
            }
            // 2. Fallback: scan /proc/net/arp for wlan0 hotspot clients (e.g. 192.168.0.103) if carrotIp is still empty or stale
            if (!isValidRemoteCarrotIp(liveSnapshot.carrotIp) || (!liveSnapshot.carrotConnected && (pollTick % 5 == 0))) {
                String arpIp = scanWlanArpCarrotIp();
                if (isValidRemoteCarrotIp(arpIp)) {
                    liveSnapshot.carrotIp = arpIp;
                }
            }
        }

        private static boolean isCarrotBridgeDaemonActive() {
            File cbFile = new File(SHARED_CARROTBRIDGE_JSON_PATH);
            if (cbFile.isFile() && (System.currentTimeMillis() - cbFile.lastModified() < 12000L)) {
                return true;
            }
            File pidFile = new File(SHARED_CARROTBRIDGE_PID_PATH);
            return pidFile.isFile() && (System.currentTimeMillis() - pidFile.lastModified() < 120000L);
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

        private static void pollStickyMediaIntentLocked(String action) {
            try {
                Class<?> filterCls = Class.forName("android.content.IntentFilter");
                Object filter = filterCls.getConstructor(String.class).newInstance(action);
                Class<?> amnCls = Class.forName("android.app.ActivityManagerNative");
                Object am = amnCls.getMethod("getDefault").invoke(null);
                if (am == null) return;
                Class<?> appThreadCls = Class.forName("android.app.IApplicationThread");
                Class<?> iirCls = Class.forName("android.content.IIntentReceiver");
                Object stickyIntent;
                try {
                    Method regM = am.getClass().getMethod(
                            "registerReceiver",
                            appThreadCls, String.class, iirCls, filterCls, String.class, int.class);
                    stickyIntent = regM.invoke(am, null, "android", null, filter, null, Integer.valueOf(0));
                } catch (NoSuchMethodException nsme) {
                    Method regM = am.getClass().getMethod(
                            "registerReceiver",
                            appThreadCls, String.class, iirCls, filterCls, String.class);
                    stickyIntent = regM.invoke(am, null, "android", null, filter, null);
                }
                if (stickyIntent != null) {
                    Object extras = stickyIntent.getClass().getMethod("getExtras").invoke(stickyIntent);
                    applyMediaWidgetBroadcastToSnapshot(action, extras, liveSnapshot);
                }
            } catch (Throwable ignored) {
            }
        }

        private static void runLogcatHscanWatcherLoop() {
            while (true) {
                Process proc = null;
                BufferedReader br = null;
                try {
                    proc = Runtime.getRuntime().exec(new String[]{
                            "/system/bin/logcat", "-b", "main", "-b", "system", "-v", "brief",
                            "TM_HSCANPacketRunner:I",
                            "ClusterService:D",
                            "BCMService:I",
                            "DATCService:V",
                            "MobisAvrcpControllerService:D",
                            "AvrcpControllerService:D",
                            "BTA_BT_PLAYER_MANAGER:D",
                            "BTA_MUSIC_SERVICE:D",
                            "SplitScreenViewBTAudio:D",
                            "AdvancedLauncher:I",
                            "MicomPowerService:I",
                            "*:S"
                    });
                    br = new BufferedReader(new InputStreamReader(proc.getInputStream(), UTF8), 4096);
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.contains("BCMService") || line.contains("RES_DEFOGGER_STATE") || line.contains("C_DefoggerRly")) {
                            synchronized (liveSnapshot) {
                                parseBcmDefoggerLogLine(line, liveSnapshot);
                            }
                        } else if (line.contains("DATCService") || line.contains("DATC_Intake") || line.contains("Flag : 12value")) {
                            synchronized (liveSnapshot) {
                                if (parseDatcIntakeLogLine(line, liveSnapshot)) {
                                     lastRawDatcIntakeInfo = "logcat:" + line.trim();
                                }
                            }
                        } else if (line.contains("ClusterService")) {
                            synchronized (liveSnapshot) {
                                int mode = parseClusterMediaLogLine(line, liveSnapshot);
                                if (mode >= 0) {
                                    lastClusterMediaMode = mode;
                                }
                            }
                        } else if (line.contains("MobisAvrcpControllerService")
                                || line.contains("AvrcpControllerService")
                                || line.contains("BTA_BT_PLAYER_MANAGER")
                                || line.contains("BTA_MUSIC_SERVICE")
                                || line.contains("SplitScreenViewBTAudio")
                                || line.contains("AdvancedLauncher")) {
                            synchronized (liveSnapshot) {
                                if (parseMediaLogLine(line, liveSnapshot)) {
                                    lastRawMediaLogInfo = line.trim();
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
                // When HKG_5W_CarrotNavi (carrotnavi.bridge) is active, yield UDP 7705 so CarrotBridgeMain
                // receives 100% of CarrotPilot 7705 broadcasts without Linux kernel socket contention.
                if (port == CARROT_UDP_PORT_PRIMARY && isCarrotBridgeDaemonActive()) {
                    try { Thread.sleep(3000L); } catch (InterruptedException ignored) {}
                    continue;
                }
                DatagramSocket sock = null;
                try {
                    sock = new DatagramSocket(null);
                    sock.setReuseAddress(true);
                    sock.setSoTimeout(3000);
                    sock.bind(new InetSocketAddress(port));
                    byte[] buf = new byte[4096];
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    while (true) {
                        if (port == CARROT_UDP_PORT_PRIMARY && isCarrotBridgeDaemonActive()) {
                            break;
                        }
                        pkt.setLength(buf.length);
                        try {
                            sock.receive(pkt);
                        } catch (java.net.SocketTimeoutException ste) {
                            continue;
                        }
                        String senderIp = (pkt.getAddress() != null) ? pkt.getAddress().getHostAddress() : "";
                        boolean fromRemoteComma = isValidRemoteCarrotIp(senderIp);
                        String json = new String(pkt.getData(), pkt.getOffset(), pkt.getLength(), UTF8);
                        synchronized (liveSnapshot) {
                            if (fromRemoteComma) {
                                liveSnapshot.carrotIp = senderIp;
                            }
                            if (parseCarrotManJson(json, liveSnapshot, fromRemoteComma)) {
                                if (fromRemoteComma) {
                                    lastCarrotRxMs = System.currentTimeMillis();
                                }
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
         * Connects to Comma 3X (ajouatom/openpilot carrot-wip)
         * ws://<comma_ip>:7000/ws/compact_state?services=carState,controlsState,carrotMan,radarState,selfdriveState,longitudinalPlan,modelV2
         * and decodes binary CVB1/CVS1 frames (speed, cruise, steering, lead distance, TPMS, and 3D lane polynomials).
         */
        private static void runCarrotCvs1WsClientLoop() {
            while (true) {
                Socket wsSock = null;
                try {
                    String ip;
                    synchronized (liveSnapshot) {
                        ip = liveSnapshot.carrotIp;
                    }
                    if (!isValidRemoteCarrotIp(ip)) {
                        ip = TurzxOverlayController.getConfig().commaIp;
                    }
                    if (!isValidRemoteCarrotIp(ip)) {
                        ip = scanWlanArpCarrotIp();
                        if (isValidRemoteCarrotIp(ip)) {
                            synchronized (liveSnapshot) {
                                liveSnapshot.carrotIp = ip;
                            }
                        }
                    }
                    if (!isValidRemoteCarrotIp(ip)) {
                        Thread.sleep(2500L);
                        continue;
                    }

                    wsSock = new Socket();
                    wsSock.connect(new InetSocketAddress(ip, CARROT_WS_PORT), 2000);
                    wsSock.setSoTimeout(5000);

                    OutputStream os = wsSock.getOutputStream();
                    InputStream is = wsSock.getInputStream();
                    String handshake = "GET /ws/compact_state?services=carState,controlsState,carrotMan,radarState,selfdriveState,longitudinalPlan,modelV2 HTTP/1.1\r\n"
                            + "Host: " + ip + ":" + CARROT_WS_PORT + "\r\n"
                            + "Upgrade: websocket\r\n"
                            + "Connection: Upgrade\r\n"
                            + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                            + "Sec-WebSocket-Version: 13\r\n\r\n";
                    os.write(handshake.getBytes(UTF8));
                    os.flush();

                    // Read HTTP 101 response headers and verify status code 101
                    StringBuilder hdrBuf = new StringBuilder(256);
                    int matchState = 0;
                    while (matchState < 4) {
                        int b = is.read();
                        if (b < 0) throw new RuntimeException("EOF during WS handshake");
                        if (hdrBuf.length() < 512) hdrBuf.append((char) (b & 0xFF));
                        if ((matchState == 0 || matchState == 2) && b == '\r') matchState++;
                        else if ((matchState == 1 || matchState == 3) && b == '\n') matchState++;
                        else matchState = 0;
                    }
                    String hdrStr = hdrBuf.toString();
                    int firstCrLf = hdrStr.indexOf("\r\n");
                    String statusLine = firstCrLf > 0 ? hdrStr.substring(0, firstCrLf) : hdrStr;
                    if (!statusLine.contains("101")) {
                        throw new RuntimeException("WS upgrade rejected: " + statusLine);
                    }
                    wsSock.setSoTimeout(15000);
                    DebugManager.logEvent("CARROT_WS", "Connected to ws://" + ip + ":" + CARROT_WS_PORT + "/ws/compact_state");

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
                        } else if (opcode == 1 && payload.length >= 2) { // Text JSON frame
                            String txt = new String(payload, UTF8);
                            synchronized (liveSnapshot) {
                                if (parseCarrotManJson(txt, liveSnapshot, true)) {
                                    lastCarrotRxMs = System.currentTimeMillis();
                                }
                            }
                        } else if (opcode == 9) { // Ping -> reply with client-masked Pong (RFC 6455)
                            int pongLen = Math.min(payload.length, 125);
                            byte[] pongFrame = new byte[6 + pongLen];
                            pongFrame[0] = (byte) 0x8A; // FIN + Pong (0xA)
                            pongFrame[1] = (byte) (0x80 | pongLen); // MASK bit set
                            pongFrame[2] = 0x12;
                            pongFrame[3] = 0x34;
                            pongFrame[4] = 0x56;
                            pongFrame[5] = 0x78;
                            for (int i = 0; i < pongLen; i++) {
                                pongFrame[6 + i] = (byte) (payload[i] ^ pongFrame[2 + (i & 3)]);
                            }
                            os.write(pongFrame);
                            os.flush();
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
                            byte[] jpg = TurzxUsbDriver.awaitFreshLandscapeJpeg(800L);
                            if (jpg != null && jpg.length > 0) {
                                writeHttpResponse(os, "200 OK", "image/jpeg", jpg);
                            } else {
                                writeHttpResponse(os, "404 Not Found", "text/plain; charset=utf-8", "Frame not ready".getBytes(UTF8));
                            }
                        } else if (path.startsWith("/albumart.jpg")) {
                            byte[] art = latestAlbumArtBytes;
                            if (art != null && art.length > 0) {
                                writeHttpResponse(os, "200 OK", "image/jpeg", art);
                            } else {
                                writeHttpResponse(os, "404 Not Found", "text/plain; charset=utf-8", "No album art".getBytes(UTF8));
                            }
                        } else if (path.startsWith("/debug.txt") || path.startsWith("/logs")) {
                            // View-only by default; writing to USB happens only on explicit save (?save=1 or overlay button)
                            String dir = TurzxOverlayController.getInstallDir();
                            boolean save = path.contains("save=1") || path.contains("save=true");
                            String savedPath = "";
                            if (save) {
                                savedPath = DebugManager.dumpDiagnosticsToDisk(dir, getLatestSnapshot(), TurzxOverlayController.getConfig());
                                DebugManager.requestDaemonUsbLogSync();
                            }
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
            c.hvacRearDefog = liveSnapshot.hvacRearDefog;
            c.hvacSteerHeat = liveSnapshot.hvacSteerHeat;
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
            c.carrotTbtDistNextM = liveSnapshot.carrotTbtDistNextM;
            c.carrotTbtRoad = liveSnapshot.carrotTbtRoad;
            c.carrotIp = liveSnapshot.carrotIp;
            c.leadDistM = liveSnapshot.leadDistM;
            c.leadRelVelKph = liveSnapshot.leadRelVelKph;
            c.hasLeadCar = liveSnapshot.hasLeadCar;
            c.steeringAngleDeg = liveSnapshot.steeringAngleDeg;
            c.laneConfidencePct = liveSnapshot.laneConfidencePct;
            c.tpmsFlPsi = liveSnapshot.tpmsFlPsi;
            c.tpmsFrPsi = liveSnapshot.tpmsFrPsi;
            c.tpmsRlPsi = liveSnapshot.tpmsRlPsi;
            c.tpmsRrPsi = liveSnapshot.tpmsRrPsi;
            c.headLampStatus = liveSnapshot.headLampStatus;
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
            c.albumArtSource = liveSnapshot.albumArtSource;
            c.albumArtTrackKey = liveSnapshot.albumArtTrackKey;
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
            config.usbPanelFps = loaded.usbPanelFps;
            config.overlayHotkeyCode = loaded.overlayHotkeyCode;
            config.hudRoadEnabled = loaded.hudRoadEnabled;
            config.usbIncrementalEncode = loaded.usbIncrementalEncode;
            config.renderParallel = loaded.renderParallel;
            config.hudDesignTesla = loaded.hudDesignTesla;
            config.telemetryPollMs = loaded.telemetryPollMs;
            LiveTuning.loadFrom(config);
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

                        // v1.4.3: the full-screen dashboard starts hidden (MAP 3s long press / launcher to open)

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

        // =================================================================
        // Full-screen diagnostic dashboard (v1.4.3). Hidden at boot; MAP held 3s (HotkeyWatcher), the launcher
        // "show"/"toggle" commands or the ✖ button change it. Auto-hidden while reversing / rear camera / screen off.
        // =================================================================
        static final String[] CARD_TITLES = new String[]{
                "USB · 프레임 파이프라인", "배터리", "전력 · 주행", "공조", "미디어", "연결 · 시스템"
        };
        private static final int CARD_COUNT = 6;
        private static final long REFRESH_MS = 500L;
        private static final Object[] cardBodyTvs = new Object[CARD_COUNT];
        private static final String[] lastCardTexts = new String[CARD_COUNT];
        private static volatile Object subtitleTv = null;
        // Sized from the experiment panel rows (v1.4.7: a fixed size of 5 broke the dashboard when rows 6-7 were added)
        static final Object[] controlValueTvs = new Object[LiveTuning.ROW_LABELS.length];
        static final String[] lastControlTexts = new String[LiveTuning.ROW_LABELS.length];
        private static volatile Object benchTv = null;
        private static final String[] lastBenchText = new String[]{""};
        private static String lastSubtitle = "";
        private static volatile boolean wantVisible = false;
        private static boolean lastShownState = true;
        private static int showRetries = 0; // UI thread only

        /** Pipeline / system values shown on the dashboard (filled from the live getters, or by self-tests). */
        static final class DashboardStats {
            boolean usbConnected;
            String usbStatus = "";
            double fps;
            int frames;
            int targetFps;
            double jpegKb;
            String encoder = "";
            double pollMs;
            double renderMs;
            double encodeMs;
            double roadMs;
            String dirtyRows = "";
            double cpuPct = -1.0;
            double txMs;
            double ackMs;
            double slotWaitMs;
            String otaStatus = "";
            String hotkeyInfo = "";
            String recentKeys = "";
        }

        static DashboardStats collectDashboardStats() {
            DashboardStats p = new DashboardStats();
            p.usbConnected = TurzxUsbDriver.isConnected();
            p.usbStatus = TurzxUsbDriver.getStatusText();
            p.fps = TurzxUsbDriver.getLiveFps();
            p.frames = TurzxUsbDriver.getPushedFrames();
            p.targetFps = Math.max(2, Math.min(25, LiveTuning.targetFps));
            p.jpegKb = TurzxUsbDriver.getAvgJpegKb();
            p.encoder = TurzxFrameRenderer.getLastEncoderName();
            p.pollMs = TurzxUsbDriver.getAvgPollMs();
            p.renderMs = TurzxFrameRenderer.getAvgRenderMs();
            p.encodeMs = TurzxFrameRenderer.getAvgEncodeMs();
            p.roadMs = TurzxFrameRenderer.getAvgRoadMs();
            p.dirtyRows = TurzxFrameRenderer.getDirtyRowsText();
            p.cpuPct = ProcCpu.sample(System.currentTimeMillis());
            p.txMs = TurzxUsbDriver.getAvgTxMs();
            p.ackMs = TurzxUsbDriver.getAvgAckMs();
            p.slotWaitMs = TurzxUsbDriver.getAvgSlotWaitMs();
            p.otaStatus = OtaUpdateManager.getStatusText();
            p.hotkeyInfo = HotkeyWatcher.getInfo();
            p.recentKeys = HotkeyWatcher.getRecentKeysText();
            return p;
        }

        /**
         * Which pipeline stage limits the frame rate. Since v1.4.4 render and encode run on separate threads,
         * so the producer cost is max(render, encode), not their sum.
         */
        static String describeBottleneck(DashboardStats p) {
            if (!p.usbConnected) return "USB 미연결";
            double produce = Math.max(p.renderMs, p.encodeMs);
            double consume = p.txMs + p.ackMs;
            if (produce <= 0.0 || consume <= 0.0) return "측정 중...";
            if (p.targetFps > 0 && p.fps >= p.targetFps * 0.9) {
                return "목표 FPS 도달";
            }
            if (p.slotWaitMs > 15.0 || consume > produce * 1.15) {
                return String.format(Locale.US, "병목: USB·패널 %.0fms", consume);
            }
            if (produce > consume * 1.15) {
                return (p.renderMs >= p.encodeMs)
                        ? String.format(Locale.US, "병목: 렌더 %.0fms", p.renderMs)
                        : String.format(Locale.US, "병목: 인코딩 %.0fms", p.encodeMs);
            }
            return String.format(Locale.US, "생성 %.0f ≈ 전송 %.0f", produce, consume);
        }

        private static String d1(double v, boolean valid) {
            return valid ? String.format(Locale.US, "%.1f", v) : "--";
        }

        private static String cut(String s, int max) {
            if (s == null || s.isEmpty()) return "--";
            return s.length() <= max ? s : s.substring(0, Math.max(1, max - 1)) + "…";
        }

        /** Card texts: at most 7 short lines each (cards are ~390px wide at 19px). */
        static String[] buildDashboardBodies(TelemetrySnapshot s, DashboardStats p) {
            String[] b = new String[CARD_COUNT];

            b[0] = cut(p.usbStatus.replace("TURZX ", ""), 22) + "\n"
                    + String.format(Locale.US, "FPS %.1f / 목표 %d\n", p.fps, p.targetFps)
                    + "JPEG " + d1(p.jpegKb, p.jpegKb > 0.0) + "KB · "
                    + (p.dirtyRows.isEmpty() ? "누적 " + p.frames : "갱신 " + p.dirtyRows + "행") + "\n"
                    + String.format(Locale.US, "렌더 %.0f(도로 %.0f) · 인코딩 %.0f\n", p.renderMs, p.roadMs, p.encodeMs)
                    + String.format(Locale.US, "전송 %.0f · 응답 %.0fms\n", p.txMs, p.ackMs)
                    + String.format(Locale.US, "수집 %.0fms · CPU ", p.pollMs)
                    + (p.cpuPct >= 0.0 ? String.format(Locale.US, "%.0f%%", p.cpuPct) : "--") + "/400%\n"
                    + "→ " + describeBottleneck(p);

            double soc = s.getEffectiveSocPct();
            b[1] = "SOC " + d1(soc, soc >= 0.0) + "% · BMS " + d1(s.bmsSocPct, s.bmsSocPct >= 0.0) + "%\n"
                    + "SOH " + d1(s.sohPct, s.sohPct >= 0.0) + "% · 잔여 "
                    + d1(s.remainEnergyKwh, s.remainEnergyKwh > 0.0) + "kWh\n"
                    + "주행가능 " + (isValidDteValue(s.dteKm) ? String.valueOf(s.dteKm) : "--") + "km"
                    + (s.dteAirconOffDeltaKm > 0 ? " (공조OFF +" + s.dteAirconOffDeltaKm + ")" : "") + "\n"
                    + "전비 " + d1(s.avgEconomyKmKwh, s.avgEconomyKmKwh > 0.0) + " km/kWh\n"
                    + "팩 " + d1(s.packVoltV, s.packVoltV > 0.0) + "V · " + d1(s.packCurrA, s.hasPackCurrent) + "A\n"
                    + "셀 " + (s.cellMaxVoltV > 0.0 ? String.format(Locale.US, "%.2f", s.cellMaxVoltV) : "--") + "/"
                    + (s.cellMinVoltV > 0.0 ? String.format(Locale.US, "%.2f", s.cellMinVoltV) : "--") + "V · 편차 "
                    + (s.cellDiffMv >= 0 ? String.valueOf(s.cellDiffMv) : "--") + "mV\n"
                    + "온도 " + (s.battMinTempC != null ? String.valueOf(s.battMinTempC) : "--") + " ~ "
                    + (s.battMaxTempC != null ? String.valueOf(s.battMaxTempC) : "--") + "°C";

            double total = s.getTotalPowerKw();
            String chg = s.fastCharging ? "급속 충전 중" : (s.stdCharging ? "완속 충전 중" : (s.charging ? "충전 중" : "충전 안 함"));
            b[2] = String.format(Locale.US, "총 %+.1fkW%s\n", total, total < -0.05 ? " (회생)" : "")
                    + String.format(Locale.US, "구동 %.1f · 공조 %.1f\n",
                    s.getEffectiveDriveMotorKw(), Math.max(0.0, s.climatePowerKw))
                    + String.format(Locale.US, "전장 %.1f · 배터리 %.1f\n",
                    Math.max(0.0, s.electronicsPowerKw), Math.max(0.0, s.batteryCarePowerKw))
                    + "기어 " + (s.gearPos != null && !s.gearPos.isEmpty() ? s.gearPos : "--")
                    + " · Ready " + (s.evReady ? "ON" : "OFF") + "\n"
                    + chg + (s.batteryConditioning ? " · 컨디셔닝" : "") + "\n"
                    + "속도 " + Math.max(0, s.carrotSpeedKph) + " · 크루즈 "
                    + (s.carrotCruiseKph > 0 ? String.valueOf(s.carrotCruiseKph) : "--") + "\n"
                    + String.format(Locale.US, "조향 %+.1f°", s.steeringAngleDeg);

            StringBuilder flags = new StringBuilder();
            if (s.hvacAuto) flags.append(" AUTO");
            if (s.hvacAc) flags.append(" A/C");
            if (s.hvacHeat) flags.append(" HEAT");
            if (s.hvacSync) flags.append(" SYNC");
            b[3] = (s.hvacOn ? "ON ·" + flags : "OFF") + "\n"
                    + (s.hvacRecirc ? "내기" : "외기") + " · 풍량 "
                    + (s.hvacBlowerStep >= 0 ? s.hvacBlowerStep + "단" : "--") + "\n"
                    + "운전석 " + d1(s.hvacDrTempC, s.hvacOn) + "° · 동승석 "
                    + (s.hvacDriverOnly ? "DRV" : d1(s.hvacPsTempC, s.hvacOn) + "°") + "\n"
                    + "실내 " + d1(s.inCarTempC, s.inCarTempC > -50.0) + "° · 실외 "
                    + d1(s.outdoorTempC, s.outdoorTempC > -50.0) + "°\n"
                    + "모드 " + formatBlowerModeText(s.hvacBlowerMode) + "\n"
                    + "앞유리 " + (s.hvacFrontDefog ? "ON" : "OFF") + " · 뒷유리 " + (s.hvacRearDefog ? "ON" : "OFF")
                    + (s.hasClimateData ? "" : "\n(공조 데이터 미수신)");

            b[4] = "소스 " + cut(s.mediaSource, 16) + "\n"
                    + "제목 " + cut(s.mediaTitle, 16) + "\n"
                    + "가수 " + cut(s.mediaArtist, 16) + "\n"
                    + "앨범 " + cut(s.mediaAlbum, 16) + "\n"
                    + (s.mediaPlaying ? "▶ 재생 " : "❚❚ 정지 ") + TurzxFrameRenderer.formatSecMinTwoDigit(s.mediaPositionSec)
                    + " / " + TurzxFrameRenderer.formatSecMinTwoDigit(s.mediaDurationSec) + "\n"
                    + "앨범아트 " + (s.albumArtBitmap != null ? cut(s.albumArtSource, 14) : "없음");

            String recent = p.recentKeys;
            int comma = recent != null ? recent.indexOf(", ") : -1;
            if (comma > 0) recent = recent.substring(0, comma); // newest key only
            b[5] = "Comma " + (s.carrotConnected ? cut(s.carrotIp, 16) : "미연결") + "\n"
                    + String.format(Locale.US, "TPMS %.0f/%.0f/%.0f/%.0f psi\n",
                    s.tpmsFlPsi, s.tpmsFrPsi, s.tpmsRlPsi, s.tpmsRrPsi)
                    + "OTA " + cut(p.otaStatus, 18) + "\n"
                    + "MAP " + cut(p.hotkeyInfo, 20) + "\n"
                    + "최근 키 " + cut(recent, 18);
            return b;
        }

        public static void toggle() {
            toggle("launcher");
        }

        /**
         * Opens / closes the dashboard based on what is actually on screen (v1.4.5). Before, the flag alone decided:
         * a dashboard that was auto-hidden (reverse, rear camera, screen off) or had failed to attach still counted
         * as "open", so the next MAP long press closed an invisible window and nothing appeared.
         */
        public static void toggle(final String source) {
            if (uiHandler == null) {
                DebugManager.logEvent("OVERLAY", "Toggle (" + source + ") ignored: UI looper not ready");
                return;
            }
            postToUi(new Runnable() {
                @Override
                public void run() {
                    boolean onScreen = wantVisible && rootView != null && lastShownState;
                    if (onScreen) {
                        wantVisible = false;
                        hideOnUiThread();
                        DebugManager.logEvent("OVERLAY", "Toggle (" + source + "): dashboard closed");
                    } else {
                        wantVisible = true;
                        showRetries = 0;
                        String hiddenWhy = autoHideReason();
                        DebugManager.logEvent("OVERLAY", "Toggle (" + source + "): dashboard open requested"
                                + (rootView != null ? " (window exists)" : "")
                                + (hiddenWhy.isEmpty() ? "" : " - auto-hidden now: " + hiddenWhy + ", appears when it clears"));
                        if (rootView != null) refreshOnUiThread();
                        else showOnUiThread();
                    }
                }
            });
        }

        /** Why the dashboard must stay hidden right now ("" = may be shown). */
        static String autoHideReason() {
            if ("1".equals(getSystemProperty("tcc.hwc.lcd.disable", "0"))) return "LCD off";
            if ("false".equalsIgnoreCase(getSystemProperty("sys.boot.show_screen", "true"))) return "boot screen";
            if (TelemetryCollector.isVehicleScreenOff()) return "vehicle screen off";
            if ("1".equals(getSystemProperty("persist.sys.rearcam.open", "0"))) return "rear camera";
            TelemetrySnapshot snap = TelemetryCollector.getLatestSnapshot();
            if (snap != null && "R".equals(snap.gearPos)) return "gear R";
            return "";
        }

        public static void setEnabledAndShow(final boolean enable) {
            postToUi(new Runnable() {
                @Override
                public void run() {
                    wantVisible = enable;
                    if (enable) showOnUiThread();
                    else hideOnUiThread();
                }
            });
        }

        public static boolean isDashboardVisible() {
            return wantVisible;
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

        // ---- View helpers (reflection; pixel units: COMPLEX_UNIT_PX = 0) ----
        private static Object newText(Class<?> ctxCls, Class<?> tvCls, Class<?> viewCls, String text, float sizePx,
                                      int color, boolean bold) throws Exception {
            Object tv = tvCls.getConstructor(ctxCls).newInstance(sysContext);
            tvCls.getMethod("setText", CharSequence.class).invoke(tv, text);
            tvCls.getMethod("setTextSize", int.class, float.class).invoke(tv, 0, sizePx);
            tvCls.getMethod("setTextColor", int.class).invoke(tv, color);
            if (bold) {
                Class<?> tfCls = Class.forName("android.graphics.Typeface");
                tvCls.getMethod("setTypeface", tfCls).invoke(tv, tfCls.getField("DEFAULT_BOLD").get(null));
            }
            return tv;
        }

        private static Object newRoundBg(Class<?> gdCls, int fill, float radius, int strokeW, int strokeColor) throws Exception {
            Object gd = gdCls.getConstructor().newInstance();
            gdCls.getMethod("setColor", int.class).invoke(gd, fill);
            gdCls.getMethod("setCornerRadius", float.class).invoke(gd, radius);
            if (strokeW > 0) gdCls.getMethod("setStroke", int.class, int.class).invoke(gd, strokeW, strokeColor);
            return gd;
        }

        private static void showOnUiThread() {
            if (!wantVisible) return;
            if (rootView != null) {
                refreshOnUiThread();
                return;
            }
            if (!ensureUiContext()) {
                postDelayedToUi(new Runnable() {
                    @Override
                    public void run() {
                        if (wantVisible && rootView == null) showOnUiThread();
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
                Method setOrientation = llCls.getMethod("setOrientation", int.class);
                Method setGravity = llCls.getMethod("setGravity", int.class);
                Method setPadding = viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class);
                Method setBg = viewCls.getMethod("setBackgroundDrawable", drawableCls);
                Method addView = vgCls.getMethod("addView", viewCls, vgLpCls);
                Method setMargins = llLpCls.getMethod("setMargins", int.class, int.class, int.class, int.class);
                Constructor<?> llLpCtor = llLpCls.getConstructor(int.class, int.class);
                Constructor<?> llLpWCtor = llLpCls.getConstructor(int.class, int.class, float.class);
                Method setLineSpacing = tvCls.getMethod("setLineSpacing", float.class, float.class);

                // Root: full screen, vertical
                Object root = llCls.getConstructor(ctxCls).newInstance(sysContext);
                setOrientation.invoke(root, 1); // VERTICAL
                setPadding.invoke(root, 28, 18, 28, 14);
                setBg.invoke(root, newRoundBg(gdCls, 0xF6070B14, 0f, 0, 0));

                // Header: title · subtitle (version/time/banner) · buttons
                Object header = llCls.getConstructor(ctxCls).newInstance(sysContext);
                setOrientation.invoke(header, 0);
                setGravity.invoke(header, 16); // CENTER_VERTICAL
                addView.invoke(header, newText(ctxCls, tvCls, viewCls, "TURZX HUD 진단", 34f, 0xFFF8FAFC, true),
                        llLpCtor.newInstance(-2, -2));
                Object sub = newText(ctxCls, tvCls, viewCls, "", 22f, 0xFF94A3B8, false);
                Object subLp = llLpWCtor.newInstance(0, -2, 1f);
                setMargins.invoke(subLp, 24, 0, 16, 0);
                addView.invoke(header, sub, subLp);

                String[] labels = new String[]{" ☁️ 업데이트 ", " 💾 로그저장 ", " ✖ 닫기 "};
                int[] strokes = new int[]{0xFF0284C7, 0xFF0D9488, 0xFF64748B};
                Runnable[] actions = new Runnable[]{
                        new Runnable() {
                            @Override
                            public void run() {
                                OtaUpdateManager.triggerManualOta(installDir, false);
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                DebugManager.triggerAsyncDump(installDir);
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                setEnabledAndShow(false);
                            }
                        }
                };
                for (int i = 0; i < labels.length; i++) {
                    Object btn = createOverlayButton(ctxCls, viewCls, tvCls, gdCls, drawableCls, labels[i], strokes[i], actions[i]);
                    Object lpBtn = llLpCtor.newInstance(-2, -2);
                    setMargins.invoke(lpBtn, 12, 0, 0, 0);
                    addView.invoke(header, btn, lpBtn);
                }
                addView.invoke(root, header, llLpCtor.newInstance(-1, -2));

                // Body: experiment panel (left, full height) + 2 x 3 card grid (right)
                Object bodyRow = llCls.getConstructor(ctxCls).newInstance(sysContext);
                setOrientation.invoke(bodyRow, 0);
                Object bodyRowLp = llLpWCtor.newInstance(-1, 0, 1f);
                setMargins.invoke(bodyRowLp, 0, 14, 0, 0);

                Object panel = llCls.getConstructor(ctxCls).newInstance(sysContext);
                setOrientation.invoke(panel, 1);
                setPadding.invoke(panel, 20, 14, 20, 12);
                setBg.invoke(panel, newRoundBg(gdCls, 0xFF0B1A24, 16f, 2, 0xFF0E7490));
                addView.invoke(panel, newText(ctxCls, tvCls, viewCls, "실험 패널 · 저장 전까지 임시 적용", 21f, 0xFF22D3EE, true),
                        llLpCtor.newInstance(-1, -2));
                Method setTvGravity = tvCls.getMethod("setGravity", int.class);
                Method setTvWidth = tvCls.getMethod("setWidth", int.class);
                for (int row = 0; row < LiveTuning.ROW_LABELS.length; row++) {
                    final int r = row;
                    Object ctl = llCls.getConstructor(ctxCls).newInstance(sysContext);
                    setOrientation.invoke(ctl, 0);
                    setGravity.invoke(ctl, 16);
                    addView.invoke(ctl, newText(ctxCls, tvCls, viewCls, LiveTuning.ROW_LABELS[row], 20f, 0xFFCBD5E1, false),
                            llLpWCtor.newInstance(0, -2, 1f));
                    addView.invoke(ctl, createOverlayButton(ctxCls, viewCls, tvCls, gdCls, drawableCls, " － ", 0xFF475569, 20f,
                            new Runnable() {
                                @Override
                                public void run() {
                                    LiveTuning.adjust(r, -1);
                                    refreshOnUiThread();
                                }
                            }), llLpCtor.newInstance(-2, -2));
                    Object val = newText(ctxCls, tvCls, viewCls, LiveTuning.valueText(row), 21f, 0xFFF8FAFC, true);
                    setTvGravity.invoke(val, 17); // CENTER
                    setTvWidth.invoke(val, 110);
                    addView.invoke(ctl, val, llLpCtor.newInstance(-2, -2));
                    addView.invoke(ctl, createOverlayButton(ctxCls, viewCls, tvCls, gdCls, drawableCls, " ＋ ", 0xFF475569, 20f,
                            new Runnable() {
                                @Override
                                public void run() {
                                    LiveTuning.adjust(r, +1);
                                    refreshOnUiThread();
                                }
                            }), llLpCtor.newInstance(-2, -2));
                    controlValueTvs[row] = val;
                    lastControlTexts[row] = "";
                    Object ctlLp = llLpCtor.newInstance(-1, -2);
                    setMargins.invoke(ctlLp, 0, 4, 0, 0);
                    addView.invoke(panel, ctl, ctlLp);
                }

                Object actRow = llCls.getConstructor(ctxCls).newInstance(sysContext);
                setOrientation.invoke(actRow, 0);
                String[] actLabels = new String[]{" ▶측정 ", " 💾저장 ", " ↺복원 ", " 🧪RST "};
                int[] actStrokes = new int[]{0xFF22D3EE, 0xFF0D9488, 0xFF64748B, 0xFFD9A441};
                Runnable[] actRuns = new Runnable[]{
                        new Runnable() {
                            @Override
                            public void run() {
                                FpsBench.restart(LiveTuning.label(), System.currentTimeMillis());
                                refreshOnUiThread();
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                LiveTuning.saveTo(config);
                                saveMonitorConfig(configFile, config);
                                DebugManager.logEvent("BENCH", "Saved experiment values to monitor.conf: " + LiveTuning.label());
                                showBannerMessage("💾 실험 값 저장: " + LiveTuning.label(), 4000L);
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                LiveTuning.loadFrom(config);
                                LiveTuning.panelFpsDirty = LiveTuning.panelFps > 0;
                                FpsBench.restart(LiveTuning.label(), System.currentTimeMillis());
                                showBannerMessage("↺ 저장된 설정으로 복원", 3000L);
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                // v1.4.4: panel JPEG restart-marker (DRI/RST) support test, A/B/C 6s each on the panel
                                showBannerMessage("🧪 " + PanelRstTest.start(System.currentTimeMillis()), 5000L);
                                refreshOnUiThread();
                            }
                        }
                };
                for (int i = 0; i < actLabels.length; i++) {
                    Object lpAct = llLpCtor.newInstance(-2, -2);
                    setMargins.invoke(lpAct, i > 0 ? 8 : 0, 0, 0, 0);
                    addView.invoke(actRow, createOverlayButton(ctxCls, viewCls, tvCls, gdCls, drawableCls,
                            actLabels[i], actStrokes[i], 19f, actRuns[i]), lpAct);
                }
                Object actLp = llLpCtor.newInstance(-1, -2);
                setMargins.invoke(actLp, 0, 12, 0, 0);
                addView.invoke(panel, actRow, actLp);

                Object bench = newText(ctxCls, tvCls, viewCls, "", 19f, 0xFFE2E8F0, false);
                setLineSpacing.invoke(bench, 0f, 1.08f);
                Object benchLp = llLpCtor.newInstance(-1, -2);
                setMargins.invoke(benchLp, 0, 10, 0, 0);
                addView.invoke(panel, bench, benchLp);
                benchTv = bench;
                lastBenchText[0] = "";
                // v1.5.1: 9 tuning rows + actions + bench may exceed the screen height -> scrollable column
                Object panelHost = panel;
                try {
                    Class<?> svCls = Class.forName("android.widget.ScrollView");
                    Object sv = svCls.getConstructor(ctxCls).newInstance(sysContext);
                    svCls.getMethod("setFillViewport", boolean.class).invoke(sv, Boolean.TRUE);
                    addView.invoke(sv, panel, vgLpCls.getConstructor(int.class, int.class).newInstance(-1, -2));
                    panelHost = sv;
                } catch (Throwable svErr) {
                    panelHost = panel;
                }
                addView.invoke(bodyRow, panelHost, llLpCtor.newInstance(520, -1));

                Object grid = llCls.getConstructor(ctxCls).newInstance(sysContext);
                setOrientation.invoke(grid, 1);
                for (int row = 0; row < 2; row++) {
                    Object rowLl = llCls.getConstructor(ctxCls).newInstance(sysContext);
                    setOrientation.invoke(rowLl, 0);
                    Object rowLp = llLpWCtor.newInstance(-1, 0, 1f);
                    setMargins.invoke(rowLp, 0, row > 0 ? 14 : 0, 0, 0);
                    for (int col = 0; col < 3; col++) {
                        int idx = row * 3 + col;
                        Object card = llCls.getConstructor(ctxCls).newInstance(sysContext);
                        setOrientation.invoke(card, 1);
                        setPadding.invoke(card, 18, 12, 18, 10);
                        setBg.invoke(card, newRoundBg(gdCls, 0xFF0F172A, 16f, 2, 0xFF1E293B));
                        addView.invoke(card, newText(ctxCls, tvCls, viewCls, CARD_TITLES[idx], 20f, 0xFF38BDF8, true),
                                llLpCtor.newInstance(-1, -2));
                        Object body = newText(ctxCls, tvCls, viewCls, "", 19f, 0xFFE2E8F0, false);
                        setLineSpacing.invoke(body, 0f, 1.1f);
                        Object bodyLp = llLpCtor.newInstance(-1, -2);
                        setMargins.invoke(bodyLp, 0, 6, 0, 0);
                        addView.invoke(card, body, bodyLp);
                        cardBodyTvs[idx] = body;
                        lastCardTexts[idx] = "";

                        Object cardLp = llLpWCtor.newInstance(0, -1, 1f);
                        setMargins.invoke(cardLp, col > 0 ? 14 : 0, 0, 0, 0);
                        addView.invoke(rowLl, card, cardLp);
                    }
                    addView.invoke(grid, rowLl, rowLp);
                }
                Object gridLp = llLpWCtor.newInstance(0, -1, 1f);
                setMargins.invoke(gridLp, 14, 0, 0, 0);
                addView.invoke(bodyRow, grid, gridLp);
                addView.invoke(root, bodyRow, bodyRowLp);

                Object foot = newText(ctxCls, tvCls, viewCls,
                        "MAP 버튼 3초: 열기/닫기  ·  실험 값은 [저장] 전까지 재시작하면 원래대로  ·  후진(R)·후방카메라·화면 꺼짐 시 자동 숨김",
                        18f, 0xFF64748B, false);
                Object footLp = llLpCtor.newInstance(-2, -2);
                setMargins.invoke(footLp, 4, 8, 0, 0);
                addView.invoke(root, foot, footLp);

                Class<?> wmLpCls = Class.forName("android.view.WindowManager$LayoutParams");
                Constructor<?> lpCtor = wmLpCls.getConstructor(int.class, int.class, int.class, int.class, int.class);
                // NOT_FOCUSABLE (hard keys keep reaching Android) | LAYOUT_IN_SCREEN | LAYOUT_NO_LIMITS | FULLSCREEN | HW accel
                final int flags = 0x00000008 | 0x00000100 | 0x00000200 | 0x00000400 | 0x01000000;
                Object lp = lpCtor.newInstance(-1, -1, 2020, flags, -3); // MATCH_PARENT, TYPE_VOLUME_OVERLAY, TRANSLUCENT
                wmLpCls.getField("gravity").setInt(lp, 51); // TOP | LEFT
                wmLpCls.getField("x").setInt(lp, 0);
                wmLpCls.getField("y").setInt(lp, 0);

                Class<?> vmCls = Class.forName("android.view.ViewManager");
                vmCls.getMethod("addView", viewCls, vgLpCls).invoke(windowManager, root, lp);

                rootView = root;
                currentWmLp = lp;
                subtitleTv = sub;
                lastSubtitle = "";
                lastShownState = true;
                isVisible = true;
                DebugManager.logEvent("OVERLAY", "Full-screen dashboard shown");
                refreshOnUiThread();
            } catch (Throwable t) {
                DebugManager.logError("OVERLAY", "showOnUiThread", t);
                rootView = null;
                if (showRetries < 1) {
                    showRetries++;
                    windowManager = null; // re-resolved by ensureUiContext()
                    DebugManager.logEvent("OVERLAY", "Dashboard attach failed -> retry in 1s");
                    postDelayedToUi(new Runnable() {
                        @Override
                        public void run() {
                            if (wantVisible && rootView == null) showOnUiThread();
                        }
                    }, 1000L);
                } else {
                    wantVisible = false; // next MAP long press tries again instead of "closing" nothing
                    DebugManager.logEvent("OVERLAY", "Dashboard attach failed twice -> closed state");
                }
            }
        }

        private static Object createOverlayButton(Class<?> ctxCls, Class<?> viewCls, Class<?> tvCls, Class<?> gdCls,
                                                  Class<?> drawableCls, String label, int strokeColor, Runnable onClick)
                throws Exception {
            return createOverlayButton(ctxCls, viewCls, tvCls, gdCls, drawableCls, label, strokeColor, 24f, onClick);
        }

        private static Object createOverlayButton(
                Class<?> ctxCls,
                Class<?> viewCls,
                Class<?> tvCls,
                final Class<?> gdCls,
                Class<?> drawableCls,
                String label,
                final int strokeColor,
                float textSizePx,
                final Runnable onClick
        ) throws Exception {
            final Object btn = tvCls.getConstructor(ctxCls).newInstance(sysContext);
            tvCls.getMethod("setText", CharSequence.class).invoke(btn, label);
            tvCls.getMethod("setTextSize", int.class, float.class).invoke(btn, 0, textSizePx);
            tvCls.getMethod("setTextColor", int.class).invoke(btn, 0xFFF8FAFC);
            int padH = Math.round(textSizePx * 0.9f);
            int padV = Math.round(textSizePx * 0.45f);
            viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(btn, padH, padV, padH, padV);

            final Object gd = newRoundBg(gdCls, 0xFF1E293B, 12f, 2, strokeColor);
            viewCls.getMethod("setBackgroundDrawable", drawableCls).invoke(btn, gd);
            viewCls.getMethod("setClickable", boolean.class).invoke(btn, true);

            // Handle touch directly on ACTION_DOWN / ACTION_UP so periodic text refreshes never cancel clicks
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
            Object h = uiHandler;
            if (h != null) {
                try {
                    h.getClass().getMethod("removeCallbacks", Runnable.class).invoke(h, REFRESH_TASK);
                } catch (Throwable ignored) {
                }
            }
            Object oldRoot = rootView;
            rootView = null;
            currentWmLp = null;
            subtitleTv = null;
            benchTv = null;
            lastBenchText[0] = "";
            for (int i = 0; i < controlValueTvs.length; i++) {
                controlValueTvs[i] = null;
                lastControlTexts[i] = "";
            }
            for (int i = 0; i < CARD_COUNT; i++) {
                cardBodyTvs[i] = null;
                lastCardTexts[i] = "";
            }
            isVisible = false;
            try {
                if (oldRoot != null && windowManager != null) {
                    Class<?> viewCls = Class.forName("android.view.View");
                    Class<?> vmCls = Class.forName("android.view.ViewManager");
                    vmCls.getMethod("removeView", viewCls).invoke(windowManager, oldRoot);
                    DebugManager.logEvent("OVERLAY", "Full-screen dashboard hidden");
                }
            } catch (Throwable ignored) {
            }
        }

        static String buildBenchText(long nowMs) {
            String rst = PanelRstTest.statusLine(nowMs);
            if (PanelRstTest.isRunning(nowMs)) return rst;
            StringBuilder sb = new StringBuilder(FpsBench.statusLine(nowMs));
            if (!rst.isEmpty()) {
                sb.append("\n").append(rst);
            }
            List<String[]> rec = FpsBench.recent(1); // 8 tuning rows: keep the panel within the screen
            for (int i = 0; i < rec.size(); i++) {
                sb.append("\n").append(rec.get(i)[0]).append("\n   ").append(rec.get(i)[1]);
            }
            return sb.toString();
        }

        private static void setTextIfChanged(Object tv, String text, String[] last, int idx) throws Exception {
            if (tv == null || text.equals(last[idx])) return;
            last[idx] = text;
            tv.getClass().getMethod("setText", CharSequence.class).invoke(tv, text);
        }

        private static void refreshOnUiThread() {
            Object h = uiHandler;
            if (h != null) {
                try {
                    h.getClass().getMethod("removeCallbacks", Runnable.class).invoke(h, REFRESH_TASK);
                } catch (Throwable ignored) {
                }
            }
            if (!wantVisible) return;
            if (rootView == null) {
                showOnUiThread();
                return;
            }
            try {
                TelemetrySnapshot snap = TelemetryCollector.getLatestSnapshot();
                // Safety: never cover the rear camera / reverse view, and stay out of the way while the screen is off
                String hiddenWhy = autoHideReason();
                boolean show = hiddenWhy.isEmpty();
                if (show != lastShownState) {
                    lastShownState = show;
                    rootView.getClass().getMethod("setVisibility", int.class)
                            .invoke(rootView, Integer.valueOf(show ? 0 : 8)); // VISIBLE : GONE
                    DebugManager.logEvent("OVERLAY", show ? "Dashboard visible again" : ("Dashboard auto-hidden: " + hiddenWhy));
                }
                if (show) {
                    long now = System.currentTimeMillis();
                    String extra = "";
                    if (now < bannerUntilMs && bannerMsg != null && !bannerMsg.isEmpty()) {
                        extra = "   |   " + bannerMsg;
                    } else if (OtaUpdateManager.isUpdating()) {
                        extra = "   |   ☁️ " + OtaUpdateManager.getStatusText();
                    }
                    String subtitle = "v" + MONITOR_VERSION + " · "
                            + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(now)) + extra;
                    String[] lastSub = new String[]{lastSubtitle};
                    setTextIfChanged(subtitleTv, subtitle, lastSub, 0);
                    lastSubtitle = lastSub[0];

                    String[] bodies = buildDashboardBodies(snap, collectDashboardStats());
                    for (int i = 0; i < CARD_COUNT; i++) {
                        setTextIfChanged(cardBodyTvs[i], bodies[i], lastCardTexts, i);
                    }
                    for (int i = 0; i < controlValueTvs.length; i++) {
                        setTextIfChanged(controlValueTvs[i], LiveTuning.valueText(i), lastControlTexts, i);
                    }
                    FpsBench.tick(now);
                    setTextIfChanged(benchTv, buildBenchText(now), lastBenchText, 0);
                }
            } catch (Throwable ignored) {
            }
            postDelayedToUi(REFRESH_TASK, REFRESH_MS);
        }
    }

    // =========================================================================
    // 4A-1. LIVE FPS EXPERIMENT PANEL: runtime-tunable values + windowed benchmark
    // =========================================================================

    /**
     * Values the dashboard experiment panel changes at runtime. The render/poll/sender threads read these instead of
     * MonitorConfig, so experiments take effect on the next frame without touching monitor.conf. Only the panel's
     * [저장] button copies them into the config file; a daemon restart otherwise reverts to the saved config.
     */
    public static final class LiveTuning {
        static final int[] QUALITY_STEPS = new int[]{50, 55, 60, 65, 70, 75, 80, 85, 90, 95};
        static final int[] TARGET_FPS_STEPS = new int[]{5, 8, 10, 12, 15, 20, 25};
        static final int[] PANEL_FPS_STEPS = new int[]{0, 10, 15, 20, 25, 30};
        static final int[] POLL_MS_STEPS = new int[]{100, 150, 200, 300, 500, 1000};
        static final String[] ROW_LABELS = new String[]{"JPEG 품질", "목표 FPS", "패널 FPS (0x0F)", "ADAS 도로", "수집 주기", "부분 인코딩", "렌더 방식", "디자인", "화면 회전"};

        static volatile int jpegQuality = 70;
        static volatile int targetFps = 12;
        static volatile int panelFps = 0;
        static volatile boolean roadEnabled = true;
        static volatile int pollMs = 200;
        static volatile boolean incrementalEncode = true;
        /** true: render || encode on two cores (v1.4.4 pipeline); false: render then encode, one core at a time. */
        static volatile boolean parallelPipeline = false;
        /** v1.5.0: Tesla-style HUD (default) vs the classic Chromium 1:1 HUD. */
        static volatile boolean teslaDesign = true;
        /** v1.5.1: panel picture turned 180 degrees (usb_rotation 90 instead of 270) for upside-down mounting. */
        static volatile boolean rotate180 = false;
        /** Set when panelFps changed: the sender thread pushes CMD 0x0F to the connected panel. */
        static volatile boolean panelFpsDirty = false;

        static void loadFrom(MonitorConfig c) {
            jpegQuality = Math.max(50, Math.min(95, c.usbJpegQuality));
            targetFps = Math.max(2, Math.min(25, c.usbFps));
            panelFps = Math.max(0, Math.min(60, c.usbPanelFps));
            roadEnabled = c.hudRoadEnabled;
            pollMs = Math.max(50, Math.min(2000, c.telemetryPollMs));
            incrementalEncode = c.usbIncrementalEncode;
            parallelPipeline = c.renderParallel;
            teslaDesign = c.hudDesignTesla;
            rotate180 = c.usbRotation == 90;
        }

        static void saveTo(MonitorConfig c) {
            c.usbJpegQuality = jpegQuality;
            c.usbFps = targetFps;
            c.usbPanelFps = panelFps;
            c.hudRoadEnabled = roadEnabled;
            c.telemetryPollMs = pollMs;
            c.usbIncrementalEncode = incrementalEncode;
            c.renderParallel = parallelPipeline;
            c.hudDesignTesla = teslaDesign;
            c.usbRotation = rotate180 ? 90 : 270;
        }

        /** Portrait rotation handed to the encoder (270 = normal, 90 = picture turned 180 degrees). */
        static int panelRotation() {
            return rotate180 ? 90 : 270;
        }

        /** Next value above (dir > 0) / below (dir < 0) cur in the step list; stays at the ends. */
        static int step(int[] list, int cur, int dir) {
            if (dir > 0) {
                for (int i = 0; i < list.length; i++) {
                    if (list[i] > cur) return list[i];
                }
                return Math.max(cur, list[list.length - 1]);
            }
            for (int i = list.length - 1; i >= 0; i--) {
                if (list[i] < cur) return list[i];
            }
            return Math.min(cur, list[0]);
        }

        static String valueText(int row) {
            switch (row) {
                case 0:
                    return String.valueOf(jpegQuality);
                case 1:
                    return String.valueOf(targetFps);
                case 2:
                    return panelFps > 0 ? String.valueOf(panelFps) : "끔";
                case 3:
                    return roadEnabled ? "ON" : "OFF";
                case 4:
                    return pollMs + "ms";
                case 5:
                    return incrementalEncode ? "ON" : "OFF";
                case 6:
                    return parallelPipeline ? "멀티코어" : "싱글코어";
                case 7:
                    return teslaDesign ? "신규" : "기존";
                default:
                    return rotate180 ? "180°" : "기본";
            }
        }

        static String label() {
            return "q" + jpegQuality + " · " + targetFps + "fps · 패널" + (panelFps > 0 ? String.valueOf(panelFps) : "끔")
                    + " · 도로" + (roadEnabled ? "ON" : "OFF") + " · 수집" + pollMs
                    + " · 부분" + (incrementalEncode ? "ON" : "OFF")
                    + " · " + (parallelPipeline ? "멀티코어" : "싱글코어")
                    + " · " + (teslaDesign ? "신규" : "기존")
                    + (rotate180 ? " · 180°" : "");
        }

        /** Panel [－]/[＋] handler: applies immediately and restarts the measurement for the new combination. */
        static void adjust(int row, int dir) {
            switch (row) {
                case 0:
                    jpegQuality = step(QUALITY_STEPS, jpegQuality, dir);
                    break;
                case 1:
                    targetFps = step(TARGET_FPS_STEPS, targetFps, dir);
                    break;
                case 2: {
                    int next = step(PANEL_FPS_STEPS, panelFps, dir);
                    if (next != panelFps) {
                        panelFps = next;
                        panelFpsDirty = true;
                    }
                    break;
                }
                case 3:
                    roadEnabled = !roadEnabled;
                    break;
                case 4:
                    pollMs = step(POLL_MS_STEPS, pollMs, dir);
                    break;
                case 5:
                    incrementalEncode = !incrementalEncode;
                    break;
                case 6:
                    parallelPipeline = !parallelPipeline;
                    break;
                case 7:
                    teslaDesign = !teslaDesign;
                    break;
                default:
                    rotate180 = !rotate180;
                    break;
            }
            FpsBench.restart(label(), System.currentTimeMillis());
        }
    }

    /**
     * v1.4.4: checks whether the TURZX panel's hardware JPEG decoder honours restart markers (DRI/RSTn) - the
     * prerequisite for an encoder that re-compresses only the changed 16-row MCU strips. Shows three bundled
     * 464x1920 frames (tools/make_panel_test_jpegs.py) for PHASE_MS each instead of the HUD:
     *   A = no restart markers (control), B = RST after every MCU row, C = RST after every MCU.
     * ACKed / failed sends are counted per phase; the visual verdict (B and C identical to A?) comes from the driver.
     */
    public static final class PanelRstTest {
        static final String[] ASSETS = {"panel_test_a_base.jpg", "panel_test_b_rst_row.jpg", "panel_test_c_rst_mcu.jpg"};
        static final String[] NAMES = {"A 기준(RST 없음)", "B RST 행 단위", "C RST 블록 단위"};
        static final long PHASE_MS = 6000L;

        private static byte[][] frames = null;
        private static long startMs = 0L;
        private static final int[] okCount = new int[3];
        private static final int[] failCount = new int[3];
        private static final double[] ackSumMs = new double[3];
        private static String lastResult = "";

        /** Dashboard button: loads the frames once and starts the A -> B -> C sequence. Returns a banner text. */
        static synchronized String start(long nowMs) {
            if (!TurzxUsbDriver.isConnected()) return "패널 테스트: USB 미연결";
            if (TurzxUsbDriver.getConnectedPid() != TURZX_USB_PID_92) {
                return "패널 테스트: 9.2\"(464×1920) 전용 패턴 · 현재 " + TurzxFrameRenderer.getActivePanelLabel() + " 연결됨";
            }
            if (frames == null) {
                byte[][] f = new byte[ASSETS.length][];
                for (int i = 0; i < ASSETS.length; i++) {
                    f[i] = TurzxFrameRenderer.readJarAssetBytesPublic(ASSETS[i]);
                    if (f[i] == null || f[i].length < 1024) return "패널 테스트: 이미지 누락 (" + ASSETS[i] + ")";
                }
                frames = f;
            }
            beginLocked(nowMs);
            DebugManager.logEvent("RSTTEST", "Started: A(no RST) / B(RST per MCU row) / C(RST per MCU), "
                    + (PHASE_MS / 1000L) + "s each, sizes " + frames[0].length + "/" + frames[1].length + "/" + frames[2].length + "B");
            return "패널 테스트 시작: A→B→C 각 " + (PHASE_MS / 1000L) + "초, 패널 화면을 비교하세요";
        }

        /** Self-test hook: same as start() with given frames and no USB requirement. */
        static synchronized void startForTest(byte[][] testFrames, long nowMs) {
            frames = testFrames;
            beginLocked(nowMs);
        }

        private static void beginLocked(long nowMs) {
            for (int i = 0; i < 3; i++) {
                okCount[i] = 0;
                failCount[i] = 0;
                ackSumMs[i] = 0.0;
            }
            lastResult = "";
            startMs = Math.max(1L, nowMs);
        }

        /** Phase 0..2 while running, -1 otherwise (finishes the test once the last phase has elapsed). */
        static synchronized int phase(long nowMs) {
            if (startMs <= 0L) return -1;
            long el = Math.max(0L, nowMs - startMs);
            int p = (int) (el / PHASE_MS);
            if (p >= ASSETS.length) {
                startMs = 0L;
                lastResult = summaryLocked();
                DebugManager.logEvent("RSTTEST", "Finished: " + lastResult);
                return -1;
            }
            return p;
        }

        static boolean isRunning(long nowMs) {
            return phase(nowMs) >= 0;
        }

        static synchronized byte[] frameFor(int phase) {
            return (frames != null && phase >= 0 && phase < frames.length) ? frames[phase] : null;
        }

        /** Sender thread: result of one test-frame transmission. */
        static synchronized void record(int phase, boolean ok, double ackMs) {
            if (phase < 0 || phase >= 3) return;
            if (ok) {
                okCount[phase]++;
                ackSumMs[phase] += ackMs;
            } else {
                failCount[phase]++;
            }
        }

        private static String summaryLocked() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                if (i > 0) sb.append(" · ");
                sb.append("ABC".charAt(i)).append(' ').append(okCount[i]).append("✓ ").append(failCount[i]).append('✗');
                if (okCount[i] > 0) {
                    sb.append(String.format(Locale.US, " %.0fms", ackSumMs[i] / okCount[i]));
                }
            }
            return sb.toString();
        }

        /** Dashboard experiment panel text: live phase while running, last result afterwards ("" if never run). */
        static synchronized String statusLine(long nowMs) {
            int p = phase(nowMs);
            if (p >= 0) {
                long remainMs = (p + 1) * PHASE_MS - Math.max(0L, nowMs - startMs);
                return "🧪 패널 테스트 " + (p + 1) + "/3 · " + NAMES[p] + " · " + ((remainMs + 999L) / 1000L) + "초\n"
                        + "지금 TURZX 패널 화면을 보세요\n"
                        + "전송 " + okCount[p] + " · 실패 " + failCount[p] + "\n"
                        + "B·C가 A와 똑같이 깨끗하면 = RST 지원";
            }
            return lastResult.isEmpty() ? "" : "🧪 RST 결과: " + lastResult;
        }

        static synchronized String reportLine() {
            if (startMs > 0L) return "running · " + summaryLocked();
            return lastResult.isEmpty() ? "not run" : lastResult;
        }
    }

    /**
     * Windowed benchmark for the experiment panel: 2s warm-up after a change, then 5s of plain averages
     * (the EMA values in /status.json lag several seconds and would blur A/B comparisons).
     */
    /** Daemon process CPU time from /proc/self/stat (utime + stime). 100% = one core fully busy. */
    public static final class ProcCpu {
        static final double CLOCK_TICKS_PER_SEC = 100.0; // Linux USER_HZ (Android: 100)
        private static long lastTicks = -1L;
        private static long lastMs = 0L;
        private static double lastPct = -1.0;

        /** utime + stime in clock ticks, or -1 when /proc is unavailable (host self-test). */
        static long readTicks() {
            return parseTicks(readFirstLineQuick(new File("/proc/self/stat")));
        }

        /** Parses fields 14 (utime) and 15 (stime) after the "(comm)" field, which may contain spaces. */
        static long parseTicks(String stat) {
            if (stat == null) return -1L;
            int rp = stat.lastIndexOf(')');
            if (rp < 0 || rp + 2 > stat.length()) return -1L;
            String[] f = stat.substring(rp + 2).trim().split("\\s+");
            if (f.length < 13) return -1L;
            try {
                return Long.parseLong(f[11]) + Long.parseLong(f[12]);
            } catch (NumberFormatException e) {
                return -1L;
            }
        }

        static double percent(long ticks0, long ticks1, long durMs) {
            if (ticks0 < 0L || ticks1 < ticks0 || durMs <= 0L) return -1.0;
            return (ticks1 - ticks0) * (1000.0 / CLOCK_TICKS_PER_SEC) * 100.0 / durMs;
        }

        /** Live CPU% since the previous call (dashboard refresh, ~500ms); keeps the last value for short gaps. */
        static synchronized double sample(long nowMs) {
            long t = readTicks();
            if (t < 0L) return -1.0;
            if (lastTicks >= 0L && nowMs - lastMs >= 400L) {
                lastPct = percent(lastTicks, t, nowMs - lastMs);
                lastTicks = t;
                lastMs = nowMs;
            } else if (lastTicks < 0L) {
                lastTicks = t;
                lastMs = nowMs;
            }
            return lastPct;
        }
    }

    public static final class FpsBench {
        static final long WARMUP_MS = 2000L;
        static final long MEASURE_MS = 5000L;
        static final int MAX_HISTORY = 8;
        private static final int IDLE = 0;
        private static final int WARMUP = 1;
        private static final int MEASURING = 2;

        private static int phase = IDLE;
        private static long phaseStartMs = 0L;
        private static String label = "";
        private static int frames = 0;
        private static double sumRender, sumEncode, sumTx, sumAck, sumKb, sumPoll;
        private static int nRender, nEncode, nPoll;
        private static long cpuTicks0 = -1L;
        private static final List<String[]> history = new ArrayList<String[]>(); // {label, result}, oldest first

        private static void clearSums() {
            frames = 0;
            sumRender = sumEncode = sumTx = sumAck = sumKb = sumPoll = 0.0;
            nRender = nEncode = nPoll = 0;
        }

        static synchronized void restart(String settingsLabel, long nowMs) {
            phase = WARMUP;
            phaseStartMs = nowMs;
            label = settingsLabel != null ? settingsLabel : "";
            clearSums();
        }

        static synchronized void addRender(double ms) {
            if (phase == MEASURING) {
                sumRender += ms;
                nRender++;
            }
        }

        static synchronized void addEncode(double ms) {
            if (phase == MEASURING) {
                sumEncode += ms;
                nEncode++;
            }
        }

        static synchronized void addPoll(double ms) {
            if (phase == MEASURING) {
                sumPoll += ms;
                nPoll++;
            }
        }

        /** One panel frame delivered + ACKed. */
        static synchronized void addFrame(double txMs, double ackMs, double kb) {
            if (phase == MEASURING) {
                frames++;
                sumTx += txMs;
                sumAck += ackMs;
                sumKb += kb;
            }
        }

        static String formatResult(int frameCount, long durMs, double render, double encode, double tx, double ack,
                                   double kb, double poll, double cpuPct) {
            if (frameCount <= 0) return "→ 전송된 프레임 없음 (USB 미연결?)";
            // R=render E=encode T=USB transfer A=panel ACK P=telemetry poll (ms, averages over the window)
            // CPU = daemon process CPU over the window, 100% = one core (4 cores = 400%)
            return String.format(Locale.US, "→ %.1f FPS | R%.0f E%.0f T%.0f A%.0f P%.0f | %.0fKB",
                    frameCount * 1000.0 / Math.max(1L, durMs), render, encode, tx, ack, poll, kb)
                    + (cpuPct >= 0.0 ? String.format(Locale.US, " | CPU %.0f%%", cpuPct) : "");
        }

        /** Advances warm-up -> measuring -> finished. Returns the finished result line (for logging) or null. */
        static String tick(long nowMs) {
            String done = null;
            String doneLabel = null;
            synchronized (FpsBench.class) {
                if (phase == WARMUP && nowMs - phaseStartMs >= WARMUP_MS) {
                    phase = MEASURING;
                    phaseStartMs = nowMs;
                    clearSums();
                    cpuTicks0 = ProcCpu.readTicks();
                } else if (phase == MEASURING && nowMs - phaseStartMs >= MEASURE_MS) {
                    done = formatResult(frames, nowMs - phaseStartMs,
                            nRender > 0 ? sumRender / nRender : 0.0,
                            nEncode > 0 ? sumEncode / nEncode : 0.0,
                            frames > 0 ? sumTx / frames : 0.0,
                            frames > 0 ? sumAck / frames : 0.0,
                            frames > 0 ? sumKb / frames : 0.0,
                            nPoll > 0 ? sumPoll / nPoll : 0.0,
                            ProcCpu.percent(cpuTicks0, ProcCpu.readTicks(), nowMs - phaseStartMs));
                    doneLabel = label;
                    history.add(new String[]{label, done});
                    while (history.size() > MAX_HISTORY) history.remove(0);
                    phase = IDLE;
                }
            }
            if (done != null) {
                try {
                    DebugManager.logEvent("BENCH", doneLabel + " " + done);
                } catch (Throwable ignored) {
                }
            }
            return done;
        }

        static synchronized String statusLine(long nowMs) {
            if (phase == WARMUP) {
                return String.format(Locale.US, "안정화 중... %.1fs", Math.max(0L, WARMUP_MS - (nowMs - phaseStartMs)) / 1000.0);
            }
            if (phase == MEASURING) {
                return String.format(Locale.US, "측정 중... %.1f / %.0fs (프레임 %d)",
                        (nowMs - phaseStartMs) / 1000.0, MEASURE_MS / 1000.0, frames);
            }
            return history.isEmpty() ? "값을 바꾸면 자동으로 7초간 측정합니다" : "측정 완료 · 값을 바꾸거나 [측정]";
        }

        /** Last n results, newest first: each as two lines {label, result}. */
        static synchronized List<String[]> recent(int n) {
            List<String[]> out = new ArrayList<String[]>();
            for (int i = history.size() - 1; i >= 0 && out.size() < n; i--) out.add(history.get(i));
            return out;
        }
    }

    // =========================================================================
    // 4A-2. MAP HARD-KEY LONG PRESS WATCHER (raw evdev, non-exclusive)
    // =========================================================================

    /**
     * Toggles the full-screen dashboard when the MAP hard key is held for 3 seconds.
     * Reads /dev/input/eventN of the D-Audio key devices directly (the daemon runs as root). The devices are NOT
     * grabbed, so Android still receives every key normally (a MAP press keeps opening navigation as before).
     * The MAP key code is resolved from /system/usr/keylayout/daudio*.kl (label SHORTCUT_MAP / *_MAP), or forced
     * with overlay_hotkey_code in monitor.conf.
     */
    public static final class HotkeyWatcher {
        static final long LONG_PRESS_MS = 3000L;
        static final long STUCK_RESET_MS = 20000L; // missed key-up safety
        private static final int EV_KEY = 1;
        private static final int INPUT_EVENT_SIZE = 16; // 32-bit userspace struct input_event
        private static final int MAX_RECENT_KEYS = 6;

        private static volatile boolean started = false;
        private static volatile String info = "not started";
        private static volatile int[] mapCodes = new int[0];
        private static final LongPressDetector detector = new LongPressDetector(LONG_PRESS_MS, STUCK_RESET_MS);
        private static final List<String> recentKeys = new ArrayList<String>();
        private static final HashMap<String, Long> keyDownMs = new HashMap<String, Long>();
        private static final HashMap<String, Thread> readers = new HashMap<String, Thread>();

        /** Pure long-press state machine (time injected for self-tests). */
        static final class LongPressDetector {
            private final long holdMs;
            private final long stuckMs;
            private long downSinceMs = 0L;
            private boolean fired = false;

            LongPressDetector(long holdMs, long stuckMs) {
                this.holdMs = holdMs;
                this.stuckMs = stuckMs;
            }

            /** down=true from an autorepeat (evdev value 2) or an unknown source: only starts timing when idle. */
            synchronized void onKey(boolean down, long nowMs) {
                onKey(down, true, nowMs);
            }

            /**
             * repeat=false is a fresh press (evdev value 1): it restarts the hold timer even if the previous key-up
             * was lost (v1.4.5: a missed key-up used to block long presses until the 20s stuck reset).
             */
            synchronized void onKey(boolean down, boolean repeat, long nowMs) {
                if (down) {
                    if (downSinceMs == 0L || !repeat) {
                        downSinceMs = Math.max(1L, nowMs);
                        fired = false;
                    }
                } else {
                    downSinceMs = 0L;
                    fired = false;
                }
            }

            /** True exactly once per press, as soon as the key has been held for holdMs (fires while still held). */
            synchronized boolean poll(long nowMs) {
                if (downSinceMs == 0L) return false;
                long held = nowMs - downSinceMs;
                if (held > stuckMs || held < 0L) {
                    downSinceMs = 0L;
                    fired = false;
                    return false;
                }
                if (!fired && held >= holdMs) {
                    fired = true;
                    return true;
                }
                return false;
            }
        }

        public static String getInfo() {
            return info;
        }

        public static String getRecentKeysText() {
            synchronized (recentKeys) {
                if (recentKeys.isEmpty()) return "-";
                StringBuilder sb = new StringBuilder();
                for (int i = recentKeys.size() - 1; i >= 0; i--) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(recentKeys.get(i));
                }
                return sb.toString();
            }
        }

        /** Parses /proc/bus/input/devices -> [name, "/dev/input/eventN"] for keyboard-class D-Audio devices. */
        static List<String[]> parseInputDevices(String procText) {
            List<String[]> out = new ArrayList<String[]>();
            if (procText == null) return out;
            String name = null;
            String handlers = null;
            String[] lines = (procText + "\n\n").split("\n");
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i].trim();
                if (l.isEmpty()) {
                    if (name != null && handlers != null) {
                        String ev = null;
                        boolean kbd = false;
                        String[] hs = handlers.split("\\s+");
                        for (int j = 0; j < hs.length; j++) {
                            if ("kbd".equals(hs[j])) kbd = true;
                            if (hs[j].startsWith("event")) ev = hs[j];
                        }
                        String lower = name.toLowerCase(Locale.US);
                        if (kbd && ev != null && lower.startsWith("daudio")) {
                            out.add(new String[]{name, "/dev/input/" + ev});
                        }
                    }
                    name = null;
                    handlers = null;
                } else if (l.startsWith("N: Name=")) {
                    name = l.substring("N: Name=".length()).replace("\"", "").trim();
                } else if (l.startsWith("H: Handlers=")) {
                    handlers = l.substring("H: Handlers=".length()).trim();
                }
            }
            return out;
        }

        static boolean isMapLabel(String label) {
            if (label == null) return false;
            String u = label.toUpperCase(Locale.US);
            return "MAP".equals(u) || u.endsWith("_MAP") || u.startsWith("MAP_");
        }

        /** Linux key codes whose .kl label is a MAP key ("key 123 SHORTCUT_MAP"). */
        static List<Integer> parseMapCodesFromKl(String klText) {
            List<Integer> out = new ArrayList<Integer>();
            if (klText == null) return out;
            String[] lines = klText.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i].trim();
                if (!l.startsWith("key ")) continue;
                String[] t = l.split("\\s+");
                if (t.length < 3 || "usage".equals(t[1])) continue;
                int code;
                try {
                    code = t[1].startsWith("0x") ? Integer.parseInt(t[1].substring(2), 16) : Integer.parseInt(t[1]);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (isMapLabel(t[2]) && !out.contains(Integer.valueOf(code))) out.add(Integer.valueOf(code));
            }
            return out;
        }

        /** True when a .kl file belongs to one of the devices (daudio_..._de22ev_base.kl <-> daudio_..._de22ev_kr). */
        static boolean klMatchesDevice(String klFileName, List<String[]> devices) {
            String stem = klFileName;
            if (stem.endsWith(".kl")) stem = stem.substring(0, stem.length() - 3);
            if (stem.endsWith("_base")) stem = stem.substring(0, stem.length() - 5);
            for (int i = 0; i < devices.size(); i++) {
                if (devices.get(i)[0].startsWith(stem)) return true;
            }
            return false;
        }

        private static int[] resolveMapCodes(List<String[]> devices, StringBuilder why) {
            int forced = TurzxOverlayController.getConfig().overlayHotkeyCode;
            if (forced > 0) {
                why.append("forced code=").append(forced);
                return new int[]{forced};
            }
            File[] kls = new File("/system/usr/keylayout").listFiles();
            List<Integer> matched = new ArrayList<Integer>();
            List<Integer> anyDaudio = new ArrayList<Integer>();
            String matchedFiles = "";
            if (kls != null) {
                for (int i = 0; i < kls.length; i++) {
                    String n = kls[i].getName();
                    if (!n.startsWith("daudio") || !n.endsWith(".kl")) continue;
                    List<Integer> codes = parseMapCodesFromKl(readAllTextQuick(kls[i]));
                    for (int j = 0; j < codes.size(); j++) {
                        if (!anyDaudio.contains(codes.get(j))) anyDaudio.add(codes.get(j));
                    }
                    if (klMatchesDevice(n, devices)) {
                        matchedFiles += (matchedFiles.isEmpty() ? "" : "+") + n;
                        for (int j = 0; j < codes.size(); j++) {
                            if (!matched.contains(codes.get(j))) matched.add(codes.get(j));
                        }
                    }
                }
            }
            List<Integer> use = !matched.isEmpty() ? matched : anyDaudio;
            why.append(!matched.isEmpty() ? matchedFiles : (anyDaudio.isEmpty() ? "no MAP label in daudio*.kl" : "all daudio*.kl"));
            int[] arr = new int[use.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = use.get(i).intValue();
            return arr;
        }

        public static synchronized void start() {
            if (started) return;
            started = true;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    runWatchLoop();
                }
            }, "Turzx-Hotkey");
            t.setDaemon(true);
            t.start();
        }

        private static void runWatchLoop() {
            long lastScanMs = 0L;
            while (true) {
                try {
                    long now = System.currentTimeMillis();
                    if (now - lastScanMs >= 15000L || lastScanMs == 0L) {
                        lastScanMs = now;
                        scanAndStartReaders();
                    }
                    if (detector.poll(System.currentTimeMillis())) {
                        DebugManager.logEvent("HOTKEY", "MAP held " + (LONG_PRESS_MS / 1000) + "s -> toggle full-screen dashboard");
                        TurzxOverlayController.toggle("MAP");
                    }
                    Thread.sleep(100L);
                } catch (Throwable t) {
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }

        private static void scanAndStartReaders() {
            List<String[]> devices = parseInputDevices(readAllTextQuick(new File("/proc/bus/input/devices")));
            StringBuilder why = new StringBuilder();
            int[] codes = resolveMapCodes(devices, why);
            boolean changed = codes.length != mapCodes.length;
            for (int i = 0; !changed && i < codes.length; i++) changed = codes[i] != mapCodes[i];
            mapCodes = codes;

            StringBuilder devText = new StringBuilder();
            synchronized (readers) {
                for (int i = 0; i < devices.size(); i++) {
                    final String devName = devices.get(i)[0];
                    final String path = devices.get(i)[1];
                    if (devText.length() > 0) devText.append(", ");
                    devText.append(path.substring(path.lastIndexOf('/') + 1));
                    Thread existing = readers.get(path);
                    if (existing != null && existing.isAlive()) continue;
                    Thread r = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            readDevice(devName, path);
                        }
                    }, "Turzx-Key-" + path.substring(path.lastIndexOf('/') + 1));
                    r.setDaemon(true);
                    readers.put(path, r);
                    r.start();
                }
            }
            String codeText = codes.length == 0 ? "MAP 코드 미확인" : ("MAP 코드 " + joinInts(codes));
            String newInfo = devices.isEmpty()
                    ? "입력 장치 없음 (/proc/bus/input/devices)"
                    : (codeText + " · " + devText + " (" + why + ")");
            if (changed || !newInfo.equals(info)) {
                DebugManager.logEvent("HOTKEY", "MAP long-press watcher: " + newInfo);
            }
            info = newInfo;
        }

        private static String joinInts(int[] a) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append("/");
                sb.append(a[i]);
            }
            return sb.toString();
        }

        private static boolean isMapCode(int code) {
            int[] c = mapCodes;
            for (int i = 0; i < c.length; i++) {
                if (c[i] == code) return true;
            }
            return false;
        }

        private static void readDevice(String devName, String path) {
            FileInputStream in = null;
            String shortDev = path.substring(path.lastIndexOf('/') + 1);
            try {
                in = new FileInputStream(path);
                byte[] buf = new byte[INPUT_EVENT_SIZE * 16];
                int have = 0;
                while (true) {
                    int n = in.read(buf, have, buf.length - have);
                    if (n <= 0) break;
                    have += n;
                    int off = 0;
                    while (have - off >= INPUT_EVENT_SIZE) {
                        int type = (buf[off + 8] & 0xFF) | ((buf[off + 9] & 0xFF) << 8);
                        int code = (buf[off + 10] & 0xFF) | ((buf[off + 11] & 0xFF) << 8);
                        int value = (buf[off + 12] & 0xFF) | ((buf[off + 13] & 0xFF) << 8)
                                | ((buf[off + 14] & 0xFF) << 16) | ((buf[off + 15] & 0xFF) << 24);
                        if (type == EV_KEY) {
                            onKeyEvent(shortDev, code, value);
                        }
                        off += INPUT_EVENT_SIZE;
                    }
                    if (off > 0) {
                        System.arraycopy(buf, off, buf, 0, have - off);
                        have -= off;
                    }
                }
            } catch (Throwable t) {
                DebugManager.logEvent("HOTKEY", "Key reader stopped on " + path + " (" + devName + "): "
                        + t.getClass().getSimpleName());
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        private static void onKeyEvent(String dev, int code, int value) {
            long now = System.currentTimeMillis();
            boolean map = isMapCode(code);
            if (map && (value == 1 || value == 2)) detector.onKey(true, value == 2, now);
            else if (map && value == 0) detector.onKey(false, false, now);

            // Recent key log (debug report / dashboard) so an unknown MAP code can be identified on the car
            String key = dev + ":" + code;
            if (value == 1) {
                synchronized (keyDownMs) {
                    keyDownMs.put(key, Long.valueOf(now));
                }
            } else if (value == 0) {
                Long down;
                synchronized (keyDownMs) {
                    down = keyDownMs.remove(key);
                }
                String entry = dev + " code " + code + (map ? "(MAP)" : "")
                        + (down != null ? " " + (now - down.longValue()) + "ms" : "");
                if (map && down != null) {
                    long held = now - down.longValue();
                    if (held >= 1000L && held < LONG_PRESS_MS) {
                        DebugManager.logEvent("HOTKEY", "MAP released after " + held + "ms (< "
                                + (LONG_PRESS_MS / 1000) + "s) -> no toggle");
                    }
                }
                synchronized (recentKeys) {
                    recentKeys.add(entry);
                    while (recentKeys.size() > MAX_RECENT_KEYS) recentKeys.remove(0);
                }
            }
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

        /** Asks turzx-daemon.sh to copy dmesg / USB device list / rotated logs to the USB stick once (picked up within ~5s). */
        public static void requestDaemonUsbLogSync() {
            try {
                writeBytesNoSync(new File(USB_LOG_SYNC_REQUEST_PATH), String.valueOf(System.currentTimeMillis()).getBytes(UTF8));
            } catch (Throwable t) {
                logError("DEBUG", "requestDaemonUsbLogSync", t);
            }
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
                        requestDaemonUsbLogSync();
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
                        .append(", usb_panel_fps=").append(cfg.usbPanelFps)
                        .append(", overlay_hotkey_code=").append(cfg.overlayHotkeyCode)
                        .append(", hud_road=").append(cfg.hudRoadEnabled)
                        .append(", usb_incremental_encode=").append(cfg.usbIncrementalEncode)
                        .append(", render_parallel=").append(cfg.renderParallel)
                        .append(", hud_design=").append(cfg.hudDesignTesla ? "tesla" : "classic")
                        .append(", telemetry_poll_ms=").append(cfg.telemetryPollMs)
                        .append(", comma_ip=").append(cfg.commaIp)
                        .append(", auto_update=").append(cfg.autoUpdate)
                        .append(", pos=(").append(cfg.x).append(",").append(cfg.y).append(")\n");
            }

            sb.append("\n--- [2] TURZX USB Driver & /sys/bus/usb/devices Scan ---\n");
            sb.append("USB Connected   : ").append(TurzxUsbDriver.isConnected()).append("\n");
            sb.append("Active Panel    : ").append(TurzxFrameRenderer.getActivePanelLabel())
                    .append(" (1cbe:").append(TurzxUsbDriver.getConnectedPidHex())
                    .append(", ").append(TurzxFrameRenderer.getActiveResolutionString())
                    .append(", fw=").append(TurzxUsbDriver.getConnectedFirmware()).append(")\n");
            sb.append("USB Status      : ").append(TurzxUsbDriver.getStatusText()).append("\n");
            sb.append("Pushed Frames   : ").append(TurzxUsbDriver.getPushedFrames())
                    .append(String.format(Locale.US, " (Live FPS: %.1f)\n", TurzxUsbDriver.getLiveFps()));
            sb.append("JPEG Encoder    : ").append(TurzxFrameRenderer.getLastEncoderName())
                    .append(" | native=").append(NativeJpeg.getInfo())
                    .append(String.format(Locale.US, " | render %.1fms (road %.1fms), encode %.1fms (avg, parallel threads)\n",
                            TurzxFrameRenderer.getAvgRenderMs(), TurzxFrameRenderer.getAvgRoadMs(),
                            TurzxFrameRenderer.getAvgEncodeMs()));
            sb.append("Incremental     : ").append(LiveTuning.incrementalEncode ? "on" : "off")
                    .append(" | available=").append(NativeJpeg.isIncrementalAvailable())
                    .append(" | active=").append(TurzxFrameRenderer.isIncrementalActive())
                    .append(" | re-encoded MCU rows (avg) ").append(TurzxFrameRenderer.getDirtyRowsText()).append("\n");
            sb.append(String.format(Locale.US,
                    "Pipeline (avg)  : poll %.1fms | usb tx %.1fms | panel ack %.1fms | slot wait %.1fms | encoder wait %.1fms | jpeg %.1fKB\n",
                    TurzxUsbDriver.getAvgPollMs(), TurzxUsbDriver.getAvgTxMs(), TurzxUsbDriver.getAvgAckMs(),
                    TurzxUsbDriver.getAvgSlotWaitMs(), TurzxUsbDriver.getAvgEncodeWaitMs(), TurzxUsbDriver.getAvgJpegKb()));
            sb.append("MAP Hotkey      : ").append(HotkeyWatcher.getInfo())
                    .append(" | dashboard=").append(TurzxOverlayController.isDashboardVisible() ? "shown" : "hidden").append("\n");
            sb.append("Recent Keys     : ").append(HotkeyWatcher.getRecentKeysText()).append("\n");
            sb.append("Live Tuning     : ").append(LiveTuning.label()).append("\n");
            sb.append("HUD Design      : ").append(LiveTuning.teslaDesign ? "tesla" : "classic")
                    .append(" | tesla assets ready=").append(TurzxFrameRenderer.TeslaHud.isReady())
                    .append(" | headLampStatus=").append(TelemetryCollector.getLatestSnapshot().headLampStatus).append("\n");
            sb.append("Panel RST test  : ").append(PanelRstTest.reportLine()).append("\n");
            List<String[]> bench = FpsBench.recent(FpsBench.MAX_HISTORY);
            for (int i = bench.size() - 1; i >= 0; i--) {
                sb.append("  FPS bench     : ").append(bench.get(i)[0]).append("  ").append(bench.get(i)[1]).append("\n");
            }
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
                sb.append("  bcmService          : ").append(TelemetryCollector.bcmService != null ? TelemetryCollector.bcmService.getClass().getName() : "null")
                        .append(" (listenerReg=").append(TelemetryCollector.bcmListenerRegistered)
                        .append(", lastCb=").append(TelemetryCollector.lastRawBcmHeatingCallback).append(")\n");
                sb.append("  hscanMicomService   : ").append(TelemetryCollector.hscanMicomService != null ? TelemetryCollector.hscanMicomService.getClass().getName() : "null")
                        .append(" (listenerReg=").append(TelemetryCollector.hscanListenerRegistered).append(")\n");
                sb.append("  clusterService      : ").append(TelemetryCollector.clusterService != null ? TelemetryCollector.clusterService.getClass().getName() : "null")
                        .append(" (lastMode=").append(TelemetryCollector.lastClusterMediaMode).append(")\n");
                TelemetrySnapshot mt = TelemetryCollector.getLatestSnapshot();
                sb.append("  mediaTiming         : pos=").append(mt.mediaPositionSec).append("s dur=").append(mt.mediaDurationSec)
                        .append("s playing=").append(mt.mediaPlaying)
                        .append(" periodic=").append(TelemetryCollector.periodicPosReporter)
                        .append(" stalled=").append(TelemetryCollector.playbackStalled)
                        .append(" lastExtPos=").append(TelemetryCollector.lastExternalPosSec)
                        .append(" modeSource=").append(TelemetryCollector.modeSourceName).append("\n");
                sb.append("  clusterPlaytime     : ").append(TelemetryCollector.clusterPlaytimeInfo).append("\n");
                synchronized (TelemetryCollector.broadcastUnitMsBySource) {
                    sb.append("  broadcastUnitMs     : ").append(TelemetryCollector.broadcastUnitMsBySource).append("\n");
                }
                sb.append("  albumArt            : ").append(mt.albumArtSource).append("\n");
                sb.append("  powerService        : ").append(TelemetryCollector.powerService != null ? TelemetryCollector.powerService.getClass().getName() : "null")
                        .append(" (").append(TelemetryCollector.lastRawPowerStateInfo).append(")\n");
                sb.append("  modeService         : ").append(TelemetryCollector.modeService != null ? TelemetryCollector.modeService.getClass().getName() : "null").append("\n");
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
                sb.append("  lastRawDatcIntake   : ").append(TelemetryCollector.lastRawDatcIntakeInfo).append("\n");
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
                sb.append("\n--- [3D] Raw Media & Broadcast Scan (AudioService / ClusterService / AvWidgetMainView) ---\n");
                sb.append("  mediaBroadcastReg   : ").append(TelemetryCollector.mediaBroadcastRegistered).append("\n");
                sb.append("  lastMediaWidgetDump : ").append(TelemetryCollector.lastRawMediaBroadcastDump).append("\n");
                sb.append("  lastMediaLogLine    : ").append(TelemetryCollector.lastRawMediaLogInfo).append("\n");
                sb.append("  lastRcClient        : ").append(TelemetryCollector.lastRawRcClientInfo).append("\n");
                sb.append("  lastRcPlaybackState : ").append(TelemetryCollector.lastRawRcStateInfo).append("\n");
                sb.append("  lastRcBundle        : ").append(TelemetryCollector.lastRawRcBundleDump).append("\n");
                if (TelemetryCollector.clusterService != null) {
                    appendZeroArgMethodDump(sb, "  clusterService.0arg : ", TelemetryCollector.clusterService);
                    appendObjectFieldsDump(sb, "  clusterService flds : ", TelemetryCollector.clusterService);
                }
                if (new File("/system/bin/sh").isFile()) {
                    String grepCmd = new File("/system/bin/busybox").canExecute() ? "/system/bin/busybox grep" : "grep";
                    String headCmd = new File("/system/bin/busybox").canExecute() ? "/system/bin/busybox head" : "head";
                    sb.append("  [dumpsys audio (RemoteControl / Focus)]:\n");
                    sb.append(runQuickShellCmd("dumpsys audio 2>/dev/null | " + grepCmd + " -iE -A 2 -B 1 'RemoteControl|Focus|Client|Package|Media|Player' | " + headCmd + " -n 60", "    "));
                    sb.append("  [Sticky & Recent Media/Vehicle Broadcasts]:\n");
                    sb.append(runQuickShellCmd("dumpsys activity broadcasts 2>/dev/null | " + grepCmd + " -iE -A 4 'hkmc|media|music|avrcp|bt|cluster|datc|hev|title|artist|play' | " + headCmd + " -n 90", "    "));
                    sb.append("  [Binder Services (hkmc / media / audio / cluster)]:\n");
                    sb.append(runQuickShellCmd("service list 2>/dev/null | " + grepCmd + " -iE 'hkmc|micom|datc|hev|hscan|cluster|audio|media|tm_' | " + headCmd + " -n 50", "    "));
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
                    try {
                        File jarBakFile = new File(installDir, "bin/turzxmonitor.jar.bak");
                        writeBytesAtomic(jarBakFile, newJarBytes);
                        jarBakFile.setReadable(true, false);
                    } catch (Throwable ignored) {
                    }
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
                try {
                    writeBytesAtomic(new File(OTA_RELOAD_TRIGGER_PATH), ("v" + rVer + "\n").getBytes(UTF8));
                } catch (Throwable ignored) {
                }
                clearDalvikCacheAndSync();
                otaStatusText = "v" + rVer + " 적용 완료 · 재시작 중";
                TurzxOverlayController.showBannerMessage("✅ v" + rVer + " 업데이트 완료! 재시작합니다...", 5000L);
                DebugManager.logEvent("OTA", "Successfully updated to v" + rVer + "; triggering hot restart");

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

        private static void clearDalvikCacheAndSync() {
            try {
                String[] cacheDirs = new String[]{"/data/dalvik-cache", "/data/local/tmp/dalvik-cache"};
                for (int d = 0; d < cacheDirs.length; d++) {
                    File dir = new File(cacheDirs[d]);
                    File[] files = dir.listFiles();
                    if (files == null) continue;
                    for (int i = 0; i < files.length; i++) {
                        String n = files[i].getName();
                        if (n.contains("turzxmonitor.jar")) {
                            files[i].delete();
                        }
                    }
                }
                Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sync"});
                p.waitFor();
            } catch (Throwable ignored) {
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

        public static List<String> resolveHostViaPublicDns(String host) {
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

            if (GITHUB_RAW_HOST.equalsIgnoreCase(host)) {
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
                    target.steeringAngleDeg = steerDeg;
                    if (target.pathPoly == null || target.pathPoly.length < 4) {
                        target.pathPoly = new float[4];
                    }
                    target.pathPoly[2] = -steerDeg * 0.000025f;
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
            case 9: { // modelV2 (3D trajectory position.x / position.y and laneLineProbs)
                int end = base + len;
                int p = base + 16; // after 8B header + frameId(u32) + frameIdExtra(u32)
                if (p + 2 > end) return false;
                int nx = bb.getShort(p) & 0xFFFF;
                p += 2;
                if (nx <= 0 || nx > 64 || p + nx * 2 + 2 > end) return false;
                int xStart = p;
                p += nx * 2;
                int ny = bb.getShort(p) & 0xFFFF;
                p += 2;
                if (ny <= 0 || ny > 64 || p + ny * 2 > end) return false;
                int yStart = p;
                p += ny * 2;

                int n = Math.min(nx, ny);
                if (n >= 6) {
                    int k1 = Math.max(1, n / 3);
                    int k2 = Math.max(k1 + 1, (n * 2) / 3);
                    float x1 = (bb.getShort(xStart + k1 * 2) & 0xFFFF) * 0.01f;
                    float x2 = (bb.getShort(xStart + k2 * 2) & 0xFFFF) * 0.01f;
                    float y0 = bb.getShort(yStart) * 0.001f;
                    float dy1 = (bb.getShort(yStart + k1 * 2) * 0.001f) - y0;
                    float dy2 = (bb.getShort(yStart + k2 * 2) * 0.001f) - y0;
                    if (x1 > 2.0f && x2 > x1 + 2.0f) {
                        float c2 = ((dy2 / x2) - (dy1 / x1)) / (x2 - x1);
                        float c1 = (dy1 / x1) - c2 * x1;
                        if (!Float.isNaN(c1) && !Float.isNaN(c2) && Math.abs(c1) < 1.0f && Math.abs(c2) < 0.05f) {
                            if (target.pathPoly == null || target.pathPoly.length < 4) {
                                target.pathPoly = new float[4];
                            }
                            target.pathPoly[0] = y0;
                            target.pathPoly[1] = c1;
                            target.pathPoly[2] = c2;
                        }
                    }
                }
                // Advance past position.z (i16_mm_list), velocity.x (i16_cm_list), and laneLines to read laneLineProbs
                for (int skipIdx = 0; skipIdx < 2 && p + 2 <= end; skipIdx++) {
                    int cnt = bb.getShort(p) & 0xFFFF;
                    p += 2 + cnt * 2;
                }
                if (p + 2 <= end) {
                    int lineCnt = bb.getShort(p) & 0xFFFF;
                    p += 2;
                    for (int li = 0; li < lineCnt && li < 8 && p + 2 <= end; li++) {
                        for (int axis = 0; axis < 3 && p + 2 <= end; axis++) {
                            int pts = bb.getShort(p) & 0xFFFF;
                            p += 2 + pts * 2;
                        }
                    }
                    if (p + 2 <= end) {
                        int probCnt = bb.getShort(p) & 0xFFFF;
                        p += 2;
                        if (probCnt >= 3 && p + probCnt * 4 <= end) {
                            float pLeft = bb.getFloat(p + 4);
                            float pRight = bb.getFloat(p + 8);
                            float bestProb = Math.max(pLeft, pRight);
                            if (!Float.isNaN(bestProb) && bestProb >= 0.0f && bestProb <= 1.0f) {
                                target.laneConfidencePct = Math.round(bestProb * 100.0f);
                            }
                        }
                    }
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

    /**
     * Decodes TP_DEVICE_FATC (43 -> b3 = 0xAB) UDS 22 01 00 -> 62 01 00 response.
     * Verified on DE22EV live HSCAN dump:
     * hdr=C7,C0,AB len=38 [62 01 00 7E 50 27 C8 FF 82 78 80 03 EF 8D ...]
     * - payload[udsIdx + 8] = 0x82 (130 -> 130 * 0.5 - 40.0 = 25.0°C in-car sensor temperature)
     * - payload[udsIdx + 9] = 0x78 (120 -> 120 * 0.5 - 40.0 = 20.0°C ambient/outdoor sensor temperature)
     */
    public static boolean parseFatcUdsPayload(byte[] payload, TelemetrySnapshot target) {
        if (payload == null || target == null || payload.length < 10) return false;
        int udsIdx = -1;
        int maxScan = Math.min(8, payload.length - 10);
        for (int i = 0; i <= maxScan; i++) {
            if ((payload[i] & 0xFF) == 0x62 && (payload[i + 1] & 0xFF) == 0x01 && (payload[i + 2] & 0xFF) == 0x00) {
                udsIdx = i;
                break;
            }
        }
        if (udsIdx < 0) return false;
        boolean updated = false;
        double inC = parseCanTempByteToCelsius(payload[udsIdx + 8] & 0xFF);
        if (inC >= -35.0 && inC <= 65.0) {
            target.inCarTempC = inC;
            updated = true;
        }
        double outC = parseCanTempByteToCelsius(payload[udsIdx + 9] & 0xFF);
        if (outC >= -45.0 && outC <= 65.0) {
            target.outdoorTempC = outC;
            updated = true;
        }
        return updated;
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
            boolean isFatcAddr = ((b3 & 0x7F) == 0x2B);
            if (isBmsAddr && data.length >= 35) return parseBmsUdsPayload(data, targetIfDirect);
            if (isFatcAddr && data.length >= 10) return parseFatcUdsPayload(data, targetIfDirect);
            if ((b3 & 0x80) == 0 && data.length >= 8) {
                return parseHscanBypassCanFrame(b3 & 0x7F, data, targetIfDirect, 10000L, 0L, 0L);
            }
            return false;
        } else {
            TelemetryCollector.onHscanPacket(b1, b2, b3, data);
            return true;
        }
    }

    public static boolean isValidRemoteCarrotIp(String ip) {
        if (ip == null) return false;
        String s = ip.trim();
        if (s.isEmpty()) return false;
        if (s.startsWith("127.") || "0.0.0.0".equals(s)
                || "192.168.0.1".equals(s) || "192.168.43.1".equals(s)
                || s.endsWith(".255")) {
            return false;
        }
        return true;
    }

    public static String scanWlanArpCarrotIp() {
        File arp = new File("/proc/net/arp");
        if (!arp.isFile()) return "";
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(arp), UTF8));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("IP address")) continue;
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 6) {
                    String ip = parts[0];
                    String flags = parts[2];
                    String dev = parts[5];
                    if ("wlan0".equals(dev) && !"0x0".equals(flags) && isValidRemoteCarrotIp(ip)) {
                        return ip;
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (br != null) { try { br.close(); } catch (Throwable ignored) {} }
        }
        return "";
    }

    /**
     * Parses /data/local/tmp/carrotbridge_status.json written by HKG_5W_CarrotNavi (carrotnavi.bridge):
     * 1. Extracts discoveredCarrotIp (or targets IP on wlan0, e.g. 192.168.0.103) -> target.carrotIp.
     * 2. Extracts lastPacket MNSOFT navigation SDI/TBT fields so genuine headunit speed camera, section
     *    enforcement, road limit, and TBT turn distance/road name are reflected on the HUD.
     */
    public static boolean parseCarrotBridgeStatusJson(String statusJson, TelemetrySnapshot target) {
        if (statusJson == null || target == null || !statusJson.contains("{")) return false;
        boolean updated = false;
        String discIp = extractJsonString(statusJson, "discoveredCarrotIp");
        if (isValidRemoteCarrotIp(discIp)) {
            target.carrotIp = discIp.trim();
            updated = true;
        } else {
            String targetsStr = extractJsonString(statusJson, "targets");
            if (targetsStr != null && !targetsStr.isEmpty()) {
                String[] toks = targetsStr.split("[,\\[\\]\\s]+");
                for (int i = 0; i < toks.length; i++) {
                    if (isValidRemoteCarrotIp(toks[i])) {
                        target.carrotIp = toks[i].trim();
                        updated = true;
                        break;
                    }
                }
            }
        }
        if (parseCarrotManJson(statusJson, target, false)) {
            updated = true;
        }
        return updated;
    }

    public static boolean parseCarrotManJson(String json, TelemetrySnapshot target) {
        return parseCarrotManJson(json, target, true);
    }

    public static boolean parseCarrotManJson(String json, TelemetrySnapshot target, boolean markConnectedIfMatched) {
        if (json == null || target == null || !json.contains("{")) return false;
        boolean matched = false;
        Integer vEgo = extractJsonInt(json, "v_ego_kph");
        if (vEgo == null) vEgo = extractJsonInt(json, "vEgo");
        if (vEgo != null) { target.carrotSpeedKph = vEgo.intValue(); matched = true; }
        Integer nPosSpd = extractJsonInt(json, "nPosSpeed");
        if (vEgo == null && nPosSpd != null && nPosSpd.intValue() >= 0 && nPosSpd.intValue() <= 260 && !target.carrotConnected) {
            target.carrotSpeedKph = nPosSpd.intValue();
            matched = true;
        }
        Integer vCruise = extractJsonInt(json, "v_cruise_kph");
        if (vCruise == null) vCruise = extractJsonInt(json, "vCruise");
        if (vCruise != null) { target.carrotCruiseKph = vCruise.intValue(); matched = true; }
        Boolean active = extractJsonBool(json, "active");
        if (active == null) active = extractJsonBool(json, "activeCarrot");
        if (active != null) { target.carrotActive = active.booleanValue(); matched = true; }

        Integer sdiType = extractJsonInt(json, "sdi_type");
        if (sdiType == null) sdiType = extractJsonInt(json, "xSpdType");
        Integer sdiLim = extractJsonInt(json, "sdi_speed_limit");
        if (sdiLim == null) sdiLim = extractJsonInt(json, "xSpdLimit");
        Integer sdiDist = extractJsonInt(json, "sdi_dist");
        if (sdiDist == null) sdiDist = extractJsonInt(json, "xSpdDist");

        // Support HKG_5W_CarrotNavi (CarrotPacket 7706) SDI fields (nSdiType, nSdiSpeedLimit, nSdiDist, nSdiBlockType, nRoadLimitSpeed)
        Integer nRoadLim = extractJsonInt(json, "nRoadLimitSpeed");
        Integer nSdiType = extractJsonInt(json, "nSdiType");
        Integer nSdiLim = extractJsonInt(json, "nSdiSpeedLimit");
        Integer nSdiDist = extractJsonInt(json, "nSdiDist");
        Integer nBlockType = extractJsonInt(json, "nSdiBlockType");
        Integer nBlockSpd = extractJsonInt(json, "nSdiBlockSpeed");
        Integer nBlockDist = extractJsonInt(json, "nSdiBlockDist");

        if (sdiType != null || sdiLim != null || sdiDist != null) {
            if (sdiType != null) { target.carrotSdiType = sdiType.intValue(); matched = true; }
            if (sdiLim != null && sdiLim.intValue() > 0 && sdiLim.intValue() <= 140) {
                target.carrotSdiSpeedLimit = sdiLim.intValue();
                matched = true;
            } else if (nRoadLim != null && nRoadLim.intValue() > 0 && nRoadLim.intValue() <= 140) {
                target.carrotSdiSpeedLimit = nRoadLim.intValue();
                matched = true;
            }
            if (sdiDist != null && sdiDist.intValue() >= 0) { target.carrotSdiDistM = sdiDist.intValue(); matched = true; }
        } else if (nSdiType != null || nRoadLim != null) {
            int roadLim = (nRoadLim != null && nRoadLim.intValue() > 0 && nRoadLim.intValue() <= 140) ? nRoadLim.intValue() : 0;
            boolean isSection = (nSdiType != null && nSdiType.intValue() == 4) || (nBlockType != null && nBlockType.intValue() > 0);
            int camLim = (nSdiLim != null && nSdiLim.intValue() > 0 && nSdiLim.intValue() <= 135) ? nSdiLim.intValue() : 0;
            int camDist = (nSdiDist != null && nSdiDist.intValue() > 0 && nSdiDist.intValue() < 1023) ? nSdiDist.intValue() : 0;
            if (isSection) {
                int secLim = (nBlockSpd != null && nBlockSpd.intValue() > 0 && nBlockSpd.intValue() <= 135)
                        ? nBlockSpd.intValue() : (camLim > 0 ? camLim : roadLim);
                int secDist = (nBlockDist != null && nBlockDist.intValue() > 0 && nBlockDist.intValue() < 65535)
                        ? nBlockDist.intValue() : Math.max(camDist, 100);
                target.carrotSdiType = 4;
                target.carrotSdiSpeedLimit = secLim;
                target.carrotSdiDistM = secDist;
                matched = true;
            } else if (nSdiType != null && nSdiType.intValue() >= 0 && camLim > 0 && camDist > 0) {
                target.carrotSdiType = (nSdiType.intValue() == 0) ? 1 : nSdiType.intValue();
                target.carrotSdiSpeedLimit = camLim;
                target.carrotSdiDistM = camDist;
                matched = true;
            } else {
                target.carrotSdiType = -1;
                target.carrotSdiSpeedLimit = roadLim;
                target.carrotSdiDistM = 0;
                matched = true;
            }
        }

        Integer tbtDist = extractJsonInt(json, "tbt_dist");
        if (tbtDist == null) tbtDist = extractJsonInt(json, "xDistToTurn");
        if (tbtDist == null) tbtDist = extractJsonInt(json, "nTBTDist");
        if (tbtDist != null && tbtDist.intValue() >= 0) {
            target.carrotTbtDistM = tbtDist.intValue();
            matched = true;
        }
        Integer tbtNext = extractJsonInt(json, "tbt_dist_next");
        if (tbtNext == null) tbtNext = extractJsonInt(json, "nTBTDistNext");
        if (tbtNext != null && tbtNext.intValue() >= 0) {
            target.carrotTbtDistNextM = tbtNext.intValue();
            matched = true;
        }
        String road = extractJsonString(json, "tbt_road");
        if (road == null || road.isEmpty()) road = extractJsonString(json, "szTBTMainText");
        if (road == null || road.isEmpty()) road = extractJsonString(json, "szPosRoadName");
        if (road != null && !road.isEmpty()) { target.carrotTbtRoad = road; matched = true; }
        String ip = extractJsonString(json, "ip");
        if (isValidRemoteCarrotIp(ip)) { target.carrotIp = ip.trim(); matched = true; }
        if (matched && markConnectedIfMatched) target.carrotConnected = true;
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
        Boolean fDef = extractJsonBool(json, "frontDefog");
        if (fDef != null) target.hvacFrontDefog = fDef.booleanValue();
        Boolean rDef = extractJsonBool(json, "rearDefog");
        if (rDef != null) target.hvacRearDefog = rDef.booleanValue();
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
        sb.append(",\"rearDefog\":").append(ev.hvacRearDefog);
        sb.append(",\"steerHeat\":").append(ev.hvacSteerHeat);
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
        sb.append(",\"tbtDistNextM\":").append(ev.carrotTbtDistNextM);
        sb.append(",\"tbtRoad\":\"").append(escapeJson(ev.carrotTbtRoad)).append("\"");
        sb.append(String.format(Locale.US, ",\"steerDeg\":%.1f,\"leadDistM\":%.1f,\"hasLead\":%b",
                ev.steeringAngleDeg, ev.leadDistM, ev.hasLeadCar));
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
        sb.append(",\"hasAlbumArt\":").append(ev.albumArtBitmap != null);
        sb.append(",\"albumArtSource\":\"").append(escapeJson(ev.albumArtSource)).append("\"");
        sb.append("}");
        sb.append(",\"usb\":{");
        sb.append("\"connected\":").append(TurzxUsbDriver.isConnected());
        sb.append(",\"panel\":\"").append(escapeJson(TurzxFrameRenderer.getActivePanelLabel())).append("\"");
        sb.append(",\"pid\":\"").append(escapeJson(TurzxUsbDriver.getConnectedPidHex())).append("\"");
        sb.append(",\"resolution\":\"").append(escapeJson(TurzxFrameRenderer.getActiveResolutionString())).append("\"");
        sb.append(",\"firmware\":\"").append(escapeJson(TurzxUsbDriver.getConnectedFirmware())).append("\"");
        sb.append(",\"frames\":").append(TurzxUsbDriver.getPushedFrames());
        sb.append(String.format(Locale.US, ",\"fps\":%.1f", TurzxUsbDriver.getLiveFps()));
        sb.append(",\"jpeg_encoder\":\"").append(escapeJson(TurzxFrameRenderer.getLastEncoderName())).append("\"");
        sb.append(",\"incremental\":").append(TurzxFrameRenderer.isIncrementalActive());
        sb.append(",\"dirty_rows\":\"").append(TurzxFrameRenderer.getDirtyRowsText()).append("\"");
        sb.append(String.format(Locale.US, ",\"render_ms\":%.1f,\"road_ms\":%.1f,\"encode_ms\":%.1f,\"encode_wait_ms\":%.1f",
                TurzxFrameRenderer.getAvgRenderMs(), TurzxFrameRenderer.getAvgRoadMs(),
                TurzxFrameRenderer.getAvgEncodeMs(), TurzxUsbDriver.getAvgEncodeWaitMs()));
        sb.append(String.format(Locale.US, ",\"poll_ms\":%.1f,\"usb_tx_ms\":%.1f,\"ack_ms\":%.1f,\"slot_wait_ms\":%.1f,\"jpeg_kb\":%.1f",
                TurzxUsbDriver.getAvgPollMs(), TurzxUsbDriver.getAvgTxMs(), TurzxUsbDriver.getAvgAckMs(),
                TurzxUsbDriver.getAvgSlotWaitMs(), TurzxUsbDriver.getAvgJpegKb()));
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

    public static int extractCurrentDteFromFlag38(int raw) {
        if (raw <= 0 || raw == 65534 || raw == 65535) return -1;
        int dte = (raw >>> 8) & 0xFFFF;
        return isValidDteValue(dte) ? dte : -1;
    }

    public static int parseSigned10BitKw(int raw) {
        if (raw < 0 || raw > 1021 || raw == 510 || raw == 511) return 0;
        return ((raw & 0x200) != 0) ? (raw - 1024) : raw;
    }

    public static double resolveDatcTempWithState(float tempC, int stateC) {
        // Genuine com.hkmc.datc.DATCConstants$TemperatureDisplayState:
        // OFF = 0, NOT_USED = 1, SENSOR_ERROR = 254, INVALID = 255, NORMAL = 65535 (0xFFFF)
        if (stateC == 0 || stateC == 1 || stateC == 254 || stateC == 255) {
            return -99.0;
        }
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

    private static float readVrClimateFloat(Object svc, int flag, float defVal) {
        if (svc == null) return defVal;
        try {
            Object v = svc.getClass().getMethod("getVRClimateStatus", int.class).invoke(svc, Integer.valueOf(flag));
            if (v instanceof Number) return ((Number) v).floatValue();
        } catch (Throwable ignored) {
        }
        return defVal;
    }

    public static int readDatcClimateInfoInt(Object svc, int flag, int defVal) {
        if (svc == null) return defVal;
        try {
            Object v = svc.getClass().getMethod("getClimateInfo", int.class).invoke(svc, Integer.valueOf(flag));
            if (v instanceof Number) return ((Number) v).intValue();
        } catch (Throwable ignored) {
        }
        return defVal;
    }

    public static boolean applyDatcStatusObject(Object statusObj, TelemetrySnapshot target) {
        if (statusObj == null || target == null) return false;
        try {
            // Supports both DatcStatus parcelable and IDATCService Binder proxy
            int op = readObjIntAny(statusObj, -1,
                    "getOperationStatus", "getOpStatus", "mOpStatus", "mOperationStatus", "operationStatus");
            int rawLevel = readObjIntAny(statusObj, -1,
                    "getMainBlowerLevel", "getBlowerLevel", "mMainBlowerLevel", "mainBlowerLevel");
            int autoStep = readObjIntAny(statusObj, -1,
                    "getAutoStep", "mAutoStep", "autoStep");
            int mode = readObjIntAny(statusObj, -1,
                    "getModeDrive", "getDriverMode", "getBlowerMode", "mDrMode", "mModeDrive", "modeDrive");
            int auto = readObjIntAny(statusObj, -1,
                    "getAuto", "getAutoStatus", "mAuto", "auto");
            int ac = readObjIntAny(statusObj, -1,
                    "getAirconOnOff", "getACStatus", "getAcOnOff", "getAC", "mAcOnOff", "mAirconOnOff");
            int heat = readObjIntAny(statusObj, -1,
                    "getHeatOnOff", "getHeatStatus", "getHeaterOnOff", "mHeatOnOff");
            int defog = readObjIntAny(statusObj, -1,
                    "getFrontDefrostStatus", "getFrontDefog", "mFrontDefog", "frontDefog");
            int autoDefogBlink = readObjIntAny(statusObj, -1,
                    "getAutoDefogBlink", "mAutoDefogBlink", "autoDefogBlink");
            int intake = readObjIntAny(statusObj, -1,
                    "getIntake", "getIntakeStatus", "getAirIntakeStatus", "getIntakeMode", "mIntake", "mIntakeStatus", "intake");
            if (intake != 0 && intake != 1) {
                int intakeStatus = readObjIntAny(statusObj, -1, "getIntakeStatus", "mIntakeStatus");
                if (intakeStatus == 0 || intakeStatus == 1) {
                    intake = intakeStatus;
                } else {
                    int climateInfoIntake = readDatcClimateInfoInt(statusObj, DATC_CLIMATE_INFO_INTAKE_STATUS, -1);
                    if (climateInfoIntake == 0 || climateInfoIntake == 1) {
                        intake = climateInfoIntake;
                    }
                }
            }
            int sync = readObjIntAny(statusObj, -1,
                    "getSyncDisp", "getSyncStatus", "getSync", "mSyncDisp", "sync");
            int dual = readObjIntAny(statusObj, -1,
                    "getDual", "getDualStatus", "mDual", "dual");
            int drvOnly = readObjIntAny(statusObj, -1,
                    "getDriverOnly", "getDriverOnlyValue", "getDriverOnlyStatus", "mDriverOnly", "driverOnly");
            int ads = readObjIntAny(statusObj, -1,
                    "getADSOnOffStatus", "getADSDisp", "getADSStatus", "mADSOnOffStatus", "mADSDisp");
            int ventMode = readObjIntAny(statusObj, -1,
                    "getVentMode", "mVentMode", "ventMode");
            int zoneType = readObjIntAny(statusObj, -1,
                    "getZoneType", "mZoneType", "zoneType");

            float drC = readObjFloatAny(statusObj, Float.NaN,
                    "getDrTempC", "getDriverTempC", "mDrTempC", "drTempC");
            int drStateC = readObjIntAny(statusObj, 65535,
                    "getDrTempStateC", "getDriverTempStateC", "mDrTempStateC");
            float psC = readObjFloatAny(statusObj, Float.NaN,
                    "getPsTempC", "getPassengerTempC", "mPsTempC", "psTempC");
            int psStateC = readObjIntAny(statusObj, 65535,
                    "getPsTempStateC", "getPassengerTempStateC", "mPsTempStateC");
            float ambC = readObjFloatAny(statusObj, Float.NaN,
                    "getAmbientTempC", "getOutdoorTempC", "mAmbientTempC");
            int ambStateC = readObjIntAny(statusObj, 65535,
                    "getAmbientTempStateC", "mAmbientTempStateC");

            // If statusObj is IDATCService proxy (no DatcStatus getters), query getVRClimateStatus(DATCFlag.*)
            if (op == -1 && rawLevel == -1 && mode == -1 && Float.isNaN(drC)) {
                float vrOp = readVrClimateFloat(statusObj, 12, -1.0f);     // DATCFlag.OP_STATE (12)
                float vrLvl = readVrClimateFloat(statusObj, 5, -1.0f);     // DATCFlag.BLOWER_LEVEL (5)
                float vrMode = readVrClimateFloat(statusObj, 6, -1.0f);    // DATCFlag.BLOWER_MODE (6)
                float vrTemp = readVrClimateFloat(statusObj, 2, Float.NaN);// DATCFlag.TEMP (2)
                if (vrOp < 0.0f && vrLvl < 0.0f && vrMode < 0.0f && Float.isNaN(vrTemp)) {
                    return false;
                }
                if (vrOp >= 0.0f) op = Math.round(vrOp);
                if (vrLvl >= 0.0f) rawLevel = Math.round(vrLvl);
                if (vrMode >= 0.0f) mode = Math.round(vrMode);
                if (!Float.isNaN(vrTemp) && vrTemp > 0.0f) drC = vrTemp;
                float vrAuto = readVrClimateFloat(statusObj, 8, -1.0f);    // DATCFlag.AUTO (8)
                float vrAc = readVrClimateFloat(statusObj, 9, -1.0f);      // DATCFlag.AC (9)
                float vrDefog = readVrClimateFloat(statusObj, 10, -1.0f);  // DATCFlag.FRONT_DEFOG (10)
                float vrIntake = readVrClimateFloat(statusObj, 11, -1.0f); // DATCFlag.INTAKE (11)
                if (vrAuto >= 0.0f) auto = Math.round(vrAuto);
                if (vrAc >= 0.0f) ac = Math.round(vrAc);
                if (vrDefog >= 0.0f) defog = Math.round(vrDefog);
                if (vrIntake >= 0.0f) intake = Math.round(vrIntake);
                if (ventMode == -1) ventMode = 0; // DE22EV Single-Zone default when polling VR
            }

            // Genuine com.hkmc.datc.DATCConstants (verified against automotive.odex & DE22EV live dump):
            // DatcOperationState: OFF = 0, NORMAL = 1 (ON), SYSTEM_OFF = 2 (OFF), SELF_DIAG = 3, AQS = 4, INVALID = 7
            // BaseDisp (Auto/AC/Heat/DrvOnly/Dual/Intake/ADS): OFF = 0, ON = 1, NO_USED = 2, INVALID = 3
            // DatcBlowerLevel: OFF = 0, LEVEL_0 = 1 (0단), LEVEL_1..8 = 2..9 (1..8단), LEVEL_AUTO = 10, INVALID = 15
            // DatcModeDisp: OFF = 0, VENT = 1, BL = 2, FLOOR = 3, MIX = 4, DEF = 5, AUTODEFLOG = 6, DEF_VENT = 7, MODE_AUTO = 8, INVALID = 15
            // TemperatureDisplayState: OFF = 0, NOT_USED = 1, SENSOR_ERROR = 254, INVALID = 255, NORMAL = 65535 (0xFFFF)
            int step = normalizeDatcBlowerStep(rawLevel);
            if ((rawLevel == 10 || step < 0) && autoStep >= 1 && autoStep <= 3) {
                step = autoStep * 2;
            }
            if (step >= 0) {
                target.hvacBlowerStep = step;
            }
            int normMode = normalizeDatcBlowerMode(mode);
            if (normMode >= 0) {
                target.hvacBlowerMode = normMode;
            }
            if (op == 0 || op == 2) {
                target.hvacOn = false;
                target.hvacBlowerStep = 0;
            } else if (op == 1 || op == 4) {
                target.hvacOn = true;
            } else if (step >= 0) {
                target.hvacOn = (step > 0);
            }

            if (auto == 0 || auto == 1) target.hvacAuto = (auto == 1) || (rawLevel == 10) || (mode == 8);
            if (ac == 0 || ac == 1) target.hvacAc = (ac == 1);
            if (heat == 0 || heat == 1) target.hvacHeat = (heat == 1);
            boolean isDefMode = (mode == 5) || (mode == 6) || (mode == 10) || (mode == 14) || (autoDefogBlink == 1);
            if (defog == 0 || defog == 1) {
                target.hvacFrontDefog = (defog == 1) || isDefMode;
            } else if (mode >= 0 && mode != 15) {
                target.hvacFrontDefog = isDefMode;
            }
            if (intake != 0 && intake != 1) {
                int climateInfoIntake = readDatcClimateInfoInt(statusObj, DATC_CLIMATE_INFO_INTAKE_STATUS, -1);
                if (climateInfoIntake == 0 || climateInfoIntake == 1) {
                    intake = climateInfoIntake;
                }
            }
            if (intake == 0 || intake == 1) target.hvacRecirc = (intake == 1);
            if (drvOnly == 0 || drvOnly == 1) target.hvacDriverOnly = (drvOnly == 1);
            if (ads == 0 || ads == 1) target.hvacAds = (ads == 1);

            double drResolved = resolveDatcTempWithState(drC, drStateC);
            if (drResolved > 0.0) {
                target.hvacDrTempC = drResolved;
            }
            double psResolved = resolveDatcTempWithState(psC, psStateC);
            if (ventMode == 0 || zoneType == 0 || psStateC == 1 || psStateC == 255 || psResolved < 0.0) {
                // Single-Zone (VentMode.SINGLE = 0, ZoneType.ZONE_1 = 0, or psStateC = 255 INVALID on DE22EV): mirror driver temp
                target.hvacSync = true;
                if (target.hvacDrTempC > 0.0) {
                    target.hvacPsTempC = target.hvacDrTempC;
                }
            } else {
                target.hvacPsTempC = psResolved;
                if (sync == 0 || sync == 1 || dual == 0 || dual == 1) {
                    target.hvacSync = (sync == 1) || (dual == 0 && Math.abs(target.hvacDrTempC - target.hvacPsTempC) < 0.1);
                }
            }

            if (ambStateC != 0 && ambStateC != 1 && ambStateC != 254 && ambStateC != 255 && !Float.isNaN(ambC)
                    && ambC >= -45.0f && ambC <= 65.0f && Math.abs(ambC - 85.0f) > 0.5f) {
                target.outdoorTempC = Math.round(ambC * 2.0) / 2.0;
            }
            target.hasClimateData = true;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String mapWidgetBroadcastActionToSource(String action) {
        if (action == null || action.isEmpty()) return "";
        String a = action.toLowerCase(Locale.US);
        if (a.contains(".timeupdate")) return "";
        if (a.contains("btaudio") || a.contains("avrcp")) return "Bluetooth Audio";
        if (a.contains("dipoaudio")) return "Apple CarPlay";
        if (a.contains("gpmaudio")) return "Android Auto";
        if (a.contains("audio.usb")) return "USB Music";
        if (a.contains("video.usb")) return "USB Video";
        if (a.contains("mymusic")) return "My Music";
        if (a.contains("radio.dab")) return "DAB";
        if (a.contains("radio")) return "Radio";
        if (a.contains("dmb")) return "DMB";
        if (a.contains("nature_sound")) return "자연의 소리";
        if (a.contains("melon")) return "Melon";
        if (a.contains("genie")) return "Genie";
        if (a.contains("qqmusic")) return "QQ Music";
        if (a.contains("kaolafm")) return "Kaola FM";
        if (a.contains("pandora")) return "Pandora";
        if (a.contains("ipod")) return "iPod";
        if (a.contains("audiocd") || a.contains("mp3cd")) return "Disc";
        if (a.contains("carlife")) return "CarLife";
        if (a.contains("mirrorlink")) return "MirrorLink";
        if (a.contains("mconnect")) return "MConnect";
        return "";
    }

    public static boolean parseBcmDefoggerLogLine(String line, TelemetrySnapshot target) {
        if (line == null || target == null) return false;
        int idx = line.indexOf("RES_DEFOGGER_STATE");
        if (idx >= 0) {
            String tail = line.substring(idx + "RES_DEFOGGER_STATE".length()).trim();
            if (tail.startsWith(":")) tail = tail.substring(1).trim();
            if (tail.startsWith("1")) {
                target.hvacRearDefog = true;
                return true;
            } else if (tail.startsWith("0")) {
                target.hvacRearDefog = false;
                return true;
            }
        }
        int idx2 = line.indexOf("C_DefoggerRly)");
        if (idx2 >= 0) {
            String tail = line.substring(idx2 + "C_DefoggerRly)".length()).trim();
            if (tail.startsWith(":")) tail = tail.substring(1).trim();
            if (tail.startsWith("1")) {
                target.hvacRearDefog = true;
                return true;
            } else if (tail.startsWith("0")) {
                target.hvacRearDefog = false;
                return true;
            }
        }
        return false;
    }

    /**
     * Parses genuine DATCService intake status logcat lines (verified against automotive-service.odex):
     * - D/DATCService: [DATC] onEvent::key = DATC_IntakeStatus, data = 00 (or 01)
     * - D/DATCService: [DATC] onEvent::key = DATC_IntakeDisp, data = 00 (or 01; ignores 02/03 INVALID)
     * - V/DATCService: BROADCASTED - Intent { act=com.hkmc.intent.action.CLIMATE_CHANGE ... }Flag : 12value0 (or value1)
     */
    public static boolean parseDatcIntakeLogLine(String line, TelemetrySnapshot target) {
        if (line == null || target == null) return false;
        String[] keys = new String[]{"DATC_IntakeStatus", "DATC_IntakeDisp"};
        for (int i = 0; i < keys.length; i++) {
            int idx = line.indexOf(keys[i]);
            if (idx >= 0) {
                int dataIdx = line.indexOf("data", idx);
                if (dataIdx >= 0) {
                    int eqIdx = line.indexOf('=', dataIdx);
                    String tail = (eqIdx >= 0 ? line.substring(eqIdx + 1) : line.substring(dataIdx + 4)).trim();
                    if (tail.startsWith(":")) tail = tail.substring(1).trim();
                    if (tail.startsWith("01") || (tail.startsWith("1") && (tail.length() == 1 || !Character.isDigit(tail.charAt(1))))) {
                        target.hvacRecirc = true;
                        target.hasClimateData = true;
                        return true;
                    } else if (tail.startsWith("00") || (tail.startsWith("0") && (tail.length() == 1 || !Character.isDigit(tail.charAt(1))))) {
                        target.hvacRecirc = false;
                        target.hasClimateData = true;
                        return true;
                    }
                }
            }
        }
        int flagIdx = line.indexOf("Flag : 12value");
        if (flagIdx < 0) flagIdx = line.indexOf("Flag: 12value");
        if (flagIdx >= 0) {
            int valPos = line.indexOf("value", flagIdx) + 5;
            if (valPos < line.length()) {
                char c = line.charAt(valPos);
                if (c == '1') {
                    target.hvacRecirc = true;
                    target.hasClimateData = true;
                    return true;
                } else if (c == '0') {
                    target.hvacRecirc = false;
                    target.hasClimateData = true;
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean evaluateVehicleScreenOffState(
            int powerStatus,
            boolean preparingSystemOff,
            boolean displayOffMode,
            boolean keepScreenOff,
            Boolean datcIg3,
            int bcmIgnCallback,
            int bcmAccCallback,
            boolean charging
    ) {
        if (preparingSystemOff || displayOffMode || keepScreenOff) {
            return true;
        }
        // Genuine MicomPowerConstants$AccStatus (verified in automotive.odex & dumpsys_broadcasts.txt):
        // 0 = ACCON (normal driving / ACC ON)
        // 1 = LOGICON (ACC OFF, logic power only)
        // 2 = ADM (Accessory Delayed Mode — entered the instant the driver presses Start/Stop to turn OFF the vehicle!)
        // 5 = SYSTEMREADY (initial cold-boot state in MicomPowerService.<clinit> before onAccOnRequest transitions to 0)
        // 6 = DELAYMODE, 7 = PRESYSTEMOFF, 255 = OFF
        // Note: DATCService.<init> initializes mIgn3Status = -1 and calls onIG3Status(false) on listener registration,
        // so datcIg3 == false must never blank the screen when powerStatus is ACCON(0) or SYSTEMREADY(5).
        if (powerStatus > 0 && powerStatus != 5) {
            return true;
        }
        if (!charging && (bcmIgnCallback == 0 || bcmAccCallback == 0)) {
            return true;
        }
        return false;
    }

    public static String mapModeManagerModeToSourceName(int mode) {
        // Genuine com.hkmc.mode.ModeConstants$ModeType (verified against automotive.odex):
        switch (mode) {
            case 2:
            case 3:
            case 56: return "FM";
            case 4:
            case 57: return "AM";
            case 5:
            case 58: return "Radio";
            case 14: return "DAB";
            case 22:
            case 23:
            case 61:
            case 62: return "DMB";
            case 33:
            case 49:
            case 71: return "USB Music";
            case 34:
            case 50:
            case 72: return "USB Video";
            case 52:
            case 74: return "iPod";
            case 53: return "AUX";
            case 54: return "Bluetooth Audio";
            case 55: return "My Music";
            case 115:
            case 117: return "Apple CarPlay";
            case 116:
            case 118: return "Android Auto";
            case 129: return "자연의 소리";
            case 130: return "Music Streaming";
            case 131: return "Genie";
            case 132: return "Melon";
            case 133: return "QQ Music";
            case 134: return "VIBE";
            default: return "";
        }
    }

    /**
     * Parses genuine Gen5W media logcat lines (verified against automotive-service.odex & DE22EV logcat_main.txt):
     * - D/MobisAvrcpControllerService: [BTAD] mSongTitle:<title>, mArtistName:<artist>, mAlbum:<album>, mPlayingTime:<ms>
     * - D/MobisAvrcpControllerService: [BTAD] title: <title>, artist:<artist>, album:<album>, playingTime:<ms>
     * - D/BTA_BT_PLAYER_MANAGER: title name: <title> artist name: <artist>
     * - D/BTA_BT_PLAYER_MANAGER: updateSplitBtAudio : <bool> ,title:<title> ,tune:<tune>
     * - I/SplitScreenViewBTAudio: onBtAudioStatusUpdate ...Artist name.. <artist>song name.. <title>play status.. <0|1>tune key..
     * - D/SplitScreenViewBTAudio: onBtAudioStatusUpdate BND: <title>
     * - D/SplitScreenViewBTAudio: onBtAudioStatusUpdate FRQ: <artist>
     * - I/AdvancedLauncher: mModeManager.getCurrentMode() : <modeStr>
     */
    public static boolean parseMediaLogLine(String line, TelemetrySnapshot target) {
        if (line == null || target == null) return false;
        try {
            if (line.contains("Waiting WIDGET_UPDATE Event")) {
                TelemetryCollector.requestMediaWidgetUpdateAsync();
                return true;
            }
            if (!line.contains("AdvancedLauncher") && target == TelemetryCollector.liveSnapshot
                    && TelemetryCollector.isNonBtModeSourceActive()) {
                // Background AVRCP / BT player logs while CarPlay, USB, radio ... is the active source
                return false;
            }

            if (line.contains("getImageResponseCallback")
                    || line.contains("onGetImageRsp")
                    || line.contains("/mnt/bt_cover_art/")) {
                String artPath = extractAvrcpCoverArtPathFromLog(line);
                if (!artPath.isEmpty()) {
                    File artFile = new File(artPath);
                    Object bmp = decodeAlbumArtFile(artFile);
                    if (bmp != null) {
                        target.albumArtBitmap = bmp;
                        target.albumArtSource = "avrcp_file";
                        if (target == TelemetryCollector.liveSnapshot) {
                            TelemetryCollector.lastNativeAlbumArtRxMs = System.currentTimeMillis();
                        }
                        AlbumArtResolver.putCachedArt(target.mediaTitle, target.mediaArtist, bmp);
                        return true;
                    }
                }
            }

            if (line.contains("MobisAvrcpControllerService") && line.contains("[BTAD]")) {
                String title = "";
                String artist = "";
                String album = "";
                long playMs = -1L;
                if (line.contains("mSongTitle:")) {
                    title = extractClusterKeyBetween(line, "mSongTitle:", ", mArtistName:");
                    artist = extractClusterKeyBetween(line, "mArtistName:", ", mAlbum:");
                    album = extractClusterKeyBetween(line, "mAlbum:", ", mPlayingTime:");
                    playMs = extractClusterKeyInt(line, "mPlayingTime:", -1);
                } else if (line.contains("title:")) {
                    title = extractClusterKeyBetween(line, "title:", ", artist:");
                    artist = extractClusterKeyBetween(line, "artist:", ", album:");
                    album = extractClusterKeyBetween(line, "album:", ", playingTime:");
                    playMs = extractClusterKeyInt(line, "playingTime:", -1);
                }
                boolean updated = false;
                if (title != null && !title.isEmpty() && !"Not Provided".equalsIgnoreCase(title) && !"null".equalsIgnoreCase(title)) {
                    target.mediaSource = "Bluetooth Audio";
                    target.mediaPlaying = true;
                    updated = true;
                }
                if (artist != null && !artist.isEmpty() && !"Not Provided".equalsIgnoreCase(artist) && !"null".equalsIgnoreCase(artist)) {
                    updated = true;
                }
                if (album != null && !album.isEmpty() && !"Not Provided".equalsIgnoreCase(album) && !"null".equalsIgnoreCase(album)) {
                    updated = true;
                }
                if (updated) {
                    onMediaTrackUpdated(target, title, artist, album);
                }
                if (playMs >= 0L && playMs < 36000000L) {
                    TelemetryCollector.notePositionUpdate(target, (int) (playMs / 1000L)); // mPlayingTime: ms
                    updated = true;
                }
                if (updated && target == TelemetryCollector.liveSnapshot && target.albumArtBitmap == null) {
                    AlbumArtResolver.requestOnlineLookupIfNeeded(target);
                }
                return updated;
            }

            if (line.contains("BTA_BT_PLAYER_MANAGER")) {
                if (line.contains("title name:") && line.contains("artist name:")) {
                    String title = extractClusterKeyBetween(line, "title name:", "artist name:");
                    String artist = extractClusterKeyTail(line, "artist name:");
                    boolean updated = false;
                    if (title != null && !title.isEmpty() && !"Not Provided".equalsIgnoreCase(title)) {
                        target.mediaSource = "Bluetooth Audio";
                        target.mediaPlaying = true;
                        updated = true;
                    }
                    if (artist != null && !artist.isEmpty() && !"Not Provided".equalsIgnoreCase(artist)) {
                        updated = true;
                    }
                    if (updated) {
                        onMediaTrackUpdated(target, title, artist, null);
                        if (target == TelemetryCollector.liveSnapshot && target.albumArtBitmap == null) {
                            AlbumArtResolver.requestOnlineLookupIfNeeded(target);
                        }
                    }
                    return updated;
                }
                if (line.contains("updateSplitBtAudio") && line.contains(",title:")) {
                    String title = extractClusterKeyBetween(line, ",title:", ",tune:");
                    if (title != null && !title.isEmpty() && !"Not Provided".equalsIgnoreCase(title)) {
                        target.mediaSource = "Bluetooth Audio";
                        onMediaTrackUpdated(target, title, null, null);
                        target.mediaPlaying = true;
                        return true;
                    }
                }
            }

            if (line.contains("SplitScreenViewBTAudio")) {
                if (line.contains("Artist name..") && line.contains("song name..")) {
                    String artist = extractClusterKeyBetween(line, "Artist name..", "song name..");
                    String title = extractClusterKeyBetween(line, "song name..", "play status..");
                    int playSt = extractClusterKeyInt(line, "play status..", -1);
                    boolean updated = false;
                    if (title != null && !title.isEmpty() && !"Not Provided".equalsIgnoreCase(title)) {
                        target.mediaSource = "Bluetooth Audio";
                        target.mediaPlaying = (playSt != 0);
                        updated = true;
                    }
                    if (artist != null && !artist.isEmpty() && !"Not Provided".equalsIgnoreCase(artist)) {
                        updated = true;
                    }
                    if (updated) {
                        onMediaTrackUpdated(target, title, artist, null);
                        if (target == TelemetryCollector.liveSnapshot && target.albumArtBitmap == null) {
                            AlbumArtResolver.requestOnlineLookupIfNeeded(target);
                        }
                    }
                    return updated;
                }
                if (line.contains("onBtAudioStatusUpdate BND:")) {
                    String title = extractClusterKeyTail(line, "onBtAudioStatusUpdate BND:");
                    if (title != null && !title.isEmpty() && !"Not Provided".equalsIgnoreCase(title)) {
                        target.mediaSource = "Bluetooth Audio";
                        onMediaTrackUpdated(target, title, null, null);
                        target.mediaPlaying = true;
                        return true;
                    }
                }
                if (line.contains("onBtAudioStatusUpdate FRQ:")) {
                    String artist = extractClusterKeyTail(line, "onBtAudioStatusUpdate FRQ:");
                    if (artist != null && !artist.isEmpty() && !"Not Provided".equalsIgnoreCase(artist)) {
                        onMediaTrackUpdated(target, null, artist, null);
                        if (target == TelemetryCollector.liveSnapshot && target.albumArtBitmap == null) {
                            AlbumArtResolver.requestOnlineLookupIfNeeded(target);
                        }
                        return true;
                    }
                }
            }

            if (line.contains("AdvancedLauncher") && line.contains("mModeManager.getCurrentMode() :")) {
                String mStr = extractClusterKeyTail(line, "mModeManager.getCurrentMode() :").toUpperCase(Locale.US);
                if (mStr.contains("BLUETOOTH")) {
                    target.mediaSource = "Bluetooth Audio";
                    return true;
                } else if (mStr.contains("CARPLAY") || mStr.contains("DIPO")) {
                    target.mediaSource = "Apple CarPlay";
                    return true;
                } else if (mStr.contains("ANDROID") || mStr.contains("GPM")) {
                    target.mediaSource = "Android Auto";
                    return true;
                } else if (mStr.contains("USB")) {
                    target.mediaSource = "USB Music";
                    return true;
                } else if (mStr.contains("FM") || mStr.contains("AM") || mStr.contains("RADIO")) {
                    target.mediaSource = "Radio";
                    return true;
                } else if (mStr.contains("DMB")) {
                    target.mediaSource = "DMB";
                    return true;
                } else if (mStr.contains("MELON")) {
                    target.mediaSource = "Melon";
                    return true;
                } else if (mStr.contains("GENIE")) {
                    target.mediaSource = "Genie";
                    return true;
                } else if (mStr.contains("NATURE")) {
                    target.mediaSource = "자연의 소리";
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String stripClusterTrailingFields(String val) {
        if (val == null) return "";
        String s = val.trim();
        String[] stops = new String[]{", user input =", ", length is", ", tune =", ", folder ="};
        for (int i = 0; i < stops.length; i++) {
            int idx = s.indexOf(stops[i]);
            if (idx >= 0) {
                s = s.substring(0, idx).trim();
            }
        }
        return s;
    }

    public static int parseClusterMediaLogLine(String line, TelemetrySnapshot target) {
        if (line == null || target == null || !line.contains("ClusterService")) return -1;
        try {
            if (line.contains("updateFileNameWithArtist::")) {
                String title = stripClusterTrailingFields(extractClusterKeyBetween(line, "filename is", ", length is"));
                String artist = stripClusterTrailingFields(extractClusterKeyBetween(line, "artistName is", ", length is"));
                onMediaTrackUpdated(target, title, artist, null);
                if (title != null && !title.isEmpty()) {
                    target.mediaPlaying = true;
                }
                if (target == TelemetryCollector.liveSnapshot && target.albumArtBitmap == null) {
                    AlbumArtResolver.requestOnlineLookupIfNeeded(target);
                }
                return TelemetryCollector.lastClusterMediaMode;
            } else if (line.contains("updateFileNameWithFolder::")) {
                String title = stripClusterTrailingFields(extractClusterKeyBetween(line, "filename is", ", length is"));
                String folder = stripClusterTrailingFields(extractClusterKeyBetween(line, "folder is", ", length is"));
                onMediaTrackUpdated(target, title, folder, null);
                if (title != null && !title.isEmpty()) {
                    target.mediaPlaying = true;
                }
                return TelemetryCollector.lastClusterMediaMode;
            } else if (line.contains("updateFilename::")) {
                String title = stripClusterTrailingFields(extractClusterKeyBetween(line, "filename is", ", length is"));
                onMediaTrackUpdated(target, title, null, null);
                if (title != null && !title.isEmpty()) {
                    target.mediaPlaying = true;
                }
                return TelemetryCollector.lastClusterMediaMode;
            } else if (line.contains("updateClusterWithArtistInfo") || line.contains("updateClusterInfoWithArtistForMusicStreaming")) {
                int mode = extractClusterKeyInt(line, "mode =", -1);
                int sec = extractClusterKeyInt(line, "seconds =", -1);
                String title = line.contains("titleCluster =")
                        ? extractClusterKeyBetween(line, "titleCluster =", ", artist =")
                        : extractClusterKeyBetween(line, "title =", ", artist =");
                String artist = stripClusterTrailingFields(extractClusterKeyTail(line, "artist ="));
                title = stripClusterTrailingFields(title);
                if (mode >= 0) {
                    target.mediaSource = mapClusterModeToSourceName(mode);
                }
                onMediaTrackUpdated(target, title, artist, null);
                if (title != null && !title.isEmpty()) {
                    target.mediaPlaying = true;
                }
                if (sec >= 0 && sec < 36000) {
                    TelemetryCollector.notePositionUpdate(target, sec); // ClusterService "seconds =": s
                }
                if (target == TelemetryCollector.liveSnapshot && target.albumArtBitmap == null) {
                    AlbumArtResolver.requestOnlineLookupIfNeeded(target);
                }
                return mode;
            } else if (line.contains("updateClusterWithFolderInfo")) {
                int mode = extractClusterKeyInt(line, "mode =", -1);
                int sec = extractClusterKeyInt(line, "seconds =", -1);
                String file;
                String folder;
                if (line.contains("title =") && line.contains(", folder =")) {
                    file = stripClusterTrailingFields(extractClusterKeyBetween(line, "title =", ", folder ="));
                    folder = stripClusterTrailingFields(extractClusterKeyTail(line, "folder ="));
                } else {
                    folder = stripClusterTrailingFields(extractClusterKeyBetween(line, "folder =", ", file ="));
                    file = stripClusterTrailingFields(extractClusterKeyTail(line, "file ="));
                }
                if (mode >= 0) {
                    target.mediaSource = mapClusterModeToSourceName(mode);
                }
                onMediaTrackUpdated(target, file, folder, null);
                if (file != null && !file.isEmpty()) {
                    target.mediaPlaying = true;
                }
                if (sec >= 0 && sec < 36000) {
                    TelemetryCollector.notePositionUpdate(target, sec); // ClusterService "seconds =": s
                }
                return mode;
            } else if (line.contains("updateClusterInfo")) {
                int mode = extractClusterKeyInt(line, "mode =", -1);
                int sec = extractClusterKeyInt(line, "seconds =", -1);
                if (mode >= 0) {
                    target.mediaSource = mapClusterModeToSourceName(mode);
                }
                if (line.contains("info1 =")) {
                    String info1 = stripClusterTrailingFields(extractClusterKeyBetween(line, "info1 =", ", info2 ="));
                    String info2 = stripClusterTrailingFields(extractClusterKeyTail(line, "info2 ="));
                    onMediaTrackUpdated(target, info1, info2, null);
                    if (info1 != null && !info1.isEmpty()) {
                        target.mediaPlaying = true;
                    }
                } else if (line.contains("title =")) {
                    String title = stripClusterTrailingFields(extractClusterKeyTail(line, "title ="));
                    onMediaTrackUpdated(target, title, null, null);
                    if (title != null && !title.isEmpty()) {
                        target.mediaPlaying = true;
                    }
                }
                if (sec >= 0 && sec < 36000) {
                    TelemetryCollector.notePositionUpdate(target, sec); // ClusterService "seconds =": s
                }
                return mode;
            } else if (line.contains("updateClusterRadioInfo")) {
                int mode = extractClusterKeyInt(line, "mode =", -1);
                String srcName = mapClusterModeToSourceName(mode);
                if (line.contains("freq =")) {
                    String freq = extractClusterKeyTail(line, "freq =");
                    int comma = (freq != null) ? freq.indexOf(',') : -1;
                    if (comma > 0) freq = freq.substring(0, comma).trim();
                    if (freq != null && !freq.isEmpty()) {
                        target.mediaSource = srcName;
                        target.mediaTitle = srcName + " " + freq;
                        target.mediaArtist = "라디오 방송 수신 중";
                        target.mediaAlbum = "";
                        target.mediaPlaying = true;
                        target.albumArtBitmap = null;
                        target.albumArtSource = "none";
                        target.albumArtTrackKey = "";
                    }
                } else if (line.contains("station =")) {
                    String ch = stripClusterTrailingFields(extractClusterKeyBetween(line, "channel =", ", station ="));
                    String st = stripClusterTrailingFields(extractClusterKeyTail(line, "station ="));
                    target.mediaSource = srcName;
                    target.mediaTitle = (st != null && !st.isEmpty()) ? st : (srcName + " " + ch);
                    target.mediaArtist = (ch != null && !ch.isEmpty() && !"0".equals(ch)) ? (srcName + " " + ch) : "라디오 방송 수신 중";
                    target.mediaAlbum = "";
                    target.mediaPlaying = true;
                    target.albumArtBitmap = null;
                    target.albumArtSource = "none";
                    target.albumArtTrackKey = "";
                }
                return mode;
            } else if (line.contains("setCurrentMode =")) {
                int mode = extractClusterKeyInt(line, "setCurrentMode =", -1);
                if (mode >= 0) {
                    target.mediaSource = mapClusterModeToSourceName(mode);
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

    public static String normalizeTrackToken(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.isEmpty()
                || "null".equalsIgnoreCase(t)
                || "Not Provided".equalsIgnoreCase(t)
                || "Unknown".equalsIgnoreCase(t)
                || "미디어 재생 대기 중".equals(t)) {
            return "";
        }
        return t;
    }

    public static boolean isRealArtistName(String artist) {
        String a = normalizeTrackToken(artist);
        if (a.isEmpty()) return false;
        if ("AVN / 블루투스 / CarPlay".equals(a)
                || "라디오 방송 수신 중".equals(a)
                || "Bluetooth Audio".equalsIgnoreCase(a)
                || "Unknown Artist".equalsIgnoreCase(a)
                || "알 수 없는 아티스트".equals(a)) {
            return false;
        }
        return true;
    }

    public static boolean isLookupEligibleTrack(String source, String title) {
        String t = normalizeTrackToken(title);
        if (t.length() < 2) return false;
        if (source != null) {
            String src = source.toUpperCase(Locale.US);
            if (src.contains("FM") || src.contains("AM") || src.contains("RADIO")
                    || src.contains("DAB") || src.contains("DMB") || src.contains("자연")) {
                return false;
            }
        }
        if (t.startsWith("FM ") || t.startsWith("AM ") || t.endsWith("MHz") || t.endsWith("kHz")) {
            return false;
        }
        return true;
    }

    public static boolean isNativeAlbumArtSource(String src) {
        if (src == null) return false;
        return src.startsWith("broadcast_")
                || "rc_display".equals(src)
                || "avrcp_file".equals(src)
                || src.startsWith("disk_");
    }

    public static void onMediaTrackUpdated(
            TelemetrySnapshot target,
            String rawTitle,
            String rawArtist,
            String rawAlbum
    ) {
        if (target == null) return;
        String newTitle = normalizeTrackToken(rawTitle);
        String newArtist = normalizeTrackToken(rawArtist);
        String newAlbum = normalizeTrackToken(rawAlbum);

        String prevTitle = normalizeTrackToken(target.mediaTitle);
        boolean titleChanged = !newTitle.isEmpty() && !newTitle.equalsIgnoreCase(prevTitle);

        if (!newTitle.isEmpty()) {
            target.mediaTitle = newTitle;
        }
        if (!newArtist.isEmpty() && (titleChanged || isRealArtistName(newArtist) || !isRealArtistName(target.mediaArtist))) {
            target.mediaArtist = newArtist;
        } else if (titleChanged && newArtist.isEmpty() && !prevTitle.isEmpty()) {
            target.mediaArtist = "";
        }
        if (!newAlbum.isEmpty()) {
            target.mediaAlbum = newAlbum;
        } else if (titleChanged && !prevTitle.isEmpty()) {
            target.mediaAlbum = "";
        }

        String trackKey = AlbumArtResolver.buildTrackCacheKey(target.mediaTitle, target.mediaArtist);
        if (titleChanged) {
            TelemetryCollector.resetPlaybackTimeForNewTrack(target);
        }
        if (titleChanged && !prevTitle.isEmpty()) {
            Object cached = AlbumArtResolver.getCachedArt(target.mediaTitle, target.mediaArtist);
            if (cached != null) {
                target.albumArtBitmap = cached;
                target.albumArtSource = "cache";
            } else if (target == TelemetryCollector.liveSnapshot
                    && (System.currentTimeMillis() - TelemetryCollector.lastNativeAlbumArtRxMs) < 1200L
                    && isNativeAlbumArtSource(target.albumArtSource)) {
                // Keep native artwork that arrived immediately before the metadata broadcast for this track transition
            } else {
                target.albumArtBitmap = null;
                target.albumArtSource = "none";
                if (target == TelemetryCollector.liveSnapshot) {
                    TelemetryCollector.latestAlbumArtBytes = null;
                }
            }
            target.albumArtTrackKey = trackKey;
            if (target == TelemetryCollector.liveSnapshot) {
                TelemetryCollector.lastTrackChangeMs = System.currentTimeMillis();
                TelemetryCollector.trackWidgetReqCount = 0;
                if (target.albumArtBitmap == null) {
                    probeOnDiskAlbumArtLocked(target);
                    TelemetryCollector.requestMediaWidgetUpdateAsync();
                }
            }
        } else {
            target.albumArtTrackKey = trackKey;
            if (target.albumArtBitmap == null && !trackKey.isEmpty()) {
                Object cached = AlbumArtResolver.getCachedArt(target.mediaTitle, target.mediaArtist);
                if (cached != null) {
                    target.albumArtBitmap = cached;
                    target.albumArtSource = "cache";
                }
            }
        }
    }

    public static boolean isBtaudioPlaceholderBundle(
            String action,
            Object bundleObj,
            Method getInt,
            Method getObj
    ) {
        if (bundleObj == null) return false;
        if (action != null && !action.toLowerCase(Locale.US).contains("btaudio")) {
            return false;
        }
        try {
            // Genuine com.daudio.av.app.btaudio.service.MusicService.b(Bitmap):
            // When albumArt == null, BTAudio puts "albumart_type"=0 and "artwork_index"=mCount with a generic stock
            // background drawable in "artwork_raw_image". When albumArt != null, it puts "albumart_type"=1 and no "artwork_index".
            int artType = -1;
            if (getInt != null) {
                try {
                    artType = ((Integer) getInt.invoke(bundleObj, "albumart_type", Integer.valueOf(-1))).intValue();
                } catch (Throwable ignored) {
                }
            }
            if (artType == -1 && getObj != null) {
                Object o = getObj.invoke(bundleObj, "albumart_type");
                if (o instanceof Number) {
                    artType = ((Number) o).intValue();
                }
            }
            if (artType == 0) {
                return true;
            }
            if (artType != 1 && getObj != null) {
                Object idxObj = getObj.invoke(bundleObj, "artwork_index");
                if (idxObj != null) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean hasValidImageMagic(byte[] b) {
        if (b == null || b.length < 16) return false;
        // JPEG: FF D8 FF
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return true;
        // PNG: 89 50 4E 47
        if ((b[0] & 0xFF) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) return true;
        // GIF: 47 49 46
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return true;
        // BMP: 42 4D
        if (b[0] == 'B' && b[1] == 'M') return true;
        // WebP: RIFF....WEBP
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return true;
        return false;
    }

    public static Object decodeAlbumArtBytes(byte[] rawArt) {
        if (rawArt == null || rawArt.length < 32) return null;
        try {
            Class<?> bfCls = Class.forName("android.graphics.BitmapFactory");
            Object bmp = bfCls.getMethod("decodeByteArray", byte[].class, int.class, int.class)
                    .invoke(null, rawArt, Integer.valueOf(0), Integer.valueOf(rawArt.length));
            if (bmp != null) {
                return bmp;
            }
        } catch (ClassNotFoundException cnfe) {
            // Host JVM self-test environment without android.graphics.BitmapFactory:
            // accept valid image byte arrays so unit tests can verify end-to-end cover art pipelines.
            if (hasValidImageMagic(rawArt)) {
                return rawArt;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static Object decodeAlbumArtFile(File f) {
        try {
            if (f == null || !f.isFile()) return null;
            long len = f.length();
            if (len < 32L || len > 8L * 1024L * 1024L) return null;
            byte[] bytes = readFileBytesQuick(f);
            Object bmp = decodeAlbumArtBytes(bytes);
            if (bmp != null) {
                TelemetryCollector.latestAlbumArtBytes = bytes;
            }
            return bmp;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object loadBitmapFromUriOrPath(String uriOrPath) {
        if (uriOrPath == null) return null;
        String s = uriOrPath.trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
        try {
            if (s.startsWith("file://")) {
                String path = s.substring("file://".length());
                try {
                    path = java.net.URLDecoder.decode(path, "UTF-8");
                } catch (Throwable ignored) {
                }
                s = path;
            }
            if (!s.startsWith("content://") && !s.startsWith("http://") && !s.startsWith("https://")) {
                File f = new File(s);
                if (!f.isFile() && s.startsWith("/") && s.length() > 3 && s.charAt(2) == ':') {
                    f = new File(s.substring(1));
                }
                if (!f.isFile()) return null;
                Object directBmp = decodeAlbumArtFile(f);
                if (directBmp != null) {
                    return directBmp;
                }
                // If path points to an audio file (e.g. USBMusic / MyMusic .mp3/.flac/.m4a), extract ID3/FLAC embedded cover art
                try {
                    Class<?> mmrCls = Class.forName("android.media.MediaMetadataRetriever");
                    Object mmr = mmrCls.newInstance();
                    try {
                        mmrCls.getMethod("setDataSource", String.class).invoke(mmr, f.getAbsolutePath());
                        byte[] emb = (byte[]) mmrCls.getMethod("getEmbeddedPicture").invoke(mmr);
                        if (emb != null && emb.length > 32) {
                            Object embBmp = decodeAlbumArtBytes(emb);
                            if (embBmp != null) {
                                TelemetryCollector.latestAlbumArtBytes = emb;
                                return embBmp;
                            }
                        }
                    } finally {
                        try { mmrCls.getMethod("release").invoke(mmr); } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {
                }
                return null;
            }
            if (s.startsWith("content://")) {
                Object ctx = TurzxOverlayController.getSysContext();
                if (ctx != null) {
                    Object resolver = ctx.getClass().getMethod("getContentResolver").invoke(ctx);
                    Class<?> uriCls = Class.forName("android.net.Uri");
                    Object uriObj = uriCls.getMethod("parse", String.class).invoke(null, s);
                    InputStream is = (InputStream) resolver.getClass()
                            .getMethod("openInputStream", uriCls).invoke(resolver, uriObj);
                    if (is != null) {
                        try {
                            ByteArrayOutputStream baos = new ByteArrayOutputStream(32768);
                            byte[] buf = new byte[4096];
                            int r;
                            while ((r = is.read(buf)) > 0) {
                                baos.write(buf, 0, r);
                                if (baos.size() > 4 * 1024 * 1024) break;
                            }
                            byte[] raw = baos.toByteArray();
                            Object bmp = decodeAlbumArtBytes(raw);
                            if (bmp != null) {
                                TelemetryCollector.latestAlbumArtBytes = raw;
                                return bmp;
                            }
                        } finally {
                            try { is.close(); } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static void probeOnDiskAlbumArtLocked(TelemetrySnapshot target) {
        if (target == null || target.albumArtBitmap != null) return;
        try {
            long now = System.currentTimeMillis();
            String src = target.mediaSource != null ? target.mediaSource : "";
            if ("Apple CarPlay".equals(src)) {
                File dipoArt = new File("/data/data/com.daudio.app.dipo/files/image");
                if (dipoArt.isFile() && dipoArt.length() > 64L
                        && TelemetryCollector.isDiskArtFreshForTrack(dipoArt.lastModified(), TelemetryCollector.lastTrackChangeMs, now)) {
                    Object bmp = decodeAlbumArtFile(dipoArt);
                    if (bmp != null) {
                        target.albumArtBitmap = bmp;
                        target.albumArtSource = "disk_carplay";
                        AlbumArtResolver.putCachedArt(target.mediaTitle, target.mediaArtist, bmp);
                        return;
                    }
                }
            } else if ("Android Auto".equals(src)) {
                File gpmArt = new File("/data/data/com.google.android.projection.sink/files/gpm_artwork");
                if (gpmArt.isFile() && gpmArt.length() > 64L
                        && TelemetryCollector.isDiskArtFreshForTrack(gpmArt.lastModified(), TelemetryCollector.lastTrackChangeMs, now)) {
                    Object bmp = decodeAlbumArtFile(gpmArt);
                    if (bmp != null) {
                        target.albumArtBitmap = bmp;
                        target.albumArtSource = "disk_androidauto";
                        AlbumArtResolver.putCachedArt(target.mediaTitle, target.mediaArtist, bmp);
                        return;
                    }
                }
            }

            if (src.isEmpty() || "Bluetooth Audio".equals(src) || "AVN Media".equals(src)) {
                String[] btDirs = new String[]{"/mnt/bt_cover_art/0", "/mnt/bt_cover_art/1"};
                File newest = null;
                long newestMod = 0L;
                for (int d = 0; d < btDirs.length; d++) {
                    File dir = new File(btDirs[d]);
                    if (!dir.isDirectory()) continue;
                    File[] files = dir.listFiles();
                    if (files == null) continue;
                    for (int i = 0; i < files.length; i++) {
                        File f = files[i];
                        if (f != null && f.isFile() && f.length() > 64L) {
                            long mod = f.lastModified();
                            if (mod >= newestMod) {
                                newestMod = mod;
                                newest = f;
                            }
                        }
                    }
                }
                if (newest != null) {
                    long minValidMod = TelemetryCollector.lastTrackChangeMs > 0L
                            ? (TelemetryCollector.lastTrackChangeMs - 5000L)
                            : (now - 600000L);
                    if (newestMod >= minValidMod) {
                        Object bmp = decodeAlbumArtFile(newest);
                        if (bmp != null) {
                            target.albumArtBitmap = bmp;
                            target.albumArtSource = "disk_bt_bip";
                            AlbumArtResolver.putCachedArt(target.mediaTitle, target.mediaArtist, bmp);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static String extractAvrcpCoverArtPathFromLog(String line) {
        if (line == null) return "";
        int idx = line.indexOf("path:");
        if (idx >= 0) {
            idx += "path:".length();
        } else {
            idx = line.indexOf("/mnt/bt_cover_art/");
            if (idx < 0) return "";
        }
        int end = idx;
        while (end < line.length()) {
            char c = line.charAt(end);
            if (c == ',' || c == ';' || c == ')' || c == ']' || c == '"' || Character.isWhitespace(c)) {
                break;
            }
            end++;
        }
        return line.substring(idx, end).trim();
    }

    public static String extractItunesArtworkUrl(String json) {
        if (json == null || json.isEmpty()) return "";
        String[] keys = new String[]{"\"artworkUrl100\"", "\"artworkUrl60\""};
        for (int k = 0; k < keys.length; k++) {
            int idx = json.indexOf(keys[k]);
            if (idx < 0) continue;
            int colon = json.indexOf(':', idx + keys[k].length());
            if (colon < 0) continue;
            int q1 = json.indexOf('"', colon + 1);
            if (q1 < 0) continue;
            int q2 = json.indexOf('"', q1 + 1);
            if (q2 < 0) continue;
            String rawUrl = json.substring(q1 + 1, q2).replace("\\/", "/").trim();
            if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
                rawUrl = rawUrl.replace("100x100bb", "200x200bb").replace("60x60bb", "200x200bb");
                return rawUrl;
            }
        }
        return "";
    }

    public static final class AlbumArtResolver {
        private static final Map<String, Object> artCache = new LinkedHashMap<String, Object>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
                return size() > 48;
            }
        };
        private static final Map<String, Long> attemptHistoryMs = new LinkedHashMap<String, Long>(128, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > 96;
            }
        };
        private static volatile boolean workerBusy = false;

        public static String buildTrackCacheKey(String title, String artist) {
            String t = normalizeTrackToken(title).toLowerCase(Locale.US);
            if (t.isEmpty()) return "";
            String a = isRealArtistName(artist) ? normalizeTrackToken(artist).toLowerCase(Locale.US) : "";
            return t + "|" + a;
        }

        public static synchronized Object getCachedArt(String title, String artist) {
            String key = buildTrackCacheKey(title, artist);
            if (key.isEmpty()) return null;
            Object v = artCache.get(key);
            if (v != null) return v;
            String titleOnlyKey = normalizeTrackToken(title).toLowerCase(Locale.US) + "|";
            return artCache.get(titleOnlyKey);
        }

        public static synchronized void putCachedArt(String title, String artist, Object bmp) {
            if (bmp == null) return;
            String key = buildTrackCacheKey(title, artist);
            if (key.isEmpty()) return;
            artCache.put(key, bmp);
            String titleOnlyKey = normalizeTrackToken(title).toLowerCase(Locale.US) + "|";
            if (!artCache.containsKey(titleOnlyKey)) {
                artCache.put(titleOnlyKey, bmp);
            }
        }

        public static void requestOnlineLookupIfNeeded(TelemetrySnapshot s) {
            if (s == null || s.albumArtBitmap != null) return;
            final String title = normalizeTrackToken(s.mediaTitle);
            final String artist = isRealArtistName(s.mediaArtist) ? normalizeTrackToken(s.mediaArtist) : "";
            if (!isLookupEligibleTrack(s.mediaSource, title)) return;

            final String key = buildTrackCacheKey(title, artist);
            if (key.isEmpty()) return;

            Object cached = getCachedArt(title, artist);
            if (cached != null) {
                s.albumArtBitmap = cached;
                s.albumArtSource = "cache";
                s.albumArtTrackKey = key;
                return;
            }

            long now = System.currentTimeMillis();
            synchronized (AlbumArtResolver.class) {
                if (workerBusy) return;
                Long lastAttempt = attemptHistoryMs.get(key);
                if (lastAttempt != null && (now - lastAttempt.longValue()) < 45000L) {
                    return;
                }
                attemptHistoryMs.put(key, Long.valueOf(now));
                workerBusy = true;
            }

            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        // Give native broadcast / on-disk cover art a brief head start right after a track change
                        long sinceChange = System.currentTimeMillis() - TelemetryCollector.lastTrackChangeMs;
                        if (sinceChange >= 0L && sinceChange < 650L) {
                            try { Thread.sleep(650L - sinceChange); } catch (InterruptedException ignored) {}
                        }
                        synchronized (TelemetryCollector.liveSnapshot) {
                            if (TelemetryCollector.liveSnapshot.albumArtBitmap != null
                                    && key.equals(TelemetryCollector.liveSnapshot.albumArtTrackKey)) {
                                return;
                            }
                        }

                        boolean weakMatch = artist.isEmpty(); // title-only search: may be a different song
                        String query = artist.isEmpty() ? title : (title + " " + artist);
                        String encoded = java.net.URLEncoder.encode(query, "UTF-8");
                        String searchPath = "/search?term=" + encoded + "&media=music&entity=song&limit=3";
                        byte[] jsonBytes = downloadHttpsUrlBytes("itunes.apple.com", searchPath, 65536);
                        String artUrl = "";
                        if (jsonBytes != null && jsonBytes.length > 20) {
                            artUrl = extractItunesArtworkUrl(new String(jsonBytes, UTF8));
                        }
                        if (artUrl.isEmpty() && !artist.isEmpty()) {
                            String fallbackEnc = java.net.URLEncoder.encode(title, "UTF-8");
                            byte[] fbBytes = downloadHttpsUrlBytes(
                                    "itunes.apple.com",
                                    "/search?term=" + fallbackEnc + "&media=music&entity=song&limit=3",
                                    65536
                            );
                            if (fbBytes != null && fbBytes.length > 20) {
                                artUrl = extractItunesArtworkUrl(new String(fbBytes, UTF8));
                                weakMatch = true;
                            }
                        }
                        if (artUrl.isEmpty()) return;

                        java.net.URL u = new java.net.URL(artUrl);
                        String host = u.getHost();
                        String pathAndQuery = u.getFile();
                        if (pathAndQuery == null || pathAndQuery.isEmpty()) pathAndQuery = "/";
                        byte[] imgBytes = downloadHttpsUrlBytes(host, pathAndQuery, 524288);
                        if (imgBytes == null || imgBytes.length < 128) return;

                        Object bmp = decodeAlbumArtBytes(imgBytes);
                        if (bmp == null) return;

                        if (!weakMatch) {
                            putCachedArt(title, artist, bmp);
                        }
                        synchronized (TelemetryCollector.liveSnapshot) {
                            String curKey = buildTrackCacheKey(
                                    TelemetryCollector.liveSnapshot.mediaTitle,
                                    TelemetryCollector.liveSnapshot.mediaArtist
                            );
                            String curTitleOnly = normalizeTrackToken(TelemetryCollector.liveSnapshot.mediaTitle).toLowerCase(Locale.US);
                            if (TelemetryCollector.liveSnapshot.albumArtBitmap == null
                                    && (key.equals(curKey) || title.toLowerCase(Locale.US).equals(curTitleOnly))) {
                                TelemetryCollector.liveSnapshot.albumArtBitmap = bmp;
                                TelemetryCollector.liveSnapshot.albumArtSource = weakMatch ? "itunes_title_only" : "itunes_api";
                                TelemetryCollector.liveSnapshot.albumArtTrackKey = curKey;
                                TelemetryCollector.latestAlbumArtBytes = imgBytes;
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        synchronized (AlbumArtResolver.class) {
                            workerBusy = false;
                        }
                    }
                }
            }, "Turzx-AlbumArtResolver");
            t.setDaemon(true);
            t.start();
        }

        private static byte[] downloadHttpsUrlBytes(String host, String pathAndQuery, int maxBytes) {
            List<String> ips = OtaUpdateManager.resolveHostViaPublicDns(host);
            for (int i = 0; i < ips.size(); i++) {
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
                    rawSock.connect(new InetSocketAddress(ip, 443), 2500);
                    rawSock.setSoTimeout(4500);

                    sslSock = (SSLSocket) sc.getSocketFactory().createSocket(rawSock, host, 443, true);
                    sslSock.setEnabledProtocols(new String[]{"TLSv1.2"});
                    try {
                        sslSock.getClass().getMethod("setHostname", String.class).invoke(sslSock, host);
                    } catch (Throwable ignored) {
                    }
                    sslSock.startHandshake();

                    OutputStream os = sslSock.getOutputStream();
                    String req = "GET " + pathAndQuery + " HTTP/1.1\r\n"
                            + "Host: " + host + "\r\n"
                            + "User-Agent: HKG-TurzxMonitor/" + MONITOR_VERSION + "\r\n"
                            + "Accept: */*\r\n"
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
                    else if (rawSock != null) { try { rawSock.close(); } catch (Throwable ignored) {} }
                }
            }
            return null;
        }
    }

    public static int parseAddedDistDatcOff(int raw) {
        if (raw == 0 || raw == -1 || raw == 65534 || raw == 65535) return 0;
        if (raw < 0 && raw > -150) return raw;
        if (raw < 0) return 0;
        // Genuine HKMC_TAppEV3 (f.g): bits 8..23 = current DTE (km), bits 0..7 = climate-off delta DTE (km)
        int lowByte = raw & 0xFF;
        if (lowByte > 0 && lowByte < 150) return lowByte;
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
        // Genuine com.hkmc.datc.DATCConstants:
        // LEVEL_OFF = 1, LEVEL_1 = 2, ..., LEVEL_8 = 9, LEVEL_AUTO = 10
        if (rawLevel == 0 || rawLevel == 1) return 0;
        if (rawLevel >= 2 && rawLevel <= 9) return rawLevel - 1;
        if (rawLevel == 10) return 3;
        if (rawLevel >= 11 && rawLevel <= 13) return (rawLevel - 10) * 2;
        return -1;
    }

    public static int normalizeDatcBlowerMode(int rawMode) {
        // Genuine com.hkmc.datc.DATCConstants$DatcModeDisp (verified against automotive.odex & DE22EV live dump):
        // OFF = 0, VENT = 1, BL = 2, FLOOR = 3, MIX = 4, DEF = 5, AUTODEFLOG = 6, DEF_VENT = 7,
        // MODE_AUTO / DEFFL = 8, DEF_VENT_FLOOR = 9, DEF_7MODE = 10,
        // DEF_VENT_ARROW = 11, DEF_FLOOR_ARROW = 12, DEF_VENT_FLOOR_ARROW = 13, DEF_ARROW = 14, INVALID = 15
        switch (rawMode) {
            case 0: return 0; // OFF
            case 1: return 1; // VENT (상체)
            case 2: return 2; // BL (상·하체)
            case 3: return 3; // FLOOR (하체)
            case 4: return 4; // MIX (유리·하체)
            case 5: return 5; // DEF (전면유리)
            case 6: return 5; // AUTODEFLOG
            case 7: return 7; // DEF_VENT (유리·상체)
            case 8: return 2; // MODE_AUTO
            case 9: return 9; // DEF_VENT_FLOOR (유리·상·하체)
            case 10: return 5; // DEF_7MODE (전면유리)
            case 11: return 7; // DEF_VENT_ARROW (유리·상체)
            case 12: return 4; // DEF_FLOOR_ARROW (유리·하체)
            case 13: return 9; // DEF_VENT_FLOOR_ARROW (유리·상·하체)
            case 14: return 5; // DEF_ARROW (전면유리)
            default:
                return -1;
        }
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

    private static final int CONFIG_REV = 3;

    public static MonitorConfig loadMonitorConfig(File file) {
        MonitorConfig cfg = new MonitorConfig();
        if (file == null || !file.isFile()) return cfg;
        BufferedReader br = null;
        int configRev = 1;
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
                else if ("usb_jpeg_quality".equals(k)) cfg.usbJpegQuality = Math.max(50, Math.min(95, parseIntSafe(v, 70)));
                else if ("config_rev".equals(k)) configRev = parseIntSafe(v, 1);
                else if ("usb_fps".equals(k)) cfg.usbFps = Math.max(2, Math.min(25, parseIntSafe(v, 12)));
                else if ("usb_panel_fps".equals(k)) cfg.usbPanelFps = Math.max(0, Math.min(60, parseIntSafe(v, 0)));
                else if ("overlay_hotkey_code".equals(k)) cfg.overlayHotkeyCode = Math.max(0, Math.min(767, parseIntSafe(v, 0)));
                else if ("hud_road".equals(k)) cfg.hudRoadEnabled = !"0".equals(v) && !"false".equalsIgnoreCase(v);
                else if ("usb_incremental_encode".equals(k)) cfg.usbIncrementalEncode = !"0".equals(v) && !"false".equalsIgnoreCase(v);
                else if ("render_parallel".equals(k)) cfg.renderParallel = !"0".equals(v) && !"false".equalsIgnoreCase(v);
                else if ("hud_design".equals(k)) cfg.hudDesignTesla = !"classic".equalsIgnoreCase(v) && !"0".equals(v);
                else if ("telemetry_poll_ms".equals(k)) cfg.telemetryPollMs = Math.max(50, Math.min(2000, parseIntSafe(v, 200)));
                else if ("comma_ip".equals(k)) cfg.commaIp = v;
                else if ("auto_update".equals(k)) cfg.autoUpdate = !"0".equals(v) && !"false".equalsIgnoreCase(v);
                else if ("x".equals(k)) cfg.x = parseIntSafe(v, 24);
                else if ("y".equals(k)) cfg.y = parseIntSafe(v, 64);
            }
        } catch (Throwable ignored) {
        } finally {
            if (br != null) { try { br.close(); } catch (Throwable ignored) {} }
        }
        // v1.4.0: old default quality 85 -> 70 (native encoder + less USB payload). Explicit values saved
        // with config_rev>=2 are respected.
        if (configRev < 2 && cfg.usbJpegQuality == 85) {
            cfg.usbJpegQuality = 70;
        }
        // v1.4.8: single-core rendering + incremental encoding become the standard. Configs saved before
        // (config_rev < 3, e.g. render_parallel=1 written by the v1.4.5-1.4.7 experiment panel) are moved over once;
        // values saved with config_rev >= 3 are respected.
        if (configRev < 3) {
            cfg.renderParallel = false;
            cfg.usbIncrementalEncode = true;
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
                    + "usb_panel_fps=" + cfg.usbPanelFps + "\n"
                    + "overlay_hotkey_code=" + cfg.overlayHotkeyCode + "\n"
                    + "hud_road=" + (cfg.hudRoadEnabled ? 1 : 0) + "\n"
                    + "usb_incremental_encode=" + (cfg.usbIncrementalEncode ? 1 : 0) + "\n"
                    + "render_parallel=" + (cfg.renderParallel ? 1 : 0) + "\n"
                    + "hud_design=" + (cfg.hudDesignTesla ? "tesla" : "classic") + "\n"
                    + "telemetry_poll_ms=" + cfg.telemetryPollMs + "\n"
                    + "comma_ip=" + (cfg.commaIp != null ? cfg.commaIp : "") + "\n"
                    + "auto_update=" + (cfg.autoUpdate ? 1 : 0) + "\n"
                    + "x=" + cfg.x + "\n"
                    + "y=" + cfg.y + "\n"
                    + "config_rev=" + CONFIG_REV + "\n";
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

    private static volatile Method sysPropGetMethod = null;

    private static String getSystemProperty(String key, String def) {
        try {
            Method m = sysPropGetMethod;
            if (m == null) {
                m = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class);
                sysPropGetMethod = m;
            }
            String val = (String) m.invoke(null, key, def);
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

    /** tmp + rename without fsync: for frequently rewritten, non-critical state files (no eMMC flush stalls). */
    private static void writeBytesNoSync(File target, byte[] data) throws Exception {
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            fos.write(data);
        } finally {
            fos.close();
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new IllegalStateException("rename failed: " + tmp + " -> " + target);
        }
    }

    private static void writeBytesAtomic(File target, byte[] data) throws Exception {
        if (target == null || data == null || data.length == 0) {
            throw new IllegalArgumentException("Refusing to write empty data to " + target);
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        // Keep a power-loss-safe .bak copy of critical executable/archive files before replacing
        String name = target.getName();
        if ((name.endsWith(".jar") || name.endsWith(".sh")) && target.isFile() && target.length() > 128L) {
            try {
                File bak = new File(target.getAbsolutePath() + ".bak");
                byte[] prevBytes = readFileBytesQuick(target);
                if (prevBytes.length > 128) {
                    File bakTmp = new File(target.getAbsolutePath() + ".bak.tmp");
                    FileOutputStream bfos = new FileOutputStream(bakTmp);
                    try {
                        bfos.write(prevBytes);
                        bfos.getFD().sync();
                    } finally {
                        bfos.close();
                    }
                    bakTmp.renameTo(bak);
                }
            } catch (Throwable ignored) {
            }
        }

        File tmp = new File(target.getAbsolutePath() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            fos.write(data);
            fos.getFD().sync();
        } finally {
            fos.close();
        }
        if (tmp.length() != data.length) {
            tmp.delete();
            throw new IllegalStateException("Short write on " + tmp + ": expected " + data.length + ", got " + tmp.length());
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

    private static boolean i123cells() {
        byte[] raw = TurzxFrameRenderer.readJarAssetBytesPublic("tesla_layout_123.txt");
        if (raw == null) return false;
        TurzxFrameRenderer.TeslaHud.Layout l = TurzxFrameRenderer.TeslaHud.parseLayout(new String(raw, UTF8));
        return l.fields.containsKey("cellMax") && l.fields.containsKey("cellDiff");
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

        // Verify HKG_5W_CarrotNavi (carrotnavi.bridge) status JSON integration & 192.168.0.1 SoftAP gateway exclusion
        assertTrue(!isValidRemoteCarrotIp("192.168.0.1") && !isValidRemoteCarrotIp("192.168.0.255")
                && !isValidRemoteCarrotIp("127.0.0.1") && isValidRemoteCarrotIp("192.168.0.103"),
                "Expected isValidRemoteCarrotIp to reject headunit gateway/broadcast and accept 192.168.0.103");
        String sampleCarrotBridgeStatus = "{\n"
                + "  \"bridgeVersion\": \"1.2.4\",\n"
                + "  \"timestampMs\": 1793090632998,\n"
                + "  \"discoveredCarrotIp\": \"192.168.0.103\",\n"
                + "  \"targets\": \"[192.168.0.103]\",\n"
                + "  \"lastPacket\": {\"carrotIndex\":6901,\"nRoadLimitSpeed\":70,\"nSdiType\":1,\"nSdiSpeedLimit\":60,\"nSdiDist\":320,\"nTBTDist\":410,\"szTBTMainText\":\"양재IC\"}\n"
                + "}";
        assertTrue(parseCarrotBridgeStatusJson(sampleCarrotBridgeStatus, snap), "Expected carrotbridge_status.json parse");
        assertTrue("192.168.0.103".equals(snap.carrotIp) && snap.carrotSdiType == 1
                && snap.carrotSdiSpeedLimit == 60 && snap.carrotSdiDistM == 320
                && snap.carrotTbtDistM == 410 && "양재IC".equals(snap.carrotTbtRoad),
                "Expected HKG_5W_CarrotNavi discoveredCarrotIp=192.168.0.103 and SDI/TBT applied");

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
            c1.usbPanelFps = 20;
            c1.commaIp = "192.168.43.10";
            c1.autoUpdate = false;
            saveMonitorConfig(tmpCfg, c1);
            MonitorConfig c2 = loadMonitorConfig(tmpCfg);
            assertTrue(new MonitorConfig().usbPanelFps == 0, "Expected usb_panel_fps default 0 (CMD 0x0F not sent)");
            assertTrue(c2.usbBrightness == 92 && c2.usbRotation == 90 && c2.usbFps == 15 && c2.usbPanelFps == 20
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

            // 7. Verify genuine HKMC_TAppEV3 flag 38 DTE + signed 10-bit motor power + DATCConstants (1=OFF, 2=ON) + AvWidgetMainView media
            assertTrue(extractCurrentDteFromFlag38(83716) == 327 && parseAddedDistDatcOff(83716) == 4,
                    "Expected DTE=327 km and delta=4 km from DE22EV getHEVStatus(38)=83716");
            assertTrue(parseSigned10BitKw(15) == 15 && parseSigned10BitKw(1009) == -15 && parseSigned10BitKw(1023) == 0,
                    "Expected signed 10-bit 2's complement motor power decoding");
            assertTrue(!isValidDteValue(65535) && !isValidDteValue(65534),
                    "Expected 65535/65534 to be rejected by isValidDteValue");

            // Verify DE22EV Single-Zone FATC OFF state (mOpStatus = 0 OFF / 2 SYSTEM_OFF, BaseDisp 0 = OFF)
            com.hkmc.datc.DatcStatus dsOff = new com.hkmc.datc.DatcStatus();
            dsOff.mOpStatus = 0;        // DatcOperationState.OFF = 0
            dsOff.mMainBlowerLevel = 1; // DatcBlowerLevel.LEVEL_0 = 1 (0단)
            dsOff.mDrMode = 0;          // DatcModeDisp.OFF = 0
            dsOff.mAuto = 0;            // BaseDisp.OFF = 0
            dsOff.mAcOnOff = 0;         // BaseDisp.OFF = 0
            dsOff.mHeatOnOff = 0;       // BaseDisp.OFF = 0
            dsOff.mIntake = 0;          // BaseDisp.OFF = 0 (Fresh)
            dsOff.mVentMode = 0;        // VentMode.SINGLE = 0
            dsOff.mZoneType = 0;        // ZoneType.ZONE_1 = 0
            dsOff.mDrTempC = 22.0f;
            dsOff.mDrTempStateC = 65535;// TemperatureDisplayState.NORMAL = 65535 (0xFFFF)
            dsOff.mPsTempC = 141.5f;
            dsOff.mPsTempStateC = 255;  // TemperatureDisplayState.INVALID = 255 (Single-Zone)
            assertTrue(applyDatcStatusObject(dsOff, snap), "Expected DatcStatus OFF decode success");
            assertTrue(!snap.hvacOn && snap.hvacBlowerStep == 0 && !snap.hvacAuto && !snap.hvacAc
                    && !snap.hvacHeat && !snap.hvacRecirc && snap.hvacSync
                    && Math.abs(snap.hvacDrTempC - 22.0) < 0.01 && Math.abs(snap.hvacPsTempC - 22.0) < 0.01,
                    "Expected genuine DE22EV DATC OFF state and Single-Zone passenger temp mirror");

            // Verify exact DE22EV live dump ON state (mOpStatus=1 NORMAL, mMainBlowerLevel=4 [3단], mDrMode=3 [FLOOR], mAuto=1, mHeatOnOff=1, mAcOnOff=0, dr=25.5, ps=141.5[st=255])
            com.hkmc.datc.DatcStatus dsOn = new com.hkmc.datc.DatcStatus();
            dsOn.mOpStatus = 1;         // DatcOperationState.NORMAL = 1 (ON)
            dsOn.mMainBlowerLevel = 4;  // DatcBlowerLevel.LEVEL_3 = 4 -> step 3
            dsOn.mDrMode = 3;           // DatcModeDisp.FLOOR = 3 -> mode 3 (하체)
            dsOn.mAuto = 1;             // BaseDisp.ON = 1
            dsOn.mAcOnOff = 0;          // BaseDisp.OFF = 0
            dsOn.mHeatOnOff = 1;        // BaseDisp.ON = 1
            dsOn.mIntake = 1;           // BaseDisp.ON = 1 (Recirc)
            dsOn.mVentMode = 0;         // VentMode.SINGLE = 0
            dsOn.mDrTempC = 25.5f;
            dsOn.mDrTempStateC = 65535; // NORMAL = 65535
            dsOn.mPsTempC = 141.5f;
            dsOn.mPsTempStateC = 255;   // INVALID = 255 (Single-Zone mirrors driver 25.5°C)
            assertTrue(applyDatcStatusObject(dsOn, snap), "Expected DatcStatus ON decode success");
            assertTrue(snap.hvacOn && snap.hvacBlowerStep == 3 && snap.hvacBlowerMode == 3
                    && snap.hvacAuto && snap.hvacHeat && !snap.hvacAc && snap.hvacRecirc && snap.hvacSync
                    && Math.abs(snap.hvacDrTempC - 25.5) < 0.01 && Math.abs(snap.hvacPsTempC - 25.5) < 0.01,
                    "Expected genuine DE22EV live DATC ON state applied accurately");

            // Verify windshield airflow modes (DEF=5, DEF_VENT=7, DEF_VENT_FLOOR=9, *_ARROW=11..14) & BCM Rear Defogger (C_DefoggerRly)
            assertTrue(normalizeDatcBlowerMode(5) == 5 && normalizeDatcBlowerMode(7) == 7
                    && normalizeDatcBlowerMode(9) == 9 && normalizeDatcBlowerMode(11) == 7
                    && normalizeDatcBlowerMode(12) == 4 && normalizeDatcBlowerMode(13) == 9
                    && normalizeDatcBlowerMode(14) == 5,
                    "Expected all windshield DatcModeDisp constants (DEF, DEF_VENT, DEF_VENT_FLOOR, *_ARROW) preserved");
            assertTrue(parseBcmDefoggerLogLine("I/BCMService( 1042): RES_DEFOGGER_STATE 1", snap) && snap.hvacRearDefog,
                    "Expected BCMService RES_DEFOGGER_STATE 1 to set hvacRearDefog=true");
            assertTrue(parseBcmDefoggerLogLine("I/HKMC_TAppService( 1420): onRemoteTeleHeatingStatusChanged() Side_Back Heating Status(C_DefoggerRly) : 0", snap) && !snap.hvacRearDefog,
                    "Expected C_DefoggerRly : 0 to set hvacRearDefog=false");
            BcmListenerBridge bcmBridge = new BcmListenerBridge();
            bcmBridge.onRemoteTeleHeatingStatusChanged(1, 1);
            TelemetrySnapshot bcmSnap = TelemetryCollector.getLatestSnapshot();
            assertTrue(bcmSnap.hvacSteerHeat && bcmSnap.hvacRearDefog,
                    "Expected BcmListenerBridge.onRemoteTeleHeatingStatusChanged(1, 1) to set steerHeat & rearDefog");
            bcmBridge.onRemoteTeleHeatingStatusChanged(0, 0);

            // Verify DE22EV intake/recirculation status (mIntake=3 INVALID on DatcStatus, resolved via CLIMATE_CHANGE OSD_FLAG=12 & DATCService logcat)
            TelemetrySnapshot intakeSnap = new TelemetrySnapshot();
            assertTrue(!intakeSnap.hvacRecirc, "Expected hvacRecirc default to false (Fresh Air) when unknown");
            com.hkmc.datc.DatcStatus dsDe22 = new com.hkmc.datc.DatcStatus();
            dsDe22.mOpStatus = 1;
            dsDe22.mMainBlowerLevel = 4;
            dsDe22.mDrMode = 3;
            dsDe22.mIntake = 3; // BaseDisp.INVALID = 3 on DE22EV
            assertTrue(applyDatcStatusObject(dsDe22, intakeSnap) && !intakeSnap.hvacRecirc,
                    "Expected mIntake=3 (BaseDisp.INVALID) not to falsely turn on hvacRecirc");
            assertTrue(parseDatcIntakeLogLine("D/DATCService( 1042): [DATC] onEvent::key = DATC_IntakeStatus, data = 01", intakeSnap)
                    && intakeSnap.hvacRecirc,
                    "Expected DATC_IntakeStatus data=01 to set hvacRecirc=true");
            assertTrue(parseDatcIntakeLogLine("D/DATCService( 1042): [DATC] onEvent::key = DATC_IntakeStatus, data = 00", intakeSnap)
                    && !intakeSnap.hvacRecirc,
                    "Expected DATC_IntakeStatus data=00 to set hvacRecirc=false");
            assertTrue(parseDatcIntakeLogLine("V/DATCService( 1042): BROADCASTED - Intent { act=com.hkmc.intent.action.CLIMATE_CHANGE }Flag : 12value1", intakeSnap)
                    && intakeSnap.hvacRecirc,
                    "Expected Flag : 12value1 log line to set hvacRecirc=true");
            android.content.Intent climateRecircOff = new android.content.Intent("com.hkmc.intent.action.CLIMATE_CHANGE");
            climateRecircOff.putExtra("OSD_FLAG", 12);
            climateRecircOff.putExtra("VALUE", 0);
            assertTrue(TelemetryCollector.applyMediaWidgetBroadcastToSnapshot(climateRecircOff.getAction(), climateRecircOff.getExtras(), intakeSnap)
                    && !intakeSnap.hvacRecirc,
                    "Expected CLIMATE_CHANGE OSD_FLAG=12 VALUE=0 to set hvacRecirc=false");
            android.content.Intent climateRecircOn = new android.content.Intent("com.hkmc.intent.action.CLIMATE_CHANGE");
            climateRecircOn.putExtra("OSD_FLAG", 12);
            climateRecircOn.putExtra("VALUE", 1);
            assertTrue(TelemetryCollector.applyMediaWidgetBroadcastToSnapshot(climateRecircOn.getAction(), climateRecircOn.getExtras(), intakeSnap)
                    && intakeSnap.hvacRecirc,
                    "Expected CLIMATE_CHANGE OSD_FLAG=12 VALUE=1 to set hvacRecirc=true");

            // Verify AvWidgetMainView media broadcast receiver (com.hkmc.audio.btaudio.widgetupdate + timeupdate)
            android.content.Intent btIntent = new android.content.Intent("com.hkmc.audio.btaudio.widgetupdate");
            btIntent.putExtra("filename", "Whiplash");
            btIntent.putExtra("artist_name", "aespa");
            btIntent.putExtra("album_name", "Whiplash - The 5th Mini Album");
            btIntent.putExtra("playstate", 1);
            btIntent.putExtra("audio_total_time", 183000L);
            btIntent.putExtra("audio_current_time", 42000L);
            assertTrue(TelemetryCollector.applyMediaWidgetBroadcastToSnapshot(btIntent.getAction(), btIntent.getExtras(), snap),
                    "Expected BTAudio widgetupdate broadcast decode");
            assertTrue("Bluetooth Audio".equals(snap.mediaSource) && "Whiplash".equals(snap.mediaTitle)
                    && "aespa".equals(snap.mediaArtist) && snap.mediaPlaying
                    && snap.mediaDurationSec == 183 && snap.mediaPositionSec == 42,
                    "Expected BTAudio widgetupdate fields applied");

            // Verify Broadcom AVRCP (PLAY_INFO + CURRENT_PLAY_POSITION) via MediaIntentReceiverBridge (IIntentReceiver.Stub)
            MediaIntentReceiverBridge iirBridge = new MediaIntentReceiverBridge();
            android.content.Intent avrcpPlayInfo = new android.content.Intent("com.broadcom.bt.avrcp.profile.action.PLAY_INFO");
            avrcpPlayInfo.putExtra("CURRENT_TITLE", "APT.");
            avrcpPlayInfo.putExtra("CURRENT_ARTIST", "ROSE & Bruno Mars");
            avrcpPlayInfo.putExtra("CURRENT_ALBUM", "rosie");
            avrcpPlayInfo.putExtra("PLAYING", true);
            iirBridge.performReceive(avrcpPlayInfo, 0, null, avrcpPlayInfo.getExtras(), false, false, 0);
            android.content.Intent avrcpPos = new android.content.Intent("com.broadcom.bt.avrcp.profile.action.CURRENT_PLAY_POSITION");
            avrcpPos.putExtra("POSITION", 65000L);
            avrcpPos.putExtra("LENGTH", 170000L);
            iirBridge.performReceive(avrcpPos, 0, null, avrcpPos.getExtras(), false, false, 0);
            TelemetrySnapshot avrcpSnap = TelemetryCollector.getLatestSnapshot();
            assertTrue("Bluetooth Audio".equals(avrcpSnap.mediaSource)
                    && "APT.".equals(avrcpSnap.mediaTitle)
                    && "ROSE & Bruno Mars".equals(avrcpSnap.mediaArtist)
                    && avrcpSnap.mediaPlaying
                    && avrcpSnap.mediaPositionSec == 65
                    && avrcpSnap.mediaDurationSec == 170,
                    "Expected MediaIntentReceiverBridge AVRCP PLAY_INFO + CURRENT_PLAY_POSITION decoded accurately");

            String clusterBtLine = "I/ClusterService( 1120): updateClusterWithArtistInfo : mode = 9, title = Supernova, artist = aespa";
            int parsedMode = parseClusterMediaLogLine(clusterBtLine, snap);
            assertTrue(parsedMode == 9 && "Supernova".equals(snap.mediaTitle) && "aespa".equals(snap.mediaArtist)
                    && "Bluetooth Audio".equals(snap.mediaSource) && snap.mediaPlaying,
                    "Expected ClusterService BT Audio log line parsed");

            // Verify genuine automotive-service.odex ClusterService D-level logs & MobisAvrcpControllerService / BTA_BT_PLAYER_MANAGER / SplitScreenViewBTAudio logs
            String clusterDLine1 = "D/ClusterService( 1042): updateFileNameWithArtist::filename is Rebel Heart, length is 11,  artistName is IVE, length is 3";
            parseClusterMediaLogLine(clusterDLine1, snap);
            assertTrue("Rebel Heart".equals(snap.mediaTitle) && "IVE".equals(snap.mediaArtist) && snap.mediaPlaying,
                    "Expected ClusterService updateFileNameWithArtist:: parsed");
            String clusterDLine2 = "D/ClusterService( 1042): updateClusterWithArtistInfo::mode = 9,seconds = 128, title = HOME SWEET HOME, artist = G-DRAGON, user input = false";
            assertTrue(parseClusterMediaLogLine(clusterDLine2, snap) == 9
                    && "HOME SWEET HOME".equals(snap.mediaTitle) && "G-DRAGON".equals(snap.mediaArtist)
                    && snap.mediaPositionSec == 128,
                    "Expected ClusterService updateClusterWithArtistInfo:: with seconds & user input parsed");
            String btadLine1 = "D/MobisAvrcpControllerService( 1310): [BTAD] mSongTitle:Drowning, mArtistName:WOODZ, mAlbum:OO-LI, mPlayingTime:94000";
            assertTrue(parseMediaLogLine(btadLine1, snap)
                    && "Bluetooth Audio".equals(snap.mediaSource)
                    && "Drowning".equals(snap.mediaTitle)
                    && "WOODZ".equals(snap.mediaArtist)
                    && "OO-LI".equals(snap.mediaAlbum)
                    && snap.mediaPositionSec == 94,
                    "Expected MobisAvrcpControllerService [BTAD] mSongTitle log parsed");
            String btaLine = "D/BTA_BT_PLAYER_MANAGER( 1310): title name: HAPPY artist name: DAY6";
            assertTrue(parseMediaLogLine(btaLine, snap)
                    && "HAPPY".equals(snap.mediaTitle) && "DAY6".equals(snap.mediaArtist),
                    "Expected BTA_BT_PLAYER_MANAGER title/artist log parsed");
            String splitBtLine = "I/SplitScreenViewBTAudio( 1480): onBtAudioStatusUpdate ...Artist name.. QWERsong name.. 내 이름 맑음play status.. 1tune key.. falsedevice name.. Galaxy S24";
            assertTrue(parseMediaLogLine(splitBtLine, snap)
                    && "내 이름 맑음".equals(snap.mediaTitle) && "QWER".equals(snap.mediaArtist) && snap.mediaPlaying,
                    "Expected SplitScreenViewBTAudio onBtAudioStatusUpdate log parsed");
            assertTrue("Bluetooth Audio".equals(mapModeManagerModeToSourceName(54))
                    && "Apple CarPlay".equals(mapModeManagerModeToSourceName(115))
                    && "Android Auto".equals(mapModeManagerModeToSourceName(116))
                    && "USB Music".equals(mapModeManagerModeToSourceName(33)),
                    "Expected ModeConstants$ModeType mapping");

            // Verify immediate ignition-off / ACC-off / ADM (powerStatus=2) & cold-boot SYSTEMREADY (5) / initial IG3=false handling
            assertTrue(!evaluateVehicleScreenOffState(0, false, false, false, Boolean.TRUE, 1, 1, false),
                    "Expected screen ON when ACCON (0) and IG3=true");
            assertTrue(!evaluateVehicleScreenOffState(5, false, false, false, Boolean.FALSE, -1, -1, false),
                    "Expected screen ON during cold-boot SYSTEMREADY (powerStatus=5) even when DATCService initial IG3=false");
            assertTrue(!evaluateVehicleScreenOffState(0, false, false, false, Boolean.FALSE, 1, 1, false),
                    "Expected screen ON when ACCON (0) and DATCService mIgn3Status=-1 returns IG3=false");
            assertTrue(evaluateVehicleScreenOffState(2, false, false, false, Boolean.TRUE, 1, 1, false),
                    "Expected screen OFF immediately when entering ADM (powerStatus=2 on Start/Stop button off)");
            assertTrue(evaluateVehicleScreenOffState(1, false, false, false, Boolean.TRUE, 1, 1, false),
                    "Expected screen OFF immediately when entering LOGICON (powerStatus=1)");
            assertTrue(evaluateVehicleScreenOffState(0, false, false, false, Boolean.TRUE, 0, 1, false),
                    "Expected screen OFF immediately when BCM onIgnitionChanged(0) fires (not charging)");
            assertTrue(!evaluateVehicleScreenOffState(0, false, false, false, Boolean.FALSE, 0, 1, true),
                    "Expected screen to remain ON during active EV charging when ACCON=0");
            assertTrue(evaluateVehicleScreenOffState(0, true, false, false, Boolean.TRUE, 1, 1, true),
                    "Expected screen OFF when isPreparingSystemOff() is true");

            android.content.Intent accAdmIntent = new android.content.Intent("com.hkmc.intent.action.ACTION_ACC_STATUS_CHANGED");
            accAdmIntent.putExtra("android.ibox.mode.EXTRA_ACC_STATUS", 2);
            assertTrue(TelemetryCollector.applyMediaWidgetBroadcastToSnapshot(accAdmIntent.getAction(), accAdmIntent.getExtras(), snap)
                    && TelemetryCollector.isVehicleScreenOff(),
                    "Expected ACTION_ACC_STATUS_CHANGED with android.ibox.mode.EXTRA_ACC_STATUS=2 (ADM) to set vehicleScreenOff=true");
            android.content.Intent accOnIntent = new android.content.Intent("com.hkmc.intent.action.ACTION_ACC_STATUS_CHANGED");
            accOnIntent.putExtra("android.ibox.mode.EXTRA_ACC_STATUS", 0);
            assertTrue(TelemetryCollector.applyMediaWidgetBroadcastToSnapshot(accOnIntent.getAction(), accOnIntent.getExtras(), snap)
                    && !TelemetryCollector.isVehicleScreenOff(),
                    "Expected ACTION_ACC_STATUS_CHANGED with android.ibox.mode.EXTRA_ACC_STATUS=0 (ACCON) to set vehicleScreenOff=false");

            // Verify TP_DEVICE_FATC (0xAB) UDS 62 01 00 indoor (25.0°C) & outdoor (20.0°C) sensor decoding
            String sampleLogFatc = "I/TM_HSCANPacketRunner(  524): onEventFromMicom(Bypass CAN) : length _41[c7, c0, ab, c8, 2b, "
                    + "62, 01, 00, 7e, 50, 27, c8, ff, 82, 78, 80, 03, ef, 8d, ff, ff, 8d, ff, 8e, ff, ff, ff, ff, ff]";
            assertTrue(parseTmHscanLogLine(sampleLogFatc, snap), "Expected FATC 62 01 00 parse");
            assertTrue(Math.abs(snap.inCarTempC - 25.0) < 0.01 && Math.abs(snap.outdoorTempC - 20.0) < 0.01,
                    "Expected FATC 62 01 00 inCarTempC=25.0°C and outdoorTempC=20.0°C, got " + snap.inCarTempC + "/" + snap.outdoorTempC);

            // Verify getEffectiveDriveMotorKw() returns 0.0 kW when parked in P gear or stopped with live subPower
            snap.gearPos = "P";
            snap.hasPackCurrent = true;
            snap.packPowerKw = 1.28;
            snap.hasSubPower = true;
            snap.drivePowerKw = 0.0;
            snap.climatePowerKw = 0.60;
            snap.electronicsPowerKw = 0.46;
            snap.batteryCarePowerKw = 0.0;
            assertTrue(Math.abs(snap.getEffectiveDriveMotorKw()) < 0.001,
                    "Expected 0.0 kW drive motor power in P gear, got " + snap.getEffectiveDriveMotorKw());
            snap.gearPos = "D";
            assertTrue(Math.abs(snap.getEffectiveDriveMotorKw()) < 0.001,
                    "Expected 0.0 kW drive motor power when stopped in D with MOTER_PWR_AVN=0, got " + snap.getEffectiveDriveMotorKw());

            // 8. Verify 5-Layer Album Cover Art acquisition (BTAudio placeholder filter, broadcast_bytes, broadcast_uri, AVRCP BIP file log, track-change cache, iTunes JSON parser)
            TelemetrySnapshot artSnap = new TelemetrySnapshot();
            artSnap.mediaSource = "Bluetooth Audio";
            artSnap.mediaTitle = "Whiplash";
            artSnap.mediaArtist = "aespa";

            // 8A. BTAudio placeholder broadcast (albumart_type=0, artwork_index=0) must be ignored
            android.content.Intent btPlaceholderArt = new android.content.Intent("com.hkmc.audio.btaudio.widgetupdate");
            btPlaceholderArt.putExtra("albumart_type", 0);
            btPlaceholderArt.putExtra("artwork_index", 0);
            btPlaceholderArt.putExtra("artwork_raw_image", clearPng);
            TelemetryCollector.applyMediaWidgetBroadcastToSnapshot(btPlaceholderArt.getAction(), btPlaceholderArt.getExtras(), artSnap);
            assertTrue(artSnap.albumArtBitmap == null && "none".equals(artSnap.albumArtSource),
                    "Expected BTAudio stock placeholder (albumart_type=0, artwork_index=0) to be filtered out");

            // 8B. Real BTAudio album art broadcast (albumart_type=1, no artwork_index) must set albumArtBitmap
            android.content.Intent btRealArt = new android.content.Intent("com.hkmc.audio.btaudio.widgetupdate");
            btRealArt.putExtra("albumart_type", 1);
            btRealArt.putExtra("artwork_raw_image", clearPng);
            TelemetryCollector.applyMediaWidgetBroadcastToSnapshot(btRealArt.getAction(), btRealArt.getExtras(), artSnap);
            assertTrue(artSnap.albumArtBitmap != null && "broadcast_bytes".equals(artSnap.albumArtSource),
                    "Expected real BTAudio album art (albumart_type=1) to populate albumArtBitmap");

            // 8C. Switching to a new uncached track clears the old track's album art; switching back restores from LRU cache
            onMediaTrackUpdated(artSnap, "Uncached New Song", "New Artist", "New Album");
            assertTrue(artSnap.albumArtBitmap == null && "none".equals(artSnap.albumArtSource),
                    "Expected track change to uncached song to clear previous track's albumArtBitmap");
            onMediaTrackUpdated(artSnap, "Whiplash", "aespa", "Whiplash - The 5th Mini Album");
            assertTrue(artSnap.albumArtBitmap != null && "cache".equals(artSnap.albumArtSource),
                    "Expected switching back to Whiplash to restore albumArtBitmap from AlbumArtResolver LRU cache");

            // 8D. USBMusic / CarPlay / Android Auto artwork_uri & AvrcpControllerService /mnt/bt_cover_art logcat path
            File tmpArtFile = File.createTempFile("turzx_art_test", ".png");
            tmpArtFile.deleteOnExit();
            writeBytesAtomic(tmpArtFile, clearPng);

            TelemetrySnapshot uriSnap = new TelemetrySnapshot();
            android.content.Intent usbArtIntent = new android.content.Intent("com.hkmc.audio.usb.widgetupdate");
            usbArtIntent.putExtra("filename", "내 이름 맑음");
            usbArtIntent.putExtra("artist_name", "QWER");
            usbArtIntent.putExtra("artwork_uri", "file://" + tmpArtFile.getAbsolutePath());
            TelemetryCollector.applyMediaWidgetBroadcastToSnapshot(usbArtIntent.getAction(), usbArtIntent.getExtras(), uriSnap);
            assertTrue(uriSnap.albumArtBitmap != null && "broadcast_uri".equals(uriSnap.albumArtSource),
                    "Expected artwork_uri file:// to load albumArtBitmap");

            TelemetrySnapshot avrcpLogSnap = new TelemetrySnapshot();
            avrcpLogSnap.mediaSource = "Bluetooth Audio";
            avrcpLogSnap.mediaTitle = "HAPPY";
            avrcpLogSnap.mediaArtist = "DAY6";
            String avrcpImgLog = "D/AvrcpControllerService( 1310): [BTAD] getImageResponseCallback device:AA:BB:CC ,handle:1000001 ,path:"
                    + tmpArtFile.getAbsolutePath() + " ,status:4";
            assertTrue(parseMediaLogLine(avrcpImgLog, avrcpLogSnap)
                    && avrcpLogSnap.albumArtBitmap != null
                    && "avrcp_file".equals(avrcpLogSnap.albumArtSource),
                    "Expected AvrcpControllerService getImageResponseCallback log to load albumArtBitmap from path");
            tmpArtFile.delete();

            // 8E. Verify iTunes Search API JSON artworkUrl100 parser & 200x200bb upgrade
            String sampleItunesJson = "{\"resultCount\":1,\"results\":[{\"trackName\":\"내 이름 맑음\",\"artistName\":\"QWER\","
                    + "\"artworkUrl100\":\"https:\\/\\/is1-ssl.mzstatic.com\\/image\\/thumb\\/Music211\\/v4\\/ab\\/cd\\/100x100bb.jpg\"}]}";
            String parsedItunesUrl = extractItunesArtworkUrl(sampleItunesJson);
            assertTrue("https://is1-ssl.mzstatic.com/image/thumb/Music211/v4/ab/cd/200x200bb.jpg".equals(parsedItunesUrl),
                    "Expected extractItunesArtworkUrl to unescape slashes and upgrade to 200x200bb.jpg, got " + parsedItunesUrl);

            // 9. Verify Chromium 1:1 pre-rendered HUD PNG layers + Public Sans TTF fonts are bundled and readable
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

        // v1.4.2: media source / playback-time / album-art rules
        try {
            TelemetrySnapshot ms = new TelemetrySnapshot();
            android.content.Intent a1 = new android.content.Intent("com.hkmc.audio.btaudio.widgetupdate");
            a1.putExtra("filename", "Track A");
            a1.putExtra("artist_name", "Artist A");
            a1.putExtra("audio_total_time", 200000L);
            a1.putExtra("audio_current_time", 150000L);
            TelemetryCollector.applyMediaWidgetBroadcastToSnapshot(a1.getAction(), a1.getExtras(), ms);
            assertTrue(ms.mediaDurationSec == 200 && ms.mediaPositionSec == 150, "media: ms broadcast decoded");
            // New track without time keys: position 0, duration unknown (not the previous track's 200s)
            android.content.Intent a2 = new android.content.Intent("com.hkmc.audio.btaudio.widgetupdate");
            a2.putExtra("filename", "Track B");
            a2.putExtra("artist_name", "Artist B");
            TelemetryCollector.applyMediaWidgetBroadcastToSnapshot(a2.getAction(), a2.getExtras(), ms);
            assertTrue("Track B".equals(ms.mediaTitle) && ms.mediaPositionSec == 0 && ms.mediaDurationSec == 0,
                    "media: track change resets position and duration");
            // Seconds-based source with a track longer than 500s (old heuristic divided it to 0)
            TelemetrySnapshot sec = new TelemetrySnapshot();
            android.content.Intent u1 = new android.content.Intent("com.hkmc.audio.usb.widgetupdate");
            u1.putExtra("filename", "Long Track");
            u1.putExtra("audio_total_time", 600L);
            u1.putExtra("audio_current_time", 550L);
            TelemetryCollector.applyMediaWidgetBroadcastToSnapshot(u1.getAction(), u1.getExtras(), sec);
            assertTrue(sec.mediaDurationSec == 600 && sec.mediaPositionSec == 550, "media: seconds-unit broadcast kept");
            // ms source: time-only update of 300ms is 0s (old heuristic read it as 300s); 0 accepted on time-only updates
            android.content.Intent t1 = new android.content.Intent("com.hkmc.audio.timeupdate");
            t1.putExtra("audio_current_time", 42000L);
            TelemetryCollector.applyMediaWidgetBroadcastToSnapshot(t1.getAction(), t1.getExtras(), ms);
            assertTrue(ms.mediaPositionSec == 42, "media: ms time-only update");
            android.content.Intent t2 = new android.content.Intent("com.hkmc.audio.timeupdate");
            t2.putExtra("audio_current_time", 300L);
            TelemetryCollector.applyMediaWidgetBroadcastToSnapshot(t2.getAction(), t2.getExtras(), ms);
            assertTrue(ms.mediaPositionSec == 0, "media: 300ms is 0s, not 300s");
            android.content.Intent t3 = new android.content.Intent("com.hkmc.audio.timeupdate");
            t3.putExtra("audio_current_time", 0L);
            ms.mediaPositionSec = 77;
            TelemetryCollector.applyMediaWidgetBroadcastToSnapshot(t3.getAction(), t3.getExtras(), ms);
            assertTrue(ms.mediaPositionSec == 0, "media: position 0 accepted on time-only update");

            assertTrue(TelemetryCollector.resolveBroadcastUnitMs("UT-X", 600L) == false
                            && TelemetryCollector.resolveBroadcastUnitMs("UT-X", -1L) == false
                            && TelemetryCollector.resolveBroadcastUnitMs("UT-Y", -1L) == true
                            && TelemetryCollector.resolveBroadcastUnitMs("UT-X", 183000L) == true,
                    "media: broadcast unit learned per source, default ms");

            // ClusterService.getLastPlaytime unit selection
            assertTrue(TelemetryCollector.chooseClusterPlaytimeSec(20000L, 2, null) == 20, "cluster: 20000 near start = 20s (ms)");
            assertTrue(TelemetryCollector.chooseClusterPlaytimeSec(20L, 19, null) == 20, "cluster: 20 near 19s = 20s (s)");
            assertTrue(TelemetryCollector.chooseClusterPlaytimeSec(45000L, 0, null) == 45, "cluster: >36000 always ms");
            assertTrue(TelemetryCollector.chooseClusterPlaytimeSec(100L, 0, Boolean.FALSE) == 100, "cluster: locked seconds");
            assertTrue(TelemetryCollector.chooseClusterPlaytimeSec(1500L, 0, Boolean.TRUE) == 1, "cluster: locked ms");
            assertTrue(TelemetryCollector.chooseClusterPlaytimeSec(-1L, 0, null) == -1, "cluster: invalid");

            // CarPlay / Android Auto disk artwork must belong to the current track
            long nowT = System.currentTimeMillis();
            assertTrue(!TelemetryCollector.isDiskArtFreshForTrack(nowT - 60000L, nowT - 10000L, nowT), "art: previous track's file rejected");
            assertTrue(TelemetryCollector.isDiskArtFreshForTrack(nowT - 11000L, nowT - 10000L, nowT), "art: file written just before change ok");
            assertTrue(TelemetryCollector.isDiskArtFreshForTrack(nowT - 5000L, nowT - 10000L, nowT), "art: file written after change ok");
            assertTrue(!TelemetryCollector.isDiskArtFreshForTrack(nowT - 700000L, 0L, nowT), "art: no track change, old file rejected");

            // BT logcat metadata ignored while ModeManager reports a non-BT source
            String savedMode = TelemetryCollector.modeSourceName;
            long savedModeMs = TelemetryCollector.modeSourceMs;
            synchronized (TelemetryCollector.liveSnapshot) {
                String before = TelemetryCollector.liveSnapshot.mediaTitle;
                TelemetryCollector.modeSourceName = "Apple CarPlay";
                TelemetryCollector.modeSourceMs = System.currentTimeMillis();
                boolean applied = parseMediaLogLine(
                        "D/MobisAvrcpControllerService( 1310): [BTAD] mSongTitle:Ghost, mArtistName:BT, mAlbum:X, mPlayingTime:1000",
                        TelemetryCollector.liveSnapshot);
                assertTrue(!applied && (before == null ? TelemetryCollector.liveSnapshot.mediaTitle == null
                                : before.equals(TelemetryCollector.liveSnapshot.mediaTitle)),
                        "media: BT log ignored while CarPlay is active");
                TelemetryCollector.modeSourceName = savedMode;
                TelemetryCollector.modeSourceMs = savedModeMs;
            }
            // Periodic-reporter detection (drives pause detection): a burst is not periodic, 3 advancing samples are
            synchronized (TelemetryCollector.liveSnapshot) {
                TelemetrySnapshot live = TelemetryCollector.liveSnapshot;
                TelemetryCollector.resetPlaybackTimeForNewTrack(live);
                TelemetryCollector.notePositionUpdate(live, 10);
                TelemetryCollector.notePositionUpdate(live, 10);
                assertTrue(!TelemetryCollector.periodicPosReporter, "stall: duplicate burst is not periodic");
                TelemetryCollector.lastExternalPosMs = System.currentTimeMillis() - 1000L;
                TelemetryCollector.notePositionUpdate(live, 11);
                assertTrue(!TelemetryCollector.periodicPosReporter, "stall: one advancing sample is not enough");
                TelemetryCollector.lastExternalPosMs = System.currentTimeMillis() - 1000L;
                TelemetryCollector.notePositionUpdate(live, 12);
                assertTrue(TelemetryCollector.periodicPosReporter && live.mediaPositionSec == 12,
                        "stall: two consecutive advancing samples = periodic");
                TelemetryCollector.resetPlaybackTimeForNewTrack(live);
                assertTrue(!TelemetryCollector.periodicPosReporter && live.mediaPositionSec == 0 && live.mediaDurationSec == 0,
                        "stall: track change resets tracking");
            }
        } catch (Exception e) {
            throw new RuntimeException("v1.4.2 media rules self-test failed", e);
        }

        // v1.4.0: JPEG quality migration (legacy 85 -> 70 unless config_rev>=2) + native encoder asset/fallback
        try {
            File legacy = File.createTempFile("turzx_cfg_legacy", ".conf");
            File current = File.createTempFile("turzx_cfg_rev2", ".conf");
            legacy.deleteOnExit();
            current.deleteOnExit();
            // createTempFile leaves empty files; File.renameTo cannot replace an existing file on a Windows host
            legacy.delete();
            current.delete();
            writeBytesNoSync(legacy, "usb_jpeg_quality=85\nusb_fps=12\n".getBytes(UTF8));
            assertTrue(loadMonitorConfig(legacy).usbJpegQuality == 70, "Expected legacy q85 config to migrate to 70");
            writeBytesNoSync(current, "usb_jpeg_quality=85\nconfig_rev=2\n".getBytes(UTF8));
            assertTrue(loadMonitorConfig(current).usbJpegQuality == 85, "Expected explicit q85 with config_rev=2 to be kept");
            MonitorConfig rt = new MonitorConfig();
            saveMonitorConfig(current, rt);
            assertTrue(readAllTextQuick(current).contains("config_rev=3"), "Expected saved config to carry config_rev=3");

            byte[] so = TurzxFrameRenderer.readJarAssetBytesPublic("libturzxjpeg_armv7.so");
            assertTrue(so != null && so.length > 16384 && so[0] == 0x7F && so[1] == 'E' && so[2] == 'L' && so[3] == 'F'
                            && so[4] == 1 && so[18] == 40,
                    "Expected bundled ELF32/ARM libturzxjpeg_armv7.so asset");
            // Host (x86) must refuse to load the ARM library and report Skia fallback instead of throwing
            assertTrue(!NativeJpeg.ensureLoaded(), "Expected NativeJpeg to stay disabled on non-ARM host");
            // Crash guard: consecutive unfinished attempts for the same .so hash; other hash / no marker resets
            assertTrue(NativeJpeg.previousGuardAttempts(null, "abc123") == 0, "guard: no marker -> 0");
            assertTrue(NativeJpeg.previousGuardAttempts("abc123:0", "abc123") == 1, "guard: one crash -> 1");
            assertTrue(NativeJpeg.previousGuardAttempts("abc123:1\n", "abc123") == NativeJpeg.MAX_GUARD_ATTEMPTS,
                    "guard: two crashes -> disable threshold");
            assertTrue(NativeJpeg.previousGuardAttempts("old999:5", "abc123") == 0, "guard: other .so hash -> 0");
            assertTrue(NativeJpeg.previousGuardAttempts("garbage", "abc123") == 0, "guard: malformed -> 0");
        } catch (Exception e) {
            throw new RuntimeException("v1.4.0 config migration / native encoder self-test failed", e);
        }

        // v1.4.3: MAP long-press hotkey (evdev) + full-screen dashboard text
        try {
            String proc = "I: Bus=0019 Vendor=0001 Product=0001 Version=0100\n"
                    + "N: Name=\"gpio_keys_polled\"\nH: Handlers=kbd event0\nB: EV=3\n\n"
                    + "I: Bus=0006 Vendor=0123 Product=1234 Version=0000\n"
                    + "N: Name=\"daudio_wp_daudioplus_de22ev_kr\"\nP: Phys=\nH: Handlers=kbd event1 \nB: EV=3\n\n"
                    + "N: Name=\"daudio_wp_daudioplus_de22ev_kr_can\"\nH: Handlers=kbd event2\n\n"
                    + "N: Name=\"MELFAS_MIT401_Touchscreen\"\nH: Handlers=event3\n";
            List<String[]> devs = HotkeyWatcher.parseInputDevices(proc);
            assertTrue(devs.size() == 2 && "/dev/input/event1".equals(devs.get(0)[1])
                            && "/dev/input/event2".equals(devs.get(1)[1]),
                    "hotkey: expected daudio kbd devices event1/event2 only, got " + devs.size());
            String kl = "# D-Audio\nkey 158   BACK\nkey 217   SHORTCUT_MAP\nkey usage 0x0c0067 MAP\nkey 0x2f0 MAP_SEARCH\nkey 116 POWER WAKE\n";
            List<Integer> codes = HotkeyWatcher.parseMapCodesFromKl(kl);
            assertTrue(codes.size() == 2 && codes.contains(Integer.valueOf(217)) && codes.contains(Integer.valueOf(0x2f0)),
                    "hotkey: expected MAP codes 217 and 0x2f0, got " + codes);
            assertTrue(!HotkeyWatcher.isMapLabel("KEYMAP") && HotkeyWatcher.isMapLabel("SHORTCUT_MAP"), "hotkey: MAP label rule");
            assertTrue(HotkeyWatcher.klMatchesDevice("daudio_wp_daudioplus_de22ev_base.kl", devs)
                            && !HotkeyWatcher.klMatchesDevice("daudio_wp_daudioplus_cvev_base.kl", devs),
                    "hotkey: .kl file must match its own vehicle's device");

            HotkeyWatcher.LongPressDetector lp = new HotkeyWatcher.LongPressDetector(3000L, 20000L);
            lp.onKey(true, 1000L);
            assertTrue(!lp.poll(3900L), "long press: 2.9s must not fire");
            lp.onKey(true, 3950L); // autorepeat must not restart the hold timer
            assertTrue(lp.poll(4000L), "long press: fires at 3.0s while still held");
            assertTrue(!lp.poll(6000L), "long press: fires only once per press");
            lp.onKey(false, 6100L);
            lp.onKey(true, 7000L);
            lp.onKey(false, 8000L);
            assertTrue(!lp.poll(11000L), "long press: short press (1s) never fires");
            lp.onKey(true, 12000L);
            assertTrue(!lp.poll(40000L), "long press: missed key-up (stuck) is reset, not fired");
            // Missed key-up followed by a fresh press (evdev value 1): the new press is timed from its own start
            lp.onKey(true, false, 100000L);
            assertTrue(lp.poll(103100L), "long press: first press fires");
            lp.onKey(true, false, 110000L); // key-up of the first press was lost
            assertTrue(!lp.poll(112000L), "long press: fresh press restarts timing (2s)");
            assertTrue(lp.poll(113100L), "long press: fresh press fires after its own 3s");
            lp.onKey(true, true, 113500L); // autorepeat does not re-arm
            assertTrue(!lp.poll(117000L), "long press: still once per press");

            assertTrue(TurzxOverlayController.controlValueTvs.length == LiveTuning.ROW_LABELS.length
                            && TurzxOverlayController.lastControlTexts.length == LiveTuning.ROW_LABELS.length,
                    "dashboard: one value slot per experiment panel row");
            for (int row = 0; row < LiveTuning.ROW_LABELS.length; row++) {
                assertTrue(LiveTuning.valueText(row) != null && !LiveTuning.valueText(row).isEmpty(),
                        "dashboard: value text for row " + row);
            }

            TurzxOverlayController.DashboardStats ds = new TurzxOverlayController.DashboardStats();
            ds.usbConnected = true;
            ds.targetFps = 12;
            ds.fps = 6.0;
            ds.renderMs = 60.0;
            ds.encodeMs = 20.0;
            ds.txMs = 10.0;
            ds.ackMs = 140.0;
            ds.slotWaitMs = 60.0;
            assertTrue(TurzxOverlayController.describeBottleneck(ds).contains("병목: USB"),
                    "dashboard: slow ACK must be reported as USB bottleneck");
            ds.ackMs = 20.0;
            ds.slotWaitMs = 0.0;
            assertTrue(TurzxOverlayController.describeBottleneck(ds).contains("렌더"),
                    "dashboard: slow render must be reported as render bottleneck");
            ds.fps = 11.5;
            assertTrue(TurzxOverlayController.describeBottleneck(ds).contains("목표 FPS"), "dashboard: target reached");
            ds.usbConnected = false;
            assertTrue(TurzxOverlayController.describeBottleneck(ds).contains("미연결"), "dashboard: disconnected");

            String[] bodies = TurzxOverlayController.buildDashboardBodies(new TelemetrySnapshot(), ds);
            assertTrue(bodies.length == TurzxOverlayController.CARD_TITLES.length, "dashboard: one body per card");
            for (int i = 0; i < bodies.length; i++) {
                assertTrue(bodies[i] != null && !bodies[i].isEmpty() && bodies[i].split("\n").length <= 7,
                        "dashboard: card " + i + " must have 1..7 lines");
            }
            // Live tuning step lists + windowed FPS bench
            assertTrue(LiveTuning.step(LiveTuning.QUALITY_STEPS, 70, +1) == 75
                    && LiveTuning.step(LiveTuning.QUALITY_STEPS, 70, -1) == 65
                    && LiveTuning.step(LiveTuning.QUALITY_STEPS, 95, +1) == 95
                    && LiveTuning.step(LiveTuning.QUALITY_STEPS, 50, -1) == 50, "tuning: quality steps");
            assertTrue(LiveTuning.step(LiveTuning.TARGET_FPS_STEPS, 13, +1) == 15
                    && LiveTuning.step(LiveTuning.TARGET_FPS_STEPS, 13, -1) == 12, "tuning: off-list value snaps to neighbours");
            assertTrue(LiveTuning.step(LiveTuning.PANEL_FPS_STEPS, 0, +1) == 10, "tuning: panel fps off -> 10");
            MonitorConfig tc = new MonitorConfig();
            tc.usbJpegQuality = 60;
            tc.usbFps = 20;
            tc.hudRoadEnabled = false;
            tc.telemetryPollMs = 300;
            LiveTuning.loadFrom(tc);
            assertTrue(LiveTuning.label().equals("q60 · 20fps · 패널끔 · 도로OFF · 수집300 · 부분ON · 싱글코어 · 신규"), "tuning: label " + LiveTuning.label());
            MonitorConfig tc2 = new MonitorConfig();
            LiveTuning.saveTo(tc2);
            assertTrue(tc2.usbJpegQuality == 60 && tc2.usbFps == 20 && !tc2.hudRoadEnabled && tc2.telemetryPollMs == 300,
                    "tuning: saveTo copies live values");
            LiveTuning.loadFrom(new MonitorConfig());

            FpsBench.restart("test", 1000L);
            FpsBench.addFrame(10.0, 50.0, 40.0); // during warm-up: ignored
            assertTrue(FpsBench.tick(2500L) == null, "bench: still warming up");
            assertTrue(FpsBench.tick(3000L) == null, "bench: warm-up -> measuring");
            for (int i = 0; i < 40; i++) FpsBench.addFrame(10.0, 50.0, 40.0);
            FpsBench.addRender(60.0);
            FpsBench.addEncode(20.0);
            String res = FpsBench.tick(8000L);
            assertTrue(res != null && res.startsWith("→ 8.0 FPS") && res.contains("R60 E20 T10 A50 P0") && res.endsWith("| 40KB"),
                    "bench: 40 frames / 5s = 8.0 FPS, got " + res);
            assertTrue(FpsBench.recent(1).size() == 1 && "test".equals(FpsBench.recent(1).get(0)[0]), "bench: history");
            assertTrue(FpsBench.formatResult(0, 5000L, 0, 0, 0, 0, 0, 0, -1.0).contains("없음"), "bench: no frames");
            assertTrue(FpsBench.formatResult(50, 5000L, 30, 40, 3, 10, 50, 30, 135.4).endsWith("| 50KB | CPU 135%"),
                    "bench: cpu suffix");
            assertTrue(ProcCpu.parseTicks("1234 (app_process (x)) S 1 2 3 4 5 6 7 8 9 10 250 50 0 0") == 300L,
                    "cpu: utime+stime after (comm) with spaces");
            assertTrue(ProcCpu.parseTicks("garbage") == -1L, "cpu: bad stat");
            assertTrue(Math.abs(ProcCpu.percent(100L, 250L, 1000L) - 150.0) < 0.01, "cpu: 150 ticks/s = 150%");

            File tuneCfg = File.createTempFile("turzx_cfg_tuning", ".conf");
            tuneCfg.deleteOnExit();
            saveMonitorConfig(tuneCfg, tc);
            MonitorConfig tc3 = loadMonitorConfig(tuneCfg);
            assertTrue(!tc3.hudRoadEnabled && tc3.telemetryPollMs == 300, "tuning: hud_road / telemetry_poll_ms roundtrip");
            tc.usbIncrementalEncode = false;
            saveMonitorConfig(tuneCfg, tc);
            assertTrue(!loadMonitorConfig(tuneCfg).usbIncrementalEncode, "tuning: usb_incremental_encode roundtrip");
            LiveTuning.loadFrom(tc);
            LiveTuning.adjust(5, +1);
            assertTrue(LiveTuning.incrementalEncode && LiveTuning.valueText(5).equals("ON"), "tuning: incremental toggle");
            tc.renderParallel = true;
            tc.usbIncrementalEncode = false;
            saveMonitorConfig(tuneCfg, tc);
            assertTrue(loadMonitorConfig(tuneCfg).renderParallel && !loadMonitorConfig(tuneCfg).usbIncrementalEncode,
                    "tuning: explicit values saved with config_rev=3 are kept");
            tuneCfg.delete(); // File.renameTo cannot replace an existing file on the Windows build host
            writeBytesNoSync(tuneCfg, "render_parallel=1\nusb_incremental_encode=0\nconfig_rev=2\n".getBytes(UTF8));
            MonitorConfig mig = loadMonitorConfig(tuneCfg);
            assertTrue(!mig.renderParallel && mig.usbIncrementalEncode, "tuning: config_rev<3 migrates to single-core + incremental");
            assertTrue(!new MonitorConfig().renderParallel && new MonitorConfig().usbIncrementalEncode,
                    "tuning: defaults are single-core + incremental");
            tc.renderParallel = false;
            tc.usbIncrementalEncode = true;
            saveMonitorConfig(tuneCfg, tc);
            assertTrue(!loadMonitorConfig(tuneCfg).renderParallel, "tuning: render_parallel roundtrip");
            LiveTuning.loadFrom(tc);
            assertTrue(LiveTuning.valueText(6).equals("싱글코어"), "tuning: single-core label");
            LiveTuning.adjust(6, +1);
            assertTrue(LiveTuning.parallelPipeline && LiveTuning.valueText(6).equals("멀티코어"), "tuning: render mode toggle");
            LiveTuning.loadFrom(new MonitorConfig());
            tuneCfg.delete();
        } catch (Exception e) {
            throw new RuntimeException("v1.4.3 hotkey / dashboard self-test failed", e);
        }

        // v1.4.4: render || encode pipeline handoff + batched road strips
        try {
            assertTrue(TurzxUsbDriver.pickFreeBuffer(-1, -1, 2) == 0
                    && TurzxUsbDriver.pickFreeBuffer(0, -1, 2) == 1
                    && TurzxUsbDriver.pickFreeBuffer(1, -1, 2) == 0
                    && TurzxUsbDriver.pickFreeBuffer(0, 1, 2) == -1, "pipeline: free buffer selection");

            // Render draws A while nothing is encoding, encoder takes A, render must then get the OTHER buffer
            int bufA = TurzxUsbDriver.acquireRenderBuffer(10L);
            assertTrue(bufA == 0, "pipeline: first buffer");
            TurzxUsbDriver.queueEncodeJob(bufA, true, false);
            assertTrue(TurzxUsbDriver.acquireRenderBuffer(30L) == -1, "pipeline: render waits while a frame is queued");
            int[] job = TurzxUsbDriver.takeEncodeJob(10L);
            assertTrue(job != null && job[0] == bufA && job[1] == 1 && job[2] == 0, "pipeline: encoder takes the queued job");
            int bufB = TurzxUsbDriver.acquireRenderBuffer(10L);
            assertTrue(bufB >= 0 && bufB != bufA, "pipeline: render never draws the buffer being encoded");
            assertTrue(TurzxUsbDriver.takeEncodeJob(10L) == null, "pipeline: no job without a queue");
            long tIdle0 = System.currentTimeMillis();
            TurzxUsbDriver.waitEncodeIdle(40L);
            assertTrue(System.currentTimeMillis() - tIdle0 >= 30L, "pipeline: single-core mode waits while encoding");
            TurzxUsbDriver.finishEncodeJob();
            tIdle0 = System.currentTimeMillis();
            TurzxUsbDriver.waitEncodeIdle(1000L);
            assertTrue(System.currentTimeMillis() - tIdle0 < 500L, "pipeline: idle encoder returns at once");
            assertTrue(TurzxUsbDriver.acquireRenderBuffer(10L) == 0, "pipeline: buffer free again after encode");

            // Batched strips cover exactly the same segments as the old per-segment loop
            float[] xs = TurzxFrameRenderer.ROAD_X_STEPS;
            int[] solid = TurzxFrameRenderer.stripRuns(xs, false, 0f);
            assertTrue(solid.length == 2 && solid[0] == 0 && solid[1] == xs.length - 1, "road: solid strip is one run");
            float[] phases = new float[]{0f, 1.3f, 2.9f, 4.5f, 17.2f};
            for (int pi = 0; pi < phases.length; pi++) {
                int[] runs = TurzxFrameRenderer.stripRuns(xs, true, phases[pi]);
                boolean[] covered = new boolean[xs.length - 1];
                for (int r = 0; r + 1 < runs.length; r += 2) {
                    assertTrue(runs[r] < runs[r + 1], "road: run has at least one segment");
                    for (int k = runs[r]; k < runs[r + 1]; k++) covered[k] = true;
                }
                for (int i = 0; i < xs.length - 1; i++) {
                    boolean on = ((((int) Math.floor((xs[i] + phases[pi]) / 3.0f)) & 1) == 0);
                    assertTrue(covered[i] == on, "road: dashed segment " + i + " phase " + phases[pi]);
                }
            }
            float[] pos = TurzxFrameRenderer.fadeStopPositions(xs, 14f, 130f, 950f, 1.35f);
            assertTrue(pos[0] == 0f && pos[pos.length - 1] == 1f, "road: gradient stops span 0..1");
            for (int i = 1; i < pos.length; i++) {
                assertTrue(pos[i] >= pos[i - 1], "road: gradient stops non-decreasing");
            }

            TurzxOverlayController.DashboardStats ds2 = new TurzxOverlayController.DashboardStats();
            ds2.usbConnected = true;
            ds2.targetFps = 20;
            ds2.fps = 9.0;
            ds2.renderMs = 27.0;
            ds2.encodeMs = 77.0;
            ds2.txMs = 3.0;
            ds2.ackMs = 10.0;
            assertTrue(TurzxOverlayController.describeBottleneck(ds2).contains("병목: 인코딩 77"),
                    "dashboard: encode-bound pipeline names the encoder");

            // Panel RST test sequencing: A -> B -> C (PHASE_MS each), then finished with a summary
            assertTrue(PanelRstTest.reportLine().equals("not run"), "rst: idle before start");
            byte[][] tf = new byte[][]{new byte[]{1}, new byte[]{2}, new byte[]{3}};
            long t0 = 100000L;
            PanelRstTest.startForTest(tf, t0);
            assertTrue(PanelRstTest.phase(t0) == 0 && PanelRstTest.frameFor(0)[0] == 1, "rst: phase A first");
            PanelRstTest.record(0, true, 10.0);
            PanelRstTest.record(0, true, 14.0);
            assertTrue(PanelRstTest.phase(t0 + PanelRstTest.PHASE_MS + 1) == 1, "rst: phase B");
            PanelRstTest.record(1, false, 0.0);
            assertTrue(PanelRstTest.phase(t0 + 2 * PanelRstTest.PHASE_MS + 1) == 2, "rst: phase C");
            assertTrue(PanelRstTest.statusLine(t0 + 2 * PanelRstTest.PHASE_MS + 1).contains("3/3"), "rst: live status");
            assertTrue(PanelRstTest.phase(t0 + 3 * PanelRstTest.PHASE_MS) == -1 && !PanelRstTest.isRunning(t0 + 3 * PanelRstTest.PHASE_MS + 5),
                    "rst: finished after three phases");
            String rr = PanelRstTest.reportLine();
            assertTrue(rr.startsWith("A 2✓ 0✗ 12ms · B 0✓ 1✗ · C 0✓ 0✗"), "rst: summary " + rr);

            // v1.4.9: Multi-panel support (9.2" 0x0092, 8.8" 0x0088, 12.3" 0x0123)
            assertTrue(isSupportedTurzxPid("0092") && isSupportedTurzxPid("0088") && isSupportedTurzxPid("0123"),
                    "pid: supported TURZX PIDs");
            assertTrue(!isSupportedTurzxPid("0000") && !isSupportedTurzxPid(null),
                    "pid: unsupported PIDs rejected");
            byte[] png720 = buildTransparentOverlayPng720x1920();
            assertTrue(png720 != null && png720.length > 64, "png720: generated");
            int ihdrW720 = ((png720[16] & 0xFF) << 24) | ((png720[17] & 0xFF) << 16)
                    | ((png720[18] & 0xFF) << 8) | (png720[19] & 0xFF);
            int ihdrH720 = ((png720[20] & 0xFF) << 24) | ((png720[21] & 0xFF) << 16)
                    | ((png720[22] & 0xFF) << 8) | (png720[23] & 0xFF);
            assertTrue(ihdrW720 == 720 && ihdrH720 == 1920,
                    "png720: IHDR 720x1920 (" + ihdrW720 + "x" + ihdrH720 + ")");

            byte[] assetBase720 = TurzxFrameRenderer.readJarAssetBytesPublic("base_shell_720.png");
            byte[] assetAct720 = TurzxFrameRenderer.readJarAssetBytesPublic("active_shell_720.png");
            byte[] assetCol2720 = TurzxFrameRenderer.readJarAssetBytesPublic("col2_hud_section_720.png");
            assertTrue(assetBase720 != null && assetBase720.length > 4096, "assets720: base_shell_720.png bundled");
            assertTrue(assetAct720 != null && assetAct720.length > 4096, "assets720: active_shell_720.png bundled");
            assertTrue(assetCol2720 != null && assetCol2720.length > 2048, "assets720: col2_hud_section_720.png bundled");
            int col2W720 = ((assetCol2720[16] & 0xFF) << 24) | ((assetCol2720[17] & 0xFF) << 16)
                    | ((assetCol2720[18] & 0xFF) << 8) | (assetCol2720[19] & 0xFF);
            int col2H720 = ((assetCol2720[20] & 0xFF) << 24) | ((assetCol2720[21] & 0xFF) << 16)
                    | ((assetCol2720[22] & 0xFF) << 8) | (assetCol2720[23] & 0xFF);
            assertTrue(col2W720 == 582 && col2H720 == 580,
                    "assets720: col2_hud_section_720.png 582x580 (" + col2W720 + "x" + col2H720 + ")");
            assertTrue(TurzxFrameRenderer.LAYOUT_720.drvTempX == 36f
                            && !TurzxFrameRenderer.LAYOUT_720.showPasTemp
                            && TurzxFrameRenderer.LAYOUT_720.outTempX == 251.5f
                            && TurzxFrameRenderer.LAYOUT_462.showPasTemp
                            && TurzxFrameRenderer.LAYOUT_462.drvTempX == 1228f,
                    "layout720: persistent bottom climate taskbar (no pasTemp, inline outTemp)");

            TurzxFrameRenderer.setActivePanelByPid(TURZX_USB_PID_123);
            assertTrue("12.3\"".equals(TurzxFrameRenderer.getActivePanelLabel())
                            && "1920x720".equals(TurzxFrameRenderer.getActiveResolutionString())
                            && TurzxFrameRenderer.getActiveHudHeight() == 720
                            && TurzxFrameRenderer.getActivePanelJpegWidth() == 720,
                    "panel: 0x0123 -> 12.3\" 1920x720 / pad 720");
            TurzxFrameRenderer.setActivePanelByPid(TURZX_USB_PID_88);
            assertTrue("8.8\"".equals(TurzxFrameRenderer.getActivePanelLabel())
                            && "1920x462".equals(TurzxFrameRenderer.getActiveResolutionString())
                            && TurzxFrameRenderer.getActivePanelJpegWidth() == 480,
                    "panel: 0x0088 -> 8.8\" 1920x462 / pad 480");
            TurzxFrameRenderer.setActivePanelByPid(TURZX_USB_PID_92);
            assertTrue("9.2\"".equals(TurzxFrameRenderer.getActivePanelLabel())
                            && "1920x462".equals(TurzxFrameRenderer.getActiveResolutionString())
                            && TurzxFrameRenderer.getActivePanelJpegWidth() == 464,
                    "panel: 0x0092 -> 9.2\" 1920x462 / pad 464");
        } catch (Exception e) {
            throw new RuntimeException("v1.4.4 pipeline / road self-test failed", e);
        }

        // v1.5.0: Tesla-style HUD layouts + lamp logic + design setting
        try {
            String[] layouts = {"tesla_layout_92.txt", "tesla_layout_123.txt"};
            for (int i = 0; i < layouts.length; i++) {
                byte[] raw = TurzxFrameRenderer.readJarAssetBytesPublic(layouts[i]);
                assertTrue(raw != null && raw.length > 500, "tesla: " + layouts[i] + " bundled");
                TurzxFrameRenderer.TeslaHud.Layout lay = TurzxFrameRenderer.TeslaHud.parseLayout(new String(raw, UTF8));
                String[] needF = {"speed", "soc", "dte", "bms", "soh", "kw", "title", "dr", "fanNum", "inT", "outT", "limit"};
                for (int k = 0; k < needF.length; k++) {
                    assertTrue(lay.fields.containsKey(needF[k]), "tesla: " + layouts[i] + " field " + needF[k]);
                }
                String[] needR = {"viz", "pill", "pwr", "prog", "art", "limit", "wheel", "tbt", "tpms", "auto",
                        "airFace", "airFoot", "prndP", "prndD", "chip_ac", "chip_heat", "chip_fdef", "chip_rdef", "chip_recirc", "fan6"};
                for (int k = 0; k < needR.length; k++) {
                    assertTrue(lay.rect(needR[k]) != null, "tesla: " + layouts[i] + " rect " + needR[k]);
                }
                float[] viz = lay.rect("viz");
                assertTrue(viz[0] >= 0f && viz[2] > 600f && viz[1] + viz[3] <= (i == 0 ? 462f : 720f), "tesla: viz inside the panel");
                TurzxFrameRenderer.TeslaHud.Field soc = lay.fields.get("soc");
                assertTrue("%".equals(soc.unit) && soc.size > 40f && soc.bold, "tesla: soc field with % unit");
                assertTrue(lay.fields.get("dte").align == 'R' && "km".equals(lay.fields.get("dte").unit), "tesla: dte right aligned with km");
                assertTrue(lay.sizes.containsKey("tbtDist") && lay.sizes.containsKey("tpms"), "tesla: TBT / TPMS typography");
            }
            assertTrue(i123cells(), "tesla: 12.3 layout has the cell voltage row");
            String[] sprites = {"tesla_base.png", "tesla_active.png", "tesla_base_720.png", "tesla_active_720.png",
                    "tesla_ego_off.png", "tesla_ego_tail.png", "tesla_ego_brake.png", "tesla_park.png", "tesla_lead.png"};
            for (int k = 0; k < sprites.length; k++) {
                byte[] b = TurzxFrameRenderer.readJarAssetBytesPublic(sprites[k]);
                assertTrue(b != null && b.length > 4096, "tesla: sprite " + sprites[k]);
            }
            TurzxFrameRenderer.TeslaHud.Layout bad = TurzxFrameRenderer.TeslaHud.parseLayout("F:x\t1\nR:y\tz\t1\t2\t3\n# c\n");
            assertTrue(bad.fields.isEmpty() && bad.rects.isEmpty(), "tesla: malformed layout lines are skipped");
            assertTrue(TurzxFrameRenderer.TeslaHud.estimateBrake("D", 0, 0.0, 0.0), "brake: stopped in D");
            assertTrue(TurzxFrameRenderer.TeslaHud.estimateBrake("D", 60, 2.0, 5.0), "brake: decelerating");
            assertTrue(TurzxFrameRenderer.TeslaHud.estimateBrake("D", 60, 0.0, -25.0), "brake: strong regen");
            assertTrue(!TurzxFrameRenderer.TeslaHud.estimateBrake("D", 60, 0.2, 18.0), "brake: cruising");
            assertTrue(!TurzxFrameRenderer.TeslaHud.estimateBrake("P", 0, 0.0, 0.0), "brake: parked");
            assertTrue(TurzxFrameRenderer.TeslaHud.headLampsOn(3) && !TurzxFrameRenderer.TeslaHud.headLampsOn(0)
                    && !TurzxFrameRenderer.TeslaHud.headLampsOn(-1), "head lamps: raw status");
            MonitorConfig dc = new MonitorConfig();
            assertTrue(dc.hudDesignTesla, "design: tesla by default");
            dc.hudDesignTesla = false;
            File dcf = File.createTempFile("turzx_cfg_design", ".conf");
            dcf.deleteOnExit();
            dcf.delete();
            saveMonitorConfig(dcf, dc);
            assertTrue(!loadMonitorConfig(dcf).hudDesignTesla, "design: hud_design=classic roundtrip");
            dcf.delete();
            assertTrue(TurzxOverlayController.controlValueTvs.length == LiveTuning.ROW_LABELS.length, "design: 9 tuning rows sized");
            assertTrue("신규".equals(LiveTuning.valueText(7)), "design: tuning value text");
            // v1.5.1: 180-degree panel rotation
            MonitorConfig rc = new MonitorConfig();
            LiveTuning.loadFrom(rc);
            assertTrue(!LiveTuning.rotate180 && LiveTuning.panelRotation() == 270 && "기본".equals(LiveTuning.valueText(8)),
                    "rotate: default 270");
            LiveTuning.adjust(8, +1);
            assertTrue(LiveTuning.rotate180 && LiveTuning.panelRotation() == 90 && "180°".equals(LiveTuning.valueText(8))
                    && LiveTuning.label().endsWith(" · 180°"), "rotate: toggled " + LiveTuning.label());
            LiveTuning.saveTo(rc);
            assertTrue(rc.usbRotation == 90, "rotate: saved as usb_rotation=90");
            File rcf = File.createTempFile("turzx_cfg_rot", ".conf");
            rcf.deleteOnExit();
            rcf.delete();
            saveMonitorConfig(rcf, rc);
            LiveTuning.loadFrom(loadMonitorConfig(rcf));
            assertTrue(LiveTuning.rotate180, "rotate: roundtrip");
            rcf.delete();
            LiveTuning.loadFrom(new MonitorConfig());
            assertTrue(LiveTuning.ROW_LABELS.length == 9 && "화면 회전".equals(LiveTuning.ROW_LABELS[8]), "rotate: row label");
        } catch (Exception e) {
            throw new RuntimeException("v1.5.0 Tesla HUD self-test failed", e);
        }

        System.out.println("[TurzxMonitor] All TURZX DES-CBC USB + Chromium 1:1 HUD Assets + 4-Domain Telemetry + CVS1 + DATC + Media + GitHub OTA + DebugManager self-tests passed.");
    }
}
