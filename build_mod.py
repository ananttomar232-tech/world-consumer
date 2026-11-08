#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RealityShop - Minecraft FABRIC 1.20.1 workspace generator  (Java 17, Fabric Loom, Yarn mappings)
================================================================================================

Writes a complete, buildable Fabric Loom workspace for the "RealityShop" mod:

  * ConsumedBlocks player capability (a persistent Fabric data attachment, long, kept on death) + a neon counter top left
  * The Reality Gate: a 3x3 block multiblock with a THIN frame and a big shader-drawn block-consumer vortex (it assembles when
    placed, collapses into an orb on pick-up, and carries you to the Black Market with a seamless swirl - no loading screen)
  * BlackMarket dimension: a round cathedral (55 blocks across) of many different blocks with stained-glass windows, a lit
    shrine window behind every pedestal and a permanent exit gate; Bedrock Realm dimension (no way back)
  * ShopPedestal block entity (floating 3D item, its power, red/green "Cost: X Blocks" tag, right-click to buy)
  * Six shop items: glitch_pickaxe, glitchy_floppy, cursed_kitty_phone, hacked_rgb_furnace,
    beacon_5g, quantum_usb  (+ DerpChicken, section-bulk destruction engine that consumes the area IN FRONT of you, FX)
  * A runtime glTF/GLB reader + PBR core shader: your 3D models are shipped and rendered UNMODIFIED
    (no mesh edits, no texture downsampling, 8K PBR maps supported; far-away chickens use decimated copies made at load time)
  * /cinematic: a client command that flies a 360 degree orbit camera around every item (player, hand and HUD hidden)

Usage
-----
    python3 build_mod.py [--out RealityShop] [--force] [--texture-size 256] [--models DIR] [--overrides DIR]
    cd RealityShop && ./gradlew build          # JDK 17
    ./gradlew runClient                         # or runServer / runGametest

Drop-in assets (next to this script, or via --models / --overrides)
-------------------------------------------------------------------
    assets/models/<name>.zip | <name>.glb | <name>.gltf | <name>/   3D models; derp_chicken and
                                                                    cursed_kitty_phone are the two the mod uses
    assets/overrides/<path under src/main/resources>                copied LAST and replaces generated files:
        assets/realityshop/textures/item/glitch_pickaxe.png         (any size, e.g. 8K, shipped byte-for-byte)
        assets/realityshop/sounds/kitty_phone_curse.ogg  ...        (used instead of the vanilla fallback sounds)

The script only needs the Python standard library.

Third-party content (CC-BY-4.0; credits are also written into the generated mod):
  "Chicken" by joshuacaca973  https://sketchfab.com/3d-models/chicken-59590091ec7647a4beae2e08acb1903a
  "Retro mobile phone with heart icons on the scre" by Violetforest
      https://sketchfab.com/3d-models/retro-mobile-phone-with-heart-icons-on-the-scre-493bf5df4b764db8849c24f1a60ca672
"""
import argparse
import base64
import hashlib
import json
import math
import os
import random
import re
import struct
import sys
import zipfile
import zlib

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

# ============================================================================ constants
MOD_ID = "realityshop"
MOD_NAME = "RealityShop"
PKG = "com.realityshop"
MC_VERSION = "1.20.1"
YARN_MAPPINGS = "1.20.1+build.10"
LOADER_VERSION = "0.16.14"
FABRIC_API_VERSION = "0.92.12+1.20.1"
LOOM_VERSION = "1.10.5"
GRADLE_VERSION = "8.14.3"
MOD_VERSION = "2.0.0"

CREDITS = (
    'DerpChicken model: "Chicken" by joshuacaca973 (https://sketchfab.com/3d-models/chicken-59590091ec7647a4beae2e08acb1903a), '
    'CC-BY-4.0. Cursed Kitty Phone model: "Retro mobile phone with heart icons on the scre" by Violetforest '
    "(https://sketchfab.com/3d-models/retro-mobile-phone-with-heart-icons-on-the-scre-493bf5df4b764db8849c24f1a60ca672), CC-BY-4.0. "
    "Both models are used unmodified."
)

KNOWN_MODELS = ("derp_chicken", "cursed_kitty_phone")

TOKENS = {
    "PKG": PKG, "MODID": MOD_ID, "MODNAME": MOD_NAME, "MCVER": MC_VERSION, "MODVER": MOD_VERSION,
}


# ============================================================================ output set
def render(text, **extra):
    """Replace @@TOKEN@@ placeholders (never touches Java generics / Groovy ${...})."""
    toks = dict(TOKENS)
    toks.update(extra)

    def sub(m):
        key = m.group(1)
        if key not in toks:
            raise SystemExit("generator bug: unknown template token @@%s@@" % key)
        return str(toks[key])

    return re.sub(r"@@([A-Z][A-Z0-9_]*)@@", sub, text)


class Out:
    """In-memory set of files to write: relative path -> bytes."""

    def __init__(self):
        self.files = {}
        self.executable = set()

    def put(self, path, data):
        if isinstance(data, str):
            data = data.replace("\r\n", "\n").encode("utf-8")
        if path in self.files:
            raise SystemExit("generator bug: duplicate output path %s" % path)
        self.files[path] = data

    def text(self, path, text, **extra):
        self.put(path, render(text, **extra))

    def json(self, path, obj):
        self.put(path, json.dumps(obj, indent=2, ensure_ascii=False) + "\n")

    def java(self, rel, src, client=False):
        root = "src/client/java/" if client else "src/main/java/"
        self.text(root + PKG.replace(".", "/") + "/" + rel, src)

    def asset_json(self, rel, obj):
        self.json("src/main/resources/assets/%s/%s" % (MOD_ID, rel), obj)

    def data_json(self, rel, obj):
        self.json("src/main/resources/data/%s/%s" % (MOD_ID, rel), obj)

    def texture(self, rel, png_bytes):
        self.put("src/main/resources/assets/%s/textures/%s" % (MOD_ID, rel), png_bytes)


# ============================================================================ embedded binary assets
# (Gradle wrapper + credits; filled in by tools/embed_assets.py)
# <<<EMBEDDED_ASSETS_BEGIN>>>
EMBEDDED_ASSETS = {
    'wrapper/gradle-wrapper.jar': ('ed2c26eba7cfb93cc2b7785d05e534f07b5b48b5e7fc941921cd098628abca58', [
        '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',
        '2wsej5/qCucKHrWq+gitLo+cl+Lxz2DQVhBY5/rq+kdce8ulX+lFs80i1Rhoq5wYU0MqnGUV9auCIf4nQdfv4KYeRiymJlfSk9HE',
        '2+t/fKvngw7MnLM6O7vKlWKczGOarDrB+dRwYDB4Ok6fbnOyOGOMMMthaykNTKkchxLeJCd5Gx9GSSus3QqKzoe0Zzhpy/geXLsu',
        'YTJgyn4Rs2zj2DcNJkOMxwRfkRwUgz7RTRbjTCX7bNTAaMm5R/KW9c+eTkzvW8aIcfOsgq3wfVxo1vVKlnNm8XkoUZI0ElqQyfNa',
        'zw7NVAoXzAlglIbJgSu5fyZgX1j7Eyu+almNZhYOHJu3IrwADFt/TwqzBpXtXDcieXrsmdgKYA/VHMaYvWntsdHhMLbp2/rf3Ndb',
        'i4f97Sm8i8y9PRAE4vqbNepRNnwTRe4uXc6ymvqL8xM7jnIfSZjFUTDvqcNkJlpT/L4ozLWKLnojIdmNf/z9OTeoptzV99a4ObhE',
        'r0c1jSMDm70xbW0z6pTboVb2ETdhVPZvjdC4BbvxQPlGdmdZBdjdcfDq8Hkzw2ZewhKCP94+a3MRQQBuWXllWg+dK27YvrTaD8a3',
        'B6Dts62S3T67U/jRF8vSQ/vsya2XXoDdO5uBD0jdkXEbHnLfE0bzT3WK+31FE7jZQMhGexLvUOmY7jvOlrVnw54szIg1n7ZolrKO',
        'E5IWYndcWbpHtegUHYSxTSEY6MrWb2/OH9dXXZu7kzu7bfGBcdY/5iqANBudnnHL85BdhS+yRgRidQegUWC4qpuR+9BDJa4AZFfy',
        'sPgx3OCpM+7ghKCdx8jLE9jcoXIa94y5nd8LXSeKeTx9+ZDGbqR1mMa4y4uOrjtOjGG+vJymHHLEJXOyh8reTL2TR0/PBiDQtSQD',
        'IlH522ezWXu/JVMIshoD0PxEqz5YCfd00jSt5Y8KnTssGlvOWaNWT+TiE2vmbiQXZDfjqL0VBC4rind/b+vJFNHK/fClxZdPFi2/',
        'eiIYemHc8Io9PJK5NRcP4ULvqmaKVpBT1dFfiRJjo59gvuWLIKc956u0HFV189kjTrqR7p3JVkorOjThMNQ5q5+iHVMlqV6P2FiN',
        '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',
        'f/qpAUJF+9bqMvybEX5rLzfz7bf/A2Cj9Ss=',
    ]),
    'wrapper/gradlew': ('fb3cbfe6d066ee52bc07f62ed61ff77bde195384f52496c94280a83008d9f531', [
        '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',
        'eSEwnUYyzXKUnfO/H/IUDw==',
    ]),
    'wrapper/gradlew.bat': ('8e327fcb99d29ce0fe3ee2fec6e6a25de815a2df83a6a44a553dea89ffc92955', [
        'eNrNVltv2zYUfg+Q/3CmQWs7JHIabHvw4CJqrCTOHDuwnEuBAgYt0TY7idRI2o5f+tt3eJFsde3Dhj7MCCLezu073znkhaTl8dEF',
        '/odLUe0kW640nJ+9/RX0ioLAOeOkALLWKyFx7kcqckJedMgyyhXNYc1zKq1oXJEMP37nBB6pVExwOI/O4LU5EPit4M3vXstOrKEk',
        'O+BCw1pRVMMULFhBgb5ktNLAOGSirApGeEZhy/TKmvKKvEvwwasRc01QgqBMhbPF4VkguhWA/a20rlS309lutxGx7kdCLjuFE1Gd',
        '4eAyGaXJKYbQEn7gBVUKJP1rzSSCMN8BqdDLjMzR94JsLW5LSXFPCxPFVjLN+PIElFjoLZHUa8qZ0pLN17oFZe0zonF4AMEkHII4',
        'hUEawPs4HaQnXs/TYHozfpjCUzyZxKPpIElhPIHL8ag/mA7GI5xdQTz6AH8MRv0ToAgkmqIvlTRxoLPMgEzzGtGU0pYjC+EcUxXN',
        '2IJlGCNfrsmSwlJsqOQYGlRUlkyZlCt0M/eaClYyTbRd/UeADafwyxYQhP3k/cN1GPR6QQAXNFsJzOLCa/rxu/3aRLiWJMesKU2k',
        'XlegMsmQeSbiJ8ZzsVWt49/TiwZsDYXIsOgUEncP9oZIZgilHPG3zhsYTUGtaFEcH1nIxqnFy/s6G00DUFRbfcYCjqE/mIziu6QX',
        'fs6rMy/m1zzWh6dqDkxNMeIfF7IkRbHD9GGN5k5lfH8/QwImM6+Yn+3Xb8ZmqTbQhDmhShQbLES+gyAKDEfwiwMsj1rMVEtJ/kTm',
        'awxSSE0lumMACUNmDr4OwvpsGLyBXEDbavh5wRqLcZ5DThdkXWi4fbwDUTkeIvdpZLtGhvVECiVs97mNH+PZ+H6aWteuJ3F/mLg5',
        'elURLJRDJbhm25XjS+SRTq7ih+F0hgetZC84fS5ffvulDMCMlBk17l1hyuAT2ZCIvlCbFnSWcSx064nFYynQDi7mt3juSorSfG9E',
        'Sevc2qPJc9LbKwrrtRBON74Jvxs9DOH83U9vrZ0wmUzGk2HymAxDwCYGZ84QimfYaYxuU3yR+4A93T3wyrJCW+gNVFzAK2P+lWnW',
        'pVnJxLrAtmi4jEVvEoetXsJ9PL2J2rrvC0oQe6PKcH5vo2Z/I0z5hknBS8q1o4nO7G3gFRnGm8yYvm/PG6RQGOu6KOxOZOJygBJm',
        'i6P7dWQbXB2nmmE36IVfoL7fDDtzxjv7LFig6Qu2b6y2JiPBvwbaIiNM42d8QwqGlMY7J9NC7rpwYP7/BGsTXt3fsK8aN2p6FMjy',
        'msCXwzhNDTF6+8r+uLQ9+eNW4rVKpZ+e+mn0iUgj7dUnzpjv48dHLbTDLysy9Jj58UGVh1iifbz+I2cNHwQVJyV1bjW9DnWeZgU2',
        'g4pgTw7Cxn3cOBCuXXVOPbnZnXmdhD87iJobMjGA/Mf2/61CLtFSYiwYjtus1BdNk38fefI8mM7wnZCOh0g3m2Pg9t2CLsxcc5vh',
        'S0evpXmL5dTmnhJ8jSycVnswK3NDe+hkrcM/uBx7I32slwOHfQT1Xh1AW8A1LNNtAnNVhV9xG5HHOtOHqpC8ZqUzby8iGg0y37o8',
        'MTHN5dnFdrAkx0d/A9BYbiQ=',
    ]),
    'CREDITS.txt': ('9ad3f5550bd62b96de7936198444930f13f117c023a113dc867872cbba7495f2', [
        'eNqtk0+PmzAQxe/5FKOcEikGkpA/bG+bVS+9VNuoUo+DPcRuwEa26ZZv3zHZ7raHbVWp4gCSH7/35g2ctfFK9OjjCNsH6JyiNsAQ',
        'SEE9wiNha+L4SbseFrWLGlojyabT00ncfxFlVqxAx9jf5bn0hNF8I+m6ztmQOX/Jn+Uhr8ectflyNltn8EC+P2kjr2SBbGSHm/EM',
        'AM7aBHhy/gp8rzFZOQvzZ/kcFsktsF24UpS6wTpjw3yrxC17Lm9Ksat2VVFUa5KHfXnAsiakDRVHlPW6Kra4TG4841cX9ICSr+qw',
        'fQv/m2j52sJgFfnXLhJy8W91bDI4DT6xPpjIRXzUzhKYSN1fO3mk6B2ratMS9NN7T4Z3pInXCUaya9JFTRA4zfwl3Z/q8wkqblAx',
        'QUWCigkqJqhwVjBUJKgoq23d7FRT1lyzqo/HspKbslnjvpC4P2x+1vzZuJZi4zyF+FaKXzX/teSTRnuhAB0qugPLQ2Vwnz7nS3t+',
        'Dz3KK6bjRUdBryDS9zhwhhXrI3mDbVgCem5Rm76f/oxIgmOK9AAYIAx93xo+QauSctY6dpp+IQQ/8Cfe0c2L8/Iw79JSxonpbDvy',
        'erAllfcumGg43fRm2punNDt5zoYQPdrAtt2KR4hMTnGBlInLbPYDkDxHVw==',
    ]),
}
# <<<EMBEDDED_ASSETS_END>>>


def asset(key):
    ent = EMBEDDED_ASSETS.get(key)
    if ent is None:
        raise SystemExit("missing embedded asset %r - run tools/embed_assets.py" % key)
    sha, chunks = ent
    data = zlib.decompress(base64.b64decode("".join(chunks)))
    if hashlib.sha256(data).hexdigest() != sha:
        raise SystemExit("embedded asset %r is corrupt" % key)
    return data


# ============================================================================ PNG encoder + tiny canvas (stdlib only)
def png_bytes(width, height, rgba):
    """Encode RGBA8 pixels (bytes/bytearray, row-major) as PNG."""
    stride = width * 4
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        raw += rgba[y * stride:(y + 1) * stride]

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


class Canvas:
    """RGBA canvas with a few anti-aliased primitives. Colours are (r, g, b[, a]) 0-255 tuples."""

    def __init__(self, w, h, fill=(0, 0, 0, 0)):
        self.w, self.h = w, h
        self.px = bytearray(bytes(fill if len(fill) == 4 else tuple(fill) + (255,)) * (w * h))

    def blend(self, x, y, c, a=1.0):
        if 0 <= x < self.w and 0 <= y < self.h:
            i = (y * self.w + x) * 4
            px = self.px
            sa = (c[3] / 255.0 if len(c) > 3 else 1.0) * a
            if sa <= 0:
                return
            da = px[i + 3] / 255.0
            oa = sa + da * (1 - sa)
            if oa <= 0:
                return
            for k in range(3):
                px[i + k] = int((c[k] * sa + px[i + k] * da * (1 - sa)) / oa + 0.5)
            px[i + 3] = int(oa * 255 + 0.5)

    def rect(self, x0, y0, x1, y1, c):
        x0, y0, x1, y1 = max(0, int(x0)), max(0, int(y0)), min(self.w, int(x1)), min(self.h, int(y1))
        for y in range(y0, y1):
            for x in range(x0, x1):
                self.blend(x, y, c)

    def disc(self, cx, cy, r, c, soft=1.0):
        for y in range(int(cy - r - 2), int(cy + r + 3)):
            for x in range(int(cx - r - 2), int(cx + r + 3)):
                d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
                a = max(0.0, min(1.0, (r - d) / soft + 0.5))
                if a > 0:
                    self.blend(x, y, c, a)

    def line(self, x0, y0, x1, y1, width, c):
        """Anti-aliased thick line (distance-to-segment coverage)."""
        hw = width / 2.0
        minx, maxx = int(min(x0, x1) - hw - 2), int(max(x0, x1) + hw + 3)
        miny, maxy = int(min(y0, y1) - hw - 2), int(max(y0, y1) + hw + 3)
        dx, dy = x1 - x0, y1 - y0
        ll = dx * dx + dy * dy or 1e-9
        for y in range(miny, maxy):
            for x in range(minx, maxx):
                t = max(0.0, min(1.0, ((x + 0.5 - x0) * dx + (y + 0.5 - y0) * dy) / ll))
                d = math.hypot(x + 0.5 - (x0 + t * dx), y + 0.5 - (y0 + t * dy))
                a = max(0.0, min(1.0, hw - d + 0.5))
                if a > 0:
                    self.blend(x, y, c, a)

    def vgradient(self, y0, y1, ca, cb):
        for y in range(max(0, y0), min(self.h, y1)):
            t = (y - y0) / max(1, (y1 - y0 - 1))
            col = tuple(int(ca[k] + (cb[k] - ca[k]) * t) for k in range(3)) + (255,)
            row = bytes(col) * self.w
            self.px[y * self.w * 4:(y + 1) * self.w * 4] = row

    def to_png(self):
        return png_bytes(self.w, self.h, self.px)


# ============================================================================ procedural textures (stdlib only)
_CYAN = (70, 235, 255)
_MAGENTA = (255, 60, 205)
_TAU = 6.283185307179586


def _clamp01(x):
    return 0.0 if x < 0.0 else (1.0 if x > 1.0 else x)


def _smooth(a, b, x):
    t = _clamp01((x - a) / (b - a))
    return t * t * (3.0 - 2.0 * t)


def _mix(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(len(a)))


def _over(d, s):
    """Straight-alpha 'over' compositing of two (r, g, b, a) tuples (a in 0..1)."""
    sa, da = s[3], d[3]
    oa = sa + da * (1.0 - sa)
    if oa <= 0.0:
        return (0.0, 0.0, 0.0, 0.0)
    k = da * (1.0 - sa)
    return ((s[0] * sa + d[0] * k) / oa, (s[1] * sa + d[1] * k) / oa, (s[2] * sa + d[2] * k) / oa, oa)


def _hash01(ix, iy, seed):
    h = (ix * 374761393 + iy * 668265263 + seed * 2246822519) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0xFFFF) / 65535.0


def _noise(x, y, seed=0, period=0):
    """Smooth value noise in 0..1; seamless when `period` lattice cells are given."""
    ix, iy = math.floor(x), math.floor(y)
    fx, fy = x - ix, y - iy
    fx = fx * fx * (3.0 - 2.0 * fx)
    fy = fy * fy * (3.0 - 2.0 * fy)
    x1, y1 = ix + 1, iy + 1
    if period:
        ix, x1, iy, y1 = ix % period, x1 % period, iy % period, y1 % period
    a = _hash01(ix, iy, seed)
    b = _hash01(x1, iy, seed)
    c = _hash01(ix, y1, seed)
    d = _hash01(x1, y1, seed)
    top = a + (b - a) * fx
    return top + ((c + (d - c) * fx) - top) * fy


def _byte(v):
    return 255 if v >= 1.0 else (0 if v <= 0.0 else int(v * 255.0 + 0.5))


def paint_strip(size, frames, fn):
    """PNG of `frames` square frames stacked vertically; fn(u, v, t) -> (r, g, b, a) in 0..1, t = frame / frames."""
    px = bytearray(size * size * frames * 4)
    inv = 1.0 / size
    i = 0
    for f in range(frames):
        t = f / float(frames)
        for y in range(size):
            v = (y + 0.5) * inv
            for x in range(size):
                r, g, b, a = fn((x + 0.5) * inv, v, t)
                px[i] = _byte(r)
                px[i + 1] = _byte(g)
                px[i + 2] = _byte(b)
                px[i + 3] = _byte(a)
                i += 4
    return png_bytes(size, size * frames, px)


def _rgb(c, a=1.0):
    return (c[0] / 255.0, c[1] / 255.0, c[2] / 255.0, a)


def _bez(p0, p1, p2, p3, n=24):
    pts = []
    for i in range(n + 1):
        t = i / float(n)
        s = 1.0 - t
        pts.append((s * s * s * p0[0] + 3 * s * s * t * p1[0] + 3 * s * t * t * p2[0] + t * t * t * p3[0],
                    s * s * s * p0[1] + 3 * s * s * t * p1[1] + 3 * s * t * t * p2[1] + t * t * t * p3[1]))
    return pts


def _arc(cx, cy, rx, ry, a0, a1, n=None):
    """Points on an ellipse from angle a0 to a1 (degrees, y axis pointing down: +90 is the bottom)."""
    n = n or max(8, int(abs(a1 - a0) / 8.0))
    return [(cx + rx * math.cos(math.radians(a0 + (a1 - a0) * i / n)),
             cy + ry * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]


def _poly(cv, pts, color):
    """Anti-aliased convex polygon."""
    area = sum(pts[i][0] * pts[(i + 1) % len(pts)][1] - pts[(i + 1) % len(pts)][0] * pts[i][1] for i in range(len(pts)))
    sign = 1.0 if area > 0 else -1.0
    edges = []
    for i in range(len(pts)):
        (x0, y0), (x1, y1) = pts[i], pts[(i + 1) % len(pts)]
        ln = math.hypot(x1 - x0, y1 - y0) or 1.0
        edges.append((x0, y0, (x1 - x0) / ln, (y1 - y0) / ln))
    minx, maxx = int(min(p[0] for p in pts)) - 1, int(max(p[0] for p in pts)) + 2
    miny, maxy = int(min(p[1] for p in pts)) - 1, int(max(p[1] for p in pts)) + 2
    for y in range(miny, maxy):
        for x in range(minx, maxx):
            d = min(sign * (nx * (y + 0.5 - ey) - ny * (x + 0.5 - ex)) for ex, ey, nx, ny in edges)
            a = max(0.0, min(1.0, d + 0.5))
            if a > 0.0:
                cv.blend(x, y, color, a)


# ---------------------------------------------------------------------------- block textures
def _gate_rune(u, v, t):
    """Glowing circuit glyph for the gate's strips and emblem: pulses run upwards, colours drift cyan <-> magenta."""
    ph = _TAU * t
    n = 0.5 * _noise(u * 8.0, v * 8.0, 41, 8) + 0.5 * _noise(u * 16.0, v * 16.0, 42, 16)
    base = (14 + 16 * n, 10 + 10 * n, 24 + 28 * n)
    pulse = 0.5 + 0.5 * math.sin(ph - v * 6.0)
    spine = math.exp(-((u - 0.5) / 0.07) ** 2)
    ticks = math.exp(-((((v * 6.0) % 1.0) - 0.5) / 0.07) ** 2) * (1.0 if abs(u - 0.5) < 0.30 else 0.0)
    diamond = math.exp(-((abs(u - 0.5) + abs(v - 0.5) - 0.34) / 0.03) ** 2)
    frame = math.exp(-((min(u, v, 1.0 - u, 1.0 - v) - 0.04) / 0.02) ** 2)
    glow = _clamp01((spine * 0.9 + ticks * 0.7 + diamond * 0.8 + frame * 0.6) * (0.55 + 0.6 * pulse))
    hot = _mix(_CYAN, _MAGENTA, 0.5 + 0.5 * math.sin(ph + u * 3.0))
    col = _mix(base, hot, glow)
    return _rgb(_mix(col, (255, 255, 255), glow * glow * 0.35), 1.0)


_SWIRL_TONES = ((0.20, (247, 242, 255)), (0.36, (71, 219, 255)), (0.54, (224, 61, 214)), (0.76, (143, 43, 230)),
                (0.88, (56, 15, 117)), (9.0, (10, 3, 28)))


def _swirl_rgb(px, py, spin=0.0):
    """The vortex's flat pixel-art tones at (px, py) in -1..1 - the same maths as the portal_vortex shader."""
    r = math.hypot(px, py)
    if r < 0.16:
        return _SWIRL_TONES[-1][1]
    if r < 0.225:
        return _SWIRL_TONES[0][1]
    s = math.atan2(py, px) / _TAU * 2.0 + math.log(r + 0.08) * 0.95 - spin
    f = s - math.floor(s)
    for limit, col in _SWIRL_TONES:
        if f < limit:
            return col
    return _SWIRL_TONES[-1][1]


def _gate_line(u, v, t):
    """The glowing 1 px inlay along the inside of the gate frame: columns 7 and 8 are hot, pulses run along the strip."""
    ph = _TAU * t
    core = math.exp(-((u - 0.5) / 0.09) ** 2)
    pulse = 0.55 + 0.45 * math.sin(ph - v * _TAU * 2.0)
    glow = _clamp01(core * (0.7 + 0.5 * pulse))
    hot = _mix(_CYAN, _MAGENTA, 0.5 + 0.5 * math.sin(ph + v * _TAU))
    col = _mix((12, 8, 24), hot, glow)
    return _rgb(_mix(col, (255, 255, 255), glow * glow * 0.4), 1.0)


def _gate_glow(u, v, t):
    """A pulsing glow dot (the eyes in the frame's corner stones, the nodes on its sides, the crest plate)."""
    d = math.hypot(u - 0.5, v - 0.5) / 0.5
    pulse = 0.7 + 0.3 * math.sin(_TAU * t)
    core = max(0.0, 1.0 - d) ** 1.4
    hot = _mix(_CYAN, _MAGENTA, 0.5 + 0.5 * math.sin(_TAU * t + d * 2.0))
    col = _mix((14, 8, 28), hot, _clamp01(core * 1.2 * pulse))
    return _rgb(_mix(col, (255, 255, 255), max(0.0, core - 0.55) * 1.6 * pulse), 1.0)


def _tri(u, v, bx, by, w, h, lean=0.0):
    k = (by - v) / h
    if not 0.0 <= k <= 1.0:
        return -1.0
    return k if abs(u - (bx + lean * k * h)) <= 0.5 * w * (1.0 - k) else -1.0


def _gate_icon(u, v, t):
    """Item icon: the thin-framed gate, amethyst crystals on its corners and the block-consuming swirl in the opening."""
    x0, x1, y0, y1 = 0.09, 0.91, 0.22, 0.95
    b = 0.07
    ix0, ix1, iy0, iy1 = x0 + b, x1 - b, y0 + b, y1 - b
    for bx, w, h, lean in ((0.15, 0.09, 0.19, -0.22), (0.23, 0.07, 0.12, 0.0), (0.85, 0.09, 0.19, 0.22), (0.77, 0.07, 0.12, 0.0)):
        k = _tri(u, v, bx, y0 + 0.02, w, h, lean)
        if k >= 0.0:
            return _rgb(_mix((150, 90, 225), (225, 185, 255), k * 0.9 + (0.25 if u < bx else 0.0)), 1.0)
    if 0.42 <= u <= 0.58 and 0.10 <= v <= y0 + 0.01:                       # the crest
        c = (212, 168, 60) if v > 0.15 else (54, 50, 68)
        return _rgb(c, 1.0)
    if not (x0 <= u <= x1 and y0 <= v <= y1):
        return (0.0, 0.0, 0.0, 0.0)
    if ix0 <= u <= ix1 and iy0 <= v <= iy1:
        cells = 20
        gx = min(cells - 1, int((u - ix0) / (ix1 - ix0) * cells))
        gy = min(cells - 1, int((v - iy0) / (iy1 - iy0) * cells))
        if gx in (0, cells - 1) or gy in (0, cells - 1):
            return _rgb(_SWIRL_TONES[-1][1], 1.0)
        px = ((gx + 0.5) / cells - 0.5) * 2.0
        py = (0.5 - (gy + 0.5) / cells) * 2.0
        return _rgb(_swirl_rgb(px, py), 1.0)
    corner = (u < x0 + 0.13 or u > x1 - 0.13) and (v < y0 + 0.13 or v > y1 - 0.13)
    if corner:
        n = _noise(u * 24.0, v * 24.0, 61, 0)
        return _rgb(_mix((50, 18, 98), (150, 70, 230), 0.15 + 0.5 * (n > 0.72)), 1.0)
    if min(abs(u - ix0), abs(u - ix1), abs(v - iy0), abs(v - iy1)) < 0.012:
        return _rgb(_CYAN, 1.0)
    brick = (_noise(u * 12.0, v * 12.0, 51, 0) - 0.5) * 20.0
    mortar = abs(math.sin(v * math.pi * 12.0)) < 0.12
    c = (40 + brick, 38 + brick, 54 + brick) if not mortar else (22, 20, 32)
    return _rgb(c, 1.0)


def _pedestal_top(u, v, t):
    cx, cy = u - 0.5, v - 0.5
    r = math.hypot(cx, cy)
    a = math.atan2(cy, cx)
    n = 0.6 * _noise(u * 8.0, v * 8.0, 3, 8) + 0.4 * _noise(u * 16.0, v * 16.0, 4, 16)
    col = (16 + 26 * n, 10 + 14 * n, 28 + 40 * n)
    ring = math.exp(-((r - 0.36) / 0.014) ** 2) + 0.7 * math.exp(-((r - 0.26) / 0.010) ** 2)
    d = 9.0
    for k in range(6):                      # six rays from the centre = a hexagram-ish rune
        ang = a - k * math.pi / 3.0
        if math.cos(ang) > 0.0:
            d = min(d, abs(r * math.sin(ang)))
    star = math.exp(-(d / 0.012) ** 2) * (1.0 - _smooth(0.30, 0.36, r))
    glow = math.exp(-(r / 0.30) ** 2) * 0.20
    edge = min(u, v, 1.0 - u, 1.0 - v)
    frame = math.exp(-((edge - 0.035) / 0.012) ** 2) * 0.75
    col = _mix(col, _MAGENTA, _clamp01(ring * 0.9 + glow + frame))
    col = _mix(col, _CYAN, _clamp01(star * 0.85))
    return _rgb(col, 1.0)


def _furnace_glow(u, v, t):
    """White/grey translucent shell: the block colour provider tints it green (0x33FF66)."""
    gx = abs(math.sin(u * math.pi * 8.0))
    gy = abs(math.sin(v * math.pi * 8.0))
    line = 1.0 - _smooth(0.0, 0.16, min(gx, gy))
    dv = ((v - t + 0.5) % 1.0) - 0.5
    scan = math.exp(-(dv * dv) / 0.004)
    sp = _noise(u * 24.0, v * 24.0 + t * 24.0, 5, 24)
    spark = max(0.0, (sp - 0.80) / 0.20)
    return (1.0, 1.0, 1.0, min(0.9, 0.10 + 0.32 * line + 0.5 * scan + 0.55 * spark))


def _beacon_glow(u, v, t):
    """White translucent shell: tinted red (0xFF2A2A) by the block colour provider; ripples pulse outwards."""
    r = math.hypot(u - 0.5, v - 0.5)
    rip = 0.5 + 0.5 * math.sin(_TAU * (r * 4.0 - t))
    rip = rip * rip * rip
    sp = _noise(u * 20.0, v * 20.0 - t * 20.0, 9, 20)
    spark = max(0.0, (sp - 0.78) / 0.22)
    edge = min(u, v, 1.0 - u, 1.0 - v)
    frame = 1.0 - _smooth(0.0, 0.05, edge)
    return (1.0, 1.0, 1.0, min(0.92, 0.10 + 0.45 * rip + 0.5 * spark + 0.4 * frame))


def _laser(u, v, t):
    """Particle texture of ONE beam: u runs across the beam, v along it (soft ends)."""
    d = abs(u - 0.5) * 2.0
    core = math.exp(-(d / 0.14) ** 2)
    glow = math.exp(-(d / 0.55) ** 2) * 0.55
    ends = _smooth(0.0, 0.12, v) * _smooth(0.0, 0.12, 1.0 - v)
    col = _mix((90, 255, 140), (235, 255, 240), core)
    return _rgb(col, min(1.0, core + glow) * (0.35 + 0.65 * ends))


# ---------------------------------------------------------------------------- Quantum USB textures
def _usb_plug(u, v, t):
    base = 168.0 + 28.0 * _hash01(int(v * 64.0), 0, 21) + 14.0 * _noise(u * 3.0, v * 90.0, 22)
    col = (base, base + 3.0, base + 10.0)
    if 0.16 < v < 0.42 and (0.12 < u < 0.38 or 0.62 < u < 0.88):
        col = (214, 172, 58)                                  # gold contacts
    edge = min(u, v, 1.0 - u, 1.0 - v)
    return _rgb(_mix(col, (40, 42, 52), 1.0 - _smooth(0.0, 0.05, edge)), 1.0)


def _usb_led(u, v, t):
    ph = _TAU * t
    k = 0.65 + 0.35 * math.sin(ph + v * 3.0)
    m = 0.5 + 0.5 * math.sin(ph + u * 2.0)
    col = _mix((0, 255, 235), (255, 40, 210), m)
    spot = math.exp(-(((u - 0.5) ** 2 + (v - 0.5) ** 2) / 0.12))
    col = _mix(col, (255, 255, 255), spot * 0.55)
    return (col[0] * k / 255.0, col[1] * k / 255.0, col[2] * k / 255.0, 1.0)


def _usb_body(size):
    rnd = random.Random(4242)
    cv = Canvas(size, size, (14, 12, 36, 255))
    cv.vgradient(0, size, (32, 28, 74), (12, 10, 34))
    s = size / 64.0
    for _ in range(26):                                        # circuit traces: Manhattan polylines with via dots
        x, y = rnd.randrange(4, 60) * s, rnd.randrange(4, 60) * s
        col = _CYAN + (150,) if rnd.random() < 0.7 else _MAGENTA + (150,)
        for _seg in range(rnd.randint(2, 4)):
            if rnd.random() < 0.5:
                nx, ny = max(2 * s, min(62 * s, x + rnd.randint(-18, 18) * s)), y
            else:
                nx, ny = x, max(2 * s, min(62 * s, y + rnd.randint(-18, 18) * s))
            cv.line(x, y, nx, ny, max(1.0, 0.9 * s), col)
            x, y = nx, ny
        cv.disc(x, y, 1.6 * s, col)
    cv.line(1.5 * s, 1.5 * s, 62.5 * s, 1.5 * s, 1.4 * s, _MAGENTA + (255,))
    cv.line(1.5 * s, 62.5 * s, 62.5 * s, 62.5 * s, 1.4 * s, _CYAN + (255,))
    return cv.to_png()


def _usb_label(size):
    cv = Canvas(size, size, (12, 8, 30, 255))
    cv.vgradient(0, size, (28, 14, 60), (8, 6, 24))
    c = size / 2.0
    for ang, col in ((0.0, _CYAN), (60.0, _MAGENTA), (120.0, (240, 240, 255))):
        ca, sa = math.cos(math.radians(ang)), math.sin(math.radians(ang))
        pts = []
        for i in range(61):
            th = _TAU * i / 60.0
            ex, ey = 0.40 * size * math.cos(th), 0.15 * size * math.sin(th)
            pts.append((c + ex * ca - ey * sa, c + ex * sa + ey * ca))
        for a, b in zip(pts, pts[1:]):
            cv.line(a[0], a[1], b[0], b[1], max(1.0, size * 0.022), col + (255,))
    cv.disc(c, c, size * 0.075, (255, 255, 255, 255))
    cv.disc(c, c, size * 0.13, (200, 160, 255, 70), soft=size * 0.05)
    return cv.to_png()


# ---------------------------------------------------------------------------- glitch sprites (animated item textures)
def _glitch_rgba(src, w, h, rnd, bands, split, reach=0.25):
    """Horizontal slice displacement + chromatic split of an RGBA byte string (a copy is returned)."""
    px = bytearray(src)
    rowb = w * 4
    for _ in range(bands):
        y0 = rnd.randrange(h)
        hh = rnd.randint(max(1, h // 32), max(2, h // 7))
        shift = rnd.randint(-int(w * reach), int(w * reach))
        for y in range(y0, min(h, y0 + hh)):
            row = px[y * rowb:(y + 1) * rowb]
            s = (shift * 4) % rowb
            if s:
                px[y * rowb:(y + 1) * rowb] = row[-s:] + row[:-s]
    if split:
        out = bytearray(len(px))
        for y in range(h):
            row = bytes(px[y * rowb:(y + 1) * rowb])
            pad = bytes(4 * split)
            left = (row[4 * split:] + pad)           # content moved left by `split` pixels
            right = (pad + row[:-4 * split])         # content moved right by `split` pixels
            r = right[0::4]
            g = row[1::4]
            b = left[2::4]
            a = bytes(map(max, left[3::4], row[3::4], right[3::4]))
            o = bytearray(rowb)
            o[0::4], o[1::4], o[2::4], o[3::4] = r, g, b, a
            out[y * rowb:(y + 1) * rowb] = o
        px = out
    return bytes(px)


def _sprite_pickaxe(size):
    s = size / 16.0
    cv = Canvas(size, size)
    cv.line(2.2 * s, 13.8 * s, 10.8 * s, 5.2 * s, 2.5 * s, (45, 26, 14, 255))
    cv.line(2.2 * s, 13.8 * s, 10.8 * s, 5.2 * s, 1.6 * s, (156, 104, 58, 255))
    cv.line(2.0 * s, 13.6 * s, 10.5 * s, 5.0 * s, 0.5 * s, (205, 150, 92, 255))
    pts = _bez((3.0 * s, 4.6 * s), (12.6 * s, 1.4 * s), (13.6 * s, 4.0 * s), (12.4 * s, 12.6 * s), 30)
    for a, b in zip(pts, pts[1:]):
        cv.line(a[0], a[1], b[0], b[1], 3.0 * s, (12, 10, 32, 255))
    for i, (a, b) in enumerate(zip(pts, pts[1:])):
        cv.line(a[0], a[1], b[0], b[1], 2.0 * s, tuple(_mix(_CYAN, _MAGENTA, i / float(len(pts) - 2))) + (255,))
    for a, b in zip(pts, pts[1:]):
        cv.line(a[0], a[1], b[0], b[1], 0.7 * s, (245, 255, 255, 255))
    return cv


def _sprite_floppy(size):
    s = size / 16.0
    cv = Canvas(size, size)
    cv.rect(1.4 * s, 1.0 * s, 14.6 * s, 15.0 * s, (10, 12, 40, 255))
    cv.rect(1.9 * s, 1.5 * s, 14.1 * s, 14.5 * s, (36, 44, 124, 255))
    cv.rect(1.9 * s, 1.5 * s, 14.1 * s, 2.0 * s, (96, 118, 255, 255))
    for y in range(size):                                      # the cut corner (top right)
        for x in range(size):
            if x / s - y / s > 10.3:
                i = (y * size + x) * 4
                cv.px[i:i + 4] = b"\x00\x00\x00\x00"
    cv.rect(4.2 * s, 1.5 * s, 11.2 * s, 5.8 * s, (205, 210, 222, 255))
    cv.rect(8.4 * s, 2.3 * s, 10.4 * s, 5.0 * s, (58, 62, 82, 255))
    cv.rect(3.0 * s, 8.2 * s, 13.0 * s, 14.5 * s, (236, 236, 246, 255))
    for k, col in enumerate(((255, 60, 210), (60, 220, 255), (120, 120, 150))):
        y = (9.7 + k * 1.6) * s
        cv.line(3.8 * s, y, (12.2 - k * 1.5) * s, y, 0.55 * s, col + (255,))
    return cv


def _glitch_strip(cv, size, frames, seed):
    """Frame 0 is the clean sprite; the others are glitched variants (slices, RGB split, debris)."""
    rnd = random.Random(seed)
    clean = bytes(cv.px)
    out = bytearray(clean)
    s = size / 16.0
    for f in range(1, frames):
        base = Canvas(size, size)
        base.px[:] = clean
        for _ in range(rnd.randint(3, 7)):                      # debris blocks
            x, y = rnd.uniform(0, 14) * s, rnd.uniform(0, 14) * s
            base.rect(x, y, x + rnd.uniform(0.6, 2.2) * s, y + rnd.uniform(0.3, 1.0) * s,
                      rnd.choice((_CYAN, _MAGENTA, (255, 255, 255))) + (230,))
        out += _glitch_rgba(bytes(base.px), size, size, rnd, rnd.randint(2, 4), max(1, int(round(s * rnd.uniform(0.4, 0.9)))))
    return png_bytes(size, size * frames, out)


# ---------------------------------------------------------------------------- mod icon
def _icon(size):
    cv = Canvas(size, size, (10, 6, 28, 255))
    cv.vgradient(0, size, (46, 12, 82), (6, 4, 20))
    s = size / 256.0
    top, ur, lr, bot, ll, ul, mid = ((128, 40), (198, 80), (198, 160), (128, 200), (58, 160), (58, 80), (128, 120))

    def p(pt):
        return (pt[0] * s, pt[1] * s)

    _poly(cv, [p(top), p(ur), p(mid), p(ul)], (156, 64, 236, 255))
    _poly(cv, [p(ul), p(mid), p(bot), p(ll)], (74, 22, 138, 255))
    _poly(cv, [p(mid), p(ur), p(lr), p(bot)], (40, 12, 92, 255))
    for x, y0, y1, a in ((88, 104, 150, 120), (110, 120, 176, 90), (150, 112, 172, 100), (174, 98, 142, 120)):   # crying streaks
        cv.line(x * s, y0 * s, x * s, y1 * s, 3 * s, (190, 150, 255, a))
    for a, b in ((top, ur), (ur, lr), (lr, bot), (bot, ll), (ll, ul), (ul, top), (ul, mid), (mid, ur), (mid, bot)):
        cv.line(a[0] * s, a[1] * s, b[0] * s, b[1] * s, 3 * s, _CYAN + (255,))
    rnd = random.Random(7)
    raw = bytes(cv.px)
    out = _glitch_rgba(raw, size, size, rnd, 3, max(1, int(2 * s)), reach=0.07)
    return png_bytes(size, size, bytearray(out))


# ---------------------------------------------------------------------------- HUD font + plate
_HUD_CHARS = "BCcdeklmnosu:,+-0123456789"
_HUD_PAD = 0.10
_HUD_STROKE = 0.085


def _hud_glyphs():
    """Stroke font: char -> (width, [polylines], [(x, y, r) dots]); em units, y down, cell height = 1."""
    T, X, B = 0.14, 0.38, 0.80
    g = {}

    def add(ch, w, paths=(), dots=()):
        g[ch] = (w, [list(p) for p in paths], list(dots))

    mid = T + (B - T) * 0.47
    r1, r2 = (mid - T) / 2.0, (B - mid) / 2.0
    xa, xb = 0.56 - r1 - 0.03, 0.56 - r2
    add("B", 0.56, [[(0.06, T), (0.06, B)],
                    [(0.06, T), (xa, T)] + _arc(xa, T + r1, r1, r1, -90, 90) + [(0.06, mid)],
                    [(0.06, mid), (xb, mid)] + _arc(xb, mid + r2, r2, r2, -90, 90) + [(0.06, B)]])
    add("C", 0.56, [_arc(0.28, (T + B) / 2, 0.28, 0.33, -42, -318)])
    add("c", 0.46, [_arc(0.23, (X + B) / 2, 0.23, 0.21, -42, -318)])
    add("o", 0.50, [_arc(0.25, (X + B) / 2, 0.25, 0.21, 0, 360, 40)])
    add("e", 0.50, [[(0.0, (X + B) / 2), (0.5, (X + B) / 2)] + _arc(0.25, (X + B) / 2, 0.25, 0.21, 0, -315)])
    add("s", 0.44, [_arc(0.22, X + 0.105, 0.20, 0.105, -25, -270) + _arc(0.22, B - 0.105, 0.22, 0.105, -90, 155)])
    add("d", 0.50, [_arc(0.23, (X + B) / 2, 0.23, 0.21, 0, 360, 40), [(0.46, T), (0.46, B)]])
    add("k", 0.48, [[(0.06, T), (0.06, B)], [(0.44, X), (0.06, 0.64)], [(0.20, 0.54), (0.46, B)]])
    add("l", 0.12, [[(0.06, T), (0.06, B)]])
    add("m", 0.78, [[(0.06, X), (0.06, B)],
                    [(0.06, 0.545)] + _arc(0.225, 0.545, 0.165, 0.165, -180, 0) + [(0.39, B)],
                    [(0.39, 0.545)] + _arc(0.555, 0.545, 0.165, 0.165, -180, 0) + [(0.72, B)]])
    add("n", 0.52, [[(0.06, X), (0.06, B)], [(0.06, X + 0.2)] + _arc(0.26, X + 0.2, 0.2, 0.2, -180, 0) + [(0.46, B)]])
    add("u", 0.52, [[(0.06, X), (0.06, B - 0.2)] + _arc(0.26, B - 0.2, 0.2, 0.2, 180, 0) + [(0.46, X)], [(0.46, X), (0.46, B)]])
    add("+", 0.40, [[(0.04, 0.47), (0.36, 0.47)], [(0.20, 0.31), (0.20, 0.63)]])
    add("-", 0.34, [[(0.04, 0.47), (0.30, 0.47)]])
    add(":", 0.14, [], [(0.07, 0.46, 0.05), (0.07, 0.75, 0.05)])
    add(",", 0.16, [[(0.08, B), (0.03, 0.90)]], [(0.08, 0.77, 0.055)])
    add("0", 0.52, [_arc(0.26, (T + B) / 2, 0.26, 0.33, 0, 360, 44)])
    add("1", 0.52, [[(0.08, T + 0.12), (0.30, T), (0.30, B)]])
    add("2", 0.52, [_arc(0.26, T + 0.2, 0.25, 0.2, -170, 40) + [(0.04, B), (0.52, B)]])
    add("3", 0.52, [_arc(0.25, T + 0.15, 0.22, 0.15, -160, 90) + _arc(0.25, T + 0.30 + 0.18, 0.26, 0.18, -90, 155)])
    add("4", 0.52, [[(0.38, B), (0.38, T), (0.02, 0.60), (0.52, 0.60)]])
    bowl = _arc(0.26, 0.60, 0.26, 0.20, -140, 145)
    add("5", 0.52, [[(0.48, T), (0.12, T), bowl[0]] + bowl])
    six = [_bez((0.0, 0.60), (0.0, 0.30), (0.12, T), (0.46, T + 0.02)), _arc(0.26, 0.60, 0.26, 0.20, 0, 360, 40)]
    add("6", 0.52, six)
    add("7", 0.52, [[(0.02, T), (0.50, T), (0.18, B)]])
    add("8", 0.52, [_arc(0.26, T + 0.155, 0.21, 0.155, 0, 360, 36), _arc(0.26, B - 0.18, 0.26, 0.18, 0, 360, 36)])
    add("9", 0.52, [[(0.52 - x, 0.94 - y) for x, y in path] for path in six])
    return g


def gen_hud(out, opts):
    cell = max(64, min(256, opts.texture_size // 2))
    glyphs = _hud_glyphs()
    cols = 8
    rows = (len(_HUD_CHARS) + cols - 1) // cols
    cv = Canvas(cols * cell, rows * cell)
    stroke = _HUD_STROKE * cell
    adv = []
    for idx, ch in enumerate(_HUD_CHARS):
        w, paths, dots = glyphs[ch]
        ox = (idx % cols) * cell + _HUD_PAD * cell
        oy = (idx // cols) * cell
        for path in paths:
            for (x0, y0), (x1, y1) in zip(path, path[1:]):
                cv.line(ox + x0 * cell, oy + y0 * cell, ox + x1 * cell, oy + y1 * cell, stroke, (255, 255, 255, 255))
        for x, y, r in dots:
            cv.disc(ox + x * cell, oy + y * cell, r * cell, (255, 255, 255, 255))
        adv.append(int(round((w + 2 * _HUD_PAD) * cell)))
    TOKENS.update({
        "HUD_CHARS": _HUD_CHARS, "HUD_ADV": ", ".join(str(a) for a in adv), "HUD_CELL": str(cell),
        "HUD_COLS": str(cols), "HUD_ATLAS_W": str(cols * cell), "HUD_ATLAS_H": str(rows * cell),
        "HUD_SPACE": str(int(round(0.28 * cell))),
    })
    out.texture("gui/hud_glyphs.png", cv.to_png())
    out.texture("gui/hud_plate.png", _hud_plate())
    out.texture("gui/hud_icon.png", _hud_icon(128))


def _hud_icon(size):
    """The counter's icon: a neon "consumed block" - an isometric cube with a vortex bite taken out of its top."""
    cv = Canvas(size, size)
    s = size / 128.0
    top, ur, lr, bot, ll, ul, mid = ((64, 10), (112, 36), (112, 92), (64, 118), (16, 92), (16, 36), (64, 62))

    def p(pt):
        return (pt[0] * s, pt[1] * s)

    _poly(cv, [p(top), p(ur), p(mid), p(ul)], (176, 84, 250, 255))
    _poly(cv, [p(ul), p(mid), p(bot), p(ll)], (90, 30, 156, 255))
    _poly(cv, [p(mid), p(ur), p(lr), p(bot)], (50, 16, 108, 255))
    # the bite: a dark elliptical hole in the top face, a swirl ring around its rim
    hole = [(64 * s + 24 * s * math.cos(a), 36 * s + 12 * s * math.sin(a)) for a in [i * _TAU / 28 for i in range(28)]]
    _poly(cv, hole, (5, 0, 14, 255))
    for i in range(60):
        a0, a1 = 0.1 * i, 0.1 * (i + 1)
        r0, r1 = 5.0 + a0 * 3.2, 5.0 + a1 * 3.2
        col = tuple(_mix(_MAGENTA, _CYAN, i / 59.0)) + (255,)
        cv.line(64 * s + r0 * math.cos(a0 * 2.2) * 1.9 * s, 36 * s + r0 * math.sin(a0 * 2.2) * 0.95 * s,
                64 * s + r1 * math.cos(a1 * 2.2) * 1.9 * s, 36 * s + r1 * math.sin(a1 * 2.2) * 0.95 * s, 2.2 * s, col)
    for a, b in ((top, ur), (ur, lr), (lr, bot), (bot, ll), (ll, ul), (ul, top), (ul, mid), (mid, ur), (mid, bot)):
        cv.line(a[0] * s, a[1] * s, b[0] * s, b[1] * s, 3.0 * s, _CYAN + (255,))
    for x, y, w, c in ((96, 22, 7, (150, 150, 158)), (104, 30, 5, (134, 96, 66)), (90, 17, 4, (92, 156, 60))):   # swallowed block bits
        cv.rect((x - w / 2) * s, (y - w / 2) * s, (x + w / 2) * s, (y + w / 2) * s, c + (255,))
    return cv.to_png()


def _hud_plate():
    """1024x128 neon pill for the 3-slice HUD plate: caps are the outer 64 px, the middle stretches."""
    W, H = 1024, 128
    margin, bw = 14.0, 5.0
    r = (H - 2 * margin) / 2.0
    cy = H / 2.0
    xl, xr = margin + r, W - margin - r
    left, right = (255, 70, 210), (70, 225, 255)
    px = bytearray(W * H * 4)
    i = 0
    for y in range(H):
        dy = y + 0.5 - cy
        shade = y / float(H)
        fill_col = (10 + 8 * shade, 8 + 2 * shade, 30 + 14 * shade)
        for x in range(W):
            fx = x + 0.5
            d = math.hypot(max(xl - fx, 0.0, fx - xr), dy) - r          # signed distance, negative inside
            col = _mix(left, right, _clamp01((fx - xl) / (xr - xl)))
            inside = _clamp01(0.5 - d)
            px_c = (0.0, 0.0, 0.0, 0.0)
            if d > 0.0:
                px_c = _over(px_c, (col[0], col[1], col[2], 0.55 * math.exp(-(d / 6.0) ** 2) * (1.0 - inside)))
            if inside > 0.0:
                px_c = _over(px_c, (fill_col[0], fill_col[1], fill_col[2], 0.82 * inside))
                px_c = _over(px_c, (col[0], col[1], col[2], 0.30 * math.exp(-(max(-d, 0.0) / 10.0) ** 2) * inside))
            ring = _clamp01(bw / 2.0 + 0.5 - abs(d + bw / 2.0))
            if ring > 0.0:
                px_c = _over(px_c, (col[0], col[1], col[2], ring))
            px[i] = _byte(px_c[0] / 255.0)
            px[i + 1] = _byte(px_c[1] / 255.0)
            px[i + 2] = _byte(px_c[2] / 255.0)
            px[i + 3] = _byte(px_c[3])
            i += 4
    return png_bytes(W, H, px)


# ---------------------------------------------------------------------------- texture generator
def gen_textures(out, opts):
    size = opts.texture_size
    anim = min(size, 256)           # animated strips grow with frames: keep them at <= 256 px per frame

    def animation(rel, frames, **kw):
        out.asset_json("textures/%s.png.mcmeta" % rel, {"animation": dict(kw, **({"frames": frames} if frames else {}))})

    # animated glitch items: frame 0 is the clean sprite, the rest are glitch variants shown in a jittery order
    frames = 6
    order = [0, 0, 0, 0, 1, 0, 0, 2, 0, 0, 0, 3, 0, 4, 0, 0, 5, 0]
    out.texture("item/glitch_pickaxe.png", _glitch_strip(_sprite_pickaxe(anim), anim, frames, 101))
    animation("item/glitch_pickaxe", order, frametime=2)
    out.texture("item/glitchy_floppy.png", _glitch_strip(_sprite_floppy(anim), anim, frames, 202))
    animation("item/glitchy_floppy", order, frametime=2)

    # Quantum USB
    out.texture("item/quantum_usb_body.png", _usb_body(size))
    out.texture("item/quantum_usb_plug.png", paint_strip(size, 1, _usb_plug))
    out.texture("item/quantum_usb_label.png", _usb_label(size))
    out.texture("item/quantum_usb_led.png", paint_strip(anim, 4, _usb_led))
    animation("item/quantum_usb_led", None, frametime=4, interpolate=True)

    # blocks
    out.texture("block/gate_rune.png", paint_strip(anim, 8, _gate_rune))
    animation("block/gate_rune", None, frametime=3, interpolate=True)
    out.texture("block/gate_line.png", paint_strip(anim, 8, _gate_line))
    animation("block/gate_line", None, frametime=3, interpolate=True)
    out.texture("block/gate_glow.png", paint_strip(anim, 8, _gate_glow))
    animation("block/gate_glow", None, frametime=3, interpolate=True)
    out.texture("item/black_market_portal.png", paint_strip(size, 1, _gate_icon))
    out.texture("block/shop_pedestal_top.png", paint_strip(size, 1, _pedestal_top))
    out.texture("block/hacked_rgb_furnace_glow.png", paint_strip(anim, 8, _furnace_glow))
    animation("block/hacked_rgb_furnace_glow", None, frametime=3, interpolate=True)
    out.texture("block/beacon_5g_glow.png", paint_strip(anim, 8, _beacon_glow))
    animation("block/beacon_5g_glow", None, frametime=3, interpolate=True)

    # particle + icon
    out.texture("particle/green_laser.png", paint_strip(min(size, 128), 1, _laser))
    out.put("src/main/resources/assets/%s/icon.png" % MOD_ID, _icon(256))


# ---------------------------------------------------------------------------- the portal vortex core shader
PORTAL_VSH = """\
#version 150

in vec3 Position;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec2 uv;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    uv = UV0;
}
"""

PORTAL_FSH = """\
#version 150

// The block-consumer vortex, drawn as flat pixel-art tones (like a block texture): a bold two-armed swirl around a black
// hole, with swallowed blocks orbiting in it. Mode 0 = the opening of a gate (40 x 40 px, 16 px per block), 1 = the full-screen
// transition (the vortex spreads from the middle), 2 = the orb of a collapsing gate. Everything is computed per pixel from
// Time, so it flows at any frame rate.

uniform vec4 ColorModulator;
uniform float Time;     // seconds
uniform float Open;     // 0..1 summon animation
uniform float Close;    // 0..1 collapse (pick-up)
uniform float Mode;
uniform float Alpha;
uniform vec2 Aspect;

in vec2 uv;
out vec4 fragColor;

const float PI = 3.14159265;

float hash21(vec2 p) {
    p = fract(p * vec2(233.34, 851.73));
    p += dot(p, p + 23.45);
    return fract(p.x * p.y);
}

vec3 tone(float f) {            // the flat tones of the spiral: white, cyan, magenta, violet, deep violet, void
    if (f < 0.20) return vec3(0.97, 0.95, 1.00);
    if (f < 0.36) return vec3(0.28, 0.86, 1.00);
    if (f < 0.54) return vec3(0.88, 0.24, 0.84);
    if (f < 0.76) return vec3(0.56, 0.17, 0.90);
    if (f < 0.88) return vec3(0.22, 0.06, 0.46);
    return vec3(0.04, 0.01, 0.11);
}

vec3 blockColor(float h) {      // what the vortex is swallowing: stone, dirt, grass, planks, deepslate, gold
    if (h < 0.17) return vec3(0.55, 0.55, 0.58);
    if (h < 0.34) return vec3(0.58, 0.40, 0.26);
    if (h < 0.51) return vec3(0.38, 0.66, 0.25);
    if (h < 0.68) return vec3(0.74, 0.60, 0.38);
    if (h < 0.85) return vec3(0.30, 0.30, 0.35);
    return vec3(0.95, 0.77, 0.22);
}

void main() {
    if (Mode > 1.5 && Mode < 2.5) {           // orb
        vec2 d = (uv - 0.5) * 2.0;
        float r2 = dot(d, d);
        float glow = exp(-r2 * 5.0);
        vec3 c = mix(vec3(0.55, 0.22, 1.0), vec3(1.0), glow * glow);
        c = mix(c, vec3(0.25, 0.85, 1.0), smoothstep(0.35, 0.9, sqrt(r2)) * 0.6);
        fragColor = vec4(c, glow * Alpha) * ColorModulator;
        return;
    }

    if (Mode > 2.5 && Mode < 3.5) {      // the /cinematic backdrop: a dark violet studio with a drifting pixel starfield
        vec2 g = vec2(160.0 * Aspect.x / 1.7778, 90.0);
        vec2 id = floor(uv * g);
        vec2 q = (uv - 0.5) * vec2(Aspect.x, 1.0) * 2.0;
        float r = length(q);
        vec3 col = mix(vec3(0.16, 0.05, 0.30), vec3(0.015, 0.0, 0.05), smoothstep(0.0, 1.5, r));
        float sx = (q.x * 0.8 + q.y * 0.35 + 0.15 * sin(Time * 0.2)) / 0.22;
        col += vec3(0.35, 0.15, 0.7) * exp(-sx * sx) * 0.10;                              // a soft light shaft
        float h = hash21(id);
        float tw = 0.55 + 0.45 * sin(Time * (1.0 + h * 2.0) + h * 40.0);
        col += vec3(0.75, 0.85, 1.0) * step(0.9935, h) * tw;                                // stars
        col += vec3(0.95, 0.4, 0.9) * step(0.9975, fract(h * 7.31)) * tw;
        float hz = (q.y - 0.55) / 0.10;
        col += vec3(0.10, 0.35, 0.55) * exp(-hz * hz) * 0.20;                              // glow of the floor on the horizon
        fragColor = vec4(col, 1.0) * ColorModulator;
        return;
    }
    if (Mode > 3.5 && Mode < 4.5) {      // the /cinematic floor: a dark glossy disc with neon rings and spokes
        vec2 d = (uv - 0.5) * 2.0;
        float r = length(d);
        float a = atan(d.y, d.x);
        float disc = 1.0 - smoothstep(0.92, 1.0, r);
        float r1 = (r - 0.22) / 0.012;
        float r2 = (r - 0.42) / 0.009;
        float r3 = (r - 0.66) / 0.007;
        float r4 = (r - 0.90) / 0.005;
        float ring = exp(-r1 * r1) + 0.8 * exp(-r2 * r2) + 0.6 * exp(-r3 * r3) + 0.5 * exp(-r4 * r4);
        float spokes = pow(abs(cos(a * 6.0)), 80.0) * smoothstep(0.1, 0.3, r) * (1.0 - smoothstep(0.5, 0.95, r)) * 0.5;
        float pulse = 0.5 + 0.5 * sin(Time * 1.6 - r * 14.0);
        vec3 neon = mix(vec3(0.25, 0.85, 1.0), vec3(0.95, 0.25, 0.85), 0.5 + 0.5 * sin(a * 3.0 + Time * 0.8));
        float glow = exp(-r * r * 6.0) * 0.45;
        vec3 col = vec3(0.02, 0.0, 0.07) + neon * (ring * (0.6 + 0.5 * pulse) + spokes + glow);
        fragColor = vec4(col, disc * (0.80 + 0.20 * min(1.0, ring + glow))) * ColorModulator;
        return;
    }

    vec2 grid = Mode < 0.5 ? vec2(40.0, 40.0) : vec2(48.0 * Aspect.x, 48.0);
    vec2 id = floor(uv * grid);
    vec2 uvq = (id + 0.5) / grid;                              // the pixel-art look of a block texture
    vec2 p = vec2(uvq.x - 0.5, 0.5 - uvq.y) * 2.0;             // -1..1, +y up
    if (Mode > 0.5) {
        p.x *= Aspect.x;                                       // full screen: keep the swirl round
    }

    vec2 pz = p * (1.0 + Close * Close * 9.0);                 // collapsing: zoom into the centre
    float r = length(pz);
    float a = atan(pz.y, pz.x);
    float spin = Time * (0.45 + Close * 2.5);
    float s = a / (2.0 * PI) * 2.0 + log(r + 0.08) * 0.95 - spin;
    vec3 col = tone(fract(s));

    float ang = spin * PI * 1.15;                              // swallowed blocks orbit with the vortex
    float ca = cos(-ang);
    float sa = sin(-ang);
    vec2 qd = vec2(pz.x * ca - pz.y * sa, pz.x * sa + pz.y * ca) * (grid.y * 0.25);
    float h = hash21(floor(qd) + vec2(11.0, 5.0));
    if (h > 0.962 && r > 0.30 && r < 0.95) {
        col = blockColor(fract(h * 977.0));
    }
    if (r < 0.16) {                                            // the black hole and its bright rim
        col = vec3(0.04, 0.01, 0.11);
    } else if (r < 0.225) {
        col = vec3(0.97, 0.95, 1.00);
    }

    float alpha = 1.0;
    if (Mode < 0.5) {
        vec2 e = min(id, grid - 1.0 - id);                     // a 1 px dark rim against the frame
        if (min(e.x, e.y) < 0.5) {
            col = vec3(0.04, 0.01, 0.11);
        }
        float o = clamp(Open, 0.0, 1.0);                       // summon: a slit tears open, then widens to the full opening
        float slitH = smoothstep(0.0, 0.30, o);
        float slitW = mix(0.035, 1.0, smoothstep(0.25, 0.95, o));
        float inSlit = (1.0 - step(slitW, abs(p.x))) * (1.0 - step(slitH, abs(p.y)));
        col = mix(col, vec3(1.0, 0.95, 1.0), (1.0 - smoothstep(0.0, 0.5, o)) * inSlit * 0.8);
        float ring = (1.0 - step(0.07, abs(length(p) - o * 1.5))) * (1.0 - o);    // a shock ring runs out from the middle
        col = mix(col, vec3(1.0), ring * 0.85);
        alpha *= inSlit;
        float shrink = 1.0 - smoothstep(0.0, 1.0, Close) * 0.95;                  // pick-up: the vortex contracts into a point
        alpha *= 1.0 - step(shrink, max(abs(p.x), abs(p.y)));
        alpha *= 1.0 - smoothstep(0.85, 1.0, Close);
        col = mix(col, vec3(1.0), Close * Close * 0.6);
    } else {
        float reach = Alpha * (length(vec2(Aspect.x, 1.0)) + 0.4);                // the vortex spreads over the screen ...
        float wob = 0.10 * sin(a * 5.0 + Time * 2.5) + 0.06 * sin(a * 9.0 - Time * 3.7);
        alpha = 1.0 - step(reach, r + wob * Alpha);                               // ... with a hard, pixel-crisp edge
    }
    fragColor = vec4(col, alpha) * ColorModulator;
}
"""


def _gen_portal_shader(out):
    base = "src/main/resources/assets/%s/shaders/core/portal_vortex" % MOD_ID
    mat4 = [1.0 if i % 5 == 0 else 0.0 for i in range(16)]

    def uniform(name, kind, count, values):
        return {"name": name, "type": kind, "count": count, "values": values}

    out.json(base + ".json", {
        "blend": {"func": "add", "srcrgb": "srcalpha", "dstrgb": "1-srcalpha"},
        "vertex": "%s:portal_vortex" % MOD_ID,
        "fragment": "%s:portal_vortex" % MOD_ID,
        "attributes": ["Position", "UV0"],
        "samplers": [],
        "uniforms": [
            uniform("ModelViewMat", "matrix4x4", 16, mat4),
            uniform("ProjMat", "matrix4x4", 16, mat4),
            uniform("ColorModulator", "float", 4, [1.0, 1.0, 1.0, 1.0]),
            uniform("Time", "float", 1, [0.0]),
            uniform("Open", "float", 1, [1.0]),
            uniform("Close", "float", 1, [0.0]),
            uniform("Mode", "float", 1, [0.0]),
            uniform("Alpha", "float", 1, [1.0]),
            uniform("Aspect", "float", 2, [1.0, 2.0]),
        ]})
    out.put(base + ".vsh", PORTAL_VSH)
    out.put(base + ".fsh", PORTAL_FSH)


# ---------------------------------------------------------------------------- the glTF PBR core shader
def gen_shaders(out, opts):
    _gen_portal_shader(out)
    base = "src/main/resources/assets/%s/shaders/core/gltf_pbr" % MOD_ID
    mat4 = [1.0 if i % 5 == 0 else 0.0 for i in range(16)]
    mat3 = [1.0 if i % 4 == 0 else 0.0 for i in range(9)]

    def uniform(name, kind, count, values):
        return {"name": name, "type": kind, "count": count, "values": values}

    out.json(base + ".json", {
        "vertex": "%s:gltf_pbr" % MOD_ID,
        "fragment": "%s:gltf_pbr" % MOD_ID,
        "attributes": ["Position", "Color", "UV0", "UV1", "UV2", "Normal"],
        "samplers": [{"name": "Sampler0"}, {"name": "Sampler1"}, {"name": "Sampler2"}, {"name": "Sampler3"}],
        "uniforms": [
            uniform("ModelViewMat", "matrix4x4", 16, mat4),
            uniform("ProjMat", "matrix4x4", 16, mat4),
            uniform("NormalMat", "matrix3x3", 9, mat3),
            uniform("ColorModulator", "float", 4, [1.0, 1.0, 1.0, 1.0]),
            uniform("BaseColorFactor", "float", 4, [1.0, 1.0, 1.0, 1.0]),
            uniform("Pbr", "float", 4, [0.0, 0.85, 0.0, 0.0]),
            uniform("Maps", "float", 4, [0.0, 0.0, 1.0, 0.0]),
            uniform("LightCoords", "float", 2, [15.0, 15.0]),
            uniform("Light0_Direction", "float", 3, [0.0, 0.0, 0.0]),
            uniform("Light1_Direction", "float", 3, [0.0, 0.0, 0.0]),
            uniform("FogStart", "float", 1, [0.0]),
            uniform("FogEnd", "float", 1, [1.0]),
            uniform("FogColor", "float", 4, [0.0, 0.0, 0.0, 0.0]),
        ]})
    out.put(base + ".vsh", """\
#version 150

// RealityShop glTF PBR vertex shader. Vertex format = vanilla ENTITY format; the (unused) Color attribute carries
// the glTF tangent: xyz * 0.5 + 0.5, w = handedness (1 / 0).

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 NormalMat;     // the item/entity matrix-stack normal matrix (vanilla light directions live in that space)

out float vertexDistance;
out vec2 texCoord0;
out vec3 viewNormal;
out vec3 viewTangent;
out float tangentSign;
out vec3 viewDir;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPos;
    vertexDistance = length(viewPos.xyz);
    texCoord0 = UV0;
    viewNormal = NormalMat * Normal;
    vec3 t = Color.xyz * 2.0 - 1.0;
    viewTangent = NormalMat * t;
    tangentSign = dot(t, t) < 0.25 ? 0.0 : (Color.w > 0.5 ? 1.0 : -1.0);
    // orthographic projection (GUI) looks along -z; otherwise towards the eye
    viewDir = ProjMat[3][3] > 0.5 ? vec3(0.0, 0.0, 1.0) : normalize(-viewPos.xyz);
}
""")
    out.put(base + ".fsh", """\
#version 150

// RealityShop glTF PBR fragment shader: metallic-roughness maps + normal map, lit like vanilla entities
// (two directional lights + the world lightmap + fog), with a Blinn-Phong lobe standing in for GGX.

uniform sampler2D Sampler0;     // base colour
uniform sampler2D Sampler1;     // normal map (tangent space, +Y up)
uniform sampler2D Sampler2;     // vanilla lightmap
uniform sampler2D Sampler3;     // metallic (B) / roughness (G)

uniform vec4 ColorModulator;
uniform vec4 BaseColorFactor;
uniform vec4 Pbr;               // metallic, roughness, alpha cutoff (MASK), unused
uniform vec4 Maps;              // has normal map, has metal/rough map, has base colour map, unused
uniform vec2 LightCoords;       // block light, sky light (0..15)
uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec2 texCoord0;
in vec3 viewNormal;
in vec3 viewTangent;
in float tangentSign;
in vec3 viewDir;

out vec4 fragColor;

void main() {
    vec4 base = BaseColorFactor;
    if (Maps.z > 0.5) {
        base *= texture(Sampler0, texCoord0);
    }
    if (Pbr.z > 0.0 && base.a < Pbr.z) {
        discard;
    }

    float metallic = clamp(Pbr.x, 0.0, 1.0);
    float roughness = clamp(Pbr.y, 0.04, 1.0);
    if (Maps.y > 0.5) {
        vec3 mr = texture(Sampler3, texCoord0).rgb;
        roughness = clamp(roughness * mr.g, 0.04, 1.0);
        metallic = clamp(metallic * mr.b, 0.0, 1.0);
    }

    vec3 n = normalize(viewNormal);
    if (!gl_FrontFacing) {
        n = -n;
    }
    if (Maps.x > 0.5 && tangentSign != 0.0) {
        vec3 t = viewTangent - n * dot(n, viewTangent);
        if (dot(t, t) > 1e-8) {
            t = normalize(t);
            vec3 b = cross(n, t) * tangentSign;
            vec3 m = texture(Sampler1, texCoord0).xyz * 2.0 - 1.0;
            n = normalize(mat3(t, b, n) * m);
        }
    }

    vec3 l0 = normalize(Light0_Direction);
    vec3 l1 = normalize(Light1_Direction);
    float diffuse = min(1.0, (max(0.0, dot(n, l0)) + max(0.0, dot(n, l1))) * 0.6 + 0.4);   // vanilla light.glsl mix

    vec3 f0 = mix(vec3(0.04), base.rgb, metallic);
    vec3 diffuseColor = base.rgb * (1.0 - metallic * 0.85);
    float shininess = mix(180.0, 6.0, roughness);
    vec3 v = normalize(viewDir);
    float spec = pow(max(0.0, dot(n, normalize(l0 + v))), shininess) + pow(max(0.0, dot(n, normalize(l1 + v))), shininess);
    spec *= (shininess + 8.0) / 100.0;

    vec3 lit = diffuseColor * diffuse + f0 * spec;
    lit *= texelFetch(Sampler2, ivec2(clamp(LightCoords, 0.0, 15.0)), 0).rgb;

    vec4 color = vec4(lit, base.a) * ColorModulator;
    if (vertexDistance > FogStart) {
        float fog = vertexDistance < FogEnd ? smoothstep(FogStart, FogEnd, vertexDistance) : 1.0;
        color = vec4(mix(color.rgb, FogColor.rgb, fog * FogColor.a), color.a);
    }
    fragColor = color;
}
""")


# @@INSERT:TEXTURES@@  (procedural texture painters are inserted above this line)


# ============================================================================ Gradle / Loom workspace
def gen_workspace(out, opts):
    out.text("gradle.properties", """\
# Gradle: Loom's Minecraft decompile step needs memory.
org.gradle.jvmargs=-Xmx3G
org.gradle.parallel=true

# Fabric (check https://fabricmc.net/develop for updates)
minecraft_version=@@MCVER@@
yarn_mappings=""" + YARN_MAPPINGS + """
loader_version=""" + LOADER_VERSION + """
fabric_version=""" + FABRIC_API_VERSION + """

# Mod
mod_version=@@MODVER@@
maven_group=@@PKG@@
archives_base_name=@@MODID@@
""")

    out.text("settings.gradle", """\
pluginManagement {
    repositories {
        maven {
            name = 'Fabric'
            url = 'https://maven.fabricmc.net/'
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = '@@MODID@@'
""")

    out.text("build.gradle", """\
plugins {
    id 'fabric-loom' version '""" + LOOM_VERSION + """'
    id 'maven-publish'
}

version = project.mod_version
group = project.maven_group

base {
    archivesName = project.archives_base_name
}

loom {
    // Client-only code lives in src/client/java so the common jar can never touch client classes
    // (a dedicated server would crash on them).
    splitEnvironmentSourceSets()

    mods {
        "@@MODID@@" {
            sourceSet sourceSets.main
            sourceSet sourceSets.client
        }
    }

    runs {
        // ./gradlew runGametest   - runs every RealityShop game test on a headless server and exits.
        // (accept the Minecraft EULA once by putting eula=true into build/gametest/eula.txt)
        gametest {
            inherit server
            name "RealityShop Game Test"
            vmArg "-Dfabric-api.gametest"
            vmArg "-Dfabric-api.gametest.report-file=${layout.buildDirectory.get().asFile}/junit.xml"
            runDir "build/gametest"
        }

        // ./gradlew runClient -PrsQuickPlay=<world folder name>  jumps straight into a singleplayer world.
        client {
            if (project.hasProperty('rsQuickPlay')) {
                programArgs '--quickPlaySingleplayer', project.property('rsQuickPlay')
            }
        }
    }
}

// every game test run starts in a fresh world (leftovers of earlier runs must not influence the results)
tasks.configureEach { task ->
    if (task.name == "runGametest") {
        task.doFirst { delete layout.buildDirectory.dir("gametest/world") }
    }
}

dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    mappings "net.fabricmc:yarn:${project.yarn_mappings}:v2"
    modImplementation "net.fabricmc:fabric-loader:${project.loader_version}"
    modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"
}

processResources {
    inputs.property "version", project.version
    filesMatching("fabric.mod.json") {
        expand "version": project.version
    }
}

tasks.withType(JavaCompile).configureEach {
    it.options.release = 17
    it.options.encoding = 'UTF-8'
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

jar {
    from("LICENSE") {
        rename { "${it}_${project.base.archivesName.get()}" }
    }
}
""")

    out.text("LICENSE", """\
All Rights Reserved (c) RealityShop.

Third-party 3D models bundled in this mod keep their own licenses (CC-BY-4.0); see CREDITS.txt.
""")

    out.text(".gitignore", """\
# gradle
.gradle/
build/
out/
classes/

# eclipse
*.launch
.settings/
.metadata/
bin/
.classpath
.project

# idea
.idea/
*.iml
*.ipr
*.iws

# vscode
.vscode/

# fabric
run/
""")

    # Gradle wrapper (the unmodified wrapper jar/scripts from the Forge MDK work for any Gradle version)
    out.put("gradle/wrapper/gradle-wrapper.jar", asset("wrapper/gradle-wrapper.jar"))
    out.put("gradle/wrapper/gradle-wrapper.properties",
            "distributionBase=GRADLE_USER_HOME\ndistributionPath=wrapper/dists\n"
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-%s-bin.zip\n"
            "networkTimeout=10000\nzipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\n" % GRADLE_VERSION)
    out.put("gradlew", asset("wrapper/gradlew"))
    out.put("gradlew.bat", asset("wrapper/gradlew.bat"))
    out.executable.add("gradlew")

    out.json("src/main/resources/fabric.mod.json", {
        "schemaVersion": 1,
        "id": MOD_ID,
        "version": "${version}",
        "name": MOD_NAME,
        "description": "Consume the world, spend it in the Black Market. Six absurd tools, one economy of destroyed blocks.",
        "authors": ["RealityShop"],
        "contributors": [
            {"name": "joshuacaca973 ('Chicken' 3D model, CC-BY-4.0)",
             "contact": {"homepage": "https://sketchfab.com/3d-models/chicken-59590091ec7647a4beae2e08acb1903a"}},
            {"name": "Violetforest ('Retro mobile phone with heart icons on the scre' 3D model, CC-BY-4.0)",
             "contact": {"homepage": "https://sketchfab.com/3d-models/retro-mobile-phone-with-heart-icons-on-the-scre-493bf5df4b764db8849c24f1a60ca672"}},
        ],
        "contact": {},
        "license": "All-Rights-Reserved",
        "icon": "assets/%s/icon.png" % MOD_ID,
        "environment": "*",
        "entrypoints": {
            "main": [PKG + ".RealityShopMod"],
            "client": [PKG + ".client.RealityShopClient"],
            "fabric-gametest": [PKG + ".gametest.RealityShopGameTests"],
        },
        "mixins": [{"config": "%s.client.mixins.json" % MOD_ID, "environment": "client"}],
        "depends": {
            "fabricloader": ">=0.15.0",
            "minecraft": "~" + MC_VERSION,
            "java": ">=17",
            "fabric-api": "*",
        },
    })
    # Three client mixins: no "Loading terrain" / "Joining world" screens while a gate carries you (see PortalTransition),
    # and the camera of /cinematic (see CinematicDirector).
    out.json("src/client/resources/%s.client.mixins.json" % MOD_ID, {
        "required": True,
        "minVersion": "0.8",
        "package": PKG + ".client.mixin",
        "compatibilityLevel": "JAVA_17",
        "client": ["ClientPlayNetworkHandlerMixin", "MinecraftClientMixin", "CameraMixin"],
        "injectors": {"defaultRequire": 1},
    })
    out.text("CREDITS.txt", asset("CREDITS.txt").decode("utf-8"))


# ============================================================================ user-supplied 3D models (glTF/GLB), used untouched
def _models_dir(opts):
    return os.path.abspath(opts.models) if opts.models else os.path.join(SCRIPT_DIR, "assets", "models")


def _resource_name(rel):
    """Minecraft resource paths may only contain [a-z0-9/._-]; Fabric silently drops anything else. Model files are
    copied byte-for-byte under such a name (`material_0_baseColor.png` -> `material_0_basecolor.png`); the runtime
    glTF loader applies the same rule to the URIs inside the (untouched) .gltf."""
    return re.sub(r"[^a-z0-9/._-]", "_", rel.lower())


def _gltf_dependencies(path):
    """Relative files (.bin / images) a .gltf refers to, so a loose .gltf can be copied together with them."""
    deps = []
    try:
        with open(path, encoding="utf-8") as fh:
            g = json.load(fh)
    except (OSError, ValueError):
        return deps
    for key in ("buffers", "images"):
        for item in g.get(key, []):
            uri = item.get("uri")
            if uri and not uri.startswith("data:"):
                deps.append(uri.replace("%20", " "))
    return deps


def gen_models(out, opts):
    base = "src/main/resources/assets/%s/models/gltf/" % MOD_ID

    def put(prefix, rel, data):
        path = prefix + _resource_name(rel)
        if path in out.files:
            raise SystemExit("model files %r collide once lower-cased (%s): rename one of them" % (rel, path))
        out.put(path, data)                                    # the BYTES are never touched, only the file name is made safe
    src = _models_dir(opts)
    found = {}
    if os.path.isdir(src):
        for entry in sorted(os.listdir(src)):
            path = os.path.join(src, entry)
            stem, ext = os.path.splitext(entry)
            if os.path.isdir(path):
                found[entry] = ("dir", path)
            elif ext.lower() == ".zip":
                found[stem] = ("zip", path)
            elif ext.lower() in (".glb", ".gltf"):
                found[stem] = ("file", path)
    for name, (kind, path) in sorted(found.items()):
        prefix = base + name + "/"
        if kind == "zip":
            with zipfile.ZipFile(path) as z:
                for info in z.infolist():
                    rel = info.filename.lstrip("/")
                    if info.is_dir() or ".." in rel.split("/"):
                        continue
                    put(prefix, rel, z.read(info))
        elif kind == "dir":
            for root, _dirs, names in os.walk(path):
                for n in sorted(names):
                    full = os.path.join(root, n)
                    with open(full, "rb") as fh:
                        put(prefix, os.path.relpath(full, path).replace(os.sep, "/"), fh.read())
        else:
            with open(path, "rb") as fh:
                put(prefix, os.path.basename(path), fh.read())
            if path.lower().endswith(".gltf"):
                for dep in _gltf_dependencies(path):
                    full = os.path.join(os.path.dirname(path), dep)
                    if os.path.isfile(full):
                        with open(full, "rb") as fh:
                            put(prefix, dep, fh.read())
    for name in KNOWN_MODELS:
        if name not in found:
            print("WARNING: no model for %r in %s - writing a placeholder; drop %s.zip/.glb/.gltf there and re-run"
                  % (name, src, name), file=sys.stderr)
            gen_placeholder_model(out, base + name + "/", name)


def gen_placeholder_model(out, prefix, name):
    """A tiny valid glTF (single textured quad pair) so the mod still loads when a model is missing."""
    pos = [-0.5, 0, 0, 0.5, 0, 0, 0.5, 1, 0, -0.5, 1, 0]
    nrm = [0, 0, 1] * 4
    tan = [1, 0, 0, 1] * 4
    uv = [0, 1, 1, 1, 1, 0, 0, 0]
    idx = [0, 1, 2, 0, 2, 3, 0, 2, 1, 0, 3, 2]
    blob = (struct.pack("<%df" % len(pos), *pos) + struct.pack("<%df" % len(nrm), *nrm)
            + struct.pack("<%df" % len(tan), *tan) + struct.pack("<%df" % len(uv), *uv)
            + struct.pack("<%dH" % len(idx), *idx))
    c = Canvas(64, 64, (40, 0, 60, 255))
    for y in range(0, 64, 16):
        for x in range(0, 64, 16):
            if (x // 16 + y // 16) % 2 == 0:
                c.rect(x, y, x + 16, y + 16, (255, 0, 200, 255))
    png = c.to_png()
    off = [0, len(pos) * 4, (len(pos) + len(nrm)) * 4, (len(pos) + len(nrm) + len(tan)) * 4,
           (len(pos) + len(nrm) + len(tan) + len(uv)) * 4]
    gltf = {
        "asset": {"version": "2.0", "generator": "RealityShop placeholder"},
        "scene": 0, "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0, "name": name}],
        "meshes": [{"primitives": [{"attributes": {"POSITION": 0, "NORMAL": 1, "TANGENT": 2, "TEXCOORD_0": 3},
                                    "indices": 4, "material": 0}]}],
        "materials": [{"doubleSided": True, "pbrMetallicRoughness": {"baseColorTexture": {"index": 0}, "metallicFactor": 0.0}}],
        "textures": [{"source": 0}],
        "images": [{"uri": "data:image/png;base64," + base64.b64encode(png).decode("ascii")}],
        "buffers": [{"byteLength": len(blob), "uri": "data:application/octet-stream;base64," + base64.b64encode(blob).decode("ascii")}],
        "bufferViews": [{"buffer": 0, "byteOffset": off[i], "byteLength": ln} for i, ln in
                        enumerate([len(pos) * 4, len(nrm) * 4, len(tan) * 4, len(uv) * 4, len(idx) * 2])],
        "accessors": [
            {"bufferView": 0, "componentType": 5126, "count": 4, "type": "VEC3", "min": [-0.5, 0, 0], "max": [0.5, 1, 0]},
            {"bufferView": 1, "componentType": 5126, "count": 4, "type": "VEC3"},
            {"bufferView": 2, "componentType": 5126, "count": 4, "type": "VEC4"},
            {"bufferView": 3, "componentType": 5126, "count": 4, "type": "VEC2"},
            {"bufferView": 4, "componentType": 5123, "count": len(idx), "type": "SCALAR"},
        ],
    }
    out.json(prefix + "scene.gltf", gltf)


# ============================================================================ Java sources
JAVA_SOURCES = {}   # rel path -> (template, client?)


def J(rel, src, client=False):
    """Register a Java template (path relative to the base package). client=True -> src/client/java."""
    if rel in JAVA_SOURCES:
        raise SystemExit("generator bug: duplicate java template " + rel)
    JAVA_SOURCES[rel] = (src, client)


J("RealityShopMod.java", r'''
package @@PKG@@;

import @@PKG@@.command.RealityShopCommand;
import @@PKG@@.economy.ConsumedBlocksCapability;
import @@PKG@@.economy.EconomyEvents;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import @@PKG@@.destroy.DestructionManager;
import @@PKG@@.entity.DerpChickenEntity;
import @@PKG@@.entity.SuckedBlockEntity;
import @@PKG@@.shop.BlackMarketRoom;
import @@PKG@@.shop.ShopDimensions;
import @@PKG@@.util.GlowLights;
import @@PKG@@.util.ServerTasks;
import @@PKG@@.item.GlitchPickaxeItem;
import @@PKG@@.registry.ModBlockEntities;
import @@PKG@@.registry.ModBlocks;
import @@PKG@@.registry.ModEntities;
import @@PKG@@.registry.ModItemGroups;
import @@PKG@@.registry.ModItems;
import @@PKG@@.registry.ModParticles;
import @@PKG@@.registry.ModSounds;
// <<IMPORTS>>

public class RealityShopMod implements ModInitializer {
    public static final String MODID = "@@MODID@@";
    public static final Logger LOGGER = LoggerFactory.getLogger("RealityShop");
    /** -Drealityshop.debug=true logs the gate trips (positions, timings) - for bug reports. */
    public static final boolean DEBUG = Boolean.getBoolean("realityshop.debug");

    public static Identifier id(String path) {
        return new Identifier(MODID, path);
    }

    @Override
    public void onInitialize() {
        RSConfig.load();
        ConsumedBlocksCapability.init();
        ModSounds.init();
        ModParticles.init();
        ModBlocks.init();
        ModEntities.init();
        ModItems.init();
        ModBlockEntities.init();
        ModItemGroups.init();
        // <<INIT_REGISTRIES>>
        EconomyEvents.register();
        RealityShopCommand.register();
        DestructionManager.register();
        GlitchPickaxeItem.registerEvents();
        DerpChickenEntity.registerEvents();
        SuckedBlockEntity.registerEvents();
        ServerTasks.register();
        GlowLights.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ServerWorld market = server.getWorld(ShopDimensions.BLACK_MARKET);
            if (market != null) {
                long t0 = System.nanoTime();
                BlackMarketRoom.ensureBuilt(market);
                LOGGER.info("Black Market ready ({} ms)", (System.nanoTime() - t0) / 1_000_000);
            }
        });
        // <<INIT_EVENTS>>
        LOGGER.info("RealityShop ready: consume the world, spend it in the Black Market.");
    }
}
''')

J("client/RealityShopClient.java", r'''
package @@PKG@@.client;

import @@PKG@@.RealityShopMod;
import @@PKG@@.client.gate.PortalGateRenderer;
import @@PKG@@.client.cinematic.CinematicDirector;
import @@PKG@@.client.gate.PortalShader;
import @@PKG@@.client.gate.PortalTransition;
import @@PKG@@.client.gltf.GltfModels;
import @@PKG@@.client.gltf.GltfRenderer;
import @@PKG@@.client.hud.ConsumedBlocksHud;
import @@PKG@@.client.hud.HudTextures;
import @@PKG@@.client.particle.GreenLaserParticle;
import @@PKG@@.client.render.DerpChickenRenderer;
import @@PKG@@.client.render.PhoneItemRenderer;
import @@PKG@@.client.render.ShopPedestalRenderer;
import @@PKG@@.client.render.SuckedBlockRenderer;
import @@PKG@@.registry.ModBlockEntities;
import @@PKG@@.registry.ModBlocks;
import @@PKG@@.registry.ModEntities;
import @@PKG@@.registry.ModItems;
import @@PKG@@.registry.ModParticles;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.client.render.entity.FlyingItemEntityRenderer;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
// <<CLIENT_IMPORTS>>

public class RealityShopClient implements ClientModInitializer {
    /** Tint of the green glow shell of the Hacked RGB Furnace / the red glow shell of Beacon 5G. */
    private static final int FURNACE_TINT = 0x33FF66;
    private static final int BEACON_TINT = 0xFF2A2A;

    @Override
    public void onInitializeClient() {
        ClientNet.register();
        GltfRenderer.registerShader();
        PortalShader.register();
        PortalTransition.register();
        ConsumedBlocksHud.register();
        CinematicDirector.register();

        BlockEntityRendererFactories.register(ModBlockEntities.SHOP_PEDESTAL, ShopPedestalRenderer::new);
        BlockEntityRendererFactories.register(ModBlockEntities.PORTAL_GATE, PortalGateRenderer::new);
        EntityRendererRegistry.register(ModEntities.DERP_CHICKEN, DerpChickenRenderer::new);
        EntityRendererRegistry.register(ModEntities.FLOPPY_PROJECTILE, FlyingItemEntityRenderer::new);
        EntityRendererRegistry.register(ModEntities.SUCKED_BLOCK, SuckedBlockRenderer::new);
        ParticleFactoryRegistry.getInstance().register(ModParticles.GREEN_LASER, GreenLaserParticle.Factory::new);
        BuiltinItemRendererRegistry.INSTANCE.register(ModItems.CURSED_KITTY_PHONE, new PhoneItemRenderer());

        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.HACKED_RGB_FURNACE, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.BEACON_5G, RenderLayer.getTranslucent());
        ColorProviderRegistry.BLOCK.register((state, world, pos, tintIndex) -> tintIndex == 0 ? FURNACE_TINT : -1, ModBlocks.HACKED_RGB_FURNACE);
        ColorProviderRegistry.ITEM.register((stack, tintIndex) -> tintIndex == 0 ? FURNACE_TINT : -1, ModBlocks.HACKED_RGB_FURNACE.asItem());
        ColorProviderRegistry.BLOCK.register((state, world, pos, tintIndex) -> tintIndex == 0 ? BEACON_TINT : -1, ModBlocks.BEACON_5G);
        ColorProviderRegistry.ITEM.register((stack, tintIndex) -> tintIndex == 0 ? BEACON_TINT : -1, ModBlocks.BEACON_5G.asItem());

        // on every resource reload: drop the cached glTF models (GPU buffers + textures) and rebuild the HUD textures
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public Identifier getFabricId() {
                return RealityShopMod.id("client_assets");
            }

            @Override
            public void reload(ResourceManager manager) {
                GltfModels.clear();
                HudTextures.reload(manager);
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(GltfModels::preload));
        // <<CLIENT_INIT>>
    }
}
''', client=True)

J("RSConfig.java", r'''
package @@PKG@@;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

/** config/realityshop.json - written with defaults on first start. Server fields drive gameplay, client fields visuals. */
public final class RSConfig {
    // ---- mass-destruction engine
    /** Max milliseconds per server tick spent on mass block removal (all jobs combined). */
    public int tickBudgetMs = 12;
    /** Allow the destruction items to remove bedrock. */
    public boolean destroyBedrock = false;
    /** Load/generate chunks that are not loaded when a huge clear reaches them (can create thousands of chunks!). */
    public boolean loadMissingChunks = false;
    /** Dimensions where the destruction items refuse to work (the shop lives here). */
    public List<String> protectedDimensions = new ArrayList<>(List.of("@@MODID@@:black_market", "@@MODID@@:bedrock_realm"));

    // ---- items and shop
    /** Whether the Glitch Pickaxe's 3x3x3 mining drops items (all other tools consume blocks without drops). */
    public boolean pickaxeDropsItems = true;
    /** Give every player one Black Market Portal block the first time they join. */
    public boolean giveStarterPortal = true;
    /** Hard cap on simultaneously alive DerpChickens. */
    public int maxDerpChickens = 40;
    /** DerpChickens despawn after this many seconds (0 = never). */
    public int derpChickenLifetimeSeconds = 45;
    /** Max flying "sucked up" block entities per Beacon 5G (visual only). */
    public int maxSuckedBlocks = 80;
    /** Seconds the Beacon 5G needs to vacuum up its whole area (slow and relentless). */
    public int beaconPurgeSeconds = 60;
    /** Seconds the Hacked RGB Furnace needs to eat its area. */
    public int furnacePurgeSeconds = 10;
    /** Side of the square the Quantum USB purges in front of you (500 x 2.95 = 1475). Only loaded chunks are touched unless loadMissingChunks is on. */
    public int usbPurgeSize = 1475;
    /** Seconds an invisible glow light stays at a Glitchy Floppy impact so the hole is not pitch black (0 = off). */
    public int floppyGlowSeconds = 45;

    // ---- client visuals
    public boolean hudEnabled = true;
    public double hudScale = 1.0;
    /** Position of the counter's top-left corner in GUI units. */
    public int hudX = 6;
    public int hudY = 6;
    /** Multiplier for laser / tornado particle counts (lower it on weak GPUs). */
    public double particleDensity = 1.0;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static RSConfig instance = new RSConfig();

    public static RSConfig get() {
        return instance;
    }

    public static void load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("@@MODID@@.json");
        RSConfig loaded = null;
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                loaded = GSON.fromJson(reader, RSConfig.class);
            } catch (Exception e) {
                RealityShopMod.LOGGER.warn("Could not read {} - using defaults ({})", file, e.toString());
            }
        }
        instance = loaded == null ? new RSConfig() : loaded;
        instance.sanitize();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(instance, writer);
            }
        } catch (IOException e) {
            RealityShopMod.LOGGER.warn("Could not write {} ({})", file, e.toString());
        }
    }

    private void sanitize() {
        tickBudgetMs = Math.max(2, Math.min(45, tickBudgetMs));
        maxDerpChickens = Math.max(0, Math.min(500, maxDerpChickens));
        derpChickenLifetimeSeconds = Math.max(0, Math.min(3600, derpChickenLifetimeSeconds));
        maxSuckedBlocks = Math.max(0, Math.min(1000, maxSuckedBlocks));
        beaconPurgeSeconds = Math.max(5, Math.min(3600, beaconPurgeSeconds));
        furnacePurgeSeconds = Math.max(2, Math.min(3600, furnacePurgeSeconds));
        floppyGlowSeconds = Math.max(0, Math.min(600, floppyGlowSeconds));
        hudScale = Math.max(0.5, Math.min(3.0, hudScale));
        hudX = Math.max(0, Math.min(4000, hudX));
        hudY = Math.max(0, Math.min(4000, hudY));
        particleDensity = Math.max(0.05, Math.min(4.0, particleDensity));
        if (protectedDimensions == null) {
            protectedDimensions = new ArrayList<>();
        }
    }

    public long tickBudgetNanos() {
        return tickBudgetMs * 1_000_000L;
    }

    public boolean isProtectedDimension(String id) {
        return protectedDimensions.contains(id);
    }
}
''')

J("economy/ConsumedBlocksCapability.java", r'''
package @@PKG@@.economy;

import com.mojang.serialization.Codec;
import @@PKG@@.RealityShopMod;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.entity.player.PlayerEntity;

/**
 * The "ConsumedBlocks" player capability.
 *
 * Fabric has no Forge-style capability system; the equivalent is a Fabric Data Attachment on the player:
 * persistent (saved with the player), a long (no 2-billion cap), and kept when the player dies.
 * Use {@link ConsumedBlocks} on the server to change it and keep the client in sync.
 */
public final class ConsumedBlocksCapability {
    public static final AttachmentType<Long> TYPE = AttachmentRegistry.<Long>builder()
            .persistent(Codec.LONG)
            .copyOnDeath()
            .initializer(() -> 0L)
            .buildAndRegister(RealityShopMod.id("consumed_blocks"));

    private ConsumedBlocksCapability() {}

    /** Forces class loading so the attachment is registered during mod initialisation. */
    public static void init() {}

    public static long get(PlayerEntity player) {
        return player.getAttachedOrElse(TYPE, 0L);
    }

    public static void set(PlayerEntity player, long value) {
        player.setAttached(TYPE, Math.max(0L, value));
    }

    /** Adds (or subtracts, if negative); saturates instead of overflowing and never goes below zero. */
    public static void add(PlayerEntity player, long delta) {
        long current = get(player);
        long result = current + delta;
        if (delta > 0 && result < current) {
            result = Long.MAX_VALUE;
        }
        set(player, Math.max(0L, result));
    }

    public static boolean tryRemove(PlayerEntity player, long cost) {
        if (cost <= 0) {
            return true;
        }
        long current = get(player);
        if (current < cost) {
            return false;
        }
        set(player, current - cost);
        return true;
    }
}
''')

J("economy/ConsumedBlocks.java", r'''
package @@PKG@@.economy;

import @@PKG@@.net.ModNetwork;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/** Server-side helpers to change a player's balance and keep the client's HUD / pedestal colours in sync. */
public final class ConsumedBlocks {
    private ConsumedBlocks() {}

    public static long get(PlayerEntity player) {
        return ConsumedBlocksCapability.get(player);
    }

    public static void add(ServerPlayerEntity player, long amount) {
        if (amount == 0) {
            return;
        }
        ConsumedBlocksCapability.add(player, amount);
        sync(player);
    }

    /** Credit by owner UUID (the player may have logged out meanwhile: then nothing happens). */
    public static void add(MinecraftServer server, UUID owner, long amount) {
        if (server == null || owner == null) {
            return;
        }
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(owner);
        if (player != null) {
            add(player, amount);
        }
    }

    public static boolean spend(ServerPlayerEntity player, long cost) {
        boolean ok = ConsumedBlocksCapability.tryRemove(player, cost);
        if (ok) {
            sync(player);
        }
        return ok;
    }

    public static void set(ServerPlayerEntity player, long value) {
        ConsumedBlocksCapability.set(player, value);
        sync(player);
    }

    public static void sync(ServerPlayerEntity player) {
        if (player.networkHandler == null) {
            return;   // e.g. mock players in game tests
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeLong(ConsumedBlocksCapability.get(player));
        ServerPlayNetworking.send(player, ModNetwork.SYNC_BALANCE, buf);
    }
}
''')

J("economy/EconomyEvents.java", r'''
package @@PKG@@.economy;

import com.mojang.serialization.Codec;
import @@PKG@@.RSConfig;
import @@PKG@@.RealityShopMod;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import @@PKG@@.registry.ModItems;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
// <<ECON_IMPORTS>>

public final class EconomyEvents {
    private static final AttachmentType<Boolean> STARTER_GIVEN = AttachmentRegistry.<Boolean>builder()
            .persistent(Codec.BOOL)
            .copyOnDeath()
            .buildAndRegister(RealityShopMod.id("starter_given"));

    private EconomyEvents() {}

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ConsumedBlocks.sync(handler.player);
            giveStarterPortal(handler.player);
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> ConsumedBlocks.sync(newPlayer));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> ConsumedBlocks.sync(player));
    }

    private static void giveStarterPortal(ServerPlayerEntity player) {
        if (!RSConfig.get().giveStarterPortal || player.getAttachedOrElse(STARTER_GIVEN, false)) {
            return;
        }
        player.setAttached(STARTER_GIVEN, true);
        ItemStack portal = new ItemStack(ModItems.BLACK_MARKET_PORTAL);
        if (!player.getInventory().insertStack(portal)) {
            player.dropItem(portal, false);
        }
    }
}
''')

J("net/ModNetwork.java", r'''
package @@PKG@@.net;

import @@PKG@@.RealityShopMod;
import net.minecraft.util.Identifier;

/** Packet channel ids (server -> client). Receivers are registered in the client entrypoint. */
public final class ModNetwork {
    public static final Identifier SYNC_BALANCE = RealityShopMod.id("sync_balance");
    public static final Identifier LASER_BURST = RealityShopMod.id("laser_burst");
    /** One boolean: true = a gate transition starts (fade into the vortex), false = it was cancelled. */
    public static final Identifier GATE_TRANSITION = RealityShopMod.id("gate_transition");

    private ModNetwork() {}
}
''')

J("command/RealityShopCommand.java", r'''
package @@PKG@@.command;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import @@PKG@@.economy.ConsumedBlocks;
import @@PKG@@.shop.PortalGateBlock;
import @@PKG@@.shop.BlackMarketRoom;
import @@PKG@@.shop.ShopDimensions;
import java.text.NumberFormat;
import java.util.Locale;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** /realityshop balance | add &lt;n&gt; | set &lt;n&gt; | market | bedrock | back | gate | rebuild   (all but balance need op level 2). */
public final class RealityShopCommand {
    private RealityShopCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("realityshop");
            root.then(CommandManager.literal("balance").executes(ctx -> {
                ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                long v = ConsumedBlocks.get(p);
                ctx.getSource().sendFeedback(() -> Text.literal("Blocks Consumed: " + fmt(v)), false);
                return (int) Math.min(v, Integer.MAX_VALUE);
            }));
            root.then(CommandManager.literal("add").requires(s -> s.hasPermissionLevel(2))
                    .then(CommandManager.argument("amount", LongArgumentType.longArg(0)).executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                        long amount = LongArgumentType.getLong(ctx, "amount");
                        ConsumedBlocks.add(p, amount);
                        long v = ConsumedBlocks.get(p);
                        ctx.getSource().sendFeedback(() -> Text.literal("Added " + fmt(amount) + " -> " + fmt(v) + " blocks"), true);
                        return 1;
                    })));
            root.then(CommandManager.literal("set").requires(s -> s.hasPermissionLevel(2))
                    .then(CommandManager.argument("amount", LongArgumentType.longArg(0)).executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                        long amount = LongArgumentType.getLong(ctx, "amount");
                        ConsumedBlocks.set(p, amount);
                        ctx.getSource().sendFeedback(() -> Text.literal("Balance set to " + fmt(amount) + " blocks"), true);
                        return 1;
                    })));
            // admin shortcuts to the shop / void (the portal block is the normal way in)
            root.then(CommandManager.literal("market").requires(s -> s.hasPermissionLevel(2)).executes(ctx -> {
                ShopDimensions.enterMarket(ctx.getSource().getPlayerOrThrow());
                return 1;
            }));
            root.then(CommandManager.literal("bedrock").requires(s -> s.hasPermissionLevel(2)).executes(ctx -> {
                ShopDimensions.enterBedrock(ctx.getSource().getPlayerOrThrow());
                return 1;
            }));
            root.then(CommandManager.literal("back").requires(s -> s.hasPermissionLevel(2)).executes(ctx -> {
                ShopDimensions.leave(ctx.getSource().getPlayerOrThrow());
                return 1;
            }));
            // place a Reality Gate four blocks in front of you (for ops: the same gate the item places)
            root.then(CommandManager.literal("gate").requires(s -> s.hasPermissionLevel(2)).executes(ctx -> {
                ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                ServerWorld w = p.getServerWorld();
                Direction look = p.getHorizontalFacing();
                BlockPos base = p.getBlockPos().offset(look, 4);
                if (ShopDimensions.isShopDimension(w) || !PortalGateBlock.canPlace(w, base, look.getOpposite(), p)) {
                    ctx.getSource().sendError(Text.literal("No room for a gate here (3 wide, 3 tall, not in the shop dimensions)"));
                    return 0;
                }
                PortalGateBlock.place(w, base, look.getOpposite());
                return 1;
            }));
            root.then(CommandManager.literal("rebuild").requires(s -> s.hasPermissionLevel(2)).executes(ctx -> {
                ServerWorld market = ctx.getSource().getServer().getWorld(ShopDimensions.BLACK_MARKET);
                if (market == null) {
                    ctx.getSource().sendError(Text.literal("The realityshop:black_market dimension is not loaded"));
                    return 0;
                }
                BlackMarketRoom.build(market);
                ctx.getSource().sendFeedback(() -> Text.literal("Black Market room rebuilt"), true);
                return 1;
            }));
            dispatcher.register(root);
        });
    }

    public static String fmt(long v) {
        return NumberFormat.getIntegerInstance(Locale.US).format(v);
    }
}
''')

J("client/ClientBalance.java", r'''
package @@PKG@@.client;

/** Client-side mirror of the local player's balance (HUD + pedestal colours read this). */
public final class ClientBalance {
    private static volatile long value;

    private ClientBalance() {}

    public static long get() {
        return value;
    }

    public static void set(long v) {
        value = Math.max(0L, v);
    }
}
''', client=True)

J("client/ClientNet.java", r'''
package @@PKG@@.client;

import @@PKG@@.RSConfig;
import @@PKG@@.net.ModNetwork;
import @@PKG@@.registry.ModParticles;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
// <<CLIENTNET_IMPORTS>>

/** Registers the server -> client packet receivers. */
public final class ClientNet {
    private ClientNet() {}

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(ModNetwork.SYNC_BALANCE, (client, handler, buf, responseSender) -> {
            long value = buf.readLong();
            client.execute(() -> ClientBalance.set(value));
        });
        // beam batch: origin + destroyed block positions -> one GreenLaserParticle (= one beam) per block
        ClientPlayNetworking.registerGlobalReceiver(ModNetwork.LASER_BURST, (client, handler, buf, responseSender) -> {
            double ox = buf.readDouble();
            double oy = buf.readDouble();
            double oz = buf.readDouble();
            int n = buf.readVarInt();
            long[] targets = new long[n];
            for (int i = 0; i < n; i++) {
                targets[i] = buf.readLong();
            }
            client.execute(() -> {
                ClientWorld world = client.world;
                if (world == null) {
                    return;
                }
                double density = RSConfig.get().particleDensity;
                double carry = 0.0;
                for (long packed : targets) {
                    carry += density;
                    if (carry < 1.0) {
                        continue;
                    }
                    for (; carry >= 1.0; carry -= 1.0) {
                        BlockPos p = BlockPos.fromLong(packed);
                        double tx = p.getX() + 0.5;
                        double ty = p.getY() + 0.5;
                        double tz = p.getZ() + 0.5;
                        world.addParticle(ModParticles.GREEN_LASER, ox, oy, oz, tx - ox, ty - oy, tz - oz);
                    }
                }
            });
        });
        // <<CLIENTNET_RECEIVERS>>
    }
}
''', client=True)

J("registry/ModSounds.java", r'''
package @@PKG@@.registry;

import @@PKG@@.RealityShopMod;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

public final class ModSounds {
    public static final SoundEvent SHOP_BUY = register("shop_buy");
    public static final SoundEvent SHOP_DENIED = register("shop_denied");
    public static final SoundEvent KITTY_PHONE_CURSE = register("kitty_phone_curse");
    public static final SoundEvent QUANTUM_PURGE = register("quantum_purge");
    public static final SoundEvent PORTAL_OPEN = register("portal_open");
    public static final SoundEvent PORTAL_CLOSE = register("portal_close");
    // <<SOUNDS>>

    private ModSounds() {}

    private static SoundEvent register(String name) {
        Identifier id = RealityShopMod.id(name);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    public static void init() {}
}
''')

J("registry/ModBlocks.java", r'''
package @@PKG@@.registry;

import @@PKG@@.RealityShopMod;
import @@PKG@@.block.Beacon5GBlock;
import @@PKG@@.block.HackedRgbFurnaceBlock;
import @@PKG@@.shop.PortalGateBlock;
import @@PKG@@.shop.ShopPedestalBlock;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
// <<BLOCK_IMPORTS>>

public final class ModBlocks {
    /** The Reality Gate (3 x 3 multiblock, see PortalGateBlock). The id stays black_market_portal. */
    public static final Block BLACK_MARKET_PORTAL = register("black_market_portal", new PortalGateBlock(
            AbstractBlock.Settings.create().mapColor(MapColor.PURPLE).strength(-1.0F, 3600000.0F).nonOpaque()
                    .luminance(state -> PortalGateBlock.isCenter(state) ? 15 : 11).sounds(BlockSoundGroup.DEEPSLATE_BRICKS)
                    .pistonBehavior(PistonBehavior.BLOCK).dropsNothing()));
    public static final Block SHOP_PEDESTAL = register("shop_pedestal", new ShopPedestalBlock(
            AbstractBlock.Settings.create().mapColor(MapColor.PURPLE).strength(-1.0F, 3600000.0F)
                    .luminance(state -> 12).nonOpaque().sounds(BlockSoundGroup.AMETHYST_BLOCK)
                    .pistonBehavior(PistonBehavior.BLOCK)));
    public static final Block HACKED_RGB_FURNACE = register("hacked_rgb_furnace", new HackedRgbFurnaceBlock(
            AbstractBlock.Settings.create().mapColor(MapColor.EMERALD_GREEN).strength(3.5F)
                    .luminance(state -> 13).sounds(BlockSoundGroup.METAL)));
    public static final Block BEACON_5G = register("beacon_5g", new Beacon5GBlock(
            AbstractBlock.Settings.create().mapColor(MapColor.RED).strength(3.0F)
                    .luminance(state -> 15).nonOpaque().sounds(BlockSoundGroup.GLASS)));
    // <<BLOCKS>>

    private ModBlocks() {}

    private static <T extends Block> T register(String name, T block) {
        return Registry.register(Registries.BLOCK, RealityShopMod.id(name), block);
    }

    public static void init() {}
}
''')

J("registry/ModItems.java", r'''
package @@PKG@@.registry;

import @@PKG@@.RealityShopMod;
import @@PKG@@.item.CursedKittyPhoneItem;
import @@PKG@@.item.GlitchPickaxeItem;
import @@PKG@@.item.GlitchyFloppyItem;
import @@PKG@@.item.PortalGateItem;
import @@PKG@@.item.PowerBlockItem;
import @@PKG@@.item.QuantumUsbItem;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Rarity;
// <<ITEM_IMPORTS>>

public final class ModItems {
    /** Every item of the mod, in registration order (the creative tab shows exactly these). */
    public static final List<Item> ALL = new ArrayList<>();

    public static final Item BLACK_MARKET_PORTAL = register("black_market_portal",
            new PortalGateItem(new Item.Settings().rarity(Rarity.EPIC)));
    public static final Item SHOP_PEDESTAL = register("shop_pedestal",
            new BlockItem(ModBlocks.SHOP_PEDESTAL, new Item.Settings().rarity(Rarity.RARE)));
    public static final Item GLITCH_PICKAXE = register("glitch_pickaxe",
            new GlitchPickaxeItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC).fireproof()));
    public static final Item GLITCHY_FLOPPY = register("glitchy_floppy",
            new GlitchyFloppyItem(new Item.Settings().maxCount(16).rarity(Rarity.RARE)));
    public static final Item CURSED_KITTY_PHONE = register("cursed_kitty_phone",
            new CursedKittyPhoneItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC).fireproof()));
    public static final Item HACKED_RGB_FURNACE = register("hacked_rgb_furnace",
            new PowerBlockItem(ModBlocks.HACKED_RGB_FURNACE, new Item.Settings().rarity(Rarity.EPIC), "hacked_rgb_furnace"));
    public static final Item BEACON_5G = register("beacon_5g",
            new PowerBlockItem(ModBlocks.BEACON_5G, new Item.Settings().rarity(Rarity.EPIC), "beacon_5g"));
    public static final Item QUANTUM_USB = register("quantum_usb",
            new QuantumUsbItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC).fireproof()));
    public static final Item DERP_CHICKEN_SPAWN_EGG = register("derp_chicken_spawn_egg",
            new SpawnEggItem(ModEntities.DERP_CHICKEN, 0xE8963C, 0xD11F1F, new Item.Settings()));
    // <<ITEMS>>

    private ModItems() {}

    private static <T extends Item> T register(String name, T item) {
        ALL.add(item);
        return Registry.register(Registries.ITEM, RealityShopMod.id(name), item);
    }

    public static void init() {}
}
''')

J("registry/ModBlockEntities.java", r'''
package @@PKG@@.registry;

import @@PKG@@.RealityShopMod;
import @@PKG@@.block.Beacon5GBlockEntity;
import @@PKG@@.block.HackedRgbFurnaceBlockEntity;
import @@PKG@@.shop.PortalGateBlockEntity;
import @@PKG@@.shop.ShopPedestalBlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
// <<BE_IMPORTS>>

public final class ModBlockEntities {
    public static final BlockEntityType<ShopPedestalBlockEntity> SHOP_PEDESTAL = Registry.register(
            Registries.BLOCK_ENTITY_TYPE, RealityShopMod.id("shop_pedestal"),
            BlockEntityType.Builder.create(ShopPedestalBlockEntity::new, ModBlocks.SHOP_PEDESTAL).build(null));
    public static final BlockEntityType<HackedRgbFurnaceBlockEntity> HACKED_RGB_FURNACE = Registry.register(
            Registries.BLOCK_ENTITY_TYPE, RealityShopMod.id("hacked_rgb_furnace"),
            BlockEntityType.Builder.create(HackedRgbFurnaceBlockEntity::new, ModBlocks.HACKED_RGB_FURNACE).build(null));
    public static final BlockEntityType<Beacon5GBlockEntity> BEACON_5G = Registry.register(
            Registries.BLOCK_ENTITY_TYPE, RealityShopMod.id("beacon_5g"),
            BlockEntityType.Builder.create(Beacon5GBlockEntity::new, ModBlocks.BEACON_5G).build(null));
    public static final BlockEntityType<PortalGateBlockEntity> PORTAL_GATE = Registry.register(
            Registries.BLOCK_ENTITY_TYPE, RealityShopMod.id("portal_gate"),
            BlockEntityType.Builder.create(PortalGateBlockEntity::new, ModBlocks.BLACK_MARKET_PORTAL).build(null));
    // <<BLOCK_ENTITIES>>

    private ModBlockEntities() {}

    public static void init() {}
}
''')

J("registry/ModItemGroups.java", r'''
package @@PKG@@.registry;

import @@PKG@@.RealityShopMod;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;

public final class ModItemGroups {
    public static final ItemGroup REALITY_SHOP = Registry.register(Registries.ITEM_GROUP, RealityShopMod.id("reality_shop"),
            FabricItemGroup.builder()
                    .displayName(Text.translatable("itemGroup.@@MODID@@"))
                    .icon(() -> new ItemStack(ModItems.QUANTUM_USB))
                    .entries((context, entries) -> ModItems.ALL.forEach(entries::add))
                    .build());

    private ModItemGroups() {}

    public static void init() {}
}
''')

J("shop/ShopOffer.java", r'''
package @@PKG@@.shop;

import @@PKG@@.RealityShopMod;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

/** The six things the Black Market sells, in price order. Costs are in "Blocks Consumed". */
public enum ShopOffer {
    GLITCH_PICKAXE("glitch_pickaxe", 0L, 1.00f),
    GLITCHY_FLOPPY("glitchy_floppy", 500L, 1.10f),
    CURSED_KITTY_PHONE("cursed_kitty_phone", 5_000L, 1.25f),
    HACKED_RGB_FURNACE("hacked_rgb_furnace", 50_000L, 0.95f),
    BEACON_5G("beacon_5g", 300_000L, 0.95f),
    QUANTUM_USB("quantum_usb", 1_000_000L, 1.25f);

    public final String id;
    public final long cost;
    /** Scale of the floating item above the pedestal. */
    public final float displayScale;

    ShopOffer(String id, long cost, float displayScale) {
        this.id = id;
        this.cost = cost;
        this.displayScale = displayScale;
    }

    public Item item() {
        return Registries.ITEM.get(RealityShopMod.id(id));
    }

    public ItemStack stack() {
        return new ItemStack(item());
    }

    public static ShopOffer byId(String id) {
        for (ShopOffer offer : values()) {
            if (offer.id.equals(id)) {
                return offer;
            }
        }
        return GLITCH_PICKAXE;
    }
}
''')

J("shop/ShopPedestalBlockEntity.java", r'''
package @@PKG@@.shop;

import @@PKG@@.registry.ModBlockEntities;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.util.math.BlockPos;

/** Remembers which {@link ShopOffer} this pedestal sells; synced to clients so the renderer can show it. */
public class ShopPedestalBlockEntity extends BlockEntity {
    private ShopOffer offer = ShopOffer.GLITCH_PICKAXE;

    public ShopPedestalBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SHOP_PEDESTAL, pos, state);
    }

    public ShopOffer getOffer() {
        return offer;
    }

    public void setOffer(ShopOffer offer) {
        this.offer = offer;
        markDirty();
        if (world != null) {
            world.updateListeners(pos, getCachedState(), getCachedState(), Block.NOTIFY_ALL);
        }
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putString("offer", offer.id);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        offer = ShopOffer.byId(nbt.getString("offer"));
    }

    @Override
    public NbtCompound toInitialChunkDataNbt() {
        return createNbt();
    }

    @Override
    public BlockEntityUpdateS2CPacket toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }
}
''')

J("shop/ShopPedestalBlock.java", r'''
package @@PKG@@.shop;

import @@PKG@@.economy.ConsumedBlocks;
import @@PKG@@.registry.ModSounds;
import @@PKG@@.command.RealityShopCommand;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/** A podium selling one {@link ShopOffer}. Right-click while your balance covers the cost to buy one copy. */
public class ShopPedestalBlock extends BlockWithEntity {
    private static final VoxelShape SHAPE = VoxelShapes.union(
            createCuboidShape(1, 0, 1, 15, 2, 15),
            createCuboidShape(4, 2, 4, 12, 11, 12),
            createCuboidShape(2, 11, 2, 14, 14, 14));

    public ShopPedestalBlock(Settings settings) {
        super(settings);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new ShopPedestalBlockEntity(pos, state);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPE;
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (hand != Hand.MAIN_HAND) {
            return ActionResult.PASS;
        }
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayerEntity sp) || !(world.getBlockEntity(pos) instanceof ShopPedestalBlockEntity pedestal)) {
            return ActionResult.PASS;
        }
        ShopOffer offer = pedestal.getOffer();
        long balance = ConsumedBlocks.get(sp);
        if (balance >= offer.cost && ConsumedBlocks.spend(sp, offer.cost)) {
            ItemStack stack = offer.stack();
            if (!sp.getInventory().insertStack(stack)) {
                sp.dropItem(stack, false);
            }
            world.playSound(null, pos, ModSounds.SHOP_BUY, SoundCategory.BLOCKS, 1.0f, 1.0f);
            ((ServerWorld) world).spawnParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 1.4, pos.getZ() + 0.5, 18, 0.35, 0.35, 0.35, 0.02);
            sp.sendMessage(Text.literal("Purchased ").append(stack.getName()).append(" for "
                    + RealityShopCommand.fmt(offer.cost) + " blocks").formatted(Formatting.GREEN), true);
        } else {
            world.playSound(null, pos, ModSounds.SHOP_DENIED, SoundCategory.BLOCKS, 1.0f, 1.0f);
            sp.sendMessage(Text.literal("Need " + RealityShopCommand.fmt(offer.cost - balance) + " more blocks").formatted(Formatting.RED), true);
        }
        return ActionResult.CONSUME;
    }
}
''')

J("shop/ShopDimensions.java", r'''
package @@PKG@@.shop;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import @@PKG@@.RealityShopMod;
import @@PKG@@.economy.ConsumedBlocks;
import @@PKG@@.net.TransitionPackets;
import @@PKG@@.util.ServerTasks;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.block.BlockState;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

/**
 * The BlackMarket and Bedrock Realm dimensions: keys, entering/leaving, and the remembered way back. Every trip is
 * "smooth": the client fades into the gate's vortex, is moved without a loading screen and fades out again.
 */
public final class ShopDimensions {
    public static final RegistryKey<World> BLACK_MARKET = RegistryKey.of(RegistryKeys.WORLD, RealityShopMod.id("black_market"));
    public static final RegistryKey<World> BEDROCK_REALM = RegistryKey.of(RegistryKeys.WORLD, RealityShopMod.id("bedrock_realm"));

    /**
     * Where a player stood before entering the shop (saved with the player, survives death). If they came through a gate, that
     * gate's cell and facing are remembered too, so they step out in FRONT of it instead of into the doorway again.
     */
    public record ReturnPoint(String dimension, double x, double y, double z, float yaw, int gateX, int gateY, int gateZ, int gateFacing) {
        public static final int NO_GATE = Integer.MIN_VALUE;
        public static final Codec<ReturnPoint> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("dim").forGetter(ReturnPoint::dimension),
                Codec.DOUBLE.fieldOf("x").forGetter(ReturnPoint::x),
                Codec.DOUBLE.fieldOf("y").forGetter(ReturnPoint::y),
                Codec.DOUBLE.fieldOf("z").forGetter(ReturnPoint::z),
                Codec.FLOAT.fieldOf("yaw").forGetter(ReturnPoint::yaw),
                Codec.INT.optionalFieldOf("gx", NO_GATE).forGetter(ReturnPoint::gateX),
                Codec.INT.optionalFieldOf("gy", 0).forGetter(ReturnPoint::gateY),
                Codec.INT.optionalFieldOf("gz", 0).forGetter(ReturnPoint::gateZ),
                Codec.INT.optionalFieldOf("gf", 0).forGetter(ReturnPoint::gateFacing)).apply(i, ReturnPoint::new));
    }

    private static final AttachmentType<ReturnPoint> RETURN_POINT = AttachmentRegistry.<ReturnPoint>builder()
            .persistent(ReturnPoint.CODEC).copyOnDeath().buildAndRegister(RealityShopMod.id("return_point"));

    private static final Map<UUID, Long> LAST_GATE = new HashMap<>();
    private static final long GATE_COOLDOWN_TICKS = 40;
    /** Fade lead-in for trips that do not start by walking into a gate (commands, the USB). */
    private static final int LEAD_TICKS = 12;
    /** Counts walk-throughs (game tests use it: the test server has no shop dimensions to arrive in). */
    public static long gateUses;

    private ShopDimensions() {}

    public static boolean isShopDimension(World world) {
        return world.getRegistryKey() == BLACK_MARKET || world.getRegistryKey() == BEDROCK_REALM;
    }

    // ------------------------------------------------------------------------------------------ gates
    /** A player walked into a gate: out of the shop if they are in it, into the shop otherwise. */
    public static void useGate(ServerPlayerEntity player, BlockPos gateMaster, Direction gateFacing) {
        gateUses++;
        long now = player.getServerWorld().getTime();
        Long last = LAST_GATE.get(player.getUuid());
        if (last != null && now - last < GATE_COOLDOWN_TICKS) {
            TransitionPackets.send(player, false);
            return;
        }
        LAST_GATE.put(player.getUuid(), now);
        if (isShopDimension(player.getServerWorld())) {
            leave(player, 0);
        } else {
            enterMarket(player, 0, gateMaster, gateFacing);
        }
    }

    // ------------------------------------------------------------------------------------------ trips
    /** Admin shortcut: to the Black Market, no gate involved. */
    public static void enterMarket(ServerPlayerEntity player) {
        enterMarket(player, LEAD_TICKS, null, null);
    }

    private static void enterMarket(ServerPlayerEntity player, int lead, BlockPos gateMaster, Direction gateFacing) {
        ServerWorld market = player.server.getWorld(BLACK_MARKET);
        if (market == null) {
            player.sendMessage(Text.literal("The Black Market dimension is not loaded (is the realityshop datapack active?)").formatted(Formatting.RED), false);
            TransitionPackets.send(player, false);
            return;
        }
        if (!isShopDimension(player.getServerWorld())) {
            rememberReturn(player, gateMaster, gateFacing);
        }
        BlackMarketRoom.ensureBuilt(market);
        BlockPos spawn = BlackMarketRoom.SPAWN;
        smooth(player, market, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, BlackMarketRoom.SPAWN_YAW, 0.0f, lead);
    }

    /** Teleports to the Bedrock Realm (used by the Quantum USB once its purge has finished). There is no gate back. */
    public static void enterBedrock(ServerPlayerEntity player) {
        ServerWorld realm = player.server.getWorld(BEDROCK_REALM);
        if (realm == null) {
            player.sendMessage(Text.literal("The Bedrock Realm dimension is not loaded").formatted(Formatting.RED), false);
            TransitionPackets.send(player, false);
            return;
        }
        if (!isShopDimension(player.getServerWorld())) {
            rememberReturn(player, null, null);
        }
        BedrockRealm.ensureBuilt(realm);
        BlockPos spawn = BedrockRealm.SPAWN;
        smooth(player, realm, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0.0f, 0.0f, LEAD_TICKS);
    }

    public static void leave(ServerPlayerEntity player) {
        leave(player, LEAD_TICKS);
    }

    private static void leave(ServerPlayerEntity player, int lead) {
        MinecraftServer server = player.server;
        ReturnPoint point = player.getAttached(RETURN_POINT);
        if (RealityShopMod.DEBUG) {
            RealityShopMod.LOGGER.info("[gate] leave from {} at ({}, {}, {}) return point = {}", player.getServerWorld().getRegistryKey().getValue(),
                    player.getX(), player.getY(), player.getZ(), point);
        }
        ServerWorld target = null;
        double x = 0;
        double y = 0;
        double z = 0;
        float yaw = 0.0f;
        if (point != null) {
            Identifier id = Identifier.tryParse(point.dimension());
            if (id != null) {
                target = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, id));
            }
            x = point.x();
            y = point.y();
            z = point.z();
            yaw = point.yaw();
        }
        if (target == null || isShopDimension(target)) {
            target = server.getOverworld();
            BlockPos spawn = target.getSpawnPos();
            x = spawn.getX() + 0.5;
            z = spawn.getZ() + 0.5;
            y = target.getTopY(Heightmap.Type.MOTION_BLOCKING, spawn.getX(), spawn.getZ());
            point = null;
        }
        // came through a gate that is still standing: step out in front of it, looking away from it (never into the doorway)
        if (point != null && point.gateX() != ReturnPoint.NO_GATE) {
            BlockPos master = new BlockPos(point.gateX(), point.gateY(), point.gateZ());
            Direction facing = Direction.byId(point.gateFacing());
            if (facing.getAxis().isHorizontal() && PortalGateBlock.intact(target, master, facing)) {
                BlockPos out = findFree(target, master.offset(facing, 2));
                if (out == null) {
                    out = findFree(target, master.offset(facing.getOpposite(), 2));
                    yaw = facing.getOpposite().asRotation();
                } else {
                    yaw = facing.asRotation();
                }
                if (out != null) {
                    x = out.getX() + 0.5;
                    y = out.getY();
                    z = out.getZ() + 0.5;
                }
            }
        }
        smooth(player, target, x, y, z, yaw, 0.0f, lead);
    }

    /** The first position at or just above/below {@code pos} with two free blocks and solid ground, or null. */
    private static BlockPos findFree(ServerWorld world, BlockPos pos) {
        for (int dy : new int[]{0, 1, -1, 2, -2}) {
            BlockPos p = pos.up(dy);
            BlockState below = world.getBlockState(p.down());
            if (world.getBlockState(p).getCollisionShape(world, p).isEmpty() && world.getBlockState(p.up()).getCollisionShape(world, p.up()).isEmpty()
                    && !below.getCollisionShape(world, p.down()).isEmpty()) {
                return p;
            }
        }
        return null;
    }

    /** Fades the client into the vortex, moves the player (no loading screen on the client) and syncs the HUD. */
    private static void smooth(ServerPlayerEntity player, ServerWorld target, double x, double y, double z, float yaw, float pitch, int lead) {
        Runnable go = () -> {
            if (player.isRemoved() || !player.isAlive()) {
                return;
            }
            if (RealityShopMod.DEBUG) {
                RealityShopMod.LOGGER.info("[gate] teleport {} -> {} to ({}, {}, {}) yaw {}", player.getServerWorld().getRegistryKey().getValue(),
                        target.getRegistryKey().getValue(), x, y, z, yaw);
            }
            player.teleport(target, x, y, z, yaw, pitch);
            player.fallDistance = 0;
            ConsumedBlocks.sync(player);
            trace(player, target.getRegistryKey().getValue().getPath());
        };
        if (lead <= 0) {
            go.run();
        } else {
            TransitionPackets.send(player, true);
            ServerTasks.later(lead, go);
        }
    }

    private static void trace(ServerPlayerEntity player, String label) {
        if (!RealityShopMod.DEBUG) {
            return;
        }
        for (int t : new int[]{1, 2, 5, 10, 20, 40, 60, 80}) {
            ServerTasks.later(t, () -> RealityShopMod.LOGGER.info("[gate-trace] {} +{}t world={} pos=({}, {}, {}) fall={} onGround={} alive={}",
                    label, t, player.getServerWorld().getRegistryKey().getValue(), String.format("%.2f", player.getX()),
                    String.format("%.2f", player.getY()), String.format("%.2f", player.getZ()), String.format("%.1f", player.fallDistance),
                    player.isOnGround(), player.isAlive()));
        }
    }

    private static void rememberReturn(ServerPlayerEntity player, BlockPos gateMaster, Direction gateFacing) {
        player.setAttached(RETURN_POINT, new ReturnPoint(player.getServerWorld().getRegistryKey().getValue().toString(),
                player.getX(), player.getY(), player.getZ(), player.getYaw(),
                gateMaster == null ? ReturnPoint.NO_GATE : gateMaster.getX(), gateMaster == null ? 0 : gateMaster.getY(),
                gateMaster == null ? 0 : gateMaster.getZ(), gateFacing == null ? 0 : gateFacing.getId()));
    }
}
''')

J("shop/BlackMarketRoom.java", r'''
package @@PKG@@.shop;

import @@PKG@@.registry.ModBlocks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LightBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * The Black Market: a round cathedral 55 blocks across. A patterned floor around a glowing "consumption well", six
 * pedestals on radial spokes, a windowed wall with coloured stained glass and pillars, a ribbed dome with a chandelier, a lit shrine window
 * behind every pedestal, and the permanent exit gate in a recess of the south wall. Built from many different blocks (blackstone, deepslate, gilded blackstone, amethyst, crying
 * obsidian, glass, sea lanterns, shroomlight ...). It is built once, when the dimension first loads or is first visited.
 */
public final class BlackMarketRoom {
    public static final int FLOOR_Y = 64;
    /** Hall radius (the clear floor is a disc of this radius). */
    public static final int R = 27;
    private static final int WALL = 4;
    /** Height of the straight wall; the dome springs from there. */
    public static final int WALL_H = 13;
    public static final int DOME_H = 15;
    public static final int PEDESTAL_R = 14;
    /** The exit gate's master cell (the bottom of its doorway): in the south wall, facing north into the hall. */
    public static final BlockPos GATE = new BlockPos(0, FLOOR_Y + 1, R);
    public static final BlockPos SPAWN = new BlockPos(0, FLOOR_Y + 1, R - 5);
    public static final float SPAWN_YAW = 180.0f;
    /** The highest layer of air under the dome; the oculus glass sits one above it. */
    public static final int APEX_Y = FLOOR_Y + WALL_H + DOME_H;
    /** Highest block the build touches (the dome's apex plus its shell). */
    public static final int TOP_Y = APEX_Y + 7;

    private BlackMarketRoom() {}

    /** Pedestal i stands at 60, 0, -60, -120, 180 and 120 degrees: the first ones nearest the entrance, going round the hall. */
    public static double pedestalAngle(int i) {
        return Math.toRadians(60 - 60 * i);
    }

    public static BlockPos pedestalPos(int i) {
        double a = pedestalAngle(i);
        return new BlockPos((int) Math.round(PEDESTAL_R * Math.cos(a)), FLOOR_Y + 1, (int) Math.round(PEDESTAL_R * Math.sin(a)));
    }

    public static boolean isBuilt(ServerWorld world) {
        return world.getBlockState(new BlockPos(0, FLOOR_Y, 0)).isOf(Blocks.PURPLE_STAINED_GLASS)
                && world.getBlockState(pedestalPos(0)).isOf(ModBlocks.SHOP_PEDESTAL)
                && world.getBlockState(GATE).isOf(ModBlocks.BLACK_MARKET_PORTAL);
    }

    public static void ensureBuilt(ServerWorld world) {
        if (!isBuilt(world)) {
            build(world);
        }
    }

    public static void placePedestal(ServerWorld world, BlockPos pos, ShopOffer offer) {
        world.setBlockState(pos, ModBlocks.SHOP_PEDESTAL.getDefaultState(), Block.NOTIFY_ALL);
        if (world.getBlockEntity(pos) instanceof ShopPedestalBlockEntity pedestal) {
            pedestal.setOffer(offer);
        }
    }

    // ------------------------------------------------------------------------------------------ the build
    public static void build(ServerWorld world) {
        int reach = R + WALL + 1;
        for (int cx = Math.floorDiv(-reach, 16); cx <= Math.floorDiv(reach, 16); cx++) {
            for (int cz = Math.floorDiv(-reach, 16); cz <= Math.floorDiv(reach, 16); cz++) {
                world.getChunk(cx, cz);   // make sure the chunks exist before placing blocks
            }
        }
        Builder b = new Builder(world);
        boolean fresh = world.getBlockState(new BlockPos(0, FLOOR_Y, 0)).isAir() && world.getBlockState(new BlockPos(R - 5, FLOOR_Y, 0)).isAir();
        if (!fresh) {
            b.clear(reach);    // an older hall (or a rebuild): wipe the whole volume first
        }
        b.floor();
        b.walls();
        b.dome();
        b.lamps();
        b.entrance();
        b.pedestals();
        b.chandelier();
    }

    private static final class Builder {
        final ServerWorld world;
        final BlockPos.Mutable pos = new BlockPos.Mutable();
        final BlockState air = Blocks.AIR.getDefaultState();
        final BlockState brick = Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState();
        final BlockState polished = Blocks.POLISHED_BLACKSTONE.getDefaultState();
        final BlockState black = Blocks.BLACKSTONE.getDefaultState();
        final BlockState basalt = Blocks.POLISHED_BASALT.getDefaultState();
        final BlockState cracked = Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS.getDefaultState();
        final BlockState gilded = Blocks.GILDED_BLACKSTONE.getDefaultState();
        final BlockState tiles = Blocks.DEEPSLATE_TILES.getDefaultState();
        final BlockState deepBricks = Blocks.DEEPSLATE_BRICKS.getDefaultState();
        final BlockState deepPolished = Blocks.POLISHED_DEEPSLATE.getDefaultState();
        final BlockState amethyst = Blocks.AMETHYST_BLOCK.getDefaultState();
        final BlockState cry = Blocks.CRYING_OBSIDIAN.getDefaultState();
        final BlockState glass = Blocks.PURPLE_STAINED_GLASS.getDefaultState();
        final BlockState lantern = Blocks.SEA_LANTERN.getDefaultState();
        final BlockState shroom = Blocks.SHROOMLIGHT.getDefaultState();
        final BlockState concrete = Blocks.PURPLE_CONCRETE.getDefaultState();
        final BlockState lamp = Blocks.LIGHT.getDefaultState().with(LightBlock.LEVEL_15, 14);
        /** The tall windows run through these colours, bay by bay. */
        final BlockState[] windows = {
                Blocks.PURPLE_STAINED_GLASS.getDefaultState(), Blocks.MAGENTA_STAINED_GLASS.getDefaultState(),
                Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState(), Blocks.CYAN_STAINED_GLASS.getDefaultState(),
                Blocks.PINK_STAINED_GLASS.getDefaultState(), Blocks.BLUE_STAINED_GLASS.getDefaultState()};
        /** The shrine window behind each pedestal matches its item: pickaxe, floppy, kitty phone, furnace, beacon, USB. */
        final BlockState[] shrines = {
                Blocks.MAGENTA_STAINED_GLASS.getDefaultState(), Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState(),
                Blocks.PINK_STAINED_GLASS.getDefaultState(), Blocks.LIME_STAINED_GLASS.getDefaultState(),
                Blocks.RED_STAINED_GLASS.getDefaultState(), Blocks.CYAN_STAINED_GLASS.getDefaultState()};

        Builder(ServerWorld world) {
            this.world = world;
        }

        void set(int x, int y, int z, BlockState state) {
            pos.set(x, y, z);
            if (world.getBlockState(pos) != state) {
                world.setBlockState(pos, state, Block.NOTIFY_LISTENERS);
            }
        }

        void clear(int reach) {
            for (int x = -reach; x <= reach; x++) {
                for (int z = -reach; z <= reach; z++) {
                    for (int y = FLOOR_Y - 4; y <= TOP_Y + 3; y++) {
                        set(x, y, z, air);
                    }
                }
            }
        }

        static int hash(int x, int z) {
            int h = x * 73856093 ^ z * 19349663;
            h ^= h >>> 13;
            return h & 0x7fffffff;
        }

        // ---------------------------------------------------------------------------------- floor
        void floor() {
            int reach = R + WALL;
            for (int x = -reach; x <= reach; x++) {
                for (int z = -reach; z <= reach; z++) {
                    double d = Math.sqrt((double) x * x + (double) z * z);
                    if (d > reach) {
                        continue;
                    }
                    set(x, FLOOR_Y, z, floorBlock(x, z, d));
                    for (int dy = 1; dy <= 3; dy++) {   // foundation, with the consumption well hollowed out in the middle
                        BlockState under = deepBricks;
                        if (d < 3.0) {
                            under = dy == 3 ? lantern : air;
                        }
                        set(x, FLOOR_Y - dy, z, under);
                    }
                }
            }
            // lanterns sunk into the floor on a ring between the pedestals
            for (int k = 0; k < 12; k++) {
                double a = Math.toRadians(15 + k * 30);
                set((int) Math.round(Math.cos(a) * (PEDESTAL_R + 3.5)), FLOOR_Y, (int) Math.round(Math.sin(a) * (PEDESTAL_R + 3.5)), lantern);
            }
        }

        BlockState floorBlock(int x, int z, double d) {
            // pedestal pads first
            for (int i = 0; i < 6; i++) {
                BlockPos p = pedestalPos(i);
                double pd = Math.sqrt((x - p.getX()) * (x - p.getX()) + (double) (z - p.getZ()) * (z - p.getZ()));
                if (pd < 2.6) {
                    return gilded;
                }
                if (pd < 3.6) {
                    return cry;
                }
            }
            if (d < 3.0) {
                return glass;              // the consumption well: a purple glow from far below
            }
            if (d < 4.0) {
                return gilded;
            }
            if (d < 5.0) {
                return polished;
            }
            if (d < 6.0) {
                return amethyst;
            }
            if (z >= 6 && Math.abs(x) <= 2) {
                return Math.abs(x) == 2 ? gilded : (z % 3 == 0 ? cry : concrete);    // the carpet from the exit gate straight to the well
            }
            if (d < PEDESTAL_R + 3.0) {
                if (onSpoke(x, z)) {
                    return ((x + z) & 1) == 0 ? cry : concrete;
                }
                return ((x + z) & 1) == 0 ? deepPolished : tiles;
            }
            if (d < PEDESTAL_R + 4.0) {
                return gilded;
            }
            if (d < R - 5) {
                int h = hash(x, z) % 10;
                return ((x / 2 + z / 2) & 1) == 0 ? (h == 0 ? black : brick) : (h == 1 ? cracked : polished);
            }
            if (d < R - 1) {
                int h = hash(x, z) % 6;
                return h == 0 ? black : (h == 1 ? basalt : brick);
            }
            return d < R ? gilded : deepBricks;
        }

        boolean onSpoke(int x, int z) {
            for (int i = 0; i < 6; i++) {
                double a = pedestalAngle(i);
                double along = x * Math.cos(a) + z * Math.sin(a);
                double across = Math.abs(-x * Math.sin(a) + z * Math.cos(a));
                if (along > 0 && across < 1.6) {
                    return true;
                }
            }
            return false;
        }

        // ---------------------------------------------------------------------------------- walls
        void walls() {
            int reach = R + WALL;
            for (int x = -reach; x <= reach; x++) {
                for (int z = -reach; z <= reach; z++) {
                    double d = Math.sqrt((double) x * x + (double) z * z);
                    if (d < R - 1 || d >= reach) {
                        continue;
                    }
                    double ang = (Math.toDegrees(Math.atan2(z, x)) + 360.0) % 360.0;
                    double phi = ((ang + 15.0) % 30.0) - 15.0;                 // degrees from the centre of this bay
                    int bay = (int) Math.floor(((ang + 15.0) % 360.0) / 30.0);
                    double s = Math.toRadians(phi) * d;                         // blocks from the centre of the bay
                    boolean pillar = Math.abs(s) >= 5.4;
                    for (int rel = 1; rel <= WALL_H; rel++) {
                        BlockState state = null;
                        if (d >= R - 1 && d < R) {
                            state = pillar && Math.abs(s) >= 5.8 ? pillarBlock(rel) : null;   // pillars stand 1 block into the hall
                        } else if (pillar && Math.abs(s) >= 5.6) {
                            state = pillarBlock(rel);
                        } else if (d < R + 2) {
                            state = panelBlock(rel, s, d, bay);
                        } else {
                            state = (d < R + 3 && Math.abs(s) <= 1.8 && rel >= 4 && rel <= 10) ? lantern : deepBricks;   // glowing backing behind the windows
                        }
                        if (state != null) {
                            set(x, FLOOR_Y + rel, z, state);
                        }
                    }
                }
            }
        }

        BlockState pillarBlock(int rel) {
            if (rel == 1) {
                return gilded;
            }
            if (rel == 2) {
                return gilded;
            }
            if (rel == WALL_H - 1 || rel == WALL_H) {
                return deepPolished;
            }
            if (rel == 7) {
                return shroom;       // a warm lamp set into each pillar
            }
            return polished;
        }

        BlockState panelBlock(int rel, double s, double d, int bay) {
            double hw = rel <= 8 ? 1.8 : (rel == 9 ? 1.2 : 0.6);
            boolean windowRows = rel >= 4 && rel <= 10;
            if (windowRows && Math.abs(s) <= hw) {
                return windows[bay % windows.length];
            }
            if (windowRows && Math.abs(s) <= hw + 1.0) {
                return gilded;          // gold frame around the window
            }
            if (rel <= 2) {
                return brick;
            }
            if (rel == 3 || rel == 11) {
                return gilded;
            }
            if (rel >= 12) {
                return tiles;
            }
            return (rel & 1) == 0 ? brick : deepPolished;
        }

        // ---------------------------------------------------------------------------------- dome
        void dome() {
            double aIn = R;
            double cIn = DOME_H;
            double aOut = R + WALL;
            double cOut = DOME_H + 6;
            int reach = R + WALL;
            for (int h = 1; h <= (int) cOut; h++) {
                int y = FLOOR_Y + WALL_H + h;
                for (int x = -reach; x <= reach; x++) {
                    for (int z = -reach; z <= reach; z++) {
                        double rho2 = (double) x * x + (double) z * z;
                        double eOut = rho2 / (aOut * aOut) + (double) h * h / (cOut * cOut);
                        if (eOut > 1.0) {
                            continue;
                        }
                        double eIn = rho2 / (aIn * aIn) + (double) h * h / (cIn * cIn);
                        if (eIn <= 1.0) {
                            continue;     // the hall's air
                        }
                        set(x, y, z, domeBlock(x, z, h, Math.sqrt(rho2), eIn));
                    }
                }
            }
        }

        BlockState domeBlock(int x, int z, int h, double rho, double eIn) {
            boolean surface = eIn <= 1.14;
            if (!surface) {
                return tiles;
            }
            if (rho < 3.4 && h >= DOME_H - 2) {
                return glass;                     // the oculus
            }
            double ang = (Math.toDegrees(Math.atan2(z, x)) + 360.0) % 360.0;
            double rib = Math.abs(((ang + 7.5) % 15.0) - 7.5) * Math.toRadians(1.0) * rho;   // blocks from the nearest rib
            if (rib < 0.7) {
                return (h % 6 == 3) ? lantern : cry;
            }
            if (h == 4 || h == 9) {
                return gilded;
            }
            return (hash(x, z) % 23 == 0) ? lantern : deepBricks;
        }

        // ---------------------------------------------------------------------------------- entrance gate in the south wall
        void entrance() {
            for (int x = -4; x <= 4; x++) {
                for (int y = 1; y <= 7; y++) {
                    for (int z = R - 1; z <= R + 2; z++) {
                        boolean pillar = Math.abs(x) >= 3;
                        boolean lintel = y >= 6;
                        BlockState state = air;
                        if (pillar || lintel) {
                            state = y == 6 || y == 1 ? gilded : (y == 7 ? deepBricks : (Math.abs(x) == 4 ? deepBricks : polished));
                            if (y == 7 && Math.abs(x) >= 3) {
                                state = air;                                  // the pediment steps in: 9 wide at the lintel, 5 wide above it
                            }
                        }
                        set(x, FLOOR_Y + y, z, state);
                    }
                }
            }
            for (int x = -2; x <= 2; x++) {
                set(x, FLOOR_Y + 7, R - 1, x == 0 ? lantern : cry);
                for (int y = 1; y <= 5; y++) {
                    set(x, FLOOR_Y + y, R + 3, y == 5 || Math.abs(x) == 2 ? cry : black);   // a dark backdrop for the swirl
                }
            }
            for (int y = 2; y <= 5; y++) {
                set(-3, FLOOR_Y + y, R - 1, y == 4 ? lantern : polished);
                set(3, FLOOR_Y + y, R - 1, y == 4 ? lantern : polished);
            }
            set(-3, FLOOR_Y + 5, R - 1, amethyst);
            set(3, FLOOR_Y + 5, R - 1, amethyst);
            for (int x = -1; x <= 1; x++) {                                    // glow under the gate
                set(x, FLOOR_Y, R, lantern);
                set(x, FLOOR_Y, R + 1, lantern);
            }
            PortalGateBlock.placeNow(world, GATE, Direction.NORTH, true);
        }

        // ---------------------------------------------------------------------------------- invisible lamps: the prices stay readable
        void lamps() {
            for (int x = -24; x <= 24; x += 8) {
                for (int z = -24; z <= 24; z += 8) {
                    if (x * x + z * z <= 22 * 22) {
                        set(x, FLOOR_Y + 3, z, lamp);
                    }
                }
            }
        }

        // ---------------------------------------------------------------------------------- pedestals + their shrine windows
        void pedestals() {
            ShopOffer[] offers = ShopOffer.values();
            for (int i = 0; i < offers.length; i++) {
                BlockPos p = pedestalPos(i);
                placePedestal(world, p, offers[i]);
                double a = pedestalAngle(i);
                double ux = Math.cos(a);                   // outwards (away from the centre of the hall)
                double uz = Math.sin(a);
                double tx = -uz;                           // sideways
                double tz = ux;
                BlockState tint = shrines[i % shrines.length];
                for (double k = -2.5; k <= 2.5; k += 0.5) {      // sea lanterns behind ...
                    int lx = (int) Math.round(p.getX() + ux * 4.0 + tx * k);
                    int lz = (int) Math.round(p.getZ() + uz * 4.0 + tz * k);
                    for (int y = 1; y <= 4; y++) {
                        set(lx, FLOOR_Y + y, lz, lantern);
                    }
                }
                for (double k = -2.5; k <= 2.5; k += 0.5) {      // ... a stained-glass window in a gilded frame
                    int gx = (int) Math.round(p.getX() + ux * 3.0 + tx * k);
                    int gz = (int) Math.round(p.getZ() + uz * 3.0 + tz * k);
                    boolean side = Math.abs(k) > 2.2;
                    for (int y = 1; y <= 5; y++) {
                        set(gx, FLOOR_Y + y, gz, side ? (y == 1 || y == 5 ? gilded : polished) : (y == 5 ? gilded : tint));
                    }
                }
                for (int side = -1; side <= 1; side += 2) {      // a low crystal brazier on each side of the pedestal
                    int bx = (int) Math.round(p.getX() + ux * 1.5 + tx * 3.0 * side);
                    int bz = (int) Math.round(p.getZ() + uz * 1.5 + tz * 3.0 * side);
                    set(bx, FLOOR_Y + 1, bz, cry);
                    set(bx, FLOOR_Y + 2, bz, shroom);
                    set(bx, FLOOR_Y + 3, bz, Blocks.AMETHYST_CLUSTER.getDefaultState());
                }
                set(p.getX(), FLOOR_Y + 4, p.getZ(), lamp);    // so the price label above the pedestal is always bright
            }
        }

        // ---------------------------------------------------------------------------------- chandelier under the oculus
        void chandelier() {
            int top = APEX_Y;
            BlockState chain = Blocks.CHAIN.getDefaultState();
            for (int y = top; y >= FLOOR_Y + 17; y--) {
                set(0, y, 0, chain);
            }
            set(0, FLOOR_Y + 16, 0, lantern);
            for (int k = 0; k < 8; k++) {
                double a = Math.toRadians(k * 45);
                int x = (int) Math.round(Math.cos(a) * 3);
                int z = (int) Math.round(Math.sin(a) * 3);
                set(x, FLOOR_Y + 16, z, k % 2 == 0 ? shroom : lantern);
                set(x, FLOOR_Y + 17, z, chain);
                set(x, FLOOR_Y + 18, z, chain);
            }
        }
    }
}
''')

J("client/render/ShopPedestalRenderer.java", r'''
package @@PKG@@.client.render;

import @@PKG@@.client.ClientBalance;
import @@PKG@@.command.RealityShopCommand;
import @@PKG@@.shop.ShopOffer;
import @@PKG@@.shop.ShopPedestalBlockEntity;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;

/**
 * Floating 3D item + a billboard tag above a {@link ShopPedestalBlockEntity}: its power, its name and "Cost: X Blocks".
 * The tag is green when the local player's balance covers the cost, red otherwise (computed per viewer).
 */
public class ShopPedestalRenderer implements BlockEntityRenderer<ShopPedestalBlockEntity> {
    private static final int GREEN = 0x55FF55;
    private static final int RED = 0xFF5555;

    private final ItemRenderer itemRenderer;
    private final TextRenderer textRenderer;
    private final EntityRenderDispatcher dispatcher;

    public ShopPedestalRenderer(BlockEntityRendererFactory.Context ctx) {
        this.itemRenderer = ctx.getItemRenderer();
        this.textRenderer = ctx.getTextRenderer();
        this.dispatcher = ctx.getEntityRenderDispatcher();
    }

    @Override
    public void render(ShopPedestalBlockEntity be, float tickDelta, MatrixStack matrices, VertexConsumerProvider vcp, int light, int overlay) {
        ShopOffer offer = be.getOffer();
        long time = be.getWorld() == null ? 0 : be.getWorld().getTime();
        float t = time + tickDelta;

        // floating, spinning, bobbing 3D item
        matrices.push();
        matrices.translate(0.5, 1.45 + Math.sin(t * 0.08) * 0.07, 0.5);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees((t * 2.0f) % 360.0f));
        float s = offer.displayScale;
        matrices.scale(s, s, s);
        itemRenderer.renderItem(offer.stack(), ModelTransformationMode.FIXED, LightmapTextureManager.MAX_LIGHT_COORDINATE,
                OverlayTexture.DEFAULT_UV, matrices, vcp, be.getWorld(), (int) be.getPos().asLong());
        matrices.pop();

        // billboard text tag
        boolean affordable = ClientBalance.get() >= offer.cost;
        Text name = offer.stack().getName();
        Text cost = Text.literal("Cost: " + RealityShopCommand.fmt(offer.cost) + " Blocks");
        Text power = Text.translatable("tooltip.realityshop." + Registries.ITEM.getId(offer.stack().getItem()).getPath() + ".1");   // "Power: ..."
        matrices.push();
        matrices.translate(0.5, 2.45, 0.5);
        matrices.multiply(dispatcher.getRotation());
        matrices.scale(-0.022f, -0.022f, 0.022f);
        Matrix4f m = matrices.peek().getPositionMatrix();
        textRenderer.draw(power, -textRenderer.getWidth(power) / 2.0f, -22, 0xC8B4FF, false, m, vcp,
                TextRenderer.TextLayerType.SEE_THROUGH, 0x44000000, LightmapTextureManager.MAX_LIGHT_COORDINATE);
        textRenderer.draw(name, -textRenderer.getWidth(name) / 2.0f, -11, 0xFFFFFF, false, m, vcp,
                TextRenderer.TextLayerType.SEE_THROUGH, 0x55000000, LightmapTextureManager.MAX_LIGHT_COORDINATE);
        textRenderer.draw(cost, -textRenderer.getWidth(cost) / 2.0f, 0, affordable ? GREEN : RED, false, m, vcp,
                TextRenderer.TextLayerType.SEE_THROUGH, 0x66000000, LightmapTextureManager.MAX_LIGHT_COORDINATE);
        matrices.pop();
    }

    @Override
    public boolean rendersOutsideBoundingBox(ShopPedestalBlockEntity be) {
        return true;
    }
}
''', client=True)

J("destroy/DestroyRegion.java", r'''
package @@PKG@@.destroy;

/** A set of block positions. All bounds are inclusive block coordinates. */
public interface DestroyRegion {
    int minX();
    int minY();
    int minZ();
    int maxX();
    int maxY();
    int maxZ();

    boolean contains(int x, int y, int z);

    /** True if every block of the inclusive box is inside the region (lets whole chunk sections be swapped out at once). */
    boolean containsBox(int x0, int y0, int z0, int x1, int y1, int z1);

    boolean intersectsBox(int x0, int y0, int z0, int x1, int y1, int z1);
}
''')

J("destroy/BoxRegion.java", r'''
package @@PKG@@.destroy;

import net.minecraft.util.math.BlockPos;

public record BoxRegion(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) implements DestroyRegion {

    /** A box of the given size whose horizontal centre is {@code centre}; vertically it spans [centre.y - below, centre.y + above]. */
    public static BoxRegion around(BlockPos centre, int sizeX, int below, int above, int sizeZ) {
        int minX = centre.getX() - sizeX / 2;
        int minZ = centre.getZ() - sizeZ / 2;
        return new BoxRegion(minX, centre.getY() - below, minZ, minX + sizeX - 1, centre.getY() + above, minZ + sizeZ - 1);
    }

    /** A cube of the given edge length centred on a block. */
    public static BoxRegion cube(BlockPos centre, int edge) {
        int r = edge / 2;
        return new BoxRegion(centre.getX() - r, centre.getY() - r, centre.getZ() - r,
                centre.getX() - r + edge - 1, centre.getY() - r + edge - 1, centre.getZ() - r + edge - 1);
    }

    @Override
    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    @Override
    public boolean containsBox(int x0, int y0, int z0, int x1, int y1, int z1) {
        return x0 >= minX && x1 <= maxX && y0 >= minY && y1 <= maxY && z0 >= minZ && z1 <= maxZ;
    }

    @Override
    public boolean intersectsBox(int x0, int y0, int z0, int x1, int y1, int z1) {
        return x1 >= minX && x0 <= maxX && y1 >= minY && y0 <= maxY && z1 >= minZ && z0 <= maxZ;
    }
}
''')

J("destroy/SphereRegion.java", r'''
package @@PKG@@.destroy;

import net.minecraft.util.math.BlockPos;

/** All blocks whose centre lies within {@code radius} of the centre of {@code origin}. */
public final class SphereRegion implements DestroyRegion {
    private final double cx;
    private final double cy;
    private final double cz;
    private final double r2;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    public SphereRegion(BlockPos origin, int radius) {
        this.cx = origin.getX() + 0.5;
        this.cy = origin.getY() + 0.5;
        this.cz = origin.getZ() + 0.5;
        this.r2 = (double) radius * radius;
        this.minX = origin.getX() - radius;
        this.maxX = origin.getX() + radius;
        this.minY = origin.getY() - radius;
        this.maxY = origin.getY() + radius;
        this.minZ = origin.getZ() - radius;
        this.maxZ = origin.getZ() + radius;
    }

    @Override public int minX() { return minX; }
    @Override public int minY() { return minY; }
    @Override public int minZ() { return minZ; }
    @Override public int maxX() { return maxX; }
    @Override public int maxY() { return maxY; }
    @Override public int maxZ() { return maxZ; }

    @Override
    public boolean contains(int x, int y, int z) {
        double dx = x + 0.5 - cx;
        double dy = y + 0.5 - cy;
        double dz = z + 0.5 - cz;
        return dx * dx + dy * dy + dz * dz <= r2;
    }

    @Override
    public boolean containsBox(int x0, int y0, int z0, int x1, int y1, int z1) {
        double dx = Math.max(Math.abs(x0 + 0.5 - cx), Math.abs(x1 + 0.5 - cx));
        double dy = Math.max(Math.abs(y0 + 0.5 - cy), Math.abs(y1 + 0.5 - cy));
        double dz = Math.max(Math.abs(z0 + 0.5 - cz), Math.abs(z1 + 0.5 - cz));
        return dx * dx + dy * dy + dz * dz <= r2;
    }

    @Override
    public boolean intersectsBox(int x0, int y0, int z0, int x1, int y1, int z1) {
        double dx = clamp(cx, x0 + 0.5, x1 + 0.5) - cx;
        double dy = clamp(cy, y0 + 0.5, y1 + 0.5) - cy;
        double dz = clamp(cz, z0 + 0.5, z1 + 0.5) - cz;
        return dx * dx + dy * dy + dz * dz <= r2;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
''')

J("destroy/RegionClearJob.java", r'''
package @@PKG@@.destroy;

import @@PKG@@.RSConfig;
import @@PKG@@.economy.ConsumedBlocks;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerLightingProvider;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Removes every block of a {@link DestroyRegion}. Built to survive absurd sizes (500 x 500 x 384 = 96 million blocks):
 *
 *  - the region is processed one chunk column at a time, nearest to {@code origin} first;
 *  - every 16x16x16 chunk section that lies completely inside the region is swapped for an empty one in O(1)
 *    (biomes are kept); only the sections cut by the region's surface are scanned block by block;
 *  - block entities inside are discarded, heightmaps recomputed, changed chunks re-sent to the players watching them,
 *    and sky/block light is repaired through the vanilla lighting engine (section emptiness + rate-limited checkBlock);
 *  - work is paced over {@code targetTicks} and limited by the shared per-tick time budget of {@link DestructionManager}.
 *
 * Nothing drops items. The number of removed blocks is credited to {@code owner}.
 */
public final class RegionClearJob {
    private static final int LIGHT_CHECKS_PER_TICK = 800;
    private static final int LIGHT_QUEUE_LIMIT = 1_500_000;
    private static final Set<Heightmap.Type> HEIGHTMAPS = EnumSet.of(Heightmap.Type.WORLD_SURFACE,
            Heightmap.Type.MOTION_BLOCKING, Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, Heightmap.Type.OCEAN_FLOOR);

    private final ServerWorld world;
    private final DestroyRegion region;
    private final int minY;
    private final int maxY;
    private final Set<BlockPos> protectedPositions = new HashSet<>();
    private final Set<WorldChunk> touched = new HashSet<>();
    /** Every chunk this job changed (kept until the final light-settled re-send). */
    private final LongOpenHashSet touchedAll = new LongOpenHashSet();
    private final LongArrayFIFOQueue lightQueue = new LongArrayFIFOQueue();
    private final BlockState air = Blocks.AIR.getDefaultState();

    private UUID owner;
    private int targetTicks;
    private BlockPos origin;
    private int sampleLimit;
    private Consumer<List<Sample>> sampleListener;
    private Consumer<RegionClearJob> progressListener;
    private Consumer<RegionClearJob> finishListener;
    private Consumer<RegionClearJob> cancelListener;

    /** A destroyed block reported to the effect listeners (position + what it was). */
    public record Sample(BlockPos pos, BlockState state) {}

    private long[] columns;
    private int next;
    private long cleared;
    private long unpaidCredit;
    private int skippedColumns;
    private boolean finished;
    private boolean cancelled;
    private final List<Sample> samples = new ArrayList<>();
    private final java.util.Random random = new java.util.Random();

    public RegionClearJob(ServerWorld world, DestroyRegion region) {
        this.world = world;
        this.region = region;
        this.minY = Math.max(region.minY(), world.getBottomY());
        this.maxY = Math.min(region.maxY(), world.getTopY() - 1);
        this.origin = new BlockPos((region.minX() + region.maxX()) / 2, (this.minY + this.maxY) / 2, (region.minZ() + region.maxZ()) / 2);
    }

    // ---- configuration (all optional)
    public RegionClearJob owner(UUID owner) { this.owner = owner; return this; }
    public RegionClearJob targetTicks(int ticks) { this.targetTicks = ticks; return this; }
    public RegionClearJob origin(BlockPos origin) { this.origin = origin; return this; }
    public RegionClearJob protect(BlockPos pos) { this.protectedPositions.add(pos.toImmutable()); return this; }
    public RegionClearJob samples(int limit, Consumer<List<Sample>> listener) { this.sampleLimit = limit; this.sampleListener = listener; return this; }
    public RegionClearJob onProgress(Consumer<RegionClearJob> listener) { this.progressListener = listener; return this; }
    public RegionClearJob onFinish(Consumer<RegionClearJob> listener) { this.finishListener = listener; return this; }
    public RegionClearJob onCancel(Consumer<RegionClearJob> listener) { this.cancelListener = listener; return this; }

    // ---- state
    public ServerWorld world() { return world; }
    public UUID owner() { return owner; }
    public long cleared() { return cleared; }
    public int skippedColumns() { return skippedColumns; }
    public boolean isFinished() { return finished; }
    public boolean isCancelled() { return cancelled; }
    public void cancel() {
        if (cancelled || finished) {
            return;
        }
        cancelled = true;
        drainLightQueue(Integer.MAX_VALUE);   // do not leave half-lit chunks behind
        resyncAfterLight();
        if (cancelListener != null) {
            cancelListener.accept(this);
        }
    }

    public double progress() {
        prepare();
        return columns.length == 0 ? 1.0 : (double) next / columns.length;
    }

    private void prepare() {
        if (columns != null) {
            return;
        }
        List<Long> list = new ArrayList<>();
        int cx0 = region.minX() >> 4;
        int cx1 = region.maxX() >> 4;
        int cz0 = region.minZ() >> 4;
        int cz1 = region.maxZ() >> 4;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (region.intersectsBox(cx << 4, minY, cz << 4, (cx << 4) + 15, maxY, (cz << 4) + 15)) {
                    list.add(ChunkPos.toLong(cx, cz));
                }
            }
        }
        double ox = origin.getX() + 0.5;
        double oz = origin.getZ() + 0.5;
        list.sort(Comparator.comparingDouble(k -> {
            double dx = ((ChunkPos.getPackedX(k) << 4) + 8) - ox;
            double dz = ((ChunkPos.getPackedZ(k) << 4) + 8) - oz;
            return dx * dx + dz * dz;
        }));
        columns = new long[list.size()];
        for (int i = 0; i < columns.length; i++) {
            columns[i] = list.get(i);
        }
    }

    /** Runs the whole job right now, ignoring pacing and the tick budget (for small regions only). */
    public void runToCompletion() {
        targetTicks = 0;
        while (!run(Long.MAX_VALUE)) {
            if (cancelled) {
                return;
            }
        }
    }

    /** Does a slice of work; returns true once the job has completely finished. */
    public boolean run(long deadlineNanos) {
        if (finished) {
            return true;
        }
        prepare();
        int perRun = targetTicks > 0 ? Math.max(1, (int) Math.ceil(columns.length / (double) targetTicks)) : Integer.MAX_VALUE;
        int done = 0;
        samples.clear();
        while (next < columns.length && done < perRun && (done == 0 || System.nanoTime() < deadlineNanos)) {
            processColumn(columns[next++]);
            done++;
        }
        finishTouchedChunks();
        drainLightQueue(deadlineNanos == Long.MAX_VALUE ? Integer.MAX_VALUE : LIGHT_CHECKS_PER_TICK);
        payCredit();
        if (sampleListener != null && !samples.isEmpty()) {
            sampleListener.accept(new ArrayList<>(samples));
        }
        if (progressListener != null) {
            progressListener.accept(this);
        }
        if (next >= columns.length && lightQueue.isEmpty()) {
            finished = true;
            resyncAfterLight();
            if (finishListener != null) {
                finishListener.accept(this);
            }
        }
        return finished;
    }

    // ---------------------------------------------------------------- per column / per section
    private void processColumn(long key) {
        int cx = ChunkPos.getPackedX(key);
        int cz = ChunkPos.getPackedZ(key);
        ServerChunkManager manager = world.getChunkManager();
        WorldChunk chunk = RSConfig.get().loadMissingChunks ? world.getChunk(cx, cz) : manager.getWorldChunk(cx, cz);
        if (chunk == null) {
            skippedColumns++;
            return;
        }
        int s0 = Math.max(ChunkSectionPos.getSectionCoord(minY), world.getBottomSectionCoord());
        int s1 = Math.min(ChunkSectionPos.getSectionCoord(maxY), world.getTopSectionCoord() - 1);
        boolean changed = false;
        // is the column open to the sky above the region? (any non-empty section above blocks it)
        boolean open = true;
        for (int sy = world.getTopSectionCoord() - 1; sy > s1; sy--) {
            if (!chunk.getSection(chunk.sectionCoordToIndex(sy)).isEmpty()) {
                open = false;
                break;
            }
        }
        for (int sy = s1; sy >= s0; sy--) {
            int index = chunk.sectionCoordToIndex(sy);
            ChunkSection section = chunk.getSection(index);
            if (!section.isEmpty() && clearSection(chunk, section, index, cx, sy, cz, open)) {
                changed = true;
            }
            if (!chunk.getSection(index).isEmpty()) {
                open = false;   // something solid remains here: everything below is shaded
            }
        }
        if (changed) {
            chunk.setNeedsSaving(true);
            touched.add(chunk);
        }
    }

    private boolean clearSection(WorldChunk chunk, ChunkSection section, int index, int cx, int sy, int cz, boolean openToSky) {
        int x0 = cx << 4;
        int y0 = sy << 4;
        int z0 = cz << 4;
        int x1 = x0 + 15;
        int y1 = y0 + 15;
        int z1 = z0 + 15;
        if (!region.intersectsBox(x0, y0, z0, x1, y1, z1) || y1 < minY || y0 > maxY) {
            return false;
        }
        boolean whole = y0 >= minY && y1 <= maxY && region.containsBox(x0, y0, z0, x1, y1, z1)
                && !protectedIn(x0, y0, z0, x1, y1, z1)
                && (RSConfig.get().destroyBedrock || !section.hasAny(RegionClearJob::isUnbreakable));
        if (whole) {
            return clearWhole(chunk, section, index, cx, sy, cz, x0, y0, z0, x1, y1, z1, openToSky);
        }
        return clearPartial(chunk, section, x0, y0, z0);
    }

    private boolean clearWhole(WorldChunk chunk, ChunkSection section, int index, int cx, int sy, int cz,
                               int x0, int y0, int z0, int x1, int y1, int z1, boolean openToSky) {
        long[] count = new long[1];
        section.getBlockStateContainer().count((state, n) -> {
            if (!state.isAir()) {
                count[0] += n;
            }
        });
        if (count[0] == 0) {
            return false;
        }
        if (sampleLimit > 0 && samples.size() < sampleLimit) {
            sampleSection(section, x0, y0, z0, 2);
        }
        for (BlockPos pos : new ArrayList<>(chunk.getBlockEntities().keySet())) {
            if (pos.getY() >= y0 && pos.getY() <= y1) {
                chunk.removeBlockEntity(pos);
            }
        }
        ChunkSection empty = new ChunkSection(new PalettedContainer<>(Block.STATE_IDS, air,
                PalettedContainer.PaletteProvider.BLOCK_STATE), section.getBiomeContainer());
        chunk.getSectionArray()[index] = empty;
        // Light: the section is now empty (exactly what vanilla does when the last block of a section is broken) and its
        // stored light arrays are replaced - the lighting engine otherwise keeps the stale values of the old solid
        // blocks while any neighbouring section is non-empty. No light emitters remain inside, so block light is zero;
        // a cleared section with open sky above it is fully sky-lit.
        ServerLightingProvider lighting = world.getChunkManager().getLightingProvider();
        ChunkSectionPos sectionPos = ChunkSectionPos.from(cx, sy, cz);
        lighting.setSectionStatus(sectionPos, true);
        lighting.enqueueSectionData(LightType.BLOCK, sectionPos, new ChunkNibbleArray());
        if (openToSky) {
            lighting.enqueueSectionData(LightType.SKY, sectionPos, new ChunkNibbleArray(15));
        }
        cleared += count[0];
        unpaidCredit += count[0];
        return true;
    }

    private boolean clearPartial(WorldChunk chunk, ChunkSection section, int x0, int y0, int z0) {
        boolean bedrock = RSConfig.get().destroyBedrock;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        boolean changed = false;
        boolean wasEmpty = section.isEmpty();
        for (int ly = 0; ly < 16; ly++) {
            int wy = y0 + ly;
            if (wy < minY || wy > maxY) {
                continue;
            }
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    BlockState state = section.getBlockState(lx, ly, lz);
                    if (state.isAir()) {
                        continue;
                    }
                    int wx = x0 + lx;
                    int wz = z0 + lz;
                    if (!region.contains(wx, wy, wz)) {
                        continue;
                    }
                    pos.set(wx, wy, wz);
                    if (!protectedPositions.isEmpty() && protectedPositions.contains(pos)) {
                        continue;
                    }
                    if (!bedrock && isUnbreakable(state)) {
                        continue;
                    }
                    section.setBlockState(lx, ly, lz, air, false);
                    if (state.hasBlockEntity()) {
                        chunk.removeBlockEntity(pos.toImmutable());
                    }
                    cleared++;
                    unpaidCredit++;
                    changed = true;
                    if (isLightSeed(wx, wy, wz, state) && lightQueue.size() < LIGHT_QUEUE_LIMIT) {
                        lightQueue.enqueue(pos.asLong());
                    }
                    if (sampleLimit > 0 && samples.size() < sampleLimit && random.nextInt(24) == 0) {
                        samples.add(new Sample(pos.toImmutable(), state));
                    }
                }
            }
        }
        if (changed && !wasEmpty && section.isEmpty()) {
            world.getChunkManager().getLightingProvider().setSectionStatus(ChunkSectionPos.from(x0 >> 4, y0 >> 4, z0 >> 4), true);
        }
        return changed;
    }

    /**
     * Which cleared cells must be handed to the lighting engine. Vanilla checks every changed block, which is far too
     * many tasks here; but the engine floods light through connected open cells by itself, so it is enough to seed it
     * (a) on the region's surface, where light flows in from the untouched world, and (b) at removed light emitters,
     * whose block light has to be taken away again.
     */
    private boolean isLightSeed(int x, int y, int z, BlockState removed) {
        if (removed.getLuminance() > 0) {
            return true;
        }
        return !region.contains(x + 1, y, z) || !region.contains(x - 1, y, z) || !region.contains(x, y + 1, z)
                || !region.contains(x, y - 1, z) || !region.contains(x, y, z + 1) || !region.contains(x, y, z - 1)
                || y >= maxY || y <= minY;
    }

    private void sampleSection(ChunkSection section, int x0, int y0, int z0, int wanted) {
        for (int found = 0, tries = 0; found < wanted && tries < 8; tries++) {
            int lx = random.nextInt(16);
            int ly = random.nextInt(16);
            int lz = random.nextInt(16);
            BlockState state = section.getBlockState(lx, ly, lz);
            if (!state.isAir()) {
                samples.add(new Sample(new BlockPos(x0 + lx, y0 + ly, z0 + lz), state));
                found++;
            }
        }
    }

    private boolean protectedIn(int x0, int y0, int z0, int x1, int y1, int z1) {
        for (BlockPos p : protectedPositions) {
            if (p.getX() >= x0 && p.getX() <= x1 && p.getY() >= y0 && p.getY() <= y1 && p.getZ() >= z0 && p.getZ() <= z1) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnbreakable(BlockState state) {
        return !state.isAir() && state.getBlock().getHardness() < 0.0F;
    }

    // ---------------------------------------------------------------- finishing: heightmaps, client resync, light, credit
    private void finishTouchedChunks() {
        if (touched.isEmpty()) {
            return;
        }
        ServerChunkManager manager = world.getChunkManager();
        ServerLightingProvider lighting = manager.getLightingProvider();
        for (WorldChunk chunk : touched) {
            touchedAll.add(chunk.getPos().toLong());
            Heightmap.populateHeightmaps(chunk, HEIGHTMAPS);
            // Sky light: vanilla's setBlockState first updates the chunk's per-column "sky surface" map and then queues a
            // light check. We bypassed setBlockState, so do both for the whole chunk: refresh the map (public bulk API)
            // and queue one check per column - the engine then resets each column from its new surface and floods sideways.
            chunk.getChunkSkyLight().refreshSurfaceY(chunk);
            int bx = chunk.getPos().getStartX();
            int bz = chunk.getPos().getStartZ();
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int y = Math.max(world.getBottomY(), Math.min(world.getTopY() - 1, chunk.getChunkSkyLight().get(lx, lz)));
                    lightQueue.enqueue(BlockPos.asLong(bx + lx, y, bz + lz));
                }
            }
            List<ServerPlayerEntity> watchers = manager.threadedAnvilChunkStorage.getPlayersWatchingChunk(chunk.getPos(), false);
            if (!watchers.isEmpty()) {
                ChunkDataS2CPacket packet = new ChunkDataS2CPacket(chunk, lighting, null, null);
                for (ServerPlayerEntity player : watchers) {
                    if (player.networkHandler != null) {
                        player.networkHandler.sendPacket(packet);
                    }
                }
            }
        }
        touched.clear();
    }

    /**
     * The chunk packets sent WHILE the job ran carry the light as it was at that moment: the light thread had not applied
     * the swapped-in sections / queued checks yet, and vanilla never re-announces light arrays that were replaced
     * wholesale - clients would keep the stale (dark) light for good. So when the job is over, wait until the light engine
     * has caught up and send every touched chunk once more. The wait is a per-chunk barrier: initializeLight() completes
     * in the engine's POST_UPDATE stage, i.e. after every light task queued before it for that chunk and a full
     * propagation pass (it re-marks non-empty sections and re-enables the sky column - both no-ops for a loaded chunk).
     */
    private void resyncAfterLight() {
        if (touchedAll.isEmpty()) {
            return;
        }
        ServerChunkManager manager = world.getChunkManager();
        ServerLightingProvider lighting = manager.getLightingProvider();
        List<ChunkPos> chunks = new ArrayList<>(touchedAll.size());
        List<CompletableFuture<?>> barriers = new ArrayList<>(touchedAll.size());
        for (long key : touchedAll) {
            WorldChunk chunk = manager.getWorldChunk(ChunkPos.getPackedX(key), ChunkPos.getPackedZ(key));
            if (chunk != null) {
                chunks.add(chunk.getPos());
                barriers.add(lighting.initializeLight(chunk, true));
            }
        }
        touchedAll.clear();
        if (!chunks.isEmpty()) {
            ServerWorld w = world;
            CompletableFuture.allOf(barriers.toArray(new CompletableFuture<?>[0]))
                    .thenRunAsync(() -> DestructionManager.queueResync(w, chunks), w.getServer());
        }
    }

    private void drainLightQueue(int max) {
        ServerLightingProvider lighting = world.getChunkManager().getLightingProvider();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int i = 0; i < max && !lightQueue.isEmpty(); i++) {
            pos.set(lightQueue.dequeueLong());
            lighting.checkBlock(pos);
        }
    }

    private void payCredit() {
        if (unpaidCredit > 0 && owner != null) {
            ConsumedBlocks.add(world.getServer(), owner, unpaidCredit);
        }
        unpaidCredit = 0;
    }
}
''')

J("destroy/DestructionManager.java", r'''
package @@PKG@@.destroy;

import @@PKG@@.RSConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

/** Runs {@link RegionClearJob}s under one shared per-tick time budget so even a 96-million-block purge cannot stall the server. */
public final class DestructionManager {
    private static final List<RegionClearJob> JOBS = new ArrayList<>();
    /** Chunks whose light has settled and that are re-sent to their watchers, a few per tick (no packet spike). */
    private static final ArrayDeque<ResyncEntry> RESYNC = new ArrayDeque<>();
    private static final int RESYNC_PER_TICK = 24;
    private static long resynced;

    private record ResyncEntry(ServerWorld world, ChunkPos pos) {}

    private DestructionManager() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(DestructionManager::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            JOBS.clear();
            RESYNC.clear();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> cancelOwnedBy(handler.player.getUuid()));
    }

    /** Queues a job; it is advanced every server tick within the time budget. */
    public static void submit(RegionClearJob job) {
        JOBS.add(job);
    }

    /** Runs a (small!) job immediately, ignoring the budget. */
    public static void runNow(RegionClearJob job) {
        job.runToCompletion();
    }

    public static int activeJobs() {
        return JOBS.size();
    }

    /** Server thread only: schedules the final, light-settled re-send of these chunks (see RegionClearJob). */
    static void queueResync(ServerWorld world, List<ChunkPos> chunks) {
        for (ChunkPos pos : chunks) {
            RESYNC.add(new ResyncEntry(world, pos));
        }
    }

    public static int pendingResyncs() {
        return RESYNC.size();
    }

    /** Total chunks handled by the light-settled re-send since the server started (for tests / diagnostics). */
    public static long resyncedChunks() {
        return resynced;
    }

    private static void resync() {
        for (int i = 0; i < RESYNC_PER_TICK && !RESYNC.isEmpty(); i++) {
            ResyncEntry entry = RESYNC.poll();
            ServerChunkManager manager = entry.world().getChunkManager();
            WorldChunk chunk = manager.getWorldChunk(entry.pos().x, entry.pos().z);
            if (chunk == null) {
                continue;
            }
            resynced++;
            List<ServerPlayerEntity> watchers = manager.threadedAnvilChunkStorage.getPlayersWatchingChunk(entry.pos(), false);
            if (watchers.isEmpty()) {
                continue;
            }
            ChunkDataS2CPacket packet = new ChunkDataS2CPacket(chunk, manager.getLightingProvider(), null, null);
            for (ServerPlayerEntity player : watchers) {
                if (player.networkHandler != null) {
                    player.networkHandler.sendPacket(packet);
                }
            }
        }
    }

    public static void cancelOwnedBy(UUID owner) {
        for (RegionClearJob job : JOBS) {
            if (owner.equals(job.owner())) {
                job.cancel();
            }
        }
    }

    public static boolean isProtected(World world) {
        return RSConfig.get().isProtectedDimension(world.getRegistryKey().getValue().toString());
    }

    private static void tick(MinecraftServer server) {
        resync();
        if (JOBS.isEmpty()) {
            return;
        }
        long slice = RSConfig.get().tickBudgetNanos() / Math.max(1, JOBS.size());
        Iterator<RegionClearJob> it = JOBS.iterator();
        while (it.hasNext()) {
            RegionClearJob job = it.next();
            try {
                if (job.isCancelled() || job.run(System.nanoTime() + slice)) {
                    it.remove();
                }
            } catch (RuntimeException e) {
                it.remove();
                @@PKG@@.RealityShopMod.LOGGER.error("Destruction job failed and was dropped", e);
            }
        }
    }
}
''')

J("registry/ModParticles.java", r'''
package @@PKG@@.registry;

import @@PKG@@.RealityShopMod;
import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.particle.DefaultParticleType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

public final class ModParticles {
    /**
     * The custom green laser particle. Its velocity parameters are interpreted as the beam VECTOR (start -> target),
     * so one particle draws one beam; see GreenLaserParticle on the client.
     */
    public static final DefaultParticleType GREEN_LASER = Registry.register(Registries.PARTICLE_TYPE,
            RealityShopMod.id("green_laser"), FabricParticleTypes.simple(true));

    private ModParticles() {}

    public static void init() {}
}
''')

J("registry/ModEntities.java", r'''
package @@PKG@@.registry;

import @@PKG@@.RealityShopMod;
import @@PKG@@.entity.DerpChickenEntity;
import @@PKG@@.entity.FloppyProjectileEntity;
import @@PKG@@.entity.SuckedBlockEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

public final class ModEntities {
    public static final EntityType<DerpChickenEntity> DERP_CHICKEN = Registry.register(Registries.ENTITY_TYPE,
            RealityShopMod.id("derp_chicken"),
            FabricEntityTypeBuilder.create(SpawnGroup.CREATURE, DerpChickenEntity::new)
                    .dimensions(EntityDimensions.fixed(0.5F, 0.95F)).trackRangeChunks(6).build());

    public static final EntityType<FloppyProjectileEntity> FLOPPY_PROJECTILE = Registry.register(Registries.ENTITY_TYPE,
            RealityShopMod.id("floppy_projectile"),
            FabricEntityTypeBuilder.<FloppyProjectileEntity>create(SpawnGroup.MISC, FloppyProjectileEntity::new)
                    .dimensions(EntityDimensions.fixed(0.3F, 0.3F)).trackRangeChunks(4).trackedUpdateRate(10)
                    .forceTrackedVelocityUpdates(true).build());

    public static final EntityType<SuckedBlockEntity> SUCKED_BLOCK = Registry.register(Registries.ENTITY_TYPE,
            RealityShopMod.id("sucked_block"),
            FabricEntityTypeBuilder.<SuckedBlockEntity>create(SpawnGroup.MISC, SuckedBlockEntity::new)
                    .dimensions(EntityDimensions.fixed(0.9F, 0.9F)).trackRangeChunks(12).trackedUpdateRate(1).build());

    static {
        FabricDefaultAttributeRegistry.register(DERP_CHICKEN, DerpChickenEntity.createAttributes());
    }

    private ModEntities() {}

    public static void init() {}
}
''')

J("net/LaserBurst.java", r'''
package @@PKG@@.net;

import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Tells nearby clients to draw laser beams from {@code origin} to a batch of destroyed blocks (one packet per batch). */
public final class LaserBurst {
    private static final double RANGE = 192.0;
    private static final int MAX_TARGETS = 200;

    private LaserBurst() {}

    public static void send(ServerWorld world, Vec3d origin, List<BlockPos> targets) {
        if (targets.isEmpty()) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeDouble(origin.x);
        buf.writeDouble(origin.y);
        buf.writeDouble(origin.z);
        int n = Math.min(targets.size(), MAX_TARGETS);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            buf.writeLong(targets.get(i).asLong());
        }
        for (ServerPlayerEntity player : PlayerLookup.around(world, origin, RANGE)) {
            ServerPlayNetworking.send(player, ModNetwork.LASER_BURST, PacketByteBufs.copy(buf));
        }
        buf.release();
    }
}
''')

J("entity/DerpChickenEntity.java", r'''
package @@PKG@@.entity;

import @@PKG@@.RSConfig;
import @@PKG@@.registry.ModEntities;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.EscapeDangerGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.World;

/** The glitchy floppy's gift: a derpy rooster. Cheap on the server, rendered from the user-supplied glTF on the client. */
public class DerpChickenEntity extends PathAwareEntity {
    private static int alive;

    public DerpChickenEntity(EntityType<? extends DerpChickenEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return PathAwareEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 6.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.28);
    }

    /** Keeps the global count of live chickens (server side) for the spawn cap. */
    public static void registerEvents() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof DerpChickenEntity) {
                alive++;
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (entity instanceof DerpChickenEntity) {
                alive = Math.max(0, alive - 1);
            }
        });
    }

    public static boolean canSpawnMore() {
        return alive < RSConfig.get().maxDerpChickens;
    }

    public static int aliveCount() {
        return alive;
    }

    /** Spawns up to {@code count} chickens around a point (respecting the cap); returns how many were created. */
    public static int spawnAround(ServerWorld world, double x, double y, double z, int count) {
        int made = 0;
        for (int i = 0; i < count && canSpawnMore(); i++) {
            DerpChickenEntity chicken = ModEntities.DERP_CHICKEN.create(world);
            if (chicken == null) {
                break;
            }
            chicken.refreshPositionAndAngles(x + world.random.nextGaussian() * 0.6, y, z + world.random.nextGaussian() * 0.6,
                    world.random.nextFloat() * 360.0F, 0.0F);
            chicken.setVelocity(world.random.nextGaussian() * 0.15, 0.35 + world.random.nextDouble() * 0.2, world.random.nextGaussian() * 0.15);
            world.spawnEntity(chicken);   // ENTITY_LOAD fires synchronously and counts it
            made++;
        }
        return made;
    }

    @Override
    protected void initGoals() {
        goalSelector.add(0, new SwimGoal(this));
        goalSelector.add(1, new EscapeDangerGoal(this, 1.6));
        goalSelector.add(2, new WanderAroundFarGoal(this, 1.0));
        goalSelector.add(3, new LookAtEntityGoal(this, PlayerEntity.class, 6.0F));
        goalSelector.add(4, new LookAroundGoal(this));
    }

    @Override
    public void tick() {
        super.tick();
        if (getWorld().isClient) {
            return;
        }
        int lifetime = RSConfig.get().derpChickenLifetimeSeconds;
        if (lifetime > 0 && age > lifetime * 20) {
            ((ServerWorld) getWorld()).spawnParticles(ParticleTypes.POOF, getX(), getY() + 0.5, getZ(), 12, 0.3, 0.3, 0.3, 0.02);
            discard();
            return;
        }
        // derp: now and then it hops and squawks an octave too low
        if (age % 90 == 45 && random.nextInt(3) == 0 && isOnGround()) {
            addVelocity(0.0, 0.42, 0.0);
            playSound(SoundEvents.ENTITY_CHICKEN_AMBIENT, 1.0F, 0.5F);
        }
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.ENTITY_CHICKEN_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(net.minecraft.entity.damage.DamageSource source) {
        return SoundEvents.ENTITY_CHICKEN_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ENTITY_CHICKEN_DEATH;
    }
}
''')

J("entity/FloppyProjectileEntity.java", r'''
package @@PKG@@.entity;

import @@PKG@@.RSConfig;
import @@PKG@@.destroy.BoxRegion;
import @@PKG@@.destroy.DestructionManager;
import @@PKG@@.destroy.RegionClearJob;
import @@PKG@@.registry.ModEntities;
import @@PKG@@.registry.ModItems;
import @@PKG@@.util.GlowLights;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.Item;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * The thrown floppy: on impact it destroys a 5x5x5 cube (credited to the thrower, who keeps their footing), leaves a short
 * invisible glow in the hole so it is not black, and rains five DerpChickens.
 */
public class FloppyProjectileEntity extends ThrownItemEntity {
    public FloppyProjectileEntity(EntityType<? extends FloppyProjectileEntity> type, World world) {
        super(type, world);
    }

    public FloppyProjectileEntity(World world, LivingEntity owner) {
        super(ModEntities.FLOPPY_PROJECTILE, owner, world);
    }

    @Override
    protected Item getDefaultItem() {
        return ModItems.GLITCHY_FLOPPY;
    }

    @Override
    protected void onCollision(HitResult hit) {
        super.onCollision(hit);
        if (getWorld().isClient) {
            return;
        }
        BlockPos center;
        if (hit instanceof BlockHitResult blockHit) {
            center = blockHit.getBlockPos();
        } else if (hit instanceof EntityHitResult entityHit) {
            center = entityHit.getEntity().getBlockPos();
        } else {
            center = getBlockPos();
        }
        impact((ServerWorld) getWorld(), center, getOwner());
        discard();
    }

    /** Everything that happens where the floppy lands (public so the game tests can trigger it without throwing anything). */
    public static void impact(ServerWorld world, BlockPos center, @Nullable Entity owner) {
        if (!DestructionManager.isProtected(world)) {
            RegionClearJob job = new RegionClearJob(world, BoxRegion.cube(center, 5)).origin(center);
            if (owner != null) {
                job.owner(owner.getUuid());
                // never take the ground out from under the thrower: the 3x3 columns below their feet stay
                BlockPos feet = owner.getBlockPos();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dy = 1; dy <= 3; dy++) {
                            job.protect(feet.add(dx, -dy, dz));
                        }
                    }
                }
            }
            DestructionManager.runNow(job);
            GlowLights.place(world, center, 14, RSConfig.get().floppyGlowSeconds * 20);
        }
        DerpChickenEntity.spawnAround(world, center.getX() + 0.5, center.getY() + 1.0, center.getZ() + 0.5, 5);
        world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5, 1, 0, 0, 0, 0);
        world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5, 60, 1.2, 1.2, 1.2, 0.3);
        world.playSound(null, center, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 1.2F, 1.5F);
    }
}
''')

J("entity/SuckedBlockEntity.java", r'''
package @@PKG@@.entity;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Purely visual: a block flying up the Beacon 5G tornado in a spiral. Not saved, discarded after a few seconds. */
public class SuckedBlockEntity extends Entity {
    private static final TrackedData<BlockState> STATE = DataTracker.registerData(SuckedBlockEntity.class, TrackedDataHandlerRegistry.BLOCK_STATE);
    private static final int LIFETIME = 110;
    private static int alive;

    private double centerX;
    private double centerZ;
    private double startAngle;
    private double startRadius;
    private double startY;

    public SuckedBlockEntity(EntityType<? extends SuckedBlockEntity> type, World world) {
        super(type, world);
        this.noClip = true;
    }

    public static void registerEvents() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof SuckedBlockEntity) {
                alive++;
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (entity instanceof SuckedBlockEntity) {
                alive = Math.max(0, alive - 1);
            }
        });
    }

    public static int aliveCount() {
        return alive;
    }

    public void launch(BlockPos from, BlockState state, BlockPos tornadoCentre) {
        dataTracker.set(STATE, state);
        centerX = tornadoCentre.getX() + 0.5;
        centerZ = tornadoCentre.getZ() + 0.5;
        double dx = from.getX() + 0.5 - centerX;
        double dz = from.getZ() + 0.5 - centerZ;
        startAngle = Math.atan2(dz, dx);
        startRadius = Math.max(1.0, Math.sqrt(dx * dx + dz * dz));
        startY = from.getY();
        setPosition(from.getX() + 0.5, from.getY(), from.getZ() + 0.5);
    }

    public BlockState getState() {
        return dataTracker.get(STATE);
    }

    @Override
    protected void initDataTracker() {
        dataTracker.startTracking(STATE, Blocks.STONE.getDefaultState());
    }

    @Override
    public void tick() {
        super.tick();
        if (getWorld().isClient) {
            return;
        }
        if (age > LIFETIME) {
            discard();
            return;
        }
        double f = age / (double) LIFETIME;
        double angle = startAngle + age * 0.2;
        double radius = startRadius * (1.0 - f) + 2.5 * f;
        setPosition(centerX + Math.cos(angle) * radius, startY + age * 1.0, centerZ + Math.sin(angle) * radius);
    }

    @Override
    public boolean shouldSave() {
        return false;
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
    }

    @Override
    public Packet<ClientPlayPacketListener> createSpawnPacket() {
        return new EntitySpawnS2CPacket(this);
    }
}
''')

J("item/GlitchPickaxeItem.java", r'''
package @@PKG@@.item;

import @@PKG@@.RSConfig;
import @@PKG@@.destroy.DestructionManager;
import @@PKG@@.economy.ConsumedBlocks;
import java.util.List;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/** Mines everything instantly and takes the surrounding 3x3x3 with it. Every block mined is credited to the player. */
public class GlitchPickaxeItem extends Item {
    private static final ThreadLocal<Boolean> BREAKING = ThreadLocal.withInitial(() -> false);

    public GlitchPickaxeItem(Settings settings) {
        super(settings);
    }

    @Override
    public float getMiningSpeedMultiplier(ItemStack stack, BlockState state) {
        return 100000.0F;   // instant mining, even obsidian
    }

    @Override
    public boolean isSuitableFor(BlockState state) {
        return true;        // drops work for every block
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        PowerTooltips.append("glitch_pickaxe", tooltip);
    }

    public static void registerEvents() {
        PlayerBlockBreakEvents.BEFORE.register(GlitchPickaxeItem::onBeforeBreak);
    }

    /** The block(s) directly below the miner are what they stand on: the 3x3x3 spares them (the targeted block itself is the miner's choice). */
    private static boolean isUnderFeet(ServerPlayerEntity player, BlockPos p) {
        return p.getY() == player.getBlockY() - 1 && Math.abs(p.getX() + 0.5 - player.getX()) < 0.95 && Math.abs(p.getZ() + 0.5 - player.getZ()) < 0.95;
    }

    private static boolean onBeforeBreak(World world, PlayerEntity player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        if (world.isClient || BREAKING.get() || !(player instanceof ServerPlayerEntity sp)
                || !(sp.getMainHandStack().getItem() instanceof GlitchPickaxeItem) || DestructionManager.isProtected(world)) {
            return true;
        }
        BREAKING.set(true);
        try {
            long count = 1;   // the block being broken right now (vanilla finishes it when this returns true)
            boolean drops = RSConfig.get().pickaxeDropsItems;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos p = pos.add(dx, dy, dz);
                        BlockState s = world.getBlockState(p);
                        if (s.isAir() || s.getHardness(world, p) < 0 || isUnderFeet(sp, p)) {
                            continue;
                        }
                        boolean broken = drops ? sp.interactionManager.tryBreakBlock(p) : world.breakBlock(p, false, sp);
                        if (broken) {
                            count++;
                        }
                    }
                }
            }
            ConsumedBlocks.add(sp, count);
        } finally {
            BREAKING.set(false);
        }
        return true;
    }
}
''')

J("item/GlitchyFloppyItem.java", r'''
package @@PKG@@.item;

import @@PKG@@.entity.FloppyProjectileEntity;
import java.util.List;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/** Right-click: throws a floppy that destroys 5x5x5 on impact and spawns five DerpChickens. */
public class GlitchyFloppyItem extends Item {
    public GlitchyFloppyItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient) {
            FloppyProjectileEntity projectile = new FloppyProjectileEntity(world, user);
            projectile.setItem(stack);
            projectile.setVelocity(user, user.getPitch(), user.getYaw(), 0.0F, 1.7F, 0.3F);
            world.spawnEntity(projectile);
            world.playSound(null, user.getBlockPos(), SoundEvents.ENTITY_SNOWBALL_THROW, SoundCategory.PLAYERS, 0.8F, 0.6F);
        }
        user.getItemCooldownManager().set(this, 12);
        return TypedActionResult.success(stack, world.isClient());
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        PowerTooltips.append("glitchy_floppy", tooltip);
    }
}
''')

J("item/CursedKittyPhoneItem.java", r'''
package @@PKG@@.item;

import @@PKG@@.command.RealityShopCommand;
import @@PKG@@.destroy.BoxRegion;
import @@PKG@@.destroy.DestructionManager;
import @@PKG@@.destroy.Front;
import @@PKG@@.destroy.RegionClearJob;
import @@PKG@@.registry.ModSounds;
import java.util.List;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Right-click the ground: a 50 x 20 x 50 pit (16 below .. 3 above the clicked block) disappears at once - in FRONT of you.
 * It starts at the block after the one you stand on, so you keep your footing and never fall into your own hole.
 */
public class CursedKittyPhoneItem extends Item {
    public static final int WIDTH = 50;
    public static final int DEPTH = 50;
    public static final int BELOW = 16;
    public static final int ABOVE = 3;

    public CursedKittyPhoneItem(Settings settings) {
        super(settings);
    }

    /** The pit for a player who clicked {@code ground} (public so the game tests can check the exact shape). */
    public static BoxRegion pit(Front front, BlockPos ground) {
        return front.box(1, DEPTH, WIDTH, ground.getY() - BELOW, ground.getY() + ABOVE);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        World world = context.getWorld();
        PlayerEntity player = context.getPlayer();
        if (world.isClient || !(player instanceof ServerPlayerEntity sp)) {
            return ActionResult.SUCCESS;
        }
        if (DestructionManager.isProtected(world)) {
            sp.sendMessage(Text.literal("Reality here is protected.").formatted(Formatting.RED), true);
            return ActionResult.FAIL;
        }
        BlockPos ground = context.getBlockPos();
        RegionClearJob job = new RegionClearJob((ServerWorld) world, pit(Front.of(sp), ground))
                .owner(sp.getUuid()).origin(sp.getBlockPos());
        DestructionManager.runNow(job);
        world.playSound(null, sp.getBlockPos(), ModSounds.KITTY_PHONE_CURSE, SoundCategory.PLAYERS, 3.0F, 1.0F);
        sp.getItemCooldownManager().set(this, 40);
        sp.sendMessage(Text.literal("+" + RealityShopCommand.fmt(job.cleared()) + " blocks consumed").formatted(Formatting.LIGHT_PURPLE), true);
        return ActionResult.SUCCESS;
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        PowerTooltips.append("cursed_kitty_phone", tooltip);
    }
}
''')

J("item/QuantumUsbItem.java", r'''
package @@PKG@@.item;

import @@PKG@@.RSConfig;
import @@PKG@@.command.RealityShopCommand;
import @@PKG@@.destroy.DestructionManager;
import @@PKG@@.destroy.Front;
import @@PKG@@.destroy.RegionClearJob;
import @@PKG@@.registry.ModSounds;
import @@PKG@@.shop.ShopDimensions;
import java.util.List;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Right-click: a 1475 x 1475 area (500 x 2.95, config usbPurgeSize) over the full world height (clipped to the dimension's
 * build height) is purged over ten seconds - starting right in FRONT of you, so the ground under your feet stays. When it is
 * done you are pulled into the Bedrock Realm. There is no portal back.
 */
public class QuantumUsbItem extends Item {
    public static final int SIZE = 1475;
    public static final int PURGE_TICKS = 200;

    public QuantumUsbItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (world.isClient || !(user instanceof ServerPlayerEntity sp)) {
            return TypedActionResult.success(stack, world.isClient());
        }
        if (DestructionManager.isProtected(world)) {
            sp.sendMessage(Text.literal("Reality here is protected.").formatted(Formatting.RED), true);
            return TypedActionResult.fail(stack);
        }
        ServerWorld sw = (ServerWorld) world;
        BlockPos pos = sp.getBlockPos();
        RegionClearJob job = new RegionClearJob(sw, Front.of(sp).box(1, RSConfig.get().usbPurgeSize, RSConfig.get().usbPurgeSize, sw.getBottomY(), sw.getTopY() - 1))
                .owner(sp.getUuid()).origin(pos).targetTicks(PURGE_TICKS)
                .onProgress(j -> {
                    if (sw.getTime() % 10 == 0) {
                        sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, sp.getX(), sp.getY() + 1, sp.getZ(), 30, 1.5, 1.5, 1.5, 0.2);
                        sp.sendMessage(Text.literal("Quantum purge " + (int) (j.progress() * 100) + "%  -  "
                                + RealityShopCommand.fmt(j.cleared()) + " blocks").formatted(Formatting.AQUA), true);
                    }
                })
                .onFinish(j -> {
                    sp.sendMessage(Text.literal("Purged " + RealityShopCommand.fmt(j.cleared()) + " blocks").formatted(Formatting.GOLD), true);
                    ShopDimensions.enterBedrock(sp);
                });
        DestructionManager.submit(job);
        world.playSound(null, pos, ModSounds.QUANTUM_PURGE, SoundCategory.PLAYERS, 4.0F, 1.0F);
        sp.getItemCooldownManager().set(this, PURGE_TICKS * 2);
        return TypedActionResult.success(stack, false);
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        PowerTooltips.append("quantum_usb", tooltip);
    }
}
''')

J("block/HackedRgbFurnaceBlock.java", r'''
package @@PKG@@.block;

import @@PKG@@.destroy.DestructionManager;
import @@PKG@@.destroy.Front;
import @@PKG@@.registry.ModBlockEntities;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/** Starts eating a 100-block-radius sphere of the world as soon as it is placed, firing green lasers at what it destroys. */
public class HackedRgbFurnaceBlock extends BlockWithEntity {
    public static final DirectionProperty FACING = HorizontalFacingBlock.FACING;

    public HackedRgbFurnaceBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new HackedRgbFurnaceBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return checkType(type, ModBlockEntities.HACKED_RGB_FURNACE, HackedRgbFurnaceBlockEntity::tick);
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (world.isClient || !(placer instanceof ServerPlayerEntity player)) {
            return;
        }
        if (DestructionManager.isProtected(world)) {
            player.sendMessage(Text.literal("Reality here is protected.").formatted(Formatting.RED), true);
            return;
        }
        if (world.getBlockEntity(pos) instanceof HackedRgbFurnaceBlockEntity furnace) {
            furnace.start(player.getUuid(), Front.of(player));
        }
    }

    /** Right-click a finished furnace to run it again. */
    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!world.isClient && hand == Hand.MAIN_HAND && !DestructionManager.isProtected(world)
                && world.getBlockEntity(pos) instanceof HackedRgbFurnaceBlockEntity furnace && furnace.isIdle()) {
            furnace.start(player.getUuid(), Front.of(player));
            return ActionResult.CONSUME;
        }
        return world.isClient ? ActionResult.SUCCESS : ActionResult.PASS;
    }
}
''')

J("block/HackedRgbFurnaceBlockEntity.java", r'''
package @@PKG@@.block;

import @@PKG@@.RSConfig;
import @@PKG@@.destroy.DestroyRegion;
import @@PKG@@.destroy.DestructionManager;
import @@PKG@@.destroy.Front;
import @@PKG@@.destroy.RegionClearJob;
import @@PKG@@.destroy.SphereRegion;
import @@PKG@@.net.LaserBurst;
import @@PKG@@.registry.ModBlockEntities;
import @@PKG@@.registry.ModParticles;
import java.util.UUID;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** Server: runs a 100-block-radius RegionClearJob once (200 ticks) and fires laser bursts at what it eats. */
public class HackedRgbFurnaceBlockEntity extends BlockEntity {
    public static final int RADIUS = 100;
    private static final int TICKS = 200;

    private UUID owner;
    private int frontDir = -1;   // Direction id of the way the owner faced when starting it (-1 = whole sphere)
    private int frontStart;
    private boolean running;
    private RegionClearJob job;

    public HackedRgbFurnaceBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HACKED_RGB_FURNACE, pos, state);
    }

    public void start(UUID owner, Front front) {
        this.owner = owner;
        if (front != null) {
            frontDir = front.dir().getId();
            frontStart = front.start(1);
        } else {
            frontDir = -1;
        }
        setRunning(true);
        job = null;
    }

    /** The half-sphere in front of the owner (so they keep their footing), or the whole sphere if nobody started it. */
    private DestroyRegion region(BlockPos pos) {
        SphereRegion sphere = new SphereRegion(pos, RADIUS);
        return frontDir < 0 ? sphere : Front.clipHalf(sphere, Direction.byId(frontDir), frontStart);
    }

    public boolean isIdle() {
        return !running && job == null;
    }

    public boolean isRunning() {
        return running;
    }

    private void setRunning(boolean value) {
        running = value;
        markDirty();
        if (world != null) {
            world.updateListeners(pos, getCachedState(), getCachedState(), Block.NOTIFY_ALL);
        }
    }

    public static void tick(World world, BlockPos pos, BlockState state, HackedRgbFurnaceBlockEntity be) {
        if (world.isClient) {
            if (be.running && world.getTime() % 3 == 0) {
                // idle glow: a few short green beams shooting up from the furnace
                Vec3d c = Vec3d.ofCenter(pos);
                world.addParticle(ModParticles.GREEN_LASER, c.x, c.y + 0.5, c.z,
                        (world.random.nextDouble() - 0.5) * 2.0, 2.5 + world.random.nextDouble() * 2.0, (world.random.nextDouble() - 0.5) * 2.0);
            }
            return;
        }
        if (!be.running || be.job != null) {
            return;
        }
        ServerWorld sw = (ServerWorld) world;
        Vec3d origin = Vec3d.ofCenter(pos).add(0, 0.2, 0);
        be.job = new RegionClearJob(sw, be.region(pos)).owner(be.owner).origin(pos).protect(pos)
                .targetTicks(RSConfig.get().furnacePurgeSeconds * 20)
                .samples(64, samples -> LaserBurst.send(sw, origin, samples.stream().map(RegionClearJob.Sample::pos).toList()))
                .onFinish(j -> {
                    be.job = null;
                    be.setRunning(false);
                })
                .onCancel(j -> be.job = null);
        DestructionManager.submit(be.job);
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putBoolean("running", running);
        nbt.putInt("frontDir", frontDir);
        nbt.putInt("frontStart", frontStart);
        if (owner != null) {
            nbt.putUuid("owner", owner);
        }
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        running = nbt.getBoolean("running");
        frontDir = nbt.contains("frontDir") ? nbt.getInt("frontDir") : -1;
        frontStart = nbt.getInt("frontStart");
        owner = nbt.containsUuid("owner") ? nbt.getUuid("owner") : null;
    }

    @Override
    public NbtCompound toInitialChunkDataNbt() {
        return createNbt();
    }

    @Override
    public BlockEntityUpdateS2CPacket toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }
}
''')

J("block/Beacon5GBlock.java", r'''
package @@PKG@@.block;

import @@PKG@@.destroy.DestructionManager;
import @@PKG@@.destroy.Front;
import @@PKG@@.registry.ModBlockEntities;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/** A red 5G mast: raises a particle tornado and vacuums up the world in a 200-block radius. */
public class Beacon5GBlock extends BlockWithEntity {
    public Beacon5GBlock(Settings settings) {
        super(settings);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new Beacon5GBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return checkType(type, ModBlockEntities.BEACON_5G, Beacon5GBlockEntity::tick);
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (world.isClient || !(placer instanceof ServerPlayerEntity player)) {
            return;
        }
        if (DestructionManager.isProtected(world)) {
            player.sendMessage(Text.literal("Reality here is protected.").formatted(Formatting.RED), true);
            return;
        }
        if (world.getBlockEntity(pos) instanceof Beacon5GBlockEntity beacon) {
            beacon.start(player.getUuid(), Front.of(player));
        }
    }

    /** Right-click a finished beacon to run it again. */
    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!world.isClient && hand == Hand.MAIN_HAND && !DestructionManager.isProtected(world)
                && world.getBlockEntity(pos) instanceof Beacon5GBlockEntity beacon && beacon.isIdle()) {
            beacon.start(player.getUuid(), Front.of(player));
            return ActionResult.CONSUME;
        }
        return world.isClient ? ActionResult.SUCCESS : ActionResult.PASS;
    }
}
''')

J("block/Beacon5GBlockEntity.java", r'''
package @@PKG@@.block;

import @@PKG@@.RSConfig;
import @@PKG@@.destroy.DestroyRegion;
import @@PKG@@.destroy.DestructionManager;
import @@PKG@@.destroy.Front;
import @@PKG@@.destroy.RegionClearJob;
import @@PKG@@.destroy.SphereRegion;
import @@PKG@@.entity.SuckedBlockEntity;
import @@PKG@@.registry.ModBlockEntities;
import @@PKG@@.registry.ModEntities;
import java.util.UUID;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.joml.Vector3f;

/**
 * Server: vacuums the half of a 200-block-radius sphere that lies in front of whoever started it, slowly (a minute by default,
 * nearest first), and sends a few of the eaten blocks spiralling up.
 * Client: spawns the red particle tornado locally (thousands of dust particles, zero network traffic).
 */
public class Beacon5GBlockEntity extends BlockEntity {
    public static final int RADIUS = 200;
    private static final DustParticleEffect RED_DUST = new DustParticleEffect(new Vector3f(1.0F, 0.04F, 0.04F), 1.6F);

    private UUID owner;
    private int frontDir = -1;   // Direction id of the way the owner faced when starting it (-1 = whole sphere)
    private int frontStart;
    private boolean running;
    private RegionClearJob job;

    public Beacon5GBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.BEACON_5G, pos, state);
    }

    public void start(UUID owner, Front front) {
        this.owner = owner;
        if (front != null) {
            frontDir = front.dir().getId();
            frontStart = front.start(1);
        } else {
            frontDir = -1;
        }
        setRunning(true);
        job = null;
    }

    private DestroyRegion region(BlockPos pos) {
        SphereRegion sphere = new SphereRegion(pos, RADIUS);
        return frontDir < 0 ? sphere : Front.clipHalf(sphere, Direction.byId(frontDir), frontStart);
    }

    public boolean isIdle() {
        return !running && job == null;
    }

    private void setRunning(boolean value) {
        running = value;
        markDirty();
        if (world != null) {
            world.updateListeners(pos, getCachedState(), getCachedState(), Block.NOTIFY_ALL);
        }
    }

    public static void tick(World world, BlockPos pos, BlockState state, Beacon5GBlockEntity be) {
        if (world.isClient) {
            be.clientTornado();
            return;
        }
        if (!be.running || be.job != null) {
            return;
        }
        ServerWorld sw = (ServerWorld) world;
        be.job = new RegionClearJob(sw, be.region(pos)).owner(be.owner).origin(pos).protect(pos)
                .targetTicks(RSConfig.get().beaconPurgeSeconds * 20)
                .samples(18, samples -> {
                    int spawned = 0;
                    for (RegionClearJob.Sample sample : samples) {
                        if (spawned >= 6 || SuckedBlockEntity.aliveCount() >= RSConfig.get().maxSuckedBlocks) {
                            break;
                        }
                        SuckedBlockEntity flying = ModEntities.SUCKED_BLOCK.create(sw);
                        if (flying != null) {
                            flying.launch(sample.pos(), sample.state(), pos);
                            sw.spawnEntity(flying);
                            spawned++;
                        }
                    }
                })
                .onFinish(j -> {
                    be.job = null;
                    be.setRunning(false);
                })
                .onCancel(j -> be.job = null);
        DestructionManager.submit(be.job);
    }

    private void clientTornado() {
        if (!running || world == null) {
            return;
        }
        Random r = world.random;
        long t = world.getTime();
        int count = (int) Math.round(240 * RSConfig.get().particleDensity);
        double bx = pos.getX() + 0.5;
        double by = pos.getY() + 1.0;
        double bz = pos.getZ() + 0.5;
        for (int i = 0; i < count; i++) {
            double h = r.nextDouble();
            double height = h * 140.0;
            double radius = 1.5 + h * h * 26.0 + r.nextDouble() * 1.5;
            double angle = t * 0.11 + height * 0.16 + r.nextDouble() * 0.6;   // half the old spin: slow and heavy
            double sin = Math.sin(angle);
            double cos = Math.cos(angle);
            world.addParticle(RED_DUST, bx + cos * radius, by + height, bz + sin * radius, -sin * 0.4, 0.55, cos * 0.4);
        }
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putBoolean("running", running);
        nbt.putInt("frontDir", frontDir);
        nbt.putInt("frontStart", frontStart);
        if (owner != null) {
            nbt.putUuid("owner", owner);
        }
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        running = nbt.getBoolean("running");
        frontDir = nbt.contains("frontDir") ? nbt.getInt("frontDir") : -1;
        frontStart = nbt.getInt("frontStart");
        owner = nbt.containsUuid("owner") ? nbt.getUuid("owner") : null;
    }

    @Override
    public NbtCompound toInitialChunkDataNbt() {
        return createNbt();
    }

    @Override
    public BlockEntityUpdateS2CPacket toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }
}
''')

J("gametest/RealityShopGameTests.java", r'''
package @@PKG@@.gametest;

import @@PKG@@.RSConfig;
import @@PKG@@.destroy.BoxRegion;
import @@PKG@@.destroy.DestructionManager;
import @@PKG@@.destroy.Front;
import @@PKG@@.destroy.RegionClearJob;
import @@PKG@@.destroy.SphereRegion;
import @@PKG@@.economy.ConsumedBlocks;
import @@PKG@@.entity.DerpChickenEntity;
import @@PKG@@.entity.FloppyProjectileEntity;
import @@PKG@@.item.CursedKittyPhoneItem;
import @@PKG@@.util.GlowLights;
import @@PKG@@.registry.ModBlocks;
import @@PKG@@.registry.ModItems;
import @@PKG@@.shop.BlackMarketRoom;
import @@PKG@@.shop.PortalGateBlock;
import @@PKG@@.shop.PortalGateBlockEntity;
import @@PKG@@.shop.ShopDimensions;
import @@PKG@@.shop.ShopOffer;
import @@PKG@@.shop.ShopPedestalBlockEntity;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import net.minecraft.world.World;

/** Run with ./gradlew runGametest. These exercise the real server code: economy, shop, engine, items, dimensions. */
public class RealityShopGameTests implements FabricGameTest {

    private static void check(TestContext ctx, boolean ok, String message) {
        if (!ok) {
            RealityShopGameTestsLog.info("CHECK FAILED: " + message);
            ctx.throwGameTestException(message);
        }
    }

    /** A chunk-aligned scratch origin with its chunks loaded; every slot is its own isolated area 1024 blocks apart. */
    private static BlockPos scratch(TestContext ctx, int chunks, int slot) {
        ServerWorld world = ctx.getWorld();
        BlockPos base = ctx.getAbsolutePos(BlockPos.ORIGIN);
        int ox = (((base.getX() >> 4) + 2) << 4) + slot * 1024;
        int oz = ((base.getZ() >> 4) + 2) << 4;
        for (int cx = 0; cx < chunks; cx++) {
            for (int cz = 0; cz < chunks; cz++) {
                world.getChunk((ox >> 4) + cx, (oz >> 4) + cz);
                world.setChunkForced((ox >> 4) + cx, (oz >> 4) + cz, true);   // keep them loaded while a paced job runs
            }
        }
        return new BlockPos(ox, world.getBottomY() + 64, oz);
    }

    private static BlockPos scratch(TestContext ctx, int chunks) {
        return scratch(ctx, chunks, 0);
    }

    private static void fill(ServerWorld world, int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    world.setBlockState(new BlockPos(x, y, z), state, 2);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------ economy
    @GameTest(templateName = EMPTY_STRUCTURE)
    public void economyAttachment(TestContext ctx) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        check(ctx, ConsumedBlocks.get(p) == 0, "balance starts at 0");
        ConsumedBlocks.add(p, 5_000);
        check(ctx, ConsumedBlocks.get(p) == 5_000, "add 5000");
        check(ctx, !ConsumedBlocks.spend(p, 6_000) && ConsumedBlocks.get(p) == 5_000, "cannot overspend");
        check(ctx, ConsumedBlocks.spend(p, 2_000) && ConsumedBlocks.get(p) == 3_000, "spend 2000");
        ConsumedBlocks.set(p, Long.MAX_VALUE - 1);
        ConsumedBlocks.add(p, 10);
        check(ctx, ConsumedBlocks.get(p) == Long.MAX_VALUE, "saturates instead of overflowing");
        ConsumedBlocks.set(p, 123_456_789_012L);
        NbtCompound tag = p.writeNbt(new NbtCompound());
        ServerPlayerEntity q = ctx.createMockCreativeServerPlayerInWorld();
        q.readNbt(tag);
        check(ctx, ConsumedBlocks.get(q) == 123_456_789_012L, "balance is persisted in the player's NBT (long, not int)");
        ctx.complete();
    }

    // ------------------------------------------------------------------------------------------ shop
    @GameTest(templateName = EMPTY_STRUCTURE)
    public void pedestalPurchase(TestContext ctx) {
        ServerWorld world = ctx.getWorld();
        BlockPos rel = new BlockPos(1, 1, 1);
        BlackMarketRoom.placePedestal(world, ctx.getAbsolutePos(rel), ShopOffer.CURSED_KITTY_PHONE);
        check(ctx, world.getBlockEntity(ctx.getAbsolutePos(rel)) instanceof ShopPedestalBlockEntity be
                && be.getOffer() == ShopOffer.CURSED_KITTY_PHONE, "pedestal remembers its offer");
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        ConsumedBlocks.set(p, 4_000);
        ctx.useBlock(rel, p);
        check(ctx, ConsumedBlocks.get(p) == 4_000 && p.getInventory().count(ModItems.CURSED_KITTY_PHONE) == 0,
                "too poor (4000 < 5000): nothing is bought");
        ConsumedBlocks.set(p, 6_000);
        ctx.useBlock(rel, p);
        check(ctx, ConsumedBlocks.get(p) == 1_000, "5000 blocks were deducted (balance " + ConsumedBlocks.get(p) + ")");
        check(ctx, p.getInventory().count(ModItems.CURSED_KITTY_PHONE) == 1, "the phone was handed over");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void shopOfferCostsMatchTheSpec(TestContext ctx) {
        long[] expected = {0L, 500L, 5_000L, 50_000L, 300_000L, 1_000_000L};
        ShopOffer[] offers = ShopOffer.values();
        check(ctx, offers.length == 6, "six offers");
        for (int i = 0; i < 6; i++) {
            check(ctx, offers[i].cost == expected[i], offers[i].id + " costs " + expected[i]);
            check(ctx, offers[i].item() != net.minecraft.item.Items.AIR, offers[i].id + " is a registered item");
        }
        ctx.complete();
    }

    /**
     * The vanilla GameTest server builds its world from an EMPTY dimension registry, so data-pack dimensions never exist
     * here (the real teleport round trip is verified on a dedicated server, see tools/verify_server.sh). What we can and do
     * verify here: the four dimension JSON files parse with the game's own registry-aware codecs.
     */
    @GameTest(templateName = EMPTY_STRUCTURE)
    public void dimensionDataPackParsesWithTheRealCodecs(TestContext ctx) {
        net.minecraft.server.MinecraftServer server = ctx.getWorld().getServer();
        com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops =
                net.minecraft.registry.RegistryOps.of(com.mojang.serialization.JsonOps.INSTANCE, server.getRegistryManager());
        for (String name : new String[]{"black_market", "bedrock_realm"}) {
            for (String kind : new String[]{"dimension_type", "dimension"}) {
                net.minecraft.util.Identifier file = new net.minecraft.util.Identifier("@@MODID@@", kind + "/" + name + ".json");
                com.google.gson.JsonElement json;
                try (java.io.Reader reader = server.getResourceManager().getResource(file).orElseThrow().getReader()) {
                    json = com.google.gson.JsonParser.parseReader(reader);
                } catch (Exception e) {
                    ctx.throwGameTestException("cannot read " + file + ": " + e);
                    return;
                }
                com.mojang.serialization.DataResult<?> parsed = kind.equals("dimension")
                        ? net.minecraft.world.dimension.DimensionOptions.CODEC.parse(ops, json)
                        : net.minecraft.world.dimension.DimensionType.CODEC.parse(ops, json);
                check(ctx, parsed.result().isPresent(), file + " does not parse: " + parsed.error().map(e -> e.message()).orElse("?"));
            }
        }
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400)
    public void shopHallBuildsCorrectly(TestContext ctx) {
        ServerWorld world = ctx.getWorld();   // the hall builder works in any world, so test it in the test world
        long t0 = System.nanoTime();
        BlackMarketRoom.build(world);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        RealityShopGameTestsLog.info("shop hall built in " + ms + " ms");
        int fy = BlackMarketRoom.FLOOR_Y;
        check(ctx, BlackMarketRoom.isBuilt(world), "isBuilt() recognises the finished hall");
        check(ctx, world.getBlockState(new BlockPos(0, fy, 0)).isOf(Blocks.PURPLE_STAINED_GLASS), "the consumption well is covered with purple glass");
        check(ctx, world.getBlockState(new BlockPos(0, fy - 3, 0)).isOf(Blocks.SEA_LANTERN), "and lit from far below");
        check(ctx, world.getBlockState(BlackMarketRoom.SPAWN).isAir() && world.getBlockState(BlackMarketRoom.SPAWN.up()).isAir()
                && !world.getBlockState(BlackMarketRoom.SPAWN.down()).isAir(), "the arrival spot is free and has a floor");
        check(ctx, world.getBlockState(new BlockPos(BlackMarketRoom.R, fy + 6, 0)).isOf(Blocks.PURPLE_STAINED_GLASS), "windows in the wall");
        check(ctx, world.getBlockState(new BlockPos(0, BlackMarketRoom.APEX_Y + 1, 0)).isOf(Blocks.PURPLE_STAINED_GLASS), "the dome has its oculus");
        // the dome is an ellipsoid: at 20 blocks from the centre its inside surface is about 10 blocks above the wall top
        int springline = fy + BlackMarketRoom.WALL_H;
        check(ctx, world.getBlockState(new BlockPos(20, springline + 8, 0)).isAir() && !world.getBlockState(new BlockPos(20, springline + 13, 0)).isAir(),
                "the dome shell curves down towards the walls");
        ShopOffer[] seen = new ShopOffer[6];
        for (int i = 0; i < 6; i++) {
            BlockPos p = BlackMarketRoom.pedestalPos(i);
            check(ctx, world.getBlockState(p).isOf(ModBlocks.SHOP_PEDESTAL) && world.getBlockEntity(p) instanceof ShopPedestalBlockEntity, "pedestal " + i + " exists with its block entity");
            seen[i] = ((ShopPedestalBlockEntity) world.getBlockEntity(p)).getOffer();
        }
        check(ctx, new java.util.HashSet<>(java.util.Arrays.asList(seen)).size() == 6, "every one of the six offers has its own pedestal");
        check(ctx, PortalGateBlock.intact(world, BlackMarketRoom.GATE, net.minecraft.util.math.Direction.NORTH), "the exit gate is complete");
        check(ctx, world.getBlockEntity(BlackMarketRoom.GATE) instanceof PortalGateBlockEntity g && g.isPermanent(), "and cannot be picked up");
        java.util.Set<net.minecraft.block.Block> kinds = new java.util.HashSet<>();
        int reach = BlackMarketRoom.R + 4;
        for (int x = -reach; x <= reach; x += 1) {
            for (int z = -reach; z <= reach; z += 1) {
                for (int y = fy - 3; y <= BlackMarketRoom.TOP_Y; y += 1) {
                    BlockState s = world.getBlockState(new BlockPos(x, y, z));
                    if (!s.isAir()) {
                        kinds.add(s.getBlock());
                    }
                }
            }
        }
        check(ctx, kinds.size() >= 16, "the hall is built from many different blocks (" + kinds.size() + " kinds)");
        check(ctx, ms < 8000, "built in a reasonable time (" + ms + " ms)");
        ctx.complete();
    }

    // ------------------------------------------------------------------------------------------ the Reality Gate
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 200)
    public void gatePlacesAndPicksUp(TestContext ctx) {
        ServerWorld world = ctx.getWorld();
        net.minecraft.util.math.Direction facing = net.minecraft.util.math.Direction.EAST;
        BlockPos master = scratch(ctx, 2, 16).add(8, 8, 8);
        fill(world, master.getX() - 4, master.getY(), master.getZ() - 4, master.getX() + 4, master.getY() + 4, master.getZ() + 4, Blocks.AIR.getDefaultState());
        check(ctx, PortalGateBlock.canPlace(world, master, facing, null), "nine free cells: the gate can be placed");
        BlockPos blocker = PortalGateBlock.partPos(master, facing, 2, 2);
        world.setBlockState(blocker, Blocks.STONE.getDefaultState(), 2);
        check(ctx, !PortalGateBlock.canPlace(world, master, facing, null), "a block in one of the nine cells refuses the gate");
        world.setBlockState(blocker, Blocks.AIR.getDefaultState(), 2);

        PortalGateBlock.placeNow(world, master, facing, false);
        check(ctx, PortalGateBlock.intact(world, master, facing), "all nine cells are gate parts");
        check(ctx, world.getBlockEntity(master) instanceof PortalGateBlockEntity be && !be.isPermanent() && !be.isClosing(), "the master has its block entity");
        BlockPos centre = PortalGateBlock.partPos(master, facing, 1, 1);
        BlockState centreState = world.getBlockState(centre);
        check(ctx, PortalGateBlock.isCenter(centreState) && centreState.getCollisionShape(world, centre).isEmpty(), "the middle of the opening is empty: you can walk through");
        check(ctx, centreState.getOutlineShape(world, centre).isEmpty(), "and it has no selection box drawn over the vortex");
        BlockPos side = PortalGateBlock.partPos(master, facing, 0, 1);
        check(ctx, !world.getBlockState(side).getCollisionShape(world, side).isEmpty(), "the side bars are solid");
        BlockPos top = PortalGateBlock.partPos(master, facing, 1, 2);
        net.minecraft.util.shape.VoxelShape topBar = world.getBlockState(top).getCollisionShape(world, top);
        check(ctx, !topBar.isEmpty() && topBar.getMin(net.minecraft.util.math.Direction.Axis.Y) > 0.7, "the top bar sits at the top of its cell, the opening below it is free");
        BlockPos floorBar = master;
        net.minecraft.util.shape.VoxelShape threshold = world.getBlockState(floorBar).getCollisionShape(world, floorBar);
        check(ctx, !threshold.isEmpty() && threshold.getMax(net.minecraft.util.math.Direction.Axis.Y) <= 0.26, "the threshold is a low step (" + threshold.getMax(net.minecraft.util.math.Direction.Axis.Y) + ")");
        net.minecraft.util.shape.VoxelShape sideShape = world.getBlockState(side).getCollisionShape(world, side);
        double sideThickness = Math.min(sideShape.getMax(net.minecraft.util.math.Direction.Axis.X) - sideShape.getMin(net.minecraft.util.math.Direction.Axis.X),
                sideShape.getMax(net.minecraft.util.math.Direction.Axis.Z) - sideShape.getMin(net.minecraft.util.math.Direction.Axis.Z));
        check(ctx, sideThickness > 0.2 && sideThickness < 0.3, "the frame is THIN: a side bar is " + sideThickness + " blocks wide in the plane of the gate");

        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        int itemsBefore = p.getInventory().count(ModItems.BLACK_MARKET_PORTAL);   // the mock player got the starter gate when it "joined"
        PortalGateBlockEntity gate = (PortalGateBlockEntity) world.getBlockEntity(master);
        check(ctx, gate.requestClose(p), "right-click folds the gate up");
        check(ctx, gate.isClosing() && !gate.requestClose(p), "it is closing and cannot be closed twice");
        ctx.runAtTick(PortalGateBlockEntity.CLOSE_TICKS + 6, () -> {
            check(ctx, !PortalGateBlock.intact(world, master, facing) && world.getBlockState(master).isAir()
                    && world.getBlockState(PortalGateBlock.partPos(master, facing, 1, 2)).isAir(), "after the collapse all nine cells are gone");
            int items = p.getInventory().count(ModItems.BLACK_MARKET_PORTAL) - itemsBefore;
            int dropped = world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(master).expand(6.0),
                    e -> e.getStack().isOf(ModItems.BLACK_MARKET_PORTAL)).size();
            check(ctx, items + dropped == 1, "exactly one gate item came back (inventory " + items + ", dropped " + dropped + ")");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 200)
    public void walkingIntoTheGateActivatesIt(TestContext ctx) {
        ServerWorld world = ctx.getWorld();
        net.minecraft.util.math.Direction facing = net.minecraft.util.math.Direction.SOUTH;
        BlockPos master = scratch(ctx, 2, 16).add(8, 12, 8);
        fill(world, master.getX() - 4, master.getY() - 1, master.getZ() - 4, master.getX() + 4, master.getY() + 4, master.getZ() + 4, Blocks.AIR.getDefaultState());
        PortalGateBlock.placeNow(world, master, facing, false);
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        long before = ShopDimensions.gateUses;
        ctx.runAtTick(30, () -> p.setPosition(master.getX() + 0.5, master.getY(), master.getZ() + 0.5 - 0.4));   // the gate is open by now: step into the swirl ...
        ctx.runAtTick(32, () -> p.setPosition(master.getX() + 0.5, master.getY(), master.getZ() + 0.5 + 1.4));    // ... and keep walking: out the other side
        ctx.runAtTick(30 + PortalGateBlockEntity.ENTER_TICKS + 6, () -> {
            check(ctx, ShopDimensions.gateUses > before, "touching the swirl used the gate even though the player walked on");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 200)
    public void gateAssemblesThenOpens(TestContext ctx) {
        ServerWorld world = ctx.getWorld();
        net.minecraft.util.math.Direction facing = net.minecraft.util.math.Direction.WEST;
        BlockPos master = scratch(ctx, 2, 16).add(8, 8, 8);
        fill(world, master.getX() - 4, master.getY(), master.getZ() - 4, master.getX() + 4, master.getY() + 4, master.getZ() + 4, Blocks.AIR.getDefaultState());
        PortalGateBlock.place(world, master, facing);
        check(ctx, world.getBlockState(master).getBlock() instanceof PortalGateBlock, "the first piece (the master) is there at once");
        check(ctx, !PortalGateBlock.intact(world, master, facing), "the rest is still assembling");
        ctx.runAtTick(PortalGateBlock.ASSEMBLY_TICKS - 1, () -> {
            check(ctx, PortalGateBlock.intact(world, master, facing), "after the assembly all nine cells stand");
            PortalGateBlockEntity be = (PortalGateBlockEntity) world.getBlockEntity(master);
            check(ctx, be.openProgress(world.getTime(), 0.0f) < 0.05f, "the vortex has not started to open yet");
        });
        ctx.runAtTick(PortalGateBlock.ASSEMBLY_TICKS + PortalGateBlockEntity.OPEN_TICKS + 3, () -> {
            PortalGateBlockEntity be = (PortalGateBlockEntity) world.getBlockEntity(master);
            check(ctx, be.openProgress(world.getTime(), 0.0f) >= 0.99f, "and then it tears open");
            check(ctx, PortalGateBlock.intact(world, master, facing), "the gate is still whole (the validator does not mistake the assembly for damage)");
            ctx.complete();
        });
    }

    // ------------------------------------------------------------------------------------------ destruction engine
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 200)
    public void boxClearCountsCreditsAndProtects(TestContext ctx) {
        ServerWorld world = ctx.getWorld();
        BlockPos o = scratch(ctx, 4, 1);
        int x0 = o.getX() + 3;
        int y0 = o.getY();
        int z0 = o.getZ() + 3;
        int x1 = x0 + 45;
        int y1 = y0 + 21;
        int z1 = z0 + 45;
        fill(world, x0 - 1, y0, z0, x1 + 1, y1, z1, Blocks.STONE.getDefaultState());   // 1-block margin of stone around the region
        BlockPos chest = new BlockPos(x0 + 20, y0 + 5, z0 + 20);
        world.setBlockState(chest, Blocks.CHEST.getDefaultState(), 2);
        BlockPos bedrock = new BlockPos(x0 + 30, y0 + 9, z0 + 30);
        world.setBlockState(bedrock, Blocks.BEDROCK.getDefaultState(), 2);
        BlockPos keep = new BlockPos(x0 + 10, y0 + 10, z0 + 10);
        long total = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);

        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        ConsumedBlocks.set(p, 0);
        long t0 = System.nanoTime();
        RegionClearJob job = new RegionClearJob(world, new BoxRegion(x0, y0, z0, x1, y1, z1)).owner(p.getUuid()).protect(keep);
        DestructionManager.runNow(job);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        long expected = total - 2;   // the protected block and the bedrock survive
        check(ctx, job.cleared() == expected, "cleared " + job.cleared() + " blocks, expected " + expected);
        check(ctx, ConsumedBlocks.get(p) == expected, "the owner was credited " + ConsumedBlocks.get(p));
        check(ctx, world.getBlockState(keep).isOf(Blocks.STONE), "protected position survived");
        check(ctx, world.getBlockState(bedrock).isOf(Blocks.BEDROCK), "bedrock survived (destroyBedrock=false)");
        check(ctx, world.getBlockEntity(chest) == null && world.getBlockState(chest).isAir(), "block entity inside a cleared section was discarded");
        check(ctx, world.getBlockState(new BlockPos(x0 + 7, y0 + 3, z0 + 7)).isAir(), "an interior block is air");
        check(ctx, world.getBlockState(new BlockPos(x0 - 1, y0 + 3, z0 + 7)).isOf(Blocks.STONE), "the margin outside the region is untouched");
        check(ctx, world.getBlockState(new BlockPos(x1 + 1, y1, z1)).isOf(Blocks.STONE), "the far margin is untouched");
        RealityShopGameTestsLog.info("boxClear: " + total + " blocks in " + ms + " ms");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 200)
    public void sphereClearCountsExactly(TestContext ctx) {
        ServerWorld world = ctx.getWorld();
        BlockPos o = scratch(ctx, 4, 2);
        BlockPos centre = o.add(24, 24, 24);
        int r = 18;
        fill(world, centre.getX() - r - 1, centre.getY() - r - 1, centre.getZ() - r - 1,
                centre.getX() + r + 1, centre.getY() + r + 1, centre.getZ() + r + 1, Blocks.STONE.getDefaultState());
        SphereRegion sphere = new SphereRegion(centre, r);
        long inside = 0;
        for (int x = centre.getX() - r - 1; x <= centre.getX() + r + 1; x++) {
            for (int y = centre.getY() - r - 1; y <= centre.getY() + r + 1; y++) {
                for (int z = centre.getZ() - r - 1; z <= centre.getZ() + r + 1; z++) {
                    if (sphere.contains(x, y, z)) {
                        inside++;
                    }
                }
            }
        }
        RegionClearJob job = new RegionClearJob(world, sphere).origin(centre);
        DestructionManager.runNow(job);
        check(ctx, job.cleared() == inside, "sphere cleared " + job.cleared() + ", expected " + inside);
        check(ctx, world.getBlockState(centre).isAir(), "centre is air");
        check(ctx, world.getBlockState(centre.add(r + 1, 0, 0)).isOf(Blocks.STONE), "just outside the radius is stone");
        check(ctx, world.getBlockState(centre.add(0, -(r + 1), 0)).isOf(Blocks.STONE), "just below the sphere is stone");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300)
    public void pacedPurgeFinishesWithinItsPace(TestContext ctx) {
        ServerWorld world = ctx.getWorld();
        BlockPos o = scratch(ctx, 8, 3);
        int half = 56;
        int cx = o.getX() + 64;
        int cz = o.getZ() + 64;
        int y = world.getBottomY() + 4;
        BoxRegion region = new BoxRegion(cx - half, world.getBottomY(), cz - half, cx + half - 1, world.getTopY() - 1, cz + half - 1);
        // stone columns that reach into the sky, replacing the flat terrain (and its bedrock) underneath them
        fill(world, cx - 8, y - 4, cz - 8, cx + 7, y + 40, cz + 7, Blocks.STONE.getDefaultState());
        // two bedrock markers outside the columns (the world may have holes from other tests): the purge must leave them alone
        world.setBlockState(new BlockPos(cx + 20, world.getBottomY(), cz), Blocks.BEDROCK.getDefaultState(), 2);
        world.setBlockState(new BlockPos(cx - 30, world.getBottomY() + 1, cz + 12), Blocks.BEDROCK.getDefaultState(), 2);
        RegionClearJob[] holder = new RegionClearJob[1];
        ctx.runAtTick(10, () -> {
            holder[0] = new RegionClearJob(world, region).origin(new BlockPos(cx, y, cz)).targetTicks(30);
            DestructionManager.submit(holder[0]);
            check(ctx, !holder[0].isFinished(), "a paced job does not finish instantly");
        });
        ctx.runAtTick(200, () -> {
            RegionClearJob job = holder[0];
            check(ctx, job.isFinished(), "purge finished within its pace (progress " + job.progress() + ")");
            check(ctx, job.skippedColumns() == 0, "no loaded column was skipped");
            check(ctx, world.getBlockState(new BlockPos(cx, y, cz)).isAir(), "centre of the purge is air");
            check(ctx, world.getBlockState(new BlockPos(cx + 20, world.getBottomY(), cz)).isOf(Blocks.BEDROCK)
                    && world.getBlockState(new BlockPos(cx - 30, world.getBottomY() + 1, cz + 12)).isOf(Blocks.BEDROCK), "bedrock survives a purge (destroyBedrock=false)");
            ctx.complete();
        });
    }

    // ------------------------------------------------------------------------------------------ lighting regression
    /**
     * Bulk-clearing chunk sections bypasses the per-block light updates, so the engine repairs light itself. This runs
     * alone in its own batch (the light thread handles ~1000 tasks per tick; other tests queue hundreds of thousands) and
     * requires the resulting sky light to equal what vanilla setBlockState produces in the same situation:
     *   A) a chunk-aligned stone shaft above the terrain,  B) un-aligned stone columns overwriting terrain and bedrock.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 500, batchId = "lightBatch")
    public void bulkClearLightMatchesVanilla(TestContext ctx) {
        ServerWorld world = ctx.getWorld();
        int bottom = world.getBottomY();
        long resyncedBefore = DestructionManager.resyncedChunks();
        // slot -> {x0, y0, z0, y1}: (A engine, A vanilla, B engine, B vanilla)
        int[][] spec = new int[4][];
        for (int i = 0; i < 4; i++) {
            BlockPos o = scratch(ctx, 3, 10 + i);
            boolean aligned = i < 2;
            int x0 = o.getX() + (aligned ? 16 : 16 + 5);
            int z0 = o.getZ() + (aligned ? 16 : 16 + 3);
            int y0 = aligned ? bottom + 16 : bottom;
            spec[i] = new int[]{x0, y0, z0, y0 + 47};
            fill(world, x0, y0, z0, x0 + 15, y0 + 47, z0 + 15, Blocks.STONE.getDefaultState());
        }
        ctx.runAtTick(120, () -> {
            for (int i = 0; i < 4; i++) {
                int[] c = spec[i];
                if (i % 2 == 0) {
                    DestructionManager.runNow(new RegionClearJob(world, new BoxRegion(c[0], c[1], c[2], c[0] + 15, c[3], c[2] + 15)));
                } else {
                    fill(world, c[0], c[1], c[2], c[0] + 15, c[3], c[2] + 15, Blocks.AIR.getDefaultState());
                }
            }
        });
        ctx.runAtTick(400, () -> {
            for (int scenario = 0; scenario < 2; scenario++) {
                int[] e = spec[scenario * 2];
                int[] v = spec[scenario * 2 + 1];
                StringBuilder row = new StringBuilder();
                for (int k : new int[]{0, 2, 8, 14, 15, 16, 20, 30, 47}) {
                    int mine = world.getLightLevel(LightType.SKY, new BlockPos(e[0] + 8, e[1] + k, e[2] + 8));
                    int ref = world.getLightLevel(LightType.SKY, new BlockPos(v[0] + 8, v[1] + k, v[2] + 8));
                    row.append(k).append(":engine=").append(mine).append("/vanilla=").append(ref).append(' ');
                }
                RealityShopGameTestsLog.info("light scenario " + (scenario == 0 ? "A(aligned shaft)" : "B(terrain overwritten)") + " " + row);
                for (int k : new int[]{0, 2, 8, 14, 15, 16, 20, 30, 47}) {
                    int mine = world.getLightLevel(LightType.SKY, new BlockPos(e[0] + 8, e[1] + k, e[2] + 8));
                    int ref = world.getLightLevel(LightType.SKY, new BlockPos(v[0] + 8, v[1] + k, v[2] + 8));
                    check(ctx, mine == ref, "scenario " + scenario + ": sky light " + k + " blocks up the shaft: engine " + mine + " vs vanilla " + ref);
                }
            }
            long resynced = DestructionManager.resyncedChunks() - resyncedBefore;
            check(ctx, resynced >= 2 && DestructionManager.pendingResyncs() == 0,
                    "the light-settled chunk re-send ran for the cleared chunks (" + resynced + " re-sent, " + DestructionManager.pendingResyncs() + " pending)");
            ctx.complete();
        });
    }

    // ------------------------------------------------------------------------------------------ items & entities
    @GameTest(templateName = EMPTY_STRUCTURE)
    public void glitchPickaxeMinesThreeByThreeAndCredits(TestContext ctx) {
        ServerWorld world = ctx.getWorld();
        BlockPos o = scratch(ctx, 2, 4);
        BlockPos c = o.add(8, 8, 8);
        fill(world, c.getX() - 2, c.getY() - 2, c.getZ() - 2, c.getX() + 2, c.getY() + 2, c.getZ() + 2, Blocks.STONE.getDefaultState());
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.setStackInHand(Hand.MAIN_HAND, new ItemStack(ModItems.GLITCH_PICKAXE));
        ConsumedBlocks.set(p, 0);
        boolean broke = p.interactionManager.tryBreakBlock(c);
        int left = 0;
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    if (world.getBlockState(c.add(x, y, z)).isOf(Blocks.STONE)) {
                        left++;
                    }
                }
            }
        }
        check(ctx, broke, "the centre block broke");
        check(ctx, left == 125 - 27, "a 3x3x3 area was mined (" + left + " stone left of 125)");
        check(ctx, ConsumedBlocks.get(p) == 27, "27 blocks credited (got " + ConsumedBlocks.get(p) + ")");
        check(ctx, new ItemStack(ModItems.GLITCH_PICKAXE).getMiningSpeedMultiplier(Blocks.OBSIDIAN.getDefaultState()) > 1000.0F, "mines obsidian instantly");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 100)
    public void kittyPhonePitIsInFrontOfYou(TestContext ctx) {
        ServerWorld world = ctx.getWorld();
        BlockPos o = scratch(ctx, 5, 5);
        BlockPos feet = o.add(40, 20, 12);
        BlockPos ground = feet.down();
        // geometry for all four facings: 50 x 20 x 50, starts right after the player's block, never contains their column
        for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            Front front = new Front(d, feet);
            BoxRegion pit = CursedKittyPhoneItem.pit(front, ground);
            boolean alongX = d.getAxis() == net.minecraft.util.math.Direction.Axis.X;
            int lenX = pit.maxX() - pit.minX() + 1;
            int lenZ = pit.maxZ() - pit.minZ() + 1;
            check(ctx, lenX == 50 && lenZ == 50 && (pit.maxY() - pit.minY() + 1) == 20, d + ": the pit is 50 x 20 x 50");
            check(ctx, pit.minY() == ground.getY() - 16 && pit.maxY() == ground.getY() + 3, d + ": 16 below .. 3 above the clicked block");
            for (int y = pit.minY(); y <= pit.maxY(); y++) {
                check(ctx, !pit.contains(feet.getX(), y, feet.getZ()), d + ": the player's own column is never part of the pit");
            }
            int first = switch (d) {
                case SOUTH -> pit.minZ();
                case NORTH -> pit.maxZ();
                case EAST -> pit.minX();
                default -> pit.maxX();
            };
            int own = alongX ? feet.getX() : feet.getZ();
            check(ctx, Math.abs(first - own) == 1, d + ": the pit begins right behind the player's block (first " + first + ", player " + own + ")");
            int lateral = alongX ? feet.getZ() : feet.getX();
            int lo = alongX ? pit.minZ() : pit.minX();
            int hi = alongX ? pit.maxZ() : pit.maxX();
            check(ctx, lo <= lateral - 24 && hi >= lateral + 24, d + ": the pit is centred on the player's line");
        }
        // and the real thing: a stone footing under the player must survive the removal of 50,000 blocks
        Front south = new Front(net.minecraft.util.math.Direction.SOUTH, feet);
        BoxRegion region = CursedKittyPhoneItem.pit(south, ground);
        fill(world, region.minX(), region.minY(), region.minZ(), region.maxX(), region.maxY(), region.maxZ(), Blocks.DIRT.getDefaultState());
        fill(world, feet.getX() - 2, ground.getY() - 16, feet.getZ() - 1, feet.getX() + 2, ground.getY(), feet.getZ(), Blocks.STONE.getDefaultState());
        RegionClearJob job = new RegionClearJob(world, region).origin(feet);
        long t0 = System.nanoTime();
        DestructionManager.runNow(job);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check(ctx, job.cleared() == 50_000L, "50,000 blocks removed in one go (got " + job.cleared() + ")");
        check(ctx, ms < 3000, "removed 'instantly' (took " + ms + " ms)");
        check(ctx, world.getBlockState(ground).isOf(Blocks.STONE) && world.getBlockState(ground.down(5)).isOf(Blocks.STONE),
                "the ground and the pillar under the player are still there - nobody falls");
        check(ctx, world.getBlockState(ground.south()).isAir(), "the block in front of the player is gone");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 200)
    public void halfSphereInFrontIsExact(TestContext ctx) {
        ServerWorld world = ctx.getWorld();
        BlockPos o = scratch(ctx, 4, 14);
        BlockPos c = o.add(32, 24, 32);
        int r = 20;
        fill(world, c.getX() - r - 1, c.getY() - r - 1, c.getZ() - r - 1, c.getX() + r + 1, c.getY() + r + 1, c.getZ() + r + 1, Blocks.STONE.getDefaultState());
        // the player stands 5 blocks west of the sphere's centre and faces east: only x >= (player + 1) may go
        Front front = new Front(net.minecraft.util.math.Direction.EAST, c.west(5));
        int start = front.start(1);
        SphereRegion sphere = new SphereRegion(c, r);
        long expected = 0;
        for (int x = c.getX() - r; x <= c.getX() + r; x++) {
            for (int y = c.getY() - r; y <= c.getY() + r; y++) {
                for (int z = c.getZ() - r; z <= c.getZ() + r; z++) {
                    if (x >= start && sphere.contains(x, y, z)) {
                        expected++;
                    }
                }
            }
        }
        RegionClearJob job = new RegionClearJob(world, front.clip(sphere, 1)).origin(c);
        DestructionManager.runNow(job);
        check(ctx, job.cleared() == expected, "front half-sphere: cleared " + job.cleared() + ", expected " + expected);
        check(ctx, world.getBlockState(new BlockPos(start - 1, c.getY(), c.getZ())).isOf(Blocks.STONE), "the plane behind the player is untouched");
        check(ctx, world.getBlockState(new BlockPos(start, c.getY(), c.getZ())).isAir(), "the first block in front is gone");
        check(ctx, world.getBlockState(new BlockPos(c.getX() - r, c.getY(), c.getZ())).isOf(Blocks.STONE), "the far side of the sphere (behind) is untouched");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 120)
    public void floppyImpactKeepsTheFootingAndLeavesAGlow(TestContext ctx) {
        ServerWorld world = ctx.getWorld();
        BlockPos o = scratch(ctx, 2, 15);
        BlockPos c = o.add(8, 8, 8);
        fill(world, c.getX() - 6, c.getY() - 6, c.getZ() - 6, c.getX() + 6, c.getY() + 6, c.getZ() + 6, Blocks.STONE.getDefaultState());
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.setPosition(c.getX() + 2.5, c.getY() + 3.0, c.getZ() + 0.5);   // stands on top of the cube's east edge
        ConsumedBlocks.set(p, 0);
        FloppyProjectileEntity.impact(world, c, p);
        int air = 0;
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    BlockState s = world.getBlockState(c.add(x, y, z));
                    if (s.isAir() || s.isOf(Blocks.LIGHT)) {
                        air++;
                    }
                }
            }
        }
        check(ctx, air == 125 - 18, "the cube minus the thrower's 3x3x3 footing was erased (" + air + " of 125 are open)");
        check(ctx, world.getBlockState(c.add(2, 1, 0)).isOf(Blocks.STONE), "the block under the thrower's feet is still there");
        check(ctx, world.getBlockState(c).isOf(Blocks.LIGHT), "a glow light sits in the hole");
        check(ctx, ConsumedBlocks.get(p) == 125 - 18, "credited " + ConsumedBlocks.get(p));
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 160)
    public void glowLightExpires(TestContext ctx) {
        ServerWorld world = ctx.getWorld();
        BlockPos pos = scratch(ctx, 1, 15).add(3, 3, 3);
        world.setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
        GlowLights.place(world, pos, 12, 40);
        check(ctx, world.getBlockState(pos).isOf(Blocks.LIGHT), "the glow light is placed");
        ctx.runAtTick(100, () -> {
            check(ctx, world.getBlockState(pos).isAir(), "the glow light removed itself again");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void derpChickensAreCapped(TestContext ctx) {
        ServerWorld world = ctx.getWorld();
        BlockPos at = ctx.getAbsolutePos(new BlockPos(4, 2, 4));
        int oldCap = RSConfig.get().maxDerpChickens;
        try {
            RSConfig.get().maxDerpChickens = DerpChickenEntity.aliveCount() + 3;
            int made = DerpChickenEntity.spawnAround(world, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 5);
            check(ctx, made == 3, "the cap limited five requested chickens to three (made " + made + ")");
            RSConfig.get().maxDerpChickens = DerpChickenEntity.aliveCount() + 10;
            made = DerpChickenEntity.spawnAround(world, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 5);
            check(ctx, made == 5, "without a cap issue five chickens spawn (made " + made + ")");
        } finally {
            RSConfig.get().maxDerpChickens = oldCap;
        }
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void allSixItemsAndTheirBlocksAreRegistered(TestContext ctx) {
        Item[] items = {ModItems.GLITCH_PICKAXE, ModItems.GLITCHY_FLOPPY, ModItems.CURSED_KITTY_PHONE,
                ModItems.HACKED_RGB_FURNACE, ModItems.BEACON_5G, ModItems.QUANTUM_USB};
        for (Item item : items) {
            check(ctx, item != net.minecraft.item.Items.AIR, "item registered");
        }
        check(ctx, ModItems.HACKED_RGB_FURNACE instanceof net.minecraft.item.BlockItem, "furnace is placeable");
        check(ctx, ModItems.BEACON_5G instanceof net.minecraft.item.BlockItem, "beacon is placeable");
        ctx.complete();
    }

    /** Tiny logger shim so test timings show up in the game test output. */
    private static final class RealityShopGameTestsLog {
        static void info(String message) {
            @@PKG@@.RealityShopMod.LOGGER.info("[gametest] {}", message);
        }
    }
}
''')

J("client/gltf/GltfModel.java", r'''
package @@PKG@@.client.gltf;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.util.Identifier;

/** A glTF/GLB model uploaded to the GPU exactly as authored (plus decimated distance LODs built from it at load time). */
public final class GltfModel {
    public static final class Material {
        public Identifier baseColor;
        public Identifier normal;
        public Identifier metalRough;
        public final float[] baseColorFactor = {1f, 1f, 1f, 1f};
        public float metallic = 0.0f;
        public float roughness = 0.85f;
        public boolean doubleSided = true;
        /** > 0 for alphaMode MASK: fragments below it are discarded. */
        public float alphaCutoff = 0.0f;
    }

    public static final class Primitive {
        public VertexBuffer buffer;
        public int vertexCount;
        public int material;
        /** Decimated copies for far-away drawing (index 0 = lod 1 ...), built at load time from the full mesh; empty for small meshes. */
        public VertexBuffer[] lodBuffers = new VertexBuffer[0];
        public int[] lodVertexCounts = new int[0];
    }

    public final String name;
    public final List<Primitive> primitives = new ArrayList<>();
    public final List<Material> materials = new ArrayList<>();
    final List<Identifier> textureIds = new ArrayList<>();
    /** Bounds of the whole model in model space (after the glTF node transforms), as authored. */
    public final float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
    public final float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
    public int triangleCount;

    GltfModel(String name) {
        this.name = name;
    }

    public float width() { return max[0] - min[0]; }
    public float height() { return max[1] - min[1]; }
    public float depth() { return max[2] - min[2]; }
    public float centerX() { return (min[0] + max[0]) * 0.5f; }
    public float centerY() { return (min[1] + max[1]) * 0.5f; }
    public float centerZ() { return (min[2] + max[2]) * 0.5f; }
    public float longestSide() { return Math.max(width(), Math.max(height(), depth())); }

    public void close() {
        for (Primitive p : primitives) {
            if (p.buffer != null) {
                p.buffer.close();
            }
            for (VertexBuffer lod : p.lodBuffers) {
                lod.close();
            }
        }
        for (Identifier id : textureIds) {
            MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
        }
        primitives.clear();
        textureIds.clear();
    }
}
''', client=True)

J("client/gltf/MipTexture.java", r'''
package @@PKG@@.client.gltf;

import com.mojang.blaze3d.platform.TextureUtil;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.resource.ResourceManager;
import org.lwjgl.opengl.GL30;

/**
 * A texture uploaded at its full original size (8K works if the GPU allows it) with generated mipmaps and trilinear
 * filtering. Used for the glTF textures and for the HUD atlas, which are both minified a lot.
 */
public final class MipTexture extends AbstractTexture {
    private final NativeImage image;

    public MipTexture(NativeImage image) {
        this.image = image;
    }

    @Override
    public void load(ResourceManager manager) {
        // uploaded eagerly by upload()
    }

    /** Uploads the image (and closes it). Call after the texture has been registered with the TextureManager. */
    public void upload() {
        int w = image.getWidth();
        int h = image.getHeight();
        int levels = Math.max(0, Math.min(8, 31 - Integer.numberOfLeadingZeros(Math.min(w, h)) - 1));
        TextureUtil.prepareImage(getGlId(), levels, w, h);
        bindTexture();
        image.upload(0, 0, 0, 0, 0, w, h, false, true);   // level 0; the image is closed after the upload
        setFilter(true, true);                              // linear + mipmaps
        if (levels > 0) {
            GL30.glGenerateMipmap(3553);
        }
    }
}
''', client=True)

J("client/gltf/GltfLoader.java", r'''
package @@PKG@@.client.gltf;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import @@PKG@@.RealityShopMod;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Reads glTF 2.0 (.gltf + .bin + images, or binary .glb) straight from the mod's resources
 * (assets/@@MODID@@/models/gltf/...). The geometry is used exactly as authored: node transforms are applied
 * (that is what glTF means), nothing is decimated, re-meshed or re-UV'd, and textures keep their resolution.
 *
 * Supported: meshes of triangles with POSITION / NORMAL / TANGENT / TEXCOORD_0 / indices of any component type,
 * strided buffers, the node hierarchy (matrix or TRS), PBR metallic-roughness materials with base colour, normal and
 * metallic-roughness maps, alphaMode MASK, doubleSided. Missing normals / tangents are generated. Not supported:
 * skins, animations, sparse accessors, additional UV sets (TEXCOORD_0 is used for every map).
 */
public final class GltfLoader {
    private static final int COMPONENT_FLOAT = 5126;
    private static boolean warnedUvSets;

    private GltfLoader() {}

    /** Loads models/gltf/{name}/scene.gltf (or scene.glb, {name}.glb/.gltf, ...) - a drop-in folder per model. */
    public static GltfModel load(ResourceManager resources, String name) throws IOException {
        String[] candidates = {name + "/scene.gltf", name + "/scene.glb", name + "/" + name + ".gltf", name + "/" + name + ".glb",
                name + ".glb", name + ".gltf"};
        for (String rel : candidates) {
            Identifier id = new Identifier(RealityShopMod.MODID, "models/gltf/" + rel);
            Optional<Resource> resource = resources.getResource(id);
            if (resource.isPresent()) {
                try (InputStream in = resource.get().getInputStream()) {
                    return parse(resources, name, id, in.readAllBytes());
                }
            }
        }
        throw new FileNotFoundException("no glTF model '" + name + "' under assets/" + RealityShopMod.MODID + "/models/gltf/");
    }

    // ------------------------------------------------------------------------------------------------ parsing
    private static GltfModel parse(ResourceManager resources, String name, Identifier file, byte[] bytes) throws IOException {
        JsonObject json;
        byte[] glbBin = null;
        if (bytes.length >= 12 && bytes[0] == 'g' && bytes[1] == 'l' && bytes[2] == 'T' && bytes[3] == 'F') {
            ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int pos = 12;
            String jsonText = null;
            while (pos + 8 <= bytes.length) {
                int length = bb.getInt(pos);
                int type = bb.getInt(pos + 4);
                if (type == 0x4E4F534A) {
                    jsonText = new String(bytes, pos + 8, length, StandardCharsets.UTF_8);
                } else if (type == 0x004E4942 && glbBin == null) {
                    glbBin = java.util.Arrays.copyOfRange(bytes, pos + 8, pos + 8 + length);
                }
                pos += 8 + length;
            }
            if (jsonText == null) {
                throw new IOException(file + ": GLB without a JSON chunk");
            }
            json = JsonParser.parseString(jsonText).getAsJsonObject();
        } else {
            json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        String dir = file.getPath().substring(0, file.getPath().lastIndexOf('/') + 1);
        String namespace = file.getNamespace();

        // buffers
        List<ByteBuffer> buffers = new ArrayList<>();
        for (JsonElement el : array(json, "buffers")) {
            JsonObject b = el.getAsJsonObject();
            byte[] data;
            if (b.has("uri")) {
                data = readUri(resources, namespace, dir, b.get("uri").getAsString());
            } else if (glbBin != null) {
                data = glbBin;
            } else {
                throw new IOException(file + ": buffer without uri");
            }
            buffers.add(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN));
        }
        JsonArray views = array(json, "bufferViews");
        JsonArray accessors = array(json, "accessors");

        GltfModel model = new GltfModel(name);
        loadMaterials(resources, model, json, buffers, views, namespace, dir);

        // node hierarchy -> every mesh instance with its world matrix
        JsonArray nodes = array(json, "nodes");
        JsonArray meshes = array(json, "meshes");
        List<JsonObject> primitivesToBuild = new ArrayList<>();
        List<Matrix4f> worlds = new ArrayList<>();
        JsonArray scenes = array(json, "scenes");
        List<Integer> roots = new ArrayList<>();
        if (scenes.size() > 0) {
            int sceneIndex = json.has("scene") ? json.get("scene").getAsInt() : 0;
            for (JsonElement n : scenes.get(sceneIndex).getAsJsonObject().getAsJsonArray("nodes")) {
                roots.add(n.getAsInt());
            }
        } else {
            for (int i = 0; i < nodes.size(); i++) {
                roots.add(i);
            }
        }
        for (int root : roots) {
            walk(nodes, meshes, root, new Matrix4f(), primitivesToBuild, worlds);
        }
        if (nodes.size() == 0) {   // no nodes at all: draw every mesh untransformed
            for (JsonElement m : meshes) {
                for (JsonElement p : m.getAsJsonObject().getAsJsonArray("primitives")) {
                    primitivesToBuild.add(p.getAsJsonObject());
                    worlds.add(new Matrix4f());
                }
            }
        }
        for (int i = 0; i < primitivesToBuild.size(); i++) {
            buildPrimitive(model, primitivesToBuild.get(i), worlds.get(i), accessors, views, buffers);
        }
        int[] lodTriangles = new int[LOD_DIVISIONS.length];
        for (GltfModel.Primitive primitive : model.primitives) {
            for (int k = 0; k < lodTriangles.length; k++) {
                lodTriangles[k] += k < primitive.lodVertexCounts.length ? primitive.lodVertexCounts[k] / 3 : primitive.vertexCount / 3;
            }
        }
        RealityShopMod.LOGGER.info("glTF '{}' loaded: {} primitives, {} triangles (distance LODs: {}), {} materials, bounds {}..{}", name,
                model.primitives.size(), model.triangleCount, java.util.Arrays.toString(lodTriangles), model.materials.size(),
                java.util.Arrays.toString(model.min), java.util.Arrays.toString(model.max));
        return model;
    }

    private static void walk(JsonArray nodes, JsonArray meshes, int index, Matrix4f parent, List<JsonObject> prims, List<Matrix4f> worlds) {
        JsonObject node = nodes.get(index).getAsJsonObject();
        Matrix4f world = new Matrix4f(parent).mul(local(node));
        if (node.has("mesh")) {
            for (JsonElement p : meshes.get(node.get("mesh").getAsInt()).getAsJsonObject().getAsJsonArray("primitives")) {
                prims.add(p.getAsJsonObject());
                worlds.add(new Matrix4f(world));
            }
        }
        if (node.has("children")) {
            for (JsonElement c : node.getAsJsonArray("children")) {
                walk(nodes, meshes, c.getAsInt(), world, prims, worlds);
            }
        }
    }

    private static Matrix4f local(JsonObject node) {
        Matrix4f m = new Matrix4f();
        if (node.has("matrix")) {
            float[] a = floats(node.getAsJsonArray("matrix"));
            m.set(a);   // glTF matrices are column-major, like JOML's float[] setter
            return m;
        }
        if (node.has("translation")) {
            float[] t = floats(node.getAsJsonArray("translation"));
            m.translate(t[0], t[1], t[2]);
        }
        if (node.has("rotation")) {
            float[] r = floats(node.getAsJsonArray("rotation"));
            m.rotate(new Quaternionf(r[0], r[1], r[2], r[3]));
        }
        if (node.has("scale")) {
            float[] s = floats(node.getAsJsonArray("scale"));
            m.scale(s[0], s[1], s[2]);
        }
        return m;
    }

    // ------------------------------------------------------------------------------------------------ materials + textures
    private static void loadMaterials(ResourceManager resources, GltfModel model, JsonObject json, List<ByteBuffer> buffers,
                                      JsonArray views, String namespace, String dir) throws IOException {
        JsonArray textures = array(json, "textures");
        JsonArray images = array(json, "images");
        Identifier[] imageIds = new Identifier[images.size()];
        for (JsonElement el : array(json, "materials")) {
            JsonObject m = el.getAsJsonObject();
            GltfModel.Material mat = new GltfModel.Material();
            if (m.has("pbrMetallicRoughness")) {
                JsonObject pbr = m.getAsJsonObject("pbrMetallicRoughness");
                if (pbr.has("baseColorFactor")) {
                    float[] f = floats(pbr.getAsJsonArray("baseColorFactor"));
                    System.arraycopy(f, 0, mat.baseColorFactor, 0, 4);
                }
                mat.metallic = pbr.has("metallicFactor") ? pbr.get("metallicFactor").getAsFloat() : 1.0f;
                mat.roughness = pbr.has("roughnessFactor") ? pbr.get("roughnessFactor").getAsFloat() : 1.0f;
                mat.baseColor = textureOf(resources, model, pbr, "baseColorTexture", textures, images, imageIds, buffers, views, namespace, dir);
                mat.metalRough = textureOf(resources, model, pbr, "metallicRoughnessTexture", textures, images, imageIds, buffers, views, namespace, dir);
            }
            mat.normal = textureOf(resources, model, m, "normalTexture", textures, images, imageIds, buffers, views, namespace, dir);
            mat.doubleSided = m.has("doubleSided") && m.get("doubleSided").getAsBoolean();
            if (m.has("alphaMode") && "MASK".equals(m.get("alphaMode").getAsString())) {
                mat.alphaCutoff = m.has("alphaCutoff") ? m.get("alphaCutoff").getAsFloat() : 0.5f;
            }
            model.materials.add(mat);
        }
        if (model.materials.isEmpty()) {
            model.materials.add(new GltfModel.Material());
        }
    }

    private static Identifier textureOf(ResourceManager resources, GltfModel model, JsonObject owner, String key, JsonArray textures,
                                        JsonArray images, Identifier[] imageIds, List<ByteBuffer> buffers, JsonArray views,
                                        String namespace, String dir) throws IOException {
        if (!owner.has(key)) {
            return null;
        }
        int textureIndex = owner.getAsJsonObject(key).get("index").getAsInt();
        int source = textures.get(textureIndex).getAsJsonObject().get("source").getAsInt();
        if (imageIds[source] == null) {
            JsonObject image = images.get(source).getAsJsonObject();
            byte[] data;
            if (image.has("uri")) {
                data = readUri(resources, namespace, dir, image.get("uri").getAsString());
            } else {
                JsonObject view = views.get(image.get("bufferView").getAsInt()).getAsJsonObject();
                ByteBuffer buf = buffers.get(view.get("buffer").getAsInt());
                int offset = view.has("byteOffset") ? view.get("byteOffset").getAsInt() : 0;
                data = new byte[view.get("byteLength").getAsInt()];
                for (int i = 0; i < data.length; i++) {
                    data[i] = buf.get(offset + i);
                }
            }
            NativeImage decoded = NativeImage.read(NativeImage.Format.RGBA, new ByteArrayInputStream(data));
            MipTexture texture = new MipTexture(decoded);
            Identifier id = new Identifier(RealityShopMod.MODID, "gltf/" + model.name + "/" + source);
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
            texture.upload();
            model.textureIds.add(id);
            imageIds[source] = id;
        }
        return imageIds[source];
    }

    private static byte[] readUri(ResourceManager resources, String namespace, String dir, String uri) throws IOException {
        if (uri.startsWith("data:")) {
            return Base64.getDecoder().decode(uri.substring(uri.indexOf(',') + 1));
        }
        String path = dir + safeName(java.net.URLDecoder.decode(uri.replace("+", "%2B"), StandardCharsets.UTF_8));
        Identifier id = new Identifier(namespace, path);
        Optional<Resource> resource = resources.getResource(id);
        if (resource.isEmpty()) {
            throw new FileNotFoundException("glTF refers to " + id + " which is not in the resources");
        }
        try (InputStream in = resource.get().getInputStream()) {
            return in.readAllBytes();
        }
    }

    /**
     * Resource paths can only contain [a-z0-9/._-]. The generator stores every model file under such a name
     * (material_0_baseColor.png -> material_0_basecolor.png, bytes untouched); URIs inside the glTF are mapped the same way.
     */
    static String safeName(String path) {
        StringBuilder sb = new StringBuilder(path.length());
        for (char c : path.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '/' || c == '.' || c == '_' || c == '-' ? c : '_');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------------ geometry
    private static void buildPrimitive(GltfModel model, JsonObject prim, Matrix4f world, JsonArray accessors, JsonArray views,
                                       List<ByteBuffer> buffers) {
        if (prim.has("mode") && prim.get("mode").getAsInt() != 4) {
            return;   // only triangle lists
        }
        JsonObject attributes = prim.getAsJsonObject("attributes");
        Accessor pos = accessor(accessors, views, buffers, attributes.get("POSITION").getAsInt());
        int vertexCount = pos.count;
        float[] positions = new float[vertexCount * 3];
        float[] normals = new float[vertexCount * 3];
        float[] uvs = new float[vertexCount * 2];
        float[] tangents = new float[vertexCount * 4];
        boolean hasNormals = attributes.has("NORMAL");
        boolean hasTangents = attributes.has("TANGENT");
        boolean hasUv = attributes.has("TEXCOORD_0");
        Accessor nrm = hasNormals ? accessor(accessors, views, buffers, attributes.get("NORMAL").getAsInt()) : null;
        Accessor tan = hasTangents ? accessor(accessors, views, buffers, attributes.get("TANGENT").getAsInt()) : null;
        Accessor uv = hasUv ? accessor(accessors, views, buffers, attributes.get("TEXCOORD_0").getAsInt()) : null;

        Matrix3f normalMatrix = world.normal(new Matrix3f());
        Matrix3f linear = new Matrix3f();
        world.get3x3(linear);
        boolean mirrored = linear.determinant() < 0;
        Vector3f v = new Vector3f();
        for (int i = 0; i < vertexCount; i++) {
            world.transformPosition(pos.f(i, 0), pos.f(i, 1), pos.f(i, 2), v);
            positions[i * 3] = v.x;
            positions[i * 3 + 1] = v.y;
            positions[i * 3 + 2] = v.z;
            for (int k = 0; k < 3; k++) {
                model.min[k] = Math.min(model.min[k], positions[i * 3 + k]);
                model.max[k] = Math.max(model.max[k], positions[i * 3 + k]);
            }
            if (nrm != null) {
                normalMatrix.transform(nrm.f(i, 0), nrm.f(i, 1), nrm.f(i, 2), v);
                v.normalize();
                normals[i * 3] = v.x;
                normals[i * 3 + 1] = v.y;
                normals[i * 3 + 2] = v.z;
            }
            if (tan != null) {
                linear.transform(tan.f(i, 0), tan.f(i, 1), tan.f(i, 2), v);
                v.normalize();
                tangents[i * 4] = v.x;
                tangents[i * 4 + 1] = v.y;
                tangents[i * 4 + 2] = v.z;
                tangents[i * 4 + 3] = (tan.f(i, 3) < 0 ? -1.0f : 1.0f) * (mirrored ? -1.0f : 1.0f);
            }
            if (uv != null) {
                uvs[i * 2] = uv.f(i, 0);
                uvs[i * 2 + 1] = uv.f(i, 1);
            }
        }
        for (int k = 1; k < 4 && hasUv; k++) {   // only TEXCOORD_0 is used for every map: say so if another set really differs
            String key = "TEXCOORD_" + k;
            if (attributes.has(key) && !warnedUvSets) {
                Accessor other = accessor(accessors, views, buffers, attributes.get(key).getAsInt());
                int step = Math.max(1, Math.min(other.count, vertexCount) / 4000);
                for (int i = 0; i < Math.min(other.count, vertexCount); i += step) {
                    if (Math.abs(other.f(i, 0) - uv.f(i, 0)) > 1e-5f || Math.abs(other.f(i, 1) - uv.f(i, 1)) > 1e-5f) {
                        warnedUvSets = true;
                        RealityShopMod.LOGGER.warn("glTF '{}': {} differs from TEXCOORD_0, but only TEXCOORD_0 is used for all maps", model.name, key);
                        break;
                    }
                }
            }
        }
        int[] indices;
        if (prim.has("indices")) {
            Accessor ia = accessor(accessors, views, buffers, prim.get("indices").getAsInt());
            indices = new int[ia.count];
            for (int i = 0; i < indices.length; i++) {
                indices[i] = ia.index(i);
            }
        } else {
            indices = new int[vertexCount];
            for (int i = 0; i < vertexCount; i++) {
                indices[i] = i;
            }
        }
        if (!hasNormals) {
            generateNormals(positions, indices, normals);
        }
        if (!hasTangents) {
            generateTangents(positions, normals, uvs, indices, tangents);
        }

        GltfModel.Primitive primitive = new GltfModel.Primitive();
        primitive.vertexCount = indices.length;
        primitive.material = prim.has("material") ? prim.get("material").getAsInt() : 0;
        if (primitive.material >= model.materials.size()) {
            primitive.material = 0;
        }
        primitive.buffer = upload(positions, tangents, uvs, normals, indices);
        buildLods(primitive, positions, normals, uvs, tangents, indices);
        model.primitives.add(primitive);
        model.triangleCount += indices.length / 3;
    }

    /**
     * Uploads plain TRIANGLES in the vanilla entity vertex format: the tangent rides in the (otherwise unused) colour
     * attribute (xyz * 0.5 + 0.5, w = handedness) and is decoded by the gltf_pbr core shader.
     */
    private static VertexBuffer upload(float[] positions, float[] tangents, float[] uvs, float[] normals, int[] indices) {
        VertexFormat format = VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL;
        BufferBuilder builder = new BufferBuilder(indices.length * format.getVertexSizeByte() + 256);
        builder.begin(VertexFormat.DrawMode.TRIANGLES, format);
        for (int index : indices) {
            builder.vertex(positions[index * 3], positions[index * 3 + 1], positions[index * 3 + 2],
                    tangents[index * 4] * 0.5f + 0.5f, tangents[index * 4 + 1] * 0.5f + 0.5f, tangents[index * 4 + 2] * 0.5f + 0.5f,
                    tangents[index * 4 + 3] >= 0 ? 1.0f : 0.0f,
                    uvs[index * 2], uvs[index * 2 + 1], OverlayTexture.DEFAULT_UV, 0,
                    normals[index * 3], normals[index * 3 + 1], normals[index * 3 + 2]);
        }
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        buffer.upload(builder.end());
        VertexBuffer.unbind();
        return buffer;
    }

    /** Grid divisions of the longest side for LOD 1 and LOD 2: roughly 12% and 2% of the triangles of a dense scan. */
    private static final float[] LOD_DIVISIONS = {64.0f, 26.0f};
    /** UV cells per unit for the same two levels: vertices of different texture islands are never merged. */
    private static final float[] LOD_UV_DIVISIONS = {48.0f, 20.0f};
    /** Meshes below this many triangles are drawn at full detail at every distance. */
    private static final int LOD_MIN_TRIANGLES = 30000;

    /**
     * Distance LODs by vertex clustering: every vertex falls into a cell of a 3D grid (and of the UV map, so texture islands stay
     * apart), a cell becomes one vertex (the average of its members) and triangles that collapse are dropped. The full mesh above is untouched and still what you see up close.
     */
    private static void buildLods(GltfModel.Primitive primitive, float[] p, float[] n, float[] uv, float[] t, int[] indices) {
        if (indices.length / 3 < LOD_MIN_TRIANGLES) {
            return;
        }
        float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < p.length; i += 3) {
            for (int k = 0; k < 3; k++) {
                lo[k] = Math.min(lo[k], p[i + k]);
                hi[k] = Math.max(hi[k], p[i + k]);
            }
        }
        float longest = Math.max(hi[0] - lo[0], Math.max(hi[1] - lo[1], hi[2] - lo[2]));
        if (longest <= 0.0f) {
            return;
        }
        List<VertexBuffer> buffers = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (int level = 0; level < LOD_DIVISIONS.length; level++) {
            float cell = longest / LOD_DIVISIONS[level];
            float uvDivisions = LOD_UV_DIVISIONS[level];
            int vc = p.length / 3;
            Map<Long, Integer> cells = new HashMap<>();
            int[] cluster = new int[vc];
            int clusters = 0;
            for (int i = 0; i < vc; i++) {
                long cx = (long) Math.floor((p[i * 3] - lo[0]) / cell);
                long cy = (long) Math.floor((p[i * 3 + 1] - lo[1]) / cell);
                long cz = (long) Math.floor((p[i * 3 + 2] - lo[2]) / cell);
                float fu = uv[i * 2] - (float) Math.floor(uv[i * 2]);
                float fv = uv[i * 2 + 1] - (float) Math.floor(uv[i * 2 + 1]);
                long cu = (long) Math.floor(fu * uvDivisions);
                long cv = (long) Math.floor(fv * uvDivisions);
                long key = (cx & 0x7FL) | ((cy & 0x7FL) << 7) | ((cz & 0x7FL) << 14) | ((cu & 0x7FL) << 21) | ((cv & 0x7FL) << 28);
                Integer id = cells.get(key);
                if (id == null) {
                    id = clusters++;
                    cells.put(key, id);
                }
                cluster[i] = id;
            }
            float[] lp = new float[clusters * 3];
            float[] ln = new float[clusters * 3];
            float[] luv = new float[clusters * 2];
            float[] lt = new float[clusters * 4];
            int[] members = new int[clusters];
            for (int i = 0; i < vc; i++) {
                int c = cluster[i];
                members[c]++;
                for (int k = 0; k < 3; k++) {
                    lp[c * 3 + k] += p[i * 3 + k];
                    ln[c * 3 + k] += n[i * 3 + k];
                    lt[c * 4 + k] += t[i * 4 + k];
                }
                lt[c * 4 + 3] += t[i * 4 + 3];
                luv[c * 2] += uv[i * 2];
                luv[c * 2 + 1] += uv[i * 2 + 1];
            }
            for (int c = 0; c < clusters; c++) {
                float inv = 1.0f / members[c];
                for (int k = 0; k < 3; k++) {
                    lp[c * 3 + k] *= inv;
                }
                luv[c * 2] *= inv;
                luv[c * 2 + 1] *= inv;
                normalize(ln, c * 3, 3);
                normalize(lt, c * 4, 3);
                lt[c * 4 + 3] = lt[c * 4 + 3] >= 0 ? 1.0f : -1.0f;
            }
            int[] tri = new int[indices.length];
            int out = 0;
            for (int i = 0; i + 2 < indices.length; i += 3) {
                int a = cluster[indices[i]];
                int b = cluster[indices[i + 1]];
                int c = cluster[indices[i + 2]];
                if (a != b && b != c && a != c) {
                    tri[out++] = a;
                    tri[out++] = b;
                    tri[out++] = c;
                }
            }
            if (out < 3 || out > indices.length * 0.6) {
                continue;   // not worth a copy
            }
            buffers.add(upload(lp, lt, luv, ln, java.util.Arrays.copyOf(tri, out)));
            counts.add(out);
        }
        primitive.lodBuffers = buffers.toArray(new VertexBuffer[0]);
        primitive.lodVertexCounts = counts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static void normalize(float[] a, int at, int n) {
        double len = 0;
        for (int k = 0; k < n; k++) {
            len += (double) a[at + k] * a[at + k];
        }
        len = Math.sqrt(len);
        if (len < 1e-8) {
            a[at] = 0.0f;
            a[at + 1] = 1.0f;
            a[at + 2] = 0.0f;
            return;
        }
        for (int k = 0; k < n; k++) {
            a[at + k] /= (float) len;
        }
    }

    private static void generateNormals(float[] p, int[] idx, float[] n) {
        for (int t = 0; t + 2 < idx.length; t += 3) {
            int a = idx[t] * 3;
            int b = idx[t + 1] * 3;
            int c = idx[t + 2] * 3;
            float ux = p[b] - p[a], uy = p[b + 1] - p[a + 1], uz = p[b + 2] - p[a + 2];
            float vx = p[c] - p[a], vy = p[c + 1] - p[a + 1], vz = p[c + 2] - p[a + 2];
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            for (int o : new int[]{a, b, c}) {
                n[o] += nx;
                n[o + 1] += ny;
                n[o + 2] += nz;
            }
        }
        for (int i = 0; i + 2 < n.length; i += 3) {
            float len = (float) Math.sqrt(n[i] * n[i] + n[i + 1] * n[i + 1] + n[i + 2] * n[i + 2]);
            if (len > 1e-8f) {
                n[i] /= len;
                n[i + 1] /= len;
                n[i + 2] /= len;
            } else {
                n[i + 1] = 1.0f;
            }
        }
    }

    private static void generateTangents(float[] p, float[] n, float[] uv, int[] idx, float[] out) {
        float[] tx = new float[n.length];
        for (int t = 0; t + 2 < idx.length; t += 3) {
            int a = idx[t], b = idx[t + 1], c = idx[t + 2];
            float e1x = p[b * 3] - p[a * 3], e1y = p[b * 3 + 1] - p[a * 3 + 1], e1z = p[b * 3 + 2] - p[a * 3 + 2];
            float e2x = p[c * 3] - p[a * 3], e2y = p[c * 3 + 1] - p[a * 3 + 1], e2z = p[c * 3 + 2] - p[a * 3 + 2];
            float du1 = uv[b * 2] - uv[a * 2], du2 = uv[c * 2] - uv[a * 2];
            float dv1 = uv[b * 2 + 1] - uv[a * 2 + 1], dv2 = uv[c * 2 + 1] - uv[a * 2 + 1];
            float det = du1 * dv2 - du2 * dv1;
            if (Math.abs(det) < 1e-12f) {
                continue;
            }
            float r = 1.0f / det;
            float x = (e1x * dv2 - e2x * dv1) * r, y = (e1y * dv2 - e2y * dv1) * r, z = (e1z * dv2 - e2z * dv1) * r;
            for (int o : new int[]{a, b, c}) {
                tx[o * 3] += x;
                tx[o * 3 + 1] += y;
                tx[o * 3 + 2] += z;
            }
        }
        for (int i = 0; i < n.length / 3; i++) {
            float nx = n[i * 3], ny = n[i * 3 + 1], nz = n[i * 3 + 2];
            float x = tx[i * 3], y = tx[i * 3 + 1], z = tx[i * 3 + 2];
            float d = nx * x + ny * y + nz * z;
            x -= nx * d;
            y -= ny * d;
            z -= nz * d;
            float len = (float) Math.sqrt(x * x + y * y + z * z);
            if (len < 1e-8f) {
                out[i * 4 + 3] = 0.0f;   // no usable tangent: the shader skips the normal map for this vertex
                continue;
            }
            out[i * 4] = x / len;
            out[i * 4 + 1] = y / len;
            out[i * 4 + 2] = z / len;
            out[i * 4 + 3] = 1.0f;
        }
    }

    // ------------------------------------------------------------------------------------------------ accessors
    private static Accessor accessor(JsonArray accessors, JsonArray views, List<ByteBuffer> buffers, int index) {
        JsonObject a = accessors.get(index).getAsJsonObject();
        JsonObject view = views.get(a.get("bufferView").getAsInt()).getAsJsonObject();
        Accessor acc = new Accessor();
        acc.data = buffers.get(view.get("buffer").getAsInt());
        acc.type = a.get("componentType").getAsInt();
        acc.count = a.get("count").getAsInt();
        acc.normalized = a.has("normalized") && a.get("normalized").getAsBoolean();
        int comps = switch (a.get("type").getAsString()) {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            case "VEC4" -> 4;
            default -> 1;
        };
        acc.offset = (view.has("byteOffset") ? view.get("byteOffset").getAsInt() : 0) + (a.has("byteOffset") ? a.get("byteOffset").getAsInt() : 0);
        acc.stride = view.has("byteStride") && view.get("byteStride").getAsInt() > 0 ? view.get("byteStride").getAsInt() : comps * typeSize(acc.type);
        return acc;
    }

    private static int typeSize(int componentType) {
        return switch (componentType) {
            case 5120, 5121 -> 1;
            case 5122, 5123 -> 2;
            default -> 4;
        };
    }

    private static final class Accessor {
        ByteBuffer data;
        int offset;
        int count;
        int type;
        int stride;
        boolean normalized;

        float f(int i, int c) {
            int pos = offset + i * stride + c * typeSize(type);
            switch (type) {
                case COMPONENT_FLOAT:
                    return data.getFloat(pos);
                case 5121: {
                    int v = data.get(pos) & 0xFF;
                    return normalized ? v / 255.0f : v;
                }
                case 5123: {
                    int v = data.getShort(pos) & 0xFFFF;
                    return normalized ? v / 65535.0f : v;
                }
                case 5120: {
                    byte v = data.get(pos);
                    return normalized ? Math.max(v / 127.0f, -1.0f) : v;
                }
                case 5122: {
                    short v = data.getShort(pos);
                    return normalized ? Math.max(v / 32767.0f, -1.0f) : v;
                }
                default:
                    return (float) (data.getInt(pos) & 0xFFFFFFFFL);
            }
        }

        int index(int i) {
            int pos = offset + i * stride;
            switch (type) {
                case 5121:
                    return data.get(pos) & 0xFF;
                case 5123:
                    return data.getShort(pos) & 0xFFFF;
                default:
                    return data.getInt(pos);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ json helpers
    private static JsonArray array(JsonObject o, String key) {
        return o.has(key) ? o.getAsJsonArray(key) : new JsonArray();
    }

    private static float[] floats(JsonArray a) {
        float[] f = new float[a.size()];
        for (int i = 0; i < f.length; i++) {
            f[i] = a.get(i).getAsFloat();
        }
        return f;
    }
}
''', client=True)

J("client/gltf/GltfRenderer.java", r'''
package @@PKG@@.client.gltf;

import com.mojang.blaze3d.systems.RenderSystem;
import @@PKG@@.RealityShopMod;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.MissingSprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;

/**
 * Draws a {@link GltfModel} with the mod's own core shader (assets/@@MODID@@/shaders/core/gltf_pbr.*): metallic-roughness
 * PBR with the base colour, normal and metallic-roughness maps, lit by the vanilla directional lights, the world's
 * lightmap (block + sky light) and fog. One draw call per glTF primitive from GPU-resident vertex buffers.
 */
public final class GltfRenderer {
    private static ShaderProgram program;
    private static boolean loggedMissingShader;

    private GltfRenderer() {}

    public static void registerShader() {
        CoreShaderRegistrationCallback.EVENT.register(context ->
                context.register(RealityShopMod.id("gltf_pbr"), VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL,
                        shader -> program = shader));
    }

    private static int glId(Identifier id) {
        return MinecraftClient.getInstance().getTextureManager().getTexture(id != null ? id : MissingSprite.getMissingSpriteId()).getGlId();
    }

    /** Draws in the current matrix space (works for world, item-in-hand, GUI and item-frame contexts) at full detail. */
    public static void render(GltfModel model, MatrixStack matrices, int packedLight, float r, float g, float b, float a) {
        render(model, matrices, packedLight, r, g, b, a, 0);
    }

    /** @param lod 0 = the full mesh, 1 and 2 = decimated copies for distant drawing (where a model has them) */
    public static void render(GltfModel model, MatrixStack matrices, int packedLight, float r, float g, float b, float a, int lod) {
        if (program == null) {
            if (!loggedMissingShader) {
                loggedMissingShader = true;
                RealityShopMod.LOGGER.error("gltf_pbr core shader is not loaded; glTF models are not drawn");
            }
            return;
        }
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(matrices.peek().getPositionMatrix());
        boolean gui = RenderSystem.getProjectionMatrix().m33() > 0.5f;   // orthographic: GUI y is flipped, so never cull there
        float block = LightmapTextureManager.getBlockLightCoordinates(packedLight);
        float sky = LightmapTextureManager.getSkyLightCoordinates(packedLight);

        LightmapTextureManager lightmap = MinecraftClient.getInstance().gameRenderer.getLightmapTextureManager();
        lightmap.enable();   // binds the lightmap to texture unit 2 (Sampler2), exactly like vanilla entities
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(515);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(r, g, b, a);

        GlUniform normalMat = program.getUniform("NormalMat");
        GlUniform lightCoords = program.getUniform("LightCoords");
        GlUniform pbr = program.getUniform("Pbr");
        GlUniform maps = program.getUniform("Maps");
        GlUniform factor = program.getUniform("BaseColorFactor");
        if (normalMat != null) {
            normalMat.set(matrices.peek().getNormalMatrix());
        }
        if (lightCoords != null) {
            lightCoords.set(block, sky);
        }
        for (GltfModel.Primitive primitive : model.primitives) {
            GltfModel.Material material = model.materials.get(primitive.material);
            if (material.baseColor == null && material.baseColorFactor[3] <= 0.0f) {
                continue;
            }
            RenderSystem.setShaderTexture(0, glId(material.baseColor));
            RenderSystem.setShaderTexture(1, glId(material.normal != null ? material.normal : material.baseColor));
            RenderSystem.setShaderTexture(3, glId(material.metalRough != null ? material.metalRough : material.baseColor));
            if (pbr != null) {
                pbr.set(material.metallic, material.roughness, material.alphaCutoff, 0.0f);
            }
            if (maps != null) {
                maps.set(material.normal != null ? 1.0f : 0.0f, material.metalRough != null ? 1.0f : 0.0f,
                        material.baseColor != null ? 1.0f : 0.0f, 0.0f);
            }
            if (factor != null) {
                factor.set(material.baseColorFactor[0], material.baseColorFactor[1], material.baseColorFactor[2], material.baseColorFactor[3]);
            }
            if (material.doubleSided || gui) {
                RenderSystem.disableCull();
            } else {
                RenderSystem.enableCull();
            }
            VertexBuffer buffer = primitive.buffer;
            if (lod > 0 && primitive.lodBuffers.length > 0) {
                buffer = primitive.lodBuffers[Math.min(lod, primitive.lodBuffers.length) - 1];
            }
            buffer.bind();
            buffer.draw(modelView, RenderSystem.getProjectionMatrix(), program);
        }
        VertexBuffer.unbind();
        lightmap.disable();
        RenderSystem.enableCull();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }
}
''', client=True)

J("client/gltf/GltfModels.java", r'''
package @@PKG@@.client.gltf;

import @@PKG@@.RealityShopMod;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.MinecraftClient;

/** Lazy, cached access to the glTF models by name (drop-in: assets/@@MODID@@/models/gltf/&lt;name&gt;/scene.gltf|glb). */
public final class GltfModels {
    private static final Map<String, GltfModel> CACHE = new HashMap<>();
    private static final Set<String> FAILED = new HashSet<>();

    private GltfModels() {}

    /** @return the model, or null if it cannot be loaded (the error is logged once). */
    public static GltfModel get(String name) {
        GltfModel model = CACHE.get(name);
        if (model != null) {
            return model;
        }
        if (FAILED.contains(name)) {
            return null;
        }
        try {
            model = GltfLoader.load(MinecraftClient.getInstance().getResourceManager(), name);
            CACHE.put(name, model);
            return model;
        } catch (Throwable t) {
            FAILED.add(name);
            RealityShopMod.LOGGER.error("Could not load glTF model '{}'", name, t);
            return null;
        }
    }

    /** Loads the models the mod uses now (on joining a world), so the first DerpChicken or phone does not cause a hitch. */
    public static void preload() {
        long t0 = System.nanoTime();
        for (String name : new String[]{"derp_chicken", "cursed_kitty_phone"}) {
            get(name);
        }
        RealityShopMod.LOGGER.info("glTF models preloaded in {} ms", (System.nanoTime() - t0) / 1_000_000);
    }

    /** Frees the GPU buffers and textures (called on every resource reload so edited assets are picked up). */
    public static void clear() {
        for (GltfModel model : CACHE.values()) {
            model.close();
        }
        CACHE.clear();
        FAILED.clear();
    }
}
''', client=True)

J("client/render/PhoneItemRenderer.java", r'''
package @@PKG@@.client.render;

import @@PKG@@.client.gltf.GltfModel;
import @@PKG@@.client.gltf.GltfModels;
import @@PKG@@.client.gltf.GltfRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;

/** Draws the Cursed Kitty Phone from the user's glTF, scaled to fit a block and centred (a transform, not a mesh edit). */
public final class PhoneItemRenderer implements BuiltinItemRendererRegistry.DynamicItemRenderer {
    @Override
    public void render(ItemStack stack, ModelTransformationMode mode, MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                       int light, int overlay) {
        GltfModel model = GltfModels.get("cursed_kitty_phone");
        if (model == null) {
            return;
        }
        float s = 0.9f / model.longestSide();
        matrices.push();
        matrices.translate(0.5, 0.5, 0.5);   // the item renderer hands us the corner of the unit cube
        matrices.scale(s, s, s);
        matrices.translate(-model.centerX(), -model.centerY(), -model.centerZ());
        GltfRenderer.render(model, matrices, light, 1.0f, 1.0f, 1.0f, 1.0f);
        matrices.pop();
    }
}
''', client=True)

J("client/render/DerpChickenRenderer.java", r'''
package @@PKG@@.client.render;

import @@PKG@@.RealityShopMod;
import @@PKG@@.client.gltf.GltfModel;
import @@PKG@@.client.gltf.GltfModels;
import @@PKG@@.client.gltf.GltfRenderer;
import @@PKG@@.entity.DerpChickenEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/**
 * Renders the DerpChicken from the user's glTF (273k triangles, 2048px textures, untouched) as one GPU-resident mesh;
 * beyond 12 and 32 blocks it draws decimated copies of that mesh (made at load time) instead.
 * The model has no skeleton, so it is animated as a whole: a waddle, a head-bob tilt and a panic wobble.
 */
public class DerpChickenRenderer extends EntityRenderer<DerpChickenEntity> {
    private static final Identifier UNUSED = RealityShopMod.id("textures/gltf_unused.png");
    private static final float HEIGHT = 0.9f;
    private static final double MAX_DISTANCE_SQ = 64.0 * 64.0;
    private static final double LOD1_DISTANCE_SQ = 12.0 * 12.0;
    private static final double LOD2_DISTANCE_SQ = 32.0 * 32.0;

    public DerpChickenRenderer(EntityRendererFactory.Context context) {
        super(context);
        this.shadowRadius = 0.28f;
    }

    @Override
    public Identifier getTexture(DerpChickenEntity entity) {
        return UNUSED;
    }

    @Override
    public boolean shouldRender(DerpChickenEntity entity, Frustum frustum, double x, double y, double z) {
        return entity.squaredDistanceTo(x, y, z) < MAX_DISTANCE_SQ && super.shouldRender(entity, frustum, x, y, z);
    }

    @Override
    public void render(DerpChickenEntity entity, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        GltfModel model = GltfModels.get("derp_chicken");
        if (model != null) {
            float t = entity.age + tickDelta + entity.getId() * 7.0f;
            float bodyYaw = MathHelper.lerpAngleDegrees(tickDelta, entity.prevBodyYaw, entity.bodyYaw);
            float speed = (float) Math.min(1.0, entity.getVelocity().horizontalLength() * 5.0);
            matrices.push();
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-bodyYaw));     // the model's beak points to +Z
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(MathHelper.sin(t * 0.55f) * 7.0f * speed
                    + MathHelper.sin(t * 0.11f) * 2.5f));                              // waddle + idle sway
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(MathHelper.sin(t * 0.8f) * 4.0f * speed));   // head-bob tilt
            float s = HEIGHT / model.height();
            matrices.scale(s, s, s);
            matrices.translate(-model.centerX(), -model.min[1], -model.centerZ());    // feet on the ground, centred
            float hurt = entity.hurtTime > 0 ? 1.0f : 0.0f;
            double distanceSq = dispatcher.getSquaredDistanceToCamera(entity);
            int lod = distanceSq > LOD2_DISTANCE_SQ ? 2 : (distanceSq > LOD1_DISTANCE_SQ ? 1 : 0);   // 273k triangles up close, far fewer further away
            GltfRenderer.render(model, matrices, light, 1.0f, 1.0f - 0.55f * hurt, 1.0f - 0.55f * hurt, 1.0f, lod);
            matrices.pop();
        }
        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
    }
}
''', client=True)

J("client/render/SuckedBlockRenderer.java", r'''
package @@PKG@@.client.render;

import @@PKG@@.entity.SuckedBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;

/** The flying blocks of the Beacon 5G tornado: a spinning block model. */
public class SuckedBlockRenderer extends EntityRenderer<SuckedBlockEntity> {
    public SuckedBlockRenderer(EntityRendererFactory.Context context) {
        super(context);
        this.shadowRadius = 0.0f;
    }

    @Override
    public Identifier getTexture(SuckedBlockEntity entity) {
        return SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE;
    }

    @Override
    public void render(SuckedBlockEntity entity, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        float t = entity.age + tickDelta;
        matrices.push();
        matrices.translate(0.0, 0.5, 0.0);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(t * 23.0f));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(t * 11.0f));
        matrices.translate(-0.5, -0.5, -0.5);
        MinecraftClient.getInstance().getBlockRenderManager().renderBlockAsEntity(entity.getState(), matrices, vertexConsumers,
                15728880, OverlayTexture.DEFAULT_UV);
        matrices.pop();
        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
    }
}
''', client=True)

J("client/particle/GreenLaserParticle.java", r'''
package @@PKG@@.client.particle;

import net.fabricmc.fabric.api.client.particle.v1.FabricSpriteProvider;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleFactory;
import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.particle.SpriteBillboardParticle;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.DefaultParticleType;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * The custom green laser ParticleType: ONE particle draws one glowing beam. The velocity parameters of
 * ClientWorld#addParticle are the beam vector (start -> target); the beam lives a few ticks and fades out.
 *
 * It is drawn in vanilla's translucent particle sheet: Minecraft 1.20.1 only renders its five built-in sheets, so a
 * custom (e.g. additive) ParticleTextureSheet would silently never be drawn.
 */
public class GreenLaserParticle extends SpriteBillboardParticle {
    private final Vec3d beam;
    private final float width;

    protected GreenLaserParticle(ClientWorld world, double x, double y, double z, double bx, double by, double bz, FabricSpriteProvider sprites) {
        super(world, x, y, z);   // the 4-argument constructor: no random velocity scatter
        this.beam = new Vec3d(bx, by, bz);
        this.maxAge = 5 + this.random.nextInt(6);
        this.width = 0.07f + this.random.nextFloat() * 0.06f;
        this.red = 0.35f;
        this.green = 1.0f;
        this.blue = 0.55f;
        this.collidesWithWorld = false;
        this.setSprite(sprites);
    }

    @Override
    public void buildGeometry(VertexConsumer consumer, Camera camera, float tickDelta) {
        Vec3d cam = camera.getPos();
        float x0 = (float) (MathHelper.lerp(tickDelta, prevPosX, x) - cam.x);
        float y0 = (float) (MathHelper.lerp(tickDelta, prevPosY, y) - cam.y);
        float z0 = (float) (MathHelper.lerp(tickDelta, prevPosZ, z) - cam.z);
        float bx = (float) beam.x;
        float by = (float) beam.y;
        float bz = (float) beam.z;
        float x1 = x0 + bx;
        float y1 = y0 + by;
        float z1 = z0 + bz;
        // billboard the ribbon around the beam axis: side = beam x (direction to the beam's middle, seen from the camera)
        float mx = (x0 + x1) * 0.5f;
        float my = (y0 + y1) * 0.5f;
        float mz = (z0 + z1) * 0.5f;
        float sx = by * mz - bz * my;
        float sy = bz * mx - bx * mz;
        float sz = bx * my - by * mx;
        float len = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (len < 1e-5f) {
            return;
        }
        float k = width / len;
        sx *= k;
        sy *= k;
        sz *= k;
        float u0 = getMinU();
        float u1 = getMaxU();
        float v0 = getMinV();
        float v1 = getMaxV();
        float a = MathHelper.clamp(1.0f - (age + tickDelta) / maxAge, 0.0f, 1.0f);
        int light = 15728880;
        // both windings, so the ribbon is visible whichever way round the camera sees it (vanilla particles are not culled either)
        consumer.vertex(x0 - sx, y0 - sy, z0 - sz).texture(u0, v1).color(red, green, blue, a).light(light).next();
        consumer.vertex(x0 + sx, y0 + sy, z0 + sz).texture(u1, v1).color(red, green, blue, a).light(light).next();
        consumer.vertex(x1 + sx, y1 + sy, z1 + sz).texture(u1, v0).color(red, green, blue, a).light(light).next();
        consumer.vertex(x1 - sx, y1 - sy, z1 - sz).texture(u0, v0).color(red, green, blue, a).light(light).next();
    }

    @Override
    public ParticleTextureSheet getType() {
        return ParticleTextureSheet.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    public int getBrightness(float tint) {
        return 15728880;
    }

    public static class Factory implements ParticleFactory<DefaultParticleType> {
        private final FabricSpriteProvider sprites;

        public Factory(FabricSpriteProvider sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(DefaultParticleType type, ClientWorld world, double x, double y, double z, double vx, double vy, double vz) {
            return new GreenLaserParticle(world, x, y, z, vx, vy, vz, sprites);
        }
    }
}
''', client=True)

J("client/hud/HudGlyphs.java", r'''
package @@PKG@@.client.hud;

/** Metrics of the generated stroke-font atlas (textures/gui/hud_glyphs.png): crisp at any HUD scale. */
public final class HudGlyphs {
    public static final String CHARS = "@@HUD_CHARS@@";
    public static final int[] ADVANCE = {@@HUD_ADV@@};
    public static final int CELL = @@HUD_CELL@@;
    public static final int COLUMNS = @@HUD_COLS@@;
    public static final int ATLAS_WIDTH = @@HUD_ATLAS_W@@;
    public static final int ATLAS_HEIGHT = @@HUD_ATLAS_H@@;
    public static final int SPACE = @@HUD_SPACE@@;

    private HudGlyphs() {}

    public static int index(char c) {
        return CHARS.indexOf(c);
    }

    public static int width(String text) {
        int w = 0;
        for (int i = 0; i < text.length(); i++) {
            int g = index(text.charAt(i));
            w += g < 0 ? SPACE : ADVANCE[g];
        }
        return w;
    }
}
''', client=True)

J("client/hud/HudTextures.java", r'''
package @@PKG@@.client.hud;

import @@PKG@@.RealityShopMod;
import @@PKG@@.client.gltf.MipTexture;
import java.io.IOException;
import java.io.InputStream;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

/** The generated HUD atlas, plate and icon, uploaded with mipmaps (plain GUI textures are not mip-mapped by vanilla). */
public final class HudTextures {
    public static final Identifier GLYPHS = RealityShopMod.id("hud/glyphs");
    public static final Identifier PLATE = RealityShopMod.id("hud/plate");
    public static final Identifier ICON = RealityShopMod.id("hud/icon");

    private HudTextures() {}

    public static void reload(ResourceManager manager) {
        load(manager, GLYPHS, RealityShopMod.id("textures/gui/hud_glyphs.png"));
        load(manager, PLATE, RealityShopMod.id("textures/gui/hud_plate.png"));
        load(manager, ICON, RealityShopMod.id("textures/gui/hud_icon.png"));
    }

    private static void load(ResourceManager manager, Identifier id, Identifier file) {
        try (InputStream in = manager.getResourceOrThrow(file).getInputStream()) {
            MipTexture texture = new MipTexture(NativeImage.read(in));
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);   // closes a previous texture
            texture.upload();
        } catch (IOException | RuntimeException e) {
            RealityShopMod.LOGGER.error("Could not load HUD texture {}", file, e);
        }
    }
}
''', client=True)

J("client/hud/ConsumedBlocksHud.java", r'''
package @@PKG@@.client.hud;

import @@PKG@@.RSConfig;
import @@PKG@@.client.ClientBalance;
import java.text.NumberFormat;
import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/**
 * The Blocks Consumed counter in the top-left corner: a neon pill with the "consumed block" icon, the number in a generated
 * high-resolution stroke font (trilinear, so it is crisp at any GUI scale) and a small "Blocks" caption. The number counts up
 * smoothly, flashes when it grows, and a "+N" / "-N" bubble drifts away from the pill when your balance changes.
 */
public final class ConsumedBlocksHud {
    private static final Identifier GLYPHS = HudTextures.GLYPHS;
    private static final Identifier PLATE = HudTextures.PLATE;
    private static final Identifier ICON = HudTextures.ICON;
    private static final int PLATE_W = 1024;
    private static final int PLATE_H = 128;
    private static final int PLATE_CAP = 64;
    private static final int ICON_PX = 128;

    private static double shown = -1;
    private static long lastTarget = -1;
    private static float flash;
    private static long delta;
    private static int deltaAge = 999;

    private ConsumedBlocksHud() {}

    public static void register() {
        HudRenderCallback.EVENT.register(ConsumedBlocksHud::render);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (deltaAge < 1000) {
                deltaAge++;
            }
            flash = Math.max(0.0f, flash - 0.04f);
        });
    }

    private static String format(long v) {
        return NumberFormat.getIntegerInstance(Locale.US).format(v);
    }

    private static void render(DrawContext context, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        RSConfig config = RSConfig.get();
        if (!config.hudEnabled || client.player == null || client.options.hudHidden) {
            return;
        }
        long target = ClientBalance.get();
        if (shown < 0) {
            shown = target;
        }
        if (lastTarget >= 0 && target != lastTarget) {
            delta = (deltaAge < 40 ? delta : 0) + (target - lastTarget);
            deltaAge = 0;
            if (target > lastTarget) {
                flash = 1.0f;
            }
        }
        lastTarget = target;
        double diff = target - shown;
        shown = Math.abs(diff) < 1.0 ? target : shown + diff * 0.14;

        float s = (float) config.hudScale;
        float h = 26.0f * s;                                   // plate height in GUI units
        float cap = h * PLATE_CAP / PLATE_H;
        float numH = 11.0f * s;
        float capH = 5.5f * s;
        String number = format(Math.round(shown));
        float numW = HudGlyphs.width(number) * (numH / HudGlyphs.CELL);
        float capW = HudGlyphs.width("Blocks") * (capH / HudGlyphs.CELL);
        float icon = 19.0f * s * (1.0f + 0.14f * flash);
        float iconSlot = 20.0f * s;
        float textX = cap * 0.55f + iconSlot + 3.0f * s;
        float plateW = textX + Math.max(numW, capW) + cap * 0.95f;
        float x = config.hudX;
        float y = config.hudY;

        MatrixStack matrices = context.getMatrices();
        matrices.push();
        matrices.translate(x, y, 0.0);
        context.setShaderColor(1.0f, 1.0f, 1.0f, 0.93f);
        int capI = Math.round(cap);
        int plateWi = Math.round(plateW);
        int plateHi = Math.round(h);
        context.drawTexture(PLATE, 0, 0, capI, plateHi, 0, 0, PLATE_CAP, PLATE_H, PLATE_W, PLATE_H);
        context.drawTexture(PLATE, capI, 0, plateWi - 2 * capI, plateHi, PLATE_CAP, 0, PLATE_W - 2 * PLATE_CAP, PLATE_H, PLATE_W, PLATE_H);
        context.drawTexture(PLATE, plateWi - capI, 0, capI, plateHi, PLATE_W - PLATE_CAP, 0, PLATE_CAP, PLATE_H, PLATE_W, PLATE_H);

        // icon (pulses when the balance grows)
        context.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        float ix = cap * 0.55f + (iconSlot - icon) / 2.0f;
        float iy = (h - icon) / 2.0f;
        matrices.push();
        matrices.translate(ix, iy, 0.0);
        matrices.scale(icon / ICON_PX, icon / ICON_PX, 1.0f);
        context.drawTexture(ICON, 0, 0, ICON_PX, ICON_PX, 0, 0, ICON_PX, ICON_PX, ICON_PX, ICON_PX);
        matrices.pop();

        // number (white, flashing cyan-green when it grows) and the caption
        float f = flash;
        drawText(context, number, textX, h * 0.5f - numH * 0.62f - 1.0f * s, numH, 0.55f + 0.45f * f, 1.0f, 0.95f, 1.0f);
        drawText(context, "Blocks", textX, h * 0.5f + numH * 0.30f, capH, 1.0f, 0.55f, 0.92f, 0.85f);
        matrices.pop();

        // +N / -N bubble drifting away from the plate
        if (deltaAge < 55 && delta != 0) {
            float k = deltaAge / 55.0f;
            float alpha = 1.0f - k * k;
            String text = (delta > 0 ? "+" : "-") + format(Math.abs(delta));
            float bubbleH = 9.0f * s;
            float bx = x + plateW + 4.0f * s;
            float by = y + (h - bubbleH) / 2.0f + k * 5.0f * s;
            if (delta > 0) {
                drawText(context, text, bx, by, bubbleH, 0.45f, 1.0f, 0.9f, alpha);
            } else {
                drawText(context, text, bx, by, bubbleH, 1.0f, 0.4f, 0.8f, alpha);
            }
        }
        context.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    /** Draws {@code text} with its top-left at (x, y) in the current matrix space, {@code height} GUI units tall. */
    private static void drawText(DrawContext context, String text, float x, float y, float height, float r, float g, float b, float a) {
        float scale = height / HudGlyphs.CELL;
        MatrixStack matrices = context.getMatrices();
        matrices.push();
        matrices.translate(x, y, 0.0);
        matrices.scale(scale, scale, 1.0f);
        context.setShaderColor(r, g, b, a);
        float pen = 0.0f;
        for (int i = 0; i < text.length(); i++) {
            int gi = HudGlyphs.index(text.charAt(i));
            if (gi < 0) {
                pen += HudGlyphs.SPACE;
                continue;
            }
            int adv = HudGlyphs.ADVANCE[gi];
            int col = gi % HudGlyphs.COLUMNS;
            int row = gi / HudGlyphs.COLUMNS;
            context.drawTexture(GLYPHS, Math.round(pen), 0, adv, HudGlyphs.CELL, col * HudGlyphs.CELL, row * HudGlyphs.CELL,
                    adv, HudGlyphs.CELL, HudGlyphs.ATLAS_WIDTH, HudGlyphs.ATLAS_HEIGHT);
            pen += adv;
        }
        matrices.pop();
    }
}
''', client=True)

J("destroy/ClippedRegion.java", r'''
package @@PKG@@.destroy;

/** A region limited to an axis-aligned box: everything of {@code base} that also lies inside the box. */
public final class ClippedRegion implements DestroyRegion {
    private final DestroyRegion base;
    private final int cx0;
    private final int cy0;
    private final int cz0;
    private final int cx1;
    private final int cy1;
    private final int cz1;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    public ClippedRegion(DestroyRegion base, int cx0, int cy0, int cz0, int cx1, int cy1, int cz1) {
        this.base = base;
        this.cx0 = cx0;
        this.cy0 = cy0;
        this.cz0 = cz0;
        this.cx1 = cx1;
        this.cy1 = cy1;
        this.cz1 = cz1;
        this.minX = Math.max(base.minX(), cx0);
        this.minY = Math.max(base.minY(), cy0);
        this.minZ = Math.max(base.minZ(), cz0);
        this.maxX = Math.min(base.maxX(), cx1);
        this.maxY = Math.min(base.maxY(), cy1);
        this.maxZ = Math.min(base.maxZ(), cz1);
    }

    @Override public int minX() { return minX; }
    @Override public int minY() { return minY; }
    @Override public int minZ() { return minZ; }
    @Override public int maxX() { return maxX; }
    @Override public int maxY() { return maxY; }
    @Override public int maxZ() { return maxZ; }

    @Override
    public boolean contains(int x, int y, int z) {
        return x >= cx0 && x <= cx1 && y >= cy0 && y <= cy1 && z >= cz0 && z <= cz1 && base.contains(x, y, z);
    }

    @Override
    public boolean containsBox(int x0, int y0, int z0, int x1, int y1, int z1) {
        return x0 >= cx0 && x1 <= cx1 && y0 >= cy0 && y1 <= cy1 && z0 >= cz0 && z1 <= cz1 && base.containsBox(x0, y0, z0, x1, y1, z1);
    }

    @Override
    public boolean intersectsBox(int x0, int y0, int z0, int x1, int y1, int z1) {
        int ax0 = Math.max(x0, cx0);
        int ay0 = Math.max(y0, cy0);
        int az0 = Math.max(z0, cz0);
        int ax1 = Math.min(x1, cx1);
        int ay1 = Math.min(y1, cy1);
        int az1 = Math.min(z1, cz1);
        return ax0 <= ax1 && ay0 <= ay1 && az0 <= az1 && base.intersectsBox(ax0, ay0, az0, ax1, ay1, az1);
    }
}
''')

J("destroy/Front.java", r'''
package @@PKG@@.destroy;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * "In front of the player": the horizontal facing and the block the player stands in. Every gadget consumes only what
 * begins AFTER that block, so the player's own footing (and everything beside and behind them) is never part of the
 * area and nobody falls into the hole they just made.
 */
public record Front(Direction dir, BlockPos origin) {
    private static final int FAR = 30_000_000;

    public static Front of(Entity entity) {
        return new Front(entity.getHorizontalFacing(), entity.getBlockPos());
    }

    /** First block coordinate (along {@code dir}) of the front area, {@code gap} blocks beyond the player's own block. */
    public int start(int gap) {
        return switch (dir) {
            case SOUTH -> origin.getZ() + gap;
            case NORTH -> origin.getZ() - gap;
            case EAST -> origin.getX() + gap;
            default -> origin.getX() - gap;   // WEST
        };
    }

    /** A box that begins {@code gap} blocks ahead, is {@code depth} long in the facing direction and {@code width} wide, centred on the player's line. */
    public BoxRegion box(int gap, int depth, int width, int minY, int maxY) {
        int half = width / 2;
        int s = start(gap);
        return switch (dir) {
            case SOUTH -> new BoxRegion(origin.getX() - half, minY, s, origin.getX() - half + width - 1, maxY, s + depth - 1);
            case NORTH -> new BoxRegion(origin.getX() - half, minY, s - depth + 1, origin.getX() - half + width - 1, maxY, s);
            case EAST -> new BoxRegion(s, minY, origin.getZ() - half, s + depth - 1, maxY, origin.getZ() - half + width - 1);
            default -> new BoxRegion(s - depth + 1, minY, origin.getZ() - half, s, maxY, origin.getZ() - half + width - 1);
        };
    }

    /** Only the half of {@code region} that lies in front of the player. */
    public DestroyRegion clip(DestroyRegion region, int gap) {
        return clipHalf(region, dir, start(gap));
    }

    /** The part of {@code region} whose coordinate along {@code dir} is at or beyond {@code start}. */
    public static DestroyRegion clipHalf(DestroyRegion region, Direction dir, int start) {
        return switch (dir) {
            case SOUTH -> new ClippedRegion(region, -FAR, -FAR, start, FAR, FAR, FAR);
            case NORTH -> new ClippedRegion(region, -FAR, -FAR, -FAR, FAR, FAR, start);
            case EAST -> new ClippedRegion(region, start, -FAR, -FAR, FAR, FAR, FAR);
            default -> new ClippedRegion(region, -FAR, -FAR, -FAR, start, FAR, FAR);
        };
    }
}
''')

J("util/ServerTasks.java", r'''
package @@PKG@@.util;

import @@PKG@@.RealityShopMod;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/** Tiny server-thread scheduler: run something N ticks from now (portal lead-ins, delayed pick-ups, glow expiry ...). */
public final class ServerTasks {
    private record Task(long due, Runnable run) {}

    private static final List<Task> TASKS = new ArrayList<>();
    private static long tick;

    private ServerTasks() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tick++;
            for (int i = 0; i < TASKS.size(); ) {
                Task task = TASKS.get(i);
                if (task.due() <= tick) {
                    TASKS.remove(i);
                    try {
                        task.run().run();
                    } catch (RuntimeException e) {
                        RealityShopMod.LOGGER.error("Scheduled task failed", e);
                    }
                } else {
                    i++;
                }
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> TASKS.clear());
    }

    /** Runs {@code task} on the server thread after {@code ticks} ticks (at least one). */
    public static void later(int ticks, Runnable task) {
        TASKS.add(new Task(tick + Math.max(1, ticks), task));
    }

    public static long now() {
        return tick;
    }
}
''')

J("util/GlowLights.java", r'''
package @@PKG@@.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LightBlock;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

/**
 * A short-lived invisible light source (a vanilla {@code light} block) so a freshly hollowed-out pocket underground is not pitch
 * black. Every light is removed again after its lifetime, when its chunk unloads and when the server stops.
 */
public final class GlowLights {
    private record Glow(ServerWorld world, BlockPos pos, long expires) {}

    private static final List<Glow> LIGHTS = new ArrayList<>();

    private GlowLights() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(GlowLights::tick);
        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            ChunkPos cp = chunk.getPos();
            Iterator<Glow> it = LIGHTS.iterator();
            while (it.hasNext()) {
                Glow glow = it.next();
                if (glow.world() == world && glow.pos().getX() >> 4 == cp.x && glow.pos().getZ() >> 4 == cp.z) {
                    if (chunk.getBlockState(glow.pos()).isOf(Blocks.LIGHT)) {
                        chunk.setBlockState(glow.pos(), Blocks.AIR.getDefaultState(), false);
                    }
                    it.remove();
                }
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Glow glow : LIGHTS) {
                remove(glow);
            }
            LIGHTS.clear();
        });
    }

    /** Lights {@code pos} (only if it is air) with the given level for {@code ticks} ticks. */
    public static void place(ServerWorld world, BlockPos pos, int level, int ticks) {
        if (ticks <= 0 || !world.getBlockState(pos).isAir()) {
            return;
        }
        BlockState light = Blocks.LIGHT.getDefaultState().with(LightBlock.LEVEL_15, Math.max(1, Math.min(15, level)));
        world.setBlockState(pos, light, Block.NOTIFY_LISTENERS);
        LIGHTS.add(new Glow(world, pos.toImmutable(), world.getTime() + ticks));
    }

    private static void tick(MinecraftServer server) {
        if (LIGHTS.isEmpty()) {
            return;
        }
        Iterator<Glow> it = LIGHTS.iterator();
        while (it.hasNext()) {
            Glow glow = it.next();
            if (glow.world().getTime() >= glow.expires()) {
                remove(glow);
                it.remove();
            }
        }
    }

    public static int active() {
        return LIGHTS.size();
    }

    private static void remove(Glow glow) {
        if (glow.world().isChunkLoaded(glow.pos()) && glow.world().getBlockState(glow.pos()).isOf(Blocks.LIGHT)) {
            glow.world().setBlockState(glow.pos(), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
        }
    }
}
''')

J("item/PowerTooltips.java", r'''
package @@PKG@@.item;

import java.util.List;
import java.util.Map;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** The "powers" of every item, shown as tooltip lines (and, for the first line, on the shop pedestals). Texts live in the lang file. */
public final class PowerTooltips {
    /** item id -> number of lang lines tooltip.@@MODID@@.<id>.1 .. n (line 1 is the name of the power). */
    public static final Map<String, Integer> LINES = Map.of(
            "glitch_pickaxe", 4,
            "glitchy_floppy", 4,
            "cursed_kitty_phone", 4,
            "hacked_rgb_furnace", 4,
            "beacon_5g", 4,
            "quantum_usb", 4,
            "black_market_portal", 3);

    private PowerTooltips() {}

    public static void append(String id, List<Text> tooltip) {
        int n = LINES.getOrDefault(id, 0);
        for (int i = 1; i <= n; i++) {
            Text line = Text.translatable("tooltip.@@MODID@@." + id + "." + i);
            tooltip.add(i == 1 ? line.copy().formatted(Formatting.GOLD, Formatting.BOLD) : line.copy().formatted(Formatting.GRAY));
        }
    }
}
''')

J("item/PowerBlockItem.java", r'''
package @@PKG@@.item;

import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/** A block item that lists its powers in the tooltip. */
public class PowerBlockItem extends BlockItem {
    private final String id;

    public PowerBlockItem(Block block, Settings settings, String id) {
        super(block, settings);
        this.id = id;
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        super.appendTooltip(stack, world, tooltip, context);
        PowerTooltips.append(id, tooltip);
    }
}
''')

J("shop/BedrockRealm.java", r'''
package @@PKG@@.shop;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * The Bedrock Realm: an endless flat bedrock plain under the End sky (the flat generator lays five bedrock layers, y 0..4),
 * with a ring of bedrock monoliths around the arrival point. The Quantum USB sends you here and there is NO portal back:
 * you leave by dying (you respawn at your spawn point) - or, as an operator, with /realityshop back.
 */
public final class BedrockRealm {
    public static final int SURFACE_Y = 4;
    public static final BlockPos SPAWN = new BlockPos(0, SURFACE_Y + 1, 0);
    private static final int RING_RADIUS = 13;
    private static final int PILLARS = 10;

    private BedrockRealm() {}

    public static boolean isBuilt(ServerWorld world) {
        return world.getBlockState(new BlockPos(RING_RADIUS, SURFACE_Y + 1, 0)).isOf(Blocks.BEDROCK);
    }

    public static void ensureBuilt(ServerWorld world) {
        if (isBuilt(world)) {
            return;
        }
        for (int cx = -2; cx <= 1; cx++) {
            for (int cz = -2; cz <= 1; cz++) {
                world.getChunk(cx, cz);   // the chunks must exist before blocks are placed
            }
        }
        BlockState rock = Blocks.BEDROCK.getDefaultState();
        // ring of monoliths: 2x2 shafts of varying height with a stepped, broken top
        for (int i = 0; i < PILLARS; i++) {
            double a = Math.PI * 2.0 * i / PILLARS + 0.31;
            int px = (int) Math.round(Math.cos(a) * RING_RADIUS);
            int pz = (int) Math.round(Math.sin(a) * RING_RADIUS);
            int height = 9 + (i * 7) % 13;
            for (int dx = 0; dx < 2; dx++) {
                for (int dz = 0; dz < 2; dz++) {
                    int h = height - ((dx + dz) % 2 == 1 ? 2 + i % 3 : 0);
                    for (int y = 1; y <= h; y++) {
                        set(world, px + dx, SURFACE_Y + y, pz + dz, rock);
                    }
                }
            }
        }
        // the arrival altar: three low steps around the spawn point
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                int d = Math.max(Math.abs(x), Math.abs(z));
                if (d >= 3) {
                    set(world, x, SURFACE_Y + 1, z, rock);
                }
                if (d == 4 && ((x + z) & 1) == 0) {
                    set(world, x, SURFACE_Y + 2, z, rock);
                }
            }
        }
        // a few floating chunks in the sky
        for (int i = 0; i < 6; i++) {
            double a = Math.PI * 2.0 * i / 6 + 0.9;
            int fx = (int) Math.round(Math.cos(a) * (22 + i * 3));
            int fz = (int) Math.round(Math.sin(a) * (22 + i * 3));
            int fy = SURFACE_Y + 24 + (i * 5) % 11;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if ((Math.abs(dx) + Math.abs(dy) + Math.abs(dz)) <= 2) {
                            set(world, fx + dx, fy + dy, fz + dz, rock);
                        }
                    }
                }
            }
        }
    }

    private static void set(ServerWorld world, int x, int y, int z, BlockState state) {
        world.setBlockState(new BlockPos(x, y, z), state, Block.NOTIFY_LISTENERS);
    }
}
''')

J("shop/PortalGateBlock.java", r'''
package @@PKG@@.shop;

import @@PKG@@.registry.ModBlockEntities;
import @@PKG@@.registry.ModBlocks;
import @@PKG@@.registry.ModSounds;
import @@PKG@@.util.ServerTasks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * The Reality Gate: a free-standing 3 x 3 portal with a THIN frame and a big swirl. Nine cells of this one block, tied
 * together by three properties:
 * <ul>
 *   <li>FACING: the side the gate's front looks at (towards whoever placed it),</li>
 *   <li>LATERAL: 0 = left, 1 = middle, 2 = right, as seen from the front,</li>
 *   <li>HEIGHT: 0 = bottom, 1 = middle, 2 = top.</li>
 * </ul>
 * The frame is a ring of 4 px bars along the outer edge of the 3 x 3 area (corner stones, feet and a crest as decoration);
 * the 2.5 x 2.5 block opening inside is empty to walk through and holds the shader-drawn vortex. The bottom middle cell is
 * the master that owns the block entity (animation state, walk-through detection).
 */
public class PortalGateBlock extends BlockWithEntity {
    public static final DirectionProperty FACING = HorizontalFacingBlock.FACING;
    public static final IntProperty LATERAL = IntProperty.of("lateral", 0, 2);
    public static final IntProperty HEIGHT = IntProperty.of("height", 0, 2);

    /** Half the width (and height) of the opening in blocks: 40 x 40 px. The opening is centred 1.5 blocks above the master's floor. */
    public static final double OPENING_HALF = 1.25;
    /** Ticks the frame needs to snap together when a gate is placed; the vortex tears open after that. */
    public static final int ASSEMBLY_TICKS = 14;

    // frame geometry in 1/16 blocks, authored for a gate whose front faces SOUTH (depth z)
    private static final double BAR = 4.0;
    private static final double Z0 = 3.5;
    private static final double Z1 = 12.5;

    /** The cells in the order the frame assembles: around the ring from the bottom middle, the centre last. */
    private static final int[][] ORDER = {{1, 0}, {0, 0}, {0, 1}, {0, 2}, {1, 2}, {2, 2}, {2, 1}, {2, 0}, {1, 1}};
    private static final int[] DELAYS = {0, 1, 3, 4, 6, 7, 9, 10, 12};

    /** Collision AND outline: the bars, feet and corner stones. The swirl itself has no shape (no selection box over the vortex). */
    private static final VoxelShape[][][] FRAME = new VoxelShape[3][3][];

    static {
        for (int l = 0; l < 3; l++) {
            for (int h = 0; h < 3; h++) {
                FRAME[l][h] = rotations(frameShape(l, h));
            }
        }
    }

    public PortalGateBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.SOUTH).with(LATERAL, 1).with(HEIGHT, 0));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, LATERAL, HEIGHT);
    }

    // ------------------------------------------------------------------------------------------ geometry helpers
    private static VoxelShape frameShape(int l, int h) {
        VoxelShape s = VoxelShapes.empty();
        if (l == 0) {
            s = VoxelShapes.union(s, createCuboidShape(0, 0, Z0, BAR, 16, Z1));
        }
        if (l == 2) {
            s = VoxelShapes.union(s, createCuboidShape(16 - BAR, 0, Z0, 16, 16, Z1));
        }
        double x0 = l == 0 ? BAR : 0;
        double x1 = l == 2 ? 16 - BAR : 16;
        if (h == 0) {
            s = VoxelShapes.union(s, createCuboidShape(x0, 0, Z0, x1, BAR, Z1));
            if (l != 1) {
                s = VoxelShapes.union(s, createCuboidShape(0, 0, 1.5, 16, 4, 14.5));      // the feet
            }
        }
        if (h == 2) {
            s = VoxelShapes.union(s, createCuboidShape(x0, 16 - BAR, Z0, x1, 16, Z1));
        }
        if (l != 1 && h != 1) {                                                            // corner stones
            double cx = l == 0 ? 0 : 9;
            double cy = h == 0 ? 0 : 9;
            s = VoxelShapes.union(s, createCuboidShape(cx, cy, 2.5, cx + 7, cy + 7, 13.5));
        }
        return s.simplify();
    }

    private static VoxelShape[] rotations(VoxelShape south) {
        VoxelShape[] out = new VoxelShape[4];
        out[0] = south;
        for (int i = 1; i < 4; i++) {
            VoxelShape[] acc = {VoxelShapes.empty()};
            out[i - 1].forEachBox((x0, y0, z0, x1, y1, z1) ->
                    acc[0] = VoxelShapes.union(acc[0], VoxelShapes.cuboid(1 - z1, y0, x0, 1 - z0, y1, x1)));   // 90 degrees clockwise
            out[i] = acc[0].simplify();
        }
        return out;
    }

    public static boolean isMaster(BlockState state) {
        return state.get(LATERAL) == 1 && state.get(HEIGHT) == 0;
    }

    /** The middle cell: nothing but the swirl, so it is the brightest. */
    public static boolean isCenter(BlockState state) {
        return state.get(LATERAL) == 1 && state.get(HEIGHT) == 1;
    }

    /** The cell of the gate with the given part coordinates, from the master (bottom middle) cell. */
    public static BlockPos partPos(BlockPos master, Direction facing, int lateral, int height) {
        return master.offset(facing.rotateYClockwise(), 1 - lateral).up(height);
    }

    /** The master cell of the gate this part belongs to. */
    public static BlockPos masterPos(BlockPos pos, BlockState state) {
        return pos.offset(state.get(FACING).rotateYClockwise(), state.get(LATERAL) - 1).down(state.get(HEIGHT));
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return FRAME[state.get(LATERAL)][state.get(HEIGHT)][state.get(FACING).getHorizontal()];
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return FRAME[state.get(LATERAL)][state.get(HEIGHT)][state.get(FACING).getHorizontal()];
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    // ------------------------------------------------------------------------------------------ block entity (master only)
    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return isMaster(state) ? new PortalGateBlockEntity(pos, state) : null;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return isMaster(state) ? checkType(type, ModBlockEntities.PORTAL_GATE, PortalGateBlockEntity::tick) : null;
    }

    // ------------------------------------------------------------------------------------------ interaction
    /** Right-click any part of the frame: the gate collapses into a glowing orb that flies into your inventory. */
    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (hand != Hand.MAIN_HAND) {
            return ActionResult.PASS;
        }
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        if (player instanceof ServerPlayerEntity sp && world.getBlockEntity(masterPos(pos, state)) instanceof PortalGateBlockEntity gate) {
            gate.requestClose(sp);
        }
        return ActionResult.CONSUME;
    }

    /** Creative players can break a part: the whole gate goes (no orphaned pieces). */
    @Override
    public void onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        if (!world.isClient) {
            removeAll(world, masterPos(pos, state), state.get(FACING));
        }
        super.onBreak(world, pos, state, player);
    }

    // ------------------------------------------------------------------------------------------ placing / removing the nine cells
    /** True if all nine cells can be replaced (air, grass, snow ...) and the player may build there. */
    public static boolean canPlace(World world, BlockPos master, Direction facing, @Nullable PlayerEntity player) {
        for (int l = 0; l < 3; l++) {
            for (int h = 0; h < 3; h++) {
                BlockPos p = partPos(master, facing, l, h);
                if (!world.isInBuildLimit(p) || !(world.getBlockState(p).isAir() || world.getBlockState(p).isReplaceable())
                        || (player != null && !player.canModifyAt(world, p))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void setCell(World world, BlockPos master, Direction facing, int lateral, int height) {
        BlockPos p = partPos(master, facing, lateral, height);
        BlockState existing = world.getBlockState(p);
        if (!(existing.isAir() || existing.isReplaceable() || existing.getBlock() instanceof PortalGateBlock)) {
            return;   // somebody built into the gate's space while it was assembling: the block entity dissolves the gate later
        }
        world.setBlockState(p, ModBlocks.BLACK_MARKET_PORTAL.getDefaultState()
                .with(FACING, facing).with(LATERAL, lateral).with(HEIGHT, height), Block.NOTIFY_ALL);
    }

    /** Builds all nine cells at once, without the assembly show (the Black Market's permanent exit, game tests). */
    public static void placeNow(World world, BlockPos master, Direction facing, boolean permanent) {
        for (int[] c : ORDER) {
            setCell(world, master, facing, c[0], c[1]);
        }
        if (world.getBlockEntity(master) instanceof PortalGateBlockEntity gate) {
            gate.open(world.getTime(), permanent, 0);
        }
    }

    /** A player's gate: the frame snaps together around the ring (about 0.7 s, one click per piece), then the vortex tears open. */
    public static void place(World world, BlockPos master, Direction facing) {
        setCell(world, master, facing, ORDER[0][0], ORDER[0][1]);
        if (world.getBlockEntity(master) instanceof PortalGateBlockEntity gate) {
            gate.open(world.getTime(), false, ASSEMBLY_TICKS);
        }
        click(world, partPos(master, facing, ORDER[0][0], ORDER[0][1]), 0);
        for (int i = 1; i < ORDER.length; i++) {
            final int index = i;
            ServerTasks.later(Math.max(1, DELAYS[i]), () -> {
                if (world.isChunkLoaded(master) && world.getBlockState(master).getBlock() instanceof PortalGateBlock) {
                    setCell(world, master, facing, ORDER[index][0], ORDER[index][1]);
                    click(world, partPos(master, facing, ORDER[index][0], ORDER[index][1]), index);
                }
            });
        }
        ServerTasks.later(ASSEMBLY_TICKS, () -> {
            if (world.isChunkLoaded(master) && world.getBlockState(master).getBlock() instanceof PortalGateBlock) {
                world.playSound(null, master, ModSounds.PORTAL_OPEN, SoundCategory.BLOCKS, 1.2f, 1.0f);
            }
        });
    }

    /** The click of one frame piece snapping into place (the pitch rises around the ring) with a few sparks. */
    private static void click(World world, BlockPos at, int index) {
        world.playSound(null, at, SoundEvents.BLOCK_DEEPSLATE_BRICKS_PLACE, SoundCategory.BLOCKS, 0.8f, 0.8f + 0.06f * index);
        if (world instanceof ServerWorld sw) {
            sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.08);
            sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 4, 0.3, 0.3, 0.3, 0.1);
        }
    }

    /** Removes all nine cells (whatever of them is still there). */
    public static void removeAll(World world, BlockPos master, Direction facing) {
        for (int l = 0; l < 3; l++) {
            for (int h = 0; h < 3; h++) {
                BlockPos p = partPos(master, facing, l, h);
                BlockState s = world.getBlockState(p);
                if (s.getBlock() instanceof PortalGateBlock) {
                    world.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL | Block.SKIP_DROPS);
                }
            }
        }
    }

    /** True while all nine cells are still parts of this gate. */
    public static boolean intact(World world, BlockPos master, Direction facing) {
        for (int l = 0; l < 3; l++) {
            for (int h = 0; h < 3; h++) {
                BlockState s = world.getBlockState(partPos(master, facing, l, h));
                if (!(s.getBlock() instanceof PortalGateBlock) || s.get(LATERAL) != l || s.get(HEIGHT) != h || s.get(FACING) != facing) {
                    return false;
                }
            }
        }
        return true;
    }
}
''')

J("shop/PortalGateBlockEntity.java", r'''
package @@PKG@@.shop;

import @@PKG@@.RSConfig;
import @@PKG@@.RealityShopMod;
import @@PKG@@.net.TransitionPackets;
import @@PKG@@.registry.ModBlockEntities;
import @@PKG@@.registry.ModItems;
import @@PKG@@.registry.ModSounds;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

/**
 * The master cell of a {@link PortalGateBlock}: owns the animation state that the client renderer reads (opened-at /
 * closing-since, both in game time, synced), watches the opening for players on the server and runs the pick-up (the vortex
 * collapses for {@link #CLOSE_TICKS}, then the frame crumbles and the item lands in the inventory).
 */
public class PortalGateBlockEntity extends BlockEntity {
    /** The vortex tears open over this many ticks (after the frame has assembled). */
    public static final int OPEN_TICKS = 40;
    /** The vortex collapses into an orb over this many ticks. */
    public static final int CLOSE_TICKS = 22;
    /**
     * From the moment a player touches the swirl until the gate takes them. The screen fades into the vortex meanwhile, so
     * walking in feels like stepping through a door; the player does not have to stand still.
     */
    public static final int ENTER_TICKS = 8;
    private static final BlockState[] FRAGMENTS = {
            Blocks.STONE.getDefaultState(), Blocks.DIRT.getDefaultState(), Blocks.GRASS_BLOCK.getDefaultState(),
            Blocks.OAK_PLANKS.getDefaultState(), Blocks.DEEPSLATE.getDefaultState(), Blocks.SAND.getDefaultState(),
            Blocks.OBSIDIAN.getDefaultState(), Blocks.NETHERRACK.getDefaultState(), Blocks.COBBLESTONE.getDefaultState()};

    private long openedAt;
    private long closingAt = -1;
    private UUID closer;
    private boolean permanent;
    /** Players that touched the swirl and are on their way through: uuid -> ticks since. */
    private final Map<UUID, Integer> entering = new HashMap<>();
    private int validateTimer;

    public PortalGateBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PORTAL_GATE, pos, state);
    }

    // ------------------------------------------------------------------------------------------ state (shared with the renderer)
    /** Starts the summon animation {@code delay} ticks from {@code time}; permanent gates are simply open. */
    public void open(long time, boolean permanent, int delay) {
        this.openedAt = permanent ? time - OPEN_TICKS : time + delay;
        this.closingAt = -1;
        this.permanent = permanent;
        sync();
    }

    public boolean isPermanent() {
        return permanent;
    }

    public UUID getCloser() {
        return closer;
    }

    public boolean isClosing() {
        return closingAt >= 0;
    }

    /** 0 = still assembling / just placed .. 1 = fully open (the summon animation). */
    public float openProgress(long time, float tickDelta) {
        return MathHelper.clamp((time + tickDelta - openedAt) / OPEN_TICKS, 0.0f, 1.0f);
    }

    /** 0 = open .. 1 = collapsed into the orb. */
    public float closeProgress(long time, float tickDelta) {
        return closingAt < 0 ? 0.0f : MathHelper.clamp((time + tickDelta - closingAt) / CLOSE_TICKS, 0.0f, 1.0f);
    }

    /** The opening of a gate (where the swirl is): 2.5 x 2.5 blocks, half a block deep to either side of the plane. */
    public static Box openingBox(BlockPos master, Direction facing) {
        double cx = master.getX() + 0.5;
        double cz = master.getZ() + 0.5;
        boolean alongX = facing.getAxis() == Direction.Axis.Z;
        double half = PortalGateBlock.OPENING_HALF;
        double depth = 0.5;
        return new Box(cx - (alongX ? half : depth), master.getY() + 0.25, cz - (alongX ? depth : half),
                cx + (alongX ? half : depth), master.getY() + 2.75, cz + (alongX ? depth : half));
    }

    private void sync() {
        markDirty();
        if (world != null) {
            world.updateListeners(pos, getCachedState(), getCachedState(), Block.NOTIFY_ALL);
        }
    }

    // ------------------------------------------------------------------------------------------ pick-up
    public boolean requestClose(ServerPlayerEntity player) {
        if (world == null) {
            return false;
        }
        if (permanent) {
            player.sendMessage(Text.literal("This gate is anchored to the Black Market.").formatted(Formatting.GRAY), true);
            return false;
        }
        if (closingAt >= 0) {
            return false;
        }
        closingAt = world.getTime();
        closer = player.getUuid();
        sync();
        world.playSound(null, pos, ModSounds.PORTAL_CLOSE, SoundCategory.BLOCKS, 1.2f, 1.0f);
        return true;
    }

    private void finishClose(ServerWorld sw, Direction facing) {
        Vec3d at = Vec3d.ofCenter(pos).add(0, 1.0, 0);
        crumble(sw, facing);
        PortalGateBlock.removeAll(sw, pos, facing);
        giveItem(sw, at, closer);
        sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, at.x, at.y, at.z, 50, 0.7, 0.9, 0.7, 0.3);
    }

    /** The frame breaks into fragments (particles + sound of the blocks it is made of) just before it disappears. */
    private void crumble(ServerWorld sw, Direction facing) {
        BlockState stone = Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState();
        BlockState corner = Blocks.CRYING_OBSIDIAN.getDefaultState();
        for (int l = 0; l < 3; l++) {
            for (int h = 0; h < 3; h++) {
                if (l == 1 && h == 1) {
                    continue;
                }
                BlockPos p = PortalGateBlock.partPos(pos, facing, l, h);
                if (sw.getBlockState(p).getBlock() instanceof PortalGateBlock) {
                    sw.syncWorldEvent(2001, p, Block.getRawIdFromState(l != 1 && h != 1 ? corner : stone));
                }
            }
        }
    }

    /** A gate that lost a piece falls apart: what is left goes away and the item is dropped. */
    private void dissolve(ServerWorld sw, Direction facing) {
        Vec3d at = Vec3d.ofCenter(pos).add(0, 1.0, 0);
        crumble(sw, facing);
        PortalGateBlock.removeAll(sw, pos, facing);
        giveItem(sw, at, null);
    }

    private static void giveItem(ServerWorld sw, Vec3d at, UUID to) {
        ItemStack stack = new ItemStack(ModItems.BLACK_MARKET_PORTAL);
        ServerPlayerEntity player = to == null ? null : sw.getServer().getPlayerManager().getPlayer(to);
        if (player != null) {
            player.getInventory().offerOrDrop(stack);
            sw.playSound(null, player.getBlockPos(), net.minecraft.sound.SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.6f, 1.2f);
        } else {
            net.minecraft.util.ItemScatterer.spawn(sw, at.x, at.y, at.z, stack);
        }
    }

    // ------------------------------------------------------------------------------------------ ticking
    public static void tick(World world, BlockPos pos, BlockState state, PortalGateBlockEntity be) {
        if (world.isClient) {
            be.clientTick(world, pos, state);
            return;
        }
        ServerWorld sw = (ServerWorld) world;
        Direction facing = state.get(PortalGateBlock.FACING);
        long now = world.getTime();
        if (be.closingAt >= 0) {
            if (now - be.closingAt >= CLOSE_TICKS) {
                be.finishClose(sw, facing);
            }
            return;
        }
        if (!be.permanent && ++be.validateTimer >= 40) {
            be.validateTimer = 0;
            if (!PortalGateBlock.intact(world, pos, facing)) {
                be.dissolve(sw, facing);
                return;
            }
        }
        if (be.openProgress(now, 0.0f) < 0.6f) {
            return;
        }
        // whoever touches the swirl starts the trip: the client fades into the vortex, ENTER_TICKS later the gate takes them
        List<ServerPlayerEntity> inside = sw.getEntitiesByClass(ServerPlayerEntity.class, openingBox(pos, facing), p -> p.isAlive() && !p.isSpectator());
        for (ServerPlayerEntity player : inside) {
            if (!be.entering.containsKey(player.getUuid())) {
                be.entering.put(player.getUuid(), 0);
                TransitionPackets.send(player, true);
                if (RealityShopMod.DEBUG) {
                    RealityShopMod.LOGGER.info("[gate] {} touched the swirl at {} in {} (permanent={}) at ({}, {}, {})", player.getName().getString(),
                            pos, sw.getRegistryKey().getValue(), be.permanent, player.getX(), player.getY(), player.getZ());
                }
            }
        }
        if (be.entering.isEmpty()) {
            return;
        }
        Vec3d centre = Vec3d.ofCenter(pos).add(0, 1.0, 0);
        Iterator<Map.Entry<UUID, Integer>> it = be.entering.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            PlayerEntity found = sw.getPlayerByUuid(entry.getKey());
            if (!(found instanceof ServerPlayerEntity player) || !player.isAlive()) {
                it.remove();
                continue;
            }
            int n = entry.getValue() + 1;
            if (n < ENTER_TICKS) {
                entry.setValue(n);
                continue;
            }
            it.remove();
            if (player.squaredDistanceTo(centre) <= 9.0) {
                ShopDimensions.useGate(player, pos, facing);
            } else {
                TransitionPackets.send(player, false);   // they backed away from the gate: fade the vortex out again
            }
        }
    }

    /** Client: a comet of sparks runs round the rim while the vortex opens; block fragments are sucked into the swirl ("block consumer"). */
    private void clientTick(World world, BlockPos pos, BlockState state) {
        long now = world.getTime();
        float open = openProgress(now, 0.0f);
        if (open <= 0.0f || closingAt >= 0) {
            return;
        }
        double density = RSConfig.get().particleDensity;
        Random r = world.random;
        Direction front = state.get(PortalGateBlock.FACING);
        Direction left = front.rotateYClockwise();
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 1.5;
        double cz = pos.getZ() + 0.5;
        double half = PortalGateBlock.OPENING_HALF;
        if (open < 1.0f) {
            for (int k = 0; k < 2; k++) {
                double s = ((open * 3.0 + k * 0.5) % 1.0) * 4.0;     // 0..4 along the square rim
                double f = s - Math.floor(s);
                double lat;
                double up;
                switch ((int) s) {
                    case 0 -> {
                        lat = -half + 2 * half * f;
                        up = -half;
                    }
                    case 1 -> {
                        lat = half;
                        up = -half + 2 * half * f;
                    }
                    case 2 -> {
                        lat = half - 2 * half * f;
                        up = half;
                    }
                    default -> {
                        lat = -half;
                        up = half - 2 * half * f;
                    }
                }
                world.addParticle(k == 0 ? ParticleTypes.ELECTRIC_SPARK : ParticleTypes.END_ROD, cx + left.getOffsetX() * lat, cy + up,
                        cz + left.getOffsetZ() * lat, 0.0, 0.0, 0.0);
            }
        }
        if (open < 0.2f) {
            return;
        }
        int n = (int) Math.round((r.nextFloat() < 0.5f ? 1 : 2) * density * open);
        for (int i = 0; i < n; i++) {
            // a point on a ring around the opening, in the gate's plane (lateral axis = the facing's left, and up)
            double ang = r.nextDouble() * Math.PI * 2.0;
            double rad = 1.3 + r.nextDouble() * 1.0;
            double lateral = Math.cos(ang) * rad;
            double depth = (r.nextDouble() - 0.5) * 0.6;
            double px = cx + left.getOffsetX() * lateral + front.getOffsetX() * depth;
            double py = cy + Math.sin(ang) * rad;
            double pz = cz + left.getOffsetZ() * lateral + front.getOffsetZ() * depth;
            world.addParticle(new BlockStateParticleEffect(ParticleTypes.BLOCK, FRAGMENTS[r.nextInt(FRAGMENTS.length)]),
                    px, py, pz, (cx - px) * 0.18, (cy - py) * 0.18, (cz - pz) * 0.18);
        }
        if (r.nextInt(3) == 0) {
            world.addParticle(ParticleTypes.REVERSE_PORTAL, cx + (r.nextDouble() - 0.5) * 2.0, cy + (r.nextDouble() - 0.5) * 2.0,
                    cz + (r.nextDouble() - 0.5), 0.0, 0.02, 0.0);
        }
    }

    // ------------------------------------------------------------------------------------------ persistence + sync
    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putLong("openedAt", openedAt);
        nbt.putLong("closingAt", closingAt);
        nbt.putBoolean("permanent", permanent);
        if (closer != null) {
            nbt.putUuid("closer", closer);
        }
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        openedAt = nbt.getLong("openedAt");
        closingAt = nbt.contains("closingAt") ? nbt.getLong("closingAt") : -1;
        permanent = nbt.getBoolean("permanent");
        closer = nbt.containsUuid("closer") ? nbt.getUuid("closer") : null;
    }

    @Override
    public NbtCompound toInitialChunkDataNbt() {
        return createNbt();
    }

    @Override
    public BlockEntityUpdateS2CPacket toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }
}
''')

J("net/TransitionPackets.java", r'''
package @@PKG@@.net;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;

/** Tells a client that a gate transition starts (fade into the vortex, no loading screen) or was cancelled. */
public final class TransitionPackets {
    private TransitionPackets() {}

    public static void send(ServerPlayerEntity player, boolean begin) {
        if (player.networkHandler == null) {
            return;   // mock players in game tests
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(begin);
        ServerPlayNetworking.send(player, ModNetwork.GATE_TRANSITION, buf);
    }
}
''')

J("item/PortalGateItem.java", r'''
package @@PKG@@.item;

import @@PKG@@.registry.ModSounds;
import @@PKG@@.shop.PortalGateBlock;
import @@PKG@@.shop.ShopDimensions;
import java.util.List;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/** Places a Reality Gate (3 wide, 3 tall) in front of you: the frame snaps together, then the vortex tears open. */
public class PortalGateItem extends Item {
    public PortalGateItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        World world = context.getWorld();
        PlayerEntity player = context.getPlayer();
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        if (ShopDimensions.isShopDimension(world)) {
            tell(player, "Reality is anchored here - gates cannot be placed in the shop dimensions.");
            return ActionResult.FAIL;
        }
        BlockPos clicked = context.getBlockPos();
        BlockPos base = world.getBlockState(clicked).isReplaceable() ? clicked : clicked.offset(context.getSide());
        Direction facing = player != null ? player.getHorizontalFacing().getOpposite() : Direction.SOUTH;
        if (!PortalGateBlock.canPlace(world, base, facing, player)) {
            tell(player, "Not enough free space for the gate (3 wide, 3 tall).");
            return ActionResult.FAIL;
        }
        PortalGateBlock.place(world, base, facing);
        if (player != null && !player.getAbilities().creativeMode) {
            context.getStack().decrement(1);
        }
        return ActionResult.CONSUME;
    }

    private static void tell(@Nullable PlayerEntity player, String message) {
        if (player instanceof ServerPlayerEntity sp) {
            sp.sendMessage(Text.literal(message).formatted(Formatting.RED), true);
        }
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        PowerTooltips.append("black_market_portal", tooltip);
    }
}
''')

J("client/gate/PortalShader.java", r'''
package @@PKG@@.client.gate;

import com.mojang.blaze3d.systems.RenderSystem;
import @@PKG@@.RealityShopMod;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import org.joml.Matrix4f;

/**
 * The block-consumer vortex: a core shader (assets/@@MODID@@/shaders/core/portal_vortex.*) that computes the swirl per pixel,
 * so the portal moves fluidly at any frame rate instead of stepping through texture frames.
 * Modes: 0 = the doorway of a gate, 1 = the full-screen transition overlay, 2 = the orb of a collapsing gate.
 */
public final class PortalShader {
    public static final float MODE_GATE = 0.0f;
    public static final float MODE_SCREEN = 1.0f;
    public static final float MODE_ORB = 2.0f;
    /** The /cinematic studio: a full-screen backdrop and a glowing floor disc. */
    public static final float MODE_BACKDROP = 3.0f;
    public static final float MODE_FLOOR = 4.0f;

    private static ShaderProgram program;

    private PortalShader() {}

    public static void register() {
        CoreShaderRegistrationCallback.EVENT.register(context ->
                context.register(RealityShopMod.id("portal_vortex"), VertexFormats.POSITION_TEXTURE, shader -> program = shader));
    }

    public static boolean ready() {
        return program != null;
    }

    private static void set(String name, float v) {
        GlUniform u = program.getUniform(name);
        if (u != null) {
            u.set(v);
        }
    }

    /**
     * Draws one quad (x0,y0)-(x1,y1) at depth z with the vortex shader.
     *
     * @param overlay true for 2D (HUD) drawing: no depth test
     */
    public static void draw(Matrix4f matrix, float x0, float y0, float x1, float y1, float z, float aspectX, float aspectY,
                            float mode, float open, float close, float alpha, float time, boolean overlay) {
        if (program == null) {
            return;
        }
        set("Time", time);
        set("Open", open);
        set("Close", close);
        set("Mode", mode);
        set("Alpha", alpha);
        GlUniform aspect = program.getUniform("Aspect");
        if (aspect != null) {
            aspect.set(aspectX, aspectY);
        }
        RenderSystem.setShader(() -> program);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        if (overlay) {
            RenderSystem.disableDepthTest();
        }
        BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
        buffer.vertex(matrix, x0, y0, z).texture(0.0f, 1.0f).next();
        buffer.vertex(matrix, x1, y0, z).texture(1.0f, 1.0f).next();
        buffer.vertex(matrix, x1, y1, z).texture(1.0f, 0.0f).next();
        buffer.vertex(matrix, x0, y1, z).texture(0.0f, 0.0f).next();
        BufferRenderer.drawWithGlobalProgram(buffer.end());
        if (overlay) {
            RenderSystem.enableDepthTest();
        }
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }
}
''', client=True)

J("client/gate/PortalTransition.java", r'''
package @@PKG@@.client.gate;

import @@PKG@@.RealityShopMod;
import @@PKG@@.net.ModNetwork;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;

/**
 * The seamless trip through a gate. The server says "a transition starts" the moment you step into the doorway: the screen
 * fades into the swirling vortex while the server counts down, the dimension change itself shows NO loading screen
 * (see the two client mixins, which ask {@link #active()}), and once you arrive the vortex fades away again.
 */
public final class PortalTransition {
    private static float fade;
    private static float prevFade;
    private static boolean wantFade;
    private static int hold;
    private static int stuck;
    private static int trace;

    private PortalTransition() {}

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(ModNetwork.GATE_TRANSITION, (client, handler, buf, responseSender) -> {
            boolean begin = buf.readBoolean();
            client.execute(() -> {
                if (RealityShopMod.DEBUG) {
                    RealityShopMod.LOGGER.info("[gate-client] transition packet begin={} (fade {})", begin, fade);
                }
                wantFade = begin;
                stuck = 0;
            });
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        HudRenderCallback.EVENT.register(PortalTransition::render);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    /** True while a transition is running: the loading screens of a dimension change are skipped then. */
    public static boolean active() {
        return wantFade || fade > 0.05f;
    }

    /** The dimension just changed under us: be fully covered right now, then fade out. */
    public static void onArrived() {
        if (RealityShopMod.DEBUG) {
            RealityShopMod.LOGGER.info("[gate-client] arrived (no loading screen)");
            trace = 100;
        }
        fade = 1.0f;
        prevFade = 1.0f;
        wantFade = false;
        hold = 6;
    }

    private static void reset() {
        fade = prevFade = 0.0f;
        wantFade = false;
        hold = 0;
        stuck = 0;
    }

    public static void traceDimensionChange(boolean skipped) {
        if (RealityShopMod.DEBUG) {
            RealityShopMod.LOGGER.info("[gate-client] dimension change: loading screen skipped = {} (wantFade {}, fade {})", skipped, wantFade, fade);
            if (!skipped) {
                trace = 100;
            }
        }
    }

    private static void tick() {
        if (RealityShopMod.DEBUG && trace > 0) {
            trace--;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null && trace % 5 == 0) {
                RealityShopMod.LOGGER.info("[gate-trace-client] left={} pos=({}, {}, {}) onGround={} fall={} chunkLoaded={} screen={}", trace,
                        String.format("%.2f", mc.player.getX()), String.format("%.2f", mc.player.getY()), String.format("%.2f", mc.player.getZ()),
                        mc.player.isOnGround(), String.format("%.1f", mc.player.fallDistance),
                        mc.world != null && mc.world.isChunkLoaded(mc.player.getBlockPos()), mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getSimpleName());
            }
        }
        prevFade = fade;
        if (wantFade) {
            fade = Math.min(1.0f, fade + 0.14f);
            if (fade >= 1.0f && ++stuck > 120) {
                wantFade = false;   // the server never moved us: do not stay covered forever
            }
        } else if (hold > 0) {
            hold--;
        } else {
            fade = Math.max(0.0f, fade - 0.05f);
        }
    }

    private static void render(DrawContext context, float tickDelta) {
        float f = MathHelper.lerp(tickDelta, prevFade, fade);
        if (f <= 0.002f || !PortalShader.ready()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        int w = context.getScaledWindowWidth();
        int h = context.getScaledWindowHeight();
        float eased = f * f * (3.0f - 2.0f * f);
        float time = client.world == null ? 0.0f : (float) (((client.world.getTime() + tickDelta) / 20.0) % 3600.0);
        PortalShader.draw(context.getMatrices().peek().getPositionMatrix(), 0.0f, 0.0f, w, h, 0.0f, (float) w / h, 1.0f,
                PortalShader.MODE_SCREEN, 1.0f, 0.0f, eased, time, true);
    }
}
''', client=True)

J("client/gate/PortalGateRenderer.java", r'''
package @@PKG@@.client.gate;

import @@PKG@@.shop.PortalGateBlock;
import @@PKG@@.shop.PortalGateBlockEntity;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Matrix4f;

/**
 * Draws the vortex inside the gate's opening (2.5 x 2.5 blocks) with the {@link PortalShader}: the summon animation while it
 * opens, the swirl while it is open, and on pick-up the collapse into an orb that flies to the player.
 */
public class PortalGateRenderer implements BlockEntityRenderer<PortalGateBlockEntity> {
    public PortalGateRenderer(BlockEntityRendererFactory.Context ctx) {
    }

    @Override
    public void render(PortalGateBlockEntity be, float tickDelta, MatrixStack matrices, VertexConsumerProvider vcp, int light, int overlay) {
        World world = be.getWorld();
        if (world == null || !PortalShader.ready()) {
            return;
        }
        long time = world.getTime();
        float open = be.openProgress(time, tickDelta);
        float close = be.closeProgress(time, tickDelta);
        if (open <= 0.001f) {
            return;
        }
        Direction facing = be.getCachedState().get(PortalGateBlock.FACING);
        float t = (float) (((time + tickDelta) / 20.0) % 3600.0);

        matrices.push();
        matrices.translate(0.5, 0.0, 0.5);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-facing.asRotation()));   // the doorway plane faces the gate's front
        if (close < 0.98f) {
            Matrix4f m = matrices.peek().getPositionMatrix();
            float half = (float) PortalGateBlock.OPENING_HALF;
            PortalShader.draw(m, -half, 0.25f, half, 2.75f, 0.0f, 1.0f, 1.0f, PortalShader.MODE_GATE, open, close, 1.0f, t, false);
        }
        matrices.pop();

        if (close > 0.3f) {
            drawOrb(be, tickDelta, matrices, close, t);
        }
    }

    /** The collapsed gate as a glowing orb that flies to whoever is picking it up. */
    private void drawOrb(PortalGateBlockEntity be, float tickDelta, MatrixStack matrices, float close, float t) {
        World world = be.getWorld();
        BlockPos pos = be.getPos();
        Vec3d from = new Vec3d(0.5, 1.5, 0.5);
        Vec3d to = from;
        UUID closer = be.getCloser();
        PlayerEntity player = closer == null ? null : world.getPlayerByUuid(closer);
        if (player != null) {
            to = player.getCameraPosVec(tickDelta).subtract(pos.getX(), pos.getY(), pos.getZ()).add(0.0, -0.35, 0.0);
        }
        float k = MathHelper.clamp((close - 0.3f) / 0.7f, 0.0f, 1.0f);
        float e = k * k * (3.0f - 2.0f * k);
        Vec3d at = from.lerp(to, e).add(0.0, Math.sin(e * Math.PI) * 0.6, 0.0);
        float size = 0.45f * (1.0f - 0.55f * e);
        matrices.push();
        matrices.translate(at.x, at.y, at.z);
        matrices.multiply(MinecraftClient.getInstance().gameRenderer.getCamera().getRotation());
        Matrix4f m = matrices.peek().getPositionMatrix();
        PortalShader.draw(m, -size, -size, size, size, 0.0f, 1.0f, 1.0f, PortalShader.MODE_ORB, 1.0f, 0.0f,
                1.0f - 0.8f * Math.max(0.0f, e - 0.85f) / 0.15f, t, false);
        matrices.pop();
    }

    @Override
    public boolean rendersOutsideBoundingBox(PortalGateBlockEntity be) {
        return true;
    }
}
''', client=True)

J("client/mixin/ClientPlayNetworkHandlerMixin.java", r'''
package @@PKG@@.client.mixin;

import @@PKG@@.client.gate.PortalTransition;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** A dimension change shows "Loading terrain" - except while one of our gates is carrying you: then the vortex covers the screen. */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Redirect(method = "onPlayerRespawn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/MinecraftClient;setScreen(Lnet/minecraft/client/gui/screen/Screen;)V", ordinal = 0))
    private void realityshop$noLoadingScreen(MinecraftClient client, Screen screen) {
        boolean skip = PortalTransition.active();
        PortalTransition.traceDimensionChange(skip);
        if (skip) {
            PortalTransition.onArrived();
        } else {
            client.setScreen(screen);
        }
    }
}
''', client=True)

J("client/mixin/MinecraftClientMixin.java", r'''
package @@PKG@@.client.mixin;

import @@PKG@@.client.gate.PortalTransition;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** joinWorld() forces one frame of a "Joining world" screen; skip it while a gate transition is running (the rest of reset() still happens). */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "reset(Lnet/minecraft/client/gui/screen/Screen;)V", at = @At("HEAD"), cancellable = true)
    private void realityshop$resetWithoutJoiningScreen(Screen screen, CallbackInfo ci) {
        if (PortalTransition.active()) {
            MinecraftClient self = (MinecraftClient) (Object) this;
            self.getSoundManager().stopAll();
            self.cameraEntity = null;
            ci.cancel();
        }
    }
}
''', client=True)

J("client/cinematic/CinematicDirector.java", r'''
package @@PKG@@.client.cinematic;

import com.mojang.brigadier.arguments.StringArgumentType;
import @@PKG@@.RealityShopMod;
import @@PKG@@.command.RealityShopCommand;
import @@PKG@@.registry.ModItems;
import @@PKG@@.shop.ShopOffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.command.CommandSource;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * /cinematic - a client-side showcase: a 360 degree orbit camera flies around every item of the mod (and the Derp Chicken) on a
 * neon stage, one title card each. The player model, the hand, the HUD and the world are hidden for the duration (the camera is
 * detached from the player, the stage is drawn over the world), nothing changes in the world, and it works anywhere.
 * ESC leaves, SPACE skips to the next item. {@code /cinematic <item>} shows one, {@code /cinematic stop} ends it.
 */
public final class CinematicDirector {
    public enum Kind { ITEM, GATE, CHICKEN }

    /** One showcase: what is shown, its title card texts and how the camera flies around it. */
    public record Shot(String id, Kind kind, ItemStack stack, Text title, Text powerLabel, List<Text> lines, Text price,
                       float radius, float height, float scale, float lift, int accent) {}

    public record Pose(Vec3d pos, float yaw, float pitch) {}

    public static final double INTRO_SECONDS = 2.6;
    /** Seconds per item (the property is for the automated client test). */
    public static final double SHOT_SECONDS = shotSeconds();
    private static final double FADE_SECONDS = Math.min(0.7, SHOT_SECONDS * 0.2);
    /** The item floats this far above the player's feet; the floor ring lies 1.65 blocks under it. */
    public static final double CENTER_HEIGHT = 2.4;

    private static boolean active;
    private static boolean pending;
    private static String pendingOnly;
    private static long startNanos;
    private static List<Shot> shots = List.of();
    private static Vec3d origin = Vec3d.ZERO;
    private static float startYaw;
    private static Perspective savedPerspective = Perspective.FIRST_PERSON;
    private static boolean savedHudHidden;
    private static int autoTicks;
    private static boolean autoStarted;

    private CinematicDirector() {}

    private static double shotSeconds() {
        try {
            return Math.max(1.5, Double.parseDouble(System.getProperty("realityshop.cinematicSeconds", "7.5")));
        } catch (NumberFormatException e) {
            return 7.5;
        }
    }

    // ------------------------------------------------------------------------------------------ wiring
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("cinematic")
                        .executes(ctx -> {
                            requestStart(null);
                            return 1;
                        })
                        .then(ClientCommandManager.argument("item", StringArgumentType.word())
                                .suggests((ctx, builder) -> CommandSource.suggestMatching(ids(), builder))
                                .executes(ctx -> {
                                    String what = StringArgumentType.getString(ctx, "item");
                                    if (what.equalsIgnoreCase("stop")) {
                                        stop();
                                        return 1;
                                    }
                                    if (!requestStart(what)) {
                                        ctx.getSource().sendError(Text.translatable("cinematic.realityshop.unknown", what));
                                        return 0;
                                    }
                                    return 1;
                                }))));
        ClientTickEvents.END_CLIENT_TICK.register(CinematicDirector::tick);
        WorldRenderEvents.LAST.register(context -> {
            if (active) {
                CinematicStage.render(context);
            }
        });
    }

    public static List<String> ids() {
        List<String> ids = new ArrayList<>();
        for (ShopOffer offer : ShopOffer.values()) {
            ids.add(offer.id);
        }
        ids.add("black_market_portal");
        ids.add("derp_chicken");
        ids.add("stop");
        return ids;
    }

    private static boolean matches(String id, String query) {
        String q = query.toLowerCase(Locale.ROOT);
        return id.equals(q) || id.contains(q);
    }

    /** Starts on the next tick (a command typed into the chat closes the chat screen right after it ran). */
    public static boolean requestStart(String only) {
        if (only != null && ids().stream().filter(id -> !id.equals("stop")).noneMatch(id -> matches(id, only))) {
            return false;
        }
        pending = true;
        pendingOnly = only;
        return true;
    }

    public static boolean active() {
        return active;
    }

    private static void tick(MinecraftClient client) {
        if (!autoStarted && Boolean.getBoolean("realityshop.cinematic.auto") && client.player != null && client.world != null && ++autoTicks > 100) {
            autoStarted = true;
            requestStart(null);
        }
        if (pending && !active) {
            pending = false;
            if (client.player != null && client.world != null) {
                begin(client, pendingOnly);
            }
        }
        if (!active) {
            return;
        }
        if (client.player == null || client.world == null || !client.player.isAlive() || client.player.hurtTime > 0 || elapsed() >= total()) {
            stop();   // leaving the world, getting hurt, or simply done
        }
    }

    private static void begin(MinecraftClient client, String only) {
        List<Shot> list = buildShots();
        if (only != null) {
            list.removeIf(shot -> !matches(shot.id(), only));
        }
        if (list.isEmpty()) {
            return;
        }
        shots = list;
        origin = client.player.getPos();
        startYaw = client.player.getYaw();
        savedPerspective = client.options.getPerspective();
        savedHudHidden = client.options.hudHidden;
        client.options.setPerspective(Perspective.FIRST_PERSON);   // first person: the player model is never drawn
        client.options.hudHidden = true;
        client.gameRenderer.setRenderHand(false);
        startNanos = System.nanoTime();
        active = true;
        RealityShopMod.LOGGER.info("[cinematic] started: {} shots, {} s each", shots.size(), SHOT_SECONDS);
        client.setScreen(new CinematicScreen());
    }

    public static void stop() {
        pending = false;
        if (!active) {
            return;
        }
        active = false;
        RealityShopMod.LOGGER.info("[cinematic] stopped after {} s", String.format("%.1f", elapsed()));
        MinecraftClient client = MinecraftClient.getInstance();
        client.options.setPerspective(savedPerspective);
        client.options.hudHidden = savedHudHidden;
        client.gameRenderer.setRenderHand(true);
        if (client.currentScreen instanceof CinematicScreen) {
            client.setScreen(null);
        }
    }

    /** SPACE: jump to the start of the next item. */
    public static void skip() {
        double t = elapsed();
        int idx = shotIndex(t);
        double next = idx < 0 ? INTRO_SECONDS : INTRO_SECONDS + (idx + 1) * SHOT_SECONDS;
        startNanos -= (long) ((next - t + 0.05) * 1.0e9);
    }

    // ------------------------------------------------------------------------------------------ the shots
    private static List<Shot> buildShots() {
        List<Shot> list = new ArrayList<>();
        // per item: camera radius, camera height above the item, item scale, item lift, accent colour
        float[][] setup = {
                {3.8f, 0.9f, 2.8f, 0.0f}, {3.8f, 0.9f, 2.6f, 0.0f}, {4.2f, 0.9f, 2.8f, 0.0f},
                {4.4f, 1.0f, 3.0f, 0.0f}, {4.2f, 1.1f, 4.2f, 0.0f}, {4.6f, 1.0f, 3.0f, 0.0f}};
        int[] accents = {0xFF5CE0, 0x6FD0FF, 0xFF8AC8, 0x5CFF7A, 0xFF5050, 0x45F0FF};
        ShopOffer[] offers = ShopOffer.values();
        for (int i = 0; i < offers.length; i++) {
            ShopOffer offer = offers[i];
            ItemStack stack = offer.stack();
            Text price = offer.cost == 0 ? Text.translatable("cinematic.realityshop.free")
                    : Text.translatable("cinematic.realityshop.cost", RealityShopCommand.fmt(offer.cost));
            list.add(new Shot(offer.id, Kind.ITEM, stack, stack.getName(), label(Text.translatable("tooltip.realityshop." + offer.id + ".1")),
                    lines("tooltip.realityshop." + offer.id + ".", 2, 4), price, setup[i][0], setup[i][1], setup[i][2], setup[i][3], accents[i]));
        }
        ItemStack portal = new ItemStack(ModItems.BLACK_MARKET_PORTAL);
        list.add(new Shot("black_market_portal", Kind.GATE, portal, portal.getName(),
                label(Text.translatable("tooltip.realityshop.black_market_portal.1")), lines("tooltip.realityshop.black_market_portal.", 2, 3),
                Text.translatable("cinematic.realityshop.gate_price"), 6.3f, 1.2f, 1.0f, 0.0f, 0xB070FF));
        list.add(new Shot("derp_chicken", Kind.CHICKEN, ItemStack.EMPTY, Text.translatable("entity.realityshop.derp_chicken"),
                label(Text.translatable("cinematic.realityshop.chicken.1")), lines("cinematic.realityshop.chicken.", 2, 4),
                Text.translatable("cinematic.realityshop.chicken_price"), 3.4f, 0.8f, 1.9f, 0.0f, 0xFFA040));
        return list;
    }

    private static List<Text> lines(String prefix, int from, int to) {
        List<Text> out = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            out.add(Text.translatable(prefix + i));
        }
        return out;
    }

    /** "Power: Glitch Mining" -> "P O W E R : G L I T C H   M I N I N G" for the small label above a title. */
    private static Text label(Text text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.getString().toUpperCase(Locale.ROOT).toCharArray()) {
            sb.append(c).append(c == ' ' ? "  " : " ");
        }
        return Text.literal(sb.toString().trim());
    }

    // ------------------------------------------------------------------------------------------ the timeline
    public static List<Shot> shots() {
        return shots;
    }

    public static double elapsed() {
        return (System.nanoTime() - startNanos) / 1.0e9;
    }

    public static double total() {
        return INTRO_SECONDS + shots.size() * SHOT_SECONDS;
    }

    /** -1 during the intro card, then the index of the item being shown. */
    public static int shotIndex(double t) {
        if (shots.isEmpty() || t < INTRO_SECONDS) {
            return -1;
        }
        return Math.min(shots.size() - 1, (int) ((t - INTRO_SECONDS) / SHOT_SECONDS));
    }

    public static double shotTime(double t, int index) {
        return index < 0 ? t : t - INTRO_SECONDS - index * SHOT_SECONDS;
    }

    private static double smooth(double x) {
        x = MathHelper.clamp(x, 0.0, 1.0);
        return x * x * (3.0 - 2.0 * x);
    }

    /** 0 = fully visible .. 1 = black: every shot fades in from and out to black. */
    public static float blackout(double t) {
        int idx = shotIndex(t);
        double local = shotTime(t, idx);
        double length = idx < 0 ? INTRO_SECONDS : SHOT_SECONDS;
        double in = idx < 0 ? 0.7 : FADE_SECONDS;
        double out = idx < 0 ? 0.5 : FADE_SECONDS;
        return (float) (1.0 - Math.min(smooth(local / in), smooth((length - local) / out)));
    }

    // ------------------------------------------------------------------------------------------ the camera
    public static Vec3d center() {
        return origin.add(0.0, CENTER_HEIGHT, 0.0);
    }

    public static Vec3d origin() {
        return origin;
    }

    /** Where the camera is right now: one slow, eased 360 degree orbit per item, rising and falling a little. */
    public static Pose pose() {
        double t = elapsed();
        int idx = shotIndex(t);
        Vec3d center = center();
        double theta = Math.toRadians(startYaw);
        double radius;
        double height;
        if (idx < 0) {                                       // the intro drifts past the empty stage
            radius = 7.5;
            height = 1.2;
            theta += 0.6 * (t / INTRO_SECONDS);
        } else {
            Shot shot = shots.get(idx);
            double u = MathHelper.clamp(shotTime(t, idx) / SHOT_SECONDS, 0.0, 1.0);
            double eased = 0.7 * u + 0.3 * (u * u * (3.0 - 2.0 * u));
            theta += idx * 1.1 + Math.PI * 2.0 * eased;
            radius = shot.radius() * (1.0 + 0.07 * Math.sin(Math.PI * 2.0 * u + 1.0));
            height = shot.height() + 0.35 * Math.sin(Math.PI * 2.0 * u);
        }
        Vec3d pos = center.add(radius * Math.sin(theta), height, -radius * Math.cos(theta));
        float pitch = (float) Math.toDegrees(Math.atan2(pos.y - (center.y + 0.1), radius));
        return new Pose(pos, (float) Math.toDegrees(theta), pitch);
    }
}
''', client=True)

J("client/cinematic/CinematicStage.java", r'''
package @@PKG@@.client.cinematic;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import @@PKG@@.client.gate.PortalShader;
import @@PKG@@.entity.DerpChickenEntity;
import @@PKG@@.registry.ModBlocks;
import @@PKG@@.registry.ModEntities;
import @@PKG@@.shop.PortalGateBlock;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * The /cinematic studio, drawn at the very end of the world pass: a full-screen backdrop (it hides the world), then - on a
 * fresh depth buffer - a glowing floor, the showcased thing floating and turning above it, and drifting light motes.
 */
public final class CinematicStage {
    private static DerpChickenEntity chicken;
    private static ClientWorld chickenWorld;

    private CinematicStage() {}

    public static void render(WorldRenderContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || !PortalShader.ready()) {
            return;
        }
        double t = CinematicDirector.elapsed();
        float time = (float) (t % 3600.0);
        int idx = CinematicDirector.shotIndex(t);
        CinematicDirector.Shot shot = idx < 0 || CinematicDirector.shots().isEmpty() ? null : CinematicDirector.shots().get(idx);

        backdrop(client, time);

        MatrixStack matrices = ctx.matrixStack();
        Camera camera = ctx.camera();
        Vec3d cam = camera.getPos();
        Vec3d center = CinematicDirector.center();
        float floorY = (float) (center.y - 1.65);
        int accent = shot == null ? 0xB070FF : shot.accent();

        // the floor: a dark glossy disc with neon rings
        matrices.push();
        matrices.translate(center.x - cam.x, floorY - cam.y, center.z - cam.z);
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90.0f));
        PortalShader.draw(matrices.peek().getPositionMatrix(), -8.0f, -8.0f, 8.0f, 8.0f, 0.0f, 1.0f, 1.0f, PortalShader.MODE_FLOOR, 1.0f, 0.0f, 1.0f, time, false);
        matrices.pop();

        // a soft halo behind the showcased thing
        tint(accent, 1.0f);
        billboard(matrices, camera, center.subtract(cam), 2.3f, 0.42f, time);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        if (shot != null) {
            switch (shot.kind()) {
                case ITEM -> item(client, matrices, cam, center, shot, time);
                case GATE -> gate(client, matrices, cam, center, time);
                case CHICKEN -> chicken(client, matrices, cam, center, time);
            }
        }
        motes(matrices, camera, cam, center, time, accent);
    }

    // ------------------------------------------------------------------------------------------ the backdrop
    private static void backdrop(MinecraftClient client, float time) {
        Matrix4f projection = RenderSystem.getProjectionMatrix();
        VertexSorter sorter = RenderSystem.getVertexSorting();
        RenderSystem.setProjectionMatrix(new Matrix4f(), VertexSorter.BY_Z);
        MatrixStack stack = RenderSystem.getModelViewStack();
        stack.push();
        stack.loadIdentity();
        RenderSystem.applyModelViewMatrix();
        float aspect = (float) client.getWindow().getFramebufferWidth() / Math.max(1, client.getWindow().getFramebufferHeight());
        PortalShader.draw(new Matrix4f(), -1.0f, -1.0f, 1.0f, 1.0f, 0.0f, aspect, 1.0f, PortalShader.MODE_BACKDROP, 1.0f, 0.0f, 1.0f, time, true);
        stack.pop();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(projection, sorter);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);   // the world's depth must not hide the stage
    }

    // ------------------------------------------------------------------------------------------ the showcased things
    private static void item(MinecraftClient client, MatrixStack matrices, Vec3d cam, Vec3d center, CinematicDirector.Shot shot, float time) {
        VertexConsumerProvider.Immediate consumers = client.getBufferBuilders().getEntityVertexConsumers();
        matrices.push();
        matrices.translate(center.x - cam.x, center.y + shot.lift() + 0.07 * Math.sin(time * 1.3) - cam.y, center.z - cam.z);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees((time * 14.0f) % 360.0f));
        matrices.scale(shot.scale(), shot.scale(), shot.scale());
        client.getItemRenderer().renderItem(shot.stack(), ModelTransformationMode.FIXED, LightmapTextureManager.MAX_LIGHT_COORDINATE,
                OverlayTexture.DEFAULT_UV, matrices, consumers, client.world, 0);
        consumers.draw();
        matrices.pop();
    }

    /** The real gate: its nine block-model cells plus the shader vortex, standing on the floor. */
    private static void gate(MinecraftClient client, MatrixStack matrices, Vec3d cam, Vec3d center, float time) {
        VertexConsumerProvider.Immediate consumers = client.getBufferBuilders().getEntityVertexConsumers();
        double gx = center.x;
        double gy = center.y - 1.6;
        double gz = center.z;
        for (int l = 0; l < 3; l++) {
            for (int h = 0; h < 3; h++) {
                BlockState state = ModBlocks.BLACK_MARKET_PORTAL.getDefaultState().with(PortalGateBlock.FACING, Direction.SOUTH)
                        .with(PortalGateBlock.LATERAL, l).with(PortalGateBlock.HEIGHT, h);
                matrices.push();
                matrices.translate(gx - cam.x + (l - 1) - 0.5, gy - cam.y + h, gz - cam.z - 0.5);
                client.getBlockRenderManager().renderBlockAsEntity(state, matrices, consumers, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV);
                matrices.pop();
            }
        }
        consumers.draw();
        matrices.push();
        matrices.translate(gx - cam.x, gy - cam.y, gz - cam.z);
        float half = (float) PortalGateBlock.OPENING_HALF;
        PortalShader.draw(matrices.peek().getPositionMatrix(), -half, 0.25f, half, 2.75f, 0.0f, 1.0f, 1.0f, PortalShader.MODE_GATE, 1.0f, 0.0f, 1.0f, time, false);
        matrices.pop();
    }

    private static void chicken(MinecraftClient client, MatrixStack matrices, Vec3d cam, Vec3d center, float time) {
        if (chicken == null || chickenWorld != client.world) {
            chickenWorld = client.world;
            chicken = ModEntities.DERP_CHICKEN.create(client.world);
        }
        if (chicken == null) {
            return;
        }
        VertexConsumerProvider.Immediate consumers = client.getBufferBuilders().getEntityVertexConsumers();
        chicken.age = (int) (time * 20.0f);
        chicken.bodyYaw = chicken.prevBodyYaw = 0.0f;
        chicken.setPosition(center.x, center.y - 1.35, center.z);      // the renderer picks its mesh detail by this distance
        matrices.push();
        matrices.translate(center.x - cam.x, center.y - 1.35 + 0.05 * Math.sin(time * 1.6) - cam.y, center.z - cam.z);
        matrices.scale(1.9f, 1.9f, 1.9f);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees((time * 12.0f) % 360.0f));
        client.getEntityRenderDispatcher().render(chicken, 0.0, 0.0, 0.0, 0.0f, 1.0f, matrices, consumers, LightmapTextureManager.MAX_LIGHT_COORDINATE);
        consumers.draw();
        matrices.pop();
    }

    // ------------------------------------------------------------------------------------------ glow
    private static void tint(int rgb, float alpha) {
        RenderSystem.setShaderColor(((rgb >> 16) & 255) / 255.0f, ((rgb >> 8) & 255) / 255.0f, (rgb & 255) / 255.0f, alpha);
    }

    /** A camera-facing glow disc (the shader's orb) at {@code at} (relative to the camera). */
    private static void billboard(MatrixStack matrices, Camera camera, Vec3d at, float size, float alpha, float time) {
        matrices.push();
        matrices.translate(at.x, at.y, at.z);
        matrices.multiply(camera.getRotation());
        PortalShader.draw(matrices.peek().getPositionMatrix(), -size, -size, size, size, 0.0f, 1.0f, 1.0f, PortalShader.MODE_ORB, 1.0f, 0.0f, alpha, time, false);
        matrices.pop();
    }

    private static float hash(int i, int salt) {
        int h = i * 73856093 ^ salt * 19349663;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return (h & 0xFFFF) / 65535.0f;
    }

    /** Drifting light motes (fireflies) around the stage, tinted with the accent colour. */
    private static void motes(MatrixStack matrices, Camera camera, Vec3d cam, Vec3d center, float time, int accent) {
        tint(accent, 1.0f);
        for (int i = 0; i < 34; i++) {
            float angle = hash(i, 1) * 6.2831855f + time * (0.12f + 0.3f * hash(i, 2)) * (i % 2 == 0 ? 1.0f : -1.0f);
            float radius = 1.4f + 4.2f * hash(i, 3);
            float y = -1.5f + 3.8f * hash(i, 4) + 0.3f * (float) Math.sin(time * 0.9f + i);
            Vec3d at = new Vec3d(center.x + Math.cos(angle) * radius - cam.x, center.y + y - cam.y, center.z + Math.sin(angle) * radius - cam.z);
            float twinkle = 0.55f + 0.45f * (float) Math.sin(time * (1.5f + hash(i, 5) * 2.0f) + i);
            billboard(matrices, camera, at, 0.05f + 0.11f * hash(i, 6), 0.75f * twinkle, time);
        }
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }
}
''', client=True)

J("client/cinematic/CinematicScreen.java", r'''
package @@PKG@@.client.cinematic;

import java.util.List;
import java.util.Locale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * The cinematic's overlay: letterbox bars, the title card of the item being shown, a progress row and the fades. Being a screen
 * it also stops every player control while the camera flies; ESC leaves, SPACE skips to the next item. Single-player pauses meanwhile.
 */
public class CinematicScreen extends Screen {
    public CinematicScreen() {
        super(Text.empty());
    }

    @Override
    protected void init() {
        GLFW.glfwSetInputMode(MinecraftClient.getInstance().getWindow().getHandle(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN);
    }

    /** Single-player pauses (vanilla only does so when nobody else is in the world): nothing can hurt you while the camera flies. */
    @Override
    public boolean shouldPause() {
        return true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            CinematicDirector.stop();
        } else if (keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_RIGHT) {
            CinematicDirector.skip();
        }
        return true;
    }

    @Override
    public void removed() {
        GLFW.glfwSetInputMode(MinecraftClient.getInstance().getWindow().getHandle(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        if (CinematicDirector.active()) {
            CinematicDirector.stop();   // closed by anything else (a death screen, a disconnect ...): the cinematic ends with it
        }
    }

    private static int argb(int rgb, float alpha) {
        return ((int) (Math.max(0.0f, Math.min(1.0f, alpha)) * 255.0f) << 24) | (rgb & 0xFFFFFF);
    }

    private void text(DrawContext ctx, Text text, float x, float y, float scale, int rgb, float alpha) {
        if (alpha < 0.03f) {
            return;
        }
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(x, y, 0.0f);
        m.scale(scale, scale, 1.0f);
        ctx.drawText(textRenderer, text, 0, 0, argb(rgb, alpha), true);
        m.pop();
    }

    private float width(Text text, float scale) {
        return textRenderer.getWidth(text) * scale;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        double t = CinematicDirector.elapsed();
        int idx = CinematicDirector.shotIndex(t);
        float black = CinematicDirector.blackout(t);
        float vis = 1.0f - black;
        int w = width;
        int h = height;
        int bar = Math.max(h / 12, (int) ((h - w / 2.39) / 2.0));
        ctx.fill(0, 0, w, bar, 0xFF000000);
        ctx.fill(0, h - bar, w, h, 0xFF000000);

        if (idx < 0) {
            Text title = Text.translatable("cinematic.realityshop.intro");
            Text tagline = Text.translatable("cinematic.realityshop.tagline");
            float k = 5.0f;
            float x = (w - width(title, k)) / 2.0f;
            float y = h / 2.0f - 22.0f;
            text(ctx, title, x + 2.0f, y + 2.0f, k, 0x45F0FF, vis * 0.55f);
            text(ctx, title, x, y, k, 0xFF5CE0, vis);
            text(ctx, tagline, (w - width(tagline, 1.2f)) / 2.0f, y + 8.0f * k + 10.0f, 1.2f, 0xD8D8EE, vis);
        } else {
            CinematicDirector.Shot shot = CinematicDirector.shots().get(idx);
            double local = CinematicDirector.shotTime(t, idx);
            float x = Math.max(24.0f, w * 0.05f);
            float y = h - bar - 80.0f;
            text(ctx, shot.powerLabel(), x, y, 1.0f, shot.accent(), vis);
            Text title = Text.literal(shot.title().getString().toUpperCase(Locale.ROOT));
            text(ctx, title, x, y + 13.0f, 2.8f, 0xFFFFFF, vis);
            List<Text> lines = shot.lines();
            if (!lines.isEmpty()) {
                Text line = lines.get((int) (local / 1.9) % lines.size());
                text(ctx, line, x, y + 13.0f + 8.0f * 2.8f + 8.0f, 1.0f, 0xC8C8DC, vis);
            }
            text(ctx, shot.price(), w - x - width(shot.price(), 1.2f), y - 2.0f, 1.2f, shot.accent(), vis);
        }

        // progress row (one dot per item) and the key hints inside the bottom bar
        List<CinematicDirector.Shot> shots = CinematicDirector.shots();
        int dots = shots.size();
        for (int i = 0; i < dots; i++) {
            int dx = w / 2 - dots * 7 + i * 14;
            int dy = h - bar / 2;
            boolean now = i == idx;
            ctx.fill(dx, dy - (now ? 2 : 1), dx + (now ? 8 : 4), dy + (now ? 2 : 1), argb(now ? shots.get(i).accent() : 0x707080, vis * (now ? 1.0f : 0.8f)));
        }
        if (t < 6.0) {
            Text hint = Text.translatable("cinematic.realityshop.hint");
            text(ctx, hint, w - width(hint, 1.0f) - 14.0f, h - bar / 2.0f - 4.0f, 1.0f, 0x9090A8, (float) Math.min(1.0, (6.0 - t) / 1.5) * vis);
        }

        if (black > 0.002f) {
            ctx.fill(0, 0, w, h, argb(0x000000, black));
        }
    }
}
''', client=True)

J("client/mixin/CameraMixin.java", r'''
package @@PKG@@.client.mixin;

import @@PKG@@.client.cinematic.CinematicDirector;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** /cinematic: while it runs, the camera is flown by the director instead of following the player (who stays unseen: first person). */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    protected abstract void setPos(double x, double y, double z);

    @Inject(method = "update", at = @At("TAIL"))
    private void realityshop$cinematicCamera(BlockView area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo ci) {
        if (CinematicDirector.active()) {
            CinematicDirector.Pose pose = CinematicDirector.pose();
            setRotation(pose.yaw(), pose.pitch());
            setPos(pose.pos().x, pose.pos().y, pose.pos().z);
        }
    }
}
''', client=True)

# @@INSERT:JAVA@@  (Java templates are inserted above this line)


def gen_java(out, opts):
    for rel, (src, client) in sorted(JAVA_SOURCES.items()):
        out.java(rel, src.lstrip("\n"), client=client)


# ============================================================================ data pack: dimensions, recipe, loot
def gen_data(out, opts):
    flat_void = {"type": "minecraft:flat", "settings": {
        "biome": "minecraft:the_void", "features": False, "lakes": False, "layers": [], "structure_overrides": []}}
    common = {"ultrawarm": False, "natural": False, "coordinate_scale": 1.0, "piglin_safe": False, "bed_works": False,
              "respawn_anchor_works": False, "has_raids": False, "logical_height": 256, "min_y": 0, "height": 256,
              "infiniburn": "#minecraft:infiniburn_overworld", "monster_spawn_light_level": 0,
              "monster_spawn_block_light_limit": 0}
    out.data_json("dimension_type/black_market.json", dict(common, has_skylight=False, has_ceiling=True, ambient_light=0.15,
                                                          fixed_time=18000, effects="minecraft:overworld"))
    out.data_json("dimension_type/bedrock_realm.json", dict(common, has_skylight=False, has_ceiling=False, ambient_light=0.4,
                                                          effects="minecraft:the_end"))
    out.data_json("dimension/black_market.json", {"type": MOD_ID + ":black_market", "generator": flat_void})
    bedrock_flat = {"type": "minecraft:flat", "settings": {
        "biome": "minecraft:the_void", "features": False, "lakes": False,
        "layers": [{"block": "minecraft:bedrock", "height": 5}], "structure_overrides": []}}
    out.data_json("dimension/bedrock_realm.json", {"type": MOD_ID + ":bedrock_realm", "generator": bedrock_flat})
    out.data_json("recipes/black_market_portal.json", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": ["COC", "OEO", "COC"],
        "key": {"C": {"item": "minecraft:crying_obsidian"}, "O": {"item": "minecraft:obsidian"},
                "E": {"item": "minecraft:ender_pearl"}},
        "result": {"item": MOD_ID + ":black_market_portal", "count": 1},
    })
    for name in ("hacked_rgb_furnace", "beacon_5g"):
        out.data_json("loot_tables/blocks/%s.json" % name, {
            "type": "minecraft:block",
            "pools": [{"rolls": 1.0, "bonus_rolls": 0.0,
                       "entries": [{"type": "minecraft:item", "name": "%s:%s" % (MOD_ID, name)}],
                       "conditions": [{"condition": "minecraft:survives_explosion"}]}],
        })


# ============================================================================ language, sounds, particles
LANG = {
    "itemGroup.realityshop": "RealityShop",
    "item.realityshop.glitch_pickaxe": "Glitch Pickaxe",
    "item.realityshop.glitchy_floppy": "Glitchy Floppy",
    "item.realityshop.cursed_kitty_phone": "Cursed Kitty Phone",
    "item.realityshop.quantum_usb": "Quantum USB",
    "item.realityshop.derp_chicken_spawn_egg": "Derp Chicken Spawn Egg",
    "block.realityshop.hacked_rgb_furnace": "Hacked RGB Furnace",
    "block.realityshop.beacon_5g": "Beacon 5G",
    "block.realityshop.black_market_portal": "Black Market Portal",
    "item.realityshop.black_market_portal": "Black Market Portal",
    "block.realityshop.shop_pedestal": "Shop Pedestal",
    "entity.realityshop.derp_chicken": "Derp Chicken",
    "entity.realityshop.floppy_projectile": "Glitchy Floppy",
    "entity.realityshop.sucked_block": "Sucked Up Block",
    "hud.realityshop.consumed": "Blocks Consumed: %s",
    "hud.realityshop.blocks": "Blocks",
    "cinematic.realityshop.intro": "REALITYSHOP",
    "cinematic.realityshop.tagline": "Consume the world. Spend it in the Black Market.",
    "cinematic.realityshop.hint": "ESC  exit     SPACE  next",
    "cinematic.realityshop.cost": "COST  %s  BLOCKS",
    "cinematic.realityshop.free": "FREE",
    "cinematic.realityshop.gate_price": "STARTER GIFT - OR CRAFT IT",
    "cinematic.realityshop.chicken_price": "SPAWN EGG - CREATIVE TAB",
    "cinematic.realityshop.chicken.1": "Power: Derp Rain",
    "cinematic.realityshop.chicken.2": "Summon one with its spawn egg.",
    "cinematic.realityshop.chicken.3": "Every Glitchy Floppy impact rains five of them.",
    "cinematic.realityshop.chicken.4": "273,114 triangles of pure chaos.",
    "cinematic.realityshop.unknown": "No such showcase: %s",
    "tooltip.realityshop.glitch_pickaxe.1": "Power: Glitch Mining",
    "tooltip.realityshop.glitch_pickaxe.2": "Breaks anything instantly - even obsidian.",
    "tooltip.realityshop.glitch_pickaxe.3": "Takes the whole 3x3x3 around the block with it.",
    "tooltip.realityshop.glitch_pickaxe.4": "Everything you mine is added to Blocks Consumed.",
    "tooltip.realityshop.glitchy_floppy.1": "Power: Glitch Throw",
    "tooltip.realityshop.glitchy_floppy.2": "Thrown: erases a 5x5x5 cube where it lands.",
    "tooltip.realityshop.glitchy_floppy.3": "Rains Derp Chickens out of the hole.",
    "tooltip.realityshop.glitchy_floppy.4": "A glow keeps the hole from going black.",
    "tooltip.realityshop.cursed_kitty_phone.1": "Power: Cursed Pit",
    "tooltip.realityshop.cursed_kitty_phone.2": "Right-click the ground: a 50x20x50 pit vanishes at once.",
    "tooltip.realityshop.cursed_kitty_phone.3": "It opens in FRONT of you - you keep your footing.",
    "tooltip.realityshop.cursed_kitty_phone.4": "The kitty screams. Loudly.",
    "tooltip.realityshop.hacked_rgb_furnace.1": "Power: Green Laser Eater",
    "tooltip.realityshop.hacked_rgb_furnace.2": "Place it: eats a radius-100 half-sphere in front of you.",
    "tooltip.realityshop.hacked_rgb_furnace.3": "Fires green lasers at everything it consumes (10 s).",
    "tooltip.realityshop.hacked_rgb_furnace.4": "Right-click it to run it again.",
    "tooltip.realityshop.beacon_5g.1": "Power: Block Tornado",
    "tooltip.realityshop.beacon_5g.2": "Place it: a red tornado vacuums the area ahead (radius 200).",
    "tooltip.realityshop.beacon_5g.3": "Slow and relentless - about a minute for everything.",
    "tooltip.realityshop.beacon_5g.4": "Right-click it to run it again.",
    "tooltip.realityshop.quantum_usb.1": "Power: Quantum Purge",
    "tooltip.realityshop.quantum_usb.2": "Right-click: a 1475x1475 area in front of you is purged in 10 s.",
    "tooltip.realityshop.quantum_usb.3": "Then it pulls you into the Bedrock Realm.",
    "tooltip.realityshop.quantum_usb.4": "There is no way back - except respawning.",
    "tooltip.realityshop.black_market_portal.1": "Power: Reality Gate",
    "tooltip.realityshop.black_market_portal.2": "Place it, walk through - you stand in the Black Market.",
    "tooltip.realityshop.black_market_portal.3": "Right-click the gate to fold it back into your inventory.",
    "subtitles.realityshop.shop_buy": "Black Market purchase",
    "subtitles.realityshop.shop_denied": "Black Market refuses",
    "subtitles.realityshop.kitty_phone_curse": "Cursed Kitty Phone screams",
    "subtitles.realityshop.quantum_purge": "Quantum purge",
    "subtitles.realityshop.portal_open": "Reality Gate opens",
    "subtitles.realityshop.portal_close": "Reality Gate folds away",
}


def gen_lang(out, opts):
    out.asset_json("lang/en_us.json", LANG)


def _custom_sound(opts, event):
    """The user's own .ogg (assets/overrides/assets/realityshop/sounds/<event>.ogg) wins over the vanilla fallback."""
    root = os.path.abspath(opts.overrides) if opts.overrides else os.path.join(SCRIPT_DIR, "assets", "overrides")
    return os.path.isfile(os.path.join(root, "assets", MOD_ID, "sounds", event + ".ogg"))


def gen_sounds(out, opts):
    fallback = {
        "shop_buy": [("minecraft:entity.player.levelup", 1.2), ("minecraft:block.amethyst_block.chime", 1.0)],
        "shop_denied": [("minecraft:entity.villager.no", 0.8), ("minecraft:block.note_block.bass", 0.6)],
        "kitty_phone_curse": [("minecraft:entity.cat.hurt", 0.55), ("minecraft:entity.cat.ambient", 0.5),
                              ("minecraft:entity.generic.explode", 0.7)],
        "quantum_purge": [("minecraft:block.portal.travel", 0.6), ("minecraft:entity.enderman.teleport", 0.5)],
        "portal_open": [("minecraft:block.end_portal.spawn", 1.6), ("minecraft:block.beacon.activate", 1.4)],
        "portal_close": [("minecraft:block.beacon.deactivate", 1.5), ("minecraft:block.portal.travel", 1.8)],
    }
    sounds = {}
    for event, options in fallback.items():
        if _custom_sound(opts, event):
            entries = [{"name": "%s:%s" % (MOD_ID, event)}]
        else:
            entries = [{"name": name, "type": "event", "pitch": pitch} for name, pitch in options]
        sounds[event] = {"subtitle": "subtitles.%s.%s" % (MOD_ID, event), "sounds": entries}
    out.asset_json("sounds.json", sounds)
    out.asset_json("particles/green_laser.json", {"textures": ["%s:green_laser" % MOD_ID]})


# ============================================================================ blockstates + models (vanilla JSON; the glTF models are rendered in code)
def _face(tex, tint=None, cull=None):
    f = {"uv": [0, 0, 16, 16], "texture": "#" + tex}
    if tint is not None:
        f["tintindex"] = tint
    if cull:
        f["cullface"] = cull
    return f


def _cuboid(x0, y0, z0, x1, y1, z1, tex, tint=None, faces=None):
    names = ("north", "east", "south", "west", "up", "down")
    fm = {n: _face((faces or {}).get(n, tex), tint) for n in names}
    return {"from": [x0, y0, z0], "to": [x1, y1, z1], "faces": fm}


def _ubox(f, t, tex, faces=None, rotation=None):
    """Cuboid with world-aligned UVs (like vanilla's automatic UVs). `tex` is the texture variable of every face; `faces`
    overrides single faces: a name, None (hidden) or (name, [u0, v0, u1, v1], rotation?) for an explicit UV window."""
    (x0, y0, z0), (x1, y1, z1) = f, t
    uv = {"south": [x0, 16 - y1, x1, 16 - y0], "north": [16 - x1, 16 - y1, 16 - x0, 16 - y0],
          "east": [16 - z1, 16 - y1, 16 - z0, 16 - y0], "west": [z0, 16 - y1, z1, 16 - y0],
          "up": [x0, z0, x1, z1], "down": [x0, 16 - z1, x1, 16 - z0]}
    fm = {}
    for n in ("north", "east", "south", "west", "up", "down"):
        name = (faces or {}).get(n, tex)
        if name is None:
            continue
        if isinstance(name, tuple):
            ent = {"uv": list(name[1]), "texture": "#" + name[0]}
            if len(name) > 2:
                ent["rotation"] = name[2]
            fm[n] = ent
        else:
            fm[n] = {"uv": [round(max(0.0, min(16.0, c)), 3) for c in uv[n]], "texture": "#" + name}
    el = {"from": [x0, y0, z0], "to": [x1, y1, z1], "faces": fm}
    if rotation:
        el["rotation"] = rotation
    return el


USB_SCALE = 2.95        # the Quantum USB is this much bigger than its original design


_GATE_CELLS = [(l, h) for l in range(3) for h in range(3)]


def _gate_clip(box, tex, faces=None):
    """A box in the gate's 48 x 48 px frame (3 x 3 cells) cut into the cells it touches (the top row also takes what sticks
    out above the gate); faces on the cuts are dropped."""
    (gx0, gy0, z0), (gx1, gy1, z1) = box
    out = {}
    for l, h in _GATE_CELLS:
        cx0, cy0 = l * 16, h * 16
        x0, x1 = max(gx0, cx0), min(gx1, cx0 + 16)
        y0, y1 = max(gy0, cy0), min(gy1, cy0 + (32 if h == 2 else 16))
        if x1 <= x0 or y1 <= y0:
            continue
        f = dict(faces or {})
        if x0 > gx0:
            f["west"] = None
        if x1 < gx1:
            f["east"] = None
        if y0 > gy0:
            f["down"] = None
        if y1 < gy1 and h != 2:
            f["up"] = None
        out[(l, h)] = _ubox((x0 - cx0, y0 - cy0, z0), (x1 - cx0, y1 - cy0, z1), tex, f)
    return out


def _gate_crystal(cell, x, y, z, width, height, angle):
    """An amethyst spike standing on (x, y) of a cell, leaning `angle` degrees around the depth axis."""
    return {cell: _ubox((x - width / 2, y, z), (x + width / 2, y + height, z + width), "ame", {"down": None},
                        rotation={"origin": [x, y, z + width / 2], "axis": "z", "angle": angle})}


def _gate_cell_elements():
    """The Reality Gate's frame: a THIN ring (4 px bars) around a 40 x 40 px opening, crying-obsidian corner stones with glowing
    eyes, a glowing inlay along the inside, glow nodes, feet, a crest and amethyst crystals. Authored for a gate whose front
    faces SOUTH; x runs left -> right as seen from the front."""
    cells = {c: [] for c in _GATE_CELLS}

    def add(d):
        for c, el in d.items():
            cells[c].append(el)

    z0, z1, b = 3.5, 12.5, 4.0
    e = 48.0 - b
    line_v = ("line", (7, 0, 8, 16))
    line_h = ("line", (7, 0, 8, 16), 90)
    dot = ("glow", (0, 0, 16, 16))
    none = {"up": None, "down": None, "east": None, "west": None, "north": None, "south": None}

    def only(**kw):
        d = dict(none)
        d.update(kw)
        return d

    add(_gate_clip(((0, 0, z0), (b, 48, z1)), "bricks", {"east": "plain", "up": "plain", "down": "plain"}))
    add(_gate_clip(((e, 0, z0), (48, 48, z1)), "bricks", {"west": "plain", "up": "plain", "down": "plain"}))
    add(_gate_clip(((b, 0, z0), (e, b, z1)), "plain", {"up": "plain", "south": "bricks", "north": "bricks"}))
    add(_gate_clip(((b, e, z0), (e, 48, z1)), "plain", {"up": "top", "south": "bricks", "north": "bricks"}))
    add(_gate_clip(((b, b, 5.0), (b + 1, e, 11.0)), "line", only(south=line_v, north=line_v, east=line_v)))
    add(_gate_clip(((e - 1, b, 5.0), (e, e, 11.0)), "line", only(south=line_v, north=line_v, west=line_v)))
    add(_gate_clip(((b + 1, b, 5.0), (e - 1, b + 1, 11.0)), "line", only(south=line_h, north=line_h, up=line_h)))
    add(_gate_clip(((b + 1, e - 1, 5.0), (e - 1, e, 11.0)), "line", only(south=line_h, north=line_h, down=line_h)))
    for cx, cy in ((0, 0), (41, 0), (0, 41), (41, 41)):                              # corner stones with a glowing eye
        add(_gate_clip(((cx, cy, 2.5), (cx + 7, cy + 7, 13.5)), "cry"))
        add(_gate_clip(((cx + 2, cy + 2, 13.5), (cx + 5, cy + 5, 13.9)), "glow", only(south=dot)))
        add(_gate_clip(((cx + 2, cy + 2, 2.1), (cx + 5, cy + 5, 2.5)), "glow", only(north=dot)))
    for cx, cy in ((22, 0), (22, 44), (0, 22), (44, 22)):                            # glow nodes in the middle of each side
        add(_gate_clip(((cx, cy, 12.5), (cx + 4, cy + 4, 12.9)), "glow", only(south=dot)))
        add(_gate_clip(((cx, cy, 3.1), (cx + 4, cy + 4, 3.5)), "glow", only(north=dot)))
    add(_gate_clip(((0, 0, 1.5), (16, 4, 14.5)), "plain", {"up": "top"}))            # feet
    add(_gate_clip(((32, 0, 1.5), (48, 4, 14.5)), "plain", {"up": "top"}))
    add(_gate_clip(((17, 48, 4.5), (31, 51, 11.5)), "gild", {"up": "plain", "down": None}))                 # crest
    add(_gate_clip(((20, 51, 5.0), (28, 55, 11.0)), "bricks", {"up": "plain", "down": None}))
    add(_gate_clip(((22, 52, 11.0), (26, 56, 11.4)), "glow", only(south=dot)))
    add(_gate_clip(((22, 52, 4.6), (26, 56, 5.0)), "glow", only(north=dot)))
    for cell, base_x, lean in (((0, 2), 3.5, -1), ((2, 2), 12.5, 1)):                # crystals on the top corners ...
        for dx, hh, ang in ((0.0, 8.0, 22.5 * lean), (3.4 * -lean, 11.0, 0.0), (6.8 * -lean, 6.0, -22.5 * lean)):
            add(_gate_crystal(cell, base_x + dx, 16.0, 6.5, 3.0, hh, ang))
    for cell, base_x, lean in (((0, 0), 4.0, -1), ((2, 0), 12.0, 1)):                # ... and on the feet
        for dx, hh, ang in ((0.0, 7.0, 22.5 * lean), (3.2 * -lean, 5.0, 0.0)):
            add(_gate_crystal(cell, base_x + dx, 4.0, 6.5, 3.0, hh, ang))
    return cells


def _gen_gate_models(block, item, blockstate):
    """The 3 x 3 Reality Gate: nine cell models (the middle one is empty: the swirl itself is drawn by a shader). The blockstate
    rotates the south-facing models for the other facings."""
    m = MOD_ID + ":"
    textures = {"particle": "minecraft:block/polished_blackstone_bricks", "bricks": "minecraft:block/polished_blackstone_bricks",
                "plain": "minecraft:block/polished_blackstone", "top": "minecraft:block/blackstone_top",
                "gild": "minecraft:block/gilded_blackstone", "cry": "minecraft:block/crying_obsidian",
                "ame": "minecraft:block/amethyst_block", "line": m + "block/gate_line", "glow": m + "block/gate_glow"}
    cells = _gate_cell_elements()
    for (lateral, height), elements in cells.items():
        used = {"particle"}
        for el in elements:
            for face in el["faces"].values():
                used.add(face["texture"][1:])
        block("gate_cell_%d_%d" % (lateral, height), {"textures": {k: v for k, v in textures.items() if k in used}, "elements": elements})
    variants = {}
    for facing, y in (("south", 0), ("west", 90), ("north", 180), ("east", 270)):
        for (lateral, height) in _GATE_CELLS:
            entry = {"model": m + "block/gate_cell_%d_%d" % (lateral, height)}
            if y:
                entry["y"] = y
            variants["facing=%s,height=%d,lateral=%d" % (facing, height, lateral)] = entry
    blockstate("black_market_portal", {"variants": variants})
    item("black_market_portal", {"parent": "minecraft:item/generated", "textures": {"layer0": m + "item/black_market_portal"}})


def gen_models_json(out, opts):
    def block(name, doc):
        out.asset_json("models/block/%s.json" % name, doc)

    def item(name, doc):
        out.asset_json("models/item/%s.json" % name, doc)

    def blockstate(name, doc):
        out.asset_json("blockstates/%s.json" % name, doc)

    m = MOD_ID + ":"
    # ---- the Reality Gate (3 x 3 multiblock)
    _gen_gate_models(block, item, blockstate)

    # ---- shop pedestal
    blockstate("shop_pedestal", {"variants": {"": {"model": m + "block/shop_pedestal"}}})
    block("shop_pedestal", {
        "parent": "minecraft:block/block",
        "textures": {"particle": "minecraft:block/crying_obsidian", "base": "minecraft:block/obsidian",
                     "shaft": "minecraft:block/crying_obsidian", "top": m + "block/shop_pedestal_top"},
        "elements": [
            _cuboid(1, 0, 1, 15, 2, 15, "base"),
            _cuboid(4, 2, 4, 12, 11, 12, "shaft"),
            _cuboid(2, 11, 2, 14, 14, 14, "shaft", faces={"up": "top"}),
        ]})
    item("shop_pedestal", {"parent": m + "block/shop_pedestal"})

    # ---- hacked RGB furnace: the vanilla furnace + a green-tinted glow shell (tintindex 0 -> ColorProvider)
    blockstate("hacked_rgb_furnace", {"variants": {
        "facing=north": {"model": m + "block/hacked_rgb_furnace"},
        "facing=east": {"model": m + "block/hacked_rgb_furnace", "y": 90},
        "facing=south": {"model": m + "block/hacked_rgb_furnace", "y": 180},
        "facing=west": {"model": m + "block/hacked_rgb_furnace", "y": 270}}})
    fins = [_ubox((1.5, 16, z - 0.45), (14.5, 19.5, z + 0.45), "iron") for z in (2.5, 5.25, 8.0, 10.75, 13.5)]
    block("hacked_rgb_furnace", {
        "parent": "minecraft:block/block",
        "textures": {"particle": "minecraft:block/furnace_top", "front": "minecraft:block/furnace_front",
                     "side": "minecraft:block/furnace_side", "top": "minecraft:block/furnace_top", "iron": "minecraft:block/iron_block",
                     "glow": m + "block/hacked_rgb_furnace_glow"},
        "elements": [
            _cuboid(0, 0, 0, 16, 16, 16, "side", faces={"north": "front", "up": "top", "down": "top"}),
            _ubox((0.6, 12.5, 15.4), (15.4, 15.4, 16.2), "iron"),                    # a heat-sink collar on the back and the fins on top
            *fins,
            _cuboid(1.5, 0.8, -0.35, 14.5, 2.2, 0.0, "glow"),                        # an RGB LED bar under the opening
            _cuboid(-0.35, 4.0, 1.5, 0.0, 5.4, 14.5, "glow"),                        # and one on each side
            _cuboid(16.0, 4.0, 1.5, 16.35, 5.4, 14.5, "glow"),
            _cuboid(-0.05, -0.05, -0.05, 16.05, 16.05, 16.05, "glow", tint=0),
        ]})
    item("hacked_rgb_furnace", {"parent": m + "block/hacked_rgb_furnace"})

    # ---- beacon 5G: the vanilla beacon geometry + a red-tinted glow shell
    blockstate("beacon_5g", {"variants": {"": {"model": m + "block/beacon_5g"}}})
    block("beacon_5g", {
        "parent": "minecraft:block/block",
        "textures": {"particle": "minecraft:block/beacon", "glass": "minecraft:block/glass",
                     "obsidian": "minecraft:block/obsidian", "beacon": "minecraft:block/beacon", "iron": "minecraft:block/iron_block",
                     "lamp": "minecraft:block/redstone_lamp_on", "glow": m + "block/beacon_5g_glow"},
        "elements": [
            _cuboid(0, 0, 0, 16, 16, 16, "glass"),
            _ubox((1, 0, 1), (15, 2.5, 15), "obsidian"),                              # the plinth
            _cuboid(3, 3, 3, 13, 14, 13, "beacon"),                                   # the core
            _ubox((2, 14, 2), (14, 16, 14), "iron"),                                  # a collar on top of the glass
            _ubox((7.0, 16, 7.0), (9.0, 31, 9.0), "iron"),                            # the 5G mast ...
            _ubox((3.5, 21, 7.4), (12.5, 22.4, 8.6), "iron"),                         # ... with two crossbars
            _ubox((7.4, 25, 3.5), (8.6, 26.4, 12.5), "iron"),
            _ubox((2.4, 20.3, 7.0), (4.2, 23.1, 9.0), "lamp"),                        # red lamps on the bar ends and the tip
            _ubox((11.8, 20.3, 7.0), (13.6, 23.1, 9.0), "lamp"),
            _ubox((7.0, 24.3, 2.4), (9.0, 27.1, 4.2), "lamp"),
            _ubox((7.0, 24.3, 11.8), (9.0, 27.1, 13.6), "lamp"),
            _ubox((6.2, 30.5, 6.2), (9.8, 32.0, 9.8), "lamp"),
            _cuboid(-0.05, -0.05, -0.05, 16.05, 16.05, 16.05, "glow", tint=0),
            _cuboid(2.6, 2.6, 2.6, 13.4, 14.4, 13.4, "glow", tint=0),
        ]})
    item("beacon_5g", {"parent": m + "block/beacon_5g", "display": {   # the mast makes the model 2 blocks tall: smaller in every view
        "gui": {"rotation": [28, 38, 0], "translation": [0, -3.5, 0], "scale": [0.33, 0.33, 0.33]},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.2, 0.2, 0.2]},
        "fixed": {"rotation": [0, 180, 0], "translation": [0, -3.5, 0], "scale": [0.26, 0.26, 0.26]},
        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.2, 0.2, 0.2]},
        "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.2, 0.2, 0.2]},
        "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, -1, 0], "scale": [0.24, 0.24, 0.24]},
        "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, -1, 0], "scale": [0.24, 0.24, 0.24]}}})

    # ---- items
    item("glitch_pickaxe", {"parent": "minecraft:item/handheld", "textures": {"layer0": m + "item/glitch_pickaxe"}})
    item("glitchy_floppy", {"parent": "minecraft:item/generated", "textures": {"layer0": m + "item/glitchy_floppy"}})
    item("derp_chicken_spawn_egg", {"parent": "minecraft:item/template_spawn_egg"})
    # the kitty phone is drawn by code from the user's glTF; this model only supplies the display transforms
    item("cursed_kitty_phone", {"parent": "minecraft:builtin/entity", "display": {
        "gui": {"rotation": [15, -25, 0], "translation": [0, 0, 0], "scale": [1.0, 1.0, 1.0]},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.6, 0.6, 0.6]},
        "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1.0, 1.0, 1.0]},
        "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 2.5, 1], "scale": [0.6, 0.6, 0.6]},
        "thirdperson_lefthand": {"rotation": [0, 0, 0], "translation": [0, 2.5, 1], "scale": [0.6, 0.6, 0.6]},
        "firstperson_righthand": {"rotation": [0, -20, 0], "translation": [-2.5, 2.5, 0], "scale": [0.5, 0.5, 0.5]},
        "firstperson_lefthand": {"rotation": [0, 20, 0], "translation": [2.5, 2.5, 0], "scale": [0.5, 0.5, 0.5]},
        "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1, 1, 1]}}})
    # quantum USB: a real 3D cuboid model (body, metal plug, glowing animated LEDs), 2.95x bigger than the original design:
    # the geometry is scaled about the stick's centre (fits the model range of -16..32), the GUI / frame transforms shrink it back
    # so the inventory icon keeps its size while the stick in your hand, on the ground and on other players is 2.95x bigger.
    k, pv = USB_SCALE, (8.0, 7.0, 8.0)

    def big(x0, y0, z0, x1, y1, z1, tex, faces=None):
        f = lambda v, p: round(p + (v - p) * k, 3)
        return _cuboid(f(x0, pv[0]), f(y0, pv[1]), f(z0, pv[2]), f(x1, pv[0]), f(y1, pv[1]), f(z1, pv[2]), tex, faces=faces)

    item("quantum_usb", {
        "gui_light": "side",   # NO item/generated parent: that one ignores "elements" and would draw nothing
        "textures": {"particle": m + "item/quantum_usb_body", "body": m + "item/quantum_usb_body",
                     "plug": m + "item/quantum_usb_plug", "led": m + "item/quantum_usb_led",
                     "label": m + "item/quantum_usb_label"},
        "elements": [
            big(5.5, 1, 6.5, 10.5, 9, 9.5, "body", faces={"south": "label", "north": "label"}),
            big(5, 0, 6, 11, 1, 10, "body"),
            big(6.5, 9, 7, 9.5, 14, 9, "plug"),
            big(7.25, 4, 9.5, 8.75, 6, 9.65, "led"),
            big(7.25, 4, 6.35, 8.75, 6, 6.5, "led"),
            big(5.4, 8, 6.4, 10.6, 8.5, 9.6, "led"),
        ],
        "display": {
            "gui": {"rotation": [30, 45, 0], "translation": [0, -0.35, 0], "scale": [round(0.95 / k, 4)] * 3},
            "ground": {"rotation": [0, 0, 0], "translation": [0, 9, 0], "scale": [0.5, 0.5, 0.5]},
            "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [round(0.9 / k, 4)] * 3},
            "thirdperson_righthand": {"rotation": [0, 0, 20], "translation": [0, 9, 3], "scale": [0.6, 0.6, 0.6]},
            "thirdperson_lefthand": {"rotation": [0, 0, -20], "translation": [0, 9, 3], "scale": [0.6, 0.6, 0.6]},
            "firstperson_righthand": {"rotation": [0, -35, 30], "translation": [-2.5, 1.5, -5], "scale": [0.55, 0.55, 0.55]},
            "firstperson_lefthand": {"rotation": [0, 35, -30], "translation": [2.5, 1.5, -5], "scale": [0.55, 0.55, 0.55]}}})


# ============================================================================ README inside the generated workspace
WORKSPACE_README = """\
# @@MODNAME@@ (Fabric @@MCVER@@)

Generated by build_mod.py. Build with JDK 17:

    ./gradlew build           # build/libs/@@MODID@@-@@MODVER@@.jar
    ./gradlew runClient       # play (opening a world: click "I know what I'm doing!" - vanilla flags worlds with
                              #   data-pack dimensions as experimental)
    ./gradlew runServer
    ./gradlew runGametest     # 19 game tests, each run in a fresh world (put eula=true into build/gametest/eula.txt)

## Play
* Place the Black Market Portal item (you get one on first join; craft it with COC / OEO / COC: Crying Obsidian, Obsidian,
  Ender Pearl): the frame snaps together and the vortex tears open. Walk into it - you stand in the Black Market. Spend your
  Blocks Consumed on the six pedestals: glitch_pickaxe (0), glitchy_floppy (500), cursed_kitty_phone (5,000),
  hacked_rgb_furnace (50,000), beacon_5g (300,000), quantum_usb (1,000,000). Right-click a gate's frame to fold it back.
* /realityshop balance | add <n> | set <n> | market | bedrock | back | gate | rebuild   (all but balance need op level 2)
* /cinematic [item | stop]   a 360 degree showcase of every item (client command; ESC leaves, SPACE skips)
* config/@@MODID@@.json is written on first start (tick budget, bedrock, chunk loading, chicken cap, purge times, USB size,
  HUD position, particles ...).

## Your assets
* 3D models: src/main/resources/assets/@@MODID@@/models/gltf/<name>/scene.gltf|glb (derp_chicken, cursed_kitty_phone),
  rendered untouched by the runtime glTF loader. File names must be lower-case ([a-z0-9/._-]).
* Any texture or sound can be replaced by putting a file at the same path; 8K textures are uploaded at full size.
  Re-run build_mod.py with an assets/overrides folder to keep such replacements across regenerations.

## Credits
@@CREDITS@@
"""


def gen_readme(out, opts):
    out.text("README.md", WORKSPACE_README, CREDITS=CREDITS)


# @@INSERT:RESOURCES@@  (JSON/data generators are inserted above this line)


# ============================================================================ drop-in overrides + self-checks
def apply_overrides(out, opts):
    """Copy assets/overrides/** (mirror of src/main/resources) over the generated files, byte-for-byte."""
    root = os.path.abspath(opts.overrides) if opts.overrides else os.path.join(SCRIPT_DIR, "assets", "overrides")
    if not os.path.isdir(root):
        return 0
    n = 0
    for dirpath, _dirs, names in os.walk(root):
        for name in sorted(names):
            if name.startswith(".") or name.lower() == "readme.md":
                continue
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            with open(full, "rb") as fh:
                out.files["src/main/resources/" + rel] = fh.read()
            n += 1
    # an overriding .png replaces the generated texture: drop the generated animation metadata that described the old
    # strip, unless the override folder brings its own .png.mcmeta
    for rel in [p for p in out.files if p.endswith(".png.mcmeta")]:
        png = rel[:-len(".mcmeta")]
        if os.path.isfile(os.path.join(root, png[len("src/main/resources/"):])) and not os.path.isfile(os.path.join(root, rel[len("src/main/resources/"):])):
            del out.files[rel]
    return n


def self_check(out):
    problems = []
    for path, data in sorted(out.files.items()):
        if path.endswith((".json", ".mcmeta")) and "/models/gltf/" not in path:
            try:
                json.loads(data.decode("utf-8"))
            except Exception as exc:  # noqa: BLE001
                problems.append("%s: invalid JSON (%s)" % (path, exc))
        if path.endswith((".java", ".json", ".gradle", ".properties", ".txt", ".vsh", ".fsh")) and b"@@" in data:
            problems.append("%s: unreplaced @@ token" % path)
        if path.startswith("src/main/java/") and path.endswith(".java") and b"net.minecraft.client" in data.replace(b"net.minecraft.client.item.TooltipContext", b""):
            problems.append("%s: common code references net.minecraft.client (move it to src/client/java)" % path)
        if path.endswith(".java"):
            m = re.search(rb"^package ([\w.]+);", data, re.M)
            expected = os.path.dirname(path.split("/java/", 1)[1]).replace("/", ".").encode()
            if not m or m.group(1) != expected:
                problems.append("%s: package line does not match its directory" % path)
    problems += check_resources(out)
    # @@INSERT:CHECKS@@
    return problems


def check_resources(out):
    """Reference closure of everything the generated resources point at, plus the resource-path rules the game enforces."""
    problems = []
    files = out.files
    res = "src/main/resources/"
    assets = res + "assets/%s/" % MOD_ID
    for path in files:
        if path.startswith(res + "assets/") or path.startswith(res + "data/"):
            tail = path[len(res):]
            if not re.fullmatch(r"[a-z0-9/._-]+", tail):
                problems.append("%s: resource paths may only contain [a-z0-9/._-] (the game ignores this file)" % path)

    def exists(p):
        return p in files

    def own(ref):
        """'realityshop:x/y' -> 'x/y'; None for other namespaces (vanilla references are not checked)."""
        if ":" in ref:
            ns, rest = ref.split(":", 1)
            return rest if ns == MOD_ID else None
        return None

    def load(p):
        try:
            return json.loads(files[p].decode("utf-8"))
        except (KeyError, ValueError):
            return {}

    lang = load(assets + "lang/en_us.json")
    for path in sorted(files):
        if path.startswith(assets + "models/block/") or path.startswith(assets + "models/item/"):
            doc = load(path)
            parent = own(doc.get("parent", ""))
            if parent and not exists(assets + "models/%s.json" % parent):
                problems.append("%s: parent %s.json is missing" % (path, parent))
            for key, ref in (doc.get("textures") or {}).items():
                tex = own(ref)
                if tex and not exists(assets + "textures/%s.png" % tex):
                    problems.append("%s: texture %s.png is missing" % (path, tex))
            if "/models/item/" in path:
                name = os.path.basename(path)[:-5]
                if "item.%s.%s" % (MOD_ID, name) not in lang and "block.%s.%s" % (MOD_ID, name) not in lang:
                    problems.append("%s: no lang entry item.%s.%s / block.%s.%s" % (path, MOD_ID, name, MOD_ID, name))
        elif path.startswith(assets + "blockstates/"):
            doc = load(path)
            variants = list((doc.get("variants") or {}).values())
            for v in variants:
                for entry in (v if isinstance(v, list) else [v]):
                    model = own(entry.get("model", ""))
                    if model and not exists(assets + "models/%s.json" % model):
                        problems.append("%s: model %s.json is missing" % (path, model))
        elif path.startswith(assets + "particles/"):
            for ref in load(path).get("textures", []):
                tex = own(ref)
                if tex and not exists(assets + "textures/particle/%s.png" % tex):
                    problems.append("%s: particle texture %s.png is missing" % (path, tex))
        elif path.endswith(".png.mcmeta"):
            if not exists(path[:-len(".mcmeta")]):
                problems.append("%s: animation metadata without its texture" % path)
        elif path.startswith(assets + "models/gltf/") and path.endswith(".gltf"):
            doc = load(path)
            base = os.path.dirname(path) + "/"
            for key in ("buffers", "images"):
                for item in doc.get(key, []):
                    uri = item.get("uri")
                    if uri and not uri.startswith("data:"):
                        target = base + _resource_name(uri.replace("%20", " "))
                        if not exists(target):
                            problems.append("%s: %s %r -> %s is missing" % (path, key, uri, target))
    sounds = load(assets + "sounds.json")
    for event, doc in sounds.items():
        for entry in doc.get("sounds", []):
            name = entry["name"] if isinstance(entry, dict) else entry
            if isinstance(entry, dict) and entry.get("type") == "event":
                continue
            own_name = own(name if ":" in name else MOD_ID + ":" + name)
            if own_name and not exists(assets + "sounds/%s.ogg" % own_name):
                problems.append("%s: sound %s.ogg is missing" % (assets + "sounds.json", own_name))
    for needed in ("textures/gui/hud_glyphs.png", "textures/gui/hud_plate.png", "shaders/core/gltf_pbr.json",
                   "shaders/core/gltf_pbr.vsh", "shaders/core/gltf_pbr.fsh", "shaders/core/portal_vortex.json",
                   "shaders/core/portal_vortex.vsh", "shaders/core/portal_vortex.fsh", "icon.png"):
        if not exists(assets + needed):
            problems.append("%s%s is missing" % (assets, needed))
    for name in KNOWN_MODELS:
        if not any(p.startswith(assets + "models/gltf/%s/" % name) and p.endswith((".gltf", ".glb")) for p in files):
            problems.append("no glTF/GLB for the model %r" % name)
    return problems


# ============================================================================ main
GENERATORS = [gen_workspace, gen_models, gen_hud, gen_java, gen_data, gen_lang, gen_sounds, gen_models_json,
              gen_textures, gen_shaders, gen_readme]  # @@INSERT:GENERATORS@@


def write_out(out, target, force):
    if os.path.isdir(target) and os.listdir(target) and not force:
        raise SystemExit("%s already exists and is not empty (use --force to overwrite generated files)" % target)
    for rel, data in out.files.items():
        path = os.path.join(target, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as fh:
            fh.write(data)
        if rel in out.executable:
            os.chmod(path, 0o755)


def main(argv=None):
    ap = argparse.ArgumentParser(description="Generate the RealityShop Fabric %s workspace." % MC_VERSION)
    ap.add_argument("--out", default="RealityShop", help="output directory (default: ./RealityShop)")
    ap.add_argument("--force", action="store_true", help="overwrite generated files in a non-empty directory")
    ap.add_argument("--texture-size", type=int, default=256, choices=(64, 128, 256, 512, 1024),
                    help="resolution of the procedurally generated textures (default 256)")
    ap.add_argument("--models", help="folder with 3D models (default: assets/models next to this script)")
    ap.add_argument("--overrides", help="folder mirroring src/main/resources whose files replace generated ones "
                                        "(default: assets/overrides next to this script)")
    opts = ap.parse_args(argv)

    out = Out()
    for gen in GENERATORS:
        gen(out, opts)
    overridden = apply_overrides(out, opts)
    problems = self_check(out)
    if problems:
        print("self-check FAILED:\n  " + "\n  ".join(problems), file=sys.stderr)
        return 1
    write_out(out, opts.out, opts.force)
    n_java = sum(1 for p in out.files if p.endswith(".java"))
    print("RealityShop workspace written to %s (%d files, %d Java classes, %d override files)"
          % (opts.out, len(out.files), n_java, overridden))
    print("Next: cd %s && ./gradlew build   (JDK 17 required)" % opts.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
