package com.tva.teachable.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Every way of answering "what should I do?" that a person might actually say or type. */
class AnswerParserTest {
    private fun a(s: String) = AnswerParser.parse(s)

    @Test fun namingAControlTapsIt() {
        for (s in listOf("tap Continue", "Tap continue.", "press the continue button", "click on Continue", "the continue button", "continue", "Continue!"))
            assertEquals(s, Answer.Tap("continue"), a(s))
        assertEquals(Answer.Tap("view cart"), a("please tap on View Cart"))
        assertEquals(Answer.Tap("add item"), a("select the Add item option"))
    }

    @Test fun stopWordsStop() {
        for (s in listOf("stop", "Cancel", "never mind", "forget it", "leave it", "leave it as it is", "no", "no thanks", "don't", "nothing"))
            assertEquals(s, Answer.Stop, a(s))
    }

    @Test fun skipWordsSkip() {
        for (s in listOf("skip", "skip it", "Skip this step", "ignore it", "not needed", "move on", "carry on"))
            assertEquals(s, Answer.Skip, a(s))
    }

    @Test fun yesAndTryAgainWordsRetry() {
        for (s in listOf("yes", "yeah", "ok", "okay", "try again", "retry", "I'm back", "done", "go ahead", "add another", "add one more", "one more", "another one"))
            assertEquals(s, Answer.Retry, a(s))
    }

    @Test fun nextIsAButtonNotASkip() {
        // "Next" is a very common caption; treating it as "skip" would swallow a legitimate "tap next".
        assertEquals(Answer.Tap("next"), a("next"))
        assertEquals(Answer.Tap("next"), a("tap next"))
    }

    @Test fun nothingUsableIsUnclear() {
        assertEquals(Answer.Unclear, a(""))
        assertEquals(Answer.Unclear, a("   "))
        assertEquals(Answer.Unclear, a("..."))
        // A whole sentence is not a caption: asking again beats tapping the nearest thing to it.
        assertEquals(Answer.Unclear, a("well I was thinking you could maybe look around for something that goes to the next page"))
    }

    @Test fun punctuationInsideACaptionSurvives() {
        assertEquals(Answer.Tap("domino's pizza"), a("tap Domino's Pizza"))
        assertEquals(Answer.Tap("corn & cheese"), a("tap corn & cheese"))
    }

    @Test fun wordsAroundAnAnswerDoNotHideIt() {
        // Said into the mic under pressure: "okay, skip it" used to become a tap on a control called "okay skip it".
        assertEquals(Answer.Skip, a("okay, skip it"))
        assertEquals(Answer.Stop, a("please just stop"))
        assertEquals(Answer.Tap("continue"), a("um tap Continue please"))
        assertEquals(Answer.Retry, a("okay"))
        assertEquals(Answer.Retry, a("yes"))
    }

    @Test fun repliesFromTheLiveSessionOf2026_10_21() {
        assertEquals(Answer.Retry, a("Done. Done."))                         // was a tap on "done done"
        val chained = a("tap select address at next step button and just select home") as Answer.Tap
        assertEquals("select address at next step", chained.spoken)          // the caption's own "select" is kept
        assertEquals(listOf("home"), chained.then)                           // was one 55-character "caption"
        assertEquals(Answer.Tap("bottom"), a("Click bottom button."))
    }

    @Test fun doneThankYouMeansTheUserGotThere() {
        for (said in listOf("Done. Thank you.", "done thank you", "thank you", "thanks", "okay thanks", "that's it", "that's all, thanks", "all done thanks"))
            assertEquals(said, Answer.Finished, a(said))
        // A bare "done" still means "I'm back, look again"; "no thanks" still means stop.
        assertEquals(Answer.Retry, a("done"))
        assertEquals(Answer.Stop, a("no thanks"))
    }
}
