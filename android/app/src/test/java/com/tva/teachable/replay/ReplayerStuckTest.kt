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
        val d = FakeDevice(listOf("Continue please"))
        val picker = ElementPicker { _, _, _ -> 0 }
        val r = Replayer(d, picker, cfg = fast).run(flow(tap("Continue A"), tap("Continue B"), tap("Continue C"), tap("Continue D")), emptyMap())
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

class CartQuestionTest {
    private class Screen(val texts: List<String>) : Device {
        val tapped = mutableListOf<String>()
        override fun tree() = LiveTree(texts.mapIndexed { i, s -> UiNode(i, 1, "com.application.zomato", "", s, "", "", "", true, false, false, false) }, emptyList())
        override fun launch(packageName: String) = true
        override fun tap(tree: LiveTree, node: UiNode) = true.also { tapped += node.text }
        override fun type(tree: LiveTree, node: UiNode, value: String) = true
        override fun scroll(tree: LiveTree) = false
        override fun back() = true
    }
    private val fast = ReplayConfig(waitMs = 30, retryMs = 30, pollMs = 10, settleMs = 30)
    private fun flow() = Flow("order", "order", "com.application.zomato", listOf(FlowStep(StepType.TAP, Selector(text = "ADD"))))

    @Test fun existingCartIsNamedInTheQuestion() = runBlocking {
        val d = Screen(listOf("Domino's Pizza", "View Cart", "1 item"))
        val err = Replayer(d, cfg = fast).run(flow(), emptyMap()).steps.single().error!!
        assertTrue(err, err.contains("cart already has 1 item") && err.endsWith("clear it first?"))
        assertTrue(d.tapped.isEmpty())
    }

    @Test fun replaceCartDialogIsQuotedAndNeverTapped() = runBlocking {
        val d = Screen(listOf("Replace cart item?", "Your cart has items from Domino's. Replace cart item?", "No", "Replace"))
        val picker = ElementPicker { _, _, _ -> 3 } // the LLM "picks" Replace: must be refused
        val err = Replayer(d, picker, cfg = fast).run(flow(), emptyMap()).steps.single().error!!
        assertTrue(err, err.contains("replace what is already in your cart"))
        assertTrue(d.tapped.isEmpty())
    }

    @Test fun noCartMeansTheOrdinaryQuestion() = runBlocking {
        val err = Replayer(Screen(listOf("Home")), cfg = fast).run(flow(), emptyMap()).steps.single().error!!
        assertTrue(err, err.startsWith("I can't find") && !err.contains("cart"))
    }
}

/** T7: the requested dish is already in the cart (its row shows a stepper), so ADD exists only on a look-alike dish. */
class AlreadyInCartTest {
    private fun n(i: Int, d: Int, text: String, click: Boolean = false, desc: String = "") = UiNode(i, d, "com.application.zomato", "", text, desc, "", "", click, false, false, false)
    private val screen = listOf(
        n(0, 0, ""), n(1, 1, ""), n(2, 2, "Margherita Pizza"), n(3, 2, "−", true), n(4, 2, "", true, desc = "1"), n(5, 2, "+", true), // count in contentDesc, as on Zomato's menu
        n(6, 1, ""), n(7, 2, "Double Cheese Margherita Pizza"), n(8, 2, "ADD", true),
        n(9, 1, ""), n(10, 2, "Farmhouse Pizza"), n(11, 2, "ADD", true), // a real list has several ADD rows, which bound each row
    )
    private class D(val nodes: List<UiNode>) : Device {
        val tapped = mutableListOf<String>()
        override fun tree() = LiveTree(nodes, emptyList())
        override fun launch(packageName: String) = true
        override fun tap(tree: LiveTree, node: UiNode) = true.also { tapped += node.text }
        override fun type(tree: LiveTree, node: UiNode, value: String) = true
        override fun scroll(tree: LiveTree) = false
        override fun back() = true
    }

