package com.tva.teachable.replay

import com.tva.teachable.a11y.LiveTree
import com.tva.teachable.core.BoundaryKind
import com.tva.teachable.core.CorrectionKind
import com.tva.teachable.core.CorrectionOp
import com.tva.teachable.core.Flow
import com.tva.teachable.core.FlowStep
import com.tva.teachable.core.Selector
import com.tva.teachable.core.StepType
import com.tva.teachable.core.StuckCause
import com.tva.teachable.core.UiNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "When a step cannot proceed: ask, act on the answer in the same run, and say what it means for the flow."
 *
 * The headline scenario ([theStuckAfterAddRun]) replays the real 21:55 run: the pizza was added, the screen is the menu
 * with a "1 item added / Continue" bar, and the taught flow's next steps name controls from the demonstration that are
 * simply not there. The same flow is run with and without a way to answer, so before and after are measured by one test.
 */
class ReplayerAskTest {
    private data class N(
        val text: String = "", val desc: String = "", val click: Boolean = true, val edit: Boolean = false,
        val id: String = "", val depth: Int = 1,
    )

    /** A phone with named screens: tapping a caption on a screen can move it to another, and typing can too. */
    private class Phone(
        var screen: String,
        private val screens: Map<String, List<N>>,
        private val goes: Map<Pair<String, String>, String> = emptyMap(),
        private val typingGoesTo: String? = null,
        private val scrollsTo: Map<String, String> = emptyMap(),
    ) : Device {
        val tapped = mutableListOf<String>()
        val typed = mutableListOf<String>()
        override fun tree(): LiveTree {
            val out = ArrayList<UiNode>()
            out += UiNode(0, 0, PKG, "", "", "", "", "", false, false, false, false)
            for (n in screens.getValue(screen)) {
                out += UiNode(out.size, n.depth, PKG, n.id, n.text, n.desc, if (n.edit) "android.widget.EditText" else "", "", n.click, n.edit, false, false)
            }
            return LiveTree(out, emptyList())
        }
        override fun launch(packageName: String) = true
        override fun tap(tree: LiveTree, node: UiNode): Boolean {
            val cap = node.text.ifEmpty { node.contentDesc }
            tapped += cap
            goes[screen to cap]?.let { screen = it }
            return true
        }
        override fun type(tree: LiveTree, node: UiNode, value: String): Boolean {
            typed += value
            typingGoesTo?.let { screen = it }
            return true
        }
        override fun scroll(tree: LiveTree): Boolean = scrollsTo[screen]?.let { screen = it; true } ?: false
        override fun back() = true
    }

    private class Scripted(private vararg val replies: String?) : StuckAsker {
        val asked = mutableListOf<Stuck>()
        override suspend fun ask(q: Stuck): String? { asked += q; return replies.getOrNull(asked.size - 1) }
    }

    private val fast = ReplayConfig(waitMs = 30, retryMs = 30, pollMs = 10, settleMs = 30)
    private fun flow(vararg s: FlowStep) = Flow("order_food", "order food", PKG, s.toList())
    private fun run(phone: Phone, asker: StuckAsker?, flow: Flow, slots: Map<String, String> = emptyMap()) =
        runBlocking { Replayer(phone, null, cfg = fast, asker = asker).run(flow, slots) }

    // ---------- the stuck-after-Add case ----------

    /** Steps 9-10 of the stored flow (a social-proof card and a "Got it" from the demo), then the implied quantity step. */
    private val staleTail = flow(
        FlowStep(StepType.TAP, Selector(text = "Ordered by 90+ customers")),
        FlowStep(StepType.TAP, Selector(text = "Got it")),
        FlowStep(StepType.SET_QUANTITY, Selector(near = "{{item}}"), slotRef = "quantity"),
    )

    private fun afterAdd() = Phone(
        "menu",
        mapOf(
            "menu" to listOf(N(text = "Spicy Chicken Poppers"), N(desc = "1 item added", click = false), N(desc = "Continue")),
            "cart" to listOf(N(text = "Bill details", click = false), N(text = "Item total", click = false), N(text = "Grand Total", click = false)),
        ),
        goes = mapOf(("menu" to "Continue") to "cart"),
    )

