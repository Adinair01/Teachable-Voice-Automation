package com.tva.teachable.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectorMatcherTest {
    private fun node(i: Int, id: String = "", text: String = "", desc: String = "", cls: String = "", pwd: Boolean = false, click: Boolean = true) =
        UiNode(i, 0, "p", id, text, desc, cls, "", click, false, false, pwd)

    @Test fun resourceIdBeatsText() {
        // Both "Go"s carry the caption; the one with the recorded id is the control.
        val nodes = listOf(node(0, text = "Go"), node(1, id = "a:id/go", text = "Go"))
        assertEquals(1, SelectorMatcher.find(nodes, Selector(resourceId = "a:id/go", text = "Go"))?.index)
    }

    @Test fun anIdWhoseCaptionDisagreesIsNotTheControl() {
        // Before: the id won whatever its caption said, which is how a step taught on "Woohoo! Thanks" tapped "Replace".
        val nodes = listOf(node(0, text = "Go"), node(1, id = "a:id/go", text = "Other"))
        assertEquals(0, SelectorMatcher.find(nodes, Selector(resourceId = "a:id/go", text = "Go"))?.index)
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

    @Test fun aReusedIdWithANamingCaptionActsOnTheFirstOfSeveralCachedCopies() {
        // Real Zomato home: THREE nodes with id search_edit_text, one per cached page, all carrying the same
        // descriptive caption. They are the same logical control, and the demonstration tapped the first.
        val nodes = listOf(
            UiNode(0, 0, "p", "", "", "", "", "", false, false, false, false),
            UiNode(1, 1, "p", "z:id/search_edit_text", "", "Double tap to open search panel", "", "", true, false, false, false),
            UiNode(2, 1, "p", "z:id/search_edit_text", "", "Double tap to open search panel", "", "", true, false, false, false),
            UiNode(3, 1, "p", "z:id/search_edit_text", "", "Double tap to open search panel", "", "", true, false, false, false),
        )
        val sel = Selector(resourceId = "z:id/search_edit_text", contentDesc = "Double tap to open search panel")
        assertEquals(1, SelectorMatcher.target(nodes, sel, null, forTap = true)?.index)
        // With no caption at all there is nothing to identify them by, so it must refuse.
        assertNull(SelectorMatcher.target(nodes, Selector(resourceId = "z:id/search_edit_text"), null, forTap = true))
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

    @Test fun aPriceInACaptionDoesNotTieTheStepToTheDemonstration() {
        // Taught against "Add item \u20b9114"; the same control reads a different amount for another size, another
        // city, or after a price change. The words are what name the button.
        assertEquals(0, SelectorMatcher.find(listOf(node(0, text = "Add item \u20b9149")), Selector(text = "Add item \u20b9114"))?.index)
        assertEquals(0, SelectorMatcher.find(listOf(node(0, text = "Add item")), Selector(text = "Add item \u20b9114"))?.index)
        // Digits that are part of a name are not a price and must survive.
        assertNull(SelectorMatcher.find(listOf(node(0, text = "Big Big 6in1 Pizza")), Selector(text = "Big Big 4in1 Pizza")))
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
