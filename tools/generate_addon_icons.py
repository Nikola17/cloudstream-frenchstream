"""Génère les icônes studio HD 512x512 (PNG transparent squircle) pour le dépôt et les 4 addons CloudStream.

Rendu en supersampling 2x (1024x1024 -> 512x512 LANCZOS) pour des courbes et contours rasoirs.
Usage : python tools/generate_addon_icons.py
"""
import math
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT_SIZE = 512
S = 1024  # canvas supersamplé 2x
RADIUS = 230

FONT_CANDIDATES = [
    r"C:\Windows\Fonts\segoeuib.ttf",
    r"C:\Windows\Fonts\arialbd.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
]


def load_font(size: int) -> ImageFont.FreeTypeFont:
    for path in FONT_CANDIDATES:
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def squircle_mask(size: int, radius: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle((12, 12, size - 13, size - 13), radius=radius, fill=255)
    return mask


def diagonal_gradient(c_tl: tuple[int, int, int], c_br: tuple[int, int, int], glow_rgb: tuple[int, int, int]) -> Image.Image:
    # Dégradé fluide calculé sur grille 96x96 puis agrandi bicubiquement
    small = Image.new("RGB", (96, 96))
    px = small.load()
    for y in range(96):
        for x in range(96):
            t = min(1.0, max(0.0, (x / 95.0) * 0.55 + (y / 95.0) * 0.45))
            px[x, y] = (
                int(c_tl[0] + (c_br[0] - c_tl[0]) * t),
                int(c_tl[1] + (c_br[1] - c_tl[1]) * t),
                int(c_tl[2] + (c_br[2] - c_tl[2]) * t),
            )
    base = small.resize((S, S), Image.BICUBIC).convert("RGBA")

    # Halo lumineux doux au centre-haut
    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse((140, -80, S - 140, S - 240), fill=(glow_rgb[0], glow_rgb[1], glow_rgb[2], 95))
    base.alpha_composite(glow.filter(ImageFilter.GaussianBlur(120)))

    # Reflet vitré subtil en haut
    sheen = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(sheen).ellipse((-120, -440, S + 120, 380), fill=(255, 255, 255, 34))
    base.alpha_composite(sheen.filter(ImageFilter.GaussianBlur(28)))
    return base


def finish_icon(canvas: Image.Image, border_color=(255, 255, 255, 42)) -> Image.Image:
    # Bordure intérieure fine façon verre poli
    border = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(border).rounded_rectangle(
        (16, 16, S - 17, S - 17), radius=RADIUS - 4, outline=border_color, width=6
    )
    canvas.alpha_composite(border)

    mask = squircle_mask(S, RADIUS)
    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    out.paste(canvas, (0, 0), mask)
    return out.resize((OUT_SIZE, OUT_SIZE), Image.LANCZOS)


def add_shadow(canvas: Image.Image, draw_fn, blur: int = 24, dy: int = 16, alpha: int = 125) -> None:
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(layer), (0, 0, 0, alpha), dy)
    canvas.alpha_composite(layer.filter(ImageFilter.GaussianBlur(blur)))


