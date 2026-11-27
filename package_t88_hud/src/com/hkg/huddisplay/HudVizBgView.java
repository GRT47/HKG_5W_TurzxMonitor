package com.hkg.huddisplay;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

/**
 * Renders the 864x576 viz background layer (road, lane lines, AP ribbon, lead/traffic cars, or ambient glow)
 * independently from the slower 1920x720 right/bottom dashboard cards.
 */
public final class HudVizBgView extends View {

    private final HudRenderer renderer;
    private HudState latestSnap;
    private boolean car3dActive = false;

    public HudVizBgView(Context context, HudRenderer renderer) {
        super(context);
        this.renderer = renderer;
        setWillNotDraw(false);
    }

    public void updateFrame(HudState snap, boolean car3dActive) {
        this.latestSnap = snap;
        this.car3dActive = car3dActive;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (latestSnap == null) return;
        renderer.renderVizBg(canvas, getWidth(), getHeight(), latestSnap, car3dActive);
    }
}
