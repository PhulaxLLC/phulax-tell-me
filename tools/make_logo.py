"""Phulax Android Tell Me logo, made from the phulax.pro shield (logo_2.png):
blueprint grid inside the shield -> radar rings + sweep (it scans), sound arcs beside the shield (it talks).
Outputs to branding/: logo_full.png (shield + wordmark), icon_512.png (Play icon), launcher foregrounds per density."""
import math, sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageChops

SRC = Path(sys.argv[1]); OUT = Path(sys.argv[2]); RES = Path(sys.argv[3]) if len(sys.argv) > 3 else None
OUT.mkdir(parents=True, exist_ok=True)
src = Image.open(SRC).convert("RGBA")
NAVY = (14, 37, 92, 255); GREY = (90, 90, 90, 255); GLOW = (120, 200, 255)

shield = src.crop((105, 204, 675, 838))
w, h = shield.size
px = shield.load()
# blue field = where blue clearly dominates (the grid lives there; the silver P and border don't)
blue = Image.new("L", (w, h), 0); bp = blue.load()
for y in range(h):
    for x in range(w):
        r, g, b, a = px[x, y]
        if b > 90 and b > r + 45 and b > g + 25: bp[x, y] = 255
blue = blue.filter(ImageFilter.MaxFilter(3)).filter(ImageFilter.MinFilter(3))

# calm the grid: blur the blue field a little, then draw radar on it
base = shield.copy()
soft = shield.filter(ImageFilter.GaussianBlur(5))
base.paste(soft, (0, 0), blue)
cx, cy = 300, 266          # the sound wave in the P
radar = Image.new("RGBA", (w, h), (0, 0, 0, 0)); d = ImageDraw.Draw(radar)
for i, r in enumerate(range(55, 420, 52)):
    d.ellipse((cx - r, cy - r, cx + r, cy + r), outline=GLOW + (150 - i * 14,), width=4)
sweep = Image.new("RGBA", (w, h), (0, 0, 0, 0)); sd = ImageDraw.Draw(sweep)
for k in range(40):        # fading wedge, like a radar sweep
    a0 = -60 + k; sd.pieslice((cx - 420, cy - 420, cx + 420, cy + 420), a0, a0 + 1.2, fill=GLOW + (int(3 + k * 2.4),))
radar = Image.alpha_composite(radar, sweep)
radar.putalpha(ImageChops.multiply(radar.getchannel("A"), blue))
# keep the silver P on top: only paint where the field is blue
mark = Image.alpha_composite(base, radar)

def arcs(img, x, y, size, color, n=3, flip=False):
    dd = ImageDraw.Draw(img)
    for i in range(n):
        r = size * (0.45 + i * 0.32)
        box = (x - r, y - r, x + r, y + r)
        a = (150, 210) if flip else (-30, 30)
        dd.arc(box, *a, fill=color, width=max(6, int(size * 0.09)))

# ---- full logo: shield + sound arcs + wordmark (PHULAX lettering reused from the original)
W, H = 900, 1040
full = Image.new("RGBA", (W, H), (255, 255, 255, 255))
ox = (W - w) // 2
full.alpha_composite(mark, (ox, 40))
arcs(full, ox + w - 40, 40 + h // 2 - 20, 70, NAVY)
arcs(full, ox + 40, 40 + h // 2 - 20, 70, NAVY, flip=True)
word = src.crop((95, 868, 548, 950))           # "PHULAX" without ".pro"
full.alpha_composite(word, ((W - word.width) // 2, 40 + h + 40))
def font(names, size):
    for n in names:
        try: return ImageFont.truetype(n, size)
        except OSError: pass
    return ImageFont.load_default()
f = font(["C:/Windows/Fonts/segoeui.ttf", "C:/Windows/Fonts/arial.ttf"], 72)
fd = ImageDraw.Draw(full); t = "Android Tell Me"
tw = fd.textlength(t, font=f); fd.text(((W - tw) / 2, 40 + h + 140), t, font=f, fill=GREY)
f2 = font(["C:/Windows/Fonts/segoeui.ttf"], 34); t2 = "by Phulax LLC"
tw = fd.textlength(t2, font=f2); fd.text(((W - tw) / 2, 40 + h + 235), t2, font=f2, fill=GREY)
full.convert("RGB").save(OUT / "logo_full.png")

# ---- square icon (Play store 512, launcher): shield on white with sound arcs
def icon(size, pad_frac=0.12, bg=(255, 255, 255, 255), with_arcs=True):
    c = Image.new("RGBA", (size, size), bg)
    inner = int(size * (1 - 2 * pad_frac))
    s = mark.copy(); s.thumbnail((int(inner * 0.78), inner), Image.LANCZOS)
    x = (size - s.width) // 2; y = (size - s.height) // 2
    c.alpha_composite(s, (x, y))
    if with_arcs:
        arcs(c, x + s.width - int(size * 0.05), y + s.height // 2 - size // 30, int(size * 0.075), NAVY)
        arcs(c, x + int(size * 0.05), y + s.height // 2 - size // 30, int(size * 0.075), NAVY, flip=True)
    return c
icon(512, 0.06).convert("RGB").save(OUT / "icon_512.png")

# ---- launcher: adaptive foreground (108dp canvas, art inside the 66dp safe zone) per density
if RES:
    for dens, px108 in {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}.items():
        d_ = RES / f"drawable-{dens}"; d_.mkdir(parents=True, exist_ok=True)
        icon(px108, 0.20, bg=(0, 0, 0, 0)).save(d_ / "ic_launcher_art.png")
print("done", mark.size)
