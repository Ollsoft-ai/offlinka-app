#!/usr/bin/env python3
"""Write the full licence texts of everything shipped in the Android app into
android/app/src/main/assets/reader/licences.html (About → Open-source libraries → Full licence texts).

MIT, BSD and Zlib require the copyright notice and the licence text to ship with the binary; the
names alone (ui/Notices.kt) are not enough. Rust crates come from the core's dependency tree for
Android (`cargo tree`, run under the shared build lock) and their licence files in the cargo
registry; libzstd's BSD licence from the copy vendored in zstd-sys; the Android side (AndroidX,
Kotlin, JNA) is Apache-2.0; the Archivo font is SIL OFL 1.1 (design/fonts/OFL.txt).

  scripts/gen-licences.py            regenerate after a dependency change, then commit the HTML
"""
import glob
import hashlib
import html
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "android/app/src/main/assets/reader/licences.html")
REGISTRY = os.path.expanduser("~/.cargo/registry/src")
LICENCE_FILES = ("LICENSE", "LICENSE-MIT", "LICENSE-MIT.md", "LICENSE.md", "LICENSE.txt", "LICENSE-APACHE",
                 "LICENSE-APACHE.md", "LICENSE-ZLIB", "LICENSE-ZLIB.md", "LICENSE-UNICODE", "COPYRIGHT", "COPYING",
                 "UNLICENSE", "LICENSE-BSD", "LICENSE.BSD-2-Clause", "LICENSE.BSD-3-Clause")
MPL = "/usr/share/common-licenses/MPL-2.0"
# Build-time only (compile libzstd, never shipped).
BUILD_ONLY = {"cc", "jobserver", "shlex", "find-msvc-tools", "pkg-config"}


def cargo_tree():
    cmd = ("source ../scripts/lib-lock.sh && run cargo tree -p offlinka-core -e normal,no-proc-macro "
           "--target aarch64-linux-android --prefix none -f '{p}|{l}'")
    out = subprocess.run(["bash", "-c", cmd], cwd=os.path.join(ROOT, "core"), check=True,
                         capture_output=True, text=True).stdout
    crates = {}
    for line in out.splitlines():
        if "|" not in line or line.endswith("(*)"):
            line = line.replace(" (*)", "")
        if "|" not in line:
            continue
        spec, lic = line.split("|", 1)
        parts = spec.split()
        if len(parts) < 2:
            continue
        name, version = parts[0], parts[1].lstrip("v")
        if name == "offlinka-core" or name in BUILD_ONLY:
            continue
        crates[(name, version)] = lic.strip()
    return sorted(crates.items())


def crate_dir(name, version):
    hits = glob.glob(os.path.join(REGISTRY, "*", f"{name}-{version}"))
    return hits[0] if hits else None


def read(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read().strip()


def licence_texts(d):
    texts = []
    for fname in LICENCE_FILES:
        p = os.path.join(d, fname)
        if os.path.isfile(p):
            texts.append((fname, read(p)))
    # REUSE layout (crc-catalog): LICENSES/<SPDX id>.txt
    for p in sorted(glob.glob(os.path.join(d, "LICENSES", "*"))):
        if os.path.isfile(p):
            texts.append(("LICENSES/" + os.path.basename(p), read(p)))
    return texts


def main():
    components = []  # (title, licence id, [(file, text)])
    for (name, version), lic in cargo_tree():
        d = crate_dir(name, version)
        if not d:
            sys.exit(f"{name} {version}: sources not in the cargo registry (build the core first)")
        texts = licence_texts(d)
        if name == "zstd-sys":
            vendored = os.path.join(d, "zstd", "LICENSE")
            if os.path.isfile(vendored):
                components.append(("libzstd 1.5.7 (Meta), vendored in zstd-sys", "BSD-3-Clause",
                                   [("zstd/LICENSE", read(vendored))]))
        if not texts and lic == "MPL-2.0" and os.path.isfile(MPL):
            # UniFFI ships no licence file in its crates; MPL-2.0 also asks to say where the source is.
            texts = [("Mozilla Public License 2.0", "Source code: https://github.com/mozilla/uniffi-rs\n\n" + read(MPL))]
        if not texts:
            sys.exit(f"{name} {version}: no licence file found")
        components.append((f"{name} {version}", lic, texts))
    apache = next((t for c in components for f, t in c[2] if "Apache License" in t and "Version 2.0" in t and len(t) > 9000), None)
    if apache:
        components.append(("Jetpack Compose, AndroidX, Kotlin, kotlinx.coroutines, JNA (used under Apache-2.0)",
                           "Apache-2.0", [("Apache License 2.0", apache)]))
    components.append(("Rust standard library", "MIT OR Apache-2.0",
                       [("note", "Copyright the Rust Project Developers. Licensed under the MIT licence or the "
                                 "Apache License 2.0, at your option (texts above).")]))
    ofl = os.path.join(ROOT, "design/fonts/OFL.txt")
    components.append(("Archivo font (Omnibus-Type)", "SIL Open Font License 1.1", [("OFL.txt", read(ofl))]))

    seen = {}
    parts = []
    for title, lic, texts in components:
        blocks = []
        for fname, text in texts:
            h = hashlib.sha1(text.encode()).hexdigest()
            if h in seen:
                blocks.append(f'<p class="same">{html.escape(fname)}: the same text as for {html.escape(seen[h])}.</p>')
            else:
                seen[h] = title
                blocks.append(f"<details><summary>{html.escape(fname)}</summary><pre>{html.escape(text)}</pre></details>")
        parts.append(f"<section><h2>{html.escape(title)}</h2><p class=\"lic\">{html.escape(lic)}</p>{''.join(blocks)}</section>")

    page = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Open-source licences</title>
<style>
body{{font:15px/1.45 system-ui,sans-serif;margin:16px;color:#17231C;background:#F5F0E3}}
h1{{font-size:22px}} h2{{font-size:16px;margin:22px 0 2px}} .lic{{margin:0 0 6px;color:#4A5A50}}
pre{{white-space:pre-wrap;font-size:12px;background:#FFFDF7;border:1px solid #D8D0BC;padding:10px;border-radius:6px}}
summary{{cursor:pointer;padding:6px 0}} .same{{color:#4A5A50;font-size:13px}}
@media (prefers-color-scheme: dark){{body{{color:#E8E4D8;background:#14291F}} pre{{background:#1C3328;border-color:#5E8270}} .lic,.same{{color:#B8C4BC}}}}
</style></head><body>
<h1>Open-source licences</h1>
<p>Offlinka is built with these components. Each is used under the licence shown; tap a file name for its full text.</p>
{''.join(parts)}
</body></html>
"""
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(page)
    print(f"{OUT}: {len(components)} components, {len(seen)} distinct texts, {len(page) // 1024} KB")


if __name__ == "__main__":
    main()
