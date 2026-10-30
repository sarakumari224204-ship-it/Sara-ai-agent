package com.saraai.offline.notify

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.saraai.offline.ai.AiRouter
import com.saraai.offline.ai.ProviderKind
import com.saraai.offline.ai.ProviderPrefs
import com.saraai.offline.core.Instruction
import com.saraai.offline.core.KnowledgeDoc
import com.saraai.offline.core.Profile
import com.saraai.offline.core.ReplyPrefs
import com.saraai.offline.core.buildPrompt
import com.saraai.offline.core.dedupeKey
import com.saraai.offline.core.isGroupNotification
import com.saraai.offline.core.retrievalContext
import com.saraai.offline.core.sanitizeReply
import com.saraai.offline.core.skipReason
import com.saraai.offline.core.shouldSkipPackage
import com.saraai.offline.data.BureauDb
import com.saraai.offline.data.PrefsStore
import com.saraai.offline.llm.LlamaBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.LinkedHashMap

class SaraNotificationListener : NotificationListenerService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)

    private val seenLock = Any()
    private val seen = object : LinkedHashMap<String, Boolean>(96, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > 80
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        scope.launch { handle(sbn) }
    }

    private suspend fun handle(sbn: StatusBarNotification) {
        val prefs = PrefsStore.read(this)
        if (!prefs.master) return
        if (shouldSkipPackage(sbn.packageName, prefs.enabledPackages)) return

        val extras = sbn.notification.extras ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val body = if (text.isNotBlank()) text else bigText
        if (body.isBlank()) return

        val groupExtras = mutableMapOf<String, Any?>()
        if (extras.containsKey("android.isGroupConversation")) {
            groupExtras["android.isGroupConversation"] = extras.getBoolean("android.isGroupConversation", false)
        }
        if (!prefs.allowGroups && isGroupNotification(sbn.packageName, title, body, groupExtras)) return

        val key = dedupeKey(sbn.packageName, title, body)
        val alreadySeen = synchronized(seenLock) { seen.containsKey(key) }
        val skip = skipReason(body, if (alreadySeen) setOf(key) else emptySet(), key, prefs.ownMark)
        if (skip != null) return

        val actions = sbn.notification.actions ?: return
        val action = actions.firstOrNull { act ->
            act.remoteInputs?.any { it.allowFreeFormInput } == true
        } ?: return
        val remoteInputs = action.remoteInputs ?: return
        val input = remoteInputs.firstOrNull { it.allowFreeFormInput } ?: return

        val providerSettings = ProviderPrefs.read(this)
        val kind = ProviderKind.fromId(providerSettings.activeKind)
        if (kind is ProviderKind.Local && !LlamaBridge.loaded()) return

        val db = BureauDb.open(this)
        val docs = db.dao().documents().map {
            KnowledgeDoc(it.id, it.title, it.kind, it.body, it.category, it.enabled)
        }
        val profiles = db.dao().profiles().map {
            Profile(
                it.id, it.name, it.gender, it.age, it.city, it.state, it.education, it.occupation,
                it.religion, it.caste, it.maritalStatus, it.height, it.income, it.expectations,
                it.family, it.contact, it.raw, it.enabled,
            )
        }
        val rules = db.dao().instructions().map {
            Instruction(it.id, it.text, it.version, it.enabled, it.priority)
        }
        val knowledge = retrievalContext(body, docs, profiles)
        val prompt = buildPrompt(
            rules,
            knowledge,
            body,
            ReplyPrefs(
                language = prefs.language,
                length = prefs.length,
                emoji = prefs.emoji,
                greeting = prefs.greeting,
            ),
        )

        val timeoutMs = (providerSettings.timeoutSec.coerceIn(15, 90) * 1000L)
        val reply = try {
            withTimeoutOrNull(timeoutMs) {
                val result = AiRouter.generate(
                    this@SaraNotificationListener,
                    systemPrompt = "You are Sara, a helpful marriage-bureau assistant. Follow the instructions in the user message. Prefer Hindi when appropriate.",
                    userMessage = prompt,
                    maxTokens = prefs.maxTokens,
                )
                if (!result.ok) "" else sanitizeReply(result.text)
            }.orEmpty()
        } catch (_: OutOfMemoryError) {
            ""
        } catch (_: Exception) {
            ""
        }
        if (reply.isBlank()) return

        if (prefs.delaySec > 0) {
            delay(prefs.delaySec * 1000L)
        }

        val fill = Intent()
        val bundle = Bundle()
        bundle.putCharSequence(input.resultKey, reply.take(500))
        RemoteInput.addResultsToIntent(arrayOf(input), fill, bundle)
        try {
            action.actionIntent.send(this, 0, fill)
            synchronized(seenLock) {
                seen[key] = true
            }
        } catch (_: Exception) {
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        scope.launch {
            try {
                val provider = ProviderPrefs.read(this@SaraNotificationListener)
                if (provider.activeKind != ProviderKind.Local.id) return@launch
                val prefs = PrefsStore.read(this@SaraNotificationListener)
                if (prefs.modelPath.isNotBlank() && !LlamaBridge.loaded()) {
                    val file = java.io.File(prefs.modelPath)
                    if (file.exists() && file.length() > 1024) {
                        val nCtx = prefs.nCtx.coerceIn(256, 2048)
                        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                        LlamaBridge.load(prefs.modelPath, nCtx, threads)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        job.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
