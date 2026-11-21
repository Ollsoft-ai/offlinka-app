#!/usr/bin/env bash
# Smoke test of the debug APK on a running emulator, driven from outside with adb and uiautomator dumps
# (no app code, no instrumentation). Every step leaves a screenshot and a UI dump; after every step logcat
# is checked for a crash (Java FATAL EXCEPTION, native fatal signal) or an ANR of the app.
#
#   scripts/ci/emulator-smoke.sh <apk-or-dir> <out-dir>
#
# 1. Czech system locale, install, launch → Connect screen („Vybrat flashku“); rotate and back; background
#    and foreground; process killed in the background and restored.
# 2. A USB drive stand-in: the emulator's virtual SD disk (a removable public volume) with the bundled guides
#    ZIM in Knihovna/, picked in the system folder picker → Home → „První pomoc“ → the reader's WebView shows
#    the guide; rotate, background, process death again. The picker belongs to the system (DocumentsUI):
#    when it cannot be driven, part 2 is skipped with a warning. Everything the app itself shows is asserted.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
APK="${1:?usage: emulator-smoke.sh <apk-or-dir> <out-dir>}"
OUT="${2:?usage: emulator-smoke.sh <apk-or-dir> <out-dir>}"
[ -d "$APK" ] && APK="$(find "$APK" -name '*.apk' | head -1)"
PKG=cz.offlinka.app.debug
ACTIVITY=cz.offlinka.app.MainActivity
ZIM="$ROOT/android/app/src/main/assets/offlinka_cs_prirucky.zim"
UIPY="$HERE/uidump.py"
UIX="$OUT/ui.xml"
mkdir -p "$OUT/shots" "$OUT/dumps"
API="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
N=0
STEP=00-start
LOGCAT_PID=

# ---- helpers ----------------------------------------------------------------------------------------
log() { echo "[api$API $STEP] $*"; }

step() {
  N=$((N + 1))
  STEP="$(printf '%02d' "$N")-$1"
  echo "::group::API $API step $STEP"
}

