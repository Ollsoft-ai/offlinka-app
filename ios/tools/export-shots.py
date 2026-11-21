#!/usr/bin/env python3
"""Export the screenshots kept in an Xcode result bundle as <prefix><attachment name>.png (macOS, Xcode 16+).

    ios/tools/export-shots.py <Result.xcresult> <out folder> [--prefix cz.offlinka.ui_]

The UI tests name their attachments (`keepScreenshot("ScreensTest_home[cs-light]")`); xcresulttool exports
them as <uuid>.png plus manifest.json, whose suggestedHumanReadableName is "<name>_<n>_<UUID>.png".
With --prefix cz.offlinka.ui_ the files match the Paparazzi names that store/screenshots/build.sh expects.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

SUFFIX = re.compile(r"_\d+_[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")


def attachments(manifest):
    """(exported file, readable name) of every attachment, whatever the nesting of manifest.json."""
    if isinstance(manifest, dict):
        if "exportedFileName" in manifest:
            yield manifest["exportedFileName"], manifest.get("suggestedHumanReadableName") or manifest["exportedFileName"]
        for value in manifest.values():
            yield from attachments(value)
    elif isinstance(manifest, list):
        for item in manifest:
            yield from attachments(item)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("result")
    parser.add_argument("out")
    parser.add_argument("--prefix", default="")
    args = parser.parse_args()

    os.makedirs(args.out, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(["xcrun", "xcresulttool", "export", "attachments", "--path", args.result, "--output-path", tmp],
                       check=True)
        manifest_path = os.path.join(tmp, "manifest.json")
        if not os.path.exists(manifest_path):
            print("no manifest.json: no attachments in", args.result)
            return 0
        with open(manifest_path) as fh:
            manifest = json.load(fh)
        count = 0
        for exported, readable in attachments(manifest):
            stem, ext = os.path.splitext(readable)
            if ext.lower() not in (".png", ".jpg", ".jpeg", ".heic"):
                continue
            name = SUFFIX.sub("", stem)
            target = os.path.join(args.out, f"{args.prefix}{name}{ext.lower()}")
            shutil.copyfile(os.path.join(tmp, exported), target)
            count += 1
            print(" ", os.path.basename(target))
        print(f"{count} screenshots -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
