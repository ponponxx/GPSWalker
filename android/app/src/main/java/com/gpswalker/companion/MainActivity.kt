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
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var status: TextView
    private lateinit var selectedPoint: TextView

    private var selected: GeoPoint? = null
    private var currentMarker: Marker? = null
    private var selectedMarker: Marker? = null
    private var routeLine: Polyline? = null
    private val routePoints = mutableListOf<GeoPoint>()
    private val routeMarkers = mutableListOf<Marker>()
    private var speedKmh = 4.5

    private val stateListener: (WalkState) -> Unit = { state ->
        refresh(state)
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh(WalkEngine.snapshot()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        map = findViewById(R.id.map)
        status = findViewById(R.id.status)
        selectedPoint = findViewById(R.id.selected_point)

        setupMap()

        findViewById<Button>(R.id.btn_settings).setOnClickListener { showSettingsDialog() }
        findViewById<Button>(R.id.btn_move_selected).setOnClickListener { moveToSelected() }
        findViewById<Button>(R.id.btn_fly_selected).setOnClickListener { flyToSelected() }
        findViewById<Button>(R.id.btn_add_waypoint).setOnClickListener { addWaypoint() }
        findViewById<Button>(R.id.btn_start_route).setOnClickListener { startRoute() }
        findViewById<Button>(R.id.btn_stop_clear).setOnClickListener { stopAndClear() }
    }

    override fun onStart() {
        super.onStart()
        WalkEngine.addListener(stateListener)
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        refresh(WalkEngine.snapshot())
    }

    override fun onPause() {
        map.onPause()
        super.onPause()
    }

    override fun onStop() {
        WalkEngine.removeListener(stateListener)
        super.onStop()
    }

    private fun setupMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.minZoomLevel = 3.0
        map.maxZoomLevel = 20.0
        map.controller.setZoom(17.0)
        map.controller.setCenter(GeoPoint(25.0330, 121.5654))
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                selectPoint(p)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean {
                selectPoint(p)
                return true
            }
        }))
    }

    private fun selectPoint(point: GeoPoint) {
        selected = point
        selectedPoint.text = "選取 %.6f, %.6f".format(point.latitude, point.longitude)
        if (selectedMarker == null) {
            selectedMarker = Marker(map).apply {
                title = "Selected"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                map.overlays.add(this)
            }
        }
        selectedMarker?.position = point
        map.invalidate()
    }

    private fun moveToSelected() {
        val point = requireSelected() ?: return
        applySpeed()
        ensureStarted()
        WalkEngine.setTarget(point.latitude, point.longitude)
    }

    private fun flyToSelected() {
        val point = requireSelected() ?: return
        ensureStarted()
        WalkEngine.setPosition(point.latitude, point.longitude)
        map.controller.animateTo(point)
    }

    private fun addWaypoint() {
        val point = requireSelected() ?: return
        routePoints += point
        val marker = Marker(map).apply {
            position = point
            title = "Waypoint ${routePoints.size}"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        routeMarkers += marker
        map.overlays.add(marker)
        redrawRoute()
        toast("Waypoint ${routePoints.size} added")
    }

    private fun startRoute() {
        if (routePoints.isEmpty()) {
            toast("Add at least one waypoint")
            return
        }
        applySpeed()
        ensureStarted()
        WalkEngine.setRoute(routePoints.map { it.latitude to it.longitude })
    }

    private fun stopAndClear() {
        WalkEngine.stopMotion()
        clearRouteOverlays()
        selected = null
        selectedPoint.text = "點擊地圖選擇位置"
        selectedMarker?.let { map.overlays.remove(it) }
        selectedMarker = null
        map.invalidate()
    }

    private fun showSettingsDialog() {
        val dialog = Dialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 22, 28, 28)
            setBackgroundColor(Color.WHITE)
        }
        val title = TextView(this).apply {
            text = "設定"
            textSize = 20f
            setTextColor(Color.rgb(35, 42, 55))
            setPadding(0, 0, 0, 12)
        }
        val speedInput = EditText(this).apply {
            hint = "Speed km/h"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("%.1f".format(speedKmh))
            setSelectAllOnFocus(true)
        }
        box.addView(title)
        box.addView(settingButton("定位權限") { requestPermissions() })
        box.addView(settingButton("Mock 位置設定") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        })
        box.addView(settingButton("啟動 Mock 服務") {
            ensureStarted()
            toast("Mock service started")
        })
        box.addView(settingButton("關閉 Mock 服務") {
            WalkEngine.shutdown()
            MockService.stop(this)
            refresh(WalkEngine.snapshot())
        })
        box.addView(speedInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        box.addView(settingButton("套用速度") {
            val speed = speedInput.text.toString().toDoubleOrNull()
            if (speed == null) {
                toast("Invalid speed")
            } else {
                speedKmh = speed.coerceIn(0.1, 9999.0)
                WalkEngine.setSpeed(speedKmh)
                toast("Speed %.1f km/h".format(speedKmh))
            }
        })
        box.addView(settingButton("關閉") { dialog.dismiss() })

        dialog.setContentView(box)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.TOP or Gravity.END)
        }
    }

    private fun settingButton(label: String, action: () -> Unit): Button {
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
        WalkEngine.setSpeed(speedKmh)
    }

    private fun applySpeed() {
        WalkEngine.setSpeed(speedKmh)
    }

    private fun refresh(state: WalkState) {
        speedKmh = state.speedKmh
        val fine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val current = GeoPoint(state.lat, state.lon)
        if (currentMarker == null) {
            currentMarker = Marker(map).apply {
                title = "Current"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                map.overlays.add(this)
                map.controller.setCenter(current)
            }
        }
        currentMarker?.position = current

        status.text = buildString {
            append(if (fine) "Perm OK" else "Perm missing").append('\n')
            append(if (MockEngine.ready) "Mock ready" else "Mock off").append('\n')
            append(state.mode).append(if (state.running) " RUN" else " idle").append('\n')
            append("%.6f, %.6f".format(state.lat, state.lon)).append('\n')
            append("%.1f km/h".format(state.speedKmh)).append('\n')
            append("Remain ").append(formatDistance(state.remainingM))
        }
        map.invalidate()
    }

    private fun redrawRoute() {
        routeLine?.let { map.overlays.remove(it) }
        routeLine = null
        if (routePoints.isNotEmpty()) {
            routeLine = Polyline().apply {
                outlinePaint.color = Color.rgb(243, 156, 18)
                outlinePaint.strokeWidth = 6f
                setPoints(routePoints)
            }
            map.overlays.add(routeLine)
        }
        map.invalidate()
    }

    private fun clearRouteOverlays() {
        routeMarkers.forEach { map.overlays.remove(it) }
        routeMarkers.clear()
        routePoints.clear()
        routeLine?.let { map.overlays.remove(it) }
        routeLine = null
    }

    private fun requireSelected(): GeoPoint? {
        val point = selected
        if (point == null) toast("Tap map to select a point")
        return point
    }

    private fun formatDistance(meters: Double): String {
        if (meters <= 0.0) return "-"
        return if (meters >= 1000.0) "%.2f km".format(meters / 1000.0)
        else "${meters.roundToInt()} m"
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
