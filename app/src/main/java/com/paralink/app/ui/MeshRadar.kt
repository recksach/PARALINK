package com.paralink.app.ui

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
import com.paralink.app.connectivity.wifi.P2PNetworkManager
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun MeshRadar(nodes: List<P2PNetworkManager.RadarNode>) {
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

            val radians = Math.toRadians(sweepDeg.toDouble())
            val sweepEnd = Offset(c.x + cos(radians).toFloat() * r, c.y + sin(radians).toFloat() * r)
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

            val deadline = 20000L
            val now = System.currentTimeMillis()
            nodes.forEach { n ->
                val age = now - n.lastSeen
                if (age > deadline) return@forEach
                val alpha = (1f - age.toFloat() / deadline.toFloat()).coerceIn(0f, 1f)
                val h = n.id.hashCode() and 0x7fffffff
                val rad = Math.toRadians((h % 360).toDouble())
                val dist = 0.36f + (h % 900) / 900f * 0.55f
                val pos = Offset(c.x + cos(rad).toFloat() * r * dist, c.y + sin(rad).toFloat() * r * dist)
                val ringR = 5f + pulse * 3f
                drawCircle(Color(0xFF29D9FF).copy(alpha = 0.22f * alpha), ringR * 2.6f, pos)
                drawCircle(Color(0xFF42E8A4).copy(alpha = alpha), ringR, pos)
                drawCircle(Color(0xFFEAF2FF).copy(alpha = alpha * 0.8f), 2f, pos)
                labelPaint.textSize = r * 0.085f
                drawContext.canvas.nativeCanvas.drawText(n.name.take(12), pos.x, pos.y - ringR - 6f, labelPaint)
            }

            labelPaint.textSize = r * 0.09f
            drawContext.canvas.nativeCanvas.drawText("YOU", c.x, c.y + 4f, labelPaint)
        }
    }
}