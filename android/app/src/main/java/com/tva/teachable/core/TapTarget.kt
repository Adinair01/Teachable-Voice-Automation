package com.tva.teachable.core

/**
 * The element a finger at (x, y) lands on, from a tree read BEFORE the tap is delivered — so it works for Compose
 * lists and dialogs, which never report TYPE_VIEW_CLICKED, and the row around it is still on screen.
 */
object TapTarget {
    /**
     * Smallest clickable node under the point, searching only the top-most window that contains it. Smallest, not
     * last-in-tree: apps keep large clickable layers (video players, list cards) that come later in the tree than the
     * button actually under the finger, and a floating bar is often earlier in the tree than the list it covers.
     */
    fun at(nodes: List<UiNode>, x: Int, y: Int): UiNode? {
        val window = nodes.filter { it.depth == 0 }.firstOrNull { contains(it, x, y) } ?: return null
        val under = RowContext.subtree(nodes, window).filter { contains(it, x, y) }
        return under.filter { it.clickable }.minByOrNull { it.area } ?: under.lastOrNull()
    }

    /** Same shape the event recorder produced: own id/class, own text or the first caption inside it. */
    fun selector(nodes: List<UiNode>, n: UiNode): Selector {
        val inside = RowContext.subtree(nodes, n).drop(1).filter { !it.password && !it.editable }
        val text = if (n.editable || n.password) "" else n.text.ifEmpty { inside.firstOrNull { it.text.isNotBlank() }?.text.orEmpty() }
        val desc = n.contentDesc.ifEmpty { if (text.isEmpty()) inside.firstOrNull { it.contentDesc.isNotBlank() }?.contentDesc.orEmpty() else "" }
        return Selector(resourceId = n.resourceId, text = text, contentDesc = desc, className = n.className)
    }

    private fun contains(n: UiNode, x: Int, y: Int): Boolean {
        val b = n.bounds.split(' ').mapNotNull { it.toIntOrNull() }
        return b.size == 4 && x >= b[0] && x < b[2] && y >= b[1] && y < b[3]
    }
}
