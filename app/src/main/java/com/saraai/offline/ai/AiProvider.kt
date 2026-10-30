package com.saraai.offline.ai

/**
 * Abstraction over local llama.cpp and cloud HTTP APIs.
 * Chat and WhatsApp auto-reply both go through [AiRouter].
 */
sealed class ProviderKind(val id: String, val label: String) {
    data object Local : ProviderKind("local", "Local (offline)")
    data object Gemini : ProviderKind("gemini", "Google Gemini")
    data object OpenAi : ProviderKind("openai", "OpenAI / ChatGPT")
    data object Grok : ProviderKind("grok", "xAI Grok")
    data object NvidiaNim : ProviderKind("nvidia_nim", "NVIDIA NIM")
    data object OpenAiCompat : ProviderKind("openai_compat", "Custom OpenAI-compatible")

    companion object {
        val all: List<ProviderKind> = listOf(Local, Gemini, OpenAi, Grok, NvidiaNim, OpenAiCompat)
        fun fromId(id: String): ProviderKind = all.firstOrNull { it.id == id } ?: Local
    }
}

data class ProviderConfig(
    val kind: String = ProviderKind.Local.id,
    val enabled: Boolean = true,
    val baseUrl: String = "",
    val modelName: String = "",
    val hasKey: Boolean = false,
    val timeoutSec: Int = 45,
    val maxTokens: Int = 120,
    val temperature: Float = 0.7f,
)

data class GenerateRequest(
    val systemPrompt: String,
    val userMessage: String,
    val maxTokens: Int = 120,
    val temperature: Float = 0.7f,
)

data class GenerateResult(
    val text: String,
    val source: String,
    val error: String = "",
    val latencyMs: Long = 0L,
) {
    val ok: Boolean get() = error.isBlank() && text.isNotBlank()
}

interface AiProvider {
    val kind: ProviderKind
    suspend fun generate(req: GenerateRequest): GenerateResult
    suspend fun testConnection(): GenerateResult
}
