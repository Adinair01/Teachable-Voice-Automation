package com.tva.teachable.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** The row of a control that does NOT repeat must stay a row, not become the whole screen. */
class RowContextRootTest {
    private fun n(i: Int, d: Int, text: String = "", desc: String = "", click: Boolean = false) =
        UiNode(i, d, "p", "", text, desc, "", "", click, false, false, false)

    @Test fun aSingleControlsRowIsNotTheWholeScreen() {
        // Margherita row (already in cart: stepper, no ADD) + Farmhouse row (the only ADD on screen).
        val nodes = listOf(
            n(0, 0),
            n(1, 1, click = true), n(2, 2, "Margherita Pizza"), n(3, 2, "−", click = true), n(4, 2, "1"), n(5, 2, "+", click = true),
            n(6, 1, click = true), n(7, 2, "Farmhouse Pizza"), n(8, 2, desc = "ADD", click = true),
        )
        val adds = nodes.filter { it.contentDesc == "ADD" }
        val row = RowContext.of(nodes, adds[0], { it in adds.toSet() })
        println("ROW TEXTS for the only ADD = $row")

        // The question asked of the screen: "ADD in the Margherita Pizza row".
        val picked = RowContext.pick(nodes, adds, "Margherita Pizza", "Margherita pizza") { it in adds.toSet() }
        // The climb is bounded by the root, so the only ADD keeps its own row and a caption from the other dish's
        // row no longer satisfies the anchor: asking for Margherita can never add Farmhouse.
        assertEquals("the row is the dish's own row", listOf("Farmhouse Pizza", "ADD"), row)
        assertEquals("a different dish's anchor must be refused", null, picked)
    }
}
