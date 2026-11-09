#!/usr/bin/env python3
"""Builds libjpeg-turbo for the HOST with `zig cc` and runs test_inc.c (incremental encoder byte-exactness test).

Requirements: pip install ziglang cmake ninja. Uses the same libjpeg-turbo source tree as build_native.py.
Usage: python package_turzx_monitor/native/test/run_host_test.py
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import sysconfig
from pathlib import Path

TEST_DIR = Path(__file__).resolve().parent
NATIVE_DIR = TEST_DIR.parent
sys.path.insert(0, str(NATIVE_DIR))
import build_native  # noqa: E402

HOST_DIR = build_native.WORK_DIR / "host"


def zig_exe() -> Path:
    import ziglang  # noqa: F401  (pip install ziglang)

    exe = Path(ziglang.__file__).parent / ("zig.exe" if os.name == "nt" else "zig")
    if not exe.is_file():
        raise RuntimeError(f"zig binary not found next to {ziglang.__file__}")
    return exe


def tool(name: str) -> str:
    found = shutil.which(name)
    if found:
        return found
    scripts = Path(sysconfig.get_path("scripts", f"{os.name}_user"))
    for cand in (scripts / name, scripts / f"{name}.exe"):
        if cand.is_file():
            return str(cand)
    try:
        import cmake  # noqa: F401

        if name == "cmake":
            return str(Path(cmake.CMAKE_BIN_DIR) / ("cmake.exe" if os.name == "nt" else "cmake"))
    except ImportError:
        pass
    raise RuntimeError(f"{name} not found (pip install {name})")


def main() -> int:
    zig = zig_exe()
    HOST_DIR.mkdir(parents=True, exist_ok=True)
    # CMake needs a single compiler path: wrap `zig cc`
    wrappers = {}
    for sub in ("cc", "ar", "ranlib"):
        if os.name == "nt":
            w = HOST_DIR / f"z{sub}.cmd"
            w.write_text(f'@"{zig}" {sub} %*\r\n', encoding="ascii")
        else:
            w = HOST_DIR / f"z{sub}"
            w.write_text(f'#!/bin/sh\nexec "{zig}" {sub} "$@"\n', encoding="ascii")
            w.chmod(0o755)
        wrappers[sub] = w.as_posix()

    src = build_native.get_turbo_source()
    tj_build = HOST_DIR / "turbo"
    lib = tj_build / "libturbojpeg.a"
    if not lib.is_file():
        if tj_build.exists():
            shutil.rmtree(tj_build)
        subprocess.run([
            tool("cmake"), "-G", "Ninja", f"-DCMAKE_MAKE_PROGRAM={tool('ninja')}",
            "-S", str(src), "-B", str(tj_build),
            f"-DCMAKE_C_COMPILER={wrappers['cc']}",
            f"-DCMAKE_AR={wrappers['ar']}",
            f"-DCMAKE_RANLIB={wrappers['ranlib']}",
            "-DCMAKE_BUILD_TYPE=Release",
            "-DENABLE_SHARED=OFF", "-DENABLE_STATIC=ON", "-DWITH_TURBOJPEG=ON", "-DWITH_SIMD=OFF",
        ], check=True)
        subprocess.run([tool("cmake"), "--build", str(tj_build), "--target", "turbojpeg-static"], check=True)
    if not lib.is_file():
        raise RuntimeError(f"{lib} not built")

    exe = HOST_DIR / ("test_inc.exe" if os.name == "nt" else "test_inc")
    subprocess.run([
        str(zig), "cc", "-O2", "-Wall", "-Wno-unused-function",
        f"-I{src}", f"-I{tj_build}",
        str(TEST_DIR / "test_inc.c"), str(lib),
        "-o", str(exe),
    ], check=True)
    return subprocess.run([str(exe)]).returncode


if __name__ == "__main__":
    sys.exit(main())
