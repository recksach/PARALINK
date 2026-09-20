package com.paralink.app.core.protocol

/**
 * Sender-side reliability: tracks fragments awaiting ACK, retransmits on
 * timeout, and gives up after a bounded number of attempts.
 */
class AckManager(
    private val maxAttempts: Int = 4,
    private val timeoutMs: Long = 3000,
    private val now: () -> Long = System::currentTimeMillis
) {

    data class Pending(
        val frame: ParalinkFrame,
        val attempt: Int,
        val expiresAt: Long
    )

    private val pending = LinkedHashMap<String, Pending>() // keyed by "packetId/fragmentIndex"

    fun track(frame: ParalinkFrame): Boolean {
        val key = key(frame.packetId, frame.fragmentIndex)
        if (pending.containsKey(key)) return false
        pending[key] = Pending(frame, 1, now() + timeoutMs)
        return true
    }

    fun onAck(packetId: Long, fragmentIndex: Int): Boolean {
        return pending.remove(key(packetId, fragmentIndex)) != null
    }

    fun isPending(packetId: Long, fragmentIndex: Int): Boolean =
        pending.containsKey(key(packetId, fragmentIndex))

    /** Returns frames that should be re-sent (timed out and attempts remain). */
    fun expired(): List<ParalinkFrame> {
        val out = mutableListOf<ParalinkFrame>()
        val t = now()
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val (k, p) = it.next()
            if (p.expiresAt <= t) {
                if (p.attempt >= maxAttempts) {
                    it.remove()
                } else {
                    val retried = p.frame.copy(
                        flags = p.frame.flags or FrameFlags.RETRANSMIT,
                        ttl = p.frame.ttl
                    )
                    out += retried
                    pending[k] = p.copy(attempt = p.attempt + 1, expiresAt = now() + timeoutMs)
                }
            }
        }
        return out
    }

    /**
     * Returns true when [packetId] has fully completed (all fragments acked)
     * — used by the reconnect/resume logic.
     */
    fun completed(packetId: Long): Boolean {
        val related = keyForPacket(packetId)
        val remaining = related.any { pending.containsKey(it) }
        return !remaining
    }

    fun clear() {
        pending.clear()
    }

    val size: Int get() = pending.size

    private fun key(packetId: Long, fragmentIndex: Int): String =
        "$packetId/$fragmentIndex"

    private fun keyForPacket(packetId: Long): List<String> =
        pending.keys.filter { it.startsWith("$packetId/") }
}

/**
 * Receiver-side duplicate protection per link session. Remembers packet ids
 * recently processed so a replayed/retransmitted fragment is never redelivered.
 */
class ProcessedPacketIds(private val maxTracked: Int = 1024) {

    private val ids = ArrayDeque<Long>()

    @Synchronized
    fun seen(packetId: Long): Boolean = packetId in ids

    @Synchronized
    fun mark(packetId: Long) {
        if (packetId in ids) return
        ids.addLast(packetId)
        if (ids.size > maxTracked) ids.removeFirst()
    }

    @Synchronized
    fun clear() = ids.clear()
}