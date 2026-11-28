#!/usr/bin/env python3
"""Build NEMESIS install package, DEX jar, and USB bundle for HKG_5W_TurzxMonitor."""
from __future__ import annotations

import base64
import hashlib
import json
import math
import os
import re
import shutil
import socket
import struct
import subprocess
import time
import urllib.request
import zipfile
import zlib
from pathlib import Path
from urllib.parse import urlparse

BASE_DIR = Path(__file__).resolve().parent
REPO_ROOT = BASE_DIR.parent
BUILD_DIR = BASE_DIR / "build"
CLASSES_DIR = BUILD_DIR / "classes"
ASSETS_DIR = BUILD_DIR / "assets"
DEX_DIR = BUILD_DIR / "dex"
SHORTCUT_CLASSES_DIR = BUILD_DIR / "shortcut_classes"
SHORTCUT_DEX_DIR = BUILD_DIR / "shortcut_dex"
PKG_STAGE_DIR = BUILD_DIR / "pkg_stage"
USB_ROOT_DIR = BUILD_DIR / "usb_root"

OUTER_ZIP_PATH = REPO_ROOT / "HKG_5W_TurzxMonitor_USB.zip"
MONITOR_VERSION = "1.5.18"

PUBLIC_SANS_FONTS = {
    "PublicSans-Bold.ttf": "https://fonts.gstatic.com/s/publicsans/v21/ijwGs572Xtc6ZYQws9YVwllKVG8qX1oyOymu8Z65ww.ttf",
    "PublicSans-Medium.ttf": "https://fonts.gstatic.com/s/publicsans/v21/ijwGs572Xtc6ZYQws9YVwllKVG8qX1oyOymuJJm5ww.ttf",
}


def find_javac() -> str:
    javac = shutil.which("javac")
    if javac:
        return javac
    candidates = [
        r"C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\javac.exe",
        r"C:\Program Files\Android\Android Studio\jbr\bin\javac.exe",
    ]
    for c in candidates:
        if os.path.exists(c):
            return c
    raise RuntimeError("javac not found in PATH or standard locations")


def find_java() -> str:
    java = shutil.which("java")
    if java:
        return java
    javac = find_javac()
    return str(Path(javac).with_name("java.exe" if os.name == "nt" else "java"))


def _version_key(path: Path) -> tuple[int, ...]:
    nums = re.findall(r"\d+", path.name)
    return tuple(int(n) for n in nums) if nums else (0,)


def find_sdk_roots() -> list[str]:
    return [
        os.environ.get("ANDROID_HOME", ""),
        os.environ.get("ANDROID_SDK_ROOT", ""),
        os.path.expanduser(r"~\AppData\Local\Android\Sdk"),
        "/usr/local/lib/android/sdk",
        "/usr/lib/android-sdk",
    ]


def find_build_tool(names: tuple[str, ...]) -> str:
    for n in names:
        hit = shutil.which(n)
        if hit:
            return hit
    for root in find_sdk_roots():
        if not root or not os.path.isdir(root):
            continue
        bt_dir = Path(root) / "build-tools"
        if bt_dir.is_dir():
            for ver_dir in sorted(bt_dir.iterdir(), key=_version_key, reverse=True):
                for n in names:
                    cand = ver_dir / n
                    if cand.is_file():
                        return str(cand)
    raise RuntimeError(f"Android SDK tool not found: {names}")


def find_d8() -> str:
    return find_build_tool(("d8.bat", "d8"))


def find_aapt() -> str:
    return find_build_tool(("aapt.exe", "aapt"))


def find_apksigner() -> str:
    return find_build_tool(("apksigner.bat", "apksigner"))


def find_android_jar() -> str:
    for root in find_sdk_roots():
        if not root or not os.path.isdir(root):
            continue
        plat_dir = Path(root) / "platforms"
        if plat_dir.is_dir():
            for ver_dir in sorted(plat_dir.iterdir(), key=_version_key, reverse=True):
                cand = ver_dir / "android.jar"
                if cand.is_file():
                    return str(cand)
    raise RuntimeError("Android SDK android.jar not found")


def find_keytool() -> str:
    kt = shutil.which("keytool")
    if kt:
        return kt
    javac = find_javac()
    cand = Path(javac).with_name("keytool.exe" if os.name == "nt" else "keytool")
    if cand.is_file():
        return str(cand)
    raise RuntimeError("keytool not found alongside javac")


def png_chunk(tag: bytes, payload: bytes) -> bytes:
    crc = zlib.crc32(tag + payload) & 0xFFFFFFFF
    return struct.pack(">I", len(payload)) + tag + payload + struct.pack(">I", crc)


