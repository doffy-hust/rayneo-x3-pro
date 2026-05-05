package com.TapLinkX3.app

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
data class ChatAgent(val name: String, val description: String)

/** Result of Groq chat-completions structured output for voice → chat. */
data class ChatIntent(val agentName: String?, val message: String)

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

    /**
     * @param groqRequestFailed true when the HTTP request failed, non-2xx, or response could not
     *     be parsed — caller may show a toast; false when a normal model JSON object was returned.
     */
    fun extractChatIntent(
            transcript: String,
            agents: List<ChatAgent>,
            callback: (ChatIntent, groqRequestFailed: Boolean) -> Unit
    ) {
        val apiKey = groqAudioService.getApiKey()
        if (apiKey.isNullOrBlank()) {
            mainHandler.post {
                callback(ChatIntent(agentName = null, message = transcript), true)
            }
            return
        }

        Thread {
            try {
                val bodyJson = buildRequestJson(transcript, agents)
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
                        mainHandler.post {
                            callback(ChatIntent(agentName = null, message = transcript), true)
                        }
                        return@use
                    }
                    val root = JSONObject(responseBody)
                    val content =
                            root.optJSONArray("choices")
                                    ?.optJSONObject(0)
                                    ?.optJSONObject("message")
                                    ?.optString("content")
                                    .orEmpty()
                    if (content.isBlank()) {
                        mainHandler.post {
                            callback(ChatIntent(agentName = null, message = transcript), true)
                        }
                        return@use
                    }
                    val parsed = parseContent(content)
                    val ok = parsed != null
                    mainHandler.post {
                        if (ok) {
                            callback(parsed!!, false)
                        } else {
                            callback(ChatIntent(agentName = null, message = transcript), true)
                        }
                    }
                }
            } catch (_: Exception) {
                mainHandler.post {
                    callback(ChatIntent(agentName = null, message = transcript), true)
                }
            }
        }.start()
    }

    private fun buildRequestJson(transcript: String, agents: List<ChatAgent>): JSONObject {
        val agentsArr = JSONArray()
        for (a in agents) {
            agentsArr.put(
                    JSONObject().apply {
                        put("name", a.name)
                        put("description", a.description)
                    }
            )
        }
        val userPayload =
                JSONObject().apply {
                    put("transcript", transcript)
                    put("pre_stripped_message", stripRoutingPrefixForHint(transcript))
                    put("agents", agentsArr)
                }

        val schema =
                JSONObject().apply {
                    put("type", "object")
                    put(
                            "properties",
                            JSONObject().apply {
                                put(
                                        "agent_name",
                                        JSONObject().apply {
                                            put("type", JSONArray(listOf("string", "null")))
                                        }
                                )
                                put("message", JSONObject().apply { put("type", "string") })
                            }
                    )
                    put("required", JSONArray(listOf("agent_name", "message")))
                    put("additionalProperties", false)
                }

        val responseFormat =
                JSONObject().apply {
                    put("type", "json_schema")
                    put(
                            "json_schema",
                            JSONObject().apply {
                                put("name", "chat_voice_intent")
                                put("strict", true)
                                put("schema", schema)
                            }
                    )
                }

        val messages =
                JSONArray().apply {
                    put(
                            JSONObject().apply {
                                put("role", "system")
                                put(
                                        "content",
                                        "You extract chat intent from voice text. " +
                                                "Input JSON contains transcript, pre_stripped_message, and agents (name + description). " +
                                                "Return JSON that matches the schema exactly. " +
                                                "agent_name MUST be selected ONLY from agents[].name in the provided list, or null if no clear match. " +
                                                "Never invent agent names. Never copy a name from transcript unless it matches one item in agents[].name. " +
                                                "message MUST be only the user question/content for chat, with routing/wake prefixes removed. " +
                                                "Keep message in the SAME language as the user transcript; do not translate. " +
                                                "Example: transcript='Mở chat và hỏi Jack thời tiết hôm nay' => " +
                                                "message='thời tiết hôm nay' (Vietnamese, not English). " +
                                                "Remove leading patterns such as: 'open chat', 'go to chat', 'open conversation', 'hey aiz'. " +
                                                "When an agent is mentioned in a command style, also remove the command wrapper. " +
                                                "Example: transcript='Open chat and ask Jack that what\\'s the weather today?' => " +
                                                "if and only if 'Jack' exists in agents[].name then agent_name='Jack'; otherwise agent_name=null. " +
                                                "message='What\\'s the weather today?'. " +
												"When users reference a domain (e.g. finance, hr, legal, marketing), choose agent_name only if one provided agent clearly matches by name or description; otherwise return null. " +
                                                "If no substantive question remains, set message to empty string."
                                )
                            }
                    )
                    put(
                            JSONObject().apply {
                                put("role", "user")
                                put("content", userPayload.toString())
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

    /** Returns null if the response body is not valid JSON for the expected shape. */
    private fun parseContent(content: String): ChatIntent? {
        return try {
            val o = JSONObject(content)
            val agentName: String? =
                    if (o.isNull("agent_name")) null
                    else o.optString("agent_name", "").trim().takeIf { it.isNotBlank() }
            val message = o.optString("message", "")
            ChatIntent(agentName = agentName, message = message)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val CHAT_COMPLETIONS_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val MODEL_STRICT_SMALL = "openai/gpt-oss-120b"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val ROUTING_PREFIX_PATTERN =
                Regex(
                        """(?i)^\s*((hey\s+)?aiz\s*)?(open|go\s*to|change\s*to|show|mở|mo|chuyển\s*sang|chuyen\s*sang|đi\s*đến|di\s*den)?\s*(conversation|conversations|chat|hội\s*thoại|hoi\s*thoai|cuộc\s*trò\s*chuyện|cuoc\s*tro\s*chuyen)\s*(and\s+)?[:,-]?\s*"""
                )
    }

    private fun stripRoutingPrefixForHint(rawText: String): String {
        return rawText.replace(ROUTING_PREFIX_PATTERN, "").trim()
    }
}
