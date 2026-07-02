package com.gpswalker.companion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.min

class TouchMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(210, 215, 222)
        strokeWidth = 1f
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 157, 170)
        strokeWidth = 2f
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(243, 156, 18)
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(58, 122, 254)
        style = Paint.Style.FILL
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(192, 57, 43)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(55, 62, 75)
        textSize = 28f
    }

    private var state = WalkState()
    private var selected: Pair<Double, Double>? = null
    var onPick: ((Double, Double) -> Unit)? = null

    fun setState(newState: WalkState) {
        state = newState
        invalidate()
    }

    fun setSelected(lat: Double, lon: Double) {
        selected = lat to lon
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(245, 247, 250))

        val cx = width / 2f
        val cy = height / 2f
        val step = min(width, height) / 6f
        var x = cx % step
        while (x < width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            x += step
        }
        var y = cy % step
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            y += step
        }
        canvas.drawLine(cx, 0f, cx, height.toFloat(), axisPaint)
        canvas.drawLine(0f, cy, width.toFloat(), cy, axisPaint)

        drawRoute(canvas)
        canvas.drawCircle(cx, cy, 13f, currentPaint)
        canvas.drawText("Tap a point, then choose an action", 18f, 34f, textPaint)

        selected?.let {
            val p = toPoint(it.first, it.second)
            canvas.drawCircle(p.first, p.second, 12f, selectedPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        val latLon = toLatLon(event.x, event.y)
        setSelected(latLon.first, latLon.second)
        onPick?.invoke(latLon.first, latLon.second)
        return true
    }

    private fun drawRoute(canvas: Canvas) {
        val points = state.route
        if (points.isEmpty()) return
        var previous = toPoint(state.lat, state.lon)
        for (i in state.routeIndex until points.size) {
            val next = toPoint(points[i].first, points[i].second)
            canvas.drawLine(previous.first, previous.second, next.first, next.second, routePaint)
            canvas.drawCircle(next.first, next.second, 8f, routePaint)
            previous = next
        }
    }

    private fun toLatLon(x: Float, y: Float): Pair<Double, Double> {
        val metersPerPx = metersPerPx()
        val dn = (height / 2f - y) * metersPerPx
        val de = (x - width / 2f) * metersPerPx
        val dLat = dn / EARTH_R
        val dLon = de / (EARTH_R * cos(Math.toRadians(state.lat)))
        return state.lat + Math.toDegrees(dLat) to state.lon + Math.toDegrees(dLon)
    }

    private fun toPoint(lat: Double, lon: Double): Pair<Float, Float> {
        val metersPerPx = metersPerPx()
        val dn = Math.toRadians(lat - state.lat) * EARTH_R
        val de = Math.toRadians(lon - state.lon) * EARTH_R * cos(Math.toRadians(state.lat))
        return (width / 2f + de / metersPerPx).toFloat() to (height / 2f - dn / metersPerPx).toFloat()
    }

    private fun metersPerPx(): Double {
        val spanM = 300.0
        return spanM / min(width.coerceAtLeast(1), height.coerceAtLeast(1)).toDouble()
    }

    companion object {
        private const val EARTH_R = 6_371_000.0
    }
}
