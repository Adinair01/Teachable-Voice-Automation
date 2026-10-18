package com.tva.teachable.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectorMatcherTest {
    private fun node(i: Int, id: String = "", text: String = "", desc: String = "", cls: String = "", pwd: Boolean = false, click: Boolean = true) =
        UiNode(i, 0, "p", id, text, desc, cls, "", click, false, false, pwd)

    @Test fun resourceIdBeatsText() {
        val nodes = listOf(node(0, text = "Go"), node(1, id = "a:id/go", text = "Other"))
        assertEquals(1, SelectorMatcher.find(nodes, Selector(resourceId = "a:id/go", text = "Go"))?.index)
    }

    @Test fun textMatchIsCaseInsensitive() {
        assertEquals(0, SelectorMatcher.find(listOf(node(0, text = "Bluetooth")), Selector(text = "bluetooth"))?.index)
    }

    @Test fun passwordNodesNeverMatchByText() {
        assertNull(SelectorMatcher.find(listOf(node(0, text = "[REDACTED]", pwd = true)), Selector(text = "[redacted]")))
    }

    @Test fun containsModeMatchesSubstring() {
        val nodes = listOf(node(0, text = "pizza near me"))
        assertNull(SelectorMatcher.find(nodes, Selector(text = "pizza")))
        assertEquals(0, SelectorMatcher.find(nodes, Selector(text = "pizza"), textContains = true)?.index)
    }

    @Test fun classNameAloneDoesNotRescueAFailedTextMatch() {
        val nodes = listOf(node(0, cls = "android.widget.LinearLayout"), node(1, text = "Display & brightness"))
        assertNull(SelectorMatcher.find(nodes, Selector(text = "Display Off", className = "android.widget.LinearLayout")))
        assertEquals(0, SelectorMatcher.find(nodes, Selector(className = "android.widget.LinearLayout"))?.index)
    }

    @Test fun tapPrefersTheClickableNodeWhenACaptionAppearsTwice() {
        // A bottom-tab item: a clickable icon carrying the description, and a dead caption carrying the same text.
        val nodes = listOf(node(0, text = "Timer", click = false), node(1, desc = "Timer"))
        assertEquals(1, SelectorMatcher.findTarget(nodes, Selector(text = "Timer", contentDesc = "Timer"), forTap = true)?.index)
        assertEquals(0, SelectorMatcher.findTarget(nodes, Selector(text = "Timer"))?.index) // non-tap steps keep plain priority
    }

    @Test fun tapFallsBackToNonClickableNodesWhenNoClickableOneMatches() {
        val nodes = listOf(node(0, text = "Bluetooth Off", click = false), node(1, text = "Wi-Fi"))
        assertEquals(0, SelectorMatcher.findTarget(nodes, Selector(text = "Bluetooth Off"), forTap = true)?.index)
    }

    @Test fun slotBoundTapFallsBackToContainsOnTheSlotValue() {
        val nodes = listOf(node(0, text = "pizza near me"))
        assertEquals(0, SelectorMatcher.findTarget(nodes, Selector(text = "pizza restaurant"), slotValue = "pizza", forTap = true)?.index)
    }

    @Test fun slotTokensResolve() {
        assertEquals("burger near me", resolveSlots("{{query}} near me", mapOf("query" to "burger")))
    }

    /** Nodes with real depth, so ancestry (and therefore "which control would this tap hit") is meaningful. */
    private fun tree(vararg rows: Triple<Int, String, Boolean>) = rows.mapIndexed { i, (d, t, click) ->
        UiNode(i, d, "p", if (t == "ADD") "z:id/add_button" else "", if (t == "ADD") "" else t, if (t == "ADD") "ADD" else "", "", "", click, false, false, false)
    }

    @Test fun aCaptionRepeatedAcrossSectionsIsNotAmbiguous() {
        // Real Zomato search: "Domino's Pizza" appears in the suggestion list, a recommendation carousel and a third
        // section, each inside its own clickable row. The caption NAMES the target, so the first hit is the one the
        // demonstration used — refusing here would stall the taught flow at the restaurant step.
        val nodes = tree(
            Triple(0, "", false),
            Triple(1, "", true), Triple(2, "Domino's Pizza", false),
            Triple(1, "", true), Triple(2, "Domino's Pizza", false),
            Triple(1, "", true), Triple(2, "Domino's Pizza", false),
        )
        assertEquals(2, SelectorMatcher.target(nodes, Selector(text = "Domino's Pizza"), null, forTap = true)?.index)
    }

    @Test fun anActionCaptionRepeatedPerRowIsRefused() {
        // The same shape, but the caption names an ACTION: one ADD per dish identifies nothing, so acting on the
        // first would add whichever dish happens to come first in the tree.
        val nodes = tree(
            Triple(0, "", false),
            Triple(1, "", true), Triple(2, "Margherita Pizza", false), Triple(2, "ADD", true),
            Triple(1, "", true), Triple(2, "Farmhouse Pizza", false), Triple(2, "ADD", true),
        )
        assertNull(SelectorMatcher.target(nodes, Selector(resourceId = "z:id/add_button", contentDesc = "ADD"), null, forTap = true))
        // The row anchor is how that tap is meant to be aimed, and it still works.
        assertEquals(6, SelectorMatcher.target(nodes, Selector(contentDesc = "ADD", near = "Farmhouse Pizza"), null, forTap = true)?.index)
    }

    @Test fun spokenSlotMatchesDespitePunctuation() {
        assertEquals(0, SelectorMatcher.find(listOf(node(0, text = "Domino's Pizza")), Selector(text = "dominos Pizza"))?.index)
    }
}

class ConfirmTextTest {
    @Test fun affirmative() { assertEquals(true, ConfirmText.isYes("yes go ahead")) }
    @Test fun negativeOrUnclearIsNo() {
        assertEquals(false, ConfirmText.isYes("no don't")); assertEquals(false, ConfirmText.isYes("hmm maybe")); assertEquals(false, ConfirmText.isYes("yes... actually cancel"))
    }
}
