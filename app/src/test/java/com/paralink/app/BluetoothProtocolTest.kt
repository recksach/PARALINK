package com.paralink.app

import com.paralink.app.core.protocol.FrameCodec
import com.paralink.app.core.protocol.FrameFlags
import com.paralink.app.core.protocol.FrameTypes
import com.paralink.app.core.protocol.Fragmenter
import com.paralink.app.core.protocol.ParalinkFrame
import com.paralink.app.core.protocol.ProcessedPacketIds
import com.paralink.app.core.protocol.ProtocolSecurity
import com.paralink.app.core.protocol.Reassembler
import com.paralink.app.core.protocol.AckManager
import com.paralink.app.core.security.SessionCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64

class BluetoothProtocolTest {

    private fun key(seed: Int): ByteArray = ByteArray(32) { i -> (seed + i).toByte() }

    private fun frame(
        type: Int = FrameTypes.MESSAGE,
        flags: Int = FrameFlags.NONE,
        packetId: Long = 42,
        sessionId: Int = 7,
        fragmentIndex: Int = 0,
        fragmentCount: Int = 0,
        sequence: Int = 1,
        payload: ByteArray = byteArrayOf(0)
    ): ParalinkFrame =
        ParalinkFrame(type, flags, 0, packetId, sessionId, fragmentIndex, fragmentCount, sequence, payload)

    // ------------------------------------------------------------------
    // FrameCodec + ProtocolSecurity
    // ------------------------------------------------------------------
    @Test
    fun frameRoundTrip() {
        val f = frame(payload = "hello".toByteArray(Charsets.UTF_8))
        val sealed = ProtocolSecurity.sealed(f, key(0x01))
        val parsed = ProtocolSecurity.unseal(sealed, key(0x01))
        assertNotNull(parsed)
        assertEquals(f.type, parsed!!.type)
        assertEquals(f.flags, parsed.flags)
        assertEquals(f.packetId, parsed.packetId)
        assertEquals(f.sessionId, parsed.sessionId)
        assertEquals(f.fragmentIndex, parsed.fragmentIndex)
        assertEquals(f.fragmentCount, parsed.fragmentCount)
        assertEquals(f.sequence, parsed.sequence)
        assertArrayEquals(f.payload, parsed.payload)
    }

    @Test
    fun tamperedPayloadRejected() {
        val sealed = ProtocolSecurity.sealed(frame(), key(0x01))
        val bad = sealed.copyOf()
        bad[bad.size - 2] = (bad[bad.size - 2].toInt() xor 0xFF).toByte()
        assertNull(ProtocolSecurity.unseal(bad, key(0x01)))
    }

    @Test
    fun wrongKeyRejected() {
        val sealed = ProtocolSecurity.sealed(frame(), key(0x01))
        assertNull(ProtocolSecurity.unseal(sealed, key(0x02)))
    }

    @Test
    fun zeroKeyAllowedBeforeSession() {
        // Handshake frames are MACed with the zero key before the link key exists.
        val sealed = ProtocolSecurity.sealed(frame(type = FrameTypes.HELLO), key(0x00))
        assertNotNull(ProtocolSecurity.unseal(sealed, key(0x00)))
    }

    @Test
    fun truncatedFrameRejected() {
        val sealed = ProtocolSecurity.sealed(frame(), key(0x01))
        assertNull(ProtocolSecurity.unseal(sealed.copyOf(sealed.size - 1), key(0x01)))
    }

    @Test
    fun ackTargetRoundTrip() {
        val payload = FrameCodec.packetIdForAck(0xABCDEF12L, 5)
        val ack = frame(type = FrameTypes.ACK, payload = payload)
        val target = ack.ackTarget()
        assertNotNull(target)
        assertEquals(0xABCDEF12L, target!!.first)
        assertEquals(5, target.second)
    }

    // ------------------------------------------------------------------
    // Fragmentation + reassembly
    // ------------------------------------------------------------------
    @Test
    fun fragmentationReassembly() {
        val payload = ByteArray(800) { (it % 251).toByte() }
        val slices = Fragmenter.fragment(payload, 60, 100, 7, 3, FrameTypes.MESSAGE)
        assertTrue(slices.size > 1)
        assertTrue(slices.all { it.flags and FrameFlags.FRAGMENTED != 0 })
        assertEquals(FrameFlags.FRAGMENTED or FrameFlags.FRAG_FIRST, slices.first().flags)
        assertEquals(FrameFlags.FRAGMENTED or FrameFlags.FRAG_LAST, slices.last().flags)

        val reassembler = Reassembler()
        var result: ByteArray? = null
        // delivered out of order
        for (i in slices.indices.reversed()) {
            result = reassembler.push(Fragmenter.frameFor(slices[i]))
        }
        assertNotNull(result)
        assertArrayEquals(payload, result)
    }

    @Test
    fun reassemblyDropsDuplicates() {
        val payload = ByteArray(300) { 9 }
        val slices = Fragmenter.fragment(payload, 100, 55, 1, 0, FrameTypes.MESSAGE)
        val r = Reassembler()
        var result: ByteArray? = null
        for (s in slices) { result = r.push(Fragmenter.frameFor(s)) }
        assertNotNull(result)
        // Re-feeding the whole packet again must NOT redeliver.
        for (s in slices) { result = r.push(Fragmenter.frameFor(s)) }
        assertNull(result)
    }

