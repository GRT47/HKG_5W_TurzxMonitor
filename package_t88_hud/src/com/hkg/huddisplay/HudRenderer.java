package com.hkg.huddisplay;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.opengl.Matrix;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Direct GPU-accelerated 1920x720 Tesla HUD renderer matching TurzxFrameRenderer.TeslaHud v1.5.16
 * with 60 FPS exponential smoothing and zero reflection overhead.
 */
public final class HudRenderer {

    public static final float HUD_W = 1920f;
    public static final float HUD_H = 720f;

    private static final float EGO_CAR_W = 114f, EGO_CAR_H = 123f, EGO_MARGIN = 12f;
    private static final float EGO_IMG_W = EGO_CAR_W + 2f * EGO_MARGIN;
    private static final float EGO_IMG_H = EGO_CAR_H + 2f * EGO_MARGIN;
    private static final float EGO_PITCH_DEG = 8f, EGO_LEN_M = 4.3f;
    private static final float PARK_W = 1440f, PARK_H = 880f;
    private static final float PARK_TRANS_SEC = 1.05f;
    private static final float OPEN_RGB_R = 0.2f;
    private static final int OPEN_RGB = 0xFFA630;
    private static final int TT_OFF = 0xFF45484F, TT_PBRAKE = 0xFFFF4D4D, TT_MIL = 0xFFF5A623;
    private static final float[][] DOOR_GEO = {
            {1.16f, 1.08f, -1f}, {1.16f, 1.08f, 1f}, {0.06f, 0.98f, -1f}, {0.06f, 0.98f, 1f}
    };

    private static final class Field {
        float x, y, size, maxW, unitSize, gap;
        boolean bold, unitBold;
        int color, unitColor;
        char align = 'L';
        String unit = "";
    }

    private static final class Layout {
        final Map<String, Field> fields = new HashMap<>();
        final Map<String, float[]> rects = new HashMap<>();
        final Map<String, Float> sizes = new HashMap<>();

        float[] rect(String k) {
            return rects.get(k);
        }
    }

    private static final class NavSprite {
        final String asset;
        final float pitch, w, h, ax, ay, ppm;
        Bitmap bmp, lamp;
        boolean tried;

        NavSprite(String asset, float pitch, float w, float h, float ax, float ay, float ppm) {
            this.asset = asset;
            this.pitch = pitch;
            this.w = w;
            this.h = h;
            this.ax = ax;
            this.ay = ay;
            this.ppm = ppm;
        }
    }

    private final AssetManager assets;
    private boolean initialized = false;
    private boolean assetsOk = false;

    private Layout layoutEv, layoutIce, curL;
    private Bitmap baseEv, baseIce, activeDark, baseEvLight, baseIceLight, activeLight, curBase, curActive;
    private Bitmap egoOff, egoTail, egoBrake, leadCar, parkCar;
    private Bitmap topHead, topTail;
    private Bitmap parkCache;
    private Canvas parkCanvas;
    private String parkKey = "";
    private int parkCacheW = 0, parkCacheH = 0;

    private NavSprite[][] navSets;
    private NavSprite navEgo, navTop;

    private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pShade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint pAdd = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pIcon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pStatus = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Paint pTintEgo, pTintLead, pTintOther, pLamp, pLampHead;
    private Paint pTintEgoLight, pTintLeadLight, pTintOtherLight, pLampLight, pLampHeadLight;
    private Paint pPubBold, pPubMed, pSysBold, pSysMed;

    private final RectF rf = new RectF();
    private final Rect rs = new Rect();
    private final Rect rd = new Rect();
    private final Path path = new Path();

    private boolean lineShadersBuilt = false;
    private boolean lineShadersLight = false;
    private final LinearGradient[] lineShaders = new LinearGradient[3];
    private LinearGradient roadShader;
    private final float[] roadXs = new float[25];
    private LinearGradient apShader;
    private int apShaderRgb = -1, apShaderA0 = -1;
    private float apShaderTopY = -1f;
    private RadialGradient shadowUnitDark, shadowUnitLight;

    private boolean lightTheme = false;
    private boolean lastStandby = false;
    private boolean lastIceMode = false;
    private long lastNs = 0L;
    private float phase = 0f;
    private float smoothSpeed = 0f;
    private float smoothSteer = 0f;
    private float smoothCurve = 0f;
    private float curEffCurve = 0f;
    private float curChaseYawDeg = 0f;
    private float smoothLeadDist = 0f;
    private float smoothLeadY = 0f;
    private float smoothKw = 0f;
    private float smoothRpm = 0f;
    private boolean smoothInit = false;
    private final float[] doorAnim = new float[6];
    private final float[] doorEased = new float[6];
    private float curDoorBlink = 0f;
    private final float[][] doorPlaced = new float[6][4];
    private boolean lastTbtActive = false;
    private float lastTbtX0, lastTbtY0, lastTbtX1, lastTbtY1;
    private float parkHeadAnim = 0f;
    private float parkBrakeAnim = 0f;
    private float parkFade = 0f;
    private boolean parkAnimInit = false;
    private float blinkerCycleSec = 0f;
    private boolean prevAnyBlinker = false;
    private float turnLeftAnim = 0f, turnRightAnim = 0f;
    private float blindLeftFade = 0f, blindRightFade = 0f;
    private float blindLeftDanger = 0f, blindRightDanger = 0f;
    private float blindLeftAnim = 0f, blindRightAnim = 0f;
    private volatile boolean forceParkView = false;
    private volatile String availableUpdateVer = "";

    public void setAvailableUpdateVersion(String ver) {
        this.availableUpdateVer = ver != null ? ver.trim() : "";
    }

    private final Object camLock = new Object();
    private static final float ORBIT_AUTO_RESET_SEC = 5.0f;
    private float parkYawDeg = 0f, parkPitchDeg = 0f, parkZoom = 1f;
    private float driveYawDeg = 0f, drivePitchDeg = 0f, driveZoom = 1f;
    private boolean driveResetting = false;
    private boolean driveAutoReturnArmed = false;
    private boolean parkAutoReturnArmed = false;
    private boolean orbitTouchActive = false;
    private float camOrbitIdleSec = 99f, driveOrbitIdleSec = 99f, camSimTimeSec = 0f;
    private boolean lastParkedState = false;
    private int prevDoorFocusMask = -1;
    private boolean doorFocusActive = false;
    private boolean doorFocusSuppressed = false;
    private float doorFocusParkYaw = 0f, doorFocusParkPitch = 0f, doorFocusParkZoom = 1f;

    private static final float[] DOOR_CAM_YAW   = {-114f, 114f, -64f, 64f, -18f, -168f};
    private static final float[] DOOR_CAM_PITCH = {  23f,  23f,  23f, 23f,  24f,   28f};
    private static final float[] DOOR_CAM_ZOOM  = {0.92f, 0.92f, 0.92f, 0.92f, 0.94f, 0.91f};
    private final float[] camViewMat = new float[16];
    private final float[] camProjMat = new float[16];
    private final float[] camVpMat = new float[16];
    private float camEyeX, camEyeY, camEyeZ, camProjScaleX;
    private final float[][] clipIn = new float[6][3];
    private final float[][] clipOut = new float[6][3];

    private static final int RIBBON_MAX_PTS = 2400;
    private static final float[] PATH_LAT_X = {-1.72f, -1.46f, -1.10f, 0.0f, 1.10f, 1.46f, 1.72f};
    private static final float[] PATH_LAT_A = {0.00f, 0.30f, 0.86f, 1.00f, 0.86f, 0.30f, 0.00f};
    private static final float[] PATH_LAT_C = {0.00f, 0.15f, 0.55f, 1.00f, 0.55f, 0.15f, 0.00f};
    private static final float[] PATH_BASE_S = {-24f, -15f, -8f, -3f, 0f, 3f, 7f, 12f, 18f, 25f, 33f, 42f, 52f, 66f, 82f};
    private final float[] ribbonVerts = new float[RIBBON_MAX_PTS * 2];
    private final int[] ribbonColors = new int[RIBBON_MAX_PTS];
    private final float[][] rClipIn = new float[6][5];
    private final float[][] rClipOut = new float[6][5];
    private final float[] pathS = new float[32];

    private static float wrapDeg180(float deg) {
        return ((deg + 180f) % 360f + 360f) % 360f - 180f;
    }

    public void setOrbitTouchActive(boolean active) {
        synchronized (camLock) {
            orbitTouchActive = active;
            if (!active) {
                if (driveAutoReturnArmed) driveOrbitIdleSec = 0f;
                if (parkAutoReturnArmed) camOrbitIdleSec = 0f;
            }
        }
    }

    public void addOrbitDelta(boolean parked, float dYawDeg, float dPitchDeg) {
        synchronized (camLock) {
            if (parked) {
                parkYawDeg = wrapDeg180(parkYawDeg + dYawDeg);
                parkPitchDeg = Math.max(-20f, Math.min(36f, parkPitchDeg + dPitchDeg));
                camOrbitIdleSec = 0f;
                parkAutoReturnArmed = true;
                doorFocusActive = false;
                doorFocusSuppressed = true;
            } else {
                driveResetting = false;
                driveYawDeg = wrapDeg180(driveYawDeg + dYawDeg);
                drivePitchDeg = Math.max(-2.5f, Math.min(56f, drivePitchDeg + dPitchDeg));
                driveOrbitIdleSec = 0f;
                driveAutoReturnArmed = true;
            }
        }
    }

    public void multiplyOrbitZoom(boolean parked, float scaleFactor) {
        if (scaleFactor <= 0.01f) return;
        synchronized (camLock) {
            if (parked) {
                parkZoom = Math.max(0.72f, Math.min(1.45f, parkZoom / scaleFactor));
                camOrbitIdleSec = 0f;
                parkAutoReturnArmed = true;
                doorFocusActive = false;
                doorFocusSuppressed = true;
            } else {
                driveResetting = false;
                driveZoom = Math.max(0.55f, Math.min(1.50f, driveZoom / scaleFactor));
                driveOrbitIdleSec = 0f;
                driveAutoReturnArmed = true;
            }
        }
    }

    public void setOrbitAngles(boolean parked, float yawDeg, float pitchDeg) {
        synchronized (camLock) {
            if (parked) {
                parkYawDeg = wrapDeg180(yawDeg);
                parkPitchDeg = Math.max(-20f, Math.min(36f, pitchDeg));
                parkZoom = 1f;
                camOrbitIdleSec = 0f;
                parkAutoReturnArmed = false;
                doorFocusActive = false;
                doorFocusSuppressed = (Math.abs(parkYawDeg) > 0.1f || Math.abs(parkPitchDeg) > 0.1f);
            } else {
                driveResetting = false;
                driveAutoReturnArmed = false;
                driveOrbitIdleSec = 0f;
                driveYawDeg = wrapDeg180(yawDeg);
                drivePitchDeg = Math.max(-2.5f, Math.min(56f, pitchDeg));
                driveZoom = 1f;
            }
        }
    }

    private void computeDoorFocusTarget(int doorMask) {
        float sumSin = 0f, sumCos = 0f, sumPitch = 0f, sumZoom = 0f;
        int cnt = 0;
        for (int i = 0; i < 6; i++) {
            if ((doorMask & (1 << i)) != 0) {
                double rad = Math.toRadians(DOOR_CAM_YAW[i]);
                sumSin += (float) Math.sin(rad);
                sumCos += (float) Math.cos(rad);
                sumPitch += DOOR_CAM_PITCH[i];
                sumZoom += DOOR_CAM_ZOOM[i];
                cnt++;
            }
        }
        if (cnt == 0) {
            doorFocusParkYaw = 0f;
            doorFocusParkPitch = 0f;
            doorFocusParkZoom = 1f;
            return;
        }
        float mag = (float) Math.hypot(sumSin, sumCos) / cnt;
        if (mag >= 0.48f) {
            float tgtWorldYaw = (float) Math.toDegrees(Math.atan2(sumSin, sumCos));
            doorFocusParkYaw = wrapDeg180(tgtWorldYaw + 146.0f);
            doorFocusParkPitch = Math.max(-20f, Math.min(36f, (sumPitch / cnt) - 27.0f));
            doorFocusParkZoom = Math.max(0.72f, Math.min(1.45f, sumZoom / cnt));
        } else {
            // Opposite-side or all-doors open: elevated 3/4 overview showing both sides + hood + trunk
            doorFocusParkYaw = 0f;
            doorFocusParkPitch = 17.0f;
            doorFocusParkZoom = 1.08f;
        }
    }

    public void resetDriveOrbit() {
        synchronized (camLock) {
            driveResetting = true;
            driveAutoReturnArmed = false;
        }
    }

    public boolean isDriveOrbitActive() {
        synchronized (camLock) {
            return !lastParkedState && (Math.abs(driveYawDeg) > 1.5f
                    || Math.abs(drivePitchDeg) > 1.5f || Math.abs(driveZoom - 1f) > 0.04f);
        }
    }

    public boolean isLastParked() {
        synchronized (camLock) {
            return lastParkedState;
        }
    }

    public boolean isLastStandby() {
        return lastStandby;
    }

    public boolean toggleForceParkView() {
        forceParkView = !forceParkView;
        return forceParkView;
    }

    public boolean isForceParkView() {
        return forceParkView;
    }

    public boolean isInTpmsRect(float nx, float ny) {
        if (curL == null) return false;
        float[] vz = curL.rect("viz");
        float[] tr = curL.rect("tpms");
        float vx = vz != null ? vz[0] : 16f, vy = vz != null ? vz[1] : 16f;
        float vw = vz != null ? vz[2] : 864f, vh = vz != null ? vz[3] : 576f;
        float tx = tr != null ? tr[0] : 679.8f, ty = tr != null ? tr[1] : 450.0f;
        float tw = tr != null ? tr[2] : 184.3f, th = tr != null ? tr[3] : 126.0f;
        float x = vx + nx * vw;
        float y = vy + ny * vh;
        return x >= tx - 6f && x <= tx + tw + 6f && y >= ty - 6f && y <= ty + th + 6f;
    }

    public HudRenderer(AssetManager assets) {
        this.assets = assets;
    }

    private void ensureInit() {
        if (initialized) return;
        initialized = true;

        pFill.setStyle(Paint.Style.FILL);
        pShade.setStyle(Paint.Style.FILL);
        pStroke.setStyle(Paint.Style.STROKE);
        pAdd.setStyle(Paint.Style.FILL);
        pAdd.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        pIcon.setStyle(Paint.Style.STROKE);
        pIcon.setStrokeCap(Paint.Cap.ROUND);
        pIcon.setStrokeJoin(Paint.Join.ROUND);

        Typeface pubBold = loadTypeface("PublicSans-Bold.ttf", Typeface.DEFAULT_BOLD);
        Typeface pubMed = loadTypeface("PublicSans-Medium.ttf", Typeface.SANS_SERIF);
        pPubBold = makeTextPaint(pubBold, true);
        pPubMed = makeTextPaint(pubMed, false);
        pSysBold = makeTextPaint(Typeface.DEFAULT_BOLD, true);
        pSysMed = makeTextPaint(Typeface.DEFAULT, false);

        pTintEgo = makeTintPaint(236, 239, 244, 1.12f);
        pTintLead = makeTintPaint(214, 219, 227, 1.08f);
        pTintOther = makeTintPaint(150, 156, 166, 0.92f);
        pLamp = makeLampPaint(0xFFFF2638, true);
        pLampHead = makeLampPaint(0xFFE1EEFF, true);

        pTintEgoLight = makeTintPaint(210, 218, 232, 0.88f);
        pTintLeadLight = makeTintPaint(168, 178, 194, 0.82f);
        pTintOtherLight = makeTintPaint(132, 142, 158, 0.72f);
        pLampLight = makeLampPaint(0xFFE11D48, false);
        pLampHeadLight = makeLampPaint(0xFF2563EB, false);

        layoutEv = parseLayout(readAssetText("design_tesla/tesla_layout_123.txt"));
        layoutIce = parseLayout(readAssetText("design_tesla/tesla_layout_ice_123.txt"));
        baseEv = loadAssetBitmap("design_tesla/tesla_base_720.png");
        baseIce = loadAssetBitmap("design_tesla/tesla_base_ice_720.png");
        activeDark = loadAssetBitmap("design_tesla/tesla_active_720.png");
        egoOff = loadAssetBitmap("design_tesla/tesla_ego_off.png");
        egoTail = loadAssetBitmap("design_tesla/tesla_ego_tail.png");
        egoBrake = loadAssetBitmap("design_tesla/tesla_ego_brake.png");
        leadCar = loadAssetBitmap("design_tesla/tesla_lead.png");
        parkCar = loadAssetBitmap("design_tesla/tesla_park.png");

        parseNavMeta(readAssetText("design_tesla/navdy_meta.txt"));
        assetsOk = (layoutEv != null && baseEv != null && activeDark != null);
    }

    public void render(Canvas c, int viewW, int viewH, HudState s, int themePref, int ptPref,
                       boolean standbyOnScreenOff, boolean showStatusPill, double fps, boolean demoMode) {
        renderBase(c, viewW, viewH, s, themePref, ptPref, standbyOnScreenOff, false, 0, demoMode, null);
        renderOverlays(c, viewW, viewH, s, fps, demoMode, showStatusPill, false, null);
    }

    public boolean renderBase(Canvas c, int viewW, int viewH, HudState s, int themePref, int ptPref,
                              boolean standbyOnScreenOff, boolean external3dCar, int bodyColorId,
                              boolean demoMode, Car3dState out3dState) {
        boolean active = stepSimulation(s, themePref, ptPref, standbyOnScreenOff, external3dCar, bodyColorId, demoMode, out3dState);
        renderDashboardOnly(c, viewW, viewH, s);
        if (active && curL != null) {
            float[] vz = curL.rect("viz");
            if (vz != null) {
                int sc = c.save();
                c.scale(viewW / HUD_W, viewH / HUD_H);
                int scVz = c.save();
                path.reset();
                rf.set(vz[0], vz[1], vz[0] + vz[2], vz[1] + vz[3]);
                float rad = vz.length > 4 ? vz[4] : 28f;
                path.addRoundRect(rf, rad, rad, Path.Direction.CW);
                c.clipPath(path);
                drawVizBgContent(c, s, vz, external3dCar);
                c.restoreToCount(scVz);
                c.restoreToCount(sc);
            }
        }
        return active;
    }

    public boolean isLightThemeActive() {
        return lightTheme;
    }

    public boolean isIceModeActive() {
        return lastIceMode;
    }

    public boolean isParkTransitioning() {
        return parkFade > 0.001f && parkFade < 0.999f;
    }

    public boolean stepSimulation(HudState s, int themePref, int ptPref, boolean standbyOnScreenOff,
                                  boolean external3dCar, int bodyColorId, boolean demoMode, Car3dState out3dState) {
        return stepSimulation(0L, s, themePref, ptPref, standbyOnScreenOff, external3dCar, bodyColorId, demoMode, out3dState);
    }

