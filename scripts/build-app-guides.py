#!/usr/bin/env python3
"""Build the app edition of the guides book (offlinka_cs_prirucky.zim) bundled inside both apps.

The drive's guides ZIM also explains the drive and the Kiwix apps ("Jak to otevřít v mobilu … aplikace
Kiwix"); a store reviewer without the drive opens exactly those pages. The app edition holds only what
makes sense inside the app: an own index page, První pomoc, Výpadek proudu and the licence page, in the
same look (drive-prep's doc.css and brand fonts, inlined like the drive build does). No Kiwix anywhere,
no links to drive-only pages, no PDFs.

  scripts/build-app-guides.py                   build, write the copy both apps bundle, check it
  scripts/build-app-guides.py --out FILE        build only FILE (no app copies), check it
  scripts/build-app-guides.py --check ZIM...    only check (the test: no "kiwix" in any entry, no dead links)

Sources (read only): /srv/offlinka/drive-prep (src/docs, src/brand, src/icons, src/licence/texts,
drive.conf, tools/brand.py, tools/edition.py); override with OFFLINKA_DRIVE_PREP.
Needs python-libzim: run with the drive-prep venv, or the script re-runs itself with the first venv found
(OFFLINKA_ZIM_PYTHON, /srv/offlinka/drive-prep/.work/venv, ~/atd-drive/.work/venv).
"""
import argparse
import datetime
import html
import os
import re
import shlex
import shutil
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PREP = os.environ.get("OFFLINKA_DRIVE_PREP", "/srv/offlinka/drive-prep")
# Every copy of the bundled guides: Android assets and the iOS app bundle (the same file).
# One file for both apps: ios/project.yml bundles ../android/app/src/main/assets/offlinka_cs_prirucky.zim.
TARGETS = ["android/app/src/main/assets/offlinka_cs_prirucky.zim"]
PAGES = ["index.html", "prvni-pomoc.html", "vypadek-proudu.html", "licence.html"]


def ensure_libzim():
    try:
        import libzim  # noqa: F401
        return
    except ImportError:
        pass
    if not os.environ.get("OFFLINKA_ZIM_REEXEC"):
        for py in [os.environ.get("OFFLINKA_ZIM_PYTHON"), os.path.join(PREP, ".work/venv/bin/python"),
                   os.path.expanduser("~/atd-drive/.work/venv/bin/python")]:
            if py and os.path.isfile(py):
                os.environ["OFFLINKA_ZIM_REEXEC"] = "1"     # a venv python is often a symlink to this same binary
                os.execv(py, [py, "-I", os.path.abspath(__file__)] + sys.argv[1:])
    sys.exit("python-libzim not found: run with the drive-prep venv python (see the docstring)")


def drive_conf():
    c = {}
    for line in open(os.path.join(PREP, "drive.conf"), encoding="utf-8"):
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            vals = shlex.split(v, comments=True)
            c[k.strip()] = vals[0] if vals else ""
    return c


def must_replace(text, old, new, what):
    if old not in text:
        sys.exit(f"source changed, cannot apply '{what}': {old[:60]!r} not found")
    return text.replace(old, new)


