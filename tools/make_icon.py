#!/usr/bin/env python3
"""X3Paranoids launcher icon: a phosphor-green wireframe Recognizer inside the tank sight's corner
brackets, on black with a receding grid — the arcade cabinet's screen, distilled. Rendered with
Pillow (glow = blurred line layer under crisp lines) into every launcher density plus art/icon-512.png.
    tools/.venv/bin/python tools/make_icon.py   (Pillow + numpy)
"""
import os, math
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app/src/main/res")
S = 1024  # master

GREEN = (70, 255, 120)
GREEN_DIM = (30, 140, 70)
RED = (255, 60, 50)
WHITE = (215, 245, 225)

def layer(): return Image.new("RGBA", (S, S), (0, 0, 0, 0))

def strokes(draw, segs, color, w):
    for (x0, y0, x1, y1) in segs: draw.line((x0, y0, x1, y1), fill=color, width=w)

def box(x0, y0, x1, y1, d=0):
    """A wireframe box with a small isometric depth extrusion d (px), as segments."""
    s = [(x0, y0, x1, y0), (x1, y0, x1, y1), (x1, y1, x0, y1), (x0, y1, x0, y0)]
    if d:
        s += [(x0, y0, x0 + d, y0 - d), (x1, y0, x1 + d, y0 - d), (x1, y1, x1 + d, y1 - d),
              (x0 + d, y0 - d, x1 + d, y0 - d), (x1 + d, y0 - d, x1 + d, y1 - d)]
    return s

def recognizer(cx, cy, sc):
    """The iconic inverted-U: a cross-bar, a raised cab, two hanging legs with flared feet."""
    segs = []
    d = int(16 * sc)
    bar = box(cx - 300 * sc, cy - 60 * sc, cx + 300 * sc, cy + 30 * sc, d)
    cab = box(cx - 95 * sc, cy - 150 * sc, cx + 95 * sc, cy - 60 * sc, d)
    legL = box(cx - 300 * sc, cy + 30 * sc, cx - 200 * sc, cy + 300 * sc, d)
    legR = box(cx + 200 * sc, cy + 30 * sc, cx + 300 * sc, cy + 300 * sc, d)
    segs += bar + cab + legL + legR
    # feet flare
    segs += [(cx - 300 * sc, cy + 300 * sc, cx - 330 * sc, cy + 340 * sc), (cx - 200 * sc, cy + 300 * sc, cx - 170 * sc, cy + 340 * sc),
             (cx - 330 * sc, cy + 340 * sc, cx - 170 * sc, cy + 340 * sc),
             (cx + 300 * sc, cy + 300 * sc, cx + 330 * sc, cy + 340 * sc), (cx + 200 * sc, cy + 300 * sc, cx + 170 * sc, cy + 340 * sc),
             (cx + 170 * sc, cy + 340 * sc, cx + 330 * sc, cy + 340 * sc)]
    # bar detail ribs + cab visor line
    for i in range(1, 6):
        x = cx - 300 * sc + i * 100 * sc
        segs.append((x, cy - 60 * sc, x, cy + 30 * sc))
    segs.append((cx - 70 * sc, cy - 105 * sc, cx + 70 * sc, cy - 105 * sc))
    return segs

def brackets(inset, arm, w_arm):
    a = inset; b = S - inset
    return [(a, a, a + arm, a), (a, a, a, a + arm), (b, a, b - arm, a), (b, a, b, a + arm),
            (a, b, a + arm, b), (a, b, a, b - arm), (b, b, b - arm, b), (b, b, b, b - arm),
            (S / 2, a, S / 2, a + w_arm), (S / 2, b, S / 2, b - w_arm), (a, S / 2, a + w_arm, S / 2), (b, S / 2, b - w_arm, S / 2)]

def grid_floor(y0, y1, vanish_y):
    segs = []
    # horizontal lines spaced by perspective
    for i in range(12):
        t = i / 11.0
        y = y0 + (y1 - y0) * (t ** 2.2)
        segs.append((0, y, S, y))
    # converging verticals
    for i in range(-6, 7):
        x1 = S / 2 + i * 150
        x0 = S / 2 + i * 40
        segs.append((x0, y0, x1, y1))
    return segs

