#!/usr/bin/env python3
"""
Aura Signature: genera la colección de fondos originales (ilustración digital hecha por código, no
fotografías ni imágenes de terceros) en signature/ con su catálogo JSON.

Reglas de la colección: protagonistas de tamaño moderado en la mitad inferior, el tercio superior
libre para el reloj, sin números impresos, sin astronautas, círculos, halos ni anillos artificiales.

Uso: python3 tools/aura_signature.py   (requiere Pillow y numpy). Resolución 1440 × 3120.
Licencia de las obras: CC BY 4.0, «Aura Signature».
"""
import json
import math
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W, H = 1440, 3120
OUT = os.path.join(os.path.dirname(__file__), "..", "signature")
RNG = np.random.default_rng(7)


# ------------------------------------------------------------------ utilidades de pintura

def hexrgb(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], dtype=np.float32)


def vgrad(stops, h=H, w=W):
    """Degradado vertical: stops = [(posición 0–1, '#rrggbb'), ...]."""
    ys = np.linspace(0, 1, h, dtype=np.float32)
    out = np.zeros((h, 3), np.float32)
    pos = [p for p, _ in stops]
    cols = [hexrgb(c) for _, c in stops]
    for ch in range(3):
        out[:, ch] = np.interp(ys, pos, [c[ch] for c in cols])
    return np.repeat(out[:, None, :], w, axis=1)


def fbm(h, w, octaves=5, base=6, seed=0):
    """Ruido fractal suave en [0,1] (escalado bicúbico de rejillas aleatorias)."""
    rng = np.random.default_rng(seed)
    acc = np.zeros((h, w), np.float32)
    amp, norm, cells = 1.0, 0.0, base
    for _ in range(octaves):
        g = rng.random((cells, max(2, int(cells * w / h)))).astype(np.float32)
        layer = np.asarray(Image.fromarray((g * 255).astype(np.uint8)).resize((w, h), Image.BICUBIC), np.float32) / 255
        acc += layer * amp
        norm += amp
        amp *= 0.5
        cells *= 2
    return acc / norm


def blend(img, color, mask):
    """Pinta [color] sobre [img] con la opacidad [mask] (h×w, 0–1)."""
    c = hexrgb(color) if isinstance(color, str) else color
    return img * (1 - mask[..., None]) + c * mask[..., None]


def poly_mask(points, blur=0.0, h=H, w=W):
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).polygon([(float(x), float(y)) for x, y in points], fill=255)
    if blur > 0:
        m = m.filter(ImageFilter.GaussianBlur(blur))
    return np.asarray(m, np.float32) / 255


def rect_mask(x0, y0, x1, y1, blur=0.0):
    return poly_mask([(x0, y0), (x1, y0), (x1, y1), (x0, y1)], blur)


def ridge_line(y_base, amplitude, roughness, seed, w=W, points=257):
    """Perfil de sierra por desplazamiento del punto medio."""
    rng = np.random.default_rng(seed)
    ys = np.zeros(points, np.float32)
    step, amp = points - 1, amplitude
    while step > 1:
        half = step // 2
        for i in range(half, points - 1, step):
            ys[i] = (ys[i - half] + ys[i + half]) / 2 + rng.uniform(-amp, amp)
        amp *= roughness
        step = half
    xs = np.linspace(0, w, points)
    return [(x, y_base + y) for x, y in zip(xs, ys)]


def haze(img, y0, y1, color, strength):
    """Bruma horizontal: más densa en el centro de la banda."""
    ys = np.arange(H, dtype=np.float32)
    band = np.clip(1 - np.abs((ys - (y0 + y1) / 2) / max(1, (y1 - y0) / 2)), 0, 1) ** 1.6 * strength
    return blend(img, color, np.repeat(band[:, None], W, axis=1))


def grain(img, amount=5.0, seed=1):
    rng = np.random.default_rng(seed)
    return img + rng.normal(0, amount, img.shape[:2]).astype(np.float32)[..., None]


def vignette(img, strength=0.35):
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    d = np.sqrt(((xx - W / 2) / (W * 0.75)) ** 2 + ((yy - H * 0.55) / (H * 0.75)) ** 2)
    return img * (1 - strength * np.clip(d, 0, 1)[..., None] ** 2)


def save(img, name):
    im = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
    im.save(os.path.join(OUT, f"{name}.webp"), "WEBP", quality=84, method=6)
    im.resize((360, 780), Image.LANCZOS).save(os.path.join(OUT, "thumbs", f"{name}.webp"), "WEBP", quality=80)