    @Test fun neverAddsTheLookAlikeDishAndAsksInstead() = runBlocking {
        val d = D(screen)
        val step = FlowStep(StepType.TAP, Selector(text = "ADD", near = "{{item}}"), slotRef = "item")
        val flow = Flow("order", "order", "com.application.zomato", listOf(step))
        val r = Replayer(d, cfg = ReplayConfig(waitMs = 30, retryMs = 30, pollMs = 10, settleMs = 30)).run(flow, mapOf("item" to "Margherita Pizza"))
        val err = r.steps.single().error!!
        assertFalse(r.success)
        assertTrue(err, err.contains("already in your cart, 1 of it") && err.endsWith("leave it as it is?"))
        assertTrue(d.tapped.isEmpty())
    }
}

/** Guesses and endings that used to "succeed" against the wrong screen. */
class HonestRunTest {
    private class Screen(val texts: List<String>) : Device {
        val tapped = mutableListOf<String>()
        override fun tree() = LiveTree(texts.mapIndexed { i, s -> UiNode(i, 1, "com.application.zomato", "", s, "", "", "", true, false, false, false) }, emptyList())
        override fun launch(packageName: String) = true
        override fun tap(tree: LiveTree, node: UiNode) = true.also { tapped += node.text }
        override fun type(tree: LiveTree, node: UiNode, value: String) = true
        override fun scroll(tree: LiveTree) = false
        override fun back() = true
    }
    private val fast = ReplayConfig(waitMs = 30, retryMs = 30, pollMs = 10, settleMs = 30)
    private fun flow(vararg s: FlowStep) = Flow("order", "order", "com.application.zomato", s.toList())

    @Test fun anUnrelatedLlmPickIsRefusedButAPopupCloseIsAllowed() = runBlocking {
        val step = FlowStep(StepType.TAP, Selector(text = "Add item ₹114"))
        val unrelated = Screen(listOf("Anindita (Ah1) & 1 more", "Spicy Chicken Poppers"))
        val r = Replayer(unrelated, ElementPicker { _, _, _ -> 0 }, cfg = fast).run(flow(step), emptyMap())
        assertFalse(r.success); assertTrue(unrelated.tapped.isEmpty())
        // A popup close no longer needs the LLM at all: with NO picker the pre-step still closes it (priority 1, item 5).
        val popup = Screen(listOf("Got it", "Spicy Chicken Poppers"))
        Replayer(popup, null, cfg = fast).run(flow(step), emptyMap())
        assertEquals(listOf("Got it"), popup.tapped)
    }

    @Test fun aPopupThatOnlyAdvancesTheFlowIsNeverTappedBlindly() = runBlocking {
        // "Continue" moves on (on Zomato's menu it opens the cart); "OK" confirms as often as it informs. Neither is a
        // dismissal, so neither is tapped as a pre-step: the run asks instead.
        val step = FlowStep(StepType.TAP, Selector(text = "Add item ₹114"))
        for (advance in listOf("Continue", "Proceed", "OK")) {
            val d = Screen(listOf(advance, "Spicy Chicken Poppers"))
            val r = Replayer(d, null, cfg = fast).run(flow(step), emptyMap())
            assertFalse(advance, r.success)
            assertTrue("$advance must not be tapped: ${d.tapped}", d.tapped.isEmpty())
        }
    }

    @Test fun anOrderingFlowNotEndingOnTheCartIsNotAnOrderReadyForReview() = runBlocking {
        val qty = FlowStep(StepType.SET_QUANTITY, Selector(near = "x"), slotRef = "quantity")
        val d = Screen(listOf("Dishes loved by friends", "Anindita (Ah1)"))
        val r = Replayer(d, cfg = fast).run(flow(qty), mapOf("quantity" to ""))
        assertFalse(r.success)
        assertTrue(r.steps.last().error!!, r.steps.last().error!!.startsWith("I did every step, but I'm not on the cart"))
    }
}
