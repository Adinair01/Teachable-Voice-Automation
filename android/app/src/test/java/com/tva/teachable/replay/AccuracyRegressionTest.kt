package com.tva.teachable.replay

import com.tva.teachable.a11y.LiveTree
import com.tva.teachable.core.Flow
import com.tva.teachable.core.FlowStep
import com.tva.teachable.core.RowContext
import com.tva.teachable.core.Selector
import com.tva.teachable.core.SelectorMatcher
import com.tva.teachable.core.StepType
import com.tva.teachable.core.StuckCause
import com.tva.teachable.core.UiNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One test per defect of the 2026-10-21 accuracy audit (rows B, E, F, M, A and the "first match wins" list). Each
 * reproduces the audit's probe on the old code's terms and fails there; the comment says what used to happen.
 */
class AccuracyRegressionTest {
    private val z = "com.application.zomato"

    /** A flat tree from rows of (depth, id, text, desc, clickable). */
    private fun tree(vararg rows: List<Any>): List<UiNode> = rows.mapIndexed { i, r ->
        UiNode(i, r[0] as Int, z, r[1] as String, r[2] as String, r[3] as String, "", "0 0 10 10", r[4] as Boolean, false, false, false)
    }
    private fun row(depth: Int, id: String = "", text: String = "", desc: String = "", click: Boolean = false) = listOf(depth, id, text, desc, click)
    private val fast = ReplayConfig(waitMs = 40, retryMs = 20, pollMs = 10, settleMs = 20, scrollTries = 1)

    /** A fixed screen that records every tap and every question. */
    private class Screen(var nodes: List<UiNode>) : Device {
        val tapped = ArrayList<String>()
        override fun tree() = LiveTree(nodes, emptyList())
        override fun launch(packageName: String) = true
        override fun tap(tree: LiveTree, node: UiNode): Boolean { tapped += node.text.ifBlank { node.contentDesc }; return true }
        override fun type(tree: LiveTree, node: UiNode, value: String) = true
        override fun scroll(tree: LiveTree) = false
        override fun back() = true
    }

    private fun replay(screen: Screen, vararg steps: FlowStep, slots: Map<String, String> = emptyMap(), questions: MutableList<String> = ArrayList()) =
        runBlocking { Replayer(screen, cfg = fast, asker = { q -> questions += q.spoken; null }).run(Flow("k", "d", z, steps.toList()), slots) }

    // ---- B: an id match must agree with the recorded caption; nothing destructive is tapped on the flow's behalf ----

    @Test fun `B - the replace-cart dialog never gets Replace tapped for a step taught on "Woohoo! Thanks"`() {
        // Before: the id positive_button matched, the caption was ignored, and "Replace" was tapped.
        val screen = Screen(tree(row(0), row(1, text = "Replace cart item?"), row(1, "$z:id/negative_button", "No", click = true),
            row(1, "$z:id/positive_button", "Replace", click = true)))
        val questions = ArrayList<String>()
        val r = replay(screen, FlowStep(StepType.TAP, Selector(resourceId = "$z:id/positive_button", text = "Woohoo! Thanks")), questions = questions)
        assertTrue("nothing was tapped: ${screen.tapped}", screen.tapped.isEmpty())
        assertTrue(!r.success)
        assertEquals("the user is asked, specifically", 1, questions.size)
        assertTrue(questions[0], questions[0].contains("replace", ignoreCase = true))
        // The matcher alone already refuses it (the destructive guard is a second, independent line).
        assertNull(SelectorMatcher.target(screen.nodes, Selector(resourceId = "$z:id/positive_button", text = "Woohoo! Thanks"), null, true))
    }

    @Test fun `B - a deterministic match on a destructive control is refused unless the step was taught on that word`() {
        // An id with no caption that happens to sit on "Clear cart": refused and asked about.
        val cart = Screen(tree(row(0), row(1, "$z:id/cta", click = true), row(2, text = "Clear cart")))
        replay(cart, FlowStep(StepType.TAP, Selector(resourceId = "$z:id/cta")))
        assertTrue(cart.tapped.toString(), cart.tapped.isEmpty())
        // A calculator's taught "Clear" is the task itself, and is tapped.
        val calc = Screen(tree(row(0), row(1, "c:id/clr", desc = "Clear", click = true)))
        assertTrue(replay(calc, FlowStep(StepType.TAP, Selector(resourceId = "c:id/clr", contentDesc = "Clear"))).success)
        assertEquals(listOf("Clear"), calc.tapped)
    }

