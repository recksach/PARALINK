package com.paralink.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paralink.app.R
import com.paralink.app.core.language.DisplayMode
import com.paralink.app.core.language.Language
import com.paralink.app.core.language.LanguageManager
import com.paralink.app.core.model.ChatMessage
import com.paralink.app.core.model.VoiceMessage
import com.paralink.app.core.model.STATUS_TRANSLATED
import com.paralink.app.core.model.STATUS_UNAVAILABLE
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed class TimelineItem {
    abstract val stamp: Long
    abstract val key: String
    data class TextMsg(val value: ChatMessage) : TimelineItem() {
        override val stamp: Long get() = value.timestamp
        override val key: String get() = "${value.id}-t"
    }
    data class VoiceMsg(val value: VoiceMessage) : TimelineItem() {
        override val stamp: Long get() = value.timestamp
        override val key: String get() = "${value.id}-v"
    }
}

@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    voiceMessages: List<VoiceMessage>,
    language: LanguageManager,
    onSend: (String) -> Unit,
    onPlayVoice: (VoiceMessage) -> Unit,
    playingVoiceId: String?
) {
    var text by remember { mutableStateOf("") }
    val showOriginalOnly = remember { mutableStateMapOf<String, Boolean>() }

    val items = remember(messages, voiceMessages) {
        buildList {
            messages.forEach { add(TimelineItem.TextMsg(it)) }
            voiceMessages.forEach { add(TimelineItem.VoiceMsg(it)) }
        }.sortedBy { it.stamp }
    }

    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Text(stringResource(R.string.messenger), fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.transport_hint), color = Color(0xFF7890AA))
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.key }) { item ->
                when (item) {
                    is TimelineItem.TextMsg -> MessageCard(item.value, language, showOriginalOnly)
                    is TimelineItem.VoiceMsg -> VoiceCard(
                        item.value,
                        playing = item.value.id == playingVoiceId,
                        onPlay = { onPlayVoice(item.value) }
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.message_hint)) },
                singleLine = true
            )
            IconButton(onClick = {
                if (text.isNotBlank()) {
                    onSend(text)
                    text = ""
                }
            }) { Icon(Icons.Default.Send, stringResource(R.string.send)) }
        }
    }
}

@Composable
private fun MessageCard(
    m: ChatMessage,
    language: LanguageManager,
    showOriginalOnly: MutableMap<String, Boolean>
) {
    val originalOnly = showOriginalOnly[m.id] ?: false
    val both = language.displayMode == DisplayMode.BOTH
    val translated = m.translationStatus == STATUS_TRANSLATED && m.translatedText != null
    val showTranslation = translated && !originalOnly && (both || language.displayMode == DisplayMode.TRANSLATION_ONLY)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.incoming) Arrangement.Start else Arrangement.End) {
        Card(
            colors = CardDefaults.cardColors(containerColor = if (m.incoming) Color(0xFF0B1220) else Color(0xFF12305A)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                if (m.incoming) {
                    Text(flagFor(m.detectedLanguage) + " " + m.senderName, fontSize = 11.sp, color = Color(0xFF63CFFF))
                }
                Text(if (showTranslation) m.translatedText!! else m.originalText, color = Color(0xFFEAF2FF))
                Text(timestamp(m.timestamp), fontSize = 10.sp, color = Color(0xFF7890AA))
                if (translated && language.showOriginal) {
                    Text(
                        if (originalOnly) stringResource(R.string.original) else stringResource(R.string.original_label),
                        Modifier.clickable { showOriginalOnly[m.id] = !originalOnly },
                        fontSize = 10.sp,
                        color = Color(0xFF29D9FF),
                        fontWeight = FontWeight.Bold
                    )
                }
                if (m.translationStatus == STATUS_UNAVAILABLE && language.autoTranslation) {
                    Text(stringResource(R.string.translation_unavailable), fontSize = 10.sp, color = Color(0xFF8092AB))
                }
            }
        }
    }
}

@Composable
private fun VoiceCard(v: VoiceMessage, playing: Boolean, onPlay: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (v.incoming) Arrangement.Start else Arrangement.End) {
        Card(
            colors = CardDefaults.cardColors(containerColor = if (v.incoming) Color(0xFF0B1220) else Color(0xFF12305A)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPlay) {
                        Icon(if (playing) Icons.Default.Stop else Icons.Default.PlayArrow, null, tint = Color(0xFF29D9FF))
                    }
                    Text("● " + formatDuration(v.durationMs), color = Color(0xFFEAF2FF), fontSize = 13.sp)
                    Text(timestamp(v.timestamp), Modifier.padding(start = 8.dp), fontSize = 10.sp, color = Color(0xFF7890AA))
                }
                v.transcript?.let {
                    Text(it, fontSize = 12.sp, color = Color(0xFFEAF2FF))
                }
                v.translatedText?.let {
                    Text(it, fontSize = 12.sp, color = Color(0xFF63CFFF))
                }
            }
        }
    }
}

private fun flagFor(code: String?): String = Language.fromCode(code)?.flag ?: ""

private fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

private fun timestamp(ms: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))