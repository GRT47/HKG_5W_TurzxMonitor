/*
 * turzx_inc.c — incremental ("dirty MCU row") JPEG encoder core for the TURZX 9.2" 464x1920 portrait panel.
 *
 * Pure C, no JNI: included by turzxjpeg.c and compiled on the host by native/test/test_inc.c.
 *
 * Idea (v1.4.5): the portrait frame is encoded as baseline 4:2:0 with a restart marker after every MCU row
 * (DRI = outW/16 MCUs, panel support verified on the car with the [RST] test). After a restart marker the DC
 * predictors are reset and the bit buffer is byte-aligned, so the entropy-coded bytes of each 16-pixel MCU row are
 * self-contained. The encoder keeps the bytes of every row from previous frames, compares the new landscape
 * frame with the last encoded one per 16-column strip (= one portrait MCU row after rotation) and re-encodes only
 * the rows that changed. The output is byte-identical to encoding the whole frame with TJPARAM_RESTARTROWS=1.
 *
 * Rotation semantics are those of rotate_into() in turzxjpeg.c:
 *   rotation == 270 : dst(px = H - 1 - y, py = x)
 *   rotation == 90  : dst(px = y,         py = W - 1 - x)
 * Columns [H, outW) of the portrait frame are opaque black padding.
 */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "turbojpeg.h"

#define TZ_MAX_ROWS 256
#define TZ_TILE 32
#define TZ_MERGE_GAP 2      /* clean rows between two dirty runs that are re-encoded anyway to save a tj call */
#define TZ_HDR_MAX 4096

typedef struct {
    tjhandle tj;
    int tjQuality;
    int valid;                      /* row cache + prev frame describe the last output */
    int quality, rotation, W, H, outW;
    uint8_t *prev;                  /* last encoded landscape pixels, W*H*4 packed */
    size_t prevCap;
    uint32_t *rot;                  /* portrait staging buffer for one run */
    size_t rotCap;                  /* pixels */
    unsigned char *jpeg;            /* tj output for one run */
    size_t jpegCap;
    uint8_t hdr[TZ_HDR_MAX];        /* SOI .. end of SOS of the full frame (SOF height patched) */
    size_t hdrLen;
    uint8_t *seg[TZ_MAX_ROWS];      /* entropy-coded bytes of each MCU row (no RST marker) */
    size_t segLen[TZ_MAX_ROWS];
    size_t segCap[TZ_MAX_ROWS];
    uint8_t *out;                   /* assembled frame */
    size_t outCap;
} tz_inc_t;

/* Rotates landscape columns [x0, x1) into portrait rows starting at pyBase (dst row 0 == portrait row pyBase). */
static void tz_rotate_cols(const uint8_t *src, size_t stride, int W, int H, int rotation,
                           int x0, int x1, uint32_t *dst, int outW, int pyBase) {
    int ty, tx, y, x;
    const int nRows = x1 - x0;
    if (outW > H) {
        for (y = 0; y < nRows; y++) {
            uint32_t *row = dst + (size_t) y * outW;
            for (x = H; x < outW; x++) row[x] = 0xFF000000u;
        }
    }
    for (ty = 0; ty < H; ty += TZ_TILE) {
        const int yEnd = (ty + TZ_TILE < H) ? ty + TZ_TILE : H;
        for (tx = x0; tx < x1; tx += TZ_TILE) {
            const int xEnd = (tx + TZ_TILE < x1) ? tx + TZ_TILE : x1;
            for (y = ty; y < yEnd; y++) {
                const uint32_t *s = (const uint32_t *) (src + (size_t) y * stride);
                if (rotation == 90) {
                    for (x = tx; x < xEnd; x++) dst[(size_t) (W - 1 - x - pyBase) * outW + y] = s[x];
                } else {
                    const int px = H - 1 - y;
                    for (x = tx; x < xEnd; x++) dst[(size_t) (x - pyBase) * outW + px] = s[x];
                }
            }
        }
    }
}

