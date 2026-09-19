package com.paralink.app.connectivity.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE transport for PARALINK. Implements a GATT server + BLE scanner + GATT
 * client so phones can exchange the same encrypted PKT lines as TCP/RFCOMM.
 *
 * Every phone runs both a peripheral (server) and a central (client) role at
 * the same time. Lines are framed with '\n' and split into MTU-sized chunks
 * (the line is rebuilt with a ByteArrayOutputStream on the other side).
 */
@SuppressLint("MissingPermission")
class BleTransport(private val context: Context) {

    companion object {
        val SERVICE = UUID.fromString("d133e46c-2e74-4e53-8c4e-7f2d3a1b9c11")
        val RX = UUID.fromString("d133e46c-2e74-4e53-8c4e-7f2d3a1b9c12")
        val TX = UUID.fromString("d133e46c-2e74-4e53-8c4e-7f2d3a1b9c13")
        const val TARGET_MTU = 512
        const val MIN_CHUNK = 20
    }

    var onLine: (String) -> Unit = {}
    var onLink: (String) -> Unit = {}
    var onLost: (String) -> Unit = {}
    var onFound: ((String, String) -> Unit)? = null

    private val manager = runCatching {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }.getOrNull()
    private val adapter: BluetoothAdapter? = manager?.adapter

    private var gattServer: BluetoothGattServer? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    private val links = ConcurrentHashMap<String, Link>()
    private val linkKind = ConcurrentHashMap<String, String>()
    private val openAddrs = ConcurrentHashMap.newKeySet<String>()
    private val pendingAddrs = ConcurrentHashMap.newKeySet<String>()
    private val clientGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val clientRxChars = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
    private val serverDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val mtus = ConcurrentHashMap<String, Int>()
    private val rxBufs = ConcurrentHashMap<String, ByteArrayOutputStream>()
    private val seenAddrs = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var running = false
    @Volatile private var scanning = false
    @Volatile private var advertising = false
    @Volatile private var serverUp = false

    private class Link(val address: String, val send: (ByteArray) -> Unit) {
        val lock = Any()
    }

    fun available(): Boolean = adapter != null

    fun enabled(): Boolean = adapter?.isEnabled == true

    fun start() {
        if (!available() || running) return
        running = true
        startServer()
        startAdvertising()
        startScan()
    }

    fun stop() {
        running = false
        runCatching { if (scanning) scanner?.stopScan(scanCb) }
        scanning = false
        runCatching { if (advertising) advertiser?.stopAdvertising(advCb) }
        advertising = false
        clientGatts.values.forEach { runCatching { it.disconnect() }; runCatching { it.close() } }
        clientGatts.clear()
        clientRxChars.clear()
        serverDevices.clear()
        runCatching { gattServer?.close() }
        gattServer = null
        txChar = null
        serverUp = false
        links.clear(); linkKind.clear(); openAddrs.clear(); pendingAddrs.clear()
        mtus.clear(); rxBufs.clear(); seenAddrs.clear()
    }

    fun connect(address: String) {
        if (!running || !available()) return
        if (openAddrs.contains(address) || pendingAddrs.contains(address)) return
        if (!pendingAddrs.add(address)) return
        runCatching {
            val dev = adapter?.getRemoteDevice(address) ?: return
            dev.connectGatt(context, false, clientCb(dev), BluetoothDevice.TRANSPORT_LE)
        }.onFailure { pendingAddrs.remove(address) }
    }

    fun openAddresses(): Set<String> = openAddrs.toSet()

    fun isOpen(address: String): Boolean = openAddrs.contains(address)

    /** Sends one full line (a single encrypted packet) to a remote address. */
    fun send(address: String, line: String) {
        val link = links[address] ?: return
        val data = (if (line.endsWith("\n")) line else line + "\n").toByteArray(Charsets.UTF_8)
        val chunk = ((mtus[address] ?: 23) - 3).coerceAtLeast(MIN_CHUNK)
        synchronized(link.lock) {
            try {
                var off = 0
                while (off < data.size) {
                    val end = minOf(data.size, off + chunk)
                    link.send(data.copyOfRange(off, end))
                    off = end
                }
            } catch (e: Exception) {
                onLost(address)
            }
        }
    }

