#!/usr/bin/env python3
"""
Builds package_t88_hud/build/HkgHudDisplay.apk using JDK 17 + Android SDK build-tools.
Reuses 1920x720 Tesla HUD assets from package_turzx_monitor/design_tesla without duplicating them in git.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import shutil
import struct
import subprocess
import urllib.request
import zipfile
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parent
SRC_DIR = HERE / "src"
MANIFEST_XML = HERE / "AndroidManifest.xml"
BUILD_DIR = HERE / "build"
ASSETS_DIR = BUILD_DIR / "assets"
RES_DIR = BUILD_DIR / "res"
CLASSES_DIR = BUILD_DIR / "classes"
DEX_DIR = BUILD_DIR / "dex"
UNSIGNED_APK = BUILD_DIR / "HkgHudDisplay-unsigned.apk"
ALIGNED_APK = BUILD_DIR / "HkgHudDisplay-aligned.apk"
SIGNED_APK = BUILD_DIR / "HkgHudDisplay.apk"

TURZX_DESIGN_DIR = REPO_ROOT / "package_turzx_monitor" / "design_tesla"
TURZX_BUILD_ASSETS = REPO_ROOT / "package_turzx_monitor" / "build" / "assets"
CAR3D_SCRIPT = HERE / "build_car3d_asset.py"
CAR3D_MJS = HERE / "simplify_meshopt.mjs"
CAR3D_GLTF = REPO_ROOT / "3d" / "scene.gltf"
CAR3D_BIN = REPO_ROOT / "3d" / "scene.bin"
CAR3D_CACHE = BUILD_DIR / "car3d" / "niro_ev.c3d"

PUBLIC_SANS_FONTS = {
    "PublicSans-Bold.ttf": "https://fonts.gstatic.com/s/publicsans/v21/ijwGs572Xtc6ZYQws9YVwllKVG8qX1oyOymu8Z65ww.ttf",
    "PublicSans-Medium.ttf": "https://fonts.gstatic.com/s/publicsans/v21/ijwGs572Xtc6ZYQws9YVwllKVG8qX1oyOymuJJm5ww.ttf",
}


def find_sdk_root() -> Path:
    for env_key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        val = os.environ.get(env_key)
        if val and Path(val).is_dir():
            return Path(val)
    local_app = os.environ.get("LOCALAPPDATA", "")
    cand = Path(local_app) / "Android" / "Sdk"
    if cand.is_dir():
        return cand
    raise RuntimeError("Android SDK not found (set ANDROID_SDK_ROOT)")


def find_jdk_tool(name: str) -> str:
    found = shutil.which(name)
    if found:
        return found
    for root in (
        Path(r"C:\Program Files\Eclipse Adoptium"),
        Path(r"C:\Program Files\Java"),
    ):
        if root.is_dir():
            for match in sorted(root.rglob(f"{name}.exe")):
                return str(match)
    raise RuntimeError(f"JDK tool not found: {name}")


def find_build_tool(bt_dir: Path, stem: str) -> str:
    for ext in (".exe", ".bat", ""):
        p = bt_dir / f"{stem}{ext}"
        if p.is_file():
            return str(p)
    raise RuntimeError(f"Build tool not found in {bt_dir}: {stem}")


def render_icon_png(size: int = 99) -> bytes:
    """Renders the ultrawide cluster tile icon matching package_turzx_monitor's launcher icon."""
    bg_top = (28, 42, 66)
    bg_bot = (14, 22, 38)
    border_col = (56, 189, 248)
    bezel_col = (94, 234, 212)
    screen_bg = (11, 18, 32)
    arc_col = (56, 189, 248)
    accent_col = (52, 211, 153)
    needle_col = (248, 250, 252)
    bar1_col = (52, 211, 153)
    bar2_col = (56, 189, 248)
    bar3_col = (251, 191, 36)

    cx, cy = 49.5, 49.5
    bx, by, br = 43.5, 43.5, 22.0
    scx, scy = 49.5, 47.5
    sbx, sby, sbr = 27.5, 13.5, 5.2
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

        if (abs(px - scx) <= 3.2 and 60.5 <= py <= 64.2) or (
            rr_dist(px, py, scx, 65.5, 12.0, 2.0, 1.8) <= 0.0
        ):
            r, g, b = bezel_col

        sdist = rr_dist(px, py, scx, scy, sbx, sby, sbr)
        if sdist <= 0.0:
            if sdist >= -2.2:
                r, g, b = bezel_col
            else:
                r, g, b = screen_bg

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

                if 48.0 <= px <= 70.5 and 41.2 <= py <= 44.2:
                    r, g, b = bar1_col
                elif 48.0 <= px <= 65.5 and 46.2 <= py <= 49.0:
                    r, g, b = bar2_col
                elif 48.0 <= px <= 68.5 and 51.0 <= py <= 53.8:
                    r, g, b = bar3_col

        return (r, g, b, 255)

    scale = 99.0 / float(size)
    rows = bytearray()
    offsets = (0.25, 0.75)
    for y in range(size):
        rows.append(0)
        for x in range(size):
            sr = sg = sb = sa = 0
            for ox in offsets:
                for oy in offsets:
                    cr, cg, cb, ca = sample((x + ox) * scale, (y + oy) * scale)
                    sr += cr
                    sg += cg
                    sb += cb
                    sa += ca
            rows.extend((sr >> 2, sg >> 2, sb >> 2, sa >> 2))

    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    idat = zlib.compress(bytes(rows), 9)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", idat) + chunk(b"IEND", b"")


