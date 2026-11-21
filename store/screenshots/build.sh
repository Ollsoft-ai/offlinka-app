#!/usr/bin/env bash
# Store screenshots for Google Play and the App Store from the app's current UI. Re-run after every UI change:
#   store/screenshots/build.sh                 everything (play, ios-69, ios-63, ios-65, ipad-13, graphics)
#   store/screenshots/build.sh play graphics   only these
#   store/screenshots/build.sh --fastlane      also copy the Play images into android/fastlane/metadata/android/*/images
# Sources: the Paparazzi goldens in android/ui/src/test/snapshots/images (re-record them first when the UI changed:
# :ui:recordPaparazziDebug) and real pages from the drive's ZIMs (offlinka-cli + /srv/offlinka/drive/release/current).
# iOS: SNAPSHOTS=<folder of simulator captures> SNAPSHOT_PAGES=app store/screenshots/build.sh ios-63 (the captures from
# the CI job "App Store screenshots" show real pages, so none is drawn over them).
# Output (git-ignored): out/play/<locale>/images/…, out/appstore/<locale>/…, out/index.html (contact sheet).
# Every Chromium step runs alone under atd-render with a 120 s timeout. Edit headlines and screens in frames.json.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
source /srv/offlinka/env.sh

FASTLANE=0
TARGETS=()
for a in "$@"; do
  case "$a" in
    --fastlane) FASTLANE=1 ;;
    play|ios-69|ios-63|ios-65|ipad-13|graphics) TARGETS+=("$a") ;;
    *) echo "unknown argument: $a (targets: play ios-69 ios-63 ios-65 ipad-13 graphics; --fastlane)" >&2; exit 2 ;;
  esac
done
[ ${#TARGETS[@]} -eq 0 ] && TARGETS=(play ios-69 ios-63 ios-65 ipad-13 graphics)

# offlinka-cli serves the ZIM pages; build it once (release) under the shared build lock if there is none.
if [ -z "${OFFLINKA_CLI:-}" ]; then
  OFFLINKA_CLI="$REPO/core/target/release/offlinka-cli"
  if [ ! -x "$OFFLINKA_CLI" ]; then
    echo "== building offlinka-cli"
    source ~/opt/offlinka-env.sh
    (cd "$REPO/core" && flock /tmp/claude-1000/offlinka-app-build.lock nice -n 10 timeout 3600 cargo build --release -j 4 --bin offlinka-cli)
  fi
fi
export OFFLINKA_CLI

mkdir -p "$HERE/build" "$HERE/out"
rm -f "$HERE/build/warnings.txt"
cd "$HERE"

# The pages drawn into the snapshots' WebView area; captures that already show real pages need none.
if [ "${SNAPSHOT_PAGES:-}" != app ]; then
  echo "== content (article, guide, map from the drive)"
  atd-render timeout 120 node render.cjs content
fi

for t in "${TARGETS[@]}"; do
  case "$t" in
    play) rm -rf out/play/*/images/phoneScreenshots ;;
    graphics) rm -f out/play/*/images/featureGraphic.png out/play/*/images/icon.png ;;
    *) rm -f out/appstore/*/*-"$t".png ;;
  esac
  echo "== $t"
  if [ "$t" = graphics ]; then atd-render timeout 120 node render.cjs graphics
  else atd-render timeout 120 node render.cjs frames "$t"; fi
done

# Check sizes and colour type of every PNG, write the contact sheet.
python3 - "$HERE" <<'EOF'
import json, os, struct, sys, html
here = sys.argv[1]; out = os.path.join(here, 'out')
cfg = json.load(open(os.path.join(here, 'frames.json')))
sizes = {(t['w'], t['h']): n for n, t in cfg['targets'].items()}
sizes[(1024, 500)] = 'feature graphic'; sizes[(512, 512)] = 'icon'
bad, rows = 0, []
for root, _, files in sorted(os.walk(out)):
    for f in sorted(files):
        if not f.endswith('.png'): continue
        p = os.path.join(root, f); d = open(p, 'rb').read(26)
        w, h = struct.unpack('>II', d[16:24]); kind = sizes.get((w, h))
        alpha = d[25] in (4, 6)
        if kind is None or (alpha and kind != 'icon'):
            bad += 1; print(f'  BAD {os.path.relpath(p, out)} {w}x{h} alpha={alpha}')
        rows.append((os.path.relpath(p, out), w, h, kind))
groups = {}
for r in rows: groups.setdefault(os.path.dirname(r[0]), []).append(r)
with open(os.path.join(out, 'index.html'), 'w') as fh:
    fh.write('<!doctype html><meta charset="utf-8"><title>Offlinka store images</title><style>body{font:14px system-ui;background:#F5F0E3;color:#17231C;margin:24px}'
             'h2{font-size:15px;margin:28px 0 8px}div{display:flex;gap:12px;flex-wrap:wrap;align-items:flex-start}figure{margin:0}img{height:360px;border:1px solid #CDBF9F}'
             'figcaption{font-size:12px;color:#5F5B4B}</style><h1>Offlinka store images</h1>')
    for g, items in sorted(groups.items()):
        fh.write(f'<h2>{html.escape(g)}</h2><div>')
        for p, w, h, k in items:
            fh.write(f'<figure><img src="{html.escape(p)}" loading="lazy"><figcaption>{html.escape(os.path.basename(p))} · {w}×{h}</figcaption></figure>')
        fh.write('</div>')
print(f'  {len(rows)} images, {bad} with a wrong size or alpha; contact sheet: {os.path.join(out, "index.html")}')
sys.exit(1 if bad else 0)
EOF

if [ "$FASTLANE" = 1 ]; then
  for d in out/play/*/; do
    loc=$(basename "$d")
    dst="$REPO/android/fastlane/metadata/android/$loc/images"
    mkdir -p "$dst"
    rm -rf "$dst/phoneScreenshots"
    cp -r "$d/images/." "$dst/"
  done
  echo "  Play images copied to android/fastlane/metadata/android/*/images (git-ignored)"
fi

if [ -s build/warnings.txt ]; then
  echo "== fallbacks used (add these Paparazzi states to get the real screen):"
  sed 's/^[a-z]* [a-z0-9-]*: //' build/warnings.txt | sort -u | sed 's/^/  /'
fi