    private fun startServer() {
        if (serverUp) return
        runCatching {
            val server = manager?.openGattServer(context, serverCb) ?: return
            val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val rx = BluetoothGattCharacteristic(
                RX,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            val tx = BluetoothGattCharacteristic(TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
            service.addCharacteristic(rx)
            service.addCharacteristic(tx)
            server.addService(service)
            gattServer = server
            txChar = tx
            serverUp = true
        }
    }

    private fun startAdvertising() {
        val adv = adapter?.bluetoothLeAdvertiser ?: return
        advertiser = adv
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(SERVICE))
            .build()
        runCatching {
            adv.startAdvertising(settings, data, advCb)
            advertising = true
        }
    }

    private fun startScan() {
        val sc = adapter?.bluetoothLeScanner ?: return
        scanner = sc
        if (scanning) return
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { sc.startScan(filters, settings, scanCb); scanning = true }
    }

    /** Rebuilds lines from arbitrary chunk boundaries and delivers them. */
    private fun feed(address: String, bytes: ByteArray) {
        if (!running || bytes.isEmpty()) return
        val buf = rxBufs.getOrPut(address) { ByteArrayOutputStream() }
        synchronized(buf) {
            buf.write(bytes)
            val all = buf.toByteArray()
            var consumed = 0
            var i = 0
            while (i < all.size) {
                if (all[i] == 10.toByte()) {
                    val line = String(all, consumed, i - consumed, Charsets.UTF_8).trimEnd('\r')
                    if (line.isNotBlank()) onLine(line)
                    consumed = i + 1
                }
                i++
            }
            if (consumed > 0) {
                val rest = all.copyOfRange(consumed, all.size)
                buf.reset()
                buf.write(rest)
            }
        }
    }

    private fun registerServerWriter(address: String, device: BluetoothDevice) {
        if (linkKind[address] == "server") return
        val txc = txChar ?: return
        links[address] = Link(address) { bytes ->
            val ok = notifyServerChunk(device, txc, bytes)
            if (!ok) throw IllegalStateException("notify failed")
        }
        linkKind[address] = "server"
        serverDevices[address] = device
        val fresh = openAddrs.add(address)
        if (fresh) {
            pendingAddrs.remove(address)
            onLink(address)
        }
    }

    private fun registerClientWriter(address: String, gatt: BluetoothGatt, rx: BluetoothGattCharacteristic) {
        if (links.containsKey(address)) {
            openAddrs.add(address)
            pendingAddrs.remove(address)
            return
        }
        links[address] = Link(address) { bytes -> writeClientChunk(gatt, rx, bytes) }
        linkKind[address] = "client"
        val fresh = openAddrs.add(address)
        if (fresh) {
            pendingAddrs.remove(address)
            onLink(address)
        }
    }

    /** GATT client write. The 3-arg overload is API 33+; the old 2-arg PDU was
     *  removed from recent SDK stubs, so keep the value-set + 1-arg path for <33. */
    private fun writeClientChunk(gatt: BluetoothGatt, rx: BluetoothGattCharacteristic, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(rx, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                rx.value = bytes
                gatt.writeCharacteristic(rx)
            }.getOrThrow()
        }
    }

    /** GATT server notification. API 33+ uses notifyCharacteristicChanged; on
     *  older devices fall back to the legacy notifyCharacteristic via reflection
     *  because the old member is no longer present in compile-time SDK stubs. */
    private fun notifyServerChunk(device: BluetoothDevice, txc: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            val server = gattServer ?: return false
            return server.notifyCharacteristicChanged(device, txc, true, bytes)
        }
        return runCatching {
            val server = gattServer ?: return@runCatching false
            val m = BluetoothGattServer::class.java.getMethod(
                "notifyCharacteristic",
                BluetoothDevice::class.java,
                BluetoothGattCharacteristic::class.java,
                Boolean::class.javaPrimitiveType,
                ByteArray::class.java
            )
            m.invoke(server, device, txc, true, bytes) as Boolean
        }.getOrDefault(false)
    }

    private fun unregister(address: String) {
        links.remove(address); linkKind.remove(address); openAddrs.remove(address)
        rxBufs.remove(address); pendingAddrs.remove(address)
        onLost(address)
    }

    private fun closeClientLink(address: String) {
        pendingAddrs.remove(address)
        clientGatts.remove(address)?.let { runCatching { it.close() } }
        if (linkKind[address] == "client") {
            unregister(address)
            // server leg may still be alive for this address
            serverDevices[address]?.let { registerServerWriter(address, it) }
        }
    }

    private fun closeServerLink(address: String) {
        serverDevices.remove(address)
        if (linkKind[address] == "server") {
            unregister(address)
            // client leg may still be alive for this address
            val gatt = clientGatts[address]
            val rx = clientRxChars[address]
            if (gatt != null && rx != null) registerClientWriter(address, gatt, rx)
        }
    }

    private fun clientCb(dev: BluetoothDevice) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    clientGatts[dev.address] = gatt
                    if (openAddrs.contains(dev.address)) { runCatching { gatt.disconnect() }; return }
                    runCatching { gatt.requestMtu(TARGET_MTU) }
                }
                BluetoothProfile.STATE_DISCONNECTED -> closeClientLink(dev.address)
                else -> pendingAddrs.remove(dev.address)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            mtus[dev.address] = mtu.coerceAtLeast(23)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { runCatching { gatt.disconnect() }; return }
            val service = gatt.getService(SERVICE)
            if (service == null) { runCatching { gatt.disconnect() }; return }
            val rx = service.getCharacteristic(RX)
            val tx = service.getCharacteristic(TX)
            if (rx == null || tx == null) { runCatching { gatt.disconnect() }; return }
            runCatching {
                gatt.setCharacteristicNotification(tx, true)
                tx.descriptors.firstOrNull { it.uuid == CCCD }?.let { desc ->
                    val enable = byteArrayOf(0x01)
                    if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(desc, enable)
                    else @Suppress("DEPRECATION") gatt.writeDescriptor(desc, enable)
                }
            }
            registerClientWriter(dev.address, gatt, rx)
            clientRxChars[dev.address] = rx
        }

        @Deprecated("deprecated")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = runCatching { characteristic.getValue() }.getOrNull()
            if (characteristic.uuid == TX && value != null) feed(dev.address, value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == TX) feed(dev.address, value)
        }
    }

    private val serverCb = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState != BluetoothProfile.STATE_CONNECTED) {
                closeServerLink(device.address)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (characteristic.uuid != RX) return
            if (responseNeeded) runCatching {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            if (value.isEmpty()) return
            registerServerWriter(device.address, device)
            feed(device.address, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (responseNeeded) runCatching {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            mtus[device.address] = mtu.coerceAtLeast(23)
        }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val addr = dev.address
            if (!seenAddrs.contains(addr)) {
                seenAddrs.add(addr)
                onFound?.invoke(result.scanRecord?.deviceName ?: dev.name ?: "PARALINK NODE", addr)
            }
            if (!openAddrs.contains(addr)) connect(addr)
        }

        override fun onScanFailed(errorCode: Int) { scanning = false }
    }

    private val advCb = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) = Unit
        override fun onStartFailure(errorCode: Int) {
            advertising = false
            runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(this) }
        }
    }

    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}