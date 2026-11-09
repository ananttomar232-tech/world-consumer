#!/usr/bin/env python3
"""Bake the six uploaded glTF vehicle packages into compact, game-ready .ttm meshes for the Tesla Tycoon mod.

What it does (the originals are never edited; they stay as the ZIPs in assets/tesla_tycoon/models/):
  * reads scene.gltf/scene.bin straight from each ZIP, bakes the node transforms,
  * rotates/scales every vehicle into one game frame: +Z = front, +Y = up, +X = the car's left, y = 0 on the ground,
    x = 0 on the centre line, z = 0 halfway between the axles, units = blocks (metres),
  * splits out the four wheels (so the mod can spin and steer them), the glass (drawn translucent so the driver is visible)
    and the lights (drawn unlit), and keeps everything else as one body mesh,
  * decimates every part to three LODs with meshoptimizer (falls back to vertex clustering if it is not installed),
  * writes  <out>/<name>.ttm  (+ <name>.meta.json, and the texture PNG for the textured models).

usage:  python3 tools/bake_models.py [--only l2_model3] [--preview DIR]
needs:  numpy, Pillow; optional: meshoptimizer (pip install meshoptimizer) for far better decimation.
The baked files are committed, so the normal Gradle build does not need Python at all.
"""
import argparse
import json
import math
import os
import shutil
import sys
import tempfile
import zipfile

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ttm_common as T  # noqa: E402

try:
    import meshoptimizer as mo
except ImportError:  # pragma: no cover
    mo = None

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIR = os.path.join(ROOT, "assets", "tesla_tycoon", "models")
OUT_MODELS = os.path.join(ROOT, "tesla_tycoon", "src", "main", "resources", "assets", "tesla_tycoon", "vehicles")
OUT_TEX = os.path.join(ROOT, "tesla_tycoon", "src", "main", "resources", "assets", "tesla_tycoon", "textures", "vehicle")

GLASS_ALPHA = 0.34


def rgb8(linear_rgb):
    return np.round(T.srgb_from_linear(linear_rgb) * 255).astype(np.uint8)


# ----------------------------------------------------------------------------------------------------------------------
# per-model configuration.  Wheel centres are in SOURCE units (x = car's left, y up, z = front), R = wheel radius.
# group(material_name, node_path) -> "paint" | "trim" | "interior" | "glass" | "light" | None (= drop)
# budgets are triangle counts for LOD0 / LOD1 / LOD2 per group; None = keep everything.
# anchors are in GAME units (blocks) after scaling.
# ----------------------------------------------------------------------------------------------------------------------
def g_l1(mat, path):
    if mat == "plate":
        return None
    if mat in ("glass", "d_glass"):
        return "glass"
    if mat in ("lights", "r_glass"):
        return "light"
    if mat == "interior":
        return "interior"
    if mat in ("body", "material"):
        return "paint"
    return "trim"


def g_l3(mat, path):
    if mat in ("panoramic", "kaca"):
        return "glass"
    if mat in ("headlight", "redlight"):
        return "light"
    if mat in ("body",):
        return "paint"
    return "trim"


def g_l4(mat, path):
    if mat == "Material.002":
        return "glass"
    if mat in ("Material.003", "Material.006"):
        return "light"
    if mat in ("Material",):
        return "paint"
    return "trim"


def g_default(mat, path):
    return "paint"


