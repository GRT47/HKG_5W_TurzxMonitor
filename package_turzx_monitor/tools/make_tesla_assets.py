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


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    port = bp._get_free_port()
    profile = PKG / "build" / "tesla_profile"
    proc = subprocess.Popen([bp.find_browser(), "--headless=new", "--disable-gpu", "--no-first-run",
                             "--no-default-browser-check", f"--user-data-dir={profile}",
                             f"--remote-debugging-port={port}", "--window-size=1920,1080", "about:blank"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        ws = None
        for _ in range(60):
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/json", timeout=1) as r:
                    for t in json.loads(r.read().decode()):
                        if t.get("type") == "page" and "webSocketDebuggerUrl" in t:
                            ws = t["webSocketDebuggerUrl"]
                if ws:
                    break
            except Exception:
                time.sleep(0.25)
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
    for f in sorted(OUT.iterdir()):
        print(f"  {f.name:28} {f.stat().st_size:>8} B")
    return 0


if __name__ == "__main__":
    sys.exit(main())
