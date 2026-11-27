package com.hkg.huddisplay;

import android.content.Context;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

/**
 * Vsync-driven coordinator + change-throttled dashboard renderer for the 1920x720 Battery/Media/Climate cards.
 * Runs 3D pipeline on exact 2-vsync cadence (29.5 FPS / 33.9 ms) with separated GL->HWUI passes to eliminate judder.
 */
public final class HudView extends View implements Choreographer.FrameCallback {

    private final HudRenderer renderer;
    private final GestureDetector gestureDetector;
    private final Car3dState car3dState = new Car3dState();
    private Runnable onOpenSettings;

    public interface ManualStateProvider {
        HudState buildSnapshot(double tSec);
    }

    private TelemetryClient telemetryClient;
    private ManualStateProvider manualProvider;
    private boolean manualMode = false;
    private HudVizBgView vizBgView;
    private Car3dView car3dView;
    private HudOverlayView overlayView;

    private boolean demoMode = false;
    private boolean demoParked = false;
    private int themeMode = HudState.THEME_AUTO;
    private int powertrainMode = HudState.PT_AUTO;
    private boolean standbyOnScreenOff = true;
    private boolean showStatusPill = true;
    private boolean car3dEnabled = true;
    private int carColorId = 0;

    private HudState latestSnap = new HudState();
    private final long startMs = SystemClock.uptimeMillis();
    private long lastDashDrawMs = 0L;
    private int lastDashHash = 0;
    private boolean vsyncSkipOdd = false;

    private volatile boolean pendingVizBgDraw = false;
    private volatile HudState pendingOverlaySnap;
    private volatile boolean pendingOverlayActive3d = false;
    private volatile boolean pendingOverlayParked = false;

    private final Runnable on3dFrameReady = new Runnable() {
        @Override
        public void run() {
            HudState snap = pendingOverlaySnap;
            if (!attached || snap == null) return;
            if (pendingVizBgDraw && vizBgView != null) {
                pendingVizBgDraw = false;
                vizBgView.updateFrame(snap, pendingOverlayActive3d);
            }
            if (overlayView != null) {
                overlayView.updateFrame(snap, demoMode, showStatusPill, pendingOverlayActive3d, pendingOverlayParked);
            }
        }
    };

    private boolean prevInit = false;
    private boolean prevActive = false;
    private boolean prevParked = false;
    private boolean prevLight = false;
    private boolean prevIce = false;
    private boolean prevActive3d = false;
    private volatile boolean attached = false;

