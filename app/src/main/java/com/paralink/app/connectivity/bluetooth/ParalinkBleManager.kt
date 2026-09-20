package com.paralink.app.connectivity.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import com.paralink.app.core.crypto.MeshCrypto
import com.paralink.app.core.protocol.FrameCodec
import com.paralink.app.core.protocol.FrameFlags
import com.paralink.app.core.protocol.FrameTypes
import com.paralink.app.core.protocol.Fragmenter
import com.paralink.app.core.protocol.ParalinkFrame
import com.paralink.app.core.protocol.ProtocolSecurity
import com.paralink.app.core.protocol.Reassembler
import com.paralink.app.core.security.SessionCrypto
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * PARALINK Bluetooth Core (BLE/GATT v2).
 *
 * Every phone runs BOTH roles:
 *  - peripheral: advertises the PARALINK service and answers writes/notifies,
 *  - central: scans for the PARALINK service and connects as a GATT client.
 *
 * One physical link per peer is enough for full-duplex traffic: when we are the
 * client we write to the peer's server characteristics and receive its
 * notifications; when we are the server the peer does the same to us.
 *
 * The link goes through a real connection state machine and a cryptographic
 * handshake (triple-ECDH + HKDF + HMAC AUTH). App data flows only after the
 * session is established and is carried by a reliable, fragmenting,
 * ACK'ed-and-retried binary protocol over the dedicated characteristics.
 *
 * Nothing here is simulated: every CONNECTED state requires a completed real
 * handshake on a real GATT connection.
 */
