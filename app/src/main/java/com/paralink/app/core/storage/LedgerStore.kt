package com.paralink.app.core.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class LedgerEntry(
    val id: String,
    val type: String,
    val peerId: String,
    val amount: Double,
    val note: String,
    val timestamp: Long
)

class LedgerStore(context: Context) {

    private val prefs = context.getSharedPreferences("paralink_ledger", Context.MODE_PRIVATE)
    private var balance: Double = 0.0
    private var accrualStart: Long = 0L
    private var connectedCount: Int = 0
    private val ratePerMin = 0.05
    private val entries = mutableListOf<LedgerEntry>()

    init { load() }

    @Synchronized
    fun balance(): Double = balance

    @Synchronized
    fun transactions(): List<LedgerEntry> = entries.toList()

    @Synchronized
    fun connectedCount(): Int = connectedCount

    @Synchronized
    fun currentBalance(now: Long): Double =
        if (accrualStart > 0) {
            val elapsedMin = (now - accrualStart) / 60000.0
            balance + connectedCount * elapsedMin * ratePerMin
        } else balance

    @Synchronized
    fun setConnectedCount(count: Int) {
        val now = System.currentTimeMillis()
        if (count > 0 && connectedCount == 0) {
            accrualStart = now
            connectedCount = count
            save()
        } else if (count == 0 && connectedCount > 0) {
            flushAccrual(now)
            connectedCount = 0
            accrualStart = 0L
            save()
        } else {
            connectedCount = count
        }
    }

    @Synchronized
    fun credit(amount: Double, peerId: String, note: String = "") {
        if (amount <= 0) return
        balance += amount
        addEntry("IN", amount, peerId, note)
        save()
    }

    @Synchronized
    fun debit(amount: Double, peerId: String, note: String = ""): Boolean {
        if (amount <= 0) return false
        flushAccrual(System.currentTimeMillis())
        if (balance < amount - 0.00001) return false
        balance -= amount
        addEntry("OUT", amount, peerId, note)
        save()
        return true
    }

    private fun flushAccrual(now: Long) {
        if (accrualStart <= 0 || connectedCount <= 0) return
        val elapsedMin = (now - accrualStart) / 60000.0
        val earned = connectedCount * elapsedMin * ratePerMin
        accrualStart = now
        if (earned > 0) {
            balance += earned
            addEntry("EARNED", earned, "network", "Connection bonus")
        }
    }

    private fun addEntry(type: String, amount: Double, peerId: String, note: String) {
        entries.add(LedgerEntry(UUID.randomUUID().toString(), type, peerId, amount, note, System.currentTimeMillis()))
        if (entries.size > 200) entries.removeAt(0)
    }

    private fun save() {
        val tx = JSONArray()
        entries.forEach { e ->
            tx.put(JSONObject().apply {
                put("id", e.id); put("type", e.type); put("peerId", e.peerId)
                put("amount", e.amount); put("note", e.note); put("timestamp", e.timestamp)
            })
        }
        prefs.edit()
            .putString("balance", balance.toString())
            .putString("accrual", accrualStart.toString())
            .putString("count", connectedCount.toString())
            .putString("tx", tx.toString())
            .apply()
    }

    private fun load() {
        balance = prefs.getString("balance", "0")?.toDoubleOrNull() ?: 0.0
        accrualStart = prefs.getString("accrual", "0")?.toLongOrNull() ?: 0L
        connectedCount = prefs.getString("count", "0")?.toIntOrNull() ?: 0
        if (accrualStart > 0) {
            flushAccrual(System.currentTimeMillis())
            connectedCount = 0
            accrualStart = 0L
            save()
        }
        entries.clear()
        runCatching {
            val arr = JSONArray(prefs.getString("tx", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                entries.add(LedgerEntry(
                    id = o.getString("id"),
                    type = o.getString("type"),
                    peerId = o.getString("peerId"),
                    amount = o.getDouble("amount"),
                    note = o.optString("note", ""),
                    timestamp = o.getLong("timestamp")
                ))
            }
        }
    }
}