# UI dump to $UIX; retries while the UI is not idle (uiautomator refuses to dump during animations).
dump() {
  for _ in 1 2 3 4 5; do
    adb shell rm -f /data/local/tmp/ui.xml >/dev/null 2>&1 || true
    if adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1 \
      && adb exec-out cat /data/local/tmp/ui.xml > "$UIX" 2>/dev/null && grep -q '<hierarchy' "$UIX"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

has() { python3 "$UIPY" has "$UIX" "$1"; }

# A "… isn't responding" dialog of another app (System UI, launcher) is emulator noise: wait it out.
# Our own ANR is caught from logcat by crash_check.
dismiss_foreign_anr() {
  if has "neodpovídá|isn't responding|not responding" && ! has "Offlinka"; then
    log "dismissing a system ANR dialog"
    local xy
    xy="$(python3 "$UIPY" center "$UIX" "počkat|wait")" && adb shell input tap $xy || true
  fi
}

# wait_for <regex> [seconds]: a node whose text or content-desc contains the regex.
wait_for() {
  local end=$((SECONDS + ${2:-30}))
  while [ "$SECONDS" -lt "$end" ]; do
    if dump; then
      has "$1" && return 0
      dismiss_foreign_anr
    fi
    sleep 1
  done
  return 1
}

# tap <regex>: taps the centre of the first on-screen node whose text or content-desc fully matches.
tap() {
  local xy
  dump || return 1
  xy="$(python3 "$UIPY" center "$UIX" "$1")" || return 1
  log "tap '$1' at $xy"
  adb shell input tap $xy
}

# scroll_tap <regex>: like tap, scrolling the screen up to 4 times to bring the node into view
# (a node below the fold is in the dump with empty bounds).
scroll_tap() {
  local w h
  for _ in 1 2 3 4 5; do
    tap "$1" && return 0
    read -r w h < <(adb shell wm size | tr -d '\r' | tail -1 | sed 's/.*: //; s/x/ /')
    log "scrolling to find '$1'"
    adb shell input swipe $((w / 2)) $((h * 3 / 4)) $((w / 2)) $((h / 3)) 400
    sleep 1
  done
  return 1
}

snap() {  # snap [suffix]
  local name="api$API-$STEP${1:+-$1}"
  adb exec-out screencap -p > "$OUT/shots/$name.png" 2>/dev/null || true
  if dump; then
    cp "$UIX" "$OUT/dumps/$name.xml"
    log "on screen: $(python3 "$UIPY" texts "$UIX" | head -12 | paste -sd'|' -)"
  fi
}

crash_check() {
  local f="$OUT/logcat-check.txt"
  adb logcat -d -b crash -b main -b system -v threadtime > "$f" 2>/dev/null || true
  if ! python3 "$UIPY" crashes "$f" "cz.offlinka.app" > "$OUT/crash-api$API.txt"; then
    cat "$OUT/crash-api$API.txt"
    echo "::error title=App crash or ANR on API $API::after step $STEP (see crash-api$API.txt and logcat in the artifact)"
    exit 1
  fi
  rm -f "$OUT/crash-api$API.txt"
}

end_step() {
  snap
  crash_check
  echo "::endgroup::"
}

fail() {
  echo "::error title=Emulator API $API::$STEP: $*"
  snap
  crash_check
  exit 1
}

warn() { echo "::warning title=Emulator API $API::$STEP: $*"; }

app_pid() { adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true; }

foreground() { adb shell am start -n "$PKG/$ACTIVITY" >/dev/null; }

# Like tapping the launcher icon: brings back the existing task (restored after process death).
launcher() { adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; }

home() { adb shell input keyevent KEYCODE_HOME; sleep 2; }

rotate() {  # rotate 0|1 (portrait, landscape)
  adb shell settings put system accelerometer_rotation 0
  adb shell settings put system user_rotation "$1"
  adb shell cmd window user-rotation lock "$1" >/dev/null 2>&1 || true
  sleep 3
}

landscape_shot() {  # true when the last screenshot is wider than tall
  local s="$OUT/shots/api$API-$STEP.png" w h
  [ -s "$s" ] || return 1
  read -r w h < <(python3 "$UIPY" size "$s")
  [ "$w" -gt "$h" ]
}

kill_in_background() {
  home
  adb shell am kill "$PKG" || true
  sleep 2
  local pid
  pid="$(app_pid)"
  if [ -n "$pid" ]; then
    # Still alive (not yet "safe to kill"): kill it as the app's own user, like the low-memory killer would.
    log "am kill left pid $pid, killing it"
    adb shell run-as "$PKG" kill -9 "$pid" 2>/dev/null || adb shell kill -9 "$pid" 2>/dev/null || true
    sleep 2
  fi
  [ -z "$(app_pid)" ] || fail "the app process survived am kill"
}

# Czech system locale: persist.sys.locale + framework restart (needs adb root: google_apis images).
czech() {
  if adb shell am get-config 2>/dev/null | grep -q 'cs-rCZ'; then return 0; fi
  adb root >/dev/null 2>&1 || true
  sleep 3
  adb wait-for-device
  adb shell setprop persist.sys.locale cs-CZ
  adb shell setprop ctl.restart zygote
  sleep 8
  local end=$((SECONDS + 240))
  while [ "$SECONDS" -lt "$end" ]; do
    if adb shell am get-config 2>/dev/null | grep -q 'cs-rCZ' \
      && adb shell dumpsys activity activities 2>/dev/null | grep 'ResumedActivity' | grep -vq FallbackHome; then
      sleep 5
      return 0
    fi
    sleep 3
  done
  echo "::error title=Emulator API $API::the Czech locale did not apply"
  exit 1
}

cleanup() {
  [ -n "$LOGCAT_PID" ] && kill "$LOGCAT_PID" 2>/dev/null || true
  adb logcat -d -b all -v threadtime > "$OUT/logcat-api$API-end.txt" 2>/dev/null || true
  rm -f "$UIX" "$OUT/logcat-check.txt"
}
trap cleanup EXIT

# ---- 1. Connect screen ---------------------------------------------------------------------------------
echo "API $API, $(adb shell getprop ro.product.model | tr -d '\r'), apk $APK"
czech
adb logcat -G 16M >/dev/null 2>&1 || true
adb logcat -b all -c || true
adb logcat -v threadtime > "$OUT/logcat-api$API.txt" 2>&1 &
LOGCAT_PID=$!

step install
adb install -r "$APK"
echo "::endgroup::"

CONNECT="Vybrat flashku"
step launch
adb shell am start -W -n "$PKG/$ACTIVITY"
wait_for "$CONNECT" 60 || fail "Connect screen without „$CONNECT“"
has "Zapojte flashku Offlinka" || fail "Connect screen without its title"
end_step

# Only on the "unplugged" variant of the Connect screen (drive seen before); not on a first start.
if has "Otevřít první pomoc"; then
  warn "the Connect screen offers the guides on a first start"
fi

step rotate-landscape
rotate 1
wait_for "$CONNECT|Zapojte flashku|Telefon s flashkou" 20 || fail "Connect screen gone after rotation"
end_step
landscape_shot || warn "the screen did not turn to landscape"

step rotate-portrait
rotate 0
wait_for "$CONNECT" 20 || fail "Connect screen gone after rotating back"
end_step

step background-foreground
home
[ -n "$(app_pid)" ] || fail "the app died in the background"
foreground
wait_for "$CONNECT" 30 || fail "Connect screen not back after foreground"
end_step

step process-death
kill_in_background
launcher
wait_for "$CONNECT" 60 || fail "Connect screen not restored after process death"
end_step

# ---- 2. Drive stand-in, Home, guides in the WebView ------------------------------------------------------
# Removable volume: vold's virtual disk, partitioned as public (FAT), like a USB drive.
virtual_drive() {
  adb root >/dev/null 2>&1 || true
  sleep 2
  adb wait-for-device
  adb shell sm set-virtual-disk true
  local disk="" vol="" end=$((SECONDS + 90))
  while [ -z "$disk" ] && [ "$SECONDS" -lt "$end" ]; do
    disk="$(adb shell sm list-disks 2>/dev/null | tr -d '\r' | grep -m1 '^disk:' || true)"
    [ -n "$disk" ] || sleep 2
  done
  [ -n "$disk" ] || { log "no virtual disk"; return 1; }
  log "virtual disk $disk"
  adb shell sm partition "$disk" public
  end=$((SECONDS + 120))
  while [ -z "$vol" ] && [ "$SECONDS" -lt "$end" ]; do
    vol="$(adb shell sm list-volumes public 2>/dev/null | tr -d '\r' | awk '$2 == "mounted" { print $3; exit }')"
    [ -n "$vol" ] || sleep 2
  done
  [ -n "$vol" ] || { log "volume not mounted: $(adb shell sm list-volumes all | tr -d '\r' | paste -sd'|' -)"; return 1; }
  log "volume $vol mounted"
  local dir="/storage/$vol/Knihovna"
  if ! adb shell mkdir -p "$dir" 2>/dev/null || ! adb push "$ZIM" "$dir/" >/dev/null; then
    dir="/mnt/media_rw/$vol/Knihovna"
    adb shell mkdir -p "$dir" && adb push "$ZIM" "$dir/" >/dev/null || return 1
  fi
  adb shell ls -l "$dir"
}

PICKER_BUTTON="^(použít tuto složku|use this folder|vybrat|select)$"
PICKER_ALLOW="^(povolit|allow)$"
# The removable volume in the picker's roots: vold's virtual disk is an "SD card" ("SD karta Virtual").
PICKER_ROOT="(sd karta|karta sd|sd card).*|.*virtual.*|.*usb.*"
# Android 11+ refuses the root of an SD card (not of a USB drive) for a folder grant.
PICKER_REFUSED="tuto složku nelze použít|can.t use this folder"

picker_shown() { dump && python3 "$UIPY" packages "$UIX" | grep -q documentsui; }

# The system folder picker (DocumentsUI): "use this folder" on the volume root, then "allow". API 29+ opens
# on the volume (StorageVolume.createOpenDocumentTreeIntent); older ones open on Recent, so go to the SD
# card through the roots drawer first. Returns 1 when it cannot be driven (a layout we do not know).
pick_drive() {
  local end=$((SECONDS + 30))
  until picker_shown; do
    [ "$SECONDS" -lt "$end" ] || { log "no picker; on screen: $(python3 "$UIPY" packages "$UIX")"; snap picker; return 1; }
    sleep 1
  done
  sleep 2
  snap picker
  if ! python3 "$UIPY" center "$UIX" "$PICKER_BUTTON" >/dev/null; then
    tap "zobrazit kořeny|show roots" || return 1
    sleep 2
    snap roots
    tap "$PICKER_ROOT" || return 1
    sleep 3
    wait_for "$PICKER_BUTTON" 15 || return 1
    snap volume
  fi
  if has "$PICKER_REFUSED"; then
    # The virtual disk is an SD card, whose root Android 11+ refuses. The app's help covers this case
    # ("some phones refuse the drive's root, then people pick Knihovna"): pick Knihovna/.
    log "the volume root is refused: picking Knihovna"
    scroll_tap "Knihovna" || return 1
    local end2=$((SECONDS + 20))
    until dump && has "$PICKER_BUTTON" && ! has "$PICKER_REFUSED"; do
      [ "$SECONDS" -lt "$end2" ] || return 1
      sleep 1
    done
    snap knihovna
  fi
  tap "$PICKER_BUTTON" || return 1
  sleep 2
  if wait_for "$PICKER_ALLOW" 10; then
    snap allow
    tap "$PICKER_ALLOW" || return 1
  fi
  return 0
}

HOME_RE="Hledat v Offlince"
GUIDE_RE="záchranná služba|První pomoc – rychlé karty|Deset situací"
step drive-plugged
if ! virtual_drive; then
  warn "no virtual SD disk on this emulator: drive, Home and reader steps skipped"
  end_step
  exit 0
fi
foreground
wait_for "$CONNECT" 30 || fail "Connect screen not shown with the drive plugged in"
has "Flashku vidím" || warn "the Connect screen does not report the plugged-in drive („Flashku vidím“)"
end_step

step pick-drive
scroll_tap "$CONNECT|Vybrat znovu" || fail "no „$CONNECT“ button"
sleep 2
# Without a visible volume the first tap only explains ("Flashku nevidím"); the second opens the picker.
if ! picker_shown && has "Vybrat znovu"; then scroll_tap "Vybrat znovu" || true; fi
if ! pick_drive; then
  warn "could not drive the system folder picker: Home and reader steps skipped"
  end_step
  exit 0
fi
wait_for "$HOME_RE|První pomoc" 90 || fail "Home did not show after picking the drive"
end_step

step first-aid
scroll_tap "První pomoc" || fail "no „První pomoc“ quick link on Home"
# The guide is in the WebView: its text in the accessibility tree, or at least its title in the reader bar.
wait_for "$GUIDE_RE" 45 || fail "the reader did not show the first-aid guide"
end_step

step reader-landscape
rotate 1
wait_for "$GUIDE_RE" 30 || fail "the guide is gone after rotation"
end_step
landscape_shot || warn "the screen did not turn to landscape"

step reader-portrait
rotate 0
wait_for "$GUIDE_RE" 30 || fail "the guide is gone after rotating back"
end_step

step reader-background-foreground
home
[ -n "$(app_pid)" ] || fail "the app died in the background"
foreground
wait_for "$GUIDE_RE" 30 || fail "the guide is not back after foreground"
end_step

step reader-process-death
kill_in_background
launcher
if ! wait_for "$GUIDE_RE" 60; then
  wait_for "$HOME_RE|$CONNECT" 10 || fail "neither the guide nor Home after process death"
  warn "after process death the app shows Home / Connect, not the guide that was open"
fi
end_step

step back-home
adb shell input keyevent KEYCODE_BACK
sleep 3
end_step
echo "API $API: all steps passed"