# ------------------------------------------------------------------ obras: «Sueños quietos»

def escalera():
    """Una escalera de madera que sale del mar y se pierde en un banco de nubes al alba."""
    img = vgrad([(0, "#2B3A63"), (0.38, "#8C7AA8"), (0.55, "#F2B7A0"), (0.62, "#F8D9B8"), (0.63, "#6F8FA8"), (1, "#1E3448")])
    sea = np.zeros((H, W), np.float32)
    sea[int(H * 0.63):] = 1
    waves = (np.sin(np.linspace(0, 260, H))[:, None] * 0.5 + 0.5) * fbm(H, W, 4, 40, 3)
    img = blend(img, "#F7DCC4", sea * np.clip(waves - 0.55, 0, 1) * 0.9)
    clouds = np.clip((fbm(H, W, 5, 5, 11) - 0.48) * 3, 0, 1)
    band = np.clip(1 - np.abs(np.linspace(0, 1, H) - 0.47) / 0.07, 0, 1)[:, None]
    img = blend(img, "#FBE6D6", clouds * band * 0.95)
    # Escalones en diagonal desde el agua hacia las nubes.
    x, y = W * 0.38, H * 0.80
    for i in range(16):
        w, h = W * 0.10, H * 0.016
        img = blend(img, "#3A2A26", rect_mask(x, y, x + w, y + h * 0.55, 1))
        img = blend(img, "#7A5A48", rect_mask(x, y - h * 0.35, x + w, y, 1))
        x += W * 0.022
        y -= H * 0.0195
    return grain(vignette(img, 0.3), 4)


def puerta():
    """Una puerta sola en las dunas; del otro lado, el mar."""
    img = vgrad([(0, "#1D2A4A"), (0.35, "#C0748A"), (0.55, "#F4B183"), (0.6, "#E9A66F"), (1, "#7A3E2A")])
    for k, (yb, col, a) in enumerate([(0.60, "#D99A68", 0.9), (0.66, "#C47F52", 0.95), (0.74, "#A8643F", 1.0)]):
        pts = [(0, H)] + [(x, H * yb + math.sin(x / W * math.pi * (1.4 + k * 0.5) + k) * H * 0.025) for x in np.linspace(0, W, 120)] + [(W, H)]
        img = blend(img, col, poly_mask(pts, 2) * a)
    # Marco de la puerta y, dentro, otro paisaje (mar azul).
    x0, x1, y0, y1 = W * 0.42, W * 0.58, H * 0.555, H * 0.74
    inside = vgrad([(0, "#7FB7D9"), (0.5, "#B9DDEB"), (0.51, "#2F6F95"), (1, "#123B57")], h=int(y1 - y0), w=int(x1 - x0))
    img[int(y0):int(y0) + inside.shape[0], int(x0):int(x0) + inside.shape[1]] = inside
    for (a, b, c, d) in [(x0 - 16, y0 - 16, x0, y1), (x1, y0 - 16, x1 + 16, y1), (x0 - 16, y0 - 16, x1 + 16, y0)]:
        img = blend(img, "#2A1A14", rect_mask(a, b, c, d, 0.8))
    shadow = poly_mask([(x0, y1), (x1, y1), (x1 + W * 0.35, y1 + H * 0.05), (x0 + W * 0.30, y1 + H * 0.06)], 10)
    img = blend(img, "#4A2418", shadow * 0.45)
    return grain(vignette(img, 0.3), 4)


def barco():
    """Un bote de madera varado en un campo de lavanda al anochecer."""
    img = vgrad([(0, "#1B1E3C"), (0.45, "#5A4A86"), (0.58, "#D48BA6"), (0.6, "#7C5A9E"), (1, "#2B1E42")])
    yy = np.linspace(0, 1, H)[:, None]
    xx = np.linspace(-1, 1, W)[None, :]
    rows = np.sin((xx / np.maximum(yy - 0.58, 0.01)) * 9) * 0.5 + 0.5
    field = (yy > 0.6) * np.clip(rows, 0, 1)
    img = blend(img, "#9C7CCB", field * 0.55)
    img = blend(img, "#3E2A66", (yy > 0.6) * (1 - rows) * 0.35)
    cx, cy, bw = W * 0.5, H * 0.73, W * 0.30
    hull = [(cx - bw / 2, cy - H * 0.018)] + [(cx + bw / 2 * math.cos(t), cy + H * 0.035 * math.sin(t)) for t in np.linspace(math.pi, 0, 40)] + [(cx + bw / 2, cy - H * 0.018)]
    img = blend(img, "#3B2416", poly_mask(hull, 1.2))
    img = blend(img, "#6E4A2E", poly_mask([(cx - bw / 2, cy - H * 0.022), (cx + bw / 2, cy - H * 0.022), (cx + bw / 2, cy - H * 0.014), (cx - bw / 2, cy - H * 0.014)], 1))
    img = haze(img, H * 0.56, H * 0.66, "#E6B9D6", 0.35)
    return grain(vignette(img, 0.35), 5)


