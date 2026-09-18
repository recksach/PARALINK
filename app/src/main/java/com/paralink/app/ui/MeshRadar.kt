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
import androidx.compose.ui.platform.LocalContext
import com.paralink.app.connectivity.wifi.P2PNetworkManager
import kotlin.math.*

const val RADAR_GOLD = 0xFFFFD54F.toInt()

@Composable
fun MeshRadar(nodes: List<P2PNetworkManager.RadarNode>, myLat: Double = 0.0, myLon: Double = 0.0) {
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
        }
    }
    val goldPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = RADAR_GOLD
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            textAlign = android.graphics.Paint.Align.CENTER
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

    Box(Modifier.fillMaxWidth().aspectRatio(1f), Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f - 6f
            val ring = Color(0xFF20507A)
            listOf(0.3f, 0.6f, 0.9f, 1f).forEach {
                drawCircle(ring.copy(alpha = 0.5f), r * it, c, style = Stroke(1.4f))
            }
            drawLine(Color(0xFF2D7DFF).copy(alpha = 0.35f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.2f, cap = StrokeCap.Round)
            drawLine(Color(0xFF2D7DFF).copy(alpha = 0.35f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.2f, cap = StrokeCap.Round)

            val rad = Math.toRadians(sweepDeg.toDouble())
            val sweepEnd = Offset(c.x + cos(rad).toFloat() * r, c.y + sin(rad).toFloat() * r)
            drawArc(
                Color(0xFF29D9FF).copy(alpha = 0.12f),
                startAngle = sweepDeg - 14f,
                sweepAngle = 28f,
                useCenter = false,
                topLeft = Offset(c.x - r, c.y - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(3f)
            )
            drawLine(Color(0xFF29D9FF).copy(alpha = 0.65f), c, sweepEnd, 2f, cap = StrokeCap.Round)
            drawCircle(Color(0xFF2D7DFF).copy(alpha = 0.55f), 36f, c, style = Stroke(1.6f))

            if (headingValid) {
                val northVis = (360f - heading) % 360f
                val nRad = Math.toRadians(northVis.toDouble())
                val nEnd = Offset(c.x + cos(nRad).toFloat() * r * 0.9f, c.y + sin(nRad).toFloat() * r * 0.9f)
                drawLine(Color(0xFFFF5252).copy(alpha = 0.6f), c, nEnd, 1.6f, cap = StrokeCap.Round)
                labelPaint.textSize = r * 0.09f
                labelPaint.color = android.graphics.Color.rgb(255, 150, 150)
                drawContext.canvas.nativeCanvas.drawText("N", nEnd.x, nEnd.y - 4f, labelPaint)
                labelPaint.color = android.graphics.Color.rgb(234, 242, 255)
            }

            val now = System.currentTimeMillis()
            val deadline = maxOf(20000L, nodes.maxOfOrNull { now - it.lastSeen }?.plus(2000) ?: 0L)
            val coordNodes = nodes.filter { it.lat != 0.0 && it.lon != 0.0 && myLat != 0.0 && myLon != 0.0 }
            val maxDistM = coordNodes.map { haversineMeters(myLat, myLon, it.lat, it.lon) }
                .maxOrNull()?.coerceAtLeast(30.0) ?: 0.0

            nodes.sortedByDescending { it.lastSeen }.forEachIndexed { idx, n ->
                val age = now - n.lastSeen
                if (age > deadline) return@forEachIndexed
                val alpha = (1f - age.toFloat() / deadline.toFloat()).coerceIn(0f, 1f)

                val useGps = n.lat != 0.0 && n.lon != 0.0 && myLat != 0.0 && myLon != 0.0
                val bearing: Double
                val distFrac: Float
                if (useGps && maxDistM > 0) {
                    bearing = bearingCW(myLat, myLon, n.lat, n.lon)
                    val d = haversineMeters(myLat, myLon, n.lat, n.lon)
                    val lo = log10(0.012)
                    val hi = log10(maxDistM / 10.0).coerceAtLeast(lo + 0.1)
                    val t = ((log10(d / 10.0)) - lo) / (hi - lo)
                    distFrac = (0.12f + 0.78f * t.toFloat()).coerceIn(0.12f, 0.94f)
                } else {
                    val h = (n.id.hashCode() and 0x7fffffff) % 360
                    bearing = h.toDouble()
                    distFrac = 0.30f + ((n.id.hashCode() and 0x7fffffff) % 900) / 900f * 0.55f
                }
                val screenDeg = (bearing - heading + 360f) % 360f
                val degRad = Math.toRadians(screenDeg.toDouble())
                val pos = Offset(c.x + cos(degRad).toFloat() * r * distFrac, c.y - sin(degRad).toFloat() * r * distFrac)

                val phase = (((idx + 1) * 137.5f + pulse * 360f) % 360f) / 360f
                drawCircle(Color(0xFF29D9FF).copy(alpha = (1f - phase) * 0.30f * alpha + 0.03f), r * distFrac * phase, c, style = Stroke(1.6f))

                val ringR = 5f + pulse * 3f
                drawCircle(Color(0xFF29D9FF).copy(alpha = 0.22f * alpha), ringR * 2.6f, pos)
                drawCircle(Color(0xFF42E8A4).copy(alpha = alpha), ringR, pos)
                drawCircle(Color(0xFFEAF2FF).copy(alpha = alpha * 0.8f), 2f, pos)
                val paint = if (n.gold) goldPaint else labelPaint
                paint.textSize = r * 0.085f
                val badge = n.badge?.takeIf { it.isNotBlank() }?.let { "$it " }.orEmpty()
                drawContext.canvas.nativeCanvas.drawText(badge + n.name.take(11), pos.x, pos.y - ringR - 6f, paint)
            }

            labelPaint.textSize = r * 0.09f
            drawContext.canvas.nativeCanvas.drawText("YOU", c.x, c.y + 4f, labelPaint)
        }
    }
}

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