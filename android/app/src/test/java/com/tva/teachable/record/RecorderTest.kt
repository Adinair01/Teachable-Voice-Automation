package com.tva.teachable.record

import com.tva.teachable.core.StepType
import com.tva.teachable.core.UiNode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a demonstration records (audit rows N and H). Taps are fed as the TouchCatcher feeds them: the screen as read
 * before the tap, and the point the finger came down on.
 */
class RecorderTest {
    private val z = "com.application.zomato"

    /** One full-width clickable control per caption, stacked 100 px apart, so the tap at y = 100 * i + 50 hits caption i. */
    private fun screen(vararg captions: String, pkg: String = z) = listOf(UiNode(0, 0, pkg, "", "", "", "", "0 0 1000 2000", false, false, false, false)) +
        captions.mapIndexed { i, c -> UiNode(i + 1, 1, pkg, "$pkg:id/c$i", c, "", "android.widget.Button", "0 ${i * 100} 1000 ${i * 100 + 100}", true, false, false, false) }

    private fun Recorder.tap(screen: List<UiNode>, index: Int) = onUserTap(screen, 500, index * 100 + 50)
    private fun recorder() = Recorder("com.tva.teachable").apply { start(); touchMode = true }
    private fun taps(r: Recorder) = r.stop().filter { it.type == StepType.TAP }.map { it.selector.text }

    @Test fun `N - prices, item counts and rotating placeholders are never stored in a selector`() {
        // Before: the flow held "Add item ₹279", "1 item added" and 'Search "binge night"', each tied to the demo.
        val r = recorder()
        val s = screen("Search \"binge night\"", "Add item ₹279", "1 item added", "Big Big 6in1 Pizza")
        for (i in 0..3) r.tap(s, i)
        assertEquals(listOf("Search", "Add item", "item added", "Big Big 6in1 Pizza"), taps(r))
    }

    @Test fun `N - coupon, offer and popup-closing taps are interruptions, not steps`() {
        // Before: "APPLY" (a coupon) and "Woohoo! Thanks" were steps 11 and 12 of the saved Zomato flow.
        val r = recorder()
        val s = screen("APPLY", "Woohoo! Thanks", "Offers for you", "Got it", "Continue")
        for (i in 0..4) r.tap(s, i)
        assertEquals(listOf("Continue"), taps(r))
    }

    @Test fun `H - Back straight after a tap removes that tap, and only that one`() {
        val r = recorder()
        val s = screen("Farmhouse Pizza", "Margherita Pizza", "Continue")
        r.tap(s, 0)
        r.tap(s, 1)       // the accidental one
        r.onBack()
        r.onBack()        // a second Back is not a second undo
        r.tap(s, 2)
        assertEquals(listOf("Farmhouse Pizza", "Continue"), taps(r))
    }

    @Test fun `H - Back after typing only closes the keyboard and removes nothing`() {
        val r = recorder()
        r.tap(screen("Search"), 0)
        r.onSwipeUp()     // anything recorded after the tap means Back is no longer about that tap
        r.onBack()
        assertEquals(listOf("Search"), taps(r))
    }

    @Test fun `bonus - taps on an incoming-call screen are not part of the demonstration`() {
        val r = recorder()
        r.tap(screen("Domino's Pizza"), 0)
        r.tap(screen("Decline", "Answer", pkg = "com.android.incallui"), 1)
        assertEquals(listOf("Domino's Pizza"), taps(r))
    }
}
