package com.example.parking

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "PARK_BLE"
const val PROXIMITY_UUID = "e2c56db5-dffb-48d2-b060-d0f5a71096e0"

object BleScanner {

    /** Scan for our iBeacon for up to [durationMs] ms. Returns true if found. */
    suspend fun scan(context: Context, wantedMinor: Int, durationMs: Long = 8_000L): Boolean {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bm.adapter ?: return false
        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is off")
            return false
        }
        val scanner = adapter.bluetoothLeScanner ?: return false

        return suspendCancellableCoroutine { cont ->
            var found = false
            val handler = Handler(Looper.getMainLooper())

            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (!found && isOurBeacon(result, wantedMinor)) {
                        found = true
                        Log.d(TAG, "Beacon found — minor=$wantedMinor")
                        handler.removeCallbacksAndMessages(null)
                        scanner.stopScan(this)
                        if (!cont.isCompleted) cont.resume(true)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.w(TAG, "Scan failed: $errorCode")
                    if (!cont.isCompleted) cont.resume(false)
                }
            }

            scanner.startScan(callback)
            Log.d(TAG, "BLE scan started (minor=$wantedMinor, timeout=${durationMs}ms)")

            handler.postDelayed({
                scanner.stopScan(callback)
                Log.d(TAG, "BLE scan timeout — found=$found")
                if (!cont.isCompleted) cont.resume(found)
            }, durationMs)

            cont.invokeOnCancellation {
                handler.removeCallbacksAndMessages(null)
                scanner.stopScan(callback)
            }
        }
    }

    private fun isOurBeacon(result: ScanResult, wantedMinor: Int): Boolean {
        // flutter_blue_plus and Android both: getManufacturerSpecificData(companyId)
        // returns payload WITHOUT the 2-byte company ID prefix.
        // Layout: [0]=0x02 [1]=0x15 [2..17]=UUID [18..19]=Major [20..21]=Minor [22]=TxPower
        val payload = result.scanRecord?.getManufacturerSpecificData(0x004C) ?: return false
        if (payload.size < 23) return false
        if (payload[0] != 0x02.toByte() || payload[1] != 0x15.toByte()) return false

        val uuidBytes = payload.copyOfRange(2, 18)
        val hex = uuidBytes.joinToString("") { "%02x".format(it) }
        val uuid = "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"

        if (uuid != PROXIMITY_UUID) return false

        val minor = ((payload[20].toInt() and 0xFF) shl 8) or (payload[21].toInt() and 0xFF)
        Log.d(TAG, "iBeacon: uuid=$uuid minor=$minor (want $wantedMinor)")
        return minor == wantedMinor
    }
}
