package com.tva.teachable.core

/**
 * Which nodes the user can actually see. Apps keep pages they have navigated away from in the accessibility tree:
 * Zomato's cart is a layer over its menu in ONE window, so the menu's own bottom bar ("2 items added | Continue")
 * sits in the tree at exactly the place of the cart's "Select address at next step". Tree position cannot tell them
 * apart (verified live, 2026-10-21: the cart is in the MIDDLE of the tree, the menu both before and after it);
 * Android's drawing order can: the cart is drawn at 15, over a full-screen tappable backdrop at 13, over the menu at 4-9.
 *
 * A node is hidden when Android says it is not visible, or when something drawn ABOVE it — a later-drawn sibling of
 * one of its ancestors — holds a tappable surface that covers it (a backdrop, a sheet, a page). Only tappable
 * surfaces count: an empty full-screen container, or one that only holds a small floating button, covers nothing.
 */
object Visibility {
    /** Covers less than this share of the screen: a button, not a layer. */
    private const val LAYER_SHARE = 0.25

    /** true at index i = node i is hidden from the user. */
    fun hidden(nodes: List<UiNode>): BooleanArray {
        val out = BooleanArray(nodes.size) { !nodes[it].visibleToUser }
        if (nodes.isEmpty()) return out
        val parent = RowContext.parents(nodes)
        val children = Array(nodes.size) { ArrayList<Int>() }
        val roots = ArrayList<Int>()
        for (n in nodes) if (parent[n.index] >= 0) children[parent[n.index]] += n.index else roots += n.index
        val screen = nodes.maxOf { area(box(it)) }.coerceAtLeast(1)
        // Separate windows are merged top-most first, so an earlier root is drawn over a later one.
        val z = IntArray(nodes.size) { nodes[it].drawingOrder }
        roots.forEachIndexed { k, r -> z[r] = roots.size - k }
        val layers = HashMap<Int, List<IntArray>>()
        fun layersOf(s: Int): List<IntArray> = layers.getOrPut(s) {
            RowContext.subtree(nodes, nodes[s]).filter { it.clickable && area(box(it)) >= screen * LAYER_SHARE }.map(::box)
        }
        for (n in nodes) {
            if (out[n.index]) continue
            val b = box(n)
            var a = n.index
            while (!out[n.index]) {
                val p = parent[a]
                val siblings = if (p >= 0) children[p] else roots
                if (siblings.any { s -> s != a && z[s] > z[a] && layersOf(s).any { covers(it, b) } }) out[n.index] = true
                if (p < 0) break
                a = p
            }
        }
        return out
    }

    /** The nodes the user can see, in tree order. */
    fun visible(nodes: List<UiNode>): List<UiNode> = hidden(nodes).let { h -> nodes.filter { !h[it.index] } }

    private fun box(n: UiNode) = n.bounds.split(' ').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 }?.toIntArray() ?: IntArray(4)
    private fun area(b: IntArray) = maxOf(0, b[2] - b[0]).toLong() * maxOf(0, b[3] - b[1])
    private fun covers(o: IntArray, b: IntArray) = area(b) > 0 && o[0] <= b[0] && o[1] <= b[1] && o[2] >= b[2] && o[3] >= b[3]
}