    @Test fun theStuckAfterAddRunBefore() {
        // BEFORE: no way to answer. It reported where it stopped and the run was over, with the cart one tap away.
        val phone = afterAdd()
        val r = run(phone, null, staleTail)
        assertFalse(r.success)
        assertEquals(1, r.steps.size)
        assertTrue(r.steps.last().error.orEmpty(), r.steps.last().error.orEmpty().startsWith("I can't find \"Ordered by 90+ customers\""))
        assertTrue(phone.tapped.isEmpty())
        assertEquals(0, r.questions)
        assertTrue(r.corrections.isEmpty())
    }

    @Test fun theStuckAfterAddRunAfter() {
        // AFTER: it asks, "tap continue" is acted on in this same run, "skip it" clears the second stale step, and the
        // run reaches the cart and hands over — with both corrections reported, none applied.
        val phone = afterAdd()
        val asker = Scripted("tap continue", "skip it")
        val r = run(phone, asker, staleTail, mapOf("item" to "Margherita pizza"))
        assertTrue(r.steps.toString(), r.success)
        assertEquals(BoundaryKind.PAYMENT, r.handover?.kind)
        assertEquals(listOf("Continue"), phone.tapped)
        assertEquals(2, r.questions)

        // The first question says where the run got to and what is on the screen, and offers the ways to answer.
        val q1 = asker.asked[0].spoken
        assertTrue(q1, q1.contains("I've got through 0 of 3 steps"))
        assertTrue(q1, q1.contains("I can't find \"Ordered by 90+ customers\""))
        assertTrue(q1, q1.contains("1 item added") && q1.contains("Continue"))
        assertTrue(q1, q1.contains("say skip") && q1.contains("say stop"))
        assertEquals(StuckCause.MISSING_TARGET, asker.asked[0].cause)

        val c = r.corrections
        assertEquals(2, c.size)
        assertEquals(CorrectionKind.FLOW_DEFECT, c[0].kind)
        assertEquals(CorrectionOp.REPLACE_SELECTOR, c[0].op)
        assertEquals(0, c[0].stepIndex)
        assertEquals("Continue", c[0].selector?.contentDesc)
        assertEquals("a learned selector prefers its caption to a layout-specific id", "", c[0].selector?.resourceId)
        assertEquals(CorrectionOp.MARK_OPTIONAL, c[1].op)
        assertEquals(1, c[1].stepIndex)
    }

    // ---------- never a dead end, never a guess ----------

    @Test fun silenceStopsTheRunAndTapsNothing() {
        val phone = afterAdd()
        val r = run(phone, Scripted(null), staleTail)
        assertFalse(r.success)
        assertEquals("I didn't hear back, so I stopped", r.steps.last().error)
        assertTrue(phone.tapped.isEmpty())
    }

    @Test fun stopMeansStop() {
        val phone = afterAdd()
        val r = run(phone, Scripted("never mind"), staleTail)
        assertFalse(r.success)
        assertEquals("you asked me to stop", r.steps.last().error)
        assertTrue(phone.tapped.isEmpty())
        assertTrue("stopping is not a correction", r.corrections.isEmpty())
    }

    @Test fun aRunNeverAsksMoreThanItsCeilingAndNeverLoops() {
        val phone = afterAdd()
        val asker = Scripted("xyzzy", "xyzzy", "xyzzy", "xyzzy", "xyzzy", "xyzzy")
        val r = run(phone, asker, staleTail)
        assertFalse(r.success)
        assertEquals(4, asker.asked.size)
        assertTrue(r.steps.last().error.orEmpty(), r.steps.last().error.orEmpty().contains("asked as much as I'm going to"))
        assertTrue(phone.tapped.isEmpty())
        // Each retry names what went wrong with the last answer instead of repeating the question blindly.
        assertTrue(asker.asked[1].spoken, asker.asked[1].spoken.contains("I can't see \"xyzzy\" on this screen"))
    }

