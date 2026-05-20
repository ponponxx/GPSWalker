package com.gpswalker.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD

/**
 * Foreground service: keeps the process alive, registers the mock providers,
 * and runs the HTTP server so the PC can push locations over Wi-Fi.
 *
 * Accepts two actions:
 *   ACTION_START - start foreground + mock providers + HTTP server
 *   ACTION_STOP  - tear everything down and stop the service
 */
class MockService : Service() {

    private var http: HttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTI_ID, buildNotification())
        MockEngine.init(applicationContext)
        startHttp()
        return START_STICKY
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun startHttp() {
        if (http != null) return
        try {
            http = HttpServer(applicationContext).also {
                it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            }
            Log.i("GPSWalker", "HTTP server up on port ${HttpServer.PORT}")
        } catch (e: Exception) {
            Log.e("GPSWalker", "HTTP server failed to start", e)
            http = null
        }
    }

    private fun teardown() {
        try { http?.stop() } catch (_: Exception) {}
        http = null
        MockEngine.shutdown(applicationContext)
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHAN_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(
                    CHAN_ID, "GPSWalker", NotificationManager.IMPORTANCE_LOW))
            }
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE else 0)
        val stopIntent = Intent(this, MockService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(this, 0, stopIntent, piFlags)

        val ip = NetUtil.localIp()
        val text = if (ip != null) "Listening on http://$ip:${HttpServer.PORT}"
                   else "No Wi-Fi address found"

        return NotificationCompat.Builder(this, CHAN_ID)
            .setContentTitle("GPSWalker active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .build()
    }

    companion object {
        private const val CHAN_ID = "gpswalker"
        private const val NOTI_ID = 1
        const val ACTION_START = "com.gpswalker.companion.START"
        const val ACTION_STOP = "com.gpswalker.companion.STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, MockService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, MockService::class.java).setAction(ACTION_STOP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
