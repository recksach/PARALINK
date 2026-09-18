package com.paralink.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paralink.app.R
import com.paralink.app.core.language.Language
import com.paralink.app.core.language.LanguageManager

@Composable
fun ProfileScreen(
    name: String,
    connected: Boolean,
    language: LanguageManager,
    nodeId: String = "",
    onRename: (String) -> Unit = {}
) {
    val languages = Language.supported
    var nick by remember(name) { mutableStateOf(name) }
    Column(
        Modifier
            .fillMaxSize()
            .padding(18.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(stringResource(R.string.identity), fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1220)),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(name, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(nodeId, fontSize = 12.sp, color = Color(0xFF7890AA))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(if (connected) stringResource(R.string.connected) else stringResource(R.string.ready), Color(0xFF29D9FF))
                    StatusPill(stringResource(R.string.relay), Color(0xFF2D7DFF))
                }
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.nickname_label), fontSize = 12.sp, color = Color(0xFF63CFFF), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = nick,
                    onValueChange = { nick = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.nickname_hint)) }
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onRename(nick) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.save_name))
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1220)), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.how_it_works), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF63CFFF))
                Spacer(Modifier.height(8.dp))
                listOf(R.string.how_1, R.string.how_2, R.string.how_3, R.string.how_4).forEachIndexed { i, res ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text("${i + 1}.", color = Color(0xFF29D9FF), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(res), color = Color(0xFFC9D8F2), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1220)), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.app_language), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF63CFFF))
                Spacer(Modifier.height(8.dp))
                LanguageMenuRow(stringResource(R.string.app_language), language.appLanguage, languages) { language.appLanguage = it }
                LanguageMenuRow(stringResource(R.string.communication_language), language.communicationLanguage, languages) { language.communicationLanguage = it }
                LanguageMenuRow(stringResource(R.string.translate_incoming_to), language.targetLanguage, languages) { language.targetLanguage = it }
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1220)), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.translation_settings), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF63CFFF))
                Spacer(Modifier.height(8.dp))
                SettingSwitchRow(stringResource(R.string.auto_translation), language.autoTranslation) { language.autoTranslation = it }
                SettingSwitchRow(stringResource(R.string.auto_detect_language), language.autoDetectLanguage) { language.autoDetectLanguage = it }
                SettingSwitchRow(stringResource(R.string.show_original), language.showOriginal) { language.showOriginal = it }
                SettingSwitchRow(stringResource(R.string.translate_voice), language.translateVoice) { language.translateVoice = it }
                SettingSwitchRow(stringResource(R.string.translate_ptt), language.translatePtt) { language.translatePtt = it }
                SettingSwitchRow(stringResource(R.string.call_captions), language.callCaptions) { language.callCaptions = it }
                DisplayModeMenuRow(stringResource(R.string.display_mode), language.displayMode) { language.displayMode = it }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.translation_unavailable), fontSize = 11.sp, color = Color(0xFF51627A))
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}