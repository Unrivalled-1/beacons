package com.example.beacons

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/** Fused location, only while the scene is visible. start() in onResume, stop() in onPause. */
class LocationSource(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    @Volatile var last: Location? = null
    @Volatile var updates = 0

    private val cb = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            r.lastLocation?.let { last = it; updates++ }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        client.lastLocation.addOnSuccessListener { if (last == null && it != null) last = it }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()
        client.requestLocationUpdates(req, cb, Looper.getMainLooper())
    }

    fun stop() {
        client.removeLocationUpdates(cb)
    }
}
