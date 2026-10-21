package com.tva.teachable.replay

import com.tva.teachable.a11y.LiveTree
import com.tva.teachable.core.BoundaryKind
import com.tva.teachable.core.Flow
import com.tva.teachable.core.FlowStep
import com.tva.teachable.core.ScreenGuard
import com.tva.teachable.core.Selector
import com.tva.teachable.core.StepType
import com.tva.teachable.core.UiNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Option B (Aditya, 2026-10-21): a replay may tap EXACTLY "Proceed to pay" or "Select payment method" to reach the
 * payment page, and the moment that page is on screen nothing more is touched — the run says "your turn".
 */
class PaymentEntryTest {
    private val z = "com.application.zomato"
    private fun nodes(vararg captions: Pair<String, Boolean>) = listOf(UiNode(0, 0, z, "", "", "", "", "", false, false, false, false)) +
        captions.mapIndexed { i, (c, click) -> UiNode(i + 1, 1, z, "", c, "", "", "", click, false, false, false) }

    private val cart = nodes("Bill details" to false, "Item total ₹279" to false, "Continue" to true, "Proceed to pay" to true)
    private val payment = nodes("Payment options" to false, "UPI" to true, "Credit card" to true, "Net banking" to true, "Continue" to true)

    /** The cart until "Proceed to pay" is tapped (or [jumpAfterReads] tree reads pass), then the payment page. FAILS on any touch there. */
    private inner class Shop(private val jumpAfterReads: Int = Int.MAX_VALUE) : Device {
        val taps = ArrayList<String>()
        var paying = false
        private var reads = 0
        override fun tree(): LiveTree { if (++reads > jumpAfterReads) paying = true; return LiveTree(if (paying) payment else cart, emptyList()) }
        override fun tap(tree: LiveTree, node: UiNode): Boolean {
            if (paying) throw AssertionError("a tap was attempted on the payment screen: ${node.text}")
            taps += node.text
            if (node.text == "Proceed to pay") paying = true
            return true
        }
        override fun type(tree: LiveTree, node: UiNode, value: String): Boolean = throw AssertionError("typed on ${if (paying) "the payment screen" else "the cart"}")
        override fun launch(packageName: String) = true
        override fun scroll(tree: LiveTree) = false
        override fun back() = true
    }

    private val fast = ReplayConfig(waitMs = 40, retryMs = 20, pollMs = 10, settleMs = 20, scrollTries = 1)
    private fun tap(caption: String, sensitive: Boolean = false) = FlowStep(StepType.TAP, Selector(text = caption), sensitive = sensitive)
    private fun run(device: Shop, vararg steps: FlowStep) = runBlocking { Replayer(device, cfg = fast).run(Flow("k", "d", z, steps.toList()), emptyMap()) }

    @Test fun `only an EXACT "Proceed to pay" or "Select payment method" may be tapped`() {
        listOf("Proceed to pay", "Proceed to Pay ›", "Select payment method").forEach { assertTrue(it, ScreenGuard.isPaymentEntry(it)) }
        listOf("Proceed to pay ₹279", "Pay ₹279", "Place order", "Proceed to checkout", "Pay using UPI").forEach { assertFalse(it, ScreenGuard.isPaymentEntry(it)) }
        // A container carrying the entry AND a committing caption is not an entry.
        val mixed = listOf(UiNode(0, 0, z, "", "", "", "", "", true, false, false, false),
            UiNode(1, 1, z, "", "Proceed to pay", "", "", "", false, false, false, false), UiNode(2, 1, z, "", "Pay ₹279", "", "", "", false, false, false, false))
        assertFalse(ScreenGuard.entersPayment(mixed, mixed[0]))
        val total = listOf(mixed[0], mixed[1], UiNode(2, 1, z, "", "₹279 TOTAL", "", "", "", false, false, false, false))
        assertTrue(ScreenGuard.entersPayment(total, total[0]))
    }

    @Test fun `the replay taps Proceed to pay unattended, then touches NOTHING on the payment page and hands over`() {
        val shop = Shop()
        // The step is flagged sensitive (as the backend does); the default gate denies everything, yet it is not asked.
        val r = run(shop, tap("Continue"), tap("Proceed to pay", sensitive = true), tap("UPI"), tap("Continue"))
        assertEquals(listOf("Continue", "Proceed to pay"), shop.taps)
        assertTrue(r.success)
        assertEquals(BoundaryKind.PAYMENT, r.handover?.kind)
    }

    @Test fun `a payment screen that appears at ANY moment of a run is never touched`() {
        // The app may jump to payment by itself at any point: after the step's own screen check and before its tap
        // (jump after read 1 or 6 here), the step finds the payment page's own "Continue" and, before the per-tap
        // guard, TAPPED it. Every timing is tried; the fake fails the test on any touch of the payment page.
        for (n in 1..8) {
            val shop = Shop(jumpAfterReads = n)
            val r = run(shop, tap("Continue"), tap("Continue"))
            assertEquals("jump after read $n", BoundaryKind.PAYMENT, r.handover?.kind)
        }
    }

    @Test fun `Pay and Place order are still never tapped`() {
        for (caption in listOf("Pay ₹279", "Place order")) {
            val shop = Shop()
            val r = run(shop, tap(caption, sensitive = true))
            assertTrue(caption, shop.taps.isEmpty())
            assertEquals(BoundaryKind.PAYMENT, r.handover?.kind)
        }
    }
}