@SuppressLint("MissingPermission")
class ParalinkBleManager(
    private val context: Context,
    private val nodeId: String,
    private val displayName: String,
    private val meshCrypto: MeshCrypto
) {

    // ---------------------------------------------------------------------
    // Public listeners
    // ---------------------------------------------------------------------
    var onLine: (String) -> Unit = {}
    var onFound: (NearbyDevice) -> Unit = {}
    var onLost: (String) -> Unit = {}
    var onState: (String, LinkState, String?) -> Unit = { _, _, _ -> }
    var onSas: (String, String) -> Unit = { _, _ -> } // address -> "482 913"

    val diagnostics = BluetoothDiagnostics()

    // ---------------------------------------------------------------------
    // BLE plumbing
    // ---------------------------------------------------------------------
    private val manager: BluetoothManager? =
        runCatching { context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }.getOrNull()
    private val adapter: BluetoothAdapter? = manager?.adapter
    private val scannerKnob = SecureRandom()

    private val thread = HandlerThread("paralink-ble").apply { start() }
    private val handler = Handler(thread.looper)

    private var gattServer: BluetoothGattServer? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    private val links = ConcurrentHashMap<String, Link>()
    private val nearby = ConcurrentHashMap<String, NearbyDevice>()

    @Volatile private var running = false
    @Volatile private var scanning = false
    @Volatile private var advertising = false

    private val cccdDescriptor = BluetoothGattDescriptor(
        ParalinkBluetoothConstants.CCCD_UUID,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
    )

    // ---------------------------------------------------------------------
    // Link model
    // ---------------------------------------------------------------------
    private class Link(val address: String) {
        var legIsClient: Boolean = true
        var client: BluetoothGatt? = null
        var serverDevice: BluetoothDevice? = null
        var clientChars: MutableMap<UUID, BluetoothGattCharacteristic> = HashMap()

        var state: LinkState = LinkState.IDLE
        var errorDetail: String? = null

        var mtu: Int = 23
        var rssi: Int = -127

        var sessionId: Int = 0
        var myNonce: ByteArray? = null
        var peerNonce: ByteArray? = null
        var myEphemeral: SessionCrypto.EphemeralKeyPair? = null
        var peerEphemeralB64: String? = null
        var peerStaticB64: String? = null
        var peerNodeId: String? = null
        var peerName: String? = null
        var peerCaps: Int = 0
        var peerProtocol: Int = 0

        var myIdentitySent = false
        var authSent = false
        var authReceived = false
        var sas: String? = null
        var sessionConfirmed = false

        val macKey: ByteArray?
            get() = sessionKey

        var sessionKey: ByteArray? = null
        var reassembler = Reassembler()
        var outbox = ArrayDeque<ByteArray>()
        var sending = false
        var pendingSent: ByteArray? = null
        var sendIndex = 0
        var lastRssiAt = 0L
        var connectedAt = 0L

        val ackPending = ConcurrentHashMap<String, ByteArray>() // "pid/frag" -> sealed frame
    }

    private val zeroKey = ByteArray(32)

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------
    fun available(): Boolean = adapter != null

    fun enabled(): Boolean = adapter?.isEnabled == true

    fun permissionsOk(): Boolean = hasScanConnectPermissions()

    fun start() {
        if (!available() || running) return
        running = true
        diagnostics.log("PARALINK Bluetooth Core v${ParalinkBluetoothConstants.PROTOCOL_VERSION}")
        if (!hasScanConnectPermissions()) {
            diagnostics.log("Bluetooth permission required (SCAN/CONNECT)")
            return
        }
        startServer()
        startAdvertising()
        startScanning()
        handler.postDelayed(sweepRunnable, 1000L)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(sweepRunnable)
        handler.post {
            scanner?.let { if (scanning) runCatching { it.stopScan(scanCb) } }.also { scanning = false }
            advertiser?.let { if (advertising) runCatching { it.stopAdvertising(advCb) } }.also { advertising = false }
            links.values.forEach { closeLink(it) }
            links.clear()
            nearby.clear()
            runCatching { gattServer?.close() }
            gattServer = null
        }
    }

    // ---------------------------------------------------------------------
    // Advertising
    // ---------------------------------------------------------------------
    private fun startAdvertising() {
        val adv = adapter?.bluetoothLeAdvertiser ?: return
        advertiser = adv
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        // Advertise the service + a compact manufacturer blurb. The display
        // name goes into the scan response so advertising never overflows the
        // 31-byte AD payload (a classic cause of "device not found").
        val caps = ParalinkBluetoothConstants.Capabilities.ALL
        val manuf = buildManufacturerData(caps)
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(ParalinkBluetoothConstants.SERVICE_UUID))
            .addManufacturerData(ParalinkBluetoothConstants.MANUFACTURER_ID, manuf)
            .setIncludeDeviceName(false)
            .build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        runCatching {
            adv.startAdvertising(settings, data, scanResponse, advCb)
            advertising = true
            diagnostics.setAdvertiser(true)
        }.onFailure {
            diagnostics.log("Advertising failed: ${it.message}")
        }
    }

    private fun buildManufacturerData(caps: Int): ByteArray {
        val b = ByteArray(9)
        b[0] = ParalinkBluetoothConstants.AD_MAGIC[0]
        b[1] = ParalinkBluetoothConstants.AD_MAGIC[1]
        b[2] = ParalinkBluetoothConstants.AD_MAGIC[2]
        b[3] = ParalinkBluetoothConstants.AD_MAGIC[3]
        b[4] = ParalinkBluetoothConstants.PROTOCOL_VERSION.toByte()
        b[5] = caps.toByte()
        val idDigits = nodeId.takeLast(3)
        b[6] = idDigits.getOrElse(0) { '_' }.code.toByte()
        b[7] = idDigits.getOrElse(1) { '_' }.code.toByte()
        b[8] = idDigits.getOrElse(2) { '_' }.code.toByte()
        return b
    }

    private val advCb = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            diagnostics.log("Advertiser started")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            diagnostics.setAdvertiser(false)
            diagnostics.log("Advertise failed ($errorCode); only scanning will run")
        }
    }

    // ---------------------------------------------------------------------
    // Scanning
    // ---------------------------------------------------------------------
    fun startScanning() {
        if (scanning) return
        val sc = adapter?.bluetoothLeScanner ?: return
        scanner = sc
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(ParalinkBluetoothConstants.SERVICE_UUID)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        runCatching {
            sc.startScan(filters, settings, scanCb)
            scanning = true
            diagnostics.setScanner(true)
        }.onFailure {
            diagnostics.log("Scan start failed: ${it.message}")
        }
    }

    fun stopScanning(reason: String = "manual") {
        scanner?.let { if (scanning) runCatching { it.stopScan(scanCb) } }
        scanning = false
        diagnostics.setScanner(false)
        diagnostics.log("Scan stopped ($reason)")
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val addr = dev.address
            val shortId = readManufacturerShortId(result)
            val name = result.scanRecord?.deviceName?.ifBlank { null } ?: dev.name ?: "PARALINK NODE"
            handler.post {
                if (!running) return@post
                val prev = nearby[addr]
                val now = System.currentTimeMillis()
                val throttled = prev != null && now - prev.lastSeen < 1500 && result.rssi < 0 &&
                    kotlin.math.abs(prev.rssi - result.rssi) < 8
                if (throttled) return@post

                val device = NearbyDevice(
                    address = addr,
                    deviceId = links[addr]?.peerNodeId ?: prev?.deviceId ?: shortId,
                    name = links[addr]?.peerName ?: prev?.name ?: name,
                    rssi = result.rssi,
                    lastSeen = now,
                    connectionState = links[addr]?.state ?: prev?.connectionState ?: LinkState.DISCOVERING,
                    serviceFound = true,
                    protocolVersion = links[addr]?.peerProtocol ?: 0,
                    capabilities = links[addr]?.peerCaps ?: 0,
                    mtu = links[addr]?.mtu ?: 0
                )
                nearby[addr] = device
                onFound(device)

                // Do not open a second physical connection when this peer is
                // already connected to us as a server (it is the driver).
                if (prev == null) {
                    val link = links[addr]
                    if (link == null || link.state == LinkState.DISCONNECTED || link.state == LinkState.IDLE || link.state == LinkState.ERROR) {
                        connect(addr)
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            diagnostics.log("Scan failed: $errorCode")
            scanning = false
        }
    }

    private fun readManufacturerShortId(result: ScanResult): String? {
        val data = result.scanRecord?.getManufacturerSpecificData(ParalinkBluetoothConstants.MANUFACTURER_ID) ?: return null
        if (data.size < 9) return null
        for (i in 0 until 4) if (data[i] != ParalinkBluetoothConstants.AD_MAGIC[i]) return null
        val proto = data[4].toInt()
        val idDigits = buildString {
            for (i in 6 until 9) append(data[i].toInt().toChar())
        }.ifBlank { null }
        return if (proto == ParalinkBluetoothConstants.PROTOCOL_VERSION) idDigits else null
    }

    // ---------------------------------------------------------------------
    // GATT server
    // ---------------------------------------------------------------------
    private fun startServer() {
        if (gattServer != null) return
        val server = manager?.openGattServer(context, serverCb) ?: return
        val service = BluetoothGattService(
            ParalinkBluetoothConstants.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        service.addCharacteristic(characteristic(ParalinkBluetoothConstants.DISCOVERY_CHAR,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE
                or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY))
        service.addCharacteristic(characteristic(ParalinkBluetoothConstants.IDENTITY_CHAR,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE
                or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY))
        service.addCharacteristic(characteristic(ParalinkBluetoothConstants.CONTROL_CHAR,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE
                or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY))
        service.addCharacteristic(characteristic(ParalinkBluetoothConstants.MESSAGE_CHAR,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY))
        service.addCharacteristic(characteristic(ParalinkBluetoothConstants.FILE_CHAR,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY))
        service.addCharacteristic(characteristic(ParalinkBluetoothConstants.CALL_CHAR,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY))
        service.addCharacteristic(characteristic(ParalinkBluetoothConstants.ACK_CHAR,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY))
        server.addService(service)
        gattServer = server
        diagnostics.setGattServer(true)
        diagnostics.log("GATT server up: $service")
    }

    private fun characteristic(uuid: UUID, properties: Int): BluetoothGattCharacteristic {
        val c = BluetoothGattCharacteristic(uuid, properties, BluetoothGattCharacteristic.PERMISSION_READ
            or BluetoothGattCharacteristic.PERMISSION_WRITE)
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            c.addDescriptor(BluetoothGattDescriptor(ParalinkBluetoothConstants.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        }
        return c
    }

    private fun charForType(type: Int): UUID = when (type) {
        FrameTypes.DISCOVERY -> ParalinkBluetoothConstants.DISCOVERY_CHAR
        FrameTypes.IDENTITY, FrameTypes.IDENTITY_RESP -> ParalinkBluetoothConstants.IDENTITY_CHAR
        FrameTypes.HELLO, FrameTypes.HELLO_RESP, FrameTypes.KEY_EXCHANGE, FrameTypes.AUTH,
        FrameTypes.SESSION, FrameTypes.PING, FrameTypes.PONG, FrameTypes.ERROR, FrameTypes.BYE -> ParalinkBluetoothConstants.CONTROL_CHAR
        FrameTypes.VOICE -> ParalinkBluetoothConstants.MESSAGE_CHAR
        FrameTypes.FILE -> ParalinkBluetoothConstants.FILE_CHAR
        FrameTypes.CALL -> ParalinkBluetoothConstants.CALL_CHAR
        FrameTypes.GAME -> ParalinkBluetoothConstants.MESSAGE_CHAR
        FrameTypes.ACK -> ParalinkBluetoothConstants.ACK_CHAR
        else -> ParalinkBluetoothConstants.MESSAGE_CHAR
    }

    private val serverCb = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            handler.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    diagnostics.log("Server: ${device.address} connected")
                    ensureServerLink(device)
                } else {
                    diagnostics.log("Server: ${device.address} disconnected (${linkStateName(newState)})")
                    closeLink(links[device.address])
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (responseNeeded) runCatching {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            handler.post {
                val link = links[device.address] ?: return@post
                receiveBytes(link, characteristic.uuid, value)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (responseNeeded) runCatching {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            runCatching {
                val value = if (descriptor.uuid == ParalinkBluetoothConstants.CCCD_UUID) byteArrayOf(0x00) else ByteArray(0)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            handler.post {
                links[device.address]?.mtu = mtu.coerceAtLeast(23)
                diagnostics.log("Server MTU ${device.address}: $mtu")
            }
        }
    }

    /** When a peer connects to our server, prefer the server leg (it's already up). */
    private fun ensureServerLink(device: BluetoothDevice) {
        val link = links[device.address]
        if (link != null && link.state == LinkState.CONNECTING && link.legIsClient) {
            // We initiated a client connection to the same peer - cancel the server leg.
            diagnostics.log("Server leg for ${device.address} duplicated by client leg; cancelling")
            runCatching { gattServer?.cancelConnection(device) }
            return
        }
        val l = link ?: Link(device.address).also { links[it.address] = it }
        l.legIsClient = false
        l.serverDevice = device
        l.client = null
        if (l.state == LinkState.IDLE || l.state == LinkState.DISCONNECTED || l.state == LinkState.ERROR) {
            l.state = LinkState.CONNECTING
            setState(l, LinkState.DISCOVERED, null) // server is ready; handshake starts on first write
        }
    }

    private fun linkStateName(s: Int): String = when (s) {
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> "DISCONNECTED"
    }

    // ---------------------------------------------------------------------
    // GATT client
    // ---------------------------------------------------------------------
    fun connect(address: String) {
        handler.post {
            if (!running || !hasScanConnectPermissions()) return@post
            val link = links[address]
            if (link != null && link.state != LinkState.IDLE && link.state != LinkState.ERROR && link.state != LinkState.DISCONNECTED) return@post
            val dev = adapter?.getRemoteDevice(address) ?: return@post
            val l = link ?: Link(address).also { links[it.address] = it }
            l.legIsClient = true
            l.client = null
            l.serverDevice = null
            l.state = LinkState.CONNECTING
            setState(l, LinkState.CONNECTING, null)
            diagnostics.log("Connecting to $address")
            runCatching {
                l.client = dev.connectGatt(context, false, clientCb(l, dev), BluetoothDevice.TRANSPORT_LE)
            }.onFailure {
                setState(l, LinkState.ERROR, "connect failed: ${it.message}")
            }
        }
    }

    fun disconnect(address: String) {
        handler.post { links[address]?.let { closeLink(it) } }
    }

    fun disconnectAll() {
        handler.post { links.values.forEach { closeLink(it) }; links.clear() }
    }

    private val clientCb: (Link, BluetoothDevice) -> BluetoothGattCallback = { link, dev ->
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                handler.post {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        diagnostics.log("Client: ${dev.address} connected")
                        link.client = gatt
                        runCatching { gatt.requestMtu(ParalinkBluetoothConstants.TARGET_MTU) }
                    } else {
                        if (link.state.securityEstablished) {
                            diagnostics.log("Client: ${dev.address} disconnected (status $status)")
                            onLost(dev.address)
                        }
                        closeLink(link)
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                handler.post {
                    link.mtu = mtu.coerceAtLeast(23)
                    diagnostics.log("Client MTU ${dev.address}: $mtu")
                    if (status == BluetoothGatt.GATT_SUCCESS) runCatching { gatt.discoverServices() }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                handler.post {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        setState(link, LinkState.ERROR, "service discovery failed ($status)")
                        return@post
                    }
                    val service = gatt.getService(ParalinkBluetoothConstants.SERVICE_UUID)
                    if (service == null) {
                        setState(link, LinkState.ERROR, "PARALINK service not found")
                        runCatching { gatt.disconnect() }
                        return@post
                    }
                    link.clientChars.clear()
                    service.characteristics.forEach { c ->
                        link.clientChars[c.uuid] = c
                        if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                            runCatching {
                                gatt.setCharacteristicNotification(c, true)
                                c.descriptors.firstOrNull { it.uuid == ParalinkBluetoothConstants.CCCD_UUID }?.let { desc ->
                                    val enable = byteArrayOf(0x01)
                                    if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(desc, enable)
                                    else @Suppress("DEPRECATION") gatt.writeDescriptor(desc, enable)
                                }
                            }
                        }
                    }
                    if (link.clientChars.isEmpty()) {
                        setState(link, LinkState.ERROR, "characteristic discovery failed")
                        return@post
                    }
                    setState(link, LinkState.DISCOVERED, null)
                    beginHandshake(link)
                }
            }

            @Deprecated("deprecated")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val value = runCatching { characteristic.getValue() }.getOrNull()
                if (value != null) handler.post { receiveBytes(link, characteristic.uuid, value) }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                handler.post { receiveBytes(link, characteristic.uuid, value) }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                handler.post {
                    link.pendingSent = null
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        diagnostics.log("Write ${dev.address} status $status")
                    }
                    link.sending = false
                    pumpSender(link)
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Handshake
    // ---------------------------------------------------------------------
    private fun beginHandshake(link: Link) {
        link.sessionId = scannerKnob.nextInt(0x10000)
        val nonce = ByteArray(16).also { scannerKnob.nextBytes(it) }
        link.myNonce = nonce
        val eph = crypto.newEphemeral()
        link.myEphemeral = eph
        setState(link, LinkState.HANDSHAKING, null)
        val hello = JSONObject().apply {
            put("v", ParalinkBluetoothConstants.PROTOCOL_VERSION)
            put("id", nodeId)
            put("name", displayName)
            put("caps", ParalinkBluetoothConstants.Capabilities.ALL)
            put("n", Base64.getEncoder().encodeToString(nonce))
            put("eph", eph.publicKeyB64)
        }
        sendControl(link, FrameTypes.HELLO, hello.toString().toByteArray(Charsets.UTF_8))
    }

    private fun handleHello(link: Link, payload: ByteArray) {
        val json = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull() ?: return
        link.peerNodeId = json.optString("id", null) ?: return
        link.peerName = json.optString("name", null)
        link.peerCaps = json.optInt("caps", 0)
        link.peerProtocol = json.optInt("v", 0)
        val nonceB = runCatching { Base64.getDecoder().decode(json.getString("n")) }.getOrNull()
        if (nonceB == null || nonceB.size != 16) return
        link.peerNonce = nonceB
        link.peerEphemeralB64 = json.optString("eph", null)?.takeIf { it.isNotBlank() }
        diagnostics.log("HELLO received from ${link.peerNodeId}")
        nearby[link.address]?.let {
            onFound(it.copy(deviceId = link.peerNodeId, name = link.peerName ?: it.name, protocolVersion = link.peerProtocol, capabilities = link.peerCaps))
        }
        if (link.myNonce == null) {
            // We are the passive side of the link - answer the HELLO.
            val nonce = ByteArray(16).also { scannerKnob.nextBytes(it) }
            link.myNonce = nonce
            val eph = crypto.newEphemeral()
            link.myEphemeral = eph
            val hello = JSONObject().apply {
                put("v", ParalinkBluetoothConstants.PROTOCOL_VERSION)
                put("id", nodeId)
                put("name", displayName)
                put("caps", ParalinkBluetoothConstants.Capabilities.ALL)
                put("n", Base64.getEncoder().encodeToString(nonce))
                put("eph", eph.publicKeyB64)
            }
            setState(link, LinkState.HANDSHAKING, null)
            sendControl(link, FrameTypes.HELLO_RESP, hello.toString().toByteArray(Charsets.UTF_8))
        }
        maybeSendIdentity(link)
        tryDerive(link)
    }

    private fun handleHelloResp(link: Link, payload: ByteArray) {
        val json = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull() ?: return
        link.peerNodeId = json.optString("id", null)
        link.peerName = json.optString("name", null)
        link.peerCaps = json.optInt("caps", 0)
        link.peerProtocol = json.optInt("v", 0)
        val nonceB = runCatching { Base64.getDecoder().decode(json.getString("n")) }.getOrNull()
        if (nonceB != null && nonceB.size == 16) link.peerNonce = nonceB
        link.peerEphemeralB64 = json.optString("eph", null)?.takeIf { it.isNotBlank() }
        diagnostics.log("HELLO_RESP received from ${link.peerNodeId}")
        maybeSendIdentity(link)
        tryDerive(link)
    }

    private fun handleIdentity(link: Link, payload: ByteArray, isResponse: Boolean) {
        val json = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull() ?: return
        val pub = json.optString("pub", null)?.takeIf { it.isNotBlank() } ?: return
        link.peerStaticB64 = pub
        setState(link, LinkState.AUTHENTICATING, null)
        if (!isResponse) {
            // The other side expects our identity; send it back.
            val resp = JSONObject().apply { put("pub", meshCrypto.myPublicKeyB64()) }
            sendFramesRaw(link, FrameTypes.IDENTITY_RESP, resp.toString().toByteArray(Charsets.UTF_8), retain = false)
        }
        maybeSendIdentity(link)
        tryDerive(link)
    }

    private fun maybeSendIdentity(link: Link) {
        if (link.myIdentitySent) return
        link.myIdentitySent = true
        val payload = JSONObject().apply { put("pub", meshCrypto.myPublicKeyB64()) }
        sendFramesRaw(link, FrameTypes.IDENTITY, payload.toString().toByteArray(Charsets.UTF_8), retain = false)
    }

    private fun tryDerive(link: Link) {
        val peerStatic = link.peerStaticB64 ?: return
        val peerEph = link.peerEphemeralB64 ?: return
        val myEph = link.myEphemeral ?: return
        val nonceA = link.myNonce ?: return
        val nonceB = link.peerNonce ?: return
        if (link.sessionKey != null) {
            maybeSendAuth(link)
            return
        }
        val key = runCatching {
            crypto.linkKey(
                meshCrypto.myStaticPrivateKey(),
                myEph,
                peerStatic,
                peerEph,
                nonceA,
                nonceB
            )
        }.getOrNull()
        if (key == null) {
            setState(link, LinkState.ERROR, "key exchange failed")
            return
        }
        link.sessionKey = key
        link.sas = crypto.sas(key, nonceA, nonceB)
        setState(link, LinkState.KEY_EXCHANGE, null)
        diagnostics.log("Key exchange complete; SAS ${link.sas}")
        if (link.authReceived) maybeSendAuth(link)
    }

    private fun maybeSendAuth(link: Link) {
        if (link.authSent) {
            if (link.authReceived && link.sessionConfirmed) {
                confirmIfReady(link)
            }
            return
        }
        val key = link.sessionKey ?: return
        val peerId = link.peerNodeId ?: return
        val proof = crypto.authProof(key, link.sessionId, nodeId, peerId)
        link.authSent = true
        val auth = JSONObject().apply { put("p", Base64.getEncoder().encodeToString(proof)) }
        sendFramesRaw(link, FrameTypes.AUTH, auth.toString().toByteArray(Charsets.UTF_8), retain = false)
    }

    private fun handleAuth(link: Link, payload: ByteArray) {
        val json = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull() ?: return
        val proof = runCatching { Base64.getDecoder().decode(json.getString("p")) }.getOrNull() ?: return
        val key = link.sessionKey ?: return
        val myId = link.peerNodeId ?: return
        val expected = crypto.authProof(key, link.sessionId, myId, nodeId)
        if (!ProtocolSecurity.tagsEqual(expected, proof)) {
            setState(link, LinkState.ERROR, "authentication failed")
            diagnostics.log("AUTH verification failed for ${link.address}")
            return
        }
        link.authReceived = true
        diagnostics.log("AUTH verified for ${link.peerNodeId}")
        setState(link, LinkState.SECURE_SESSION, null)
        maybeSendAuth(link)
        confirmIfReady(link)
        link.sas?.let { onSas(link.address, it) }
    }

    private fun confirmIfReady(link: Link) {
        if (!link.authSent || !link.authReceived) return
        if (link.sessionConfirmed) return
        link.sessionConfirmed = true
        setState(link, LinkState.CONNECTED, null)
        link.connectedAt = System.currentTimeMillis()
        diagnostics.log("Secure session established with ${link.peerNodeId}")
        val ok = JSONObject().apply { put("id", nodeId); put("ok", 1) }
        sendFramesRaw(link, FrameTypes.SESSION, ok.toString().toByteArray(Charsets.UTF_8), retain = false)
        nearby[link.address]?.let { onFound(it.copy(connectionState = LinkState.CONNECTED, deviceId = link.peerNodeId)) }
    }

    private fun handleSession(link: Link, payload: ByteArray) {
        val json = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull() ?: return
        if (json.optInt("ok", 0) == 1) {
            link.sessionConfirmed = true
            if (!link.authSent) maybeSendAuth(link)
            if (link.authSent && link.authReceived) {
                setState(link, LinkState.CONNECTED, null)
                link.connectedAt = System.currentTimeMillis()
                diagnostics.log("Session confirmed with ${link.peerNodeId}")
                nearby[link.address]?.let { onFound(it.copy(connectionState = LinkState.CONNECTED, deviceId = link.peerNodeId)) }
            }
        }
    }

    /** User accepted the SAS displayed on both devices. */
    fun confirmSas(address: String) {
        handler.post {
            val link = links[address] ?: return@post
            if (link.state == LinkState.SECURE_SESSION || link.state == LinkState.CONNECTED) {
                diagnostics.log("SAS confirmed for $address")
                // Both sides compute identical keys; once AUTH is verified the
                // session is trusted. Nothing extra needs to be exchanged.
                if (!link.sessionConfirmed) confirmIfReady(link)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Packet processing (receive path)
    // ---------------------------------------------------------------------
    private fun receiveBytes(link: Link, charUuid: UUID, bytes: ByteArray) {
        val key = link.sessionKey ?: zeroKey
        val frame = ProtocolSecurity.unseal(bytes, key) ?: return
        when (frame.type) {
            FrameTypes.HELLO, FrameTypes.HELLO_RESP -> {
                if (frame.sessionId != 0) link.sessionId = frame.sessionId
            }
        }
        when (frame.type) {
            FrameTypes.ACK -> {
                val t = frame.ackTarget()
                if (t != null) {
                    link.ackPending.remove("${t.first}/${t.second}")
                }
                pumpSender(link)
                return
            }
            FrameTypes.PING -> {
                sendFramesRaw(link, FrameTypes.PONG, ByteArray(0), retain = false)
                return
            }
            FrameTypes.PONG -> return
        }

        // Receiver-side ACK for every RETAIN frame (each fragment is acked).
        if (frame.flags and FrameFlags.RETAIN != 0) {
            sendAck(link, frame.packetId, frame.fragmentIndex)
        }

        val whole = link.reassembler.push(frame) ?: return
        processDatagram(link, frame.type, whole)
    }

    private fun sendAck(link: Link, packetId: Long, fragmentIndex: Int) {
        val ack = ParalinkFrame(
            type = FrameTypes.ACK,
            flags = FrameFlags.NONE,
            ttl = 0,
            packetId = (scannerKnob.nextInt().toLong() and 0xFFFFFFFFL),
            sessionId = link.sessionId,
            fragmentIndex = 0,
            fragmentCount = 0,
            sequence = link.sendIndex++ and 0xFFFF,
            payload = FrameCodec.packetIdForAck(packetId, fragmentIndex)
        )
        sendSealed(link, ack)
    }

    private fun processDatagram(link: Link, type: Int, payload: ByteArray) {
        when (type) {
            FrameTypes.HELLO -> handleHello(link, payload)
            FrameTypes.HELLO_RESP -> handleHelloResp(link, payload)
            FrameTypes.IDENTITY -> handleIdentity(link, payload, false)
            FrameTypes.IDENTITY_RESP -> handleIdentity(link, payload, true)
            FrameTypes.AUTH -> handleAuth(link, payload)
            FrameTypes.SESSION -> handleSession(link, payload)
            FrameTypes.DISCOVERY -> { /* presence refresh only */ }
            FrameTypes.ERROR -> diagnostics.log("Peer error on ${link.address}: ${String(payload, Charsets.UTF_8)}")
            FrameTypes.BYE -> closeLink(link)
            FrameTypes.MESSAGE, FrameTypes.FILE, FrameTypes.VOICE, FrameTypes.CALL, FrameTypes.GAME -> {
                if (!link.sessionConfirmed) {
                    diagnostics.log("Dropped datagram ($type) before session confirmed")
                    return
                }
                val line = String(payload, Charsets.UTF_8)
                onLine(line)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Send path
    // ---------------------------------------------------------------------
    /** Sends an app-level mesh line as a MESSAGE datagram. */
    fun send(address: String, line: String) = sendAs(address, line, FrameTypes.MESSAGE)

    /** Sends an app-level mesh line using a specific channel characteristic. */
    fun sendAs(address: String, line: String, channelType: Int) {
        val link = links[address] ?: return
        handler.post {
            if (!link.sessionConfirmed) {
                diagnostics.log("sendAs dropped: session not established for $address")
                return@post
            }
            dispatchDatagram(link, channelType, line.toByteArray(Charsets.UTF_8))
        }
    }

    private fun dispatchDatagram(link: Link, type: Int, payload: ByteArray) {
        val capacity = ParalinkBluetoothConstants.fragmentCapacity(link.mtu)
        val packetId = (scannerKnob.nextInt().toLong() and 0xFFFFFFFFL)
        val seq = link.sendIndex++ and 0xFFFF
        val slices = Fragmenter.fragment(payload, capacity, packetId, link.sessionId, seq, type)
        val key = link.sessionKey ?: return
        for (s in slices) {
            val frame = Fragmenter.frameFor(s).copy(flags = s.flags or FrameFlags.RETAIN)
            val sealed = ProtocolSecurity.sealed(frame, key)
            if (frame.flags and FrameFlags.RETAIN != 0) {
                link.ackPending["${frame.packetId}/${frame.fragmentIndex}"] = sealed
            }
            enqueue(link, sealed)
        }
    }

    private fun sendControl(link: Link, type: Int, payload: ByteArray) {
        val frame = ParalinkFrame(type, FrameFlags.NONE, 0, (scannerKnob.nextInt().toLong() and 0xFFFFFFFFL), link.sessionId,
            fragmentIndex = 0, fragmentCount = 0, sequence = link.sendIndex++ and 0xFFFF, payload = payload)
        sendSealed(link, frame)
    }

    private fun sendFramesRaw(link: Link, type: Int, payload: ByteArray, retain: Boolean) {
        val capacity = ParalinkBluetoothConstants.fragmentCapacity(link.mtu.coerceAtLeast(ParalinkBluetoothConstants.MIN_MTU))
        val packetId = (scannerKnob.nextInt().toLong() and 0xFFFFFFFFL)
        val key = link.sessionKey ?: zeroKey
        val slices = Fragmenter.fragment(payload, capacity, packetId, link.sessionId, link.sendIndex++ and 0xFFFF, type)
        for (s in slices) {
            val frame = Fragmenter.frameFor(s).copy(flags = (if (retain) s.flags or FrameFlags.RETAIN else s.flags))
            val sealed = ProtocolSecurity.sealed(frame, key)
            if (retain) link.ackPending["${frame.packetId}/${frame.fragmentIndex}"] = sealed
            enqueue(link, sealed)
        }
    }

    private fun sendSealed(link: Link, frame: ParalinkFrame) {
        val key = link.sessionKey ?: zeroKey
        enqueue(link, ProtocolSecurity.sealed(frame, key))
    }

    private fun enqueue(link: Link, sealed: ByteArray) {
        link.outbox.addLast(sealed)
        pumpSender(link)
    }

    /** Serialized sender: one BLE write/notify in flight at a time. */
    private fun pumpSender(link: Link) {
        if (link.sending) return
        link.sending = true
        handler.post { pumpLoop(link) }
    }

    private fun pumpLoop(link: Link) {
        while (true) {
            val nextFrame = link.outbox.removeFirstOrNull() ?: break
            val parsed = FrameCodec.parse(nextFrame)
            val frameType = parsed?.first?.type ?: FrameTypes.MESSAGE
            if (link.legIsClient) {
                val gatt = link.client ?: break
                val char = link.clientChars[charForType(frameType)] ?: continue
                link.pendingSent = nextFrame
                writeClientChunk(gatt, char, nextFrame)
                return // resume from onCharacteristicWrite (single flight)
            } else {
                val server = gattServer ?: break
                val device = link.serverDevice ?: break
                val ok = notifyServerChunk(server, device, charForType(frameType), nextFrame)
                if (!ok) diagnostics.log("Notify failed ${link.address}")
            }
        }
        link.sending = false
    }

    private fun writeClientChunk(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                char.value = bytes
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                gatt.writeCharacteristic(char)
            }
        }
    }

    private fun notifyServerChunk(server: BluetoothGattServer, device: BluetoothDevice, uuid: UUID, bytes: ByteArray): Boolean {
        val char = server.getService(ParalinkBluetoothConstants.SERVICE_UUID)?.getCharacteristic(uuid) ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            server.notifyCharacteristicChanged(device, char, false, bytes) == BluetoothGatt.GATT_SUCCESS
        } else {
            runCatching {
                val m = BluetoothGattServer::class.java.getMethod(
                    "notifyCharacteristic",
                    BluetoothDevice::class.java,
                    BluetoothGattCharacteristic::class.java,
                    Boolean::class.javaPrimitiveType,
                    ByteArray::class.java
                )
                m.invoke(server, device, char, false, bytes) as Boolean
            }.getOrDefault(false)
        }
    }

    // ---------------------------------------------------------------------
    // Housekeeping + retransmit sweep
    // ---------------------------------------------------------------------
    private val sweepRunnable = object : Runnable {
        override fun run() {
            retransmitSweep()
            if (running) handler.postDelayed(this, 1000L)
        }
    }

    private fun retransmitSweep() {
        handler.post {
            if (!running) return@post
            val now = System.currentTimeMillis()
            links.values.forEach { link ->
                val expired = link.ackPending.entries.filter { it.value != null && (now - link.lastSentAt(it.key)) > 2500 }
                expired.forEach { (key, sealed) ->
                    val parts = key.split("/")
                    if (parts.size == 2) {
                        val attemptCount = link.retryCount(key)
                        if (attemptCount < maxRetries) {
                            link.markRetry(key)
                            link.outbox.addLast(sealed) // retransmit same frame (same packetId -> peer dedups)
                            pumpSender(link)
                        } else {
                            diagnostics.log("Give up on fragment $key (${link.address})")
                            link.ackPending.remove(key)
                            onLost(link.address)
                        }
                    }
                }
            }
        }
    }

    private val maxRetries = 4

    private fun Link.lastSentAt(key: String): Long = retryStamp[key] ?: System.currentTimeMillis()
    private val retryStamp = ConcurrentHashMap<String, Long>()
    private fun Link.retryCount(key: String): Int = retryCounts[key] ?: 0
    private val retryCounts = ConcurrentHashMap<String, Int>()
    private fun Link.markRetry(key: String) {
        val c = retryCounts[key] ?: 0
        retryCounts[key] = c + 1
        retryStamp[key] = System.currentTimeMillis()
    }

    private fun closeLink(link: Link?) {
        link ?: return
        holdClose(link)
    }

    private fun holdClose(link: Link) {
        if (link.state == LinkState.CONNECTED || link.state.securityEstablished) {
            diagnostics.log("Link ${link.address} closed (was ${link.state})")
            onLost(link.address)
        }
        setState(link, LinkState.DISCONNECTING, null)
        runCatching { link.client?.disconnect() }
        runCatching { link.client?.close() }
        link.client = null
        link.outbox.clear()
        link.ackPending.clear()
        link.state = LinkState.DISCONNECTED
        link.sessionConfirmed = false
        link.sessionKey = null
    }

    private fun setState(link: Link, state: LinkState, detail: String?) {
        val prev = link.state
        link.state = state
        link.errorDetail = detail
        if (detail != null) diagnostics.log("${link.address}: $state ($detail)")
        else if (prev != state) diagnostics.log("${link.address}: $state")
        onState(link.address, state, detail)
    }

    fun openAddresses(): Set<String> = links.entries.filter { it.value.state == LinkState.CONNECTED }.map { it.key }.toSet()

    fun isOpen(address: String): Boolean = links[address]?.state == LinkState.CONNECTED

    fun stateFor(address: String): LinkState = links[address]?.state ?: LinkState.IDLE

    fun nearby(): List<NearbyDevice> = nearby.values.filter { System.currentTimeMillis() - it.lastSeen < 30000 }.toList()

    fun rssiFor(address: String): Int = nearby[address]?.rssi ?: -127

    private fun hasScanConnectPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private val crypto = SessionCrypto()
}