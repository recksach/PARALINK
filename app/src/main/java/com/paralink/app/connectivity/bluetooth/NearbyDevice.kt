package com.paralink.app.connectivity.bluetooth

/**
 * Real detected remote device as surfaced to the UI. Populated from actual
 * BLE scan results and the PARALINK identity exchange — never from mocks.
 */
data class NearbyDevice(
    val address: String,
    val deviceId: String?, // PARALINK node id, resolved after identity exchange
    val name: String,
    val rssi: Int,
    val lastSeen: Long,
    val connectionState: LinkState,
    val serviceFound: Boolean = true,
    val protocolVersion: Int = 0,
    val capabilities: Int = 0,
    val mtu: Int = 0
)

/** State machine for a single PARALINK Bluetooth link. */
enum class LinkState {
    IDLE,
    DISCOVERING,
    CONNECTING,
    DISCOVERED,
    HANDSHAKING,
    AUTHENTICATING,
    KEY_EXCHANGE,
    SECURE_SESSION,
    CONNECTED,
    TRANSFERRING,
    CALLING,
    DISCONNECTING,
    DISCONNECTED,
    ERROR;

    val securityEstablished: Boolean
        get() = this == SECURE_SESSION || this == CONNECTED || this == TRANSFERRING || this == CALLING
}

data class NearbyFilter(
    val text: String? = null,
    val caps: Int = 0
)