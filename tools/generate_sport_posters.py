"""Génère les visuels des événements sportifs de l'addon FSTV (assets/sports/*.webp).

Pictogrammes : Twemoji (https://github.com/jdecked/twemoji), licence CC-BY 4.0.
Usage : python3 tools/generate_sport_posters.py <dossier_svg_twemoji> <dossier_sortie>
"""
import io
import sys
from pathlib import Path

import cairosvg
from PIL import Image, ImageDraw, ImageFilter, ImageFont

WIDTH, HEIGHT = 960, 540
FONT = "/usr/share/fonts/opentype/inter/Inter-ExtraBold.otf"

# clé, libellé, pictogramme Twemoji, couleur sombre, couleur vive
SPORTS = [
    ("foot", "Football", "26bd", "#0b3d1f", "#1f9d55"),
    ("basket", "Basket", "1f3c0", "#3b1505", "#e8670c"),
    ("tennis", "Tennis", "1f3be", "#0f2f13", "#7cb518"),
    ("rugby", "Rugby", "1f3c9", "#2a1206", "#a0522d"),
    ("handball", "Handball", "1f93e", "#06233f", "#1e7fd8"),
    ("cyclisme", "Cyclisme", "1f6b4", "#3a2c00", "#e0b400"),
    ("f1", "Formule 1", "1f3c1", "#2b0000", "#d90429"),
    ("moto", "Moto", "1f3cd", "#1b1b1b", "#ff6a00"),
    ("footus", "Football US", "1f3c8", "#0c1a3a", "#7a3e1d"),
    ("hockey", "Hockey", "1f3d2", "#04263b", "#5fb8e8"),
    ("boxe", "Boxe", "1f94a", "#2a0008", "#c1121f"),
    ("mma", "MMA", "1f93c", "#120012", "#8e1b8e"),
    ("combat", "Sports de combat", "1f94b", "#1a1a1a", "#b8860b"),
    ("judo", "Judo", "1f94b", "#0a1f44", "#2f6fd6"),
    ("athletisme", "Athlétisme", "1f3c3", "#3a0d00", "#e4572e"),
    ("biathlon", "Biathlon", "26f7", "#06283d", "#47b5ff"),
    ("hiver", "Sports d'hiver", "1f3c2", "#0b2545", "#8ecae6"),
    ("flechettes", "Fléchettes", "1f3af", "#1c0b0b", "#d62828"),
    ("golf", "Golf", "26f3", "#0d2b12", "#40916c"),
    ("rallye", "Rallye", "1f699", "#2b1d0e", "#c77d22"),
    ("motocross", "Motocross", "1f3cd", "#2a1a00", "#f4a261"),
    ("auto", "Sport auto", "1f3ce", "#111111", "#6c757d"),
    ("billard", "Billard", "1f3b1", "#062b1a", "#1b7f4c"),
    ("volley", "Volley", "1f3d0", "#2b2100", "#f2c14e"),
    ("padel", "Padel", "1f3be", "#0b2b3a", "#00a6a6"),
    ("squash", "Squash", "1f3be", "#2a0a2a", "#c2185b"),
    ("pingpong", "Tennis de table", "1f3d3", "#2a0505", "#e63946"),
    ("baseball", "Baseball", "26be", "#0b1d3a", "#c8102e"),
    ("natation", "Natation", "1f3ca", "#012a4a", "#0096c7"),
    ("tir", "Tir", "1f3af", "#1d2d1d", "#6a994e"),
    ("magazine", "Émission", "1f4fa", "#14142b", "#5a4fcf"),
    ("sport", "Sport", "1f3c6", "#1f1600", "#d4a017"),
]


def hex_rgb(value):
    value = value.lstrip("#")
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4))


def render_svg(path, size):
    png = cairosvg.svg2png(url=str(path), output_width=size, output_height=size)
    return Image.open(io.BytesIO(png)).convert("RGBA")


def gradient(dark, bright):
    dark, bright = hex_rgb(dark), hex_rgb(bright)
    base = Image.new("RGB", (WIDTH, HEIGHT))
    pixels = base.load()
    for y in range(HEIGHT):
        for x in range(WIDTH):
            t = min(1.0, max(0.0, (x / WIDTH) * 0.65 + (1 - y / HEIGHT) * 0.35))
            pixels[x, y] = tuple(int(d + (b - d) * t) for d, b in zip(dark, bright))
    return base.convert("RGBA")


def poster(svg_dir, key, label, code, dark, bright):
    image = gradient(dark, bright)
    icon_path = Path(svg_dir) / f"{code}.svg"

    # Grand pictogramme en filigrane pour donner de la profondeur.
    watermark = render_svg(icon_path, 620).rotate(-18, resample=Image.BICUBIC, expand=True)
    alpha = watermark.getchannel("A").point(lambda a: int(a * 0.13))
    watermark.putalpha(alpha)
    image.alpha_composite(watermark, (WIDTH - watermark.width + 170, -90))

    # Halo lumineux derrière le pictogramme principal.
    glow = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse((WIDTH / 2 - 190, 50, WIDTH / 2 + 190, 430), fill=(255, 255, 255, 70))
    image.alpha_composite(glow.filter(ImageFilter.GaussianBlur(60)))

    icon = render_svg(icon_path, 290)
    left = (WIDTH - icon.width) // 2
    # L'ombre est floutée sur un calque pleine taille pour ne pas être coupée net.
    shadow = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
    silhouette = Image.new("RGBA", icon.size, (0, 0, 0, 0))
    silhouette.putalpha(icon.getchannel("A").point(lambda a: int(a * 0.45)))
    shadow.alpha_composite(silhouette, (left + 8, 72))
    image.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(12)))
    image.alpha_composite(icon, (left, 58))

    draw = ImageDraw.Draw(image)
    text = label.upper()
    size = 64
    font = ImageFont.truetype(FONT, size)
    while draw.textlength(text, font=font) > WIDTH - 120:
        size -= 2
        font = ImageFont.truetype(FONT, size)
    width = draw.textlength(text, font=font)
    position = ((WIDTH - width) / 2, 395)
    text_shadow = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
    ImageDraw.Draw(text_shadow).text((position[0] + 2, position[1] + 4), text, font=font, fill=(0, 0, 0, 150))
    image.alpha_composite(text_shadow.filter(ImageFilter.GaussianBlur(4)))
    draw.text(position, text, font=font, fill=(255, 255, 255, 255))
    return image.convert("RGB")


def main():
    svg_dir, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
    out_dir.mkdir(parents=True, exist_ok=True)
    for key, label, code, dark, bright in SPORTS:
        poster(svg_dir, key, label, code, dark, bright).save(out_dir / f"{key}.webp", quality=92, method=6)


if __name__ == "__main__":
    main()