/*
 * Walks the marker segments of a baseline JPEG. Returns the offset of the first entropy-coded byte (just past
 * the SOS segment), or -1. *sofHeightOff = offset of the 2-byte frame height in SOF0, *dri = restart interval.
 */
static long tz_parse_header(const uint8_t *j, size_t n, long *sofHeightOff, int *dri) {
    size_t pos = 2;
    *sofHeightOff = -1;
    *dri = 0;
    if (n < 4 || j[0] != 0xFF || j[1] != 0xD8) return -1;
    while (pos + 4 <= n) {
        uint8_t m;
        size_t len;
        if (j[pos] != 0xFF) return -1;
        m = j[pos + 1];
        if (m == 0xFF) {        /* fill byte */
            pos++;
            continue;
        }
        len = ((size_t) j[pos + 2] << 8) | j[pos + 3];
        if (len < 2 || pos + 2 + len > n) return -1;
        if (m == 0xC0) {
            if (len < 8) return -1;
            *sofHeightOff = (long) pos + 5;
        } else if (m >= 0xC1 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
            return -1;          /* not baseline */
        } else if (m == 0xDD) {
            if (len < 4) return -1;
            *dri = (j[pos + 4] << 8) | j[pos + 5];
        }
        pos += 2 + len;
        if (m == 0xDA) return (*sofHeightOff >= 0) ? (long) pos : -1;
    }
    return -1;
}

static int tz_store_seg(tz_inc_t *st, int row, const uint8_t *p, size_t len) {
    if (len > st->segCap[row]) {
        uint8_t *q = (uint8_t *) realloc(st->seg[row], len);
        if (q == NULL) return -1;
        st->seg[row] = q;
        st->segCap[row] = len;
    }
    memcpy(st->seg[row], p, len);
    st->segLen[row] = len;
    return 0;
}

/* Splits the entropy-coded data of one run JPEG at its RST markers into the row cache [row0, row0 + nRows). */
static int tz_split_rows(tz_inc_t *st, const uint8_t *j, size_t n, size_t es, int row0, int nRows) {
    size_t i, start = es, end;
    int row = row0;
    if (n < es + 2 || j[n - 2] != 0xFF || j[n - 1] != 0xD9) return -1;
    end = n - 2;
    for (i = es; i + 1 < end; i++) {
        uint8_t nx;
        if (j[i] != 0xFF) continue;
        nx = j[i + 1];
        if (nx == 0x00) {       /* stuffed 0xFF data byte */
            i++;
            continue;
        }
        if (nx < 0xD0 || nx > 0xD7) return -1;
        if (row >= row0 + nRows - 1) return -1;
        if (tz_store_seg(st, row, j + start, i - start) != 0) return -1;
        row++;
        start = i + 2;
        i++;
    }
    if (row != row0 + nRows - 1) return -1;
    return tz_store_seg(st, row, j + start, end - start);
}

static int tz_ensure_tj(tz_inc_t *st, int quality) {
    if (st->tj == NULL) {
        st->tj = tj3Init(TJINIT_COMPRESS);
        if (st->tj == NULL) return -1;
        if (tj3Set(st->tj, TJPARAM_SUBSAMP, TJSAMP_420) < 0) return -1;
        if (tj3Set(st->tj, TJPARAM_FASTDCT, 1) < 0) return -1;
        if (tj3Set(st->tj, TJPARAM_OPTIMIZE, 0) < 0) return -1;   /* standard Huffman tables: same in every run */
        if (tj3Set(st->tj, TJPARAM_RESTARTROWS, 1) < 0) return -1;
        if (tj3Set(st->tj, TJPARAM_NOREALLOC, 1) < 0) return -1;
        st->tjQuality = -1;
    }
    if (quality != st->tjQuality) {
        if (tj3Set(st->tj, TJPARAM_QUALITY, quality) < 0) return -1;
        st->tjQuality = quality;
    }
    return 0;
}