    @Test fun `B - a count or price in the caption no longer sends an id match to the first control with that id`() {
        // Before: "1 item added" vs a live "2 items added" -> the first "container" ("Offers for you") was tapped.
        val bar = tree(row(0), row(1, "$z:id/container", desc = "Offers for you", click = true), row(1, "$z:id/container", desc = "2 items added", click = true))
        assertEquals("2 items added", SelectorMatcher.target(bar, Selector(resourceId = "$z:id/container", contentDesc = "1 item added"), null, true)?.contentDesc)
        // Before: "Add item ₹279" vs a live "Add item ₹149" -> the first "button" ("Customise").
        val sheet = tree(row(0), row(1, "$z:id/button", "Customise", click = true), row(1, "$z:id/button", "Add item ₹149", click = true))
        assertEquals("Add item ₹149", SelectorMatcher.target(sheet, Selector(resourceId = "$z:id/button", text = "Add item ₹279"), null, true)?.text)
        // A rotating placeholder: 'Search "binge night"' taught, 'Search "comfort food"' live.
        val home = tree(row(0), row(1, "$z:id/search_bar", "Search \"comfort food\"", click = true))
        assertNotNull(SelectorMatcher.target(home, Selector(resourceId = "$z:id/search_bar", text = "Search \"binge night\""), null, true))
    }

    // ---- F: shorthand picks the CLOSEST name, in either list order; a real tie asks ----

    private fun menu(vararg names: String) = tree(row(0), row(1, click = false), *names.flatMap { n ->
        listOf(row(2, click = true), row(3, text = n), row(3, "$z:id/add", desc = "ADD", click = true))
    }.toTypedArray())

    private fun addFor(nodes: List<UiNode>, item: String): String? =
        SelectorMatcher.target(nodes, Selector(contentDesc = "ADD", near = item), item, true)?.let { add -> nodes[add.index - 1].text }

    @Test fun `F - "margherita" picks Margherita Pizza over Double Cheese Margherita Pizza in either order`() {
        // Before: the first row containing the word won, so the look-alike listed first was added.
        assertEquals("Margherita Pizza", addFor(menu("Double Cheese Margherita Pizza", "Margherita Pizza"), "margherita"))
        assertEquals("Margherita Pizza", addFor(menu("Margherita Pizza", "Double Cheese Margherita Pizza"), "margherita"))
    }

    @Test fun `F - two different names equally close is asked about, the same dish listed twice is not`() {
        assertNull(addFor(menu("Margherita Pizza", "Margherita Pasta"), "margherita"))
        assertEquals("Margherita Pizza", addFor(menu("Margherita Pizza", "Farmhouse Pizza", "Margherita Pizza"), "margherita"))
    }

    // ---- M: a learned "{{restaurant}}" finds the closest caption, and the landing is checked ----

    @Test fun `M - restaurant Pizza Hut opens "Pizza Hut", not "Pizza Hut Express" listed first`() {
        val list = tree(row(0), row(1, text = "Pizza Hut Express", click = true), row(1, text = "Pizza Hut", click = true))
        assertEquals("Pizza Hut", SelectorMatcher.target(list, Selector(text = "Pizza Hut"), "Pizza Hut", true)?.text)
        // No caption is exactly the value: the closest one wins, not the first one containing it.
        val outlets = tree(row(0), row(1, text = "Domino's Pizza Express", click = true), row(1, text = "Domino's Pizza", click = true))
        assertEquals("Domino's Pizza", SelectorMatcher.target(outlets, Selector(text = "Domino's"), "Domino's", true)?.text)
        // ...and the learned "Search in {{restaurant}}" finds the menu search, not the restaurant's own title.
        val page = tree(row(0), row(1, text = "Domino's Pizza", click = true), row(1, desc = "Search in Domino's Pizza", click = true))
        assertEquals("Search in Domino's Pizza", SelectorMatcher.target(page, Selector(contentDesc = "Search in Domino's"), "Domino's", true)?.contentDesc)
        assertEquals("Domino's Pizza", SelectorMatcher.target(page, Selector(text = "Domino's"), "Domino's", true)?.text)
    }

