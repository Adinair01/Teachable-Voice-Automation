package com.tva.teachable.core

/**
 * What a caption says once everything that belongs to the MOMENT is taken out — the one rule the recorder (what to
 * store), the matcher (what to compare) and the replayer (what to check) share, so the two sides of a comparison are
 * always normalised the same way.
 *
 * Moment-bound parts: a price ("Add item ₹279" is "Add item ₹149" tomorrow), an item count ("1 item added" is
 * "2 items added" with a cart), and a rotating placeholder quoted inside a caption ('Search "binge night"' is
 * 'Search "comfort food"' on the next launch).
 */
object Captions {
    private val PRICE = Regex("""(?:₹|rs\.?|inr|\$)\s*[\d,]+(?:\.\d+)?""", RegexOption.IGNORE_CASE)
    // Double or curly quotes only: an apostrophe is part of names ("Domino's") and never opens a placeholder.
    private val QUOTED = Regex("[\"\u201c\u201d][^\"\u201c\u201d]{2,40}[\"\u201c\u201d]")
    // Only a search prompt's quoted suggestion rotates ('Search "binge night"'); quoted text elsewhere may be the value.
    private val PROMPT = Regex("^(search|try|find|looking for|craving)\\b", RegexOption.IGNORE_CASE)
    private val COUNT = Regex("""\b\d{1,3}\s+(items?|products?|dishes?)\b""", RegexOption.IGNORE_CASE)
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** The caption with its moment-bound parts removed, for STORING a selector: "Add item ₹279" -> "Add item". */
    fun durable(s: String): String = s.replace(PRICE, " ").let { if (PROMPT.containsMatchIn(it.trim())) it.replace(QUOTED, " ") else it }
        .replace(COUNT) { m -> m.groupValues[1].lowercase().removeSuffix("s").removeSuffix("e") }
        .replace(Regex("\\s+"), " ").trim()

    /** Lower-case words of the durable caption, "&" spelled out: the form two captions are COMPARED in. */
    fun words(s: String): List<String> =
        durable(s).lowercase().replace("&", " and ").replace("'", "").split(NOT_WORD).filter { it.isNotEmpty() }.map(::singular)

    /** Do two captions name the same control once prices, counts and placeholders are set aside? */
    fun same(a: String, b: String): Boolean = words(a).let { it.isNotEmpty() && it == words(b) }

    /**
     * How many words [caption] has beyond [needle], or null if it does not contain the needle as a run of words.
     * "Pizza Hut" in "Pizza Hut" = 0, in "Pizza Hut Express" = 1, in "Search in Pizza Hut" = 2. The fewest wins:
     * the caption that is closest to being exactly the requested name.
     */
    fun extraWords(caption: String, needle: String): Int? {
        val c = words(caption)
        val n = words(needle)
        if (n.isEmpty() || c.size < n.size) return null
        for (i in 0..c.size - n.size) if (c.subList(i, i + n.size) == n) return c.size - n.size
        return null
    }

    /**
     * Does [caption] name [want]? Whole value, or (spoken plurals, shorthand) its most distinctive word. Used to check
     * that a tap landed on what was asked for: "Farmhouse pizzas" asked, "Farmhouse Pizza" shown, is the same dish.
     */
    fun mentions(caption: String, want: String): Boolean {
        val w = words(want)
        if (w.isEmpty()) return true
        val c = words(caption)
        if (c.isEmpty()) return false
        if (extraWords(caption, want) != null) return true
        val key = w.filter { it.length >= 4 && it !in GENERIC }.maxByOrNull { it.length } ?: return false
        return key in c
    }

    /** "pizzas" -> "pizza", "dishes" -> "dish": plurals said aloud rarely match the menu's singular. */
    private fun singular(w: String) = when {
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 4 && (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes")) -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    /** Words that describe a kind of thing, not which one: never enough on their own to say a screen named the dish. */
    private val GENERIC = setOf("pizza", "burger", "item", "order", "food", "restaurant", "store", "shop", "menu")
}

/**
 * Taps that only deal with something the app put in the way — closing a popup, a coupon or an offer — rather than
 * doing the task. While teaching they are not steps (a demo that applied a coupon would apply it on every replay); on
 * replay the dismissal ones are what the assistant may tap by itself to clear the way.
 */
object Interruptions {
    /** Controls that only CLOSE something. No Continue/Proceed (they advance the flow) and no OK (it confirms as often as it informs). */
    val DISMISSAL = Regex(
        "^(close|skip|not now|maybe later|no thanks|got it|dismiss|later|[×✕x]|thanks|thank you|woohoo!? ?thanks|awesome|great)$",
        RegexOption.IGNORE_CASE,
    )
    /** Promotions a demo may wander into: applying a coupon, opening an offer. Never part of the task asked for. */
    private val PROMO = Regex(
        "^(apply|apply coupon|apply code|view (all )?offers|offers for you|offers?|claim( now| offer)?|unlock .*|.*\\b\\d{1,2}% off\\b.*|.*\\bcoupons?\\b.*)$",
        RegexOption.IGNORE_CASE,
    )

    fun isDismissal(caption: String) = DISMISSAL.matches(caption.trim())
    fun isInterruption(caption: String) = caption.trim().let { DISMISSAL.matches(it) || PROMO.matches(it) }
}