def ballena():
    """Una ballena que nada en la niebla sobre un lago quieto con un muelle."""
    img = vgrad([(0, "#8FA3AE"), (0.5, "#D9DEDC"), (0.62, "#E8E6DE"), (0.63, "#B6C1C2"), (1, "#5E7279")])
    fog = fbm(H, W, 5, 4, 21)
    img = blend(img, "#F1F0EA", np.clip(fog - 0.35, 0, 1) * 0.6)
    cx, cy, L = W * 0.52, H * 0.50, W * 0.55
    top = [(cx - L / 2 + L * t, cy - H * 0.022 * math.sin(math.pi * t) ** 0.7) for t in np.linspace(0, 1, 60)]
    bot = [(cx - L / 2 + L * t, cy + H * 0.016 * math.sin(math.pi * t) ** 0.9) for t in np.linspace(1, 0, 60)]
    tail = [(cx - L / 2 - W * 0.08, cy - H * 0.022), (cx - L / 2 + W * 0.02, cy), (cx - L / 2 - W * 0.08, cy + H * 0.016)]
    img = blend(img, "#4E5F68", poly_mask(top + bot, 3) * 0.85)
    img = blend(img, "#4E5F68", poly_mask(tail, 3) * 0.85)
    img = blend(img, "#6F818A", poly_mask([(cx - W * 0.02, cy + H * 0.006), (cx + W * 0.06, cy + H * 0.03), (cx + W * 0.08, cy + H * 0.006)], 3) * 0.7)
    for i in range(7):
        x = W * (0.70 + i * 0.022)
        img = blend(img, "#3E4A50", rect_mask(x, H * 0.70, x + 10, H * 0.745, 1))
    img = blend(img, "#3E4A50", rect_mask(W * 0.68, H * 0.70, W * 0.86, H * 0.707, 1))
    return grain(img, 3.5)


# ------------------------------------------------------------------ obras: «Horizontes»

def montanas():
    """Cordones montañosos al alba, cada vez más lejanos y claros."""
    img = vgrad([(0, "#253456"), (0.4, "#B98CA0"), (0.58, "#F6C9A0"), (1, "#F6C9A0")])
    layers = [(0.56, "#9C8AA6", 18, 0.62), (0.62, "#7C6C8E", 16, 0.6), (0.69, "#5A4E70", 13, 0.58), (0.77, "#3A3250", 9, 0.55), (0.86, "#221E34", 5, 0.5)]
    for i, (yb, col, blur, rough) in enumerate(layers):
        pts = [(0, H)] + ridge_line(H * yb, H * 0.07, rough, 30 + i) + [(W, H)]
        img = blend(img, col, poly_mask(pts, blur * 0.4))
        img = haze(img, H * (yb - 0.01), H * (yb + 0.05), "#F3CDB4", 0.18)
    return grain(img, 3.5)


def mar():
    """Mar abierto en la hora azul, con rocas lejanas."""
    img = vgrad([(0, "#0D1B33"), (0.45, "#2F4A7A"), (0.6, "#8FA3C8"), (0.61, "#355B86"), (1, "#081526")])
    yy = np.linspace(0, 1, H)[:, None]
    ripples = (np.sin(fbm(H, W, 3, 60, 41) * 40 + yy * 900) * 0.5 + 0.5)
    img = blend(img, "#AFC2DE", (yy > 0.61) * np.clip(ripples - 0.75, 0, 1) * 1.6 * np.clip(1.2 - yy, 0, 1))
    for x0, w, hgt in [(0.10, 0.12, 0.018), (0.74, 0.18, 0.026)]:
        pts = [(W * x0, H * 0.61)] + ridge_line(0, H * hgt, 0.55, int(x0 * 100), w=int(W * w), points=33)
        pts = [(W * x0 + px, H * 0.61 - abs(py)) for px, py in pts[1:]] + [(W * (x0 + w), H * 0.61)]
        img = blend(img, "#0E1A2C", poly_mask(pts, 1.5))
    return grain(vignette(img, 0.3), 4)


