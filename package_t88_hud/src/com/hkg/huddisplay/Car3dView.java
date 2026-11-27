package com.hkg.huddisplay;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.util.Log;
import android.view.TextureView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Transparent OpenGL ES 3.0 TextureView rendering the 2019 Kia e-Niro 3D model
 * with spinning/steerable wheels, real-time lights, smooth drive<->park camera,
 * touch 360-degree orbit, and projected 3D anchor coordinates for door badges.
 */
public final class Car3dView extends TextureView implements TextureView.SurfaceTextureListener {

    private static final String TAG = "HudCar3d";

    private static final int EGL_OPENGL_ES3_BIT_KHR = 0x0040;
    private static final int PART_BODY = 0;
    private static final int PART_WHEEL_FL = 1;
    private static final int PART_WHEEL_FR = 2;
    private static final int PART_WHEEL_RL = 3;
    private static final int PART_WHEEL_RR = 4;

    private static final int MAT_BODY = 0;
    private static final int MAT_GLASS = 4;
    private static final int MAT_HEADLAMP = 8;
    private static final float WHEEL_RADIUS_M = 0.340f;
    private static final float PARK_TRANS_SEC = 1.05f;

    private static float wrapDeg180(float deg) {
        return ((deg + 180f) % 360f + 360f) % 360f - 180f;
    }

    // Projected anchor points (in car meter coordinates) for door/trunk/hood badges
    private static final float[][] MARKER_ANCHORS = {
            {-0.96f, 0.82f, -0.32f}, // 0: FL door
            { 0.96f, 0.82f, -0.32f}, // 1: FR door
            {-0.96f, 0.84f,  0.62f}, // 2: RL door
            { 0.96f, 0.84f,  0.62f}, // 3: RR door
            { 0.00f, 0.96f,  2.22f}, // 4: Trunk
            { 0.00f, 0.90f, -2.12f}, // 5: Hood / Frunk
    };

    private static final float[][] BODY_COLORS_DARK = {
            {0.76f, 0.80f, 0.86f}, // 0: Silver
            {0.31f, 0.34f, 0.39f}, // 1: Graphite
            {0.91f, 0.93f, 0.95f}, // 2: Pearl White
            {0.15f, 0.34f, 0.64f}, // 3: Gravity Blue
            {0.11f, 0.12f, 0.15f}, // 4: Aurora Black
    };

    private static final float[][] BODY_COLORS_LIGHT = {
            {0.52f, 0.57f, 0.65f}, // 0: Silver (high contrast on bright card)
            {0.27f, 0.30f, 0.35f}, // 1: Graphite
            {0.85f, 0.88f, 0.92f}, // 2: Pearl White
            {0.13f, 0.31f, 0.60f}, // 3: Gravity Blue
            {0.10f, 0.11f, 0.14f}, // 4: Aurora Black
    };

    private static final String CAR_VERT_SRC =
            "#version 300 es\n"
                    + "layout(location = 0) in vec3 aPos;\n"
                    + "layout(location = 1) in vec3 aNrm;\n"
                    + "uniform mat4 uModel;\n"
                    + "uniform mat4 uViewProj;\n"
                    + "uniform float uPosScale;\n"
                    + "out vec3 vWorldPos;\n"
                    + "out vec3 vWorldNrm;\n"
                    + "out vec3 vLocalPos;\n"
                    + "void main() {\n"
                    + "    vLocalPos = aPos * uPosScale;\n"
                    + "    vec4 wp = uModel * vec4(vLocalPos, 1.0);\n"
                    + "    vWorldPos = wp.xyz;\n"
                    + "    vWorldNrm = mat3(uModel) * aNrm;\n"
                    + "    gl_Position = uViewProj * wp;\n"
                    + "}\n";