    public HudView(Context context) {
        super(context);
        this.renderer = new HudRenderer(context.getAssets());
        this.gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (onOpenSettings == null || getWidth() <= 0 || getHeight() <= 0) return false;
                if (renderer.isLastStandby()) {
                    onOpenSettings.run();
                    return true;
                }
                float nx = e.getX() / getWidth();
                float ny = e.getY() / getHeight();
                if (nx >= 1392f / 1920f && nx <= 1904f / 1920f
                        && ny >= 16f / 720f && ny <= 592f / 720f) {
                    onOpenSettings.run();
                    return true;
                }
                return false;
            }
        });
    }

    public HudRenderer getRenderer() {
        return renderer;
    }

    public void setVizBgView(HudVizBgView view) {
        this.vizBgView = view;
    }

    public void setCar3dView(Car3dView view) {
        this.car3dView = view;
        if (view != null) {
            view.setOnFrameRendered(on3dFrameReady);
        }
    }

    public void setOverlayView(HudOverlayView view) {
        this.overlayView = view;
    }

    public void setTelemetryClient(TelemetryClient client) {
        this.telemetryClient = client;
    }

    public void setManualStateProvider(ManualStateProvider provider) {
        this.manualProvider = provider;
    }

    public void setManualMode(boolean enabled) {
        this.manualMode = enabled;
        this.prevInit = false;
        this.lastDashDrawMs = 0L;
        this.lastDashHash = 0;
        invalidate();
    }

    public void forceDashboardRefresh() {
        this.lastDashDrawMs = 0L;
        this.lastDashHash = 0;
        invalidate();
    }

    public void setOnOpenSettings(Runnable listener) {
        this.onOpenSettings = listener;
    }

    public void configure(boolean demo, boolean demoParked, int themeMode, int powertrainMode,
                          boolean standbyOnScreenOff, boolean showStatusPill,
                          boolean car3dEnabled, int carColorId) {
        this.demoMode = demo;
        this.demoParked = demoParked;
        this.themeMode = themeMode;
        this.powertrainMode = powertrainMode;
        this.standbyOnScreenOff = standbyOnScreenOff;
        this.showStatusPill = showStatusPill;
        this.car3dEnabled = car3dEnabled;
        this.carColorId = carColorId;
        this.prevInit = false;
        this.vsyncSkipOdd = false;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        Choreographer.getInstance().removeFrameCallback(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!attached) return;
        Choreographer.getInstance().postFrameCallback(this);

        boolean use3dReady = car3dEnabled && car3dView != null && car3dView.isSurfaceReady();
        if (use3dReady) {
            vsyncSkipOdd = !vsyncSkipOdd;
            if (vsyncSkipOdd) return;
        }
        stepFrame(frameTimeNanos);
    }

    private void stepFrame(long frameTimeNanos) {
        long nowMs = SystemClock.uptimeMillis();
        HudState snap;
        if (manualMode && manualProvider != null) {
            snap = manualProvider.buildSnapshot((nowMs - startMs) / 1000.0);
        } else if (demoMode) {
            boolean ice = powertrainMode == HudState.PT_ICE;
            snap = HudState.buildDemoSnapshot((nowMs - startMs) / 1000.0, ice, demoParked);
        } else if (telemetryClient != null) {
            snap = telemetryClient.snapshot();
        } else {
            snap = new HudState();
        }
        latestSnap = snap;

        boolean use3d = car3dEnabled && (car3dView != null);
        boolean active = renderer.stepSimulation(
                frameTimeNanos,
                snap,
                themeMode,
                powertrainMode,
                standbyOnScreenOff && !demoMode && !manualMode,
                use3d,
                carColorId,
                demoMode,
                car3dState
        );

        boolean light = renderer.isLightThemeActive();
        boolean ice = renderer.isIceModeActive();
        boolean parked = car3dState.parked;
        boolean active3d = use3d && active;

        boolean immediateMode = !prevInit || active != prevActive || light != prevLight || ice != prevIce;
        boolean modeChanged = immediateMode || parked != prevParked || active3d != prevActive3d;

        // 1. Slow dashboard cards: immediate on mode/theme/standby change, otherwise change-driven >= 400ms
        int dh = renderer.computeDashboardHash(snap);
        if (immediateMode || (active && dh != lastDashHash && (nowMs - lastDashDrawMs >= 400L))) {
            lastDashHash = dh;
            lastDashDrawMs = nowMs;
            invalidate();
        }

        // 2. Visibility updates
        if (vizBgView != null) {
            int wantViz = active ? View.VISIBLE : View.INVISIBLE;
            if (vizBgView.getVisibility() != wantViz) vizBgView.setVisibility(wantViz);
        }
        if (overlayView != null) {
            int wantOv = active ? View.VISIBLE : View.INVISIBLE;
            if (overlayView.getVisibility() != wantOv) overlayView.setVisibility(wantOv);
        }
        if (car3dView != null) {
            int wantCar = active3d ? View.VISIBLE : View.INVISIBLE;
            if (car3dView.getVisibility() != wantCar) car3dView.setVisibility(wantCar);
            car3dView.setRenderPaused(!active3d);
        }

        // 3. Synchronized GL -> HWUI handoff when 3D surface is active; direct draw otherwise
        boolean parkTrans = renderer.isParkTransitioning();
        if (active3d && car3dView.isSurfaceReady()) {
            pendingOverlaySnap = snap;
            pendingOverlayActive3d = true;
            pendingOverlayParked = parked;
            if (!parked || modeChanged || parkTrans) {
                pendingVizBgDraw = true;
            }
            car3dView.pushState(car3dState);
        } else {
            if (car3dView != null) car3dView.pushState(car3dState);
            if (vizBgView != null && active && (!active3d || !parked || modeChanged || parkTrans)) {
                vizBgView.updateFrame(snap, active3d);
            }
            if (overlayView != null && active) {
                overlayView.updateFrame(snap, demoMode, showStatusPill, active3d, parked);
            }
        }

        prevInit = true;
        prevActive = active;
        prevParked = parked;
        prevLight = light;
        prevIce = ice;
        prevActive3d = active3d;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        renderer.renderDashboardOnly(canvas, getWidth(), getHeight(), latestSnap);
    }
}
