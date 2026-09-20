package com.paralink.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
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
import com.paralink.app.core.media.PttRecorder
import com.paralink.app.core.media.WavPlayer
import com.paralink.app.core.model.ChatMessage
import com.paralink.app.core.model.FileMessage
import com.paralink.app.core.model.VoiceMessage
import com.paralink.app.core.model.STATUS_NONE
import com.paralink.app.core.model.STATUS_PENDING
import com.paralink.app.core.model.STATUS_TRANSLATED
import com.paralink.app.core.model.STATUS_UNAVAILABLE
import com.paralink.app.core.notify.NotificationHelper
import com.paralink.app.core.storage.LedgerStore
import com.paralink.app.core.storage.LocalMessageStore
import com.paralink.app.core.store.ShopStore
import com.paralink.app.ui.BluetoothDiagnosticsScreen
import com.paralink.app.ui.ChatScreen
import com.paralink.app.ui.NetworkScreen
import com.paralink.app.ui.ProfileScreen
import com.paralink.app.ui.RadioScreen
import com.paralink.app.ui.WalletScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
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
    private lateinit var ledger: LedgerStore
    private lateinit var shop: ShopStore

    @Volatile private var appVisible = false

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        p2p.restartDiscovery()
        startLocationUpdates()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        identity = IdentityManager(this)
        store = LocalMessageStore(this)
        val nodeId = identity.getOrCreateNodeId()
        val displayName = getSharedPreferences("paralink_profile", MODE_PRIVATE)
            .getString("name", "NODE-${nodeId.takeLast(4)}") ?: "NODE-${nodeId.takeLast(4)}"
        language = buildLanguageManager()
        ledger = LedgerStore(this)
        p2p = P2PNetworkManager(this, nodeId, displayName)
        shop = ShopStore(this)
        p2p.setNodeStyle(shop.owns("gold"), if (shop.owns("star")) "★" else null)
        p2p.setBeaconBoost(shop.boostActive())
        p2p.setDeepScan(shop.owns("scan"))
        requestPermissions()
        p2p.start()
        setContent {
            MaterialTheme(colorScheme = ParalinkScheme) {
                ParalinkApp(this, nodeId, displayName, WifiCapabilities(this), p2p, store, language, ledger, shop) { this.appVisible }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        appVisible = true
    }

    override fun onPause() {
        appVisible = false
        super.onPause()
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
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 26) NotificationHelper.ensureChannels(this)
        permissions.launch(list.toTypedArray())
    }

    private fun startLocationUpdates() {
        val granted = if (Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val push: (Location) -> Unit = { loc ->
            p2p.setMyLocation(loc.latitude, loc.longitude)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
            runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 5f, exec) { loc -> p2p.setMyLocation(loc.latitude, loc.longitude) } }
            runCatching { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 5f, exec) { loc -> p2p.setMyLocation(loc.latitude, loc.longitude) } }
            runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { p2p.setMyLocation(it.latitude, it.longitude) } }
        } else {
            @Suppress("DEPRECATION")
            val l = object : LocationListener {
                override fun onLocationChanged(location: Location) = push(location)
                @Deprecated("deprecated")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }
            @Suppress("DEPRECATION")
            runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 5f, l) }
            @Suppress("DEPRECATION")
            runCatching { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 5f, l) }
        }
    }
}

private enum class Tab { NETWORK, CHAT, RADIO, WALLET, PROFILE }

