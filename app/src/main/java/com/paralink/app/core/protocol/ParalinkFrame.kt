package com.paralink.app.core.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * PARALINK binary link protocol.
 *
 * Every frame transmitted over BLE GATT has the exact fixed header below
 * (big-endian), shared by Android and iOS implementations. Large payloads are
 * split into fragments; each fragment repeats the header and carries one slice
 * of the logical payload with its [fragmentIndex] / [fragmentCount] and a
 * fragment local [packetId]. The receiving side reassembles by [packetId] and
 * acknowledges each fragment individually so transfers can resume after a
 * link drop.
 *
 * Wire layout:
 *
 *   0   4  magic       "PRLK" (0x50524C4B)
 *   4   1  version     1
 *   5   1  type        see FrameTypes
 *   6   1  flags       see FrameFlags
 *   7   1  ttl         mesh hop budget (0 = direct link)
 *   8   4  packetId    uint32, unique within a session (fragments share it)
 *   12  2  sessionId   random per physical link, negotiated during handshake
 *   14  2  fragmentIndex
 *   16  2  fragmentCount (0 = single part)
 *   18  2  sequence    monotonically increasing per datagram
 *   20  2  payloadLength
 *   22  n  payload
 *  22+n 16 authTag     HMAC-SHA256 truncated to 16 bytes over header+payload
 */
object FrameTypes {
    const val UNKNOWN = 0
    const val DISCOVERY = 1
    const val HELLO = 2
    const val HELLO_RESP = 3
    const val IDENTITY = 4
    const val IDENTITY_RESP = 5
    const val KEY_EXCHANGE = 6
    const val AUTH = 7
    const val SESSION = 8
    const val MESSAGE = 9
    const val FILE = 10
    const val VOICE = 11
    const val CALL = 12
    const val ACK = 13
    const val PING = 14
    const val PONG = 15
    const val ERROR = 16
    const val BYE = 17
    const val GAME = 18
}

object FrameFlags {
    const val NONE = 0
    const val FRAGMENTED = 1 shl 0
    const val FRAG_FIRST = 1 shl 1
    const val FRAG_LAST = 1 shl 2
    const val RETAIN = 1 shl 3 // receiver MUST send an ACK back
    const val RETRANSMIT = 1 shl 4
}

data class ParalinkFrame(
    val type: Int,
    val flags: Int,
    val ttl: Int,
    val packetId: Long, // uint32
    val sessionId: Int,
    val fragmentIndex: Int,
    val fragmentCount: Int, // 0 = single part
    val sequence: Int,
    val payload: ByteArray
) {
    val fragmented: Boolean get() = fragmentCount > 0

    /** For an ACK frame the payload is [packetId, fragmentIndex] 8 bytes. */
    fun ackTarget(): Pair<Long, Int>? =
        if (type == FrameTypes.ACK && payload.size == 8) {
            val pid = ((payload[0].toLong() and 0xFF) shl 24) or
                ((payload[1].toLong() and 0xFF) shl 16) or
                ((payload[2].toLong() and 0xFF) shl 8) or
                (payload[3].toLong() and 0xFF)
            val fi = ((payload[4].toInt() and 0xFF) shl 8) or
                (payload[5].toInt() and 0xFF)
            pid to fi
        } else null
}

object FrameCodec {
    const val MAGIC = 0x50524C4B // "PRLK"
    const val VERSION = 1
    const val HEADER_SIZE = 22
    const val AUTH_TAG_SIZE = 16
    const val MAX_PAYLOAD_LEN = 0xFFFF

    /** Builds header + payload (without auth tag) — the MAC input. */
    fun headerAndPayload(frame: ParalinkFrame): ByteArray {
        val p = frame.payload
        val out = ByteArrayOutputStream(HEADER_SIZE + p.size)
        DataOutputStream(out).use { d ->
            d.writeInt(MAGIC)
            d.writeByte(VERSION)
            d.writeByte(frame.type)
            d.writeByte(frame.flags)
            d.writeByte(frame.ttl)
            d.writeInt(frame.packetId.toInt())
            d.writeShort(frame.sessionId)
            d.writeShort(frame.fragmentIndex)
            d.writeShort(frame.fragmentCount)
            d.writeShort(frame.sequence)
            d.writeShort(p.size)
            d.write(p)
        }
        return out.toByteArray()
    }

    /** Full wire frame = headerAndPayload + authTag. */
    fun build(frame: ParalinkFrame, authTag: ByteArray): ByteArray {
        return headerAndPayload(frame) + authTag
    }

    /** Parses a wire frame. Returns null on any structural error. */
    fun parse(bytes: ByteArray): Pair<ParalinkFrame, ByteArray>? {
        if (bytes.size < HEADER_SIZE + AUTH_TAG_SIZE) return null
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { r ->
                val magic = r.readInt()
                if (magic != MAGIC) return null
                val version = r.readUnsignedByte()
                if (version != VERSION) return null
                val type = r.readUnsignedByte()
                val flags = r.readUnsignedByte()
                val ttl = r.readUnsignedByte()
                val packetId = r.readInt().toLong() and 0xFFFFFFFFL
                val sessionId = r.readUnsignedShort()
                val fragmentIndex = r.readUnsignedShort()
                val fragmentCount = r.readUnsignedShort()
                val sequence = r.readUnsignedShort()
                val len = r.readUnsignedShort()
                if (bytes.size != HEADER_SIZE + len + AUTH_TAG_SIZE) return null
                val payload = ByteArray(len)
                r.readFully(payload)
                val authTag = ByteArray(AUTH_TAG_SIZE)
                r.readFully(authTag)
                Pair(
                    ParalinkFrame(type, flags, ttl, packetId, sessionId, fragmentIndex, fragmentCount, sequence, payload),
                    authTag
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun packetIdForAck(packetId: Long, fragmentIndex: Int): ByteArray {
        val out = ByteArray(8)
        out[0] = ((packetId ushr 24) and 0xFF).toByte()
        out[1] = ((packetId ushr 16) and 0xFF).toByte()
        out[2] = ((packetId ushr 8) and 0xFF).toByte()
        out[3] = (packetId and 0xFF).toByte()
        out[4] = ((fragmentIndex ushr 8) and 0xFF).toByte()
        out[5] = (fragmentIndex and 0xFF).toByte()
        out[6] = 0
        out[7] = 0
        return out
    }
}