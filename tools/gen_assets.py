#!/usr/bin/env python3
"""Writes the small hand-made resources of the Tesla Tycoon mod (block textures, models, blockstates, loot tables, the recipe,
the language file, the NPC suit skins, icons) into tesla_tycoon/src/main/resources.  The three uploaded banner PNGs are copied
UNCHANGED from assets/tesla_tycoon/banners/ (no banner graphics are generated).  needs: Pillow.  usage: python3 tools/gen_assets.py"""
import json
import os
import random
import shutil

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "tesla_tycoon", "src", "main", "resources")
A = os.path.join(RES, "assets", "tesla_tycoon")
D = os.path.join(RES, "data", "tesla_tycoon")
BANNERS = os.path.join(ROOT, "assets", "tesla_tycoon", "banners")
NS = "tesla_tycoon"


def w(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=2, ensure_ascii=False)


def img_save(im, rel):
    path = os.path.join(A, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path)


# --------------------------------------------------------------------------------------------------------------- textures
def noise_tex(base, amp, seed, size=16):
    rnd = random.Random(seed)
    im = Image.new("RGB", (size, size))
    px = im.load()
    for y in range(size):
        for x in range(size):
            n = rnd.randint(-amp, amp)
            px[x, y] = tuple(max(0, min(255, c + n)) for c in base)
    return im


