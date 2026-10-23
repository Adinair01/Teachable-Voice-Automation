package com.tva.teachable.replay

import com.tva.teachable.a11y.LiveTree
import com.tva.teachable.core.BoundaryKind
import com.tva.teachable.core.Flow
import com.tva.teachable.core.FlowStep
import com.tva.teachable.core.Selector
import com.tva.teachable.core.StepType
import com.tva.teachable.core.UiNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T11: the replayer stops on payment/credential screens even when no step was flagged sensitive while teaching. */
class ReplayerGuardTest {
    private class FakeDevice(var screen: List<String>) : Device {
        val tapped = mutableListOf<String>()
        override fun tree() = LiveTree(screen.mapIndexed { i, s -> UiNode(i, 1, "com.application.zomato", "", s, "", "", "", true, false, false, false) }, emptyList())
        override fun launch(packageName: String) = true
        override fun tap(tree: LiveTree, node: UiNode) = true.also { tapped += node.text }
        override fun type(tree: LiveTree, node: UiNode, value: String) = true
        override fun scroll(tree: LiveTree) = true
        override fun back() = true
    }

    private val fast = ReplayConfig(waitMs = 30, retryMs = 30, pollMs = 10, settleMs = 30)
    private val askedAnyway = ConfirmationGate { _, _ -> error("payment must hand over, not ask") }
    private fun tap(text: String) = FlowStep(StepType.TAP, Selector(text = text))
    private fun flow(vararg steps: FlowStep) = Flow("order_food", "order food", "com.application.zomato", steps.toList())

    @Test fun paymentScreenStopsAnUnflaggedStepWithoutTapping() = runBlocking {
        val d = FakeDevice(listOf("UPI", "Credit card", "Wallets", "Netbanking", "Continue"))
        val r = Replayer(d, gate = askedAnyway, cfg = fast).run(flow(tap("Continue")), emptyMap())
        assertEquals(BoundaryKind.PAYMENT, r.handover?.kind)
        assertTrue(d.tapped.isEmpty())
    }

    @Test fun reachingPlaceOrderIsASuccessfulHandover() = runBlocking {
        val d = FakeDevice(listOf("Margherita", "Place Order"))
        val r = Replayer(d, gate = askedAnyway, cfg = fast).run(flow(tap("Margherita"), tap("Place Order")), emptyMap())
        assertTrue(r.success)
        assertEquals(BoundaryKind.PAYMENT, r.handover?.kind)
        assertEquals(listOf("Margherita"), d.tapped)
    }

    @Test fun flowEndingOnTheCartHandsOverForPayment() = runBlocking {
        val d = FakeDevice(listOf("Margherita", "₹163", "Place Order"))
        val r = Replayer(d, gate = askedAnyway, cfg = fast).run(flow(tap("Margherita")), emptyMap())
        assertTrue(r.success)
        assertEquals(BoundaryKind.PAYMENT, r.handover?.kind)
    }

    @Test fun llmFallbackCannotPickAPayButton() = runBlocking {
        val d = FakeDevice(listOf("Pay ₹349", "Something else"))
        val picker = ElementPicker { _, _, _ -> 0 }
        val r = Replayer(d, picker, askedAnyway, fast).run(flow(tap("Checkout now please")), emptyMap())
        assertTrue(d.tapped.isEmpty())
        assertEquals(BoundaryKind.PAYMENT, r.handover?.kind)
    }

    @Test fun aCheckoutStepWhoseButtonIsNotOnScreenIsNotReportedAsReachingPayment() = runBlocking {
        // Amazon, live: the run was on a product page, and "Proceed to checkout" was handed over on its label alone.
        val d = FakeDevice(listOf("Samsung Galaxy S25 Ultra", "Add to cart", "Buy Now"))
        val r = Replayer(d, gate = askedAnyway, cfg = fast).run(flow(tap("Proceed to checkout")), emptyMap())
        assertTrue(d.tapped.isEmpty())
        assertNull(r.handover)
        assertFalse(r.success)
    }

    @Test fun aCartTabTaughtByItsBadgeCountIsFoundWithAnotherCount() = runBlocking {
        // Amazon, live: the Cart tab was "1" while teaching and "2" on the replay.
        val tab = "androidx.appcompat.app.ActionBar\$Tab"
        val d = object : Device by FakeDevice(emptyList()) {
            val taps = mutableListOf<String>()
            override fun tree() = LiveTree(listOf(
                UiNode(0, 0, "a", "", "", "", "", "", false, false, false, false),
                UiNode(1, 1, "a", "", "Home", "", tab, "", true, false, false, false),
                UiNode(2, 1, "a", "", "2", "", tab, "", true, false, false, false),
            ), emptyList())
            override fun tap(tree: LiveTree, node: UiNode): Boolean { taps += node.text; return true }
        }
        val step = tap("1").let { it.copy(selector = it.selector.copy(className = tab)) }
        Replayer(d, gate = askedAnyway, cfg = fast).run(flow(step), emptyMap())
        assertEquals(listOf("2"), d.taps)
    }

    @Test fun loggedOutAppHandsOverAsNotSuccess() = runBlocking {
        val d = object : Device by FakeDevice(emptyList()) {
            override fun tree() = LiveTree(listOf(
                UiNode(0, 1, "com.application.zomato", "", "Log in or sign up", "", "", "", false, false, false, false),
                UiNode(1, 1, "com.application.zomato", "", "Enter phone number", "", "", "", true, true, false, false),
            ), emptyList())
        }
        val r = Replayer(d, gate = askedAnyway, cfg = fast).run(flow(tap("Search")), emptyMap())
        assertFalse(r.success)
        assertEquals(BoundaryKind.LOGIN, r.handover?.kind)
    }
}
