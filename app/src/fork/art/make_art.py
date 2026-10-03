"""Regenerates the GooseFlix artwork in src/fork/res from goose.png and wordmark.png here.

Usage (needs Pillow):
    python3 app/src/fork/art/make_art.py app/src/fork/res
"""

import os
import sys

from PIL import Image, ImageDraw, ImageFilter

SRC = os.path.dirname(os.path.abspath(__file__))
RES = sys.argv[1]  # app/src/fork/res

goose = Image.open(f'{SRC}/goose.png').convert('RGBA')
wordmark = Image.open(f'{SRC}/wordmark.png').convert('RGBA')

NAVY = (15, 23, 42)
NAVY_HI = (23, 37, 84)  # #172554
GLOW = (59, 130, 246)  # #3b82f6
DENSITIES = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}


def out(path):
    full = os.path.join(RES, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    return full


def fit_height(img, h):
    return img.resize((round(img.width * h / img.height), round(h)), Image.LANCZOS)


def fit_width(img, w):
    return img.resize((round(w), round(img.height * w / img.width)), Image.LANCZOS)


def backdrop(w, h, glow_at=(0.3, 0.5)):
    """Navy diagonal gradient with a soft blue glow, like the mocks."""
    grad = Image.new('RGB', (w, h))
    px = grad.load()
    for y in range(h):
        for x in range(w):
            t = min(1.0, (x / w + y / h) / 1.2)
            px[x, y] = tuple(round(NAVY_HI[i] + (NAVY[i] - NAVY_HI[i]) * t) for i in range(3))
    glow = Image.new('L', (w, h), 0)
    gx, gy = glow_at
    ImageDraw.Draw(glow).ellipse(
        [w * gx - w * 0.32, h * gy - h * 0.42, w * gx + w * 0.32, h * gy + h * 0.42], fill=80)
    glow = glow.filter(ImageFilter.GaussianBlur(min(w, h) * 0.18))
    grad.paste(Image.new('RGB', (w, h), GLOW), (0, 0), glow)
    return grad.convert('RGBA')


# --- TV banner: 320x180 dp at xhdpi; a little margin, bigger wordmark than the mock ---
def banner(scale):
    S = 4  # draw big, shrink once
    w, h = 320 * scale * S // 2, 180 * scale * S // 2
    img = backdrop(w, h, glow_at=(0.22, 0.5))
    u = w / 320  # px per banner unit
    margin, gap, goose_h = 9 * u, 5 * u, 92 * u
    g = fit_height(goose, goose_h)
    word_w = w - 2 * margin - g.width - gap
    t = fit_width(wordmark, word_w)
    x = margin
    img.alpha_composite(g, (round(x), round((h - g.height) / 2)))
    x += g.width + gap
    img.alpha_composite(t, (round(x), round((h - t.height) / 2 + 2 * u)))
    return img.resize((w // S, h // S), Image.LANCZOS).convert('RGB')


for name, scale in (('xhdpi', 2), ('xxhdpi', 3), ('xxxhdpi', 4)):
    banner(scale).save(out(f'mipmap-{name}/ic_banner_fork.png'), optimize=True)
# Pre-API-26 devices read this one directly
banner(2).save(out('mipmap-xhdpi/ic_banner.png'), optimize=True)


# --- Adaptive launcher icon foreground: 108dp canvas, art inside the 66dp safe zone ---
for name, d in DENSITIES.items():
    side = round(108 * d)
    fg = Image.new('RGBA', (side, side), (0, 0, 0, 0))
    g = fit_height(goose, 60 * d)
    fg.alpha_composite(g, ((side - g.width) // 2, (side - g.height) // 2))
    fg.save(out(f'mipmap-{name}/ic_launcher_foreground.webp'), lossless=True)


# --- Legacy launcher icons (pre-26 and launchers that ignore adaptive icons) ---
def legacy(side, round_shape):
    S = 4
    big = side * S
    bg = backdrop(big, big, glow_at=(0.45, 0.55))
    mask = Image.new('L', (big, big), 0)
    if round_shape:
        ImageDraw.Draw(mask).ellipse([0, 0, big - 1, big - 1], fill=255)
    else:
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, big - 1, big - 1], radius=big * 0.22, fill=255)
    g = fit_height(goose, big * 0.72)
    bg.alpha_composite(g, ((big - g.width) // 2, (big - g.height) // 2))
    icon = Image.new('RGBA', (big, big), (0, 0, 0, 0))
    icon.paste(bg, (0, 0), mask)
    return icon.resize((side, side), Image.LANCZOS)


for name, d in DENSITIES.items():
    legacy(round(48 * d), False).save(out(f'mipmap-{name}/ic_launcher.webp'), lossless=True)
    legacy(round(48 * d), True).save(out(f'mipmap-{name}/ic_launcher_round.webp'), lossless=True)


# --- Sign-in screen artwork (nodpi, drawn at about 2x display size) ---
fit_height(goose, 300).save(out('drawable-nodpi/sign_in_goose.png'), optimize=True)
fit_width(wordmark, 720).save(out('drawable-nodpi/sign_in_wordmark.png'), optimize=True)
