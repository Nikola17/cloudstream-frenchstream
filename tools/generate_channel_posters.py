"""Génère les posters HD 960x540 (16:9) des chaînes TV de l'addon FSTV (assets/channels/*.webp).

Privilégie les logos HD 512px de tv-logo/tv-logos (GitHub), complétés par des badges nets
pour les déclinaisons (beIN Sports MAX 4..10, L'Équipe Live 2/3, NBC Sports régions…) et
des cartes typographiques studio HD pour les quelques chaînes de niche absentes de tv-logos.

Chaque logo est centré dans la zone de sécurité (450x210 à y=225) sur un fond studio sombre
pour être net, centré et non rogné aussi bien sur les cartes 16:9 que sur la bannière Hero 1:1
de CloudStream.

Usage : python tools/generate_channel_posters.py <dossier_scratch> <dossier_sortie> [--missing-only]
"""
import html as html_lib
import io
import json
import re
import ssl
import sys
import unicodedata
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import quote, urlsplit, urlunsplit

from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageOps

WIDTH, HEIGHT = 960, 540
SAFE_W, SAFE_H = 450, 210
LOGO_CENTER_Y = 225

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
SSL_CTX = ssl.create_default_context()
TV_LOGOS_RAW = "https://raw.githubusercontent.com/tv-logo/tv-logos/main"

FONT_CANDIDATES = [
    r"C:\Windows\Fonts\segoeuib.ttf",
    r"C:\Windows\Fonts\arialbd.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/opentype/inter/Inter-ExtraBold.otf",
]

COUNTRY_PRIORITY = [
    "france", "belgium", "switzerland", "united-kingdom", "international", "world",
    "spain", "italy", "germany", "portugal", "netherlands", "united-states",
    "canada", "arab-world", "qatar", "united-arab-emirates", "brazil", "poland", "turkey",
]
COUNTRY_RANK = {c: i for i, c in enumerate(COUNTRY_PRIORITY)}

# Correspondances exactes (slug witv.channel -> chemin tv-logo/tv-logos)
EXACT_TV_LOGO_PATHS = {
    "canal": "countries/france/canal-plus-fr.png",
    "canal-foot": "countries/france/canal-plus-foot-fr.png",
    "canal-sport": "countries/france/canal-plus-sport-fr.png",
    "canal-sport-1": "countries/france/canal-plus-sport-fr.png",
    "canal-sport1": "countries/france/canal-plus-sport-fr.png",
    "canal-sport-360": "countries/france/canal-plus-sport-360-fr.png",
    "canal-cinema": "countries/france/canal-plus-cinema-fr.png",
    "canal-gr-ecran": "countries/france/canal-plus-grand-ecran-fr.png",
    "canal-docs": "countries/france/canal-plus-docs-fr.png",
    "canal-kids": "countries/france/canal-plus-kids-fr.png",
    "canal-series": "countries/france/canal-plus-series-fr.png",
    "canal-box-office": "countries/france/canal-plus-box-office-fr.png",
    "canal-comedie": "countries/france/comedie-plus-fr.png",
    "comedie-hd": "countries/france/comedie-plus-fr.png",
    "canal-sport-poland": "countries/poland/canal-plus-sport-pl.png",
    "rmc-fr": "countries/france/rmc-sport-1-fr.png",
    "rmc-sport-2-fr": "countries/france/rmc-sport-2-fr.png",
    "cine-frisson": "countries/france/cine-plus-frisson-fr.png",
    "cine-premier": "countries/france/cine-plus-premier-fr.png",
    "cine-emotion": "countries/france/cine-plus-emotion-fr.png",
    "cine-famiz": "countries/france/cine-plus-famiz-fr.png",
    "cine-club": "countries/france/cine-plus-club-fr.png",
    "cine-classic": "countries/france/cine-plus-classic-fr.png",
    "cine-festival": "countries/france/cine-plus-festival-fr.png",
    "cine-polar": "countries/france/polar-plus-fr.png",
    "ligue-1": "countries/france/ligue-1plus-fr.png",
    "ocs-city": "countries/france/ocs-max-fr.png",
    "tcm-cinema": "countries/france/tcm-cinema-fr.png",
    "cstar": "countries/france/c-star-fr.png",
    "tf1": "countries/france/tf1-fr.png",
    "m6": "countries/france/m6-fr.png",
    "arte": "countries/france/arte-fr.png",
    "6ter": "countries/france/6ter-fr.png",
    "ab1": "countries/france/ab1-fr.png",
    "animaux": "countries/france/animaux-fr.png",
    "france-info": "countries/france/franceinfo-fr.png",
    "chasse-peche": "countries/france/chasse-et-peche-fr.png",
    "automoto-la-chaine-fhd": "countries/france/automoto-la-chaine-fr.png",
    "automoto": "countries/france/automoto-la-chaine-fr.png",
    "nat-geo-fr": "countries/australia/national-geographic-au.png",
    "disney-junior": "countries/france/disney-jr-fr.png",
    "nat-geo-wild-fr": "countries/france/national-geographic-wild-fr.png",
    "nat-geo-wild-usa": "countries/united-states/nat-geo-wild-us.png",
    "syfy-hd": "countries/france/syfy-fr.png",
    "syfy-usa": "countries/united-states/syfy-us.png",
    "barca-tv-spain": "countries/spain/barca-tv-es.png",
    "real-madrid-tv-spain": "countries/spain/real-madrid-tv-es.png",
    "rtbf-la-une": "countries/belgium/la-une-be.png",
    "rtbf-tipik-belgique": "countries/belgium/tipik-be.png",
    "rtbf-tipik": "countries/belgium/tipik-be.png",
    "rtbf-la-trois": "countries/belgium/la-trois-be.png",
    "rts-1": "countries/switzerland/rts-un-ch.png",
    "rts-2-suisse": "countries/switzerland/rts-deux-ch.png",
    "rts-2": "countries/switzerland/rts-deux-ch.png",
    "lcp-assemblee-nationale": "countries/france/lcp-fr.png",
    "rds-sport-canada": "countries/canada/rds-ca.png",
    "bbc-america-bbca": "countries/united-states/bbc-america-us.png",
    "starz-kids-family": "countries/united-states/starz-kids-and-family-us.png",
    "discovery-science-tlc": "countries/france/tlc-fr.png",
    "tnt-sports-1-uk": "countries/united-kingdom/tnt-sports-1-uk.png",
    "tnt-sports-2-uk": "countries/united-kingdom/tnt-sports-2-uk.png",
    "tnt-sports-3-uk": "countries/united-kingdom/tnt-sports-3-uk.png",
    "tnt-sports-4-uk": "countries/united-kingdom/tnt-sports-4-uk.png",
    "euronews": "countries/international/euronews-int.png",
    "euronews-fr": "countries/international/euronews-int.png",
}