    public boolean stepSimulation(long frameTimeNanos, HudState s, int themePref, int ptPref,
                                  boolean standbyOnScreenOff, boolean external3dCar, int bodyColorId,
                                  boolean demoMode, Car3dState out3dState) {
        ensureInit();
        if (!assetsOk) {
            lastStandby = true;
            if (out3dState != null) out3dState.visible = false;
            return false;
        }

        long nowNs = frameTimeNanos > 0L ? frameTimeNanos : System.nanoTime();
        float dt = (lastNs == 0L) ? 0.016f : Math.min(0.1f, Math.max(0.001f, (nowNs - lastNs) / 1e9f));
        lastNs = nowNs;

        if (standbyOnScreenOff && s.screenOff) {
            lastStandby = true;
            if (out3dState != null) out3dState.visible = false;
            return false;
        }
        lastStandby = false;

        if (!smoothInit) {
            smoothInit = true;
            smoothSpeed = s.speedKph;
            smoothSteer = s.steerDeg;
            smoothCurve = s.curveC2;
            smoothLeadDist = s.leadDistM;
            smoothLeadY = s.leadYRightM;
            smoothKw = (float) s.totalKw;
            smoothRpm = Math.max(0, s.rpm);
        } else {
            float aFast = 1f - (float) Math.exp(-dt * 14f);
            float aMed = 1f - (float) Math.exp(-dt * 9f);
            smoothSpeed += (s.speedKph - smoothSpeed) * aFast;
            smoothSteer += (s.steerDeg - smoothSteer) * aFast;
            smoothCurve += (s.curveC2 - smoothCurve) * aMed;
            smoothLeadDist += (s.leadDistM - smoothLeadDist) * aMed;
            smoothLeadY += (s.leadYRightM - smoothLeadY) * aMed;
            smoothKw += ((float) s.totalKw - smoothKw) * aMed;
            if (s.rpm >= 0) smoothRpm += (s.rpm - smoothRpm) * aMed;
        }
        String gearNow = (s.gearPos != null && !s.gearPos.isEmpty()) ? s.gearPos : "D";
        boolean parkedNow = "P".equals(gearNow) || forceParkView;
        if (!parkAnimInit) {
            parkAnimInit = true;
            for (int i = 0; i < 6; i++) {
                doorAnim[i] = s.doors[i] ? 1f : 0f;
                doorEased[i] = doorAnim[i];
            }
            parkHeadAnim = s.headLamp ? 1f : 0f;
            parkBrakeAnim = s.brakeLamp ? 1f : 0f;
            parkFade = parkedNow ? 1f : 0f;
        } else {
            float doorStep = dt / 0.38f;
            float lampStep = dt / 0.28f;
            float parkStep = dt / PARK_TRANS_SEC;
            for (int i = 0; i < 6; i++) {
                float tgt = s.doors[i] ? 1f : 0f;
                float cur = doorAnim[i];
                if (cur < tgt) cur = Math.min(tgt, cur + doorStep);
                else if (cur > tgt) cur = Math.max(tgt, cur - doorStep);
                doorAnim[i] = cur;
                doorEased[i] = cur * cur * (3f - 2f * cur);
            }
            float hTgt = s.headLamp ? 1f : 0f;
            if (parkHeadAnim < hTgt) parkHeadAnim = Math.min(hTgt, parkHeadAnim + lampStep);
            else if (parkHeadAnim > hTgt) parkHeadAnim = Math.max(hTgt, parkHeadAnim - lampStep);
            float bTgt = s.brakeLamp ? 1f : 0f;
            if (parkBrakeAnim < bTgt) parkBrakeAnim = Math.min(bTgt, parkBrakeAnim + lampStep);
            else if (parkBrakeAnim > bTgt) parkBrakeAnim = Math.max(bTgt, parkBrakeAnim - lampStep);
            float pTgt = parkedNow ? 1f : 0f;
            if (parkFade < pTgt) parkFade = Math.min(pTgt, parkFade + parkStep);
            else if (parkFade > pTgt) parkFade = Math.max(pTgt, parkFade - parkStep);
        }

        boolean anyBlinker = s.leftBlinker || s.rightBlinker;
        if (anyBlinker) {
            if (!prevAnyBlinker) blinkerCycleSec = 0f;
            else blinkerCycleSec = (blinkerCycleSec + dt) % 0.72f;
        } else {
            blinkerCycleSec = 0f;
        }
        prevAnyBlinker = anyBlinker;
        boolean flashOn = anyBlinker && (blinkerCycleSec < 0.37f);
        float turnRise = dt / 0.045f;
        float turnFall = dt / 0.065f;
        float tLTgt = (s.leftBlinker && flashOn) ? 1f : 0f;
        if (turnLeftAnim < tLTgt) turnLeftAnim = Math.min(tLTgt, turnLeftAnim + turnRise);
        else if (turnLeftAnim > tLTgt) turnLeftAnim = Math.max(tLTgt, turnLeftAnim - turnFall);
        float tRTgt = (s.rightBlinker && flashOn) ? 1f : 0f;
        if (turnRightAnim < tRTgt) turnRightAnim = Math.min(tRTgt, turnRightAnim + turnRise);
        else if (turnRightAnim > tRTgt) turnRightAnim = Math.max(tRTgt, turnRightAnim - turnFall);

        boolean bsdAllowed = !"P".equals(gearNow);
        boolean bsdL = bsdAllowed && s.leftBlindspot;
        boolean bsdR = bsdAllowed && s.rightBlindspot;
        float bsdUp = dt / 0.16f;
        float bsdDown = dt / 0.24f;
        if (blindLeftFade < (bsdL ? 1f : 0f)) blindLeftFade = Math.min(1f, blindLeftFade + bsdUp);
        else if (blindLeftFade > (bsdL ? 1f : 0f)) blindLeftFade = Math.max(0f, blindLeftFade - bsdDown);
        if (blindRightFade < (bsdR ? 1f : 0f)) blindRightFade = Math.min(1f, blindRightFade + bsdUp);
        else if (blindRightFade > (bsdR ? 1f : 0f)) blindRightFade = Math.max(0f, blindRightFade - bsdDown);
        float dngStep = dt / 0.10f;
        float dLTgt = (bsdL && s.leftBlinker) ? 1f : 0f;
        if (blindLeftDanger < dLTgt) blindLeftDanger = Math.min(dLTgt, blindLeftDanger + dngStep);
        else if (blindLeftDanger > dLTgt) blindLeftDanger = Math.max(dLTgt, blindLeftDanger - dngStep);
        float dRTgt = (bsdR && s.rightBlinker) ? 1f : 0f;
        if (blindRightDanger < dRTgt) blindRightDanger = Math.min(dRTgt, blindRightDanger + dngStep);
        else if (blindRightDanger > dRTgt) blindRightDanger = Math.max(dRTgt, blindRightDanger - dngStep);

        float speedSign = "R".equals(gearNow) ? -1f : 1f;
        phase = ((phase + speedSign * (Math.max(0f, smoothSpeed) / 3.6f) * dt) % 7000f + 7000f) % 7000f;

        boolean light = s.isLightTheme(themePref);
        lightTheme = light;
        boolean iceMode = s.isIceMode(ptPref);
        lastIceMode = iceMode;
        curL = (iceMode && layoutIce != null && !layoutIce.fields.isEmpty()) ? layoutIce : layoutEv;

        if (light) {
            if (iceMode) {
                if (baseIceLight == null && baseIce != null) baseIceLight = deriveLightBitmap(baseIce, false, curL);
            } else {
                if (baseEvLight == null && baseEv != null) baseEvLight = deriveLightBitmap(baseEv, false, layoutEv);
            }
            if (activeLight == null && activeDark != null) activeLight = deriveLightBitmap(activeDark, true, layoutEv);
            curBase = (iceMode && baseIceLight != null) ? baseIceLight
                    : (baseEvLight != null ? baseEvLight : ((iceMode && baseIce != null) ? baseIce : baseEv));
            curActive = activeLight != null ? activeLight : activeDark;
        } else {
            curBase = (iceMode && baseIce != null) ? baseIce : baseEv;
            curActive = activeDark;
        }

        String gear = (s.gearPos != null && !s.gearPos.isEmpty()) ? s.gearPos : "D";
        boolean parked = "P".equals(gear) || forceParkView;

        float effCurve = Math.max(-0.0025f, Math.min(0.0025f, smoothCurve));
        if (Math.abs(effCurve) < 1e-6f && Math.abs(smoothSteer) > 0.05f) {
            effCurve = Math.max(-0.0025f, Math.min(0.0025f, -smoothSteer * 0.000025f));
        }
        curEffCurve = effCurve;
        curChaseYawDeg = parked ? 0f : Math.max(-6.5f, Math.min(6.5f, (curEffCurve / 0.0010f) * 4.2f));

        float pb = parkFade * parkFade * parkFade * (parkFade * (parkFade * 6f - 15f) + 10f);
        synchronized (camLock) {
            camSimTimeSec += dt;
            if (!orbitTouchActive) {
                camOrbitIdleSec = Math.min(99f, camOrbitIdleSec + dt);
                driveOrbitIdleSec = Math.min(99f, driveOrbitIdleSec + dt);
            }
            int doorMask = 0;
            for (int i = 0; i < 6; i++) {
                if (s.doors[i]) doorMask |= (1 << i);
            }
            if (prevDoorFocusMask < 0) {
                prevDoorFocusMask = doorMask;
                if (doorMask != 0 && parked) {
                    computeDoorFocusTarget(doorMask);
                    doorFocusActive = true;
                }
            } else if (doorMask != prevDoorFocusMask) {
                prevDoorFocusMask = doorMask;
                doorFocusSuppressed = false;
                parkAutoReturnArmed = false;
                if (doorMask == 0) {
                    doorFocusParkYaw = 0f;
                    doorFocusParkPitch = 0f;
                    doorFocusParkZoom = 1f;
                    doorFocusActive = true;
                } else {
                    computeDoorFocusTarget(doorMask);
                    doorFocusActive = true;
                }
            } else if (!lastParkedState && parked && doorMask != 0 && !doorFocusSuppressed) {
                computeDoorFocusTarget(doorMask);
                doorFocusActive = true;
            }
            if (parkAutoReturnArmed && !orbitTouchActive && camOrbitIdleSec >= ORBIT_AUTO_RESET_SEC) {
                parkAutoReturnArmed = false;
                doorFocusSuppressed = false;
                computeDoorFocusTarget(doorMask);
                doorFocusActive = true;
            }
            if (doorFocusActive && !doorFocusSuppressed) {
                float k = 1f - (float) Math.exp(-dt * 4.8f);
                float dYaw = wrapDeg180(doorFocusParkYaw - parkYawDeg);
                parkYawDeg = wrapDeg180(parkYawDeg + dYaw * k);
                parkPitchDeg = Math.max(-20f, Math.min(36f, parkPitchDeg + (doorFocusParkPitch - parkPitchDeg) * k));
                parkZoom = Math.max(0.72f, Math.min(1.45f, parkZoom + (doorFocusParkZoom - parkZoom) * k));
                if (Math.abs(dYaw) < 0.15f && Math.abs(doorFocusParkPitch - parkPitchDeg) < 0.15f
                        && Math.abs(doorFocusParkZoom - parkZoom) < 0.005f) {
                    parkYawDeg = doorFocusParkYaw;
                    parkPitchDeg = doorFocusParkPitch;
                    parkZoom = doorFocusParkZoom;
                    doorFocusActive = false;
                }
            }
            lastParkedState = parked;
            float doorHz = parked ? 1.55f : 2.25f;
            float rawB = 0.5f + 0.5f * (float) Math.sin(camSimTimeSec * Math.PI * 2.0 * doorHz);
            float bt = Math.max(0f, Math.min(1f, (rawB - 0.18f) / 0.64f));
            curDoorBlink = bt * bt * (3f - 2f * bt);
            float bsdCalm = 0.65f + 0.35f * (0.5f + 0.5f * (float) Math.sin(camSimTimeSec * Math.PI * 2.0 * 1.55));
            float rawUrg = 0.5f + 0.5f * (float) Math.sin(camSimTimeSec * Math.PI * 2.0 * 3.4);
            float ut = Math.max(0f, Math.min(1f, (rawUrg - 0.15f) / 0.70f));
            float bsdUrg = 0.25f + 0.75f * (ut * ut * (3f - 2f * ut));
            blindLeftAnim = blindLeftFade * (bsdCalm + (bsdUrg - bsdCalm) * blindLeftDanger);
            blindRightAnim = blindRightFade * (bsdCalm + (bsdUrg - bsdCalm) * blindRightDanger);
            if (driveAutoReturnArmed && !orbitTouchActive && driveOrbitIdleSec >= ORBIT_AUTO_RESET_SEC) {
                driveAutoReturnArmed = false;
                driveResetting = true;
            }
            if (driveResetting) {
                float dec = (float) Math.exp(-dt * 5.2f);
                driveYawDeg *= dec;
                drivePitchDeg *= dec;
                driveZoom = 1f + (driveZoom - 1f) * dec;
                if (Math.abs(driveYawDeg) < 0.15f && Math.abs(drivePitchDeg) < 0.15f && Math.abs(driveZoom - 1f) < 0.005f) {
                    driveYawDeg = 0f;
                    drivePitchDeg = 0f;
                    driveZoom = 1f;
                    driveResetting = false;
                }
            }

            float omRaw = Math.min(1f, (float) Math.hypot(driveYawDeg / 35f, drivePitchDeg / 18f));
            float om = omRaw * omRaw * (3f - 2f * omRaw);
            float dTgtX = -0.24f * (1f - om);
            float dTgtY = 3.10f + (0.65f - 3.10f) * om;
            float dTgtZ = 0.0f;
            float birdExtra = Math.max(0f, drivePitchDeg - 18f) * 0.22f;
            float dDist = (float) (23.0 * Math.pow((12.5 + birdExtra) / 23.0, om)) * driveZoom;
            float dYawDeg = wrapDeg180(driveYawDeg - curChaseYawDeg * (1f - om * 0.7f));
            float dPitchDeg = 3.60f + drivePitchDeg;
            float dFov = 20.0f + (32.0f - 20.0f) * om;

            float demoSway = (demoMode && doorMask == 0 && !doorFocusActive
                    && !parkAutoReturnArmed && !orbitTouchActive && camOrbitIdleSec > 3.5f)
                    ? (float) Math.sin(camSimTimeSec * 0.55) * 14f
                    : 0f;
            float pTgtX = -0.14f, pTgtY = 0.44f, pTgtZ = 0.02f;
            float pDist = 6.35f * parkZoom;
            float pYawDeg = wrapDeg180(-146.0f + parkYawDeg + demoSway);
            float pPitchDeg = 27.0f + parkPitchDeg;
            float pFov = 34.0f;

            float tgtX = dTgtX + (pTgtX - dTgtX) * pb;
            float tgtY = dTgtY + (pTgtY - dTgtY) * pb;
            float tgtZ = dTgtZ + (pTgtZ - dTgtZ) * pb;
            float yawDelta = wrapDeg180(pYawDeg - dYawDeg);
            double yawRad = Math.toRadians(dYawDeg + yawDelta * pb);
            double pitchRad = Math.toRadians(dPitchDeg + (pPitchDeg - dPitchDeg) * pb);
            float dist = (float) (dDist * Math.pow(pDist / dDist, pb));
            camEyeX = tgtX + (float) (dist * Math.cos(pitchRad) * Math.sin(yawRad));
            camEyeY = tgtY + (float) (dist * Math.sin(pitchRad));
            camEyeZ = tgtZ + (float) (dist * Math.cos(pitchRad) * Math.cos(yawRad));
            float fov = dFov + (pFov - dFov) * pb;

            Matrix.setLookAtM(camViewMat, 0, camEyeX, camEyeY, camEyeZ, tgtX, tgtY, tgtZ, 0f, 1f, 0f);
            Matrix.perspectiveM(camProjMat, 0, fov, 864f / 576f, 0.8f, 220f);
            Matrix.multiplyMM(camVpMat, 0, camProjMat, 0, camViewMat, 0);
            camProjScaleX = camProjMat[0];

            if (out3dState != null) {
                System.arraycopy(camVpMat, 0, out3dState.vpMat, 0, 16);
                out3dState.eyeX = camEyeX;
                out3dState.eyeY = camEyeY;
                out3dState.eyeZ = camEyeZ;
                out3dState.parkBlend = pb;
                out3dState.hasSharedCamera = true;
            }
        }

        if (out3dState != null) {
            out3dState.speedKph = smoothSpeed;
            out3dState.steerDeg = smoothSteer;
            out3dState.curveC2 = curEffCurve;
            out3dState.chaseYawDeg = curChaseYawDeg;
            out3dState.parked = parked;
            out3dState.reverse = "R".equals(gear);
            out3dState.lightTheme = light;
            out3dState.headAnim = parkHeadAnim * parkHeadAnim * (3f - 2f * parkHeadAnim);
            out3dState.brakeAnim = parkBrakeAnim * parkBrakeAnim * (3f - 2f * parkBrakeAnim);
            out3dState.turnLeftAnim = turnLeftAnim;
            out3dState.turnRightAnim = turnRightAnim;
            out3dState.blindLeftAnim = blindLeftAnim;
            out3dState.blindRightAnim = blindRightAnim;
            out3dState.blindLeftDanger = blindLeftDanger;
            out3dState.blindRightDanger = blindRightDanger;
            System.arraycopy(doorEased, 0, out3dState.doorEased, 0, 6);
            out3dState.doorBlink = curDoorBlink;
            out3dState.bodyColorId = bodyColorId;
            out3dState.demoMode = demoMode;
            out3dState.visible = external3dCar;
            out3dState.frameTimeNanos = nowNs;
        }
        return true;
    }

    public int computeDashboardHash(HudState s) {
        if (s == null) return 0;
        int h = 17;
        h = 31 * h + (lastStandby ? 1 : 0);
        h = 31 * h + (lightTheme ? 1 : 0);
        h = 31 * h + (lastIceMode ? 1 : 0);
        if (lastIceMode) {
            h = 31 * h + s.fuelPct;
            h = 31 * h + s.iceDteKm;
            h = 31 * h + Math.round(smoothRpm / 50f);
            h = 31 * h + s.coolantC;
            h = 31 * h + (int) Math.round(s.odoKm);
            h = 31 * h + (int) Math.round(s.tripKm * 10.0);
        } else {
            h = 31 * h + (int) Math.round(s.socPct * 10.0);
            h = 31 * h + s.dteKm;
            h = 31 * h + Math.round(smoothKw * 5f);
            h = 31 * h + (int) Math.round(s.remainKwh * 10.0);
            h = 31 * h + (int) Math.round(s.battTempC * 2.0);
        }
        h = 31 * h + (s.mediaTitle != null ? s.mediaTitle.hashCode() : 0);
        h = 31 * h + (s.mediaArtist != null ? s.mediaArtist.hashCode() : 0);
        h = 31 * h + s.mediaPosSec + 31 * s.mediaDurSec + (s.mediaPlaying ? 1 : 0);
        h = 31 * h + (s.hasAlbumArt ? 1 : 0);
        h = 31 * h + s.albumArtSeq;
        h = 31 * h + System.identityHashCode(s.albumArt);
        h = 31 * h + s.hvacBlowerStep + 16 * s.hvacBlowerMode;
        h = 31 * h + (s.hvacAc ? 1 : 0) + (s.hvacAuto ? 2 : 0) + (s.hvacHeat ? 4 : 0)
                + (s.hvacFrontDefog ? 8 : 0) + (s.hvacRearDefog ? 16 : 0) + (s.hvacRecirc ? 32 : 0);
        h = 31 * h + (int) Math.round(s.hvacDrTempC * 2.0)
                + 100 * (int) Math.round(s.inCarTempC * 2.0)
                + 10000 * (int) Math.round(s.outdoorTempC * 2.0);
        return h;
    }

    public void renderDashboardOnly(Canvas c, int viewW, int viewH, HudState s) {
        ensureInit();
        if (viewW <= 0 || viewH <= 0) return;
        if (!assetsOk) {
            c.drawColor(0xFF0A0B0D);
            pStatus.setColor(0xFFF5F5F7);
            pStatus.setTextSize(32f);
            c.drawText("HUD assets missing", 40f, 80f, pStatus);
            return;
        }
        if (lastStandby) {
            c.drawColor(0xFF000000);
            pStatus.setColor(0xFF3E434E);
            pStatus.setTextSize(22f);
            pStatus.setTextAlign(Paint.Align.CENTER);
            c.drawText("시동 OFF · 절전 대기 중 (화면 탭하여 설정)", viewW * 0.5f, viewH * 0.52f, pStatus);
            pStatus.setTextAlign(Paint.Align.LEFT);
            return;
        }
        if (curL == null || curBase == null || s == null) return;
        int sc = c.save();
        c.scale(viewW / HUD_W, viewH / HUD_H);
        c.drawBitmap(curBase, 0f, 0f, null);
        if (lastIceMode) drawIceFuel(c, s);
        else drawBattery(c, s);
        drawMedia(c, s);
        drawClimate(c, s);
        c.restoreToCount(sc);
    }

    public void renderVizBg(Canvas c, int viewW, int viewH, HudState s, boolean external3dCar) {
        ensureInit();
        if (lastStandby || !assetsOk || curL == null || curBase == null || viewW <= 0 || viewH <= 0 || s == null) return;
        float[] vz = curL.rect("viz");
        if (vz == null) return;
        int sc = c.save();
        c.scale(viewW / vz[2], viewH / vz[3]);
        c.translate(-vz[0], -vz[1]);
        int x0 = Math.round(vz[0]), y0 = Math.round(vz[1]);
        int x1 = Math.round(vz[0] + vz[2]), y1 = Math.round(vz[1] + vz[3]);
        rs.set(x0, y0, x1, y1);
        rf.set(vz[0], vz[1], vz[0] + vz[2], vz[1] + vz[3]);
        c.drawBitmap(curBase, rs, rf, pBmp);
        drawVizBgContent(c, s, vz, external3dCar);
        c.restoreToCount(sc);
    }

    private void drawVizBgContent(Canvas c, HudState s, float[] vz, boolean external3dCar) {
        String gear = (s.gearPos != null && !s.gearPos.isEmpty()) ? s.gearPos : "D";
        boolean parked = "P".equals(gear) || forceParkView;
        int spdDisp = Math.max(0, Math.round(smoothSpeed));
        if (external3dCar) {
            float pb = parkFade * parkFade * parkFade * (parkFade * (parkFade * 6f - 15f) + 10f);
            if (pb > 0.01f && !lightTheme) {
                glow(c, vz[0] + vz[2] * 0.54f, vz[1] + vz[3] * 0.56f, vz[2] * 0.54f, 0xFFFFFF, 0.08f * pb);
            }
            float roadAlpha = Math.max(0f, Math.min(1f, 1f - pb * 1.45f));
            if (roadAlpha >= 0.995f) {
                drawRoad3d(c, s, vz, gear, spdDisp);
            } else if (roadAlpha > 0.01f) {
                int sc = c.saveLayerAlpha(vz[0], vz[1], vz[0] + vz[2], vz[1] + vz[3], Math.round(roadAlpha * 255f));
                drawRoad3d(c, s, vz, gear, spdDisp);
                c.restoreToCount(sc);
            }
        } else if (parked) {
            drawParkedCached(c, vz, s.headLamp, s.brakeLamp, lastIceMode, s.doors);
        } else {
            drawRoad(c, s, vz, gear, spdDisp, true);
        }
    }

    public void renderOverlays(Canvas c, int viewW, int viewH, HudState s, double fps,
                               boolean demoMode, boolean showStatusPill, boolean external3dCar, float[] doorMarkersUv) {
        renderOverlays(c, viewW, viewH, 0f, 0f, HUD_W, HUD_H, s, fps, demoMode, showStatusPill, external3dCar, doorMarkersUv);
    }

    public void renderOverlays(Canvas c, int viewW, int viewH,
                               float hudOffX, float hudOffY, float hudSpanW, float hudSpanH,
                               HudState s, double fps, boolean demoMode, boolean showStatusPill,
                               boolean external3dCar, float[] doorMarkersUv) {
        ensureInit();
        if (lastStandby || !assetsOk || curL == null || viewW <= 0 || viewH <= 0 || s == null) return;
        int sc = c.save();
        c.scale(viewW / hudSpanW, viewH / hudSpanH);
        c.translate(-hudOffX, -hudOffY);
        String gear = (s.gearPos != null && !s.gearPos.isEmpty()) ? s.gearPos : "D";
        boolean drivingGear = !"P".equals(gear);
        int spdDisp = Math.max(0, Math.round(smoothSpeed));
        float[] vz = curL.rect("viz");

        boolean anyDoor = hasAnyOpenDoor();
        boolean driveOrbit = external3dCar && isDriveOrbitActive();
        boolean anyBlinker = s.leftBlinker || s.rightBlinker || turnLeftAnim > 0.01f || turnRightAnim > 0.01f;
        if (vz != null && (blindLeftFade > 0.01f || blindRightFade > 0.01f)) {
            drawBlindspotOverlay(c, vz);
        }
        drawVizOverlays(c, s, vz, gear, spdDisp, !drivingGear, lastIceMode);
        if (vz != null && anyBlinker) {
            drawTurnSignals(c, s, vz);
        }
        if (anyDoor && external3dCar && vz != null && doorMarkersUv != null) {
            drawDoorMarkers3d(c, vz, doorMarkersUv, drivingGear);
        }
        if (driveOrbit && vz != null) {
            drawDriveResetPill(c, vz, anyBlinker);
        }
        if (anyDoor && vz != null) {
            drawDoorAlertBanner(c, vz, drivingGear, driveOrbit, anyBlinker);
        }
        if (showStatusPill || (!demoMode && !s.hubConnected)) {
            drawStatusPill(c, s, fps, demoMode);
        }
        c.restoreToCount(sc);
    }