def render_turzx_tile_icon_png(
    bg_top: tuple[int, int, int],
    bg_bot: tuple[int, int, int],
    border_col: tuple[int, int, int],
    bezel_col: tuple[int, int, int],
    screen_bg: tuple[int, int, int],
    arc_col: tuple[int, int, int],
    accent_col: tuple[int, int, int],
    needle_col: tuple[int, int, int],
    bar1_col: tuple[int, int, int],
    bar2_col: tuple[int, int, int],
    bar3_col: tuple[int, int, int],
) -> bytes:
    """Render a 99x99 RGBA PNG launcher tile matching Gen5W app-menu rounded-square proportions."""
    w, h = 99, 99
    cx, cy = 49.5, 49.5
    bx, by, br = 43.5, 43.5, 22.0

    # Ultrawide cluster screen center + half extents
    scx, scy = 49.5, 47.5
    sbx, sby, sbr = 27.5, 13.5, 5.2

    # Mini speedometer on left side of screen
    gcx, gcy = 36.8, 48.2
    ang_rad = math.radians(-35.0)
    nx0, ny0 = gcx, gcy
    nx1, ny1 = gcx + math.cos(ang_rad) * 6.0, gcy + math.sin(ang_rad) * 6.0
    ndx, ndy = nx1 - nx0, ny1 - ny0
    nlen2 = ndx * ndx + ndy * ndy

    def rr_dist(px: float, py: float, rcx: float, rcy: float, hx: float, hy: float, r: float) -> float:
        dx = max(abs(px - rcx) - (hx - r), 0.0)
        dy = max(abs(py - rcy) - (hy - r), 0.0)
        return math.hypot(dx, dy) + min(max(abs(px - rcx) - (hx - r), abs(py - rcy) - (hy - r)), 0.0) - r

    def sample(px: float, py: float) -> tuple[int, int, int, int]:
        dist = rr_dist(px, py, cx, cy, bx, by, br)
        if dist > 0.0:
            return (0, 0, 0, 0)

        t = max(0.0, min(1.0, (py - 6.0) / 87.0))
        r = int(bg_top[0] + (bg_bot[0] - bg_top[0]) * t)
        g = int(bg_top[1] + (bg_bot[1] - bg_top[1]) * t)
        b = int(bg_top[2] + (bg_bot[2] - bg_top[2]) * t)

        if dist >= -2.4:
            r, g, b = border_col

        # Stand neck & base foot under ultrawide cluster housing
        if (abs(px - scx) <= 3.2 and 60.5 <= py <= 64.2) or (
            rr_dist(px, py, scx, 65.5, 12.0, 2.0, 1.8) <= 0.0
        ):
            r, g, b = bezel_col

        # Ultrawide cluster screen frame + dark glass interior
        sdist = rr_dist(px, py, scx, scy, sbx, sby, sbr)
        if sdist <= 0.0:
            if sdist >= -2.2:
                r, g, b = bezel_col
            else:
                r, g, b = screen_bg

                # Mini digital speed gauge (left)
                gdx, gdy = px - gcx, py - gcy
                gr = math.hypot(gdx, gdy)
                if 4.5 <= gr <= 7.0:
                    deg = math.degrees(math.atan2(gdy, gdx))
                    if deg < 40.0:
                        deg += 360.0
                    if 135.0 <= deg <= 400.0:
                        r, g, b = accent_col if deg >= 315.0 else arc_col

                proj = max(0.0, min(1.0, ((px - nx0) * ndx + (py - ny0) * ndy) / nlen2))
                ndist = math.hypot(px - (nx0 + proj * ndx), py - (ny0 + proj * ndy))
                if ndist <= 1.25 or gr <= 2.0:
                    r, g, b = needle_col

                # Right telemetry bars (SOC / power / drive strips)
                if 48.0 <= px <= 70.5 and 41.2 <= py <= 44.2:
                    r, g, b = bar1_col
                elif 48.0 <= px <= 65.5 and 46.2 <= py <= 49.0:
                    r, g, b = bar2_col
                elif 48.0 <= px <= 68.5 and 51.0 <= py <= 53.8:
                    r, g, b = bar3_col

        return (r, g, b, 255)

    rows = bytearray()
    offsets = (0.25, 0.75)
    for y in range(h):
        rows.append(0)
        for x in range(w):
            sr = sg = sb = sa = 0
            for ox in offsets:
                for oy in offsets:
                    cr, cg, cb, ca = sample(x + ox, y + oy)
                    sr += cr
                    sg += cg
                    sb += cb
                    sa += ca
            rows.extend((sr >> 2, sg >> 2, sb >> 2, sa >> 2))

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + png_chunk(b"IHDR", ihdr)
        + png_chunk(b"IDAT", zlib.compress(bytes(rows), 9))
        + png_chunk(b"IEND", b"")
    )


TILE_ICON_PALETTES = {
    "icon.png": {
        "bg_top": (28, 42, 66),
        "bg_bot": (14, 22, 38),
        "border_col": (56, 189, 248),
        "bezel_col": (94, 234, 212),
        "screen_bg": (11, 18, 32),
        "arc_col": (56, 189, 248),
        "accent_col": (52, 211, 153),
        "needle_col": (248, 250, 252),
        "bar1_col": (52, 211, 153),
        "bar2_col": (56, 189, 248),
        "bar3_col": (251, 191, 36),
    },
    "icon_night.png": {
        "bg_top": (14, 24, 42),
        "bg_bot": (8, 13, 24),
        "border_col": (20, 184, 166),
        "bezel_col": (45, 212, 191),
        "screen_bg": (7, 12, 22),
        "arc_col": (56, 189, 248),
        "accent_col": (52, 211, 153),
        "needle_col": (241, 245, 249),
        "bar1_col": (52, 211, 153),
        "bar2_col": (56, 189, 248),
        "bar3_col": (245, 158, 11),
    },
    "icon_disabled.png": {
        "bg_top": (105, 114, 128),
        "bg_bot": (78, 86, 98),
        "border_col": (148, 163, 184),
        "bezel_col": (180, 188, 198),
        "screen_bg": (60, 68, 80),
        "arc_col": (180, 188, 198),
        "accent_col": (200, 208, 216),
        "needle_col": (226, 232, 240),
        "bar1_col": (180, 188, 198),
        "bar2_col": (190, 198, 208),
        "bar3_col": (170, 178, 188),
    },
    "icon_disabled_night.png": {
        "bg_top": (34, 40, 50),
        "bg_bot": (22, 26, 34),
        "border_col": (71, 85, 105),
        "bezel_col": (100, 116, 139),
        "screen_bg": (20, 25, 34),
        "arc_col": (100, 116, 139),
        "accent_col": (120, 135, 155),
        "needle_col": (148, 163, 184),
        "bar1_col": (100, 116, 139),
        "bar2_col": (110, 126, 149),
        "bar3_col": (95, 110, 132),
    },
}


