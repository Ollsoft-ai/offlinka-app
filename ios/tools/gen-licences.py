#!/usr/bin/env python3
"""Write the full licence texts of everything shipped in the iOS app into
ios/Offlinka/Resources/licences.html (About → Open-source libraries → Full licence texts).

The iOS twin of scripts/gen-licences.py (Android): the same page from the same helpers, with the core's
Rust crates for the iOS target and without the Android libraries (the Swift side uses only Apple's
frameworks). Runs `cargo tree` under the shared build lock; needs the crates in the cargo registry.

  ios/tools/gen-licences.py          regenerate after a dependency change, then commit the HTML
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ANDROID = os.path.join(ROOT, "scripts/gen-licences.py")
OUT = os.path.join(ROOT, "ios/Offlinka/Resources/licences.html")


class Components(list):
    """The Android script's component list, minus its Android-only section."""

    def append(self, item):
        if "AndroidX" not in item[0]:
            super().append(item)


def main():
    source = open(ANDROID, encoding="utf-8").read()
    for needle in ("components = []", "--target aarch64-linux-android"):
        if needle not in source:
            sys.exit(f"scripts/gen-licences.py changed ({needle!r} not found): update ios/tools/gen-licences.py")
    source = source.replace("components = []", "components = _Components()")
    source = source.replace("--target aarch64-linux-android", "--target aarch64-apple-ios")
    namespace = {"__name__": "ios_licences", "__file__": ANDROID, "_Components": Components}
    exec(compile(source, ANDROID, "exec"), namespace)
    namespace["OUT"] = OUT
    namespace["main"]()


if __name__ == "__main__":
    main()
