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

    /**
     * The row holding [node]. The climb stops at whichever comes first: another control of the same kind one level
     * up (the normal case in a list), the scrollable list itself, or the root.
     *
     * The last two bounds matter when the control does NOT repeat — a dish already in the cart leaves a single ADD
     * on screen. Without them the climb ran all the way to the root, so "the row" became the whole screen and ANY
     * caption on it satisfied the anchor: asking for one dish could add a different one. Keeping the row at the
     * outermost non-root, non-list ancestor means an anchor that is not really in the row simply fails to match,
     * and the caller asks instead of tapping.
     */
    fun rowOf(nodes: List<UiNode>, node: UiNode, sameKind: (UiNode) -> Boolean, parent: IntArray = parents(nodes)): UiNode {
        var row = node
        for (up in 0 until MAX_UP) {
            // getOrElse, not [..]: a caller may hand us a node list whose indices do not line up with it, and a
            // crash here would take down a judged run.
            val p = nodes.getOrNull(parent.getOrElse(row.index) { -1 }) ?: break
            if (subtree(nodes, p).any { it.index != node.index && sameKind(it) }) break
            if (p.scrollable || parent.getOrElse(p.index) { -1 } < 0) break // the list, and the screen, are never a row
            row = p
        }
        return row
    }

    /** Visible texts of the row holding [node]; [sameKind] says which nodes count as "another one of these". */
    fun of(nodes: List<UiNode>, node: UiNode, sameKind: (UiNode) -> Boolean, parent: IntArray = parents(nodes)): List<String> =
        texts(nodes, rowOf(nodes, node, sameKind, parent))

    /**
     * Picks the candidate whose row names [near] (whole caption, normalised: "{{item}} Pizza" -> "Farmhouse Pizza"),
     * else one whose row merely contains [fallback] (the bare slot value). Null when no row qualifies: tapping the
     * first identical button instead could add the wrong dish, or a paid membership.
     */
    fun pick(nodes: List<UiNode>, candidates: List<UiNode>, near: String, fallback: String?, sameKind: (UiNode) -> Boolean): UiNode? {
        if (candidates.isEmpty()) return null
        val parent = parents(nodes)
        val raw = candidates.map { it to of(nodes, it, sameKind, parent) }
        val n = norm(near)
        if (n.isNotEmpty()) raw.firstOrNull { (_, texts) -> texts.any { norm(it) == n } }?.let { return it.first }
        // The item itself is on screen but its row has no such control (a dish already in the cart shows a stepper
        // instead of ADD): a neighbour that merely contains the words ("Double Cheese Margherita Pizza") is a different dish.
        if (n.isNotEmpty() && nodes.any { !it.editable && !it.password && (norm(it.text) == n || norm(it.contentDesc) == n) }) return null
        // The bare slot value, then its longest word: "Margherita pizzas" (spoken plural) still finds "Margherita Pizza".
        val word = fallback.orEmpty().split(Regex("\\s+")).maxByOrNull { it.length }.orEmpty()
        for (f in listOf(fallback.orEmpty(), word.takeIf { norm(it).length >= 4 }.orEmpty()).filter { norm(it).isNotEmpty() }.distinct()) {
            closestRow(raw, f)?.let { return it.getOrNull() }
        }
        return null
    }

    /**
     * Among rows that mention [needle], the one whose caption is closest to being exactly it (fewest extra words):
     * "Margherita Pizza" beats "Double Cheese Margherita Pizza" for "margherita" in EITHER list order — tree order
     * used to decide. null = no row mentions it; Result(null) = two different names equally close: ask, don't guess.
     * The same name twice (one dish listed in two menu sections) is one dish, and the first is used.
     */
    private fun closestRow(rows: List<Pair<UiNode, List<String>>>, needle: String): Result<UiNode?>? {
        val f = norm(needle)
        val scored = rows.mapNotNull { (node, texts) ->
            texts.mapNotNull { t ->
                (Captions.extraWords(t, needle) ?: if (f in norm(t)) maxOf(0, Captions.words(t).size - Captions.words(needle).size) else null)
                    ?.let { t to it }
            }.minByOrNull { it.second }?.let { (t, extra) -> Triple(node, t, extra) }
        }
        if (scored.isEmpty()) return null
        val best = scored.minOf { it.third }
        val top = scored.filter { it.third == best }
        return Result.success(if (top.map { norm(it.second) }.distinct().size == 1) top.first().first else null)
    }

    /**
     * Case, spacing and punctuation-insensitive form: "Domino's Pizza" == "dominos pizza". "&" becomes "and" first,
     * because a menu writes "Corn & Cheese Pizza" while the user says "corn and cheese pizza"; dropping the "&"
     * instead would leave the two forms permanently unequal.
     */
    fun norm(s: String) = PRICE.replace(s.lowercase().replace("&", " and "), " ").filter { it.isLetterOrDigit() }

    // A money amount in a caption belongs to the moment it was recorded, not to the control: the button that was
    // "Add item ₹114" during the demonstration reads "Add item ₹149" for another size, another city or after a price
    // change. Dropping the amount leaves the part that actually names the control. Digits inside a NAME survive,
    // because only a currency-prefixed number is removed ("Big Big 6in1 Pizza" keeps its 6 and 1).
    private val PRICE = Regex("""(?:\u20b9|rs\.?|inr|\$)\s*[\d,]+(?:\.\d+)?""", RegexOption.IGNORE_CASE)

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
        val hidden = Visibility.hidden(nodes)
        // Zomato's menu stepper keeps its count in contentDesc; its cart stepper keeps it in text. The menu's steppers
        // under an open cart are hidden, so the cart's own are the ones used.
        return nodes.filter { !hidden[it.index] && !it.editable && NUMBER.matches(count(it)) }.mapNotNull { n ->
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