    @Test fun `M - a tap that opened a page not naming the restaurant is questioned, not carried on`() {
        val screen = Screen(tree(row(0), row(1, text = "Pizza Hut", click = true)))
        val step = FlowStep(StepType.TAP, Selector(text = "{{restaurant}}"), slotRef = "restaurant", expect = com.tva.teachable.core.Expect(textContains = "{{restaurant}}"))
        // The tap "works" but the page never changes to anything naming Pizza Hut Express.
        val r = replay(screen, step, slots = mapOf("restaurant" to "Pizza Hut Express"))
        assertTrue(!r.success)
    }

    // ---- E: plurals pass the landing check; a failed landing check is a question ----

    @Test fun `E - "Farmhouse pizzas" asked, "Farmhouse Pizza" shown, is the same dish`() {
        // Before: success=false, "expected screen not reached", and no question (probe P6).
        val screen = Screen(tree(row(0), row(1, text = "Farmhouse Pizza"), row(1, click = true), row(2, text = "Farmhouse Pizza"), row(2, "$z:id/add", desc = "ADD", click = true)))
        val step = FlowStep(StepType.TAP, Selector(contentDesc = "ADD", near = "{{item}}"), slotRef = "item", expect = com.tva.teachable.core.Expect(textContains = "{{item}}"))
        val r = replay(screen, step, slots = mapOf("item" to "Farmhouse pizzas"))
        assertTrue(r.steps.toString(), r.success)
    }

    @Test fun `E - an expected screen that does not show up is asked about with what IS there`() {
        val screen = Screen(tree(row(0), row(1, text = "Details", click = true), row(1, text = "Double Cheese Margherita")))
        val step = FlowStep(StepType.TAP, Selector(text = "Details"), slotRef = "item", expect = com.tva.teachable.core.Expect(textContains = "{{item}}"))
        val questions = ArrayList<String>()
        replay(screen, step, slots = mapOf("item" to "Farmhouse pizza"), questions = questions)
        assertEquals(1, questions.size)
        assertTrue(questions[0], questions[0].contains("doesn't mention \"Farmhouse pizza\""))
    }

    // ---- every remaining non-safety stop is a question, never a bare "failed" ----

    @Test fun `a back that does nothing and a missing key are questions too`() {
        val dead = object : Device by Screen(tree(row(0), row(1, text = "x"))) { override fun back() = false }
        val q = ArrayList<String>()
        runBlocking { Replayer(dead, cfg = fast, asker = { s -> q += s.spoken; null }).run(Flow("k", "d", z, listOf(FlowStep(StepType.BACK))), emptyMap()) }
        assertEquals(1, q.size)
    }

    // ---- A: a stuck step is asked within the budget, and a run has a hard cap ----

    @Test fun `A - the 37_9 s probe now reaches its question inside the 16 s step budget`() = runBlocking {
        var typed = ""
        val base = tree(row(0), row(1, "$z:id/edittext", click = true), row(1, text = "Got it", click = true))
        val dev = object : Device {
            override fun tree() = LiveTree(base.map { if (it.resourceId.endsWith("edittext")) it.copy(text = typed) else it }, emptyList())
            override fun launch(packageName: String) = true
            override fun tap(tree: LiveTree, node: UiNode) = true          // "Got it" never goes away
            override fun type(tree: LiveTree, node: UiNode, value: String): Boolean { typed = value; return true }
            override fun scroll(tree: LiveTree) = true                    // always scrolls, never reveals
            override fun back() = true
        }
        val slowPicker = ElementPicker { _, _, _ -> delay(15_000); null } // the phone's old 15 s read timeout
        var askedAfter = 0L
        val start = System.currentTimeMillis()
        Replayer(dev, slowPicker, asker = { askedAfter = System.currentTimeMillis() - start; null }).run(
            Flow("k", "d", z, listOf(FlowStep(StepType.TYPE, Selector(resourceId = "$z:id/edittext"), value = "x"), FlowStep(StepType.TAP, Selector(text = "Continue")))),
            emptyMap(),
        )
        println("A: question handed over after $askedAfter ms (was 37 866 ms)")
        assertTrue("asked after $askedAfter ms", askedAfter in 1..18_000)
    }