def block_textures():
    # asphalt: dark road surface with pebbles
    im = noise_tex((40, 42, 48), 7, 1)
    d = ImageDraw.Draw(im)
    rnd = random.Random(5)
    for _ in range(14):
        x, y = rnd.randint(0, 15), rnd.randint(0, 15)
        d.point((x, y), fill=(78, 80, 88))
    img_save(im, "textures/block/asphalt.png")
    # neon panels (on / off) in four colours
    colours = {"cyan": (40, 230, 255), "magenta": (255, 60, 220), "white": (240, 248, 255), "red": (255, 50, 60)}
    for name, c in colours.items():
        on = Image.new("RGB", (16, 16), tuple(int(v * 0.35) for v in c))
        d = ImageDraw.Draw(on)
        d.rectangle([1, 1, 14, 14], fill=tuple(int(v * 0.75) for v in c))
        d.rectangle([3, 3, 12, 12], fill=c)
        d.rectangle([5, 5, 10, 10], fill=tuple(min(255, v + 60) for v in c))
        img_save(on, "textures/block/neon_%s_on.png" % name)
        off = Image.new("RGB", (16, 16), (24, 27, 33))
        d = ImageDraw.Draw(off)
        d.rectangle([1, 1, 14, 14], fill=(34, 38, 46))
        d.rectangle([3, 3, 12, 12], fill=tuple(int(v * 0.14) + 22 for v in c))
        img_save(off, "textures/block/neon_%s_off.png" % name)
    # spikes: brushed steel
    im = noise_tex((150, 156, 168), 12, 9)
    img_save(im, "textures/block/road_spikes.png")
    # concrete roadblock with hazard stripes
    im = noise_tex((176, 176, 176), 8, 11)
    d = ImageDraw.Draw(im)
    for i in range(-16, 16, 4):
        for k in range(2):
            d.line([(i + k, 5), (i + 6 + k, 11)], fill=(214, 40, 40))
    d.rectangle([0, 0, 15, 1], fill=(120, 120, 124))
    d.rectangle([0, 14, 15, 15], fill=(120, 120, 124))
    img_save(im, "textures/block/concrete_barrier.png")
    # billboard back board: dark steel with rivets
    im = noise_tex((46, 51, 62), 5, 13)
    d = ImageDraw.Draw(im)
    for x in (1, 14):
        for y in (1, 14):
            d.point((x, y), fill=(120, 130, 150))
    d.rectangle([0, 0, 15, 0], fill=(70, 78, 92))
    d.rectangle([0, 15, 15, 15], fill=(70, 78, 92))
    img_save(im, "textures/block/billboard_panel.png")
    # terminals
    def term_front(accent, glyph):
        t = Image.new("RGB", (16, 16), (26, 30, 38))
        d = ImageDraw.Draw(t)
        d.rectangle([1, 1, 14, 14], fill=(34, 40, 52))
        d.rectangle([2, 2, 13, 10], fill=tuple(int(v * 0.25) for v in accent))
        d.rectangle([3, 3, 12, 9], fill=tuple(int(v * 0.55) for v in accent))
        for (x, y) in glyph:
            d.point((x, y), fill=(255, 255, 255))
        for x in range(3, 13, 2):
            d.point((x, 12), fill=accent)
        return t
    dollar = [(7, 3), (8, 3), (6, 4), (7, 5), (8, 6), (9, 7), (6, 8), (7, 8), (8, 8), (8, 4), (7, 9)]
    car = [(4, 7), (5, 6), (6, 5), (7, 5), (8, 5), (9, 5), (10, 6), (11, 7), (4, 8), (5, 8), (6, 8), (7, 8), (8, 8), (9, 8), (10, 8), (11, 8), (5, 9), (10, 9)]
    img_save(term_front((60, 230, 255), dollar), "textures/block/shop_terminal_front.png")
    img_save(term_front((255, 200, 70), car), "textures/block/robotaxi_terminal_front.png")
    side = noise_tex((40, 46, 58), 4, 17)
    ImageDraw.Draw(side).rectangle([0, 0, 15, 1], fill=(60, 230, 255))
    img_save(side, "textures/block/terminal_side.png")
    top = noise_tex((36, 42, 54), 4, 19)
    ImageDraw.Draw(top).rectangle([2, 2, 13, 13], outline=(60, 230, 255))
    img_save(top, "textures/block/terminal_top.png")
    # delivery crate with the red Tesla band
    im = noise_tex((196, 198, 204), 6, 23)
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 15, 1], fill=(150, 152, 160))
    d.rectangle([0, 14, 15, 15], fill=(150, 152, 160))
    d.rectangle([0, 6, 15, 9], fill=(208, 28, 36))
    d.rectangle([6, 3, 9, 4], fill=(208, 28, 36))
    d.rectangle([7, 4, 8, 12], fill=(208, 28, 36))
    img_save(im, "textures/block/delivery_crate.png")
    # MegaPack
    im = noise_tex((236, 238, 242), 4, 29)
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 15, 15], outline=(170, 175, 185))
    for x in (4, 8, 12):
        d.line([(x, 1), (x, 14)], fill=(205, 208, 216))
    d.rectangle([6, 6, 9, 7], fill=(208, 28, 36))
    d.rectangle([7, 7, 8, 11], fill=(208, 28, 36))
    img_save(im, "textures/block/megapack.png")
    # Starlink dish
    im = noise_tex((224, 226, 230), 5, 31)
    d = ImageDraw.Draw(im)
    d.ellipse([2, 2, 13, 13], outline=(170, 175, 185))
    d.ellipse([6, 6, 9, 9], fill=(90, 96, 110))
    img_save(im, "textures/block/starlink_dish.png")


