package com.tva.teachable.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapTargetTest {
    private val nodes = mutableListOf<UiNode>()
    private fun n(depth: Int, bounds: String, text: String = "", desc: String = "", id: String = "", click: Boolean = false) =
        UiNode(nodes.size, depth, "z", id, text, desc, "", bounds, click, false, false, false).also { nodes += it }

    init {
        // top window: a dialog with its confirm button
        n(0, "0 1200 1080 2412"); n(1, "384 2220 1044 2376", text = "Add item ₹114", id = "z:id/button", click = true)
        // page underneath: a Compose card (clickable, captions on children) and a row ADD
        n(0, "0 0 1080 2412")
        n(1, "36 400 1044 600", click = true); n(2, "60 420 600 480", text = "Domino's Pizza"); n(2, "60 500 300 540", text = "4.6")
        n(1, "36 660 1044 1000"); n(2, "36 660 429 731", desc = "Margherita Pizza"); n(2, "714 865 911 985", desc = "ADD", id = "z:id/text_view_title", click = true)
    }

    @Test fun dialogButtonWinsOverThePageUnderIt() = assertEquals(1, TapTarget.at(nodes, 700, 2300)?.index)
    @Test fun composeCardTakesItsFirstCaption() {
        val card = TapTarget.at(nodes, 500, 450)!!
        assertEquals(3, card.index)
        assertEquals("Domino's Pizza", TapTarget.selector(nodes, card).text)
    }
    @Test fun rowButtonIsTheDeepestClickable() = assertEquals(8, TapTarget.at(nodes, 800, 900)?.index)
    @Test fun sharedIdIsNarrowedByCaption() {
        val list = listOf(
            UiNode(0, 0, "z", "z:id/container", "", "", "", "", true, false, false, false),
            UiNode(1, 1, "z", "", "Unlock 20% OFF", "", "", "", false, false, false, false),
            UiNode(2, 0, "z", "z:id/container", "", "", "", "", true, false, false, false),
            UiNode(3, 1, "z", "", "", "1 item added", "", "", false, false, false, false),
        )
        assertEquals(2, SelectorMatcher.find(list, Selector(resourceId = "z:id/container", contentDesc = "1 item added"))?.index)
    }

    @Test fun aSnapshotStandsInForTheScreenOnlyWhenTheControlUnderTheFingerIsUnchanged() {
        val add = UiNode(5, 3, "z", "z:id/add", "", "ADD", "android.view.View", "800 400 1000 500", true, false, false, false)
        assertTrue(TapTarget.sameControl(add, add.copy(index = 9, depth = 4)))     // same control, read on another path
        assertFalse(TapTarget.sameControl(add, add.copy(bounds = "800 300 1000 400"))) // the list scrolled
        assertFalse(TapTarget.sameControl(add, add.copy(contentDesc = "")))            // ADD became a stepper
        assertFalse(TapTarget.sameControl(add, add.copy(resourceId = "z:id/other")))   // a different control
    }
}
