package com.paralink.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paralink.app.connectivity.wifi.P2PNetworkManager
import com.paralink.app.connectivity.wifi.WifiCapabilities
import com.paralink.app.core.identity.IdentityManager
import com.paralink.app.core.language.CascadeTranslationEngine
import com.paralink.app.core.language.JsonPhraseStore
import com.paralink.app.core.language.Language
import com.paralink.app.core.language.LanguageManager
import com.paralink.app.core.language.LanguageManifest
import com.paralink.app.core.language.OfflinePhraseTranslationEngine
import com.paralink.app.core.language.StatisticalMTEngine
import com.paralink.app.core.media.WavPlayer
import com.paralink.app.core.model.ChatMessage
import com.paralink.app.core.model.VoiceMessage
import com.paralink.app.core.model.STATUS_NONE
import com.paralink.app.core.model.STATUS_PENDING
import com.paralink.app.core.model.STATUS_TRANSLATED
import com.paralink.app.core.model.STATUS_UNAVAILABLE
import com.paralink.app.core.storage.LocalMessageStore
import com.paralink.app.ui.ChatScreen
import com.paralink.app.ui.NetworkScreen
import com.paralink.app.ui.ProfileScreen
import com.paralink.app.ui.RadioScreen
import com.paralink.app.ui.WalletScreen
import kotlinx.coroutines.launch
import java.util.Locale

private val ParalinkScheme = darkColorScheme(
    primary = Color(0xFF2D7DFF),
    secondary = Color(0xFF29D9FF),
    background = Color(0xFF050913),
    surface = Color(0xFF0B1220),
    onBackground = Color(0xFFEAF2FF),
    onSurface = Color(0xFFEAF2FF)
)

class MainActivity : ComponentActivity() {
    private lateinit var identity: IdentityManager
    private lateinit var p2p: P2PNetworkManager
    private lateinit var store: LocalMessageStore
    private lateinit var language: LanguageManager

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        identity = IdentityManager(this)
        store = LocalMessageStore(this)
        val nodeId = identity.getOrCreateNodeId()
        val displayName = getSharedPreferences("paralink_profile", MODE_PRIVATE)
            .getString("name", "NODE-${nodeId.takeLast(4)}") ?: "NODE-${nodeId.takeLast(4)}"
        language = buildLanguageManager()
        p2p = P2PNetworkManager(this, nodeId, displayName)
        requestPermissions()
        p2p.start()
        setContent {
            MaterialTheme(colorScheme = ParalinkScheme) {
                ParalinkApp(nodeId, displayName, WifiCapabilities(this), p2p, store, language)
            }
        }
    }

    override fun onDestroy() {
        p2p.stop()
        super.onDestroy()
    }

    private fun buildLanguageManager(): LanguageManager {
        val smt = runCatching { StatisticalMTEngine.load(assets.open("smt/uk-de.txt")) }.getOrNull()
        val phrase = runCatching {
            OfflinePhraseTranslationEngine(JsonPhraseStore(assets.open("offline-corpus.json")))
        }.getOrNull()
        val delegates = buildList {
            smt?.let { add(it) }
            phrase?.let { add(it) }
        }
        val engine = if (delegates.isEmpty()) null else CascadeTranslationEngine(delegates)
        val manager = LanguageManager(this, engine)
        runCatching {
            LanguageManager.bundledManifest = LanguageManifest.fromJson(
                assets.open("language-manifest.json").bufferedReader().use { it.readText() }
            )
        }
        return manager
    }

    private fun requestPermissions() {
        val list = buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            } else add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.RECORD_AUDIO)
        }
        permissions.launch(list.toTypedArray())
    }
}

private enum class Tab { NETWORK, CHAT, RADIO, WALLET, PROFILE }

