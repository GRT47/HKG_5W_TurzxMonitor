#!/usr/bin/env python3
"""Drive a PC-connected TURZX USB secondary monitor (9.2" / 8.8" / 12.3") in real time.

Supported USB monitors (VID 0x1CBE, WinUSB on Windows or libusb/pyusb on Linux/macOS):
  - 0x0123 : TURZX 12.3" IPS (native 1920x720 landscape -> 720x1920 portrait JPEG/PNG)
  - 0x0092 : TURZX  9.2" IPS (native 1920x462 landscape -> 464x1920 portrait JPEG / 480x1920 PNG)
  - 0x0088 : TURZX  8.8" IPS (1920x462 landscape -> 480x1920 portrait JPEG/PNG)

Examples:
  # List connected TURZX USB monitors
  python package_turzx_monitor/tools/pc_usb_monitor.py --list

  # Stream live animated HUD to the connected monitor (Ctrl+C to stop)
  python package_turzx_monitor/tools/pc_usb_monitor.py

  # Stream for 10 seconds and leave the final HUD frame on screen
  python package_turzx_monitor/tools/pc_usb_monitor.py --seconds 10

  # Mirror live telemetry from a Gen5W headunit over Wi-Fi
  python package_turzx_monitor/tools/pc_usb_monitor.py --hu-url http://192.168.43.1:8766/status.json

  # Turn off / blank the connected monitor
  python package_turzx_monitor/tools/pc_usb_monitor.py --blank
"""
from __future__ import annotations

import argparse
import ctypes
import io
import json
import platform
import re
import struct
import sys
import time
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from Crypto.Cipher import DES
from PIL import Image

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
MOCKUP_HTML = REPO_ROOT / "turzx_92_mockup.html"

TURZX_VID = 0x1CBE
EP_OUT = 0x01
EP_IN = 0x81
DES_KEY = b"slv3tuzx"
WINUSB_GUID_STR = "{88bae032-5a81-49f0-bc3d-a4ff138216d6}"

CMD_HELLO = 10
CMD_BRIGHTNESS = 14
CMD_SEND_JPEG = 101
CMD_SEND_PNG = 102
CMD_STOP_VIDEO = 111


@dataclass(frozen=True)
class PanelSpec:
    pid: int
    label: str
    panel_key: str        # "92" or "123" for turzx_92_mockup.html
    land_w: int           # landscape pixel width (2x DPR)
    land_h: int           # landscape pixel height (2x DPR)
    jpeg_w: int           # portrait scanout JPEG width (macroblock-aligned)
    jpeg_h: int           # portrait scanout JPEG height
    png_w: int            # portrait overlay PNG width
    png_h: int            # portrait overlay PNG height


PANEL_SPECS: dict[int, PanelSpec] = {
    0x0123: PanelSpec(0x0123, '12.3"', "123", 1920, 720, 720, 1920, 720, 1920),
    0x0092: PanelSpec(0x0092, '9.2"', "92", 1920, 462, 464, 1920, 480, 1920),
    0x0088: PanelSpec(0x0088, '8.8"', "92", 1920, 462, 480, 1920, 480, 1920),
}


def build_command_header(cmd_id: int, payload_len: int = 0) -> bytearray:
    pkt = bytearray(500)
    pkt[0] = cmd_id & 0xFF
    pkt[1] = 0x00
    pkt[2] = 0x1A
    pkt[3] = 0x6D
    t_local = time.localtime()
    midnight = time.mktime(t_local[:3] + (0, 0, 0, 0, 0, -1))
    ts_ms = int((time.time() - midnight) * 1000) & 0xFFFFFFFF
    pkt[4:8] = struct.pack("<I", ts_ms)
    if payload_len > 0:
        pkt[8:12] = struct.pack(">I", payload_len)
    return pkt


def encrypt_command_packet(plain500: bytearray) -> bytes:
    cipher = DES.new(DES_KEY, DES.MODE_CBC, DES_KEY)
    enc = cipher.encrypt(bytes(plain500).ljust(504, b"\x00"))
    out = bytearray(512)
    out[:504] = enc
    out[510] = 0xA1
    out[511] = 0x1A
    return bytes(out)