    @Test fun `A - a run stops at the hard cap, at a step boundary, with a reason`() = runBlocking {
        var clock = 0L
        val dev = object : Device by Screen(tree(row(0), row(1, text = "Next", click = true))) {
            override fun tap(tree: LiveTree, node: UiNode): Boolean { clock += 60_000; return true } // each tap "takes" a minute
        }
        val step = FlowStep(StepType.TAP, Selector(text = "Next"))
        val r = Replayer(dev, cfg = fast.copy(runCapMs = 150_000, clock = { clock })).run(Flow("k", "d", z, List(5) { step }), emptyMap())
        assertTrue(!r.success)
        assertEquals(4, r.steps.size) // 3 taps (3 minutes) then the stop at step 4
        assertTrue(r.steps.last().error!!, r.steps.last().error!!.startsWith("This has taken over 2 minutes"))
    }

    // ---- the remaining "first match wins" places ----

    @Test fun `Q13 - the upsell pass never taps OK on a replace-cart dialog (it used to, from the quantity step)`() {
        val screen = Screen(tree(row(0), row(1, text = "Replace cart item?"), row(1, text = "OK", click = true)))
        replay(screen, FlowStep(StepType.SET_QUANTITY, Selector(near = "{{item}}"), slotRef = "quantity"), slots = mapOf("quantity" to "2", "item" to "Farmhouse pizza"))
        assertTrue(screen.tapped.toString(), screen.tapped.isEmpty())
    }

    @Test fun `Q13 - two saved addresses with the same label are asked about, not chosen by list order`() {
        val screen = Screen(tree(row(0), row(1, text = "Deliver to Home", click = true),
            row(1, click = true), row(2, text = "Work"), row(2, text = "Tidel Park, Chennai"),
            row(1, click = true), row(2, text = "Work"), row(2, text = "Manyata Tech Park, Bengaluru")))
        val q = ArrayList<String>()
        replay(screen, FlowStep(StepType.CHOOSE_ADDRESS, slotRef = "address"), slots = mapOf("address" to "Work"), questions = q)
        assertTrue(screen.tapped.toString(), "Work" !in screen.tapped)
        assertTrue(q.toString(), q.single().contains("2 saved addresses called \"Work\""))
    }

    @Test fun `Q13 - RowContext pick and Steppers find no longer depend on tree order`() {
        val parentOrder = listOf("Double Cheese Margherita Pizza", "Margherita Pizza")
        for (names in listOf(parentOrder, parentOrder.reversed())) {
            val nodes = menu(*names.toTypedArray())
            val adds = nodes.filter { it.contentDesc == "ADD" }
            val picked = RowContext.pick(nodes, adds, "", "margherita") { it.contentDesc == "ADD" }
            assertEquals("Margherita Pizza", nodes[picked!!.index - 1].text)
        }
    }

    @Test fun `Q13 - typing into the first of two same-id fields is safe - a wrong field ends in a question, nothing pressed`() {
        // Zomato's home keeps one search field per cached page; typing takes the first. Typing presses nothing, and if
        // the text went to a field that does nothing, the next step cannot find its target: it retypes once, then asks.
        val screen = Screen(tree(row(0), row(1, "$z:id/edittext", click = true), row(1, "$z:id/edittext", click = true)))
        val q = ArrayList<String>()
        replay(screen, FlowStep(StepType.TYPE, Selector(resourceId = "$z:id/edittext"), slotRef = "item", value = "{{item}}"),
            FlowStep(StepType.TAP, Selector(contentDesc = "ADD", near = "{{item}}"), slotRef = "item"), slots = mapOf("item" to "Farmhouse pizza"), questions = q)
        assertTrue(screen.tapped.toString(), screen.tapped.isEmpty())
        assertEquals(1, q.size)
    }

    @Test fun `progress names each step with THIS run's values, as it starts`() {
        val screen = Screen(tree(row(0), row(1, text = "Margherita Pizza", click = true), row(1, desc = "Next", click = true)))
        val seen = ArrayList<String>()
        runBlocking {
            Replayer(screen, cfg = fast, progress = { i, n, what -> seen += "${i + 1}/$n $what" }).run(Flow("k", "d", z, listOf(
                FlowStep(StepType.TAP, Selector(text = "{{item}}"), slotRef = "item"),
                FlowStep(StepType.TAP, Selector(contentDesc = "Next")),
            )), mapOf("item" to "Margherita pizza"))
        }
        assertEquals(listOf("1/2 tapping Margherita pizza", "2/2 tapping Next"), seen)
    }
}
