package com.tva.teachable.a11y

import android.graphics.Rect
import android.os.Build
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
            drawingOrder = n.drawingOrder, visibleToUser = n.isVisibleToUser,
        )
    }

    private fun walk(n: AccessibilityNodeInfo, depth: Int, nodes: MutableList<UiNode>, handles: MutableList<AccessibilityNodeInfo>) {
        if (nodes.size >= MAX_NODES) return
        nodes += toUiNode(n, nodes.size, depth)
        handles += n
        for (i in 0 until n.childCount) child(n, i)?.let { walk(it, depth + 1, nodes, handles) }
    }

    /**
     * One child, fetched together with its descendants on Android 13+, uninterrupted. Measured on the device for
     * Zomato's 397-node screen: 532-1614 ms one node at a time, 409-812 ms with this. Zomato's animations keep
     * invalidating the accessibility cache, so most nodes still cost one request each; while teaching every tap waits
     * for this read before it reaches the app.
     */
    private fun child(n: AccessibilityNodeInfo, i: Int): AccessibilityNodeInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) n.getChild(i, PREFETCH) else n.getChild(i)

    /** The prefetching strategy for every fetch of the tree (Android 13+). */
    val PREFETCH = AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST or AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE
}
