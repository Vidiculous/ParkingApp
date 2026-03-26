package com.example.parking

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import org.osmdroid.config.Configuration

class ParkingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Required by osmdroid — identifies our app for tile server user-agent
        Configuration.getInstance().userAgentValue = packageName
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                "parking_events",
                "Parking events",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when your parking is registered or unregistered"
            }
        )
    }
}
