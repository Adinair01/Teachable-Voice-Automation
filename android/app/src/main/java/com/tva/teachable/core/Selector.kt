package com.tva.teachable.core

/** Pure-Kotlin model of one UI node; no Android types so matching is unit-testable. */
data class UiNode(
    val index: Int,
    val depth: Int,
    val packageName: String,
    val resourceId: String,
    val text: String,
    val contentDesc: String,
    val className: String,
    val bounds: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val password: Boolean,
)

/** Step target description. Priority: resourceId > text > contentDesc > className. */
data class Selector(
    val resourceId: String = "",
    val text: String = "",
    val contentDesc: String = "",
    val className: String = "",
)

object SelectorMatcher {
    /** Returns the best match in the flat node list, or null. Password nodes are never matched by text. */
    fun find(nodes: List<UiNode>, sel: Selector, position: Int = 0): UiNode? {
        val steps = listOf<(UiNode) -> Boolean>(
            { sel.resourceId.isNotEmpty() && it.resourceId == sel.resourceId },
            { sel.text.isNotEmpty() && !it.password && it.text.equals(sel.text, ignoreCase = true) },
            { sel.contentDesc.isNotEmpty() && it.contentDesc.equals(sel.contentDesc, ignoreCase = true) },
            { sel.className.isNotEmpty() && it.className == sel.className },
        )
        for (pred in steps) {
            val hits = nodes.filter(pred)
            if (hits.isNotEmpty()) return hits.getOrNull(position) ?: hits.first()
        }
        return null
    }
}
