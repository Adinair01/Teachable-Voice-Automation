package com.tva.teachable.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * SET_QUANTITY / CHOOSE_ADDRESS are not recorded taps: they are added to ordering flows so a quantity or delivery
 * address never shown in the demo can still be asked for ("two pizzas", "deliver to work"). Skipped when the slot is empty.
 */
enum class StepType { LAUNCH, TAP, TYPE, SCROLL, BACK, WAIT, SET_QUANTITY, CHOOSE_ADDRESS;
    companion object { fun parse(s: String) = valueOf(s.uppercase()) }
}

data class Expect(val packageName: String = "", val textContains: String = "")

data class FlowStep(
    val type: StepType,
    val selector: Selector = Selector(),
    val slotRef: String? = null,
    val value: String = "",
    val sensitive: Boolean = false,
    val expect: Expect = Expect(),
    /** Recording only: captions of the tapped element's row, so the backend can turn one into selector.near. */
    val context: List<String> = emptyList(),
    /** Recording only: how many controls of this kind the demo screen showed; >1 means the row anchor is required. */
    val candidates: Int = 0,
    /** Recording only: captions of the screen this tap led to, so the backend can set expect.textContains. */
    val screen: List<String> = emptyList(),
    /**
     * A tap the user said can be left out ("skip it") when its control is not on screen. Honoured ONLY for a plain tap
     * with no slot: a missing slot target means the flow cannot do what was asked and is never skipped quietly.
     */
    val optional: Boolean = false,
)

data class Flow(val intentKey: String, val description: String, val appPackage: String, val steps: List<FlowStep>)

/** Slot substitution: "{{item}}" tokens in [value] are replaced from [slots]. Single implementation for replay + tests. */
fun resolveSlots(value: String, slots: Map<String, String>): String =
    slots.entries.fold(value) { acc, (k, v) -> acc.replace("{{$k}}", v) }

object FlowJson {
    fun parse(json: String): Flow {
        val o = JSONObject(json)
        val steps = o.getJSONArray("steps")
        return Flow(
            intentKey = o.optString("intentKey"),
            description = o.optString("description"),
            appPackage = o.optString("appPackage"),
            steps = (0 until steps.length()).map { stepFrom(steps.getJSONObject(it)) },
        )
    }

    fun stepFrom(o: JSONObject): FlowStep {
        val s = o.optJSONObject("selector")
        val e = o.optJSONObject("expect")
        return FlowStep(
            type = StepType.parse(o.getString("type")),
            selector = Selector(
                resourceId = s?.optString("resourceId").orEmpty(),
                text = s?.optString("text").orEmpty(),
                contentDesc = s?.optString("contentDesc").orEmpty(),
                className = s?.optString("className").orEmpty(),
                near = s?.optString("near").orEmpty(),
            ),
            slotRef = o.optString("slotRef").takeIf { it.isNotEmpty() && it != "null" },
            value = o.optString("value"),
            sensitive = o.optBoolean("sensitive", false),
            expect = Expect(e?.optString("package").orEmpty(), e?.optString("textContains").orEmpty()),
            optional = o.optBoolean("optional", false),
        )
    }

    fun stepToJson(step: FlowStep): JSONObject = JSONObject()
        .put("type", step.type.name.lowercase())
        .put("selector", JSONObject().put("resourceId", step.selector.resourceId).put("text", step.selector.text)
            .put("contentDesc", step.selector.contentDesc).put("className", step.selector.className).put("near", step.selector.near))
        .put("slotRef", step.slotRef ?: JSONObject.NULL)
        .put("value", step.value)
        .put("sensitive", step.sensitive)
        .put("expect", JSONObject().put("package", step.expect.packageName).put("textContains", step.expect.textContains))
        .apply {
            if (step.context.isNotEmpty()) put("context", JSONArray(step.context))
            if (step.candidates > 0) put("candidates", step.candidates)
            if (step.screen.isNotEmpty()) put("screen", JSONArray(step.screen))
            if (step.optional) put("optional", true)
        }

    fun toJson(steps: List<FlowStep>): JSONArray = JSONArray().also { a -> steps.forEach { a.put(stepToJson(it)) } }
}

/** Spoken confirmation parsing; anything not clearly affirmative is a NO (fail safe). */
object ConfirmText {
    private val YES = Regex("\\b(yes|yeah|yep|yup|confirm|confirmed|go ahead|do it|sure|proceed)\\b", RegexOption.IGNORE_CASE)
    private val NO = Regex("\\b(no|nope|cancel|stop|don'?t|abort)\\b", RegexOption.IGNORE_CASE)
    fun isYes(text: String) = YES.containsMatchIn(text) && !NO.containsMatchIn(text)
}
