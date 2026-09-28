package com.tva.teachable.replay

import com.tva.teachable.a11y.LiveTree
import com.tva.teachable.core.Flow
import com.tva.teachable.core.FlowStep
import com.tva.teachable.core.Selector
import com.tva.teachable.core.StepType
import com.tva.teachable.core.UiNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T4 item row, T5 quantity stepper, T6 saved address, and a demo-only interstitial that is not shown on replay. */
class ReplayerSlotsTest {
    /** A cart-like screen whose stepper and delivery header really change when tapped. */
    private class Cart(
        var qty: Int = 1, var address: String = "Home", val labels: List<String> = listOf("Home", "Work"),
        val outOfRange: Set<String> = emptySet(),
    ) : Device {
        var pickerOpen = false
        val tapped = mutableListOf<String>()
        private fun screen(): List<Triple<Int, String, Boolean>> = if (pickerOpen)
            listOf(Triple(0, "", false), Triple(1, "Select an address", false)) + labels.map { Triple(1, it, true) }
        else listOf(
            Triple(0, "", false), Triple(1, "25-30 mins to $address", true),
            Triple(1, "", false), Triple(2, "Margherita Pizza", false), Triple(2, "", false),
            Triple(3, "−", true), Triple(3, "$qty", false), Triple(3, "+", true),
            Triple(1, "ADD", true), // Gold-membership style ADD elsewhere on the page
            Triple(1, "Bill details", false), // a real cart always shows its bill; the run must see it to call the order ready
        )
        override fun tree() = LiveTree(screen().mapIndexed { i, (d, t, c) -> UiNode(i, d, "com.application.zomato", "", t, "", "", "", c, false, false, false) }, emptyList())
        override fun launch(packageName: String) = true
        override fun tap(tree: LiveTree, node: UiNode): Boolean {
            tapped += node.text
            when {
                node.text == "+" -> qty++
                node.text == "−" -> qty--
                node.text.startsWith("25-30") -> pickerOpen = true
                pickerOpen && node.text in labels && node.text !in outOfRange -> { address = node.text; pickerOpen = false }
            }
            return true
        }
        override fun type(tree: LiveTree, node: UiNode, value: String) = true
        override fun scroll(tree: LiveTree) = false
        override fun back() = true.also { pickerOpen = false }
    }

    private val fast = ReplayConfig(waitMs = 30, retryMs = 30, pollMs = 10, settleMs = 30)
    private fun flow(vararg s: FlowStep) = Flow("order_food", "order food", "com.application.zomato", s.toList())
    private val qtyStep = FlowStep(StepType.SET_QUANTITY, Selector(near = "{{item}} Pizza"), slotRef = "quantity")
    private val addrStep = FlowStep(StepType.CHOOSE_ADDRESS, slotRef = "address")

    @Test fun quantityIsSteppedUpAndReadBack() = runBlocking {
        val d = Cart()
        val r = Replayer(d, cfg = fast).run(flow(qtyStep), mapOf("item" to "Margherita", "quantity" to "3"))
        assertTrue(r.steps.toString(), r.steps.all { it.ok })
        assertEquals(3, d.qty)
        assertEquals(listOf("+", "+"), d.tapped)
    }

    @Test fun quantityWithoutASlotIsLeftAlone() = runBlocking {
        val d = Cart()
        Replayer(d, cfg = fast).run(flow(qtyStep), mapOf("item" to "Margherita"))
        assertTrue(d.tapped.isEmpty())
    }

    @Test fun addressIsChosenFromSavedLabels() = runBlocking {
        val d = Cart()
        val r = Replayer(d, cfg = fast).run(flow(addrStep), mapOf("address" to "work"))
        assertTrue(r.steps.toString(), r.steps.all { it.ok })
        assertEquals("Work", d.address)
    }

    @Test fun unknownAddressFailsWithASpecificReasonAndClosesThePicker() = runBlocking {
        val d = Cart()
        val r = Replayer(d, cfg = fast).run(flow(addrStep), mapOf("address" to "gym"))
        assertEquals("there's no saved address called \"gym\"", r.steps.last().error)
        assertEquals("Home", d.address)
        assertTrue(!d.pickerOpen)
    }

    @Test fun addressTheAppRefusesIsReportedAndThePickerClosed() = runBlocking {
        val d = Cart(address = "Work", outOfRange = setOf("Home"))
        val r = Replayer(d, cfg = fast).run(flow(addrStep), mapOf("address" to "home"))
        assertEquals("the app didn't switch to your \"home\" address, it may be outside the delivery area", r.steps.last().error)
        assertEquals("Work", d.address)
        assertTrue(!d.pickerOpen)
    }

    @Test fun interstitialFromTheDemoIsSkippedWhenNotShown() = runBlocking {
        val d = Cart()
        val r = Replayer(d, cfg = fast).run(flow(FlowStep(StepType.TAP, Selector(text = "Continue")), FlowStep(StepType.TAP, Selector(text = "+"))), emptyMap())
        assertTrue(r.steps.toString(), r.success)
        assertEquals(listOf("+"), d.tapped)
    }
}
