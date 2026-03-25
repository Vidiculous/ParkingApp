package com.example.parking

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val FILE = "parking_prefs"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var Context.userName: String
        get() = sp(this).getString("user_name", "") ?: ""
        set(v) { sp(this).edit().putString("user_name", v).apply() }

    var Context.dongleId: String
        get() = sp(this).getString("dongle_id", "PARK-001") ?: "PARK-001"
        set(v) { sp(this).edit().putString("dongle_id", v).apply() }

    var Context.dongleMinor: Int
        get() = sp(this).getInt("dongle_minor", 1)
        set(v) { sp(this).edit().putInt("dongle_minor", v).apply() }

    var Context.backendUrl: String
        get() = sp(this).getString("backend_url", "http://10.0.0.1:8000") ?: "http://10.0.0.1:8000"
        set(v) { sp(this).edit().putString("backend_url", v).apply() }

    var Context.geoLat: Double
        get() = java.lang.Double.longBitsToDouble(sp(this).getLong("geo_lat", java.lang.Double.doubleToLongBits(Double.NaN)))
        set(v) { sp(this).edit().putLong("geo_lat", java.lang.Double.doubleToLongBits(v)).apply() }

    var Context.geoLng: Double
        get() = java.lang.Double.longBitsToDouble(sp(this).getLong("geo_lng", java.lang.Double.doubleToLongBits(Double.NaN)))
        set(v) { sp(this).edit().putLong("geo_lng", java.lang.Double.doubleToLongBits(v)).apply() }

    var Context.geoRadius: Double
        get() = java.lang.Double.longBitsToDouble(sp(this).getLong("geo_radius", java.lang.Double.doubleToLongBits(100.0)))
        set(v) { sp(this).edit().putLong("geo_radius", java.lang.Double.doubleToLongBits(v)).apply() }

    var Context.debugInsideGeofence: Boolean
        get() = sp(this).getBoolean("debug_inside_geofence", false)
        set(v) { sp(this).edit().putBoolean("debug_inside_geofence", v).apply() }

    var Context.debugBeaconNearby: Boolean
        get() = sp(this).getBoolean("debug_beacon_nearby", false)
        set(v) { sp(this).edit().putBoolean("debug_beacon_nearby", v).apply() }

    fun Context.hasGeofence(): Boolean {
        val lat = geoLat
        val lng = geoLng
        return !lat.isNaN() && !lng.isNaN()
    }
}