def build_shortcut_apk(javac: str, d8: str) -> Path:
    """Compile & v1-sign HkgTurzxShortcut.apk (API 19 DEX 035 trampoline activity)."""
    android_jar = find_android_jar()
    aapt = find_aapt()
    apksigner = find_apksigner()
    keytool = find_keytool()

    SHORTCUT_CLASSES_DIR.mkdir(parents=True, exist_ok=True)
    SHORTCUT_DEX_DIR.mkdir(parents=True, exist_ok=True)

    launch_java = BASE_DIR / "shortcut_app" / "src" / "com" / "hkg" / "turzxmonitor" / "app" / "LaunchActivity.java"
    manifest_xml = BASE_DIR / "shortcut_app" / "AndroidManifest.xml"
    subprocess.run(
        [
            javac,
            "--release",
            "8",
            "-encoding",
            "UTF-8",
            "-cp",
            android_jar,
            "-d",
            str(SHORTCUT_CLASSES_DIR),
            str(launch_java),
        ],
        check=True,
    )

    class_files = [str(p) for p in sorted(SHORTCUT_CLASSES_DIR.rglob("*.class"))]
    if not class_files:
        raise RuntimeError("No shortcut classes compiled")
    subprocess.run(
        [
            d8,
            "--min-api",
            "19",
            "--no-desugaring",
            "--lib",
            android_jar,
            "--output",
            str(SHORTCUT_DEX_DIR),
            *class_files,
        ],
        check=True,
    )

    dex_bytes = (SHORTCUT_DEX_DIR / "classes.dex").read_bytes()
    if dex_bytes[:8] != b"dex\n035\x00":
        raise RuntimeError(f"Unexpected shortcut DEX magic: {dex_bytes[:8]!r}")

    shortcut_res_dir = BUILD_DIR / "shortcut_res"
    shortcut_res_drawable = shortcut_res_dir / "drawable"
    shortcut_res_drawable.mkdir(parents=True, exist_ok=True)
    (shortcut_res_drawable / "ic_launcher.png").write_bytes(
        render_turzx_tile_icon_png(**TILE_ICON_PALETTES["icon.png"])
    )

    unsigned_apk = BUILD_DIR / "HkgTurzxShortcut-unsigned.apk"
    subprocess.run(
        [
            aapt,
            "package",
            "-f",
            "-M",
            str(manifest_xml),
            "-S",
            str(shortcut_res_dir),
            "-I",
            android_jar,
            "-F",
            str(unsigned_apk),
        ],
        check=True,
    )
    with zipfile.ZipFile(unsigned_apk, "a", compression=zipfile.ZIP_DEFLATED) as zf:
        zinfo = zipfile.ZipInfo("classes.dex", date_time=(2026, 1, 1, 0, 0, 0))
        zinfo.compress_type = zipfile.ZIP_DEFLATED
        zf.writestr(zinfo, dex_bytes)

    keystore = BUILD_DIR / "shortcut_sign.p12"
    if keystore.exists():
        keystore.unlink()
    subprocess.run(
        [
            keytool,
            "-genkeypair",
            "-storetype",
            "PKCS12",
            "-keystore",
            str(keystore),
            "-storepass",
            "android",
            "-keypass",
            "android",
            "-alias",
            "hkgturzx",
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "10000",
            "-dname",
            "CN=HKGTurzxMonitor,O=HKG,C=KR",
        ],
        check=True,
    )

    signed_apk = BUILD_DIR / "HkgTurzxShortcut.apk"
    subprocess.run(
        [
            apksigner,
            "sign",
            "--ks",
            str(keystore),
            "--ks-pass",
            "pass:android",
            "--key-pass",
            "pass:android",
            "--v1-signing-enabled",
            "true",
            "--v2-signing-enabled",
            "false",
            "--v3-signing-enabled",
            "false",
            "--min-sdk-version",
            "19",
            "--out",
            str(signed_apk),
            str(unsigned_apk),
        ],
        check=True,
    )
    subprocess.run(
        [apksigner, "verify", "--min-sdk-version", "19", str(signed_apk)],
        check=True,
    )
    return signed_apk


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def normalize_lf(path: Path) -> bytes:
    orig = path.read_bytes()
    if b"\x00" in orig:
        raise RuntimeError(f"Null byte detected in text file: {path}")
    raw = orig.replace(b"\r\n", b"\n")
    if raw != orig:
        path.write_bytes(raw)
    return raw


def find_browser() -> str:
    for name in ("msedge", "chrome", "chromium", "google-chrome"):
        found = shutil.which(name)
        if found:
            return found
    candidates = [
        r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    ]
    for c in candidates:
        if os.path.isfile(c):
            return c
    raise RuntimeError("No Chromium browser (Edge/Chrome) found for 1:1 HUD layer pre-rendering")