    private static final String CAR_FRAG_SRC =
            "#version 300 es\n"
                    + "precision mediump float;\n"
                    + "in vec3 vWorldPos;\n"
                    + "in vec3 vWorldNrm;\n"
                    + "in vec3 vLocalPos;\n"
                    + "uniform int uMatId;\n"
                    + "uniform vec3 uCamPos;\n"
                    + "uniform vec3 uBodyRgb;\n"
                    + "uniform float uHeadLamp;\n"
                    + "uniform float uBrakeLamp;\n"
                    + "uniform float uReverse;\n"
                    + "uniform float uLightTheme;\n"
                    + "out vec4 fragColor;\n"
                    + "void main() {\n"
                    + "    vec3 N = normalize(vWorldNrm);\n"
                    + "    if (!gl_FrontFacing) N = -N;\n"
                    + "    vec3 V = normalize(uCamPos - vWorldPos);\n"
                    + "    float NdotV = clamp(abs(dot(N, V)), 0.0, 1.0);\n"
                    + "\n"
                    + "    vec3 baseCol = vec3(0.5);\n"
                    + "    float metal = 0.0;\n"
                    + "    float shininess = 32.0;\n"
                    + "    float specStrength = 0.4;\n"
                    + "    float envStrength = 0.25;\n"
                    + "    float alpha = 1.0;\n"
                    + "    vec3 emissive = vec3(0.0);\n"
                    + "\n"
                    + "    if (uMatId == 0) {\n" // BODY
                    + "        baseCol = uBodyRgb;\n"
                    + "        metal = 0.42;\n"
                    + "        shininess = 96.0;\n"
                    + "        specStrength = 0.72;\n"
                    + "        envStrength = 0.78;\n"
                    + "    } else if (uMatId == 1) {\n" // TRIM_BLACK
                    + "        baseCol = vec3(0.075, 0.082, 0.095);\n"
                    + "        shininess = 28.0;\n"
                    + "        specStrength = 0.25;\n"
                    + "        envStrength = 0.14;\n"
                    + "    } else if (uMatId == 2) {\n" // CHROME
                    + "        baseCol = vec3(0.82, 0.85, 0.90);\n"
                    + "        metal = 0.90;\n"
                    + "        shininess = 136.0;\n"
                    + "        specStrength = 0.95;\n"
                    + "        envStrength = 0.92;\n"
                    + "    } else if (uMatId == 3) {\n" // BLUE_ACCENT
                    + "        baseCol = vec3(0.10, 0.48, 0.96);\n"
                    + "        metal = 0.50;\n"
                    + "        shininess = 80.0;\n"
                    + "        specStrength = 0.65;\n"
                    + "        envStrength = 0.45;\n"
                    + "    } else if (uMatId == 4) {\n" // GLASS (opaque dark privacy glass)
                    + "        baseCol = vec3(0.032, 0.048, 0.075);\n"
                    + "        metal = 0.25;\n"
                    + "        shininess = 140.0;\n"
                    + "        specStrength = 1.0;\n"
                    + "        envStrength = 1.30;\n"
                    + "    } else if (uMatId == 5) {\n" // UNDERCARRIER / WHEELHOUSE LINER (deep matte)
                    + "        baseCol = vec3(0.024, 0.027, 0.032);\n"
                    + "        shininess = 10.0;\n"
                    + "        specStrength = 0.06;\n"
                    + "        envStrength = 0.02;\n"
                    + "    } else if (uMatId == 6) {\n" // TIRE
                    + "        baseCol = vec3(0.052, 0.056, 0.062);\n"
                    + "        shininess = 12.0;\n"
                    + "        specStrength = 0.14;\n"
                    + "        envStrength = 0.05;\n"
                    + "    } else if (uMatId == 7) {\n" // RIM
                    + "        baseCol = vec3(0.58, 0.62, 0.68);\n"
                    + "        metal = 0.82;\n"
                    + "        shininess = 110.0;\n"
                    + "        specStrength = 0.85;\n"
                    + "        envStrength = 0.72;\n"
                    + "    } else if (uMatId == 8) {\n" // HEADLAMP CLEAR OUTER POLYCARBONATE LENS (Pass 3)
                    + "        baseCol = vec3(0.76, 0.84, 0.94);\n"
                    + "        metal = 0.05;\n"
                    + "        shininess = 180.0;\n"
                    + "        specStrength = 1.25;\n"
                    + "        envStrength = 1.35;\n"
                    + "        float fresGlass = mix(0.06, 0.50, pow(1.0 - NdotV, 2.8));\n"
                    + "        alpha = clamp(fresGlass + 0.05 * uHeadLamp, 0.08, 0.58);\n"
                    + "        emissive = vec3(0.24, 0.34, 0.50) * uHeadLamp * (0.12 + 0.22 * (1.0 - NdotV));\n"
                    + "    } else if (uMatId == 9) {\n" // DRL
                    + "        baseCol = vec3(0.76, 0.84, 0.94);\n"
                    + "        shininess = 120.0;\n"
                    + "        specStrength = 0.85;\n"
                    + "        envStrength = 0.6;\n"
                    + "        emissive = vec3(0.75, 0.90, 1.15) * max(0.45, uHeadLamp);\n"
                    + "    } else if (uMatId == 10) {\n" // TAILLAMP: bumper reflector+reverse / main lamp / spoiler HMSL
                    + "        if (vLocalPos.y < 0.72) {\n"
                    + "            float revZone = smoothstep(0.658, 0.674, abs(vLocalPos.x));\n"
                    + "            baseCol = mix(vec3(0.24, 0.02, 0.03), vec3(0.68, 0.72, 0.78), revZone);\n"
                    + "            shininess = mix(120.0, 155.0, revZone);\n"
                    + "            specStrength = mix(0.85, 1.05, revZone);\n"
                    + "            envStrength = mix(0.50, 0.88, revZone);\n"
                    + "            emissive = vec3(2.80, 3.00, 3.30) * uReverse * revZone;\n"
                    + "        } else if (vLocalPos.y > 1.25) {\n"
                    + "            baseCol = vec3(0.16, 0.02, 0.03);\n"
                    + "            shininess = 140.0;\n"
                    + "            specStrength = 0.90;\n"
                    + "            envStrength = 0.55;\n"
                    + "            emissive = vec3(2.80, 0.40, 0.22) * uBrakeLamp;\n"
                    + "        } else {\n"
                    + "            baseCol = vec3(0.20, 0.018, 0.028);\n"
                    + "            shininess = 140.0;\n"
                    + "            specStrength = 0.95;\n"
                    + "            envStrength = 0.65;\n"
                    + "            vec3 tailCol = vec3(0.24, 0.015, 0.028) * uHeadLamp;\n"
                    + "            float core = smoothstep(0.32, 0.78, abs(vLocalPos.x)) * (1.0 - smoothstep(0.04, 0.10, abs(vLocalPos.y - 1.05)));\n"
                    + "            vec3 brkCol = mix(vec3(2.20, 0.14, 0.10), vec3(3.40, 0.75, 0.40), core * 0.65) * uBrakeLamp;\n"
                    + "            emissive = max(tailCol, brkCol);\n"
                    + "        }\n"
                    + "    } else if (uMatId == 11) {\n" // MAT_REVERSE
                    + "        baseCol = vec3(0.72, 0.75, 0.80);\n"
                    + "        shininess = 120.0;\n"
                    + "        specStrength = 0.85;\n"
                    + "        emissive = vec3(2.60, 2.80, 3.10) * uReverse;\n"
                    + "    } else {\n" // MAT_PROJECTOR (12: dual bi-LED convex lenses & fog)
                    + "        baseCol = vec3(0.11, 0.15, 0.24);\n"
                    + "        metal = 0.28;\n"
                    + "        shininess = 160.0;\n"
                    + "        specStrength = 1.05;\n"
                    + "        envStrength = 1.05;\n"
                    + "        emissive = vec3(1.15, 1.30, 1.55) * uHeadLamp;\n"
                    + "    }\n"
                    + "\n"
                    + "    vec3 skyCol = mix(vec3(0.66, 0.72, 0.84), vec3(0.92, 0.95, 1.00), uLightTheme);\n"
                    + "    vec3 gndCol = mix(vec3(0.14, 0.15, 0.19), vec3(0.34, 0.37, 0.44), uLightTheme);\n"
                    + "    vec3 hemi = mix(gndCol, skyCol, clamp(N.y * 0.5 + 0.5, 0.0, 1.0));\n"
                    + "\n"
                    + "    vec3 L1 = normalize(vec3(-0.45, 0.82, -0.58));\n"
                    + "    vec3 L2 = normalize(vec3( 0.62, 0.42,  0.66));\n"
                    + "    float diff1 = max(dot(N, L1), 0.0);\n"
                    + "    float diff2 = max(dot(N, L2), 0.0) * 0.45;\n"
                    + "    vec3 diffuse = baseCol * (hemi * 0.58 + vec3(0.92) * diff1 * 0.58 + vec3(0.70, 0.80, 0.96) * diff2);\n"
                    + "\n"
                    + "    vec3 dNdx = dFdx(N);\n"
                    + "    vec3 dNdy = dFdy(N);\n"
                    + "    float nVar = clamp(dot(dNdx, dNdx) + dot(dNdy, dNdy), 0.0, 1.0);\n"
                    + "    float aaShininess = shininess / (1.0 + nVar * shininess * 0.45);\n"
                    + "    float aaSpec = specStrength / (1.0 + nVar * 2.5);\n"
                    + "    float aaEnv = envStrength / (1.0 + nVar * 1.5);\n"
                    + "\n"
                    + "    vec3 H1 = normalize(L1 + V);\n"
                    + "    vec3 H2 = normalize(L2 + V);\n"
                    + "    float spec1 = pow(max(dot(N, H1), 0.0), aaShininess) * aaSpec;\n"
                    + "    float spec2 = pow(max(dot(N, H2), 0.0), aaShininess * 0.6) * aaSpec * 0.42;\n"
                    + "    vec3 specTint = mix(vec3(1.0), baseCol, metal * 0.55);\n"
                    + "    vec3 spec = specTint * (spec1 + spec2);\n"
                    + "\n"
                    + "    vec3 R = reflect(-V, N);\n"
                    + "    float fres = mix(0.06, 1.0, pow(1.0 - NdotV, 3.5));\n"
                    + "    float horizonBand = smoothstep(-0.08, 0.16, R.y) * (1.0 - smoothstep(0.16, 0.52, R.y));\n"
                    + "    float roofStrip = smoothstep(0.55, 0.88, R.y) * smoothstep(0.35, 0.0, abs(R.x));\n"
                    + "    vec3 envTop = mix(vec3(0.30, 0.36, 0.46), vec3(0.68, 0.74, 0.84), uLightTheme);\n"
                    + "    vec3 envCol = envTop * (0.30 + 0.65 * horizonBand + 0.55 * roofStrip);\n"
                    + "    vec3 refl = mix(envCol, envCol * baseCol, metal * 0.6) * fres * aaEnv;\n"
                    + "\n"
                    + "    if (uMatId == 8) {\n"
                    + "        vec3 gCol = diffuse * alpha + (spec + refl) * (0.55 + 0.45 * alpha) + emissive;\n"
                    + "        gCol = gCol / (gCol + vec3(0.62));\n"
                    + "        gCol = clamp(gCol * 1.42, 0.0, 1.0);\n"
                    + "        fragColor = vec4(gCol, alpha);\n"
                    + "    } else {\n"
                    + "        vec3 col = diffuse * (1.0 - metal * 0.35) + spec + refl + emissive;\n"
                    + "        col = col / (col + vec3(0.62));\n" // subtle soft filmic highlight rolloff
                    + "        col = clamp(col * 1.42, 0.0, 1.0);\n"
                    + "        fragColor = vec4(col, 1.0);\n"
                    + "    }\n"
                    + "}\n";

    private static final String FLOOR_VERT_SRC =
            "#version 300 es\n"
                    + "layout(location = 0) in vec3 aPos;\n"
                    + "uniform mat4 uViewProj;\n"
                    + "out vec2 vXZ;\n"
                    + "void main() {\n"
                    + "    vXZ = aPos.xz;\n"
                    + "    gl_Position = uViewProj * vec4(aPos, 1.0);\n"
                    + "}\n";

