package com.paralink.app.connectivity.wifi

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.*
import android.content.pm.PackageManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.spec.SecretKeySpec
import com.paralink.app.connectivity.bluetooth.LinkState
import com.paralink.app.connectivity.bluetooth.NearbyDevice
import com.paralink.app.connectivity.bluetooth.ParalinkBleManager
import com.paralink.app.core.crypto.MeshCrypto
import com.paralink.app.core.mesh.MeshPacket
import com.paralink.app.core.mesh.PacketCodec
import com.paralink.app.core.mesh.PacketKinds
import com.paralink.app.core.mesh.RelayCore
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

class P2PNetworkManager(
    private val context: Context,
    private val nodeId: String,
    private var displayName: String
) {
    companion object {
        const val PORT = 49152
        const val UDP_PORT = 49151
        const val CHANNEL_ALL = "*"
        const val BEACON_TTL_MS = 20000L
        const val BT_UUID = "d133e46c-2e74-4e53-8c4e-7f2d3a1b9c00"
        const val MCAST_GROUP = "239.255.255.250"
        const val FILE_PART_BYTES = 4096
    }

    private class OutFile(
        val metaId: String, val name: String, val mime: String, val bytes: ByteArray,
        val target: String?, val deferred: CompletableDeferred<Long>
    )
    private class InFile(val metaId: String, val name: String, val mime: String, val expected: Long)

    data class Node(val id: String, val name: String, val address: String?, val connected: Boolean)
    data class RadarNode(val id: String, val name: String, val lastSeen: Long, val lat: Double = 0.0, val lon: Double = 0.0, val gold: Boolean = false, val badge: String? = null)
    data class Event(
        val type: Type,
        val node: Node? = null,
        val text: String? = null,
        val wavB64: String? = null,
        val durationMs: Long = 0,
        val tokenAmount: Double = 0.0,
        val tokenNote: String? = null,
        val filePath: String? = null,
        val fileName: String? = null,
        val fileSize: Long = 0,
        val fileMime: String? = null
    )
    enum class Type { PEERS, CONNECTED, MESSAGE, VOICE, TOKEN, DISCONNECTED, ERROR, NETWORK, FILE }

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = manager.initialize(context, context.mainLooper, null)
    private var receiver: BroadcastReceiver? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val crypto = MeshCrypto(context)

    private val sockets = ConcurrentHashMap<String, Socket>()
    private val writers = ConcurrentHashMap<String, BufferedWriter>()
    private val linkPeers = ConcurrentHashMap<String, String>()
    private val networkKeys = ConcurrentHashMap<String, String>()
    private val nodeNames = ConcurrentHashMap<String, String>()
    private val nodeCoords = ConcurrentHashMap<String, Pair<Double, Double>>()
    private val nodeAddr = ConcurrentHashMap<String, String>()
    private val nodeStyle = ConcurrentHashMap<String, Pair<Boolean, String?>>()
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private val pairwise = ConcurrentHashMap<String, SecretKeySpec>()
    private val seenIds = ConcurrentHashMap.newKeySet<String>()
    private val beaconCooldown = ConcurrentHashMap<String, Long>()
    private val tryConnectAt = ConcurrentHashMap<String, Long>()

    private var server: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var mcastSocket: MulticastSocket? = null
    private var groupInfo: WifiP2pGroup? = null
    private val btAdapter: BluetoothAdapter? =
        runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }.getOrNull()
    private val btSockets = ConcurrentHashMap<String, BluetoothSocket>()
    private val btWriters = ConcurrentHashMap<String, BufferedWriter>()
    private val btFound = ConcurrentHashMap<String, String>()
    private var btServerSocket: BluetoothServerSocket? = null
    @Volatile private var btScanning = false
    private var ble: ParalinkBleManager? = null
    private val bleKnown = ConcurrentHashMap.newKeySet<String>()
    private val outFiles = ConcurrentHashMap<String, OutFile>()
    private val inFiles = ConcurrentHashMap<String, InFile>()
    private val filesDir = File(context.filesDir, "paralink/files")
    private var listener: ((Event) -> Unit)? = null
    private var peerDevices = emptyList<WifiP2pDevice>()

    @Volatile private var localChannel: String = CHANNEL_ALL
    @Volatile private var autoPair: Boolean = true
    @Volatile private var myLat: Double = 0.0
    @Volatile private var myLon: Double = 0.0
    @Volatile private var myGold: Boolean = false
    @Volatile private var myBadge: String? = null
    @Volatile private var beaconBoost: Boolean = false
    @Volatile private var deepScan: Boolean = false
    private val discoveryLock = Any()
    private var discoveryRunning = false
    private val beaconLock = Any()
    private var beaconRunning = false

    fun setListener(l: (Event) -> Unit) { listener = l }

    fun start() {
        startBluetooth()
    }

    fun stop() {
        runCatching { receiver?.let { context.unregisterReceiver(it) } }
        receiver = null
        runCatching { server?.close() }
        server = null
        runCatching { udpSocket?.close() }
        udpSocket = null
        runCatching { mcastSocket?.close() }
        mcastSocket = null
        runCatching { if (btScanning) btAdapter?.cancelDiscovery() }
        runCatching { btServerSocket?.close() }
        btServerSocket = null
        btSockets.values.forEach { runCatching { it.close() } }
        btSockets.clear(); btWriters.clear(); btFound.clear()
        ble?.stop()
        ble = null
        sockets.values.forEach { runCatching { it.close() } }
        sockets.clear(); writers.clear(); linkPeers.clear(); networkKeys.clear(); pairwise.clear()
        scope.cancel()
    }

    fun setChannel(c: String) {
        localChannel = c.trim().ifEmpty { CHANNEL_ALL }
    }

    fun currentChannel(): String = localChannel

    fun setAutoPair(on: Boolean) {
        autoPair = on
        if (on) autoPairPeers()
    }

    fun connect(device: WifiP2pDevice) {
        if (!hasPermission()) return
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit
            override fun onFailure(reason: Int) { if (autoPair) emit(Event(Type.ERROR, text = "Pairing failed: $reason")) }
        })
    }

    fun peers(): List<WifiP2pDevice> = peerDevices

    fun knownNodes(): List<Node> = networkKeys.keys
        .filter { it != nodeId }
        .sorted()
        .map { Node(it, nodeNames[it] ?: it, null, true) }

    fun radarNodes(): List<RadarNode> {
        val ids = LinkedHashSet<String>()
        networkKeys.keys.forEach { ids += it }
        lastSeen.keys.forEach { ids += it }
        val now = System.currentTimeMillis()
        val ttl = if (deepScan) 45000L else BEACON_TTL_MS
        return ids
            .filter { it != nodeId && now - (lastSeen[it] ?: 0L) < ttl }
            .sorted()
            .map {
                val c = nodeCoords[it]
                val s = nodeStyle[it]
                RadarNode(
                    it,
                    nodeNames[it] ?: it,
                    lastSeen[it] ?: 0L,
                    c?.first ?: 0.0,
                    c?.second ?: 0.0,
                    s?.first ?: false,
                    s?.second
                )
            }
    }

    fun setMyLocation(lat: Double, lon: Double) {
        if (lat != 0.0 && lon != 0.0) {
            myLat = lat
            myLon = lon
        }
    }

    fun myLocation(): Pair<Double, Double> = myLat to myLon

    fun setNodeStyle(gold: Boolean, badge: String?) {
        myGold = gold
        myBadge = badge
    }

    fun setBeaconBoost(on: Boolean) {
        beaconBoost = on
    }

    fun setDeepScan(on: Boolean) {
        deepScan = on
    }

    fun setDisplayName(name: String) {
        val clean = name.trim()
        if (clean.isNotEmpty()) displayName = clean
    }

    fun sendText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val body = "TXT|${displayName.replace('|','_')}|${UUID.randomUUID()}|${System.currentTimeMillis()}|$localChannel|${clean.replace('\n',' ')}"
        sendEncrypted(PacketKinds.TXT, body.toByteArray(Charsets.UTF_8), visual = clean)
    }

    fun sendTextTo(targetId: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || targetId == nodeId) return
        val body = "TXT|${displayName.replace('|','_')}|${UUID.randomUUID()}|${System.currentTimeMillis()}|$localChannel|${clean.replace('\n',' ')}"
        sendEncrypted(PacketKinds.TXT, body.toByteArray(Charsets.UTF_8), visual = clean, target = targetId)
    }

    fun sendVoice(wavB64: String, durationMs: Long, previewText: String) {
        val body = "VOICE|${displayName.replace('|','_')}|${UUID.randomUUID()}|${System.currentTimeMillis()}|$localChannel|$durationMs|$wavB64"
        sendEncrypted(PacketKinds.VOICE, body.toByteArray(Charsets.UTF_8), visual = previewText)
    }

    fun sendVoiceTo(targetId: String, wavB64: String, durationMs: Long, previewText: String) {
        if (wavB64.isEmpty() || targetId == nodeId) return
        val body = "VOICE|${displayName.replace('|','_')}|${UUID.randomUUID()}|${System.currentTimeMillis()}|$localChannel|$durationMs|$wavB64"
        sendEncrypted(PacketKinds.VOICE, body.toByteArray(Charsets.UTF_8), visual = previewText, target = targetId)
    }

    fun sendToken(targetId: String, amount: Double, note: String) {
        if (amount <= 0 || targetId == nodeId) return
        val cleaned = note?.replace('|', ' ').orEmpty()
        val body = "PAY|${displayName.replace('|','_')}|${UUID.randomUUID()}|${System.currentTimeMillis()}|$amount|$cleaned"
        sendEncrypted(PacketKinds.PAY, body.toByteArray(Charsets.UTF_8), visual = "PAY $amount", target = targetId)
    }

    private fun sendEncrypted(kind: String, plain: ByteArray, visual: String, target: String? = null) {
        scope.launch { sendEncryptedCore(kind, plain, visual, target) }
    }

    private suspend fun sendEncryptedCore(kind: String, plain: ByteArray, visual: String, target: String?) {
        var targets = if (target != null) listOf(target) else networkKeys.keys.filter { it != nodeId }
        if (target != null && !networkKeys.containsKey(target)) {
            val t = target
            nodeAddr[t]?.let { addr ->
                if (sockets.values.none { it.inetAddress?.hostAddress == addr }) {
                    runCatching {
                        val socket = Socket()
                        socket.tcpNoDelay = true
                        socket.connect(InetSocketAddress(addr, PORT), 4000)
                        attachSocket("direct-$t", socket)
                    }
                }
            }
            delay(900)
        }
        if (targets.isEmpty()) {
            nodeAddr.entries.take(3).forEach { (id, addr) ->
                runCatching {
                    if (sockets.values.none { it.inetAddress?.hostAddress == addr }) {
                        val socket = Socket()
                        socket.tcpNoDelay = true
                        socket.connect(InetSocketAddress(addr, PORT), 4000)
                        attachSocket("retry-$id", socket)
                    }
                }
            }
            delay(900)
            targets = if (target != null) networkKeys.keys.filter { it == target } else networkKeys.keys.filter { it != nodeId }
        }
        if (targets.isEmpty()) {
            emit(Event(Type.ERROR, text = "No peers known yet. Open PARALINK on a nearby device first."))
            return
        }
        targets.forEach { t ->
            runCatching {
                val key = pairwiseKey(t)
                val encrypted = crypto.encrypt(key, plain)
                val packet = MeshPacket(kind, UUID.randomUUID().toString(), nodeId, t, RelayCore.MAX_TTL, encrypted)
                broadcast(packet)
            }.onFailure { emit(Event(Type.ERROR, text = "Send failed: ${it.message}")) }
        }
    }

    /**
     * Sends a file to all peers (targetId == null) or to one peer. Streams
     * FMETA + FCHUNK packets over the same encrypted mesh channel as text.
     * Receive side answers with FACK so long transfers can resume.
     */
    fun sendFile(fileName: String, mime: String, bytes: ByteArray, targetId: String? = null) {
        if (bytes.isEmpty()) return
        scope.launch {
            val cleanName = fileName.replace('|', '_').take(255)
            val metaId = UUID.randomUUID().toString().replace("-", "").take(16)
            val deferred = CompletableDeferred<Long>()
            outFiles[metaId] = OutFile(metaId, cleanName, mime, bytes, targetId, deferred)
            emit(Event(Type.FILE, text = metaId, fileName = cleanName, fileSize = bytes.size.toLong(), fileMime = mime))
            sendEncryptedCore(
                PacketKinds.FILE,
                "FMETA|$metaId|$cleanName|${bytes.size}|$mime|$FILE_PART_BYTES".toByteArray(Charsets.UTF_8),
                cleanName, targetId
            )
            val resumeOffset = runCatching { withTimeoutOrNull(3000) { deferred.await() } }.getOrNull() ?: 0L
            var offset = resumeOffset
            var sinceYield = 0
            while (offset < bytes.size) {
                val end = minOf(bytes.size.toLong(), offset + FILE_PART_BYTES).toInt()
                val chunk = bytes.copyOfRange(offset.toInt(), end)
                val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
                sendEncryptedCore(
                    PacketKinds.FILE,
                    "FCHUNK|$metaId|$offset|$b64".toByteArray(Charsets.UTF_8),
                    cleanName, targetId
                )
                offset = end.toLong()
                sinceYield++
                if (sinceYield >= 8) { delay(2); sinceYield = 0 }
            }
            sendEncryptedCore(
                PacketKinds.FILE,
                "FDONE|$metaId|${bytes.size}".toByteArray(Charsets.UTF_8),
                cleanName, targetId
            )
            outFiles.remove(metaId)
        }
    }

    fun connectToIp(address: String) {
        scope.launch {
            delay(300)
            runCatching {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(address, PORT), 5000)
                attachSocket("manual-$address", socket)
            }.onFailure { emit(Event(Type.ERROR, text = "Connection failed: $address ${it.message}")) }
        }
    }

    fun connectToGroupOwner(address: String) {
        scope.launch {
            delay(700)
            runCatching {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(address, PORT), 5000)
                attachSocket("$address:$PORT", socket)
            }.onFailure { emit(Event(Type.ERROR, text = "Connection failed: ${it.message}")) }
        }
    }

    fun requestGroupInfo() {
        if (!hasPermission()) return
        manager.requestGroupInfo(channel) { group ->
            groupInfo = group
            if (!group.isGroupOwner) connectToGroupOwner("192.168.49.1")
        }
    }

    fun createOwnNetwork() {
        if (!hasPermission()) {
            emit(Event(Type.ERROR, text = "Grant nearby-device permission first"))
            return
        }
        if (groupInfo != null) {
            emit(Event(Type.ERROR, text = "Already own a network. Stop it first."))
            return
        }
        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                manager.requestGroupInfo(channel) { group ->
                    groupInfo = group
                    val ssid = group?.networkName
                    val pass = group?.passphrase
                    emit(Event(Type.NETWORK, text = if (ssid != null && pass != null) "$ssid|$pass" else null))
                }
            }
            override fun onFailure(reason: Int) {
                emit(Event(Type.ERROR, text = "Create own network failed: $reason. Tablet/some devices can't host; try Join instead."))
            }
        })
    }

    fun stopOwnNetwork() {
        if (!hasPermission()) return
        runCatching {
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { groupInfo = null; emit(Event(Type.NETWORK, text = null)) }
                override fun onFailure(reason: Int) = Unit
            })
        }
    }

    fun wifiJoinNetwork(ssid: String, passphrase: String) {
        scope.launch {
            runCatching {
                val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val config = WifiConfiguration().apply {
                    SSID = "\"$ssid\""
                    preSharedKey = "\"$passphrase\""
                    status = WifiConfiguration.Status.ENABLED
                    priority = 1000
                }
                @Suppress("DEPRECATION")
                val id = wifi.addNetwork(config)
                if (id == -1) {
                    emit(Event(Type.ERROR, text = "Join failed: cannot add network $ssid"))
                    return@launch
                }
                @Suppress("DEPRECATION")
                val ok = wifi.disconnect() && wifi.enableNetwork(id, true)
                @Suppress("DEPRECATION")
                runCatching { wifi.reconnect() }
                emit(Event(Type.NETWORK, text = "JOIN|$ssid"))
                if (!ok) {
                    emit(Event(Type.ERROR, text = "Join failed: cannot switch to $ssid"))
                    return@launch
                }
                for (i in 1..12) {
                    delay(4000)
                    val connected = runCatching {
                        val s = Socket()
                        s.tcpNoDelay = true
                        s.connect(InetSocketAddress("192.168.49.1", PORT), 3000)
                        attachSocket("wifi-own-$ssid", s)
                        true
                    }.getOrDefault(false)
                    if (connected) {
                        emit(Event(Type.NETWORK, text = "LINKED|$ssid"))
                        return@launch
                    }
                }
                emit(Event(Type.ERROR, text = "Joined $ssid but host not found. Make sure the other phone runs PARALINK 'Create own network'."))
            }.onFailure { emit(Event(Type.ERROR, text = "Join failed: ${it.message}")) }
        }
    }

    private fun registerReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestGroupInfo()
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        if (state == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                            emit(Event(Type.ERROR, text = "Wi-Fi Direct is off. Enable Wi-Fi to discover nearby devices."))
                        }
                    }
                    BluetoothDevice.ACTION_FOUND -> {
                        if (!hasBluetoothPermission()) return@onReceive
                        val dev = runCatching {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        }.getOrNull()
                        if (dev != null) {
                            val devName = dev.name?.ifBlank { "PARALINK NODE" } ?: "PARALINK NODE"
                            btFound[dev.address] = devName
                            if (dev.bondState == BluetoothDevice.BOND_BONDED) connectBt(dev.address)
                            emit(Event(Type.PEERS))
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> btScanning = false
                }
            }
        }
        receiver = r
        val f = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else @Suppress("DEPRECATION") context.registerReceiver(r, f)
    }

    fun startDiscovery() {
        if (!hasPermission()) return
        synchronized(discoveryLock) {
            if (discoveryRunning) return
            discoveryRunning = true
        }
        scope.launch {
            while (isActive) {
                runCatching {
                    manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() = Unit
                        override fun onFailure(reason: Int) {
                            if (reason == WifiP2pManager.P2P_UNSUPPORTED) {
                                emit(Event(Type.ERROR, text = "Wi-Fi Direct is not supported on this device. Enable Wi-Fi or use Connect by IP."))
                            }
                        }
                    })
                }
                delay(15000)
            }
        }
    }

    fun restartDiscovery() {
        startBluetooth()
    }

    private fun requestPeers() {
        if (!hasPermission()) return
        manager.requestPeers(channel) { list ->
            peerDevices = list.deviceList.toList()
            emit(Event(Type.PEERS))
            autoPairPeers()
        }
    }

    private fun autoPairPeers() {
        if (!autoPair) return
        val now = System.currentTimeMillis()
        peerDevices.forEach { d ->
            if (d.status == WifiP2pDevice.AVAILABLE) {
                val last = tryConnectAt.getOrDefault(d.deviceAddress, 0L)
                if (now - last > 20000) {
                    tryConnectAt[d.deviceAddress] = now
                    connect(d)
                }
            }
        }
    }

    private fun ensureServer() {
        if (server?.isClosed == false) return
        scope.launch {
            runCatching {
                val ss = ServerSocket(PORT, 32, InetAddress.getByName("0.0.0.0"))
                server = ss
                while (!ss.isClosed) {
                    val s = ss.accept()
                    attachSocket("incoming-${s.inetAddress.hostAddress}-${s.port}", s)
                }
            }.onFailure { if (it !is SocketException) emit(Event(Type.ERROR, text = "Server error: ${it.message}")) }
        }
    }

    fun hasBluetooth(): Boolean = btAdapter != null

    fun btPeers(): List<Pair<String, String>> {
        val out = btFound.entries.map { it.value to it.key }.toMutableList()
        ble?.nearby()?.forEach { dev ->
            if (out.none { it.second == dev.address }) out.add(dev.name to dev.address)
        }
        return out
    }

    fun blePeers(): List<NearbyDevice> = ble?.nearby() ?: emptyList()

    fun bleDiagnostics(): String = ble?.diagnostics?.takeIf { true }?.snapshot()?.joinToString("\n") ?: "BLE manager not started"

    /** UI hook: (address, sasCode) shown for out-of-band comparison during pairing. */
    var onBleSas: ((String, String) -> Unit)? = null

    /** UI hook: (address, LinkState, rssi). */
    private var onBleState: ((String, LinkState, Int) -> Unit)? = null

    fun setBleStateListener(l: (String, LinkState, Int) -> Unit) { onBleState = l }

    fun connectBt(address: String) {
        if (!hasBluetoothPermission()) return
        ble?.connect(address)
    }

    fun bleConfirmSas(address: String) {
        ble?.confirmSas(address)
    }

    private fun diagnosticsLog(msg: String) {
        ble?.diagnostics?.log(msg)
    }

    fun startBluetooth() {
        if (btAdapter == null) {
            emit(Event(Type.ERROR, text = "Bluetooth is not available on this device."))
            return
        }
        if (!hasBluetoothPermission()) {
            emit(Event(Type.ERROR, text = "Bluetooth needs the Nearby devices permission first."))
            return
        }
        startBle()
        scope.launch { announce() }
    }

    private fun startBle() {
        if (!hasBluetoothPermission()) return
        val current = ble
        if (current != null) {
            if (!current.available()) { ble = null } else { current.start(); return }
        }
        val transport = ParalinkBleManager(context, nodeId, displayName, crypto)
        if (!transport.available()) {
            emit(Event(Type.ERROR, text = "Bluetooth Low Energy is not available on this device."))
            return
        }
        if (!transport.permissionsOk()) {
            emit(Event(Type.ERROR, text = "Bluetooth SCAN/CONNECT permission is required."))
            return
        }
        ble = transport
        transport.onLine = { line -> handleLine(line) }
        transport.onFound = { dev ->
            btFound[dev.address] = dev.name
            bleKnown.add(dev.address)
            emit(Event(Type.PEERS))
        }
        transport.onLost = { addr ->
            bleKnown.add(addr)
            emit(Event(Type.PEERS))
        }
        transport.onState = { addr, state, _ ->
            onBleState?.invoke(addr, state, transport.rssiFor(addr))
            emit(Event(Type.PEERS))
        }
        transport.onSas = { addr, sas ->
            diagnosticsLog("SAS for $addr: $sas")
            onBleSas?.invoke(addr, sas)
        }
        transport.start()
        diagnosticsLog("PARALINK Bluetooth Core started (BG advertisement + scanner)")
    }

    fun startBluetoothDiscovery() {
        if (btAdapter == null) {
            emit(Event(Type.ERROR, text = "Bluetooth is not available on this device."))
            return
        }
        if (!hasBluetoothPermission()) {
            emit(Event(Type.ERROR, text = "Bluetooth needs the Nearby devices permission first."))
            return
        }
        scope.launch {
            if (btAdapter?.isEnabled != true) {
                emit(Event(Type.ERROR, text = "Bluetooth is off. Turn it on first."))
                return@launch
            }
            connectBonded()
            runCatching {
                if (!btScanning) btScanning = btAdapter?.startDiscovery() == true
            }
        }
    }

    private fun startBtServer() {
        if (btServerSocket != null) return
        scope.launch {
            runCatching {
                val srv = btAdapter?.listenUsingRfcommWithServiceRecord("PARALINK", UUID.fromString(BT_UUID))
                btServerSocket = srv
                while (isActive && srv != null && srv == btServerSocket) {
                    val socket = srv.accept()
                    attachBt("bt-in-${socket.remoteDevice.address}", socket)
                }
            }.onFailure { emit(Event(Type.ERROR, text = "Bluetooth server: ${it.message}")) }
        }
    }

    private fun connectBonded() {
        if (!hasBluetoothPermission()) return
        runCatching {
            btAdapter?.bondedDevices?.forEach { dev ->
                val devName = dev.name?.ifBlank { "PARALINK NODE" } ?: "PARALINK NODE"
                btFound[dev.address] = devName
                if (btSockets.values.none { it.remoteDevice.address == dev.address }) connectBt(dev.address)
            }
            emit(Event(Type.PEERS))
        }
    }

    private fun attachBt(key: String, socket: BluetoothSocket) {
        scope.launch {
            try {
                btSockets[key] = socket
                val writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))
                btWriters[key] = writer
                val hello = MeshPacket(PacketKinds.HELLO, UUID.randomUUID().toString(), nodeId, RelayCore.BROADCAST, RelayCore.MAX_TTL, crypto.myPublicKeyB64())
                writer.write(PacketCodec.encode(hello) + "\n"); writer.flush()
                BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        handleLine(line)
                    }
                }
            } catch (e: IOException) {
                if (socket.isConnected) emit(Event(Type.ERROR, text = "Bluetooth link: ${e.message}"))
            } finally {
                runCatching { socket.close() }
                btSockets.remove(key); btWriters.remove(key)
            }
        }
    }

    private fun announce() {
        scope.launch {
            while (isActive) {
                try {
                    if (writers.isNotEmpty() || btWriters.isNotEmpty() || (ble?.openAddresses()?.isNotEmpty() == true)) {
                        val packet = MeshPacket(PacketKinds.HELLO, UUID.randomUUID().toString(), nodeId, RelayCore.BROADCAST, RelayCore.MAX_TTL, crypto.myPublicKeyB64())
                        broadcast(packet)
                    }
                } catch (e: Exception) { }
                delay(5000)
            }
        }
    }

    private fun startBeacon() {
        synchronized(beaconLock) {
            if (beaconRunning) return
            beaconRunning = true
        }
        scope.launch {
            runCatching {
                val ds = DatagramSocket(null)
                ds.reuseAddress = true
                ds.broadcast = true
                ds.bind(InetSocketAddress(UDP_PORT))
                udpSocket = ds
                while (isActive) {
                    runCatching { sendBeacon() }
                    delay(if (beaconBoost) 1500L else 3000L)
                }
            }
        }
        scope.launch {
            val buf = ByteArray(2048)
            while (isActive) {
                val ds = udpSocket ?: break
                if (ds.isClosed) break
                runCatching {
                    val p = DatagramPacket(buf, buf.size)
                    ds.receive(p)
                    val msg = String(p.data, 0, p.length, Charsets.UTF_8)
                    handleBeacon(msg, p.address.hostAddress)
                }
            }
        }
        scope.launch {
            runCatching {
                val ms = MulticastSocket(null)
                ms.reuseAddress = true
                ms.bind(InetSocketAddress(UDP_PORT))
                ms.joinGroup(InetAddress.getByName(MCAST_GROUP))
                mcastSocket = ms
                val buf = ByteArray(2048)
                while (isActive && mcastSocket == ms) {
                    runCatching {
                        val p = DatagramPacket(buf, buf.size)
                        ms.receive(p)
                        val msg = String(p.data, 0, p.length, Charsets.UTF_8)
                        handleBeacon(msg, p.address.hostAddress)
                    }
                }
            }
        }
    }

    private fun sendBeacon() {
        val badge = myBadge?.replace('|', ' ').orEmpty()
        val payload = "BEACON|$nodeId|${displayName.replace('|','_')}|$PORT|CH|${myLat}|${myLon}|${if (myGold) "1" else "0"}|$badge".toByteArray(Charsets.UTF_8)
        val s = DatagramSocket()
        s.broadcast = true
        runCatching {
            s.send(DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), UDP_PORT))
        }
        runCatching {
            s.send(DatagramPacket(payload, payload.size, InetAddress.getByName(MCAST_GROUP), UDP_PORT))
        }
        runCatching { s.close() }
    }

    private fun handleBeacon(msg: String, fromAddr: String) {
        val parts = msg.split('|')
        if (parts.size < 4 || parts[0] != "BEACON" || parts[1] == nodeId) return
        val senderId = parts[1]
        val senderName = parts[2]
        val senderPort = parts[3].toIntOrNull() ?: PORT
        nodeNames[senderId] = senderName
        nodeAddr[senderId] = fromAddr
        if (parts.size >= 7) {
            val lat = parts[5].toDoubleOrNull() ?: 0.0
            val lon = parts[6].toDoubleOrNull() ?: 0.0
            if (lat != 0.0 && lon != 0.0) nodeCoords[senderId] = lat to lon
        }
        if (parts.size >= 8) nodeStyle[senderId] = (parts[7] == "1") to parts.getOrNull(8)?.takeIf { it.isNotBlank() }
        lastSeen[senderId] = System.currentTimeMillis()
        emit(Event(Type.PEERS))
        if (networkKeys.containsKey(senderId)) return
        val now = System.currentTimeMillis()
        if (now - (beaconCooldown.getOrDefault(senderId, 0L)) < 30000) return
        beaconCooldown[senderId] = now
        runCatching {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(fromAddr, senderPort), 4000)
            attachSocket("lan-$senderId", socket)
        }
    }

    private fun attachSocket(key: String, socket: Socket) {
        scope.launch {
            try {
                socket.tcpNoDelay = true
                sockets[key] = socket
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                writers[key] = writer
                val hello = MeshPacket(PacketKinds.HELLO, UUID.randomUUID().toString(), nodeId, RelayCore.BROADCAST, RelayCore.MAX_TTL, crypto.myPublicKeyB64())
                writer.write(PacketCodec.encode(hello) + "\n"); writer.flush()
                BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        handleLine(line)
                    }
                }
            } catch (e: IOException) {
                if (socket.isConnected) emit(Event(Type.ERROR, text = e.message ?: "socket error"))
            } finally {
                runCatching { socket.close() }
                val peer = linkPeers[key]
                sockets.remove(key); writers.remove(key); linkPeers.remove(key)
                if (peer != null) emit(Event(Type.DISCONNECTED, Node(peer, nodeNames[peer] ?: peer, null, false)))
            }
        }
    }

    private fun handleLine(line: String) {
        val packet = PacketCodec.decode(line) ?: return
        if (packet.id in seenIds) return
        seenIds += packet.id
        if (seenIds.size > 4096) { seenIds.clear() }
        lastSeen[packet.src] = System.currentTimeMillis()

        when (packet.kind) {
            PacketKinds.HELLO -> {
                networkKeys[packet.src] = packet.payload
                emit(Event(Type.CONNECTED, Node(packet.src, nodeNames[packet.src] ?: packet.src, null, true)))
                emit(Event(Type.PEERS))
                if (RelayCore.shouldForward(packet, nodeId)) broadcast(RelayCore.nextHop(packet))
            }
            else -> {
                val mine = RelayCore.shouldDeliverLocally(packet, nodeId)
                if (mine) {
                    val peerKey = networkKeys[packet.src]
                    if (peerKey != null) {
                        runCatching {
                            val key = pairwiseKey(packet.src)
                            val plain = crypto.decrypt(key, packet.payload)
                            deliverDecrypted(packet, plain)
                        }.onFailure { emit(Event(Type.ERROR, text = "Decrypt failed: ${it.message}")) }
                    }
                }
                if (RelayCore.shouldForward(packet, nodeId)) broadcast(RelayCore.nextHop(packet))
            }
        }
    }

    private fun nodeNameFor(nodeId: String): String = nodeNames[nodeId] ?: nodeId

    private fun onChannel(ch: String?): Boolean =
        localChannel == CHANNEL_ALL || ch == localChannel

    private fun deliverDecrypted(packet: MeshPacket, plain: ByteArray) {
        val text = plain.toString(Charsets.UTF_8)
        val parts = text.split('|')
        when (packet.kind) {
            PacketKinds.TXT -> {
                if (parts.size >= 6 && parts[0] == "TXT") {
                    if (!onChannel(parts[4])) return
                    nodeNames[packet.src] = parts[1]
                    emit(Event(Type.MESSAGE, Node(packet.src, parts[1], null, true), text = parts.drop(5).joinToString("|")))
                }
            }
            PacketKinds.VOICE -> {
                if (parts.size >= 7 && parts[0] == "VOICE") {
                    if (!onChannel(parts[4])) return
                    nodeNames[packet.src] = parts[1]
                    val dur = parts[5].toLongOrNull() ?: 0L
                    val wav = parts.drop(6).joinToString("|")
                    emit(Event(Type.VOICE, Node(packet.src, parts[1], null, true), wavB64 = wav, durationMs = dur))
                }
            }
            PacketKinds.PAY -> {
                if (parts.size >= 6 && parts[0] == "PAY") {
                    nodeNames[packet.src] = parts[1]
                    val amount = parts[4].toDoubleOrNull() ?: 0.0
                    val note = parts[5]
                    emit(Event(Type.TOKEN, Node(packet.src, parts[1], null, true), text = parts[1], tokenAmount = amount, tokenNote = note))
                }
            }
            PacketKinds.FILE -> handleFilePacket(packet.src, parts)
            PacketKinds.FACK -> {
                if (parts.size >= 3 && parts[0] == "FACK") {
                    val metaId = parts[1]
                    val offset = parts[2].toLongOrNull() ?: 0L
                    outFiles[metaId]?.deferred?.complete(offset)
                }
            }
        }
    }

    private fun handleFilePacket(src: String, parts: List<String>) {
        when (parts.getOrNull(0)) {
            "FMETA" -> {
                if (parts.size < 6) return
                val metaId = parts[1]
                val name = parts[2]
                val size = parts[3].toLongOrNull() ?: return
                val mime = parts[4]
                runCatching { filesDir.mkdirs() }
                val part = File(filesDir, "$metaId.part")
                val existing = if (part.exists()) part.length() else 0L
                if (existing >= size) {
                    finalizeFile(metaId, name, mime, size, src)
                } else {
                    inFiles[metaId] = InFile(metaId, name, mime, size)
                    // tell the sender where we are so transfers resume after a reconnect
                    sendEncrypted(PacketKinds.FACK, "FACK|$metaId|$existing".toByteArray(Charsets.UTF_8), "FACK", target = src)
                }
            }
            "FCHUNK" -> {
                if (parts.size < 4) return
                val metaId = parts[1]
                val offset = parts[2].toLongOrNull() ?: return
                val data = runCatching { Base64.decode(parts[3], Base64.NO_WRAP) }.getOrNull() ?: return
                val info = inFiles[metaId] ?: return
                val part = File(filesDir, "$metaId.part")
                val current = if (part.exists()) part.length() else 0L
                if (offset < current || offset > current) return
                runCatching {
                    FileOutputStream(part, true).use { it.write(data) }
                }
                if (part.exists() && part.length() >= info.expected) {
                    finalizeFile(metaId, info.name, info.mime, info.expected, src)
                }
            }
            "FDONE" -> {
                val metaId = parts.getOrNull(1) ?: return
                val info = inFiles.remove(metaId) ?: return
                if (File(filesDir, "$metaId.part").length() >= info.expected) {
                    finalizeFile(metaId, info.name, info.mime, info.expected, src)
                }
            }
        }
    }

    private fun finalizeFile(metaId: String, name: String, mime: String, size: Long, senderId: String) {
        val part = File(filesDir, "$metaId.part")
        if (!part.exists() || part.length() < size) return
        val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "file-$metaId" }
        val out = File(filesDir, "${System.currentTimeMillis()}-$safe")
        runCatching { part.renameTo(out) }
            .onFailure { out.writeBytes(part.readBytes()) }
        inFiles.remove(metaId)
        emit(Event(
            Type.FILE,
            Node(senderId, nodeNames[senderId] ?: senderId, null, true),
            text = out.absolutePath,
            fileName = safe,
            fileSize = size,
            fileMime = mime
        ))
    }

    private fun pairwiseKey(peerNodeId: String): SecretKeySpec {
        return pairwise.getOrPut(peerNodeId) {
            val b64 = networkKeys[peerNodeId]
                ?: throw IllegalStateException("no session key available for $peerNodeId")
            crypto.sessionKey(b64)
        }
    }

    private fun broadcast(packet: MeshPacket) {
        val line = PacketCodec.encode(packet) + "\n"
        writers.values.toList().forEach { w ->
            runCatching { w.write(line); w.flush() }
        }
        btWriters.values.toList().forEach { w ->
            runCatching { w.write(line); w.flush() }
        }
        ble?.openAddresses()?.forEach { addr ->
            runCatching { ble?.send(addr, line) }
        }
    }

    private fun emit(event: Event) = Handler(Looper.getMainLooper()).post { listener?.invoke(event) }

    private fun hasBluetoothPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    private fun hasPermission(): Boolean {
        val nearby = if (Build.VERSION.SDK_INT >= 33) ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED else true
        val loc = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return nearby && loc
    }
}