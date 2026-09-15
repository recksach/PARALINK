package com.paralink.app.core.mesh

data class MeshPacket(
    val kind: String,
    val id: String,
    val src: String,
    val dst: String,
    val ttl: Int,
    val payload: String
)

object PacketCodec {

    const val MARKER = "PKT"
    const val VERSION = "1"

    fun encode(packet: MeshPacket): String =
        listOf(MARKER, VERSION, packet.id, packet.src, packet.dst, packet.ttl.toString(), packet.kind, packet.payload)
            .joinToString("|")

    fun decode(line: String): MeshPacket? {
        if (!line.startsWith("$MARKER|")) return null
        val parts = line.split('|')
        if (parts.size < 8) return null
        val ttl = parts[5].toIntOrNull() ?: return null
        val payload = parts.drop(7).joinToString("|")
        return MeshPacket(parts[6], parts[2], parts[3], parts[4], ttl, payload)
    }
}

object PacketKinds {
    const val HELLO = "HELLO"
    const val TXT = "TXT"
    const val VOICE = "VOICE"
}

object RelayCore {

    const val BROADCAST = "BROADCAST"
    const val MAX_TTL = 3

    fun shouldDeliverLocally(packet: MeshPacket, me: String): Boolean =
        packet.dst == BROADCAST || packet.dst == me

    fun shouldForward(packet: MeshPacket, me: String): Boolean =
        packet.dst != me && !packet.ttl.let { it <= 0 }

    fun nextHop(packet: MeshPacket): MeshPacket = packet.copy(ttl = packet.ttl - 1)
}