    private static final String FLOOR_FRAG_SRC =
            "#version 300 es\n"
                    + "precision mediump float;\n"
                    + "in vec2 vXZ;\n"
                    + "uniform float uHeadLamp;\n"
                    + "uniform float uBrakeLamp;\n"
                    + "uniform float uReverse;\n"
                    + "uniform float uParkBlend;\n"
                    + "uniform float uLightTheme;\n"
                    + "uniform vec4 uDoorsSide;\n"
                    + "uniform vec4 uDoorsEnd;\n"
                    + "out vec4 fragColor;\n"
                    + "float doorPuddle(vec2 p, vec2 c, vec2 r, float open) {\n"
                    + "    if (open <= 0.01) return 0.0;\n"
                    + "    vec2 d = (p - c) / r;\n"
                    + "    return exp(-dot(d, d) * 2.2) * open;\n"
                    + "}\n"
                    + "float coneBeam(vec2 p, vec2 src, float reach) {\n"
                    + "    vec2 d = p - src;\n"
                    + "    float fwd = -d.y;\n" // -Z is forward
                    + "    if (fwd <= 0.0 || fwd > reach) return 0.0;\n"
                    + "    float spread = abs(d.x - src.x * 0.04 * fwd) / (0.18 + fwd * 0.44);\n"
                    + "    float angFade = 1.0 - smoothstep(0.65, 1.0, spread);\n"
                    + "    float distFade = (1.0 - smoothstep(0.3, reach, fwd)) * smoothstep(0.0, 0.25, fwd);\n"
                    + "    return angFade * distFade;\n"
                    + "}\n"
                    + "float revConeBeam(vec2 p, vec2 src, float reach) {\n"
                    + "    vec2 d = p - src;\n"
                    + "    float bwd = d.y;\n" // +Z is rearward
                    + "    if (bwd <= 0.0 || bwd > reach) return 0.0;\n"
                    + "    float spread = abs(d.x - src.x * 0.10 * bwd) / (0.16 + bwd * 0.48);\n"
                    + "    float angFade = 1.0 - smoothstep(0.62, 1.0, spread);\n"
                    + "    float distFade = (1.0 - smoothstep(0.2, reach, bwd)) * smoothstep(0.0, 0.22, bwd);\n"
                    + "    return angFade * distFade;\n"
                    + "}\n"
                    + "void main() {\n"
                    + "    // Quad spans X in [-2.6, 2.6], Z in [-6.8, 5.4]; smoothly fade near boundary\n"
                    + "    float edgeX = 1.0 - smoothstep(2.0, 2.55, abs(vXZ.x));\n"
                    + "    float edgeZ = smoothstep(-6.75, -5.6, vXZ.y) * (1.0 - smoothstep(4.2, 5.35, vXZ.y));\n"
                    + "    float edge = edgeX * edgeZ;\n"
                    + "    if (edge <= 0.001) discard;\n"
                    + "\n"
                    + "    // Soft chassis contact shadow + 4 tire contact darkening\n"
                    + "    vec2 bodyD = vec2(max(abs(vXZ.x) - 0.68, 0.0) / 0.56, max(abs(vXZ.y - 0.04) - 1.72, 0.0) / 0.68);\n"
                    + "    float bodySh = exp(-dot(bodyD, bodyD) * 2.2);\n"
                    + "    vec2 wheelD1 = vec2(abs(abs(vXZ.x) - 0.79) / 0.24, (vXZ.y + 1.30) / 0.34);\n"
                    + "    vec2 wheelD2 = vec2(abs(abs(vXZ.x) - 0.79) / 0.24, (vXZ.y - 1.40) / 0.34);\n"
                    + "    float wSh = max(exp(-dot(wheelD1, wheelD1) * 2.0), exp(-dot(wheelD2, wheelD2) * 2.0));\n"
                    + "    float shAlpha = clamp(bodySh * 0.54 + wSh * 0.38, 0.0, 0.78) * mix(1.0, 0.45, uLightTheme);\n"
                    + "    vec3 shRgb = mix(vec3(0.0, 0.0, 0.0), vec3(0.10, 0.14, 0.22), uLightTheme);\n"
                    + "\n"
                    + "    // Parked ground headlight beams & rear brake/tail floor glow\n"
                    + "    float beam = (coneBeam(vXZ, vec2(-0.66, -2.02), 4.2) + coneBeam(vXZ, vec2(0.66, -2.02), 4.2)) * uHeadLamp * uParkBlend;\n"
                    + "    vec3 beamRgb = mix(vec3(0.80, 0.90, 1.00), vec3(0.24, 0.50, 0.95), uLightTheme);\n"
                    + "    float beamA = clamp(beam * mix(0.32, 0.18, uLightTheme), 0.0, 0.45);\n"
                    + "\n"
                    + "    vec2 tailD = vec2((abs(vXZ.x) - 0.66) / 0.54, (vXZ.y - 2.22) / 0.78);\n"
                    + "    float tailShape = exp(-dot(tailD, tailD) * 1.8) * smoothstep(1.82, 2.22, vXZ.y);\n"
                    + "    float tailOnlyA = tailShape * uHeadLamp * uParkBlend * mix(0.14, 0.08, uLightTheme);\n"
                    + "\n"
                    + "    vec2 brkD = vec2(vXZ.x / 1.15, (vXZ.y - 2.45) / 0.95);\n"
                    + "    float brkShape = max(tailShape * 1.15, exp(-dot(brkD, brkD) * 1.55) * smoothstep(1.80, 2.28, vXZ.y));\n"
                    + "    float brkFloorA = brkShape * uBrakeLamp * mix(0.66, 0.42, uLightTheme);\n"
                    + "\n"
                    + "    vec3 tailRgb = mix(vec3(0.85, 0.08, 0.14), vec3(0.75, 0.10, 0.16), uLightTheme);\n"
                    + "    vec3 brkRgb = mix(vec3(1.00, 0.12, 0.10), vec3(0.92, 0.08, 0.12), uLightTheme);\n"
                    + "    float tailA = clamp(max(tailOnlyA, brkFloorA), 0.0, 0.68);\n"
                    + "    vec3 rearGlowCol = mix(tailRgb, brkRgb, clamp(uBrakeLamp * 1.5, 0.0, 1.0));\n"
                    + "\n"
                    + "    float revBeam = (revConeBeam(vXZ, vec2(-0.71, 2.04), 2.8) + revConeBeam(vXZ, vec2(0.71, 2.04), 2.8)) * uReverse;\n"
                    + "    vec3 revBeamRgb = mix(vec3(0.88, 0.94, 1.00), vec3(0.42, 0.68, 1.00), uLightTheme);\n"
                    + "    float revBeamA = clamp(revBeam * mix(0.50, 0.28, uLightTheme), 0.0, 0.56);\n"
                    + "\n"
                    + "    float doorG = 0.0;\n"
                    + "    doorG = max(doorG, doorPuddle(vXZ, vec2(-1.18, -0.32), vec2(0.48, 0.56), uDoorsSide.x));\n"
                    + "    doorG = max(doorG, doorPuddle(vXZ, vec2( 1.18, -0.32), vec2(0.48, 0.56), uDoorsSide.y));\n"
                    + "    doorG = max(doorG, doorPuddle(vXZ, vec2(-1.18,  0.66), vec2(0.46, 0.54), uDoorsSide.z));\n"
                    + "    doorG = max(doorG, doorPuddle(vXZ, vec2( 1.18,  0.66), vec2(0.46, 0.54), uDoorsSide.w));\n"
                    + "    doorG = max(doorG, doorPuddle(vXZ, vec2( 0.00,  2.38), vec2(0.85, 0.48), uDoorsEnd.x));\n"
                    + "    doorG = max(doorG, doorPuddle(vXZ, vec2( 0.00, -2.32), vec2(0.82, 0.46), uDoorsEnd.y));\n"
                    + "    float blinkMul = 0.20 + 0.80 * uDoorsEnd.z;\n"
                    + "    vec3 doorRgb = mix(vec3(1.00, 0.42, 0.10), vec3(1.00, 0.16, 0.22), uDoorsEnd.w);\n"
                    + "    float doorA = clamp(doorG * blinkMul * mix(0.62, 0.42, uLightTheme), 0.0, 0.65);\n"
                    + "\n"
                    + "    float outA = clamp(shAlpha + beamA + tailA + revBeamA + doorA, 0.0, 0.92) * edge;\n"
                    + "    vec3 premul = (shRgb * shAlpha + beamRgb * beamA + rearGlowCol * tailA + revBeamRgb * revBeamA + doorRgb * doorA) * edge;\n"
                    + "    fragColor = vec4(premul, outA);\n"
                    + "}\n";

