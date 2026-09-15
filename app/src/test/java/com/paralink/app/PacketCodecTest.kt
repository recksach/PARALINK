package com.paralink.app

import com.paralink.app.core.mesh.MeshPacket
import com.paralink.app.core.mesh.PacketCodec
import com.paralink.app.core.mesh.PacketKinds
import com.paralink.app.core.mesh.RelayCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketCodecTest {

    @Test
    fun roundTrip() {
        val packet = MeshPacket(PacketKinds.TXT, "id-1", "A", "BROADCAST", 3, "hello world")
        val decoded = PacketCodec.decode(PacketCodec.encode(packet))!!
        assertEquals(packet, decoded)
    }

    @Test
    fun payloadWithSeparatorsSurvives() {
        val packet = MeshPacket(PacketKinds.VOICE, "id-2", "A", "B", 3, "STUFF|with|pipes|---")
        val decoded = PacketCodec.decode(PacketCodec.encode(packet))!!
        assertEquals("STUFF|with|pipes|---", decoded.payload)
    }

    @Test
    fun rejectMalformedLine() {
        assertNull(PacketCodec.decode("NOPE|1|x"))
        assertNull(PacketCodec.decode("PKT|1|id|A|B|zzz|TXT|p"))
    }

    @Test
    fun broadcastDeliveredLocally() {
        val p = MeshPacket(PacketKinds.HELLO, "id", "A", RelayCore.BROADCAST, 3, "k")
        assertTrue(RelayCore.shouldDeliverLocally(p, "C"))
    }

    @Test
    fun directDeliveredOnlyToTarget() {
        val p = MeshPacket(PacketKinds.TXT, "id", "A", "B", 3, "msg")
        assertTrue(RelayCore.shouldDeliverLocally(p, "B"))
        assertTrue(!RelayCore.shouldDeliverLocally(p, "C"))
        assertTrue(RelayCore.shouldForward(p, "C"))
    }

    @Test
    fun ttlDecrementsAndStops() {
        var p = MeshPacket(PacketKinds.TXT, "id", "A", "Z", 3, "msg")
        repeat(3) { p = RelayCore.nextHop(p) }
        assertEquals(0, p.ttl)
        assertTrue(!RelayCore.shouldForward(p, "C"))
    }
}