def _get_free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class _CdpClient:
    def __init__(self, ws_url: str):
        u = urlparse(ws_url)
        self.sock = socket.create_connection((u.hostname, u.port), timeout=10)
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        req = (
            f"GET {u.path} HTTP/1.1\r\n"
            f"Host: {u.hostname}:{u.port}\r\n"
            f"Upgrade: websocket\r\n"
            f"Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            f"Sec-WebSocket-Version: 13\r\n\r\n"
        )
        self.sock.sendall(req.encode("ascii"))
        buf = b""
        while b"\r\n\r\n" not in buf:
            buf += self.sock.recv(4096)
        self.rx_buf = buf.split(b"\r\n\r\n", 1)[1]
        self.msg_id = 0

    def _recv_exact(self, n: int) -> bytes:
        while len(self.rx_buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise EOFError("WebSocket closed")
            self.rx_buf += chunk
        out = self.rx_buf[:n]
        self.rx_buf = self.rx_buf[n:]
        return out

    def _recv_frame(self) -> str:
        while True:
            hdr = self._recv_exact(2)
            opcode = hdr[0] & 0x0F
            length = hdr[1] & 0x7F
            if length == 126:
                length = int.from_bytes(self._recv_exact(2), "big")
            elif length == 127:
                length = int.from_bytes(self._recv_exact(8), "big")
            payload = self._recv_exact(length)
            if opcode == 0x01:
                return payload.decode("utf-8")

    def call(self, method: str, params: dict | None = None) -> dict:
        self.msg_id += 1
        mid = self.msg_id
        msg = json.dumps({"id": mid, "method": method, "params": params or {}}).encode("utf-8")
        frame = bytearray([0x81])
        mask = os.urandom(4)
        L = len(msg)
        if L < 126:
            frame.append(0x80 | L)
        elif L < 65536:
            frame.append(0x80 | 126)
            frame.extend(L.to_bytes(2, "big"))
        else:
            frame.append(0x80 | 127)
            frame.extend(L.to_bytes(8, "big"))
        frame.extend(mask)
        frame.extend(bytes(b ^ mask[i & 3] for i, b in enumerate(msg)))
        self.sock.sendall(frame)
        while True:
            resp = json.loads(self._recv_frame())
            if resp.get("id") == mid:
                if "error" in resp:
                    raise RuntimeError(f"CDP error: {resp['error']}")
                return resp.get("result", {})


def _capture_png(cdp: _CdpClient, clip: dict, omit_bg: bool = False) -> bytes:
    if omit_bg:
        cdp.call("Emulation.setDefaultBackgroundColorOverride", {"color": {"r": 0, "g": 0, "b": 0, "a": 0}})
    else:
        cdp.call("Emulation.setDefaultBackgroundColorOverride", {})
    shot = cdp.call(
        "Page.captureScreenshot",
        {"format": "png", "fromSurface": True, "clip": clip},
    )
    return base64.b64decode(shot["data"])


def generate_hud_assets(assets_dir: Path) -> None:
    """Render 1:1 Chromium PNG layers from turzx_92_mockup.html and fetch Public Sans .ttf fonts."""
    assets_dir.mkdir(parents=True, exist_ok=True)
    for fname, url in PUBLIC_SANS_FONTS.items():
        req = urllib.request.Request(url, headers={"User-Agent": "curl/7.68.0"})
        with urllib.request.urlopen(req, timeout=15) as r:
            (assets_dir / fname).write_bytes(r.read())

    browser = find_browser()
    port = _get_free_port()
    user_data = BUILD_DIR / "chrome_profile"
    html_src = REPO_ROOT / "turzx_92_mockup.html"
    proc = subprocess.Popen(
        [
            browser,
            "--headless=new",
            "--disable-gpu",
            "--no-first-run",
            "--no-default-browser-check",
            f"--user-data-dir={user_data}",
            f"--remote-debugging-port={port}",
            "--window-size=1920,1080",
            html_src.as_uri(),
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        ws_url = None
        for _ in range(40):
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/json", timeout=1) as r:
                    targets = json.loads(r.read().decode("utf-8"))
                    for t in targets:
                        if t.get("type") == "page" and "webSocketDebuggerUrl" in t:
                            ws_url = t["webSocketDebuggerUrl"]
                            break
                if ws_url:
                    break
            except Exception:
                time.sleep(0.2)
        if not ws_url:
            raise RuntimeError("Failed to connect to Headless Chromium CDP")

        cdp = _CdpClient(ws_url)
        cdp.call("Page.enable")
        cdp.call(
            "Emulation.setDeviceMetricsOverride",
            {"width": 1400, "height": 600, "deviceScaleFactor": 2, "mobile": False},
        )
        time.sleep(1.2)

        def _capture_panel_layers(panel_id: str, dp_h: int, col2_dp_h: int, suffix: str) -> None:
            cdp.call("Page.navigate", {"url": html_src.as_uri()})
            time.sleep(1.0)
            cdp.call(
                "Runtime.evaluate",
                {
                    "expression": f"""
                    (() => {{
                        for (let i = 1; i < 9999; i++) window.clearInterval(i);
                        if (typeof setPanelModel === 'function') setPanelModel('{panel_id}', null);
                        window.updateViewportScale = () => {{}};
                        const lcd = document.getElementById('turzxLcd');
                        document.body.appendChild(lcd);
                        Array.from(document.body.children).forEach(ch => {{
                            if (ch !== lcd) ch.remove();
                        }});
                        document.documentElement.style.margin = '0';
                        document.documentElement.style.padding = '0';
                        document.documentElement.style.background = 'transparent';
                        document.body.style.margin = '0';
                        document.body.style.padding = '0';
                        document.body.style.overflow = 'hidden';
                        window.removeEventListener('resize', updateViewportScale);
                        lcd.style.setProperty('position', 'fixed', 'important');
                        lcd.style.setProperty('left', '0px', 'important');
                        lcd.style.setProperty('top', '0px', 'important');
                        lcd.style.setProperty('width', '960px', 'important');
                        lcd.style.setProperty('height', '{dp_h}px', 'important');
                        lcd.style.setProperty('transform', 'none', 'important');
                        lcd.style.setProperty('z-index', '999999', 'important');
                        const lc = document.getElementById('laneConfText');
                        if (lc) lc.innerHTML = '차선 <strong id="laneConfVal" style="color:var(--secondary);font-weight:600;">98%</strong>';
                    }})()
                    """
                },
            )
            time.sleep(0.2)

            # 1. BASE SHELL: static labels/rules/vinyl/chassis/seated figure, inactive HVAC & normal TPMS
            cdp.call(
                "Runtime.evaluate",
                {
                    "expression": """
                    (() => {
                        const hideSels = [
                            '.ev-figure-row', '#socBar', '#effSubText', '#pwrBar', '#cumEnergyText', '#batMinMaxText',
                            '#tpmsFlVal', '#tpmsFrVal', '#tpmsRlVal', '#tpmsRrVal',
                            '#commaRoadCanvas', '.comma-fused-top', '.comma-corner-foot',
                            '#mediaTitleText', '#mediaArtistText', '#mediaProgressBar', '#mediaCurTime', '#mediaDurTime',
                            '#inTempText', '#outTempText'
                        ];
                        hideSels.forEach(s => document.querySelectorAll(s).forEach(el => el.style.visibility = 'hidden'));
                        const fsl = document.getElementById('fanStepLabel');
                        if (fsl) fsl.innerHTML = '<span style="visibility:hidden">3</span>단';
                        ['#tpmsTempSummary', '#drvTempText', '#pasTempText'].forEach(s => {
                            const el = document.querySelector(s);
                            if (el) {
                                el.style.color = 'transparent';
                                el.querySelectorAll('*').forEach(c => c.style.color = 'transparent');
                            }
                        });
                        ['airArrowUp', 'airArrowMid', 'airArrowDown'].forEach(id => {
                            const g = document.getElementById(id);
                            if (g) g.className.baseVal = 'air-arrow off';
                        });
                        document.querySelectorAll('#fanBarsRail .fan-bar-seg').forEach(b => b.classList.remove('active'));
                        document.querySelectorAll('.hvac-icon-dock .hvac-dock-btn').forEach(b => b.classList.remove('on'));
                    })()
                    """
                },
            )
            time.sleep(0.15)
            (assets_dir / f"base_shell{suffix}.png").write_bytes(
                _capture_png(cdp, {"x": 0, "y": 0, "width": 960, "height": dp_h, "scale": 1}, omit_bg=False)
            )

            # 2. ACTIVE SHELL: active airflow arrows, 7 fan bars, 7 HVAC dock buttons, amber TPMS warning
            cdp.call(
                "Runtime.evaluate",
                {
                    "expression": """
                    (() => {
                        ['airArrowUp', 'airArrowMid', 'airArrowDown'].forEach(id => {
                            const g = document.getElementById(id);
                            if (g) g.className.baseVal = 'air-arrow';
                        });
                        document.querySelectorAll('#fanBarsRail .fan-bar-seg').forEach(b => b.classList.add('active'));
                        document.querySelectorAll('.hvac-icon-dock .hvac-dock-btn').forEach(b => b.classList.add('on'));
                        ['svgTireFl', 'svgTireFr', 'svgTireRl', 'svgTireRr'].forEach(id => {
                            const t = document.getElementById(id);
                            if (t) t.setAttribute('stroke', '#d9a441');
                        });
                        const ts = document.getElementById('tpmsStatusTag');
                        if (ts) {
                            ts.textContent = '저압';
                            ts.className = 'tpms-status-dot warn';
                        }
                        const fsl = document.getElementById('fanStepLabel');
                        if (fsl) fsl.style.visibility = 'hidden';
                    })()
                    """
                },
            )
            time.sleep(0.15)
            (assets_dir / f"active_shell{suffix}.png").write_bytes(
                _capture_png(cdp, {"x": 0, "y": 0, "width": 960, "height": dp_h, "scale": 1}, omit_bg=False)
            )

            # 3. COLUMN 2 TRANSPARENT HUD OVERLAYS (x=304..595, y=6..(dp_h-6) dp)
            cdp.call(
                "Runtime.evaluate",
                {
                    "expression": """
                    (() => {
                        document.body.style.background = 'transparent';
                        const lcd = document.getElementById('turzxLcd');
                        lcd.style.background = 'transparent';
                        document.querySelector('.panel-ev').style.visibility = 'hidden';
                        document.querySelector('.right-stack-col').style.visibility = 'hidden';
                        const pm = document.querySelector('.panel-media');
                        if (pm) pm.style.visibility = 'hidden';
                        const ph = document.querySelector('.panel-hvac');
                        if (ph) ph.style.visibility = 'hidden';
                        const pn = document.querySelector('.panel-navi');
                        pn.style.background = 'transparent';
                        document.getElementById('commaRoadCanvas').style.visibility = 'hidden';

                        document.querySelector('.comma-fused-top').style.visibility = 'visible';
                        document.querySelector('.comma-corner-foot').style.visibility = 'visible';

                        const hideHudDynamic = [
                            '#hudGearChar', '#egoSpeedText', '#cruiseTargetText',
                            '#camLimitNum', '#camDistFigure', '#camAvgSpeed',
                            '#tbtDistFigure', '#tbtRoadName', '#tbtNextText',
                            '#laneConfVal', '#leadDistText', '#steerAngleText'
                        ];
                        hideHudDynamic.forEach(s => {
                            const el = document.querySelector(s);
                            if (el) el.style.color = 'transparent';
                        });
                        const rChipSpans = document.querySelectorAll('.comma-corner-foot .corner-chip:nth-child(2) > span');
                        if (rChipSpans.length >= 2) rChipSpans[1].style.color = 'transparent';
                    })()
                    """
                },
            )
            col2_h = col2_dp_h
            for mode in ("section", "traffic", "none"):
                cdp.call(
                    "Runtime.evaluate",
                    {
                        "expression": f"""
                        (() => {{
                            setSdiEnforcementState('{mode}', 80, '2.3 km', 80, null);
                            ['#camLimitNum', '#camDistFigure', '#camAvgSpeed'].forEach(s => {{
                                const el = document.querySelector(s);
                                if (el) el.style.color = 'transparent';
                            }});
                        }})()
                        """
                    },
                )
                time.sleep(0.1)
                (assets_dir / f"col2_hud_{mode}{suffix}.png").write_bytes(
                    _capture_png(cdp, {"x": 304, "y": 6, "width": 291, "height": col2_h, "scale": 1}, omit_bg=True)
                )

        # Capture 9.2" (1920x462) and 12.3" (1920x720: 290dp upper row + 58dp bottom climate taskbar) 1:1 Chromium layer sets
        _capture_panel_layers("92", 231, 219, "")
        _capture_panel_layers("123", 360, 290, "_720")
    finally:
        proc.kill()
        shutil.rmtree(user_data, ignore_errors=True)


NATIVE_SO_ASSET = "libturzxjpeg_armv7.so"


def stage_native_encoder(assets_dir: Path) -> None:
    """Bundle libturzxjpeg.so (libjpeg-turbo NEON JNI) into jar assets.

    Rebuilds with the NDK when available (set TURZX_SKIP_NATIVE_BUILD=1 to force the prebuilt),
    otherwise uses the committed native/prebuilt/armeabi-v7a/libturzxjpeg.so.
    """
    import sys

    sys.path.insert(0, str(BASE_DIR / "native"))
    import build_native  # noqa: E402

    so_path = build_native.PREBUILT_SO
    if (
        os.environ.get("TURZX_SKIP_NATIVE_BUILD") != "1"
        and build_native.find_ndk() is not None
        and shutil.which("cmake") is not None
    ):
        so_path = build_native.build_native()
    elif not so_path.is_file():
        raise RuntimeError(f"No NDK found and prebuilt native encoder missing: {so_path}")
    else:
        print(f"Using prebuilt native encoder: {so_path}")
    data = so_path.read_bytes()
    if data[:4] != b"\x7fELF" or data[4] != 1 or data[18] != 40:
        raise RuntimeError(f"{so_path} is not an ELF32 ARM shared object")
    (assets_dir / NATIVE_SO_ASSET).write_bytes(data)


# Clients up to v1.5.13 download ota/turzxmonitor.b64 with a hard 4MB cap (headers included) and hand a
# truncated file to the SHA-256 check -> "무결성 검증 실패". Every OTA build must stay below that cap so
# those clients can still update; keep a margin for HTTP headers and growth.
LEGACY_OTA_B64_LIMIT = 4 * 1024 * 1024
OTA_B64_MARGIN = 128 * 1024


def optimize_png_assets(assets_dir: Path) -> None:
    """Lossless PNG recompression (oxipng; pixels unchanged). Install with: pip install pyoxipng"""
    try:
        import oxipng  # type: ignore
    except ImportError:
        print("WARNING: pyoxipng not installed -> PNG assets not optimized (pip install pyoxipng)")
        return
    before = after = 0
    for png in sorted(assets_dir.glob("*.png")):
        before += png.stat().st_size
        # Keep bit depth / color type (RGBA stays RGBA): only the IDAT deflate stream is re-encoded, so Android
        # 4.4 Skia decodes exactly the same pixel format as before.
        oxipng.optimize(
            png,
            level=3,
            strip=oxipng.StripChunks.safe(),
            bit_depth_reduction=False,
            color_type_reduction=False,
            palette_reduction=False,
            grayscale_reduction=False,
            optimize_alpha=False,
            scale_16=False,
        )
        after += png.stat().st_size
    print(f"PNG assets optimized (lossless): {before} -> {after} bytes")


def stage_hmgapi_assets(assets_dir: Path, shortcut_apk: Path) -> None:
    """Copy HMG-API launcher mod files into jar assets so OTA upgrades also register the shortcut."""
    shutil.copy2(BASE_DIR / "hmgapi_mod" / "mod.json", assets_dir / "hmgapi_mod.json")
    shutil.copy2(BASE_DIR / "hmgapi_mod" / "bin" / "open-ui.sh", assets_dir / "hmgapi_open_ui.sh")
    shutil.copy2(shortcut_apk, assets_dir / "hmgapi_shortcut.apk")
    for icon_name, palette in TILE_ICON_PALETTES.items():
        (assets_dir / f"hmgapi_{icon_name}").write_bytes(render_turzx_tile_icon_png(**palette))


def reuse_assets_from_ota(assets_dir: Path) -> None:
    """TURZX_REUSE_OTA_ASSETS=1: take PNG layers + fonts from the current ota/turzxmonitor.b64 jar
    instead of re-rendering them with Chromium (for build hosts without Edge/Chrome)."""
    assets_dir.mkdir(parents=True, exist_ok=True)
    jar_bytes = base64.b64decode((REPO_ROOT / "ota" / "turzxmonitor.b64").read_bytes())
    import io

    with zipfile.ZipFile(io.BytesIO(jar_bytes)) as zf:
        for name in zf.namelist():
            fname = Path(name).name
            if (
                name.startswith("assets/")
                and not name.endswith("/")
                and not name.endswith(".so")
                and not fname.startswith("hmgapi_")
            ):
                (assets_dir / fname).write_bytes(zf.read(name))


def build() -> None:
    if BUILD_DIR.exists():
        shutil.rmtree(BUILD_DIR)
    CLASSES_DIR.mkdir(parents=True, exist_ok=True)
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    DEX_DIR.mkdir(parents=True, exist_ok=True)
    PKG_STAGE_DIR.mkdir(parents=True, exist_ok=True)
    USB_ROOT_DIR.mkdir(parents=True, exist_ok=True)

    # 1. Normalize LF line endings on all source scripts/configs
    source_files = [
        "NEMESIS.data",
        "config/monitor.conf",
        "scripts/post.sh",
        "scripts/preun.sh",
        "bin/launch.sh",
        "svc/turzx-daemon.sh",
        "hmgapi_mod/mod.json",
        "hmgapi_mod/bin/open-ui.sh",
    ]
    for rel in source_files:
        normalize_lf(BASE_DIR / rel)
    normalize_lf(BASE_DIR / "shortcut_app" / "AndroidManifest.xml")

    # 1b. Render 1:1 Chromium HUD layer PNGs + fetch Public Sans .ttf fonts
    if os.environ.get("TURZX_REUSE_OTA_ASSETS") == "1":
        reuse_assets_from_ota(ASSETS_DIR)
    else:
        generate_hud_assets(ASSETS_DIR)
    # 1c. libjpeg-turbo NEON JNI encoder (.so) as jar asset — extracted at runtime by NativeJpeg
    stage_native_encoder(ASSETS_DIR)
    # 1d. Panel JPEG restart-marker test frames (committed; regenerate with tools/make_panel_test_jpegs.py)
    panel_test_dir = BASE_DIR / "panel_test"
    panel_test_jpegs = sorted(panel_test_dir.glob("panel_test_*.jpg"))
    if len(panel_test_jpegs) != 3:
        raise RuntimeError(f"Expected 3 panel test JPEGs in {panel_test_dir}, found {len(panel_test_jpegs)}")
    for jpg in panel_test_jpegs:
        shutil.copy2(jpg, ASSETS_DIR / jpg.name)
    # 1e. v1.5.0 Tesla-style HUD assets (regenerate with tools/make_tesla_assets.py)
    tesla_dir = BASE_DIR / "design_tesla"
    tesla_files = sorted(tesla_dir.glob("tesla_*")) + sorted(tesla_dir.glob("navdy_*"))
    if len(tesla_files) < 15 + 156:
        raise RuntimeError(f"Tesla HUD assets missing in {tesla_dir} ({len(tesla_files)} files)")
    for f in tesla_files:
        shutil.copy2(f, ASSETS_DIR / f.name)

    javac = find_javac()
    java = find_java()
    d8 = find_d8()

    # 1e-2. Build HMG-API launcher shortcut APK + tile PNGs and embed into jar assets (for OTA sync)
    shortcut_apk = build_shortcut_apk(javac, d8)
    stage_hmgapi_assets(ASSETS_DIR, shortcut_apk)

    # 1f. Lossless PNG recompression (keeps the OTA jar under the legacy 4MB download cap)
    optimize_png_assets(ASSETS_DIR)
    classes_assets_dir = CLASSES_DIR / "assets"
    classes_assets_dir.mkdir(parents=True, exist_ok=True)
    for asset_file in sorted(ASSETS_DIR.iterdir()):
        if asset_file.is_file():
            shutil.copy2(asset_file, classes_assets_dir / asset_file.name)

    # 2. Compile TurzxMonitorMain.java + compile-time stubs (Java 8 target -> Android API 19 DEX)
    java_src = BASE_DIR / "src_java" / "com" / "hkg" / "turzxmonitor" / "TurzxMonitorMain.java"
    stub_srcs = [str(p) for p in sorted((BASE_DIR / "src_stubs").rglob("*.java"))]

    subprocess.run(
        [javac, "--release", "8", "-encoding", "UTF-8", "-d", str(CLASSES_DIR), *stub_srcs, str(java_src)],
        check=True,
    )

    # 3. Run host self-test suite on compiled classes
    subprocess.run(
        [java, "-cp", str(CLASSES_DIR), "com.hkg.turzxmonitor.TurzxMonitorMain", "test-self"],
        check=True,
    )

    # 4. Convert only com.hkg.turzxmonitor .class files to Dalvik DEX (--min-api 19 -> DEX 035 for Android 4.4.2)
    # The class files go to d8 inside one jar: passing every .class path exceeds the Windows command-line limit.
    app_classes_dir = CLASSES_DIR / "com" / "hkg" / "turzxmonitor"
    app_classes_jar = BUILD_DIR / "app_classes.jar"
    with zipfile.ZipFile(app_classes_jar, "w", compression=zipfile.ZIP_STORED) as zf:
        for p in sorted(app_classes_dir.rglob("*.class")):
            zf.write(p, p.relative_to(CLASSES_DIR).as_posix())
    subprocess.run(
        [d8, "--min-api", "19", "--no-desugaring", "--output", str(DEX_DIR), str(app_classes_jar)],
        check=True,
    )

    classes_dex = DEX_DIR / "classes.dex"
    dex_bytes = classes_dex.read_bytes()
    if dex_bytes[:8] != b"dex\n035\x00":
        raise RuntimeError(f"Unexpected DEX magic header: {dex_bytes[:8]!r} (expected b'dex\\n035\\x00')")

    jar_path = BUILD_DIR / "turzxmonitor.jar"
    with zipfile.ZipFile(jar_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zinfo = zipfile.ZipInfo("classes.dex", date_time=(2026, 1, 1, 0, 0, 0))
        zinfo.compress_type = zipfile.ZIP_DEFLATED
        zf.writestr(zinfo, dex_bytes)
        for asset_file in sorted(ASSETS_DIR.iterdir()):
            if asset_file.is_file():
                ainfo = zipfile.ZipInfo(f"assets/{asset_file.name}", date_time=(2026, 1, 1, 0, 0, 0))
                ainfo.compress_type = zipfile.ZIP_DEFLATED
                zf.writestr(ainfo, asset_file.read_bytes())

    jar_bytes = jar_path.read_bytes()
    jar_sha = sha256_bytes(jar_bytes)
    (BUILD_DIR / "turzxmonitor.jar.sha256").write_bytes(f"{jar_sha}  turzxmonitor.jar\n".encode("utf-8"))

    daemon_bytes = (BASE_DIR / "svc" / "turzx-daemon.sh").read_bytes()
    launch_bytes = (BASE_DIR / "bin" / "launch.sh").read_bytes()
    daemon_sha = sha256_bytes(daemon_bytes)
    launch_sha = sha256_bytes(launch_bytes)

    # 4b. Generate GitHub OTA text artifacts in <repo>/ota/
    ota_dir = REPO_ROOT / "ota"
    ota_dir.mkdir(parents=True, exist_ok=True)
    version_meta = {
        "package": "hkg.turzxmonitor",
        "version": MONITOR_VERSION,
        "sha256_jar": jar_sha,
        "sha256_daemon": daemon_sha,
        "sha256_launch": launch_sha,
        "repo": "GRT47/HKG_5W_TurzxMonitor",
    }
    b64_raw = base64.b64encode(jar_bytes).decode("ascii")
    b64_lines = "\n".join(b64_raw[i : i + 76] for i in range(0, len(b64_raw), 76)) + "\n"
    if len(b64_lines) > LEGACY_OTA_B64_LIMIT - OTA_B64_MARGIN:
        raise RuntimeError(
            f"ota/turzxmonitor.b64 would be {len(b64_lines)} bytes; clients <= v1.5.13 cut downloads at "
            f"{LEGACY_OTA_B64_LIMIT} bytes (OTA fails with SHA-256 mismatch). Shrink the jar assets "
            f"(limit incl. margin: {LEGACY_OTA_B64_LIMIT - OTA_B64_MARGIN})."
        )
    print(f"OTA b64 size: {len(b64_lines)} bytes (legacy client limit {LEGACY_OTA_B64_LIMIT}, margin {OTA_B64_MARGIN})")
    version_json_bytes = (json.dumps(version_meta, indent=2) + "\n").encode("utf-8")
    (ota_dir / "version.json").write_bytes(version_json_bytes)
    (ota_dir / "turzx-daemon.sh").write_bytes(daemon_bytes)
    (ota_dir / "launch.sh").write_bytes(launch_bytes)

    (ota_dir / "turzxmonitor.b64").write_bytes(b64_lines.encode("ascii"))

    # 5. Stage NEMESIS package files + config/version.json + web/turzx_92_mockup.html
    for rel in source_files:
        src = BASE_DIR / rel
        dst = PKG_STAGE_DIR / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)

    stage_ver = PKG_STAGE_DIR / "config" / "version.json"
    stage_ver.parent.mkdir(parents=True, exist_ok=True)
    stage_ver.write_bytes(version_json_bytes)

    stage_jar = PKG_STAGE_DIR / "bin" / "turzxmonitor.jar"
    stage_jar.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(jar_path, stage_jar)

    html_src = REPO_ROOT / "turzx_92_mockup.html"
    if html_src.is_file():
        stage_html = PKG_STAGE_DIR / "web" / "turzx_92_mockup.html"
        stage_html.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(html_src, stage_html)
        normalize_lf(stage_html)

    # 5b. Stage HMG-API companion shortcut APK + finalized 99x99 tile PNG icons (identical to jar assets)
    stage_mod_dir = PKG_STAGE_DIR / "hmgapi_mod"
    stage_mod_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(ASSETS_DIR / "hmgapi_shortcut.apk", stage_mod_dir / "HkgTurzxShortcut.apk")
    for icon_name in TILE_ICON_PALETTES:
        shutil.copy2(ASSETS_DIR / f"hmgapi_{icon_name}", stage_mod_dir / icon_name)

    # 6. Build inner NEMESIS package zip (packages/hkg.turzxmonitor.zip)
    inner_zip_path = USB_ROOT_DIR / "packages" / "hkg.turzxmonitor.zip"
    inner_zip_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(inner_zip_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for file_path in sorted(PKG_STAGE_DIR.rglob("*")):
            if file_path.is_file():
                arcname = file_path.relative_to(PKG_STAGE_DIR).as_posix()
                zinfo = zipfile.ZipInfo(arcname, date_time=(2026, 1, 1, 0, 0, 0))
                zinfo.compress_type = zipfile.ZIP_DEFLATED
                if arcname.endswith(".sh"):
                    zinfo.external_attr = (0o100755 & 0xFFFF) << 16
                else:
                    zinfo.external_attr = (0o100644 & 0xFFFF) << 16
                zf.writestr(zinfo, file_path.read_bytes())

    # 7. Build USB root descriptor (usb_root/NEMESIS.data)
    usb_meta = (
        "[nemesis]\n"
        "version=1.0\n"
        "type=install\n"
        "path=packages/hkg.turzxmonitor.zip\n"
    )
    (USB_ROOT_DIR / "NEMESIS.data").write_bytes(usb_meta.encode("utf-8"))

    # 8. Build outer USB bundle zip (HKG_5W_TurzxMonitor_USB.zip)
    target_zip = OUTER_ZIP_PATH
    try:
        if target_zip.exists():
            os.chmod(target_zip, 0o666)
            target_zip.unlink()
    except PermissionError:
        target_zip = REPO_ROOT / f"HKG_5W_TurzxMonitor_USB_v{MONITOR_VERSION}.zip"
    with zipfile.ZipFile(target_zip, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for file_path in sorted(USB_ROOT_DIR.rglob("*")):
            if file_path.is_file():
                arcname = file_path.relative_to(USB_ROOT_DIR).as_posix()
                zf.write(file_path, arcname)

    print(f"Built DEX jar:       {jar_path} ({jar_path.stat().st_size} bytes, sha256={jar_sha[:16]}...)")
    print(f"Built shortcut APK:  {shortcut_apk} ({shortcut_apk.stat().st_size} bytes)")
    print(f"Built inner package: {inner_zip_path} ({inner_zip_path.stat().st_size} bytes)")
    print(f"Built USB bundle:    {target_zip} ({target_zip.stat().st_size} bytes)")


if __name__ == "__main__":
    build()

