package com.tva.teachable.core

/**
 * What the screen says about a cart that was already there (T7). Read from captions only, so it works for any shopping
 * app that shows a "View cart · N items" bar or asks whether to replace the basket. It only ever informs the question
 * put to the user; nothing here is a tap target.
 */
object CartState {
    private val VIEW = Regex("^view (cart|basket|bag)$", RegexOption.IGNORE_CASE)
    private val COUNT = Regex("^(\\d{1,3}) items?$", RegexOption.IGNORE_CASE)
    // The prompt an app shows when adding from another shop: replacing loses what the user had, so it is never tapped.
    private val REPLACE = Regex(
        "replace (the |your )?(cart|items|basket)|start (afresh|a new (cart|basket))|discard (the |your )?(cart|items)|clear (the |your )?(cart|basket)",
        RegexOption.IGNORE_CASE,
    )
    private const val MAX_PROMPT = 90

    fun itemsInCart(nodes: List<UiNode>): Int? {
        if (nodes.none { VIEW.matches(it.text.trim()) }) return null
        return nodes.firstNotNullOfOrNull { COUNT.matchEntire(it.text.trim())?.groupValues?.get(1)?.toIntOrNull() }?.takeIf { it > 0 }
    }

    fun replacePrompt(nodes: List<UiNode>): String? =
        nodes.asSequence().filter { !it.editable && !it.password }
            .flatMap { sequenceOf(it.text, it.contentDesc) }.map { it.trim() }
            .firstOrNull { it.length <= MAX_PROMPT && REPLACE.containsMatchIn(it) }

    /** Words on a control that would throw away the user's cart or data: never chosen by a guess. */
    val DESTRUCTIVE = Regex("\\b(replace|clear|remove|delete|discard|start afresh)\\b", RegexOption.IGNORE_CASE)
}
