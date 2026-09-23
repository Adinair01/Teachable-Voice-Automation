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

/** Screen area from the flattened bounds "left top right bottom"; 0 when unknown. */
val UiNode.area: Long
    get() = bounds.split(' ').mapNotNull { it.toLongOrNull() }.takeIf { it.size == 4 }
        ?.let { (l, t, r, b) -> maxOf(0, r - l) * maxOf(0, b - t) } ?: 0

/**
 * Step target description. Priority: resourceId > text > contentDesc > className.
 * [near] anchors the target to its row's caption ("{{item}} Pizza") when the same control repeats per row.
 */
data class Selector(
    val resourceId: String = "",
    val text: String = "",
    val contentDesc: String = "",
    val className: String = "",
    val near: String = "",
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
        val hits = findAll(nodes, sel, textContains)
        return hits.getOrNull(position) ?: hits.firstOrNull()
    }

    /** Every node matching the highest-priority field that matches anything (all the ADDs, not just the first). */
    fun findAll(nodes: List<UiNode>, sel: Selector, textContains: Boolean = false): List<UiNode> {
        val steps = listOf<(UiNode) -> Boolean>(
            { sel.resourceId.isNotEmpty() && it.resourceId == sel.resourceId },
            { sel.text.isNotEmpty() && !it.password && textMatches(it.text, sel.text, textContains) },
            { sel.contentDesc.isNotEmpty() && textMatches(it.contentDesc, sel.contentDesc, textContains) },
            // className is a last resort ONLY for selectors with no id/text/desc; otherwise it matches arbitrary containers.
            { sel.className.isNotEmpty() && sel.resourceId.isEmpty() && sel.text.isEmpty() && sel.contentDesc.isEmpty() && it.className == sel.className },
        )
        for (pred in steps) {
            val hits = nodes.filter(pred)
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    /**
     * Target lookup for one replay step. For taps, clickable nodes are tried first: the same caption often appears
     * twice (a tab's icon and its label) and only one of them sits on a clickable branch, so plain matching can pick
     * the dead one and "succeed" without anything happening. Slot-bound taps also fall back to "contains the value".
     */
    fun findTarget(nodes: List<UiNode>, sel: Selector, slotValue: String? = null, forTap: Boolean = false): UiNode? {
        val pools = if (forTap) listOf(nodes.filter { it.clickable }, nodes) else listOf(nodes)
        for (pool in pools) {
            find(pool, sel)?.let { return it }
            if (forTap && !slotValue.isNullOrEmpty()) {
                find(pool, Selector(text = slotValue), textContains = true)?.let { return it }
            }
        }
        return null
    }

    /**
     * The node a tap/type step acts on — the single rule shared by replay and the debug channel. Taps never target text
     * inputs (a typed value would otherwise match itself); a [Selector.near] anchor restricts candidates to the row
     * naming it (or, failing that, [slotValue]) and never falls back to "the first one".
     */
    fun target(nodes: List<UiNode>, sel: Selector, slotValue: String?, forTap: Boolean): UiNode? {
        val pool = if (forTap) nodes.filter { !it.editable } else nodes
        if (sel.near.isEmpty()) return findTarget(pool, sel, slotValue, forTap)
        val same = findAll(pool, sel.copy(near = "")).toSet()
        return RowContext.pick(nodes, same.toList(), sel.near, slotValue) { it in same }
    }

    private fun textMatches(actual: String, wanted: String, contains: Boolean) =
        if (contains) actual.contains(wanted, ignoreCase = true) else actual.equals(wanted, ignoreCase = true)
}
