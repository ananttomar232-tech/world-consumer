#!/usr/bin/env python3
"""Shared helpers for the Tesla Tycoon model tools: a small glTF 2.0 reader (numpy), the .ttm container, and a tiny
software preview renderer.  Dev-time only: the mod itself ships the baked .ttm files and needs none of this."""
import base64
import json
import math
import os
import struct

import numpy as np

CT = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
NC = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT2": 4, "MAT3": 9, "MAT4": 16}


# ----------------------------------------------------------------------------------------------------------------- glTF
class Gltf:
    def __init__(self, directory):
        self.dir = directory
        self.g = json.load(open(os.path.join(directory, "scene.gltf"), encoding="utf-8"))
        self.bufs = []
        for b in self.g["buffers"]:
            uri = b.get("uri", "")
            if uri.startswith("data:"):
                self.bufs.append(base64.b64decode(uri.split(",", 1)[1]))
            else:
                with open(os.path.join(directory, uri), "rb") as fh:
                    self.bufs.append(fh.read())
        self.nodes = self.g["nodes"]
        self.parent = {}
        for i, n in enumerate(self.nodes):
            for c in n.get("children", []):
                self.parent[c] = i
        self._world = {}

    def accessor(self, idx):
        a = self.g["accessors"][idx]
        n = NC[a["type"]]
        dt = np.dtype(CT[a["componentType"]])
        cnt = a["count"]
        if "bufferView" not in a:
            return np.zeros((cnt, n), dtype=dt)
        bv = self.g["bufferViews"][a["bufferView"]]
        base = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
        stride = bv.get("byteStride", 0) or n * dt.itemsize
        raw = self.bufs[bv["buffer"]]
        if stride == n * dt.itemsize:
            arr = np.frombuffer(raw, dtype=dt, count=cnt * n, offset=base).reshape(cnt, n)
        else:
            full = np.frombuffer(raw, dtype=np.uint8, count=stride * (cnt - 1) + n * dt.itemsize, offset=base)
            arr = np.lib.stride_tricks.as_strided(full, shape=(cnt, n * dt.itemsize), strides=(stride, 1)).copy()
            arr = arr.view(dt).reshape(cnt, n)
        if a.get("normalized") and dt.kind in "iu":
            arr = arr.astype(np.float32) / float(np.iinfo(dt).max)
        return arr

    @staticmethod
    def local_matrix(nd):
        if "matrix" in nd:
            return np.array(nd["matrix"], dtype=np.float64).reshape(4, 4).T
        t = nd.get("translation", [0, 0, 0])
        x, y, z, w = nd.get("rotation", [0, 0, 0, 1])
        s = nd.get("scale", [1, 1, 1])
        R = np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                      [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                      [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])
        M = np.eye(4)
        M[:3, :3] = R * np.array(s)[None, :]
        M[:3, 3] = t
        return M

    def world(self, i):
        if i in self._world:
            return self._world[i]
        M = self.local_matrix(self.nodes[i])
        if i in self.parent:
            M = self.world(self.parent[i]) @ M
        self._world[i] = M
        return M

    def node_path(self, i):
        names = []
        while i is not None:
            names.append(self.nodes[i].get("name") or "")
            i = self.parent.get(i)
        return "/".join(reversed(names))

    def material(self, mi):
        """Plain dict with the fields the baker needs (linear colours, as authored)."""
        if mi is None:
            return {"name": "default", "base": [1, 1, 1, 1], "tex": None, "emis": [0, 0, 0], "emis_tex": None, "emis_strength": 1.0,
                    "metal": 0.0, "rough": 0.8, "alpha": "OPAQUE", "cutoff": 0.5}
        m = self.g["materials"][mi]
        pbr = m.get("pbrMetallicRoughness", {})
        ext = m.get("extensions", {})
        return {
            "name": m.get("name", "mat%d" % mi),
            "base": pbr.get("baseColorFactor", [1, 1, 1, 1]),
            "tex": self.texture_uri(pbr.get("baseColorTexture", {}).get("index")),
            "emis": m.get("emissiveFactor", [0, 0, 0]),
            "emis_tex": self.texture_uri(m.get("emissiveTexture", {}).get("index")),
            "emis_strength": ext.get("KHR_materials_emissive_strength", {}).get("emissiveStrength", 1.0),
            "metal": pbr.get("metallicFactor", 1.0),
            "rough": pbr.get("roughnessFactor", 1.0),
            "alpha": m.get("alphaMode", "OPAQUE"),
            "cutoff": m.get("alphaCutoff", 0.5),
        }

    def texture_uri(self, tex_index):
        if tex_index is None:
            return None
        t = self.g["textures"][tex_index]
        return self.g["images"][t["source"]].get("uri")

    def primitives(self):
        """Every triangle primitive in model space (node transforms baked in)."""
        out = []
        for i, n in enumerate(self.nodes):
            if "mesh" not in n:
                continue
            M = self.world(i)
            N = np.linalg.inv(M[:3, :3]).T
            for p in self.g["meshes"][n["mesh"]]["primitives"]:
                if p.get("mode", 4) != 4:
                    continue
                pos = self.accessor(p["attributes"]["POSITION"]).astype(np.float64)
                pos = (np.c_[pos, np.ones(len(pos))] @ M.T)[:, :3].astype(np.float32)
                if "NORMAL" in p["attributes"]:
                    nrm = self.accessor(p["attributes"]["NORMAL"]).astype(np.float64) @ N.T
                    ln = np.linalg.norm(nrm, axis=1, keepdims=True)
                    nrm = (nrm / np.maximum(ln, 1e-12)).astype(np.float32)
                else:
                    nrm = None
                uv = self.accessor(p["attributes"]["TEXCOORD_0"]).astype(np.float32) if "TEXCOORD_0" in p["attributes"] else None
                if "indices" in p:
                    idx = self.accessor(p["indices"]).reshape(-1).astype(np.uint32)
                else:
                    idx = np.arange(len(pos), dtype=np.uint32)
                # a node with a negative determinant mirrors the mesh: flip the winding so faces stay outward
                if np.linalg.det(M[:3, :3]) < 0:
                    idx = idx.reshape(-1, 3)[:, ::-1].reshape(-1).copy()
                out.append({"pos": pos, "nrm": nrm, "uv": uv, "idx": idx, "mat": p.get("material"), "node": i,
                            "name": self.nodes[i].get("name") or "", "path": self.node_path(i)})
        return out