def render():
    """The four pages, rendered like drive-prep's render_docs.py (CSS and fonts inlined, brand substituted)."""
    sys.dont_write_bytecode = True  # drive-prep stays untouched: no __pycache__ next to its tools
    sys.path.insert(0, os.path.join(PREP, "tools"))
    import brand    # embedded Archivo fonts + logo, read only
    import edition
    c = drive_conf()
    src = os.path.join(PREP, "src/docs")
    css = brand.fontface_css_static() + open(os.path.join(src, "doc.css"), encoding="utf-8").read()
    logo = f'<div class="brand brandlogo"><img src="{brand.svg_uri("logo-ink.svg")}" alt="{html.escape(c["BRAND"])}"></div>'
    ed = edition.edition_name(c, "cs")
    pages = {}

    p = open(os.path.join(src, "prvni-pomoc.html"), encoding="utf-8").read()
    # The WikiSkripta are on the drive, not in the app.
    p = must_replace(p, " Podrobnosti najdete ve WikiSkriptech na této flashce.", "", "first aid: drive-only pointer")
    pages["prvni-pomoc.html"] = p

    v = open(os.path.join(src, "vypadek-proudu.html"), encoding="utf-8").read()
    v = must_replace(v, " a vytištěné karty první pomoci z této flashky.", " a vytištěné karty první pomoci.", "power cut: drive cards")
    v = must_replace(v, "    <li><b>Nabitý notebook</b> – na baterii otevře celou tuto knihovnu.</li>\n", "", "power cut: drive library")
    pages["vypadek-proudu.html"] = v

    pages["index.html"] = f"""<!doctype html>
<html lang="cs">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Nouzové příručky</title>
<link rel="stylesheet" href="doc.css">
</head>
<body>
<main>
<div class="brand">{{{{BRAND}}}}</div>
<h1>Nouzové příručky</h1>
<p class="sub">Krátké české návody, které fungují bez internetu.</p>
<div class="call">
  <div><b>155</b>záchranná služba</div>
  <div><b>112</b>tísňové volání</div>
  <div><b>150</b>hasiči</div>
  <div><b>158</b>policie</div>
</div>
<nav class="toc">
  <a href="prvni-pomoc.html">První pomoc – rychlé karty<span>Resuscitace, krvácení, dušení, popáleniny, mrtvice…</span></a>
  <a href="vypadek-proudu.html">Výpadek proudu – co připravit<span>Co mít doma, co dělat, na co si dát pozor</span></a>
  <a href="licence.html">Licence a autoři<span>Texty a písmo</span></a>
</nav>
<footer>{{{{BRAND}}}} · edice {{{{EDITION_CS}}}} · texty Ollcee s.r.o., CC BY-SA 4.0</footer>
</main>
</body>
</html>
"""
    ofl = html.escape(open(os.path.join(PREP, "src/licence/texts/OFL-1.1.txt"), encoding="utf-8").read())
    pages["licence.html"] = f"""<!doctype html>
<html lang="cs">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Licence a autoři</title>
<link rel="stylesheet" href="doc.css">
</head>
<body>
<main>
<div class="brand">{{{{BRAND}}}} · Nouzové příručky</div>
<h1>Licence a autoři</h1>
<ul>
<li><b>Texty Nouzových příruček</b> (První pomoc – rychlé karty, Výpadek proudu – co připravit): © Ollcee s.r.o., licence <a href="https://creativecommons.org/licenses/by-sa/4.0/deed.cs">CC BY-SA 4.0</a>. Smíte je kopírovat, tisknout a upravovat, když uvedete autora a licenci.</li>
<li><b>První pomoc</b> je sestavena podle obecně dostupných zásad laické první pomoci (Evropská rada pro resuscitaci). Nenahrazuje kurz první pomoci ani radu lékaře.</li>
<li><b>Písmo Archivo</b>: © The Archivo Project Authors, licence SIL Open Font License 1.1 (celé znění níže).</li>
</ul>
<h2>SIL Open Font License 1.1</h2>
<pre style="white-space:pre-wrap;font-size:13px">{ofl}</pre>
<footer>{{{{BRAND}}}} · Nouzové příručky · edice {{{{EDITION_CS}}}}</footer>
</main>
</body>
</html>
"""
    subs = {"{{BRAND}}": c["BRAND"], "{{EDITION_CS}}": ed}
    out = {}
    for name, txt in pages.items():
        txt = txt.replace('<link rel="stylesheet" href="doc.css">', "<style>\n" + css + "</style>")
        for k, val in subs.items():
            txt = txt.replace(k, val)
        txt = txt.replace(f'<div class="brand">{c["BRAND"]}</div>', logo)
        left = re.findall(r"\{\{[A-Z_]+\}\}", txt)
        if left:
            sys.exit(f"{name}: unreplaced {left}")
        out[name] = txt
    return out


def title_of(page_html):
    m = re.search(r"<title>(.*?)</title>", page_html, re.S)
    return html.unescape(m.group(1).strip()) if m else ""


