package com.gpswalker.companion

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Owns mock providers and pushes Location objects. One process-wide instance.
 */
object MockEngine {
    private const val TAG = "GPSWalker"

    private val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER
    )

    @Volatile var ready = false
        private set

    @Volatile var lastLat: Double = 0.0
    @Volatile var lastLon: Double = 0.0

    fun init(ctx: Context) {
        if (ready) return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        for (p in providers) {
            try { lm.removeTestProvider(p) } catch (_: Throwable) {}
            try {
                lm.addTestProvider(
                    p,
                    /*requiresNetwork*/ false,
                    /*requiresSatellite*/ false,
                    /*requiresCell*/ false,
                    /*hasMonetaryCost*/ false,
                    /*supportsAltitude*/ true,
                    /*supportsSpeed*/ true,
                    /*supportsBearing*/ true,
                    /*powerRequirement*/ 1,
                    /*accuracy*/ 1
                )
                lm.setTestProviderEnabled(p, true)
            } catch (e: SecurityException) {
                Log.e(TAG, "addTestProvider($p) failed — is this app set as mock location app?", e)
                return
            } catch (e: Throwable) {
                Log.e(TAG, "addTestProvider($p) error", e)
            }
        }
        ready = true
        Log.i(TAG, "MockEngine ready")
    }

    fun push(ctx: Context, lat: Double, lon: Double,
             accuracyM: Float = 1.5f, speedMps: Float = 1.2f, bearingDeg: Float = 0f) {
        if (!ready) init(ctx)
        if (!ready) return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val now = System.currentTimeMillis()
        val nanos = SystemClock.elapsedRealtimeNanos()
        for (p in providers) {
            val loc = Location(p).apply {
                latitude = lat
                longitude = lon
                altitude = 10.0
                accuracy = accuracyM
                speed = speedMps
                bearing = bearingDeg
                time = now
                elapsedRealtimeNanos = nanos
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    bearingAccuracyDegrees = 1f
                    verticalAccuracyMeters = 1f
                    speedAccuracyMetersPerSecond = 0.3f
                }
            }
            try { lm.setTestProviderLocation(p, loc) }
            catch (e: Throwable) { Log.e(TAG, "setTestProviderLocation($p) failed", e) }
        }
        lastLat = lat; lastLon = lon
    }

    fun shutdown(ctx: Context) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        for (p in providers) {
            try { lm.removeTestProvider(p) } catch (_: Throwable) {}
        }
        ready = false
    }
}
