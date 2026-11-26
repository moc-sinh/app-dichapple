package vn.mocsinh.mstrans.core.screen

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TreeWalkTest {
    private class Node(val name: String, val children: List<Node?> = emptyList())

    private fun leaf(name: String) = Node(name)

    /** Walks `root` and gives the names in the order visited. */
    private fun names(root: Node, maxNodes: Int = 100, maxDepth: Int = 10, goInto: (Node) -> Boolean = { true }): List<String> = runBlocking {
        val seen = ArrayList<String>()
        TreeWalk.walk(
            root, maxNodes, maxDepth, coroutineContext,
            child = { node, i -> node.children[i] },
            visit = { node, _ ->
                seen.add(node.name)
                if (goInto(node)) node.children.size else 0
            },
        )
        seen
    }

    @Test
    fun `every node is visited once, level by level, in the order of the tree`() {
        val root = Node("a", listOf(Node("b", listOf(leaf("d"), leaf("e"))), Node("c", listOf(leaf("f")))))
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), names(root))
    }

    @Test
    fun `a node the visit does not go into keeps its children unread`() {
        val root = Node("a", listOf(Node("hidden", listOf(leaf("x"))), Node("c", listOf(leaf("f")))))
        assertEquals(listOf("a", "hidden", "c", "f"), names(root) { it.name != "hidden" })
    }

    @Test
    fun `no more nodes than allowed, those nearest the root first`() {
        val root = Node("a", listOf(Node("b", listOf(leaf("d"), leaf("e"))), Node("c", listOf(leaf("f")))))
        assertEquals(listOf("a", "b", "c", "d"), names(root, maxNodes = 4))
    }

    @Test
    fun `children that will not be visited are not asked for`() = runBlocking {
        val asked = AtomicInteger()
        val root = Node("a", List(20) { leaf("n$it") })
        val visited = TreeWalk.walk(
            root, maxNodes = 5, maxDepth = 10, readers = coroutineContext,
            child = { node, i -> asked.incrementAndGet(); node.children[i] },
            visit = { node, _ -> node.children.size },
        )
        assertEquals(5, visited)
        assertEquals(4, asked.get())
    }

    @Test
    fun `no deeper than allowed`() {
        val root = Node("a", listOf(Node("b", listOf(Node("c", listOf(leaf("d")))))))
        assertEquals(listOf("a", "b", "c"), names(root, maxDepth = 2))
    }

    @Test
    fun `a child that is not there is passed over`() {
        val root = Node("a", listOf(null, leaf("b"), null, leaf("c")))
        assertEquals(listOf("a", "b", "c"), names(root))
    }

    @Test
    fun `the children of a level are asked for side by side`() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val running = AtomicInteger()
            val most = AtomicInteger()
            val root = Node("a", List(12) { leaf("n$it") })
            val seen = ArrayList<String>()
            runBlocking {
                TreeWalk.walk(
                    root, 100, 10, pool.asCoroutineDispatcher(),
                    child = { node, i ->
                        // Asking the app for a node blocks its thread.
                        most.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
                        Thread.sleep(30)
                        running.decrementAndGet()
                        node.children[i]
                    },
                    visit = { node, _ ->
                        seen.add(node.name)
                        node.children.size
                    },
                )
            }
            assertTrue(most.get() >= 2, "at most ${most.get()} at a time")
            // Whichever answer came first, the order is the tree's.
            assertEquals(listOf("a") + List(12) { "n$it" }, seen)
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `a node knows its parent and its place among the parent's children`() = runBlocking {
        val seen = ArrayList<String>()
        val root = Node("a", listOf(leaf("b"), Node("c", listOf(null, leaf("d")))))
        TreeWalk.walk(
            root, 100, 10, coroutineContext,
            child = { node, i -> node.children[i] },
            visit = { node, at ->
                seen.add("${node.name}:${at.number}<${at.parent}[${at.index}]@${at.depth}")
                node.children.size
            },
        )
        assertEquals(listOf("a:0<-1[0]@0", "b:1<0[0]@1", "c:2<0[1]@1", "d:3<2[1]@2"), seen)
    }

    @Test
    fun `each node is given back once its children are read`() = runBlocking {
        val left = ArrayList<String>()
        val root = Node("a", listOf(Node("b", listOf(leaf("d"))), leaf("c")))
        TreeWalk.walk(
            root, 100, 10, coroutineContext,
            child = { node, i -> node.children[i] },
            visit = { node, _ -> node.children.size },
            left = { left.add(it.name) },
        )
        assertEquals(listOf("a", "b", "c", "d"), left)
    }
}