    private static final String BLOOM_VERT_SRC =
            "#version 300 es\n"
                    + "layout(location = 0) in vec3 aPos;\n"
                    + "layout(location = 1) in vec2 aUv;\n"
                    + "uniform mat4 uModel;\n"
                    + "uniform mat4 uViewProj;\n"
                    + "out vec2 vUv;\n"
                    + "out vec3 vWorldPos;\n"
                    + "out vec3 vRearNrm;\n"
                    + "void main() {\n"
                    + "    vUv = aUv;\n"
                    + "    vec4 wp = uModel * vec4(aPos, 1.0);\n"
                    + "    vWorldPos = wp.xyz;\n"
                    + "    vRearNrm = normalize(mat3(uModel) * vec3(0.0, 0.08, 1.0));\n"
                    + "    gl_Position = uViewProj * wp;\n"
                    + "}\n";

    private static final String BLOOM_FRAG_SRC =
            "#version 300 es\n"
                    + "precision mediump float;\n"
                    + "in vec2 vUv;\n"
                    + "in vec3 vWorldPos;\n"
                    + "in vec3 vRearNrm;\n"
                    + "uniform vec3 uCamPos;\n"
                    + "uniform float uBrakeLamp;\n"
                    + "uniform float uLightTheme;\n"
                    + "uniform float uReverseMode;\n"
                    + "out vec4 fragColor;\n"
                    + "void main() {\n"
                    + "    float r2 = dot(vUv, vUv);\n"
                    + "    if (r2 >= 1.0) discard;\n"
                    + "    vec3 V = normalize(uCamPos - vWorldPos);\n"
                    + "    float rearVis = smoothstep(-0.10, 0.35, dot(normalize(vRearNrm), V));\n"
                    + "    float outer = exp(-r2 * 3.4) * (1.0 - smoothstep(0.55, 1.0, r2));\n"
                    + "    float core = exp(-r2 * 13.0);\n"
                    + "    float str = (outer * mix(0.62, 0.44, uLightTheme) + core * mix(0.50, 0.32, uLightTheme)) * uBrakeLamp * rearVis;\n"
                    + "    if (str <= 0.002) discard;\n"
                    + "    vec3 brkOuter = mix(vec3(1.00, 0.14, 0.10), vec3(0.92, 0.08, 0.12), uLightTheme);\n"
                    + "    vec3 brkCore  = mix(vec3(1.00, 0.58, 0.36), vec3(1.00, 0.28, 0.20), uLightTheme);\n"
                    + "    vec3 revOuter = mix(vec3(0.72, 0.86, 1.00), vec3(0.45, 0.72, 1.00), uLightTheme);\n"
                    + "    vec3 revCore  = mix(vec3(1.00, 1.00, 1.00), vec3(0.96, 0.99, 1.00), uLightTheme);\n"
                    + "    vec3 outerCol = mix(brkOuter, revOuter, uReverseMode);\n"
                    + "    vec3 coreCol  = mix(brkCore,  revCore,  uReverseMode);\n"
                    + "    vec3 col = mix(outerCol, coreCol, clamp(core * 1.25, 0.0, 1.0));\n"
                    + "    fragColor = vec4(col * str, clamp(str, 0.0, 0.85));\n"
                    + "}\n";

    private static final class Batch {
        int partId;
        int matId;
        int vboByteOffset;
        int indexByteOffset;
        int indexCount;
    }

    private final AssetManager assets;
    private final Object stateLock = new Object();
    private final Car3dState sharedState = new Car3dState();
    private final float[] sharedMarkersUv = new float[MARKER_ANCHORS.length * 3];

    private GLThread glThread;
    private SurfaceTexture pendingSurface;
    private int pendingW;
    private int pendingH;
    private volatile boolean renderPaused = false;
    private volatile boolean surfaceReady = false;
    private boolean framePending = true;
    private Runnable onFrameRendered;

    private float orbitDeltaYawDeg = 0f;
    private float orbitDeltaPitchDeg = 0f;
    private boolean orbitSetPending = false;
    private float orbitTargetYawDeg = 0f;
    private float orbitTargetPitchDeg = 0f;

    public Car3dView(Context context) {
        super(context);
        this.assets = context.getAssets();
        setOpaque(false);
        setSurfaceTextureListener(this);
    }

    public void setOnFrameRendered(Runnable listener) {
        this.onFrameRendered = listener;
    }

    public boolean isSurfaceReady() {
        return surfaceReady;
    }

    public void pushState(Car3dState src) {
        synchronized (stateLock) {
            sharedState.copyFrom(src);
            framePending = true;
            stateLock.notifyAll();
        }
    }

    public void addOrbitDelta(float dYawDeg, float dPitchDeg) {
        synchronized (stateLock) {
            orbitDeltaYawDeg += dYawDeg;
            orbitDeltaPitchDeg += dPitchDeg;
        }
    }

    public void setOrbitAngles(float yawDeg, float pitchDeg) {
        synchronized (stateLock) {
            orbitTargetYawDeg = yawDeg;
            orbitTargetPitchDeg = Math.max(-20f, Math.min(36f, pitchDeg));
            orbitSetPending = true;
            orbitDeltaYawDeg = 0f;
            orbitDeltaPitchDeg = 0f;
            framePending = true;
            stateLock.notifyAll();
        }
    }

    public void copyDoorMarkersUv(float[] out18) {
        if (out18 == null || out18.length < sharedMarkersUv.length) return;
        synchronized (stateLock) {
            System.arraycopy(sharedMarkersUv, 0, out18, 0, sharedMarkersUv.length);
        }
    }

