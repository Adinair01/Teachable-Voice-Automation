package com.tva.teachable.core

/**
 * "Which row is this element in?" on a flat pre-order node list. Lists repeat the same control once per row (every
 * dish has an identical ADD button), so a tap is anchored to its row's text instead ("ADD in the Margherita Pizza row").
 * The row is the LARGEST ancestor that holds no other control of the same kind: one level higher would take in the
 * next row as well.
 */
object RowContext {
    private const val MAX_UP = 8
    private const val MAX_TEXTS = 12

    fun parents(nodes: List<UiNode>): IntArray {
        val parent = IntArray(nodes.size) { -1 }
        val stack = ArrayDeque<UiNode>()
        for (n in nodes) {
            while (stack.isNotEmpty() && stack.last().depth >= n.depth) stack.removeLast()
            parent[n.index] = stack.lastOrNull()?.index ?: -1
            stack.addLast(n)
        }
        return parent
    }

    fun subtree(nodes: List<UiNode>, root: UiNode): List<UiNode> =
        listOf(root) + nodes.drop(root.index + 1).takeWhile { it.depth > root.depth }

    /** Visible texts of the row holding [node]; [sameKind] says which nodes count as "another one of these". */
    fun of(nodes: List<UiNode>, node: UiNode, sameKind: (UiNode) -> Boolean, parent: IntArray = parents(nodes)): List<String> {
        var row = node
        for (up in 0 until MAX_UP) {
            val p = nodes.getOrNull(parent[row.index]) ?: break
            if (subtree(nodes, p).any { it.index != node.index && sameKind(it) }) break
            row = p
        }
        return texts(nodes, row)
    }

    /**
     * Picks the candidate whose row names [near] (whole caption, normalised: "{{item}} Pizza" -> "Farmhouse Pizza"),
     * else one whose row merely contains [fallback] (the bare slot value). Null when no row qualifies: tapping the
     * first identical button instead could add the wrong dish, or a paid membership.
     */
    fun pick(nodes: List<UiNode>, candidates: List<UiNode>, near: String, fallback: String?, sameKind: (UiNode) -> Boolean): UiNode? {
        if (candidates.isEmpty()) return null
        val parent = parents(nodes)
        val rows = candidates.map { it to of(nodes, it, sameKind, parent).map(::norm) }
        val n = norm(near)
        if (n.isNotEmpty()) rows.firstOrNull { (_, texts) -> n in texts }?.let { return it.first }
        // The item itself is on screen but its row has no such control (a dish already in the cart shows a stepper
        // instead of ADD): a neighbour that merely contains the words ("Double Cheese Margherita Pizza") is a different dish.
        if (n.isNotEmpty() && nodes.any { !it.editable && !it.password && (norm(it.text) == n || norm(it.contentDesc) == n) }) return null
        // The bare slot value, then its longest word: "Margherita pizzas" (spoken plural) still finds "Margherita Pizza".
        val word = fallback.orEmpty().split(Regex("\\s+")).maxByOrNull { it.length }.orEmpty()
        for (f in listOf(norm(fallback.orEmpty()), norm(word).takeIf { it.length >= 4 }.orEmpty()).distinct()) {
            if (f.isNotEmpty()) rows.firstOrNull { (_, texts) -> texts.any { f in it } }?.let { return it.first }
        }
        return null
    }

    /** Case, spacing and punctuation-insensitive form: "Domino's Pizza" == "dominos pizza". */
    fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun texts(nodes: List<UiNode>, row: UiNode) = subtree(nodes, row)
        .filter { !it.password }
        .flatMap { listOf(it.text, it.contentDesc) }
        .map { it.trim().take(80) }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_TEXTS)
}

/** A "− n +" quantity control: a number whose previous and next siblings are both tappable. */
data class Stepper(val count: Int, val minus: UiNode, val plus: UiNode, val number: UiNode)

object Steppers {
    private val NUMBER = Regex("^\\d{1,2}$")

    private fun count(n: UiNode) = n.text.ifBlank { n.contentDesc }.trim()

    fun all(nodes: List<UiNode>): List<Stepper> {
        val parent = RowContext.parents(nodes)
        // Zomato's menu stepper keeps its count in contentDesc; its cart stepper keeps it in text.
        return nodes.filter { !it.editable && NUMBER.matches(count(it)) }.mapNotNull { n ->
            val siblings = nodes.filter { parent[it.index] == parent[n.index] && parent[n.index] >= 0 }
            val at = siblings.indexOf(n)
            val minus = siblings.getOrNull(at - 1)?.takeIf { it.clickable }
            val plus = siblings.getOrNull(at + 1)?.takeIf { it.clickable }
            if (minus != null && plus != null) Stepper(count(n).toInt(), minus, plus, n) else null
        }
    }

    /** The stepper in the row that names [near] / [fallback]; with no anchor, only an unambiguous single stepper. */
    fun find(nodes: List<UiNode>, near: String, fallback: String?): Stepper? {
        val all = all(nodes)
        if (near.isBlank() && fallback.isNullOrBlank()) return all.singleOrNull()
        val numbers = all.map { it.number }.toSet()
        // Later in the tree = drawn on top: the cart's stepper, not the same dish's stepper on the menu behind it.
        val hit = RowContext.pick(nodes, all.map { it.number }.reversed(), near, fallback) { it in numbers } ?: return null
        return all.first { it.number == hit }
    }
}
