package com.tva.teachable.core

/**
 * A few of the screen's own labels, for telling the user what the assistant is looking at when it cannot go on. Personal
 * data must not leak into a spoken line or a RunLog: password fields, input fields and anything with a long digit run
 * (phone numbers, PINs, card numbers) are skipped, and only short captions are kept.
 */
object ScreenSummary {
    private val DIGIT_RUN = Regex("\\d{4,}")
    private const val MAX_LABEL = 28

    fun of(nodes: List<UiNode>, max: Int = 4): String {
        val labels = nodes.asSequence()
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