MODELS = {
    "l1_classic": dict(
        zip="l1_classic.zip", title="Tesla Classic Compact", length=4.5,
        wheels=[("FL", (30.55, 14.6, 57.55), 14.6, 5.9), ("FR", (-30.55, 14.6, 57.55), 14.6, 5.9),
                ("RL", (30.55, 14.5, -56.25), 14.6, 5.9), ("RR", (-30.55, 14.5, -56.25), 14.6, 5.9)],
        ground=0.0, group=g_l1,
        budgets={"paint": (9000, 2600, 700), "trim": (6500, 1700, 450), "interior": (2600, 450, 0), "glass": (1100, 260, 90),
                 "light": (700, 200, 60), "wheel": (1900, 520, 120)},
        seat=(0.36, 0.0, 0.05), seat_hip=0.46, steer=(0.36, 0.86, 0.78, 22.0),
        thrusters=[], tail_lights=[(0.0, 0.7, -2.2)], head_lights=[(0.0, 0.7, 2.2)],
        textured=False),
    "l2_model3": dict(
        zip="l2_model3.zip", title="Tesla Model 3", length=5.2,
        wheels=[("FL", (78.0, 35.35, 156.1), 35.3, 14.5), ("FR", (-78.0, 35.35, 156.1), 35.3, 14.5),
                ("RL", (78.55, 35.35, -133.8), 35.3, 14.5), ("RR", (-78.55, 35.35, -133.8), 35.3, 14.5)],
        wheel_by_name={"FLW": 0, "FRW": 1, "RLW": 2, "RRW": 3},
        ground=0.05, group=g_default,
        budgets={g: (None, None, None) for g in ("paint", "trim", "interior", "glass", "light", "wheel")},
        seat=(0.38, 0.0, 0.0), seat_hip=0.50, steer=(0.38, 0.86, 0.80, 22.0),
        thrusters=[], tail_lights=[], head_lights=[], textured=True,
        glass_by_uv=dict(max_lum=95, max_sat=22, min_y=0.55, max_abs_x=0.92)),
    "l3_cybertruck": dict(
        zip="l3_cybertruck.zip", title="Armored Tesla Cybertruck", length=5.8,
        wheels=[("FL", (0.905, 0.455, 1.985), 0.455, 0.19), ("FR", (-0.875, 0.455, 1.985), 0.455, 0.19),
                ("RL", (0.905, 0.455, -1.755), 0.455, 0.19), ("RR", (-0.875, 0.455, -1.755), 0.455, 0.19)],
        ground=0.0, group=g_l3,
        budgets={g: (None, None, None) for g in ("paint", "trim", "interior", "glass", "light", "wheel")},
        seat=(0.42, 0.0, -0.05), seat_hip=0.66, steer=(0.42, 1.02, 0.78, 20.0),
        thrusters=[], tail_lights=[], head_lights=[], textured=False),
    "l4_cybercab": dict(
        zip="l4_cybercab.zip", title="Autonomous Tesla Cybercab", length=5.3,
        wheels=[("FL", (0.835, 0.125, 1.705), 0.415, 0.19), ("FR", (-0.835, 0.125, 1.705), 0.415, 0.19),
                ("RL", (0.835, 0.125, -1.265), 0.415, 0.19), ("RR", (-0.835, 0.125, -1.265), 0.415, 0.19)],
        ground=-0.29, group=g_l4,
        budgets={"paint": (4200, 1500, 450), "trim": (2900, 900, 300), "interior": (1, 1, 1), "glass": (500, 160, 60),
                 "light": (60, 40, 16), "wheel": (2300, 700, 180)},
        seat=(0.42, 0.0, 0.05), seat_hip=0.58, steer=(0.42, 0.98, 0.85, 20.0),
        thrusters=[], tail_lights=[], head_lights=[], textured=False),
    "l5_roadster": dict(
        zip="l5_roadster.zip", title="Flying SpaceX Tesla Roadster", length=5.6,
        wheels=[("FL", (0.331, 0.1455, 0.525), 0.1455, 0.095), ("FR", (-0.331, 0.1455, 0.525), 0.1455, 0.095),
                ("RL", (0.331, 0.1455, -0.628), 0.1455, 0.095), ("RR", (-0.329, 0.1455, -0.628), 0.1455, 0.095)],
        ground=0.0, group=g_default,
        budgets={"paint": (40000, 9000, 2200), "trim": (1, 1, 1), "interior": (1, 1, 1), "glass": (1500, 400, 120),
                 "light": (1, 1, 1), "wheel": (3200, 900, 220)},
        seat=(0.36, 0.0, -0.1), seat_hip=0.42, steer=(0.36, 0.80, 0.62, 18.0),
        thrusters=[(0.55, 0.52, -2.62), (-0.55, 0.52, -2.62)], tail_lights=[], head_lights=[], textured=False,
        bake_vertex_colors=True, map_emissive=170, red_is_light=True,
        glass_by_color=dict(max_lum=40, max_sat=30, min_y=0.36, max_abs_x=0.84)),
    "rival_truck": dict(
        zip="rival_truck.zip", title="Seth Gotiya's Diesel Monster Truck", length=5.6,
        wheels=[("FL", (0.4625, -0.4625, 0.3905), 0.37, 0.29), ("FR", (-0.4625, -0.4625, 0.3905), 0.37, 0.29),
                ("RL", (0.4625, -0.4625, -0.3905), 0.37, 0.29), ("RR", (-0.4625, -0.4625, -0.3905), 0.37, 0.29)],
        ground=-0.833, group=g_default,
        budgets={g: (None, None, None) for g in ("paint", "trim", "interior", "glass", "light", "wheel")},
        seat=(0.0, 0.0, -0.1), seat_hip=2.35, steer=(0.0, 2.9, 0.65, 20.0),
        thrusters=[], tail_lights=[], head_lights=[], textured=True),
}


# ----------------------------------------------------------------------------------------------------------------------
def _bind_meshopt():
    """The pip wrapper declares no ctypes argtypes (size_t/float arguments get mangled), so bind the C entry points ourselves."""
    import ctypes as C
    lib = mo.simplifier.lib
    P = C.POINTER
    lib.meshopt_simplify.restype = C.c_size_t
    lib.meshopt_simplify.argtypes = [P(C.c_uint), P(C.c_uint), C.c_size_t, P(C.c_float), C.c_size_t, C.c_size_t,
                                     C.c_size_t, C.c_float, C.c_uint, P(C.c_float)]
    lib.meshopt_simplifyWithAttributes.restype = C.c_size_t
    lib.meshopt_simplifyWithAttributes.argtypes = [P(C.c_uint), P(C.c_uint), C.c_size_t, P(C.c_float), C.c_size_t, C.c_size_t,
                                                   P(C.c_float), C.c_size_t, P(C.c_float), C.c_size_t, P(C.c_ubyte),
                                                   C.c_size_t, C.c_float, C.c_uint, P(C.c_float)]
    return lib, C


