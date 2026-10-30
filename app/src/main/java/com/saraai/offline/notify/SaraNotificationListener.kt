package com.saraai.offline.notify

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.saraai.offline.core.KnowledgeDoc
import com.saraai.offline.core.ReplyPrefs
import com.saraai.offline.core.buildPrompt
import com.saraai.offline.core.dedupeKey
import com.saraai.offline.core.retrievalContext
import com.saraai.offline.core.skipReason
import com.saraai.offline.data.BureauDb
import com.saraai.offline.llm.LlamaBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class SaraNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seen = LinkedHashSet<String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        scope.launch { handle(sbn) }
    }

    private suspend fun handle(sbn: StatusBarNotification) {
        val prefs = PrefsStore.read(this)
        if (!prefs.master) return
        if (sbn.packageName !in prefs.enabledPackages) return
        val extras = sbn.notification.extras
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        if (text.isBlank()) return
        val key = dedupeKey(sbn.packageName, title, text)
        val skip = skipReason(text, seen, key, prefs.ownMark)
        if (skip != null) return
        val action = sbn.notification.actions?.firstOrNull { act ->
            act.remoteInputs?.any { it.allowFreeFormInput } == true
        } ?: return
        val input = action.remoteInputs.first { it.allowFreeFormInput }
        if (!LlamaBridge.loaded()) return
        val db = BureauDb.open(this)
        val docs = db.dao().documents().map { KnowledgeDoc(it.id, it.title, it.kind, it.body) }
        val rules = db.dao().instructions().map { com.saraai.offline.core.Instruction(it.id, it.text, it.version) }
        val knowledge = retrievalContext(text, docs)
        val prompt = buildPrompt(rules, knowledge, text, ReplyPrefs(language = prefs.language, length = prefs.length, emoji = prefs.emoji, greeting = prefs.greeting))
        val reply = LlamaBridge.generate(prompt, prefs.maxTokens).trim()
        if (reply.isBlank()) return
        if (prefs.delaySec > 0) Thread.sleep(prefs.delaySec * 1000L)
        val fill = Intent()
        val bundle = Bundle()
        bundle.putCharSequence(input.resultKey, reply.take(500))
        RemoteInput.addResultsToIntent(arrayOf(input), fill, bundle)
        try {
            action.actionIntent.send(this, 0, fill)
            seen += key
            while (seen.size > 80) seen.remove(seen.first())
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        runBlocking { }
        super.onDestroy()
    }
}