    @Test fun aControlTheUserNamesBelowTheFoldIsScrolledToThenTapped() {
        // Found on the phone: "tap Apps" was refused because Apps sits below the fold of Settings. Scrolling only
        // reveals; the control is still found by its caption and still goes through the same guards.
        val phone = Phone(
            "top",
            mapOf(
                "top" to listOf(N(text = "Wi-Fi"), N(text = "Bluetooth")),
                "lower" to listOf(N(text = "Battery"), N(text = "Apps")),
                "apps" to listOf(N(text = "App management")),
            ),
            goes = mapOf(("lower" to "Apps") to "apps"), scrollsTo = mapOf("top" to "lower"),
        )
        val r = run(phone, Scripted("tap apps"), flow(FlowStep(StepType.TAP, Selector(text = "Legacy promo banner"))))
        assertTrue(r.steps.toString(), r.success)
        assertEquals(listOf("Apps"), phone.tapped)
    }

    @Test fun aLearnedPlainTapBelowTheFoldIsReachedWithoutAskingAgain() {
        // Found live: the flow learned "tap Apps" from an answer, then asked about it on the next run because a plain
        // tap never scrolled for its target. The learned step must reach its control the way the answer did.
        val phone = Phone(
            "top",
            mapOf("top" to listOf(N(text = "Wi-Fi")), "lower" to listOf(N(text = "Apps")), "apps" to listOf(N(text = "App management"))),
            goes = mapOf(("lower" to "Apps") to "apps"), scrollsTo = mapOf("top" to "lower"),
        )
        val asker = Scripted()
        val r = run(phone, asker, flow(FlowStep(StepType.TAP, Selector(text = "Apps"))))
        assertTrue(r.steps.toString(), r.success)
        assertEquals(listOf("Apps"), phone.tapped)
        assertEquals("no question: it found the control by itself", 0, asker.asked.size)
    }

    @Test fun aControlThatIsNowhereIsStillAskedAboutAndNothingIsTapped() {
        val phone = Phone("top", mapOf("top" to listOf(N(text = "Wi-Fi")), "lower" to listOf(N(text = "Battery"))), scrollsTo = mapOf("top" to "lower"))
        val asker = Scripted("tap apps", "stop")
        run(phone, asker, flow(FlowStep(StepType.TAP, Selector(text = "Legacy promo banner"))))
        assertTrue(phone.tapped.isEmpty())
        assertTrue(asker.asked[1].spoken.contains("I can't see \"apps\""))
    }

    @Test fun anAnswerNamingSomethingNotOnScreenIsAskedAgainNotGuessed() {
        val phone = afterAdd()
        val asker = Scripted("tap checkout now please", "stop")
        run(phone, asker, staleTail)
        assertTrue(phone.tapped.isEmpty())
        assertTrue(asker.asked[1].spoken.contains("I can't see"))
    }

    // ---------- safety is not weakened by being asked ----------

    @Test fun aTapThatWouldCommitPaymentIsHandedOverNotTapped() {
        val phone = Phone("s", mapOf("s" to listOf(N(text = "Offers"), N(text = "Place order"))))
        val r = run(phone, Scripted("tap place order"), flow(FlowStep(StepType.TAP, Selector(text = "Missing control"))))
        assertEquals(BoundaryKind.PAYMENT, r.handover?.kind)
        assertTrue("the user pays themselves: ${phone.tapped}", phone.tapped.isEmpty())
    }

    @Test fun aTapThatWouldThrowTheCartAwayIsRefusedEvenWhenTheUserAsks() {
        val phone = Phone("s", mapOf("s" to listOf(N(text = "Offers"), N(text = "Remove"))))
        val asker = Scripted("tap remove", "stop")
        run(phone, asker, flow(FlowStep(StepType.TAP, Selector(text = "Missing control"))))
        assertTrue(phone.tapped.isEmpty())
        assertTrue(asker.asked[1].spoken, asker.asked[1].spoken.contains("I won't tap \"Remove\" myself"))
    }

