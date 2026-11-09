#!/usr/bin/env python3
"""Look inside a baked car: the shell (cut open), the procedural interior, a stand-in for the seated driver and the steering wheel.
Writes one sheet with four views per car (left window, from above, the driver's eyes, from the back seat).

usage:  python3 tools/interior_preview.py --out DIR [--only l1_classic] [--lod 0]
"""
import argparse
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cabin_probe as P  # noqa: E402
import interior_gen as G  # noqa: E402
import preview3d as R3  # noqa: E402

NAMES = ["l1_classic", "l2_model3", "l3_cybertruck", "l4_cybercab", "l5_roadster"]


def flat_normals(tris):
    n = np.cross(tris[:, 1] - tris[:, 0], tris[:, 2] - tris[:, 0])
    n /= np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-12)
    return np.repeat(n[:, None, :], 3, axis=1)


def shell_geometry(parts, drop_glass, cut):
    """(tris, vcol, vnrm) of the cut-open shell. cut(tri_centroids) -> boolean mask of triangles to keep."""
    T, C = [], []
    for (n, layer, w, t, c) in parts:
        if w >= 0 or n == "interior":
            continue
        if layer == "glass" and drop_glass:
            continue
        keep = cut(t.mean(1))
        col = np.array([120, 160, 205] if layer == "glass" else [142, 148, 158], np.float32)
        T.append(t[keep])
        C.append(np.tile(col, (keep.sum(), 3, 1)))
    T = np.concatenate(T)
    return T, np.concatenate(C), flat_normals(T)


def sheet(name, out_dir, lod=0):
    cab = G.CABINS[name]
    header, parts = P.load(name)
    body = np.concatenate([t for (n, layer, w, t, c) in parts if layer == "solid" and w < 0])
    V, I = G.build(cab, lod, body)
    it, ic, inn = R3.mesh_arrays(V, I)
    occ = R3.occupant_proxy(cab)
    whl = R3.wheel_proxy(cab)

    def compose(shell, icut=None, with_occupant=True):
        a, b, c = it, ic, inn
        if icut is not None:
            keep = icut(a.mean(1))
            a, b, c = a[keep], b[keep], c[keep]
        parts_t = [shell[0], a, whl[0]] + ([occ[0]] if with_occupant else [])
        parts_c = [shell[1], b, whl[1]] + ([occ[1]] if with_occupant else [])
        parts_n = [shell[2], c, whl[2]] + ([occ[2]] if with_occupant else [])
        return np.concatenate(parts_t), np.concatenate(parts_c), np.concatenate(parts_n)

    views = []
    eye_y = cab.hip + 0.92 * cab.k
    # 1. from the left, the left door and everything beyond x > 0.55 removed
    g = compose(shell_geometry(parts, True, lambda c: c[:, 0] < 0.55), lambda c: c[:, 0] < 0.55)
    views.append(("left window", R3.render(*g, eye=(2.4, cab.hip + 0.9, cab.sz + 0.4), target=(0.2, cab.hip + 0.3, cab.sz + 0.1), fov=52, size=(900, 520))))
    # 2. from above, the roof, the upper glass and the headliner removed
    g = compose(shell_geometry(parts, True, lambda c: c[:, 1] < cab.belt + 0.05), lambda c: c[:, 1] < cab.belt + 0.25)
    views.append(("from above", R3.render(*g, eye=(0.0, cab.roof + 1.9, cab.sz - 0.9), target=(0.0, cab.hip, cab.sz + 0.2), fov=58, size=(900, 520))))
    # 3. the driver's eyes (glass removed = transparent), looking forward and slightly down; his own body is not drawn
    g = compose(shell_geometry(parts, True, lambda c: np.ones(len(c), bool)), None, False)
    views.append(("driver's eyes", R3.render(*g, eye=(cab.sx, eye_y, cab.sz + 0.02), target=(cab.sx * 0.5, eye_y - 0.25, cab.sz + 1.5), fov=78, size=(900, 520))))
    # 4. from the back seat looking at the dash, roof removed
    g = compose(shell_geometry(parts, True, lambda c: c[:, 1] < cab.belt + 0.05), lambda c: c[:, 1] < cab.belt + 0.25)
    views.append(("from behind", R3.render(*g, eye=(-0.1, cab.hip + 1.2, cab.sz - 1.5), target=(0.1, cab.hip + 0.1, cab.sz + 0.6), fov=62, size=(900, 520))))
    w, h = 900, 520
    img = Image.new("RGB", (w * 2, h * 2))
    d = ImageDraw.Draw(img)
    for i, (label, arr) in enumerate(views):
        tile = Image.fromarray(arr)
        ImageDraw.Draw(tile).text((8, 6), "%s  %s   k=%.2f  (%d tris)" % (name, label, cab.k, len(I) // 3), fill=(255, 255, 255))
        img.paste(tile, ((i % 2) * w, (i // 2) * h))
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, "%s_sheet.png" % name)
    img.save(path)
    return path


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--only")
    ap.add_argument("--lod", type=int, default=0)
    a = ap.parse_args()
    for n in ([a.only] if a.only else NAMES):
        print(sheet(n, a.out, a.lod))