@Composable
private fun ParalinkApp(
    context: Context,
    nodeId: String,
    displayName: String,
    caps: WifiCapabilities,
    p2p: P2PNetworkManager,
    store: LocalMessageStore,
    language: LanguageManager,
    ledger: LedgerStore,
    shop: ShopStore,
    appVisible: () -> Boolean = { true }
) {
    var tab by remember { mutableStateOf(Tab.NETWORK) }
    var peerDevices by remember { mutableStateOf(p2p.peers()) }
    var knownNodes by remember { mutableStateOf(p2p.knownNodes()) }
    var connected by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var messages by remember { mutableStateOf(store.all()) }
    var voiceMessages by remember { mutableStateOf(store.allVoices()) }
    var files by remember { mutableStateOf(store.allFiles()) }
    var playingVoiceId by remember { mutableStateOf<String?>(null) }
    var walletBalance by remember { mutableStateOf(ledger.currentBalance(System.currentTimeMillis())) }
    var channel by remember { mutableStateOf(p2p.currentChannel()) }
    var autoPairEnabled by remember { mutableStateOf(true) }
    var radarNodes by remember { mutableStateOf(p2p.radarNodes()) }
    var myLoc by remember { mutableStateOf(0.0 to 0.0) }
    var firstRunShown by remember { mutableStateOf(!language.firstRunDone) }
    var ownNetwork by remember { mutableStateOf<String?>(null) }
    var joinStatus by remember { mutableStateOf<String?>(null) }
    var myName by remember { mutableStateOf(displayName) }
    var chatWith by remember { mutableStateOf<String?>(null) }
    var radarPttTarget by remember { mutableStateOf<String?>(null) }
    var btDevices by remember { mutableStateOf(p2p.btPeers()) }
    var bleNearby by remember { mutableStateOf(p2p.blePeers()) }
    var sasPending by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showBleDiag by remember { mutableStateOf(false) }
    val radarRec = remember { PttRecorder() }
    val scope = rememberCoroutineScope()

    val chatName = chatWith?.let { id ->
        radarNodes.firstOrNull { it.id == id }?.name
            ?: knownNodes.firstOrNull { it.id == id }?.name
    }

    val renameNick: (String) -> Unit = { raw ->
        val clean = raw.trim()
        if (clean.isNotEmpty() && clean != myName) {
            context.getSharedPreferences("paralink_profile", Context.MODE_PRIVATE)
                .edit().putString("name", clean).apply()
            myName = clean
            p2p.setDisplayName(clean)
        }
    }

    val openPeer: (String) -> Unit = { id -> chatWith = id; tab = Tab.CHAT }

    val closePeer: () -> Unit = { chatWith = null }

    LaunchedEffect(Unit) {
        while (true) {
            walletBalance = ledger.currentBalance(System.currentTimeMillis())
            radarNodes = p2p.radarNodes()
            myLoc = p2p.myLocation()
            delay(1000)
        }
    }

    LaunchedEffect(knownNodes.size) {
        ledger.setConnectedCount(knownNodes.size)
        walletBalance = ledger.currentBalance(System.currentTimeMillis())
    }

    val sendText: (String) -> Unit = txt@{ text ->
        val clean = text.trim()
        if (clean.isEmpty()) return@txt
        val id = System.nanoTime().toString()
        store.add(ChatMessage(
            id = id,
            senderId = nodeId,
            senderName = myName,
            text = clean,
            timestamp = System.currentTimeMillis(),
            incoming = false,
            originalText = clean,
            translationStatus = STATUS_NONE,
            peerId = chatWith
        ))
        if (chatWith != null) p2p.sendTextTo(chatWith!!, clean) else p2p.sendText(clean)
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

    val setChannel: (String) -> Unit = { c ->
        channel = c
        p2p.setChannel(c)
    }

    val sendFile: (String, String, ByteArray) -> Unit = { fileName, mime, bytes ->
        val t = chatWith
        store.addFile(FileMessage(
            id = System.nanoTime().toString(),
            senderId = nodeId,
            senderName = myName,
            fileName = fileName,
            mime = mime,
            size = bytes.size.toLong(),
            timestamp = System.currentTimeMillis(),
            incoming = false,
            peerId = t
        ))
        p2p.sendFile(fileName, mime, bytes, t)
        files = store.allFiles()
    }

    val openFile: (FileMessage) -> Unit = open@{ m ->
        val path = m.path ?: return@open
        runCatching {
            val uri = FileProvider.getUriForFile(context, "com.paralink.app.fileprovider", File(path))
            val resolved = m.mime.ifBlank { "application/octet-stream" }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, resolved)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            lastError = "No viewer for ${m.fileName}"
        }
    }

    val onRadarTap: (String) -> Unit = openPeer

    val onRadarPtt: (String, Boolean) -> Unit = { id, pressed ->
        if (pressed) {
            radarPttTarget = id
            radarRec.start()
        } else {
            val t = radarPttTarget
            radarPttTarget = null
            val (wav, dur) = radarRec.stop()
            if (wav.isNotEmpty() && dur >= 300 && t != null) {
                store.addVoice(VoiceMessage(
                    id = System.nanoTime().toString(),
                    senderId = nodeId,
                    senderName = myName,
                    wavBase64 = wav,
                    durationMs = dur,
                    timestamp = System.currentTimeMillis(),
                    incoming = false,
                    peerId = t
                ))
                p2p.sendVoiceTo(t, wav, dur, "voice $dur")
                voiceMessages = store.allVoices()
            }
        }
    }

    DisposableEffect(Unit) {
        p2p.onBleSas = { addr, sas -> sasPending = addr to sas }
        p2p.setListener { event ->
            when (event.type) {
                P2PNetworkManager.Type.PEERS -> {
                    peerDevices = p2p.peers()
                    knownNodes = p2p.knownNodes()
                    radarNodes = p2p.radarNodes()
                    btDevices = p2p.btPeers()
                    bleNearby = p2p.blePeers()
                }
                P2PNetworkManager.Type.CONNECTED -> {
                    connected = true
                    knownNodes = p2p.knownNodes()
                }
                P2PNetworkManager.Type.DISCONNECTED -> {
                    connected = false
                    knownNodes = p2p.knownNodes()
                }
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
                        if (!appVisible()) {
                            NotificationHelper.notify(
                                context, NotificationHelper.CH_MESSAGES,
                                context.getString(R.string.message_notification_title), "${n.name}: $text",
                                n.id.hashCode(), android.R.drawable.stat_notify_more
                            )
                        }
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
                P2PNetworkManager.Type.TOKEN -> {
                    val n = event.node
                    if (n != null && event.tokenAmount > 0) {
                        ledger.credit(event.tokenAmount, n.id, event.tokenNote ?: "")
                        walletBalance = ledger.currentBalance(System.currentTimeMillis())
                    }
                }
                P2PNetworkManager.Type.VOICE -> {
                    val n = event.node
                    val wav = event.wavB64.orEmpty()
                    if (wav.isNotEmpty()) {
                        store.addVoice(VoiceMessage(
                            id = System.nanoTime().toString(),
                            senderId = n?.id ?: "unknown",
                            senderName = n?.name ?: myName,
                            wavBase64 = wav,
                            durationMs = event.durationMs,
                            timestamp = System.currentTimeMillis(),
                            incoming = true
                        ))
                        voiceMessages = store.allVoices()
                        if (!appVisible() && n != null) {
                            NotificationHelper.notify(
                                context, NotificationHelper.CH_VOICE,
                                context.getString(R.string.voice_notification_title), "${n.name}: ${event.durationMs / 1000}s",
                                n.id.hashCode(), android.R.drawable.stat_sys_phone_call
                            )
                        }
                    }
                }
                P2PNetworkManager.Type.FILE -> {
                    val n = event.node
                    val fileName = event.fileName ?: return@setListener
                    if (n == null) {
                        // sender-side echo (outgoing file)
                        files = store.allFiles()
                    } else {
                        store.addFile(FileMessage(
                            id = System.nanoTime().toString(),
                            senderId = n.id,
                            senderName = n.name,
                            fileName = fileName,
                            mime = event.fileMime ?: "application/octet-stream",
                            size = event.fileSize,
                            path = event.text,
                            timestamp = System.currentTimeMillis(),
                            incoming = true
                        ))
                        files = store.allFiles()
                        if (!appVisible()) {
                            NotificationHelper.notify(
                                context, NotificationHelper.CH_FILES,
                                context.getString(R.string.file_notification_title), "${n.name}: $fileName",
                                n.id.hashCode(), android.R.drawable.stat_sys_download_done
                            )
                        }
                    }
                }
                P2PNetworkManager.Type.ERROR -> lastError = event.text
                P2PNetworkManager.Type.NETWORK -> {
                    val t = event.text
                    when {
                        t == null -> { ownNetwork = null }
                        t.startsWith("JOIN|") -> joinStatus = "Connecting to ${t.removePrefix("JOIN|")}…"
                        t.startsWith("LINKED|") -> joinStatus = "Linked to ${t.removePrefix("LINKED|")} ✓"
                        else -> ownNetwork = t
                    }
                }
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
                    name = myName,
                    peers = peerDevices,
                    knownNodes = knownNodes.map { it.id },
                    caps = caps,
                    connected = connected,
                    error = lastError,
                    refresh = {
                        refresh++
                        peerDevices = p2p.peers()
                        knownNodes = p2p.knownNodes()
                    },
                    connect = { device -> p2p.connect(device) },
                    connectIp = { ip -> p2p.connectToIp(ip) },
                    radarNodes = radarNodes,
                    myLat = myLoc.first,
                    myLon = myLoc.second,
                    autoPair = autoPairEnabled,
                    onAutoPair = { enabled ->
                        autoPairEnabled = enabled
                        p2p.setAutoPair(enabled)
                    },
                    ownNetwork = ownNetwork,
                    onCreateNetwork = { p2p.createOwnNetwork() },
                    onStopNetwork = { p2p.stopOwnNetwork() },
                    joinStatus = joinStatus,
                    onJoinNetwork = { ssid, pass -> p2p.wifiJoinNetwork(ssid, pass) },
                    mineBadge = if (shop.owns("star")) "★" else null,
                    mineGold = shop.owns("gold"),
                    onRadarTap = onRadarTap,
                    onRadarPtt = onRadarPtt,
                    pttTarget = radarPttTarget,
                    btDevices = btDevices,
                    onBluetoothScan = { p2p.startBluetoothDiscovery() },
                    onBluetoothConnect = { addr -> p2p.connectBt(addr) }
                )
                Tab.CHAT -> ChatScreen(
                    messages = messages,
                    voiceMessages = voiceMessages,
                    files = files,
                    language = language,
                    onSend = sendText,
                    onSendFile = sendFile,
                    onOpenFile = openFile,
                    onPlayVoice = playVoice,
                    playingVoiceId = playingVoiceId,
                    channel = channel,
                    onChannelChange = setChannel,
                    peerId = chatWith,
                    peerName = chatName,
                    onBack = closePeer,
                    onOpenPeer = openPeer,
                    peersOnline = radarNodes
                        .filter { System.currentTimeMillis() - it.lastSeen < 10000 }
                        .sortedByDescending { it.lastSeen }
                        .map { it.id to it.name }
                )
                Tab.RADIO -> RadioScreen(
                    voiceMessages = voiceMessages,
                    voiceTranslation = language.translateVoice,
                    callCaptions = language.callCaptions,
                    onPttSends = { wav, duration -> p2p.sendVoice(wav, duration, "voice $duration") },
                    onPlayVoice = playVoice,
                    playingVoiceId = playingVoiceId,
                    channel = channel,
                    onChannelChange = setChannel
                )
                Tab.WALLET -> WalletScreen(
                    balance = walletBalance,
                    connected = connected,
                    peerCount = knownNodes.size + (if (connected) 1 else 0),
                    peers = p2p.knownNodes(),
                    history = ledger.transactions(),
                    onTransfer = { peerId, amount, note ->
                        ledger.debit(amount, peerId, note).also { if (it) p2p.sendToken(peerId, amount, note) }
                    },
                    shop = shop,
                    onBuy = { item ->
                        val ok = shop.buy(item, ledger)
                        if (ok) {
                            when (item.id) {
                                "gold" -> p2p.setNodeStyle(true, if (shop.owns("star")) "★" else null)
                                "star" -> p2p.setNodeStyle(shop.owns("gold"), "★")
                                "boost" -> p2p.setBeaconBoost(shop.boostActive())
                                "scan" -> p2p.setDeepScan(true)
                            }
                            walletBalance = ledger.currentBalance(System.currentTimeMillis())
                        }
                        ok
                    }
                )
                Tab.PROFILE -> ProfileScreen(myName, connected, language, nodeId = nodeId, onRename = renameNick, onOpenDiagnostics = { showBleDiag = true })
            }
            if (showBleDiag) {
                BluetoothDiagnosticsScreen(
                    devices = bleNearby,
                    diagnostics = p2p.bleDiagnostics(),
                    onRefresh = { bleNearby = p2p.blePeers() },
                    onConnect = { addr -> p2p.connectBt(addr) },
                    onClose = { showBleDiag = false }
                )
            }
        }
    }

    if (firstRunShown) {
        FirstRunDialog(language) {
            firstRunShown = false
            language.firstRunDone = true
        }
    }

    val pendingSas = sasPending
    if (pendingSas != null) {
        AlertDialog(
            onDismissRequest = { sasPending = null },
            title = { Text("Bluetooth pairing") },
            text = {
                Column {
                    Text("Both devices must show the same code before I trust the link.")
                    Spacer(Modifier.height(10.dp))
                    Text(
                        pendingSas.second,
                        color = Color(0xFF29D9FF),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(pendingSas.first, fontSize = 11.sp, color = Color(0xFF7890AA))
                    Text(
                        "If the codes differ, cancel — someone may be intercepting.",
                        fontSize = 11.sp,
                        color = Color(0xFF88A3C8)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    p2p.bleConfirmSas(pendingSas.first)
                    sasPending = null
                }) { Text("Codes match") }
            },
            dismissButton = {
                TextButton(onClick = { sasPending = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun FirstRunDialog(language: LanguageManager, onDone: () -> Unit) {
    var stage by remember { mutableStateOf(0) }
    var choosing by remember { mutableStateOf(false) }
    val detected = language.detectedSystemLanguage
    if (stage == 1) {
        AlertDialog(
            onDismissRequest = { onDone() },
            title = { Text(stringResource(R.string.guide_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    listOf(
                        R.string.guide_1,
                        R.string.guide_2,
                        R.string.guide_3,
                        R.string.guide_4,
                        R.string.guide_5
                    ).forEachIndexed { i, res ->
                        Row(Modifier.padding(vertical = 6.dp)) {
                            Text("${i + 1}.", color = Color(0xFF29D9FF), fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(res), modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDone) { Text(stringResource(R.string.guide_start)) }
            }
        )
    } else if (choosing) {
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
                            stage = 1
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
                    stage = 1
                }) { Text(stringResource(R.string.use_language)) }
            },
            dismissButton = {
                TextButton(onClick = { choosing = true }) { Text(stringResource(R.string.change_language)) }
            }
        )
    }
}