def draw_play_triangle(draw: ImageDraw.ImageDraw, cx: int, cy: int, size: int, fill) -> None:
    h = int(size * 0.92)
    pts = [
        (cx - int(size * 0.38), cy - h // 2),
        (cx - int(size * 0.38), cy + h // 2),
        (cx + int(size * 0.56), cy),
    ]
    draw.polygon(pts, fill=fill)


def draw_centered_text(canvas: Image.Image, text: str, cy: int, font_size: int, fill=(255, 255, 255, 255)) -> None:
    font = load_font(font_size)
    probe = ImageDraw.Draw(canvas)
    bbox = probe.textbbox((0, 0), text, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    tx = (S - tw) // 2 - bbox[0]
    ty = cy - th // 2 - bbox[1]

    sh = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(sh).text((tx, ty + 10), text, font=font, fill=(0, 0, 0, 130))
    canvas.alpha_composite(sh.filter(ImageFilter.GaussianBlur(14)))
    ImageDraw.Draw(canvas).text((tx, ty), text, font=font, fill=fill)


def make_frenchstream_icon() -> Image.Image:
    # Cinéma rouge rubis -> grenat sombre + ruban pellicule + bouton play + FS
    c = diagonal_gradient((220, 38, 60), (65, 10, 28), (255, 95, 109))

    # Anneau lumineux central
    def ring(d, col, dy=0):
        d.ellipse((230, 155 + dy, S - 230, 715 + dy), fill=col)

    add_shadow(c, ring, blur=30, dy=20, alpha=140)
    ring_layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(ring_layer).ellipse((230, 155, S - 230, 715), fill=(255, 255, 255, 242))
    c.alpha_composite(ring_layer)

    # Triangle play rouge rubis au centre du disque blanc
    d = ImageDraw.Draw(c)
    draw_play_triangle(d, S // 2 + 14, 435, 230, (205, 24, 48, 255))

    # Petit liseré tricolore élégant en bas + label FS
    bar_y = 775
    bar_w = 104
    start_x = (S - bar_w * 3) // 2
    d.rounded_rectangle((start_x, bar_y, start_x + bar_w - 6, bar_y + 18), radius=9, fill=(59, 130, 246, 255))
    d.rounded_rectangle((start_x + bar_w + 3, bar_y, start_x + bar_w * 2 - 3, bar_y + 18), radius=9, fill=(255, 255, 255, 255))
    d.rounded_rectangle((start_x + bar_w * 2 + 6, bar_y, start_x + bar_w * 3, bar_y + 18), radius=9, fill=(244, 63, 94, 255))
    draw_centered_text(c, "FRENCH-STREAM", 865, 76, (255, 255, 255, 245))
    return finish_icon(c)


def make_movix_icon() -> Image.Image:
    # Violet électrique -> indigo profond + monogramme M géométrique + play néon
    c = diagonal_gradient((124, 58, 237), (24, 18, 74), (192, 132, 252))

    # Carte vitrée centrale
    glass = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(glass).rounded_rectangle((175, 165, S - 175, 725), radius=120, fill=(255, 255, 255, 26), outline=(255, 255, 255, 70), width=6)
    c.alpha_composite(glass)

    # M stylisé vectoriel
    def draw_m(d, col, dy=0):
        pts = [
            (255, 625 + dy), (255, 265 + dy), (365, 265 + dy),
            (512, 465 + dy), (659, 265 + dy), (769, 265 + dy),
            (769, 625 + dy), (667, 625 + dy), (667, 425 + dy),
            (512, 610 + dy), (357, 425 + dy), (357, 625 + dy),
        ]
        d.polygon(pts, fill=col)

    add_shadow(c, draw_m, blur=24, dy=16, alpha=130)
    draw_m(ImageDraw.Draw(c), (255, 255, 255, 250))

    # Petit accent cyan néon en triangle play sous le V du M
    draw_play_triangle(ImageDraw.Draw(c), S // 2 + 4, 330, 86, (56, 189, 248, 255))
    draw_centered_text(c, "MOVIX", 840, 108, (255, 255, 255, 248))
    return finish_icon(c)


def make_frenchmanga_icon() -> Image.Image:
    # Orange mangaka / corail -> cramoisi profond + soleil levant stylisé + étoile anime + FM
    c = diagonal_gradient((249, 115, 22), (136, 19, 55), (253, 186, 116))

    # Rayons subtils en arrière-plan
    rays = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    rd = ImageDraw.Draw(rays)
    cx, cy = S // 2, 430
    for i in range(12):
        a1 = math.radians(i * 30)
        a2 = math.radians(i * 30 + 11)
        pts = [
            (cx, cy),
            (cx + int(math.cos(a1) * 760), cy + int(math.sin(a1) * 760)),
            (cx + int(math.cos(a2) * 760), cy + int(math.sin(a2) * 760)),
        ]
        rd.polygon(pts, fill=(255, 255, 255, 18))
    c.alpha_composite(rays)

    # Disque doré / blanc façon lune manga + étoile 4 branches
    def draw_emblem(d, col, dy=0):
        d.ellipse((242, 160 + dy, S - 242, 700 + dy), fill=col)

    add_shadow(c, draw_emblem, blur=28, dy=18, alpha=130)
    ImageDraw.Draw(c).ellipse((242, 160, S - 242, 700), fill=(255, 251, 235, 248))

    # Étoile 4 pointes dynamique couleur corail-rouge au centre
    def star4(cx0, cy0, r_out, r_in):
        pts = []
        for i in range(8):
            ang = math.radians(i * 45 - 90)
            r = r_out if i % 2 == 0 else r_in
            pts.append((cx0 + int(math.cos(ang) * r), cy0 + int(math.sin(ang) * r)))
        return pts

    d = ImageDraw.Draw(c)
    d.polygon(star4(S // 2, 430, 195, 58), fill=(234, 65, 36, 255))
    d.ellipse((S // 2 - 44, 430 - 44, S // 2 + 44, 430 + 44), fill=(255, 251, 235, 255))
    draw_play_triangle(d, S // 2 + 4, 430, 56, (234, 65, 36, 255))

    draw_centered_text(c, "FRENCH-MANGA", 845, 76, (255, 255, 255, 250))
    return finish_icon(c)


def make_fstv_icon() -> Image.Image:
    # Émeraude sport / bleu nuit + écran TV moderne + ondes live + pastille rouge LIVE
    c = diagonal_gradient((16, 185, 129), (12, 34, 68), (52, 211, 153))

    # Cadre écran TV blanc lumineux
    def draw_tv(d, col, dy=0):
        d.rounded_rectangle((170, 210 + dy, S - 170, 665 + dy), radius=76, fill=col)

    add_shadow(c, draw_tv, blur=28, dy=20, alpha=135)
    d = ImageDraw.Draw(c)
    d.rounded_rectangle((170, 210, S - 170, 665), radius=76, fill=(248, 250, 252, 245))
    d.rounded_rectangle((208, 248, S - 208, 627), radius=48, fill=(13, 25, 48, 255))

    # Pied TV élégant
    d.rounded_rectangle((390, 688, S - 390, 722), radius=17, fill=(248, 250, 252, 225))

    # Triangle play blanc dans l'écran
    draw_play_triangle(d, S // 2 + 10, 438, 170, (255, 255, 255, 250))

    # Badge rouge LIVE en haut à droite de l'écran
    d.rounded_rectangle((590, 272, 782, 348), radius=38, fill=(239, 68, 68, 255))
    d.ellipse((614, 296, 642, 324), fill=(255, 255, 255, 255))
    font_live = load_font(44)
    d.text((656, 282), "LIVE", font=font_live, fill=(255, 255, 255, 255))

    draw_centered_text(c, "FSTV · SPORT", 845, 86, (255, 255, 255, 248))
    return finish_icon(c)


def make_repo_icon() -> Image.Image:
    # Dépôt CloudStream FR : bleu roi -> indigo + nuage/play + drapeau FR subtil
    c = diagonal_gradient((37, 99, 235), (30, 27, 75), (96, 165, 250))

    # Disque central brillant
    def disc(d, col, dy=0):
        d.ellipse((230, 155 + dy, S - 230, 715 + dy), fill=col)

    add_shadow(c, disc, blur=30, dy=20, alpha=140)
    d = ImageDraw.Draw(c)
    d.ellipse((230, 155, S - 230, 715), fill=(255, 255, 255, 245))
    draw_play_triangle(d, S // 2 + 16, 435, 230, (37, 99, 235, 255))

    # Accent tricolore moderne
    bar_y = 770
    bar_w = 104
    start_x = (S - bar_w * 3) // 2
    d.rounded_rectangle((start_x, bar_y, start_x + bar_w - 6, bar_y + 20), radius=10, fill=(59, 130, 246, 255))
    d.rounded_rectangle((start_x + bar_w + 3, bar_y, start_x + bar_w * 2 - 3, bar_y + 20), radius=10, fill=(255, 255, 255, 255))
    d.rounded_rectangle((start_x + bar_w * 2 + 6, bar_y, start_x + bar_w * 3, bar_y + 20), radius=10, fill=(239, 68, 68, 255))

    draw_centered_text(c, "CLOUDSTREAM FR", 865, 74, (255, 255, 255, 248))
    return finish_icon(c)


def main():
    out_dir = Path("assets/icons")
    out_dir.mkdir(parents=True, exist_ok=True)
    makers = {
        "repo.png": make_repo_icon,
        "frenchstream.png": make_frenchstream_icon,
        "movix.png": make_movix_icon,
        "frenchmanga.png": make_frenchmanga_icon,
        "fstv.png": make_fstv_icon,
    }
    for filename, fn in makers.items():
        img = fn()
        path = out_dir / filename
        img.save(path, format="PNG", optimize=True)
        print(f"Generated {path} ({path.stat().st_size / 1024:.1f} KB)")


if __name__ == "__main__":
    main()
