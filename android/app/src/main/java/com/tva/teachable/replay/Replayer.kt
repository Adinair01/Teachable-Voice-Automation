package com.tva.teachable.replay

import com.tva.teachable.a11y.LiveTree
import com.tva.teachable.core.Boundary
import com.tva.teachable.core.BoundaryKind
import com.tva.teachable.core.Flow
import com.tva.teachable.core.FlowStep
import com.tva.teachable.core.ScreenGuard
import com.tva.teachable.core.ScreenSummary
import com.tva.teachable.core.Selector
import com.tva.teachable.core.SelectorMatcher
import com.tva.teachable.core.StepType
import com.tva.teachable.core.Steppers
import com.tva.teachable.core.UiNode
import com.tva.teachable.core.resolveSlots
import kotlinx.coroutines.delay

/** What the replayer needs from the phone. Implemented by the AccessibilityService; faked in tests. */
interface Device {
    fun tree(): LiveTree
    fun launch(packageName: String): Boolean
    fun tap(tree: LiveTree, node: UiNode): Boolean
    fun type(tree: LiveTree, node: UiNode, value: String): Boolean
    fun scroll(tree: LiveTree): Boolean
    fun back(): Boolean
}

/** Asked before any sensitive step. Default denies: nothing sensitive ever runs without explicit consent. */
fun interface ConfirmationGate { suspend fun confirm(step: FlowStep, slots: Map<String, String>): Boolean }
val DenyAll = ConfirmationGate { _, _ -> false }

data class ReplayConfig(val waitMs: Long = 6000, val retryMs: Long = 3000, val pollMs: Long = 300, val scrollTries: Int = 3, val settleMs: Long = 3000)
data class StepResult(
    val index: Int, val ok: Boolean, val usedLlmFallback: Boolean = false, val error: String? = null,
    val declined: Boolean = false, val handover: Boundary? = null,
)
/**
 * [declined] = the user said no to a sensitive step. That is the safety feature working, not a failed replay.
 * [handover] = the run hit a payment/OTP/login/password boundary and gave the phone back. Reaching payment is the
 * end of every ordering flow, so a PAYMENT handover with all earlier steps done is a success.
 */
data class RunResult(val success: Boolean, val steps: List<StepResult>, val declined: Boolean = false, val handover: Boundary? = null)

