package com.saraai.offline.ai

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.providerDataStore by preferencesDataStore("sara_provider_prefs")

/**
 * Non-secret provider settings (kind, base URL, model name, timeout).
 * API keys stay in [SecureApiKeys] (Android Keystore).
 */
data class ProviderSettings(
    val activeKind: String = ProviderKind.Local.id,
    val timeoutSec: Int = 45,
    val cloudMaxTokens: Int = 120,
    val temperature: Float = 0.7f,
    val geminiModel: String = "gemini-2.0-flash",
    val openaiModel: String = "gpt-4o-mini",
    val grokModel: String = "grok-3",
    val nvidiaModel: String = "meta/llama-3.1-8b-instruct",
    val customModel: String = "",
    val customBaseUrl: String = "",
    val openaiBaseUrl: String = "https://api.openai.com/v1",
    val grokBaseUrl: String = "https://api.x.ai/v1",
    val nvidiaBaseUrl: String = "https://integrate.api.nvidia.com/v1",
) {
    fun modelFor(providerId: String): String = when (providerId) {
        ProviderKind.Gemini.id -> geminiModel
        ProviderKind.OpenAi.id -> openaiModel
        ProviderKind.Grok.id -> grokModel
        ProviderKind.NvidiaNim.id -> nvidiaModel
        ProviderKind.OpenAiCompat.id -> customModel
        else -> ""
    }

    fun baseUrlFor(providerId: String): String = when (providerId) {
        ProviderKind.OpenAi.id -> openaiBaseUrl
        ProviderKind.Grok.id -> grokBaseUrl
        ProviderKind.NvidiaNim.id -> nvidiaBaseUrl
        ProviderKind.OpenAiCompat.id -> customBaseUrl
        else -> ""
    }
}

object ProviderPrefs {
    private val activeKind = stringPreferencesKey("active_kind")
    private val timeoutSec = intPreferencesKey("timeout_sec")
    private val cloudMaxTokens = intPreferencesKey("cloud_max_tokens")
    private val temperature = stringPreferencesKey("temperature")
    private val geminiModel = stringPreferencesKey("gemini_model")
    private val openaiModel = stringPreferencesKey("openai_model")
    private val grokModel = stringPreferencesKey("grok_model")
    private val nvidiaModel = stringPreferencesKey("nvidia_model")
    private val customModel = stringPreferencesKey("custom_model")
    private val customBaseUrl = stringPreferencesKey("custom_base_url")
    private val openaiBaseUrl = stringPreferencesKey("openai_base_url")
    private val grokBaseUrl = stringPreferencesKey("grok_base_url")
    private val nvidiaBaseUrl = stringPreferencesKey("nvidia_base_url")

    suspend fun read(context: Context): ProviderSettings {
        val p = context.providerDataStore.data.first()
        return ProviderSettings(
            activeKind = p[activeKind] ?: ProviderKind.Local.id,
            timeoutSec = p[timeoutSec] ?: 45,
            cloudMaxTokens = p[cloudMaxTokens] ?: 120,
            temperature = p[temperature]?.toFloatOrNull() ?: 0.7f,
            geminiModel = p[geminiModel] ?: "gemini-2.0-flash",
            openaiModel = p[openaiModel] ?: "gpt-4o-mini",
            grokModel = p[grokModel] ?: "grok-3",
            nvidiaModel = p[nvidiaModel] ?: "meta/llama-3.1-8b-instruct",
            customModel = p[customModel] ?: "",
            customBaseUrl = p[customBaseUrl] ?: "",
            openaiBaseUrl = p[openaiBaseUrl] ?: "https://api.openai.com/v1",
            grokBaseUrl = p[grokBaseUrl] ?: "https://api.x.ai/v1",
            nvidiaBaseUrl = p[nvidiaBaseUrl] ?: "https://integrate.api.nvidia.com/v1",
        )
    }

    suspend fun update(context: Context, block: (ProviderSettings) -> ProviderSettings) {
        val cur = read(context)
        val next = block(cur)
        context.providerDataStore.edit {
            it[activeKind] = next.activeKind
            it[timeoutSec] = next.timeoutSec
            it[cloudMaxTokens] = next.cloudMaxTokens
            it[temperature] = next.temperature.toString()
            it[geminiModel] = next.geminiModel
            it[openaiModel] = next.openaiModel
            it[grokModel] = next.grokModel
            it[nvidiaModel] = next.nvidiaModel
            it[customModel] = next.customModel
            it[customBaseUrl] = next.customBaseUrl
            it[openaiBaseUrl] = next.openaiBaseUrl
            it[grokBaseUrl] = next.grokBaseUrl
            it[nvidiaBaseUrl] = next.nvidiaBaseUrl
        }
    }
}