@Composable
private fun ParalinkApp(
    nodeId: String,
    displayName: String,
    caps: WifiCapabilities,
    p2p: P2PNetworkManager,
    store: LocalMessageStore,
    language: LanguageManager
) {
    var tab by remember { mutableStateOf(Tab.NETWORK) }
    var peerDevices by remember { mutableStateOf(p2p.peers()) }
    var knownNodes by remember { mutableStateOf(p2p.knownNodes()) }
    var connected by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var messages by remember { mutableStateOf(store.all()) }
    var voiceMessages by remember { mutableStateOf(store.allVoices()) }
    var playingVoiceId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val sendText: (String) -> Unit = { text ->
        val id = System.nanoTime().toString()
        store.add(ChatMessage(
            id = id,
            senderId = nodeId,
            senderName = displayName,
            text = text,
            timestamp = System.currentTimeMillis(),
            incoming = false,
            originalText = text,
            translationStatus = STATUS_NONE
        ))
        p2p.sendText(text)
        messages = store.all()
    }

    val playVoice: (VoiceMessage) -> Unit = { v ->
        if (playingVoiceId == v.id) {
            WavPlayer.stop()
            playingVoiceId = null
        } else {
            WavPlayer.stop()
            WavPlayer.play(v.wavBase64) { playingVoiceId = null }
            playingVoiceId = v.id
        }
    }

    DisposableEffect(Unit) {
        p2p.setListener { event ->
            when (event.type) {
                P2PNetworkManager.Type.PEERS -> {
                    peerDevices = p2p.peers()
                    knownNodes = p2p.knownNodes()
                }
                P2PNetworkManager.Type.CONNECTED -> connected = true
                P2PNetworkManager.Type.DISCONNECTED -> connected = false
                P2PNetworkManager.Type.MESSAGE -> {
                    val n = event.node
                    val text = event.text.orEmpty()
                    if (n != null && text.isNotBlank()) {
                        val id = System.nanoTime().toString()
                        val msg = ChatMessage(
                            id = id,
                            senderId = n.id,
                            senderName = n.name,
                            text = text,
                            timestamp = System.currentTimeMillis(),
                            incoming = true,
                            originalText = text,
                            translationStatus = STATUS_PENDING
                        )
                        store.add(msg)
                        messages = store.all()
                        scope.launch {
                            val view = language.processIncoming(text)
                            store.update(msg.copy(
                                translatedText = view.translated,
                                detectedLanguage = view.detectedLanguage?.code,
                                translationStatus = if (view.unavailable) STATUS_UNAVAILABLE else STATUS_TRANSLATED
                            ))
                            messages = store.all()
                        }
                    }
                }
                P2PNetworkManager.Type.VOICE -> {
                    val n = event.node
                    val wav = event.wavB64.orEmpty()
                    if (wav.isNotEmpty()) {
                        store.addVoice(VoiceMessage(
                            id = System.nanoTime().toString(),
                            senderId = n?.id ?: "unknown",
                            senderName = n?.name ?: displayName,
                            wavBase64 = wav,
                            durationMs = event.durationMs,
                            timestamp = System.currentTimeMillis(),
                            incoming = true
                        ))
                        voiceMessages = store.allVoices()
                    }
                }
                P2PNetworkManager.Type.ERROR -> lastError = event.text
            }
        }
        onDispose { WavPlayer.stop() }
    }

    Scaffold(
        containerColor = Color(0xFF050913),
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF09111E)) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.NETWORK -> Icons.Default.NetworkCheck
                                    Tab.CHAT -> Icons.Default.Chat
                                    Tab.RADIO -> Icons.Default.Mic
                                    Tab.WALLET -> Icons.Default.AccountBalanceWallet
                                    Tab.PROFILE -> Icons.Default.Person
                                },
                                t.name
                            )
                        },
                        label = { Text(t.name.lowercase(Locale.getDefault()).replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                Tab.NETWORK -> NetworkScreen(
                    nodeId = nodeId,
                    name = displayName,
                    peers = peerDevices,
                    knownNodes = knownNodes.map { it.nodeId },
                    caps = caps,
                    connected = connected,
                    error = lastError,
                    refresh = {
                        refresh++
                        peerDevices = p2p.peers()
                        knownNodes = p2p.knownNodes()
                    },
                    connect = { device -> p2p.connect(device) }
                )
                Tab.CHAT -> ChatScreen(
                    messages = messages,
                    voiceMessages = voiceMessages,
                    language = language,
                    onSend = sendText,
                    onPlayVoice = playVoice,
                    playingVoiceId = playingVoiceId
                )
                Tab.RADIO -> RadioScreen(
                    voiceMessages = voiceMessages,
                    voiceTranslation = language.translateVoice,
                    callCaptions = language.callCaptions,
                    onPttSends = { wav, duration -> p2p.sendVoice(wav, duration, "voice $duration") },
                    onPlayVoice = playVoice,
                    playingVoiceId = playingVoiceId
                )
                Tab.WALLET -> WalletScreen()
                Tab.PROFILE -> ProfileScreen(nodeId, displayName, connected, language)
            }
        }
    }

    if (!language.firstRunDone) {
        FirstRunDialog(language) { language.firstRunDone = true }
    }
}

@Composable
private fun FirstRunDialog(language: LanguageManager, onDone: () -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    val detected = language.detectedSystemLanguage
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.detected_language_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Language.supported.forEach { lang ->
                        TextButton(onClick = {
                            language.appLanguage = lang
                            language.communicationLanguage = lang
                            language.targetLanguage = lang
                            onDone()
                        }) {
                            Text("${lang.flag} ${lang.nativeName}")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.change_language)) } }
        )
    } else {
        AlertDialog(
            onDismissRequest = { onDone() },
            title = { Text("PARALINK") },
            text = {
                Column {
                    Text(stringResource(R.string.autonomous_network))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.detected_language_title))
                    Text("${detected.flag} ${detected.nativeName}", color = Color(0xFF63CFFF))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    language.appLanguage = detected
                    language.communicationLanguage = detected
                    language.targetLanguage = detected
                    onDone()
                }) { Text(stringResource(R.string.use_language)) }
            },
            dismissButton = {
                TextButton(onClick = { choosing = true }) { Text(stringResource(R.string.change_language)) }
            }
        )
    }
}