class Replayer(
    private val device: Device,
    private val picker: ElementPicker? = null,
    private val gate: ConfirmationGate = DenyAll,
    private val cfg: ReplayConfig = ReplayConfig(),
) {
    private var appPackage = ""
    private var llmPicks = 0

    suspend fun run(flow: Flow, slots: Map<String, String>): RunResult {
        appPackage = flow.appPackage; llmPicks = 0
        val results = ArrayList<StepResult>()
        for ((i, step) in flow.steps.withIndex()) {
            val r = runStep(i, step, flow.steps.getOrNull(i + 1), slots)
            results += r
            r.handover?.let { return RunResult(it.kind == BoundaryKind.PAYMENT, results, handover = it) }
            if (!r.ok) return RunResult(false, results, r.declined)
        }
        val end = awaitStable().nodes
        // An ordering flow ends where the user pays; the cart may still be loading after the last tap, so wait for it.
        val ordering = flow.steps.any { it.type == StepType.SET_QUANTITY || it.type == StepType.CHOOSE_ADDRESS }
        // A popup (a coupon offer) can hide the pay bar; the flow still ended where it was taught to stop, before paying.
        val handover = ScreenGuard.screen(end) ?: ScreenGuard.readyToPay(end)
            ?: if (ordering) poll(cfg.waitMs) { ScreenGuard.readyToPay(it.nodes) } ?: Boundary(BoundaryKind.PAYMENT, "order ready for review")
            else null
        return RunResult(true, results, handover = handover)
    }

    private suspend fun runStep(i: Int, step: FlowStep, next: FlowStep?, slots: Map<String, String>): StepResult {
        // Checked on the live screen before every step, whatever was flagged while teaching: nothing but launching
        // the app (which touches no screen) is allowed once a credential or payment screen is up.
        if (step.type != StepType.LAUNCH && step.type != StepType.WAIT) {
            ScreenGuard.screen(device.tree().nodes)?.let { return handover(i, it) }
        }
        if (step.type == StepType.TAP) ScreenGuard.actionText("${step.selector.text} ${step.selector.contentDesc}")?.let { return handover(i, it) }
        if (step.sensitive && !gate.confirm(step, slots)) return StepResult(i, false, error = "sensitive step not confirmed", declined = true)
        val value = resolveSlots(step.value, slots)
        val slotValue = step.slotRef?.let { slots[it] }?.trim().orEmpty()
        return when (step.type) {
            StepType.LAUNCH -> finish(i, step, device.launch(value) && waitFor { it.nodes.firstOrNull()?.packageName == value })
            StepType.WAIT -> { delay(value.toLongOrNull() ?: 500); StepResult(i, true) }
            StepType.BACK -> finish(i, step, device.back())
            StepType.SCROLL -> finish(i, step, device.scroll(device.tree()))
            StepType.TAP, StepType.TYPE -> targeted(i, step, next, slots, value)
            // Optional by design: "order a pizza" keeps the app's own quantity and address.
            StepType.SET_QUANTITY -> if (slotValue.isEmpty()) StepResult(i, true) else quantity(i, step, slots, slotValue)
            StepType.CHOOSE_ADDRESS -> if (slotValue.isEmpty()) StepResult(i, true) else address(i, slotValue)
        }
    }

    private suspend fun targeted(i: Int, step: FlowStep, next: FlowStep?, slots: Map<String, String>, value: String): StepResult {
        val sel = resolved(step.selector, slots)
        val isTap = step.type == StepType.TAP
        val slotValue = step.slotRef?.let { slots[it] }
        val probe = probeFor(step.type, sel, slotValue)

        var fallback = false
        var found = poll(cfg.waitMs, probe)
        // A sheet or upsell seen during the demo ("Unlock 20% OFF… Continue") need not appear again: if the next step
        // can already run, this one was an interstitial that was not shown this time.
        if (found == null && next != null && isDistinctive(next) && probeFor(next.type, resolved(next.selector, slots), next.slotRef?.let { slots[it] })(device.tree()) != null) {
            return StepResult(i, true, error = "skipped: not shown this time")
        }
        if (found == null && sel.near.isNotEmpty()) {
            // Row-anchored target (the ADD in the Farmhouse row) may be further down the list. Never handed to the LLM:
            // a wrong guess here adds the wrong dish.
            repeat(cfg.scrollTries) {
                if (found == null && device.scroll(device.tree())) found = poll(cfg.retryMs, probe)
            }
            if (found == null) return StepResult(i, false, error = stuck("\"${sel.near}\""))
        }
        if (found == null) found = poll(cfg.retryMs, probe) // retry once
        // LLM fallback: also how unexpected popups get dismissed/bypassed. Capped per run so a screen that keeps
        // changing under the assistant ends in a question instead of a string of guesses.
        if (found == null && picker != null && llmPicks < MAX_LLM_PICKS) {
            val tree = device.tree()
            val idx = picker.pick(step, slots, tree.nodes)
            tree.nodes.getOrNull(idx ?: -1)?.let { found = tree to it; fallback = true; llmPicks++ }
        }
        val (tree, node) = found ?: return StepResult(i, false, error = stuck(targetLabel(step, sel)))
        if (isTap) ScreenGuard.action(tree.nodes, node)?.let { return handover(i, it) }
        val before = signature(tree)
        val acted = if (isTap) device.tap(tree, node) else typeAndVerify(tree, node, sel, value)
        // Let the screen react before the next step looks for its target, or it may act on the old screen.
        if (acted && isTap) poll(cfg.settleMs) { t -> t.takeIf { signature(it) != before } }
        return finish(i, step, acted, fallback)
    }

    /**
     * Text set while a screen is still opening can be wiped when the field initialises: if the screen does not react
     * at all, the text is set again.
     */
    private suspend fun typeAndVerify(tree: LiveTree, node: UiNode, sel: Selector, value: String): Boolean {
        if (node.password) return device.type(tree, node, value) // never read back
        // Typing into a screen that is still initialising gets wiped when the field resets: wait for it to settle.
        var t = awaitStable()
        var n = SelectorMatcher.target(t.nodes, sel, null, forTap = false) ?: node.also { t = tree }
        // Some fields keep reporting their hint as their text, so "did it stick" is judged on the whole screen: any
        // reaction (a clear button, a spinner, results) counts. Slow search results are the next step's wait to handle;
        // retyping into a field that did react would only restart the search.
        repeat(TYPE_TRIES) {
            val before = signature(t)
            if (!device.type(t, n, value)) return false
            if (poll(cfg.retryMs) { tt -> tt.takeIf { signature(it) != before } } != null) return true
            android.util.Log.w("TVA", "replay: typed text did not show on screen, retrying")
            t = device.tree()
            n = SelectorMatcher.target(t.nodes, sel, null, forTap = false) ?: return false
        }
        return false
    }


    /** Only a tap with its own caption or row anchor proves the screen moved on; a bare text-field id exists everywhere. */
    private fun isDistinctive(step: FlowStep) = step.type == StepType.TAP &&
        (step.selector.text.isNotBlank() || step.selector.contentDesc.isNotBlank() || step.selector.near.isNotBlank())

    /** Returns the screen once it has not changed for [QUIET_MS] (at most [cfg.settleMs] of waiting). */
    private suspend fun awaitStable(): LiveTree {
        var last = device.tree()
        var quiet = 0L
        var waited = 0L
        while (quiet < QUIET_MS && waited < cfg.settleMs) {
            delay(cfg.pollMs); waited += cfg.pollMs
            val now = device.tree()
            quiet = if (signature(now) == signature(last)) quiet + cfg.pollMs else 0
            last = now
        }
        return last
    }

    private fun signature(t: LiveTree) = t.nodes.take(200).joinToString("|") { "${it.resourceId}:${it.text}:${it.contentDesc}" }.hashCode()

    private fun probeFor(type: StepType, sel: Selector, slotValue: String?): (LiveTree) -> Pair<LiveTree, UiNode>? = { t ->
        SelectorMatcher.target(t.nodes, sel, slotValue, type == StepType.TAP)?.let { t to it }
    }

    /** Taps "+" / "−" beside the item until the count reads [want]; each tap is re-read, never assumed. */
    private suspend fun quantity(i: Int, step: FlowStep, slots: Map<String, String>, want: String): StepResult {
        val n = want.toIntOrNull()?.takeIf { it in 1..MAX_QTY } ?: return StepResult(i, false, error = "quantity \"$want\" is not a number I can set")
        val near = resolveSlots(step.selector.near, slots)
        val item = SLOT_TOKEN.find(step.selector.near)?.groupValues?.get(1)?.let { slots[it] }
        var last = -1
        repeat(MAX_QTY + 2) {
            // Tree and stepper from the SAME read: a node is only tappable through the tree it was found in.
            val (t, s) = poll(cfg.waitMs) { tt -> Steppers.find(tt.nodes, near, item)?.let { tt to it } }
                ?: return StepResult(i, false, error = "couldn't find the quantity control for ${item ?: "the item"}")
            if (s.count == n) return StepResult(i, true)
            if (s.count == last) return StepResult(i, false, error = "the quantity stayed at ${s.count}")
            if (s.count > n && s.count == 1) return StepResult(i, false, error = "the quantity can't go below 1")
            val button = if (s.count < n) s.plus else s.minus
            ScreenGuard.action(t.nodes, button)?.let { return handover(i, it) }
            last = s.count
            device.tap(t, button)
            poll(cfg.waitMs) { tt -> Steppers.find(tt.nodes, near, item)?.takeIf { it.count != last } }
        }
        return StepResult(i, false, error = "couldn't set the quantity to $n")
    }

    /** Opens the delivery-address picker and chooses the saved address labelled [label]; verified on the header. */
    private suspend fun address(i: Int, label: String): StepResult {
        val chosen = Regex("\\bto\\s+${Regex.escape(label)}\\b", RegexOption.IGNORE_CASE)
        val isChosen = { t: LiveTree -> t.nodes.any { !it.editable && chosen.containsMatchIn(it.text + " " + it.contentDesc) } }
        if (isChosen(device.tree())) return StepResult(i, true)

        val opener = poll(cfg.waitMs) { t -> t.nodes.firstOrNull { ADDRESS_HEADER.containsMatchIn(it.text + " " + it.contentDesc) }?.let { t to it } }
            ?: return StepResult(i, false, error = "couldn't find where to change the delivery address")
        device.tap(opener.first, opener.second)

        val entry = { t: LiveTree -> t.nodes.firstOrNull { !it.editable && (it.text.trim().equals(label, true) || it.contentDesc.trim().equals(label, true)) }?.let { t to it } }
        var found = poll(cfg.waitMs, entry)
        repeat(cfg.scrollTries) { if (found == null && device.scroll(device.tree())) found = poll(cfg.retryMs, entry) }
        val (tree, node) = found ?: run {
            device.back() // close the picker again rather than leave the user in it
            return StepResult(i, false, error = "there's no saved address called \"$label\"")
        }
        device.tap(tree, node)
        if (waitFor(isChosen)) return StepResult(i, true)
        if (entry(device.tree()) != null) device.back() // still in the picker: close it rather than leave the user there
        return StepResult(i, false, error = "the app didn't switch to your \"$label\" address, it may be outside the delivery area")
    }

    private fun targetLabel(step: FlowStep, sel: Selector): String =
        (sel.text.ifBlank { sel.contentDesc }.ifBlank { step.selector.resourceId.substringAfterLast('/') }).let { if (it.isBlank()) "the next control" else "\"$it\"" }

    /**
     * Nothing to tap and nothing safe to guess: say what is missing and what the screen shows, as a question. A screen
     * of another app means the run was thrown out of the app (or never got in), which is worth saying differently.
     */
    private fun stuck(what: String): String {
        val nodes = device.tree().nodes
        val pkg = nodes.firstOrNull()?.packageName.orEmpty()
        if (appPackage.isNotEmpty() && pkg.isNotEmpty() && pkg != appPackage) {
            return "I'm not in the app any more, this screen belongs to ${pkg.substringAfterLast('.')}. Can you take me back to it so I can continue?"
        }
        return "I can't find $what. This screen shows ${ScreenSummary.of(nodes)}. What should I do?"
    }

    private fun resolved(sel: Selector, slots: Map<String, String>) = sel.copy(
        // Selector text may hold slot tokens ("{{query}} near me"); resolved against the CURRENT slot values.
        text = resolveSlots(sel.text, slots),
        contentDesc = resolveSlots(sel.contentDesc, slots),
        near = resolveSlots(sel.near, slots),
    )

    private fun handover(i: Int, b: Boundary) = StepResult(i, false, error = "handed over: ${b.reason}", handover = b)

    /** Post-action check: `expect` (package / text) must appear within the wait window. */
    private suspend fun finish(i: Int, step: FlowStep, acted: Boolean, fallback: Boolean = false): StepResult {
        if (!acted) return StepResult(i, false, fallback, "action failed")
        val e = step.expect
        if (e.packageName.isEmpty() && e.textContains.isEmpty()) return StepResult(i, true, fallback)
        val ok = waitFor { t ->
            (e.packageName.isEmpty() || t.nodes.firstOrNull()?.packageName == e.packageName) &&
                (e.textContains.isEmpty() || t.nodes.any {
                    !it.password && (it.text.contains(e.textContains, true) || it.contentDesc.contains(e.textContains, true))
                })
        }
        return if (ok) StepResult(i, true, fallback) else StepResult(i, false, fallback, "expected screen not reached")
    }

    private suspend fun <T : Any> poll(timeoutMs: Long, probe: (LiveTree) -> T?): T? {
        var waited = 0L
        while (true) {
            probe(device.tree())?.let { return it }
            if (waited >= timeoutMs) return null
            delay(cfg.pollMs); waited += cfg.pollMs
        }
    }

    private suspend fun waitFor(cond: (LiveTree) -> Boolean): Boolean =
        poll(cfg.waitMs) { t -> if (cond(t)) true else null } ?: false

    private companion object {
        const val MAX_QTY = 20
        const val MAX_LLM_PICKS = 2
        const val TYPE_TRIES = 3
        const val QUIET_MS = 600L
        val SLOT_TOKEN = Regex("\\{\\{(\\w+)\\}\\}")
        // "25-30 mins to Home", "Deliver to Work", "Delivering to …": the control that opens the address picker.
        val ADDRESS_HEADER = Regex("\\b(deliver(ing|y)?\\s+to|mins?\\s+to)\\s+\\S", RegexOption.IGNORE_CASE)
    }
}
