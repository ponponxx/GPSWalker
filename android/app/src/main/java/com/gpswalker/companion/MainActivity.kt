package com.gpswalker.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)

        findViewById<Button>(R.id.btn_perm).setOnClickListener {
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms += Manifest.permission.POST_NOTIFICATIONS
            }
            permLauncher.launch(perms.toTypedArray())
        }

        findViewById<Button>(R.id.btn_dev).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            MockService.start(this)
            refresh()
        }

        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            MockService.stop(this)
            status.postDelayed({ refresh() }, 400)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val fine = ContextCompat.checkSelfPermission(this,
            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val ip = NetUtil.localIp()
        status.text = buildString {
            append("==== USE THIS ON THE PC ====\n")
            if (ip != null) {
                append("Phone address:  ").append(ip).append(':').append(HttpServer.PORT)
            } else {
                append("Phone address:  (not on Wi-Fi?)")
            }
            append("\n============================\n\n")
            append("App package: ").append(packageName).append('\n')
            append("Fine location perm: ").append(if (fine) "OK" else "MISSING").append('\n')
            append("Engine ready: ").append(if (MockEngine.ready) "YES" else "NO").append('\n')
            if (MockEngine.ready) {
                append("Last fix: ")
                    .append("%.6f, %.6f".format(MockEngine.lastLat, MockEngine.lastLon))
            }
        }
    }
}
