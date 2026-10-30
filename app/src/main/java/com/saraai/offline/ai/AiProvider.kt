package com.saraai.offline.ai

/**
 * Abstraction over local llama.cpp and cloud HTTP APIs.
 * Chat and WhatsApp auto-reply both go through [AiRouter].
 */
sealed class ProviderKind(val id: String, val label: String) {
    data object Local : ProviderKind("local", "Local (offline)")
    data object Gemini : ProviderKind("gemini", "Google Gemini")
    data object NvidiaNim : ProviderKind("nvidia_nim", "NVIDIA NIM")
    data object OpenAiCompat : ProviderKind("openai_compat", "OpenAI-compatible")
}

data class ProviderConfig(
    val kind: String = ProviderKind.Local.id,
    val enabled: Boolean = true,
    val baseUrl: String = "",
    val modelName: String = "",
    val hasKey: Boolean = false,
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
) {
    val ok: Boolean get() = error.isBlank() && text.isNotBlank()
}

interface AiProvider {
    val kind: ProviderKind
    suspend fun generate(req: GenerateRequest): GenerateResult
    suspend fun testConnection(): GenerateResult
}