def landscape_to_panel_jpeg_bytes(
    img: Image.Image,
    spec: PanelSpec,
    rotation: int = 270,
    quality: int = 88,
) -> bytes:
    rgb = img.convert("RGB")
    if rgb.size != (spec.land_w, spec.land_h):
        rgb = rgb.resize((spec.land_w, spec.land_h), Image.Resampling.LANCZOS)
    if rotation == 90:
        rot = rgb.transpose(Image.Transpose.ROTATE_90)
    else:
        rot = rgb.transpose(Image.Transpose.ROTATE_270)

    if rot.size == (spec.jpeg_w, spec.jpeg_h):
        canvas = rot
    else:
        canvas = Image.new("RGB", (spec.jpeg_w, spec.jpeg_h), (0, 0, 0))
        canvas.paste(rot, (0, 0))

    bio = io.BytesIO()
    canvas.save(
        bio,
        format="JPEG",
        quality=max(50, min(95, quality)),
        subsampling=2,
        restart_marker_rows=1,
    )
    return bio.getvalue()


def build_transparent_png_bytes(width: int, height: int) -> bytes:
    img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    bio = io.BytesIO()
    img.save(bio, format="PNG", compress_level=1)
    return bio.getvalue()


# =============================================================================
# Windows WinUSB Discovery & Bulk Transport
# =============================================================================

@dataclass
class DiscoveredTurzxDevice:
    pid: int
    spec: PanelSpec
    backend: str      # "winusb" or "pyusb"
    path: str
    usb_dev: object = None


