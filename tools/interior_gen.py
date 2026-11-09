#!/usr/bin/env python3
"""Procedural car interiors for the Tesla Tycoon models (the uploaded glTF cars are hollow shells).

Everything is built from a handful of primitives (soft "squircle" cushions, boxes, cylinders, a pixel-art touch screen) with vertex
colours and material values, in the baked vehicle frame: +Z = front, +Y = up, +X = the car's left (the driver sits on the left), y = 0 on the
ground, units = blocks.  build(cabin, lod, shell) returns the vertex/index arrays of the part "interior" for that level of detail.

A Cabin holds the measured numbers of one car (floor, roof, window sill, half width, seat and wheel anchors ...).  The same numbers decide how
big the seated driver may be (occ_scale), so no head, arm or leg can end up outside the car.
"""
import math
from dataclasses import dataclass, field

import numpy as np

import ttm_common as T

# ---------------------------------------------------------------------------------------------------------------- materials
LEATHER = (0, 205, 0, 1)
FABRIC = (0, 235, 0, 1)
PLASTIC = (0, 170, 0, 1)
SOFT = (0, 215, 0, 1)
GLOSS = (60, 70, 0, 1)
CHROME = (230, 60, 0, 1)
CARPET = (0, 250, 0, 1)


def glow(level=255):
    return (0, 120, level, 1)


def rgb(h):
    """'#rrggbb' or (r, g, b) -> (r, g, b, 255) in 0..255 (colours are authored in sRGB, the shader works on the stored values)."""
    if isinstance(h, str):
        h = h.lstrip("#")
        return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255)
    return (int(h[0]), int(h[1]), int(h[2]), 255)


def shade(c, f):
    return (int(max(0, min(255, c[0] * f))), int(max(0, min(255, c[1] * f))), int(max(0, min(255, c[2] * f))), c[3])


