#!/usr/bin/env bash
# Copies the README / website screenshots out of the Roborazzi output. Run the screenshot tests first:
#
#   (cd android && ./gradlew :app:testDebugUnitTest --tests '*Screenshots*')
#   docs/update-screenshots.sh
#
# Needs ImageMagick (convert) to scale them down.
set -euo pipefail
cd "$(dirname "$0")"
src=../android/app/build/outputs/roborazzi

# <name on the site> <Roborazzi test name>
shots=(
  "home dashboard_live"
  "scan scan_card_scanned"
  "people people_all"
  "participant detail_sensitive_top"
  "travel travel_list"
  "ticket ticket_detail"
)

mkdir -p screenshots
for pair in "${shots[@]}"; do
  read -r name test <<<"$pair"
  for theme in light dark; do
    convert "$src/${test}_${theme}.png" -resize 540x -strip -define png:compression-level=9 "screenshots/$name-$theme.png"
  done
done
ls -l screenshots