def _discover_windows_winusb() -> list[DiscoveredTurzxDevice]:
    if platform.system() != "Windows":
        return []

    from ctypes import wintypes

    GENERIC_READ = 0x80000000
    GENERIC_WRITE = 0x40000000
    FILE_SHARE_READ = 0x00000001
    FILE_SHARE_WRITE = 0x00000002
    OPEN_EXISTING = 3
    FILE_FLAG_OVERLAPPED = 0x40000000
    INVALID_HANDLE_VALUE = ctypes.c_void_p(-1).value

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.CreateFileW.argtypes = [
        wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD,
        wintypes.LPVOID, wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE,
    ]
    kernel32.CreateFileW.restype = wintypes.HANDLE
    kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel32.CloseHandle.restype = wintypes.BOOL

    candidates: list[str] = []

    # Method 1: SetupAPI SetupDiEnumDeviceInterfaces for WinUSB GUID
    try:
        class GUID(ctypes.Structure):
            _fields_ = [
                ("Data1", wintypes.DWORD),
                ("Data2", wintypes.WORD),
                ("Data3", wintypes.WORD),
                ("Data4", ctypes.c_ubyte * 8),
            ]

        class SP_DEVICE_INTERFACE_DATA(ctypes.Structure):
            _fields_ = [
                ("cbSize", wintypes.DWORD),
                ("InterfaceClassGuid", GUID),
                ("Flags", wintypes.DWORD),
                ("Reserved", ctypes.c_size_t),
            ]

        winusb_guid = GUID(
            0x88BAE032,
            0x5A81,
            0x49F0,
            (ctypes.c_ubyte * 8)(0xBC, 0x3D, 0xA4, 0xFF, 0x13, 0x82, 0x16, 0xD6),
        )
        setupapi = ctypes.WinDLL("setupapi", use_last_error=True)
        setupapi.SetupDiGetClassDevsW.argtypes = [
            ctypes.POINTER(GUID), wintypes.LPCWSTR, wintypes.HWND, wintypes.DWORD,
        ]
        setupapi.SetupDiGetClassDevsW.restype = wintypes.HANDLE
        setupapi.SetupDiEnumDeviceInterfaces.argtypes = [
            wintypes.HANDLE, wintypes.LPVOID, ctypes.POINTER(GUID),
            wintypes.DWORD, ctypes.POINTER(SP_DEVICE_INTERFACE_DATA),
        ]
        setupapi.SetupDiEnumDeviceInterfaces.restype = wintypes.BOOL
        setupapi.SetupDiGetDeviceInterfaceDetailW.argtypes = [
            wintypes.HANDLE, ctypes.POINTER(SP_DEVICE_INTERFACE_DATA),
            wintypes.LPVOID, wintypes.DWORD, ctypes.POINTER(wintypes.DWORD), wintypes.LPVOID,
        ]
        setupapi.SetupDiGetDeviceInterfaceDetailW.restype = wintypes.BOOL
        setupapi.SetupDiDestroyDeviceInfoList.argtypes = [wintypes.HANDLE]

        DIGCF_PRESENT = 0x02
        DIGCF_DEVICEINTERFACE = 0x10
        h_info = setupapi.SetupDiGetClassDevsW(
            ctypes.byref(winusb_guid), None, None, DIGCF_PRESENT | DIGCF_DEVICEINTERFACE
        )
        if h_info and h_info != INVALID_HANDLE_VALUE:
            try:
                idx = 0
                detail_cb_size = 8 if ctypes.sizeof(ctypes.c_void_p) == 8 else 6
                while True:
                    ifc_data = SP_DEVICE_INTERFACE_DATA()
                    ifc_data.cbSize = ctypes.sizeof(SP_DEVICE_INTERFACE_DATA)
                    if not setupapi.SetupDiEnumDeviceInterfaces(
                        h_info, None, ctypes.byref(winusb_guid), idx, ctypes.byref(ifc_data)
                    ):
                        break
                    idx += 1
                    req_size = wintypes.DWORD(0)
                    setupapi.SetupDiGetDeviceInterfaceDetailW(
                        h_info, ctypes.byref(ifc_data), None, 0, ctypes.byref(req_size), None
                    )
                    if req_size.value < 8:
                        continue
                    buf = ctypes.create_string_buffer(req_size.value)
                    struct.pack_into("<I", buf, 0, detail_cb_size)
                    if setupapi.SetupDiGetDeviceInterfaceDetailW(
                        h_info, ctypes.byref(ifc_data), buf, req_size.value, None, None
                    ):
                        path = ctypes.wstring_at(ctypes.addressof(buf) + 4)
                        if path:
                            candidates.append(path)
            finally:
                setupapi.SetupDiDestroyDeviceInfoList(h_info)
    except Exception:
        pass

    # Method 2: Registry DeviceClasses fallback (verified live via CreateFileW)
    try:
        import winreg

        reg_key_path = rf"SYSTEM\CurrentControlSet\Control\DeviceClasses\{WINUSB_GUID_STR}"
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, reg_key_path) as root:
            i = 0
            while True:
                try:
                    sub = winreg.EnumKey(root, i)
                    i += 1
                except OSError:
                    break
                if "vid_1cbe" not in sub.lower():
                    continue
                if sub.startswith("##?#"):
                    dev_path = "\\\\?\\" + sub[4:]
                elif sub.startswith("#?#"):
                    dev_path = "\\\\?\\" + sub[3:]
                else:
                    continue
                if all(c.lower() != dev_path.lower() for c in candidates):
                    candidates.append(dev_path)
    except Exception:
        pass

    found: list[DiscoveredTurzxDevice] = []
    seen_lower: set[str] = set()
    pid_re = re.compile(r"vid_1cbe&pid_([0-9a-f]{4})", re.IGNORECASE)

    for path in candidates:
        low = path.lower()
        if low in seen_lower:
            continue
        m = pid_re.search(low)
        if not m:
            continue
        pid = int(m.group(1), 16)
        spec = PANEL_SPECS.get(pid)
        if spec is None:
            continue
        h = kernel32.CreateFileW(
            path,
            GENERIC_READ | GENERIC_WRITE,
            FILE_SHARE_READ | FILE_SHARE_WRITE,
            None,
            OPEN_EXISTING,
            FILE_FLAG_OVERLAPPED,
            None,
        )
        if h and h != INVALID_HANDLE_VALUE:
            kernel32.CloseHandle(wintypes.HANDLE(h))
            seen_lower.add(low)
            found.append(DiscoveredTurzxDevice(pid=pid, spec=spec, backend="winusb", path=path))

    # Prefer 12.3" (0x0123), then 9.2" (0x0092), then 8.8" (0x0088) when multiple are attached
    order = {0x0123: 0, 0x0092: 1, 0x0088: 2}
    found.sort(key=lambda d: order.get(d.pid, 99))
    return found


