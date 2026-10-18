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

/**
 * Two ways a replay could claim success without doing the job, both found by running the real taught calculator
 * flow ("Add five and three") against "what is 12 plus 7" on the phone.
 */
class KeypadAndSkipTest {
    /** A calculator: single-digit keys, an add key and an equals key, and a formula line that records the presses. */
    private class Keypad(private val keys: List<String> = (0..9).map { it.toString() }) : Device {
        val pressed = mutableListOf<String>()
        override fun tree(): LiveTree {
            // Digit keys carry their label in text; the operator keys carry theirs in contentDesc, as the real
            // calculator does (which is also what the taught flow's selectors recorded).
            val nodes = ArrayList<UiNode>()
            fun add(id: String, text: String, desc: String, click: Boolean) {
                nodes += UiNode(nodes.size, if (nodes.isEmpty()) 0 else 1, PKG, id, text, desc, "android.widget.Button", "", click, false, false, false)
            }
            add("", "", "", false) // root
            for (k in keys) add("$PKG:id/k_$k", k, "", true)
            add("$PKG:id/op_add", "", "Add", true)
            add("$PKG:id/eq", "", "Equals", true)
            return LiveTree(nodes, emptyList())
        }
        override fun launch(packageName: String) = true
        override fun tap(tree: LiveTree, node: UiNode) = true.also { pressed += node.text.ifEmpty { node.contentDesc } }
        override fun type(tree: LiveTree, node: UiNode, value: String) = true
        override fun scroll(tree: LiveTree) = false
        override fun back() = true
    }

    private val fast = ReplayConfig(waitMs = 30, retryMs = 30, pollMs = 10, settleMs = 30)
    private val num1 = FlowStep(StepType.TAP, Selector(text = "{{num1}}", className = "android.widget.Button"), slotRef = "num1")
    private val add = FlowStep(StepType.TAP, Selector(contentDesc = "Add", className = "android.widget.Button"))
    private val num2 = FlowStep(StepType.TAP, Selector(text = "{{num2}}", className = "android.widget.Button"), slotRef = "num2")
    private val eq = FlowStep(StepType.TAP, Selector(contentDesc = "Equals", className = "android.widget.Button"))

    private fun run(device: Device, slots: Map<String, String>) = runBlocking {
        Replayer(device, cfg = fast).run(Flow("add", "Add five and three", PKG, listOf(num1, add, num2, eq)), slots)
    }

    @Test fun theDemonstratedSingleDigitCaseStillWorks() {
        val d = Keypad()
        val r = run(d, mapOf("num1" to "5", "num2" to "3"))
        assertTrue(r.steps.toString(), r.success)
        assertEquals(listOf("5", "Add", "3", "Equals"), d.pressed)
    }

    @Test fun aMultiDigitValueIsEnteredOneKeyAtATime() {
        // "12" is not a key. Before this, the step was skipped and the run reported success having pressed "+ 7 =".
        val d = Keypad()
        val r = run(d, mapOf("num1" to "12", "num2" to "7"))
        assertTrue(r.steps.toString(), r.success)
        assertEquals(listOf("1", "2", "Add", "7", "Equals"), d.pressed)
    }

    @Test fun aParameterisedStepIsNeverSkippedAsAnInterstitial() {
        // No key for 8 at all: the flow genuinely cannot do what was asked, so it must say so and not press on.
        val d = Keypad(keys = listOf("1", "2", "3", "7"))
        val r = run(d, mapOf("num1" to "8", "num2" to "7"))
        assertTrue("must not report success", !r.success)
        assertEquals("nothing may be pressed", emptyList<String>(), d.pressed)
        assertTrue(r.steps.last().error.orEmpty(), r.steps.last().error.orEmpty().contains("8"))
    }

    @Test fun aHalfEnterableNumberIsRefusedRatherThanPartlyTyped() {
        // "19" with no 9 key: entering just "1" would compute something nobody asked for.
        val d = Keypad(keys = listOf("1", "2", "3", "7"))
        val r = run(d, mapOf("num1" to "19", "num2" to "7"))
        assertTrue(!r.success)
        assertEquals(emptyList<String>(), d.pressed)
    }

    private companion object { const val PKG = "com.coloros.calculator" }
}
