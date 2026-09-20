package com.paralink.app.connectivity.bluetooth

import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory diagnostics for the Bluetooth layer. Backed by a bounded ring of
 * timestamped events so Settings -> Advanced -> Bluetooth Diagnostics can show
 * what is really happening on the wire.
 */
class BluetoothDiagnostics {
    private data class Entry(val time: Long, val text: String)

    private val events = CopyOnWriteArrayList<Entry>()
    private val maxEntries = 400

    @Volatile var scannerRunning = false; private set
    @Volatile var advertiserRunning = false; private set
    @Volatile var gattServerRunning = false; private set

    fun log(message: String) {
        events.add(Entry(System.currentTimeMillis(), message))
        while (events.size > maxEntries) events.removeAt(0)
    }

    fun setScanner(running: Boolean) { scannerRunning = running; log(if (running) "Scanner: RUNNING" else "Scanner: IDLE") }
    fun setAdvertiser(running: Boolean) { advertiserRunning = running; log(if (running) "Advertiser: RUNNING" else "Advertiser: IDLE") }
    fun setGattServer(running: Boolean) { gattServerRunning = running; log(if (running) "GATT server: RUNNING" else "GATT server: IDLE") }

    /** Immutable snapshot for the UI. */
    fun snapshot(): List<String> = events.map { "[${fmt(it.time)}] ${it.text}" }

    fun clear() = events.clear()

    private fun fmt(ms: Long): String {
        val h = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return "%02d:%02d:%02d".format(h.get(java.util.Calendar.HOUR_OF_DAY), h.get(java.util.Calendar.MINUTE), h.get(java.util.Calendar.SECOND))
    }
}