    @Test fun aCartReplacePromptIsNeverAnsweredByTappingReplace() {
        val phone = Phone("s", mapOf("s" to listOf(N(text = "Replace cart?", click = false), N(text = "Replace"), N(text = "No"))))
        val asker = Scripted("tap replace", "stop")
        val r = run(phone, asker, flow(FlowStep(StepType.TAP, Selector(text = "Missing control"))))
        assertFalse(r.success)
        assertTrue(phone.tapped.isEmpty())
        assertEquals(StuckCause.REPLACE_PROMPT, asker.asked[0].cause)
    }

    @Test fun aStepTheRequestDependsOnCannotBeSkipped() {
        val phone = Phone("s", mapOf("s" to listOf(N(text = "Veg Extravaganza"))))
        val asker = Scripted("skip it", "stop")
        val r = run(phone, asker, flow(FlowStep(StepType.TAP, Selector(text = "{{item}}"), slotRef = "item")), mapOf("item" to "Chicken Tikka Pizza"))
        assertFalse(r.success)
        assertTrue(asker.asked[1].spoken, asker.asked[1].spoken.contains("I can't skip that one"))
        assertTrue(r.corrections.isEmpty())
    }

    // ---------- situational answers are used once and never stored ----------

    @Test fun pickingAnotherDishForThisRunIsSituationalAndNotStored() {
        val phone = Phone("s", mapOf("s" to listOf(N(text = "Veg Extravaganza"), N(text = "Farmhouse Pizza"))))
        val r = run(phone, Scripted("tap veg extravaganza"), flow(FlowStep(StepType.TAP, Selector(text = "{{item}}"), slotRef = "item")), mapOf("item" to "Chicken Tikka Pizza"))
        assertTrue(r.steps.toString(), r.success)
        assertEquals(listOf("Veg Extravaganza"), phone.tapped)
        assertEquals(CorrectionKind.SITUATIONAL, r.corrections.single().kind)
        assertEquals(CorrectionOp.NONE, r.corrections.single().op)
    }

    @Test fun severalIdenticalControlsAreAskedAboutNotGuessed() {
        val phone = Phone("s", mapOf("s" to listOf(N(desc = "ADD", id = "z:id/add"), N(desc = "ADD", id = "z:id/add"))))
        val asker = Scripted("stop")
        run(phone, asker, flow(FlowStep(StepType.TAP, Selector(resourceId = "z:id/add", contentDesc = "ADD"))))
        assertTrue(phone.tapped.isEmpty())
        assertEquals(StuckCause.AMBIGUOUS, asker.asked[0].cause)
        assertTrue(asker.asked[0].spoken, asker.asked[0].spoken.contains("2 different controls"))
    }

    // ---------- typing and the cart ----------

    @Test fun aTapThatRevealsTheMissingFieldLetsTheTypingStepRunAsTaught() {
        val phone = Phone(
            "home", mapOf("home" to listOf(N(desc = "Search")), "field" to listOf(N(text = "hint", edit = true, id = "z:id/edittext")), "results" to listOf(N(text = "Results"))),
            goes = mapOf(("home" to "Search") to "field"), typingGoesTo = "results",
        )
        val step = FlowStep(StepType.TYPE, Selector(resourceId = "z:id/edittext"), value = "{{item}}", slotRef = "item")
        val r = run(phone, Scripted("tap search"), flow(step), mapOf("item" to "margherita"))
        assertTrue(r.steps.toString(), r.success)
        assertEquals(listOf("margherita"), phone.typed)
        assertEquals("opening the search field only cleared the way: nothing to store", CorrectionKind.SITUATIONAL, r.corrections.single().kind)
    }

    @Test fun aTypingStepIsNeverMarkedDoneJustBecauseSomethingWasTapped() {
        // The field is still missing after the tap, so the typing has NOT happened and the run must not say it did.
        val phone = Phone("home", mapOf("home" to listOf(N(desc = "Search"))))
        val r = run(phone, Scripted("tap search", "stop"), flow(FlowStep(StepType.TYPE, Selector(resourceId = "z:id/edittext"), value = "x")))
        assertFalse(r.success)
        assertTrue(phone.typed.isEmpty())
    }

