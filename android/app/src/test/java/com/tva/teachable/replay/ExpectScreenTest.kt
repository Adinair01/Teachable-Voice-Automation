package com.tva.teachable.replay

import com.tva.teachable.a11y.LiveTree
import com.tva.teachable.core.Expect
import com.tva.teachable.core.Flow
import com.tva.teachable.core.FlowStep
import com.tva.teachable.core.Selector
import com.tva.teachable.core.StepType
import com.tva.teachable.core.UiNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `expect.textContains` holds a slot token, so a replay checks that the screen it opened names the value actually
 * asked for — the difference between "still inside Zomato" and "really on the dish I requested".
 */
class ExpectScreenTest {
    private class Screen(private val captions: List<String>) : Device {
        val tapped = mutableListOf<String>()
        override fun tree() = LiveTree(
            (listOf("") + captions).mapIndexed { i, c ->
                UiNode(i, if (i == 0) 0 else 1, "com.application.zomato", "", c, "", "", "", i > 0, false, false, false)
            },
            emptyList(),
        )
        override fun launch(packageName: String) = true
        override fun tap(tree: LiveTree, node: UiNode) = true.also { tapped += node.text }
        override fun type(tree: LiveTree, node: UiNode, value: String) = true
        override fun scroll(tree: LiveTree) = false
        override fun back() = true
    }

    private val fast = ReplayConfig(waitMs = 30, retryMs = 30, pollMs = 10, settleMs = 30)

    private fun run(captions: List<String>, item: String) = runBlocking {
        val step = FlowStep(
            StepType.TAP, Selector(text = "Details"),
            expect = Expect(textContains = "{{item}}"),
        )
        Replayer(Screen(captions), cfg = fast)
            .run(Flow("order_food", "order food", "com.application.zomato", listOf(step)), mapOf("item" to item))
    }

    @Test fun theScreenMustNameTheValueThatWasAskedFor() {
        val r = run(listOf("Details", "Farmhouse Pizza", "₹299"), "Farmhouse pizza")
        assertTrue(r.steps.toString(), r.success)
        assertEquals("no payment boundary should be involved", null, r.handover)
    }

    @Test fun punctuationAndSpacingDoNotCauseAFalseFailure() {
        // Spoken "corn and cheese pizza" against the menu's "Corn & Cheese Pizza".
        val r = run(listOf("Details", "Corn & Cheese Pizza"), "corn and cheese pizza")
        assertTrue(r.steps.toString(), r.success)
    }

    @Test fun aScreenThatNamesSomethingElseFailsTheStep() {
        // The tap opened the wrong dish: caught here rather than carried into the rest of the run.
        val r = run(listOf("Details", "Double Cheese Margherita"), "Farmhouse pizza")
        assertTrue(!r.success)
        assertEquals("expected screen not reached", r.steps.last().error)
    }
}
