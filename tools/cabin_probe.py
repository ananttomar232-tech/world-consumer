#!/usr/bin/env python3
"""Measure the cabin of every baked vehicle (so occupants and the procedural interior can be fitted exactly).

For the driver's seat position it shoots rays through the LOD0 meshes and prints where the roof, the glass and the floor are, how wide
the cabin is at head and shoulder height and how far away the windscreen and the rear window are. With --out it also writes a
cut-away side view of every car (the half beyond the driver's seat removed) so the interior can be looked at.

usage:  python3 tools/cabin_probe.py [--only l1_classic] [--out DIR]
"""
import argparse
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ttm_common as T  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VEH = os.path.join(ROOT, "tesla_tycoon", "src", "main", "resources", "assets", "tesla_tycoon", "vehicles")
NAMES = ["l1_classic", "l2_model3", "l3_cybertruck", "l4_cybercab", "l5_roadster"]


def load(name, lod=0):
    header, blob = T.read_ttm(os.path.join(VEH, name + ".ttm"))
    blob = bytes(blob)
    out = []
    for p in header["parts"]:
        e = p["lods"][lod]
        vb = np.frombuffer(blob, dtype=T.VERT_DT, count=e["vc"], offset=e["off"])
        isz = 2 if e["it"] == "i2" else 4
        ib = np.frombuffer(blob, dtype=np.uint16 if isz == 2 else np.uint32, count=e["ic"], offset=e["off"] + e["vc"] * T.VERT_DT.itemsize).astype(np.int64)
        tri = vb["pos"][ib.reshape(-1, 3)].astype(np.float64)
        col = vb["col"][ib.reshape(-1, 3)][:, 0, :3]
        out.append((p["name"], p["layer"], p["wheel"], tri, col))
    return header, out


def ray_hits(tris, origin, axis):
    """Intersections of the line origin + t * e_axis with the triangles (projected along that axis). Returns the coordinates along the axis."""
    a = [i for i in range(3) if i != axis]
    p = np.array([origin[a[0]], origin[a[1]]])
    A, B, C = tris[:, 0][:, a], tris[:, 1][:, a], tris[:, 2][:, a]
    v0, v1, v2 = C - A, B - A, p - A
    d00 = (v0 * v0).sum(1)
    d01 = (v0 * v1).sum(1)
    d02 = (v0 * v2).sum(1)
    d11 = (v1 * v1).sum(1)
    d12 = (v1 * v2).sum(1)
    den = d00 * d11 - d01 * d01
    ok = np.abs(den) > 1e-12
    inv = np.where(ok, 1.0 / np.where(ok, den, 1.0), 0.0)
    u = (d11 * d02 - d01 * d12) * inv
    v = (d00 * d12 - d01 * d02) * inv
    inside = ok & (u >= -1e-6) & (v >= -1e-6) & (u + v <= 1 + 1e-6)
    if not inside.any():
        return np.zeros(0)
    uu, vv = u[inside], v[inside]
    t = tris[inside]
    coord = t[:, 0, axis] * (1 - uu - vv) + t[:, 2, axis] * uu + t[:, 1, axis] * vv
    return np.sort(coord)


def probe(name):
    header, parts = load(name)
    seat = header["seat"]
    hip = header["seatHip"]
    sx, sz = seat[0], seat[2]
    solid = np.concatenate([t for (n, layer, w, t, c) in parts if layer == "solid" and w < 0])
    glass = np.concatenate([t for (n, layer, w, t, c) in parts if layer == "glass"]) if any(layer == "glass" for (_, layer, _, _, _) in parts) else np.zeros((0, 3, 3))
    bb = header["bbox"]
    print("== %s  length %.2f width %.2f height %.2f  seat x=%.2f z=%.2f hip=%.2f  wheel %s" % (name, header["length"], header["width"], header["height"], sx, sz, hip, header["steer"]))
    print("   bbox", [round(v, 2) for v in bb])
    for label, tris in (("solid", solid), ("glass", glass)):
        if len(tris) == 0:
            continue
        ys = ray_hits(tris, (sx, 0, sz), 1)
        print("   vertical ray at the driver seat, %-5s hits y:" % label, [round(float(y), 2) for y in ys])
    # lateral rays (along x) at head height and shoulder height through the seat z: the cabin walls
    for hy in (hip + 0.35, hip + 0.7, hip + 0.9):
        xs = ray_hits(np.concatenate([solid, glass]) if len(glass) else solid, (0, hy, sz), 0)
        print("   x-ray at y=%.2f z=%.2f: %s" % (hy, sz, [round(float(x), 2) for x in xs]))
    # longitudinal rays (along z) at the seat x, at hip + 0.5: windscreen / dash / rear window / rear wall
    for hy in (hip + 0.25, hip + 0.6, hip + 0.9):
        zs = ray_hits(np.concatenate([solid, glass]) if len(glass) else solid, (sx, hy, 0), 2)
        print("   z-ray at y=%.2f x=%.2f: %s" % (hy, sx, [round(float(z), 2) for z in zs]))


