package com.tva.teachable.core

/** What the user meant by their reply to a question the assistant asked when a step could not proceed. */
sealed interface Answer {
    /** Give up on this run. */
    data object Stop : Answer
    /** Leave this step out and carry on. */
    data object Skip : Answer
    /** "Yes", "I'm back", "try again": look again (or, for a cart question, add another). */
    data object Retry : Answer
    /** Tap the control captioned [caption] instead. */
    data class Tap(val caption: String) : Answer
    /** Nothing usable was said. */
    data object Unclear : Answer
}

/**
 * Reads a short spoken or typed reply into an [Answer]. Deliberately a small closed vocabulary: the reply drives a
 * tap on someone's phone, so anything it cannot read confidently is [Answer.Unclear] and gets asked again rather
 * than guessed at. Pure, so every phrasing is covered by a unit test.
 */
object AnswerParser {
    private val STOP = Regex("^(stop|cancel|abort|quit|never ?mind|forget it|leave it( (as it is|alone))?|leave (it|that) be|no|nope|no thanks|don'?t( do (it|that))?|nothing)( please)?$")
    private val SKIP = Regex("^(skip( it| this| that| this step| that step)?|ignore( it| that| this)?|not needed|no need|move on|carry on|go on without (it|that))( please)?$")
    // "yes"-like words and "again": for a cart question these mean "add another"; elsewhere "look again".
    private val RETRY = Regex("^(retry|try again|again|i'?m back|i am back|ok|okay|done|go ahead|go on|yes|yeah|yep|yup|sure|please do|add (another|one more|it|one)|another( one)?|one more)( please)?$")
    private val LEAD = Regex("^(please )?(can you |could you )?(tap|press|click|select|choose|hit|touch|open|go to|tap on|press on|click on|tap the|press the|click the)\\b ?")
    private val DETERMINER = Regex("^(the|a|an|on|on the|that|this) ")
    private val TRAIL = Regex(" (button|option|link|tab|icon|one)$")
    private const val MAX_CAPTION = 60

    fun parse(raw: String): Answer {
        val t = raw.lowercase().replace(Regex("[\\p{P}&&[^'&]]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return Answer.Unclear
        if (STOP.matches(t)) return Answer.Stop
        if (SKIP.matches(t)) return Answer.Skip
        if (RETRY.matches(t)) return Answer.Retry
        var caption = LEAD.replace(t, "").trim()
        repeat(2) { caption = DETERMINER.replace(caption, "").trim() }
        caption = TRAIL.replace(caption, "").trim()
        // A whole sentence is not a caption; asking again beats tapping the nearest thing to it.
        return if (caption.isEmpty() || caption.length > MAX_CAPTION) Answer.Unclear else Answer.Tap(caption)
    }
}
