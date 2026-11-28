package com.hkg.huddisplay;

/**
 * Immutable-by-swap snapshot pushed from HudRenderer to Car3dView's GL thread.
 */
public final class Car3dState {
    public float speedKph;
    public float steerDeg;
    public float curveC2;
    public float chaseYawDeg;
    public boolean parked;
    public boolean reverse;
    public boolean lightTheme;
    public float headAnim;
    public float brakeAnim;
    public float turnLeftAnim;
    public float turnRightAnim;
    public float blindLeftAnim;
    public float blindRightAnim;
    public float blindLeftDanger;
    public float blindRightDanger;
    public final float[] doorEased = new float[6];
    public float doorBlink;
    public boolean pdwActive;
    public final float[] pdwLevel = new float[6]; // FL, FC, FR, RL, RC, RR (0..3)
    public float pdwPulse;
    public float rccaLeftAnim;
    public float rccaRightAnim;
    public int bodyColorId;
    public boolean demoMode;
    public boolean visible = true;
    public long frameTimeNanos;
    public final float[] vpMat = new float[16];
    public float eyeX, eyeY, eyeZ;
    public float parkBlend;
    public boolean hasSharedCamera;

    public void copyFrom(Car3dState s) {
        if (s == null) return;
        speedKph = s.speedKph;
        steerDeg = s.steerDeg;
        curveC2 = s.curveC2;
        chaseYawDeg = s.chaseYawDeg;
        parked = s.parked;
        reverse = s.reverse;
        lightTheme = s.lightTheme;
        headAnim = s.headAnim;
        brakeAnim = s.brakeAnim;
        turnLeftAnim = s.turnLeftAnim;
        turnRightAnim = s.turnRightAnim;
        blindLeftAnim = s.blindLeftAnim;
        blindRightAnim = s.blindRightAnim;
        blindLeftDanger = s.blindLeftDanger;
        blindRightDanger = s.blindRightDanger;
        System.arraycopy(s.doorEased, 0, doorEased, 0, 6);
        doorBlink = s.doorBlink;
        pdwActive = s.pdwActive;
        System.arraycopy(s.pdwLevel, 0, pdwLevel, 0, 6);
        pdwPulse = s.pdwPulse;
        rccaLeftAnim = s.rccaLeftAnim;
        rccaRightAnim = s.rccaRightAnim;
        bodyColorId = s.bodyColorId;
        demoMode = s.demoMode;
        visible = s.visible;
        frameTimeNanos = s.frameTimeNanos;
        hasSharedCamera = s.hasSharedCamera;
        if (s.hasSharedCamera) {
            System.arraycopy(s.vpMat, 0, vpMat, 0, 16);
            eyeX = s.eyeX;
            eyeY = s.eyeY;
            eyeZ = s.eyeZ;
            parkBlend = s.parkBlend;
        }
    }
}