def stage_assets() -> None:
    if ASSETS_DIR.exists():
        shutil.rmtree(ASSETS_DIR)
    design_dst = ASSETS_DIR / "design_tesla"
    design_dst.mkdir(parents=True, exist_ok=True)

    # Copy only 1920x720 assets + shared sprites/navdy assets (~650 KB total)
    keep_exact = {
        "tesla_base_720.png",
        "tesla_active_720.png",
        "tesla_base_ice_720.png",
        "tesla_layout_123.txt",
        "tesla_layout_ice_123.txt",
        "tesla_park.png",
        "tesla_ego_off.png",
        "tesla_ego_tail.png",
        "tesla_ego_brake.png",
        "tesla_lead.png",
        "navdy_meta.txt",
    }
    copied = 0
    for src in sorted(TURZX_DESIGN_DIR.iterdir()):
        if not src.is_file():
            continue
        if src.name in keep_exact or src.name.startswith("navdy_"):
            shutil.copy2(src, design_dst / src.name)
            copied += 1
    if copied < 20:
        raise RuntimeError(f"Expected design_tesla assets missing (copied {copied})")

    # Public Sans TTF fonts (copy from package_turzx_monitor build cache or fetch)
    for fname, url in PUBLIC_SANS_FONTS.items():
        cached = TURZX_BUILD_ASSETS / fname
        dst = ASSETS_DIR / fname
        if cached.is_file():
            shutil.copy2(cached, dst)
        else:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            dst.write_bytes(urllib.request.urlopen(req, timeout=15).read())

    # 3D Niro EV mesh asset (compiled from 3d/scene.gltf if present)
    if CAR3D_GLTF.is_file() and CAR3D_BIN.is_file() and CAR3D_SCRIPT.is_file():
        need_rebuild = not CAR3D_CACHE.is_file()
        if not need_rebuild:
            c_mtime = CAR3D_CACHE.stat().st_mtime
            if (CAR3D_GLTF.stat().st_mtime > c_mtime
                    or CAR3D_BIN.stat().st_mtime > c_mtime
                    or CAR3D_SCRIPT.stat().st_mtime > c_mtime
                    or (CAR3D_MJS.is_file() and CAR3D_MJS.stat().st_mtime > c_mtime)):
                need_rebuild = True
        if need_rebuild:
            import build_car3d_asset
            info = build_car3d_asset.compile_car3d(CAR3D_GLTF, CAR3D_BIN, CAR3D_CACHE)
            print(
                f"Compiled 3D Kia e-Niro: {info['orig_tris']:,} -> {info['tris']:,} tris, "
                f"{info['verts']:,} verts, {info['bytes']:,} bytes"
            )
        car3d_dst = ASSETS_DIR / "car3d" / "niro_ev.c3d"
        car3d_dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(CAR3D_CACHE, car3d_dst)

    extra_assets = HERE / "assets"
    if extra_assets.is_dir():
        for src in extra_assets.rglob("*"):
            if src.is_file():
                rel = src.relative_to(extra_assets)
                dst = ASSETS_DIR / rel
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(src, dst)

    drawable = RES_DIR / "drawable"
    drawable.mkdir(parents=True, exist_ok=True)
    (drawable / "ic_launcher.png").write_bytes(render_icon_png(99))
    drawable_xx = RES_DIR / "drawable-xxhdpi"
    drawable_xx.mkdir(parents=True, exist_ok=True)
    (drawable_xx / "ic_launcher.png").write_bytes(render_icon_png(198))
    print(f"Staged {copied} HUD assets + 2 fonts cleanly into {ASSETS_DIR}")


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            chunk = f.read(65536)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def update_manifest_version(version_name: str | None, version_code: int | None) -> tuple[int, str]:
    txt = MANIFEST_XML.read_text(encoding="utf-8")
    cur_code_m = re.search(r'android:versionCode="(\d+)"', txt)
    cur_name_m = re.search(r'android:versionName="([^"]+)"', txt)
    cur_code = int(cur_code_m.group(1)) if cur_code_m else 100
    cur_name = cur_name_m.group(1) if cur_name_m else "1.0.0"

    new_code = version_code if version_code is not None else cur_code
    new_name = version_name if version_name else cur_name
    if version_name and version_code is None and new_name != cur_name:
        new_code = cur_code + 1

    if new_code != cur_code or new_name != cur_name:
        txt = re.sub(r'android:versionCode="\d+"', f'android:versionCode="{new_code}"', txt, count=1)
        txt = re.sub(r'android:versionName="[^"]+"', f'android:versionName="{new_name}"', txt, count=1)
        MANIFEST_XML.write_text(txt, encoding="utf-8")
        print(f"Updated AndroidManifest.xml -> versionName={new_name}, versionCode={new_code}")
    return new_code, new_name


