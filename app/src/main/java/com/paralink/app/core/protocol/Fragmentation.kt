package com.paralink.app.core.protocol

/**
 * Splits logical payloads into BLE-sized fragments and reassembles them.
 * A [FragmentSlice] maps 1:1 to a [ParalinkFrame] that carries one fragment.
 */
object Fragmenter {

    data class FragmentSlice(
        val type: Int,
        val packetId: Long,
        val sessionId: Int,
        val sequence: Int,
        val fragmentIndex: Int,
        val fragmentCount: Int,
        val flags: Int,
        val payload: ByteArray
    )

    /**
     * @param payload the full logical payload bytes
     * @param capacity maximum fragment payload length (derived from MTU)
     * @param packetId shared by every fragment so they reassemble
     * @param sessionId link session id
     * @param sequence stream-sequential token
     * @param type frame type carried by the fragments
     */
    fun fragment(
        payload: ByteArray,
        capacity: Int,
        packetId: Long,
        sessionId: Int,
        sequence: Int,
        type: Int
    ): List<FragmentSlice> {
        require(capacity > 0) { "fragment capacity must be positive" }
        if (payload.isEmpty()) {
            return listOf(
                FragmentSlice(type, packetId, sessionId, sequence, 0, 0, FrameFlags.NONE, ByteArray(0))
            )
        }
        val count = (payload.size + capacity - 1) / capacity
        return List(count) { i ->
            val start = i * capacity
            val end = minOf(payload.size, start + capacity)
            val flags = FrameFlags.FRAGMENTED or
                (if (i == 0) FrameFlags.FRAG_FIRST else 0) or
                (if (i == count - 1) FrameFlags.FRAG_LAST else 0)
            FragmentSlice(type, packetId, sessionId, sequence, i, count, flags, payload.copyOfRange(start, end))
        }
    }

    fun frameFor(slice: FragmentSlice, ttl: Int = 0): ParalinkFrame =
        ParalinkFrame(
            type = slice.type,
            flags = slice.flags,
            ttl = ttl,
            packetId = slice.packetId,
            sessionId = slice.sessionId,
            fragmentIndex = slice.fragmentIndex,
            fragmentCount = slice.fragmentCount,
            sequence = slice.sequence,
            payload = slice.payload
        )
}

/**
 * Collects payload fragments per packet id and returns the complete payload
 * exactly once. Tolerates out-of-order and duplicate delivery.
 */
class Reassembler(private val maxBufferedPackets: Int = 64) {

    private class Partial(
        val type: Int,
        val count: Int,
        val sequence: Int,
        val chunks: Array<ByteArray?>,
        val received: BooleanArray
    ) {
        var delivered = false
    }

    private val buffers = LinkedHashMap<Long, Partial>()

    /** Feed one reassembled/parsed frame; returns the full payload when done. */
    @Synchronized
    fun push(frame: ParalinkFrame): ByteArray? {
        if (!frame.fragmented) {
            return if (registerDelivered(frame.packetId)) frame.payload else null
        }
        val partial = buffers.getOrPut(frame.packetId) {
            Partial(frame.type, frame.fragmentCount, frame.sequence, arrayOfNulls(frame.fragmentCount), BooleanArray(frame.fragmentCount))
        }
        if (partial.count != frame.fragmentCount) return null // inconsistent stream, drop
        val idx = frame.fragmentIndex
        if (idx >= partial.count || partial.received[idx]) return null
        partial.chunks[idx] = frame.payload
        partial.received[idx] = true
        if (!partial.received.all { it }) {
            trim()
            return null
        }
        // Full stream
        val size = partial.chunks.sumOf { it?.size ?: 0 }
        val out = ByteArray(size)
        var off = 0
        for (c in partial.chunks) {
            c?.let { System.arraycopy(it, 0, out, off, it.size); off += it.size }
        }
        buffers.remove(frame.packetId)
        return if (registerDelivered(frame.packetId)) out else null
    }

    private val deliveredIds = ArrayDeque<Long>()

    private fun registerDelivered(id: Long): Boolean {
        if (deliveredIds.contains(id)) return false
        deliveredIds.addLast(id)
        if (deliveredIds.size > 512) deliveredIds.removeFirst()
        return true
    }

    private fun trim() {
        while (buffers.size > maxBufferedPackets) {
            val first = buffers.keys.firstOrNull() ?: break
            buffers.remove(first)
        }
    }
}