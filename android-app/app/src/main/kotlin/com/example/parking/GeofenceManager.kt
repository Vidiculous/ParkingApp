package com.example.parking

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.parking.Prefs.geoLat
import com.example.parking.Prefs.geoLng
import com.example.parking.Prefs.geoRadius
import com.example.parking.Prefs.hasGeofence
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

private const val TAG = "PARK_GEO"
private const val GEOFENCE_ID = "parking_lot"

object GeofenceManager {

    fun register(context: Context) {
        if (!context.hasGeofence()) {
            Log.w(TAG, "No geofence configured — skipping registration")
            return
        }

        val lat = context.geoLat
        val lng = context.geoLng
        val radius = context.geoRadius.toFloat().coerceAtLeast(20f)

        val geofence = Geofence.Builder()
            .setRequestId(GEOFENCE_ID)
            .setCircularRegion(lat, lng, radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or
                Geofence.GEOFENCE_TRANSITION_DWELL or
                Geofence.GEOFENCE_TRANSITION_EXIT
            )
            .setLoiteringDelay(30_000)  // 30s dwell before DWELL event
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_DWELL)
            .addGeofence(geofence)
            .build()

        val client = LocationServices.getGeofencingClient(context)

        try {
            client.addGeofences(request, pendingIntent(context))
                .addOnSuccessListener {
                    Log.d(TAG, "Geofence registered: centre=($lat,$lng) radius=${radius}m")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Geofence registration failed: ${e.message}")
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission not granted: ${e.message}")
        }
    }

    fun unregister(context: Context) {
        LocationServices.getGeofencingClient(context)
            .removeGeofences(pendingIntent(context))
            .addOnSuccessListener { Log.d(TAG, "Geofence removed") }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }
}
