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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paralink.app.R
import com.paralink.app.connectivity.wifi.P2PNetworkManager
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
    connectIp: (String) -> Unit = {},
    radarNodes: List<P2PNetworkManager.RadarNode> = emptyList(),
    autoPair: Boolean = true,
    onAutoPair: (Boolean) -> Unit = {},
    ownNetwork: String? = null,
    onCreateNetwork: () -> Unit = {},
    onStopNetwork: () -> Unit = {},
    joinStatus: String? = null,
    onJoinNetwork: (String, String) -> Unit = { _, _ -> },
    myLat: Double = 0.0,
    myLon: Double = 0.0,
    mineBadge: String? = null,
    mineGold: Boolean = false,
    onRadarTap: (String) -> Unit = {},
    onRadarPtt: (String, Boolean) -> Unit = { _, _ -> },
    pttTarget: String? = null,
    btDevices: List<Pair<String, String>> = emptyList(),
    onBluetoothScan: () -> Unit = {},
    onBluetoothConnect: (String) -> Unit = {}
) {
    var ipInput by remember { mutableStateOf("") }
    var joinSsid by remember { mutableStateOf("") }
    var joinPass by remember { mutableStateOf("") }
    val liveCount = radarNodes.count { System.currentTimeMillis() - it.lastSeen < 10000 }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column {
                Text("PARALINK", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.autonomous_network), fontSize = 11.sp, color = Color(0xFF75B6FF), letterSpacing = 2.sp)
            }
            IconButton(onClick = refresh) { Icon(Icons.Default.Refresh, null) }
        }
        Spacer(Modifier.height(8.dp))
        MeshRadar(radarNodes, myLat, myLon, onNodeTap = onRadarTap, onNodePtt = onRadarPtt, pttTarget = pttTarget)
        if (pttTarget != null) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFF5252)))
                Spacer(Modifier.width(8.dp))
                val pttName = radarNodes.firstOrNull { it.id == pttTarget }?.name
                Text(
                    "● REC → ${pttName.orEmpty()}",
                    color = Color(0xFFFF6B6B),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(
                if (liveCount > 0 || connected) stringResource(R.string.connected) else stringResource(R.string.local_search),
                if (liveCount > 0 || connected) Color(0xFF42E8A4) else Color(0xFF29D9FF)
            )
            Spacer(Modifier.width(8.dp))
            StatusPill(stringResource(R.string.node_count, liveCount + (if (connected) 1 else 0)), Color(0xFF2D7DFF))
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.auto_pair), fontSize = 11.sp, color = Color(0xFF7890AA))
            Switch(checked = autoPair, onCheckedChange = onAutoPair, modifier = Modifier.scale(0.8f))
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1220))) {
            Column(Modifier.padding(14.dp)) {
                Text(stringResource(R.string.own_network_title), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.own_network_desc), fontSize = 11.sp, color = Color(0xFF7890AA))
                Spacer(Modifier.height(10.dp))
                if (ownNetwork != null) {
                    val parts = ownNetwork.split("|")
                    Text(stringResource(R.string.network_created_label), fontSize = 11.sp, color = Color(0xFF6F9BCC))
                    Text("📡 ${parts.getOrElse(0) { "" }}", fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.network_password_label) + " ${parts.getOrElse(1) { "" }}", color = Color(0xFF29D9FF))
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = onStopNetwork, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.stop_network))
                    }
                } else {
                    Button(onClick = onCreateNetwork, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.create_network))
                    }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFF162238))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.join_network), fontSize = 11.sp, color = Color(0xFF6F9BCC))
                OutlinedTextField(
                    value = joinSsid,
                    onValueChange = { joinSsid = it },
                    label = { Text(stringResource(R.string.network_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = joinPass,
                    onValueChange = { joinPass = it },
                    label = { Text(stringResource(R.string.network_password_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (joinStatus != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(joinStatus, fontSize = 12.sp, color = Color(0xFF29D9FF))
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (joinSsid.isNotBlank() && joinPass.isNotBlank()) {
                            onJoinNetwork(joinSsid.trim(), joinPass.trim())
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.join_button)) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1220))) {
            Column(Modifier.padding(14.dp)) {
                Text(stringResource(R.string.node_label), fontSize = 11.sp, color = Color(0xFF6F9BCC))
                Text(
                    "${mineBadge.orEmpty()} $name".trim(),
                    fontWeight = FontWeight.SemiBold,
                    color = if (mineGold) Color(0xFFFFD54F) else Color.Unspecified
                )
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
                Text(stringResource(R.string.bluetooth_title), fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.bluetooth_desc),
                    fontSize = 11.sp,
                    color = Color(0xFF7890AA)
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onBluetoothScan,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.bluetooth_scan)) }
                if (btDevices.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    btDevices.forEach { (devName, addr) ->
                        Card(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1729))
                        ) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF11304F)), Alignment.Center) {
                                    Text("」", color = Color(0xFF29D9FF), fontSize = 16.sp)
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(devName, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                    Text(addr, fontSize = 11.sp, color = Color(0xFF7890AA))
                                }
                                TextButton(onClick = { onBluetoothConnect(addr) }) {
                                    Text(stringResource(R.string.bluetooth_connect), color = Color(0xFF29D9FF))
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
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