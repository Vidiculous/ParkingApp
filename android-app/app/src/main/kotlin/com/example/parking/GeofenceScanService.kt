package com.example.parking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.Geofence
import kotlinx.coroutines.*

private const val TAG = "PARK_SVC"
private const val CHANNEL_ID = "parking_scan"
private const val NOTIF_ID = 1
private const val NOTIF_ID_PARKING_EVENT = 3

class GeofenceScanService : Service() {

    companion object {
        const val EXTRA_TRANSITION = "transition"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val transition = intent?.getIntExtra(EXTRA_TRANSITION, -1) ?: -1

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Parking")
            .setContentText("Checking parking status…")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()

        startForeground(NOTIF_ID, notif)

        // Acquire a WakeLock so the CPU doesn't sleep during BLE scan
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "parking:scan").also {
            it.acquire(30_000L)  // max 30s safety timeout
        }

        scope.launch {
            try {
                handleTransition(transition)
            } finally {
                wakeLock?.release()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun handleTransition(transition: Int) {
        val minor = dongleMinor
        Log.d(TAG, "Scanning for beacon minor=$minor after transition=$transition")

        val beaconFound = BleScanner.scan(this, minor)
        Log.d(TAG, "Beacon found=$beaconFound transition=$transition")
        // Persist so debug panel can show last known beacon state
        with(Prefs) { debugBeaconNearby = beaconFound }

        val status = when {
            (transition == Geofence.GEOFENCE_TRANSITION_ENTER ||
             transition == Geofence.GEOFENCE_TRANSITION_DWELL) && beaconFound -> "parked"
            transition == Geofence.GEOFENCE_TRANSITION_EXIT && beaconFound -> "left"
            else -> {
                Log.d(TAG, "No status change: transition=$transition beaconFound=$beaconFound")
                return
            }
        }

        val result = ParkingRepository.postStatus(this, status, manual = false)
        Log.d(TAG, "POST $status → $result")

        when {
            result is PostResult.OkWithWarning && result.warning == "lot_full" ->
                showLotFullNotification()
            result is PostResult.Ok || result is PostResult.OkWithWarning ->
                showParkingEventNotification(status)
        }
    }

    private val dongleMinor: Int get() {
        val prefs = getSharedPreferences("parking_prefs", Context.MODE_PRIVATE)
        return prefs.getInt("dongle_minor", 1)
    }

    private fun showParkingEventNotification(status: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val title = if (status == "parked") "You are now parked" else "Parking unregistered"
        val notif = NotificationCompat.Builder(this, "parking_events")
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID_PARKING_EVENT, notif)
    }

    private fun showLotFullNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Parking lot is full")
            .setContentText("Your status was recorded, but all 7 spots are taken.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        nm.notify(2, notif)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Parking scan",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown briefly while checking beacon"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
