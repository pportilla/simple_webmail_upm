#!/usr/bin/env python3
"""Render Play artwork from the existing launcher vector (requires cairosvg)."""

from pathlib import Path
from html import escape
import xml.etree.ElementTree as ET

import cairosvg


ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
SOURCE = ROOT / "app/src/main/res/drawable/ic_launcher_foreground.xml"
ARTWORK = ROOT / "docs/store-artwork"
METADATA = ROOT / "fastlane/metadata/android"


def envelope() -> str:
    paths = []
    for path in ET.parse(SOURCE).getroot().findall("path"):
        color = path.attrib[ANDROID + "fillColor"]
        if len(color) == 9:
            if color[1:3] != "FF":
                raise ValueError("Update the renderer to handle non-opaque Android colors.")
            color = "#" + color[3:]
        paths.append(f'<path fill="{color}" d="{escape(path.attrib[ANDROID + "pathData"])}"/>')
    return "".join(paths)


def render(svg: str, source: Path, output: Path) -> None:
    source.parent.mkdir(parents=True, exist_ok=True)
    output.parent.mkdir(parents=True, exist_ok=True)
    source.write_text(svg, encoding="utf-8")
    cairosvg.svg2png(bytestring=svg.encode(), write_to=str(output))
    print(output.relative_to(ROOT))


def main() -> None:
    glyph = envelope()
    icon = f'''<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 108 108">
<rect width="108" height="108" fill="#003f87"/>{glyph}</svg>'''
    translations = {
        "en-US": ("Your UPM mail, with you.", "An unofficial Android email client"),
        "es-ES": ("Tu correo UPM, contigo.", "Cliente de correo Android no oficial"),
    }
    for locale, (headline, subtitle) in translations.items():
        images = METADATA / locale / "images"
        render(icon, ARTWORK / f"{locale}-icon.svg", images / "icon.png")
        graphic = f'''<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="500" viewBox="0 0 1024 500">
<defs><linearGradient id="background" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#00244d"/><stop offset="1" stop-color="#004b9c"/></linearGradient></defs>
<rect width="1024" height="500" fill="url(#background)"/>
<circle cx="900" cy="50" r="320" fill="#ffffff" opacity="0.035"/>
<circle cx="890" cy="460" r="230" fill="#ffffff" opacity="0.035"/>
<rect x="68" y="70" width="178" height="178" rx="35" fill="#003f87" stroke="#ffffff" stroke-opacity="0.16"/>
<g transform="translate(68 70) scale(1.648148)">{glyph}</g>
<g font-family="DejaVu Sans, sans-serif" fill="#ffffff">
<text x="282" y="158" font-size="58" font-weight="700">UPM Mail</text>
<text x="286" y="206" font-size="23" fill="#d3e4ff">{escape(subtitle)}</text>
<text x="74" y="337" font-size="45" font-weight="700">{escape(headline)}</text>
<path d="M76 370h66" stroke="#f6b000" stroke-width="5" stroke-linecap="round"/>
<text x="76" y="422" font-size="20" fill="#d3e4ff">zango</text>
</g></svg>'''
        render(graphic, ARTWORK / f"{locale}-feature-graphic.svg", images / "featureGraphic.png")


if __name__ == "__main__":
    main()
