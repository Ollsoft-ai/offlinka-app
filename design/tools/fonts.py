#!/usr/bin/env python3
"""Convert the drive's Archivo woff2 subsets (design/brand/fonts/static) into TTFs the apps can bundle.

Needs fontTools + brotli (dev only, e.g. a scratch venv: pip install fonttools brotli). Not part of any app build.

    python3 design/tools/fonts.py            # writes design/fonts/*.ttf and checks every cs/sk glyph

The woff2 instances all carry the variable font's leftover name "Archivo-SemiBold", so iOS would register only
one of them. We give each file its own family/style/PostScript name.
"""
from pathlib import Path

from fontTools.ttLib import TTFont

D = Path(__file__).resolve().parent.parent
SRC = D / "brand" / "fonts" / "static"
OUT = D / "fonts"

# source, output, family (ID1), subfamily (ID2), typographic family (ID16), typographic subfamily (ID17), PostScript
FONTS = [
    ("archivo-400.woff2", "archivo_regular.ttf", "Archivo", "Regular", None, None, "Archivo-Regular"),
    ("archivo-600.woff2", "archivo_semibold.ttf", "Archivo SemiBold", "Regular", "Archivo", "SemiBold", "Archivo-SemiBold"),
    ("archivo-700.woff2", "archivo_bold.ttf", "Archivo", "Bold", None, None, "Archivo-Bold"),
    ("archivo-expanded-800.woff2", "archivo_expanded_extrabold.ttf", "Archivo Expanded ExtraBold", "Regular",
     "Archivo Expanded", "ExtraBold", "ArchivoExpanded-ExtraBold"),
]

# Every letter Czech and Slovak need, typographic quotes and the punctuation the UI strings use.
CS_SK = ("aábcčdďeéěfghiíjklĺľmnňoóôpqrŕřsštťuúůvwxyýzž"
         "AÁBCČDĎEÉĚFGHIÍJKLĹĽMNŇOÓÔPQRŔŘSŠTŤUÚŮVWXYÝZŽäÄ"
         "0123456789 .,:;!?()[]/%+-–…„“‚‘’”«»§°×€$&@#*'\"")


def set_name(t, nid, value):
    t["name"].removeNames(nameID=nid)
    if value is not None:
        t["name"].setName(value, nid, 3, 1, 0x409)
        t["name"].setName(value, nid, 1, 0, 0)


def main():
    OUT.mkdir(exist_ok=True)
    for src, out, fam, sub, tfam, tsub, ps in FONTS:
        t = TTFont(SRC / src)
        t.flavor = None
        full = f"{tfam or fam} {tsub or sub}".replace(" Regular", "") if (tsub or sub) != "Regular" else (tfam or fam)
        if sub == "Regular" and tsub is None:
            full = f"{fam} Regular"
        set_name(t, 1, fam)
        set_name(t, 2, sub)
        set_name(t, 3, f"2.001;OMNI;{ps}")
        set_name(t, 4, full)
        set_name(t, 6, ps)
        set_name(t, 16, tfam)
        set_name(t, 17, tsub)
        os2, head = t["OS/2"], t["head"]
        bold = sub == "Bold"
        # fsSelection: bit 5 BOLD, bit 6 REGULAR; keep USE_TYPO_METRICS (bit 7)
        os2.fsSelection = (os2.fsSelection & ~((1 << 0) | (1 << 5) | (1 << 6))) | ((1 << 5) if bold else (1 << 6))
        head.macStyle = 1 if bold else 0
        t.save(OUT / out)
        cmap = TTFont(OUT / out).getBestCmap()
        missing = [c for c in CS_SK if ord(c) not in cmap]
        assert not missing, f"{out}: missing glyphs {missing}"
        print(f"{out:34} {ps:28} {len(cmap)} chars, cs/sk complete, {(OUT / out).stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
