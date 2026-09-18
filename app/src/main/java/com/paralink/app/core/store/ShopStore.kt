package com.paralink.app.core.store

import android.content.Context
import com.paralink.app.R
import com.paralink.app.core.storage.LedgerStore

object ShopItems {
    data class Item(val id: String, val nameRes: Int, val descRes: Int, val cost: Double, val icon: String)

    val LIST = listOf(
        Item("star", R.string.shop_star, R.string.shop_star_desc, 4.0, "★"),
        Item("gold", R.string.shop_gold, R.string.shop_gold_desc, 10.0, "✦"),
        Item("boost", R.string.shop_boost, R.string.shop_boost_desc, 5.0, "⏫"),
        Item("scan", R.string.shop_scan, R.string.shop_scan_desc, 8.0, "⭍")
    )

    fun byId(id: String): Item? = LIST.firstOrNull { it.id == id }
}

class ShopStore(context: Context) {
    companion object {
        private const val BOOST_MS = 10L * 60_000L
    }

    private val prefs = context.getSharedPreferences("paralink_shop", Context.MODE_PRIVATE)

    fun owns(itemId: String): Boolean = prefs.getBoolean("owned_$itemId", false)

    fun boostExpiresAt(): Long = prefs.getLong("boost_expires", 0L)

    fun boostActive(): Boolean = System.currentTimeMillis() < boostExpiresAt()

    fun buy(item: ShopItems.Item, ledger: LedgerStore): Boolean {
        if (owns(item.id)) return false
        if (!ledger.debit(item.cost, "shop", item.id)) return false
        prefs.edit().putBoolean("owned_${item.id}", true).apply()
        if (item.id == "boost") {
            prefs.edit().putLong("boost_expires", System.currentTimeMillis() + BOOST_MS).apply()
        }
        return true
    }
}