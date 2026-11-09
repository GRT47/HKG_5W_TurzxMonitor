/*
 * Host test for turzx_inc.c (run by native/test/run_host_test.py):
 * the incremental encoder must produce exactly the bytes of a full-frame encode with TJPARAM_RESTARTROWS=1,
 * across rotations, partial changes, no-change frames, quality changes and forced refreshes,
 * and the result must decode to the same pixels as a plain (no restart marker) encode.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "../turzx_inc.c"

#define W 1920
#define H 462
#define PAD 464

static int failures = 0;

static void check(int cond, const char *msg) {
    if (!cond) {
        printf("FAIL: %s\n", msg);
        failures++;
    }
}

static void fill_frame(uint8_t *px, size_t stride, unsigned seed) {
    int x, y;
    for (y = 0; y < H; y++) {
        uint8_t *row = px + (size_t) y * stride;
        for (x = 0; x < W; x++) {
            unsigned v = (unsigned) (x * 7 + y * 13) ^ (seed * 2654435761u);
            row[x * 4 + 0] = (uint8_t) (x * 255 / W);
            row[x * 4 + 1] = (uint8_t) ((y * 3 + (v >> 5)) & 0xFF);
            row[x * 4 + 2] = (uint8_t) (((x / 37) * 40 + (y / 23) * 17) & 0xFF);
            row[x * 4 + 3] = 0xFF;
        }
    }
}

static void paint_rect(uint8_t *px, size_t stride, int x0, int x1, int y0, int y1, uint32_t c) {
    int x, y;
    for (y = y0; y < y1; y++) {
        for (x = x0; x < x1; x++) memcpy(px + (size_t) y * stride + x * 4, &c, 4);
    }
}

/* Reference: whole frame rotated + one tj3Compress8 call with the given restart rows (0 = none). */
static unsigned char *ref_encode(const uint8_t *px, size_t stride, int rotation, int quality, int restartRows,
                                 size_t *outLen) {
    static uint32_t rot[PAD * W];
    tjhandle tj = tj3Init(TJINIT_COMPRESS);
    unsigned char *buf = NULL;
    size_t sz = 0;
    tj3Set(tj, TJPARAM_SUBSAMP, TJSAMP_420);
    tj3Set(tj, TJPARAM_FASTDCT, 1);
    tj3Set(tj, TJPARAM_QUALITY, quality);
    tj3Set(tj, TJPARAM_RESTARTROWS, restartRows);
    tz_rotate_cols(px, stride, W, H, rotation, 0, W, rot, PAD, 0);
    if (tj3Compress8(tj, (const unsigned char *) rot, PAD, PAD * 4, W, TJPF_RGBX, &buf, &sz) != 0) {
        printf("ref encode failed: %s\n", tj3GetErrorStr(tj));
        exit(2);
    }
    tj3Destroy(tj);
    *outLen = sz;
    return buf;
}

static unsigned char *decode(const unsigned char *j, size_t n) {
    tjhandle tj = tj3Init(TJINIT_DECOMPRESS);
    unsigned char *pix;
    if (tj3DecompressHeader(tj, j, n) != 0) {
        tj3Destroy(tj);
        return NULL;
    }
    if (tj3Get(tj, TJPARAM_JPEGWIDTH) != PAD || tj3Get(tj, TJPARAM_JPEGHEIGHT) != W) {
        tj3Destroy(tj);
        return NULL;
    }
    pix = (unsigned char *) malloc((size_t) PAD * W * 3);
    if (tj3Decompress8(tj, j, n, pix, PAD * 3, TJPF_RGB) != 0) {
        printf("decode error: %s\n", tj3GetErrorStr(tj));
        free(pix);
        pix = NULL;
    }
    tj3Destroy(tj);
    return pix;
}

