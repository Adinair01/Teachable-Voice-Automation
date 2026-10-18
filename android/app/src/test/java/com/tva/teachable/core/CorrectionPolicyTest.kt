package com.tva.teachable.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The line between "the flow is out of date" and "this run was different". Getting it wrong in one direction asks the
 * same question forever; in the other it rewrites a good flow because of one odd screen.
 */
class CorrectionPolicyTest {
    private val plainTap = FlowStep(StepType.TAP, Selector(text = "Ordered by 90+ customers"))
    private val slotTap = FlowStep(StepType.TAP, Selector(text = "{{restaurant}} Pizza"), slotRef = "restaurant")
    private val anchoredTap = FlowStep(StepType.TAP, Selector(contentDesc = "ADD", near = "{{item}}"), slotRef = "item")
    private val typing = FlowStep(StepType.TYPE, Selector(resourceId = "z:id/edittext"), value = "{{item}}", slotRef = "item")
    private val tapAnswer = Answer.Tap("continue")

    @Test fun aMissingPlainControlThatTheUserRedirectsIsAFlowDefect() {
        assertEquals(CorrectionKind.FLOW_DEFECT, CorrectionPolicy.kind(plainTap, StuckCause.MISSING_TARGET, tapAnswer))
        assertEquals(CorrectionOp.REPLACE_SELECTOR, CorrectionPolicy.op(CorrectionKind.FLOW_DEFECT, tapAnswer))
    }

    @Test fun skippingAMissingPlainControlMarksItOptionalRatherThanDeletingIt() {
        assertEquals(CorrectionKind.FLOW_DEFECT, CorrectionPolicy.kind(plainTap, StuckCause.MISSING_TARGET, Answer.Skip))
        assertEquals(CorrectionOp.MARK_OPTIONAL, CorrectionPolicy.op(CorrectionKind.FLOW_DEFECT, Answer.Skip))
    }

    @Test fun anythingTiedToARequestedValueIsSituational() {
        // A dish or restaurant that is absent today is no reason to forget how to order it.
        for (cause in StuckCause.entries) {
            assertEquals("$cause slot tap", CorrectionKind.SITUATIONAL, CorrectionPolicy.kind(slotTap, cause, tapAnswer))
            assertEquals("$cause anchored", CorrectionKind.SITUATIONAL, CorrectionPolicy.kind(anchoredTap, cause, tapAnswer))
            assertEquals("$cause typing", CorrectionKind.SITUATIONAL, CorrectionPolicy.kind(typing, cause, tapAnswer))
        }
    }

    @Test fun onlyAMissingControlCanBeAFlowDefect() {
        for (cause in StuckCause.entries.filter { it != StuckCause.MISSING_TARGET })
            assertEquals(cause.name, CorrectionKind.SITUATIONAL, CorrectionPolicy.kind(plainTap, cause, tapAnswer))
    }

    @Test fun stoppingOrRetryingNeverChangesAFlow() {
        for (answer in listOf(Answer.Stop, Answer.Retry, Answer.Unclear))
            assertEquals(CorrectionKind.SITUATIONAL, CorrectionPolicy.kind(plainTap, StuckCause.MISSING_TARGET, answer))
        assertEquals(CorrectionOp.NONE, CorrectionPolicy.op(CorrectionKind.SITUATIONAL, tapAnswer))
    }
}
