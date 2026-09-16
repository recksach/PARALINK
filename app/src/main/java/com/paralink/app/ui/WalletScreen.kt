package com.paralink.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paralink.app.R
import com.paralink.app.connectivity.wifi.P2PNetworkManager
import com.paralink.app.core.storage.LedgerEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WalletScreen(
    balance: Double,
    connected: Boolean,
    peerCount: Int,
    peers: List<P2PNetworkManager.Node>,
    history: List<LedgerEntry>,
    onTransfer: (peerId: String, amount: Double, note: String) -> Boolean
) {
    var selectedPeer by remember { mutableStateOf<String?>(null) }
    var amountText by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Text(stringResource(R.string.wallet_title), fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.offline_ledger), color = Color(0xFF7890AA))
        Spacer(Modifier.height(20.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1220)),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.para_credits), fontSize = 11.sp, color = Color(0xFF6F9BCC), letterSpacing = 2.sp)
                    Spacer(Modifier.weight(1f))
                    StatusDot(connected)
                }
                Text(String.format(Locale.US, "%.4f", balance), fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.local_balance), color = Color(0xFF7890AA))
                Spacer(Modifier.height(10.dp))
                if (connected && peerCount > 0) {
                    Text(stringResource(R.string.earn_rate, peerCount), fontSize = 12.sp, color = Color(0xFF42E8A4))
                } else {
                    Text(stringResource(R.string.earn_offline), fontSize = 12.sp, color = Color(0xFF7890AA))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.transfer), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1220)),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Box {
                    val selected = peers.firstOrNull { it.id == selectedPeer }
                    OutlinedTextField(
                        value = selected?.let { "${it.name} • ${it.id}" } ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.transfer_to)) },
                        trailingIcon = { Text("▼", color = Color(0xFF29D9FF)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (peers.isEmpty()) {
                            DropdownMenuItem(text = { Text("No connected nodes yet") }, onClick = { menuOpen = false })
                        }
                        peers.forEach { p ->
                            DropdownMenuItem(
                                text = { Text("${p.name} • ${p.id}") },
                                onClick = { selectedPeer = p.id; menuOpen = false }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text(stringResource(R.string.amount)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text(stringResource(R.string.note)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = selectedPeer != null && (amountText.toDoubleOrNull() ?: 0.0) > 0,
                    onClick = {
                        val amount = amountText.toDoubleOrNull() ?: 0.0
                        val ok = onTransfer(selectedPeer!!, amount, noteText.trim())
                        status = if (ok) "OK: sent $amount PARA" else "Insufficient balance"
                        if (ok) { amountText = ""; noteText = "" }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.send)) }
                if (status != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        status.orEmpty(),
                        fontSize = 12.sp,
                        color = if (status?.startsWith("OK") == true) Color(0xFF42E8A4) else Color(0xFFFF6B6B)
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.history), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (history.isEmpty()) {
            Text("No transactions yet", color = Color(0xFF7890AA))
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1220)),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                LazyColumn(Modifier.height(220.dp), contentPadding = PaddingValues(8.dp)) {
                    items(history.reversed()) { e ->
                        TxRow(e, fmt)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusDot(connected: Boolean) {
    Box(
        Modifier.size(10.dp).clip(CircleShape)
            .background(if (connected) Color(0xFF42E8A4) else Color(0xFF3A4A63))
    )
}

@Composable
private fun TxRow(entry: LedgerEntry, fmt: SimpleDateFormat) {
    val sign = if (entry.type == "OUT") "-" else "+"
    val color = when (entry.type) {
        "IN" -> Color(0xFF42E8A4)
        "OUT" -> Color(0xFFFFB86B)
        else -> Color(0xFF29D9FF)
    }
    val label = when (entry.type) {
        "IN" -> stringResource(R.string.received)
        "OUT" -> stringResource(R.string.sent)
        else -> stringResource(R.string.earned)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("$label • ${entry.peerId}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                entry.note.ifBlank { "-" },
                fontSize = 11.sp,
                color = Color(0xFF7890AA),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "$sign${String.format(Locale.US, "%.4f", entry.amount)}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(fmt.format(Date(entry.timestamp)), fontSize = 10.sp, color = Color(0xFF7890AA))
        }
    }
}