    private fun alreadyInCart() = Phone(
        "menu",
        mapOf("menu" to listOf(
            N(depth = 1, click = true), N(text = "Margherita Pizza", depth = 2, click = false),
            N(text = "−", depth = 2), N(text = "1", depth = 2, click = false), N(text = "+", depth = 2),
        )),
    )
    private val addMargherita = FlowStep(StepType.TAP, Selector(contentDesc = "ADD", near = "{{item}}"), slotRef = "item")

    @Test fun anItemAlreadyInTheCartAsksAndAddsAnotherOnlyWhenTold() {
        val phone = alreadyInCart()
        val asker = Scripted("add another")
        val r = run(phone, asker, flow(addMargherita), mapOf("item" to "Margherita pizza"))
        assertTrue(r.steps.toString(), r.success)
        assertEquals(StuckCause.CART_CONFLICT, asker.asked[0].cause)
        assertTrue(asker.asked[0].spoken, asker.asked[0].spoken.contains("already in your cart, 1 of it"))
        assertEquals("exactly one tap, on the +", listOf("+"), phone.tapped)
    }

    @Test fun leavingTheCartAsItIsTapsNothing() {
        val phone = alreadyInCart()
        val r = run(phone, Scripted("leave it as it is"), flow(addMargherita), mapOf("item" to "Margherita pizza"))
        assertFalse(r.success)
        assertTrue(phone.tapped.isEmpty())
        assertTrue("a cart decision is about today, not the flow", r.corrections.isEmpty())
    }

    // ---------- priority 1, item 5: popups no longer need the LLM ----------

    @Test fun aPopupThatHidesTheControlIsClosedBeforeAnythingIsAsked() {
        val phone = Phone(
            "popup", mapOf("popup" to listOf(N(text = "Close")), "page" to listOf(N(text = "Add item"))),
            goes = mapOf(("popup" to "Close") to "page"),
        )
        val asker = Scripted()
        val r = run(phone, asker, flow(FlowStep(StepType.TAP, Selector(text = "Add item"))))
        assertTrue(r.steps.toString(), r.success)
        assertEquals(listOf("Close", "Add item"), phone.tapped)
        assertEquals("no question was needed", 0, asker.asked.size)
    }

    // ---------- optional steps ----------

    @Test fun anOptionalPlainTapIsSkippedQuietly() {
        val phone = afterAdd()
        val asker = Scripted()
        val r = run(phone, asker, flow(FlowStep(StepType.TAP, Selector(text = "Got it"), optional = true)))
        assertTrue(r.success)
        assertEquals(0, asker.asked.size)
        assertTrue(phone.tapped.isEmpty())
    }

    @Test fun optionalNeverExcusesAMissingSlotTarget() {
        // The false-success lesson: a step the request depends on is never skipped quietly, flag or no flag.
        val phone = Phone("s", mapOf("s" to listOf(N(text = "Veg Extravaganza"))))
        val asker = Scripted("stop")
        val step = FlowStep(StepType.TAP, Selector(text = "{{item}}"), slotRef = "item", optional = true)
        val r = run(phone, asker, flow(step), mapOf("item" to "Chicken Tikka Pizza"))
        assertFalse(r.success)
        assertEquals(1, asker.asked.size)
    }

    // ---------- the flow ran out of steps short of the cart ----------

    @Test fun aFlowThatStopsShortOfTheCartAsksHowToGetThereAndRemembersTheMissingStep() {
        // The other half of the 21:55 finding: even with every step done, the demonstration never opened the cart.
        val phone = afterAdd()
        val qty = FlowStep(StepType.SET_QUANTITY, Selector(near = "{{item}}"), slotRef = "quantity")
        val asker = Scripted("tap continue")
        val r = run(phone, asker, flow(qty), mapOf("item" to "Margherita pizza"))
        assertTrue(r.steps.toString(), r.success)
        assertEquals(BoundaryKind.PAYMENT, r.handover?.kind)
        assertTrue(asker.asked[0].spoken, asker.asked[0].spoken.contains("I'm not on the cart or checkout screen"))
        val c = r.corrections.single()
        assertEquals(CorrectionOp.INSERT_TAP, c.op)
        assertEquals("inserted before the quantity step, not after it", 0, c.stepIndex)
        assertEquals("Continue", c.selector?.contentDesc)
    }

