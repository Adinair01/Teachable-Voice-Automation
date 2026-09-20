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
}

class ConfirmTextTest {
    @Test fun affirmative() { assertEquals(true, ConfirmText.isYes("yes go ahead")) }
    @Test fun negativeOrUnclearIsNo() {
        assertEquals(false, ConfirmText.isYes("no don't")); assertEquals(false, ConfirmText.isYes("hmm maybe")); assertEquals(false, ConfirmText.isYes("yes... actually cancel"))
    }
}
