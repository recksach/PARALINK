package com.paralink.app.core.mesh

import com.paralink.app.core.model.Peer
import com.paralink.app.core.model.Route

class MeshRouter {
    private val peers = LinkedHashMap<String, Peer>()
    private val adjacency = LinkedHashMap<String, MutableSet<String>>()

    @Synchronized
    fun updatePeer(peer: Peer, connectedTo: Set<String> = emptySet()) {
        peers[peer.nodeId] = peer
        adjacency[peer.nodeId] = connectedTo.toMutableSet()
    }

    @Synchronized
    fun removePeer(nodeId: String) {
        peers.remove(nodeId)
        adjacency.remove(nodeId)
        adjacency.values.forEach { it.remove(nodeId) }
    }

    /**
     * Small deterministic breadth-first router for the MVP.
     * It intentionally works on an in-memory graph and can later be replaced
     * with a link-quality weighted Dijkstra/AODV-style implementation.
     */
    @Synchronized
    fun findRoute(source: String, destination: String): Route? {
        if (source == destination) return Route(destination, listOf(source), 0.0)
        val queue = ArrayDeque<List<String>>()
        val visited = mutableSetOf(source)
        queue.add(listOf(source))

        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            val current = path.last()
            for (next in adjacency[current].orEmpty()) {
                if (next in visited) continue
                val nextPath = path + next
                if (next == destination) {
                    return Route(destination, nextPath, nextPath.size.toDouble())
                }
                visited += next
                queue.addLast(nextPath)
            }
        }
        return null
    }
}
