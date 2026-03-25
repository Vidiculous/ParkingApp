package com.example.parking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.parking.Prefs.debugInsideGeofence
import com.example.parking.Prefs.debugLastGeofenceEvent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "PARK_RECV"

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.e(TAG, "GeofencingEvent error: ${event.errorCode}")
            return
        }

        val transition = event.geofenceTransition
        val label = when (transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTER"
            Geofence.GEOFENCE_TRANSITION_DWELL -> "DWELL"
            Geofence.GEOFENCE_TRANSITION_EXIT  -> "EXIT"
            else -> "UNKNOWN($transition)"
        }
        Log.d(TAG, "Geofence transition: $label")

        // Persist last known geofence state so the debug panel can read it
        val inside = transition == Geofence.GEOFENCE_TRANSITION_ENTER ||
                     transition == Geofence.GEOFENCE_TRANSITION_DWELL
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        with(Prefs) {
            context.debugInsideGeofence = inside
            context.debugLastGeofenceEvent = "$label @ $time"
        }

        // Hand off to service — BLE scan must happen off the receiver's main thread
        val serviceIntent = Intent(context, GeofenceScanService::class.java).apply {
            putExtra(GeofenceScanService.EXTRA_TRANSITION, transition)
        }
        context.startForegroundService(serviceIntent)
    }
}
