package com.example.parking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("PARK_BOOT", "Boot completed — re-registering geofence")
            GeofenceManager.register(context)
        }
    }
}