def bosque():
    """Pinos que aparecen y desaparecen en la niebla."""
    img = vgrad([(0, "#AFC0BE"), (0.55, "#E6EAE3"), (1, "#9DB1AA")])
    rng = np.random.default_rng(5)
    for depth, (col, alpha, scale) in enumerate([("#8FA39E", 0.55, 0.6), ("#5E7671", 0.75, 0.8), ("#2C403C", 1.0, 1.0)]):
        for _ in range(9 + depth * 3):
            x = rng.uniform(-0.05, 1.05) * W
            base = H * (0.70 + depth * 0.07 + rng.uniform(-0.02, 0.02))
            hgt = H * (0.10 + rng.uniform(0, 0.06)) * scale
            wid = hgt * 0.32
            pts = [(x, base - hgt)]
            for k in range(6, 0, -1):
                y = base - hgt * (k - 1) / 6
                pts += [(x + wid * (1 - (k - 1) / 6) / 2 + 6, y)]
            pts += [(x, base)]
            pts += [(2 * x - px, py) for px, py in reversed(pts[1:-1])]
            img = blend(img, col, poly_mask(pts, 2.5 - depth) * alpha)
        img = haze(img, H * (0.62 + depth * 0.07), H * (0.80 + depth * 0.07), "#EEF1EC", 0.45)
    return grain(img, 3)


def salar():
    """Un salar que refleja el cielo, con una figura pequeña caminando."""
    sky = vgrad([(0, "#3C5E9E"), (0.4, "#87A9D8"), (0.6, "#F3D6C2")], h=int(H * 0.6))
    img = np.concatenate([sky, sky[::-1][: H - sky.shape[0]] * 0.92], axis=0)
    clouds = np.clip((fbm(int(H * 0.6), W, 5, 4, 51) - 0.5) * 3, 0, 1)
    img[: clouds.shape[0]] = img[: clouds.shape[0]] * (1 - clouds[..., None] * 0.75) + 255 * clouds[..., None] * 0.75
    refl = clouds[::-1][: H - clouds.shape[0]]
    img[clouds.shape[0]:] = img[clouds.shape[0]:] * (1 - refl[..., None] * 0.6) + 240 * refl[..., None] * 0.6
    hexes = (np.abs(np.sin(np.linspace(0, 1, W)[None, :] * 80 + np.linspace(0, 1, H)[:, None] * 300)) < 0.03) * (np.linspace(0, 1, H)[:, None] > 0.62)
    img = blend(img, "#FFFFFF", hexes * 0.25)
    fx, fy = W * 0.62, H * 0.6
    figure = [(fx - 9, fy), (fx - 6, fy - 60), (fx - 12, fy - 62), (fx - 4, fy - 95), (fx + 4, fy - 95), (fx + 12, fy - 62), (fx + 6, fy - 60), (fx + 9, fy)]
    img = blend(img, "#1E2230", poly_mask(figure, 0.8))
    img = blend(img, "#1E2230", poly_mask([(x, 2 * fy - y) for x, y in figure], 2) * 0.4)
    return grain(img, 3)


# ------------------------------------------------------------------ obras: «Materia»

def papel():
    """Planos de papel translúcido que se superponen en la luz."""
    img = vgrad([(0, "#EDE9E1"), (1, "#D8CCB6")])
    rng = np.random.default_rng(9)
    for i in range(9):
        cx, cy = W * rng.uniform(0.15, 0.85), H * rng.uniform(0.5, 0.92)
        w, h, ang = W * rng.uniform(0.35, 0.7), H * rng.uniform(0.08, 0.18), rng.uniform(-0.5, 0.5)
        pts = [(cx + math.cos(ang) * dx - math.sin(ang) * dy, cy + math.sin(ang) * dx + math.cos(ang) * dy) for dx, dy in [(-w / 2, -h / 2), (w / 2, -h / 2), (w / 2, h / 2), (-w / 2, h / 2)]]
        col = ["#D8BA8B", "#FFFFFF", "#C9B79A", "#719A91"][i % 4]
        img = blend(img, "#9C8C74", poly_mask([(x + 18, y + 26) for x, y in pts], 30) * 0.18)
        img = blend(img, col, poly_mask(pts, 1.5) * 0.42)
    return grain(img, 2.5)


