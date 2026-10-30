package com.saraai.offline.apps

import android.content.Context
import android.graphics.drawable.Drawable
import com.saraai.offline.core.KNOWN_MESSAGING
import com.saraai.offline.core.isMessagingPackage

data class MessagingAppInfo(
    val packageName: String,
    val label: String,
    val installed: Boolean,
    val icon: Drawable? = null,
    val replyLikely: Boolean,
)

object MessagingAppScanner {
    private val labels = mapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "org.telegram.messenger" to "Telegram",
        "org.telegram.messenger.web" to "Telegram (Web)",
        "com.facebook.orca" to "Messenger",
        "com.instagram.android" to "Instagram",
        "org.thoughtcrime.securesms" to "Signal",
        "com.google.android.apps.messaging" to "Messages (Google)",
        "com.android.mms" to "Messages (AOSP)",
        "com.samsung.android.messaging" to "Samsung Messages",
        "com.skype.raider" to "Skype",
        "com.viber.voip" to "Viber",
        "com.discord" to "Discord",
        "jp.naver.line.android" to "LINE",
    )

    fun scan(context: Context): List<MessagingAppInfo> {
        val pm = context.packageManager
        return KNOWN_MESSAGING.map { pkg ->
            val installed = try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
            val label = if (installed) {
                try {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(ai)?.toString() ?: labels[pkg] ?: pkg
                } catch (_: Exception) {
                    labels[pkg] ?: pkg
                }
            } else labels[pkg] ?: pkg
            val icon = if (installed) try {
                pm.getApplicationIcon(pkg)
            } catch (_: Exception) {
                null
            } else null
            val replyLikely = pkg in setOf(
                "com.whatsapp", "com.whatsapp.w4b",
                "org.telegram.messenger", "org.telegram.messenger.web",
                "org.thoughtcrime.securesms",
                "com.google.android.apps.messaging",
                "com.facebook.orca",
            )
            MessagingAppInfo(pkg, label, installed, icon, replyLikely)
        }.sortedWith(compareByDescending<MessagingAppInfo> { it.installed }.thenBy { it.label })
    }

    fun isRelevant(pkg: String): Boolean = isMessagingPackage(pkg)
}