def windows(name):
    """Windscreen / rear window / side windows from the glass normals: the numbers the interior is fitted to."""
    header, parts = load(name)
    g = [t for (n, layer, w, t, c) in parts if layer == "glass"]
    if not g:
        print("   (no glass part)")
        return
    t = np.concatenate(g)
    n = np.cross(t[:, 1] - t[:, 0], t[:, 2] - t[:, 0])
    ln = np.linalg.norm(n, axis=1, keepdims=True)
    n = n / np.maximum(ln, 1e-12)
    # the glass faces both ways (single surface): use |n|
    cen = t.mean(1)
    area = ln[:, 0] * 0.5

    def stats(label, mask):
        if mask.sum() == 0:
            print("   %-12s none" % label)
            return
        pts = t[mask].reshape(-1, 3)
        lo, hi = pts.min(0), pts.max(0)
        print("   %-12s tris %4d  x %.2f..%.2f  y %.2f..%.2f  z %.2f..%.2f" % (label, mask.sum(), lo[0], hi[0], lo[1], hi[1], lo[2], hi[2]))

    center = np.abs(cen[:, 0]) < 0.5
    stats("windscreen", center & (np.abs(n[:, 2]) > 0.45) & (cen[:, 2] > 0.2) & (np.abs(n[:, 1]) > 0.25))
    stats("rear window", center & (np.abs(n[:, 2]) > 0.45) & (cen[:, 2] < -0.5))
    stats("roof glass", center & (np.abs(n[:, 1]) > 0.85) & (cen[:, 1] > 1.2))
    stats("side left", (cen[:, 0] > 0.3) & (np.abs(n[:, 0]) > 0.6))
    stats("side right", (cen[:, 0] < -0.3) & (np.abs(n[:, 0]) > 0.6))


def draw_cutaway(shell, extra, header, path, title, size=(1300, 560), z_range=(-3.0, 3.1), y_range=(-0.1, 2.0)):
    """Side cut-away: the car's right half is removed and the left half is drawn seen from the right side (front of the car = image right),
    with a 0.5 block grid. shell = [(tris, rgb)], extra = [(tris, rgb)] (interior parts, drawn last = on top)."""
    from PIL import Image, ImageDraw
    w, h = size
    sc = min(w / (z_range[1] - z_range[0]), h / (y_range[1] - y_range[0]))
    ox = (w - (z_range[1] - z_range[0]) * sc) / 2
    oy = h - 30

    def px(z, y):
        return ((z - z_range[0]) * sc + ox, oy - (y - y_range[0]) * sc)

    img = Image.new("RGB", size, (30, 32, 40))
    d = ImageDraw.Draw(img)
    for yy in np.arange(0.0, y_range[1], 0.5):
        a, b = px(z_range[0], yy), px(z_range[1], yy)
        d.line([a, b], fill=(58, 62, 74), width=1)
        d.text((4, a[1] - 5), "y%.1f" % yy, fill=(120, 130, 150))
    for zz in np.arange(-3.0, z_range[1], 0.5):
        a, b = px(zz, y_range[0]), px(zz, y_range[1])
        d.line([a, b], fill=(58, 62, 74), width=1)
        d.text((a[0] - 8, h - 14), "%.1f" % zz, fill=(120, 130, 150))
    light = np.array([0.35, 0.85, 0.4])
    light /= np.linalg.norm(light)
    for group in (shell, extra):
        if not group:
            continue
        tris = np.concatenate([g[0] for g in group])
        cols = np.concatenate([g[1] for g in group]).astype(np.float32)
        n = np.cross(tris[:, 1] - tris[:, 0], tris[:, 2] - tris[:, 0])
        n /= np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-12)
        shade = 0.45 + 0.55 * np.abs(n @ light)
        cols = np.clip(cols * shade[:, None], 0, 255).astype(np.uint8)
        order = np.argsort(tris[:, :, 0].mean(1))             # the far side (large x) first... we look from -x, so draw big x first
        order = order[::-1]
        for t in order:
            pts = [px(tris[t, k, 2], tris[t, k, 1]) for k in range(3)]
            d.polygon(pts, fill=tuple(int(c) for c in cols[t]))
    sx, hip = header["seat"][0], header["seatHip"]
    for label, p, col in (("hip", (header["seat"][2], hip), (255, 90, 90)), ("wheel", (header["steer"][2], header["steer"][1]), (255, 255, 90))):
        q = px(*p)
        d.ellipse([q[0] - 5, q[1] - 5, q[0] + 5, q[1] + 5], outline=col, width=2)
        d.text((q[0] + 7, q[1] - 6), label, fill=col)
    d.text((8, 6), title, fill=(255, 255, 255))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def section(name, out_dir):
    header, parts = load(name)
    shell = []
    for (n, layer, w, t, c) in parts:
        if w >= 0:
            continue
        keep = t[:, :, 0].mean(1) >= -0.02
        cc = np.tile(np.array([90, 190, 255] if layer == "glass" else [185, 190, 198], np.uint8), (len(t), 1))
        shell.append((t[keep], cc[keep]))
    draw_cutaway(shell, [], header, os.path.join(out_dir, "%s_section.png" % name), "%s  cut-away (left half, seen from the right)" % name)


