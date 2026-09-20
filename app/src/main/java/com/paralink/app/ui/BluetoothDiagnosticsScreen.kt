package com.paralink.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paralink.app.connectivity.bluetooth.LinkState
import com.paralink.app.connectivity.bluetooth.NearbyDevice

/**
 * Settings -> Advanced -> Bluetooth Diagnostics.
 * Shows the live link state machine, radio flags, per-peer RSSI/MTU and the
 * ring buffer of raw wire events so a broken link is actually debuggable.
 */
@Composable
fun BluetoothDiagnosticsScreen(
    devices: List<NearbyDevice>,
    diagnostics: String,
    onRefresh: () -> Unit = {},
    onConnect: (String) -> Unit = {},
    onClose: () -> Unit = {}
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF050913))
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "back")
            }
            Text(
                "Bluetooth Diagnostics",
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "refresh") }
        }
        Spacer(Modifier.height(6.dp))

        if (devices.isEmpty()) {
            Text(
                "No BLE peers in range yet. Keep both devices on the same screen.",
                fontSize = 12.sp, color = Color(0xFF7890AA)
            )
        } else {
            Text("Nearby BLE devices (${devices.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(devices) { d ->
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1220))
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            StateDot(d.connectionState)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(d.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(d.address, fontSize = 10.sp, color = Color(0xFF7890AA))
                                Text(
                                    "${d.connectionState}  RSSI ${d.rssi} dBm  MTU ${d.mtu.takeIf { it > 0 } ?: "-"}  v${d.protocolVersion.takeIf { it > 0 } ?: "?"}",
                                    fontSize = 10.sp,
                                    color = Color(0xFF88A3C8)
                                )
                            }
                            if (d.connectionState == LinkState.DISCOVERING ||
                                d.connectionState == LinkState.IDLE ||
                                d.connectionState == LinkState.DISCONNECTED ||
                                d.connectionState == LinkState.ERROR
                            ) {
                                TextButton(onClick = { onConnect(d.address) }) {
                                    Text("Connect", color = Color(0xFF29D9FF))
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        if (diagnostics.isNotBlank()) {
            Text("Event log", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Card(
                Modifier.fillMaxWidth().heightIn(max = 320.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1220))
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp)
                ) {
                    diagnostics.lines().reversed().take(200).forEach { line ->
                        Text(line, fontSize = 10.sp, color = Color(0xFFAED9FF), fontFamily = FontFamily.Monospace)
                    }
                }
            }
        } else {
            Text("No diagnostics recorded yet.", fontSize = 12.sp, color = Color(0xFF7890AA))
        }
    }
}

@Composable
private fun StateDot(state: LinkState) {
    val color = when (state) {
        LinkState.CONNECTED -> Color(0xFF42E8A4)
        LinkState.SECURE_SESSION, LinkState.KEY_EXCHANGE, LinkState.TRANSFERRING, LinkState.CALLING -> Color(0xFF29D9FF)
        LinkState.HANDSHAKING, LinkState.AUTHENTICATING, LinkState.DISCOVERED, LinkState.CONNECTING -> Color(0xFFFFD54F)
        LinkState.ERROR -> Color(0xFFFF6B6B)
        else -> Color(0xFF51627A)
    }
    Box(Modifier.size(12.dp).background(color, RoundedCornerShape(6.dp)))
}