def decimate(V, I, target_tris, lock_border=False):
    """Weld -> simplify -> rebuild.  V: structured vertex array, I: uint32 triangle indices.
    Returns (V2, I2) with ~target_tris triangles. Normals: smooth (mean of the merged originals) except at hard edges
    (original normals spread > 60 degrees), which get flat per-face normals.  Colour/material bytes are part of the weld key,
    so colour regions keep their borders.  Falls back to vertex clustering when meshoptimizer is missing."""
    n_tris = len(I) // 3
    if target_tris is None or n_tris <= target_tris:
        return V, I
    target_tris = max(int(target_tris), 4)
    diag = float(np.linalg.norm(V["pos"].max(0) - V["pos"].min(0)))
    q = max(diag * 2e-6, 1e-9)
    ip = np.round(V["pos"].astype(np.float64) / q).astype(np.int64)
    key = np.concatenate([ip, V["col"].astype(np.int64), V["mat"].astype(np.int64)], axis=1)
    _, first, inv = np.unique(key, axis=0, return_index=True, return_inverse=True)
    inv = inv.reshape(-1)
    nw = len(first)
    wpos = (ip[first] * q).astype(np.float32)
    nrm = V["nrm"].astype(np.float64) / 127.0
    ns = np.stack([np.bincount(inv, weights=nrm[:, k], minlength=nw) for k in range(3)], 1)
    mean = ns / np.maximum(np.linalg.norm(ns, axis=1, keepdims=True), 1e-9)
    spread = np.ones(nw)
    np.minimum.at(spread, inv, (nrm * mean[inv]).sum(1))
    hard = spread < 0.5
    widx = inv[I].astype(np.uint32)
    # drop triangles that collapsed by welding
    t = widx.reshape(-1, 3)
    ok = (t[:, 0] != t[:, 1]) & (t[:, 1] != t[:, 2]) & (t[:, 0] != t[:, 2])
    widx = np.ascontiguousarray(t[ok].reshape(-1))
    if mo is not None:
        lib, C = _bind_meshopt()
        P = C.POINTER
        col = np.ascontiguousarray(V["col"][first][:, :3].astype(np.float32) / 255.0)
        w = np.ascontiguousarray([0.8, 0.8, 0.8], dtype=np.float32)
        dst = np.zeros_like(widx)
        n = lib.meshopt_simplifyWithAttributes(dst.ctypes.data_as(P(C.c_uint)), widx.ctypes.data_as(P(C.c_uint)), len(widx),
                                               wpos.ctypes.data_as(P(C.c_float)), nw, 12, col.ctypes.data_as(P(C.c_float)), 12,
                                               w.ctypes.data_as(P(C.c_float)), 3, None, target_tris * 3, 0.5,
                                               1 if lock_border else 0, None)
        if n // 3 > target_tris * 1.35:
            dst2 = np.zeros_like(widx)
            src = np.ascontiguousarray(dst[:n])
            n2 = lib.meshopt_simplify(dst2.ctypes.data_as(P(C.c_uint)), src.ctypes.data_as(P(C.c_uint)), len(src),
                                      wpos.ctypes.data_as(P(C.c_float)), nw, 12, target_tris * 3, 0.6, 0, None)
            dst, n = dst2, n2
        new_idx = dst[:n].copy()
    else:
        new_idx = cluster_simplify(wpos, widx, target_tris).astype(np.uint32)
    tri = new_idx.reshape(-1, 3)
    fn = np.cross(wpos[tri[:, 1]] - wpos[tri[:, 0]], wpos[tri[:, 2]] - wpos[tri[:, 0]])
    fn = fn / np.maximum(np.linalg.norm(fn, axis=1, keepdims=True), 1e-20)
    out = np.zeros(len(tri) * 3, dtype=T.VERT_DT)
    cv = tri.reshape(-1)
    src_v = V[first[cv]]
    out["pos"] = wpos[cv]
    nn = np.where(hard[cv][:, None], np.repeat(fn, 3, axis=0), mean[cv])
    out["nrm"] = np.round(np.clip(nn, -1, 1) * 127).astype(np.int8)
    out["uv"] = src_v["uv"]
    out["col"] = src_v["col"]
    out["mat"] = src_v["mat"]
    return out, np.arange(len(out), dtype=np.uint32)


def cluster_simplify(pos, idx, target_tris):
    """Fallback decimation: uniform vertex clustering with a binary search on the cell size."""
    lo, hi = pos.min(0), pos.max(0)
    diag = float(np.linalg.norm(hi - lo))
    a, b = diag / 2000.0, diag / 4.0
    best = idx
    for _ in range(14):
        cell = math.sqrt(a * b)
        key = np.floor((pos - lo) / cell).astype(np.int64)
        _, inv = np.unique(key, axis=0, return_inverse=True)
        t = inv.reshape(-1)[idx.reshape(-1, 3)]
        keep = (t[:, 0] != t[:, 1]) & (t[:, 1] != t[:, 2]) & (t[:, 0] != t[:, 2])
        count = int(keep.sum())
        if count > target_tris:
            a = cell
        else:
            b = cell
            best = idx.reshape(-1, 3)[keep].reshape(-1)
        if abs(count - target_tris) < target_tris * 0.05:
            break
    return best


def compact(verts, idx):
    """Drop unreferenced vertices, keep order stable. verts is a structured array."""
    used = np.zeros(len(verts), dtype=bool)
    used[idx] = True
    remap = np.cumsum(used) - 1
    return verts[used], remap[idx].astype(np.uint32)


def part_attrs(v, textured):
    n = v["nrm"].astype(np.float32) / 127.0
    col = v["col"][:, :3].astype(np.float32) / 255.0
    if textured:
        return np.concatenate([n, col, v["uv"]], axis=1), [0.5] * 3 + [0.6] * 3 + [1.0] * 2
    return np.concatenate([n, col], axis=1), [0.5] * 3 + [0.6] * 3