# Déclinaisons avec logo HD officiel + badge net en dessous
BADGED_HD_LOGOS = {
    "bein-sports-max-4-france": ("countries/international/beinsports/old/horizontal/bein-sports-max-hz-int.png", "MAX 4", (124, 58, 237)),
    "bein-sports-max-5-france": ("countries/international/beinsports/old/horizontal/bein-sports-max-hz-int.png", "MAX 5", (124, 58, 237)),
    "bein-sports-max-6-france": ("countries/international/beinsports/old/horizontal/bein-sports-max-hz-int.png", "MAX 6", (124, 58, 237)),
    "bein-sports-max-7-france": ("countries/international/beinsports/old/horizontal/bein-sports-max-hz-int.png", "MAX 7", (124, 58, 237)),
    "bein-sports-max-8-france": ("countries/international/beinsports/old/horizontal/bein-sports-max-hz-int.png", "MAX 8", (124, 58, 237)),
    "bein-sports-max-9-france": ("countries/international/beinsports/old/horizontal/bein-sports-max-hz-int.png", "MAX 9", (124, 58, 237)),
    "bein-sports-max-10-france": ("countries/international/beinsports/old/horizontal/bein-sports-max-hz-int.png", "MAX 10", (124, 58, 237)),
    "lequipe-2-live-tv": ("countries/france/lequipe-fr.png", "LIVE 2", (214, 30, 36)),
    "lequipe-3-live-tv": ("countries/france/lequipe-fr.png", "LIVE 3", (214, 30, 36)),
    "dazn-ligue-1-live-1-fhd": ("countries/belgium/dazn-1-fr-be.png", "LIGUE 1", (163, 230, 53)),
    "movistar-laliga": ("countries/spain/deportes-por-movistar-plus-es.png", "LALIGA", (0, 169, 224)),
    "nbc-sports-california": ("countries/united-states/nbc-sports-hz-us.png", "CALIFORNIA", (245, 158, 11)),
    "nbc-sports-philadelphia": ("countries/united-states/nbc-sports-hz-us.png", "PHILADELPHIA", (245, 158, 11)),
    "nbc-sports-boston": ("countries/united-states/nbc-sports-hz-us.png", "BOSTON", (245, 158, 11)),
    "nbc-sports-bay-area": ("countries/united-states/nbc-sports-hz-us.png", "BAY AREA", (245, 158, 11)),
}

