package com.paralink.app.connectivity.bluetooth

import android.bluetooth.BluetoothAdapter
import android.content.Context

class BluetoothCapabilities(context: Context) {
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    val supported: Boolean get() = adapter != null
    val enabled: Boolean get() = adapter?.isEnabled == true
}
