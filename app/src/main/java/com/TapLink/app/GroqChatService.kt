package com.TapLinkX3.app

import com.TapLinkX3.app.routing.model.RouteTarget
import com.TapLinkX3.app.routing.model.RoutingAction
import com.TapLinkX3.app.routing.model.RoutingContext
import com.TapLinkX3.app.routing.model.RoutingDecision
import android.os.Handler
import android.os.Looper
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Agent row scraped from the chat page for structured intent extraction. */
data class ChatAgent(val id: String?, val name: String, val description: String)

/**
 * Groq chat completions with [Structured Outputs](https://console.groq.com/docs/structured-outputs)
 * strict mode. Runs network work off the main thread; invokes [callback] on the main looper.
 */
class GroqChatService(private val groqAudioService: GroqAudioService) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val client =
            OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .build()

    fun extractRoutingDecision(
            context: RoutingContext,
            callback: (RoutingDecision, groqRequestFailed: Boolean) -> Unit
    ) {
        DebugLog.alwaysI(
                TAG,
                "extractRoutingDecision transcript=\"${context.transcript.trim()}\" agents=${context.agents.size} dashboards=${context.dashboards.size} inConversation=${context.isInConversation}"
        )
        val apiKey = groqAudioService.getApiKey()
        if (apiKey.isNullOrBlank()) {
            DebugLog.alwaysW(TAG, "extractRoutingDecision skipped: API key empty")
            mainHandler.post { callback(defaultRoutingDecision(context.transcript), true) }
            return
        }
        Thread {
            try {
                val bodyJson = buildRoutingRequestJson(context)
                DebugLog.alwaysI(
                        TAG,
                        "llm_request model=$MODEL_STRICT_SMALL transcript=\"${context.transcript.trim().take(220)}\" normalized=\"${context.normalizedTranscript.take(220)}\""
                )
                val request =
                        Request.Builder()
                                .url(CHAT_COMPLETIONS_URL)
                                .addHeader("Authorization", "Bearer $apiKey")
                                .addHeader("Content-Type", "application/json")
                                .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                                .build()
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        DebugLog.alwaysW(TAG, "llm_output http=${response.code} (request failed)")
                        logLongField(TAG, "llm_transcript", context.transcript.trim())
                        logLongField(TAG, "llm_response_body", responseBody)
                        mainHandler.post { callback(defaultRoutingDecision(context.transcript), true) }
                        return@use
                    }
                    val root = JSONObject(responseBody)
                    val content =
                            root.optJSONArray("choices")
                                    ?.optJSONObject(0)
                                    ?.optJSONObject("message")
                                    ?.optString("content")
                                    .orEmpty()
                    DebugLog.alwaysI(TAG, "llm_output http=${response.code} (assistant JSON)")
                    logLongField(TAG, "llm_transcript", context.transcript.trim())
                    logLongField(TAG, "llm_response_content", content)
                    if (content.isBlank()) {
                        mainHandler.post { callback(defaultRoutingDecision(context.transcript), true) }
                        return@use
                    }
                    val parsed = parseRoutingContent(content)
                    DebugLog.alwaysI(
                            TAG,
                            "llm_decision parsed=${parsed != null} action=${parsed?.action} target=${parsed?.routeTarget} dashboardId=${parsed?.dashboardId} agentId=${parsed?.agentId} confidence=${parsed?.confidence} message=\"${parsed?.message?.trim()?.take(260)}\""
                    )
                    mainHandler.post {
                        if (parsed != null) callback(parsed, false)
                        else callback(defaultRoutingDecision(context.transcript), true)
                    }
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "extractRoutingDecision exception", e)
                mainHandler.post { callback(defaultRoutingDecision(context.transcript), true) }
            }
        }.start()
    }

    private fun buildRoutingRequestJson(context: RoutingContext): JSONObject {
        val agentsArr = JSONArray()
        for (a in context.agents) {
            agentsArr.put(
                    JSONObject().apply {
                        put("id", a["id"])
                        put("name", a["name"])
                        put("description", a["description"])
                    }
            )
        }
        val dashboardsArr = JSONArray()
        for (d in context.dashboards) {
            dashboardsArr.put(
                    JSONObject().apply {
                        put("dashboard_id", d.dashboardId)
                        put("library_id", d.libraryId)
                        put("title", d.title)
                    }
            )
        }
        val payload =
                JSONObject().apply {
                    put("transcript", context.transcript)
                    put("normalized_transcript", context.normalizedTranscript)
                    put("locale", context.locale)
                    put("current_url", context.currentUrl)
                    put("is_in_conversation", context.isInConversation)
                    put("current_conversation_id", context.currentConversationId)
                    put("agents", agentsArr)
                    put("dashboards", dashboardsArr)
                }
        val schema =
                JSONObject().apply {
                    put("type", "object")
                    put(
                            "properties",
                            JSONObject().apply {
                                put("action", JSONObject().apply { put("type", "string") })
                                put("route_target", JSONObject().apply { put("type", "string") })
                                put(
                                        "dashboard_id",
                                        JSONObject().apply {
                                            put("type", JSONArray(listOf("string", "null")))
                                        }
                                )
                                put(
                                        "agent_id",
                                        JSONObject().apply {
                                            put("type", JSONArray(listOf("string", "null")))
                                        }
                                )
                                put("message", JSONObject().apply { put("type", "string") })
                                put("confidence", JSONObject().apply { put("type", "number") })
                                put("reason_code", JSONObject().apply { put("type", "string") })
                            }
                    )
                    put(
                            "required",
                            JSONArray(
                                    listOf(
                                            "action",
                                            "route_target",
                                            "dashboard_id",
                                            "agent_id",
                                            "message",
                                            "confidence",
                                            "reason_code"
                                    )
                            )
                    )
                    put("additionalProperties", false)
                }
        val responseFormat =
                JSONObject().apply {
                    put("type", "json_schema")
                    put(
                            "json_schema",
                            JSONObject().apply {
                                put("name", "voice_routing_decision")
                                put("strict", true)
                                put("schema", schema)
                            }
                    )
                }
        val system =
                "You are a voice routing policy engine. Return JSON only. " +
                        "Choose action from: NAVIGATE_DASHBOARD_LIST, NAVIGATE_DASHBOARD_DETAIL, NAVIGATE_AGENT_LIST, NAVIGATE_AGENT_DETAIL, NAVIGATE_CONVERSATION_NEW, CHAT_IN_CURRENT_CONVERSATION, CHAT_WITH_AGENT_SWITCH, DICTATE_TEXT, NO_OP. " +
                        "Use dashboard_id only from dashboards[].dashboard_id. Use agent_id only from agents[].id. Never invent ids. " +
                        "For explicit list navigation requests, always return list actions even when candidates are empty: " +
                        "dashboard list command => NAVIGATE_DASHBOARD_LIST; agents list command => NAVIGATE_AGENT_LIST. " +
                        "For dashboard detail use dashboard_id when user asks open/detail a specific dashboard. " +
                        "For agent detail use agent_id when user asks open/detail a specific agent. " +
                        "Detail actions require IDs. If ID cannot be resolved for detail actions, return NO_OP. " +
                        "When in conversation: continue same thread => CHAT_IN_CURRENT_CONVERSATION; if user wants another agent => CHAT_WITH_AGENT_SWITCH. " +
                        "If user asks to switch assistant but no specific agent id can be resolved, still use CHAT_WITH_AGENT_SWITCH with agent_id=null and keep message. " +
                        "If ambiguous or insufficient confidence return NO_OP with empty ids. " +
                        "Keep message in original language and strip routing wrappers."
        val messages =
                JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", system) })
                    put(
                            JSONObject().apply {
                                put("role", "user")
                                put("content", payload.toString())
                            }
                    )
                }
        return JSONObject().apply {
            put("model", MODEL_STRICT_SMALL)
            put("temperature", 0)
            put("messages", messages)
            put("response_format", responseFormat)
        }
    }

    /** Android Log truncates long lines; split so transcript / JSON content stay readable in logcat. */
    private fun logLongField(tag: String, label: String, value: String, maxChunk: Int = 3800) {
        if (value.isEmpty()) {
            DebugLog.alwaysI(tag, "$label=(empty)")
            return
        }
        if (value.length <= maxChunk) {
            DebugLog.alwaysI(tag, "$label=$value")
            return
        }
        val totalParts = (value.length + maxChunk - 1) / maxChunk
        var offset = 0
        var partIndex = 0
        while (offset < value.length) {
            val end = minOf(offset + maxChunk, value.length)
            DebugLog.alwaysI(
                    tag,
                    "$label part=${partIndex + 1}/$totalParts ${value.substring(offset, end)}"
            )
            offset = end
            partIndex++
        }
    }

    private fun parseRoutingContent(content: String): RoutingDecision? {
        return try {
            val o = JSONObject(content)
            val action =
                    runCatching { RoutingAction.valueOf(o.optString("action", "NO_OP").trim()) }
                            .getOrDefault(RoutingAction.NO_OP)
            val target =
                    runCatching { RouteTarget.valueOf(o.optString("route_target", "NONE").trim()) }
                            .getOrDefault(RouteTarget.NONE)
            val dashboardId =
                    if (o.isNull("dashboard_id")) null
                    else o.optString("dashboard_id", "").trim().takeIf { it.isNotBlank() }
            val agentId =
                    if (o.isNull("agent_id")) null
                    else o.optString("agent_id", "").trim().takeIf { it.isNotBlank() }
            RoutingDecision(
                    action = action,
                    routeTarget = target,
                    dashboardId = dashboardId,
                    agentId = agentId,
                    message = o.optString("message", ""),
                    confidence = o.optDouble("confidence", 0.0),
                    reasonCode = o.optString("reason_code", "model_output")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun defaultRoutingDecision(transcript: String): RoutingDecision =
            RoutingDecision(
                    action = RoutingAction.NO_OP,
                    routeTarget = RouteTarget.NONE,
                    dashboardId = null,
                    agentId = null,
                    message = transcript.trim(),
                    confidence = 0.0,
                    reasonCode = "fallback"
            )

    companion object {
        private const val TAG = "GroqChatService"
        private const val CHAT_COMPLETIONS_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val MODEL_STRICT_SMALL = "openai/gpt-oss-20b"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