# Chaînes de niche sans logo HD dans tv-logos : rendu typographique studio HD (ligne1, ligne2, couleur accent)
TYPO_POSTERS = {
    "after-foot-tv": ("AFTER FOOT", "RMC LIVE", (220, 38, 38)),
    "cherie-25": ("CHÉRIE 25", "TNT HD", (219, 39, 119)),
    "le-figaro": ("LE FIGARO", "TV", (37, 99, 235)),
    "okoo-tv": ("OKOO", "FRANCE·TV", (6, 182, 212)),
    "tv-monaco": ("TV MONACO", "DIRECT", (225, 29, 72)),
    "ligue-2": ("LIGUE 2+", "FOOTBALL", (16, 185, 129)),
    "motor-racing": ("MOTOR RACING", "LIVE", (234, 88, 12)),
    "naturetime-france": ("NATURETIME", "FRANCE", (22, 163, 74)),
    "voyage": ("VOYAGE", "DOCUMENTAIRE", (14, 165, 233)),
    "voyages": ("VOYAGES+", "DOCUMENTAIRE", (14, 165, 233)),
    "voyages-saveurs": ("VOYAGES", "& SAVEURS", (245, 158, 11)),
    "caillou": ("CAILLOU", "JEUNESSE", (234, 179, 8)),
}


def load_font(size: int) -> ImageFont.FreeTypeFont:
    for path in FONT_CANDIDATES:
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def encode_url(url: str) -> str:
    parts = urlsplit(url)
    path = quote(parts.path, safe="/%+-._~")
    return urlunsplit((parts.scheme, parts.netloc, path, parts.query, parts.fragment))


def fetch_bytes(url: str, referer: str | None = None, timeout: int = 12) -> bytes:
    headers = {"User-Agent": UA}
    if referer:
        headers["Referer"] = referer
    req = urllib.request.Request(encode_url(url), headers=headers)
    with urllib.request.urlopen(req, timeout=timeout, context=SSL_CTX) as r:
        return r.read()


def norm(s: str) -> str:
    s = html_lib.unescape(s)
    s = unicodedata.normalize("NFD", s)
    s = "".join(ch for ch in s if unicodedata.category(ch) != "Mn")
    s = s.lower().replace("+", " plus ").replace("&", " et ")
    s = re.sub(r"\bsports\b", "sport", s)
    s = re.sub(
        r"\b(hd|fhd|uhd|tv|live|fr|france|be|belgique|ch|suisse|uk|es|spain|espagne|it|de|pt|ar|arabe|arabic|usa|us|canada|poland|la chaine|chaine)\b",
        " ",
        s,
    )
    return re.sub(r"[^a-z0-9]", "", s)


def build_tv_logo_index(pngs: list[str]) -> dict[str, list[tuple]]:
    by_key: dict[str, list[tuple]] = {}
    for p in pngs:
        parts = p.split("/")
        if len(parts) < 3:
            continue
        country = parts[1]
        fname = parts[-1][:-4]
        base = re.sub(r"-(fr|uk|be|ch|us|es|it|de|pt|nl|ca|int|ar|me|qa|br|pl|tr|au|nz|hz)$", "", fname)
        rank = COUNTRY_RANK.get(country, 99)
        penalty = 0
        if any(x in fname for x in ["-black", "-dark", "-alt", "-old", "-mono", "-201"]):
            penalty += 5
        if "-white" in fname:
            penalty -= 1
        for variant in {norm(base), norm(base.replace("-and-", "-et-")), norm(base.replace("-et-", "-and-"))}:
            if variant:
                by_key.setdefault(variant, []).append((rank, penalty, len(fname), p))
    for k in by_key:
        by_key[k].sort()
    return by_key