# ----------------------------------------------------------------------------------------------------------- block models
CAR_ITEM = {"parent": "builtin/entity", "gui_light": "front", "display": {
    "gui": {"rotation": [20, 215, 0], "translation": [0, 1, 0], "scale": [0.9, 0.9, 0.9]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
    "fixed": {"rotation": [0, 90, 0], "translation": [0, 0, 0], "scale": [0.9, 0.9, 0.9]},
    "thirdperson_righthand": {"rotation": [0, 180, 0], "translation": [0, 2, 1], "scale": [0.55, 0.55, 0.55]},
    "thirdperson_lefthand": {"rotation": [0, 180, 0], "translation": [0, 2, 1], "scale": [0.55, 0.55, 0.55]},
    "firstperson_righthand": {"rotation": [0, 200, 0], "translation": [2, 2, 0], "scale": [0.6, 0.6, 0.6]},
    "firstperson_lefthand": {"rotation": [0, 200, 0], "translation": [2, 2, 0], "scale": [0.6, 0.6, 0.6]}}}


def block_models():
    cube_all = ["asphalt", "concrete_barrier", "billboard_panel", "delivery_crate", "megapack"]
    for n in cube_all:
        w(os.path.join(A, "models/block/%s.json" % n), {"parent": "minecraft:block/cube_all", "textures": {"all": "%s:block/%s" % (NS, n)}})
        w(os.path.join(A, "blockstates/%s.json" % n), {"variants": {"": {"model": "%s:block/%s" % (NS, n)}}})
        w(os.path.join(A, "models/item/%s.json" % n), {"parent": "%s:block/%s" % (NS, n)})
    # neon: color x lit
    variants = {}
    for c in ("cyan", "magenta", "white", "red"):
        for lit in ("true", "false"):
            tex = "%s:block/neon_%s_%s" % (NS, c, "on" if lit == "true" else "off")
            name = "neon_%s_%s" % (c, "on" if lit == "true" else "off")
            w(os.path.join(A, "models/block/%s.json" % name), {"parent": "minecraft:block/cube_all", "textures": {"all": tex}})
            variants["color=%s,lit=%s" % (c, lit)] = {"model": "%s:block/%s" % (NS, name)}
    w(os.path.join(A, "blockstates/neon.json"), {"variants": variants})
    w(os.path.join(A, "models/item/neon.json"), {"parent": "%s:block/neon_cyan_on" % NS})
    # spikes: a plate with a grid of spikes
    els = [{"from": [0, 0, 0], "to": [16, 1, 16], "faces": {f: {"uv": [0, 0, 16, 16], "texture": "#t"} for f in ("north", "south", "east", "west", "up", "down")}}]
    for gx in range(4):
        for gz in range(4):
            x, z = 1 + gx * 4, 1 + gz * 4
            els.append({"from": [x, 1, z], "to": [x + 2, 3, z + 2], "faces": {f: {"uv": [0, 0, 4, 4], "texture": "#t"} for f in ("north", "south", "east", "west", "up")}})
    w(os.path.join(A, "models/block/road_spikes.json"), {"textures": {"t": "%s:block/road_spikes" % NS, "particle": "%s:block/road_spikes" % NS}, "elements": els})
    w(os.path.join(A, "blockstates/road_spikes.json"), {"variants": {"": {"model": "%s:block/road_spikes" % NS}}})
    w(os.path.join(A, "models/item/road_spikes.json"), {"parent": "%s:block/road_spikes" % NS})
    # terminals (front on the facing side)
    for n in ("shop_terminal", "robotaxi_terminal"):
        w(os.path.join(A, "models/block/%s.json" % n), {"parent": "minecraft:block/orientable", "textures": {
            "top": "%s:block/terminal_top" % NS, "front": "%s:block/%s_front" % (NS, n), "side": "%s:block/terminal_side" % NS}})
        w(os.path.join(A, "blockstates/%s.json" % n), {"variants": {
            "facing=north": {"model": "%s:block/%s" % (NS, n)},
            "facing=east": {"model": "%s:block/%s" % (NS, n), "y": 90},
            "facing=south": {"model": "%s:block/%s" % (NS, n), "y": 180},
            "facing=west": {"model": "%s:block/%s" % (NS, n), "y": 270}}})
        w(os.path.join(A, "models/item/%s.json" % n), {"parent": "%s:block/%s" % (NS, n)})
    # starlink dish: mast + tilted-looking dish plate
    def faces(t):
        return {f: {"uv": [0, 0, 16, 16], "texture": "#t"} for f in ("north", "south", "east", "west", "up", "down")}
    w(os.path.join(A, "models/block/starlink_dish.json"), {"textures": {"t": "%s:block/starlink_dish" % NS, "particle": "%s:block/starlink_dish" % NS}, "elements": [
        {"from": [7, 0, 7], "to": [9, 5, 9], "faces": faces("t")},
        {"from": [2, 5, 3], "to": [14, 7, 13], "faces": faces("t")},
        {"from": [3, 7, 4], "to": [13, 8, 12], "faces": faces("t")}]})
    w(os.path.join(A, "blockstates/starlink_dish.json"), {"variants": {"": {"model": "%s:block/starlink_dish" % NS}}})
    w(os.path.join(A, "models/item/starlink_dish.json"), {"parent": "%s:block/starlink_dish" % NS})
    # loot tables: the city furniture drops itself, Gotiya's sabotage items and billboards drop nothing
    droppers = ["asphalt", "neon", "shop_terminal", "robotaxi_terminal", "delivery_crate", "megapack", "starlink_dish"]
    for n in droppers:
        w(os.path.join(D, "loot_tables/blocks/%s.json" % n), {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": "%s:%s" % (NS, n)}], "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    for n in ("road_spikes", "concrete_barrier", "billboard_panel"):
        w(os.path.join(D, "loot_tables/blocks/%s.json" % n), {"type": "minecraft:block", "pools": []})
    w(os.path.join(RES, "data", "minecraft", "tags", "blocks", "mineable", "pickaxe.json"), {"replace": False, "values": ["%s:%s" % (NS, n) for n in
        ["asphalt", "neon", "road_spikes", "concrete_barrier", "billboard_panel", "shop_terminal", "robotaxi_terminal", "megapack", "starlink_dish"]]})
    # item models
    w(os.path.join(A, "models/item/wall_poster.json"), {"parent": "minecraft:item/generated", "textures": {"layer0": "%s:item/wall_poster" % NS}})
    for v in ("tesla_classic", "tesla_model3", "tesla_cybertruck", "tesla_cybercab", "tesla_roadster", "gotiya_monster_truck", "delivery_truck",
              "gotiya_hatch", "gotiya_mustang", "gotiya_lambo"):
        w(os.path.join(A, "models/item/%s_spawner.json" % v), CAR_ITEM)
    # the physical upgrades handed out by the depot pedestals: the car itself (drawn in 3D by the client) or a 3D block model
    for lv in ("level2", "level3", "level4", "level5"):
        w(os.path.join(A, "models/item/upgrade_%s.json" % lv), CAR_ITEM)
    for uid, parent in (("hydraulics", "minecraft:block/piston_inventory"), ("solar", "minecraft:block/daylight_detector"), ("ram", "minecraft:block/anvil"),
                        ("starlink", "%s:block/starlink_dish" % NS), ("billboards", "%s:block/billboard_panel" % NS)):
        w(os.path.join(A, "models/item/upgrade_%s.json" % uid), {"parent": parent})
    w(os.path.join(A, "models/item/upgrade_posters.json"), {"parent": "minecraft:item/generated", "textures": {"layer0": "%s:item/wall_poster" % NS}})
    w(os.path.join(D, "recipes/wall_poster.json"), {"type": "minecraft:crafting_shaped", "pattern": ["PPP", "PDP", "PPP"],
        "key": {"P": {"item": "minecraft:paper"}, "D": {"item": "minecraft:cyan_dye"}}, "result": {"item": "%s:wall_poster" % NS, "count": 1}})


# ---------------------------------------------------------------------------------------------------------------- images
def banners_and_icons():
    for n in ("showroom_banner", "city_billboard", "wall_poster"):
        dst = os.path.join(A, "textures", "signboard", n + ".png")
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copyfile(os.path.join(BANNERS, n + ".png"), dst)                 # byte-for-byte: the uploaded images, untouched
    poster = Image.open(os.path.join(BANNERS, "wall_poster.png")).convert("RGB")
    pw, ph = poster.size
    side = pw
    icon = poster.crop((0, int(ph * 0.22), side, int(ph * 0.22) + side)).resize((64, 64), Image.LANCZOS)
    img_save(icon, "textures/item/wall_poster.png")
    banner = Image.open(os.path.join(BANNERS, "showroom_banner.png")).convert("RGB")
    bw, bh = banner.size
    mark = banner.crop((0, 0, bh, bh)).resize((128, 128), Image.LANCZOS)
    img_save(mark, "icon.png")


# ------------------------------------------------------------------------------------------------------------- NPC skins
def cuboid(u, v, w_, h, d):
    return {"top": (u + d, v, w_, d), "bottom": (u + d + w_, v, w_, d), "right": (u, v + d, d, h), "front": (u + d, v + d, w_, h),
            "left": (u + d + w_, v + d, d, h), "back": (u + 2 * d + w_, v + d, w_, h)}


LOOKS = [
    # jacket, trousers, tie, skin, hair, eyes, hat, mustache
    dict(jacket=(26, 34, 70), trousers=(18, 22, 44), tie=(200, 30, 40), skin=(224, 172, 134), hair=(30, 22, 18), eyes=(60, 140, 70), cap=None, stache=False),   # manager
    dict(jacket=(20, 20, 24), trousers=(14, 14, 18), tie=(240, 200, 60), skin=(198, 142, 103), hair=(20, 16, 14), eyes=(90, 60, 30), cap=None, stache=False),
    dict(jacket=(54, 58, 66), trousers=(38, 40, 46), tie=(40, 160, 190), skin=(235, 190, 155), hair=(80, 60, 40), eyes=(60, 110, 180), cap=None, stache=False),
    dict(jacket=(216, 204, 176), trousers=(190, 178, 150), tie=(120, 30, 90), skin=(160, 108, 76), hair=(16, 12, 10), eyes=(70, 50, 30), cap=None, stache=False),
    dict(jacket=(112, 22, 34), trousers=(70, 16, 24), tie=(230, 190, 60), skin=(190, 130, 92), hair=(20, 16, 14), eyes=(50, 40, 30), cap=(112, 22, 34), stache=True),    # Seth Gotiya
    dict(jacket=(22, 66, 54), trousers=(16, 44, 36), tie=(240, 240, 240), skin=(214, 160, 122), hair=(60, 46, 36), eyes=(60, 130, 70), cap=None, stache=False),
    # the Tesla staff (shopkeeper / manager): the face IS the Tesla logo - a red head with the white "T" and its arc
    dict(jacket=(18, 18, 22), trousers=(12, 12, 16), tie=(227, 25, 55), skin=(227, 25, 55), hair=(227, 25, 55), eyes=(255, 255, 255), cap=None,
         stache=False, tesla=True),
]


def suit_skin(look):
    t = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(t)
    L = look

    def fill(rect, col):
        x, y, ww, hh = rect
        d.rectangle([x, y, x + ww - 1, y + hh - 1], fill=col + (255,))

    def pix(rect, px, py, col):
        x, y, _, _ = rect
        d.point((x + px, y + py), fill=col + (255,))

    shirt = (240, 240, 244)
    jacket_dark = tuple(max(0, v - 14) for v in L["jacket"])
    # head
    head = cuboid(0, 0, 8, 10, 8)
    for f in head.values():
        fill(f, L["skin"])
    for fx in ("right", "left", "back"):
        pass
    fr = head["front"]
    if L.get("tesla"):
        red, white, dark = (227, 25, 55), (250, 250, 250), (150, 12, 34)
        for f in head.values():
            fill(f, red)
        for xx in range(8):                                           # metallic edge round the face
            pix(fr, xx, 9, dark)
        for xx in range(1, 7):
            pix(fr, xx, 1, white)                                      # the arc over the T
        pix(fr, 0, 2, white)
        pix(fr, 7, 2, white)
        for xx in range(1, 7):
            pix(fr, xx, 3, white)                                      # the bar of the T (curving down at the tips)
        pix(fr, 1, 4, white)
        pix(fr, 6, 4, white)
        for yy in range(4, 9):
            pix(fr, 3, yy, white)                                      # the stem
            pix(fr, 4, yy, white)
        nose = cuboid(24, 0, 2, 4, 2)
        for f in nose.values():
            fill(f, dark)
        hat = cuboid(32, 0, 8, 10, 8)
        fill(hat["top"], dark)                                         # a dark crown so the head reads as a badge
        L = dict(L, cap=None)
    else:
        _face(L, fr, pix, head, fill)
    return _suit_body(L, t, d, fill, pix, shirt, jacket_dark)


def _face(L, fr, pix, head, fill):
    pix(fr, 1, 4, (255, 255, 255))
    pix(fr, 2, 4, L["eyes"])
    pix(fr, 5, 4, L["eyes"])
    pix(fr, 6, 4, (255, 255, 255))
    for px in (1, 2, 5, 6):
        pix(fr, px, 3, L["hair"])
    for px in (3, 4):
        pix(fr, px, 8, tuple(max(0, v - 40) for v in L["skin"]))
    if L["stache"]:
        for px in (1, 2, 3, 4, 5, 6):
            pix(fr, px, 7, L["hair"])
        pix(fr, 0, 8, L["hair"])
        pix(fr, 7, 8, L["hair"])
    nose = cuboid(24, 0, 2, 4, 2)
    for f in nose.values():
        fill(f, tuple(max(0, v - 22) for v in L["skin"]))


def _suit_body(L, t, d, fill, pix, shirt, jacket_dark):
    # hair / cap on the "hat" layer (same layout as the head, at u=32)
    hat = cuboid(32, 0, 8, 10, 8)
    top, right, front, left, back = hat["top"], hat["right"], hat["front"], hat["left"], hat["back"]
    if L.get("tesla"):
        pass
    elif L["cap"]:
        for f in (top,):
            fill(f, L["cap"])
        for f in (right, left, back, front):
            x, y, ww, hh = f
            d.rectangle([x, y, x + ww - 1, y + 2], fill=L["cap"] + (255,))
            d.rectangle([x, y + 2, x + ww - 1, y + 2], fill=(230, 190, 60, 255))
    else:
        fill(top, L["hair"])
        for f in (right, left):
            x, y, ww, hh = f
            d.rectangle([x, y, x + ww - 1, y + 3], fill=L["hair"] + (255,))
        fill(back, L["hair"])
        x, y, ww, hh = front
        d.rectangle([x, y, x + ww - 1, y + 1], fill=L["hair"] + (255,))
    # body, jacket (long coat overlay), arms, legs
    body = cuboid(16, 20, 8, 12, 6)
    for f in body.values():
        fill(f, L["jacket"])
    coat = cuboid(0, 38, 8, 20, 6)
    for f in coat.values():
        fill(f, L["jacket"])
    cf = coat["front"]
    for yy in range(0, 7):
        for xx in (3, 4):
            pix(cf, xx, yy, shirt)
    for yy in range(1, 9):
        for xx in (3, 4):
            pix(cf, xx, yy, L["tie"])
    for yy in range(0, 8):
        pix(cf, 2 - (yy // 4), yy, jacket_dark)
        pix(cf, 5 + (yy // 4), yy, jacket_dark)
    for yy in (10, 13):
        pix(cf, 3, yy, (210, 190, 90))
    bf = body["front"]
    for yy in range(0, 4):
        for xx in (3, 4):
            pix(bf, xx, yy, shirt)
    for yy in range(1, 6):
        for xx in (3, 4):
            pix(bf, xx, yy, L["tie"])
    arm_r = cuboid(44, 22, 4, 8, 4)
    for f in arm_r.values():
        fill(f, L["jacket"])
    fill((arm_r["front"][0], arm_r["front"][1] + 6, 4, 2), L["skin"])
    cross = cuboid(40, 38, 8, 4, 4)
    for f in cross.values():
        fill(f, L["jacket"])
    cfr = cross["front"]
    fill((cfr[0] + 2, cfr[1] + 1, 4, 3), L["skin"])
    leg = cuboid(0, 22, 4, 12, 4)
    for f in leg.values():
        fill(f, L["trousers"])
    for key in ("front", "back", "left", "right"):
        x, y, ww, hh = leg[key]
        d.rectangle([x, y + hh - 2, x + ww - 1, y + hh - 1], fill=(14, 12, 12, 255))
    return t


def npc_skins():
    for i, look in enumerate(LOOKS):
        img_save(suit_skin(look), "textures/entity/suited_%d.png" % i)


# ------------------------------------------------------------------------------------------------------------------- lang
def lang():
    L = {}
    L["itemGroup.tesla_tycoon"] = "Tesla Tycoon"
    L["item.tesla_tycoon.wall_poster"] = "Luxury Wall Poster"
    for k, v in {"asphalt": "Asphalt", "neon": "Neon Panel", "road_spikes": "Gotiya's Road Spikes", "concrete_barrier": "Gotiya's Concrete Roadblock",
                 "billboard_panel": "Billboard Board", "shop_terminal": "Upgrade Depot Terminal", "robotaxi_terminal": "Robotaxi Terminal",
                 "delivery_crate": "Tesla Delivery Crate", "megapack": "Tesla MegaPack", "starlink_dish": "Starlink Satellite Dish"}.items():
        L["block.tesla_tycoon.%s" % k] = v
    names = {"tesla_classic": "Tesla Classic Compact", "tesla_model3": "Tesla Model 3 (Autopilot Sedan)", "tesla_cybertruck": "Armored Tesla Cybertruck",
             "tesla_cybercab": "Autonomous Tesla Cybercab (Robotaxi)", "tesla_roadster": "Flying SpaceX Tesla Roadster",
             "gotiya_monster_truck": "Seth Gotiya's Rival Diesel Monster Truck", "delivery_truck": "Tesla Delivery Truck",
             "gotiya_hatch": "Gotiya Motors Diesel Hatchback", "gotiya_mustang": "Ford Mustang Shelby GT500 (Petrol V8)",
             "gotiya_lambo": "Lamborghini Aventador (Petrol V12)"}
    for k, v in names.items():
        L["entity.tesla_tycoon.%s" % k] = v
        L["item.tesla_tycoon.%s_spawner" % k] = v
    L["entity.tesla_tycoon.gotiya_truck"] = names["gotiya_monster_truck"]
    L["entity.tesla_tycoon.signboard"] = "Signboard"
    L["entity.tesla_tycoon.wall_poster"] = "Luxury Wall Poster"
    L["entity.tesla_tycoon.suited_villager"] = "Suited Villager"
    L["entity.tesla_tycoon.cinema_camera"] = "Cutscene Camera"
    L["subtitles.tesla_tycoon.voice.gotiya"] = "Seth Gotiya talks"
    L["subtitles.tesla_tycoon.voice.customer"] = "A customer talks"
    L["key.categories.tesla_tycoon"] = "Tesla Tycoon"
    L["key.tesla_tycoon.talk"] = "Push to talk (voice chat to Gotiya)"
    L["key.tesla_tycoon.ram"] = "Beast Ram Mode (Cybertruck)"
    L["key.tesla_tycoon.emp"] = "Fire EMP Shockwave (Roadster)"
    L["key.tesla_tycoon.guide"] = "Show / hide the guide"
    up = {
        "hydraulics": ("Jump Hydraulics + Armor Tires", "Counter to Gotiya's road spikes. SPACE makes any car hop, and armored tires crush the spikes instead of puncturing."),
        "level2": ("Tesla Model 3", "Adds a glass floor. The Model 3 (Autopilot Sedan) takes the ground floor; the Classic Compact moves up."),
        "solar": ("Tesla Solar Roof + MegaPack", "Counter to the blackout: brings the neon back and adds solar panels and MegaPacks."),
        "level3": ("Armored Cybertruck", "Adds a glass floor and unlocks the Armored Cybertruck."),
        "ram": ("Beast Ram Mode Module", "Counter to Gotiya's roadblocks: drive the Cybertruck into them (armed automatically, B toggles it) and smash 2 roadblocks."),
        "level4": ("Cybercab Robotaxi", "Adds a glass floor, the Robotaxi Terminal and 4 autonomous Cybercabs that loop the whole city and carry passengers between the sidewalk stations."),
        "starlink": ("Starlink Satellite Dish", "Part 1 of the GPS counter: a dish on the roof."),
        "billboards": ("Billboard Marketing Pack", "Part 2 of the GPS counter: right-click empty billboards on the highway turns around the city to run the Anant Tesla ad (+6% income each, 3 needed)."),
        "level5": ("Flying Roadster", "The finale: unlocks the Flying SpaceX Roadster on the rooftop helipad, the lobby launch pad and the EMP shockwave (R)."),
        "posters": ("Luxury Wall Poster x4", "Four wall posters to decorate the showroom walls and glass pillars."),
    }
    for k, (n, dsc) in up.items():
        L["upgrade.tesla_tycoon.%s" % k] = n
        L["upgrade.tesla_tycoon.%s.desc" % k] = dsc
        L["item.tesla_tycoon.upgrade_%s" % k] = n
    L["screen.tesla_tycoon.shop"] = "UPGRADE DEPOT"
    L["screen.tesla_tycoon.shop_sub"] = "Buy counters to Seth Gotiya's sabotage, upgrade the showroom floor by floor"
    L["hud.tesla_tycoon.level"] = "Showroom Lv.%s - %s"
    L["hud.tesla_tycoon.next"] = "Next: %s - $%s"
    L["hud.tesla_tycoon.shop_hint"] = "Buy it on its pedestal in the Upgrade Depot, then place it"
    L["hud.tesla_tycoon.guide_title"] = "GUIDE  -  press Right Shift to hide"
    L["hud.tesla_tycoon.blackout"] = "BLACKOUT: neon is down"
    L["hud.tesla_tycoon.showroom_hp"] = "Showroom %s%%"
    L["hud.tesla_tycoon.fly"] = "SPACE thrusters  |  CTRL descend  |  mouse: pitch + yaw"
    L["hud.tesla_tycoon.emp_ready"] = "[R] EMP shockwave READY"
    L["hud.tesla_tycoon.emp_cooldown"] = "[R] EMP recharging %ss"
    L["hud.tesla_tycoon.ram_on"] = "[B] BEAST RAM MODE: ON"
    L["hud.tesla_tycoon.ram_off"] = "[B] Beast Ram Mode: off"
    L["hud.tesla_tycoon.hop"] = "SPACE: hydraulic hop"
    s = {1: ("GOTIYA'S ROAD SPIKES on the road!", "Buy Jump Hydraulics + Armor Tires at the Upgrade Depot"),
         2: ("GOTIYA CUT THE POWER! Neon is dead", "Buy the Tesla Solar Roof + MegaPack at the Upgrade Depot"),
         3: ("GOTIYA'S CONCRETE ROADBLOCKS!", "Buy Beast Ram Mode, then smash 2 roadblocks with the Cybertruck"),
         4: ("GOTIYA JAMMED THE CYBERCAB GPS!", "Buy the Starlink dish + Billboard pack, then put up 3 billboards"),
         5: ("MONSTER TRUCK ATTACK ON THE SHOWROOM!", "Fly the Roadster: SPACE thrusters, then R = EMP on the truck")}
    for k, (a, b) in s.items():
        L["hud.tesla_tycoon.sabotage.%d" % k] = a
        L["hud.tesla_tycoon.counter.%d" % k] = b
    L["hud.tesla_tycoon.alarm.gate"] = "SECURITY ALARM - GOTIYA IS RAMMING THE GATE!"
    L["hud.tesla_tycoon.alarm.attack"] = "MONSTER TRUCK ATTACK - FLY THE ROADSTER, PRESS R"
    L["hud.tesla_tycoon.alarm.hint1"] = "Right-click the red Roadster on the helipad."
    L["hud.tesla_tycoon.alarm.hint2"] = "Right-click the red Roadster, SPACE = thrusters, R = EMP the truck."
    w(os.path.join(A, "lang/en_us.json"), L)


def main():
    block_textures()
    block_models()
    banners_and_icons()
    npc_skins()
    lang()
    print("resources written to", RES)


if __name__ == "__main__":
    main()
