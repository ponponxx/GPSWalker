package com.gpswalker.companion

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD

/**
 * Tiny HTTP server so the PC can push locations over Wi-Fi without adb.
 *
 * Endpoints:
 *   GET /ping                     -> "pong"
 *   GET /set?lat=..&lon=..        -> injects a mock location
 *        optional: &acc=&spd=&brg=
 */
class HttpServer(
    private val ctx: Context,
    port: Int = PORT,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.uri) {
                "/ping" -> ok("pong")
                "/set" -> {
                    val p = session.parameters
                    val lat = p["lat"]?.firstOrNull()?.toDoubleOrNull()
                    val lon = p["lon"]?.firstOrNull()?.toDoubleOrNull()
                    if (lat == null || lon == null) return err("missing lat/lon")
                    val acc = p["acc"]?.firstOrNull()?.toFloatOrNull() ?: 1.5f
                    val spd = p["spd"]?.firstOrNull()?.toFloatOrNull() ?: 1.2f
                    val brg = p["brg"]?.firstOrNull()?.toFloatOrNull() ?: 0f
                    MockEngine.push(ctx.applicationContext, lat, lon, acc, spd, brg)
                    ok("ok %.6f,%.6f".format(lat, lon))
                }
                else -> err("unknown endpoint: ${session.uri}")
            }
        } catch (e: Exception) {
            Log.e("GPSWalker", "http serve error", e)
            err("error: ${e.message}")
        }
    }

    private fun ok(msg: String) =
        newFixedLengthResponse(Response.Status.OK, "text/plain", msg)

    private fun err(msg: String) =
        newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", msg)

    companion object {
        const val PORT = 8080
    }
}