# ----------------------------------------------------------------------------------------------------------------------
def load_vertices(name, cfg, tmp):
    z = zipfile.ZipFile(os.path.join(SRC_DIR, cfg["zip"]))
    d = os.path.join(tmp, name)
    z.extractall(d)
    g = T.Gltf(d)
    tex_img = None
    out = []
    for p in g.primitives():
        m = g.material(p["mat"])
        group = cfg["group"](m["name"], p["path"])
        if group is None:
            continue
        n = len(p["pos"])
        v = np.zeros(n, dtype=T.VERT_DT)
        v["pos"] = p["pos"]
        nrm = p["nrm"] if p["nrm"] is not None else np.tile(np.array([0, 1, 0], np.float32), (n, 1))
        v["nrm"] = np.round(np.clip(nrm, -1, 1) * 127).astype(np.int8)
        if p["uv"] is not None:
            v["uv"] = p["uv"]
        base = np.array(m["base"], dtype=np.float64)
        col = np.zeros(4, np.uint8)
        col[:3] = rgb8(base[:3])
        col[3] = 255
        metal = int(round(min(1.0, m["metal"] if m["metal"] is not None else 1.0) * 255))
        rough = int(round(min(1.0, m["rough"] if m["rough"] is not None else 1.0) * 255))
        emis = 0
        flag = 0
        tex = m["tex"] or m["emis_tex"]
        baked_rgb = None
        if tex and cfg.get("bake_vertex_colors") and p["uv"] is not None:
            from PIL import Image
            arr = np.asarray(Image.open(os.path.join(d, tex)).convert("RGB")).astype(np.float32)
            h_, w_ = arr.shape[:2]
            fx = (p["uv"][:, 0] % 1.0) * (w_ - 1)
            fy = (p["uv"][:, 1] % 1.0) * (h_ - 1)
            x0, y0 = np.floor(fx).astype(int), np.floor(fy).astype(int)
            x1, y1 = np.minimum(x0 + 1, w_ - 1), np.minimum(y0 + 1, h_ - 1)
            tx, ty = (fx - x0)[:, None], (fy - y0)[:, None]
            baked_rgb = (arr[y0, x0] * (1 - tx) * (1 - ty) + arr[y0, x1] * tx * (1 - ty) + arr[y1, x0] * (1 - tx) * ty + arr[y1, x1] * tx * ty)
            flag = 1
            if m["emis_tex"] and not m["tex"]:
                emis = int(cfg.get("map_emissive", 170))
        elif tex and cfg.get("textured"):
            tex_img = tex_img or os.path.join(d, tex)
            col[:3] = 255
            if m["emis_tex"] and not m["tex"]:
                emis = 170
        else:
            flag = 1                                              # untextured vertex: use the vertex colour only
        em = np.array(m["emis"], dtype=np.float64) * float(m["emis_strength"])
        if em.max() > 0.01 and not m["emis_tex"]:
            col[:3] = rgb8(np.clip(em / max(em.max(), 1e-6), 0, 1))
            emis = 255
        if group == "light":
            emis = 255
        v["col"] = col
        if baked_rgb is not None:
            v["col"][:, :3] = np.clip(np.round(baked_rgb), 0, 255).astype(np.uint8)
        v["mat"][:, 0] = metal
        v["mat"][:, 1] = rough
        v["mat"][:, 2] = emis
        v["mat"][:, 3] = flag
        out.append({"v": v, "idx": p["idx"], "group": group, "path": p["path"], "mat": m["name"], "node": p["node"]})
    return g, out, tex_img


def luminance_at(img, uv):
    w, h = img.size
    a = np.asarray(img.convert("RGB"))
    x = np.clip((uv[:, 0] % 1.0) * w, 0, w - 1).astype(int)
    y = np.clip((uv[:, 1] % 1.0) * h, 0, h - 1).astype(int)
    c = a[y, x].astype(np.float32)
    return c @ np.array([0.2126, 0.7152, 0.0722], np.float32), c