    @Test fun aFlowThatStopsShortOfTheCartWithNoAnswerStillSaysSo() {
        val phone = afterAdd()
        val qty = FlowStep(StepType.SET_QUANTITY, Selector(near = "{{item}}"), slotRef = "quantity")
        val r = run(phone, Scripted(null), flow(qty))
        assertFalse(r.success)
        assertNull(r.handover)
        assertTrue(r.steps.last().error.orEmpty(), r.steps.last().error.orEmpty().startsWith("I did every step, but I'm not on the cart"))
    }

    // ---------- typing that did not take ----------

    /**
     * A search box that fills in on the first entry but only RUNS the search on the second — what the live Zomato menu did
     * (a retype by hand moments later worked). Results (a row with an ADD) appear only after the field has been cleared
     * and filled again.
     */
    private class StubbornSearch : Device {
        var text = ""
        private var cleared = false
        private var results = false
        val typed = mutableListOf<String>()
        val addedTo = mutableListOf<String>()
        override fun tree(): LiveTree {
            val out = ArrayList<UiNode>()
            out += UiNode(0, 0, PKG, "", "", "", "", "", false, false, false, false)
            out += UiNode(1, 1, PKG, "$PKG:id/edittext", text, "", "android.widget.EditText", "", true, true, false, false)
            if (results) {
                out += UiNode(2, 1, PKG, "", "", "", "", "", true, false, false, false)
                out += UiNode(3, 2, PKG, "", "Margherita Pizza", "", "", "", false, false, false, false)
                out += UiNode(4, 2, PKG, "$PKG:id/add", "", "ADD", "", "", true, false, false, false)
            }
            return LiveTree(out, emptyList())
        }
        override fun launch(packageName: String) = true
        override fun tap(tree: LiveTree, node: UiNode) = true.also { if (node.contentDesc == "ADD") addedTo += "Margherita Pizza" }
        override fun type(tree: LiveTree, node: UiNode, value: String): Boolean {
            typed += value; text = value
            if (value.isEmpty()) cleared = true else if (cleared) results = true
            return true
        }
        override fun scroll(tree: LiveTree) = false
        override fun back() = true
    }

    private val typeThenAdd = flow(
        FlowStep(StepType.TYPE, Selector(resourceId = "$PKG:id/edittext"), value = "{{item}}", slotRef = "item"),
        FlowStep(StepType.TAP, Selector(contentDesc = "ADD", near = "{{item}}"), slotRef = "item"),
    )

    @Test fun typingThatDidNotTakeIsRetypedOnceAndTheRunContinues() {
        val phone = StubbornSearch()
        val asker = Scripted()
        val r = runBlocking { Replayer(phone, null, cfg = fast, asker = asker).run(typeThenAdd, mapOf("item" to "Margherita pizza")) }
        assertTrue(r.steps.toString(), r.success)
        assertEquals("entered, cleared, entered again - exactly once more", listOf("Margherita pizza", "", "Margherita pizza"), phone.typed)
        assertEquals(listOf("Margherita Pizza"), phone.addedTo)
        assertEquals("it fixed itself without bothering anyone", 0, asker.asked.size)
    }

    @Test fun theRetypeIsBoundedAndAFieldThatNeverFiltersStillEndsInAQuestion() {
        // Nothing the field does ever produces results: one retype, then the normal specific question. No loop.
        val phone = object : Device by StubbornSearch() {
            val inner = StubbornSearch()
            val typed = mutableListOf<String>()
            override fun tree() = inner.tree()
            override fun type(tree: LiveTree, node: UiNode, value: String): Boolean { typed += value; inner.text = value; return true }
        }
        val asker = Scripted("stop")
        val r = runBlocking { Replayer(phone, null, cfg = fast, asker = asker).run(typeThenAdd, mapOf("item" to "Margherita pizza")) }
        assertFalse(r.success)
        assertEquals(1, asker.asked.size)
        assertEquals(listOf("Margherita pizza", "", "Margherita pizza"), phone.typed)
    }

    // ---------- a click that does not land ----------