def srgb_from_linear(c):
    c = np.clip(np.asarray(c, dtype=np.float64), 0.0, 1.0)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * np.power(c, 1 / 2.4) - 0.055)


# ------------------------------------------------------------------------------------------------------ connected sets
def components(n, a, b):
    """Connected components of an undirected graph (numpy union-find with pointer jumping). Returns a label per node."""
    parent = np.arange(n, dtype=np.int64)
    a = a.astype(np.int64)
    b = b.astype(np.int64)
    while True:
        pa, pb = parent[a], parent[b]
        lo, hi = np.minimum(pa, pb), np.maximum(pa, pb)
        m = lo != hi
        if not m.any():
            break
        np.minimum.at(parent, hi[m], lo[m])
        while True:
            pp = parent[parent]
            if (pp == parent).all():
                break
            parent = pp
    return parent


def weld_ids(pos, eps):
    """Id per vertex such that vertices closer than eps (grid snapped) share an id."""
    q = np.round(pos.astype(np.float64) / eps).astype(np.int64)
    _, inv = np.unique(q, axis=0, return_inverse=True)
    return inv.reshape(-1)


# ---------------------------------------------------------------------------------------------------------- .ttm file
MAGIC = b"TTM1"


def write_ttm(path, header, blob):
    h = json.dumps(header, separators=(",", ":")).encode("utf-8")
    with open(path, "wb") as fh:
        fh.write(MAGIC)
        fh.write(struct.pack("<i", len(h)))
        fh.write(h)
        fh.write(blob)


def read_ttm(path):
    with open(path, "rb") as fh:
        raw = fh.read()
    assert raw[:4] == MAGIC, "not a TTM1 file"
    (hl,) = struct.unpack_from("<i", raw, 4)
    header = json.loads(raw[8:8 + hl].decode("utf-8"))
    return header, memoryview(raw)[8 + hl:]


VERT_DT = np.dtype([("pos", "<f4", 3), ("nrm", "i1", 3), ("pad", "i1"), ("uv", "<f4", 2), ("col", "u1", 4), ("mat", "u1", 4)])
assert VERT_DT.itemsize == 32


