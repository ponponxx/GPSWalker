package com.gpswalker.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Broadcast entry point. Triggered by:
 *   adb shell am broadcast -a com.gpswalker.SET \
 *     -n com.gpswalker.companion/.LocReceiver \
 *     --ef lat 25.0330 --ef lon 121.5654
 */
class LocReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val lat = intent.getFloatExtra("lat", Float.NaN)
        val lon = intent.getFloatExtra("lon", Float.NaN)
        if (lat.isNaN() || lon.isNaN()) {
            Log.w("GPSWalker", "LocReceiver missing lat/lon extras")
            return
        }
        val acc = intent.getFloatExtra("acc", 1.5f)
        val spd = intent.getFloatExtra("spd", 1.2f)
        val brg = intent.getFloatExtra("brg", 0f)
        MockEngine.push(ctx.applicationContext, lat.toDouble(), lon.toDouble(), acc, spd, brg)
    }
}