def bake_model(name, cfg, preview_dir=None):
    tmp = tempfile.mkdtemp(prefix="ttbake_")
    try:
        g, prims, tex_path = load_vertices(name, cfg, tmp)
        # ---- 1. one big triangle soup with a group tag per triangle
        verts, idx, tri_group, tri_prim = [], [], [], []
        off = 0
        for i, p in enumerate(prims):
            verts.append(p["v"])
            ix = p["idx"].astype(np.int64) + off
            idx.append(ix.reshape(-1, 3))
            tri_group += [p["group"]] * (len(ix) // 3)
            tri_prim += [i] * (len(ix) // 3)
            off += len(p["v"])
        V = np.concatenate(verts)
        I = np.concatenate(idx)
        tri_group = np.array(tri_group)
        tri_prim = np.array(tri_prim)
        # ---- 2. canonical frame
        wheels = cfg["wheels"]
        cen = np.array([w[1] for w in wheels], dtype=np.float64)
        track_c = cen[:, 0].mean()
        axle_c = (cen[:2, 2].mean() + cen[2:, 2].mean()) / 2
        solid = tri_group != "glass"
        pp = V["pos"][I[solid].reshape(-1)]
        lo, hi = pp.min(0), pp.max(0)
        scale = cfg["length"] / float(hi[2] - lo[2])
        origin = np.array([track_c, cfg["ground"], axle_c])
        V["pos"] = ((V["pos"].astype(np.float64) - origin) * scale).astype(np.float32)
        wheel_defs = []
        for (wn, c, R, hw) in wheels:
            c = (np.array(c) - origin) * scale
            wheel_defs.append({"name": wn, "pivot": [round(float(x), 4) for x in c], "radius": round(R * scale, 4),
                               "hw": round(hw * scale, 4), "front": wn[0] == "F", "left": wn[1] == "L"})
        # ---- 3. wheel assignment: by node name, else by the cylinder test (all three vertices inside)
        tri_wheel = np.full(len(I), -1, dtype=np.int8)
        P = V["pos"][I]                                     # (T,3,3)
        by_name = cfg.get("wheel_by_name")
        if by_name:
            for i, p in enumerate(prims):
                for key, wi in by_name.items():
                    if key in p["path"]:
                        tri_wheel[tri_prim == i] = wi
        else:
            for wi, w in enumerate(wheel_defs):
                c = np.array(w["pivot"])
                dy, dz = P[:, :, 1] - c[1], P[:, :, 2] - c[2]
                inside = (np.abs(P[:, :, 0] - c[0]) <= w["hw"]).all(1) & ((dy * dy + dz * dz) <= (w["radius"] * 1.06) ** 2).all(1)
                inside &= tri_wheel < 0
                inside &= tri_group != "glass"
                tri_wheel[inside] = wi
        # ---- 4. glass / light / untextured-plate classification by texture colour (textured models)
        gbu = cfg.get("glass_by_uv")
        if gbu and tex_path:
            from PIL import Image
            img = Image.open(tex_path)
            cent_uv = V["uv"][I].mean(1)
            lum, rgb0 = luminance_at(img, cent_uv)
            sat = rgb0.max(1) - rgb0.min(1)
            cy = P[:, :, 1].mean(1)
            hgt = float(V["pos"][:, 1].max())
            cx = np.abs(P[:, :, 0].mean(1))
            halfw = float(np.abs(V["pos"][:, 0]).max())
            m = (lum < gbu["max_lum"]) & (sat < gbu.get("max_sat", 255)) & (cy > gbu["min_y"] * hgt) & (cx < gbu["max_abs_x"] * halfw) & (tri_wheel < 0)
            tri_group[m & (tri_group != "glass")] = "glass"
            print("   glass-by-texture triangles: %d" % int(m.sum()))
        gbc = cfg.get("glass_by_color")
        if gbc:
            rgb = V["col"][I][:, :, :3].astype(np.float32).mean(1)
            lum = rgb @ np.array([0.2126, 0.7152, 0.0722], np.float32)
            sat = rgb.max(1) - rgb.min(1)
            cy = P[:, :, 1].mean(1)
            hgt = float(V["pos"][:, 1].max())
            cx = np.abs(P[:, :, 0].mean(1))
            halfw = float(np.abs(V["pos"][:, 0]).max())
            m = (lum < gbc["max_lum"]) & (sat < gbc["max_sat"]) & (cy > gbc["min_y"] * hgt) & (cx < gbc["max_abs_x"] * halfw) & (tri_wheel < 0)
            tri_group[m & (tri_group != "glass")] = "glass"
            print("   glass-by-colour triangles: %d" % int(m.sum()))
        if cfg.get("red_is_light"):
            rgb = V["col"][I][:, :, :3].astype(np.float32).mean(1)
            red = (rgb[:, 0] > 150) & (rgb[:, 1] < 80) & (rgb[:, 2] < 80) & (tri_wheel < 0)
            tri_group[red & (tri_group != "glass")] = "light"
            lights_v = np.unique(I[tri_group == "light"].reshape(-1))
            V["mat"][lights_v, 2] = 255
            print("   tail-light triangles: %d" % int((tri_group == "light").sum()))
        # ---- 5. build parts (+ LODs)
        parts = []
        blob = bytearray()
        total_tris = [0, 0, 0]

        def emit(part_name, layer, wheel_index, tri_mask, group_budget_of):
            """group_budget_of(group) -> (b0,b1,b2).  Triangles of several groups can be merged into one part."""
            tris = np.nonzero(tri_mask)[0]
            if len(tris) == 0:
                return
            lods = []
            groups = sorted(set(tri_group[tris]))
            for lod in range(3):
                if lod > 0 and all(group_budget_of(grp)[lod] is None for grp in groups):
                    lods.append(dict(lods[0]))
                    total_tris[lod] += lods[0]["ic"] // 3
                    continue
                v_all, i_all, base = [], [], 0
                for grp in groups:
                    sel = tris[tri_group[tris] == grp]
                    ix = I[sel].reshape(-1)
                    sub_v, sub_i = compact(V, ix)
                    # weld exact duplicates (same position + attributes)
                    flat = np.ascontiguousarray(sub_v).view(np.uint8).reshape(len(sub_v), -1)
                    _, first, inv = np.unique(flat, axis=0, return_index=True, return_inverse=True)
                    sub_v = sub_v[first]
                    sub_i = inv.reshape(-1)[sub_i].astype(np.uint32)
                    budget = group_budget_of(grp)[lod]
                    sub_v, new_i = decimate(sub_v, sub_i, budget, lock_border=(lod == 0))
                    sub_v, new_i = compact(sub_v, new_i)
                    flat2 = np.ascontiguousarray(sub_v).view(np.uint8).reshape(len(sub_v), -1)
                    _, first2, inv2 = np.unique(flat2, axis=0, return_index=True, return_inverse=True)
                    sub_v = sub_v[first2]
                    new_i = inv2.reshape(-1)[new_i].astype(np.uint32)
                    v_all.append(sub_v)
                    i_all.append(new_i.astype(np.int64) + base)
                    base += len(sub_v)
                vv = np.concatenate(v_all)
                ii = np.concatenate(i_all).astype(np.uint32)
                it = "i2" if len(vv) < 65536 else "i4"
                lods.append({"vc": int(len(vv)), "ic": int(len(ii)), "it": it, "off": len(blob)})
                blob.extend(vv.tobytes())
                blob.extend((ii.astype(np.uint16) if it == "i2" else ii).tobytes())
                total_tris[lod] += len(ii) // 3
            parts.append({"name": part_name, "layer": layer, "wheel": wheel_index, "lods": lods})

        budgets = cfg["budgets"]
        for wi, w in enumerate(wheel_defs):
            emit("wheel_" + w["name"], "solid", wi, (tri_wheel == wi), lambda grp: budgets["wheel"])
        body_mask = (tri_wheel < 0) & (tri_group != "glass")
        emit("body", "solid", -1, body_mask, lambda grp: budgets.get(grp, budgets["paint"]))
        glass_mask = (tri_wheel < 0) & (tri_group == "glass")
        emit("glass", "glass", -1, glass_mask, lambda grp: budgets["glass"])
        lo_c, hi_c = V["pos"][np.unique(I[(tri_group != "glass")].reshape(-1))].min(0), V["pos"][np.unique(I[(tri_group != "glass")].reshape(-1))].max(0)
        tex_rel = None
        if tex_path:
            os.makedirs(OUT_TEX, exist_ok=True)
            tex_rel = "tesla_tycoon:textures/vehicle/%s.png" % name
            shutil.copyfile(tex_path, os.path.join(OUT_TEX, name + ".png"))
        wb = (wheel_defs[0]["pivot"][2] - wheel_defs[2]["pivot"][2])
        header = {
            "name": name, "title": cfg["title"], "units": "blocks",
            "bbox": [round(float(x), 4) for x in list(lo_c) + list(hi_c)],
            "length": round(float(hi_c[2] - lo_c[2]), 3), "width": round(float(hi_c[0] - lo_c[0]), 3), "height": round(float(hi_c[1] - lo_c[1]), 3),
            "wheelbase": round(wb, 4), "track": round(abs(wheel_defs[0]["pivot"][0] - wheel_defs[1]["pivot"][0]), 4),
            "wheelRadius": wheel_defs[0]["radius"], "wheels": wheel_defs,
            "seat": list(cfg["seat"]), "seatHip": cfg["seat_hip"], "steer": list(cfg["steer"]),
            "thrusters": [list(t) for t in cfg["thrusters"]],
            "texture": tex_rel, "emissiveMap": False,
            "lods": 3, "tris": total_tris, "parts": parts,
        }
        os.makedirs(OUT_MODELS, exist_ok=True)
        T.write_ttm(os.path.join(OUT_MODELS, name + ".ttm"), header, bytes(blob))
        meta = {k: v for k, v in header.items() if k != "parts"}
        with open(os.path.join(OUT_MODELS, name + ".meta.json"), "w") as fh:
            json.dump(meta, fh, indent=1)
        print("%-14s %5d / %5d / %5d tris  bbox %s  (%d KiB)" % (name, total_tris[0], total_tris[1], total_tris[2],
                                                              [round(float(x), 2) for x in (hi_c - lo_c)], len(blob) // 1024))
        if preview_dir:
            preview(name, header, bytes(blob), preview_dir)
        return header
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# ----------------------------------------------------------------------------------------------------------------------
def preview(name, header, blob, out_dir, lod=0):
    """Render the baked model: wheels green, glass cyan, lights yellow, body = its colour; plus a cut-away of the left half."""
    os.makedirs(out_dir, exist_ok=True)
    tris, cols = [], []
    tex = None
    if header.get("texture"):
        from PIL import Image
        tex = Image.open(os.path.join(OUT_TEX, name + ".png")).convert("RGB")
    for p in header["parts"]:
        e = p["lods"][lod]
        vb = np.frombuffer(blob, dtype=T.VERT_DT, count=e["vc"], offset=e["off"])
        isz = 2 if e["it"] == "i2" else 4
        ib = np.frombuffer(blob, dtype=np.uint16 if isz == 2 else np.uint32, count=e["ic"], offset=e["off"] + e["vc"] * T.VERT_DT.itemsize).astype(np.int64)
        t = vb["pos"][ib.reshape(-1, 3)]
        c = vb["col"][ib.reshape(-1, 3)][:, 0, :3].astype(np.float32)
        if tex is not None:
            _, rgb = luminance_at(tex, vb["uv"][ib.reshape(-1, 3)].mean(1))
            untex = vb["mat"][ib.reshape(-1, 3)][:, 0, 3] == 1
            c = np.where(untex[:, None], c, rgb)
        if p["layer"] == "glass":
            c = np.tile(np.array([90, 200, 255], np.float32), (len(t), 1))
        elif p["wheel"] >= 0:
            c = np.tile(np.array([90, 230, 90], np.float32), (len(t), 1))
        em = vb["mat"][ib.reshape(-1, 3)][:, 0, 2] == 255
        c = np.where(em[:, None] & (p["layer"] != "glass"), np.array([255, 230, 60], np.float32), c)
        tris.append(t)
        cols.append(c)
    tris = np.concatenate(tris)
    cols = np.clip(np.concatenate(cols), 0, 255).astype(np.uint8)
    views = [("side", (0, 0, 1), (0, 1, 0), (1, 0, 0)), ("front", (1, 0, 0), (0, 1, 0), (0, 0, -1)),
             ("top", (-1, 0, 0), (0, 0, 1), (0, -1, 0)), T.standard_views()[3]]
    bb = header["bbox"]
    img = T.render_views(tris, cols, views, size=(760, 440), title="%s LOD%d" % (name, lod), bbox=bb)
    markers = [("seat", (header["seat"][0], header["seatHip"], header["seat"][2]), (255, 80, 80)),
               ("wheel", (header["steer"][0], header["steer"][1], header["steer"][2]), (255, 255, 80))]
    for t in header["thrusters"]:
        markers.append(("jet", tuple(t), (80, 160, 255)))
    keep = tris.mean(1)[:, 0] >= 0.0
    cut = T.render_views(tris[keep], cols[keep], views[:1], size=(760, 440), title="cut-away (left half)", markers=markers, bbox=bb)
    from PIL import Image
    sheet = Image.new("RGB", (img.width, img.height + cut.height))
    sheet.paste(img, (0, 0))
    sheet.paste(cut, (0, img.height))
    sheet.save(os.path.join(out_dir, "%s_lod%d.png" % (name, lod)))



# ----------------------------------------------------------------------------------------------------------------------
# The delivery truck is the one vehicle that is NOT one of the six uploaded models: it is generated here from boxes and cylinders
# (a white box truck with a red Tesla stripe) so the back-alley delivery scene has something to drive.
# ----------------------------------------------------------------------------------------------------------------------
class _Mesh:
    def __init__(self):
        self.v = []
        self.i = []

    def _quad(self, p, n, col, metal, rough, emis):
        base = len(self.v)
        for q in p:
            rec = np.zeros((), dtype=T.VERT_DT)
            rec["pos"] = q
            rec["nrm"] = np.round(np.array(n) * 127).astype(np.int8)
            rec["col"] = col
            rec["mat"] = (metal, rough, emis, 1)
            self.v.append(rec)
        self.i += [base, base + 1, base + 2, base, base + 2, base + 3]

    def box(self, x0, y0, z0, x1, y1, z1, col, metal=70, rough=150, emis=0):
        c = [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
        faces = [((0, 3, 2, 1), (0, 0, -1)), ((4, 5, 6, 7), (0, 0, 1)), ((0, 4, 7, 3), (-1, 0, 0)),
                 ((1, 2, 6, 5), (1, 0, 0)), ((0, 1, 5, 4), (0, -1, 0)), ((3, 7, 6, 2), (0, 1, 0))]
        for idx, n in faces:
            self._quad([c[k] for k in idx], n, col, metal, rough, emis)

    def wheel(self, cx, cy, cz, r, hw, col, hub, seg=20):
        for k in range(seg):
            a0, a1 = 2 * math.pi * k / seg, 2 * math.pi * (k + 1) / seg
            p0 = (cy + r * math.sin(a0), cz + r * math.cos(a0))
            p1 = (cy + r * math.sin(a1), cz + r * math.cos(a1))
            n = (0, math.sin((a0 + a1) / 2), math.cos((a0 + a1) / 2))
            self._quad([(cx - hw, p0[0], p0[1]), (cx + hw, p0[0], p0[1]), (cx + hw, p1[0], p1[1]), (cx - hw, p1[0], p1[1])], n, col, 20, 220, 0)
            for sx, nx, rr in ((cx + hw, 1, r), (cx - hw, -1, r)):
                hubr = r * 0.55
                h0 = (cy + hubr * math.sin(a0), cz + hubr * math.cos(a0))
                h1 = (cy + hubr * math.sin(a1), cz + hubr * math.cos(a1))
                sx2 = sx + nx * 0.02
                self._quad([(sx2, cy, cz), (sx2, h0[0], h0[1]), (sx2, h1[0], h1[1]), (sx2, h1[0], h1[1])] if nx > 0 else
                           [(sx2, cy, cz), (sx2, h1[0], h1[1]), (sx2, h0[0], h0[1]), (sx2, h0[0], h0[1])], (nx, 0, 0), hub, 200, 90, 0)
                self._quad([(sx, h0[0], h0[1]), (sx, p0[0], p0[1]), (sx, p1[0], p1[1]), (sx, h1[0], h1[1])] if nx > 0 else
                           [(sx, h0[0], h0[1]), (sx, h1[0], h1[1]), (sx, p1[0], p1[1]), (sx, p0[0], p0[1])], (nx, 0, 0), col, 20, 220, 0)

    def arrays(self):
        if not self.v:
            return np.zeros(0, dtype=T.VERT_DT), np.zeros(0, dtype=np.uint32)
        return np.array(self.v, dtype=T.VERT_DT), np.array(self.i, dtype=np.uint32)


def bake_truck(preview_dir=None):
    name = "transport_truck"
    white, red, dark, gray = (232, 234, 238, 255), (208, 28, 36, 255), (40, 42, 48, 255), (150, 152, 160, 255)
    body, glass = _Mesh(), _Mesh()
    ground = 0.0
    body.box(-1.0, 0.45, -3.5, 1.0, 0.95, 3.3, dark, 120, 140)                      # chassis
    body.box(-1.3, 0.95, 1.2, 1.3, 3.05, 3.5, white, 150, 90)                        # cab
    body.box(-1.31, 1.9, 1.2, 1.31, 2.15, 3.5, red, 150, 90)                         # red band round the cab
    body.box(-1.35, 0.95, -3.7, 1.35, 3.55, 1.0, white, 100, 120)                    # cargo box
    body.box(-1.36, 1.7, -3.7, 1.36, 2.3, 1.0, red, 100, 120)                        # the Tesla stripe
    body.box(-0.6, 0.95, -3.72, 0.6, 3.1, -3.7, dark, 80, 150)                       # rear door seam
    body.box(-0.35, 2.3, -3.74, 0.35, 3.0, -3.72, red, 100, 120)                     # "T" logo block
    body.box(-1.1, 1.0, 3.5, 1.1, 1.35, 3.62, gray, 220, 80)                         # bumper
    body.box(-1.0, 1.45, 3.5, -0.6, 1.7, 3.58, (255, 250, 220, 255), 0, 0, 255)      # head lights
    body.box(0.6, 1.45, 3.5, 1.0, 1.7, 3.58, (255, 250, 220, 255), 0, 0, 255)
    body.box(-1.2, 1.1, -3.76, -0.9, 1.4, -3.7, (255, 40, 40, 255), 0, 0, 255)       # tail lights
    body.box(0.9, 1.1, -3.76, 1.2, 1.4, -3.7, (255, 40, 40, 255), 0, 0, 255)
    glass.box(-1.12, 2.0, 3.51, 1.12, 2.95, 3.53, (120, 170, 210, 255), 0, 20, 0)     # windscreen
    glass.box(1.31, 2.0, 1.7, 1.33, 2.9, 3.3, (120, 170, 210, 255), 0, 20, 0)
    glass.box(-1.33, 2.0, 1.7, -1.31, 2.9, 3.3, (120, 170, 210, 255), 0, 20, 0)
    wheels = [("FL", (1.05, 0.55, 2.2), True, True), ("FR", (-1.05, 0.55, 2.2), True, False),
              ("RL", (1.05, 0.55, -2.2), False, True), ("RR", (-1.05, 0.55, -2.2), False, False)]
    wmeshes = []
    for nm, (cx, cy, cz), fr, lf in wheels:
        m = _Mesh()
        m.wheel(cx, cy, cz, 0.55, 0.2, (24, 24, 28, 255), (170, 172, 180, 255))
        wmeshes.append((nm, m, (cx, cy, cz), fr, lf))

    parts, blob, tris = [], bytearray(), 0

    def emit(pname, layer, widx, mesh):
        nonlocal tris
        V, I = mesh.arrays()
        entry = {"vc": int(len(V)), "ic": int(len(I)), "it": "i2", "off": len(blob)}
        blob.extend(V.tobytes())
        blob.extend(I.astype(np.uint16).tobytes())
        tris += len(I) // 3
        parts.append({"name": pname, "layer": layer, "wheel": widx, "lods": [dict(entry), dict(entry), dict(entry)]})

    for wi, (nm, m, c, fr, lf) in enumerate(wmeshes):
        emit("wheel_" + nm, "solid", wi, m)
    emit("body", "solid", -1, body)
    emit("glass", "glass", -1, glass)
    header = {
        "name": name, "title": "Tesla Delivery Truck", "units": "blocks", "bbox": [-1.36, 0.0, -3.76, 1.36, 3.55, 3.62],
        "length": 7.38, "width": 2.72, "height": 3.55, "wheelbase": 4.4, "track": 2.1, "wheelRadius": 0.55,
        "wheels": [{"name": nm, "pivot": [round(v, 4) for v in c], "radius": 0.55, "hw": 0.2, "front": fr, "left": lf} for nm, m, c, fr, lf in wmeshes],
        "seat": [0.5, 0.0, 2.0], "seatHip": 1.6, "steer": [0.5, 1.9, 2.6, 25.0], "thrusters": [],
        "texture": None, "emissiveMap": False, "lods": 3, "tris": [tris, tris, tris], "parts": parts,
    }
    os.makedirs(OUT_MODELS, exist_ok=True)
    T.write_ttm(os.path.join(OUT_MODELS, name + ".ttm"), header, bytes(blob))
    with open(os.path.join(OUT_MODELS, name + ".meta.json"), "w") as fh:
        json.dump({k: v for k, v in header.items() if k != "parts"}, fh, indent=1)
    print("%-14s %5d tris (generated from boxes, not one of the uploaded models)" % (name, tris))
    if preview_dir:
        preview(name, header, bytes(blob), preview_dir)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", action="append")
    ap.add_argument("--preview", metavar="DIR")
    ap.add_argument("--lod", type=int, default=0)
    a = ap.parse_args()
    if mo is None:
        print("warning: meshoptimizer is not installed - using the crude vertex-clustering fallback (pip install meshoptimizer)")
    for name, cfg in MODELS.items():
        if a.only and name not in a.only:
            continue
        bake_model(name, cfg, a.preview)
    if not a.only or "transport_truck" in a.only:
        bake_truck(a.preview)
    return 0


if __name__ == "__main__":
    sys.exit(main())
