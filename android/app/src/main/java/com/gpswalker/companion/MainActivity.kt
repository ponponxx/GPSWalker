package com.gpswalker.companion

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var speedInput: EditText
    private lateinit var latInput: EditText
    private lateinit var lonInput: EditText
    private lateinit var targetLatInput: EditText
    private lateinit var targetLonInput: EditText
    private lateinit var routeInput: EditText
    private lateinit var touchMap: TouchMapView
    private var updatingInputs = false

    private val stateListener: (WalkState) -> Unit = { state ->
        refresh(state)
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh(WalkEngine.snapshot()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        speedInput = findViewById(R.id.input_speed)
        latInput = findViewById(R.id.input_lat)
        lonInput = findViewById(R.id.input_lon)
        targetLatInput = findViewById(R.id.input_target_lat)
        targetLonInput = findViewById(R.id.input_target_lon)
        routeInput = findViewById(R.id.input_route)
        touchMap = findViewById(R.id.touch_map)

        findViewById<Button>(R.id.btn_perm).setOnClickListener { requestPermissions() }
        findViewById<Button>(R.id.btn_dev).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }
        findViewById<Button>(R.id.btn_start).setOnClickListener {
            MockService.start(this)
            WalkEngine.start(this)
            toast("Mock service started")
        }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            WalkEngine.shutdown()
            MockService.stop(this)
            refresh(WalkEngine.snapshot())
        }
        findViewById<Button>(R.id.btn_apply_speed).setOnClickListener {
            val speed = readDouble(speedInput, "speed") ?: return@setOnClickListener
            ensureStarted()
            WalkEngine.setSpeed(speed)
        }
        findViewById<Button>(R.id.btn_teleport).setOnClickListener {
            val lat = readDouble(latInput, "latitude") ?: return@setOnClickListener
            val lon = readDouble(lonInput, "longitude") ?: return@setOnClickListener
            ensureStarted()
            WalkEngine.setPosition(lat, lon)
        }
        findViewById<Button>(R.id.btn_walk_target).setOnClickListener {
            val lat = readDouble(targetLatInput, "target latitude") ?: return@setOnClickListener
            val lon = readDouble(targetLonInput, "target longitude") ?: return@setOnClickListener
            applySpeedIfPresent()
            ensureStarted()
            WalkEngine.setTarget(lat, lon)
        }
        findViewById<Button>(R.id.btn_walk_route).setOnClickListener {
            val points = parseRoute(routeInput.text.toString()) ?: return@setOnClickListener
            applySpeedIfPresent()
            ensureStarted()
            WalkEngine.setRoute(points)
        }
        findViewById<Button>(R.id.btn_stop_motion).setOnClickListener {
            WalkEngine.stopMotion()
        }
        touchMap.onPick = { lat, lon ->
            showPointActionSheet(lat, lon)
        }
    }

    override fun onStart() {
        super.onStart()
        WalkEngine.addListener(stateListener)
    }

    override fun onStop() {
        WalkEngine.removeListener(stateListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refresh(WalkEngine.snapshot())
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        permLauncher.launch(perms.toTypedArray())
    }

    private fun ensureStarted() {
        MockService.start(this)
        WalkEngine.start(this)
    }

    private fun applySpeedIfPresent() {
        speedInput.text.toString().toDoubleOrNull()?.let { WalkEngine.setSpeed(it) }
    }

    private fun refresh(state: WalkState) {
        val fine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!updatingInputs && !latInput.hasFocus() && !lonInput.hasFocus() && !speedInput.hasFocus()) {
            updatingInputs = true
            latInput.setText("%.6f".format(state.lat))
            lonInput.setText("%.6f".format(state.lon))
            speedInput.setText("%.1f".format(state.speedKmh))
            updatingInputs = false
        }

        val target = if (state.targetLat != null && state.targetLon != null) {
            "%.6f, %.6f".format(state.targetLat, state.targetLon)
        } else {
            "-"
        }
        val routeProgress = if (state.mode == "walk_route") {
            "${state.routeIndex + 1}/${state.route.size}"
        } else {
            "-"
        }

        status.text = buildString {
            append("Fine location permission: ").append(if (fine) "OK" else "MISSING").append('\n')
            append("Mock engine ready: ").append(if (MockEngine.ready) "YES" else "NO").append('\n')
            append("Mode: ").append(state.mode).append(if (state.running) " (RUN)" else " (idle)").append('\n')
            append("Lat: ").append("%.6f".format(state.lat)).append('\n')
            append("Lon: ").append("%.6f".format(state.lon)).append('\n')
            append("Speed: ").append("%.1f km/h".format(state.speedKmh)).append('\n')
            append("Target: ").append(target).append('\n')
            append("Route: ").append(routeProgress).append('\n')
            append("Remaining: ").append(formatDistance(state.remainingM)).append('\n')
            append("ETA: ").append(formatEta(state.etaS))
        }
        touchMap.setState(state)
    }

    private fun showPointActionSheet(lat: Double, lon: Double) {
        val dialog = Dialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 20)
            setBackgroundColor(Color.WHITE)
        }
        val title = TextView(this).apply {
            text = "Selected: %.6f, %.6f".format(lat, lon)
            textSize = 15f
            setTextColor(Color.rgb(35, 42, 55))
            setPadding(0, 0, 0, 10)
        }
        box.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        box.addView(actionButton("Walk Here") {
            targetLatInput.setText("%.6f".format(lat))
            targetLonInput.setText("%.6f".format(lon))
            applySpeedIfPresent()
            ensureStarted()
            WalkEngine.setTarget(lat, lon)
            dialog.dismiss()
        })
        box.addView(actionButton("Teleport Here") {
            latInput.setText("%.6f".format(lat))
            lonInput.setText("%.6f".format(lon))
            ensureStarted()
            WalkEngine.setPosition(lat, lon)
            dialog.dismiss()
        })
        box.addView(actionButton("Add Waypoint") {
            appendRoutePoint(lat, lon)
            toast("Waypoint added")
            dialog.dismiss()
        })
        box.addView(actionButton("Cancel") {
            dialog.dismiss()
        })

        dialog.setContentView(box)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        dialog.show()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
    }

    private fun actionButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { action() }
        }
    }

    private fun appendRoutePoint(lat: Double, lon: Double) {
        val existing = routeInput.text.toString().trim()
        val point = "%.6f,%.6f".format(lat, lon)
        routeInput.setText(if (existing.isEmpty()) point else "$existing\n$point")
        routeInput.setSelection(routeInput.text.length)
    }

    private fun parseRoute(text: String): List<Pair<Double, Double>>? {
        val points = mutableListOf<Pair<Double, Double>>()
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed
            val parts = line.split(',', ' ', '\t').filter { it.isNotBlank() }
            if (parts.size < 2) {
                toast("Route line ${index + 1} needs lat,lon")
                return null
            }
            val lat = parts[0].toDoubleOrNull()
            val lon = parts[1].toDoubleOrNull()
            if (lat == null || lon == null) {
                toast("Route line ${index + 1} has invalid numbers")
                return null
            }
            points += lat to lon
        }
        if (points.isEmpty()) {
            toast("Add at least one route point")
            return null
        }
        return points
    }

    private fun readDouble(input: EditText, name: String): Double? {
        val value = input.text.toString().trim().toDoubleOrNull()
        if (value == null) toast("Invalid $name")
        return value
    }

    private fun formatDistance(meters: Double): String {
        if (meters <= 0.0) return "-"
        return if (meters >= 1000.0) "%.2f km".format(meters / 1000.0)
        else "${meters.roundToInt()} m"
    }

    private fun formatEta(seconds: Double): String {
        if (seconds <= 0.0) return "-"
        val s = seconds.roundToInt()
        val h = s / 3600
        val m = (s % 3600) / 60
        val r = s % 60
        return when {
            h > 0 -> "${h}h ${m}m ${r}s"
            m > 0 -> "${m}m ${r}s"
            else -> "${r}s"
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