    private void drawTurnSignals(Canvas c, HudState s, float[] vz) {
        float cx = vz[0] + vz[2] * 0.50f;
        float cy = vz[1] + 54f;
        int arrowOnRgb = lightTheme ? 0x15803D : 0x22C55E;
        int arrowOffRgb = lightTheme ? 0x94A3B8 : 0x2E3833;
        int bgCol = lightTheme ? 0xD8FFFFFF : 0xBA10141C;

        for (int side = -1; side <= 1; side += 2) {
            boolean active = side < 0 ? s.leftBlinker : s.rightBlinker;
            float anim = side < 0 ? turnLeftAnim : turnRightAnim;
            if (!active && anim <= 0.01f) continue;

            float ax = cx + side * 44f;
            roundRect(c, ax - 22f, cy - 15f, ax + 22f, cy + 15f, 12f, bgCol);
            pStroke.setColor(withAlpha(blendRgb(arrowOffRgb, arrowOnRgb, anim), 0.35f + 0.55f * anim));
            pStroke.setStrokeWidth(1.4f);
            rf.set(ax - 22f, cy - 15f, ax + 22f, cy + 15f);
            c.drawRoundRect(rf, 12f, 12f, pStroke);

            if (anim > 0.02f) {
                glow(c, ax, cy, 28f, arrowOnRgb, (lightTheme ? 0.30f : 0.45f) * anim);
            }

            path.reset();
            path.moveTo(ax + side * 12f, cy);
            path.lineTo(ax, cy - 9.5f);
            path.lineTo(ax, cy - 4.5f);
            path.lineTo(ax - side * 11f, cy - 4.5f);
            path.lineTo(ax - side * 11f, cy + 4.5f);
            path.lineTo(ax, cy + 4.5f);
            path.lineTo(ax, cy + 9.5f);
            path.close();

            int onFillRgb = lightTheme ? arrowOnRgb : blendRgb(arrowOnRgb, 0xBBF7D0, 0.35f);
            int fillRgb = blendRgb(arrowOffRgb, onFillRgb, anim);
            pFill.setShader(null);
            pFill.setColor(withAlpha(fillRgb, active ? (0.28f + 0.72f * anim) : anim));
            c.drawPath(path, pFill);
        }
    }