def kintsugi():
    """Cerámica nocturna reparada con oro."""
    base = fbm(H, W, 5, 5, 61)
    img = vgrad([(0, "#15181C"), (1, "#22262B")]) * (0.8 + 0.4 * base[..., None])
    vein = 1 - 2 * np.abs(fbm(H, W, 4, 3, 63) - 0.5)
    lower = np.clip((np.linspace(0, 1, H)[:, None] - 0.45) * 4, 0, 1)
    vein2 = 1 - 2 * np.abs(fbm(H, W, 4, 5, 67) - 0.5)
    gold = np.clip(np.maximum(vein - 0.94, vein2 - 0.955) * 28, 0, 1) * lower
    img = blend(img, "#D8BA8B", gold)
    glow = np.asarray(Image.fromarray((gold * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(10)), np.float32) / 255
    img = blend(img, "#B98D4E", glow * 0.35)
    return grain(img, 3)


def marmol():
    """Mármol jade con vetas claras."""
    n = fbm(H, W, 6, 3, 71)
    veins = np.abs(np.sin((np.linspace(0, 1, W)[None, :] * 3 + np.linspace(0, 1, H)[:, None] * 2 + n * 4) * math.pi))
    img = vgrad([(0, "#1F3A35"), (0.6, "#476D64"), (1, "#719A91")])
    img = blend(img, "#C9DDD5", np.clip((0.06 - veins) * 14, 0, 1) * 0.8 * np.clip((np.linspace(0, 1, H)[:, None] - 0.3) * 3, 0, 1))
    img = blend(img, "#0F1F1C", (1 - n) * 0.25)
    return grain(img, 2.5)


def seda():
    """Pliegues de seda champán que caen en diagonal."""
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    u, v = xx / W, yy / H
    warp = fbm(H, W, 3, 3, 81) * 2.2
    f = np.sin((u * 7 + v * 2.5 + warp) * math.pi) * 0.7 + np.sin((u * 15 - v * 1.5 + warp * 0.6) * math.pi) * 0.3
    shade = (f - f.min()) / (f.max() - f.min())
    fade = np.clip((v - 0.36) * 3.5, 0, 1)
    img = vgrad([(0, "#241D17"), (1, "#3A3026")])
    img = blend(img, "#C9A97A", shade ** 1.2 * fade * 0.9)
    img = blend(img, "#FFF1D6", np.clip(shade - 0.82, 0, 1) * 5 * fade)
    img = blend(img, "#1A140F", np.clip(0.18 - shade, 0, 1) * 4 * fade * 0.8)
    return grain(img, 2.5)


COLLECTIONS = [
    ("suenos", "Sueños quietos", "Surrealismo poético: escenas imposibles y serenas.", [
        ("escalera", "La escalera del alba", escalera),
        ("puerta", "Una puerta al mar", puerta),
        ("barco", "Bote en la lavanda", barco),
        ("ballena", "Ballena de niebla", ballena),
    ]),
    ("horizontes", "Horizontes", "Paisajes de luz y distancia, al estilo de la fotografía de larga exposición.", [
        ("montanas", "Montañas al alba", montanas),
        ("mar", "Mar en hora azul", mar),
        ("bosque", "Bosque de niebla", bosque),
        ("salar", "Salar espejo", salar),
    ]),
    ("materia", "Materia", "Texturas y luz: papel, cerámica, piedra y tela.", [
        ("papel", "Papel y luz", papel),
        ("kintsugi", "Kintsugi nocturno", kintsugi),
        ("marmol", "Mármol jade", marmol),
        ("seda", "Seda", seda),
    ]),
]


def main():
    os.makedirs(os.path.join(OUT, "thumbs"), exist_ok=True)
    catalog = {"version": 1, "license": "CC BY 4.0 — Aura Signature (obras originales generadas por código)", "collections": []}
    for cid, title, desc, works in COLLECTIONS:
        items = []
        for wid, wtitle, fn in works:
            save(fn(), wid)
            items.append({"id": wid, "title": wtitle, "image": f"{wid}.webp", "thumb": f"thumbs/{wid}.webp", "width": W, "height": H,
                          "clockArea": [0.08, 0.12, 0.92, 0.42]})
            print("ok", wid)
        catalog["collections"].append({"id": cid, "title": title, "description": desc, "items": items})
    with open(os.path.join(OUT, "catalog.json"), "w") as f:
        json.dump(catalog, f, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main()
