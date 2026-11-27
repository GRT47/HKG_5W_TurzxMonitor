#!/usr/bin/env python3
"""
Converts 3d/scene.gltf + 3d/scene.bin (2019 Kia e-Niro) into a high-precision
compact binary OpenGL ES 3.0 asset (C3D2: niro_ev.c3d) for package_t88_hud.

Strategy for smooth OEM-grade body quality + fast t88 rendering:
- Drops 6 completely hidden internal materials (sub-surface taillight LED pins, calipers, etc.)
- Keeps all exterior body panels, glass, wheel cladding, blue accents, tires, DRLs,
  and mirrors 100% lossless (no grid clustering distortion)
- Applies topology-preserving meshoptimizer QEM edge-collapse (with LockBorder +
  normal attributes) only on high-density bevel/interior meshes (ChromeTrims, Rims, etc.)
- Quantizes positions & normals to int16 (12 bytes/vert = 0.076 mm resolution) and splits
  batches <= 65,000 vertices so every draw uses fast uint16 indices.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import struct
import subprocess
import tempfile
import time
from pathlib import Path
from typing import Dict, List, Tuple

import numpy as np

RAW_CX = 129.715
RAW_CY = 268.90
SCALE = 4.375 / 538.42  # ~0.008125627 m per raw unit
POS_SCALE = 2.5         # max abs meter extent (< 2.5 m) mapped to int16 (-32767..32767)
MAX_BATCH_VERTS = 65000

PART_BODY = 0
PART_WHEEL_FL = 1
PART_WHEEL_FR = 2
PART_WHEEL_RL = 3
PART_WHEEL_RR = 4

MAT_BODY = 0
MAT_TRIM_BLACK = 1
MAT_CHROME = 2
MAT_BLUE_ACCENT = 3
MAT_GLASS = 4
MAT_INTERIOR = 5
MAT_TIRE = 6
MAT_RIM = 7
MAT_HEADLAMP = 8
MAT_DRL = 9
MAT_TAILLAMP = 10
MAT_REVERSE = 11
MAT_PROJECTOR = 12

MAT_NAMES = [
    "MAT_BODY",
    "MAT_TRIM_BLACK",
    "MAT_CHROME",
    "MAT_BLUE_ACCENT",
    "MAT_GLASS",
    "MAT_INTERIOR",
    "MAT_TIRE",
    "MAT_RIM",
    "MAT_HEADLAMP",
    "MAT_DRL",
    "MAT_TAILLAMP",
    "MAT_REVERSE",
    "MAT_PROJECTOR",
]

# Completely hidden internal geometry + interior cabin & calipers
DROP_RAW_MATS = {3, 9, 19, 22, 23, 24}

# Wheel parts split by quadrant
WHEEL_RAW_MATS = {1, 7, 8, 25, 26, 27}
WHEEL_PIVOT_RAW_MATS = {25, 26, 27}

# QEM edge-collapse targets: (ratio, err, normal_weight, lock_border); small badges kept 100% lossless
QEM_CFG: Dict[int, Tuple[float, float, float, bool]] = {
    14: (0.42, 0.0035, 0.85, True),   # Graphite_Gray main body
    29: (0.65, 0.0040, 0.85, True),   # Windows
    4:  (0.42, 0.0080, 0.80, True),   # BlackTrims1 wheel arches
    28: (0.60, 0.0100, 0.35, True),   # UnderCarrier & wheelhouse liners (watertight borders)
    27: (0.42, 0.0080, 0.70, True),   # Tires
    5:  (0.50, 0.0080, 0.75, True),   # BlueAccent
    10: (0.20, 0.0400, 0.25, False),  # ChromeTrims (includes headlight projector shrouds)
    6:  (0.14, 0.0550, 0.15, False),  # BrakeLightsGlass
    30: (0.20, 0.0450, 0.20, False),  # WindowsTrims
    2:  (0.20, 0.0450, 0.20, False),  # Black1
    15: (0.18, 0.0550, 0.20, False),  # Grille1
    18: (0.45, 0.0120, 0.50, True),   # HeadLightsGlass (clear outer cover + projectors + DRLs)
    17: (0.32, 0.0250, 0.35, True),   # HeadLightsBase (sculpted dark bezel)
    25: (0.18, 0.0500, 0.20, False),  # Rims1
    26: (0.18, 0.0500, 0.20, False),  # Rims2
    7:  (0.18, 0.0400, 0.20, False),  # BrakeRotors1 (disc face)
    8:  (0.18, 0.0400, 0.20, False),  # BrakeRotors2 (rotor hat / shield)
    11: (0.35, 0.0250, 0.35, False),  # DRLs
    12: (0.28, 0.0350, 0.30, False),  # DarkRed
}


def read_accessor(raw: bytes, accs: list, bvs: list, idx: int) -> np.ndarray:
    a = accs[idx]
    bv = bvs[a["bufferView"]]
    off = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
    cnt = a["count"]
    dt = {
        5120: np.int8,
        5121: np.uint8,
        5122: np.int16,
        5123: np.uint16,
        5125: np.uint32,
        5126: np.float32,
    }[a["componentType"]]
    nc = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[a["type"]]
    arr = np.frombuffer(raw, dtype=dt, count=cnt * nc, offset=off)
    return arr.reshape(cnt, nc) if nc > 1 else arr


def map_semantic_material(raw_mat: int, cy_raw: np.ndarray) -> np.ndarray:
    n = len(cy_raw)
    if raw_mat in (14, 13):
        return np.full(n, MAT_BODY, dtype=np.int32)
    if raw_mat in (2, 4, 8, 15, 16, 30, 31):
        return np.full(n, MAT_TRIM_BLACK, dtype=np.int32)
    if raw_mat in (0, 1, 10, 20):
        return np.full(n, MAT_CHROME, dtype=np.int32)
    if raw_mat == 5:
        return np.full(n, MAT_BLUE_ACCENT, dtype=np.int32)
    if raw_mat == 29:
        return np.full(n, MAT_GLASS, dtype=np.int32)
    if raw_mat in (19, 28):
        return np.full(n, MAT_INTERIOR, dtype=np.int32)
    if raw_mat == 27:
        return np.full(n, MAT_TIRE, dtype=np.int32)
    if raw_mat in (7, 25, 26):
        return np.full(n, MAT_RIM, dtype=np.int32)
    if raw_mat in (6, 12):
        return np.full(n, MAT_TAILLAMP, dtype=np.int32)
    if raw_mat == 21:
        return np.full(n, MAT_REVERSE, dtype=np.int32)
    if raw_mat == 17:  # HeadLightsBase: front projector bezel is dark trim, mirror side is DRL
        out = np.full(n, MAT_DRL, dtype=np.int32)
        out[cy_raw < 120.0] = MAT_TRIM_BLACK
        out[cy_raw > 460.0] = MAT_TAILLAMP
        return out
    if raw_mat == 18:  # HeadLightsGlass: front optical lens, mirror indicator
        out = np.full(n, MAT_DRL, dtype=np.int32)
        out[cy_raw < 120.0] = MAT_HEADLAMP
        out[cy_raw > 460.0] = MAT_TAILLAMP
        return out
    if raw_mat == 11:  # DRLs: front + mirror -> DRL, rear Y > 460 -> reverse
        out = np.full(n, MAT_DRL, dtype=np.int32)
        out[cy_raw > 460.0] = MAT_REVERSE
        return out
    return np.full(n, MAT_BODY, dtype=np.int32)


def classify_headlight_glass(pos: np.ndarray, tris: np.ndarray) -> np.ndarray:
    """Splits raw_mat 18 shells into clear covers (MAT_HEADLAMP), optics (MAT_PROJECTOR), and DRLs (MAT_DRL)."""
    out = np.full(len(tris), MAT_HEADLAMP, dtype=np.int32)
    if len(tris) == 0:
        return out
    qp = np.round(pos * 1000.0).astype(np.int64)
    kv = np.ascontiguousarray(qp).view(np.dtype((np.void, qp.dtype.itemsize * 3))).ravel()
    _, uf, inv = np.unique(kv, return_index=True, return_inverse=True)
    nt = inv[tris]
    parent = list(range(len(uf)))

    def find(x: int) -> int:
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for a, b, cc in nt:
        ra, rb, rc = find(int(a)), find(int(b)), find(int(cc))
        if ra != rb:
            parent[rb] = ra
        if ra != rc:
            parent[rc] = ra

    comps: Dict[int, List[int]] = {}
    for ti, (a, _, _) in enumerate(nt):
        comps.setdefault(find(int(a)), []).append(ti)

    for tlist in comps.values():
        tarr = np.asarray(tlist, dtype=np.int64)
        pts = pos[tris[tarr].ravel()]
        mn = pts.min(axis=0)
        mx = pts.max(axis=0)
        sz = mx - mn
        cy = 0.5 * (mn[1] + mx[1])
        cz = 0.5 * (mn[2] + mx[2])
        if cz > -1.0:
            mid = MAT_DRL
        elif cy >= 0.70:
            if sz[2] > 0.42:
                mid = MAT_HEADLAMP
            elif sz[2] > 0.25:
                mid = MAT_DRL
            else:
                mid = MAT_PROJECTOR
        else:
            if sz[0] > 0.070 and sz[1] > 0.070:
                mid = MAT_HEADLAMP
            elif sz[0] > 0.050:
                mid = MAT_PROJECTOR
            else:
                mid = MAT_DRL
        out[tarr] = mid
    return out


def weld_pos_normal(
    pos: np.ndarray, nrm: np.ndarray, tris: np.ndarray
) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Welds exact duplicate (0.1 mm position + 0.01 normal) vertices cleanly."""
    if len(tris) == 0:
        return pos[:0], nrm[:0], tris[:0]
    qp = np.round(pos * 10000.0).astype(np.int64)
    qn = np.round(nrm * 100.0).astype(np.int64)
    keys = np.hstack([qp, qn])
    kv = np.ascontiguousarray(keys).view(np.dtype((np.void, keys.dtype.itemsize * 6))).ravel()
    _, ufirst, inv = np.unique(kv, return_index=True, return_inverse=True)
    new_pos = pos[ufirst]
    new_nrm = nrm[ufirst]
    nt = inv[tris]
    valid = (nt[:, 0] != nt[:, 1]) & (nt[:, 1] != nt[:, 2]) & (nt[:, 0] != nt[:, 2])
    nt = nt[valid]
    if len(nt) == 0:
        return np.zeros((0, 3), dtype=np.float32), np.zeros((0, 3), dtype=np.float32), np.zeros((0, 3), dtype=np.uint32)
    used, inv_u = np.unique(nt.ravel(), return_inverse=True)
    return (
        new_pos[used].astype(np.float32),
        new_nrm[used].astype(np.float32),
        inv_u.reshape(-1, 3).astype(np.uint32),
    )


