package com.tva.teachable.core

/**
 * A few of the screen's own labels, for telling the user what the assistant is looking at when it cannot go on. Personal
 * data must not leak into a spoken line or a RunLog: password fields, input fields and anything with a long digit run
 * (phone numbers, PINs, card numbers) are skipped, and only short captions are kept.
 */
object ScreenSummary {
    private val DIGIT_RUN = Regex("\\d{4,}")
    private const val MAX_LABEL = 28

    /**
     * Labels from the page the user is LOOKING at, in reading order: pages the app keeps underneath (Zomato's menu
     * under its cart) are left out by drawing order (see Visibility), and the main action at the bottom of the screen
     * ("Select address at next step") is always named, since it is what the user is most likely to answer with.
     */
    fun of(nodes: List<UiNode>, max: Int = 4): String {
        val seen = Visibility.visible(nodes)
        val tappable = SelectorMatcher.tappable(nodes).map { it.index }.toSet()
        // The main action is a WIDE control (half the screen or more): a small tag lower down ("ADDED") is not it.
        val width = nodes.maxOfOrNull { box(it).let { b -> b[2] - b[0] } } ?: 0
        val bottomAction = seen.filter { it.index in tappable && it.text.ifBlank { it.contentDesc }.isNotBlank() }
            .map { it to box(it) }.filter { (_, b) -> b[3] > b[1] && (b[2] - b[0]) * 2 >= width }
            .maxByOrNull { (_, b) -> b[3] }?.first
        val labels = (seen.asSequence() + listOfNotNull(bottomAction).asSequence())
            .filter { !it.password && !it.editable }
            .flatMap { sequenceOf(it.text, it.contentDesc) }
            .map { it.trim() }
            .filter { it.length in 2..MAX_LABEL && !DIGIT_RUN.containsMatchIn(it) }
            .distinctBy { it.lowercase() }
            .toList()
            .let { all -> all.take(max) + all.drop(max).filter { it == label(bottomAction) } }
        return if (labels.isEmpty()) "nothing I can read" else labels.joinToString(", ") { "\"$it\"" }
    }

    private fun box(n: UiNode) = n.bounds.split(' ').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 } ?: listOf(0, 0, 0, 0)

    private fun label(n: UiNode?) = n?.let { it.text.ifBlank { it.contentDesc }.trim() }
}
