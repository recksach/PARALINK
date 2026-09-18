package com.paralink.app.connectivity.wifi

import android.Manifest
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
import com.paralink.app.core.crypto.MeshCrypto
import com.paralink.app.core.mesh.MeshPacket
import com.paralink.app.core.mesh.PacketCodec
import com.paralink.app.core.mesh.PacketKinds
import com.paralink.app.core.mesh.RelayCore

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
    }

    data class Node(val id: String, val name: String, val address: String?, val connected: Boolean)
    data class RadarNode(val id: String, val name: String, val lastSeen: Long, val lat: Double = 0.0, val lon: Double = 0.0, val gold: Boolean = false, val badge: String? = null)
    data class Event(
        val type: Type,
        val node: Node? = null,
        val text: String? = null,
        val wavB64: String? = null,
        val durationMs: Long = 0,
        val tokenAmount: Double = 0.0,
        val tokenNote: String? = null
    )
    enum class Type { PEERS, CONNECTED, MESSAGE, VOICE, TOKEN, DISCONNECTED, ERROR, NETWORK }

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
    private var groupInfo: WifiP2pGroup? = null
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
        registerReceiver()
        startDiscovery()
        ensureServer()
        announce()
        startBeacon()
    }

    fun stop() {
        runCatching { receiver?.let { context.unregisterReceiver(it) } }
        receiver = null
        runCatching { server?.close() }
        server = null
        runCatching { udpSocket?.close() }
        udpSocket = null
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
        scope.launch {
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
                return@launch
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
                }
            }
        }
        receiver = r
        val f = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
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
        if (receiver == null) registerReceiver()
        startDiscovery()
        startBeacon()
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

    private fun announce() {
        scope.launch {
            while (isActive) {
                try {
                    if (writers.isNotEmpty()) {
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
    }

    private fun sendBeacon() {
        val badge = myBadge?.replace('|', ' ').orEmpty()
        val payload = "BEACON|$nodeId|${displayName.replace('|','_')}|$PORT|CH|${myLat}|${myLon}|${if (myGold) "1" else "0"}|$badge".toByteArray(Charsets.UTF_8)
        val s = DatagramSocket()
        s.broadcast = true
        runCatching {
            s.send(DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), UDP_PORT))
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
        }
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
    }

    private fun emit(event: Event) = Handler(Looper.getMainLooper()).post { listener?.invoke(event) }

    private fun hasPermission(): Boolean {
        val nearby = if (Build.VERSION.SDK_INT >= 33) ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED else true
        val loc = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return nearby && loc
    }
}