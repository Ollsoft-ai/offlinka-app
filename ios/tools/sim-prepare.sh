#!/usr/bin/env bash
# Boot a simulator and give it a clean, store-ready status bar (9:41, full bars and battery), light appearance.
#   ios/tools/sim-prepare.sh <udid>
set -euo pipefail
UDID="$1"
xcrun simctl boot "$UDID" 2>/dev/null || true   # already booted is fine
xcrun simctl bootstatus "$UDID" -b >/dev/null
xcrun simctl ui "$UDID" appearance light
# No one-time keyboard tip ("Speed up your typing by sliding…") over half the screen.
xcrun simctl spawn "$UDID" defaults write com.apple.Preferences DidShowContinuousPathIntroduction -bool true || true
xcrun simctl status_bar "$UDID" override --time "9:41" --dataNetwork wifi --wifiMode active --wifiBars 3 \
  --cellularMode active --cellularBars 4 --operatorName "" --batteryState charged --batteryLevel 100
echo "simulator $UDID ready"