def _discover_pyusb() -> list[DiscoveredTurzxDevice]:
    try:
        import usb.core  # type: ignore
    except Exception:
        return []

    found: list[DiscoveredTurzxDevice] = []
    try:
        devs = usb.core.find(find_all=True, idVendor=TURZX_VID)
        for dev in devs or []:
            pid = int(getattr(dev, "idProduct", 0))
            spec = PANEL_SPECS.get(pid)
            if spec is not None:
                bus = getattr(dev, "bus", 0) or 0
                addr = getattr(dev, "address", 0) or 0
                found.append(
                    DiscoveredTurzxDevice(
                        pid=pid,
                        spec=spec,
                        backend="pyusb",
                        path=f"usb:{bus:03d}:{addr:03d}",
                        usb_dev=dev,
                    )
                )
    except Exception:
        pass
    return found


def discover_turzx_devices() -> list[DiscoveredTurzxDevice]:
    devs = _discover_windows_winusb()
    if devs:
        return devs
    return _discover_pyusb()


class TurzxTransport:
    """Unified USB bulk transport over Windows WinUSB or cross-platform pyusb."""

    def __init__(self, target: DiscoveredTurzxDevice):
        self.target = target
        self.spec = target.spec
        self.firmware = ""
        self._win_file = None
        self._win_usb = None
        self._pyusb_dev = None

        if target.backend == "winusb":
            self._open_winusb(target.path)
        else:
            self._open_pyusb(target.usb_dev)

    def _open_winusb(self, dev_path: str) -> None:
        from ctypes import wintypes

        GENERIC_READ = 0x80000000
        GENERIC_WRITE = 0x40000000
        FILE_SHARE_READ = 0x00000001
        FILE_SHARE_WRITE = 0x00000002
        OPEN_EXISTING = 3
        FILE_FLAG_OVERLAPPED = 0x40000000
        INVALID_HANDLE_VALUE = ctypes.c_void_p(-1).value

        self._k32 = ctypes.WinDLL("kernel32", use_last_error=True)
        self._wusb = ctypes.WinDLL("winusb", use_last_error=True)
        self._k32.CreateFileW.argtypes = [
            wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD,
            wintypes.LPVOID, wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE,
        ]
        self._k32.CreateFileW.restype = wintypes.HANDLE

        h_file = self._k32.CreateFileW(
            dev_path,
            GENERIC_READ | GENERIC_WRITE,
            FILE_SHARE_READ | FILE_SHARE_WRITE,
            None,
            OPEN_EXISTING,
            FILE_FLAG_OVERLAPPED,
            None,
        )
        if not h_file or h_file == INVALID_HANDLE_VALUE:
            raise RuntimeError(f"CreateFileW({dev_path}) failed: winerr={ctypes.get_last_error()}")

        h_winusb = wintypes.HANDLE()
        if not self._wusb.WinUsb_Initialize(wintypes.HANDLE(h_file), ctypes.byref(h_winusb)):
            err = ctypes.get_last_error()
            self._k32.CloseHandle(wintypes.HANDLE(h_file))
            raise RuntimeError(f"WinUsb_Initialize failed: winerr={err}")

        self._win_file = h_file
        self._win_usb = h_winusb
        self._set_winusb_timeout(EP_OUT, 2000)
        self._set_winusb_timeout(EP_IN, 500)

    def _set_winusb_timeout(self, pipe_id: int, timeout_ms: int) -> None:
        from ctypes import wintypes

        PIPE_TRANSFER_TIMEOUT = 0x03
        val = wintypes.ULONG(timeout_ms)
        self._wusb.WinUsb_SetPipePolicy(
            self._win_usb,
            ctypes.c_ubyte(pipe_id),
            wintypes.ULONG(PIPE_TRANSFER_TIMEOUT),
            wintypes.ULONG(ctypes.sizeof(val)),
            ctypes.byref(val),
        )

    def _open_pyusb(self, dev: object) -> None:
        if dev is None:
            raise RuntimeError("No pyusb device handle")
        try:
            if dev.is_kernel_driver_active(0):  # type: ignore[attr-defined]
                dev.detach_kernel_driver(0)     # type: ignore[attr-defined]
        except Exception:
            pass
        dev.set_configuration()                 # type: ignore[attr-defined]
        self._pyusb_dev = dev

    def write_bulk(self, data: bytes, timeout_ms: int = 2000) -> int:
        if self._win_usb is not None:
            from ctypes import wintypes

            self._set_winusb_timeout(EP_OUT, timeout_ms)
            buf = (ctypes.c_ubyte * len(data)).from_buffer_copy(data)
            transferred = wintypes.ULONG(0)
            ok = self._wusb.WinUsb_WritePipe(
                self._win_usb,
                ctypes.c_ubyte(EP_OUT),
                buf,
                wintypes.ULONG(len(data)),
                ctypes.byref(transferred),
                None,
            )
            if not ok:
                raise RuntimeError(f"WinUsb_WritePipe failed: winerr={ctypes.get_last_error()}")
            return int(transferred.value)
        return int(self._pyusb_dev.write(EP_OUT, data, timeout=timeout_ms))  # type: ignore[union-attr]

    def read_bulk(self, max_len: int = 1024, timeout_ms: int = 500) -> bytes:
        if self._win_usb is not None:
            from ctypes import wintypes

            self._set_winusb_timeout(EP_IN, timeout_ms)
            buf = (ctypes.c_ubyte * max_len)()
            transferred = wintypes.ULONG(0)
            ok = self._wusb.WinUsb_ReadPipe(
                self._win_usb,
                ctypes.c_ubyte(EP_IN),
                buf,
                wintypes.ULONG(max_len),
                ctypes.byref(transferred),
                None,
            )
            if not ok:
                return b""
            return bytes(buf[: transferred.value])
        try:
            raw = self._pyusb_dev.read(EP_IN, max_len, timeout=timeout_ms)  # type: ignore[union-attr]
            return bytes(raw)
        except Exception:
            return b""

    def drain_in(self) -> None:
        for _ in range(4):
            if not self.read_bulk(1024, timeout_ms=30):
                break

    def send_command(self, plain500: bytearray, payload: bytes = b"", ack_timeout_ms: int = 800) -> bytes:
        hdr = encrypt_command_packet(plain500)
        self.write_bulk(hdr + payload if len(payload) <= 65536 else hdr)
        if len(payload) > 65536:
            self.write_bulk(payload)
        ack = self.read_bulk(1024, timeout_ms=ack_timeout_ms)
        if ack_timeout_ms >= 200:
            self.drain_in()
        return ack

    def handshake_and_init(self, brightness: int = 95) -> str:
        self.drain_in()
        ack = self.send_command(build_command_header(CMD_HELLO))
        if len(ack) >= 20:
            chars = []
            for b in ack[8:28]:
                if 0x20 <= b <= 0x7E:
                    chars.append(chr(b))
                else:
                    break
            self.firmware = "".join(chars).strip()

        self.send_command(build_command_header(CMD_STOP_VIDEO))
        self.set_brightness(brightness)
        clr_png = build_transparent_png_bytes(self.spec.png_w, self.spec.png_h)
        self.send_command(build_command_header(CMD_SEND_PNG, len(clr_png)), clr_png)
        return self.firmware

    def set_brightness(self, brightness: int) -> bytes:
        cmd = build_command_header(CMD_BRIGHTNESS)
        cmd[8] = max(0, min(100, int(brightness)))
        return self.send_command(cmd)

    def send_jpeg_bytes(self, jpeg_bytes: bytes) -> tuple[float, float]:
        hdr = encrypt_command_packet(build_command_header(CMD_SEND_JPEG, len(jpeg_bytes)))
        t0 = time.perf_counter()
        self.write_bulk(hdr + jpeg_bytes)
        t1 = time.perf_counter()
        self.read_bulk(1024, timeout_ms=600)
        t2 = time.perf_counter()
        return (t1 - t0) * 1000.0, (t2 - t1) * 1000.0

    def blank(self) -> None:
        blk = Image.new("RGB", (self.spec.land_w, self.spec.land_h), (0, 0, 0))
        jpg = landscape_to_panel_jpeg_bytes(blk, self.spec, rotation=270, quality=80)
        self.send_jpeg_bytes(jpg)
        self.set_brightness(0)

    def close(self) -> None:
        if self._win_usb is not None:
            try:
                self._wusb.WinUsb_Free(self._win_usb)
            except Exception:
                pass
            self._win_usb = None
        if self._win_file is not None:
            from ctypes import wintypes

            try:
                self._k32.CloseHandle(wintypes.HANDLE(self._win_file))
            except Exception:
                pass
            self._win_file = None


