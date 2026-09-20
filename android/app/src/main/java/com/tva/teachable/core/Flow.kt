package com.tva.teachable.core

import org.json.JSONArray
import org.json.JSONObject

enum class StepType { LAUNCH, TAP, TYPE, SCROLL, BACK, WAIT;
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
            ),
            slotRef = o.optString("slotRef").takeIf { it.isNotEmpty() && it != "null" },
            value = o.optString("value"),
            sensitive = o.optBoolean("sensitive", false),
            expect = Expect(e?.optString("package").orEmpty(), e?.optString("textContains").orEmpty()),
        )
    }

    fun stepToJson(step: FlowStep): JSONObject = JSONObject()
        .put("type", step.type.name.lowercase())
        .put("selector", JSONObject().put("resourceId", step.selector.resourceId).put("text", step.selector.text)
            .put("contentDesc", step.selector.contentDesc).put("className", step.selector.className))
        .put("slotRef", step.slotRef ?: JSONObject.NULL)
        .put("value", step.value)
        .put("sensitive", step.sensitive)
        .put("expect", JSONObject().put("package", step.expect.packageName).put("textContains", step.expect.textContains))

    fun toJson(steps: List<FlowStep>): JSONArray = JSONArray().also { a -> steps.forEach { a.put(stepToJson(it)) } }
}

/** Spoken confirmation parsing; anything not clearly affirmative is a NO (fail safe). */
object ConfirmText {
    private val YES = Regex("\\b(yes|yeah|yep|yup|confirm|confirmed|go ahead|do it|sure|proceed)\\b", RegexOption.IGNORE_CASE)
    private val NO = Regex("\\b(no|nope|cancel|stop|don'?t|abort)\\b", RegexOption.IGNORE_CASE)
    fun isYes(text: String) = YES.containsMatchIn(text) && !NO.containsMatchIn(text)
}
