package com.tva.teachable.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.tva.teachable.core.UiNode

/** Snapshot: plain nodes (safe to log/send, passwords redacted) paired index-for-index with live handles. */
class LiveTree(val nodes: List<UiNode>, val handles: List<AccessibilityNodeInfo>) {
    fun handle(node: UiNode): AccessibilityNodeInfo = handles[node.index]
}

object UiTreeReader {
    private const val MAX_NODES = 1500
    private val EMPTY = LiveTree(emptyList(), emptyList())

    fun readLive(root: AccessibilityNodeInfo?): LiveTree {
        if (root == null) return EMPTY
        val nodes = ArrayList<UiNode>()
        val handles = ArrayList<AccessibilityNodeInfo>()
        walk(root, 0, nodes, handles)
        return LiveTree(nodes, handles)
    }

    /** Several windows as one tree (a popup and the page under it); each root starts again at depth 0. */
    fun readLive(roots: List<AccessibilityNodeInfo>): LiveTree {
        val nodes = ArrayList<UiNode>()
        val handles = ArrayList<AccessibilityNodeInfo>()
        roots.forEach { walk(it, 0, nodes, handles) }
        return LiveTree(nodes, handles)
    }

    fun toUiNode(n: AccessibilityNodeInfo, index: Int = 0, depth: Int = 0): UiNode {
        val r = Rect().also { n.getBoundsInScreen(it) }
        val pwd = n.isPassword
        return UiNode(
            index = index, depth = depth,
            packageName = n.packageName?.toString().orEmpty(),
            resourceId = n.viewIdResourceName.orEmpty(),
            text = if (pwd) "[REDACTED]" else n.text?.toString().orEmpty(), // never read password text
            contentDesc = n.contentDescription?.toString().orEmpty(),
            className = n.className?.toString().orEmpty(),
            bounds = r.flattenToString(),
            clickable = n.isClickable, editable = n.isEditable,
            scrollable = n.isScrollable, password = pwd,
        )
    }

    private fun walk(n: AccessibilityNodeInfo, depth: Int, nodes: MutableList<UiNode>, handles: MutableList<AccessibilityNodeInfo>) {
        if (nodes.size >= MAX_NODES) return
        nodes += toUiNode(n, nodes.size, depth)
        handles += n
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1, nodes, handles) }
    }
}
