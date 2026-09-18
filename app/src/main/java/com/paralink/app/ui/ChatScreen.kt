package com.paralink.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.paralink.app.R
import com.paralink.app.core.language.DisplayMode
import com.paralink.app.core.language.Language
import com.paralink.app.core.language.LanguageManager
import com.paralink.app.core.model.ChatMessage
import com.paralink.app.core.model.FileMessage
import com.paralink.app.core.model.VoiceMessage
import com.paralink.app.core.model.STATUS_TRANSLATED
import com.paralink.app.core.model.STATUS_UNAVAILABLE
import java.io.ByteArrayOutputStream
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
    data class FileMsg(val value: FileMessage) : TimelineItem() {
        override val stamp: Long get() = value.timestamp
        override val key: String get() = "${value.id}-f"
    }
}

@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    voiceMessages: List<VoiceMessage>,
    files: List<FileMessage> = emptyList(),
    language: LanguageManager,
    onSend: (String) -> Unit,
    onSendFile: (String, String, ByteArray) -> Unit = { _, _, _ -> },
    onOpenFile: (FileMessage) -> Unit = {},
    onPlayVoice: (VoiceMessage) -> Unit,
    playingVoiceId: String?,
    channel: String = "*",
    onChannelChange: (String) -> Unit = {},
    peerId: String? = null,
    peerName: String? = null,
    onBack: () -> Unit = {},
    onOpenPeer: (String) -> Unit = {},
    peersOnline: List<Pair<String, String>> = emptyList()
) {
    var text by remember { mutableStateOf("") }
    var attachMenu by remember { mutableStateOf(false) }
    val showOriginalOnly = remember { mutableStateMapOf<String, Boolean>() }
    val context = LocalContext.current

    val inPeer = peerId != null
    val filteredMsgs = if (inPeer) messages.filter { if (it.incoming) it.senderId == peerId else it.peerId == peerId } else messages
    val filteredVoices = if (inPeer) voiceMessages.filter { if (it.incoming) it.senderId == peerId else it.peerId == peerId } else voiceMessages
    val filteredFiles = if (inPeer) files.filter { if (it.incoming) it.senderId == peerId else it.peerId == peerId } else files

    val attach: (Uri, Boolean) -> Unit = attach@{ uri, isImage ->
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@attach
        if (bytes.isEmpty()) return@attach
        val name = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i) else null
            }
        }.getOrNull() ?: "photo-${System.currentTimeMillis()}.jpg"
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val data = if (isImage) compressImage(bytes) else bytes
        onSendFile(name, mime, data)
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) attach(uri, true)
    }
    val pickDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) attach(uri, false)
    }

    val items = remember(filteredMsgs, filteredVoices, filteredFiles) {
        buildList {
            filteredMsgs.forEach { add(TimelineItem.TextMsg(it)) }
            filteredVoices.forEach { add(TimelineItem.VoiceMsg(it)) }
            filteredFiles.forEach { add(TimelineItem.FileMsg(it)) }
        }.sortedBy { it.stamp }
    }

    Column(Modifier.fillMaxSize().padding(14.dp)) {
        if (inPeer) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Color(0xFF29D9FF)) }
                Column {
                    Text(peerName ?: "…", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.private_chat), fontSize = 11.sp, color = Color(0xFF42E8A4), letterSpacing = 1.sp)
                }
            }
        } else {
            Text(stringResource(R.string.messenger), fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.transport_hint), color = Color(0xFF7890AA))
        }
        Spacer(Modifier.height(8.dp))
        if (!inPeer) {
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
            if (peersOnline.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.contacts), fontSize = 11.sp, color = Color(0xFF6F9BCC), fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Spacer(Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(peersOnline) { (id, nm) ->
                        AssistChip(onClick = { onOpenPeer(id) }, label = { Text("◉ $nm") })
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (items.isEmpty()) {
            Text(
                stringResource(if (inPeer) R.string.no_messages_peer else R.string.no_messages),
                color = Color(0xFF8092AB),
                modifier = Modifier.padding(top = 14.dp)
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.key }) { item ->
                when (item) {
                    is TimelineItem.TextMsg -> MessageCard(item.value, language, showOriginalOnly)
                    is TimelineItem.VoiceMsg -> VoiceCard(
                        item.value,
                        playing = item.value.id == playingVoiceId,
                        onPlay = { onPlayVoice(item.value) }
                    )
                    is TimelineItem.FileMsg -> FileCard(item.value, onOpen = { onOpenFile(item.value) })
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { attachMenu = true }) {
                    Icon(Icons.Default.Add, stringResource(R.string.attach), tint = Color(0xFF29D9FF))
                }
                DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.photo)) },
                        onClick = {
                            attachMenu = false
                            pickPhoto.launch("image/*")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.document)) },
                        onClick = {
                            attachMenu = false
                            pickDoc.launch(arrayOf("*/*"))
                        }
                    )
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(if (inPeer) R.string.send_to_peer else R.string.message_hint, peerName ?: "")) },
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

@Composable
private fun FileCard(f: FileMessage, onOpen: () -> Unit) {
    val openable = f.incoming && f.path != null
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (f.incoming) Arrangement.Start else Arrangement.End) {
        Card(
            colors = CardDefaults.cardColors(containerColor = if (f.incoming) Color(0xFF0B1220) else Color(0xFF12305A)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                Modifier.padding(12.dp).clickable(enabled = openable, onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(Color(0xFF11304F)),
                    contentAlignment = Alignment.Center
                ) { Text("📎", fontSize = 16.sp) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    if (f.incoming) {
                        Text(f.senderName, fontSize = 10.sp, color = Color(0xFF63CFFF))
                    }
                    Text(f.fileName, fontSize = 13.sp, color = Color(0xFFEAF2FF), fontWeight = FontWeight.Medium)
                    Text(formatBytes(f.size), fontSize = 11.sp, color = Color(0xFF7890AA))
                }
                if (openable) {
                    Text(stringResource(R.string.file_open), color = Color(0xFF29D9FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun formatBytes(size: Long): String {
    if (size < 1024) return "$size B"
    val kb = size / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.1f GB".format(mb / 1024.0)
}

private fun compressImage(bytes: ByteArray): ByteArray {
    val result = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return@runCatching bytes
        var sample = 1
        while (opts.outWidth / sample > 1600 || opts.outHeight / sample > 1600) sample *= 2
        val mo = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, mo) ?: return@runCatching bytes
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        out.toByteArray()
    }
    return result.getOrDefault(bytes)
}

private fun flagFor(code: String?): String = Language.fromCode(code)?.flag ?: ""

private fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

private fun timestamp(ms: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))