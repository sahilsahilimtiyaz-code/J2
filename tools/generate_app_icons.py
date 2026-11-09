#!/usr/bin/env python3
"""Generates every Android launcher asset for Octa-Jarvis from one square source.

Usage
-----
    python3 tools/generate_app_icons.py                     # render the built-in orb artwork
    python3 tools/generate_app_icons.py design/app-icon.png  # use your own 1024x1024 PNG

Outputs (repo-relative):
    design/octa-jarvis-icon.png                         master 1024px artwork
    app/src/main/res/drawable-nodpi/ic_app_logo.png     in-app branding logo (1024px)
    app/src/main/res/drawable-nodpi/ic_app_logo_foreground.png  adaptive-icon foreground
    app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png
    app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_round.png

The adaptive foreground keeps the emblem inside the 66dp system safe zone so the
launcher mask (circle, squircle, rounded square) never clips it.
"""
from __future__ import annotations

import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
DESIGN = os.path.join(ROOT, "design")
MASTER = os.path.join(DESIGN, "octa-jarvis-icon.png")

DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

SS = 4  # supersampling factor


# --------------------------------------------------------------------------- #
# Built-in artwork: glossy sphere with a chrome-and-blue orbital swoosh.
# --------------------------------------------------------------------------- #
def _radial_gradient(size: int, inner, outer, radius: float, center=(0.5, 0.5)) -> Image.Image:
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    cx, cy = center[0] * size, center[1] * size
    dist = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / (radius * size)
    t = np.clip(dist, 0.0, 1.0)[..., None]
    inner_arr = np.array(inner[:3], dtype=np.float32)
    outer_arr = np.array(outer[:3], dtype=np.float32)
    rgb = inner_arr * (1 - t) + outer_arr * t
    alpha = np.full((size, size, 1), 255.0, dtype=np.float32)
    return Image.fromarray(np.concatenate([rgb, alpha], axis=2).astype(np.uint8), "RGBA")


def _vertical_gradient(size: int, top, bottom) -> Image.Image:
    ramp = np.linspace(0.0, 1.0, size, dtype=np.float32)[:, None, None]
    top_arr = np.array(top[:3], dtype=np.float32)
    bottom_arr = np.array(bottom[:3], dtype=np.float32)
    rgb = top_arr * (1 - ramp) + bottom_arr * ramp
    rgb = np.repeat(rgb, size, axis=1)
    alpha = np.full((size, size, 1), 255.0, dtype=np.float32)
    return Image.fromarray(np.concatenate([rgb, alpha], axis=2).astype(np.uint8), "RGBA")


def _bezier(points, steps: int = 900) -> np.ndarray:
    """Cubic Bezier evaluation for a list of 4 control points."""
    t = np.linspace(0.0, 1.0, steps, dtype=np.float32)[:, None]
    p0, p1, p2, p3 = [np.array(p, dtype=np.float32) for p in points]
    return (
        ((1 - t) ** 3) * p0
        + 3 * ((1 - t) ** 2) * t * p1
        + 3 * (1 - t) * (t ** 2) * p2
        + (t ** 3) * p3
    )


def _taper_width(t: np.ndarray, peak: float, ends: float) -> np.ndarray:
    """Smooth taper: thick through the middle, fine at both tips."""
    shaped = np.clip(np.sin(np.clip(t, 0.0, 1.0) * math.pi), 0.0, 1.0) ** 0.55
    return ends + (peak - ends) * shaped


def _stroke(curve: np.ndarray, widths: np.ndarray, size: int, color_fn) -> Image.Image:
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    n = len(curve)
    for i in range(n - 1):
        w = float(widths[i])
        if w <= 0.4:
            continue
        x, y = curve[i]
        color = color_fn(i / max(1, n - 1))
        draw.ellipse((x - w, y - w, x + w, y + w), fill=color)
    return layer