# ------------------------------------------------------------------------------------------------ software preview
def render_views(tris, colors, views, size=(900, 520), markers=None, title=None, bbox=None):
    """Orthographic painter's-algorithm preview. tris (T,3,3) float, colors (T,3) 0..255 uint8.
    views: list of (name, right, up, forward) unit vectors. Returns a PIL image with the views side by side."""
    from PIL import Image, ImageDraw
    w, h = size
    sheet = Image.new("RGB", (w * len(views), h), (28, 30, 38))
    n = np.cross(tris[:, 1] - tris[:, 0], tris[:, 2] - tris[:, 0])
    ln = np.linalg.norm(n, axis=1, keepdims=True)
    n = n / np.maximum(ln, 1e-20)
    light = np.array([-0.35, 0.85, 0.4])
    light /= np.linalg.norm(light)
    lum = 0.38 + 0.62 * np.abs(n @ light)
    shaded = np.clip(colors.astype(np.float32) * lum[:, None], 0, 255).astype(np.uint8)
    lo = tris.reshape(-1, 3).min(0) if bbox is None else np.array(bbox[:3])
    hi = tris.reshape(-1, 3).max(0) if bbox is None else np.array(bbox[3:])
    center = (lo + hi) / 2
    for vi, (name, right, up, fwd) in enumerate(views):
        right, up, fwd = (np.asarray(v, dtype=np.float64) for v in (right, up, fwd))
        P = tris.reshape(-1, 3).astype(np.float64) - center
        sx, sy, dz = P @ right, P @ up, P @ fwd
        ext = max(sx.max() - sx.min(), sy.max() - sy.min(), 1e-9)
        sc = min(w, h) * 0.88 / ext
        px = (sx * sc + w / 2).reshape(-1, 3)
        py = (-sy * sc + h / 2).reshape(-1, 3)
        depth = dz.reshape(-1, 3).mean(1)
        order = np.argsort(-depth)            # far to near
        img = Image.new("RGB", (w, h), (28, 30, 38))
        d = ImageDraw.Draw(img)
        for t in order:
            d.polygon([(px[t, 0], py[t, 0]), (px[t, 1], py[t, 1]), (px[t, 2], py[t, 2])], fill=tuple(int(c) for c in shaded[t]))
        if markers:
            for (label, p, col) in markers:
                q = np.asarray(p, dtype=np.float64) - center
                x, y = float(q @ right) * sc + w / 2, -float(q @ up) * sc + h / 2
                d.ellipse([x - 5, y - 5, x + 5, y + 5], outline=col, width=2)
                d.text((x + 7, y - 6), label, fill=col)
        d.text((8, 6), "%s%s" % (title + "  " if title else "", name), fill=(255, 255, 255))
        sheet.paste(img, (vi * w, 0))
    return sheet


def standard_views():
    # car faces +Z, up +Y.  side: front to the right; front: looking at the nose; top: nose up; iso: front-left from above
    side = ("side", (0, 0, 1), (0, 1, 0), (-1, 0, 0))
    front = ("front", (1, 0, 0), (0, 1, 0), (0, 0, -1))
    top = ("top", (-1, 0, 0), (0, 0, 1), (0, -1, 0))
    a = math.radians(35)
    f = np.array([-math.sin(a), -0.5, -math.cos(a)])
    f /= np.linalg.norm(f)
    r = np.cross(f, (0, 1, 0))
    r /= np.linalg.norm(r)
    u = np.cross(r, f)
    iso = ("iso", r, u, f)
    return [side, front, top, iso]


