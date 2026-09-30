#!/bin/zsh
# Screenshots the demo app on a simulator, optionally opening a screen via deep link.
#
#   scripts/shot.sh <simulator name or UDID> <out.png> [attend://link] [light|dark]
#
# Build first (from ios/):
#   xcodebuild -project Attend.xcodeproj -scheme Attend -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
#     -derivedDataPath build/DerivedData build
# Env: WAIT=<seconds> before the screenshot (default 4), APP=<path to Attend.app>.
set -e
cd "${0:A:h}/.."
DEV=$1 OUT=$2 LINK=${3:-} LOOK=${4:-light}
APP=${APP:-build/DerivedData/Build/Products/Debug-iphonesimulator/Attend.app}
xcrun simctl boot "$DEV" 2>/dev/null || true
xcrun simctl bootstatus "$DEV" -b >/dev/null
xcrun simctl ui "$DEV" appearance "$LOOK"
xcrun simctl status_bar "$DEV" override --time 9:41 --batteryState charged --batteryLevel 100 --cellularBars 4 --wifiBars 3 2>/dev/null || true
xcrun simctl terminate "$DEV" au.ingo.betterattend 2>/dev/null || true
xcrun simctl install "$DEV" "$APP"
ARGS=(-AttendDemo YES)
[[ -n $LINK ]] && ARGS+=(-AttendOpenURL "$LINK")
xcrun simctl launch "$DEV" au.ingo.betterattend "${ARGS[@]}" >/dev/null
sleep ${WAIT:-4}
xcrun simctl io "$DEV" screenshot "$OUT" >/dev/null
echo "$OUT"