def render_orb(size: int = 1024) -> Image.Image:
    """Renders the Octa-Jarvis glass-sphere emblem on a black background."""
    s = size * SS
    canvas = _radial_gradient(
        s,
        inner=(6.0, 12.0, 22.0, 255.0),
        outer=(0.0, 0.0, 0.0, 255.0),
        radius=0.62,
        center=(0.5, 0.46),
    ).convert("RGBA")

    cx = cy = s / 2.0
    radius = s * 0.415

    # --- Sphere body -------------------------------------------------------
    sphere = _vertical_gradient(s, (12, 27, 43, 255), (2, 5, 10, 255))
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).ellipse(
        (cx - radius, cy - radius, cx + radius, cy + radius), fill=255
    )

    # Curved inner shading so the glass reads as a ball rather than a disc.
    shade = np.zeros((s, s, 3), dtype=np.float32)
    yy, xx = np.mgrid[0:s, 0:s].astype(np.float32)
    nx = (xx - cx) / radius
    ny = (yy - cy) / radius
    nz = np.clip(1.0 - nx ** 2 - ny ** 2, 0.0, 1.0) ** 0.5
    light = np.clip(0.05 - 0.30 * nx - 0.42 * ny + 0.24 * nz, 0.0, 0.48)
    shade[..., 0] = 34.0 * light
    shade[..., 1] = 78.0 * light
    shade[..., 2] = 130.0 * light
    glow = Image.fromarray(np.clip(shade, 0, 255).astype(np.uint8), "RGB")
    glow = glow.filter(ImageFilter.GaussianBlur(s * 0.05)).convert("RGBA")
    sphere = Image.alpha_composite(sphere, glow).convert("RGBA")

    canvas.paste(sphere, (0, 0), mask)

    # --- Rim light: bright blue-white edge, strongest low-left -------------
    rim = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    draw = ImageDraw.Draw(rim)
    for i, (width, color) in enumerate(
        [
            (s * 0.020, (60, 170, 255, 190)),
            (s * 0.011, (150, 225, 255, 220)),
            (s * 0.005, (245, 252, 255, 235)),
        ]
    ):
        draw.arc(
            (cx - radius + i * 1.5, cy - radius + i * 1.5, cx + radius - i * 1.5, cy + radius - i * 1.5),
            start=150,
            end=330,
            fill=color,
            width=max(2, int(width)),
        )
    rim = rim.filter(ImageFilter.GaussianBlur(s * 0.006))
    canvas = Image.alpha_composite(canvas, rim)

    # Broad blue bloom under the sphere for depth.
    bloom = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    bd = ImageDraw.Draw(bloom)
    bd.ellipse(
        (cx - radius * 0.92, cy + radius * 0.42, cx + radius * 0.92, cy + radius * 1.02),
        fill=(24, 104, 205, 56),
    )
    bloom = bloom.filter(ImageFilter.GaussianBlur(s * 0.055))
    canvas = Image.alpha_composite(canvas, bloom)

    # Floor reflection glow.
    floor = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    fd = ImageDraw.Draw(floor)
    fd.ellipse(
        (cx - radius * 0.78, cy + radius * 0.94, cx + radius * 0.78, cy + radius * 1.17),
        fill=(120, 200, 255, 78),
    )
    floor = floor.filter(ImageFilter.GaussianBlur(s * 0.04))
    canvas = Image.alpha_composite(canvas, floor)

    # --- Chrome swoosh -----------------------------------------------------
    # Two mirrored crescents with a sweeping diagonal: the "Z / J" motion mark.
    upper = _bezier(
        [(s * 0.255, s * 0.545), (s * 0.330, s * 0.255), (s * 0.760, s * 0.250), (s * 0.800, s * 0.430)],
    )
    lower = _bezier(
        [(s * 0.800, s * 0.430), (s * 0.845, s * 0.640), (s * 0.380, s * 0.700), (s * 0.245, s * 0.560)],
    )
    for curve, peak in ((upper, s * 0.052), (lower, s * 0.046)):
        t = np.linspace(0.0, 1.0, len(curve), dtype=np.float32)
        widths = _taper_width(t, peak=peak, ends=s * 0.004)

        # Blue ambient glow behind the ribbon.
        halo = _stroke(
            curve,
            widths * 2.5,
            s,
            lambda p: (36, 130, 255, int(70 + 45 * math.sin(math.pi * p))),
        ).filter(ImageFilter.GaussianBlur(s * 0.018))
        canvas = Image.alpha_composite(canvas, halo)

        # Dark chrome base.
        base = _stroke(
            curve,
            widths * 0.97,
            s,
            lambda p: (
                int(24 + 26 * math.sin(math.pi * p)),
                int(40 + 34 * math.sin(math.pi * p)),
                int(62 + 44 * math.sin(math.pi * p)),
                255,
            ),
        )
        canvas = Image.alpha_composite(canvas, base)

        # Metallic body: silver top, electric blue through the low arc.
        def chrome(p: float):
            lift = math.sin(math.pi * p)
            r = int(210 + 30 * lift)
            g = int(225 + 22 * lift)
            b = int(240 + 15 * lift)
            blue = max(0.0, 1.0 - abs(p - 0.66) / 0.36)
            r = int(r * (1 - 0.55 * blue))
            g = int(g * (1 - 0.18 * blue))
            b = int(min(255, b * (1 - 0.02 * blue)))
            return (r, g, b, 255)

        body = _stroke(curve, widths * 0.86, s, chrome)
        canvas = Image.alpha_composite(canvas, body)

        # Specular hairline riding the top edge of the ribbon.
        offset = np.array([0.0, -widths[0] * 0.34], dtype=np.float32)
        hair = _stroke(
            curve + offset,
            widths * 0.16,
            s,
            lambda p: (255, 255, 255, int(150 + 80 * math.sin(math.pi * p))),
        ).filter(ImageFilter.GaussianBlur(s * 0.0025))
        canvas = Image.alpha_composite(canvas, hair)

        # Cool bounce light low on the curve.
        bounce = _stroke(
            curve + np.array([0.0, widths[0] * 0.30], dtype=np.float32),
            widths * 0.20,
            s,
            lambda p: (110, 200, 255, int(60 + 70 * math.sin(math.pi * p))),
        ).filter(ImageFilter.GaussianBlur(s * 0.004))
        canvas = Image.alpha_composite(canvas, bounce)


    # Sharp diagonal blade sweeping across the sphere (the signature stroke).
    blade_curve = _bezier(
        [(s * 0.185, s * 0.760), (s * 0.430, s * 0.505), (s * 0.630, s * 0.395), (s * 0.880, s * 0.262)],
    )
    bt = np.linspace(0.0, 1.0, len(blade_curve), dtype=np.float32)
    blade_widths = _taper_width(bt, peak=s * 0.040, ends=s * 0.0016)
    blade_halo = _stroke(
        blade_curve,
        blade_widths * 2.2,
        s,
        lambda p: (60, 150, 255, int(40 + 55 * math.sin(math.pi * p))),
    ).filter(ImageFilter.GaussianBlur(s * 0.016))
    canvas = Image.alpha_composite(canvas, blade_halo)

    def blade_chrome(p: float):
        lift = math.sin(math.pi * p) ** 0.7
        base = int(150 + 105 * lift)
        return (int(base * 0.92), int(base * 0.97), min(255, int(base * 1.03)), 255)

    blade_body = _stroke(blade_curve, blade_widths * 0.86, s, blade_chrome)
    canvas = Image.alpha_composite(canvas, blade_body)
    blade_hi = _stroke(
        blade_curve + np.array([0.0, -blade_widths[0] * 0.30], dtype=np.float32),
        blade_widths * 0.18,
        s,
        lambda p: (255, 255, 255, int(120 + 90 * math.sin(math.pi * p))),
    ).filter(ImageFilter.GaussianBlur(s * 0.0022))
    canvas = Image.alpha_composite(canvas, blade_hi)

    # --- Glass highlights --------------------------------------------------
    glass = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glass)
    gd.ellipse(
        (cx - radius * 0.62, cy - radius * 0.82, cx + radius * 0.10, cy - radius * 0.34),
        fill=(228, 244, 255, 96),
    )
    gd.ellipse(
        (cx - radius * 0.52, cy - radius * 0.74, cx - radius * 0.10, cy - radius * 0.40),
        fill=(255, 255, 255, 132),
    )
    gd.ellipse(
        (cx - radius * 0.55, cy + radius * 0.52, cx + radius * 0.55, cy + radius * 0.78),
        fill=(120, 200, 255, 42),
    )
    glass = glass.filter(ImageFilter.GaussianBlur(s * 0.022))
    glass_mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(glass_mask).ellipse(
        (cx - radius * 0.97, cy - radius * 0.97, cx + radius * 0.97, cy + radius * 0.97), fill=255
    )
    canvas = Image.alpha_composite(canvas, Image.composite(glass, Image.new("RGBA", (s, s)), glass_mask))

    return canvas.resize((size, size), Image.LANCZOS).convert("RGBA")


