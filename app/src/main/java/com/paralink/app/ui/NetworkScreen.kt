package com.paralink.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paralink.app.R
import com.paralink.app.connectivity.wifi.WifiCapabilities

@Composable
fun NetworkScreen(
    nodeId: String,
    name: String,
    peers: List<android.net.wifi.p2p.WifiP2pDevice>,
    knownNodes: List<String>,
    caps: WifiCapabilities,
    connected: Boolean,
    error: String?,
    refresh: () -> Unit,
    connect: (android.net.wifi.p2p.WifiP2pDevice) -> Unit,
    connectIp: (String) -> Unit = {}
) {
    var ipInput by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column {
                Text("PARALINK", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.autonomous_network), fontSize = 11.sp, color = Color(0xFF75B6FF), letterSpacing = 2.sp)
            }
            IconButton(onClick = refresh) { Icon(Icons.Default.Refresh, null) }
        }
        Spacer(Modifier.height(12.dp)); Radar((knownNodes.size + if (connected) 1 else 0).coerceAtLeast(peers.size))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(if (connected) stringResource(R.string.connected) else stringResource(R.string.local_search), if (connected) Color(0xFF42E8A4) else Color(0xFF29D9FF))
            StatusPill(stringResource(R.string.node_count, knownNodes.size + peers.size), Color(0xFF2D7DFF))
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1220))) {
            Column(Modifier.padding(14.dp)) {
                Text(stringResource(R.string.node_label), fontSize = 11.sp, color = Color(0xFF6F9BCC))
                Text("$name • $nodeId", fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.wifi_direct_label) + " ${caps.summary()["wifiDirect"] == true}")
                Text(stringResource(R.string.wifi_aware_label) + " ${caps.summary()["wifiAware"] == true}")
                if (error != null) Text(error, color = Color(0xFFFF6B6B), fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.nearby_devices), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.height(220.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(peers) { d ->
                PeerCard(d) { connect(d) }
            }
        }
        if (peers.isEmpty()) Text(
            stringResource(R.string.nearby_hint),
            color = Color(0xFF8092AB),
            modifier = Modifier.padding(top = 10.dp)
        )
        Spacer(Modifier.height(14.dp))
        if (knownNodes.isNotEmpty()) {
            Text(stringResource(R.string.mesh_nodes), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.height(160.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(knownNodes) { id ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1220))) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF11304F)), Alignment.Center) {
                                Text("⬢", color = Color(0xFF42E8A4), fontSize = 16.sp)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(id, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1220))) {
            Column(Modifier.padding(14.dp)) {
                Text(stringResource(R.string.connect_ip), fontSize = 11.sp, color = Color(0xFF6F9BCC))
                OutlinedTextField(
                    value = ipInput,
                    onValueChange = { ipInput = it },
                    label = { Text(stringResource(R.string.connect_ip_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (ipInput.isNotBlank()) {
                            connectIp(ipInput.trim())
                            ipInput = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.manual_connect)) }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Radar(count: Int) {
    Box(Modifier.fillMaxWidth().height(260.dp), Alignment.Center) {
        Canvas(Modifier.size(240.dp)) {
            val c = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
            val r = size.minDimension / 2.2f
            listOf(0.25f, 0.5f, 0.75f, 1f).forEach { drawCircle(Color(0xFF20507A).copy(0.45f), r * it, c, style = Stroke(1.4f)) }
            drawLine(Color(0xFF2D7DFF).copy(0.7f), c, androidx.compose.ui.geometry.Offset(c.x + r, c.y - r * 0.2f), 2f)
            repeat(minOf(count, 8)) { i ->
                val a = Math.toRadians(i * 45.0); val rr = r * (0.35 + (i % 3) * 0.18)
                drawCircle(Color(0xFF29D9FF), 6f, androidx.compose.ui.geometry.Offset(c.x + (kotlin.math.cos(a) * rr).toFloat(), c.y + (kotlin.math.sin(a) * rr).toFloat()))
            }
        }
        Box(
            Modifier.size(66.dp).clip(CircleShape).background(Color(0xFF10233E))
                .border(2.dp, Color(0xFF29D9FF), CircleShape),
            Alignment.Center
        ) { Text(stringResource(R.string.you), fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun PeerCard(d: android.net.wifi.p2p.WifiP2pDevice, connect: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = connect), colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1220))) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF17345E)), Alignment.Center) {
                Text("◉", color = Color(0xFF29D9FF), fontSize = 20.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.deviceName.ifBlank { "PARALINK NODE" }, fontWeight = FontWeight.SemiBold)
                Text(d.deviceAddress, fontSize = 11.sp, color = Color(0xFF7890AA))
            }
            Text(stringResource(R.string.connect), fontSize = 11.sp, color = Color(0xFF29D9FF), fontWeight = FontWeight.Bold)
        }
    }
}