#!/usr/bin/env python3
"""Small helpers for scripts/ci/emulator-smoke.sh: read `uiautomator dump` XML and logcat.

  uidump.py has <dump.xml> <regex>        exit 0 when a node's text or content-desc contains the regex
  uidump.py center <dump.xml> <regex>     print "x y" of the first node whose text/content-desc fully matches
  uidump.py texts <dump.xml>              print every text / content-desc on screen (for the log)
  uidump.py packages <dump.xml>           print the packages on screen
  uidump.py focus <dump.xml>              print "item|other|none" (focused node is a DocumentsUI list item
                                          or not) and the texts under the focused node
  uidump.py crashes <logcat.txt> <pkg>    print crash / ANR blocks of <pkg> (prefix match), exit 1 when found
  uidump.py size <file.png>               print "width height" of a PNG

Regexes are Python regexes, case-insensitive.
"""
import re
import struct
import sys
import xml.etree.ElementTree as ET


def nodes(path):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        return []
    return list(root.iter("node"))


def labels(n):
    return [s.strip() for s in (n.get("text") or "", n.get("content-desc") or "") if s and s.strip()]


def center(n):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    if x2 <= x1 or y2 <= y1:
        return None
    return (x1 + x2) // 2, (y1 + y2) // 2


def crashes(path, pkg):
    lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
    p = re.escape(pkg)
    hits = []
    for i, line in enumerate(lines):
        block = None
        if "FATAL EXCEPTION" in line and any(re.search(r"Process: " + p, l) for l in lines[i + 1 : i + 4]):
            block = lines[i : i + 40]
        elif re.search(r"ANR in " + p, line):
            block = lines[i : i + 15]
        elif re.search(r"Fatal signal .*\(" + p, line) or re.search(r">>> " + p, line):
            block = lines[max(0, i - 2) : i + 30]
        if block:
            hits.append("\n".join(block))
    for h in hits:
        print(h)
        print("-" * 60)
    return 1 if hits else 0


def main():
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "has":
        rx = re.compile(args[1], re.I)
        sys.exit(0 if any(rx.search(s) for n in nodes(args[0]) for s in labels(n)) else 1)
    if cmd == "center":
        rx = re.compile(args[1], re.I)
        for n in nodes(args[0]):
            if any(rx.fullmatch(s) for s in labels(n)):
                c = center(n)
                if c:
                    print(*c)
                    return
        sys.exit(1)
    if cmd == "texts":
        for n in nodes(args[0]):
            for s in labels(n):
                print(s.replace("\n", " ")[:120])
        return
    if cmd == "packages":
        print(" ".join(sorted({n.get("package") or "" for n in nodes(args[0])} - {""})))
        return
    if cmd == "focus":
        try:
            root = ET.parse(args[0]).getroot()
        except ET.ParseError:
            print("none")
            return
        for n in root.iter("node"):
            if n.get("focused") == "true":
                where = "item" if (n.get("resource-id") or "").endswith(":id/item_root") else "other"
                print(where, "|".join(s for d in n.iter("node") for s in labels(d)))
                return
        print("none")
        return
    if cmd == "crashes":
        sys.exit(crashes(args[0], args[1]))
    if cmd == "size":
        with open(args[0], "rb") as f:
            head = f.read(24)
        w, h = struct.unpack(">II", head[16:24])
        print(w, h)
        return
    sys.exit(f"unknown command {cmd}")


if __name__ == "__main__":
    main()
