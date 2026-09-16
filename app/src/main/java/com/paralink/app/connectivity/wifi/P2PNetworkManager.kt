package com.paralink.app.connectivity.wifi

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
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
    private val displayName: String
) {
    companion object {
        const val PORT = 49152
        const val UDP_PORT = 49151
    }

    data class Node(val id: String, val name: String, val address: String?, val connected: Boolean)
    data class Event(
        val type: Type,
        val node: Node? = null,
        val text: String? = null,
        val wavB64: String? = null,
        val durationMs: Long = 0,
        val tokenAmount: Double = 0.0,
        val tokenNote: String? = null
    )
    enum class Type { PEERS, CONNECTED, MESSAGE, VOICE, TOKEN, DISCONNECTED, ERROR }

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
    private val pairwise = ConcurrentHashMap<String, SecretKeySpec>()
    private val seenIds = ConcurrentHashMap.newKeySet<String>()
    private val beaconCooldown = ConcurrentHashMap<String, Long>()

    private var server: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var groupInfo: WifiP2pGroup? = null
    private var listener: ((Event) -> Unit)? = null
    private var peerDevices = emptyList<WifiP2pDevice>()

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

    fun connect(device: WifiP2pDevice) {
        if (!hasPermission()) return
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit
            override fun onFailure(reason: Int) { emit(Event(Type.ERROR, text = "Wi-Fi Direct connect failed: $reason")) }
        })
    }

    fun peers(): List<WifiP2pDevice> = peerDevices

    fun knownNodes(): List<Node> = networkKeys.keys
        .filter { it != nodeId }
        .sorted()
        .map { Node(it, nodeNames[it] ?: it, null, true) }

    fun sendText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val body = "TXT|${displayName.replace('|','_')}|${UUID.randomUUID()}|${System.currentTimeMillis()}|${clean.replace('\n',' ')}"
        sendEncrypted(PacketKinds.TXT, body.toByteArray(Charsets.UTF_8), visual = clean)
    }

    fun sendVoice(wavB64: String, durationMs: Long, previewText: String) {
        val body = "VOICE|${displayName.replace('|','_')}|${UUID.randomUUID()}|${System.currentTimeMillis()}|$durationMs|$wavB64"
        sendEncrypted(PacketKinds.VOICE, body.toByteArray(Charsets.UTF_8), visual = previewText)
    }

    fun sendToken(targetId: String, amount: Double, note: String) {
        if (amount <= 0 || targetId == nodeId) return
        val cleaned = note?.replace('|', ' ').orEmpty()
        val body = "PAY|${displayName.replace('|','_')}|${UUID.randomUUID()}|${System.currentTimeMillis()}|$amount|$cleaned"
        sendEncrypted(PacketKinds.PAY, body.toByteArray(Charsets.UTF_8), visual = "PAY $amount", target = targetId)
    }

    private fun sendEncrypted(kind: String, plain: ByteArray, visual: String, target: String? = null) {
        val targets = if (target != null) listOf(target) else networkKeys.keys.filter { it != nodeId }
        if (targets.isEmpty()) {
            emit(Event(Type.ERROR, text = "No peers known yet. Open PARALINK on a nearby device first."))
            return
        }
        scope.launch {
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
                    delay(3000)
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
        val payload = "BEACON|$nodeId|${displayName.replace('|','_')}|$PORT".toByteArray(Charsets.UTF_8)
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

    private fun deliverDecrypted(packet: MeshPacket, plain: ByteArray) {
        val text = plain.toString(Charsets.UTF_8)
        val parts = text.split('|', limit = 6)
        when (packet.kind) {
            PacketKinds.TXT -> {
                if (parts.size >= 6 && parts[0] == "TXT") {
                    nodeNames[packet.src] = parts[1]
                    emit(Event(Type.MESSAGE, Node(packet.src, parts[1], null, true), text = parts[5]))
                }
            }
            PacketKinds.VOICE -> {
                if (parts.size >= 6 && parts[0] == "VOICE") {
                    nodeNames[packet.src] = parts[1]
                    val dur = parts[4].toLongOrNull() ?: 0L
                    val wav = parts[5]
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