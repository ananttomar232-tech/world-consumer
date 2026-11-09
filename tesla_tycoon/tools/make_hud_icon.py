#!/usr/bin/env python3
"""Draws the glowing green cash stack used by the ProBoiz-style money bar (textures/gui/cash_stack.png, 48x48, transparent).
usage: python3 tools/make_hud_icon.py"""
import math
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

S = 8                      # supersampling
W = 64 * S
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "assets", "tesla_tycoon", "textures", "gui", "cash_stack.png")


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def bill(d, cx, cy, w, h, shade):
    """one banknote seen slightly from above: dark rim, bright face, inner frame"""
    rim = lerp((6, 70, 40), (10, 100, 56), shade)
    face = lerp((38, 190, 96), (96, 245, 140), shade)
    r = int(h * 0.28)
    d.rounded_rectangle((cx - w // 2, cy - h // 2, cx + w // 2, cy + h // 2), r, fill=rim + (255,))
    m = int(h * 0.12)
    d.rounded_rectangle((cx - w // 2 + m, cy - h // 2 + m, cx + w // 2 - m, cy + h // 2 - m), max(2, r - m), fill=face + (255,))
    m2 = int(h * 0.26)
    d.rounded_rectangle((cx - w // 2 + m2, cy - h // 2 + m2, cx + w // 2 - m2, cy + h // 2 - m2), max(2, r - m2), outline=rim + (200,), width=max(2, S // 2))


def main():
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    # soft green glow behind everything
    glow = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    for i in range(40, 0, -1):
        a = int(150 * (1 - i / 40.0) ** 2.0)
        r = int(W * 0.49 * i / 40.0)
        gd.ellipse((W // 2 - r, W // 2 - r, W // 2 + r, W // 2 + r), fill=(40, 255, 120, a))
    img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(S * 2)))
    d = ImageDraw.Draw(img, "RGBA")
    bw, bh = int(W * 0.66), int(W * 0.30)
    cx = W // 2
    base = int(W * 0.66)
    step = int(W * 0.115)
    # shadow under the stack
    sh = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    ImageDraw.Draw(sh).ellipse((cx - bw // 2, base + bh // 2 - S * 3, cx + bw // 2, base + bh // 2 + S * 6), fill=(0, 30, 12, 150))
    img.alpha_composite(sh.filter(ImageFilter.GaussianBlur(S * 1.5)))
    d = ImageDraw.Draw(img, "RGBA")
    layers = 4
    for i in range(layers):
        y = base - i * step
        bill(d, cx, y, bw, bh, i / (layers - 1.0))
    # paper band round the middle of the stack
    top_y = base - (layers - 1) * step
    d.rectangle((cx - int(bw * 0.10), top_y - bh // 2 + S * 2, cx + int(bw * 0.10), base + bh // 2 - S * 2), fill=(236, 246, 230, 235))
    # the dollar sign on the top note
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", int(bh * 0.95))
    except OSError:
        font = ImageFont.load_default()
    tw = d.textlength("$", font=font)
    d.text((cx - tw / 2 + S * 6, top_y - bh * 0.62), "$", font=font, fill=(8, 80, 44, 255))
    d.text((cx - tw / 2 - S * 18, top_y - bh * 0.62), "$", font=font, fill=(8, 80, 44, 200))
    # glints
    d.ellipse((cx - bw // 2 + S * 3, top_y - bh // 2 + S * 3, cx - bw // 2 + S * 9, top_y - bh // 2 + S * 7), fill=(255, 255, 255, 190))
    out = img.resize((48, 48), Image.LANCZOS)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    out.save(OUT)
    print("wrote", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
