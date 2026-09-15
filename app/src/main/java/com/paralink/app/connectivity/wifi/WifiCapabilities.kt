package com.paralink.app.connectivity.wifi

import android.content.Context
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.content.pm.PackageManager

class WifiCapabilities(private val context: Context) {
    fun isWifiDirectSupported(): Boolean =
        context.getSystemService(Context.WIFI_P2P_SERVICE) is WifiP2pManager

    fun isWifiAwareSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.getSystemService(WifiAwareManager::class.java)?.isAvailable == true

    fun summary(): Map<String, Boolean> = mapOf(
        "wifiDirect" to isWifiDirectSupported(),
        "wifiAware" to isWifiAwareSupported(),
        "nearbyWifiPermission" to if (Build.VERSION.SDK_INT >= 33) {
            context.checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES") == PackageManager.PERMISSION_GRANTED
        } else true
    )
}
