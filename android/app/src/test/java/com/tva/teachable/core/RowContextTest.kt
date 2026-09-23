package com.tva.teachable.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Shaped like Zomato's menu: one identical ADD per dish, plus a paid-membership ADD that must never be picked. */
class RowContextTest {
    private val nodes = mutableListOf<UiNode>()
    private fun n(depth: Int, text: String = "", desc: String = "", id: String = "", click: Boolean = false) =
        UiNode(nodes.size, depth, "z", id, text, desc, "", "", click, false, false, false).also { nodes += it }

    private val add = "z:id/text_view_title"
    private val isAdd = { u: UiNode -> u.resourceId == add && u.text == "ADD" }

    init {
        n(0)
        n(1); n(2, desc = "Margherita Pizza", id = "z:id/dish_name"); n(2); n(3, text = "ADD", id = add, click = true) // 4
        n(1); n(2, desc = "Double Cheese Margherita", id = "z:id/dish_name"); n(2); n(3, text = "ADD", id = add, click = true) // 8
        n(1); n(2, desc = "Renew Gold for 3 months"); n(2, text = "ADD", id = add, click = true) // 11
    }

    private fun pick(near: String, fallback: String?) = RowContext.pick(nodes, nodes.filter(isAdd), near, fallback, isAdd)?.index

    @Test fun exactRowCaptionWins() = assertEquals(4, pick("Margherita Pizza", "Margherita"))
    @Test fun captionIsPunctuationAndCaseInsensitive() = assertEquals(8, pick("double cheese margherita", null))
    @Test fun fallsBackToRowContainingTheSlotValue() = assertEquals(8, pick("Double Cheese Pizza", "Double Cheese"))
    @Test fun noMatchingRowMeansNoTapNotTheFirstAdd() = assertNull(pick("Farmhouse Pizza", "Farmhouse"))

    @Test fun stepperIsFoundInTheItemsRow() {
        nodes.clear()
        n(0)
        n(1); n(2, desc = "Margherita Pizza"); n(2); n(3, id = "z:id/minus", click = true); n(3, text = "2"); n(3, id = "z:id/plus", click = true)
        n(1); n(2, desc = "Farmhouse Pizza"); n(2); n(3, id = "z:id/minus", click = true); n(3, text = "1"); n(3, id = "z:id/plus", click = true)
        val s = Steppers.find(nodes, "Farmhouse Pizza", "Farmhouse")!!
        assertEquals(1, s.count)
        assertEquals(12, s.plus.index)
        assertEquals(10, s.minus.index)
        assertEquals(2, Steppers.find(nodes, "", "Margherita")!!.count)
        assertNull(Steppers.find(nodes, "", null)) // two steppers and no anchor: ambiguous, so none
    }

    @Test fun plainNumbersAreNotSteppers() {
        nodes.clear()
        n(0); n(1, text = "4.6"); n(1, text = "12"); n(1, text = "Filters", click = true)
        assertEquals(0, Steppers.all(nodes).size)
    }
}