def build(out_path):
    from libzim.writer import Creator, Hint, Item, StringProvider

    class Entry(Item):
        def __init__(self, path, title, mime, data, front):
            super().__init__()
            self.p, self.t, self.m, self.d, self.f = path, title, mime, data, front

        def get_path(self):
            return self.p

        def get_title(self):
            return self.t

        def get_mimetype(self):
            return self.m

        def get_contentprovider(self):
            return StringProvider(self.d)

        def get_hints(self):
            return {Hint.FRONT_ARTICLE: self.f, Hint.COMPRESS: self.m.startswith("text/")}

    pages = render()
    icon = open(os.path.join(PREP, "src/icons/prirucky.png"), "rb").read()
    tmp = out_path + ".part"
    if os.path.exists(tmp):
        os.remove(tmp)
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    # No full-text index: the apps search titles with their own index (built on the phone).
    with Creator(tmp).config_indexing(False, "ces") as cr:
        cr.set_mainpath("index.html")
        meta = {
            "Name": "offlinka_cs_prirucky",          # unchanged: the book id the apps use
            "Title": "Nouzové příručky",
            "Language": "ces",
            "Creator": "Ollcee s.r.o.",
            "Publisher": "Ollcee s.r.o.",
            "Date": datetime.date.today().isoformat(),
            "Description": "První pomoc a výpadek proudu (česky)",
            "LongDescription": "Vydání pro aplikaci Offlinka: krátké české návody, první pomoc a výpadek proudu. Text CC BY-SA 4.0.",
            "Flavour": "app",
            "Tags": "_category:other;offlinka;_app",
            "Scraper": "offlinka-app-guides",
        }
        for k, v in meta.items():
            cr.add_metadata(k, v)
        cr.add_illustration(icon, 48)
        for name in PAGES:
            cr.add_item(Entry(name, title_of(pages[name]), "text/html", pages[name].encode("utf-8"), True))
        cr.add_item(Entry("favicon.png", "", "image/png", icon, False))
    os.replace(tmp, out_path)
    return out_path


TEXTY = ("text/", "application/javascript", "application/json", "image/svg+xml")
LINK = re.compile(r"""(?:href|src)\s*=\s*["']([^"']+)["']""", re.I)


def check(zim_path):
    """The test: no "kiwix" (any case) in any path, title, text content or metadata; no dead relative links."""
    from libzim.reader import Archive
    a = Archive(zim_path)
    problems = []
    for key in a.metadata_keys:
        if key.startswith("Illustration"):
            continue
        val = bytes(a.get_metadata(key)).decode("utf-8", "replace")
        if "kiwix" in val.lower():
            problems.append(f"metadata {key}: mentions Kiwix")
    entries = [a.get_entry_by_id(i) for i in range(a.entry_count)]
    paths = {e.path for e in entries}
    for e in entries:
        for what, s in (("path", e.path), ("title", e.title)):
            if "kiwix" in s.lower():
                problems.append(f"{e.path}: {what} mentions Kiwix")
        if e.is_redirect:
            continue
        it = e.get_item()
        mime = it.mimetype
        if not mime.startswith(TEXTY):
            continue
        text = bytes(it.content).decode("utf-8", "replace")
        if "kiwix" in text.lower():
            problems.append(f"{e.path}: content mentions Kiwix")
        if "html" in mime:
            base = os.path.dirname(e.path)
            for link in LINK.findall(text):
                if re.match(r"^[a-z][a-z0-9+.-]*:", link, re.I) or link.startswith(("#", "//")):
                    continue        # external, data:, mailto:, tel:, same-page anchors
                target = os.path.normpath(os.path.join(base, link.split("#")[0].split("?")[0]))
                if target not in paths:
                    problems.append(f"{e.path}: dead link {link}")
    for p in problems:
        print("FAIL", zim_path, p)
    if problems:
        return False
    print(f"OK {zim_path}: {a.entry_count} entries ({', '.join(sorted(paths))}), main {a.main_entry.get_item().path}, "
          f"no Kiwix, no dead links")
    return True


def main():
    ensure_libzim()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", nargs="+", metavar="ZIM", help="only check these ZIMs")
    ap.add_argument("--out", help="build only this file (default: both app copies)")
    args = ap.parse_args()
    if args.check:
        sys.exit(0 if all([check(z) for z in args.check]) else 1)
    if args.out:
        built = build(args.out)
        sys.exit(0 if check(built) else 1)
    first = os.path.join(REPO, TARGETS[0])
    build(first)
    if not check(first):
        sys.exit(1)
    for t in TARGETS[1:]:
        dest = os.path.join(REPO, t)
        if os.path.isdir(os.path.dirname(dest)):
            shutil.copyfile(first, dest)
            print(f"copied -> {t}")
        else:
            print(f"skipped {t}: {os.path.dirname(t)} does not exist in this checkout")
    tracked = subprocess.run(["git", "-C", REPO, "ls-files"], capture_output=True, text=True).stdout.split()
    for t in tracked:
        if t.endswith("offlinka_cs_prirucky.zim") and t not in TARGETS:
            print(f"WARNING: another tracked copy {t} was not updated")


if __name__ == "__main__":
    main()
