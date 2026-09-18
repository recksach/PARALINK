package com.paralink.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paralink.app.R
import com.paralink.app.core.media.PttRecorder
import com.paralink.app.core.media.WavPlayer
import com.paralink.app.core.model.VoiceMessage

@Composable
fun RadioScreen(
    voiceMessages: List<VoiceMessage>,
    voiceTranslation: Boolean,
    callCaptions: Boolean,
    onPttSends: (wavB64: String, durationMs: Long) -> Unit,
    onPlayVoice: (VoiceMessage) -> Unit,
    playingVoiceId: String?,
    channel: String = "*",
    onChannelChange: (String) -> Unit = {}
) {
    val recorder = remember { PttRecorder() }
    var holding by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }

    LaunchedEffect(holding) {
        while (holding) {
            seconds++
            kotlinx.coroutines.delay(1000)
        }
    }

    Column(Modifier.fillMaxSize().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(30.dp))
        Text(stringResource(R.string.push_to_talk), fontSize = 15.sp, color = Color(0xFF75B6FF), letterSpacing = 3.sp)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.channel_label), fontSize = 11.sp, color = Color(0xFF6F9BCC), fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = channel,
                onValueChange = onChannelChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.channel_hint)) },
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (channel.isNotBlank()) "● $channel" else "● ${stringResource(R.string.all_channels)}",
                color = Color(0xFF42E8A4),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .size(220.dp)
                .clip(CircleShape)
                .background(if (holding) Color(0xFF193E6B) else Color(0xFF0B1220))
                .border(2.dp, if (holding) Color(0xFF29D9FF) else Color(0xFF2D7DFF), CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            holding = true
                            recorder.start()
                            seconds = 0
                            tryAwaitRelease()
                            holding = false
                            val (wav, duration) = recorder.stop()
                            if (wav.isNotEmpty()) onPttSends(wav, duration)
                        }
                    )
                },
            Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Mic, null, Modifier.size(58.dp), tint = Color(0xFF29D9FF))
                Text(
                    if (holding) stringResource(R.string.transmitting) else stringResource(R.string.hold_to_talk),
                    fontWeight = FontWeight.Bold
                )
                if (holding) Text("${seconds}s", fontSize = 12.sp, color = Color(0xFF63CFFF))
            }
        }
        Spacer(Modifier.height(22.dp))
        Text(stringResource(R.string.radio_hint), color = Color(0xFF7890AA), fontSize = 13.sp)

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.voice_original_label), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.translate_voice) + ":", fontSize = 11.sp, color = Color(0xFF7890AA))
                Text(
                    if (voiceTranslation) "●" else "○",
                    fontSize = 13.sp,
                    color = if (voiceTranslation) Color(0xFF29D9FF) else Color(0xFF8092AB)
                )
                if (callCaptions) Text("  CC", fontSize = 11.sp, color = Color(0xFF63CFFF))
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(voiceMessages.reversed()) { v ->
                RadioVoiceRow(v, v.id == playingVoiceId, { onPlayVoice(v) })
            }
            if (voiceMessages.isEmpty()) {
                item {
                    Text(stringResource(R.string.no_translation_available), color = Color(0xFF51627A), fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.language_uncertain) + " / " + stringResource(R.string.translation_unavailable),
            fontSize = 10.sp,
            color = Color(0xFF51627A)
        )
    }
}

@Composable
private fun RadioVoiceRow(v: VoiceMessage, playing: Boolean, onPlay: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1220))) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlay) {
                Icon(if (playing) Icons.Default.Stop else Icons.Default.PlayArrow, null, tint = Color(0xFF29D9FF))
            }
            Column(Modifier.weight(1f)) {
                Text("${v.senderName} • ${formatDur(v.durationMs)}", fontSize = 12.sp, color = Color(0xFF7890AA))
                v.transcript?.let { Text(it, fontSize = 12.sp, color = Color(0xFFEAF2FF)) }
                v.translatedText?.let { Text(it, fontSize = 12.sp, color = Color(0xFF63CFFF)) }
            }
            Icon(Icons.Default.Translate, null, tint = Color(0xFF2D7DFF))
        }
    }
}

private fun formatDur(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}