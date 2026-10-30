package com.saraai.offline.ai

import android.content.Context
import com.saraai.offline.llm.LlamaBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalProvider : AiProvider {
    override val kind: ProviderKind = ProviderKind.Local

    override suspend fun generate(req: GenerateRequest): GenerateResult = withContext(Dispatchers.Default) {
        val t0 = System.currentTimeMillis()
        if (!LlamaBridge.available) {
            return@withContext GenerateResult("", kind.id, "Native library missing", 0)
        }
        if (!LlamaBridge.loaded()) {
            return@withContext GenerateResult("", kind.id, "Local model not loaded", 0)
        }
        val prompt = buildString {
            if (req.systemPrompt.isNotBlank()) {
                append(req.systemPrompt.trim())
                append("\n\n")
            }
            append(req.userMessage.trim())
        }
        val text = try {
            LlamaBridge.generateEx(prompt, req.maxTokens, req.temperature, 40, 0.9f).trim()
        } catch (oom: OutOfMemoryError) {
            return@withContext GenerateResult("", kind.id, "OutOfMemoryError during generation", System.currentTimeMillis() - t0)
        } catch (t: Throwable) {
            return@withContext GenerateResult("", kind.id, t.message ?: "local generate failed", System.currentTimeMillis() - t0)
        }
        val ms = System.currentTimeMillis() - t0
        if (text.isBlank()) {
            GenerateResult("", kind.id, LlamaBridge.lastError.ifBlank { "empty local response" }, ms)
        } else {
            GenerateResult(text, kind.id, "", ms)
        }
    }

    override suspend fun testConnection(): GenerateResult {
        return if (!LlamaBridge.available) {
            GenerateResult("", kind.id, "Native library missing")
        } else if (!LlamaBridge.loaded()) {
            GenerateResult("", kind.id, "No GGUF model loaded")
        } else {
            GenerateResult("ok", kind.id, "")
        }
    }
}

class OpenAiCompatibleProvider(
    private val context: Context,
    private val providerId: String,
    private val defaultBaseUrl: String,
    private val defaultModel: String,
    override val kind: ProviderKind,
) : AiProvider {

    private fun resolveBase(cfgBase: String): String {
        val b = cfgBase.trim().ifBlank { defaultBaseUrl }.trimEnd('/')
        return if (b.endsWith("/chat/completions")) b else "$b/chat/completions"
    }

    private fun resolveModel(cfgModel: String): String =
        cfgModel.trim().ifBlank { defaultModel }

    override suspend fun generate(req: GenerateRequest): GenerateResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val key = SecureApiKeys.load(context, providerId)
        if (key.isBlank()) {
            return@withContext GenerateResult("", kind.id, "API key not set for ${kind.label}", 0)
        }
        val prefs = ProviderPrefs.read(context)
        val url = resolveBase(prefs.baseUrlFor(providerId))
        val model = resolveModel(prefs.modelFor(providerId))
        val timeout = prefs.timeoutSec.coerceIn(10, 120)
        val body = CloudHttp.openAiChatBody(model, req.systemPrompt, req.userMessage, req.maxTokens, req.temperature)
        val http = CloudHttp.postJson(
            url,
            mapOf("Authorization" to "Bearer $key"),
            body,
            timeout,
        )
        val ms = System.currentTimeMillis() - t0
        if (http.error.isNotBlank()) {
            return@withContext GenerateResult("", kind.id, http.error, ms)
        }
        if (http.code !in 200..299) {
            return@withContext GenerateResult("", kind.id, CloudHttp.friendlyHttpError(http.code, http.body), ms)
        }
        val (text, err) = CloudHttp.parseOpenAiChat(http.body)
        if (err.isNotBlank()) GenerateResult("", kind.id, err, ms)
        else GenerateResult(text, kind.id, "", ms)
    }

    override suspend fun testConnection(): GenerateResult {
        return generate(
            GenerateRequest(
                systemPrompt = "You are a connectivity test.",
                userMessage = "Reply with the single word OK.",
                maxTokens = 8,
                temperature = 0f,
            ),
        )
    }
}

class GeminiProvider(private val context: Context) : AiProvider {
    override val kind: ProviderKind = ProviderKind.Gemini

    override suspend fun generate(req: GenerateRequest): GenerateResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val key = SecureApiKeys.load(context, kind.id)
        if (key.isBlank()) {
            return@withContext GenerateResult("", kind.id, "API key not set for Gemini", 0)
        }
        val prefs = ProviderPrefs.read(context)
        val model = prefs.modelFor(kind.id).ifBlank { "gemini-2.0-flash" }
        val timeout = prefs.timeoutSec.coerceIn(10, 120)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val body = CloudHttp.geminiBody(req.systemPrompt, req.userMessage, req.maxTokens, req.temperature)
        val http = CloudHttp.postJson(
            url,
            mapOf("x-goog-api-key" to key),
            body,
            timeout,
        )
        val ms = System.currentTimeMillis() - t0
        if (http.error.isNotBlank()) {
            return@withContext GenerateResult("", kind.id, http.error, ms)
        }
        if (http.code !in 200..299) {
            return@withContext GenerateResult("", kind.id, CloudHttp.friendlyHttpError(http.code, http.body), ms)
        }
        val (text, err) = CloudHttp.parseGemini(http.body)
        if (err.isNotBlank()) GenerateResult("", kind.id, err, ms)
        else GenerateResult(text, kind.id, "", ms)
    }

    override suspend fun testConnection(): GenerateResult {
        return generate(
            GenerateRequest(
                systemPrompt = "",
                userMessage = "Reply with the single word OK.",
                maxTokens = 8,
                temperature = 0f,
            ),
        )
    }
}