    private void drawBlindspotOverlay(Canvas c, float[] vz) {
        if (vz == null || (blindLeftFade <= 0.01f && blindRightFade <= 0.01f)) return;
        float vx = vz[0], vy = vz[1], vw = vz[2], vh = vz[3];
        float vrad = vz.length > 4 ? vz[4] : 28f;

        int scClip = c.save();
        rf.set(vx, vy, vx + vw, vy + vh);
        path.reset();
        path.addRoundRect(rf, vrad, vrad, Path.Direction.CW);
        c.clipPath(path);

        Paint.Cap prevCap = pStroke.getStrokeCap();
        for (int side = -1; side <= 1; side += 2) {
            float fade = side < 0 ? blindLeftFade : blindRightFade;
            float anim = side < 0 ? blindLeftAnim : blindRightAnim;
            float dng = side < 0 ? blindLeftDanger : blindRightDanger;
            if (fade <= 0.01f) continue;

            int amberRgb = lightTheme ? 0xEA580C : 0xFF8C1A;
            int redRgb = lightTheme ? 0xDC2626 : 0xFF2438;
            int colRgb = blendRgb(amberRgb, redRgb, dng);
            int brightRgb = blendRgb(0xFFD080, 0xFF9AA2, dng);

            float yTop = vy + 225f;
            float yBot = vy + 445f;
            float yMid = (yTop + yBot) * 0.5f;
            float edgeX = side < 0 ? vx : (vx + vw);
            float inDir = -side;

            float bandW = 72f + 36f * dng;
            float peakA = Math.min(0.88f, (lightTheme ? 0.46f : 0.62f) * (0.45f + 0.55f * anim) * (1.0f + 0.28f * dng));
            int scGlow = c.save();
            c.translate(edgeX, yMid);
            c.scale(bandW, (yBot - yTop) * 0.56f);
            pFill.setShader(new RadialGradient(0f, 0f, 1f,
                    new int[]{withAlpha(colRgb, peakA), withAlpha(colRgb, peakA * 0.44f), withAlpha(colRgb, 0f)},
                    new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(0f, 0f, 1f, pFill);
            pFill.setShader(null);
            c.restoreToCount(scGlow);

            pStroke.setStrokeCap(Paint.Cap.ROUND);
            pStroke.setColor(withAlpha(blendRgb(colRgb, brightRgb, 0.45f * anim), Math.min(1f, 0.55f + 0.45f * anim) * fade));
            pStroke.setStrokeWidth(3.5f + 1.5f * dng);
            c.drawLine(edgeX + inDir * 3.5f, yTop + 18f, edgeX + inDir * 3.5f, yBot - 18f, pStroke);

            float bx = edgeX + inDir * 44f;
            float by = yMid - 8f;
            int cardBg = blendRgb(lightTheme ? 0xFFFFFF : 0x161922, colRgb, 0.22f + 0.42f * dng * anim);
            roundRect(c, bx - 22f, by - 19f, bx + 22f, by + 19f, 11f, withAlpha(cardBg, 0.90f * fade));
            pStroke.setColor(withAlpha(colRgb, (0.65f + 0.35f * anim) * fade));
            pStroke.setStrokeWidth(1.6f + 0.6f * dng);
            rf.set(bx - 22f, by - 19f, bx + 22f, by + 19f);
            c.drawRoundRect(rf, 11f, 11f, pStroke);

            float tx = bx + inDir * 6.5f;
            path.reset();
            path.moveTo(tx, by - 7.5f);
            path.lineTo(tx + 7.5f, by + 6.5f);
            path.lineTo(tx - 7.5f, by + 6.5f);
            path.close();
            pFill.setShader(null);
            pFill.setColor(withAlpha(blendRgb(colRgb, 0xFFFFFF, 0.45f * anim), fade));
            c.drawPath(path, pFill);
            pFill.setColor(withAlpha(cardBg, fade));
            c.drawRect(tx - 0.9f, by - 3.2f, tx + 0.9f, by + 1.8f, pFill);
            c.drawCircle(tx, by + 4.0f, 1.0f, pFill);

            float ax = bx + inDir * 1.5f;
            float startDeg = (side < 0) ? 142f : -38f;
            pStroke.setColor(withAlpha(brightRgb, (0.55f + 0.45f * anim) * fade));
            pStroke.setStrokeWidth(1.9f);
            rf.set(ax - 10.5f, by - 10.5f, ax + 10.5f, by + 10.5f);
            c.drawArc(rf, startDeg, 76f, false, pStroke);
            rf.set(ax - 15.5f, by - 15.5f, ax + 15.5f, by + 15.5f);
            c.drawArc(rf, startDeg + 5f, 66f, false, pStroke);

            if (dng > 0.25f) {
                float df = fade * Math.min(1f, (dng - 0.25f) / 0.75f);
                String msg = side < 0 ? "좌측 후측방" : "우측 후측방";
                float tw = measure(msg, 12.5f, true);
                float pw = tw + 20f;
                float ph = 24f;
                float px0 = Math.max(vx + 10f, Math.min(vx + vw - pw - 10f, (edgeX + inDir * 56f) - pw * 0.5f));
                float py0 = yMid + 20f;
                int pillBg = blendRgb(0x7F1D1D, 0xDC2626, anim);
                roundRect(c, px0, py0, px0 + pw, py0 + ph, 12f, withAlpha(pillBg, 0.94f * df));
                pStroke.setColor(withAlpha(0xFF9AA2, (0.6f + 0.4f * anim) * df));
                pStroke.setStrokeWidth(1.5f);
                rf.set(px0, py0, px0 + pw, py0 + ph);
                c.drawRoundRect(rf, 12f, 12f, pStroke);
                text(c, msg, px0 + 10f, py0 + 16.5f, 12.5f, true, withAlpha(0xFFFFFF, df));
            }
        }
        pStroke.setStrokeCap(prevCap);
        c.restoreToCount(scClip);
    }

    private void drawDriveResetPill(Canvas c, float[] vz, boolean belowBlinkers) {
        String txt = "↺ 기본 시점";
        pStatus.setTypeface(Typeface.DEFAULT_BOLD);
        pStatus.setTextSize(14f);
        float tw = pStatus.measureText(txt);
        float cx = vz[0] + vz[2] * 0.50f;
        float bx0 = cx - tw * 0.5f - 14f;
        float bx1 = cx + tw * 0.5f + 14f;
        float by0 = vz[1] + (belowBlinkers ? 78f : 40f);
        float by1 = by0 + 28f;
        roundRect(c, bx0, by0, bx1, by1, 14f, lightTheme ? 0xEBFFFFFF : 0xD4141822);
        pStroke.setColor(lightTheme ? 0xFF3B82F6 : 0xFF4F7FFF);
        pStroke.setStrokeWidth(1.5f);
        rf.set(bx0, by0, bx1, by1);
        c.drawRoundRect(rf, 14f, 14f, pStroke);
        pStatus.setColor(lightTheme ? 0xFF1D4ED8 : 0xFF93B4FF);
        c.drawText(txt, bx0 + 14f, by0 + 19f, pStatus);
    }

    private static final String[] DOOR_LABELS = {"운전석", "조수석", "좌후석", "우후석", "트렁크", "후드"};

    private boolean hasAnyOpenDoor() {
        for (int i = 0; i < 6; i++) {
            if (doorEased[i] > 0.03f) return true;
        }
        return false;
    }

    private static boolean rectsOverlap(float x0, float y0, float w, float h, float[] b) {
        return x0 < b[2] + 4f && (x0 + w) > b[0] - 4f && y0 < b[3] + 4f && (y0 + h) > b[1] - 4f;
    }

    private void drawDoorMarkers3d(Canvas c, float[] vz, float[] uv, boolean driving) {
        if (uv.length < 18) return;
        float vx = vz[0], vy = vz[1], vw = vz[2], vh = vz[3];
        float blink = curDoorBlink;
        float rate = driving ? 1.45f : 1.05f;
        float r1 = (camSimTimeSec * rate) % 1f;
        float r2 = (r1 + 0.5f) % 1f;

        int warnCore = driving ? 0xFF2D3D : 0xFF6B1A;
        int warnPeak = driving ? 0xFF5A36 : 0xFFB020;
        int pillOff = driving ? 0x26121A : 0x1A1E2B;
        int pillOn = driving ? 0xDC2626 : 0xE64A19;

        int placedCount = 0;
        for (int i = 0; i < 6; i++) {
            float open = doorEased[i];
            if (open <= 0.03f || uv[i * 3 + 2] < 0.5f) continue;
            float px = Math.max(vx + 56f, Math.min(vx + vw - 56f, vx + uv[i * 3] * vw));
            float py = Math.max(vy + 30f, Math.min(vy + vh - 30f, vy + uv[i * 3 + 1] * vh));
            if (px < vx + 205f && py < vy + 300f) {
                px = vx + 210f;
            }
            float fade = Math.min(1f, open * 1.6f);

            // 1. Two staggered expanding radar shockwave rings
            for (int rIdx = 0; rIdx < 2; rIdx++) {
                float r = (rIdx == 0) ? r1 : r2;
                float rad = 10f + 26f * r;
                float ra = (1f - r) * (1f - r) * 0.78f * fade;
                if (ra > 0.02f) {
                    pStroke.setColor(withAlpha(warnPeak, ra));
                    pStroke.setStrokeWidth(2.4f - 1.0f * r);
                    rf.set(px - rad, py - rad, px + rad, py + rad);
                    c.drawOval(rf, pStroke);
                }
            }

            // 2. Pulsing filled glow halo
            float haloR = 13f + 7f * blink;
            pFill.setShader(null);
            pFill.setColor(withAlpha(warnPeak, (0.12f + 0.32f * blink) * fade));
            c.drawCircle(px, py, haloR, pFill);

            // 3. Crisp blinking beacon ring & bright core dot
            float ringR = 9.5f + 2.5f * blink;
            pStroke.setColor(withAlpha(blendRgb(warnCore, 0xFFE0B2, blink * 0.65f), fade));
            pStroke.setStrokeWidth(2.8f);
            rf.set(px - ringR, py - ringR, px + ringR, py + ringR);
            c.drawOval(rf, pStroke);

            pFill.setColor(withAlpha(blendRgb(warnCore, 0xFFFFFF, blink * 0.85f), fade));
            c.drawCircle(px, py, 3.8f + 1.2f * blink, pFill);

            // 4. Label pill with vertical anti-overlap nudging
            String lbl = DOOR_LABELS[i];
            float tSize = 15.5f;
            float tw = measure(lbl, tSize, true);
            float bw = tw + 28f;
            float bh = 28f;
            float bx0 = Math.max(vx + 10f, Math.min(vx + vw - bw - 10f, px - bw * 0.5f));
            float byBase = py - ringR - bh - 6f;
            if (byBase < vy + 78f) byBase = py + ringR + 6f;
            byBase = Math.max(vy + 78f, Math.min(vy + vh - bh - 10f, byBase));
            if (lastTbtActive && (bx0 + bw) > lastTbtX0 - 6f && byBase < lastTbtY1 + 6f) {
                bx0 = Math.max(vx + 10f, lastTbtX0 - bw - 8f);
            }

            float by0 = byBase;
            float stepY = bh + 5f;
            float[] tries = {0f, stepY, -stepY, stepY * 2f, -stepY * 2f, stepY * 3f};
            for (float dy : tries) {
                float cand = byBase + dy;
                if (cand < vy + 78f || cand + bh > vy + vh - 8f) continue;
                boolean hit = false;
                for (int p = 0; p < placedCount; p++) {
                    if (rectsOverlap(bx0, cand, bw, bh, doorPlaced[p])) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) {
                    by0 = cand;
                    break;
                }
            }
            if (placedCount < 6) {
                doorPlaced[placedCount][0] = bx0;
                doorPlaced[placedCount][1] = by0;
                doorPlaced[placedCount][2] = bx0 + bw;
                doorPlaced[placedCount][3] = by0 + bh;
                placedCount++;
            }

            // 5. Connector stem from anchor to pill
            float stemX = Math.max(bx0 + 6f, Math.min(bx0 + bw - 6f, px));
            float stemY = (by0 + bh * 0.5f < py) ? (by0 + bh) : by0;
            pStroke.setColor(withAlpha(warnPeak, (0.45f + 0.55f * blink) * fade));
            pStroke.setStrokeWidth(1.8f);
            c.drawLine(px, py, stemX, stemY, pStroke);

            // Outer pill glow halo
            if (blink > 0.15f) {
                roundRect(c, bx0 - 3f, by0 - 3f, bx0 + bw + 3f, by0 + bh + 3f, 16f,
                        withAlpha(warnPeak, 0.34f * blink * fade));
            }
            int bgCol = blendRgb(pillOff, pillOn, blink);
            roundRect(c, bx0, by0, bx0 + bw, by0 + bh, 14f,
                    withAlpha(bgCol, (0.88f + 0.10f * blink) * fade));
            pStroke.setColor(withAlpha(blendRgb(warnCore, 0xFFE0B2, blink * 0.65f), fade));
            pStroke.setStrokeWidth(1.8f + 0.6f * blink);
            rf.set(bx0, by0, bx0 + bw, by0 + bh);
            c.drawRoundRect(rf, 14f, 14f, pStroke);

            pFill.setShader(null);
            pFill.setColor(withAlpha(blendRgb(warnCore, 0xFFFFFF, blink), fade));
            c.drawCircle(bx0 + 11f, by0 + bh * 0.5f, 4.0f, pFill);

            text(c, lbl, bx0 + 19f, by0 + bh * 0.5f + 5.4f, tSize, true,
                    withAlpha(0xFFFFFF, fade));
        }
    }

    private void drawDoorAlertBanner(Canvas c, float[] vz, boolean driving, boolean belowResetPill, boolean belowBlinkers) {
        int cnt = 0;
        int firstIdx = -1;
        float maxOpen = 0f;
        for (int i = 0; i < 6; i++) {
            float o = doorEased[i];
            if (o > 0.03f) {
                if (firstIdx < 0) firstIdx = i;
                if (o > maxOpen) maxOpen = o;
                cnt++;
            }
        }
        if (cnt == 0) return;

        String msg;
        if (cnt == 1) {
            if (firstIdx == 4) msg = "트렁크 열림";
            else if (firstIdx == 5) msg = "후드 열림";
            else msg = "도어 열림 · " + DOOR_LABELS[firstIdx];
        } else if (cnt == 6) {
            msg = "전체 도어 · 트렁크 · 후드 열림";
        } else {
            msg = "도어 열림 · " + DOOR_LABELS[firstIdx] + " 외 " + (cnt - 1) + "곳";
        }

        float blink = curDoorBlink;
        float fade = Math.min(1f, maxOpen * 1.6f);
        int warnCore = driving ? 0xFF2D3D : 0xFF6B1A;
        int warnPeak = driving ? 0xFF5A36 : 0xFFB020;
        int pillOff = driving ? 0x26121A : 0x1A1E2B;
        int pillOn = driving ? 0xDC2626 : 0xE64A19;

        float tSize = 15f;
        float tw = measure(msg, tSize, true);
        float bw = tw + 42f;
        float bh = 30f;
        float cx = vz[0] + vz[2] * 0.45f;
        float bx0 = Math.max(vz[0] + 12f, Math.min(vz[0] + vz[2] - bw - 12f, cx - bw * 0.5f));
        float by0 = vz[1] + 40f + (belowBlinkers ? 38f : 0f) + (belowResetPill ? 34f : 0f);

        if (blink > 0.15f) {
            roundRect(c, bx0 - 3.5f, by0 - 3.5f, bx0 + bw + 3.5f, by0 + bh + 3.5f, 18f,
                    withAlpha(warnPeak, 0.36f * blink * fade));
        }
        int bgCol = blendRgb(pillOff, pillOn, blink);
        roundRect(c, bx0, by0, bx0 + bw, by0 + bh, 15f,
                withAlpha(bgCol, (0.90f + 0.08f * blink) * fade));
        pStroke.setColor(withAlpha(blendRgb(warnCore, 0xFFE0B2, blink * 0.65f), fade));
        pStroke.setStrokeWidth(1.8f + 0.6f * blink);
        rf.set(bx0, by0, bx0 + bw, by0 + bh);
        c.drawRoundRect(rf, 15f, 15f, pStroke);

        // Warning triangle icon on left
        float ix = bx0 + 16f;
        float iy = by0 + bh * 0.5f;
        path.reset();
        path.moveTo(ix, iy - 6.5f);
        path.lineTo(ix + 6.5f, iy + 5.5f);
        path.lineTo(ix - 6.5f, iy + 5.5f);
        path.close();
        pFill.setShader(null);
        pFill.setColor(withAlpha(blendRgb(warnPeak, 0xFFFFFF, blink * 0.85f), fade));
        c.drawPath(path, pFill);
        pFill.setColor(withAlpha(bgCol, fade));
        c.drawRect(ix - 0.9f, iy - 2.6f, ix + 0.9f, iy + 1.8f, pFill);
        c.drawCircle(ix, iy + 3.6f, 0.95f, pFill);

        text(c, msg, bx0 + 28f, by0 + bh * 0.5f + 5.2f, tSize, true,
                withAlpha(0xFFFFFF, fade));
    }

    private void drawStatusPill(Canvas c, HudState s, double fps, boolean demoMode) {
        String txt;
        int dotCol;
        String upd = availableUpdateVer;
        if (demoMode) {
            txt = String.format(Locale.US, "DEMO · %.0f FPS", fps);
            dotCol = 0xFF6E95FF;
        } else if (s.hubConnected) {
            txt = String.format(Locale.US, "HUD %.0f FPS · HUB%s", fps,
                    s.commaDirectConnected ? " · 직결" : (s.commaConnected ? " · 경유" : ""));
            dotCol = !upd.isEmpty() ? 0xFF6E95FF : 0xFF3DD68C;
        } else {
            txt = (s.hubIp != null && !s.hubIp.isEmpty())
                    ? ("연결 대기 " + s.hubIp + " (미디어 탭 설정)")
                    : "헤드유닛 자동탐색 중 (:8767) · 미디어 탭 설정";
            dotCol = 0xFFF5A623;
        }
        if (!upd.isEmpty()) {
            txt = txt + " · 업데이트 v" + upd;
        }
        pStatus.setTypeface(Typeface.DEFAULT_BOLD);
        pStatus.setTextSize(15f);
        float tw = pStatus.measureText(txt);
        float px = 492f - tw * 0.5f;
        float py = 26f;
        roundRect(c, px - 22f, py - 11f, px + tw + 12f, py + 14f, 12f,
                lightTheme ? 0xD9FFFFFF : 0xB8111318);
        pFill.setShader(null);
        pFill.setColor(dotCol);
        c.drawCircle(px - 10f, py + 1.5f, 4.5f, pFill);
        pStatus.setColor(themeColor(0xFF8E9099, lightTheme));
        c.drawText(txt, px, py + 6.5f, pStatus);
    }

    // ---- Driving & Parked 3D Visualization ----

    private float camU0, camKx, camH, camHorizon, camEgoD, camCurve, camBendPivot, camChaseSlope;

    private float camBend(float x) {
        float r = (camChaseSlope == 0f) ? Math.max(0f, x - camBendPivot) : (x - camBendPivot);
        return camChaseSlope * r + camCurve * r * r * 0.5f;
    }

    private float camU(float x, float y) {
        return camU0 + camKx * ((y + camBend(x)) / x);
    }

    private float camV(float x) {
        return camHorizon + camKx * camH / x;
    }

    private static float camX(float egoD, float d) {
        return egoD + EGO_LEN_M + (d <= 30f ? d : 30f + (d - 30f) * 0.7f);
    }

    private static final float[] ROAD_3D_S = {
            -48f, -36f, -26f, -18f, -12f, -7f, -3f, 0f, 3f, 7f, 12f,
            18f, 25f, 33f, 42f, 52f, 64f, 78f, 94f, 110f, 128f
    };

    private boolean clipAddQuad(Path dst, float[] m, float vx, float vy, float vw, float vh,
                                float x0, float y0, float z0, float x1, float y1, float z1,
                                float x2, float y2, float z2, float x3, float y3, float z3) {
        final float nearW = 0.85f;
        clipIn[0][0] = m[0]*x0 + m[4]*y0 + m[8]*z0 + m[12];
        clipIn[0][1] = m[1]*x0 + m[5]*y0 + m[9]*z0 + m[13];
        clipIn[0][2] = m[3]*x0 + m[7]*y0 + m[11]*z0 + m[15];
        clipIn[1][0] = m[0]*x1 + m[4]*y1 + m[8]*z1 + m[12];
        clipIn[1][1] = m[1]*x1 + m[5]*y1 + m[9]*z1 + m[13];
        clipIn[1][2] = m[3]*x1 + m[7]*y1 + m[11]*z1 + m[15];
        clipIn[2][0] = m[0]*x2 + m[4]*y2 + m[8]*z2 + m[12];
        clipIn[2][1] = m[1]*x2 + m[5]*y2 + m[9]*z2 + m[13];
        clipIn[2][2] = m[3]*x2 + m[7]*y2 + m[11]*z2 + m[15];
        clipIn[3][0] = m[0]*x3 + m[4]*y3 + m[8]*z3 + m[12];
        clipIn[3][1] = m[1]*x3 + m[5]*y3 + m[9]*z3 + m[13];
        clipIn[3][2] = m[3]*x3 + m[7]*y3 + m[11]*z3 + m[15];

        boolean allIn = clipIn[0][2] >= nearW && clipIn[1][2] >= nearW && clipIn[2][2] >= nearW && clipIn[3][2] >= nearW;
        int outN;
        if (allIn) {
            outN = 4;
            for (int i = 0; i < 4; i++) {
                clipOut[i][0] = clipIn[i][0];
                clipOut[i][1] = clipIn[i][1];
                clipOut[i][2] = clipIn[i][2];
            }
        } else {
            if (clipIn[0][2] < nearW && clipIn[1][2] < nearW && clipIn[2][2] < nearW && clipIn[3][2] < nearW) {
                return false;
            }
            outN = 0;
            for (int i = 0; i < 4; i++) {
                float[] a = clipIn[i], b = clipIn[(i + 1) & 3];
                boolean ain = a[2] >= nearW, bin = b[2] >= nearW;
                if (ain) {
                    clipOut[outN][0] = a[0]; clipOut[outN][1] = a[1]; clipOut[outN][2] = a[2];
                    outN++;
                }
                if (ain != bin && outN < 6) {
                    float t = (nearW - a[2]) / (b[2] - a[2]);
                    clipOut[outN][0] = a[0] + (b[0] - a[0]) * t;
                    clipOut[outN][1] = a[1] + (b[1] - a[1]) * t;
                    clipOut[outN][2] = nearW;
                    outN++;
                }
            }
        }
        if (outN < 3) return false;
        for (int i = 0; i < outN; i++) {
            float iw = 1f / clipOut[i][2];
            float sx = vx + vw * (0.5f + 0.5f * clipOut[i][0] * iw);
            float sy = vy + vh * (0.5f - 0.5f * clipOut[i][1] * iw);
            if (i == 0) dst.moveTo(sx, sy);
            else dst.lineTo(sx, sy);
        }
        dst.close();
        return true;
    }

    private void fillRoadBand(Canvas c, float[] m, float vx, float vy, float vw, float vh,
                              float curve, float sA, float sB, float hw0, float hw1, int color, float stepM) {
        if (sB <= sA + 0.05f || ((color >>> 24) & 0xFF) == 0) return;
        path.reset();
        boolean any = false;
        float s = sA;
        while (s < sB - 0.01f) {
            float sn = Math.min(sB, s + stepM);
            float cx0 = 0.5f * curve * s * s, cx1 = 0.5f * curve * sn * sn;
            float cos0 = (float) Math.cos(curve * s), sin0 = (float) Math.sin(curve * s);
            float cos1 = (float) Math.cos(curve * sn), sin1 = (float) Math.sin(curve * sn);
            if (clipAddQuad(path, m, vx, vy, vw, vh,
                    cx0 + hw0 * cos0, 0f, -s + hw0 * sin0,
                    cx0 + hw1 * cos0, 0f, -s + hw1 * sin0,
                    cx1 + hw1 * cos1, 0f, -sn + hw1 * sin1,
                    cx1 + hw0 * cos1, 0f, -sn + hw0 * sin1)) {
                any = true;
            }
            s = sn;
        }
        if (any) {
            pFill.setShader(null);
            pFill.setColor(color);
            c.drawPath(path, pFill);
        }
    }

    private int emitClippedGradientQuad(float[] m, float vx, float vy, float vw, float vh,
                                        float x0, float z0, float a0, float c0,
                                        float x1, float z1, float a1, float c1,
                                        float x2, float z2, float a2, float c2,
                                        float x3, float z3, float a3, float c3,
                                        int edgeRgb, int coreRgb, int nPts) {
        final float nearW = 0.35f;
        rClipIn[0][0] = m[0]*x0 + m[8]*z0 + m[12];
        rClipIn[0][1] = m[1]*x0 + m[9]*z0 + m[13];
        rClipIn[0][2] = m[3]*x0 + m[11]*z0 + m[15];
        rClipIn[0][3] = a0; rClipIn[0][4] = c0;

        rClipIn[1][0] = m[0]*x1 + m[8]*z1 + m[12];
        rClipIn[1][1] = m[1]*x1 + m[9]*z1 + m[13];
        rClipIn[1][2] = m[3]*x1 + m[11]*z1 + m[15];
        rClipIn[1][3] = a1; rClipIn[1][4] = c1;

        rClipIn[2][0] = m[0]*x2 + m[8]*z2 + m[12];
        rClipIn[2][1] = m[1]*x2 + m[9]*z2 + m[13];
        rClipIn[2][2] = m[3]*x2 + m[11]*z2 + m[15];
        rClipIn[2][3] = a2; rClipIn[2][4] = c2;

        rClipIn[3][0] = m[0]*x3 + m[8]*z3 + m[12];
        rClipIn[3][1] = m[1]*x3 + m[9]*z3 + m[13];
        rClipIn[3][2] = m[3]*x3 + m[11]*z3 + m[15];
        rClipIn[3][3] = a3; rClipIn[3][4] = c3;

        int outN;
        if (rClipIn[0][2] >= nearW && rClipIn[1][2] >= nearW && rClipIn[2][2] >= nearW && rClipIn[3][2] >= nearW) {
            for (int i = 0; i < 4; i++) {
                rClipOut[i][0] = rClipIn[i][0];
                rClipOut[i][1] = rClipIn[i][1];
                rClipOut[i][2] = rClipIn[i][2];
                rClipOut[i][3] = rClipIn[i][3];
                rClipOut[i][4] = rClipIn[i][4];
            }
            outN = 4;
        } else {
            if (rClipIn[0][2] < nearW && rClipIn[1][2] < nearW && rClipIn[2][2] < nearW && rClipIn[3][2] < nearW) {
                return nPts;
            }
            outN = 0;
            for (int i = 0; i < 4; i++) {
                float[] a = rClipIn[i], b = rClipIn[(i + 1) & 3];
                boolean ain = a[2] >= nearW, bin = b[2] >= nearW;
                if (ain && outN < 6) {
                    rClipOut[outN][0] = a[0]; rClipOut[outN][1] = a[1]; rClipOut[outN][2] = a[2];
                    rClipOut[outN][3] = a[3]; rClipOut[outN][4] = a[4];
                    outN++;
                }
                if (ain != bin && outN < 6) {
                    float t = (nearW - a[2]) / (b[2] - a[2]);
                    rClipOut[outN][0] = a[0] + (b[0] - a[0]) * t;
                    rClipOut[outN][1] = a[1] + (b[1] - a[1]) * t;
                    rClipOut[outN][2] = nearW;
                    rClipOut[outN][3] = a[3] + (b[3] - a[3]) * t;
                    rClipOut[outN][4] = a[4] + (b[4] - a[4]) * t;
                    outN++;
                }
            }
        }
        if (outN < 3) return nPts;

        float iw0 = 1f / rClipOut[0][2];
        float sx0 = vx + vw * (0.5f + 0.5f * rClipOut[0][0] * iw0);
        float sy0 = vy + vh * (0.5f - 0.5f * rClipOut[0][1] * iw0);
        int col0 = (Math.max(0, Math.min(255, Math.round(rClipOut[0][3]))) << 24) | blendRgb(edgeRgb, coreRgb, rClipOut[0][4]);

        for (int i = 1; i < outN - 1; i++) {
            if (nPts + 3 > RIBBON_MAX_PTS) break;
            float iwA = 1f / rClipOut[i][2];
            float sxA = vx + vw * (0.5f + 0.5f * rClipOut[i][0] * iwA);
            float syA = vy + vh * (0.5f - 0.5f * rClipOut[i][1] * iwA);
            int colA = (Math.max(0, Math.min(255, Math.round(rClipOut[i][3]))) << 24) | blendRgb(edgeRgb, coreRgb, rClipOut[i][4]);

            float iwB = 1f / rClipOut[i + 1][2];
            float sxB = vx + vw * (0.5f + 0.5f * rClipOut[i + 1][0] * iwB);
            float syB = vy + vh * (0.5f - 0.5f * rClipOut[i + 1][1] * iwB);
            int colB = (Math.max(0, Math.min(255, Math.round(rClipOut[i + 1][3]))) << 24) | blendRgb(edgeRgb, coreRgb, rClipOut[i + 1][4]);

            int p = nPts * 2;
            ribbonVerts[p]     = sx0; ribbonVerts[p + 1] = sy0;
            ribbonVerts[p + 2] = sxA; ribbonVerts[p + 3] = syA;
            ribbonVerts[p + 4] = sxB; ribbonVerts[p + 5] = syB;
            ribbonColors[nPts]     = col0;
            ribbonColors[nPts + 1] = colA;
            ribbonColors[nPts + 2] = colB;
            nPts += 3;
        }
        return nPts;
    }

    private static float pathLongAlpha(float s, float fadeStart, float sEnd) {
        if (s <= -24f || s >= sEnd) return 0f;
        if (s < -2f) {
            float u = (s + 24f) / 22f;
            return u * u * (3f - 2f * u);
        }
        if (s <= fadeStart) return 1f;
        float u = (sEnd - s) / Math.max(0.1f, sEnd - fadeStart);
        return u * u;
    }

    private void drawFeatheredPath3d(Canvas c, float[] m, float vx, float vy, float vw, float vh,
                                     float curve, float sEnd, boolean hasLead,
                                     int edgeRgb, int coreRgb, int aMax) {
        if (sEnd <= -20f || aMax <= 0) return;
        float frontSpan = hasLead ? Math.max(3.5f, Math.min(9.0f, (sEnd - 2f) * 0.32f)) : 28f;
        float fadeStart = Math.max(-2f, sEnd - frontSpan);
        float fadeMid = fadeStart + (sEnd - fadeStart) * 0.5f;

        int nS = 0;
        for (float bs : PATH_BASE_S) {
            if (bs < sEnd - 0.15f && nS < 28) pathS[nS++] = bs;
        }
        if (fadeStart > -23.5f && fadeStart < sEnd - 0.15f && nS < 29) pathS[nS++] = fadeStart;
        if (fadeMid > -23.5f && fadeMid < sEnd - 0.15f && nS < 30) pathS[nS++] = fadeMid;
        pathS[nS++] = sEnd;

        // Sort & dedupe stations in-place
        for (int i = 1; i < nS; i++) {
            float v = pathS[i];
            int j = i - 1;
            while (j >= 0 && pathS[j] > v) {
                pathS[j + 1] = pathS[j];
                j--;
            }
            pathS[j + 1] = v;
        }
        int uniq = 1;
        for (int i = 1; i < nS; i++) {
            if (pathS[i] - pathS[uniq - 1] >= 0.15f) {
                pathS[uniq++] = pathS[i];
            } else if (i == nS - 1) {
                pathS[uniq - 1] = pathS[i];
            }
        }
        nS = uniq;
        if (nS < 2) return;

        int nPts = 0;
        for (int si = 0; si < nS - 1; si++) {
            float s0 = pathS[si], s1 = pathS[si + 1];
            float la0 = pathLongAlpha(s0, fadeStart, sEnd) * aMax;
            float la1 = pathLongAlpha(s1, fadeStart, sEnd) * aMax;
            if (la0 <= 0.1f && la1 <= 0.1f) continue;

            float cx0 = 0.5f * curve * s0 * s0, cx1 = 0.5f * curve * s1 * s1;
            float cos0 = (float) Math.cos(curve * s0), sin0 = (float) Math.sin(curve * s0);
            float cos1 = (float) Math.cos(curve * s1), sin1 = (float) Math.sin(curve * s1);

            for (int li = 0; li < PATH_LAT_X.length - 1; li++) {
                float lx0 = PATH_LAT_X[li], lx1 = PATH_LAT_X[li + 1];
                float aL0 = la0 * PATH_LAT_A[li], aR0 = la0 * PATH_LAT_A[li + 1];
                float aL1 = la1 * PATH_LAT_A[li], aR1 = la1 * PATH_LAT_A[li + 1];
                float cL = PATH_LAT_C[li], cR = PATH_LAT_C[li + 1];
                nPts = emitClippedGradientQuad(m, vx, vy, vw, vh,
                        cx0 + lx0 * cos0, -s0 + lx0 * sin0, aL0, cL,
                        cx0 + lx1 * cos0, -s0 + lx1 * sin0, aR0, cR,
                        cx1 + lx1 * cos1, -s1 + lx1 * sin1, aR1, cR,
                        cx1 + lx0 * cos1, -s1 + lx0 * sin1, aL1, cL,
                        edgeRgb, coreRgb, nPts);
            }
        }
        if (nPts >= 3) {
            pFill.setShader(null);
            pFill.setColor(0xFFFFFFFF);
            c.drawVertices(Canvas.VertexMode.TRIANGLES, nPts * 2, ribbonVerts, 0,
                    null, 0, ribbonColors, 0, null, 0, 0, pFill);
        }
    }

    private void drawRoad3d(Canvas c, HudState s, float[] vz, String gear, int spd) {
        float vx = vz[0], vy = vz[1], vw = vz[2], vh = vz[3];
        float curve = curEffCurve;
        float[] m = camVpMat;

        // 1. Subtle 3D asphalt surface in 3 distance fade bands
        int roadC0 = lightTheme ? 0x16334155 : 0x10FFFFFF;
        int roadC1 = lightTheme ? 0x0B334155 : 0x07FFFFFF;
        fillRoadBand(c, m, vx, vy, vw, vh, curve, -48f, 44f, -8.8f, 8.8f, roadC0, 8f);
        fillRoadBand(c, m, vx, vy, vw, vh, curve, 44f, 118f, -8.8f, 8.8f, roadC1, 14f);

        // 2. Shoulder / dashed / ego lane lines (exact world-space dash phase)
        int[][] lineRgbA = lightTheme
                ? new int[][]{{148, 156, 170, 128}, {100, 110, 128, 155}, {68, 78, 96, 220}}
                : new int[][]{{160, 164, 172, 120}, {230, 232, 236, 150}, {245, 245, 247, 232}};
        float[] lats = {8.75f, 5.25f, 1.75f};
        float[] widths = {0.06f, 0.06f, 0.075f};
        float[] bandFade = {1.0f, 0.52f, 0.18f};
        float[][] bands = {{-48f, 44f}, {44f, 86f}, {86f, 128f}};

        for (int k = 0; k < 3; k++) {
            int baseRgb = (lineRgbA[k][0] << 16) | (lineRgbA[k][1] << 8) | lineRgbA[k][2];
            int baseA = lineRgbA[k][3];
            boolean dashed = (k == 1);
            for (int b = 0; b < 3; b++) {
                int col = (Math.round(baseA * bandFade[b]) << 24) | baseRgb;
                path.reset();
                boolean any = false;
                if (!dashed) {
                    for (int i = 0; i < ROAD_3D_S.length - 1; i++) {
                        float s0 = Math.max(bands[b][0], ROAD_3D_S[i]);
                        float s1 = Math.min(bands[b][1], ROAD_3D_S[i + 1]);
                        if (s1 <= s0 + 0.01f) continue;
                        float cx0 = 0.5f * curve * s0 * s0, cx1 = 0.5f * curve * s1 * s1;
                        float cos0 = (float) Math.cos(curve * s0), sin0 = (float) Math.sin(curve * s0);
                        float cos1 = (float) Math.cos(curve * s1), sin1 = (float) Math.sin(curve * s1);
                        for (int side = -1; side <= 1; side += 2) {
                            float lat = side * lats[k], w = widths[k];
                            if (clipAddQuad(path, m, vx, vy, vw, vh,
                                    cx0 + (lat - w) * cos0, 0.005f, -s0 + (lat - w) * sin0,
                                    cx0 + (lat + w) * cos0, 0.005f, -s0 + (lat + w) * sin0,
                                    cx1 + (lat + w) * cos1, 0.005f, -s1 + (lat + w) * sin1,
                                    cx1 + (lat - w) * cos1, 0.005f, -s1 + (lat - w) * sin1)) {
                                any = true;
                            }
                        }
                    }
                } else {
                    float dashLen = 3.5f, period = 7.0f;
                    float shift = phase % period;
                    float sStart = (float) Math.floor((bands[b][0] + shift) / period) * period - shift;
                    for (float ds = sStart; ds < bands[b][1]; ds += period) {
                        float s0 = Math.max(bands[b][0], ds);
                        float s1 = Math.min(bands[b][1], ds + dashLen);
                        if (s1 <= s0 + 0.05f) continue;
                        float cx0 = 0.5f * curve * s0 * s0, cx1 = 0.5f * curve * s1 * s1;
                        float cos0 = (float) Math.cos(curve * s0), sin0 = (float) Math.sin(curve * s0);
                        float cos1 = (float) Math.cos(curve * s1), sin1 = (float) Math.sin(curve * s1);
                        for (int side = -1; side <= 1; side += 2) {
                            float lat = side * lats[k], w = widths[k];
                            if (clipAddQuad(path, m, vx, vy, vw, vh,
                                    cx0 + (lat - w) * cos0, 0.006f, -s0 + (lat - w) * sin0,
                                    cx0 + (lat + w) * cos0, 0.006f, -s0 + (lat + w) * sin0,
                                    cx1 + (lat + w) * cos1, 0.006f, -s1 + (lat + w) * sin1,
                                    cx1 + (lat - w) * cos1, 0.006f, -s1 + (lat - w) * sin1)) {
                                any = true;
                            }
                        }
                    }
                }
                if (any) {
                    pFill.setShader(null);
                    pFill.setColor(col);
                    c.drawPath(path, pFill);
                }
            }
        }

        // 3. Ego Autopilot path carpet (3D metric [-1.62m..+1.62m] along curve)
        boolean hasLead = s.hasLead && smoothLeadDist > 0.5f;
        float leadS = 2.2f + (hasLead ? (smoothLeadDist <= 30f ? smoothLeadDist : 30f + (smoothLeadDist - 30f) * 0.7f) : 0f);
        boolean crit = hasLead && smoothLeadDist <= 12f, close = hasLead && smoothLeadDist <= 18f;
        if ("D".equals(gear) || "R".equals(gear)) {
            boolean apPath = s.isCruiseEngaged() || s.isSteeringEngaged();
            float sEnd = hasLead ? Math.max(3.5f, leadS - 1.6f) : 82f;
            int rgb = lightTheme
                    ? (crit ? 0xDC2626 : (close ? 0xD97706 : (apPath ? 0x2563EB : 0x475569)))
                    : (crit ? 0xE31937 : (close ? 0xF5A623 : (apPath ? 0x3E6AE1 : 0xC8CDD7)));
            int coreRgb = (apPath && !close) ? blendRgb(rgb, lightTheme ? 0x60A5FA : 0x74B3FF, 0.28f) : rgb;
            int aMax = lightTheme ? ((apPath || close) ? 195 : 72) : ((apPath || close) ? 185 : 58);
            drawFeatheredPath3d(c, m, vx, vy, vw, vh, curve, sEnd, hasLead, rgb, coreRgb, aMax);
        }

        // 4. Neighboring vehicles + lead vehicle sorted farthest-first by 3D clip depth
        long nowMs = System.currentTimeMillis();
        float[] tr = ((s.commaConnected || s.commaDirectConnected) && s.trafficLeads != null && nowMs - s.trafficMs < 1500L)
                ? s.trafficLeads : new float[0];
        int n = hasLead ? 1 : 0;
        for (int i = 0; i + 3 < tr.length; i += 4) if (!isStationaryTarget(spd, tr[i + 3])) n++;
        float[] wxA = new float[n], wzA = new float[n], yawA = new float[n], cwA = new float[n], uA = new float[n], vA = new float[n];
        int[] clA = new int[n];
        boolean[] isLeadA = new boolean[n];
        int cnt = 0;
        for (int i = 0; i + 3 < tr.length; i += 4) {
            if (isStationaryTarget(spd, tr[i + 3])) continue;
            float d = tr[i], y = tr[i + 1];
            float sv = 2.2f + (d <= 30f ? d : 30f + (d - 30f) * 0.7f);
            float th = curve * sv;
            float wx = 0.5f * curve * sv * sv + y * (float) Math.cos(th);
            float wz = -sv + y * (float) Math.sin(th);
            float cw = m[3]*wx + m[7]*0f + m[11]*wz + m[15];
            if (cw < 1.2f) continue;
            float cx = m[0]*wx + m[4]*0f + m[8]*wz + m[12];
            float cy = m[1]*wx + m[5]*0f + m[9]*wz + m[13];
            wxA[cnt] = wx; wzA[cnt] = wz; yawA[cnt] = th; cwA[cnt] = cw;
            uA[cnt] = vx + vw * (0.5f + 0.5f * cx / cw);
            vA[cnt] = vy + vh * (0.5f - 0.5f * cy / cw);
            clA[cnt] = tr[i + 2] < -1.5f ? 2 : (s.headLamp ? 1 : 0);
            cnt++;
        }
        if (hasLead) {
            float sv = leadS, y = smoothLeadY, th = curve * sv;
            float wx = 0.5f * curve * sv * sv + y * (float) Math.cos(th);
            float wz = -sv + y * (float) Math.sin(th);
            float cw = m[3]*wx + m[7]*0f + m[11]*wz + m[15];
            if (cw >= 1.2f) {
                float cx = m[0]*wx + m[4]*0f + m[8]*wz + m[12];
                float cy = m[1]*wx + m[5]*0f + m[9]*wz + m[13];
                wxA[cnt] = wx; wzA[cnt] = wz; yawA[cnt] = th; cwA[cnt] = cw;
                uA[cnt] = vx + vw * (0.5f + 0.5f * cx / cw);
                vA[cnt] = vy + vh * (0.5f - 0.5f * cy / cw);
                clA[cnt] = s.leadAccelMps2 < -1.5f ? 2 : (s.headLamp ? 1 : 0);
                isLeadA[cnt] = true;
                cnt++;
            }
        }
        for (int i = 1; i < cnt; i++) {
            for (int j = i; j > 0 && cwA[j] > cwA[j - 1]; j--) {
                float tf;
                tf = wxA[j]; wxA[j] = wxA[j-1]; wxA[j-1] = tf;
                tf = wzA[j]; wzA[j] = wzA[j-1]; wzA[j-1] = tf;
                tf = yawA[j]; yawA[j] = yawA[j-1]; yawA[j-1] = tf;
                tf = cwA[j]; cwA[j] = cwA[j-1]; cwA[j-1] = tf;
                tf = uA[j]; uA[j] = uA[j-1]; uA[j-1] = tf;
                tf = vA[j]; vA[j] = vA[j-1]; vA[j-1] = tf;
                int ti = clA[j]; clA[j] = clA[j-1]; clA[j-1] = ti;
                boolean tb = isLeadA[j]; isLeadA[j] = isLeadA[j-1]; isLeadA[j-1] = tb;
            }
        }

        boolean nav = navReady();
        float tagX = 0f, tagY = 0f;
        boolean showTag = false;
        for (int i = 0; i < cnt; i++) {
            float wx = wxA[i], wz = wzA[i], th = yawA[i], cw = cwA[i];
            float dxCam = wx - camEyeX, dyCam = camEyeY, dzCam = wz - camEyeZ;
            float horiz = (float) Math.hypot(dxCam, dzCam);
            float elevDeg = (float) Math.toDegrees(Math.atan2(dyCam, Math.max(0.1f, horiz)));
            double camAzRad = Math.atan2(dxCam, -dzCam);
            double relDeg = Math.toDegrees(camAzRad - th);
            while (relDeg > 180.0) relDeg -= 360.0;
            while (relDeg < -180.0) relDeg += 360.0;

            float alpha = Math.max(0.35f, Math.min(1f, 1.25f - cw / 115f));
            boolean drewNav = false;
            if (nav && Math.abs(relDeg) <= 25.0 && elevDeg <= 19f) {
                float rx = wx - 2.10f * (float) Math.sin(th);
                float rz = wz + 2.10f * (float) Math.cos(th);
                float rcw = m[3]*rx + m[7]*0f + m[11]*rz + m[15];
                if (rcw >= 1.2f) {
                    float rcx = m[0]*rx + m[4]*0f + m[8]*rz + m[12];
                    float rcy = m[1]*rx + m[5]*0f + m[9]*rz + m[13];
                    float ru = vx + vw * (0.5f + 0.5f * rcx / rcw);
                    float rv = vy + vh * (0.5f - 0.5f * rcy / rcw);
                    float pxPerM = (0.5f * vw * Math.abs(camProjScaleX)) / rcw;
                    int nIdx = Math.max(0, Math.min(24, (int) Math.round(relDeg / 2.0) + 12));
                    NavSprite sp = navSets[navSetFor(navSets, elevDeg)][nIdx];
                    Paint tp = isLeadA[i] ? (lightTheme ? pTintLeadLight : pTintLead) : (lightTheme ? pTintOtherLight : pTintOther);
                    drawNav(c, sp, ru, rv, pxPerM / sp.ppm, tp, clA[i], alpha);
                    drewNav = true;
                }
            }
            if (!drewNav) {
                drawFacetedCar3d(c, m, vx, vy, vw, vh, wx, wz, th, isLeadA[i], clA[i], alpha);
            }
            if (isLeadA[i]) {
                float lx = m[0]*wx + m[4]*1.92f + m[8]*wz + m[12];
                float ly = m[1]*wx + m[5]*1.92f + m[9]*wz + m[13];
                float lw = m[3]*wx + m[7]*1.92f + m[11]*wz + m[15];
                if (lw >= 1.2f) {
                    tagX = vx + vw * (0.5f + 0.5f * lx / lw);
                    tagY = vy + vh * (0.5f - 0.5f * ly / lw);
                    showTag = true;
                }
            }
        }
        if (showTag) {
            String t = Math.round(smoothLeadDist) + " m";
            float size = vw / 30f;
            float tw = measure(t, size, true);
            text(c, t, tagX - tw / 2f, tagY, size, true,
                    themeColor(crit ? 0xFFFF4D63 : (close ? 0xFFF5A623 : 0xFFC9CBD1), lightTheme));
        }
    }

    private void drawFacetedQuad(Canvas c, float[] m, float vx, float vy, float vw, float vh,
                                 float cx, float cz, float cosT, float sinT,
                                 float x0, float y0, float z0, float x1, float y1, float z1,
                                 float x2, float y2, float z2, float x3, float y3, float z3,
                                 int color) {
        path.reset();
        boolean ok = clipAddQuad(path, m, vx, vy, vw, vh,
                cx + x0 * cosT - z0 * sinT, y0, cz + x0 * sinT + z0 * cosT,
                cx + x1 * cosT - z1 * sinT, y1, cz + x1 * sinT + z1 * cosT,
                cx + x2 * cosT - z2 * sinT, y2, cz + x2 * sinT + z2 * cosT,
                cx + x3 * cosT - z3 * sinT, y3, cz + x3 * sinT + z3 * cosT);
        if (ok) {
            pFill.setShader(null);
            pFill.setColor(color);
            c.drawPath(path, pFill);
        }
    }

    private boolean isFaceVisible(float cx, float cz, float cosT, float sinT,
                                  float lcx, float lcy, float lcz, float lnx, float lny, float lnz) {
        float px = cx + lcx * cosT - lcz * sinT;
        float pz = cz + lcx * sinT + lcz * cosT;
        float nx = lnx * cosT - lnz * sinT;
        float nz = lnx * sinT + lnz * cosT;
        return (camEyeX - px) * nx + (camEyeY - lcy) * lny + (camEyeZ - pz) * nz > 0f;
    }

    private void drawFacetedCar3d(Canvas c, float[] m, float vx, float vy, float vw, float vh,
                                  float cx, float cz, float th, boolean isLead, int lamp, float alpha) {
        float cosT = (float) Math.cos(th), sinT = (float) Math.sin(th);
        float hw = 0.90f, zf = -2.18f, zr = 2.18f;
        int shCol = (Math.round((lightTheme ? 72 : 135) * alpha) << 24);
        drawFacetedQuad(c, m, vx, vy, vw, vh, cx, cz, cosT, sinT,
                -hw * 1.12f, 0.01f, zf * 1.06f, hw * 1.12f, 0.01f, zf * 1.06f,
                hw * 1.12f, 0.01f, zr * 1.06f, -hw * 1.12f, 0.01f, zr * 1.06f, shCol);

        int cTop = isLead ? (lightTheme ? 0xCFD8E3 : 0xD2D7E2) : (lightTheme ? 0x9BA6B8 : 0x8F97A6);
        int cSide = isLead ? (lightTheme ? 0xAEBACB : 0x9CA3B2) : (lightTheme ? 0x7D889B : 0x6D7482);
        int cRear = isLead ? (lightTheme ? 0x96A3B6 : 0x7A8190) : (lightTheme ? 0x6A7588 : 0x565C69);
        int cGlass = lightTheme ? 0x263142 : 0x161B26;
        int aByte = Math.max(0, Math.min(255, Math.round(alpha * 255f))) << 24;

        // Lower body sides (front -Z, rear +Z, left -X, right +X) + hood/trunk deck
        if (isFaceVisible(cx, cz, cosT, sinT, 0f, 0.54f, zf, 0f, 0f, -1f)) {
            drawFacetedQuad(c, m, vx, vy, vw, vh, cx, cz, cosT, sinT,
                    -hw, 0.20f, zf, hw, 0.20f, zf, hw, 0.86f, zf + 0.14f, -hw, 0.86f, zf + 0.14f, aByte | cSide);
            int hlCol = (lamp > 0) ? (aByte | 0xE6F1FF) : (aByte | 0x9CA3AF);
            drawFacetedQuad(c, m, vx, vy, vw, vh, cx, cz, cosT, sinT,
                    -hw * 0.88f, 0.54f, zf + 0.02f, -hw * 0.44f, 0.54f, zf + 0.02f, -hw * 0.44f, 0.68f, zf + 0.05f, -hw * 0.88f, 0.68f, zf + 0.05f, hlCol);
            drawFacetedQuad(c, m, vx, vy, vw, vh, cx, cz, cosT, sinT,
                    hw * 0.44f, 0.54f, zf + 0.02f, hw * 0.88f, 0.54f, zf + 0.02f, hw * 0.88f, 0.68f, zf + 0.05f, hw * 0.44f, 0.68f, zf + 0.05f, hlCol);
        }
        if (isFaceVisible(cx, cz, cosT, sinT, 0f, 0.54f, zr, 0f, 0f, 1f)) {
            drawFacetedQuad(c, m, vx, vy, vw, vh, cx, cz, cosT, sinT,
                    -hw, 0.22f, zr, hw, 0.22f, zr, hw, 0.94f, zr - 0.10f, -hw, 0.94f, zr - 0.10f, aByte | cRear);
            if (lamp > 0) {
                int tlCol = aByte | (lamp >= 2 ? 0xFF3344 : 0xD92B3E);
                drawFacetedQuad(c, m, vx, vy, vw, vh, cx, cz, cosT, sinT,
                        -hw * 0.86f, 0.66f, zr - 0.01f, -hw * 0.36f, 0.66f, zr - 0.01f, -hw * 0.36f, 0.80f, zr - 0.03f, -hw * 0.86f, 0.80f, zr - 0.03f, tlCol);
                drawFacetedQuad(c, m, vx, vy, vw, vh, cx, cz, cosT, sinT,
                        hw * 0.36f, 0.66f, zr - 0.01f, hw * 0.86f, 0.66f, zr - 0.01f, hw * 0.86f, 0.80f, zr - 0.03f, hw * 0.36f, 0.80f, zr - 0.03f, tlCol);
            }
        }
        if (isFaceVisible(cx, cz, cosT, sinT, -hw, 0.54f, 0f, -1f, 0f, 0f)) {
            drawFacetedQuad(c, m, vx, vy, vw, vh, cx, cz, cosT, sinT,
                    -hw, 0.20f, zf, -hw, 0.22f, zr, -hw, 0.94f, zr - 0.10f, -hw, 0.86f, zf + 0.14f, aByte | cSide);
        }
        if (isFaceVisible(cx, cz, cosT, sinT, hw, 0.54f, 0f, 1f, 0f, 0f)) {
            drawFacetedQuad(c, m, vx, vy, vw, vh, cx, cz, cosT, sinT,
                    hw, 0.20f, zf, hw, 0.22f, zr, hw, 0.94f, zr - 0.10f, hw, 0.86f, zf + 0.14f, aByte | cSide);
        }
        drawFacetedQuad(c, m, vx, vy, vw, vh, cx, cz, cosT, sinT,
                -hw, 0.86f, zf + 0.14f, hw, 0.86f, zf + 0.14f, hw, 0.94f, zr - 0.10f, -hw, 0.94f, zr - 0.10f, aByte | cTop);

        // Cabin greenhouse + roof
        float gw = 0.72f, gzf = -0.78f, gzr = 1.48f, gtop = 1.42f;
        if (isFaceVisible(cx, cz, cosT, sinT, 0f, 1.15f, -0.98f, 0f, 0.45f, -0.89f)) {
            drawFacetedQuad(c, m, vx, vy, vw, vh, cx, cz, cosT, sinT,
                    -hw * 0.90f, 0.88f, -1.16f, hw * 0.90f, 0.88f, -1.16f, gw, gtop, gzf, -gw, gtop, gzf, aByte | cGlass);
        }
        if (isFaceVisible(cx, cz, cosT, sinT, 0f, 1.15f, 1.72f, 0f, 0.45f, 0.89f)) {
            drawFacetedQuad(c, m, vx, vy, vw, vh, cx, cz, cosT, sinT,
                    -hw * 0.90f, 0.94f, 1.94f, hw * 0.90f, 0.94f, 1.94f, gw, gtop, gzr, -gw, gtop, gzr, aByte | cGlass);
        }
        if (isFaceVisible(cx, cz, cosT, sinT, -0.80f, 1.15f, 0.35f, -0.92f, 0.38f, 0f)) {
            drawFacetedQuad(c, m, vx, vy, vw, vh, cx, cz, cosT, sinT,
                    -hw * 0.90f, 0.88f, -1.16f, -hw * 0.90f, 0.94f, 1.94f, -gw, gtop, gzr, -gw, gtop, gzf, aByte | cGlass);
        }
        if (isFaceVisible(cx, cz, cosT, sinT, 0.80f, 1.15f, 0.35f, 0.92f, 0.38f, 0f)) {
            drawFacetedQuad(c, m, vx, vy, vw, vh, cx, cz, cosT, sinT,
                    hw * 0.90f, 0.88f, -1.16f, hw * 0.90f, 0.94f, 1.94f, gw, gtop, gzr, gw, gtop, gzf, aByte | cGlass);
        }
        drawFacetedQuad(c, m, vx, vy, vw, vh, cx, cz, cosT, sinT,
                -gw, gtop, gzf, gw, gtop, gzf, gw, gtop, gzr, -gw, gtop, gzr, aByte | cTop);
    }

    private void drawRoad(Canvas c, HudState s, float[] vz, String gear, int spd, boolean drawEgo) {
        float vx = vz[0], vy = vz[1], vw = vz[2], vh = vz[3];
        float curve = curEffCurve;

        final float bottom = vy + vh, horizon = vy + vh * 0.38f, bottomV = vy + vh * 0.985f;
        final float tanP = (float) Math.tan(Math.toRadians(EGO_PITCH_DEG));
        final float kx = (bottomV - horizon) / tanP;
        final float egoWpx = Math.min(vw * 0.165f, vh * 0.27f) * 1.13f;
        final float egoD = 1.9f * kx / egoWpx;
        camU0 = vx + vw * 0.52f;
        camKx = kx;
        camEgoD = egoD;
        camH = egoD * tanP;
        camHorizon = horizon;
        camCurve = curve;
        camBendPivot = drawEgo ? egoD : (egoD * 1.287f);
        camChaseSlope = drawEgo ? 0f : (0.522f * (float) Math.tan(Math.toRadians(curChaseYawDeg)));
        final float near = kx * camH / (bottom - horizon);

        float[] xs = roadXs;
        for (int i = 0; i <= 24; i++) xs[i] = near + 120f * (i / 24f) * (i / 24f);

        if (!lineShadersBuilt || lineShadersLight != lightTheme) {
            lineShadersBuilt = true;
            lineShadersLight = lightTheme;
            int[][] rgbA = lightTheme
                    ? new int[][]{{148, 156, 170, 130}, {100, 110, 128, 150}, {68, 78, 96, 215}}
                    : new int[][]{{160, 164, 172, 128}, {230, 232, 236, 140}, {245, 245, 247, 230}};
            for (int k = 0; k < 3; k++) {
                int col = (rgbA[k][0] << 16) | (rgbA[k][1] << 8) | rgbA[k][2];
                lineShaders[k] = new LinearGradient(0f, bottom, 0f, horizon,
                        new int[]{(rgbA[k][3] << 24) | col, (Math.round(rgbA[k][3] * 0.08f) << 24) | col},
                        new float[]{0f, 1f}, Shader.TileMode.CLAMP);
            }
            int[] roadCols = lightTheme
                    ? new int[]{0x00334155, 0x0C334155, 0x16334155}
                    : new int[]{0x00FFFFFF, 0x05FFFFFF, 0x09FFFFFF};
            roadShader = new LinearGradient(0f, camV(xs[24]), 0f, bottom,
                    roadCols, new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP);
        }

        path.reset();
        for (int i = 0; i < xs.length; i++) {
            if (i == 0) path.moveTo(camU(xs[i], -9f), camV(xs[i]));
            else path.lineTo(camU(xs[i], -9f), camV(xs[i]));
        }
        for (int i = xs.length - 1; i >= 0; i--) path.lineTo(camU(xs[i], 9f), camV(xs[i]));
        path.close();
        pFill.setShader(roadShader);
        c.drawPath(path, pFill);

        float[][] styles = {{9.0f, 0.06f, 0f}, {5.4f, 0.055f, 1f}, {1.8f, 0.07f, 0f}};
        for (int k = 0; k < 3; k++) {
            path.reset();
            boolean dashed = styles[k][2] > 0f;
            for (int side = -1; side <= 1; side += 2) {
                float lat = side * styles[k][0], w = styles[k][1];
                for (int i = 0; i < xs.length - 1; i++) {
                    float x0 = xs[i], x1 = xs[i + 1];
                    if (dashed && (((int) Math.floor((x0 + phase) / 3.5f)) & 1) != 0) continue;
                    float v0 = camV(x0), v1 = camV(x1);
                    path.moveTo(camU(x0, lat - w), v0);
                    path.lineTo(camU(x0, lat + w), v0);
                    path.lineTo(camU(x1, lat + w), v1);
                    path.lineTo(camU(x1, lat - w), v1);
                    path.close();
                }
            }
            pFill.setShader(lineShaders[k]);
            c.drawPath(path, pFill);
        }
        pFill.setShader(null);

        boolean hasLead = s.hasLead && smoothLeadDist > 0.5f;
        float leadX = hasLead ? camX(egoD, smoothLeadDist) : 0f;
        boolean crit = hasLead && smoothLeadDist <= 12f, close = hasLead && smoothLeadDist <= 18f;
        if ("D".equals(gear) || "R".equals(gear)) {
            boolean apPath = s.isCruiseEngaged() || s.isSteeringEngaged();
            float end = Math.max(near + 0.5f, hasLead ? leadX - 1.5f : near + 70f);
            int rgb = lightTheme
                    ? (crit ? 0xDC2626 : (close ? 0xD97706 : (apPath ? 0x2563EB : 0x475569)))
                    : (crit ? 0xE31937 : (close ? 0xF5A623 : (apPath ? 0x3E6AE1 : 0xC8CDD7)));
            int coreRgb = (apPath && !close) ? blendRgb(rgb, lightTheme ? 0x60A5FA : 0x74B3FF, 0.28f) : rgb;
            int aMax = lightTheme ? ((apPath || close) ? 185 : 70) : ((apPath || close) ? 175 : 56);
            float fSpan = hasLead ? Math.max(2.5f, (end - near) * 0.35f) : Math.max(12f, (end - near) * 0.42f);
            float fStart = Math.max(near, end - fSpan);

            int nPts = 0;
            for (int i = 0; i < xs.length - 1; i++) {
                float x0 = xs[i];
                if (x0 >= end - 0.05f) break;
                float x1 = Math.min(end, xs[i + 1]);
                float v0 = camV(x0), v1 = camV(x1);
                float fa0 = x0 <= fStart ? 1f : (end - x0) / Math.max(0.1f, end - fStart);
                float fa1 = x1 <= fStart ? 1f : (end - x1) / Math.max(0.1f, end - fStart);
                float la0 = fa0 * fa0 * aMax * (i == 0 ? 0.55f : 1f);
                float la1 = fa1 * fa1 * aMax;

                for (int li = 0; li < PATH_LAT_X.length - 1; li++) {
                    if (nPts + 6 > RIBBON_MAX_PTS) break;
                    float lx0 = PATH_LAT_X[li], lx1 = PATH_LAT_X[li + 1];
                    float uL0 = camU(x0, lx0), uR0 = camU(x0, lx1);
                    float uL1 = camU(x1, lx0), uR1 = camU(x1, lx1);
                    int cL0 = (Math.max(0, Math.min(255, Math.round(la0 * PATH_LAT_A[li]))) << 24) | blendRgb(rgb, coreRgb, PATH_LAT_C[li]);
                    int cR0 = (Math.max(0, Math.min(255, Math.round(la0 * PATH_LAT_A[li + 1]))) << 24) | blendRgb(rgb, coreRgb, PATH_LAT_C[li + 1]);
                    int cL1 = (Math.max(0, Math.min(255, Math.round(la1 * PATH_LAT_A[li]))) << 24) | blendRgb(rgb, coreRgb, PATH_LAT_C[li]);
                    int cR1 = (Math.max(0, Math.min(255, Math.round(la1 * PATH_LAT_A[li + 1]))) << 24) | blendRgb(rgb, coreRgb, PATH_LAT_C[li + 1]);

                    int p = nPts * 2;
                    ribbonVerts[p]     = uL0; ribbonVerts[p + 1]  = v0;
                    ribbonVerts[p + 2] = uR0; ribbonVerts[p + 3]  = v0;
                    ribbonVerts[p + 4] = uR1; ribbonVerts[p + 5]  = v1;
                    ribbonVerts[p + 6] = uL0; ribbonVerts[p + 7]  = v0;
                    ribbonVerts[p + 8] = uR1; ribbonVerts[p + 9]  = v1;
                    ribbonVerts[p + 10] = uL1; ribbonVerts[p + 11] = v1;
                    ribbonColors[nPts]     = cL0; ribbonColors[nPts + 1] = cR0; ribbonColors[nPts + 2] = cR1;
                    ribbonColors[nPts + 3] = cL0; ribbonColors[nPts + 4] = cR1; ribbonColors[nPts + 5] = cL1;
                    nPts += 6;
                }
            }
            if (nPts >= 3) {
                pFill.setShader(null);
                pFill.setColor(0xFFFFFFFF);
                c.drawVertices(Canvas.VertexMode.TRIANGLES, nPts * 2, ribbonVerts, 0,
                        null, 0, ribbonColors, 0, null, 0, 0, pFill);
            }
        }

        long nowMs = System.currentTimeMillis();
        float[] tr = ((s.commaConnected || s.commaDirectConnected) && s.trafficLeads != null && nowMs - s.trafficMs < 1500L)
                ? s.trafficLeads : new float[0];
        int n = hasLead ? 1 : 0;
        for (int i = 0; i + 3 < tr.length; i += 4) if (!isStationaryTarget(spd, tr[i + 3])) n++;
        float[] cx = new float[n], cy = new float[n];
        int[] cl = new int[n];
        boolean[] isLead = new boolean[n];
        int m = 0;
        for (int i = 0; i + 3 < tr.length; i += 4) {
            if (isStationaryTarget(spd, tr[i + 3])) continue;
            cx[m] = camX(egoD, tr[i]);
            cy[m] = tr[i + 1];
            cl[m] = tr[i + 2] < -1.5f ? 2 : (s.headLamp ? 1 : 0);
            m++;
        }
        if (hasLead) {
            cx[m] = leadX;
            cy[m] = smoothLeadY;
            cl[m] = s.leadAccelMps2 < -1.5f ? 2 : (s.headLamp ? 1 : 0);
            isLead[m] = true;
        }
        for (int i = 1; i < n; i++) {
            for (int j = i; j > 0 && cx[j] > cx[j - 1]; j--) {
                float tf = cx[j]; cx[j] = cx[j - 1]; cx[j - 1] = tf;
                tf = cy[j]; cy[j] = cy[j - 1]; cy[j - 1] = tf;
                int ti = cl[j]; cl[j] = cl[j - 1]; cl[j - 1] = ti;
                boolean tb = isLead[j]; isLead[j] = isLead[j - 1]; isLead[j - 1] = tb;
            }
        }

        boolean nav = navReady();
        float tagX = 0f, tagY = 0f;
        boolean tag = false;
        for (int i = 0; i < n; i++) {
            float x = cx[i], y = cy[i], gx = camU(x, y), gy = camV(x);
            float top;
            if (nav) {
                float elev = (float) Math.toDegrees(Math.atan2(camH, x));
                NavSprite sp = navSets[navSetFor(navSets, elev)][navIndex(x, y, curve, camBendPivot, camChaseSlope)];
                float alpha = Math.max(0.35f, Math.min(1f, 1.3f - (x - egoD) / 90f));
                Paint tp = isLead[i] ? (lightTheme ? pTintLeadLight : pTintLead) : (lightTheme ? pTintOtherLight : pTintOther);
                top = drawNav(c, sp, gx, gy, (kx / x) / sp.ppm, tp, cl[i], alpha);
            } else if (leadCar != null) {
                float w = Math.abs(kx * (1.9f / x)), fullW = w * (200f / 172f), fullH = fullW * 0.8f;
                drawImage(c, leadCar, gx - fullW / 2f, gy - fullH * (158f / 160f), fullW, fullH);
                top = fullH * (137f / 160f);
            } else {
                top = 0f;
            }
            if (isLead[i]) {
                tag = true;
                tagX = gx;
                tagY = gy - top;
            }
        }
        if (tag) {
            String t = Math.round(smoothLeadDist) + " m";
            float size = vw / 30f;
            float tw = measure(t, size, true);
            text(c, t, tagX - tw / 2f, tagY - vw / 120f, size, true,
                    themeColor(crit ? 0xFFFF4D63 : (close ? 0xFFF5A623 : 0xFFC9CBD1), lightTheme));
        }

        if (!drawEgo) return;
        int egoLamp = s.brakeLamp ? 2 : (s.headLamp ? 1 : 0);
        Paint egoPaint = lightTheme ? pTintEgoLight : pTintEgo;
        if (nav && drawNav(c, navEgo, camU(egoD, 0f), camV(egoD), (kx / egoD) / navEgo.ppm, egoPaint, egoLamp, 1f) > 0f) {
            return;
        }
        Bitmap ego = s.brakeLamp ? egoBrake : (s.headLamp ? egoTail : egoOff);
        if (ego != null) {
            float w = Math.min(vw * 0.165f, vh * 0.27f), k = (w * 1.13f) / EGO_CAR_W;
            float ex = camU0, by = bottomV, bodyH = EGO_CAR_H * k;
            shadow(c, ex, by - bodyH * 0.02f, EGO_CAR_W * k * 0.58f, 0.14f, lightTheme ? 0.36f : 0.55f);
            drawImage(c, ego, ex - (EGO_CAR_W / 2f + EGO_MARGIN) * k, by - (EGO_CAR_H + EGO_MARGIN) * k, EGO_IMG_W * k, EGO_IMG_H * k);
        }
    }

    private static int scaleAlpha(int argb, float factor) {
        int a = Math.max(0, Math.min(255, Math.round(((argb >>> 24) & 0xFF) * factor)));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    private static int withAlpha(int rgb, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    private void drawParkedCached(Canvas c, float[] vz, boolean head, boolean brake, boolean iceMode, boolean[] doors) {
        int x0 = (int) Math.floor(vz[0]), y0 = (int) Math.floor(vz[1]);
        int w = (int) Math.ceil(vz[0] + vz[2]) - x0, h = (int) Math.ceil(vz[1] + vz[3]) - y0;
        if (w <= 0 || h <= 0) return;
        boolean animating = (parkHeadAnim > 0.001f && parkHeadAnim < 0.999f)
                || (parkBrakeAnim > 0.001f && parkBrakeAnim < 0.999f);
        if (!animating) {
            for (int i = 0; i < 6; i++) {
                float a = doorAnim[i];
                if (a > 0.001f) { animating = true; break; }
            }
        }
        if (animating) {
            int sc = c.save();
            c.clipRect(x0, y0, x0 + w, y0 + h);
            drawParked(c, vz);
            c.restoreToCount(sc);
            return;
        }
        int bits = (parkHeadAnim >= 0.5f ? 1 : 0) | (parkBrakeAnim >= 0.5f ? 2 : 0);
        for (int i = 0; i < 6; i++) if (doorAnim[i] >= 0.5f) bits |= 4 << i;
        String key = (iceMode ? 1 : 0) + ":" + (lightTheme ? 1 : 0) + ":" + x0 + "," + y0 + "," + w + "x" + h + ":" + bits
                + ":" + (navTop != null && navTop.bmp != null);
        if (parkCache == null || parkCacheW != w || parkCacheH != h) {
            parkCache = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            parkCanvas = new Canvas(parkCache);
            parkCacheW = w;
            parkCacheH = h;
            parkKey = "";
        }
        if (!key.equals(parkKey)) {
            int sc = parkCanvas.save();
            parkCanvas.translate(-x0, -y0);
            parkCanvas.drawBitmap(curBase, 0f, 0f, null);
            drawParked(parkCanvas, vz);
            parkCanvas.restoreToCount(sc);
            parkKey = key;
        }
        c.drawBitmap(parkCache, x0, y0, null);
    }

    private void drawParked(Canvas c, float[] vz) {
        if (navTop == null || !navReady() || !navBitmaps(navTop)) {
            float vx = vz[0], vy = vz[1], vw = vz[2], vh = vz[3];
            if (!lightTheme) glow(c, vx + vw * 0.58f, vy + vh * 0.9f, vw * 0.55f, 0xFFFFFF, 0.10f);
            if (parkCar != null) {
                float w = Math.min(vw * 0.78f, vh * 1.36f), h = w * PARK_H / PARK_W;
                float cx = vx + vw * 0.58f, bottom = vy + vh * 0.96f;
                shadow(c, cx, bottom - h * 0.05f, w * 0.52f, 0.12f, lightTheme ? 0.38f : 0.65f);
                drawImage(c, parkCar, cx - w / 2f, bottom - h, w, h);
            }
            return;
        }
        if (topHead == null) topHead = loadAssetBitmap("design_tesla/navdy_top_head.png");
        if (topTail == null) topTail = loadAssetBitmap("design_tesla/navdy_top_tail.png");
        float headA = parkHeadAnim * parkHeadAnim * (3f - 2f * parkHeadAnim);
        float brakeA = parkBrakeAnim * parkBrakeAnim * (3f - 2f * parkBrakeAnim);
        float vx = vz[0], vy = vz[1], vw = vz[2], vh = vz[3];
        boolean wide = (vh > 0f) && ((vw / vh) > 1.75f);
        float ppm = wide ? Math.min(vw * 0.098f, vh * 0.215f) : Math.min(vw * 0.115f, vh * 0.24f);
        float cx = vx + vw * (wide ? 0.535f : 0.55f), cy = vy + vh * (wide ? 0.60f : 0.54f);
        if (!lightTheme) glow(c, cx, cy, vw * 0.5f, 0xFFFFFF, 0.07f);
        if (headA > 0.01f) {
            int[] beamCols = lightTheme
                    ? new int[]{scaleAlpha(0x423B82F6, headA), scaleAlpha(0x223B82F6, headA), scaleAlpha(0x0A3B82F6, headA), 0x003B82F6}
                    : new int[]{scaleAlpha(0x8CD7E8FF, headA), scaleAlpha(0x3DD7E8FF, headA), scaleAlpha(0x12D7E8FF, headA), 0x00D7E8FF};
            Paint beamPaint = lightTheme ? pShade : pAdd;
            for (int side = -1; side <= 1; side += 2) {
                float x0 = cx + 2.2f * ppm, y0 = cy + side * 0.62f * ppm, reach = 4.0f * ppm;
                beamPaint.setShader(new RadialGradient(x0, y0, reach, beamCols, new float[]{0f, 0.3f, 0.7f, 1f}, Shader.TileMode.CLAMP));
                path.reset();
                path.moveTo(x0, y0);
                rf.set(x0 - reach, y0 - reach, x0 + reach, y0 + reach);
                path.arcTo(rf, (float) Math.toDegrees(-0.42 + side * 0.05), (float) Math.toDegrees(0.84));
                path.close();
                c.drawPath(path, beamPaint);
                beamPaint.setShader(null);
            }
        }
        float lampA = Math.max(headA, brakeA);
        if (lampA > 0.01f) {
            for (int side = -1; side <= 1; side += 2) {
                float x0 = cx - 2.3f * ppm, y0 = cy + side * 0.66f * ppm;
                if (brakeA > 0.01f) {
                    float tailGlowAlpha = Math.max(0f, headA - brakeA) * (lightTheme ? 0.22f : 0.28f);
                    if (tailGlowAlpha > 0.01f) {
                        softGlow(c, x0, y0, 0.8f * ppm, lightTheme ? 0xDC2626 : 0xFF1E32, tailGlowAlpha, !lightTheme);
                    }
                    softGlow(c, x0, y0, (0.8f + 0.45f * brakeA) * ppm, lightTheme ? 0xDC2626 : 0xFF192D,
                            (lightTheme ? 0.36f : 0.55f) * brakeA, !lightTheme);
                    softGlow(c, x0, y0, 0.45f * ppm, lightTheme ? 0xE11D48 : 0xFF3C50,
                            (lightTheme ? 0.52f : 0.8f) * brakeA, !lightTheme);
                } else {
                    softGlow(c, x0, y0, 0.8f * ppm, lightTheme ? 0xDC2626 : 0xFF1E32,
                            (lightTheme ? 0.22f : 0.28f) * headA, !lightTheme);
                }
            }
        }
        int panelFillCol = lightTheme ? 0xFF9AA3B2 : 0xFFD9DDE3;
        int glassCutCol = lightTheme ? 0xD93B4252 : 0xD9282C34;
        int mirrorCol = lightTheme ? 0xFF9AA3B2 : 0xFFC9CDD4;
        float dBlink = 0.35f + 0.65f * curDoorBlink;
        int dRgb = blendRgb(0xFF5A1F, 0xFFCA3A, curDoorBlink);
        for (int i = 0; i < 4; i++) {
            float o = doorEased[i];
            if (o <= 0.005f) continue;
            float fade = Math.min(1f, o * 4.5f);
            double a = Math.toRadians(58.0 * o);
            float hinge = DOOR_GEO[i][0], len = DOOR_GEO[i][1], side = DOOR_GEO[i][2];
            float hx = cx + hinge * ppm, hy = cy + side * 0.93f * ppm;
            float ex = hx - (float) Math.cos(a) * len * ppm, ey = hy + side * (float) Math.sin(a) * len * ppm;
            strokeLine(c, hx, hy, ex, ey, 0.2f * ppm, withAlpha(dRgb, 0.72f * dBlink * fade));
            strokeLine(c, hx, hy, ex, ey, 0.1f * ppm, scaleAlpha(panelFillCol, fade));
            strokeLine(c, hx + (ex - hx) * 0.12f, hy + (ey - hy) * 0.12f,
                    hx + (ex - hx) * 0.8f, hy + (ey - hy) * 0.8f, 0.035f * ppm, scaleAlpha(glassCutCol, fade));
        }
        shadow(c, cx, cy + 0.05f * ppm, 2.55f * ppm, 0.42f, lightTheme ? 0.35f : 0.5f);
        float k = ppm / navTop.ppm;
        int sc = c.save();
        c.translate(cx, cy);
        c.rotate(90f, 0f, 0f);
        rf.set(-navTop.ax * k, -navTop.ay * k, (navTop.w - navTop.ax) * k, (navTop.h - navTop.ay) * k);
        Paint tp = lightTheme ? pTintEgoLight : pTintEgo;
        tp.setAlpha(255);
        c.drawBitmap(navTop.bmp, null, rf, tp);
        if (headA > 0.01f && topHead != null) {
            Paint hp = lightTheme ? pLampHeadLight : pLampHead;
            hp.setAlpha(Math.round(255f * headA));
            c.drawBitmap(topHead, null, rf, hp);
        }
        float tailAlpha = Math.max(headA * (lightTheme ? 190f : 166f), brakeA * 255f);
        if (tailAlpha > 1f && topTail != null) {
            Paint bp = lightTheme ? pLampLight : pLamp;
            bp.setAlpha(Math.round(tailAlpha));
            c.drawBitmap(topTail, null, rf, bp);
        }
        c.restoreToCount(sc);
        for (int side = -1; side <= 1; side += 2) {
            float my = cy + side * 0.93f * ppm + (side < 0 ? -0.2f * ppm : 0f);
            roundRect(c, cx + 1.02f * ppm, my, cx + 1.18f * ppm, my + 0.2f * ppm, 0.05f * ppm, mirrorCol);
        }
        for (int i = 0; i < 4; i++) {
            float o = doorEased[i];
            if (o <= 0.005f) continue;
            float hinge = DOOR_GEO[i][0], len = DOOR_GEO[i][1], side = DOOR_GEO[i][2];
            float y0 = cy + side * 0.93f * ppm, y1 = cy + side * (0.93f - 0.15f * o) * ppm;
            roundRect(c, cx + (hinge - len) * ppm, Math.min(y0, y1), cx + hinge * ppm, Math.max(y0, y1),
                    0f, withAlpha(dRgb, 0.88f * dBlink * o));
        }
        float tr = doorEased[4];
        if (tr > 0.005f) {
            float fade = Math.min(1f, tr * 2.4f);
            float slideX = (1f - tr) * 0.72f * ppm;
            roundRect(c, cx - 2.95f * ppm + slideX, cy - 0.78f * ppm, cx - 2.15f * ppm + slideX * 0.35f, cy + 0.78f * ppm,
                    0.12f * ppm, withAlpha(dRgb, 0.55f * dBlink * fade));
            roundRect(c, cx - 2.88f * ppm + slideX, cy - 0.72f * ppm, cx - 2.26f * ppm + slideX * 0.5f, cy + 0.72f * ppm,
                    0.1f * ppm, scaleAlpha(panelFillCol, fade));
            roundRect(c, cx - 2.8f * ppm + slideX, cy - 0.55f * ppm, cx - 2.5f * ppm + slideX * 0.8f, cy + 0.55f * ppm,
                    0.06f * ppm, scaleAlpha(glassCutCol, fade));
        }
        float fr = doorEased[5];
        if (fr > 0.005f) {
            float fade = Math.min(1f, fr * 2.2f);
            float liftX = fr * 0.08f * ppm;
            float insetY = (1f - fr) * 0.14f * ppm;
            roundRect(c, cx + 1.66f * ppm + liftX, cy - 0.82f * ppm + insetY,
                    cx + 2.28f * ppm + liftX, cy + 0.82f * ppm - insetY,
                    0.12f * ppm, withAlpha(dRgb, 0.32f * dBlink * fade));
            pStroke.setColor(withAlpha(dRgb, (0.55f + 0.45f * curDoorBlink) * fade));
            pStroke.setStrokeWidth(0.06f * ppm);
            rf.set(cx + 1.66f * ppm + liftX, cy - 0.82f * ppm + insetY,
                    cx + 2.28f * ppm + liftX, cy + 0.82f * ppm - insetY);
            c.drawRoundRect(rf, 0.12f * ppm, 0.12f * ppm, pStroke);
        }
    }

    // ---- Overlays: Speed, Telltales, Limit, Wheel, Cruise, TBT, TPMS, Lane ----

    private void drawVizOverlays(Canvas c, HudState s, float[] vz, String gear, int spd, boolean parked, boolean iceMode) {
        copyActive(c, curL.rect("prnd" + gear));
        field(c, "speed", String.valueOf(spd), 0);
        drawTelltales(c, String.valueOf(spd), s.pbrake, iceMode, s.milOn);

        if (!parked) {
            boolean camera = !(s.sdiType < 0 && s.sdiDistM <= 0);
            int limit = Math.max(0, s.sdiSpeedLimit);
            float[] lr = curL.rect("limit");
            if (limit > 0 && lr != null) {
                float cx = lr[0] + lr[2] / 2f, cy = lr[1] + lr[3] / 2f, r = lr[2] / 2f;
                rf.set(cx - r, cy - r, cx + r, cy + r);
                pFill.setShader(null);
                pFill.setColor(0xFFFFFFFF);
                c.drawOval(rf, pFill);
                float sw = r * 0.23f;
                pStroke.setStrokeWidth(sw);
                pStroke.setColor(camera ? 0xFFE31937 : 0xFF3E6AE1);
                rf.set(cx - r + sw / 2f, cy - r + sw / 2f, cx + r - sw / 2f, cy + r - sw / 2f);
                c.drawOval(rf, pStroke);
                field(c, "limit", String.valueOf(limit), 0);
            }

            float[] wr = curL.rect("wheel");
            if (wr != null) {
                float cx = wr[0] + wr[2] / 2f, cy = wr[1] + wr[3] / 2f, u = wr[2] / 24f;
                boolean steerOn = s.isSteeringEngaged() || s.isCruiseEngaged();
                int col = themeColor(steerOn ? 0xFF6E95FF : 0xFF5A5D64, lightTheme);
                int sc = c.save();
                c.rotate(Math.max(-540f, Math.min(540f, -smoothSteer)), cx, cy);
                pStroke.setColor(col);
                pStroke.setStrokeWidth(2.2f * u);
                rf.set(cx - 9 * u, cy - 9 * u, cx + 9 * u, cy + 9 * u);
                c.drawOval(rf, pStroke);
                rf.set(cx - 2.2f * u, cy - 2.2f * u, cx + 2.2f * u, cy + 2.2f * u);
                c.drawOval(rf, pStroke);
                path.reset();
                path.moveTo(cx - 8.5f * u, cy - 1.5f * u); path.lineTo(cx - 2.2f * u, cy - 1.5f * u);
                path.moveTo(cx + 2.2f * u, cy - 1.5f * u); path.lineTo(cx + 8.5f * u, cy - 1.5f * u);
                path.moveTo(cx, cy + 2.2f * u); path.lineTo(cx, cy + 9f * u);
                c.drawPath(path, pStroke);
                c.restoreToCount(sc);
            }

            if (s.isCruiseEngaged()) field(c, "cruise", String.valueOf(s.cruiseKph), 0);
            if (camera && s.sdiDistM > 0) {
                int d = s.sdiDistM;
                String ds = d >= 1000 ? String.format(Locale.US, "%.1f km", d / 1000.0) : (d + " m");
                boolean section = s.sdiType == 2 || s.sdiType == 3 || s.sdiType == 4 || d >= 800;
                field(c, "sect", section ? ("구간단속 " + ds + " · 평균 " + Math.max(spd, limit)) : ("과속카메라 " + ds), 0);
            }
        }

        boolean tbt = !parked && s.tbtDistM > 0 && vz != null;
        lastTbtActive = false;
        if (tbt) drawTbt(c, s, vz);
        drawTpms(c, s, vz, false);
        if (!parked) {
            Field lf = curL.fields.get("lane");
            String lane = s.laneConfPct > 0f ? String.format(Locale.US, "%.0f%%", s.laneConfPct)
                    : ((s.commaConnected || s.commaDirectConnected) ? ((s.isCruiseEngaged() || s.isSteeringEngaged()) ? "ON" : "대기") : "--");
            if (lf != null) {
                float pw = measure("차선 ", lf.size, false);
                text(c, "차선 ", lf.x - pw, lf.y, lf.size, false, themeColor(0xFF5A5D64, lightTheme));
                field(c, "lane", lane, 0);
            }
        }
    }

    private void drawTelltales(Canvas c, String speedText, boolean pbrake, boolean iceMode, boolean milOn) {
        float[] r0 = curL.rect("ttHead"), r1 = curL.rect("ttHigh"), r2 = curL.rect("ttPbrake");
        Field f = curL.fields.get("speed");
        if (r0 == null || r1 == null || r2 == null || f == null) return;
        Float gap = curL.sizes.get("ttGap");
        float w = measure(fit(speedText, f.size, f.bold, f.maxW), f.size, f.bold);
        float x0 = f.x + w + (gap != null ? gap : 12f);
        float step = r1[0] - r0[0];
        if (step <= 0f) step = r2[2] * 1.25f;
        int[] ids = iceMode ? new int[]{2, 3} : new int[]{2};
        boolean[] on = {pbrake, milOn};
        int[] col = lightTheme ? new int[]{0xFFDC2626, 0xFFD97706} : new int[]{TT_PBRAKE, TT_MIL};
        int offCol = lightTheme ? 0xFFB6BECC : TT_OFF;
        for (int i = 0; i < ids.length; i++) {
            float x = x0 + i * step, y = r0[1], sz = r2[2], u = sz / 32f;
            pIcon.setColor(on[i] ? col[i] : offCol);
            pIcon.setStrokeWidth(2.3f * u);
            path.reset();
            if (ids[i] == 2) {
                rf.set(x + 7.4f * u, y + 7.4f * u, x + 24.6f * u, y + 24.6f * u);
                c.drawOval(rf, pIcon);
                rf.set(x + 4.3f * u, y + 4.3f * u, x + 27.7f * u, y + 27.7f * u);
                path.addArc(rf, 142f, 76f);
                path.addArc(rf, -38f, 76f);
                path.moveTo(x + 13.6f * u, y + 20.5f * u);
                path.lineTo(x + 13.6f * u, y + 11.5f * u);
                path.lineTo(x + 17f * u, y + 11.5f * u);
                rf.set(x + 14.4f * u, y + 11.5f * u, x + 19.6f * u, y + 16.7f * u);
                path.arcTo(rf, -90f, 180f);
                path.lineTo(x + 13.6f * u, y + 16.7f * u);
            } else {
                path.moveTo(x + 11 * u, y + 8.5f * u); path.lineTo(x + 19 * u, y + 8.5f * u);
                path.moveTo(x + 15 * u, y + 8.5f * u); path.lineTo(x + 15 * u, y + 12 * u);
                path.moveTo(x + 4.5f * u, y + 14 * u); path.lineTo(x + 4.5f * u, y + 22 * u);
                path.moveTo(x + 4.5f * u, y + 18 * u); path.lineTo(x + 8 * u, y + 18 * u);
                path.moveTo(x + 8 * u, y + 13.5f * u); path.lineTo(x + 10.5f * u, y + 12 * u);
                path.lineTo(x + 18 * u, y + 12 * u); path.lineTo(x + 20 * u, y + 14.5f * u);
                path.lineTo(x + 24.2f * u, y + 14.5f * u); path.lineTo(x + 26 * u, y + 16.5f * u);
                path.lineTo(x + 26 * u, y + 21 * u); path.lineTo(x + 24.2f * u, y + 23 * u);
                path.lineTo(x + 20 * u, y + 23 * u); path.lineTo(x + 18 * u, y + 25 * u);
                path.lineTo(x + 10.5f * u, y + 25 * u); path.lineTo(x + 8 * u, y + 23 * u);
                path.close();
                path.moveTo(x + 27.5f * u, y + 15.5f * u); path.lineTo(x + 27.5f * u, y + 21 * u);
            }
            c.drawPath(path, pIcon);
        }
    }

    private void drawTbt(Canvas c, HudState s, float[] vz) {
        float[] tr = curL.rect("tbt");
        Field roadF = curL.fields.get("navRoad");
        Float ds = curL.sizes.get("tbtDist");
        if (tr == null || roadF == null || ds == null) return;
        float distSize = ds, roadSize = roadF.size;
        int d = s.tbtDistM;
        String num = d >= 1000 ? String.format(Locale.US, "%.1f", d / 1000.0) : String.valueOf(d);
        String unit = d >= 1000 ? "km" : "m";
        String road = s.tbtRoad != null ? s.tbtRoad : "";
        if (road.contains("·")) {
            String[] parts = road.split("·");
            road = parts[parts.length - 1].trim();
        }
        road = fit(road, roadSize, false, distSize * 6f);
        String next = s.tbtDistNextM > 0 ? (s.tbtDistNextM >= 1000
                ? String.format(Locale.US, "+%.1fkm", s.tbtDistNextM / 1000.0) : ("+" + s.tbtDistNextM + "m")) : "";
        float icon = distSize * 1.3f, pad = distSize * 0.45f;
        float wNum = measure(num, distSize, true) + distSize * 0.08f + measure(unit, distSize * 0.55f, false);
        float wRoad = measure(road, roadSize, false) + (next.isEmpty() ? 0f : roadSize * 0.4f + measure(next, roadSize, true));
        float wText = Math.max(wNum, wRoad);
        float h = tr[3], x1 = vz[0] + vz[2] - 20f, y0 = vz[1] + 20f, x0 = x1 - (pad * 2f + icon + pad * 0.8f + wText);
        lastTbtActive = true;
        lastTbtX0 = x0;
        lastTbtY0 = y0;
        lastTbtX1 = x1;
        lastTbtY1 = y0 + h;
        float rr = tr.length > 4 ? tr[4] : 24f;
        roundRect(c, x0, y0, x1, y0 + h, rr, lightTheme ? 0xEBFFFFFF : 0xDB0E0F12);
        pStroke.setStrokeWidth(2f);
        pStroke.setColor(lightTheme ? 0xFFCBD2DF : 0xFF2A2C31);
        rf.set(x0, y0, x1, y0 + h);
        c.drawRoundRect(rf, rr, rr, pStroke);

        float ix = x0 + pad + icon / 2f, iy = y0 + h / 2f, u = icon / 2f;
        int fgPrimary = themeColor(0xFFF5F5F7, lightTheme), fgSec = themeColor(0xFF8E9099, lightTheme);
        drawTbtIcon(c, s.resolveTbtTurnKind(), ix, iy, u, fgPrimary);

        float tx = ix + icon / 2f + pad * 0.8f;
        float base1 = y0 + h * 0.52f, base2 = y0 + h * 0.84f;
        float w1 = text(c, num, tx, base1, distSize, true, fgPrimary);
        text(c, unit, tx + w1 + distSize * 0.08f, base1, distSize * 0.55f, false, fgSec);
        float w2 = text(c, road, tx, base2, roadSize, false, fgSec);
        if (!next.isEmpty()) text(c, next, tx + w2 + roadSize * 0.4f, base2, roadSize, true, fgPrimary);
    }

    private void addArrowHead(float tipX, float tipY, float angleRad, float size) {
        float lx = tipX - size * (float) Math.cos(angleRad - 0.56);
        float ly = tipY - size * (float) Math.sin(angleRad - 0.56);
        float rx = tipX - size * (float) Math.cos(angleRad + 0.56);
        float ry = tipY - size * (float) Math.sin(angleRad + 0.56);
        path.moveTo(lx, ly);
        path.lineTo(tipX, tipY);
        path.lineTo(rx, ry);
    }

    private void drawTbtIcon(Canvas c, int kind, float ix, float iy, float u, int fg) {
        pIcon.setColor(fg);
        pIcon.setStrokeWidth(Math.max(2f, u * 0.27f));
        path.reset();
        float head = u * 0.42f;
        switch (kind) {
            case HudState.TBT_RIGHT:
                path.moveTo(ix - u * 0.25f, iy + u * 0.78f);
                path.lineTo(ix - u * 0.25f, iy - u * 0.12f);
                path.lineTo(ix - u * 0.13f, iy - u * 0.24f);
                path.lineTo(ix + u * 0.68f, iy - u * 0.24f);
                addArrowHead(ix + u * 0.72f, iy - u * 0.24f, 0f, head);
                c.drawPath(path, pIcon);
                break;
            case HudState.TBT_LEFT:
                path.moveTo(ix + u * 0.25f, iy + u * 0.78f);
                path.lineTo(ix + u * 0.25f, iy - u * 0.12f);
                path.lineTo(ix + u * 0.13f, iy - u * 0.24f);
                path.lineTo(ix - u * 0.68f, iy - u * 0.24f);
                addArrowHead(ix - u * 0.72f, iy - u * 0.24f, (float) Math.PI, head);
                c.drawPath(path, pIcon);
                break;
            case HudState.TBT_SLIGHT_RIGHT:
                path.moveTo(ix - u * 0.25f, iy + u * 0.78f);
                path.lineTo(ix - u * 0.25f, iy + u * 0.08f);
                path.lineTo(ix + u * 0.56f, iy - u * 0.66f);
                addArrowHead(ix + u * 0.58f, iy - u * 0.68f, (float) (-Math.PI / 4.0), head);
                c.drawPath(path, pIcon);
                break;
            case HudState.TBT_SLIGHT_LEFT:
                path.moveTo(ix + u * 0.25f, iy + u * 0.78f);
                path.lineTo(ix + u * 0.25f, iy + u * 0.08f);
                path.lineTo(ix - u * 0.56f, iy - u * 0.66f);
                addArrowHead(ix - u * 0.58f, iy - u * 0.68f, (float) (-3.0 * Math.PI / 4.0), head);
                c.drawPath(path, pIcon);
                break;
            case HudState.TBT_SHARP_RIGHT:
                path.moveTo(ix - u * 0.35f, iy + u * 0.78f);
                path.lineTo(ix - u * 0.35f, iy - u * 0.44f);
                path.lineTo(ix + u * 0.58f, iy + u * 0.34f);
                addArrowHead(ix + u * 0.60f, iy + u * 0.36f, (float) (Math.PI / 4.0), head);
                c.drawPath(path, pIcon);
                break;
            case HudState.TBT_SHARP_LEFT:
                path.moveTo(ix + u * 0.35f, iy + u * 0.78f);
                path.lineTo(ix + u * 0.35f, iy - u * 0.44f);
                path.lineTo(ix - u * 0.58f, iy + u * 0.34f);
                addArrowHead(ix - u * 0.60f, iy + u * 0.36f, (float) (3.0 * Math.PI / 4.0), head);
                c.drawPath(path, pIcon);
                break;
            case HudState.TBT_UTURN:
                path.moveTo(ix + u * 0.34f, iy + u * 0.76f);
                path.lineTo(ix + u * 0.34f, iy - u * 0.28f);
                for (int k = 1; k <= 8; k++) {
                    double a = -Math.PI * k / 8.0;
                    path.lineTo(ix + (float) (u * 0.34f * Math.cos(a)), iy - u * 0.28f + (float) (u * 0.34f * Math.sin(a)));
                }
                path.lineTo(ix - u * 0.34f, iy + u * 0.40f);
                addArrowHead(ix - u * 0.34f, iy + u * 0.44f, (float) (Math.PI / 2.0), head * 0.92f);
                c.drawPath(path, pIcon);
                break;
            case HudState.TBT_ROUNDABOUT: {
                float r = u * 0.40f, cy = iy - u * 0.08f;
                for (int k = 0; k <= 14; k++) {
                    double a = Math.PI * 0.65 + (Math.PI * 1.55) * k / 14.0;
                    float px = ix + (float) (r * Math.cos(a)), py = cy + (float) (r * Math.sin(a));
                    if (k == 0) path.moveTo(px, py);
                    else path.lineTo(px, py);
                }
                path.moveTo(ix, iy + u * 0.78f);
                path.lineTo(ix, cy + r);
                addArrowHead(ix + r * 0.85f, cy - r * 0.55f, -0.35f, head * 0.82f);
                c.drawPath(path, pIcon);
                break;
            }
            case HudState.TBT_TOLLGATE:
                path.moveTo(ix - u * 0.72f, iy + u * 0.52f);
                path.lineTo(ix - u * 0.45f, iy - u * 0.42f);
                path.lineTo(ix + u * 0.45f, iy - u * 0.42f);
                path.lineTo(ix + u * 0.72f, iy + u * 0.52f);
                path.moveTo(ix - u * 0.76f, iy + u * 0.06f); path.lineTo(ix + u * 0.76f, iy + u * 0.06f);
                path.moveTo(ix, iy + u * 0.70f); path.lineTo(ix, iy - u * 0.64f);
                addArrowHead(ix, iy - u * 0.68f, (float) (-Math.PI / 2.0), head * 0.85f);
                c.drawPath(path, pIcon);
                break;
            case HudState.TBT_ARRIVE:
                pIcon.setStrokeWidth(Math.max(1.8f, u * 0.24f));
                rf.set(ix - u * 0.62f, iy - u * 0.62f, ix + u * 0.62f, iy + u * 0.62f);
                c.drawOval(rf, pIcon);
                pFill.setShader(null);
                pFill.setColor(fg);
                rf.set(ix - u * 0.24f, iy - u * 0.24f, ix + u * 0.24f, iy + u * 0.24f);
                c.drawOval(rf, pFill);
                break;
            default:
                path.moveTo(ix, iy + u * 0.78f);
                path.lineTo(ix, iy - u * 0.68f);
                addArrowHead(ix, iy - u * 0.72f, (float) (-Math.PI / 2.0), head);
                c.drawPath(path, pIcon);
                break;
        }
    }

    private void drawTpms(Canvas c, HudState s, float[] vz, boolean top) {
        float[] tr = curL.rect("tpms");
        Float ts = curL.sizes.get("tpms");
        if (tr == null || ts == null || vz == null) return;
        float w = tr[2], h = tr[3], x0 = tr[0], y0 = top ? vz[1] + 20f : tr[1], rad = tr.length > 4 ? tr[4] : 20f;
        roundRect(c, x0, y0, x0 + w, y0 + h, rad, lightTheme ? 0xEBFFFFFF : 0xDB0E0F12);
        pStroke.setStrokeWidth(forceParkView ? 2.4f : 2f);
        pStroke.setColor(forceParkView
                ? (lightTheme ? 0xFF2563EB : 0xFF4F7FFF)
                : (lightTheme ? 0xFFCBD2DF : 0xFF2A2C31));
        rf.set(x0, y0, x0 + w, y0 + h);
        c.drawRoundRect(rf, rad, rad, pStroke);

        float size = ts, cx = x0 + w / 2f, carW = size * 0.95f, carH = h * 0.5f, carTop = y0 + h * 0.12f;
        pStroke.setColor(lightTheme ? 0xFF646C7E : 0xFF8E9099);
        pStroke.setStrokeWidth(2.4f);
        rf.set(cx - carW / 2f, carTop, cx + carW / 2f, carTop + carH);
        c.drawRoundRect(rf, carW * 0.42f, carW * 0.42f, pStroke);
        float[] psi = {s.tpmsFlPsi, s.tpmsFrPsi, s.tpmsRlPsi, s.tpmsRrPsi};
        float gap = size * 0.3f, row1 = carTop + carH * 0.42f, row2 = carTop + carH * 0.95f;
        for (int i = 0; i < 4; i++) {
            String t = String.format(Locale.US, "%.0f", psi[i]);
            boolean warn = psi[i] > 0f && psi[i] < 31f;
            float tw = measure(t, size, true);
            float x = (i % 2 == 0) ? cx - carW / 2f - gap - tw : cx + carW / 2f + gap;
            text(c, t, x, i < 2 ? row1 : row2, size, true, themeColor(warn ? 0xFFF5A623 : 0xFFF5F5F7, lightTheme));
        }
        if (forceParkView) {
            String lbl = "psi · P고정";
            float pw = measure(lbl, size * 0.36f, true);
            text(c, lbl, cx - pw / 2f, y0 + h - size * 0.3f, size * 0.36f, true,
                    lightTheme ? 0xFF2563EB : 0xFF6E95FF);
        } else {
            float pw = measure("psi", size * 0.36f, false);
            text(c, "psi", cx - pw / 2f, y0 + h - size * 0.3f, size * 0.36f, false, themeColor(0xFF5A5D64, lightTheme));
        }
    }

    // ---- Cards: EV Battery, ICE, Media, Climate ----

    private void drawBattery(Canvas c, HudState s) {
        double soc = s.socPct;
        if (s.remainKwh > 0.0) field(c, "remain", String.format(Locale.US, "· 잔여 %.1f kWh", s.remainKwh), 0);
        if (soc >= 0.0) field(c, "soc", String.format(Locale.US, "%.1f", soc), soc < 20.0 ? 0xFFF5A623 : 0);
        if (s.dteKm > 0 && s.dteKm < 2000) field(c, "dte", String.valueOf(s.dteKm), 0);
        if (s.avgEconKmKwh > 0.0) field(c, "eff", String.format(Locale.US, "%.1f km/kWh", s.avgEconKmKwh), 0);
        float[] pr = curL.rect("pill");
        if (pr != null && soc >= 0.0) {
            barFill(c, pr, pr[0], pr[0] + pr[2] * (float) Math.min(1.0, soc / 100.0),
                    themeColor(soc < 20.0 ? 0xFFF5A623 : 0xFFF5F5F7, lightTheme));
        }
        if (s.bmsSocPct >= 0.0) field(c, "bms", String.format(Locale.US, "%.1f", s.bmsSocPct), 0);
        if (s.sohPct >= 0.0) field(c, "soh", String.format(Locale.US, "%.1f", s.sohPct), 0);
        if (s.hasBattTemp) field(c, "batT", String.format(Locale.US, "%.1f", s.battTempC), 0);
        if (s.cellMaxV > 0.0) field(c, "cellMax", String.format(Locale.US, "%.2f", s.cellMaxV), 0);
        if (s.cellMinV > 0.0) field(c, "cellMin", String.format(Locale.US, "%.2f", s.cellMinV), 0);
        if (s.cellDiffMv >= 0) field(c, "cellDiff", String.valueOf(s.cellDiffMv), 0);

        double kwVal = smoothKw;
        boolean regen = kwVal < -0.05;
        String kw = (kwVal > 0.05 ? "+" : (regen ? "−" : "")) + String.format(Locale.US, "%.1f", Math.abs(kwVal));
        field(c, "kw", kw, regen ? 0xFF3DD68C : 0);
        field(c, "pwrLbl", regen ? "회생 제동" : (kwVal > 0.05 ? "출력" : "대기"), 0);
        float[] wr = curL.rect("pwr");
        if (wr != null) {
            float zero = wr[0] + wr[2] * 0.28f;
            if (kwVal >= 0.0) {
                barFill(c, wr, zero, zero + (wr[2] * 0.72f) * (float) Math.min(1.0, kwVal / 150.0), themeColor(0xFFF5F5F7, lightTheme));
            } else {
                float x0 = zero - (wr[2] * 0.28f) * (float) Math.min(1.0, -kwVal / 60.0);
                barFill(c, wr, x0, zero, themeColor(0xFF3DD68C, lightTheme));
            }
            roundRect(c, zero - 2f, wr[1] - 6f, zero + 2f, wr[1] + wr[3] + 6f, 2f, themeColor(0xFFC9CBD1, lightTheme));
        }
    }

    private void drawIceDuration(Canvas c, String key, long sec) {
        if (sec < 0L) {
            field(c, key, "--", 0xFF5A5D64, false);
            return;
        }
        long min = sec / 60L;
        if (min >= 60L) field(c, key, String.format(Locale.US, "%d:%02d", min / 60L, min % 60L), 0, false);
        else field(c, key, String.valueOf(min), 0, true);
    }

    private void drawIceFuel(Canvas c, HudState s) {
        if (s.tripKm >= 0.0) field(c, "tripKm", String.format(Locale.US, "· 트립 %.1f km", s.tripKm), 0);
        int fp = s.fuelPct;
        if (fp >= 0) {
            int clamped = Math.max(0, Math.min(100, fp));
            int col = clamped <= 8 ? 0xFFE31937 : (clamped <= 15 ? 0xFFF5A623 : 0);
            field(c, "fuelPct", String.valueOf(clamped), col, true);
        } else {
            field(c, "fuelPct", "--", 0xFF5A5D64, false);
        }
        if (s.iceDteKm > 0 && s.iceDteKm < 2000) field(c, "iceDte", String.valueOf(s.iceDteKm), 0, true);
        else field(c, "iceDte", "--", 0xFF5A5D64, true);
        field(c, "avgKmL", s.avgFuelKmL > 0.0 ? String.format(Locale.US, "%.1f km/L", s.avgFuelKmL) : "-- km/L", 0);

        float[] fpr = curL.rect("fuelPill");
        if (fpr != null) {
            if (fp >= 0) {
                int clamped = Math.max(0, Math.min(100, fp));
                int barCol = themeColor(clamped <= 8 ? 0xFFE31937 : (clamped <= 15 ? 0xFFF5A623 : 0xFFF5F5F7), lightTheme);
                barFill(c, fpr, fpr[0], fpr[0] + fpr[2] * (clamped / 100f), barCol);
            }
            pFill.setShader(null);
            pFill.setColor(lightTheme ? 0x80FFFFFF : 0x66000000);
            for (float f : new float[]{0.25f, 0.50f, 0.75f}) {
                float tx = fpr[0] + fpr[2] * f;
                c.drawRect(tx - 1.5f, fpr[1] + 3f, tx + 1.5f, fpr[1] + fpr[3] - 3f, pFill);
            }
        }
        if (s.instFuelKmL > 0.0) field(c, "instKmL", String.format(Locale.US, "%.1f", s.instFuelKmL), 0, true);
        else field(c, "instKmL", "--", 0xFF5A5D64, true);
        if (s.coolantC > Integer.MIN_VALUE) {
            int cc = s.coolantC < 60 ? 0xFF7EA6FF : (s.coolantC >= 105 ? 0xFFE31937 : 0);
            field(c, "coolant", String.valueOf(s.coolantC), cc, true);
        } else {
            field(c, "coolant", "--", 0xFF5A5D64, false);
        }
        if (s.volt12 > 0.0) fieldWithUnit(c, "volt12", String.format(Locale.US, "%.1f", s.volt12), s.volt12 < 12.0 ? 0xFFF5A623 : 0, null);
        else if (s.auxSocPct >= 0) fieldWithUnit(c, "volt12", String.valueOf(s.auxSocPct), 0, "%");
        else field(c, "volt12", "--", 0xFF5A5D64, false);

        drawIceDuration(c, "runTime", s.runSec);
        drawIceDuration(c, "idleTime", s.idleSec);
        if (s.odoKm > 0.0) field(c, "odoKm", String.format(Locale.US, "%,d", Math.round(s.odoKm)), 0, true);
        else field(c, "odoKm", "--", 0xFF5A5D64, false);

        int rpm = s.rpm >= 0 ? Math.max(0, Math.round(smoothRpm)) : -1;
        int spd = Math.max(0, Math.round(smoothSpeed));
        if (rpm >= 0) {
            field(c, "rpmLbl", (spd == 0 && rpm > 0) ? "공회전" : "엔진 회전", 0);
            field(c, "rpm", String.format(Locale.US, "%,d", rpm), 0, true);
        } else {
            field(c, "rpmLbl", "엔진 회전", 0);
            field(c, "rpm", "--", 0xFF5A5D64, true);
        }
        float[] rr = curL.rect("rpm");
        if (rr != null) {
            if (rpm > 0) barFill(c, rr, rr[0], rr[0] + rr[2] * Math.min(1f, rpm / 7000f), themeColor(0xFFF5F5F7, lightTheme));
            pFill.setShader(null);
            pFill.setColor(lightTheme ? 0x8CFFFFFF : 0x73000000);
            for (int k = 1; k <= 6; k++) {
                float tx = rr[0] + rr[2] * (k / 7f);
                c.drawRect(tx - 1.5f, rr[1] + 2f, tx + 1.5f, rr[1] + rr[3] - 2f, pFill);
            }
        }
    }

    private void drawMedia(Canvas c, HudState s) {
        float[] ar = curL.rect("art");
        if (ar != null && s.albumArt != null) {
            int sc = c.save();
            path.reset();
            rf.set(ar[0], ar[1], ar[0] + ar[2], ar[1] + ar[3]);
            float rad = ar.length > 4 ? ar[4] : 16f;
            path.addRoundRect(rf, rad, rad, Path.Direction.CW);
            c.clipPath(path);
            drawImage(c, s.albumArt, ar[0], ar[1], ar[2], ar[3]);
            c.restoreToCount(sc);
        }
        boolean real = s.mediaTitle != null && !s.mediaTitle.isEmpty() && !"미디어 재생 대기 중".equals(s.mediaTitle);
        field(c, "src", s.mediaSource, 0);
        field(c, "title", real ? s.mediaTitle : "미디어 재생 대기 중", 0);
        String sub = s.mediaArtist != null ? s.mediaArtist : "";
        if (s.mediaAlbum != null && !s.mediaAlbum.isEmpty() && !s.mediaAlbum.equals(s.mediaArtist)) {
            sub = sub.isEmpty() ? s.mediaAlbum : (sub + " · " + s.mediaAlbum);
        }
        field(c, "artist", real ? sub : "", 0);
        int pos = real ? Math.max(0, s.mediaPosSec) : 0, dur = real ? Math.max(0, s.mediaDurSec) : 0;
        float[] pr = curL.rect("prog");
        if (pr != null && dur > 0) barFill(c, pr, pr[0], pr[0] + pr[2] * Math.min(1f, pos / (float) dur), themeColor(0xFFF5F5F7, lightTheme));
        field(c, "pos", (pos / 60) + ":" + String.format(Locale.US, "%02d", pos % 60), 0);
        field(c, "dur", dur > 0 ? ("-" + ((dur - pos) / 60) + ":" + String.format(Locale.US, "%02d", Math.max(0, dur - pos) % 60)) : "--:--", 0);
    }

    private void drawClimate(Canvas c, HudState s) {
        boolean on = s.hvacOn;
        int tone = !on ? 0xFF5A5D64 : (s.hvacHeat ? 0xFFFF9A5C : (s.hvacAc ? 0xFF7EA6FF : 0xFFF5F5F7));
        field(c, "dr", on ? String.format(Locale.US, "%.1f°", s.hvacDrTempC) : "OFF", tone);
        if (on && s.hvacAuto) copyActive(c, curL.rect("auto"));
        int bm = s.hvacBlowerMode;
        if (on && (bm == 1 || bm == 2)) copyActive(c, curL.rect("airFace"));
        if (on && (bm == 2 || bm == 3 || bm == 4)) copyActive(c, curL.rect("airFoot"));
        int step = on ? Math.max(0, Math.min(7, s.hvacBlowerStep)) : 0;
        for (int i = 0; i < step; i++) copyActive(c, curL.rect("fan" + i));
        field(c, "fanNum", on ? String.valueOf(step) : "-", 0);
        field(c, "inT", String.format(Locale.US, "%.1f°", s.inCarTempC), 0);
        field(c, "outT", String.format(Locale.US, "%.1f°", s.outdoorTempC), 0);
        if (on && s.hvacAc) copyActive(c, curL.rect("chip_ac"));
        if (on && s.hvacHeat) copyActive(c, curL.rect("chip_heat"));
        if (on && (s.hvacFrontDefog || bm == 5)) copyActive(c, curL.rect("chip_fdef"));
        if (s.hvacRearDefog) copyActive(c, curL.rect("chip_rdef"));
        if (s.hvacRecirc) copyActive(c, curL.rect("chip_recirc"));
    }

    // ---- Drawing & Text Primitives ----

    private void copyActive(Canvas c, float[] r) {
        if (curActive == null || r == null) return;
        int x0 = Math.round(r[0]) - 1, y0 = Math.round(r[1]) - 1;
        int x1 = Math.round(r[0] + r[2]) + 1, y1 = Math.round(r[1] + r[3]) + 1;
        rs.set(x0, y0, x1, y1);
        rd.set(x0, y0, x1, y1);
        c.drawBitmap(curActive, rs, rd, null);
    }

    private void roundRect(Canvas c, float x0, float y0, float x1, float y1, float rad, int color) {
        pFill.setShader(null);
        pFill.setColor(color);
        rf.set(x0, y0, x1, y1);
        c.drawRoundRect(rf, rad, rad, pFill);
    }

    private void strokeLine(Canvas c, float x0, float y0, float x1, float y1, float w, int color) {
        pIcon.setColor(color);
        pIcon.setStrokeWidth(w);
        path.reset();
        path.moveTo(x0, y0);
        path.lineTo(x1, y1);
        c.drawPath(path, pIcon);
    }

    private void softGlow(Canvas c, float x, float y, float r, int rgb, float a, boolean additive) {
        if (r <= 0.5f) return;
        int a0 = Math.max(0, Math.min(255, Math.round(a * 255)));
        int a1 = Math.max(0, Math.min(255, Math.round(a * 0.45f * 255)));
        Paint paint = additive ? pAdd : pShade;
        paint.setShader(new RadialGradient(x, y, r,
                new int[]{(a0 << 24) | rgb, (a1 << 24) | rgb, rgb & 0x00FFFFFF},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(x - r, y - r, x + r, y + r, paint);
        paint.setShader(null);
    }

    private void glow(Canvas c, float x, float y, float r, int rgb, float a) {
        softGlow(c, x, y, r, rgb, a, true);
    }

    private void shadow(Canvas c, float cx, float cy, float rx, float squash, float alpha) {
        if (rx <= 0.5f) return;
        RadialGradient baseGrad = lightTheme ? shadowUnitLight : shadowUnitDark;
        if (baseGrad == null) {
            baseGrad = new RadialGradient(0f, 0f, 1f, new int[]{0xFF000000, 0x00000000},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP);
            if (lightTheme) shadowUnitLight = baseGrad;
            else shadowUnitDark = baseGrad;
        }
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        int sc = c.save();
        c.translate(cx, cy);
        c.scale(rx, rx * squash);
        pShade.setShader(baseGrad);
        pShade.setAlpha(a);
        c.drawRect(-1f, -1f, 1f, 1f, pShade);
        pShade.setAlpha(255);
        pShade.setShader(null);
        c.restoreToCount(sc);
    }

    private void drawImage(Canvas c, Bitmap bmp, float x, float y, float w, float h) {
        if (bmp == null) return;
        rf.set(x, y, x + w, y + h);
        c.drawBitmap(bmp, null, rf, pBmp);
    }

    private static boolean hasHangulOrCjk(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if ((ch >= 0xAC00 && ch <= 0xD7AF) || (ch >= 0x3130 && ch <= 0x318F)
                    || (ch >= 0x1100 && ch <= 0x11FF) || (ch >= 0x4E00 && ch <= 0x9FFF)) {
                return true;
            }
        }
        return false;
    }

    private Paint textPaint(String t, boolean bold) {
        return hasHangulOrCjk(t) ? (bold ? pSysBold : pSysMed) : (bold ? pPubBold : pPubMed);
    }

    private float measure(String t, float size, boolean bold) {
        if (t == null || t.isEmpty()) return 0f;
        Paint p = textPaint(t, bold);
        p.setTextSize(size);
        return p.measureText(t);
    }

    private float text(Canvas c, String t, float x, float y, float size, boolean bold, int color) {
        if (t == null || t.isEmpty()) return 0f;
        Paint p = textPaint(t, bold);
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(color);
        c.drawText(t, x, y, p);
        return p.measureText(t);
    }

    private String fit(String t, float size, boolean bold, float maxW) {
        if (t == null || maxW <= 0f || measure(t, size, bold) <= maxW) return t;
        String s = t;
        while (s.length() > 1 && measure(s + "…", size, bold) > maxW) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private void field(Canvas c, String key, String value, int color) {
        fieldWithUnit(c, key, value, color, null);
    }

    private void field(Canvas c, String key, String value, int color, boolean showUnit) {
        fieldWithUnit(c, key, value, color, showUnit ? null : "");
    }

    private void fieldWithUnit(Canvas c, String key, String value, int color, String unitOverride) {
        Field f = curL.fields.get(key);
        if (f == null || value == null || value.isEmpty()) return;
        String v = fit(value, f.size, f.bold, f.maxW);
        float wv = measure(v, f.size, f.bold);
        String unitStr = unitOverride != null ? unitOverride : f.unit;
        boolean hasUnit = unitStr != null && !unitStr.isEmpty();
        float wu = hasUnit ? measure(unitStr, f.unitSize, f.unitBold) : 0f;
        float total = wv + (hasUnit ? f.gap + wu : 0f);
        float x = (f.align == 'R') ? f.x - total : (f.align == 'C' ? f.x - total / 2f : f.x);
        int fc = color != 0 ? themeColor(color, lightTheme) : themeColor(f.color, lightTheme);
        text(c, v, x, f.y, f.size, f.bold, fc);
        if (hasUnit) text(c, unitStr, x + wv + f.gap, f.y, f.unitSize, f.unitBold, themeColor(f.unitColor, lightTheme));
    }

    private void barFill(Canvas c, float[] r, float x0, float x1, int color) {
        if (r == null || x1 - x0 < 0.5f) return;
        roundRect(c, x0, r[1], x1, r[1] + r[3], r[3] / 2f, color);
    }

    // ---- Navdy Sprites ----

    private boolean navReady() {
        return navSets != null && navSets.length > 0 && navEgo != null;
    }

    private boolean navBitmaps(NavSprite sp) {
        if (!sp.tried) {
            sp.tried = true;
            sp.bmp = loadAssetBitmap("design_tesla/" + sp.asset + ".png");
            sp.lamp = loadAssetBitmap("design_tesla/" + sp.asset + "_lamp.png");
        }
        return sp.bmp != null;
    }

    private float drawNav(Canvas c, NavSprite sp, float gx, float gy, float scale, Paint paint, int lamp, float alpha) {
        if (!navBitmaps(sp)) return 0f;
        float x = gx - sp.ax * scale, y = gy - sp.ay * scale, w = sp.w * scale, h = sp.h * scale;
        shadow(c, x + w / 2f, y + h - h * 0.04f, w * 0.56f, 0.18f, (lightTheme ? 0.36f : 0.55f) * alpha);
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        paint.setAlpha(a);
        rf.set(x, y, x + w, y + h);
        c.drawBitmap(sp.bmp, null, rf, paint);
        if (lamp > 0 && sp.lamp != null) {
            Paint lp = lightTheme ? pLampLight : pLamp;
            if (lamp >= 2) {
                float g = 0.06f;
                lp.setAlpha(Math.round(a * 0.55f));
                rf.set(x - w * g, y - h * g, x + w * (1f + g), y + h * (1f + g));
                c.drawBitmap(sp.lamp, null, rf, lp);
                lp.setAlpha(a);
                rf.set(x, y, x + w, y + h);
                c.drawBitmap(sp.lamp, null, rf, lp);
                c.drawBitmap(sp.lamp, null, rf, lp);
            } else {
                lp.setAlpha(Math.round(a * (lightTheme ? 0.72f : 0.6f)));
                c.drawBitmap(sp.lamp, null, rf, lp);
            }
        }
        return sp.ay * scale;
    }

    private static int navIndex(float x, float y, float curve, float pivotX, float chaseSlope) {
        float r = (chaseSlope == 0f) ? Math.max(0f, x - pivotX) : (x - pivotX);
        float bend = chaseSlope * r + curve * r * r * 0.5f;
        double roadYaw = Math.atan(chaseSlope + curve * r);
        double deg = Math.toDegrees(Math.atan2(y + bend, x) - roadYaw);
        return Math.max(0, Math.min(24, (int) Math.round(deg / 2.0) + 12));
    }

    private static int navSetFor(NavSprite[][] sets, float elevDeg) {
        int best = 0;
        for (int i = 1; i < sets.length; i++) {
            if (Math.abs(sets[i][0].pitch - elevDeg) < Math.abs(sets[best][0].pitch - elevDeg)) best = i;
        }
        return best;
    }

    private static boolean isStationaryTarget(int egoKph, float targetSpeedMps) {
        return egoKph >= 20 && !Float.isNaN(targetSpeedMps) && Math.abs(targetSpeedMps) < 1.5f;
    }

    // ---- Light Theme Bitmap LUTs (identical to TurzxFrameRenderer.TeslaHud v1.5.15) ----

    private static int[] buildRgbLut(int[][] anchors) {
        int[] lut = new int[256];
        for (int val = 0; val < 256; val++) {
            if (val <= anchors[0][0]) {
                lut[val] = (anchors[0][1] << 16) | (anchors[0][2] << 8) | anchors[0][3];
                continue;
            }
            int rgb = (anchors[anchors.length - 1][1] << 16) | (anchors[anchors.length - 1][2] << 8) | anchors[anchors.length - 1][3];
            for (int i = 0; i < anchors.length - 1; i++) {
                int[] a0 = anchors[i], a1 = anchors[i + 1];
                if (val <= a1[0]) {
                    float t = (val - a0[0]) / (float) (a1[0] - a0[0]);
                    rgb = (Math.round(a0[1] + (a1[1] - a0[1]) * t) << 16)
                            | (Math.round(a0[2] + (a1[2] - a0[2]) * t) << 8)
                            | Math.round(a0[3] + (a1[3] - a0[3]) * t);
                    break;
                }
            }
            lut[val] = rgb;
        }
        return lut;
    }

    private static final int[] LUT_CARD = buildRgbLut(new int[][]{
            {0, 226, 230, 238}, {12, 240, 243, 248}, {24, 255, 255, 255}, {33, 241, 244, 249},
            {43, 220, 225, 235}, {54, 186, 193, 208}, {92, 112, 120, 138}, {144, 76, 83, 99},
            {245, 17, 19, 24}, {255, 17, 19, 24}
    });
    private static final int[] LUT_BAR = buildRgbLut(new int[][]{
            {0, 226, 230, 238}, {10, 235, 238, 244}, {19, 244, 246, 250}, {31, 205, 211, 223},
            {43, 194, 201, 214}, {54, 174, 182, 198}, {77, 138, 147, 166}, {92, 105, 112, 130},
            {144, 72, 79, 95}, {245, 17, 19, 24}, {255, 17, 19, 24}
    });
    private static final int[] LUT_VIZ_FG = buildRgbLut(new int[][]{
            {37, 226, 232, 240}, {54, 196, 203, 216}, {92, 112, 120, 138},
            {144, 76, 83, 99}, {245, 17, 19, 24}, {255, 17, 19, 24}
    });

    private static float rrSd(float px, float py, float x0, float y0, float w, float h, float rad) {
        float cx = x0 + w * 0.5f, cy = y0 + h * 0.5f, hx = w * 0.5f - rad, hy = h * 0.5f - rad;
        float dx = Math.max(Math.abs(px - cx) - hx, 0f), dy = Math.max(Math.abs(py - cy) - hy, 0f);
        return (float) Math.hypot(dx, dy) + Math.min(Math.max(Math.abs(px - cx) - hx, Math.abs(py - cy) - hy), 0f) - rad;
    }

    private static int blendRgb(int rgb0, int rgb1, float t) {
        if (t <= 0f) return rgb0;
        if (t >= 1f) return rgb1;
        int r0 = (rgb0 >> 16) & 0xFF, g0 = (rgb0 >> 8) & 0xFF, b0 = rgb0 & 0xFF;
        int r1 = (rgb1 >> 16) & 0xFF, g1 = (rgb1 >> 8) & 0xFF, b1 = rgb1 & 0xFF;
        return (Math.round(r0 + (r1 - r0) * t) << 16) | (Math.round(g0 + (g1 - g0) * t) << 8) | Math.round(b0 + (b1 - b0) * t);
    }

    private static boolean inBox1(int x, int y, float[] r) {
        return r != null && x >= r[0] - 1f && x <= r[0] + r[2] + 1f && y >= r[1] - 1f && y <= r[1] + r[3] + 1f;
    }

    private static Bitmap deriveLightBitmap(Bitmap src, boolean isActive, Layout lay) {
        if (src == null || lay == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);

        float[] vz = lay.rect("viz");
        float vx = vz != null ? vz[0] : 16f, vy = vz != null ? vz[1] : 16f;
        float vw = vz != null ? vz[2] : 864f, vh = vz != null ? vz[3] : 576f;
        float vrad = (vz != null && vz.length > 4) ? vz[4] : 28f;
        int vix0 = (int) Math.floor(vx), viy0 = (int) Math.floor(vy);
        int vix1 = (int) Math.ceil(vx + vw), viy1 = (int) Math.ceil(vy + vh);
        float[] art = lay.rect("art");
        float artRad = (art != null && art.length > 4) ? art[4] : 16f;
        int barY = Math.round(vy + vh) + 6;
        float[] heatR = lay.rect("chip_heat");
        float[][] blueChips = new float[][]{
                lay.rect("auto"), lay.rect("chip_ac"), lay.rect("chip_fdef"), lay.rect("chip_rdef"), lay.rect("chip_recirc")
        };
        float[][] blueIcons = new float[][]{
                lay.rect("airFace"), lay.rect("airFoot"), lay.rect("fan0"), lay.rect("fan1"), lay.rect("fan2"),
                lay.rect("fan3"), lay.rect("fan4"), lay.rect("fan5"), lay.rect("fan6")
        };
        final int CANVAS_RGB = 0xE2E6EE, BLUE_BG = 0xDBE7FF, BLUE_BDR = 0x3B76F6, BLUE_FG = 0x1D54D6, BLUE_TXT = 0x14306E;
        final int HEAT_BG = 0xFFE7D4, HEAT_BDR = 0xE8722A, HEAT_FG = 0xC44408;

        for (int y = 0; y < h; y++) {
            boolean inBar = y >= barY;
            float vt = Math.max(0f, Math.min(1f, (y - vy) / Math.max(1f, vh)));
            int vizBg = (vt <= 0.45f) ? blendRgb(0xD8DFE9, 0xE6EBF2, vt / 0.45f) : blendRgb(0xE6EBF2, 0xF1F4F8, (vt - 0.45f) / 0.55f);
            int rowOff = y * w;
            for (int x = 0; x < w; x++) {
                int argb = px[rowOff + x], alphaMask = argb & 0xFF000000;
                int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                int lum = (r * 77 + g * 150 + b * 29) >> 8;
                int sat = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));

                if (isActive && inBar) {
                    if (inBox1(x, y, heatR)) {
                        int rgb = (r <= 8 && g <= 8 && b <= 8) ? CANVAS_RGB
                                : (r < 40 && b < 18 ? blendRgb(CANVAS_RGB, HEAT_BG, r / 38f)
                                : (lum <= 98 && r >= b ? blendRgb(HEAT_BG, HEAT_BDR, (r - 38) / 86f) : blendRgb(HEAT_BG, HEAT_FG, (lum - 85) / 92f)));
                        px[rowOff + x] = alphaMask | rgb;
                        continue;
                    }
                    boolean hitBlueChip = false;
                    for (float[] bc : blueChips) if (inBox1(x, y, bc)) { hitBlueChip = true; break; }
                    if (hitBlueChip) {
                        int rgb = (r <= 3 && g <= 5 && b <= 10) ? CANVAS_RGB
                                : (r <= 12 && g <= 21 && b <= 44 ? blendRgb(CANVAS_RGB, BLUE_BG, b / 41f)
                                : (sat <= 20 ? blendRgb(BLUE_BG, BLUE_TXT, (lum - 24) / 221f)
                                : (r <= 34 && g <= 56 && b <= 120 ? blendRgb(BLUE_BG, BLUE_BDR, (b - 41) / 73f) : blendRgb(BLUE_BG, BLUE_FG, (lum - 20) / 133f))));
                        px[rowOff + x] = alphaMask | rgb;
                        continue;
                    }
                    boolean hitBlueIcon = false;
                    for (float[] bi : blueIcons) if (inBox1(x, y, bi)) { hitBlueIcon = true; break; }
                    if (hitBlueIcon) {
                        px[rowOff + x] = alphaMask | blendRgb(CANVAS_RGB, BLUE_FG, b / 255f);
                        continue;
                    }
                }
                if (!isActive && inBox1(x, y, art)) {
                    float sd = rrSd(x + 0.5f, y + 0.5f, art[0], art[1], art[2], art[3], artRad);
                    px[rowOff + x] = alphaMask | (sd >= 0.8f ? 0xFFFFFF : (sd <= -0.8f ? (argb & 0x00FFFFFF) : blendRgb(0xFFFFFF, argb & 0x00FFFFFF, 0.5f - sd / 1.6f)));
                    continue;
                }
                if (x >= vix0 && x <= vix1 && y >= viy0 && y <= viy1) {
                    float sd = rrSd(x + 0.5f, y + 0.5f, vx, vy, vw, vh, vrad);
                    int rgb = (sd >= 0.9f) ? CANVAS_RGB : (sd >= -0.9f ? blendRgb(CANVAS_RGB, vizBg, (0.9f - sd) / 1.8f)
                            : (lum <= 40 ? vizBg : (lum < 56 ? blendRgb(vizBg, LUT_VIZ_FG[lum], (lum - 38) / 18f) : LUT_VIZ_FG[lum])));
                    px[rowOff + x] = alphaMask | rgb;
                    continue;
                }
                px[rowOff + x] = alphaMask | (inBar ? LUT_BAR[lum] : LUT_CARD[lum]);
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private static int themeColor(int darkArgb, boolean light) {
        if (!light || darkArgb == 0) return darkArgb;
        switch (darkArgb) {
            case 0xFFF5F5F7: return 0xFF111318;
            case 0xFF8E9099: return 0xFF4C5363;
            case 0xFF5A5D64: return 0xFF70788A;
            case 0xFF6E95FF: return 0xFF1D54D6;
            case 0xFF7EA6FF: return 0xFF2563EB;
            case 0xFF3DD68C: return 0xFF16A34A;
            case 0xFFFF9A5C: return 0xFFD9530A;
            case 0xFFF5A623: return 0xFFD97706;
            case 0xFFE31937:
            case 0xFFFF4D63: return 0xFFDC2626;
            case 0xFFC9CBD1: return 0xFF334155;
            default: return darkArgb;
        }
    }

    // ---- Asset & Parser Helpers ----

    private Typeface loadTypeface(String name, Typeface fallback) {
        try {
            return Typeface.createFromAsset(assets, name);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static Paint makeTextPaint(Typeface tf, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(tf);
        p.setFakeBoldText(bold && tf == Typeface.DEFAULT);
        return p;
    }

    private static Paint makeTintPaint(int r, int g, int b, float gain) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(new float[]{
                r / 255f * gain, 0, 0, 0, 0,
                0, g / 255f * gain, 0, 0, 0,
                0, 0, b / 255f * gain, 0, 0,
                0, 0, 0, 1, 0
        }));
        return p;
    }

    private static Paint makeLampPaint(int color, boolean addMode) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        if (addMode) p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        return p;
    }

    private Bitmap loadAssetBitmap(String pathName) {
        try (InputStream is = assets.open(pathName)) {
            return BitmapFactory.decodeStream(is);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String readAssetText(String pathName) {
        try (InputStream is = assets.open(pathName);
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Layout parseLayout(String text) {
        if (text == null) return null;
        Layout l = new Layout();
        for (String line : text.split("\n")) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\t", -1);
            if (p.length >= 5 && p[0].startsWith("R:")) {
                float[] v = new float[p.length - 1];
                for (int i = 0; i < v.length; i++) v[i] = Float.parseFloat(p[i + 1]);
                l.rects.put(p[0].substring(2), v);
            } else if (p.length >= 2 && p[0].startsWith("S:")) {
                l.sizes.put(p[0].substring(2), Float.parseFloat(p[1]));
            } else if (p.length >= 13 && p[0].startsWith("F:")) {
                Field f = new Field();
                f.x = Float.parseFloat(p[1]);
                f.y = Float.parseFloat(p[2]);
                f.size = Float.parseFloat(p[3]);
                f.bold = "1".equals(p[4]);
                f.color = (int) Long.parseLong(p[5], 16);
                f.align = p[6].charAt(0);
                f.maxW = Float.parseFloat(p[7]);
                f.unit = p[8];
                f.unitSize = Float.parseFloat(p[9]);
                f.unitBold = "1".equals(p[10]);
                f.unitColor = (int) Long.parseLong(p[11], 16);
                f.gap = Float.parseFloat(p[12]);
                l.fields.put(p[0].substring(2), f);
            }
        }
        return l;
    }

    private void parseNavMeta(String text) {
        if (text == null) return;
        TreeMap<Float, NavSprite[]> bySet = new TreeMap<>();
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] f = t.split("\\s+");
            if (f.length < 8) continue;
            try {
                float pitch = Float.parseFloat(f[1]);
                int idx = Integer.parseInt(f[2]);
                float w = Float.parseFloat(f[3]), h = Float.parseFloat(f[4]);
                float ax = Float.parseFloat(f[5]), ay = Float.parseFloat(f[6]), ppm = Float.parseFloat(f[7]);
                if (w <= 0f || h <= 0f || ppm <= 0f) continue;
                if ("navdy_ego".equals(f[0])) { navEgo = new NavSprite("navdy_ego", pitch, w, h, ax, ay, ppm); continue; }
                if ("navdy_top".equals(f[0])) { navTop = new NavSprite("navdy_top_car", pitch, w, h, ax, ay, ppm); continue; }
                if (idx < 0 || idx > 24) continue;
                NavSprite[] arr = bySet.get(pitch);
                if (arr == null) { arr = new NavSprite[25]; bySet.put(pitch, arr); }
                arr[idx] = new NavSprite(String.format(Locale.US, "%s_car_%02d", f[0], idx), pitch, w, h, ax, ay, ppm);
            } catch (NumberFormatException ignored) {}
        }
        navSets = bySet.values().toArray(new NavSprite[0][]);
    }
}
