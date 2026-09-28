package com.tva.teachable.replay

import com.tva.teachable.a11y.LiveTree
import com.tva.teachable.core.Flow
import com.tva.teachable.core.FlowStep
import com.tva.teachable.core.ScreenSummary
import com.tva.teachable.core.Selector
import com.tva.teachable.core.StepType
import com.tva.teachable.core.UiNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T10: a screen the flow cannot continue from ends in one specific question, never a loop or a blind tap. */
class ReplayerStuckTest {
    private class FakeDevice(var screen: List<String>, val pkg: String = "com.application.zomato") : Device {
        val tapped = mutableListOf<String>()
        override fun tree() = LiveTree(screen.mapIndexed { i, s -> UiNode(i, 1, pkg, "", s, "", "", "", true, false, false, false) }, emptyList())
        override fun launch(packageName: String) = true
        override fun tap(tree: LiveTree, node: UiNode) = true.also { tapped += node.text }
        override fun type(tree: LiveTree, node: UiNode, value: String) = true
        override fun scroll(tree: LiveTree) = false
        override fun back() = true
    }

    private val fast = ReplayConfig(waitMs = 30, retryMs = 30, pollMs = 10, settleMs = 30)
    private fun tap(text: String) = FlowStep(StepType.TAP, Selector(text = text))
    private fun flow(vararg steps: FlowStep) = Flow("order_food", "order food", "com.application.zomato", steps.toList())

    @Test fun missingTargetNamesItAndWhatTheScreenShowsAsAQuestion() = runBlocking {
        val d = FakeDevice(listOf("Bienvenue", "Se connecter?x", "Langue"))
        val r = Replayer(d, cfg = fast).run(flow(tap("Add item")), emptyMap())
        val err = r.steps.single().error!!
        assertFalse(r.success)
        assertTrue(err, err.contains("\"Add item\"") && err.contains("\"Bienvenue\"") && err.endsWith("What should I do?"))
        assertTrue(d.tapped.isEmpty())
    }

    @Test fun beingOutsideTheAppIsSaidAsSuch() = runBlocking {
        val d = FakeDevice(listOf("Home"), pkg = "com.android.launcher")
        val r = Replayer(d, cfg = fast).run(flow(tap("Add item")), emptyMap())
        assertTrue(r.steps.single().error!!.contains("not in the app any more"))
        assertTrue(d.tapped.isEmpty())
    }

    @Test fun llmGuessesAreCappedPerRun() = runBlocking {
        val d = FakeDevice(listOf("Something else"))
        val picker = ElementPicker { _, _, _ -> 0 }
        val r = Replayer(d, picker, cfg = fast).run(flow(tap("A"), tap("B"), tap("C"), tap("D")), emptyMap())
        assertEquals(2, d.tapped.size) // third step finds nothing and no third guess is made
        assertEquals(listOf(true, true, false), r.steps.map { it.ok })
    }

    @Test fun summaryKeepsPersonalDataOut() {
        val nodes = listOf(
            UiNode(0, 1, "p", "", "Phone: 919876543210", "", "", "", false, false, false, false),
            UiNode(1, 1, "p", "", "hunter2", "", "", "", false, true, false, true),
            UiNode(2, 1, "p", "", "Work", "", "", "", true, false, false, false),
            UiNode(3, 1, "p", "", "work", "", "", "", true, false, false, false),
            UiNode(4, 1, "p", "", "F205,F block, Abode Valley, Potheri, Chennai", "", "", "", false, false, false, false),
        )
        assertEquals("\"Work\"", ScreenSummary.of(nodes))
        assertEquals("nothing I can read", ScreenSummary.of(emptyList()))
    }
}