    public void setRenderPaused(boolean paused) {
        this.renderPaused = paused;
        synchronized (stateLock) {
            if (!paused) framePending = true;
            stateLock.notifyAll();
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        synchronized (stateLock) {
            pendingSurface = surface;
            pendingW = width;
            pendingH = height;
            framePending = true;
            stopThreadLocked();
            glThread = new GLThread(surface, width, height);
            glThread.start();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        synchronized (stateLock) {
            pendingW = width;
            pendingH = height;
            framePending = true;
            if (glThread != null) glThread.onResize(width, height);
            stateLock.notifyAll();
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        surfaceReady = false;
        synchronized (stateLock) {
            pendingSurface = null;
            stopThreadLocked();
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    private void stopThreadLocked() {
        if (glThread != null) {
            GLThread t = glThread;
            glThread = null;
            t.requestStop();
            t.joinQuietly(600L);
        }
    }

    private final class GLThread extends Thread {
        private final SurfaceTexture surfaceTexture;
        private volatile int viewW;
        private volatile int viewH;
        private volatile boolean running = true;
        private volatile boolean resized = true;

        private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
        private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

        private int carProgram;
        private int uModelLoc, uVpLoc, uPosScaleLoc, uMatLoc, uCamPosLoc, uBodyRgbLoc, uHeadLoc, uBrakeLoc, uRevLoc, uLightLoc;

        private int floorProgram;
        private int fVpLoc, fHeadLoc, fBrakeLoc, fRevLoc, fParkLoc, fLightLoc, fDoorsSideLoc, fDoorsEndLoc;

        private int bloomProgram;
        private int bModelLoc, bVpLoc, bCamPosLoc, bBrakeLoc, bLightLoc, bRevModeLoc;

        private int carVbo, carIbo, floorVbo, bloomVbo;
        private float posScale = 2.5f;
        private float[][] partPivots;
        private Batch[] batches;

        private final Car3dState localState = new Car3dState();
        private float wheelSpinDeg = 0f;
        private float revAnim = 0f;
        private float parkBlend = 0f;
        private boolean parkBlendInit = false;
        private float userYawDeg = 0f;
        private float userPitchDeg = 0f;
        private float simTimeSec = 0f;
        private float orbitIdleSec = 99f;

        private final float[] viewMat = new float[16];
        private final float[] projMat = new float[16];
        private final float[] vpMat = new float[16];
        private final float[] floorMat = new float[16];
        private final float[] floorVpMat = new float[16];
        private final float[][] partModels = new float[5][16];
        private final float[] tmpIn = new float[4];
        private final float[] tmpOut = new float[4];
        private final float[] tmpMarkers = new float[MARKER_ANCHORS.length * 3];

        GLThread(SurfaceTexture st, int w, int h) {
            super("Car3dGLThread");
            this.surfaceTexture = st;
            this.viewW = Math.max(1, w);
            this.viewH = Math.max(1, h);
        }

        void onResize(int w, int h) {
            this.viewW = Math.max(1, w);
            this.viewH = Math.max(1, h);
            this.resized = true;
        }

        void requestStop() {
            running = false;
            synchronized (stateLock) {
                stateLock.notifyAll();
            }
        }

        void joinQuietly(long ms) {
            try {
                join(ms);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void run() {
            if (!initEgl()) {
                releaseEgl();
                return;
            }
            if (!initGlResources()) {
                surfaceReady = false;
                releaseGlResources();
                releaseEgl();
                return;
            }
            surfaceReady = true;

            long prevFrameNs = 0L;
            long fpsWinMs = SystemClock.uptimeMillis();
            int fpsCount = 0;

            while (running) {
                synchronized (stateLock) {
                    while (running && (renderPaused || !framePending)) {
                        try {
                            stateLock.wait(250L);
                        } catch (InterruptedException ignored) {
                            break;
                        }
                    }
                    if (!running) break;
                    framePending = false;
                    localState.copyFrom(sharedState);
                    if (orbitSetPending) {
                        userYawDeg = orbitTargetYawDeg;
                        userPitchDeg = orbitTargetPitchDeg;
                        orbitSetPending = false;
                        orbitIdleSec = 0f;
                    } else if (orbitDeltaYawDeg != 0f || orbitDeltaPitchDeg != 0f) {
                        userYawDeg += orbitDeltaYawDeg;
                        userPitchDeg = Math.max(-20f, Math.min(36f, userPitchDeg + orbitDeltaPitchDeg));
                        orbitDeltaYawDeg = 0f;
                        orbitDeltaPitchDeg = 0f;
                        orbitIdleSec = 0f;
                    }
                }

                long frameNs = localState.frameTimeNanos > 0L ? localState.frameTimeNanos : System.nanoTime();
                float dt = (prevFrameNs == 0L) ? 0.0339f : Math.min(0.1f, Math.max(0.001f, (frameNs - prevFrameNs) / 1e9f));
                prevFrameNs = frameNs;
                simTimeSec += dt;
                orbitIdleSec = Math.min(99f, orbitIdleSec + dt);

                if (resized) {
                    resized = false;
                    GLES30.glViewport(0, 0, viewW, viewH);
                }

                renderFrame(dt);
                EGL14.eglSwapBuffers(eglDisplay, eglSurface);

                if (running && surfaceReady) {
                    Runnable cb = onFrameRendered;
                    if (cb != null) post(cb);
                }

                fpsCount++;
                long nowMs = SystemClock.uptimeMillis();
                if (nowMs - fpsWinMs >= 2000L) {
                    double fps = (fpsCount * 1000.0) / (nowMs - fpsWinMs);
                    fpsCount = 0;
                    fpsWinMs = nowMs;
                    Log.i(TAG, String.format(java.util.Locale.US, "FPS=%.1f parked=%s parkBlend=%.2f size=%dx%d",
                            fps, localState.parked, parkBlend, viewW, viewH));
                }
            }

            surfaceReady = false;
            releaseGlResources();
            releaseEgl();
        }

        private boolean initEgl() {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                Log.e(TAG, "eglGetDisplay failed");
                return false;
            }
            int[] ver = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) {
                Log.e(TAG, "eglInitialize failed");
                return false;
            }

            int[][] candidates = {
                    {EGL_OPENGL_ES3_BIT_KHR, 1, 4, 16},
                    {EGL_OPENGL_ES3_BIT_KHR, 1, 2, 16},
                    {EGL_OPENGL_ES3_BIT_KHR, 0, 0, 24},
                    {EGL_OPENGL_ES3_BIT_KHR, 0, 0, 16},
                    {EGL14.EGL_OPENGL_ES2_BIT, 0, 0, 16},
            };

            EGLConfig chosen = null;
            int chosenSamples = 0;
            for (int[] c : candidates) {
                int[] attribs = c[1] > 0
                        ? new int[]{
                                EGL14.EGL_RENDERABLE_TYPE, c[0],
                                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                                EGL14.EGL_RED_SIZE, 8,
                                EGL14.EGL_GREEN_SIZE, 8,
                                EGL14.EGL_BLUE_SIZE, 8,
                                EGL14.EGL_ALPHA_SIZE, 8,
                                EGL14.EGL_DEPTH_SIZE, c[3],
                                EGL14.EGL_SAMPLE_BUFFERS, 1,
                                EGL14.EGL_SAMPLES, c[2],
                                EGL14.EGL_NONE
                        }
                        : new int[]{
                                EGL14.EGL_RENDERABLE_TYPE, c[0],
                                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                                EGL14.EGL_RED_SIZE, 8,
                                EGL14.EGL_GREEN_SIZE, 8,
                                EGL14.EGL_BLUE_SIZE, 8,
                                EGL14.EGL_ALPHA_SIZE, 8,
                                EGL14.EGL_DEPTH_SIZE, c[3],
                                EGL14.EGL_NONE
                        };
                EGLConfig[] out = new EGLConfig[1];
                int[] num = new int[1];
                if (EGL14.eglChooseConfig(eglDisplay, attribs, 0, out, 0, 1, num, 0) && num[0] > 0) {
                    chosen = out[0];
                    chosenSamples = c[2];
                    break;
                }
            }
            if (chosen == null) {
                Log.e(TAG, "No suitable EGLConfig found");
                return false;
            }

            int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
            eglContext = EGL14.eglCreateContext(eglDisplay, chosen, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                Log.e(TAG, "eglCreateContext ES3 failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
                return false;
            }

            int[] surfAttribs = {EGL14.EGL_NONE};
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, chosen, surfaceTexture, surfAttribs, 0);
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "eglCreateWindowSurface failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
                return false;
            }

            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                Log.e(TAG, "eglMakeCurrent failed");
                return false;
            }

            Log.i(TAG, "Initialized GL: renderer=" + GLES30.glGetString(GLES30.GL_RENDERER)
                    + " version=" + GLES30.glGetString(GLES30.GL_VERSION) + " msaa=" + chosenSamples);
            return true;
        }

        private void releaseEgl() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
                EGL14.eglTerminate(eglDisplay);
            }
            eglSurface = EGL14.EGL_NO_SURFACE;
            eglContext = EGL14.EGL_NO_CONTEXT;
            eglDisplay = EGL14.EGL_NO_DISPLAY;
        }

        private boolean initGlResources() {
            carProgram = buildProgram(CAR_VERT_SRC, CAR_FRAG_SRC);
            floorProgram = buildProgram(FLOOR_VERT_SRC, FLOOR_FRAG_SRC);
            bloomProgram = buildProgram(BLOOM_VERT_SRC, BLOOM_FRAG_SRC);
            if (carProgram == 0 || floorProgram == 0 || bloomProgram == 0) return false;

            uModelLoc = GLES30.glGetUniformLocation(carProgram, "uModel");
            uVpLoc = GLES30.glGetUniformLocation(carProgram, "uViewProj");
            uPosScaleLoc = GLES30.glGetUniformLocation(carProgram, "uPosScale");
            uMatLoc = GLES30.glGetUniformLocation(carProgram, "uMatId");
            uCamPosLoc = GLES30.glGetUniformLocation(carProgram, "uCamPos");
            uBodyRgbLoc = GLES30.glGetUniformLocation(carProgram, "uBodyRgb");
            uHeadLoc = GLES30.glGetUniformLocation(carProgram, "uHeadLamp");
            uBrakeLoc = GLES30.glGetUniformLocation(carProgram, "uBrakeLamp");
            uRevLoc = GLES30.glGetUniformLocation(carProgram, "uReverse");
            uLightLoc = GLES30.glGetUniformLocation(carProgram, "uLightTheme");

            fVpLoc = GLES30.glGetUniformLocation(floorProgram, "uViewProj");
            fHeadLoc = GLES30.glGetUniformLocation(floorProgram, "uHeadLamp");
            fBrakeLoc = GLES30.glGetUniformLocation(floorProgram, "uBrakeLamp");
            fRevLoc = GLES30.glGetUniformLocation(floorProgram, "uReverse");
            fParkLoc = GLES30.glGetUniformLocation(floorProgram, "uParkBlend");
            fLightLoc = GLES30.glGetUniformLocation(floorProgram, "uLightTheme");
            fDoorsSideLoc = GLES30.glGetUniformLocation(floorProgram, "uDoorsSide");
            fDoorsEndLoc = GLES30.glGetUniformLocation(floorProgram, "uDoorsEnd");

            bModelLoc = GLES30.glGetUniformLocation(bloomProgram, "uModel");
            bVpLoc = GLES30.glGetUniformLocation(bloomProgram, "uViewProj");
            bCamPosLoc = GLES30.glGetUniformLocation(bloomProgram, "uCamPos");
            bBrakeLoc = GLES30.glGetUniformLocation(bloomProgram, "uBrakeLamp");
            bLightLoc = GLES30.glGetUniformLocation(bloomProgram, "uLightTheme");
            bRevModeLoc = GLES30.glGetUniformLocation(bloomProgram, "uReverseMode");

            if (!loadCarAsset()) return false;

            float[] floorVerts = {
                    -2.6f, 0.002f, -6.8f,
                     2.6f, 0.002f, -6.8f,
                    -2.6f, 0.002f,  5.4f,
                     2.6f, 0.002f,  5.4f,
            };
            float[] bloomVerts = {
                    // Left taillamp (-0.62, 1.05, 2.11), hw=0.48, hh=0.26
                    -1.10f, 0.79f, 2.11f, -1f, -1f,
                    -0.14f, 0.79f, 2.11f,  1f, -1f,
                    -0.14f, 1.31f, 2.11f,  1f,  1f,
                    -1.10f, 0.79f, 2.11f, -1f, -1f,
                    -0.14f, 1.31f, 2.11f,  1f,  1f,
                    -1.10f, 1.31f, 2.11f, -1f,  1f,
                    // Right taillamp (+0.62, 1.05, 2.11), hw=0.48, hh=0.26
                     0.14f, 0.79f, 2.11f, -1f, -1f,
                     1.10f, 0.79f, 2.11f,  1f, -1f,
                     1.10f, 1.31f, 2.11f,  1f,  1f,
                     0.14f, 0.79f, 2.11f, -1f, -1f,
                     1.10f, 1.31f, 2.11f,  1f,  1f,
                     0.14f, 1.31f, 2.11f, -1f,  1f,
                    // Top spoiler HMSL (0.00, 1.42, 1.87), hw=0.44, hh=0.15
                    -0.44f, 1.27f, 1.87f, -1f, -1f,
                     0.44f, 1.27f, 1.87f,  1f, -1f,
                     0.44f, 1.57f, 1.87f,  1f,  1f,
                    -0.44f, 1.27f, 1.87f, -1f, -1f,
                     0.44f, 1.57f, 1.87f,  1f,  1f,
                    -0.44f, 1.57f, 1.87f, -1f,  1f,
                    // Left bumper reverse lamp (-0.715, 0.580, 2.13), hw=0.24, hh=0.14
                    -0.955f, 0.440f, 2.13f, -1f, -1f,
                    -0.475f, 0.440f, 2.13f,  1f, -1f,
                    -0.475f, 0.720f, 2.13f,  1f,  1f,
                    -0.955f, 0.440f, 2.13f, -1f, -1f,
                    -0.475f, 0.720f, 2.13f,  1f,  1f,
                    -0.955f, 0.720f, 2.13f, -1f,  1f,
                    // Right bumper reverse lamp (+0.715, 0.580, 2.13), hw=0.24, hh=0.14
                     0.475f, 0.440f, 2.13f, -1f, -1f,
                     0.955f, 0.440f, 2.13f,  1f, -1f,
                     0.955f, 0.720f, 2.13f,  1f,  1f,
                     0.475f, 0.440f, 2.13f, -1f, -1f,
                     0.955f, 0.720f, 2.13f,  1f,  1f,
                     0.475f, 0.720f, 2.13f, -1f,  1f,
            };
            int[] bufs = new int[2];
            GLES30.glGenBuffers(2, bufs, 0);
            floorVbo = bufs[0];
            bloomVbo = bufs[1];
            FloatBuffer fb = ByteBuffer.allocateDirect(floorVerts.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            fb.put(floorVerts).position(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, floorVbo);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, floorVerts.length * 4, fb, GLES30.GL_STATIC_DRAW);

            FloatBuffer bb = ByteBuffer.allocateDirect(bloomVerts.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            bb.put(bloomVerts).position(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bloomVbo);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, bloomVerts.length * 4, bb, GLES30.GL_STATIC_DRAW);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);

            GLES30.glDisable(GLES30.GL_CULL_FACE);
            GLES30.glEnable(GLES30.GL_DEPTH_TEST);
            GLES30.glDepthFunc(GLES30.GL_LEQUAL);
            GLES30.glEnable(GLES30.GL_BLEND);
            GLES30.glBlendFuncSeparate(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA,
                    GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
            return true;
        }

        private void releaseGlResources() {
            int[] b = {carVbo, carIbo, floorVbo, bloomVbo};
            GLES30.glDeleteBuffers(4, b, 0);
            if (carProgram != 0) GLES30.glDeleteProgram(carProgram);
            if (floorProgram != 0) GLES30.glDeleteProgram(floorProgram);
            if (bloomProgram != 0) GLES30.glDeleteProgram(bloomProgram);
            carVbo = carIbo = floorVbo = bloomVbo = carProgram = floorProgram = bloomProgram = 0;
        }

        private boolean loadCarAsset() {
            try (InputStream is = assets.open("car3d/niro_ev.c3d")) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(3200000);
                byte[] tmp = new byte[32768];
                int n;
                while ((n = is.read(tmp)) > 0) baos.write(tmp, 0, n);
                byte[] bytes = baos.toByteArray();
                ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                if (bb.get() != 'C' || bb.get() != '3' || bb.get() != 'D' || bb.get() != '2') {
                    Log.e(TAG, "Invalid C3D2 magic");
                    return false;
                }
                int numParts = bb.getShort() & 0xFFFF;
                int numBatches = bb.getShort() & 0xFFFF;
                int totalVerts = bb.getInt();
                int totalIndices = bb.getInt();
                posScale = bb.getFloat();

                partPivots = new float[numParts][3];
                for (int i = 0; i < numParts; i++) {
                    partPivots[i][0] = bb.getFloat();
                    partPivots[i][1] = bb.getFloat();
                    partPivots[i][2] = bb.getFloat();
                }

                batches = new Batch[numBatches];
                for (int i = 0; i < numBatches; i++) {
                    Batch b = new Batch();
                    b.partId = bb.getShort() & 0xFFFF;
                    b.matId = bb.getShort() & 0xFFFF;
                    b.vboByteOffset = bb.getInt() * 12;
                    b.indexByteOffset = bb.getInt() * 2;
                    b.indexCount = bb.getInt();
                    batches[i] = b;
                }

                int vByteLen = totalVerts * 12;
                ByteBuffer vBuf = ByteBuffer.allocateDirect(vByteLen).order(ByteOrder.nativeOrder());
                ShortBuffer vShort = vBuf.asShortBuffer();
                for (int i = 0; i < totalVerts * 6; i++) vShort.put(bb.getShort());
                vBuf.position(0);

                int iByteLen = totalIndices * 2;
                ByteBuffer iBuf = ByteBuffer.allocateDirect(iByteLen).order(ByteOrder.nativeOrder());
                ShortBuffer iShort = iBuf.asShortBuffer();
                for (int i = 0; i < totalIndices; i++) iShort.put(bb.getShort());
                iBuf.position(0);

                int[] bufs = new int[2];
                GLES30.glGenBuffers(2, bufs, 0);
                carVbo = bufs[0];
                carIbo = bufs[1];

                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, carVbo);
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vByteLen, vBuf, GLES30.GL_STATIC_DRAW);
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);

                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, carIbo);
                GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, iByteLen, iBuf, GLES30.GL_STATIC_DRAW);
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);

                Log.i(TAG, "Loaded C3D2 niro_ev.c3d: verts=" + totalVerts + " tris=" + (totalIndices / 3)
                        + " batches=" + numBatches + " posScale=" + posScale);
                return true;
            } catch (IOException e) {
                Log.e(TAG, "Failed to load car3d/niro_ev.c3d", e);
                return false;
            }
        }

        private void renderFrame(float dt) {
            GLES30.glClearColor(0f, 0f, 0f, 0f);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);

            if (!localState.visible || partPivots == null || batches == null) return;

            // Kinematics update: use unified shared camera matrix when available
            float b;
            float carYawDeg;
            float eyeX, eyeY, eyeZ;
            float aspect = (float) viewW / Math.max(1f, viewH);
            float absSteerNorm = Math.min(1f, Math.abs(localState.steerDeg) / 500f);
            float steerProg = absSteerNorm * 0.62f
                    + (1f - (1f - absSteerNorm) * (1f - absSteerNorm)) * 0.38f;
            float baseWheelDeg = Math.signum(localState.steerDeg) * 33.2f * steerProg;
            float steerFlDeg = (localState.steerDeg >= 0f) ? (baseWheelDeg * 1.07f) : (baseWheelDeg * 0.93f);
            float steerFrDeg = (localState.steerDeg >= 0f) ? (baseWheelDeg * 0.93f) : (baseWheelDeg * 1.07f);
            float steerLeadDeg = localState.parked ? 0f
                    : Math.max(-1.6f, Math.min(1.6f, (-localState.steerDeg / 90f) * 1.1f));
            float revTgt = localState.reverse ? 1f : 0f;
            float revStep = dt / 0.18f;
            if (revAnim < revTgt) revAnim = Math.min(revTgt, revAnim + revStep);
            else if (revAnim > revTgt) revAnim = Math.max(revTgt, revAnim - revStep);

            if (localState.speedKph > 0.2f) {
                float speedMps = localState.speedKph / 3.6f;
                float dir = localState.reverse ? 1f : -1f; // -X rotation rolls top of tire toward -Z (forward)
                wheelSpinDeg = (wheelSpinDeg + dir * (speedMps / WHEEL_RADIUS_M) * (180f / (float) Math.PI) * dt) % 360f;
            }

            if (localState.hasSharedCamera) {
                System.arraycopy(localState.vpMat, 0, vpMat, 0, 16);
                eyeX = localState.eyeX;
                eyeY = localState.eyeY;
                eyeZ = localState.eyeZ;
                b = localState.parkBlend;
                parkBlend = b;
                parkBlendInit = true;
                float driveWeight = 1f - b;
                carYawDeg = -steerLeadDeg * driveWeight;
            } else {
                float parkTarget = localState.parked ? 1f : 0f;
                if (!parkBlendInit) {
                    parkBlendInit = true;
                    parkBlend = parkTarget;
                } else {
                    float pStep = dt / PARK_TRANS_SEC;
                    if (parkBlend < parkTarget) parkBlend = Math.min(parkTarget, parkBlend + pStep);
                    else if (parkBlend > parkTarget) parkBlend = Math.max(parkTarget, parkBlend - pStep);
                }
                if (!localState.parked) {
                    float decay = (float) Math.exp(-dt * 2.2f);
                    userYawDeg *= decay;
                    userPitchDeg *= decay;
                }
                b = parkBlend * parkBlend * parkBlend * (parkBlend * (parkBlend * 6f - 15f) + 10f);
                float driveWeight = 1f - b;
                carYawDeg = Math.max(-7.5f, Math.min(7.5f, -(localState.chaseYawDeg + steerLeadDeg))) * driveWeight;

                float dTgtX = -0.24f, dTgtY = 2.92f, dTgtZ = 0.0f;
                float dDist = 25.92375f;
                float dYawDeg = 0f;
                float dPitchDeg = 5.6003f;
                float dFov = 18.0f;

                float demoSway = (localState.demoMode && orbitIdleSec > 3.5f)
                        ? (float) Math.sin(simTimeSec * 0.55) * 14f
                        : 0f;
                float pTgtX = -0.14f, pTgtY = 0.44f, pTgtZ = 0.02f;
                float pDist = 6.35f;
                float pYawDeg = wrapDeg180(-146.0f + userYawDeg + demoSway);
                float pPitchDeg = 27.0f + userPitchDeg;
                float pFov = 34f;

                float tgtX = dTgtX + (pTgtX - dTgtX) * b;
                float tgtY = dTgtY + (pTgtY - dTgtY) * b;
                float tgtZ = dTgtZ + (pTgtZ - dTgtZ) * b;
                double yawRad = Math.toRadians(dYawDeg + (pYawDeg - dYawDeg) * b);
                double pitchRad = Math.toRadians(dPitchDeg + (pPitchDeg - dPitchDeg) * b);
                float dist = (float) (dDist * Math.pow(pDist / dDist, b));
                eyeX = tgtX + (float) (dist * Math.cos(pitchRad) * Math.sin(yawRad));
                eyeY = tgtY + (float) (dist * Math.sin(pitchRad));
                eyeZ = tgtZ + (float) (dist * Math.cos(pitchRad) * Math.cos(yawRad));
                float fov = dFov + (pFov - dFov) * b;

                Matrix.setLookAtM(viewMat, 0, eyeX, eyeY, eyeZ, tgtX, tgtY, tgtZ, 0f, 1f, 0f);
                Matrix.perspectiveM(projMat, 0, fov, aspect, 0.8f, 45f);
                Matrix.multiplyMM(vpMat, 0, projMat, 0, viewMat, 0);
            }

            float driveWeight = 1f - b;
            float speedRoll = Math.max(0.25f, Math.min(1.0f, localState.speedKph / 60f));
            float carRollDeg = Math.max(-0.55f, Math.min(0.55f, (localState.chaseYawDeg / 4.2f) * 0.42f * speedRoll)) * driveWeight;

            // Compute model matrices for body (yaw + suspension roll) + 4 wheels (yaw + steer + spin)
            float[] bodyM = partModels[PART_BODY];
            Matrix.setIdentityM(bodyM, 0);
            Matrix.rotateM(bodyM, 0, carYawDeg, 0f, 1f, 0f);
            Matrix.translateM(bodyM, 0, 0f, 0.34f, 0f);
            Matrix.rotateM(bodyM, 0, carRollDeg, 0f, 0f, 1f);
            Matrix.translateM(bodyM, 0, 0f, -0.34f, 0f);

            for (int pid = PART_WHEEL_FL; pid <= PART_WHEEL_RR; pid++) {
                float[] m = partModels[pid];
                float[] pv = partPivots[pid];
                Matrix.setIdentityM(m, 0);
                Matrix.rotateM(m, 0, carYawDeg, 0f, 1f, 0f);
                Matrix.translateM(m, 0, pv[0], pv[1], pv[2]);
                if (pid == PART_WHEEL_FL) {
                    Matrix.rotateM(m, 0, steerFlDeg, 0f, 1f, 0f);
                } else if (pid == PART_WHEEL_FR) {
                    Matrix.rotateM(m, 0, steerFrDeg, 0f, 1f, 0f);
                }
                Matrix.rotateM(m, 0, wheelSpinDeg, 1f, 0f, 0f);
            }

            float lt = localState.lightTheme ? 1f : 0f;

            // Pass 1: Floor shadow + floor lamp beams (yaw-aligned with car)
            Matrix.setIdentityM(floorMat, 0);
            Matrix.rotateM(floorMat, 0, carYawDeg, 0f, 1f, 0f);
            Matrix.multiplyMM(floorVpMat, 0, vpMat, 0, floorMat, 0);
            updateProjectedMarkers(floorVpMat);

            GLES30.glDisable(GLES30.GL_CULL_FACE);
            GLES30.glDepthMask(false);
            GLES30.glEnable(GLES30.GL_BLEND);
            GLES30.glUseProgram(floorProgram);
            GLES30.glUniformMatrix4fv(fVpLoc, 1, false, floorVpMat, 0);
            GLES30.glUniform1f(fHeadLoc, localState.headAnim);
            GLES30.glUniform1f(fBrakeLoc, localState.brakeAnim);
            GLES30.glUniform1f(fRevLoc, revAnim);
            GLES30.glUniform1f(fParkLoc, b);
            GLES30.glUniform1f(fLightLoc, lt);
            GLES30.glUniform4f(fDoorsSideLoc,
                    localState.doorEased[0], localState.doorEased[1],
                    localState.doorEased[2], localState.doorEased[3]);
            GLES30.glUniform4f(fDoorsEndLoc,
                    localState.doorEased[4], localState.doorEased[5],
                    localState.doorBlink, localState.parked ? 0f : 1f);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, floorVbo);
            GLES30.glEnableVertexAttribArray(0);
            GLES30.glDisableVertexAttribArray(1);
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

            // Pass 2: Car opaque geometry (matId != MAT_GLASS) with backface culling
            int colorIdx = Math.max(0, Math.min(BODY_COLORS_DARK.length - 1, localState.bodyColorId));
            float[] bodyRgb = localState.lightTheme ? BODY_COLORS_LIGHT[colorIdx] : BODY_COLORS_DARK[colorIdx];

            GLES30.glEnable(GLES30.GL_CULL_FACE);
            GLES30.glCullFace(GLES30.GL_BACK);
            GLES30.glDepthMask(true);
            GLES30.glDisable(GLES30.GL_BLEND);
            GLES30.glUseProgram(carProgram);
            GLES30.glUniformMatrix4fv(uVpLoc, 1, false, vpMat, 0);
            GLES30.glUniform1f(uPosScaleLoc, posScale);
            GLES30.glUniform3f(uCamPosLoc, eyeX, eyeY, eyeZ);
            GLES30.glUniform3f(uBodyRgbLoc, bodyRgb[0], bodyRgb[1], bodyRgb[2]);
            GLES30.glUniform1f(uHeadLoc, localState.headAnim);
            GLES30.glUniform1f(uBrakeLoc, localState.brakeAnim);
            GLES30.glUniform1f(uRevLoc, revAnim);
            GLES30.glUniform1f(uLightLoc, lt);

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, carVbo);
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, carIbo);
            GLES30.glEnableVertexAttribArray(0);
            GLES30.glEnableVertexAttribArray(1);

            drawBatches(false);

            // Pass 3: Clear polycarbonate headlamp cover glass so internal optics shine through
            GLES30.glEnable(GLES30.GL_BLEND);
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
            GLES30.glDepthMask(false);
            drawBatches(true);
            GLES30.glDepthMask(true);
            GLES30.glDisable(GLES30.GL_BLEND);

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);

            // Pass 4: 3-point brake + 2-point reverse LED bloom halo quads
            if ((localState.brakeAnim > 0.01f || revAnim > 0.01f) && bloomProgram != 0 && bloomVbo != 0) {
                GLES30.glDisable(GLES30.GL_CULL_FACE);
                GLES30.glDepthMask(false);
                GLES30.glEnable(GLES30.GL_BLEND);
                GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
                GLES30.glUseProgram(bloomProgram);
                GLES30.glUniformMatrix4fv(bModelLoc, 1, false, partModels[PART_BODY], 0);
                GLES30.glUniformMatrix4fv(bVpLoc, 1, false, vpMat, 0);
                GLES30.glUniform3f(bCamPosLoc, eyeX, eyeY, eyeZ);
                GLES30.glUniform1f(bLightLoc, lt);
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bloomVbo);
                GLES30.glEnableVertexAttribArray(0);
                GLES30.glEnableVertexAttribArray(1);
                GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, 0);
                GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, 12);
                if (localState.brakeAnim > 0.01f) {
                    GLES30.glUniform1f(bBrakeLoc, localState.brakeAnim);
                    GLES30.glUniform1f(bRevModeLoc, 0f);
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 18);
                }
                if (revAnim > 0.01f) {
                    GLES30.glUniform1f(bBrakeLoc, revAnim);
                    GLES30.glUniform1f(bRevModeLoc, 1f);
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 18, 12);
                }
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
                GLES30.glDepthMask(true);
                GLES30.glDisable(GLES30.GL_BLEND);
            }
        }

        private void drawBatches(boolean transparentHeadlamp) {
            int lastPart = -1;
            int lastMat = -1;
            int lastVboOff = -1;
            for (Batch b : batches) {
                if ((b.matId == MAT_HEADLAMP) != transparentHeadlamp) continue;
                if (b.vboByteOffset != lastVboOff) {
                    lastVboOff = b.vboByteOffset;
                    GLES30.glVertexAttribPointer(0, 3, GLES30.GL_SHORT, true, 12, lastVboOff);
                    GLES30.glVertexAttribPointer(1, 3, GLES30.GL_SHORT, true, 12, lastVboOff + 6);
                }
                if (b.partId != lastPart) {
                    lastPart = b.partId;
                    GLES30.glUniformMatrix4fv(uModelLoc, 1, false, partModels[lastPart], 0);
                }
                if (b.matId != lastMat) {
                    lastMat = b.matId;
                    GLES30.glUniform1i(uMatLoc, lastMat);
                }
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, b.indexCount, GLES30.GL_UNSIGNED_SHORT, b.indexByteOffset);
            }
        }

        private void updateProjectedMarkers(float[] vp) {
            for (int i = 0; i < MARKER_ANCHORS.length; i++) {
                float[] a = MARKER_ANCHORS[i];
                tmpIn[0] = a[0];
                tmpIn[1] = a[1];
                tmpIn[2] = a[2];
                tmpIn[3] = 1f;
                Matrix.multiplyMV(tmpOut, 0, vp, 0, tmpIn, 0);
                float w = tmpOut[3];
                if (w > 0.05f) {
                    float ndcX = tmpOut[0] / w;
                    float ndcY = tmpOut[1] / w;
                    float u = (ndcX * 0.5f) + 0.5f;
                    float v = 0.5f - (ndcY * 0.5f);
                    tmpMarkers[i * 3] = u;
                    tmpMarkers[i * 3 + 1] = v;
                    tmpMarkers[i * 3 + 2] = (u >= -0.05f && u <= 1.05f && v >= -0.05f && v <= 1.05f) ? 1f : 0f;
                } else {
                    tmpMarkers[i * 3] = 0.5f;
                    tmpMarkers[i * 3 + 1] = 0.5f;
                    tmpMarkers[i * 3 + 2] = 0f;
                }
            }
            synchronized (stateLock) {
                System.arraycopy(tmpMarkers, 0, sharedMarkersUv, 0, tmpMarkers.length);
            }
        }

        private int compileShader(int type, String src) {
            int sh = GLES30.glCreateShader(type);
            GLES30.glShaderSource(sh, src);
            GLES30.glCompileShader(sh);
            int[] ok = new int[1];
            GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) {
                Log.e(TAG, "Shader compile error: " + GLES30.glGetShaderInfoLog(sh));
                GLES30.glDeleteShader(sh);
                return 0;
            }
            return sh;
        }

        private int buildProgram(String vs, String fs) {
            int v = compileShader(GLES30.GL_VERTEX_SHADER, vs);
            int f = compileShader(GLES30.GL_FRAGMENT_SHADER, fs);
            if (v == 0 || f == 0) {
                if (v != 0) GLES30.glDeleteShader(v);
                if (f != 0) GLES30.glDeleteShader(f);
                return 0;
            }
            int p = GLES30.glCreateProgram();
            GLES30.glAttachShader(p, v);
            GLES30.glAttachShader(p, f);
            GLES30.glLinkProgram(p);
            int[] ok = new int[1];
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
            GLES30.glDeleteShader(v);
            GLES30.glDeleteShader(f);
            if (ok[0] == 0) {
                Log.e(TAG, "Program link error: " + GLES30.glGetProgramInfoLog(p));
                GLES30.glDeleteProgram(p);
                return 0;
            }
            return p;
        }
    }
}
