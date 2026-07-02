package com.gpswalker.companion

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class WalkState(
    var lat: Double = 25.0330,
    var lon: Double = 121.5654,
    var speedKmh: Double = 4.5,
    var running: Boolean = false,
    var targetLat: Double? = null,
    var targetLon: Double? = null,
    var joyX: Double = 0.0,
    var joyY: Double = 0.0,
    var mode: String = "idle",
    var route: MutableList<Pair<Double, Double>> = mutableListOf(),
    var routeIndex: Int = 0,
    var remainingM: Double = 0.0,
    var etaS: Double = 0.0,
)

object WalkEngine {
    private const val EARTH_R = 6_371_000.0
    private const val TICK_HZ = 5L

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val gaussian = java.util.Random()
    private val listeners = mutableSetOf<(WalkState) -> Unit>()
    private var executor: ScheduledExecutorService? = null
    private var task: ScheduledFuture<*>? = null
    private var appContext: Context? = null
    private var state = WalkState()
    private var lastBearingDeg = 0f

    fun start(ctx: Context) {
        appContext = ctx.applicationContext
        MockEngine.init(ctx.applicationContext)
        if (executor == null) {
            executor = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "GPSWalker-WalkEngine").apply { isDaemon = true }
            }
        }
        if (task?.isDone == false) return
        val dt = 1.0 / TICK_HZ.toDouble()
        task = executor!!.scheduleAtFixedRate({
            tick(dt)
        }, 0, 1000L / TICK_HZ, TimeUnit.MILLISECONDS)
        publish()
    }

    fun addListener(listener: (WalkState) -> Unit) {
        synchronized(lock) { listeners += listener }
        listener(snapshot())
    }

    fun removeListener(listener: (WalkState) -> Unit) {
        synchronized(lock) { listeners -= listener }
    }

    fun snapshot(): WalkState = synchronized(lock) {
        state.copy(route = state.route.toMutableList())
    }

    fun setPosition(lat: Double, lon: Double) {
        synchronized(lock) {
            state.lat = lat
            state.lon = lon
        }
        pushNow()
        publish()
    }

    fun setSpeed(kmh: Double) {
        synchronized(lock) {
            state.speedKmh = kmh.coerceIn(0.1, 9999.0)
        }
        publish()
    }

    fun setTarget(lat: Double, lon: Double) {
        synchronized(lock) {
            state.targetLat = lat
            state.targetLon = lon
            state.route.clear()
            state.routeIndex = 0
            state.mode = "walk_to"
            state.running = true
        }
        publish()
    }

    fun setRoute(points: List<Pair<Double, Double>>) {
        synchronized(lock) {
            state.route = points.toMutableList()
            state.routeIndex = 0
            state.targetLat = null
            state.targetLon = null
            state.mode = if (points.isEmpty()) "idle" else "walk_route"
            state.running = points.isNotEmpty()
        }
        publish()
    }

    fun setJoystick(x: Double, y: Double) {
        synchronized(lock) {
            state.joyX = x.coerceIn(-1.0, 1.0)
            state.joyY = y.coerceIn(-1.0, 1.0)
            val mag = hypot(state.joyX, state.joyY)
            if (mag > 0.05) {
                state.mode = "joystick"
                state.running = true
                state.targetLat = null
                state.targetLon = null
                state.route.clear()
                state.routeIndex = 0
            } else if (state.mode == "joystick") {
                state.running = false
                state.mode = "idle"
            }
        }
        publish()
    }

    fun stopMotion() {
        synchronized(lock) {
            state.running = false
            state.mode = "idle"
            state.targetLat = null
            state.targetLon = null
            state.route.clear()
            state.routeIndex = 0
            state.joyX = 0.0
            state.joyY = 0.0
        }
        publish()
    }

    fun shutdown() {
        stopMotion()
        task?.cancel(true)
        task = null
        executor?.shutdownNow()
        executor = null
    }

    private fun tick(dt: Double) {
        val ctx = appContext ?: return
        var lat: Double
        var lon: Double
        var speedMps: Float
        synchronized(lock) {
            stepLocked(dt)
            updateDerivedLocked()
            lat = state.lat
            lon = state.lon
            speedMps = max(0.0, state.speedKmh / 3.6).toFloat()
        }
        MockEngine.push(ctx, lat, lon, speedMps = speedMps, bearingDeg = lastBearingDeg)
        publish()
    }

    private fun pushNow() {
        val ctx = appContext ?: return
        val s = snapshot()
        MockEngine.push(ctx, s.lat, s.lon, speedMps = (s.speedKmh / 3.6).toFloat(), bearingDeg = lastBearingDeg)
    }

    private fun stepLocked(dt: Double) {
        val s = state
        if (!s.running) return

        var stepM = max(0.0, s.speedKmh / 3.6 * Random.nextDouble(0.92, 1.08)) * dt
        val dn: Double
        val de: Double

        when (s.mode) {
            "walk_to" -> {
                val tLat = s.targetLat ?: return
                val tLon = s.targetLon ?: return
                val dist = haversineM(s.lat, s.lon, tLat, tLon)
                if (dist <= max(stepM, 1.5)) {
                    s.lat = tLat
                    s.lon = tLon
                    s.targetLat = null
                    s.targetLon = null
                    s.running = false
                    s.mode = "idle"
                    return
                }
                var br = bearingTo(s.lat, s.lon, tLat, tLon)
                br += Math.toRadians(Random.nextDouble(-6.0, 6.0))
                lastBearingDeg = normalizeDegrees(Math.toDegrees(br)).toFloat()
                dn = cos(br) * stepM
                de = sin(br) * stepM
            }
            "walk_route" -> {
                if (s.routeIndex >= s.route.size) {
                    s.route.clear()
                    s.routeIndex = 0
                    s.running = false
                    s.mode = "idle"
                    return
                }
                val (tLat, tLon) = s.route[s.routeIndex]
                val dist = haversineM(s.lat, s.lon, tLat, tLon)
                if (dist <= max(stepM, 1.5)) {
                    s.lat = tLat
                    s.lon = tLon
                    s.routeIndex += 1
                    if (s.routeIndex >= s.route.size) {
                        s.route.clear()
                        s.routeIndex = 0
                        s.running = false
                        s.mode = "idle"
                    }
                    return
                }
                var br = bearingTo(s.lat, s.lon, tLat, tLon)
                br += Math.toRadians(Random.nextDouble(-6.0, 6.0))
                lastBearingDeg = normalizeDegrees(Math.toDegrees(br)).toFloat()
                dn = cos(br) * stepM
                de = sin(br) * stepM
            }
            "joystick" -> {
                val mag = hypot(s.joyX, s.joyY)
                if (mag < 0.05) return
                stepM *= mag
                var br = atan2(s.joyX / mag, s.joyY / mag)
                br += Math.toRadians(Random.nextDouble(-4.0, 4.0))
                lastBearingDeg = normalizeDegrees(Math.toDegrees(br)).toFloat()
                dn = cos(br) * stepM
                de = sin(br) * stepM
            }
            else -> return
        }

        val jitterN = gaussian.nextGaussian() * 0.4
        val jitterE = gaussian.nextGaussian() * 0.4
        val (newLat, newLon) = offsetMeters(s.lat, s.lon, dn + jitterN, de + jitterE)
        s.lat = newLat
        s.lon = newLon
    }

    private fun updateDerivedLocked() {
        val remaining = remainingMLocked()
        state.remainingM = remaining
        val speedMps = max(0.01, state.speedKmh / 3.6)
        state.etaS = if (remaining > 0.0 && state.running) remaining / speedMps else 0.0
    }

    private fun remainingMLocked(): Double {
        val s = state
        if (s.mode == "walk_to" && s.targetLat != null && s.targetLon != null) {
            return haversineM(s.lat, s.lon, s.targetLat!!, s.targetLon!!)
        }
        if (s.mode == "walk_route" && s.routeIndex < s.route.size) {
            var total = 0.0
            var fromLat = s.lat
            var fromLon = s.lon
            for (i in s.routeIndex until s.route.size) {
                val (toLat, toLon) = s.route[i]
                total += haversineM(fromLat, fromLon, toLat, toLon)
                fromLat = toLat
                fromLon = toLon
            }
            return total
        }
        return 0.0
    }

    private fun publish() {
        val snap = snapshot()
        val copy = synchronized(lock) { listeners.toList() }
        main.post {
            copy.forEach { it(snap) }
        }
    }

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_R * asin(sqrt(a))
    }

    private fun offsetMeters(lat: Double, lon: Double, dn: Double, de: Double): Pair<Double, Double> {
        val dLat = dn / EARTH_R
        val dLon = de / (EARTH_R * cos(Math.toRadians(lat)))
        return lat + Math.toDegrees(dLat) to lon + Math.toDegrees(dLon)
    }

    private fun bearingTo(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return atan2(y, x)
    }

    private fun normalizeDegrees(deg: Double): Double {
        val rounded = deg.roundToInt().toDouble()
        return ((rounded % 360.0) + 360.0) % 360.0
    }
}