# --------------------------------------------------------------------------- #
# Export
# --------------------------------------------------------------------------- #
def _square(image: Image.Image, size: int) -> Image.Image:
    return image.convert("RGBA").resize((size, size), Image.LANCZOS)


def _round_crop(image: Image.Image, size: int) -> Image.Image:
    big = image.convert("RGBA").resize((size * 4, size * 4), Image.LANCZOS)
    mask = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size * 4, size * 4), fill=255)
    out = Image.new("RGBA", (size * 4, size * 4), (0, 0, 0, 0))
    out.paste(big, (0, 0), mask)
    return out.resize((size, size), Image.LANCZOS)


def _adaptive_foreground(master: Image.Image, size: int = 512) -> Image.Image:
    """Emblem on a transparent canvas, scaled into the adaptive-icon safe zone."""
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inner = int(size * 0.66)  # 108dp canvas, 72dp safe circle
    orb = master.resize((inner, inner), Image.LANCZOS)
    mask = Image.new("L", (inner, inner), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, inner - 1, inner - 1), fill=255)
    canvas.paste(orb, ((size - inner) // 2, (size - inner) // 2), mask)
    return canvas


def main(argv: list[str]) -> int:
    source = argv[1] if len(argv) > 1 else None
    if source:
        master = Image.open(source).convert("RGBA")
        if master.width != master.height:
            side = min(master.width, master.height)
            left = (master.width - side) // 2
            top = (master.height - side) // 2
            master = master.crop((left, top, left + side, top + side))
        master = _square(master, 1024)
        origin = source
    else:
        master = render_orb(1024)
        origin = "built-in Octa-Jarvis orb renderer"

    os.makedirs(DESIGN, exist_ok=True)
    master.save(MASTER)
    master.save(os.path.join(RES, "drawable-nodpi", "ic_app_logo.png"))
    _adaptive_foreground(master).save(
        os.path.join(RES, "drawable-nodpi", "ic_app_logo_foreground.png")
    )

    for folder, px in DENSITIES.items():
        target = os.path.join(RES, folder)
        os.makedirs(target, exist_ok=True)
        _square(master, px).save(os.path.join(target, "ic_launcher.png"))
        _round_crop(master, px).save(os.path.join(target, "ic_launcher_round.png"))

    # Web / README copies used by the Octa-Jarvis download site.
    site = os.path.join(ROOT, "site", "assets")
    if os.path.isdir(site):
        master.save(os.path.join(site, "app-icon.png"))
        master.resize((180, 180), Image.LANCZOS).save(os.path.join(site, "apple-touch-icon.png"))
        master.resize((32, 32), Image.LANCZOS).save(os.path.join(site, "favicon.png"))

    print(f"Source: {origin}")
    print(f"Master: {os.path.relpath(MASTER, ROOT)} (1024x1024)")
    print("Wrote launcher icons for: " + ", ".join(f"{k}@{v}" for k, v in DENSITIES.items()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