# =============================================================================
# Chromium 1:1 HUD Renderer & Streaming Loop
# =============================================================================

def fetch_headunit_json(url: str, timeout_s: float = 0.6) -> Optional[dict]:
    try:
        req = urllib.request.Request(url, headers={"Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=timeout_s) as resp:
            return json.loads(resp.read().decode("utf-8", errors="replace"))
    except Exception:
        return None


def build_demo_telemetry(elapsed_s: float) -> dict:
    import math

    spd = int(round(78 + 14 * math.sin(elapsed_s * 0.55)))
    pwr = round(16.5 + 22.0 * math.sin(elapsed_s * 0.85), 1)
    soc = round(max(10.0, 78.4 - (elapsed_s * 0.01)), 1)
    steer = round(4.8 * math.sin(elapsed_s * 0.7), 1)
    lead_d = round(42.0 + 16.0 * math.cos(elapsed_s * 0.45), 1)
    pos_sec = 84 + int(elapsed_s) % 158
    tbt_dist = max(60, 420 - int((elapsed_s * 22) % 360))
    sdi_phase = int(elapsed_s // 6.0) % 3
    sdi_type = 0 if sdi_phase == 0 else (1 if sdi_phase == 1 else -1)
    sdi_dist = max(120, 2300 - int((elapsed_s * 45) % 1800)) if sdi_type >= 0 else 0

    return {
        "ok": True,
        "soc": soc,
        "dteKm": int(round(soc * 4.9)),
        "totalKw": pwr,
        "avgEcon": 6.1,
        "remainKwh": round(soc * 0.64, 1),
        "battTempC": 24.0,
        "gear": "D",
        "tpms": {"fl": 38.0, "fr": 38.0, "rl": 39.0, "rr": 39.0},
        "climate": {
            "on": True,
            "drTempC": 21.5,
            "psTempC": 21.5,
            "inCarTempC": 22.5,
            "outdoorTempC": 18.0,
            "blowerStep": 3,
            "blowerMode": 2,
            "auto": True,
            "ac": True,
            "heat": False,
            "sync": True,
            "recirc": True,
            "frontDefog": False,
            "rearDefog": False,
        },
        "media": {
            "source": "Bluetooth Audio",
            "title": "Supernova",
            "artist": "aespa",
            "album": "Armageddon",
            "playing": True,
            "posSec": pos_sec,
            "durSec": 179,
        },
        "carrot": {
            "connected": True,
            "active": True,
            "speedKph": spd,
            "cruiseKph": 80,
            "tbtRoad": "올림픽대로",
            "tbtDistM": tbt_dist,
            "tbtType": 2,
            "tbtNextDistM": 1800,
            "sdiType": 2 if sdi_type == 0 else sdi_type,
            "sdiSpeedLimit": 80,
            "sdiDistM": sdi_dist,
            "steerDeg": steer,
            "laneConf": 96.0,
            "hasLead": True,
            "leadDistM": lead_d,
        },
    }


def stream_hud(
    transport: TurzxTransport,
    hu_url: Optional[str],
    seconds: float,
    fps: float,
    quality: int,
    brightness: int,
    rotation: int,
    blank_on_exit: bool,
) -> None:
    from playwright.sync_api import sync_playwright

    if not MOCKUP_HTML.is_file():
        raise FileNotFoundError(f"Mockup HTML not found: {MOCKUP_HTML}")

    spec = transport.spec
    fw = transport.handshake_and_init(brightness=brightness)
    print(
        f"[TURZX] Connected {spec.label} (1CBE:{spec.pid:04X}) via {transport.target.backend} "
        f"| layout={spec.land_w}x{spec.land_h} -> {spec.jpeg_w}x{spec.jpeg_h} JPEG "
        f"| fw={fw or 'n/a'}"
    )

    css_w = spec.land_w // 2
    css_h = spec.land_h // 2

    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True)
        context = browser.new_context(
            viewport={"width": max(1280, css_w + 160), "height": max(720, css_h + 240)},
            device_scale_factor=2,
        )
        page = context.new_page()
        url = f"{MOCKUP_HTML.as_uri()}?panel={spec.panel_key}"
        page.goto(url, wait_until="load")
        page.evaluate(
            """([panelKey, wPx, hPx]) => {
                if (typeof setPanelModel === 'function') {
                    setPanelModel(panelKey);
                }
                document.querySelectorAll('.top-bar, .controls-bar, .footer-note').forEach(el => {
                    el.style.display = 'none';
                });
                const stage = document.getElementById('monitorStage');
                if (stage) {
                    stage.style.padding = '0';
                    stage.style.border = 'none';
                    stage.style.boxShadow = 'none';
                }
                const clip = document.getElementById('turzxLcdClip');
                const lcd = document.getElementById('turzxLcd');
                if (clip) {
                    clip.style.width = wPx + 'px';
                    clip.style.height = hPx + 'px';
                    clip.style.border = 'none';
                    clip.style.borderRadius = '0';
                }
                if (lcd) {
                    lcd.style.width = wPx + 'px';
                    lcd.style.height = hPx + 'px';
                    lcd.style.transform = 'none';
                }
                if (typeof resizeCommaRoadCanvas === 'function') {
                    resizeCommaRoadCanvas();
                }
            }""",
            [spec.panel_key, css_w, css_h],
        )
        lcd = page.locator("#turzxLcd")

        start_t = time.perf_counter()
        last_poll_t = -1.0
        last_log_t = start_t
        frames = 0
        win_frames = 0
        avg_tx_ms = 0.0
        avg_ack_ms = 0.0
        avg_kb = 0.0
        target_dt = 1.0 / max(1.0, fps)

        mode_desc = f"Headunit live ({hu_url})" if hu_url else "Demo telemetry + 3D ADAS road"
        dur_desc = f"{seconds:.1f}s" if seconds > 0 else "continuous (Ctrl+C to stop)"
        print(f"[TURZX] Streaming {mode_desc} at up to {fps:.0f} FPS ({dur_desc})...")

        try:
            while True:
                frame_t0 = time.perf_counter()
                elapsed = frame_t0 - start_t
                if seconds > 0 and elapsed >= seconds and frames > 0:
                    break

                if frame_t0 - last_poll_t >= 0.20:
                    last_poll_t = frame_t0
                    data = fetch_headunit_json(hu_url) if hu_url else build_demo_telemetry(elapsed)
                    if data is not None:
                        page.evaluate(
                            "d => { if (typeof applyHeadunitStatusJson === 'function') applyHeadunitStatusJson(d); }",
                            data,
                        )

                raw_jpg = lcd.screenshot(type="jpeg", quality=94)
                img = Image.open(io.BytesIO(raw_jpg))
                panel_jpg = landscape_to_panel_jpeg_bytes(
                    img, spec, rotation=rotation, quality=quality
                )
                tx_ms, ack_ms = transport.send_jpeg_bytes(panel_jpg)

                frames += 1
                win_frames += 1
                kb = len(panel_jpg) / 1024.0
                avg_tx_ms = tx_ms if frames == 1 else (avg_tx_ms * 0.85 + tx_ms * 0.15)
                avg_ack_ms = ack_ms if frames == 1 else (avg_ack_ms * 0.85 + ack_ms * 0.15)
                avg_kb = kb if frames == 1 else (avg_kb * 0.85 + kb * 0.15)

                now = time.perf_counter()
                if now - last_log_t >= 2.0:
                    live_fps = win_frames / max(0.001, now - last_log_t)
                    print(
                        f"  [{elapsed:5.1f}s] {live_fps:4.1f} FPS | frames={frames} "
                        f"| jpeg={avg_kb:.1f}KB | tx={avg_tx_ms:.1f}ms | ack={avg_ack_ms:.1f}ms"
                    )
                    win_frames = 0
                    last_log_t = now

                sleep_s = target_dt - (time.perf_counter() - frame_t0)
                if sleep_s > 0.002:
                    time.sleep(sleep_s)
        except KeyboardInterrupt:
            print("\n[TURZX] Stopped by user.")
        finally:
            total_s = max(0.001, time.perf_counter() - start_t)
            print(
                f"[TURZX] Summary: {frames} frames in {total_s:.2f}s ({frames / total_s:.1f} avg FPS), "
                f"jpeg={avg_kb:.1f}KB, usb_tx={avg_tx_ms:.1f}ms, ack={avg_ack_ms:.1f}ms"
            )
            if blank_on_exit:
                transport.blank()
                print("[TURZX] Display blanked (brightness=0).")
            browser.close()


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="Drive a PC-connected TURZX 9.2\" / 8.8\" / 12.3\" USB secondary monitor."
    )
    p.add_argument("--list", action="store_true", help="List connected TURZX USB monitors and exit.")
    p.add_argument(
        "--pid",
        default="auto",
        help="Target USB PID in hex: auto, 0123 (12.3\"), 0092 (9.2\"), or 0088 (8.8\").",
    )
    p.add_argument(
        "--hu-url",
        default="",
        help="Optional Gen5W headunit status URL (e.g. http://192.168.43.1:8766/status.json).",
    )
    p.add_argument(
        "--hu-ip",
        default="",
        help="Shorthand for --hu-url http://<IP>:8766/status.json.",
    )
    p.add_argument(
        "--seconds",
        type=float,
        default=0.0,
        help="Stream duration in seconds (0 = continuous until Ctrl+C).",
    )
    p.add_argument("--fps", type=float, default=15.0, help="Target frame rate (default: 15).")
    p.add_argument("--quality", type=int, default=88, help="JPEG quality 50..95 (default: 88).")
    p.add_argument("--brightness", type=int, default=95, help="Panel brightness 0..100 (default: 95).")
    p.add_argument(
        "--rotation",
        type=int,
        choices=(90, 270),
        default=270,
        help="Portrait scanout rotation (270 or 90, default: 270).",
    )
    p.add_argument("--blank", action="store_true", help="Blank the connected monitor and exit.")
    p.add_argument(
        "--blank-on-exit",
        action="store_true",
        help="Blank the monitor when streaming stops.",
    )
    return p.parse_args()


def main() -> int:
    args = parse_args()
    devs = discover_turzx_devices()

    if args.list:
        if not devs:
            print("[TURZX] No connected TURZX USB monitor found (VID 1CBE, PID 0123/0092/0088).")
            return 1
        for idx, d in enumerate(devs, 1):
            print(
                f"  [{idx}] TURZX {d.spec.label} (1CBE:{d.pid:04X}) "
                f"native {d.spec.land_w}x{d.spec.land_h} -> {d.spec.jpeg_w}x{d.spec.jpeg_h} "
                f"[{d.backend}] {d.path}"
            )
        return 0

    if not devs:
        print(
            "[TURZX] Error: No connected TURZX USB monitor found (expected VID 1CBE, PID 0123/0092/0088).",
            file=sys.stderr,
        )
        return 1

    target = devs[0]
    if args.pid.lower() != "auto":
        want_pid = int(args.pid, 16)
        matches = [d for d in devs if d.pid == want_pid]
        if not matches:
            avail = ", ".join(f"{d.pid:04X}" for d in devs)
            print(
                f"[TURZX] Error: Requested PID {want_pid:04X} not connected (found: {avail}).",
                file=sys.stderr,
            )
            return 1
        target = matches[0]

    hu_url = args.hu_url.strip()
    if not hu_url and args.hu_ip.strip():
        hu_url = f"http://{args.hu_ip.strip()}:8766/status.json"

    transport = TurzxTransport(target)
    try:
        if args.blank:
            transport.handshake_and_init(brightness=0)
            transport.blank()
            print(f"[TURZX] Blanked TURZX {target.spec.label} (1CBE:{target.pid:04X}).")
            return 0

        stream_hud(
            transport=transport,
            hu_url=hu_url or None,
            seconds=args.seconds,
            fps=args.fps,
            quality=args.quality,
            brightness=args.brightness,
            rotation=args.rotation,
            blank_on_exit=args.blank_on_exit,
        )
    finally:
        transport.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