static void step(tz_inc_t *st, const uint8_t *px, size_t stride, int rotation, int quality, int forceFull,
                 int expectRows, const char *name) {
    const uint8_t *out = NULL;
    size_t outLen = 0, refLen = 0, plainLen = 0;
    int stats[3] = {-1, -1, -1};
    unsigned char *ref, *plain, *dA, *dB;
    char msg[256];
    int rc = tz_inc_encode(st, px, stride, W, H, rotation, PAD, quality, forceFull, &out, &outLen, stats);
    snprintf(msg, sizeof msg, "%s: encode rc=%d", name, rc);
    check(rc == 0, msg);
    if (rc != 0) return;
    ref = ref_encode(px, stride, rotation, quality, 1, &refLen);
    snprintf(msg, sizeof msg, "%s: bytes identical to full RESTARTROWS=1 encode (inc %zu vs ref %zu)", name, outLen, refLen);
    check(outLen == refLen && memcmp(out, ref, refLen) == 0, msg);
    if (expectRows >= 0) {
        snprintf(msg, sizeof msg, "%s: re-encoded rows %d (expected %d), runs %d", name, stats[0], expectRows, stats[1]);
        check(stats[0] == expectRows, msg);
    }
    plain = ref_encode(px, stride, rotation, quality, 0, &plainLen);
    dA = decode(out, outLen);
    dB = decode(plain, plainLen);
    snprintf(msg, sizeof msg, "%s: decodes to the same pixels as a plain encode", name);
    check(dA != NULL && dB != NULL && memcmp(dA, dB, (size_t) PAD * W * 3) == 0, msg);
    printf("%-34s rows %3d/%d runs %2d  %6zu B\n", name, stats[0], stats[2], stats[1], outLen);
    free(dA);
    free(dB);
    tj3Free(ref);
    tj3Free(plain);
}

int main(void) {
    const size_t stride = (size_t) W * 4 + 64;  /* padded stride like some Android bitmaps */
    uint8_t *px = (uint8_t *) calloc((size_t) H, stride);
    tz_inc_t st;
    memset(&st, 0, sizeof st);

    fill_frame(px, stride, 1);
    step(&st, px, stride, 270, 60, 0, 120, "270 first frame (full)");
    step(&st, px, stride, 270, 60, 0, 0, "270 unchanged");
    paint_rect(px, stride, 100, 131, 50, 90, 0xFF2040FFu);       /* strips 6..8 */
    step(&st, px, stride, 270, 60, 0, 3, "270 small change");
    paint_rect(px, stride, 610, 1188, 14, 448, 0xFF101820u);     /* road column: strips 38..74 */
    paint_rect(px, stride, 1500, 1503, 300, 301, 0xFFFFFFFFu);   /* strip 93 */
    paint_rect(px, stride, 1530, 1531, 10, 11, 0xFF00FF00u);     /* strip 95 (gap 1 -> merged) */
    step(&st, px, stride, 270, 60, 0, 37 + 3, "270 road + far changes");
    paint_rect(px, stride, 0, 1, 0, 1, 0xFF000000u);             /* strip 0 */
    paint_rect(px, stride, 1919, 1920, 461, 462, 0xFF0000FFu);   /* strip 119 */
    step(&st, px, stride, 270, 60, 0, 2, "270 first/last strip");
    step(&st, px, stride, 270, 75, 0, 120, "270 quality change -> full");
    step(&st, px, stride, 270, 75, 1, 120, "270 forced full");
    fill_frame(px, stride, 7);
    step(&st, px, stride, 270, 75, 0, -1, "270 whole new frame");

    fill_frame(px, stride, 3);
    step(&st, px, stride, 90, 50, 0, 120, "90 rotation change -> full");
    paint_rect(px, stride, 100, 131, 50, 90, 0xFF2040FFu);
    step(&st, px, stride, 90, 50, 0, 3, "90 small change");
    paint_rect(px, stride, 610, 1188, 14, 448, 0xFF101820u);
    step(&st, px, stride, 90, 50, 0, 37, "90 road column");

    free(px);
    if (failures) {
        printf("%d FAILURE(S)\n", failures);
        return 1;
    }
    printf("ALL OK\n");
    return 0;
}
