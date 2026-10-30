package com.saraai.offline.ai

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single entry point used by Chat UI and WhatsApp notification listener.
 */
object AiRouter {
    private val mutex = Mutex()
    @Volatile var lastResult: GenerateResult? = null
        private set
    @Volatile var lastError: String = ""
        private set

    fun providerFor(context: Context, kind: ProviderKind): AiProvider = when (kind) {
        ProviderKind.Local -> LocalProvider()
        ProviderKind.Gemini -> GeminiProvider(context)
        ProviderKind.OpenAi -> OpenAiCompatibleProvider(
            context, ProviderKind.OpenAi.id,
            "https://api.openai.com/v1", "gpt-4o-mini", ProviderKind.OpenAi,
        )
        ProviderKind.Grok -> OpenAiCompatibleProvider(
            context, ProviderKind.Grok.id,
            "https://api.x.ai/v1", "grok-3", ProviderKind.Grok,
        )
        ProviderKind.NvidiaNim -> OpenAiCompatibleProvider(
            context, ProviderKind.NvidiaNim.id,
            "https://integrate.api.nvidia.com/v1", "meta/llama-3.1-8b-instruct", ProviderKind.NvidiaNim,
        )
        ProviderKind.OpenAiCompat -> OpenAiCompatibleProvider(
            context, ProviderKind.OpenAiCompat.id,
            "", "", ProviderKind.OpenAiCompat,
        )
    }

    suspend fun activeKind(context: Context): ProviderKind {
        val id = ProviderPrefs.read(context).activeKind
        return ProviderKind.fromId(id)
    }

    suspend fun generate(
        context: Context,
        systemPrompt: String,
        userMessage: String,
        maxTokens: Int? = null,
        temperature: Float? = null,
    ): GenerateResult = mutex.withLock {
        val settings = ProviderPrefs.read(context)
        val kind = ProviderKind.fromId(settings.activeKind)
        val provider = providerFor(context, kind)
        val req = GenerateRequest(
            systemPrompt = systemPrompt,
            userMessage = userMessage,
            maxTokens = maxTokens ?: settings.cloudMaxTokens,
            temperature = temperature ?: settings.temperature,
        )
        val result = try {
            provider.generate(req)
        } catch (t: Throwable) {
            GenerateResult("", kind.id, t.message ?: "router error")
        }
        lastResult = result
        lastError = result.error
        result
    }

    suspend fun testConnection(context: Context, kind: ProviderKind? = null): GenerateResult {
        val k = kind ?: activeKind(context)
        return providerFor(context, k).testConnection()
    }

    fun isLocal(kind: ProviderKind): Boolean = kind is ProviderKind.Local
}