def interior_preview(name, out_dir, lod=0):
    """Cut-away with the procedural interior in place (side), and a transverse section at the driver's seat (from behind)."""
    from PIL import Image, ImageDraw
    import interior_gen as G
    cab = G.CABINS[name]
    header, parts = load(name)
    solid = np.concatenate([t for (n, layer, w, t, c) in parts if layer == "solid" and w < 0 and n == "body"])
    V, I = G.build(cab, lod, solid)
    tri = V["pos"][I.reshape(-1, 3)].astype(np.float64)
    col = V["col"][I.reshape(-1, 3)][:, 0, :3]
    keep = tri[:, :, 0].mean(1) >= -0.02
    shell = []
    for (n, layer, w, t, c) in parts:
        if w >= 0 or n == "interior":
            continue
        kp = t[:, :, 0].mean(1) >= -0.02
        cc = np.tile(np.array([90, 190, 255] if layer == "glass" else [120, 124, 132], np.uint8), (len(t), 1))
        shell.append((t[kp], cc[kp]))
    hdr = dict(header)
    hdr["seat"] = [cab.sx, 0, cab.sz]
    hdr["seatHip"] = cab.hip
    hdr["steer"] = [cab.sx, cab.wy, cab.wz, 20.0]
    draw_cutaway(shell, [(tri[keep], col[keep])], hdr, os.path.join(out_dir, "%s_interior_side.png" % name),
                 "%s interior LOD%d  (%d tris, occupant scale %.2f)" % (name, lod, len(I) // 3, cab.k))
    # transverse view from behind at the driver's seat: interior triangles with z <= sz + 0.15 plus the cabin outline from x-rays
    w, h = 900, 520
    img = Image.new("RGB", (w, h), (30, 32, 40))
    d = ImageDraw.Draw(img)
    sc = 300.0
    cx0, y0 = w / 2, h - 40

    def pt(x, y):
        return (cx0 - x * sc, y0 - y * sc)                  # the driver (x > 0) is on the LEFT of the picture, as seen from behind? (looking forward, left = +x)

    allsolid = np.concatenate([t for (n, layer, w_, t, c) in parts if w_ < 0])
    for yy in np.arange(0.15, cab.roof + 0.1, 0.04):
        xs = ray_hits(allsolid, (0, yy, cab.sz), 0)
        for x in xs:
            q = pt(x, yy)
            d.ellipse([q[0] - 1.5, q[1] - 1.5, q[0] + 1.5, q[1] + 1.5], fill=(150, 160, 175))
    for label, yy in (("floor", cab.floor), ("hip", cab.hip), ("belt", cab.belt), ("roof", cab.roof)):
        a, b = pt(-1.2, yy), pt(1.2, yy)
        d.line([a, b], fill=(70, 78, 92), width=1)
        d.text((8, a[1] - 10), "%s %.2f" % (label, yy), fill=(140, 150, 170))
    sel = tri[:, :, 2].mean(1) <= cab.sz + 0.15
    order = np.argsort(tri[sel][:, :, 2].mean(1))              # far = small z... we look forward (+z): draw the rear first
    tsel, csel = tri[sel], col[sel]
    n = np.cross(tsel[:, 1] - tsel[:, 0], tsel[:, 2] - tsel[:, 0])
    n /= np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-12)
    light = np.array([0.2, 0.8, -0.5])
    light /= np.linalg.norm(light)
    shd = 0.5 + 0.5 * np.abs(n @ light)
    order = np.argsort(tsel[:, :, 2].mean(1))
    for t in order:
        pts = [pt(tsel[t, k, 0], tsel[t, k, 1]) for k in range(3)]
        c = tuple(int(min(255, v * shd[t])) for v in csel[t])
        d.polygon(pts, fill=c)
    d.text((8, 6), "%s transverse section at the driver's seat (from behind, +x = left of the picture)" % name, fill=(255, 255, 255))
    img.save(os.path.join(out_dir, "%s_interior_back.png" % name))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--only")
    ap.add_argument("--out")
    ap.add_argument("--interior", action="store_true", help="preview the procedural interior (tools/interior_gen.py)")
    a = ap.parse_args()
    for n in ([a.only] if a.only else NAMES):
        if a.interior:
            interior_preview(n, a.out)
            continue
        probe(n)
        windows(n)
        if a.out:
            section(n, a.out)
