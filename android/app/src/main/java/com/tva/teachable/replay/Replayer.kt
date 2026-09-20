package com.tva.teachable.replay

import com.tva.teachable.a11y.LiveTree
import com.tva.teachable.core.Flow
import com.tva.teachable.core.FlowStep
import com.tva.teachable.core.Selector
import com.tva.teachable.core.SelectorMatcher
import com.tva.teachable.core.StepType
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

data class ReplayConfig(val waitMs: Long = 6000, val retryMs: Long = 3000, val pollMs: Long = 300)
data class StepResult(val index: Int, val ok: Boolean, val usedLlmFallback: Boolean = false, val error: String? = null, val declined: Boolean = false)
/** [declined] = the user said no to a sensitive step. That is the safety feature working, not a failed replay. */
data class RunResult(val success: Boolean, val steps: List<StepResult>, val declined: Boolean = false)

class Replayer(
    private val device: Device,
    private val picker: ElementPicker? = null,
    private val gate: ConfirmationGate = DenyAll,
    private val cfg: ReplayConfig = ReplayConfig(),
) {
    suspend fun run(flow: Flow, slots: Map<String, String>): RunResult {
        val results = ArrayList<StepResult>()
        for ((i, step) in flow.steps.withIndex()) {
            val r = runStep(i, step, slots)
            results += r
            if (!r.ok) return RunResult(false, results, r.declined)
        }
        return RunResult(true, results)
    }

    private suspend fun runStep(i: Int, step: FlowStep, slots: Map<String, String>): StepResult {
        if (step.sensitive && !gate.confirm(step, slots)) return StepResult(i, false, error = "sensitive step not confirmed", declined = true)
        val value = resolveSlots(step.value, slots)
        return when (step.type) {
            StepType.LAUNCH -> finish(i, step, device.launch(value) && waitFor { it.nodes.firstOrNull()?.packageName == value })
            StepType.WAIT -> { delay(value.toLongOrNull() ?: 500); StepResult(i, true) }
            StepType.BACK -> finish(i, step, device.back())
            StepType.SCROLL -> finish(i, step, device.scroll(device.tree()))
            StepType.TAP, StepType.TYPE -> targeted(i, step, slots, value)
        }
    }

    private suspend fun targeted(i: Int, step: FlowStep, slots: Map<String, String>, value: String): StepResult {
        // Selector text may hold slot tokens ("{{query}} near me"); resolved against the CURRENT slot values.
        val sel = step.selector.copy(
            text = resolveSlots(step.selector.text, slots),
            contentDesc = resolveSlots(step.selector.contentDesc, slots),
        )
        val isTap = step.type == StepType.TAP
        val slotValue = step.slotRef?.let { slots[it] }
        val probe = { t: LiveTree ->
            // Taps never target text inputs (a typed value would otherwise match itself).
            val nodes = if (isTap) t.nodes.filter { !it.editable } else t.nodes
            SelectorMatcher.findTarget(nodes, sel, slotValue, isTap)?.let { t to it }
        }

        var fallback = false
        var found = poll(cfg.waitMs, probe)
        if (found == null) found = poll(cfg.retryMs, probe) // retry once
        if (found == null && picker != null) { // LLM fallback: also how unexpected popups get dismissed/bypassed
            val tree = device.tree()
            val idx = picker.pick(step, slots, tree.nodes)
            tree.nodes.getOrNull(idx ?: -1)?.let { found = tree to it; fallback = true }
        }
        val (tree, node) = found ?: return StepResult(i, false, error = "target not found")
        val acted = if (isTap) device.tap(tree, node) else device.type(tree, node, value)
        return finish(i, step, acted, fallback)
    }

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
}