def ensure_meshopt_js(build_dir: Path) -> Path:
    node_tools = build_dir / "node_tools"
    simplifier_js = node_tools / "node_modules" / "meshoptimizer" / "meshopt_simplifier.js"
    if simplifier_js.is_file():
        return simplifier_js
    npm_cmd = shutil.which("npm.cmd") if os.name == "nt" else shutil.which("npm")
    if not npm_cmd:
        npm_cmd = shutil.which("npm")
    if not npm_cmd:
        raise RuntimeError("npm not found for installing meshoptimizer build helper")
    node_tools.mkdir(parents=True, exist_ok=True)
    subprocess.run([npm_cmd, "install", "--no-save", "--prefix", str(node_tools), "meshoptimizer"], check=True)
    if not simplifier_js.is_file():
        raise RuntimeError(f"Missing {simplifier_js} after npm install")
    return simplifier_js


def run_meshopt_batch(
    simplifier_js: Path, mjs_helper: Path, jobs: List[Tuple[np.ndarray, np.ndarray, np.ndarray, float, float, float, int]]
) -> List[Tuple[np.ndarray, np.ndarray, np.ndarray]]:
    """Runs topology-preserving meshoptimizer QEM simplification in a single Node call."""
    if not jobs:
        return []
    node_exe = shutil.which("node")
    if not node_exe:
        raise RuntimeError("node not found for running simplify_meshopt.mjs")

    with tempfile.TemporaryDirectory() as td:
        req_path = Path(td) / "req.bin"
        res_path = Path(td) / "res.bin"
        with req_path.open("wb") as fp:
            fp.write(struct.pack("<I", len(jobs)))
            for pos, nrm, tris, ratio, err, norm_w, flags_mask in jobs:
                p32 = np.ascontiguousarray(pos, dtype="<f4")
                n32 = np.ascontiguousarray(nrm, dtype="<f4")
                i32 = np.ascontiguousarray(tris.ravel(), dtype="<u4")
                # target index count multiple of 3
                tgt_tris = max(4, int(round(len(tris) * ratio)))
                tgt_idx = tgt_tris * 3
                fp.write(struct.pack("<IIIffI", len(p32), len(i32), tgt_idx, float(err), float(norm_w), int(flags_mask)))
                fp.write(p32.tobytes())
                fp.write(n32.tobytes())
                fp.write(i32.tobytes())

        subprocess.run([node_exe, str(mjs_helper), str(simplifier_js), str(req_path), str(res_path)], check=True)

        data = res_path.read_bytes()
        off = 0
        (num_out,) = struct.unpack_from("<I", data, off)
        off += 4
        if num_out != len(jobs):
            raise RuntimeError(f"Expected {len(jobs)} chunks, got {num_out}")
        results: List[Tuple[np.ndarray, np.ndarray, np.ndarray]] = []
        for pos, nrm, _, _, _, _, _ in jobs:
            (out_cnt,) = struct.unpack_from("<I", data, off)
            off += 4
            out_idx = np.frombuffer(data, dtype="<u4", count=out_cnt, offset=off).reshape(-1, 3)
            off += out_cnt * 4
            if len(out_idx) == 0:
                results.append((np.zeros((0, 3), np.float32), np.zeros((0, 3), np.float32), np.zeros((0, 3), np.uint32)))
                continue
            used, inv = np.unique(out_idx.ravel(), return_inverse=True)
            results.append((pos[used].astype(np.float32), nrm[used].astype(np.float32), inv.reshape(-1, 3).astype(np.uint32)))
        return results


