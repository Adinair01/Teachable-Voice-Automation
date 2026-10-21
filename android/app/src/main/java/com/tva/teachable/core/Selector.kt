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
    /**
     * Where this node is drawn among its siblings (Android's drawing order, which accounts for elevation): a higher
     * one is painted over a lower one. Apps like Zomato keep the previous page in the tree under the new one, and
     * this is how the page on top is told apart.
     */
    val drawingOrder: Int = 0,
    /** Android's own "visible to the user" flag: false for nodes scrolled or clipped out of view. */
    val visibleToUser: Boolean = true,
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
    private const val MAX_UP = 8
    /** The resource-id tier: the one place a reused id needs the recorded caption to narrow it. */
    private const val ID_TIER = 0

    /** A caption that names an action, not a thing: it is repeated once per row, so it identifies nothing. */
    private val ACTION_WORD = Regex("add(\\s+item)?|\\+|buy( now)?|order( now)?|select|choose|get|go", RegexOption.IGNORE_CASE)

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
        for ((tier, pred) in steps.withIndex()) {
            var hits = nodes.filter(pred)
            // Apps reuse ids ("container", "positive_button") across unrelated controls, so an id match is only a match
            // when the recorded caption agrees. Taking the id alone tapped "Replace" on a replace-cart dialog for a step
            // taught on "Woohoo! Thanks", and the first of several "container"s for "1 item added". When the caption
            // disagrees the id tier yields nothing, and the lookup goes on by caption (and in the end asks).
            if (tier == ID_TIER) hits = byCaption(nodes, hits, sel)
            if (hits.isEmpty()) continue
            return hits
        }
        return emptyList()
    }

    /** The id hits whose own caption (or a caption inside them) is the recorded one; all of them if none was recorded. */
    private fun byCaption(nodes: List<UiNode>, hits: List<UiNode>, sel: Selector): List<UiNode> {
        if (hits.isEmpty() || (sel.text.isBlank() && sel.contentDesc.isBlank())) return hits
        return narrowByCaption(nodes, hits, sel).orEmpty()
    }

    /**
     * Target lookup for one replay step. For taps, clickable nodes are tried first: the same caption often appears
     * twice (a tab's icon and its label) and only one of them sits on a clickable branch, so plain matching can pick
     * the dead one and "succeed" without anything happening. Slot-bound taps also fall back to "contains the value".
     *
     * A TAP is never acted on while several DIFFERENT controls match it equally well. Matching used to end in
     * "take the first hit", so a selector that named no single control (an id every row reuses, "ADD") tapped
     * whatever happened to come first in the tree — the wrong row, silently, reported as success. Hits that share
     * one clickable node are the same control seen twice and are still fine to act on; hits spread across several
     * clickable controls are a genuine ambiguity, and the caller must ask rather than guess. [tree] carries the
     * whole screen when [nodes] is a filtered pool, since that is what the structure is read from.
     */
    fun findTarget(
        nodes: List<UiNode>, sel: Selector, slotValue: String? = null, forTap: Boolean = false,
        tree: List<UiNode> = nodes,
    ): UiNode? {
        val pools = if (forTap) listOf(nodes.filter { it.clickable }, nodes) else listOf(nodes)
        val parent by lazy { RowContext.parents(tree) }
        fun pick(hits: List<UiNode>): UiNode? =
            hits.firstOrNull()?.takeIf { !forTap || !blind(tree, parent, sel, hits) }
        // A slot-bound control is the one whose caption holds the requested value with the fewest other words: the
        // learned caption itself ("Search in {{restaurant}}" resolved), then the bare value.
        val needles = if (slotValue.isNullOrEmpty()) emptyList() else listOf(sel.text, sel.contentDesc, slotValue).filter { it.isNotBlank() }.distinct()
        for (pool in pools) {
            val hits = findAll(pool, sel)
            if (hits.isNotEmpty()) {
                pick(hits)?.let { return it }
                // The selector cannot identify one control; only the slot value may still single one out.
                return closest(pool, needles)
            }
            if (forTap) closest(pool, needles)?.let { return it }
        }
        return null
    }

    /**
     * The node whose caption contains a needle with the fewest extra words: "Pizza Hut" over "Pizza Hut Express",
     * "Domino's Pizza" over "Search in Domino's Pizza". The same name in several places (a suggestion and a carousel)
     * is the app repeating itself and the first is used; two DIFFERENT names equally close is a real ambiguity: null,
     * so the caller asks. It used to take the first node merely containing the value, in tree order.
     */
    fun closest(pool: List<UiNode>, needles: List<String>): UiNode? {
        for (needle in needles) {
            val scored = pool.filter { !it.password }.mapNotNull { n ->
                listOf(n.text, n.contentDesc).mapNotNull { c -> Captions.extraWords(c, needle)?.let { c to it } }.minByOrNull { it.second }
                    ?.let { (c, extra) -> Triple(n, c, extra) }
            }
            if (scored.isEmpty()) continue
            val best = scored.minOf { it.third }
            val top = scored.filter { it.third == best }
            return if (top.map { Captions.words(it.second) }.distinct().size == 1) top.first().first else null
        }
        return null
    }

    /**
     * Would acting on these hits be a guess? Only when the selector did not identify ONE control: it matched on a
     * field that says nothing about identity (an id or class every row reuses), or on a caption that names an action
     * rather than a thing ("ADD", "+"). Hits that share one clickable node are the same control seen twice and are
     * never a guess; several different controls behind a NAMING caption are the app repeating its own content in a
     * suggestion list and a carousel, where the first hit is the one the demonstration used.
     */
    private fun blind(tree: List<UiNode>, parent: IntArray, sel: Selector, hits: List<UiNode>): Boolean {
        if (hits.size <= 1 || oneControl(tree, parent, hits)) return false
        // Whichever field matched, it is the CAPTION that says which control this is. A naming caption shared by
        // several controls is the app repeating itself — Zomato's home carries three identical search bars, one per
        // cached page, and its menu lists one "Domino's Pizza" per section — and the demonstration used the first.
        // An action caption ("ADD", "+") names what the control does, not which one it is, so it cannot aim a tap;
        // nor can an empty caption, which leaves nothing but a resource id every row reuses.
        val caption = sel.text.ifBlank { sel.contentDesc }.trim()
        return caption.isEmpty() || ACTION_WORD.matches(caption)
    }

    /** Hits that resolve to one clickable node are one control seen twice; more than one is a real ambiguity. */
    private fun oneControl(tree: List<UiNode>, parent: IntArray, hits: List<UiNode>): Boolean =
        hits.size <= 1 || hits.map { controlOf(tree, parent, it) }.distinct().size == 1

    /** The nearest clickable node at or above [n] — the thing a tap would actually hit. */
    private fun controlOf(tree: List<UiNode>, parent: IntArray, n: UiNode): Int {
        var cur = n
        repeat(MAX_UP) {
            if (cur.clickable) return cur.index
            // No clickable ancestor: stay distinct per node, so two dead captions are not mistaken for one control.
            cur = tree.getOrNull(parent.getOrElse(cur.index) { -1 }) ?: return n.index
        }
        return n.index
    }

    /**
     * The node a tap/type step acts on — the single rule shared by replay and the debug channel. Taps never target text
     * inputs (a typed value would otherwise match itself); a [Selector.near] anchor restricts candidates to the row
     * naming it (or, failing that, [slotValue]) and never falls back to "the first one".
     */
    fun target(nodes: List<UiNode>, sel: Selector, slotValue: String?, forTap: Boolean): UiNode? {
        // A tap needs something clickable at or above the node: a caption echoed in a plain label (the search box
        // repeating "Domino's pizza") would otherwise be "tapped" to no effect. And only what the user can SEE: a page
        // the app keeps underneath (Zomato's menu under its cart, with its own "Continue") is never a target.
        val hidden = Visibility.hidden(nodes)
        val pool = (if (forTap) tappable(nodes) else nodes).filter { !hidden[it.index] }
        if (sel.near.isEmpty()) return findTarget(pool, sel, slotValue, forTap, tree = nodes)
        val same = findAll(pool, sel.copy(near = "")).toSet()
        return RowContext.pick(nodes, same.toList(), sel.near, slotValue) { it in same }
    }

    /** Nodes a tap can reach: clickable themselves or inside a clickable ancestor, never a text input. */
    fun tappable(nodes: List<UiNode>): List<UiNode> {
        val parent = RowContext.parents(nodes)
        val reachable = BooleanArray(nodes.size)
        for (n in nodes) reachable[n.index] = n.clickable || (parent[n.index] >= 0 && reachable[parent[n.index]])
        return nodes.filter { !it.editable && reachable[it.index] }
    }

    private fun narrowByCaption(nodes: List<UiNode>, hits: List<UiNode>, sel: Selector): List<UiNode>? {
        val caption = listOf(sel.text, sel.contentDesc).filter { it.isNotEmpty() }
        if (caption.isEmpty()) return null
        return hits.filter { h ->
            val at = nodes.indexOf(h)
            val inside = listOf(h) + nodes.drop(at + 1).takeWhile { it.depth > h.depth }
            caption.all { c -> inside.any { !it.password && (Captions.same(it.text, c) || Captions.same(it.contentDesc, c)) } }
        }.takeIf { it.isNotEmpty() }
    }

    // Punctuation- and spacing-insensitive as a second chance: a spoken slot ("dominos") rarely carries the app's
    // apostrophes ("Domino's Pizza").
    private fun textMatches(actual: String, wanted: String, contains: Boolean): Boolean {
        if (if (contains) actual.contains(wanted, true) else actual.equals(wanted, true)) return true
        val a = RowContext.norm(actual)
        val w = RowContext.norm(wanted)
        // An exact caption also matches once prices, counts and rotating placeholders are set aside on BOTH sides.
        return w.isNotEmpty() && if (contains) w in a else a == w || Captions.same(actual, wanted)
    }
}
