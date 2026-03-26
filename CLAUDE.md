# Parking Detection System — nRF52840 Dongle

## Hardware
- **Board:** nRF52840 Dongle (PCA10059)
- **West build target:** `nrf52840dongle/nrf52840`
- **No J-Link** — flashing is done via USB DFU only

## Environment
- **SDK:** `~/ncs/v3.2.4/`
- **Toolchain:** `~/ncs/toolchains/2ac5840438/opt/zephyr-sdk/`
- **Python tooling:** managed with `uv` — always use `uv run west` not bare `west`

## Required shell exports
Add these to `~/.bashrc` or `~/.zshrc`:
```bash
export ZEPHYR_BASE=~/ncs/v3.2.4/zephyr
export ZEPHYR_TOOLCHAIN_VARIANT=zephyr
export ZEPHYR_SDK_INSTALL_DIR=~/ncs/toolchains/2ac5840438/opt/zephyr-sdk
export PATH=~/bin:~/ncs/toolchains/2ac5840438/opt/zephyr-sdk/arm-zephyr-eabi/bin:~/ncs/toolchains/2ac5840438/usr/local/bin:~/ncs/toolchains/2ac5840438/usr/bin:$PATH
```

> **Note:** `~/bin` must be first — it contains a `ninja` wrapper that fixes a broken Python wrapper in the NCS toolchain (`usr/local/bin/ninja` fails with `No module named 'ninja'`). Create it once with:
> ```bash
> mkdir -p ~/bin
> printf '#!/bin/sh\nexec ~/ncs/toolchains/2ac5840438/usr/local/lib/python3.12/site-packages/ninja/data/bin/ninja "$@"\n' > ~/bin/ninja
> chmod +x ~/bin/ninja
> ```

## Project structure
```
ParkingApp/
├── src/
│   └── main.c               # iBeacon firmware (change BEACON_MINOR per dongle)
├── backend/
│   ├── main.py              # FastAPI server + WebSocket
│   └── requirements.txt
├── dashboard/
│   └── index.html           # live web dashboard (served by backend)
├── android-app/             # Android app (Kotlin)
├── boards/                  # board overlays — nrf54l15dk overlay is for a
│                            #   different chip (nRF54L15); delete it
├── build/                   # west build output
├── CMakeLists.txt
├── prj.conf                 # BLE + GPIO config
├── pyproject.toml           # uv-managed Python deps
├── uv.lock
└── CLAUDE.md
```

## Per-dongle flash customisation
Before building for each dongle, edit two things in `src/main.c` and `prj.conf`:
- `prj.conf`: `CONFIG_BT_DEVICE_NAME="PARK-00N"` (N = 1..10)
- `src/main.c`: `#define BEACON_MINOR N`

## Running the backend
```bash
cd ~/repos/ParkingApp
uv run uvicorn backend.main:app --host 0.0.0.0 --port 8000
```
Dashboard at http://localhost:8000

### Backend configuration (environment variables)
| Variable            | Default | Description                                     |
|---------------------|---------|-------------------------------------------------|
| `TOTAL_SPOTS`       | `7`     | Number of parking spaces in the lot             |
| `STALE_AFTER_HOURS` | `24`    | Hours before a parked status becomes unknown    |
| `PORT`              | `8000`  | TCP port (only used when running via `__main__`)|

## Android app

See `android-app/` — standard Gradle project, open in Android Studio or build with:
```bash
cd android-app
./gradlew assembleDebug
```

> Grant "Allow all the time" for location and disable battery optimisation for the app so background geofencing works reliably.

## Test the backend
```bash
# Check status
curl http://localhost:8000/api/status

# Simulate a park event
curl -X POST http://localhost:8000/api/park \
  -H "Content-Type: application/json" \
  -d '{"name":"Alice","dongle_id":"PARK-001","status":"parked"}'
```

## Build
```bash
cd ~/repos/ParkingApp
uv run west build -b nrf52840dongle/nrf52840
```

## Flash
The dongle has no J-Link. Flash via USB DFU:

1. Press the sideways reset button on the dongle edge — red LED should pulse (bootloader mode)
2. Find the port:
   ```bash
   ls /dev/ttyACM* /dev/ttyUSB*
   ```
3. Package and flash using the standalone `nrfutil` binary:
   ```bash
   nrfutil pkg generate --hw-version 52 --sd-req=0x00 \
     --application build/zephyr/zephyr.hex \
     --application-version 1 firmware.zip

   nrfutil dfu usb-serial -pkg firmware.zip -p /dev/ttyACM0
   ```

## Tooling notes
- `nrfutil` is **not** installed via uv — the PyPI package is broken on Python 3.12
- Use the standalone binary from Nordic instead:
  ```bash
  curl --proto '=https' --tlsv1.2 -sSf \
    https://files.nordicsemi.com/artifactory/swtools/external/nrfutil/executables/x86_64-unknown-linux-gnu/nrfutil \
    -o ~/.local/bin/nrfutil
  chmod +x ~/.local/bin/nrfutil
  nrfutil install nrf5sdk-tools
  nrfutil install device
  ```
- `west==1.5.0` is installed via uv — always invoke as `uv run west`

## Next steps / known issues
- [ ] Confirm firmware build succeeds with `nrf52840dongle/nrf52840` target
- [ ] Confirm nrfutil standalone binary is installed and on PATH
- [ ] Verify iBeacon visible in nRF Connect app after flashing
- [ ] Set backend URL to LAN IP (not localhost) in app settings so phones can reach it
- [ ] Android: prompt users to disable battery optimisation for the app
- [ ] Delete `boards/nrf54l15dk_nrf54l15_cpuapp_hpf_gpio.overlay` — it targets the nRF54L15, not the nRF52840 used here. West ignores it (board name mismatch) but it causes confusion.
