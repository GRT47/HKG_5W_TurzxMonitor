#!/usr/bin/env python3
"""Generate the TURZX panel JPEG restart-marker (DRI/RSTn) test frames bundled into the jar (v1.4.4).

Background: a "re-encode only the changed strips" encoder needs restart markers so each 16-row MCU strip of the
464x1920 portrait frame is independently decodable. Before building that, the panel's hardware JPEG decoder must be
shown to honour DRI/RST markers. The daemon's dashboard button [RST] shows these three frames for 6s each:

  A  panel_test_a_base.jpg     baseline 4:2:0, no restart markers (control - same as today's frames)
  B  panel_test_b_rst_row.jpg  restart interval = 1 MCU row (29 MCUs) - the layout the strip cache would emit
  C  panel_test_c_rst_mcu.jpg  restart interval = 1 MCU (extreme case)

All three decode to identical pixels on a conforming decoder; a decoder that ignores RST markers shows B/C as
smeared/garbled after the first strip (or the panel stops ACKing).

The frames are committed under panel_test/ so builds do not need Pillow; rerun this script to regenerate:
    python package_turzx_monitor/tools/make_panel_test_jpegs.py
"""
from __future__ import annotations

import io
import os
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

OUT_DIR = Path(__file__).resolve().parent.parent / "panel_test"
LAND_W, LAND_H = 1920, 462   # HUD landscape frame
PANEL_W = 464                # macroblock-aligned portrait width (29 x 16)
QUALITY = 60

VARIANTS = [
    ("panel_test_a_base.jpg", "A", "기준 · RST 없음", {}),
    ("panel_test_b_rst_row.jpg", "B", "RST 1행(29블록)마다", {"restart_marker_rows": 1}),
    ("panel_test_c_rst_mcu.jpg", "C", "RST 1블록마다", {"restart_marker_blocks": 1}),
]


def find_font(size: int) -> ImageFont.ImageFont:
    for cand in (r"C:\Windows\Fonts\malgunbd.ttf", r"C:\Windows\Fonts\malgun.ttf",
                 "/usr/share/fonts/truetype/nanum/NanumGothicBold.ttf"):
        if os.path.isfile(cand):
            return ImageFont.truetype(cand, size)
    return ImageFont.load_default()


def landscape_pattern(letter: str, caption: str) -> Image.Image:
    img = Image.new("RGB", (LAND_W, LAND_H), (14, 17, 24))
    d = ImageDraw.Draw(img)
    # Colour bars across the full width: every MCU strip of the rotated frame crosses several colours
    bars = [(229, 86, 111), (217, 164, 65), (68, 162, 130), (74, 138, 214), (167, 139, 250), (240, 240, 240)]
    bw = LAND_W // len(bars)
    for i, c in enumerate(bars):
        d.rectangle([i * bw, 0, (i + 1) * bw - 1, 70], fill=c)
    # Horizontal grey ramp + fine grid (makes DC drift / misaligned strips obvious)
    for x in range(LAND_W):
        g = int(255 * x / (LAND_W - 1))
        d.line([(x, LAND_H - 60), (x, LAND_H - 1)], fill=(g, g, g))
    for x in range(0, LAND_W, 32):
        d.line([(x, 80), (x, LAND_H - 70)], fill=(40, 46, 60))
    for y in range(80, LAND_H - 60, 32):
        d.line([(0, y), (LAND_W, y)], fill=(40, 46, 60))
    # Checkerboard block (high-frequency content)
    for yy in range(0, 160, 16):
        for xx in range(0, 320, 16):
            if ((xx // 16) + (yy // 16)) % 2 == 0:
                d.rectangle([1560 + xx, 150 + yy, 1560 + xx + 15, 150 + yy + 15], fill=(250, 250, 250))
    big = find_font(190)
    mid = find_font(52)
    small = find_font(34)
    d.text((60, 105), letter, font=big, fill=(255, 255, 255))
    d.text((300, 120), "TURZX 패널 디코더 테스트", font=mid, fill=(255, 255, 255))
    d.text((300, 200), caption, font=mid, fill=(34, 211, 238))
    d.text((300, 290), "A·B·C 세 화면이 모두 똑같이 깨끗하면 = RST 지원", font=small, fill=(203, 213, 225))
    d.text((300, 340), "B/C가 번지거나 깨지거나 멈추면 = 미지원", font=small, fill=(203, 213, 225))
    return img


def to_panel(img: Image.Image) -> Image.Image:
    # Same mapping as the native encoder with usb_rotation=270: dst(px = H-1-y, py = x), black pad columns
    rot = img.transpose(Image.Transpose.ROTATE_270)  # 462 x 1920
    panel = Image.new("RGB", (PANEL_W, LAND_W), (0, 0, 0))
    panel.paste(rot, (0, 0))
    return panel


def markers(data: bytes) -> tuple[int, int]:
    """(restart interval from DRI or 0, number of RSTn markers)."""
    dri = 0
    i = data.find(b"\xff\xdd")
    if i >= 0:
        dri = int.from_bytes(data[i + 4:i + 6], "big")
    sos = data.find(b"\xff\xda")
    body = data[sos:]
    rst = sum(body.count(bytes([0xFF, 0xD0 + k])) for k in range(8))
    return dri, rst


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    ref_pixels = None
    for fname, letter, caption, opts in VARIANTS:
        panel = to_panel(landscape_pattern(letter, caption))
        buf = io.BytesIO()
        panel.save(buf, "JPEG", quality=QUALITY, subsampling=2, optimize=False, progressive=False, **opts)
        data = buf.getvalue()
        (OUT_DIR / fname).write_bytes(data)
        dri, rst = markers(data)
        decoded = Image.open(io.BytesIO(data))
        decoded.load()
        assert decoded.size == (PANEL_W, LAND_W), decoded.size
        print(f"{fname}: {len(data)} B, DRI={dri}, RST markers={rst}")
        if letter == "A":
            assert dri == 0 and rst == 0
            ref_pixels = decoded
        elif letter == "B":
            assert dri == PANEL_W // 16 and rst == LAND_W // 16 - 1, (dri, rst)
        else:
            assert dri == 1 and rst == (PANEL_W // 16) * (LAND_W // 16) - 1, (dri, rst)
    _ = ref_pixels


if __name__ == "__main__":
    main()
