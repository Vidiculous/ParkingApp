package com.example.parking

import android.app.Application
import org.osmdroid.config.Configuration

class ParkingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Required by osmdroid — identifies our app for tile server user-agent
        Configuration.getInstance().userAgentValue = packageName
    }
}