/*
 * Encodes the landscape RGBA frame (W x H, W a multiple of 16) as a rotated outW x W portrait JPEG, re-encoding
 * only the MCU rows whose 16-column landscape strip changed since the previous call.
 * On success returns 0 and points *outJpeg at st->out (valid until the next call).
 * stats (may be NULL): [0] = MCU rows re-encoded, [1] = tj compress calls, [2] = total MCU rows.
 * On failure the cache is invalidated (next call encodes the full frame) and -1 is returned.
 */
static int tz_inc_encode(tz_inc_t *st, const uint8_t *px, size_t stride, int W, int H, int rotation,
                         int padWidth, int quality, int forceFull,
                         const uint8_t **outJpeg, size_t *outLen, int *stats) {
    const int outW = (padWidth > H) ? padWidth : H;
    const int rows = W / 16;
    const size_t rowBytes = (size_t) W * 4;
    uint8_t dirty[TZ_MAX_ROWS];
    int full, r, y, k, encodedRows = 0, runs = 0;
    size_t total, pos;

    if (px == NULL || W <= 0 || H <= 0 || (W % 16) != 0 || (outW % 16) != 0 || rows > TZ_MAX_ROWS) return -1;
    if (rotation != 90 && rotation != 270) return -1;
    if (stride < rowBytes) return -1;
    if (quality < 1) quality = 1;
    if (quality > 100) quality = 100;

    if (tz_ensure_tj(st, quality) != 0) goto fail;

    full = forceFull || !st->valid || st->quality != quality || st->rotation != rotation
           || st->W != W || st->H != H || st->outW != outW || st->hdrLen == 0;

    /* Buffers: previous frame, one full-height staging buffer, worst-case JPEG for a full frame */
    if (st->prevCap < rowBytes * (size_t) H) {
        uint8_t *p = (uint8_t *) realloc(st->prev, rowBytes * (size_t) H);
        if (p == NULL) goto fail;
        st->prev = p;
        st->prevCap = rowBytes * (size_t) H;
        full = 1;
    }
    if (st->rotCap < (size_t) outW * W) {
        uint32_t *p = (uint32_t *) realloc(st->rot, (size_t) outW * W * sizeof(uint32_t));
        if (p == NULL) goto fail;
        st->rot = p;
        st->rotCap = (size_t) outW * W;
    }
    {
        size_t need = tj3JPEGBufSize(outW, W, TJSAMP_420);
        if (need == 0) goto fail;
        if (need > st->jpegCap) {
            if (st->jpeg != NULL) tj3Free(st->jpeg);
            st->jpeg = (unsigned char *) tj3Alloc(need);
            if (st->jpeg == NULL) {
                st->jpegCap = 0;
                goto fail;
            }
            st->jpegCap = need;
        }
    }

    /* 1. Which 16-column landscape strips changed (strip k <-> portrait MCU row r) */
    if (full) {
        memset(dirty, 1, (size_t) rows);
        for (y = 0; y < H; y++) memcpy(st->prev + (size_t) y * rowBytes, px + (size_t) y * stride, rowBytes);
    } else {
        uint8_t strip[TZ_MAX_ROWS];
        memset(strip, 0, (size_t) rows);
        for (y = 0; y < H; y++) {
            const uint8_t *s = px + (size_t) y * stride;
            const uint8_t *p = st->prev + (size_t) y * rowBytes;
            if (memcmp(s, p, rowBytes) == 0) continue;
            for (k = 0; k < rows; k++) {
                if (!strip[k] && memcmp(s + (size_t) k * 64, p + (size_t) k * 64, 64) != 0) strip[k] = 1;
            }
        }
        for (y = 0; y < H; y++) {
            const uint8_t *s = px + (size_t) y * stride;
            uint8_t *p = st->prev + (size_t) y * rowBytes;
            for (k = 0; k < rows; k++) {
                if (strip[k]) memcpy(p + (size_t) k * 64, s + (size_t) k * 64, 64);
            }
        }
        for (k = 0; k < rows; k++) dirty[(rotation == 90) ? rows - 1 - k : k] = strip[k];
    }
    /* The previous-frame copy now matches px; if anything below fails the cache is invalidated. */
    st->valid = 0;

    /* 2. Re-encode dirty runs (portrait MCU rows ra..rb, short clean gaps merged) */
    for (r = 0; r < rows;) {
        int ra, rb, q, nPix, x0, x1;
        size_t jpegSize;
        long es, sofOff;
        int dri;
        if (!dirty[r]) {
            r++;
            continue;
        }
        ra = r;
        rb = r;
        for (q = r + 1; q < rows && q - rb - 1 <= TZ_MERGE_GAP; q++) {
            if (dirty[q]) rb = q;
        }
        nPix = (rb - ra + 1) * 16;
        if (rotation == 90) {
            x0 = W - 16 * (rb + 1);
            x1 = W - 16 * ra;
        } else {
            x0 = 16 * ra;
            x1 = 16 * (rb + 1);
        }
        tz_rotate_cols(px, stride, W, H, rotation, x0, x1, st->rot, outW, 16 * ra);
        jpegSize = st->jpegCap;
        if (tj3Compress8(st->tj, (const unsigned char *) st->rot, outW, outW * 4, nPix,
                         TJPF_RGBX, &st->jpeg, &jpegSize) != 0) goto fail;
        es = tz_parse_header(st->jpeg, jpegSize, &sofOff, &dri);
        if (es < 0 || dri != outW / 16) goto fail;
        if (full && ra == 0) {
            /* Header of the full frame = header of this run with the frame height patched to W */
            if ((size_t) es > sizeof(st->hdr)) goto fail;
            memcpy(st->hdr, st->jpeg, (size_t) es);
            st->hdr[sofOff] = (uint8_t) ((W >> 8) & 0xFF);
            st->hdr[sofOff + 1] = (uint8_t) (W & 0xFF);
            st->hdrLen = (size_t) es;
        }
        if (tz_split_rows(st, st->jpeg, jpegSize, (size_t) es, ra, rb - ra + 1) != 0) goto fail;
        encodedRows += rb - ra + 1;
        runs++;
        r = rb + 1;
    }

    /* 3. Assemble: header + row 0 + RST0 + row 1 + RST1 ... + row N-1 + EOI */
    total = st->hdrLen + 2 * (size_t) (rows - 1) + 2;
    for (r = 0; r < rows; r++) {
        if (st->seg[r] == NULL || st->segLen[r] == 0) goto fail;
        total += st->segLen[r];
    }
    if (total > st->outCap) {
        uint8_t *p = (uint8_t *) realloc(st->out, total);
        if (p == NULL) goto fail;
        st->out = p;
        st->outCap = total;
    }
    memcpy(st->out, st->hdr, st->hdrLen);
    pos = st->hdrLen;
    for (r = 0; r < rows; r++) {
        memcpy(st->out + pos, st->seg[r], st->segLen[r]);
        pos += st->segLen[r];
        if (r < rows - 1) {
            st->out[pos++] = 0xFF;
            st->out[pos++] = (uint8_t) (0xD0 + (r & 7));
        }
    }
    st->out[pos++] = 0xFF;
    st->out[pos++] = 0xD9;

    st->valid = 1;
    st->quality = quality;
    st->rotation = rotation;
    st->W = W;
    st->H = H;
    st->outW = outW;
    *outJpeg = st->out;
    *outLen = pos;
    if (stats != NULL) {
        stats[0] = encodedRows;
        stats[1] = runs;
        stats[2] = rows;
    }
    return 0;

fail:
    st->valid = 0;
    return -1;
}