    @Test
    fun singleFrameNotFragmentedStream() {
        val slices = Fragmenter.fragment(ByteArray(5), 100, 1, 1, 0, FrameTypes.MESSAGE)
        assertEquals(1, slices.size)
        assertEquals(1, slices[0].fragmentCount)
        // A lone part is flagged FRAGMENTED|FIRST|LAST so the reassembler
        // (and per-fragment ACKs) treat every datagram uniformly.
        assertTrue(slices[0].flags and FrameFlags.FRAGMENTED != 0)
        assertTrue(slices[0].flags and FrameFlags.FRAG_FIRST != 0)
        assertTrue(slices[0].flags and FrameFlags.FRAG_LAST != 0)
    }

    @Test
    fun processedIdsDedup() {
        val ids = ProcessedPacketIds(4)
        assertFalse(ids.seen(3))
        ids.mark(3)
        assertTrue(ids.seen(3))
    }

    // ------------------------------------------------------------------
    // Reliability / ACK manager
    // ------------------------------------------------------------------
    @Test
    fun ackManagerLifecycle() {
        var now = 0L
        val am = AckManager(maxAttempts = 3, timeoutMs = 1000, now = { now })
        am.track(frame(packetId = 10, fragmentIndex = 0))
        assertTrue(am.isPending(10, 0))
        assertFalse(am.onAck(10, 1))
        assertTrue(am.onAck(10, 0))
        assertFalse(am.isPending(10, 0))
    }

    @Test
    fun ackManagerRetransmitsThenGivesUp() {
        var now = 0L
        val am = AckManager(maxAttempts = 3, timeoutMs = 1000, now = { now })
        am.track(frame(packetId = 77, fragmentIndex = 1))

        now = 1000
        assertEquals(1, am.expired().size) // attempt 1 -> 2
        assertTrue(am.isPending(77, 1))

        now = 2000
        assertEquals(1, am.expired().size) // attempt 2 -> 3

        now = 3000
        assertTrue(am.expired().isEmpty()) // attempt 3 exceeded
        assertFalse(am.isPending(77, 1))
    }

    // ------------------------------------------------------------------
    // SessionCrypto (pure JVM, no Keystore)
    // ------------------------------------------------------------------
    private fun staticKeyPair(): Pair<java.security.PrivateKey, String> {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        val kp = g.generateKeyPair()
        return kp.private to Base64.getEncoder().encodeToString(kp.public.encoded)
    }

    @Test
    fun linkKeyIsSymmetric() {
        val (staticA, pubA) = staticKeyPair()
        val (staticB, pubB) = staticKeyPair()
        val cryptoA = SessionCrypto()
        val cryptoB = SessionCrypto()
        val ephA = cryptoA.newEphemeral()
        val ephB = cryptoB.newEphemeral()
        val nonceA = ByteArray(16) { 1 }
        val nonceB = ByteArray(16) { 2 }

        val keyA = cryptoA.linkKey(staticA, ephA, pubB, ephB.publicKeyB64, nonceA, nonceB)
        val keyB = cryptoB.linkKey(staticB, ephB, pubA, ephA.publicKeyB64, nonceA, nonceB)
        assertArrayEquals(keyA, keyB)
    }

    @Test
    fun linkKeyChangesWhenEphemeralChanges() {
        val (staticA, pubA) = staticKeyPair()
        val (staticB, pubB) = staticKeyPair()
        val c = SessionCrypto()
        val nonceA = ByteArray(16) { 1 }
        val nonceB = ByteArray(16) { 2 }
        val k1 = c.linkKey(staticA, c.newEphemeral(), pubB, c.newEphemeral().publicKeyB64, nonceA, nonceB)
        val k2 = c.linkKey(staticA, c.newEphemeral(), pubB, c.newEphemeral().publicKeyB64, nonceA, nonceB)
        assertNotEquals(k1.contentToString(), k2.contentToString())
    }

    @Test
    fun sasMatchesOnBothSides() {
        val (staticA, pubA) = staticKeyPair()
        val (staticB, pubB) = staticKeyPair()
        val c = SessionCrypto()
        val ephA = c.newEphemeral()
        val ephB = c.newEphemeral()
        val nonceA = ByteArray(16) { 5 }
        val nonceB = ByteArray(16) { 9 }
        val key = c.linkKey(staticA, ephA, pubB, ephB.publicKeyB64, nonceA, nonceB)
        val sas = c.sas(key, nonceA, nonceB)
        assertTrue(Regex("\\d{3} \\d{3}").matches(sas))
        assertEquals(sas, c.sas(key, nonceA, nonceB))
    }

    @Test
    fun authProofMutuallyVerifiable() {
        val (staticA, pubA) = staticKeyPair()
        val (staticB, pubB) = staticKeyPair()
        val c = SessionCrypto()
        val ephA = c.newEphemeral()
        val ephB = c.newEphemeral()
        val nonceA = ByteArray(16) { 3 }
        val nonceB = ByteArray(16) { 4 }
        val key = c.linkKey(staticA, ephA, pubB, ephB.publicKeyB64, nonceA, nonceB)
        val sessionId = 0x1234

        // Initiator A proves "A<->B in session 0x1234"; responder B verifies it.
        val proofA = c.authProof(key, sessionId, "A", "B")
        val expectedByX = c.authProof(key, sessionId, "A", "B")
        assertArrayEquals(proofA, expectedByX)

        // A bogus role order must not pass as A's proof.
        val wrong = c.authProof(key, sessionId, "B", "A")
        assertFalse(ProtocolSecurity.tagsEqual(proofA, wrong))
    }
}