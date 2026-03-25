#!/usr/bin/env bash
# Geofence testing script — injects fake GPS via ADB
# Usage: ./test_geofence.sh [lat] [lng] [radius_m]
#
# Defaults to the coordinates saved in the app.
# Press Ctrl+C to clean up and restore real GPS.

set -e

PARK_LAT="${1:-59.3755856}"
PARK_LNG="${2:-13.5058296}"
RADIUS="${3:-100}"

# A point clearly outside the geofence (~500 m away)
OUTSIDE_LAT=$(echo "$PARK_LAT + 0.005" | bc -l)
OUTSIDE_LNG="$PARK_LNG"

cleanup() {
  echo ""
  echo "Restoring real GPS..."
  adb shell cmd location set-test-provider-enabled gps false 2>/dev/null || true
  adb shell cmd location set-test-provider-enabled network false 2>/dev/null || true
  echo "Done."
}
trap cleanup EXIT

mock_location() {
  local lat=$1 lng=$2 label=$3
  echo "→ Setting location: $label ($lat, $lng)"
  adb shell cmd location set-test-provider-enabled gps true
  adb shell cmd location set-test-provider-location gps "$lat" "$lng" 0 1 0 0 0
  adb shell cmd location set-test-provider-enabled network true
  adb shell cmd location set-test-provider-location network "$lat" "$lng" 0 1 0 0 0
}

echo "=============================="
echo " Geofence Test"
echo " Parking:  $PARK_LAT, $PARK_LNG (r=${RADIUS}m)"
echo " Outside:  $OUTSIDE_LAT, $OUTSIDE_LNG"
echo "=============================="
echo ""
echo "Controls:"
echo "  [1] Simulate INSIDE  geofence (at parking)"
echo "  [2] Simulate OUTSIDE geofence (~500m away)"
echo "  [3] Restore real GPS and exit"
echo ""

while true; do
  read -rp "Choice: " choice
  case $choice in
    1) mock_location "$PARK_LAT"    "$PARK_LNG"    "INSIDE  (parking)" ;;
    2) mock_location "$OUTSIDE_LAT" "$OUTSIDE_LNG" "OUTSIDE (away)"    ;;
    3) break ;;
    *) echo "  1, 2 or 3" ;;
  esac
done
