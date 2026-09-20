package com.paralink.app.connectivity.bluetooth

import java.util.UUID

/**
 * Central definition of every PARALINK Bluetooth identifier.
 * The same service and characteristics are used by the iOS CoreBluetooth
 * implementation so Android and iOS talk to the same GATT service.
 */
object ParalinkBluetoothConstants {

    const val PROTOCOL_VERSION = 1
    const val PARALINK_NAME = "PARALINK"

    /**
     * Primary service. Stable — never regenerate. Both Android and iOS
     * advertise and scan for exactly this UUID.
     */
    val SERVICE_UUID: UUID = UUID.fromString("d133e46c-2e74-4e53-8c4e-7f2d3a1b9c11")

    // Dedicated characteristics (beyond the legacy RX/TX pair).
    val DISCOVERY_CHAR: UUID = UUID.fromString("d133e46c-2e74-4e53-8c4e-7f2d3a1b9c14")
    val IDENTITY_CHAR: UUID = UUID.fromString("d133e46c-2e74-4e53-8c4e-7f2d3a1b9c15")
    val CONTROL_CHAR: UUID = UUID.fromString("d133e46c-2e74-4e53-8c4e-7f2d3a1b9c16")
    val MESSAGE_CHAR: UUID = UUID.fromString("d133e46c-2e74-4e53-8c4e-7f2d3a1b9c17")
    val FILE_CHAR: UUID = UUID.fromString("d133e46c-2e74-4e53-8c4e-7f2d3a1b9c18")
    val CALL_CHAR: UUID = UUID.fromString("d133e46c-2e74-4e53-8c4e-7f2d3a1b9c19")
    val ACK_CHAR: UUID = UUID.fromString("d133e46c-2e74-4e53-8c4e-7f2d3a1b9c1a")

    /** Legacy stream chars kept for the previous transport; not used by GATT v2. */
    val LEGACY_RX_CHAR: UUID = UUID.fromString("d133e46c-2e74-4e53-8c4e-7f2d3a1b9c12")
    val LEGACY_TX_CHAR: UUID = UUID.fromString("d133e46c-2e74-4e53-8c4e-7f2d3a1b9c13")

    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Manufacturer specific data in the advertisement: "PRLK" + version + caps + shortId. */
    const val MANUFACTURER_ID = 0x5040
    val AD_MAGIC = byteArrayOf(0x50, 0x52, 0x4C, 0x4B) // "PRLK"

    /** Target ATT MTU requested by the GATT client. */
    const val TARGET_MTU = 512

    /** Absolute minimum MTU that can carry a frame header + 1 payload byte + tag. */
    const val MIN_MTU = 42

    const val FrameHeaderSize = 22
    const val AuthTagSize = 16

    /** Bytes reserved per frame: header (22) + auth tag (16). */
    const val FRAME_OVERHEAD = FrameHeaderSize + AuthTagSize

    /** Fragment payload capacity for a negotiated MTU. */
    fun fragmentCapacity(mtu: Int): Int =
        ((mtu.coerceAtLeast(MIN_MTU) - 3) - FRAME_OVERHEAD).coerceAtLeast(1)

    /** Capability bits carried in advertising and identity. */
    object Capabilities {
        const val TEXT = 1 shl 0
        const val VOICE = 1 shl 1
        const val FILE = 1 shl 2
        const val CALL = 1 shl 3
        const val GAME = 1 shl 4
        const val ALL = TEXT or VOICE or FILE or CALL or GAME
    }
}