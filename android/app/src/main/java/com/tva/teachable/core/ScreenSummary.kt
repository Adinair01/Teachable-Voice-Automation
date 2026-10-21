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
     * Labels from the page the user is LOOKING at. Apps like Zomato keep the previous page in the accessibility tree
     * and add the new one after it, so reading from the top described the page underneath (seen live: the menu's
     * dishes were read out while the cart was on screen). Read from the end, tappable controls first: what the user
     * can see and could name in an answer.
     */
    fun of(nodes: List<UiNode>, max: Int = 4): String {
        val tappable = SelectorMatcher.tappable(nodes).toSet()
        val labels = nodes.asReversed().sortedBy { if (it in tappable) 0 else 1 }.asSequence()
            .filter { !it.password && !it.editable }
            .flatMap { sequenceOf(it.text, it.contentDesc) }
            .map { it.trim() }
            .filter { it.length in 2..MAX_LABEL && !DIGIT_RUN.containsMatchIn(it) }
            .distinctBy { it.lowercase() }
            .take(max)
            .toList()
        return if (labels.isEmpty()) "nothing I can read" else labels.joinToString(", ") { "\"$it\"" }
    }
}
