#!/usr/bin/env python3
"""Build NEMESIS install package, DEX jar, and USB bundle for HKG_5W_TurzxMonitor."""
from __future__ import annotations

import base64
import hashlib
import json
import os
import shutil
import subprocess
import zipfile
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
REPO_ROOT = BASE_DIR.parent
BUILD_DIR = BASE_DIR / "build"
CLASSES_DIR = BUILD_DIR / "classes"
DEX_DIR = BUILD_DIR / "dex"
PKG_STAGE_DIR = BUILD_DIR / "pkg_stage"
USB_ROOT_DIR = BUILD_DIR / "usb_root"

OUTER_ZIP_PATH = REPO_ROOT / "HKG_5W_TurzxMonitor_USB.zip"
MONITOR_VERSION = "1.1.0"


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


def find_d8() -> str:
    d8 = shutil.which("d8") or shutil.which("d8.bat")
    if d8:
        return d8
    sdk_roots = [
        os.environ.get("ANDROID_HOME", ""),
        os.environ.get("ANDROID_SDK_ROOT", ""),
        os.path.expanduser(r"~\AppData\Local\Android\Sdk"),
        "/usr/local/lib/android/sdk",
        "/usr/lib/android-sdk",
    ]
    for root in sdk_roots:
        if not root or not os.path.isdir(root):
            continue
        bt_dir = Path(root) / "build-tools"
        if bt_dir.is_dir():
            for ver_dir in sorted(bt_dir.iterdir(), reverse=True):
                for name in ("d8.bat", "d8"):
                    cand = ver_dir / name
                    if cand.is_file():
                        return str(cand)
    raise RuntimeError("Android SDK d8 not found")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def normalize_lf(path: Path) -> bytes:
    raw = path.read_bytes().replace(b"\r\n", b"\n")
    path.write_bytes(raw)
    return raw


def build() -> None:
    if BUILD_DIR.exists():
        shutil.rmtree(BUILD_DIR)
    CLASSES_DIR.mkdir(parents=True, exist_ok=True)
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
    ]
    for rel in source_files:
        normalize_lf(BASE_DIR / rel)

    # 2. Compile TurzxMonitorMain.java + compile-time stubs (Java 8 target -> Android API 19 DEX)
    javac = find_javac()
    java = find_java()
    d8 = find_d8()
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
    app_classes_dir = CLASSES_DIR / "com" / "hkg" / "turzxmonitor"
    class_files = [str(p) for p in sorted(app_classes_dir.rglob("*.class"))]
    subprocess.run(
        [d8, "--min-api", "19", "--no-desugaring", "--output", str(DEX_DIR), *class_files],
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
    version_json_bytes = (json.dumps(version_meta, indent=2) + "\n").encode("utf-8")
    (ota_dir / "version.json").write_bytes(version_json_bytes)
    (ota_dir / "turzx-daemon.sh").write_bytes(daemon_bytes)
    (ota_dir / "launch.sh").write_bytes(launch_bytes)

    b64_raw = base64.b64encode(jar_bytes).decode("ascii")
    b64_lines = "\n".join(b64_raw[i : i + 76] for i in range(0, len(b64_raw), 76)) + "\n"
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

    # 6. Build inner NEMESIS package zip (packages/hkg.turzxmonitor.zip)
    inner_zip_path = USB_ROOT_DIR / "packages" / "hkg.turzxmonitor.zip"
    inner_zip_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(inner_zip_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for file_path in sorted(PKG_STAGE_DIR.rglob("*")):
            if file_path.is_file():
                arcname = file_path.relative_to(PKG_STAGE_DIR).as_posix()
                zf.write(file_path, arcname)

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
    print(f"Built inner package: {inner_zip_path} ({inner_zip_path.stat().st_size} bytes)")
    print(f"Built USB bundle:    {target_zip} ({target_zip.stat().st_size} bytes)")


if __name__ == "__main__":
    build()

