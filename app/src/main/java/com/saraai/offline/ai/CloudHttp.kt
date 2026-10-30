package com.saraai.offline.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Minimal HTTPS client for OpenAI-compatible and Gemini REST APIs.
 * No third-party networking library to keep APK light.
 */
internal object CloudHttp {

    data class HttpResult(
        val code: Int,
        val body: String,
        val error: String = "",
    )

    fun postJson(
        urlStr: String,
        headers: Map<String, String>,
        body: String,
        timeoutSec: Int,
    ): HttpResult {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                connectTimeout = TimeUnit.SECONDS.toMillis(min(timeoutSec, 15).toLong()).toInt()
                readTimeout = TimeUnit.SECONDS.toMillis(timeoutSec.toLong()).toInt()
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { s ->
                BufferedReader(InputStreamReader(s, Charsets.UTF_8)).readText()
            }.orEmpty()
            HttpResult(code, text)
        } catch (t: Throwable) {
            HttpResult(-1, "", t.message ?: "network error")
        } finally {
            conn?.disconnect()
        }
    }

    fun openAiChatBody(
        model: String,
        systemPrompt: String,
        userMessage: String,
        maxTokens: Int,
        temperature: Float,
    ): String {
        val messages = JSONArray()
        if (systemPrompt.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        }
        messages.put(JSONObject().put("role", "user").put("content", userMessage))
        return JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_tokens", maxTokens.coerceIn(8, 1024))
            .put("temperature", temperature.toDouble().coerceIn(0.0, 2.0))
            .toString()
    }

    fun parseOpenAiChat(body: String): Pair<String, String> {
        return try {
            val root = JSONObject(body)
            if (root.has("error")) {
                val err = root.optJSONObject("error")
                val msg = err?.optString("message") ?: root.optString("error")
                return "" to (msg.ifBlank { "API error" })
            }
            val choices = root.optJSONArray("choices") ?: return "" to "no choices"
            if (choices.length() == 0) return "" to "empty choices"
            val msg = choices.getJSONObject(0).optJSONObject("message")
            val content = msg?.optString("content")?.trim().orEmpty()
            if (content.isBlank()) "" to "empty content" else content to ""
        } catch (t: Throwable) {
            "" to (t.message ?: "parse error")
        }
    }

    fun geminiBody(
        systemPrompt: String,
        userMessage: String,
        maxTokens: Int,
        temperature: Float,
    ): String {
        val parts = JSONArray().put(JSONObject().put("text", userMessage))
        val contents = JSONArray().put(
            JSONObject().put("role", "user").put("parts", parts),
        )
        val gen = JSONObject()
            .put("maxOutputTokens", maxTokens.coerceIn(8, 1024))
            .put("temperature", temperature.toDouble().coerceIn(0.0, 2.0))
        val root = JSONObject()
            .put("contents", contents)
            .put("generationConfig", gen)
        if (systemPrompt.isNotBlank()) {
            root.put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemPrompt)),
                ),
            )
        }
        return root.toString()
    }

    fun parseGemini(body: String): Pair<String, String> {
        return try {
            val root = JSONObject(body)
            if (root.has("error")) {
                val err = root.optJSONObject("error")
                val msg = err?.optString("message") ?: "Gemini error"
                return "" to msg
            }
            val candidates = root.optJSONArray("candidates") ?: return "" to "no candidates"
            if (candidates.length() == 0) return "" to "empty candidates"
            val content = candidates.getJSONObject(0).optJSONObject("content")
            val parts = content?.optJSONArray("parts") ?: return "" to "no parts"
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val t = parts.getJSONObject(i).optString("text")
                if (t.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(t)
                }
            }
            val text = sb.toString().trim()
            if (text.isBlank()) "" to "empty text" else text to ""
        } catch (t: Throwable) {
            "" to (t.message ?: "parse error")
        }
    }

    fun friendlyHttpError(code: Int, body: String): String {
        return when (code) {
            401, 403 -> "Invalid or missing API key (HTTP $code)"
            429 -> "Rate limited (HTTP 429). Try again later."
            400 -> "Bad request (HTTP 400). Check model name / payload."
            404 -> "Not found (HTTP 404). Check base URL or model."
            500, 502, 503 -> "Provider server error (HTTP $code)"
            -1 -> body.ifBlank { "Network / timeout error" }
            else -> "HTTP $code: ${body.take(200)}"
        }
    }
}
