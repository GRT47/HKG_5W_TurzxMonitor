#!/usr/bin/env python3
"""Render the Tesla-style HUD design (turzx_redesign_tesla.html) into Android renderer assets (v1.5.0).

Outputs (package_turzx_monitor/design_tesla/, bundled into the jar by build_package.py):
  tesla_base.png / tesla_base_720.png      static layer (cards, labels, every state OFF), 1920 x 462 / 720
  tesla_active.png / tesla_active_720.png  same layer with every state ON (rects are copied from it)
  tesla_layout_92.txt / tesla_layout_123.txt  text anchors + rects in panel pixels (TeslaHud reads them)
  tesla_lead.png                           lead car sprite (grey sedan)
  tesla_ego_off/tail/brake.png             ego car sprites (3D "2019 Kia e-Niro" by tonielpro520, CC BY 4.0)
  tesla_park.png                           parked car photo (front 3/4, chroma-keyed)

Needs Edge/Chrome (same as build_package.generate_hud_assets).
Usage: python package_turzx_monitor/tools/make_tesla_assets.py
"""
from __future__ import annotations

import base64
import json
import shutil
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

PKG = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PKG))
import build_package as bp  # noqa: E402

REPO = PKG.parent
HTML = REPO / "turzx_redesign_tesla.html"
OUT = PKG / "design_tesla"
COPIES = {
    "turzx_niro_rear_off.png": "tesla_ego_off.png",
    "turzx_niro_rear_tail.png": "tesla_ego_tail.png",
    "turzx_niro_rear_brake.png": "tesla_ego_brake.png",
    "turzx_niro_ev_park.png": "tesla_park.png",
}


NAVDY = REPO / "navdy-vehicle-sprites-export"
# (source folder, jar prefix, camera pitch) - car sprites rendered by the Navdy SDF tools (gen_assets.py /
# make_pitch_set.py), picked per car by the closest pitch to the real viewing elevation
NAVDY_SETS = (("sprites", "navdy_p25", 2.5), ("sprites_p5", "navdy_p5", 5.0), ("sprites_p8", "navdy_p8", 8.0))


def copy_navdy() -> None:
    """v1.5.4 traffic / ego car sprites + navdy_meta.txt (set pitch idx width height anchorX anchorY ppm)."""
    for old in OUT.glob("navdy_*"):
        old.unlink()
    lines = ["# set pitchDeg index width height anchorX anchorY pxPerMetre  (anchor = rear-centre ground point)"]
    for folder, prefix, pitch in NAVDY_SETS:
        src = NAVDY / folder
        for row in (src / "meta.txt").read_text(encoding="utf-8").splitlines():
            f = row.split()
            if not f or f[0] != "car":
                continue
            idx = int(f[1])
            for suffix in ("", "_lamp"):
                shutil.copy2(src / f"car_{idx:02d}{suffix}.png", OUT / f"{prefix}_car_{idx:02d}{suffix}.png")
            lines.append(f"{prefix} {pitch} {idx} {f[3]} {f[4]} {f[5]} {f[6]} {f[8]}")
    ego = NAVDY / "sprites_ego"
    shutil.copy2(ego / "ego_persp.png", OUT / "navdy_ego.png")
    shutil.copy2(ego / "ego_persp_lamp.png", OUT / "navdy_ego_lamp.png")
    w, h, ax, ay, ppm, dist, cam_h, pitch = (ego / "ego_persp_meta.txt").read_text(encoding="utf-8").splitlines()[1].split()
    lines.append(f"navdy_ego {pitch} 0 {w} {h} {ax} {ay} {ppm}")
    # v1.5.5 parked top view (front = up in the PNG; anchor = car centre)
    top = NAVDY / "sprites_top"
    for name in ("car", "head", "tail"):
        shutil.copy2(top / f"top_{name}.png", OUT / f"navdy_top_{name}.png")
    tw, th, tcx, tcy, tppm = (top / "top_meta.txt").read_text(encoding="utf-8").splitlines()[1].split()
    lines.append(f"navdy_top 90 0 {tw} {th} {tcx} {tcy} {tppm}")
    (OUT / "navdy_meta.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"navdy: {len(lines) - 1} sprites (ego camera {dist} m behind, {cam_h} m high)")


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    if "--navdy-only" in sys.argv:
        copy_navdy()
        return 0
    port = bp._get_free_port()
    profile = PKG / "build" / "tesla_profile"
    proc = subprocess.Popen([bp.find_browser(), "--headless=new", "--disable-gpu", "--no-first-run",
                             "--allow-file-access-from-files", f"--user-data-dir={profile}",
                             f"--remote-debugging-port={port}", "about:blank"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        ws = None
        for _ in range(60):
            time.sleep(0.5)
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/json", timeout=2) as r:
                    for t in json.loads(r.read().decode()):
                        if t.get("type") == "page" and "webSocketDebuggerUrl" in t:
                            ws = t["webSocketDebuggerUrl"]
            except Exception:
                pass
            if ws:
                break
        if not ws:
            raise RuntimeError("headless browser did not expose a DevTools page")
        cdp = bp._CdpClient(ws)
        cdp.call("Page.enable")
        cdp.call("Emulation.setDeviceMetricsOverride", {"width": 1200, "height": 800, "deviceScaleFactor": 2, "mobile": False})
        for model, dp_h, suffix in (("92", 231, ""), ("123", 360, "_720")):
            for cap in ("base", "active"):
                cdp.call("Page.navigate", {"url": HTML.as_uri() + f"?shot={model}&scene=drive&cap={cap}"})
                time.sleep(2.4)
                png = bp._capture_png(cdp, {"x": 0, "y": 0, "width": 960, "height": dp_h, "scale": 1}, omit_bg=False)
                (OUT / f"tesla_{cap}{suffix}.png").write_bytes(png)
                if cap == "base":
                    lay = cdp.call("Runtime.evaluate", {"expression": "window.__exportLayout()", "returnByValue": True})
                    text = lay["result"]["value"]
                    (OUT / f"tesla_layout_{model}.txt").write_text(text + "\n", encoding="utf-8")
                    print(f"{model}: layout {len(text.splitlines())} lines")
        # Lead car sprite from the mockup's SVG model
        js = """(async () => {
            const img = new Image();
            img.src = "data:image/svg+xml;charset=utf-8," + encodeURIComponent(window.__leadSvg());
            await img.decode();
            const c = document.createElement("canvas"); c.width = 400; c.height = 320;
            c.getContext("2d").drawImage(img, 0, 0, 400, 320);
            return c.toDataURL("image/png").split(",")[1];
        })()"""
        r = cdp.call("Runtime.evaluate", {"expression": js, "awaitPromise": True, "returnByValue": True})
        (OUT / "tesla_lead.png").write_bytes(base64.b64decode(r["result"]["value"]))
    finally:
        proc.kill()
        shutil.rmtree(profile, ignore_errors=True)
    for src, dst in COPIES.items():
        shutil.copy2(REPO / src, OUT / dst)
    copy_navdy()
    for f in sorted(OUT.iterdir()):
        print(f"  {f.name:28} {f.stat().st_size:>8} B")
    return 0


if __name__ == "__main__":
    sys.exit(main())