master = Image.new("RGBA", (S, S), (0, 0, 0, 255))
# faint green radial haze
haze = layer(); hd = ImageDraw.Draw(haze)
hd.ellipse((S * 0.15, S * 0.12, S * 0.85, S * 0.82), fill=(20, 90, 40, 90))
haze = haze.filter(ImageFilter.GaussianBlur(160))
master = Image.alpha_composite(master, haze)

# floor grid (dim, with glow)
gl = layer(); gd = ImageDraw.Draw(gl)
strokes(gd, grid_floor(S * 0.62, S * 0.92, S * 0.5), GREEN_DIM + (170,), 3)
master = Image.alpha_composite(master, gl.filter(ImageFilter.GaussianBlur(5)))
master = Image.alpha_composite(master, gl)

# recognizer: glow pass then crisp pass
segs = recognizer(S / 2 - 8, S * 0.44, 1.0)
gl = layer(); gd = ImageDraw.Draw(gl); strokes(gd, segs, GREEN + (255,), 22)
master = Image.alpha_composite(master, gl.filter(ImageFilter.GaussianBlur(26)))
gl = layer(); gd = ImageDraw.Draw(gl); strokes(gd, segs, GREEN + (255,), 9)
master = Image.alpha_composite(master, gl.filter(ImageFilter.GaussianBlur(2)))
gl = layer(); gd = ImageDraw.Draw(gl); strokes(gd, segs, WHITE + (255,), 3)
master = Image.alpha_composite(master, gl)

# the cab's red eye slit
gl = layer(); gd = ImageDraw.Draw(gl)
cx, cy = S / 2 - 8, S * 0.44
gd.rounded_rectangle((cx - 48, cy - 140, cx + 48, cy - 118), radius=8, fill=RED + (255,))
master = Image.alpha_composite(master, gl.filter(ImageFilter.GaussianBlur(18)))
master = Image.alpha_composite(master, gl.filter(ImageFilter.GaussianBlur(1)))

# sight brackets (kept inside the adaptive-icon safe zone)
gl = layer(); gd = ImageDraw.Draw(gl); strokes(gd, brackets(int(S * 0.20), int(S * 0.11), int(S * 0.05)), GREEN + (255,), 10)
master = Image.alpha_composite(master, gl.filter(ImageFilter.GaussianBlur(10)))
gl = layer(); gd = ImageDraw.Draw(gl); strokes(gd, brackets(int(S * 0.20), int(S * 0.11), int(S * 0.05)), WHITE + (255,), 4)
master = Image.alpha_composite(master, gl)

# scanlines
sl = layer(); sd = ImageDraw.Draw(sl)
for y in range(0, S, 6): sd.line((0, y, S, y), fill=(0, 0, 0, 70), width=2)
master = Image.alpha_composite(master, sl)

rgb = master.convert("RGB")
os.makedirs(os.path.join(ROOT, "art"), exist_ok=True)
rgb.resize((512, 512), Image.LANCZOS).save(os.path.join(ROOT, "art/icon-512.png"))

# adaptive foreground: 108dp canvas, art in the 66% safe zone → we scale the master (which already
# keeps everything inside ~60-80%) onto a 108-unit canvas
def fg(px):
    canvas = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    art = master.resize((px, px), Image.LANCZOS)
    canvas.alpha_composite(art)
    return canvas
def legacy(px):
    return rgb.resize((px, px), Image.LANCZOS)
def legacy_round(px):
    im = legacy(px).convert("RGBA")
    mask = Image.new("L", (px, px), 0); ImageDraw.Draw(mask).ellipse((0, 0, px - 1, px - 1), fill=255)
    im.putalpha(mask); return im

dens = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
for name, k in dens.items():
    d = os.path.join(RES, f"mipmap-{name}"); os.makedirs(d, exist_ok=True)
    fg(int(108 * k)).save(os.path.join(d, "ic_launcher_foreground.png"))
    legacy(int(48 * k)).save(os.path.join(d, "ic_launcher.png"))
    legacy_round(int(48 * k)).save(os.path.join(d, "ic_launcher_round.png"))
print("icon written")
