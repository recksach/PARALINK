package com.paralink.app.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import com.paralink.app.connectivity.wifi.P2PNetworkManager
import kotlin.math.*

const val RADAR_GOLD = 0xFFFFD54F.toInt()

private class Placed(
    val node: P2PNetworkManager.RadarNode,
    val pos: Offset,
    val alpha: Float,
    val useGps: Boolean,
    val distM: Double?
)

private fun buildPlaced(
    nodes: List<P2PNetworkManager.RadarNode>,
    myLat: Double,
    myLon: Double,
    heading: Float,
    now: Long,
    size: Size
): List<Placed> {
    if (size.width < 1f || size.height < 1f) return emptyList()
    val c = Offset(size.width / 2f, size.height / 2f)
    val r = size.minDimension / 2f - 6f
    val deadline = maxOf(20000L, nodes.maxOfOrNull { now - it.lastSeen }?.plus(2000) ?: 0L)
    val coordNodes = nodes.filter { it.lat != 0.0 && it.lon != 0.0 && myLat != 0.0 && myLon != 0.0 }
    val maxDistM = coordNodes.map { haversineMeters(myLat, myLon, it.lat, it.lon) }
        .maxOrNull()?.coerceAtLeast(30.0) ?: 0.0
    val lo = log10(0.012)
    val hi = log10(maxDistM / 10.0).coerceAtLeast(lo + 0.1)

    return nodes.sortedByDescending { it.lastSeen }.mapNotNull { n ->
        val age = now - n.lastSeen
        if (age > deadline) return@mapNotNull null
        val alpha = (1f - age.toFloat() / deadline.toFloat()).coerceIn(0f, 1f)
        val useGps = n.lat != 0.0 && n.lon != 0.0 && myLat != 0.0 && myLon != 0.0
        val bearing: Double
        val distFrac: Float
        val distM: Double?
        if (useGps && maxDistM > 0) {
            bearing = bearingCW(myLat, myLon, n.lat, n.lon)
            val d = haversineMeters(myLat, myLon, n.lat, n.lon)
            distM = d
            val t = ((log10(d / 10.0)) - lo) / (hi - lo)
            distFrac = (0.12f + 0.78f * t.toFloat()).coerceIn(0.12f, 0.94f)
        } else {
            val h = (n.id.hashCode() and 0x7fffffff) % 360
            bearing = h.toDouble()
            distFrac = 0.30f + ((n.id.hashCode() and 0x7fffffff) % 900) / 900f * 0.55f
            distM = null
        }
        val screenDeg = (bearing - heading + 360f) % 360f
        val degRad = Math.toRadians(screenDeg.toDouble())
        val pos = Offset(c.x + cos(degRad).toFloat() * r * distFrac, c.y - sin(degRad).toFloat() * r * distFrac)
        Placed(n, pos, alpha, useGps, distM)
    }
}

private fun hitPlaced(placed: List<Placed>, pos: Offset, tolPx: Float): String? {
    var best: String? = null
    var bestD = tolPx
    placed.forEach { p ->
        val d = hypot(p.pos.x - pos.x, p.pos.y - pos.y)
        if (d <= bestD) {
            bestD = d
            best = p.node.id
        }
    }
    return best
}

