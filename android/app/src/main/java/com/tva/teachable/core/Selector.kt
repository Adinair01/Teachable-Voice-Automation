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
) {
    val isEmpty get() = resourceId.isEmpty() && text.isEmpty() && contentDesc.isEmpty() && className.isEmpty()
}

object SelectorMatcher {
    /**
     * Best match in the flat node list, or null. Password nodes never match by text.
     * [textContains] switches text matching from equality to substring (used for slot-bound targets,
     * e.g. a suggestion "pizza near me" for slot value "pizza").
     */
    fun find(nodes: List<UiNode>, sel: Selector, textContains: Boolean = false, position: Int = 0): UiNode? {
        val steps = listOf<(UiNode) -> Boolean>(
            { sel.resourceId.isNotEmpty() && it.resourceId == sel.resourceId },
            { sel.text.isNotEmpty() && !it.password && textMatches(it.text, sel.text, textContains) },
            { sel.contentDesc.isNotEmpty() && textMatches(it.contentDesc, sel.contentDesc, textContains) },
            // className is a last resort ONLY for selectors with no id/text/desc; otherwise it matches arbitrary containers.
            { sel.className.isNotEmpty() && sel.resourceId.isEmpty() && sel.text.isEmpty() && sel.contentDesc.isEmpty() && it.className == sel.className },
        )
        for (pred in steps) {
            val hits = nodes.filter(pred)
            if (hits.isNotEmpty()) return hits.getOrNull(position) ?: hits.first()
        }
        return null
    }

    private fun textMatches(actual: String, wanted: String, contains: Boolean) =
        if (contains) actual.contains(wanted, ignoreCase = true) else actual.equals(wanted, ignoreCase = true)
}
