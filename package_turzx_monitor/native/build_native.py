#!/usr/bin/env python3
"""Build libturzxjpeg.so (libjpeg-turbo NEON JPEG encoder JNI) for Gen5W (Android 4.4, armeabi-v7a).

Requirements: Android NDK r25c (last NDK line that still targets API 19), CMake.
  - NDK is found via ANDROID_NDK_HOME / ANDROID_NDK_ROOT / <sdk>/ndk/25.*.
  - libjpeg-turbo source: LIBJPEG_TURBO_SRC env var, or the pinned tag is downloaded into build/.

Output: native/prebuilt/armeabi-v7a/libturzxjpeg.so (committed, so builds without an NDK reuse it).

Usage:
    python package_turzx_monitor/native/build_native.py
"""
from __future__ import annotations

import os
import platform
import shutil
import subprocess
import tarfile
import urllib.request
from pathlib import Path

NATIVE_DIR = Path(__file__).resolve().parent
PKG_DIR = NATIVE_DIR.parent
WORK_DIR = PKG_DIR / "build" / "native"
PREBUILT_SO = NATIVE_DIR / "prebuilt" / "armeabi-v7a" / "libturzxjpeg.so"

LIBJPEG_TURBO_TAG = "3.0.4"
LIBJPEG_TURBO_URL = f"https://github.com/libjpeg-turbo/libjpeg-turbo/archive/refs/tags/{LIBJPEG_TURBO_TAG}.tar.gz"
API_LEVEL = 19
ABI = "armeabi-v7a"


def find_ndk() -> Path | None:
    cands: list[Path] = []
    for env in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "ANDROID_NDK"):
        if os.environ.get(env):
            cands.append(Path(os.environ[env]))
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(env):
            ndk_root = Path(os.environ[env]) / "ndk"
            if ndk_root.is_dir():
                cands.extend(sorted(ndk_root.glob("25.*"), reverse=True))
    win_sdk = Path(os.path.expanduser(r"~\AppData\Local\Android\Sdk")) / "ndk"
    if win_sdk.is_dir():
        cands.extend(sorted(win_sdk.glob("25.*"), reverse=True))
    for c in cands:
        if (c / "build" / "cmake" / "android.toolchain.cmake").is_file():
            return c
    return None


def ndk_host_tag() -> str:
    system = platform.system()
    if system == "Windows":
        return "windows-x86_64"
    if system == "Darwin":
        return "darwin-x86_64"
    return "linux-x86_64"


def get_turbo_source() -> Path:
    env_src = os.environ.get("LIBJPEG_TURBO_SRC")
    if env_src and (Path(env_src) / "turbojpeg.h").is_file():
        return Path(env_src)
    src_dir = WORK_DIR / f"libjpeg-turbo-{LIBJPEG_TURBO_TAG}"
    if (src_dir / "turbojpeg.h").is_file():
        return src_dir
    WORK_DIR.mkdir(parents=True, exist_ok=True)
    tgz = WORK_DIR / f"libjpeg-turbo-{LIBJPEG_TURBO_TAG}.tar.gz"
    req = urllib.request.Request(LIBJPEG_TURBO_URL, headers={"User-Agent": "HKG-TurzxMonitor-build"})
    with urllib.request.urlopen(req, timeout=60) as r:
        tgz.write_bytes(r.read())
    with tarfile.open(tgz) as tf:
        tf.extractall(WORK_DIR)
    return src_dir


def build_native() -> Path:
    ndk = find_ndk()
    if ndk is None:
        raise RuntimeError("Android NDK r25.x not found (set ANDROID_NDK_HOME)")
    cmake = shutil.which("cmake")
    if cmake is None:
        raise RuntimeError("cmake not found in PATH")

    src = get_turbo_source()
    tj_build = WORK_DIR / "turbo-build"
    if tj_build.exists():
        shutil.rmtree(tj_build)
    tj_build.mkdir(parents=True)

    gen_args: list[str] = []
    if shutil.which("ninja"):
        gen_args = ["-G", "Ninja"]
    elif platform.system() == "Windows":
        ndk_make = ndk / "prebuilt" / ndk_host_tag() / "bin" / "make.exe"
        gen_args = ["-G", "MinGW Makefiles", f"-DCMAKE_MAKE_PROGRAM={ndk_make}"]

    subprocess.run(
        [
            cmake, *gen_args,
            "-S", str(src), "-B", str(tj_build),
            f"-DCMAKE_TOOLCHAIN_FILE={ndk / 'build' / 'cmake' / 'android.toolchain.cmake'}",
            f"-DANDROID_ABI={ABI}",
            f"-DANDROID_PLATFORM=android-{API_LEVEL}",
            "-DCMAKE_BUILD_TYPE=Release",
            "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
            "-DENABLE_SHARED=OFF",
            "-DENABLE_STATIC=ON",
            "-DWITH_TURBOJPEG=ON",
            "-DREQUIRE_SIMD=ON",
        ],
        check=True,
    )
    subprocess.run([cmake, "--build", str(tj_build), "--target", "turbojpeg-static", "-j", "4"], check=True)

    static_lib = tj_build / "libturbojpeg.a"
    if not static_lib.is_file():
        raise RuntimeError(f"libturbojpeg.a not produced in {tj_build}")

    exe_suffix = ".cmd" if platform.system() == "Windows" else ""
    clang = ndk / "toolchains" / "llvm" / "prebuilt" / ndk_host_tag() / "bin" / f"armv7a-linux-androideabi{API_LEVEL}-clang{exe_suffix}"
    out_so = WORK_DIR / "libturzxjpeg.so"
    subprocess.run(
        [
            str(clang),
            "-shared", "-fPIC", "-O2", "-Wall",
            f'-DTURZXJPEG_TURBO_VERSION="{LIBJPEG_TURBO_TAG}"',
            f"-I{src}", f"-I{tj_build}",
            str(NATIVE_DIR / "turzxjpeg.c"),
            str(static_lib),
            "-ljnigraphics", "-lm",
            "-Wl,--gc-sections", "-Wl,--exclude-libs,ALL", "-Wl,-z,noexecstack", "-Wl,--hash-style=both",
            "-Wl,-soname,libturzxjpeg.so",
            "-s",
            "-o", str(out_so),
        ],
        check=True,
    )
    PREBUILT_SO.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(out_so, PREBUILT_SO)
    print(f"Built native encoder: {PREBUILT_SO} ({PREBUILT_SO.stat().st_size} bytes)")
    return PREBUILT_SO


if __name__ == "__main__":
    build_native()