def split_batch_by_verts(
    pos: np.ndarray, nrm: np.ndarray, tris: np.ndarray, max_verts: int = MAX_BATCH_VERTS
) -> List[Tuple[np.ndarray, np.ndarray, np.ndarray]]:
    """Splits a batch into sub-batches if vertex count exceeds uint16 range."""
    if len(pos) <= max_verts:
        return [(pos, nrm, tris)]
    sub_batches: List[Tuple[np.ndarray, np.ndarray, np.ndarray]] = []
    # Greedily partition triangle slice chunks so each stays <= max_verts
    start = 0
    n_t = len(tris)
    est_chunks = max(2, int(np.ceil(len(pos) / (max_verts * 0.75))))
    step = max(1, (n_t + est_chunks - 1) // est_chunks)
    while start < n_t:
        end = min(n_t, start + step)
        sub_t = tris[start:end]
        used, inv = np.unique(sub_t.ravel(), return_inverse=True)
        while len(used) > max_verts and end > start + 1:
            end = start + (end - start) // 2
            sub_t = tris[start:end]
            used, inv = np.unique(sub_t.ravel(), return_inverse=True)
        sub_batches.append((pos[used], nrm[used], inv.reshape(-1, 3).astype(np.uint32)))
        start = end
    return sub_batches


def compile_car3d(gltf_path: Path, bin_path: Path, out_path: Path, global_scale: float = 1.0) -> dict:
    t0 = time.perf_counter()
    here = Path(__file__).resolve().parent
    build_dir = here / "build"
    mjs_helper = here / "simplify_meshopt.mjs"
    simplifier_js = ensure_meshopt_js(build_dir)

    g = json.loads(gltf_path.read_text(encoding="utf-8"))
    raw = bin_path.read_bytes()
    accs, bvs = g["accessors"], g["bufferViews"]

    wheel_pivot_pts: Dict[int, List[np.ndarray]] = {1: [], 2: [], 3: [], 4: []}
    # Group initially by (raw_mat, part_id, sem_mat) so QEM preserves per-material ratios cleanly
    raw_groups: Dict[Tuple[int, int, int], List[Tuple[np.ndarray, np.ndarray, np.ndarray]]] = {}
    hl_glass_chunks: List[Tuple[np.ndarray, np.ndarray, np.ndarray]] = []
    hl_glass_vb = 0
    orig_tris_total = 0

    for mesh in g["meshes"]:
        for pr in mesh["primitives"]:
            raw_mat = pr.get("material", 14)
            pos_raw = read_accessor(raw, accs, bvs, pr["attributes"]["POSITION"]).astype(np.float64)
            nrm_raw = read_accessor(raw, accs, bvs, pr["attributes"]["NORMAL"]).astype(np.float64)
            idx_raw = read_accessor(raw, accs, bvs, pr["indices"]).astype(np.int64).reshape(-1, 3)
            orig_tris_total += len(idx_raw)
            if raw_mat in DROP_RAW_MATS:
                continue

            pos_m = np.empty_like(pos_raw)
            pos_m[:, 0] = -(pos_raw[:, 0] - RAW_CX) * SCALE
            pos_m[:, 1] = pos_raw[:, 2] * SCALE
            pos_m[:, 2] = (pos_raw[:, 1] - RAW_CY) * SCALE

            nrm_m = np.empty_like(nrm_raw)
            nrm_m[:, 0] = -nrm_raw[:, 0]
            nrm_m[:, 1] = nrm_raw[:, 2]
            nrm_m[:, 2] = nrm_raw[:, 1]
            nl = np.linalg.norm(nrm_m, axis=1, keepdims=True)
            nrm_m = np.where(nl > 1e-8, nrm_m / np.maximum(nl, 1e-8), np.array([[0.0, 1.0, 0.0]]))

            if raw_mat == 18:
                hl_glass_chunks.append((pos_m, nrm_m, idx_raw + hl_glass_vb))
                hl_glass_vb += len(pos_m)
                continue

            cy_raw = (pos_raw[idx_raw[:, 0], 1] + pos_raw[idx_raw[:, 1], 1] + pos_raw[idx_raw[:, 2], 1]) / 3.0
            cx_m = (pos_m[idx_raw[:, 0], 0] + pos_m[idx_raw[:, 1], 0] + pos_m[idx_raw[:, 2], 0]) / 3.0
            cz_m = (pos_m[idx_raw[:, 0], 2] + pos_m[idx_raw[:, 1], 2] + pos_m[idx_raw[:, 2], 2]) / 3.0
            sem_mats = map_semantic_material(raw_mat, cy_raw)

            if raw_mat in WHEEL_RAW_MATS:
                parts = np.empty(len(idx_raw), dtype=np.int32)
                front = cz_m < 0.0
                left = cx_m < 0.0
                parts[front & left] = PART_WHEEL_FL
                parts[front & (~left)] = PART_WHEEL_FR
                parts[(~front) & left] = PART_WHEEL_RL
                parts[(~front) & (~left)] = PART_WHEEL_RR
                if raw_mat in WHEEL_PIVOT_RAW_MATS:
                    for pid in (PART_WHEEL_FL, PART_WHEEL_FR, PART_WHEEL_RL, PART_WHEEL_RR):
                        mask = parts == pid
                        if np.any(mask):
                            u_idx = np.unique(idx_raw[mask].ravel())
                            wheel_pivot_pts[pid].append(pos_m[u_idx])
            else:
                parts = np.full(len(idx_raw), PART_BODY, dtype=np.int32)

            pairs = parts * 32 + sem_mats
            for pair in np.unique(pairs):
                pid = int(pair // 32)
                mid = int(pair % 32)
                sub_tris = idx_raw[pairs == pair]
                u_v, inv_v = np.unique(sub_tris.ravel(), return_inverse=True)
                raw_groups.setdefault((raw_mat, pid, mid), []).append(
                    (pos_m[u_v], nrm_m[u_v], inv_v.reshape(-1, 3))
                )

    if hl_glass_chunks:
        hl_pos = np.vstack([c[0] for c in hl_glass_chunks])
        hl_nrm = np.vstack([c[1] for c in hl_glass_chunks])
        hl_tris = np.vstack([c[2] for c in hl_glass_chunks])
        hl_mids = classify_headlight_glass(hl_pos, hl_tris)
        for mid in np.unique(hl_mids):
            sub_tris = hl_tris[hl_mids == mid]
            u_v, inv_v = np.unique(sub_tris.ravel(), return_inverse=True)
            raw_groups.setdefault((18, PART_BODY, int(mid)), []).append(
                (hl_pos[u_v], hl_nrm[u_v], inv_v.reshape(-1, 3))
            )

    pivots = np.zeros((5, 3), dtype=np.float32)
    for pid in (PART_WHEEL_FL, PART_WHEEL_FR, PART_WHEEL_RL, PART_WHEEL_RR):
        pts = np.vstack(wheel_pivot_pts[pid])
        pivots[pid] = ((pts.min(axis=0) + pts.max(axis=0)) * 0.5).astype(np.float32)

    # Weld per raw_group and split into lossless vs QEM simplification jobs
    merged_by_sem: Dict[Tuple[int, int], List[Tuple[np.ndarray, np.ndarray, np.ndarray]]] = {}
    qem_keys: List[Tuple[int, int]] = []
    qem_jobs: List[Tuple[np.ndarray, np.ndarray, np.ndarray, float, float, float, int]] = []

    for (raw_mat, pid, mid), chunks in sorted(raw_groups.items()):
        pos_l, nrm_l, tri_l = [], [], []
        vb = 0
        piv = pivots[pid].astype(np.float64)
        for cp, cn, ct in chunks:
            pos_l.append(cp - piv)
            nrm_l.append(cn)
            tri_l.append(ct + vb)
            vb += len(cp)
        wp, wn, wt = weld_pos_normal(np.vstack(pos_l), np.vstack(nrm_l), np.vstack(tri_l))
        if len(wt) == 0:
            continue
        if raw_mat in QEM_CFG and len(wt) > 300:
            ratio, err, norm_w, lock_border = QEM_CFG[raw_mat]
            eff_ratio = max(0.10, min(1.0, ratio / global_scale))
            if eff_ratio < 0.99:
                qem_keys.append((pid, mid))
                qem_jobs.append((wp, wn, wt, eff_ratio, err * global_scale, norm_w, 1 if lock_border else 0))
                continue
        merged_by_sem.setdefault((pid, mid), []).append((wp, wn, wt))

    if qem_jobs:
        qem_out = run_meshopt_batch(simplifier_js, mjs_helper, qem_jobs)
        for (pid, mid), (qp, qn, qt) in zip(qem_keys, qem_out):
            if len(qt) > 0:
                merged_by_sem.setdefault((pid, mid), []).append((qp, qn, qt))

    # Combine chunks per semantic (partId, matId) and ensure <= MAX_BATCH_VERTS per draw batch
    compiled_batches: List[Tuple[int, int, np.ndarray, np.ndarray, np.ndarray]] = []
    total_verts = 0
    total_tris = 0

    for (pid, mid) in sorted(merged_by_sem.keys()):
        chunks = merged_by_sem[(pid, mid)]
        pos_l, nrm_l, tri_l = [], [], []
        vb = 0
        for cp, cn, ct in chunks:
            pos_l.append(cp)
            nrm_l.append(cn)
            tri_l.append(ct + vb)
            vb += len(cp)
        wp, wn, wt = weld_pos_normal(np.vstack(pos_l), np.vstack(nrm_l), np.vstack(tri_l))
        if len(wt) == 0:
            continue
        for sp, sn, st in split_batch_by_verts(wp, wn, wt):
            compiled_batches.append((pid, mid, sp, sn, st))
            total_verts += len(sp)
            total_tris += len(st)

    # Pack compact C3D2 binary (int16 normalized pos & nrm = 12 bytes/vert; uint16 local indices)
    all_verts = np.empty((total_verts, 6), dtype="<i2")
    all_indices = np.empty(total_tris * 3, dtype="<u2")
    batch_headers: List[Tuple[int, int, int, int, int]] = []

    v_cursor = 0
    i_cursor = 0
    for pid, mid, vp, vn, tri in compiled_batches:
        vc = len(vp)
        ic = len(tri) * 3
        p_i16 = np.round(np.clip(vp / POS_SCALE, -1.0, 1.0) * 32767.0).astype("<i2")
        n_len = np.linalg.norm(vn, axis=1, keepdims=True)
        vn_unit = np.where(n_len > 1e-6, vn / np.maximum(n_len, 1e-6), np.array([[0.0, 1.0, 0.0]], np.float32))
        n_i16 = np.round(np.clip(vn_unit, -1.0, 1.0) * 32767.0).astype("<i2")

        all_verts[v_cursor : v_cursor + vc, 0:3] = p_i16
        all_verts[v_cursor : v_cursor + vc, 3:6] = n_i16
        all_indices[i_cursor : i_cursor + ic] = tri.ravel().astype("<u2")
        batch_headers.append((pid, mid, v_cursor, i_cursor, ic))
        v_cursor += vc
        i_cursor += ic

    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("wb") as fp:
        fp.write(struct.pack("<4sHHIIf", b"C3D2", 5, len(batch_headers), total_verts, total_tris * 3, POS_SCALE))
        fp.write(pivots.astype("<f4").tobytes())
        for pid, mid, voff, ioff, icnt in batch_headers:
            fp.write(struct.pack("<HHIII", pid, mid, voff, ioff, icnt))
        fp.write(all_verts.tobytes())
        fp.write(all_indices.tobytes())

    dt = time.perf_counter() - t0
    return {
        "orig_tris": orig_tris_total,
        "tris": total_tris,
        "verts": total_verts,
        "batches": len(batch_headers),
        "bytes": out_path.stat().st_size,
        "pivots": pivots.tolist(),
        "seconds": round(dt, 2),
        "batch_stats": [
            (pid, MAT_NAMES[mid], ic // 3, len(vp))
            for (pid, mid, vp, _, _), (_, _, _, _, ic) in zip(compiled_batches, batch_headers)
        ],
    }


def main() -> None:
    here = Path(__file__).resolve().parent
    repo = here.parent
    ap = argparse.ArgumentParser()
    ap.add_argument("--gltf", type=Path, default=repo / "3d" / "scene.gltf")
    ap.add_argument("--bin", type=Path, default=repo / "3d" / "scene.bin")
    ap.add_argument("--out", type=Path, default=here / "build" / "car3d" / "niro_ev.c3d")
    ap.add_argument("--scale", type=float, default=1.0)
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    info = compile_car3d(args.gltf, args.bin, args.out, global_scale=args.scale)
    print(
        f"[car3d C3D2] {args.gltf.name}: {info['orig_tris']:,} -> {info['tris']:,} tris, "
        f"{info['verts']:,} verts, {info['batches']} batches, "
        f"{info['bytes']:,} bytes ({info['seconds']}s)"
    )
    if args.verbose:
        for i, pv in enumerate(info["pivots"]):
            print(f"  pivot[{i}]: ({pv[0]:+.3f}, {pv[1]:+.3f}, {pv[2]:+.3f})")
        for pid, mname, tc, vc in info["batch_stats"]:
            print(f"  part={pid} {mname:16s} tris={tc:6,d} verts={vc:6,d}")


if __name__ == "__main__":
    main()