# ----------------------------------------------------------------------------------------------------------------- Wavefront OBJ
class ObjScene:
    """The same interface as Gltf (primitives(), material()) for an .obj + .mtl export (Blockbench, Maya ...).
    Every `o` / `g` block with its own `usemtl` becomes one primitive; path = "/<object name>#<running index>"."""

    def __init__(self, obj_path):
        self.dir = os.path.dirname(obj_path)
        self.mats = {}
        self._prims = []
        V, VT, VN = [], [], []
        cur = {"name": "root", "mat": None, "f": []}
        blocks = [cur]
        mtllib = None
        count = 0

        def new_block(name=None, mat=None):
            nonlocal cur, count
            count += 1
            cur = {"name": name if name is not None else cur["name"], "mat": mat if mat is not None else cur["mat"], "f": [], "i": count}
            blocks.append(cur)

        with open(obj_path, encoding="utf-8", errors="ignore") as fh:
            for ln in fh:
                t = ln.split()
                if not t:
                    continue
                k = t[0]
                if k == "v":
                    V.append([float(x) for x in t[1:4]])
                elif k == "vt":
                    VT.append([float(x) for x in t[1:3]])
                elif k == "vn":
                    VN.append([float(x) for x in t[1:4]])
                elif k in ("o", "g"):
                    new_block(name=" ".join(t[1:]) or "unnamed")
                elif k == "usemtl":
                    if cur["f"]:
                        new_block(mat=" ".join(t[1:]))
                    else:
                        cur["mat"] = " ".join(t[1:])
                elif k == "mtllib":
                    mtllib = " ".join(t[1:])
                elif k == "f":
                    corners = []
                    for c in t[1:]:
                        s = c.split("/")
                        vi = int(s[0])
                        ti = int(s[1]) if len(s) > 1 and s[1] else 0
                        ni = int(s[2]) if len(s) > 2 and s[2] else 0
                        corners.append((vi - 1 if vi > 0 else len(V) + vi, ti - 1 if ti > 0 else (len(VT) + ti if ti < 0 else -1),
                                        ni - 1 if ni > 0 else (len(VN) + ni if ni < 0 else -1)))
                    for j in range(1, len(corners) - 1):
                        cur["f"].append((corners[0], corners[j], corners[j + 1]))
        if mtllib and os.path.exists(os.path.join(self.dir, mtllib)):
            self._read_mtl(os.path.join(self.dir, mtllib))
        V, VT, VN = np.array(V, np.float64), np.array(VT or [[0, 0]], np.float64), np.array(VN or [[0, 1, 0]], np.float64)
        for b in blocks:
            if not b["f"]:
                continue
            corners = np.array([c for tri in b["f"] for c in tri], dtype=np.int64)
            pos = V[corners[:, 0]].astype(np.float32)
            uv = VT[np.maximum(corners[:, 1], 0)].astype(np.float32)
            uv[:, 1] = 1.0 - uv[:, 1]                                   # OBJ: v up; glTF / the game: v down
            if (corners[:, 2] >= 0).all():
                nrm = VN[corners[:, 2]].astype(np.float32)
            else:                                                       # flat normals
                P = pos.reshape(-1, 3, 3).astype(np.float64)
                n = np.cross(P[:, 1] - P[:, 0], P[:, 2] - P[:, 0])
                n /= np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-12)
                nrm = np.repeat(n, 3, axis=0).astype(np.float32)
            self._prims.append({"pos": pos, "nrm": nrm, "uv": uv, "idx": np.arange(len(pos), dtype=np.uint32),
                                "mat": b["mat"], "path": "/%s#%d" % (b["name"], b.get("i", 0)), "node": b.get("i", 0)})

    def _read_mtl(self, path):
        cur = None
        with open(path, encoding="utf-8", errors="ignore") as fh:
            for ln in fh:
                t = ln.split()
                if not t:
                    continue
                if t[0] == "newmtl":
                    cur = " ".join(t[1:])
                    self.mats[cur] = {"Kd": [1, 1, 1], "map": None}
                elif cur and t[0] == "Kd":
                    self.mats[cur]["Kd"] = [float(x) for x in t[1:4]]
                elif cur and t[0] == "map_Kd" and len(t) > 1:
                    self.mats[cur]["map"] = " ".join(t[1:]).replace("\\", "/").split("/")[-1]      # (Windows paths of the exporter)

    def primitives(self):
        return self._prims

    def material(self, name):
        m = self.mats.get(name, {"Kd": [1, 1, 1], "map": None})
        tex = m["map"]
        if tex is not None and not os.path.exists(os.path.join(self.dir, tex)):
            tex = next((tex + e for e in (".png", ".jpg", ".jpeg") if os.path.exists(os.path.join(self.dir, tex + e))), None)
        return {"name": name or "default", "base": list(m["Kd"]) + [1.0], "tex": tex, "emis": [0, 0, 0], "emis_tex": None,
                "emis_strength": 1.0, "metal": 0.0, "rough": 0.8, "alpha": "OPAQUE", "cutoff": 0.5}
