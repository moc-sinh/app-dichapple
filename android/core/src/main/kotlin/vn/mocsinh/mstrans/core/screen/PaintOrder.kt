package vn.mocsinh.mstrans.core.screen

/**
 * What is painted over what, as far as the tree of a page tells. An app says a text is visible as
 * long as its list does not cut it off, even when a bar that stays in place (the page's header, its
 * row of tabs, the buttons at the bottom) is drawn over it: the translation then went on top of the
 * bar. The tree is given as arrays, one entry a node, parents before their children.
 */
object PaintOrder {
    /**
     * When each node is painted, 0 first: a parent before its children, brothers in their drawing
     * order (`orders`; the same number: as they come).
     */
    fun sequence(parents: IntArray, orders: IntArray): IntArray {
        val count = parents.size
        val children = Array(count) { ArrayList<Int>(0) }
        val roots = ArrayList<Int>()
        for (i in 0 until count) if (parents[i] in 0 until count) children[parents[i]].add(i) else roots.add(i)
        for (list in children) if (list.size > 1) list.sortBy { orders[it] }
        val sequence = IntArray(count)
        var next = 0
        val stack = ArrayDeque<Int>()
        for (root in roots.asReversed()) stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            sequence[node] = next++
            for (child in children[node].asReversed()) stack.addLast(child)
        }
        return sequence
    }

    /**
     * For each node, whether it lies under a bar that stays in place: the node is in a list that
     * scrolls, and something with text in it, not part of that list and painted after it, covers
     * a third of the node or more. A layer as large as the page is not such a bar (it is see-through, and holds
     * a floating button); the bars inside it are. True is a doubt, not a certainty: the bar may be
     * see-through too, so the picture of the screen has the last word.
     *
     * @param scrolls the node scrolls its content; @param texts the node has text of its own.
     */
    fun underPanels(parents: IntArray, orders: IntArray, boxes: List<Box>, scrolls: BooleanArray, texts: BooleanArray, window: Box): BooleanArray {
        val count = parents.size
        val under = BooleanArray(count)
        if (count == 0) return under
        val sequence = sequence(parents, orders)
        // The last thing painted in each node's own tree.
        val end = IntArray(count) { sequence[it] }
        for (i in count - 1 downTo 0) parents[i].let { if (it in 0 until count) end[it] = maxOf(end[it], end[i]) }
        // The outermost list each node is in.
        val lists = IntArray(count) { -1 }
        for (i in 0 until count) {
            val parent = parents[i]
            if (parent !in 0 until count) continue
            lists[i] = if (lists[parent] >= 0) lists[parent] else if (scrolls[parent]) parent else -1
        }
        val large = window.area * 35 / 100
        val panels = HashMap<Int, List<Box>>()
        for (node in 0 until count) {
            val list = lists[node]
            if (!texts[node] || list < 0) continue
            val over = panels.getOrPut(list) {
                val found = LinkedHashSet<Box>()
                for (other in 0 until count) {
                    if (!texts[other] || sequence[other] <= end[list]) continue
                    // The largest thing around that text that is still a bar, and has not the list in it.
                    var panel = other
                    while (true) {
                        val parent = parents[panel]
                        if (parent !in 0 until count || boxes[parent].area > large) break
                        if (sequence[parent] <= sequence[list] && end[parent] >= end[list]) break
                        panel = parent
                    }
                    found.add(boxes[panel])
                }
                found.toList()
            }
            val box = boxes[node]
            under[node] = box.area > 0 && over.any { (it.intersect(box)?.area ?: 0L) * 10 >= box.area * 3 }
        }
        return under
    }
}
