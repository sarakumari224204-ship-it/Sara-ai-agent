package com.saraai.offline.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore("sara_prefs")

data class RuntimePrefs(
    val master: Boolean = false,
    val enabledPackages: Set<String> = setOf("com.whatsapp"),
    val language: String = "hi",
    val length: String = "short",
    val emoji: Boolean = false,
    val greeting: String = "Namaste",
    val delaySec: Int = 4,
    val ownMark: String = "SaraBureau",
    val maxTokens: Int = 120,
    val nCtx: Int = 1024,
    val modelPath: String = "",
)

object PrefsStore {
    private val master = booleanPreferencesKey("master")
    private val pkgs = stringSetPreferencesKey("pkgs")
    private val language = stringPreferencesKey("lang")
    private val length = stringPreferencesKey("len")
    private val emoji = booleanPreferencesKey("emoji")
    private val greeting = stringPreferencesKey("greet")
    private val delay = intPreferencesKey("delay")
    private val mark = stringPreferencesKey("mark")
    private val maxTokens = intPreferencesKey("max")
    private val nCtx = intPreferencesKey("nctx")
    private val model = stringPreferencesKey("model")

    suspend fun read(context: Context): RuntimePrefs {
        val p = context.dataStore.data.first()
        return RuntimePrefs(
            master = p[master] ?: false,
            enabledPackages = p[pkgs] ?: setOf("com.whatsapp"),
            language = p[language] ?: "hi",
            length = p[length] ?: "short",
            emoji = p[emoji] ?: false,
            greeting = p[greeting] ?: "Namaste",
            delaySec = p[delay] ?: 4,
            ownMark = p[mark] ?: "SaraBureau",
            maxTokens = p[maxTokens] ?: 120,
            nCtx = p[nCtx] ?: 1024,
            modelPath = p[model] ?: "",
        )
    }

    suspend fun update(context: Context, block: (RuntimePrefs) -> RuntimePrefs) {
        val cur = read(context)
        val next = block(cur)
        context.dataStore.edit {
            it[master] = next.master
            it[pkgs] = next.enabledPackages
            it[language] = next.language
            it[length] = next.length
            it[emoji] = next.emoji
            it[greeting] = next.greeting
            it[delay] = next.delaySec
            it[mark] = next.ownMark
            it[maxTokens] = next.maxTokens
            it[nCtx] = next.nCtx
            it[model] = next.modelPath
        }
    }
}
