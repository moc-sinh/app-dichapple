package vn.mocsinh.mstrans.core.screen

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.CoroutineContext

/**
 * Reads a tree level by level. Asking a node for one of its children is the slow part (on Android
 * each is a question to the app, answered on its main thread), so the children of a level are asked
 * for side by side on `readers`. Everything else runs in the caller, in the tree's own order: the
 * same nodes in the same order at every reading, however the answers come back.
 */
object TreeWalk {
    /**
     * A node being read: its number among those visited (the root is 0), its parent's number (-1
     * for the root), its place among its parent's children, how deep it is.
     */
    class Visit(val number: Int, val parent: Int, val index: Int, val depth: Int)

    private class Waiting<N>(val node: N, val parent: Int, val index: Int)

    /**
     * @param child the i-th child of a node, null when it is not there. Called on `readers`.
     * @param visit reads a node, and says how many of its children to go into (0: none).
     * @param left a node's children have been asked for: it is no longer needed here.
     * @return how many nodes were visited: at most `maxNodes`, those nearest the root first.
     */
    suspend fun <N : Any> walk(
        root: N,
        maxNodes: Int,
        maxDepth: Int,
        readers: CoroutineContext,
        child: (N, Int) -> N?,
        visit: (N, Visit) -> Int,
        left: (N) -> Unit = {},
    ): Int {
        var level = listOf(Waiting(root, -1, 0))
        var depth = 0
        var visited = 0
        while (level.isNotEmpty()) {
            val asks = ArrayList<Waiting<N>>()
            for (item in level) {
                if (visited >= maxNodes) break
                val number = visited++
                val wanted = visit(item.node, Visit(number, item.parent, item.index, depth))
                if (depth < maxDepth) for (i in 0 until wanted) asks.add(Waiting(item.node, number, i))
            }
            // No more than can still be visited.
            val room = (maxNodes - visited).coerceAtLeast(0)
            val asked = if (asks.size > room) asks.subList(0, room) else asks
            val next = if (asked.isEmpty()) emptyList() else coroutineScope {
                asked.map { ask -> async(readers) { child(ask.node, ask.index)?.let { Waiting(it, ask.parent, ask.index) } } }.awaitAll().filterNotNull()
            }
            for (item in level) left(item.node)
            level = next
            depth++
        }
        return visited
    }
}