def rot_x(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def rot_y(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def rot_z(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def _spow(x, e):
    return math.copysign(abs(x) ** e, x)


@dataclass
class Palette:
    seat: str = "#1a1a1c"          # seat leather / fabric
    seat_dark: str = "#101012"     # bolsters
    accent: str = "#d8d4cb"        # centre inserts, stitching
    dash: str = "#17181b"          # dashboard top
    dash_trim: str = "#b9bcc2"     # trim band across the dash
    door: str = "#202225"          # door cards
    carpet: str = "#1b1c1f"
    headliner: str = "#cfcdc6"
    console: str = "#17181b"
    glow: str = "#6fe3ff"          # ambient LED strips


@dataclass
class Cabin:
    name: str
    sx: float                      # driver seat centre x (left = +)
    sz: float                      # driver hip z
    hip: float                     # y of the top of the seat cushion
    floor: float
    roof: float                    # height of the roof lining above the driver's head
    belt: float                    # window sill height
    hw: float                      # inner half width of the doors (x of the door cards)
    wz: float                      # steering wheel centre z
    wy: float                      # steering wheel centre y
    dash_top: float                # y of the top of the dashboard
    dash_front: float              # z where the dashboard meets the windscreen
    rear_z: float                  # z of the rear window sill (parcel shelf / boot wall)
    rear_seats: bool = True
    glass_roof: bool = False       # a glass roof gets no headliner
    seat_w: float = 0.52
    lining_front: float = None     # z where the headliner meets the windscreen header (default: 0.55 in front of the driver)
    lining_hw: float = 0.62        # half width of the headliner
    pal: Palette = field(default_factory=Palette)
    k: float = 0.8                 # scale of the seated occupant (computed from the roof)


# ---------------------------------------------------------------------------------------------------------------- the mesh builder
class Mesh:
    def __init__(self):
        self.V = []   # (pos, nrm, col, mat)
        self.I = []

    def _v(self, p, n, col, mat):
        self.V.append((tuple(float(x) for x in p), tuple(float(x) for x in n), tuple(col), tuple(mat)))
        return len(self.V) - 1

    def quad(self, p0, p1, p2, p3, col, mat, n=None):
        a, b, c = np.array(p0, float), np.array(p1, float), np.array(p2, float)
        if n is None:
            n = np.cross(b - a, c - a)
            n = n / max(np.linalg.norm(n), 1e-9)
        i = [self._v(p, n, col, mat) for p in (p0, p1, p2, p3)]
        self.I += [i[0], i[1], i[2], i[0], i[2], i[3]]

    def box(self, x0, y0, z0, x1, y1, z1, col, mat, R=None, pivot=None, top=1.0, side=0.82, bottom=0.55):
        """Axis aligned box (optionally rotated about a pivot).  top/side/bottom: brightness of the faces (cheap ambient occlusion)."""
        c = np.array([(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)], float)
        faces = [((0, 3, 2, 1), (0, 0, -1), side), ((4, 5, 6, 7), (0, 0, 1), side), ((0, 4, 7, 3), (-1, 0, 0), side),
                 ((1, 2, 6, 5), (1, 0, 0), side), ((0, 1, 5, 4), (0, -1, 0), bottom), ((3, 7, 6, 2), (0, 1, 0), top)]
        for idx, n, f in faces:
            pts = c[list(idx)]
            nn = np.array(n, float)
            if R is not None:
                pts = (pts - pivot) @ R.T + pivot
                nn = R @ nn
            self.quad(pts[0], pts[1], pts[2], pts[3], shade(col, f), mat, nn)

    def squircle(self, cx, cy, cz, hx, hy, hz, e, col, mat, nu=10, nv=6, R=None, pivot=None, ao=0.35):
        """A soft box: superellipsoid with exponent e (small = boxy).  The lower part is darkened by `ao` (ambient occlusion)."""
        rows = []
        for j in range(nv + 1):
            v = -math.pi / 2 + math.pi * j / nv
            cv, sv = _spow(math.cos(v), e), _spow(math.sin(v), e)
            ncv, nsv = _spow(math.cos(v), 2 - e), _spow(math.sin(v), 2 - e)
            row = []
            for i in range(nu + 1):
                u = -math.pi + 2 * math.pi * i / nu
                cu, su = _spow(math.cos(u), e), _spow(math.sin(u), e)
                ncu, nsu = _spow(math.cos(u), 2 - e), _spow(math.sin(u), 2 - e)
                p = np.array([hx * cv * cu, hy * sv, hz * cv * su]) + np.array([cx, cy, cz], float)
                n = np.array([ncv * ncu / max(hx, 1e-6), nsv / max(hy, 1e-6), ncv * nsu / max(hz, 1e-6)])
                n = n / max(np.linalg.norm(n), 1e-9)
                if R is not None:
                    p = np.array(pivot, float) + R @ (p - np.array(pivot, float))
                    n = R @ n
                f = 1.0 - ao * max(0.0, -math.sin(v)) if ao else 1.0
                row.append(self._v(p, n, shade(col, f), mat))
            rows.append(row)
        for j in range(nv):
            for i in range(nu):
                a, b, c, d = rows[j][i], rows[j][i + 1], rows[j + 1][i + 1], rows[j + 1][i]
                if j > 0:
                    self.I += [a, c, b]          # (counter-clockwise from outside)
                if j < nv - 1:
                    self.I += [a, d, c]

    def cylinder(self, p0, p1, r, col, mat, seg=10, cap=True):
        a, b = np.array(p0, float), np.array(p1, float)
        ax = b - a
        ln = np.linalg.norm(ax)
        ax = ax / ln
        t = np.array([1.0, 0, 0]) if abs(ax[0]) < 0.9 else np.array([0, 1.0, 0])
        u = np.cross(ax, t)
        u /= np.linalg.norm(u)
        v = np.cross(ax, u)
        ring0, ring1 = [], []
        for k in range(seg):
            ang = 2 * math.pi * k / seg
            d = math.cos(ang) * u + math.sin(ang) * v
            ring0.append(self._v(a + d * r, d, col, mat))
            ring1.append(self._v(b + d * r, d, col, mat))
        for k in range(seg):
            n = (k + 1) % seg
            self.I += [ring0[k], ring0[n], ring1[n], ring0[k], ring1[n], ring1[k]]
        if cap:
            for p, rr, sign in ((a, ring0, -1), (b, ring1, 1)):
                c = self._v(p, ax * sign, shade(col, 0.9), mat)
                cap_ring = [self._v(self.V[i][0], ax * sign, shade(col, 0.9), mat) for i in rr]
                for k in range(seg):
                    n = (k + 1) % seg
                    self.I += [c, cap_ring[k], cap_ring[n]] if sign > 0 else [c, cap_ring[n], cap_ring[k]]

    def arrays(self):
        if not self.V:
            return np.zeros(0, dtype=T.VERT_DT), np.zeros(0, dtype=np.uint32)
        v = np.zeros(len(self.V), dtype=T.VERT_DT)
        for i, (p, n, c, m) in enumerate(self.V):
            v[i]["pos"] = p
            v[i]["nrm"] = np.clip(np.round(np.array(n) * 127), -127, 127).astype(np.int8)
            v[i]["col"] = c
            v[i]["mat"] = m
        return v, np.array(self.I, dtype=np.uint32)


# ---------------------------------------------------------------------------------------------------------------- parts
def seat(m, c, x, z, lod, rear=False, width=None, heads=1, tilt=None, tall=1.0, mb=None):
    """Cushion, bolsters and base go into `m`; the backrest and head restraints into `mb` (default: `m`)."""
    if mb is None:
        mb = m
    if not rear:
        z = z + 0.09            # the seated figure is centred on the hip anchor: the backrest is just behind its back
    pal = c.pal
    w = width or c.seat_w
    depth = 0.54
    hip = c.hip + (0.015 if rear else 0.0)
    nu, nv = (10, 6) if lod == 0 else (6, 4)
    leather = rgb(pal.seat)
    dark = rgb(pal.seat_dark)
    acc = rgb(pal.accent)
    tilt = math.radians(13 if rear else 9) if tilt is None else tilt
    # cushion
    m.squircle(x, hip - 0.07, z, w / 2, 0.07, depth / 2, 0.32, leather, LEATHER, nu, nv)
    # side bolsters of the cushion
    if lod == 0:
        for s in (-1, 1):
            m.squircle(x + s * (w / 2 - 0.045), hip - 0.035, z - 0.01, 0.05, 0.05, depth / 2 - 0.03, 0.4, dark, LEATHER, 8, 4)
        m.squircle(x, hip - 0.012, z + 0.02, w / 2 - 0.12, 0.016, depth / 2 - 0.1, 0.45, acc, LEATHER, 8, 4)       # centre insert
    # rails / base
    m.box(x - w / 2 + 0.07, c.floor, z - depth / 2 + 0.08, x + w / 2 - 0.07, hip - 0.13, z + depth / 2 - 0.1, rgb(pal.console), PLASTIC)
    # backrest, tilted back about the rear edge of the cushion
    pivot = np.array([x, hip - 0.02, z - depth / 2 + 0.04])
    R = rot_x(-tilt)
    back_h = 0.60 * tall
    mb.squircle(x, pivot[1] + back_h / 2, pivot[2] - 0.03, w / 2, back_h / 2, 0.065, 0.32, leather, LEATHER, nu, nv, R, pivot)
    if lod == 0:
        for s in (-1, 1):
            mb.squircle(x + s * (w / 2 - 0.045), pivot[1] + back_h * 0.5, pivot[2] - 0.0, 0.045, back_h / 2 - 0.04, 0.07, 0.4, dark, LEATHER, 6, 5, R, pivot)
        mb.squircle(x, pivot[1] + back_h * 0.5, pivot[2] + 0.036, w / 2 - 0.13, back_h / 2 - 0.1, 0.014, 0.45, acc, LEATHER, 8, 5, R, pivot)   # centre insert
    # head restraint(s)
    for k in range(heads):
        hx = x + ((k - (heads - 1) / 2) * (w / heads) if heads > 1 else 0.0)
        hw = 0.12 if heads == 1 else 0.10
        mb.squircle(hx, pivot[1] + back_h + 0.07, pivot[2] - 0.035, hw, 0.085, 0.05, 0.4, leather, LEATHER, 8 if lod == 0 else 6, 4, R, pivot)
        if lod == 0:
            for s in (-1, 1):
                mb.box(hx + s * 0.05 - 0.007, pivot[1] + back_h - 0.02, pivot[2] - 0.06, hx + s * 0.05 + 0.007, pivot[1] + back_h + 0.03, pivot[2] - 0.045,
                      (120, 122, 128, 255), CHROME, R, pivot)


def screen_ui(m, c, cx, cy, cz, w, h, tilt, lod):
    """The central touch screen: a glowing pixel-art Tesla UI (navigation map, car, speed, app row) on a black bezel.
    The face looks back at the driver (-z); tilt > 0 leans the top towards the driver."""
    cols, rows = (24, 13) if lod == 0 else (12, 7)
    R = rot_x(-tilt)
    pivot = np.array([cx, cy, cz])
    m.box(cx - w / 2 - 0.012, cy - h / 2 - 0.012, cz, cx + w / 2 + 0.012, cy + h / 2 + 0.012, cz + 0.016, (8, 8, 10, 255), GLOSS, R, pivot, 0.9, 0.7, 0.5)
    sx, sy = w / cols, h / rows
    zf = cz - 0.002
    for iy in range(rows):
        for ix in range(cols):
            u, v = (ix + 0.5) / cols, 1.0 - (iy + 0.5) / rows          # u 0..1 left -> right as the driver sees it, v 0..1 bottom -> top
            if v > 0.9:                                                  # status bar
                col = np.array([24, 34, 56])
            elif u < 0.30:                                               # the car panel
                col = np.array([16, 22, 36])
                if 0.12 < u < 0.22 and 0.3 < v < 0.8:
                    col = np.array([205, 210, 220])                      # car silhouette
                if 0.14 < u < 0.20 and 0.72 < v < 0.8:
                    col = np.array([90, 100, 120])
                if 0.06 < u < 0.26 and 0.12 < v < 0.2:
                    col = np.array([60, 200, 120])                       # range bar
            else:                                                        # the map
                col = np.array([20, 30, 46])
                if abs((v - 0.52) - 0.85 * (u - 0.65)) < 0.07:
                    col = np.array([48, 62, 88])                         # a road
                if abs(v - 0.42) < 0.04 and u > 0.32:
                    col = np.array([48, 62, 88])
                if abs(u - 0.62) < 0.03 and v > 0.2:
                    col = np.array([48, 62, 88])
                if abs((v - 0.30) - 0.55 * (u - 0.40)) < 0.045 and 0.36 < u < 0.82:
                    col = np.array([36, 140, 255])                       # the route
                if (u - 0.58) ** 2 + (v - 0.40) ** 2 < 0.0030:
                    col = np.array([255, 70, 60])                        # the car marker
            if v < 0.14 and u > 0.30:                                    # app dock
                k = int(u * 14)
                col = [np.array([80, 200, 120]), np.array([230, 90, 70]), np.array([240, 200, 70]), np.array([90, 150, 240])][(k // 2) % 4] if k % 2 == 0 else np.array([14, 20, 32])
            xr = cx + w / 2 - ix * sx                                    # +x is the driver's left: the picture's left edge is at +x
            y0 = cy - h / 2 + (rows - 1 - iy) * sy
            pts = np.array([(xr, y0, zf), (xr - sx, y0, zf), (xr - sx, y0 + sy, zf), (xr, y0 + sy, zf)])
            pts = (pts - pivot) @ R.T + pivot
            m.quad(pts[0], pts[1], pts[2], pts[3], (int(col[0]), int(col[1]), int(col[2]), 255), glow(235), R @ np.array([0, 0, -1.0]))


def dashboard(m, c, lod):
    pal = c.pal
    x_in = c.hw - 0.04
    dark = rgb(pal.dash)
    trim = rgb(pal.dash_trim)
    zf = c.dash_front
    zb = c.wz + 0.13                                    # just in front of the steering wheel
    top = c.dash_top
    nu, nv = (12, 5) if lod == 0 else (8, 3)
    # main dash body: a long low slab, softly rounded on top
    m.squircle(0.0, top - 0.11, (zf + zb) / 2, x_in, 0.11, (zf - zb) / 2, 0.30, dark, SOFT, nu + 4, nv, ao=0.2)
    # trim band across the middle of the dash (white / wood / carbon, depending on the car) and a thin LED line above it
    m.box(-x_in + 0.05, top - 0.15, zb - 0.012, x_in - 0.05, top - 0.115, zb + 0.004, trim, SOFT, None, None, 1.0, 1.0, 0.7)
    m.box(-x_in + 0.10, top - 0.105, zb - 0.014, x_in - 0.10, top - 0.098, zb - 0.004, rgb(pal.glow), glow(210), None, None, 1.0, 1.0, 1.0)
    # air vents (long dark slits) in front of the driver and the passenger
    if lod == 0:
        for s in (-1, 1):
            m.box(s * 0.62 - 0.17, top - 0.2, zb - 0.015, s * 0.62 + 0.17, top - 0.185, zb - 0.002, (6, 6, 8, 255), GLOSS)
    # instrument hood in front of the driver
    m.squircle(c.sx, top + 0.0, zb + 0.06, 0.26, 0.04, 0.09, 0.4, dark, SOFT, 8, 4, ao=0.2)
    # the touch screen, in the middle of the dash, tilted a little towards the driver
    screen_ui(m, c, 0.0, top + 0.115, zb + 0.14, 0.50, 0.29, math.radians(10), lod)
    m.box(-0.02, top - 0.01, zb + 0.09, 0.02, top + 0.0, zb + 0.14, (10, 10, 12, 255), PLASTIC)        # the screen's foot
    # glove box / passenger side panel
    if lod == 0:
        m.box(-x_in + 0.12, top - 0.30, zb - 0.02, -0.28, top - 0.17, zb, shade(dark, 1.35), SOFT)


def console(m, c, lod):
    pal = c.pal
    z0, z1 = c.sz - 0.22, c.sz + 0.52
    top = c.hip + 0.045
    col = rgb(pal.console)
    cw = max(0.07, c.sx - c.seat_w / 2 - 0.012)                                  # half width: fits between the two seats
    nu, nv = (10, 4) if lod == 0 else (6, 3)
    m.squircle(0.0, (c.floor + top) / 2, (z0 + z1) / 2, cw, (top - c.floor) / 2, (z1 - z0) / 2, 0.3, col, SOFT, nu, nv, ao=0.2)
    m.squircle(0.0, top - 0.005, c.sz + 0.02, cw - 0.02, 0.022, 0.2, 0.4, rgb(pal.seat), LEATHER, 8, 4)                  # padded armrest lid
    if lod == 0:
        for s in (-1, 1):
            m.cylinder((s * cw * 0.45, top - 0.01, c.sz + 0.38), (s * cw * 0.45, top - 0.0, c.sz + 0.38), min(0.036, cw * 0.4), (4, 4, 6, 255), PLASTIC, 10)        # cup holders
        m.box(-cw * 0.7, top - 0.01, c.sz + 0.43, cw * 0.7, top + 0.005, c.sz + 0.5, (6, 6, 8, 255), GLOSS)                       # the gear / shift pad
    # rear of the console: air vents for the back seats
    if lod == 0 and c.rear_seats:
        m.box(-0.06, c.floor + 0.12, c.sz - 0.24, 0.06, c.floor + 0.2, c.sz - 0.22, (6, 6, 8, 255), GLOSS)


def doors(m, c, lod):
    pal = c.pal
    col = rgb(pal.door)
    acc = rgb(pal.accent)
    for s in (-1, 1):
        xo = s * c.hw
        xi = s * (c.hw - 0.055)
        x0, x1 = (min(xo, xi), max(xo, xi))
        z0, z1 = c.sz - (1.15 if c.rear_seats else 0.55), c.sz + 0.78
        m.box(x0, c.floor + 0.18, z0, x1, c.belt - 0.03, z1, col, PLASTIC)                                          # door card
        # armrest + trim insert + window switches (front doors)
        m.squircle(s * (c.hw - 0.085), c.belt - 0.15, c.sz + 0.02, 0.05, 0.03, 0.30, 0.4, rgb(pal.seat), LEATHER, 8, 4)
        m.box(s * (c.hw - 0.062) - 0.005, c.belt - 0.30, c.sz - 0.34, s * (c.hw - 0.062) + 0.005, c.belt - 0.19, c.sz + 0.44, acc, SOFT)
        if lod == 0:
            m.box(s * (c.hw - 0.066) - 0.006, c.belt - 0.065, c.sz - 0.12, s * (c.hw - 0.066) + 0.006, c.belt - 0.05, c.sz + 0.1, rgb(pal.glow), glow(200))     # ambient LED
            m.box(s * (c.hw - 0.07) - 0.01, c.belt - 0.22, c.sz + 0.5, s * (c.hw - 0.07) + 0.01, c.belt - 0.19, c.sz + 0.62, (150, 152, 158, 255), CHROME)   # handle


def floor_and_pedals(m, c, lod):
    carpet = rgb(c.pal.carpet)
    y = c.floor + 0.012
    m.box(-c.hw + 0.05, c.floor, c.sz - (1.15 if c.rear_seats else 0.55), c.hw - 0.05, y, c.wz + 0.38, carpet, CARPET, None, None, 1.0, 1.0, 1.0)
    m.box(-0.17, y, c.sz - (1.1 if c.rear_seats else 0.5), 0.17, y + 0.02, c.wz + 0.3, shade(carpet, 0.8), CARPET)          # tunnel
    # pedals: brake + accelerator + dead pedal
    pz = c.wz + 0.2
    m.box(c.sx - 0.09, c.floor + 0.12, pz, c.sx - 0.01, c.floor + 0.125 + 0.08, pz + 0.03, (30, 30, 34, 255), PLASTIC, rot_x(math.radians(-18)), np.array([c.sx, c.floor + 0.12, pz]))
    m.box(c.sx + 0.05, c.floor + 0.10, pz + 0.04, c.sx + 0.12, c.floor + 0.18, pz + 0.07, (30, 30, 34, 255), PLASTIC, rot_x(math.radians(-24)), np.array([c.sx + 0.05, c.floor + 0.10, pz + 0.04]))
    if lod == 0:
        m.box(c.sx - 0.28, c.floor + 0.06, pz - 0.02, c.sx - 0.14, c.floor + 0.1, pz + 0.18, shade(carpet, 1.6), PLASTIC, rot_x(math.radians(-25)), np.array([c.sx - 0.28, c.floor + 0.06, pz - 0.02]))


def column(m, c, lod):
    """The steering column shroud between the dash and the wheel hub (the wheel itself is the animated part drawn by the renderer)."""
    a = np.array([c.sx, c.wy - 0.02, c.wz + 0.02])
    b = np.array([c.sx, c.wy - 0.1, c.wz + 0.2])
    m.cylinder(a, b, 0.045, (14, 14, 17, 255), PLASTIC, 10)
    if lod == 0:
        m.box(c.sx - 0.14, c.wy - 0.06, c.wz + 0.06, c.sx - 0.1, c.wy - 0.035, c.wz + 0.17, (20, 20, 24, 255), PLASTIC)      # indicator stalks
        m.box(c.sx + 0.1, c.wy - 0.06, c.wz + 0.06, c.sx + 0.14, c.wy - 0.035, c.wz + 0.17, (20, 20, 24, 255), PLASTIC)


def rear_shelf(m, c, lod):
    """Behind the last seats: the parcel shelf (or the bulkhead of a two-seater)."""
    pal = c.pal
    z = c.sz - (1.0 + 0.55 if c.rear_seats else 0.42)
    if c.rear_seats:
        m.box(-c.hw + 0.08, c.belt - 0.12, c.rear_z + 0.02, c.hw - 0.08, c.belt - 0.09, z + 0.0, rgb(pal.door), PLASTIC)
    else:
        m.box(-c.hw + 0.07, c.floor + 0.06, z - 0.02, c.hw - 0.07, c.belt + 0.28, z + 0.04, rgb(pal.door), PLASTIC)           # bulkhead behind the seats
        m.squircle(0.0, c.belt + 0.13, z + 0.07, 0.30, 0.1, 0.03, 0.4, rgb(pal.seat), LEATHER, 8, 4)


def headliner(m, c, lod, shell):
    """Follows the roof exactly: a height field sampled by ray casting against the shell (vertical rays), a little below the roof."""
    if c.glass_roof or shell is None:
        return
    import cabin_probe as P
    zs = np.linspace(c.lining_front if c.lining_front is not None else c.sz + 0.55, c.sz - (1.5 if c.rear_seats else 0.7), 11 if lod == 0 else 6)
    xs = np.linspace(-c.lining_hw, c.lining_hw, 9 if lod == 0 else 5)
    H = np.full((len(zs), len(xs)), np.nan)
    for i, z in enumerate(zs):
        for j, x in enumerate(xs):
            ys = P.ray_hits(shell, (x, 0.0, z), 1)
            ys = ys[ys > c.belt + 0.25]
            if len(ys):
                H[i, j] = ys.min() - 0.025
    if (~np.isnan(H)).sum() < 6:
        return
    # fill the holes (a glass-lined rear, gaps in the mesh) from their neighbours, staying below the roof: one continuous sheet
    for _ in range(8):
        if not np.isnan(H).any():
            break
        Hn = H.copy()
        for i in range(H.shape[0]):
            for j in range(H.shape[1]):
                if np.isnan(H[i, j]):
                    nb = [H[a, b] for a, b in ((i - 1, j), (i + 1, j), (i, j - 1), (i, j + 1))
                          if 0 <= a < H.shape[0] and 0 <= b < H.shape[1] and not np.isnan(H[a, b])]
                    if nb:
                        Hn[i, j] = min(nb)
        H = Hn
    ok = ~np.isnan(H)
    col = rgb(c.pal.headliner)
    ids = -np.ones(H.shape, int)
    for i in range(len(zs)):
        for j in range(len(xs)):
            if ok[i, j]:
                ids[i, j] = m._v((xs[j], H[i, j], zs[i]), (0, -1, 0), shade(col, 0.95 if 0 < j < len(xs) - 1 else 0.8), FABRIC)
    for i in range(len(zs) - 1):
        for j in range(len(xs) - 1):
            q = [ids[i, j], ids[i, j + 1], ids[i + 1, j + 1], ids[i + 1, j]]
            if min(q) >= 0:
                m.I += [q[0], q[1], q[2], q[0], q[2], q[3]]


def build_parts(c, lod, shell=None):
    """The interior of one car at level of detail `lod` (0 or 1), split in three parts so that whoever sits in the car can be seen:
       interior       always drawn: dashboard, console, doors, floor, cushions, passenger seat, headliner
       interior_back  the driver's seat back and head restraint: they stand right behind the seated figure, so they hide its torso
                      and the cape from a chase camera - the renderer skips this part while the car has an occupant
       interior_rear  the rear bench and the parcel shelf: from behind they cut the seated figure off at the chest (a 'floating head')
    -> {part name: (vertices, indices)}"""
    main, back, rear = Mesh(), Mesh(), Mesh()
    dashboard(main, c, lod)
    column(main, c, lod)
    console(main, c, lod)
    doors(main, c, lod)
    floor_and_pedals(main, c, lod)
    seat(main, c, c.sx, c.sz, lod, mb=back)                      # driver
    seat(main, c, -c.sx, c.sz, lod)                              # front passenger
    if c.rear_seats:
        seat(rear, c, 0.0, c.sz - 0.98, lod, rear=True, width=min(1.18, 2 * c.hw - 0.2), heads=3, tilt=math.radians(14))
    rear_shelf(rear, c, lod)
    headliner(main, c, lod, shell)
    return {"interior": main.arrays(), "interior_back": back.arrays(), "interior_rear": rear.arrays()}


def build(c, lod, shell=None):
    """The whole interior as one mesh (previews and tests)."""
    parts = build_parts(c, lod, shell)
    vs = [v for v, i in parts.values()]
    off, idx = 0, []
    for v, i in parts.values():
        idx.append(i + off)
        off += len(v)
    return np.concatenate(vs), np.concatenate(idx).astype(np.uint32)


# ---------------------------------------------------------------------------------------------------------------- occupant fitting
def occupant_scale(c, margin=0.07):
    """The seated figure (hip to the top of the head = 1.30 blocks for a villager at scale 1) must be lower than the roof lining."""
    return max(0.55, min(0.95, (c.roof - c.hip - margin) / 1.30))


# ---------------------------------------------------------------------------------------------------------------- the five cabins
# measured from the baked meshes (tools/cabin_probe.py) and fitted with the cut-away previews
CABINS = {
    "l1_classic": Cabin("l1_classic", sx=0.36, sz=0.05, hip=0.46, floor=0.17, roof=1.45, belt=1.0, hw=0.74, wz=0.55, wy=0.76, dash_top=0.90,
                        dash_front=1.30, rear_z=-1.8, rear_seats=True, glass_roof=False, seat_w=0.48,
                        pal=Palette(seat="#d8d0c0", seat_dark="#b9b09f", accent="#2a2a2c", dash="#141416", dash_trim="#8a8d93", door="#1d1e21",
                                    carpet="#1a1a1c", headliner="#c9c6bd", console="#17171a", glow="#7fe8ff")),
    "l2_model3": Cabin("l2_model3", sx=0.38, sz=0.0, hip=0.50, floor=0.22, roof=1.54, belt=1.0, hw=0.90, wz=0.50, wy=0.80, dash_top=0.93,
                       dash_front=1.20, rear_z=-2.0, rear_seats=True, glass_roof=True, seat_w=0.52,
                       pal=Palette(seat="#17171a", seat_dark="#0e0e10", accent="#e6e4df", dash="#121214", dash_trim="#cfd2d6", door="#1c1d20",
                                   carpet="#18181a", headliner="#cfcdc6", console="#141416", glow="#8fe9ff")),
    "l3_cybertruck": Cabin("l3_cybertruck", sx=0.42, sz=-0.05, hip=0.66, floor=0.34, roof=1.83, belt=1.30, hw=0.88, wz=0.52, wy=0.96, dash_top=1.08,
                           dash_front=1.65, rear_z=-1.0, rear_seats=True, glass_roof=True, seat_w=0.54,
                           pal=Palette(seat="#2b2d31", seat_dark="#1a1b1f", accent="#a3a7ae", dash="#1c1e22", dash_trim="#7c8087", door="#24262a",
                                       carpet="#1a1b1e", headliner="#a8abb0", console="#1a1b1f", glow="#7fe8ff")),
    "l4_cybercab": Cabin("l4_cybercab", sx=0.42, sz=0.05, hip=0.58, floor=0.36, roof=1.74, belt=1.25, hw=0.93, wz=0.60, wy=0.87, dash_top=1.0,
                         dash_front=1.05, rear_z=-2.6, rear_seats=True, glass_roof=True, seat_w=0.54,
                         pal=Palette(seat="#ecebe5", seat_dark="#d5d2ca", accent="#bdb9af", dash="#e9e7e1", dash_trim="#f6f5f2", door="#e3e1db",
                                     carpet="#4a4b4f", headliner="#f2f1ed", console="#d9d7d0", glow="#ffe9b0")),
    "l5_roadster": Cabin("l5_roadster", sx=0.36, sz=-0.10, hip=0.42, floor=0.14, roof=1.47, belt=0.90, hw=0.83, wz=0.42, wy=0.68, dash_top=0.80,
                         dash_front=1.20, rear_z=-0.8, rear_seats=False, glass_roof=False, seat_w=0.50, lining_front=0.78, lining_hw=0.8,
                         pal=Palette(seat="#8c1118", seat_dark="#5d0b10", accent="#141416", dash="#111113", dash_trim="#b7b9be", door="#17171a",
                                     carpet="#141416", headliner="#2a2a2c", console="#141416", glow="#ff4a3a")),
}
for _c in CABINS.values():
    _c.k = occupant_scale(_c)