def resolve_hd_logo_url(ch: dict, png_set: set[str], by_key: dict[str, list[tuple]]) -> list[str]:
    slug = ch["slug"]
    name = ch["name"]
    urls: list[str] = []

    if slug in EXACT_TV_LOGO_PATHS and EXACT_TV_LOGO_PATHS[slug] in png_set:
        urls.append(f"{TV_LOGOS_RAW}/{EXACT_TV_LOGO_PATHS[slug]}")

    nl = (name + " " + slug).lower()
    preferred = None
    if "uk" in nl or "sky sports" in nl or "tnt sports" in nl:
        preferred = "united-kingdom"
    elif "arab" in nl:
        preferred = "arab-world"
    elif "espagne" in nl or "spain" in nl or "movistar" in nl or "laliga" in nl:
        preferred = "spain"
    elif "belg" in nl or "rtbf" in nl or "rtl tvi" in nl or "club rtl" in nl or "plug rtl" in nl:
        preferred = "belgium"
    elif "suisse" in nl or "rts" in nl:
        preferred = "switzerland"
    elif "usa" in nl or "nbc sports" in nl:
        preferred = "united-states"

    for cand in [norm(name), norm(slug.replace("-", " "))]:
        if cand in by_key:
            picked = None
            if preferred:
                for item in by_key[cand]:
                    if item[3].startswith(f"countries/{preferred}/"):
                        picked = item[3]
                        break
            if not picked:
                picked = by_key[cand][0][3]
            u = f"{TV_LOGOS_RAW}/{picked}"
            if u not in urls:
                urls.append(u)
            break

    return urls


def analyze_logo(im: Image.Image) -> tuple[bool, float, float, tuple[int, int, int]]:
    rgba = im.convert("RGBA")
    w, h = rgba.size
    px = rgba.load()

    border_total = border_opaque = 0
    for x in range(w):
        for y in (0, h - 1):
            border_total += 1
            if px[x, y][3] > 220:
                border_opaque += 1
    for y in range(h):
        for x in (0, w - 1):
            border_total += 1
            if px[x, y][3] > 220:
                border_opaque += 1
    is_opaque_card = (border_opaque / max(1, border_total)) > 0.78

    small = rgba.resize((min(64, w), min(64, h)), Image.BILINEAR)
    lumas, sats = [], []
    accent_r = accent_g = accent_b = accent_w = 0.0
    pixels = small.get_flattened_data() if hasattr(small, "get_flattened_data") else list(small.getdata())
    for r, g, b, a in pixels:
        if a < 120:
            continue
        luma = 0.299 * r + 0.587 * g + 0.114 * b
        mx, mn = max(r, g, b), min(r, g, b)
        sat = mx - mn
        lumas.append(luma)
        sats.append(sat)
        if sat > 45 and 35 < luma < 230:
            weight = sat / 255.0
            accent_r += r * weight
            accent_g += g * weight
            accent_b += b * weight
            accent_w += weight

    mean_luma = sum(lumas) / len(lumas) if lumas else 128.0
    mean_sat = sum(sats) / len(sats) if sats else 0.0
    if accent_w > 1.5:
        accent = (int(accent_r / accent_w), int(accent_g / accent_w), int(accent_b / accent_w))
    else:
        accent = (56, 102, 180)
    return is_opaque_card, mean_luma, mean_sat, accent


def trim_logo(im: Image.Image) -> Image.Image:
    rgba = im.convert("RGBA")
    alpha = rgba.getchannel("A")
    bbox = alpha.point(lambda a: 255 if a > 12 else 0).getbbox()
    if bbox:
        rgba = rgba.crop(bbox)
    return rgba


def make_studio_bg(accent: tuple[int, int, int]) -> Image.Image:
    ar, ag, ab = accent
    c1 = (int(11 + ar * 0.06), int(15 + ag * 0.06), int(25 + ab * 0.07))
    c2 = (int(20 + ar * 0.14), int(27 + ag * 0.14), int(44 + ab * 0.15))

    grad = Image.new("RGB", (64, 36))
    gpx = grad.load()
    for y in range(36):
        for x in range(64):
            t = min(1.0, max(0.0, (x / 63.0) * 0.55 + (1.0 - y / 35.0) * 0.45))
            gpx[x, y] = (
                int(c1[0] + (c2[0] - c1[0]) * t),
                int(c1[1] + (c2[1] - c1[1]) * t),
                int(c1[2] + (c2[2] - c1[2]) * t),
            )
    base = grad.resize((WIDTH, HEIGHT), Image.BICUBIC).convert("RGBA")

    glow = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse(
        (WIDTH // 2 - 280, LOGO_CENTER_Y - 190, WIDTH // 2 + 280, LOGO_CENTER_Y + 190),
        fill=(min(255, ar + 70), min(255, ag + 70), min(255, ab + 70), 48),
    )
    base.alpha_composite(glow.filter(ImageFilter.GaussianBlur(75)))
    return base


def round_corners(im: Image.Image, radius: int) -> Image.Image:
    rgba = im.convert("RGBA")
    mask = Image.new("L", rgba.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, rgba.width - 1, rgba.height - 1), radius=radius, fill=255)
    out = Image.new("RGBA", rgba.size, (0, 0, 0, 0))
    out.paste(rgba, (0, 0), mask)
    return out


