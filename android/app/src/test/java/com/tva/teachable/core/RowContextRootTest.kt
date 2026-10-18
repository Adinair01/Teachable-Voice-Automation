package com.tva.teachable.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Diagnostic: how far up does RowContext.of() walk when only ONE control of that kind is on screen? */
class RowContextRootTest {
    private fun n(i: Int, d: Int, text: String = "", desc: String = "", click: Boolean = false) =
        UiNode(i, d, "p", "", text, desc, "", "", click, false, false, false)

    @Test fun singleControlMakesTheWholeScreenItsRow() {
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
        // CHARACTERISATION, NOT desired behaviour. of() climbs while no OTHER control of the same kind is in the
        // subtree; with a single control on screen nothing stops it, so "the row" becomes the root and the anchor
        // matches a caption belonging to a different dish. Should be null (refuse); is Farmhouse's ADD today.
        assertEquals("documents the root-walk bug: wrong dish chosen", 8, picked?.index)
        assertEquals("the 'row' is really the whole screen", listOf("Margherita Pizza", "\u2212", "1", "+", "Farmhouse Pizza", "ADD"), row)
    }
}
