package com.hkg.huddisplay;

import android.content.Context;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * Top transparent overlay rendering gauges, TBT, TPMS, telltales, and 3D-tracked door badges
 * inside the 864x576 viz card above Car3dView, plus measuring real rendered HWUI FPS.
 */
public final class HudOverlayView extends View {

    private static final String TAG = "HudDisplay";

    private final HudRenderer renderer;
    private final GestureDetector gestureDetector;
    private final ScaleGestureDetector scaleDetector;
    private final float[] markersUv = new float[18];

    private Car3dView car3dView;
    private Runnable onOpenSettings;
    private HudState latestSnap;
    private boolean demoMode = false;
    private boolean showStatusPill = true;
    private boolean car3dActive = false;
    private boolean parkedNow = false;

    private long fpsWindowStartMs = SystemClock.uptimeMillis();
    private int fpsFrames = 0;
    private double measuredFps = 0.0;

    private boolean orbiting = false;
    private float lastTouchX = 0f;
    private float lastTouchY = 0f;

    public HudOverlayView(Context context, HudRenderer renderer) {
        super(context);
        this.renderer = renderer;
        setWillNotDraw(false);
        this.gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (!orbiting && getWidth() > 0 && getHeight() > 0) {
                    float nx = e.getX() / getWidth();
                    float ny = e.getY() / getHeight();
                    if (renderer.isInTpmsRect(nx, ny)) {
                        renderer.toggleForceParkView();
                        return true;
                    }
                    if (!parkedNow && car3dActive && renderer.isDriveOrbitActive()
                            && nx >= 0.42f && nx <= 0.59f && ny >= 0.05f && ny <= 0.16f) {
                        renderer.resetDriveOrbit();
                        return true;
                    }
                }
                return false;
            }
        });
        this.scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (car3dActive) {
                    renderer.multiplyOrbitZoom(parkedNow, detector.getScaleFactor());
                }
                return true;
            }
        });
    }

    public void setCar3dView(Car3dView view) {
        this.car3dView = view;
    }

    public void setOnOpenSettings(Runnable listener) {
        this.onOpenSettings = listener;
    }

    public void updateFrame(HudState snap, boolean demoMode, boolean showStatusPill,
                            boolean car3dActive, boolean parked) {
        this.latestSnap = snap;
        this.demoMode = demoMode;
        this.showStatusPill = showStatusPill;
        this.car3dActive = car3dActive;
        this.parkedNow = parked;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        scaleDetector.onTouchEvent(ev);
        int action = ev.getActionMasked();
        if (ev.getPointerCount() >= 2 || scaleDetector.isInProgress()) {
            orbiting = false;
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                renderer.setOrbitTouchActive(false);
            } else if (action == MotionEvent.ACTION_POINTER_UP && ev.getPointerCount() == 2) {
                int keepIdx = ev.getActionIndex() == 0 ? 1 : 0;
                lastTouchX = ev.getX(keepIdx);
                lastTouchY = ev.getY(keepIdx);
            }
            gestureDetector.onTouchEvent(ev);
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            orbiting = false;
            if (car3dActive) {
                renderer.setOrbitTouchActive(true);
                lastTouchX = ev.getX();
                lastTouchY = ev.getY();
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (car3dActive) {
                float dx = ev.getX() - lastTouchX;
                float dy = ev.getY() - lastTouchY;
                if (orbiting || Math.abs(dx) + Math.abs(dy) > 6f) {
                    orbiting = true;
                    renderer.addOrbitDelta(parkedNow, -dx * 0.42f, dy * 0.26f);
                    lastTouchX = ev.getX();
                    lastTouchY = ev.getY();
                }
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            orbiting = false;
            renderer.setOrbitTouchActive(false);
        }
        gestureDetector.onTouchEvent(ev);
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (latestSnap == null) return;
        long nowMs = SystemClock.uptimeMillis();
        fpsFrames++;
        long dtWindow = nowMs - fpsWindowStartMs;
        if (dtWindow >= 2000L) {
            measuredFps = (fpsFrames * 1000.0) / dtWindow;
            fpsFrames = 0;
            fpsWindowStartMs = nowMs;
            Log.i(TAG, String.format(java.util.Locale.US, "FPS=%.1f demo=%s car3d=%s parked=%s size=%dx%d",
                    measuredFps, demoMode, car3dActive, parkedNow, getWidth(), getHeight()));
        }

        float[] markers = null;
        if (car3dActive && car3dView != null) {
            car3dView.copyDoorMarkersUv(markersUv);
            markers = markersUv;
        }
        renderer.renderOverlays(canvas, getWidth(), getHeight(), 16f, 16f, 864f, 576f,
                latestSnap, measuredFps, demoMode, showStatusPill, car3dActive, markers);
    }
}
