package com.paralink.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paralink.app.R

@Composable
fun WalletScreen() {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Text(stringResource(R.string.wallet_title), fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.offline_ledger), color = Color(0xFF7890AA))
        Spacer(Modifier.height(20.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1220)),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(22.dp)) {
                Text(stringResource(R.string.para_credits), fontSize = 11.sp, color = Color(0xFF6F9BCC), letterSpacing = 2.sp)
                Text("0.00", fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.local_balance), color = Color(0xFF7890AA))
            }
        }
    }
}