def invert_dark_mono_to_white(im: Image.Image) -> Image.Image:
    rgba = im.convert("RGBA")
    r, g, b, a = rgba.split()
    return Image.merge("RGBA", (ImageOps.invert(r), ImageOps.invert(g), ImageOps.invert(b), a))


def draw_pill_badge(canvas: Image.Image, text: str, center_y: int, accent: tuple[int, int, int]) -> None:
    font = load_font(30)
    probe = ImageDraw.Draw(canvas)
    bbox = probe.textbbox((0, 0), text, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    pad_x, pad_y = 26, 10
    bw, bh = tw + pad_x * 2, th + pad_y * 2
    x1, y1 = (WIDTH - bw) // 2, center_y - bh // 2
    x2, y2 = x1 + bw, y1 + bh

    badge = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
    bdraw = ImageDraw.Draw(badge)
    bdraw.rounded_rectangle((x1, y1, x2, y2), radius=bh // 2, fill=(accent[0], accent[1], accent[2], 235))
    canvas.alpha_composite(badge)

    # Texte noir si accent très lumineux (vert fluo/jaune), sinon blanc
    luma = 0.299 * accent[0] + 0.587 * accent[1] + 0.114 * accent[2]
    txt_color = (15, 23, 42, 255) if luma > 175 else (255, 255, 255, 255)
    draw = ImageDraw.Draw(canvas)
    draw.text((x1 + pad_x - bbox[0], y1 + pad_y - bbox[1]), text, font=font, fill=txt_color)


def render_poster(raw_bytes: bytes, badge: tuple[str, tuple[int, int, int]] | None = None) -> Image.Image:
    logo = trim_logo(Image.open(io.BytesIO(raw_bytes)))
    is_opaque_card, mean_luma, mean_sat, accent = analyze_logo(logo)
    if badge:
        accent = badge[1]

    safe_h = 155 if badge else SAFE_H
    center_y = 195 if badge else LOGO_CENTER_Y

    orig_w, orig_h = logo.size
    scale = min(SAFE_W / max(1, orig_w), safe_h / max(1, orig_h))
    if scale > 2.4:
        scale = 2.4
    new_w = max(1, int(round(orig_w * scale)))
    new_h = max(1, int(round(orig_h * scale)))
    logo = logo.resize((new_w, new_h), Image.LANCZOS)
    if scale > 1.25:
        logo = logo.filter(ImageFilter.UnsharpMask(radius=1.1, percent=115, threshold=2))

    canvas = make_studio_bg(accent)
    left = (WIDTH - new_w) // 2
    top = center_y - new_h // 2

    if is_opaque_card:
        logo = round_corners(logo, min(24, max(10, min(new_w, new_h) // 8)))
        shadow = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
        ImageDraw.Draw(shadow).rounded_rectangle(
            (left, top + 6, left + new_w, top + new_h + 6), radius=22, fill=(0, 0, 0, 145)
        )
        canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(18)))
        canvas.alpha_composite(logo, (left, top))
    elif mean_luma < 65 and mean_sat < 26:
        white_logo = invert_dark_mono_to_white(logo)
        shadow = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
        sil = Image.new("RGBA", white_logo.size, (0, 0, 0, 0))
        sil.putalpha(white_logo.getchannel("A").point(lambda a: int(a * 0.5)))
        shadow.alpha_composite(sil, (left, top + 6))
        canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(14)))
        canvas.alpha_composite(white_logo, (left, top))
    elif mean_luma < 80:
        pad_x, pad_y = 34, 24
        px1, py1 = left - pad_x, top - pad_y
        px2, py2 = left + new_w + pad_x, top + new_h + pad_y
        pill = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
        ImageDraw.Draw(pill).rounded_rectangle((px1, py1 + 6, px2, py2 + 6), radius=28, fill=(0, 0, 0, 110))
        canvas.alpha_composite(pill.filter(ImageFilter.GaussianBlur(16)))
        card = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
        ImageDraw.Draw(card).rounded_rectangle((px1, py1, px2, py2), radius=26, fill=(241, 245, 249, 238))
        canvas.alpha_composite(card)
        canvas.alpha_composite(logo, (left, top))
    else:
        shadow = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
        sil = Image.new("RGBA", logo.size, (0, 0, 0, 0))
        sil.putalpha(logo.getchannel("A").point(lambda a: int(a * 0.45)))
        shadow.alpha_composite(sil, (left, top + 6))
        canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(14)))
        canvas.alpha_composite(logo, (left, top))

    if badge:
        draw_pill_badge(canvas, badge[0], top + new_h + 44, badge[1])

    return canvas.convert("RGB")