@Composable
fun MeshRadar(
    nodes: List<P2PNetworkManager.RadarNode>,
    myLat: Double = 0.0,
    myLon: Double = 0.0,
    onNodeTap: (String) -> Unit = {},
    onNodePtt: (String, Boolean) -> Unit = { _, _ -> },
    pttTarget: String? = null
) {
    val infinite = rememberInfiniteTransition(label = "radar")
    val sweepDeg by infinite.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep"
    )
    val pulse by infinite.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse"
    )
    val labelPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.rgb(234, 242, 255)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            textAlign = android.graphics.Paint.Align.CENTER
            setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt())
        }
    }
    val goldPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = RADAR_GOLD
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            textAlign = android.graphics.Paint.Align.CENTER
            setShadowLayer(5f, 0f, 1f, 0xCC000000.toInt())
        }
    }
    val subPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.rgb(120, 144, 170)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL)
            textAlign = android.graphics.Paint.Align.CENTER
            setShadowLayer(3f, 0f, 1f, 0xCC000000.toInt())
        }
    }

    val ctx = LocalContext.current
    var heading by remember { mutableFloatStateOf(0f) }
    var headingValid by remember { mutableStateOf(false) }
    DisposableEffect(ctx) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rot = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rot != null) {
            val r = FloatArray(9)
            val orien = FloatArray(3)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    SensorManager.getRotationMatrixFromVector(r, e.values)
                    SensorManager.getOrientation(r, orien)
                    val raw = ((Math.toDegrees(orien[0].toDouble()).toFloat() + 360f) % 360f)
                    heading = if (headingValid) {
                        var d = raw - heading
                        if (d > 180f) d -= 360f
                        if (d < -180f) d += 360f
                        (heading + d * 0.12f + 360f) % 360f
                    } else {
                        headingValid = true
                        raw
                    }
                }
                override fun onAccuracyChanged(s: Sensor, accuracy: Int) = Unit
            }
            sm.registerListener(listener, rot, SensorManager.SENSOR_DELAY_UI)
            onDispose { sm.unregisterListener(listener) }
        } else {
            onDispose { }
        }
    }

    var radarSize by remember { mutableStateOf(Size.Zero) }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .onSizeChanged { radarSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(nodes, radarSize, myLat, myLon) {
                if (radarSize.width < 1f) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val now = System.currentTimeMillis()
                    val placed = buildPlaced(nodes, myLat, myLon, heading, now, radarSize)
                    val hit = hitPlaced(placed, down.position, radarSize.minDimension * 0.10f)
                    val t0 = now
                    var ptt = false
                    var released = false
                    while (!released) {
                        val ev = awaitPointerEvent(PointerEventPass.Main)
                        if (!ev.changes.any { it.pressed && it.id == down.id }) {
                            released = true
                        } else if (!ptt && hit != null && (System.currentTimeMillis() - t0) >= 600) {
                            ptt = true
                            onNodePtt(hit, true)
                        }
                    }
                    if (ptt) {
                        if (hit != null) onNodePtt(hit, false)
                    } else if (hit != null) {
                        onNodeTap(hit)
                    }
                }
            },
        Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f - 6f
            val now = System.currentTimeMillis()
            val placed = buildPlaced(nodes, myLat, myLon, heading, now, size)
            val latest = placed.firstOrNull()

            val base = Color(0xFF0A1B2E)
            drawCircle(base, r + 10f, c)
            drawCircle(Color(0xFF122B45), r, c)

            listOf(0.25f, 0.5f, 0.75f, 1f).forEach { f ->
                drawCircle(Color(0xFF2C7DB8).copy(alpha = if (f == 1f) 0.85f else 0.45f), r * f, c, style = Stroke(if (f == 1f) 2.2f else 1.2f))
            }
            drawLine(Color(0xFF3EA8E8).copy(alpha = 0.35f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f, cap = StrokeCap.Round)
            drawLine(Color(0xFF3EA8E8).copy(alpha = 0.35f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f, cap = StrokeCap.Round)

            val rad = Math.toRadians(sweepDeg.toDouble())
            val sweepEnd = Offset(c.x + cos(rad).toFloat() * r, c.y + sin(rad).toFloat() * r)
            drawArc(
                Color(0xFF29D9FF).copy(alpha = 0.08f),
                startAngle = sweepDeg - 22f,
                sweepAngle = 44f,
                useCenter = false,
                topLeft = Offset(c.x - r, c.y - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(10f, cap = StrokeCap.Round)
            )
            drawArc(
                Color(0xFF29D9FF).copy(alpha = 0.85f),
                startAngle = sweepDeg - 8f,
                sweepAngle = 16f,
                useCenter = false,
                topLeft = Offset(c.x - r, c.y - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(3.2f, cap = StrokeCap.Round)
            )
            drawLine(Color(0xFF29D9FF).copy(alpha = 0.7f), c, sweepEnd, 2.2f, cap = StrokeCap.Round)

            drawCircle(Color(0xFF2D7DFF).copy(alpha = 0.5f), 46f, c, style = Stroke(1.6f))
            drawCircle(Color(0xFF29D9FF).copy(alpha = 0.14f), 46f + 8f + pulse * 6f, c)

            if (headingValid) {
                val northVis = (360f - heading) % 360f
                val nRad = Math.toRadians(northVis.toDouble())
                val nEnd = Offset(c.x + cos(nRad).toFloat() * r * 0.92f, c.y + sin(nRad).toFloat() * r * 0.92f)
                drawLine(Color(0xFFFF5252).copy(alpha = 0.7f), c, nEnd, 2f, cap = StrokeCap.Round)
                labelPaint.textSize = r * 0.09f
                labelPaint.color = android.graphics.Color.rgb(255, 140, 140)
                drawContext.canvas.nativeCanvas.drawText("N", nEnd.x, nEnd.y - 6f, labelPaint)
                labelPaint.color = android.graphics.Color.rgb(234, 242, 255)
            }

            placed.forEachIndexed { idx, p ->
                val phase = (((idx + 1) * 137.5f + pulse * 360f) % 360f) / 360f
                val echoR = hypot(p.pos.x - c.x, p.pos.y - c.y) * phase
                if (echoR > 4f) {
                    drawCircle(Color(0xFF29D9FF).copy(alpha = (1f - phase) * 0.30f * p.alpha + 0.03f), echoR, c, style = Stroke(1.8f, cap = StrokeCap.Round))
                }

                val ringR = 6f + pulse * 3f
                if (p.node.gold) {
                    drawCircle(Color(0xFFFFD54F).copy(alpha = 0.25f * p.alpha), ringR * 3.2f, p.pos)
                    drawCircle(Color(0xFFFFD54F).copy(alpha = 0.9f * p.alpha), ringR, p.pos)
                    drawCircle(Color(0xFFFFE082).copy(alpha = p.alpha), 2.6f, p.pos)
                } else {
                    drawCircle(Color(0xFF42E8A4).copy(alpha = 0.16f * p.alpha), ringR * 3f, p.pos)
                    drawCircle(Color(0xFF42E8A4).copy(alpha = 0.95f * p.alpha), ringR, p.pos)
                    drawCircle(Color(0xFFEAF2FF).copy(alpha = p.alpha * 0.9f), 2.2f, p.pos)
                }
                if (p.node.id == pttTarget) {
                    drawCircle(Color(0xFFFF5252).copy(alpha = 0.5f + pulse * 0.4f), ringR * 2.4f + pulse * 5f, p.pos, style = Stroke(2.4f))
                }
                val paint = if (p.node.gold) goldPaint else labelPaint
                paint.textSize = r * 0.085f
                val badge = p.node.badge?.takeIf { it.isNotBlank() }?.let { "$it " }.orEmpty()
                drawContext.canvas.nativeCanvas.drawText(badge + p.node.name.take(13), p.pos.x, p.pos.y - ringR - 8f, paint)
                if (p.distM != null) {
                    subPaint.textSize = r * 0.062f
                    drawContext.canvas.nativeCanvas.drawText(shortDist(p.distM), p.pos.x, p.pos.y - ringR - 8f + paint.textSize + 6f, subPaint)
                }
            }

            drawCircle(Color(0xFF29D9FF).copy(alpha = 0.18f), r * 0.10f, c, style = Stroke(1.4f))
            labelPaint.textSize = r * 0.10f
            drawContext.canvas.nativeCanvas.drawText("YOU", c.x, c.y + 4f, labelPaint)

            if (myLat == 0.0 && myLon == 0.0) {
                subPaint.textSize = r * 0.07f
                drawContext.canvas.nativeCanvas.drawText("GPS OFF", c.x, c.y + r * 0.30f, subPaint)
            }
            if (placed.isNotEmpty() && latest != null && latest.distM != null && latest.useGps) {
                subPaint.textSize = r * 0.07f
                drawContext.canvas.nativeCanvas.drawText("NEAREST ${shortDist(latest.distM)}", c.x, c.y + r - 14f, subPaint)
            }
        }
    }
}

private fun shortDist(m: Double): String =
    if (m >= 1000.0) "%.1f km".format(m / 1000.0)
    else "%.0f m".format(m)

private fun bearingCW(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dLon = Math.toRadians(lon2 - lon1)
    val y = sin(dLon) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val er = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2.0 * er * asin(sqrt(a))
}