#!/usr/bin/env python3
"""A tiny z-buffered software renderer (numpy + Pillow) used to look at the baked cars and their procedural interiors from any camera,
with a stand-in for the seated driver, without starting Minecraft.  Gouraud shading, two lights, perspective camera."""
import math

import numpy as np


def look_at(eye, target, up=(0, 1, 0)):
    eye, target, up = (np.array(v, float) for v in (eye, target, up))
    f = target - eye
    f /= np.linalg.norm(f)
    r = np.cross(f, up)
    r /= np.linalg.norm(r)
    u = np.cross(r, f)
    return eye, r, u, f


def render(tris, vcol, vnrm, eye, target, fov=60.0, size=(1000, 600), bg=(30, 32, 40), light_dir=(0.35, 0.8, -0.45), ambient=0.42, clip_near=0.05):
    """tris (N,3,3) positions, vcol (N,3,3) 0..255 per-vertex colours, vnrm (N,3,3) per-vertex unit normals.  Returns an (h,w,3) uint8 array."""
    w, h = size
    eye, r, u, f = look_at(eye, target)
    P = tris.reshape(-1, 3) - eye
    x, y, z = P @ r, P @ u, P @ f
    focal = (h / 2) / math.tan(math.radians(fov) / 2)
    zz = np.maximum(z, 1e-6)
    sx = (x / zz * focal + w / 2).reshape(-1, 3)
    sy = (-y / zz * focal + h / 2).reshape(-1, 3)
    depth = z.reshape(-1, 3)
    L = np.array(light_dir, float)
    L /= np.linalg.norm(L)
    L2 = np.array([-0.5, 0.35, 0.6])
    L2 /= np.linalg.norm(L2)
    n = vnrm.reshape(-1, 3)
    # two-sided: flip the normal towards the viewer
    view = -(tris.reshape(-1, 3) - eye)
    view /= np.maximum(np.linalg.norm(view, axis=1, keepdims=True), 1e-9)
    flip = np.where((n * view).sum(1) < 0, -1.0, 1.0)[:, None]
    n = n * flip
    lam = np.clip(n @ L, 0, 1) * 0.7 + np.clip(n @ L2, 0, 1) * 0.28
    lit = np.clip((ambient + lam)[:, None] * vcol.reshape(-1, 3).astype(np.float32), 0, 255).reshape(-1, 3, 3)
    img = np.zeros((h, w, 3), np.float32)
    img[:] = np.array(bg, np.float32)
    zbuf = np.full((h, w), 1e30, np.float32)
    ok = (depth > clip_near).all(1)
    order = np.nonzero(ok)[0]
    for t in order:
        xs, ys = sx[t], sy[t]
        x0, x1 = int(max(0, math.floor(xs.min()))), int(min(w - 1, math.ceil(xs.max())))
        y0, y1 = int(max(0, math.floor(ys.min()))), int(min(h - 1, math.ceil(ys.max())))
        if x0 > x1 or y0 > y1:
            continue
        den = (ys[1] - ys[2]) * (xs[0] - xs[2]) + (xs[2] - xs[1]) * (ys[0] - ys[2])
        if abs(den) < 1e-9:
            continue
        gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
        l0 = ((ys[1] - ys[2]) * (gx - xs[2]) + (xs[2] - xs[1]) * (gy - ys[2])) / den
        l1 = ((ys[2] - ys[0]) * (gx - xs[2]) + (xs[0] - xs[2]) * (gy - ys[2])) / den
        l2 = 1.0 - l0 - l1
        inside = (l0 >= -1e-4) & (l1 >= -1e-4) & (l2 >= -1e-4)
        if not inside.any():
            continue
        d = l0 * depth[t, 0] + l1 * depth[t, 1] + l2 * depth[t, 2]
        sub = zbuf[y0:y1 + 1, x0:x1 + 1]
        mask = inside & (d < sub)
        if not mask.any():
            continue
        sub[mask] = d[mask]
        col = l0[..., None] * lit[t, 0] + l1[..., None] * lit[t, 1] + l2[..., None] * lit[t, 2]
        img[y0:y1 + 1, x0:x1 + 1][mask] = col[mask]
    return np.clip(img, 0, 255).astype(np.uint8)