def render_typo_poster(line1: str, line2: str, accent: tuple[int, int, int]) -> Image.Image:
    canvas = make_studio_bg(accent)
    draw = ImageDraw.Draw(canvas)

    size = 76
    font = load_font(size)
    while draw.textlength(line1, font=font) > SAFE_W and size > 34:
        size -= 2
        font = load_font(size)

    bbox = draw.textbbox((0, 0), line1, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    tx = (WIDTH - tw) // 2 - bbox[0]
    ty = 195 - th // 2 - bbox[1]

    shadow = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).text((tx, ty + 5), line1, font=font, fill=(0, 0, 0, 160))
    canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(8)))

    draw = ImageDraw.Draw(canvas)
    draw.text((tx, ty), line1, font=font, fill=(255, 255, 255, 255))
    draw_pill_badge(canvas, line2, 195 + th // 2 + 46, accent)
    return canvas.convert("RGB")


def main():
    scratch = Path(sys.argv[1])
    out_dir = Path(sys.argv[2])
    missing_only = "--missing-only" in sys.argv[3:]
    out_dir.mkdir(parents=True, exist_ok=True)

    channels = json.loads((scratch / "witv_channels_184.json").read_text(encoding="utf-8"))
    pngs = json.loads((scratch / "tv_logos_tree.json").read_text(encoding="utf-8"))
    png_set = set(pngs)
    by_key = build_tv_logo_index(pngs)

    if missing_only:
        channels = [
            ch for ch in channels
            if not (out_dir / f"{ch['slug']}.webp").exists() or ch["slug"] == "ligue-1"
        ]

    def process(ch: dict):
        slug = ch["slug"]
        if slug in BADGED_HD_LOGOS:
            rel_path, badge_text, accent = BADGED_HD_LOGOS[slug]
            url = f"{TV_LOGOS_RAW}/{rel_path}"
            try:
                raw = fetch_bytes(url, timeout=15)
                poster = render_poster(raw, badge=(badge_text, accent))
                poster.save(out_dir / f"{slug}.webp", format="WEBP", quality=90, method=5)
                return (slug, True, "badged-HD", url)
            except Exception as e:
                return (slug, False, "ERR", f"{url}: {e}")

        if slug in TYPO_POSTERS:
            l1, l2, accent = TYPO_POSTERS[slug]
            poster = render_typo_poster(l1, l2, accent)
            poster.save(out_dir / f"{slug}.webp", format="WEBP", quality=90, method=5)
            return (slug, True, "typo-HD", l1)

        candidates = resolve_hd_logo_url(ch, png_set, by_key)
        last_err = None
        for url in candidates:
            try:
                raw = fetch_bytes(url, timeout=15)
                poster = render_poster(raw)
                poster.save(out_dir / f"{slug}.webp", format="WEBP", quality=90, method=5)
                return (slug, True, "tv-logos-HD", url)
            except Exception as e:
                last_err = f"{url}: {e}"
        # Ultime secours typographique propre
        clean_name = html_lib.unescape(ch["name"]).upper()
        poster = render_typo_poster(clean_name, "TV EN DIRECT", (59, 130, 246))
        poster.save(out_dir / f"{slug}.webp", format="WEBP", quality=90, method=5)
        return (slug, True, "typo-fallback", last_err)

    with ThreadPoolExecutor(max_workers=8) as ex:
        results = list(ex.map(process, channels))

    ok = [r for r in results if r[1]]
    fail = [r for r in results if not r[1]]
    print(f"Processed {len(ok)}/{len(channels)} posters")
    if fail:
        print(f"FAILED ({len(fail)}):")
        for f in fail:
            print(" ", f)

    all_webp = sorted(out_dir.glob("*.webp"))
    total_kb = sum(p.stat().st_size for p in all_webp) / 1024
    print(f"Total files in {out_dir}: {len(all_webp)} ({total_kb:.1f} KB)")


if __name__ == "__main__":
    main()
