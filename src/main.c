/*
 * Parking Detection System — iBeacon Firmware
 * Board: nRF52840 Dongle (PCA10059)
 *
 * Advertises in Apple iBeacon format so iOS and Android can monitor
 * for it without a BLE connection, including in the background.
 *
 * Per-dongle configuration: change BEACON_MINOR and CONFIG_BT_DEVICE_NAME
 * (in prj.conf) for each unit.
 *
 * Proximity UUID: E2C56DB5-DFFB-48D2-B060-D0F5A71096E0 (shared across all dongles)
 * Major: 1  (identifies this office / parking lot)
 * Minor: 1  (unique per dongle — change per unit)
 */

#include <zephyr/kernel.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/hci.h>
#include <zephyr/drivers/gpio.h>

/* ── LED ─────────────────────────────────────────────────────────────── */
#define LED_NODE DT_ALIAS(led0)
static const struct gpio_dt_spec led = GPIO_DT_SPEC_GET(LED_NODE, gpios);

/* ── iBeacon parameters ──────────────────────────────────────────────── */
#define BEACON_MAJOR      1
#define BEACON_MINOR      2   /* change per dongle: 1–10 */
#define BEACON_TX_POWER   (-59) /* calibrated RSSI at 1 m */

/*
 * Apple iBeacon manufacturer-specific payload (25 bytes total):
 *   [0-1]  Apple company ID  0x004C (little-endian)
 *   [2]    iBeacon type      0x02
 *   [3]    remaining length  0x15 (21)
 *   [4-19] Proximity UUID    E2C56DB5-DFFB-48D2-B060-D0F5A71096E0
 *   [20-21] Major            big-endian
 *   [22-23] Minor            big-endian
 *   [24]   TX Power          signed byte
 */
static uint8_t beacon_data[] = {
	/* Company ID: Apple (0x004C) */
	0x4C, 0x00,
	/* iBeacon type + length */
	0x02, 0x15,
	/* Proximity UUID: E2C56DB5-DFFB-48D2-B060-D0F5A71096E0 */
	0xE2, 0xC5, 0x6D, 0xB5,
	0xDF, 0xFB,
	0x48, 0xD2,
	0xB0, 0x60,
	0xD0, 0xF5, 0xA7, 0x10, 0x96, 0xE0,
	/* Major (big-endian) */
	(BEACON_MAJOR >> 8) & 0xFF, BEACON_MAJOR & 0xFF,
	/* Minor (big-endian) */
	(BEACON_MINOR >> 8) & 0xFF, BEACON_MINOR & 0xFF,
	/* TX Power */
	(uint8_t)BEACON_TX_POWER,
};

static const struct bt_data ad[] = {
	/* Flags: LE General Discoverable, BR/EDR not supported */
	BT_DATA_BYTES(BT_DATA_FLAGS, BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR),
	/* Manufacturer specific data (Apple iBeacon) */
	BT_DATA(BT_DATA_MANUFACTURER_DATA, beacon_data, sizeof(beacon_data)),
};

/* Scan response: carries the human-readable device name so apps like
 * nRF Connect can display it. iOS ignores the scan response for beacon
 * monitoring purposes — it doesn't affect iBeacon behaviour. */
static const struct bt_data sd[] = {
	BT_DATA(BT_DATA_NAME_COMPLETE,
		CONFIG_BT_DEVICE_NAME, sizeof(CONFIG_BT_DEVICE_NAME) - 1),
};

/* ── Main ────────────────────────────────────────────────────────────── */
int main(void)
{
	int err;

	/* Set up LED */
	if (device_is_ready(led.port)) {
		gpio_pin_configure_dt(&led, GPIO_OUTPUT_INACTIVE);
	}

	/* Enable Bluetooth */
	err = bt_enable(NULL);
	if (err) {
		return err;
	}

	/* Start non-connectable advertising (iBeacon is non-connectable) */
	err = bt_le_adv_start(BT_LE_ADV_NCONN, ad, ARRAY_SIZE(ad), sd, ARRAY_SIZE(sd));
	if (err) {
		return err;
	}

	/* Slow 2 s blink to confirm firmware is running */
	while (1) {
		if (device_is_ready(led.port)) {
			gpio_pin_toggle_dt(&led);
		}
		k_sleep(K_SECONDS(1));
	}

	return 0;
}
