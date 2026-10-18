package com.tva.teachable.core

/** Why a step could not proceed. Decides whether the user's answer may change the stored flow. */
enum class StuckCause {
    /** A taught control is not on this screen and the step is not tied to any slot value. */
    MISSING_TARGET,
    /** Several different controls match and nothing says which one. */
    AMBIGUOUS,
    /** The requested value (a dish, a restaurant) is not on this screen. A property of the request, not of the flow. */
    SLOT_ABSENT,
    /** The run is no longer inside the app it was taught in. */
    WRONG_APP,
    /** The cart already holds the requested item (its row shows a stepper instead of ADD). */
    CART_CONFLICT,
    /** The app is asking to REPLACE the cart. Never tapped by the assistant, whatever it is told. */
    REPLACE_PROMPT,
}

/**
 * Is a correction a defect in the taught flow, or only an answer for this run?
 *
 * [FLOW_DEFECT]: the taught step itself is out of date — it names a control that is no longer there, and the user
 * pointed at what to do instead (or said it is not needed). Rewriting it would stop the same question being asked
 * on every future run. Persisted ONLY after the user has been told and has agreed.
 *
 * [SITUATIONAL]: the answer is about this screen or this request: which of two controls was meant, a dish that is
 * not on this menu, the app being elsewhere. Rewriting the flow for it would damage it for every other value, so it
 * resolves this run and is never stored.
 */
enum class CorrectionKind { FLOW_DEFECT, SITUATIONAL }

/** A change to a stored step that the user's answer implies. [none] means nothing would be persisted. */
enum class CorrectionOp { REPLACE_SELECTOR, MARK_OPTIONAL, INSERT_TAP, NONE }

data class Correction(
    val stepIndex: Int,
    val kind: CorrectionKind,
    val op: CorrectionOp,
    /** What the step was taught to do, in words. */
    val was: String,
    /** What the user had it do instead, in words. */
    val now: String,
    val selector: Selector? = null,
)

object CorrectionPolicy {
    /**
     * Only a plain, un-parameterised TAP whose control is missing can be a flow defect, and only when the answer
     * is a tap on something else or "skip it". Anything tied to a slot value or a row anchor is situational by
     * construction: a dish that is absent today is no reason to forget how to order it.
     */
    fun kind(step: FlowStep, cause: StuckCause, answer: Answer): CorrectionKind {
        val plainTap = step.type == StepType.TAP && step.slotRef == null && step.selector.near.isEmpty()
        val corrective = answer is Answer.Tap || answer is Answer.Skip
        return if (cause == StuckCause.MISSING_TARGET && plainTap && corrective) CorrectionKind.FLOW_DEFECT else CorrectionKind.SITUATIONAL
    }

    fun op(kind: CorrectionKind, answer: Answer): CorrectionOp = when {
        kind != CorrectionKind.FLOW_DEFECT -> CorrectionOp.NONE
        answer is Answer.Skip -> CorrectionOp.MARK_OPTIONAL
        answer is Answer.Tap -> CorrectionOp.REPLACE_SELECTOR
        else -> CorrectionOp.NONE
    }
}
