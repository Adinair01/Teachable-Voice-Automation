package com.tva.teachable.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.tva.teachable.core.UiNode

/** Flattens an accessibility tree into UiNodes, redacting password fields (never read their text). */
object UiTreeReader {
    private const val MAX_NODES = 1500

    fun read(root: AccessibilityNodeInfo): List<UiNode> {
        val out = ArrayList<UiNode>()
        walk(root, 0, out)
        return out
    }

    private fun walk(n: AccessibilityNodeInfo, depth: Int, out: MutableList<UiNode>) {
        if (out.size >= MAX_NODES) return
        val r = Rect().also { n.getBoundsInScreen(it) }
        val pwd = n.isPassword
        out += UiNode(
            index = out.size, depth = depth,
            packageName = n.packageName?.toString().orEmpty(),
            resourceId = n.viewIdResourceName.orEmpty(),
            text = if (pwd) "[REDACTED]" else n.text?.toString().orEmpty(),
            contentDesc = n.contentDescription?.toString().orEmpty(),
            className = n.className?.toString().orEmpty(),
            bounds = r.flattenToString(),
            clickable = n.isClickable, editable = n.isEditable,
            scrollable = n.isScrollable, password = pwd,
        )
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c, depth + 1, out)
        }
    }
}
