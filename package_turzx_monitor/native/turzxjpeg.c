/*
 * libturzxjpeg.so — libjpeg-turbo (NEON SIMD) JPEG encoder for HKG 5W TurzxMonitor.
 *
 * Replaces Skia Bitmap.compress() (libjpeg 6b, scalar) on Android 4.4 / armeabi-v7a.
 * Reads the ARGB_8888 android.graphics.Bitmap pixels in place via libjnigraphics,
 * optionally rotates them 90/270 degrees into a macroblock-padded portrait buffer
 * (the TURZX 9.2" panel scans out 464x1920), and encodes baseline 4:2:0 JPEG.
 *
 * Rotation semantics match the former Skia Matrix path in TurzxFrameRenderer:
 *   rotation == 90  : dst(px = y,         py = W - 1 - x)   (postRotate 270 + translate(0, W))
 *   rotation == 270 : dst(px = H - 1 - y, py = x)           (postRotate 90  + translate(H, 0))
 *   rotation == 0   : no rotation (landscape web preview)
 * Columns [H, padWidth) of the portrait output are filled opaque black.
 */
#include <jni.h>
#include <android/bitmap.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "turbojpeg.h"

/* Internal libjpeg-turbo SIMD capability probe (exported from the static library). */
extern int jsimd_can_rgb_ycc(void);

#define TILE 32

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static tjhandle g_tj = NULL;
static int g_tjQuality = -1;
static uint32_t *g_rot = NULL;
static size_t g_rotCap = 0;            /* in pixels */
static unsigned char *g_jpeg = NULL;
static size_t g_jpegCap = 0;           /* in bytes */

static int ensure_handle(int quality) {
    if (g_tj == NULL) {
        g_tj = tj3Init(TJINIT_COMPRESS);
        if (g_tj == NULL) return -1;
        if (tj3Set(g_tj, TJPARAM_SUBSAMP, TJSAMP_420) < 0) return -1;
        if (tj3Set(g_tj, TJPARAM_FASTDCT, 1) < 0) return -1;
        if (tj3Set(g_tj, TJPARAM_NOREALLOC, 1) < 0) return -1;
        g_tjQuality = -1;
    }
    if (quality != g_tjQuality) {
        if (tj3Set(g_tj, TJPARAM_QUALITY, quality) < 0) return -1;
        g_tjQuality = quality;
    }
    return 0;
}

static int ensure_jpeg_buf(int w, int h) {
    size_t need = tj3JPEGBufSize(w, h, TJSAMP_420);
    if (need == 0) return -1;
    if (need > g_jpegCap) {
        if (g_jpeg != NULL) tj3Free(g_jpeg);
        g_jpeg = (unsigned char *) tj3Alloc(need);
        if (g_jpeg == NULL) {
            g_jpegCap = 0;
            return -1;
        }
        g_jpegCap = need;
    }
    return 0;
}

static int ensure_rot_buf(size_t pixels) {
    if (pixels > g_rotCap) {
        uint32_t *p = (uint32_t *) realloc(g_rot, pixels * sizeof(uint32_t));
        if (p == NULL) return -1;
        g_rot = p;
        g_rotCap = pixels;
    }
    return 0;
}

static void rotate_into(const uint8_t *srcBase, uint32_t srcStrideBytes, int W, int H,
                        int rotation, uint32_t *dst, int outW) {
    int ty, tx, y, x;
    /* Opaque black padding columns (RGBA bytes 00 00 00 FF on little-endian). */
    if (outW > H) {
        for (y = 0; y < W; y++) {
            uint32_t *row = dst + (size_t) y * outW;
            for (x = H; x < outW; x++) row[x] = 0xFF000000u;
        }
    }
    for (ty = 0; ty < H; ty += TILE) {
        int yEnd = (ty + TILE < H) ? ty + TILE : H;
        for (tx = 0; tx < W; tx += TILE) {
            int xEnd = (tx + TILE < W) ? tx + TILE : W;
            for (y = ty; y < yEnd; y++) {
                const uint32_t *s = (const uint32_t *) (srcBase + (size_t) y * srcStrideBytes);
                if (rotation == 90) {
                    for (x = tx; x < xEnd; x++) dst[(size_t) (W - 1 - x) * outW + y] = s[x];
                } else {
                    const int px = H - 1 - y;
                    for (x = tx; x < xEnd; x++) dst[(size_t) x * outW + px] = s[x];
                }
            }
        }
    }
}

/*
 * byte[] nEncode(Object bitmap, int rotation, int padWidth, int quality)
 * Returns null on any failure so the Java side can fall back to Skia.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_hkg_turzxmonitor_TurzxMonitorMain_00024NativeJpeg_nEncode(
        JNIEnv *env, jclass clazz, jobject bitmap, jint rotation, jint padWidth, jint quality) {
    AndroidBitmapInfo info;
    void *pixels = NULL;
    jbyteArray out = NULL;
    size_t jpegSize = 0;
    int rc = -1;
    (void) clazz;

    if (bitmap == NULL) return NULL;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return NULL;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return NULL;
    if (info.width == 0 || info.height == 0) return NULL;
    if (quality < 1) quality = 1;
    if (quality > 100) quality = 100;

    pthread_mutex_lock(&g_lock);
    if (ensure_handle(quality) != 0) goto unlock;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == NULL) {
        goto unlock;
    }

    if (rotation == 90 || rotation == 270) {
        const int W = (int) info.width;
        const int H = (int) info.height;
        const int outW = (padWidth > H) ? padWidth : H;
        const int outH = W;
        if (ensure_rot_buf((size_t) outW * outH) == 0 && ensure_jpeg_buf(outW, outH) == 0) {
            rotate_into((const uint8_t *) pixels, info.stride, W, H, rotation, g_rot, outW);
            AndroidBitmap_unlockPixels(env, bitmap);
            pixels = NULL;
            jpegSize = g_jpegCap;
            rc = tj3Compress8(g_tj, (const unsigned char *) g_rot, outW, outW * 4, outH,
                              TJPF_RGBX, &g_jpeg, &jpegSize);
        }
    } else {
        if (ensure_jpeg_buf((int) info.width, (int) info.height) == 0) {
            jpegSize = g_jpegCap;
            rc = tj3Compress8(g_tj, (const unsigned char *) pixels, (int) info.width, (int) info.stride,
                              (int) info.height, TJPF_RGBX, &g_jpeg, &jpegSize);
        }
    }
    if (pixels != NULL) AndroidBitmap_unlockPixels(env, bitmap);

    if (rc == 0 && jpegSize > 0) {
        out = (*env)->NewByteArray(env, (jsize) jpegSize);
        if (out != NULL) {
            (*env)->SetByteArrayRegion(env, out, 0, (jsize) jpegSize, (const jbyte *) g_jpeg);
        }
    }
unlock:
    pthread_mutex_unlock(&g_lock);
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_hkg_turzxmonitor_TurzxMonitorMain_00024NativeJpeg_nInfo(JNIEnv *env, jclass clazz) {
    char buf[96];
    (void) clazz;
    strcpy(buf, "libjpeg-turbo " TURZXJPEG_TURBO_VERSION);
    strcat(buf, jsimd_can_rgb_ycc() ? " (NEON)" : " (scalar)");
    return (*env)->NewStringUTF(env, buf);
}
