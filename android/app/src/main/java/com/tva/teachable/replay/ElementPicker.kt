package com.tva.teachable.replay

import com.tva.teachable.core.FlowStep
import com.tva.teachable.core.UiNode
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/** LLM fallback when a step's selector finds nothing. Returns the chosen node index, or null. */
fun interface ElementPicker {
    suspend fun pick(step: FlowStep, slots: Map<String, String>, nodes: List<UiNode>): Int?

    /**
     * The same choice with more to go on: [intent] in words ("tap the control after opening Domino's", or "user said: you
     * can search it here") and [context] (the task and the steps around this one). [step] is null for a user's answer.
     */
    suspend fun pickFor(step: FlowStep?, intent: String, context: String, slots: Map<String, String>, nodes: List<UiNode>): Int? =
        step?.let { pick(it, slots, nodes) }
}

/** Calls the backend, which asks Groq. Only compact, password-redacted node fields leave the device. */
class BackendElementPicker(private val baseUrl: String) : ElementPicker {
    override suspend fun pick(step: FlowStep, slots: Map<String, String>, nodes: List<UiNode>): Int? =
        pickFor(step, "${step.type.name.lowercase()} ${step.selector.text.ifEmpty { step.selector.contentDesc.ifEmpty { step.selector.resourceId } }}".trim(), "", slots, nodes)

    override suspend fun pickFor(step: FlowStep?, intent: String, context: String, slots: Map<String, String>, nodes: List<UiNode>): Int? {
        val compact = JSONArray()
        nodes.filter { !it.password && (it.text.isNotEmpty() || it.contentDesc.isNotEmpty() || it.resourceId.isNotEmpty()) }
            .take(MAX_NODES).forEach {
                compact.put(JSONObject().put("i", it.index).put("id", it.resourceId.substringAfter('/'))
                    .put("text", it.text.take(60)).put("desc", it.contentDesc.take(60))
                    .put("cls", it.className.substringAfterLast('.')).put("click", it.clickable).put("edit", it.editable))
            }
        val body = JSONObject()
            .put("intent", intent.take(300)).put("context", context.take(800))
            .put("slots", JSONObject(slots)).put("nodes", compact)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { post(body) }
    }

    private fun post(body: JSONObject): Int? {
        val c = URL("$baseUrl/api/llm/pick-element").openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "POST"; c.connectTimeout = 5000; c.readTimeout = 8000; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode != 200) { Log.w(TAG, "pick-element http ${c.responseCode}"); return null }
            val res = JSONObject(c.inputStream.bufferedReader().readText())
            if (res.isNull("index")) null else res.getInt("index")
        } catch (e: Exception) { Log.w(TAG, "pick-element failed: $e"); null } finally { c.disconnect() }
    }

    companion object { private const val MAX_NODES = 120; private const val TAG = "TVA" }
}
