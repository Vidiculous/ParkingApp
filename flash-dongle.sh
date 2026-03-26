#!/usr/bin/env bash
# flash-dongle.sh — configure, build, and flash a PARK dongle via USB DFU
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# ── Prompt for dongle ID ──────────────────────────────────────────────────────

while true; do
    read -rp "Dongle number (1-10): " N
    if [[ "$N" =~ ^([1-9]|10)$ ]]; then
        break
    fi
    echo "  Error: enter a number between 1 and 10." >&2
done

DEVICE_NAME="$(printf 'PARK-%03d' "$N")"
echo "Configuring for $DEVICE_NAME (minor=$N)…"

# ── Patch source files ────────────────────────────────────────────────────────

sed -i "s/CONFIG_BT_DEVICE_NAME=.*/CONFIG_BT_DEVICE_NAME=\"$DEVICE_NAME\"/" prj.conf
sed -i "s/#define BEACON_MINOR.*/#define BEACON_MINOR      $N   \/* change per dongle: 1–10 *\//" src/main.c

echo "  prj.conf → CONFIG_BT_DEVICE_NAME=\"$DEVICE_NAME\""
echo "  src/main.c → BEACON_MINOR=$N"

# ── Build ─────────────────────────────────────────────────────────────────────

echo ""
echo "Building…"
uv run west build -b nrf52840dongle/nrf52840

HEX="build/ParkingApp/zephyr/zephyr.hex"
if [[ ! -f "$HEX" ]]; then
    echo "Error: build succeeded but $HEX not found. Check west build output above." >&2
    exit 1
fi

# ── Detect port ───────────────────────────────────────────────────────────────

echo ""
echo "Put the dongle into bootloader mode now (press the sideways reset button"
echo "until the red LED pulses), then press Enter."
read -rp ""

PORTS=(/dev/ttyACM* /dev/ttyUSB*)
AVAILABLE=()
for p in "${PORTS[@]}"; do
    [[ -e "$p" ]] && AVAILABLE+=("$p")
done

if [[ ${#AVAILABLE[@]} -eq 0 ]]; then
    echo "Error: no serial port found. Is the dongle in bootloader mode?" >&2
    exit 1
elif [[ ${#AVAILABLE[@]} -eq 1 ]]; then
    PORT="${AVAILABLE[0]}"
    echo "Using port: $PORT"
else
    echo "Multiple ports found:"
    for i in "${!AVAILABLE[@]}"; do
        echo "  [$i] ${AVAILABLE[$i]}"
    done
    read -rp "Choose port number: " IDX
    PORT="${AVAILABLE[$IDX]}"
fi

# ── Package and flash ─────────────────────────────────────────────────────────

echo ""
echo "Packaging firmware…"
nrfutil pkg generate \
    --hw-version 52 \
    --sd-req=0x00 \
    --application "$HEX" \
    --application-version 1 \
    firmware.zip

if [[ ! -f firmware.zip ]]; then
    echo "Error: nrfutil pkg generate failed — firmware.zip was not created." >&2
    exit 1
fi

echo "Flashing to $PORT…"
nrfutil dfu usb-serial -pkg firmware.zip -p "$PORT"
if [[ $? -ne 0 ]]; then
    echo "Error: flashing failed." >&2
    exit 1
fi

echo ""
echo "Done. $DEVICE_NAME flashed successfully."
