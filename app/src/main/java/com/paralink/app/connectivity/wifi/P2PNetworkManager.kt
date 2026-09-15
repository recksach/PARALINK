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
    companion object { const val PORT = 49152 }

    data class Node(val id: String, val name: String, val address: String?, val connected: Boolean)
    data class Event(val type: Type, val node: Node? = null, val text: String? = null, val wavB64: String? = null, val durationMs: Long = 0)
    enum class Type { PEERS, CONNECTED, MESSAGE, VOICE, DISCONNECTED, ERROR }

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = manager.initialize(context, context.mainLooper, null)
    private var receiver: BroadcastReceiver? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val crypto = MeshCrypto(context)

    private val sockets = ConcurrentHashMap<String, Socket>()
    private val writers = ConcurrentHashMap<String, BufferedWriter>()
    private val linkPeers = ConcurrentHashMap<String, String>()
    private val networkKeys = ConcurrentHashMap<String, String>()
    private val pairwise = ConcurrentHashMap<String, SecretKeySpec>()
    private val seenIds = ConcurrentHashMap.newKeySet<String>()

    private var server: ServerSocket? = null
    private var groupInfo: WifiP2pGroup? = null
    private var listener: ((Event) -> Unit)? = null
    private var peerDevices = emptyList<WifiP2pDevice>()

    fun setListener(l: (Event) -> Unit) { listener = l }

    fun start() {
        registerReceiver()
        startPeerDiscovery()
        ensureServer()
        announce()
    }

    fun stop() {
        runCatching { receiver?.let { context.unregisterReceiver(it) } }
        receiver = null
        runCatching { server?.close() }
        server = null
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
        .map { Node(it, it, null, true) }

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

    private fun sendEncrypted(kind: String, plain: ByteArray, visual: String) {
        val targets = networkKeys.keys.filter { it != nodeId }
        if (targets.isEmpty()) {
            emit(Event(Type.ERROR, text = "No peers known yet. Open PARALINK on a nearby device first."))
            return
        }
        scope.launch {
            targets.forEach { target ->
                runCatching {
                    val key = pairwiseKey(target)
                    val encrypted = crypto.encrypt(key, plain)
                    val packet = MeshPacket(kind, UUID.randomUUID().toString(), nodeId, target, RelayCore.MAX_TTL, encrypted)
                    broadcast(packet)
                }.onFailure { emit(Event(Type.ERROR, text = "Send failed: ${it.message}")) }
            }
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

    fun publishCurrentGroupOwnerAddressIfClient() {
        if (!hasPermission()) return
        manager.requestGroupInfo(channel) { group ->
            groupInfo = group
            if (!group.isGroupOwner) connectToGroupOwner("192.168.49.1")
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
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> Unit
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

    private fun startPeerDiscovery() {
        if (!hasPermission()) return
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit
            override fun onFailure(reason: Int) { emit(Event(Type.ERROR, text = "Discovery failed: $reason")) }
        })
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
                if (peer != null) emit(Event(Type.DISCONNECTED, Node(peer, peer, null, false)))
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
                emit(Event(Type.CONNECTED, Node(packet.src, nodeNameFor(packet.src), null, true)))
                if (RelayCore.shouldForward(packet, nodeId)) broadcast(RelayCore.nextHop(packet))
            }
            else -> {
                val mine = RelayCore.shouldDeliverLocally(packet, nodeId)
                if (mine) {
                    val peerKey = networkKeys[packet.src]
                    if (packet.kind == PacketKinds.HELLO) return
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

    private fun nodeNameFor(nodeId: String): String = nodeId

    private fun deliverDecrypted(packet: MeshPacket, plain: ByteArray) {
        val text = plain.toString(Charsets.UTF_8)
        val parts = text.split('|', limit = 6)
        when (packet.kind) {
            PacketKinds.TXT -> {
                if (parts.size >= 6 && parts[0] == "TXT") {
                    emit(Event(Type.MESSAGE, Node(packet.src, parts[1], null, true), parts[5]))
                }
            }
            PacketKinds.VOICE -> {
                if (parts.size >= 6 && parts[0] == "VOICE") {
                    val dur = parts[4].toLongOrNull() ?: 0L
                    val wav = parts[5]
                    emit(Event(Type.VOICE, Node(packet.src, parts[1], null, true), wavB64 = wav, durationMs = dur))
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