# ------------------------------------------------------------------------------------------------------ geometry helpers
def mesh_arrays(V, I):
    """(vertex array, index array) of the ttm format -> (tris, vcol, vnrm) for render(); emissive vertices stay bright (flag via mat[2])."""
    pos = V["pos"].astype(np.float64)
    nrm = V["nrm"].astype(np.float64) / 127.0
    nrm /= np.maximum(np.linalg.norm(nrm, axis=1, keepdims=True), 1e-9)
    col = V["col"][:, :3].astype(np.float32)
    tri = I.reshape(-1, 3)
    return pos[tri], col[tri], nrm[tri]


def box_tris(x0, y0, z0, x1, y1, z1, color, R=None, pivot=None):
    c = np.array([(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)], float)
    faces = [((0, 3, 2, 1), (0, 0, -1)), ((4, 5, 6, 7), (0, 0, 1)), ((0, 4, 7, 3), (-1, 0, 0)), ((1, 2, 6, 5), (1, 0, 0)), ((0, 1, 5, 4), (0, -1, 0)), ((3, 7, 6, 2), (0, 1, 0))]
    T, C, N = [], [], []
    for idx, nn in faces:
        p = c[list(idx)]
        n = np.array(nn, float)
        if R is not None:
            p = (p - pivot) @ R.T + pivot
            n = R @ n
        for a, b, d in ((0, 1, 2), (0, 2, 3)):
            T.append([p[a], p[b], p[d]])
            C.append([color] * 3)
            N.append([n] * 3)
    return np.array(T), np.array(C, np.float32), np.array(N)


def rot_x(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def occupant_proxy(cab, scale=None, skin=(222, 178, 140), cloth=(40, 60, 120)):
    """Boxes where the seated player / villager is drawn in game: hips on the seat, legs forward-down to the floor, arms to the wheel,
    scale k.  Sizes are the vanilla player model (pixels * 0.9375 / 16) times k."""
    k = cab.k if scale is None else scale
    pxs = 0.9375 / 16 * k
    hipy = cab.hip
    x, z = cab.sx, cab.sz
    parts = []
    # torso 8x12x4 px, hips at y = hip; the upper body is vertical
    parts.append(box_tris(x - 4 * pxs, hipy, z - 2 * pxs, x + 4 * pxs, hipy + 12 * pxs, z + 2 * pxs, cloth))
    # head 8x8x8 on top
    parts.append(box_tris(x - 4 * pxs, hipy + 12 * pxs, z - 4 * pxs, x + 4 * pxs, hipy + 20 * pxs, z + 4 * pxs, skin))
    # legs 4x12x4 each, hanging from the hips, pitched forward so that the feet reach the floor
    drop = hipy - cab.floor - 0.02
    L = 12 * pxs
    ang = math.acos(max(-1.0, min(1.0, drop / L)))
    for sgn in (-1, 1):
        px = x + sgn * 2 * pxs
        pivot = np.array([px, hipy, z])
        R = rot_x(-ang)              # a point below the pivot swings to +z (forward)
        parts.append(box_tris(px - 2 * pxs, hipy - L, z - 2 * pxs, px + 2 * pxs, hipy, z + 2 * pxs, (60, 62, 90), R, pivot))
    # arms 4x12x4 from the shoulders, pitched forward 70 degrees
    for sgn in (-1, 1):
        px = x + sgn * 6 * pxs
        pivot = np.array([px, hipy + 10 * pxs, z])
        R = rot_x(-1.22)
        parts.append(box_tris(px - 2 * pxs, hipy + 10 * pxs - 12 * pxs, z - 2 * pxs, px + 2 * pxs, hipy + 10 * pxs, z + 2 * pxs, cloth, R, pivot))
    T = np.concatenate([p[0] for p in parts])
    C = np.concatenate([p[1] for p in parts])
    N = np.concatenate([p[2] for p in parts])
    return T, C, N


def wheel_proxy(cab, tilt_deg=20.0, radius=0.19):
    """The steering wheel ring (the real one is drawn by the renderer from tools' anchors) as 16 little boxes."""
    T, C, N = [], [], []
    R = rot_x(-math.radians(tilt_deg))
    pivot = np.array([cab.sx, cab.wy, cab.wz])
    for i in range(16):
        a = 2 * math.pi * i / 16
        cx, cy = radius * math.cos(a), radius * math.sin(a)
        t, c, n = box_tris(cab.sx + cx - 0.02, cab.wy + cy - 0.02, cab.wz - 0.02, cab.sx + cx + 0.02, cab.wy + cy + 0.02, cab.wz + 0.02, (24, 24, 28), R, pivot)
        T.append(t)
        C.append(c)
        N.append(n)
    return np.concatenate(T), np.concatenate(C), np.concatenate(N)