def write_update_json(version_code: int, version_name: str, apk_path: Path, repo: str, tag: str, notes: str) -> Path:
    sha = sha256_file(apk_path)
    apk_url = f"https://github.com/{repo}/releases/download/{tag}/{apk_path.name}"
    meta = {
        "package": "com.hkg.huddisplay",
        "versionCode": version_code,
        "versionName": version_name,
        "apkUrl": apk_url,
        "sha256": sha,
        "notes": notes,
    }
    out = HERE / "update.json"
    out.write_text(json.dumps(meta, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Wrote {out} -> v{version_name} ({version_code}), sha256={sha}")
    return out


def build(install: bool = False, version: str | None = None, version_code: int | None = None,
          do_write_update_json: bool = False, repo: str = "GRT47/HKG_5W_TurzxMonitor",
          tag: str | None = None, notes: str = "") -> Path:
    v_code, v_name = update_manifest_version(version, version_code)
    sdk = find_sdk_root()
    platforms = sorted((sdk / "platforms").glob("android-*"), reverse=True)
    build_tools = sorted((sdk / "build-tools").iterdir(), reverse=True)
    if not platforms or not build_tools:
        raise RuntimeError("Missing Android platforms or build-tools in SDK")
    android_jar = platforms[0] / "android.jar"
    bt = build_tools[0]

    aapt = find_build_tool(bt, "aapt")
    d8 = find_build_tool(bt, "d8")
    zipalign = find_build_tool(bt, "zipalign")
    apksigner = find_build_tool(bt, "apksigner")
    javac = find_jdk_tool("javac")
    keytool = find_jdk_tool("keytool")

    BUILD_DIR.mkdir(parents=True, exist_ok=True)
    stage_assets()

    if CLASSES_DIR.exists():
        shutil.rmtree(CLASSES_DIR)
    CLASSES_DIR.mkdir(parents=True, exist_ok=True)
    if DEX_DIR.exists():
        shutil.rmtree(DEX_DIR)
    DEX_DIR.mkdir(parents=True, exist_ok=True)

    java_srcs = sorted(str(p) for p in SRC_DIR.rglob("*.java"))
    print(f"Compiling {len(java_srcs)} Java sources against {android_jar.name}...")
    subprocess.run(
        [
            javac,
            "--release",
            "8",
            "-encoding",
            "UTF-8",
            "-cp",
            str(android_jar),
            "-d",
            str(CLASSES_DIR),
            *java_srcs,
        ],
        check=True,
    )

    class_files = sorted(str(p) for p in CLASSES_DIR.rglob("*.class"))
    subprocess.run(
        [
            d8,
            "--min-api",
            "26",
            "--lib",
            str(android_jar),
            "--output",
            str(DEX_DIR),
            *class_files,
        ],
        check=True,
    )

    dex_file = DEX_DIR / "classes.dex"
    if not dex_file.is_file():
        raise RuntimeError("d8 did not produce classes.dex")

    if UNSIGNED_APK.exists():
        UNSIGNED_APK.unlink()
    subprocess.run(
        [
            aapt,
            "package",
            "-f",
            "-M",
            str(MANIFEST_XML),
            "-S",
            str(RES_DIR),
            "-A",
            str(ASSETS_DIR),
            "-I",
            str(android_jar),
            "-F",
            str(UNSIGNED_APK),
        ],
        check=True,
    )

    with zipfile.ZipFile(UNSIGNED_APK, "a", compression=zipfile.ZIP_DEFLATED) as zf:
        zinfo = zipfile.ZipInfo("classes.dex", date_time=(2026, 1, 1, 0, 0, 0))
        zinfo.compress_type = zipfile.ZIP_DEFLATED
        zf.writestr(zinfo, dex_file.read_bytes())

    if ALIGNED_APK.exists():
        ALIGNED_APK.unlink()
    subprocess.run([zipalign, "-f", "4", str(UNSIGNED_APK), str(ALIGNED_APK)], check=True)

    env_ks = os.environ.get("HKG_HUD_KEYSTORE", "").strip()
    keystore = Path(env_ks) if env_ks else (BUILD_DIR / "debug_sign.p12")
    ks_pass = os.environ.get("HKG_HUD_KS_PASS", "android")
    key_pass = os.environ.get("HKG_HUD_KEY_PASS", "android")
    key_alias = os.environ.get("HKG_HUD_KEY_ALIAS", "hkghud")
    if not keystore.is_file():
        subprocess.run(
            [
                keytool,
                "-genkeypair",
                "-storetype",
                "PKCS12",
                "-keystore",
                str(keystore),
                "-storepass",
                ks_pass,
                "-keypass",
                key_pass,
                "-alias",
                key_alias,
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "10000",
                "-dname",
                "CN=HkgHudDisplay,O=HKG,C=KR",
            ],
            check=True,
        )

    if SIGNED_APK.exists():
        SIGNED_APK.unlink()
    subprocess.run(
        [
            apksigner,
            "sign",
            "--ks",
            str(keystore),
            "--ks-pass",
            f"pass:{ks_pass}",
            "--key-pass",
            f"pass:{key_pass}",
            "--ks-key-alias",
            key_alias,
            "--min-sdk-version",
            "26",
            "--out",
            str(SIGNED_APK),
            str(ALIGNED_APK),
        ],
        check=True,
    )
    subprocess.run([apksigner, "verify", "--min-sdk-version", "26", str(SIGNED_APK)], check=True)
    apk_sha = sha256_file(SIGNED_APK)
    print(f"Built & verified APK: {SIGNED_APK} ({SIGNED_APK.stat().st_size} bytes) sha256={apk_sha}")

    if do_write_update_json:
        rel_tag = tag or f"hud-v{v_name}"
        write_update_json(v_code, v_name, SIGNED_APK, repo, rel_tag, notes)

    if install:
        adb = str(sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))
        # T88-MIPI91 (Mar 2026) checks per-package system setting in addition to persist.sys.tc.allow.third.install
        subprocess.run([adb, "shell", "settings", "--user", "0", "put", "system", "com.hkg.huddisplay", "1"], check=False)
        subprocess.run([adb, "install", "-r", str(SIGNED_APK)], check=True)
    return SIGNED_APK


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--install", action="store_true", help="Install APK via adb after build")
    ap.add_argument("--version", default=None, help="Set android:versionName before building")
    ap.add_argument("--version-code", type=int, default=None, help="Set android:versionCode before building")
    ap.add_argument("--write-update-json", action="store_true", help="Write package_t88_hud/update.json with APK sha256")
    ap.add_argument("--repo", default="GRT47/HKG_5W_TurzxMonitor", help="GitHub repo slug (owner/repo)")
    ap.add_argument("--tag", default=None, help="GitHub release tag for update.json apkUrl (default hud-v<version>)")
    ap.add_argument("--notes", default="", help="Release notes text for update.json")
    args = ap.parse_args()
    build(
        install=args.install,
        version=args.version,
        version_code=args.version_code,
        do_write_update_json=args.write_update_json,
        repo=args.repo,
        tag=args.tag,
        notes=args.notes,
    )