    /** One clickable "Domino's Pizza" suggestion whose first [failures] clicks are refused, like a node that went stale. */
    private class FlakyClick(private var failures: Int) : Device {
        var attempts = 0
        var clicked = false
        override fun tree(): LiveTree = LiveTree(
            listOf(
                UiNode(0, 0, PKG, "", "", "", "", "", false, false, false, false),
                UiNode(1, 1, PKG, "", "Domino's Pizza", "", "", "", true, false, false, false),
            ),
            emptyList(),
        )
        override fun launch(packageName: String) = true
        override fun tap(tree: LiveTree, node: UiNode): Boolean { attempts++; return if (failures > 0) { failures--; false } else true.also { clicked = true } }
        override fun type(tree: LiveTree, node: UiNode, value: String) = true
        override fun scroll(tree: LiveTree) = false
        override fun back() = true
    }

    private val tapRestaurant = flow(FlowStep(StepType.TAP, Selector(text = "{{restaurant}} Pizza"), slotRef = "restaurant"))

    @Test fun aClickThatDoesNotLandIsRetriedOnceBeforeAnythingIsReported() {
        // Found live on Zomato: the suggestion was found, the click was refused (a list still refreshing), and the
        // run died with a bare "action failed" after six seconds.
        val phone = FlakyClick(failures = 1)
        val asker = Scripted()
        val r = runBlocking { Replayer(phone, null, cfg = fast, asker = asker).run(tapRestaurant, mapOf("restaurant" to "Domino's")) }
        assertTrue(r.steps.toString(), r.success)
        assertEquals(2, phone.attempts)
        assertEquals(0, asker.asked.size)
    }

    @Test fun aClickThatNeverLandsIsAQuestionNotABareFailure() {
        val phone = FlakyClick(failures = 99)
        val asker = Scripted("stop")
        val r = runBlocking { Replayer(phone, null, cfg = fast, asker = asker).run(tapRestaurant, mapOf("restaurant" to "Domino's")) }
        assertFalse(r.success)
        assertEquals("one retry, not a loop", 2, phone.attempts)
        val said = asker.asked.single().spoken
        assertTrue(said, said.contains("I found \"Domino's Pizza\" but couldn't tap it"))
        assertTrue(said, said.contains("What should I do?"))
        assertFalse("never the old generic message", r.steps.last().error.orEmpty().contains("action failed"))
    }

    // ---------- steps that are gestures, not actions ----------

    @Test fun aTaughtScrollWithNothingToScrollDoesNotEndTheRun() {
        // Seen live: Zomato's home feed had not loaded, scroll returned false, and the run ended with "action failed".
        val phone = Phone("home", mapOf("home" to listOf(N(text = "Search"))))
        val r = run(phone, Scripted(), flow(FlowStep(StepType.SCROLL), FlowStep(StepType.TAP, Selector(text = "Search"))))
        assertTrue(r.steps.toString(), r.success)
        assertEquals(listOf("Search"), phone.tapped)
    }

    @Test fun anAppThatNeverComesToTheFrontIsAQuestionNotABareFailure() {
        // Seen live: the phone had locked itself, the launch could not land, and the run said only "action failed".
        val locked = object : Device {
            override fun tree() = LiveTree(listOf(UiNode(0, 0, "com.android.systemui", "", "", "", "", "", false, false, false, false)), emptyList())
            override fun launch(packageName: String) = true
            override fun tap(tree: LiveTree, node: UiNode) = true
            override fun type(tree: LiveTree, node: UiNode, value: String) = true
            override fun scroll(tree: LiveTree) = false
            override fun back() = true
        }
        val asker = Scripted("stop")
        val r = runBlocking { Replayer(locked, null, cfg = fast, asker = asker).run(flow(FlowStep(StepType.LAUNCH, value = PKG)), emptyMap()) }
        assertFalse(r.success)
        assertEquals(StuckCause.WRONG_APP, asker.asked.single().cause)
        assertTrue(asker.asked.single().spoken, asker.asked.single().spoken.contains("never came to the front"))
    }

    private companion object { const val PKG = "com.application.zomato" }
}
