package com.saraai.offline.core

data class KnowledgeDoc(val id: String, val title: String, val kind: String, val text: String)
data class Instruction(val id: String, val text: String, val version: Int)
data class ReplyPrefs(
    val language: String = "hi",
    val tone: String = "formal",
    val length: String = "short",
    val emoji: Boolean = false,
    val greeting: String = "Namaste",
)
data class ChunkHit(val title: String, val text: String, val score: Int)
data class Reply(val text: String, val sources: List<String>, val grounded: Boolean, val honestGap: Boolean)
sealed class OwnerCommand {
    data object None : OwnerCommand()
    data class Add(val text: String) : OwnerCommand()
    data class Delete(val query: String) : OwnerCommand()
    data class Pref(val language: String? = null, val emoji: Boolean? = null, val length: String? = null, val tone: String? = null) : OwnerCommand()
    data object ListRules : OwnerCommand()
    data class NeedFile(val message: String) : OwnerCommand()
}

private val stop = setOf("hai","hain","the","a","of","to","for","in","on","is","are","se","ka","ki","ko")

fun chunkScore(query: String, docs: List<KnowledgeDoc>, limit: Int = 3): List<ChunkHit> {
    val q = tokens(query)
    if (q.isEmpty()) return emptyList()
    val hits = mutableListOf<ChunkHit>()
    for (doc in docs) {
        val parts = chunk(doc.text)
        for (part in parts) {
            val hay = tokens(part + " " + doc.title)
            var score = 0
            for (t in q) {
                if (hay.contains(t)) score += 2
                else if (hay.any { it.contains(t) || t.contains(it) }) score += 1
            }
            if (score > 0) hits += ChunkHit(doc.title, part, score)
        }
    }
    return hits.sortedByDescending { it.score }.distinctBy { it.text.take(80) }.take(limit)
}

fun retrievalContext(message: String, docs: List<KnowledgeDoc>): String {
    val hits = chunkScore(message, docs, 3).filter { it.score >= 2 }
    if (hits.isEmpty()) return ""
    return hits.joinToString("\n") { "[${it.title}] ${it.text}" }
}

fun composeGroundedFallback(message: String, docs: List<KnowledgeDoc>, prefs: ReplyPrefs): Reply {
    val hits = chunkScore(message, docs, 2).filter { it.score >= 2 }
    val lang = resolveLang(message, prefs.language)
    if (hits.isEmpty() || (sensitive(message) && !hasFact(hits.joinToString(" ") { it.text }))) {
        return Reply(gap(lang, prefs), emptyList(), false, true)
    }
    var body = hits.joinToString(" ") { it.text }
    if (prefs.length == "short") body = body.split(Regex("(?<=[।.!?])\\s+")).take(2).joinToString(" ").ifBlank { body.take(220) }
    else body = body.take(700)
    var text = if (prefs.greeting.isNotBlank()) "${prefs.greeting}. $body" else body
    if (!prefs.emoji) text = text.replace(Regex("\\p{So}|\\p{Cs}"), "").replace(Regex("\\s+"), " ").trim()
    return Reply(text, hits.map { it.title }.distinct(), true, false)
}

fun interpretOwner(raw: String): OwnerCommand {
    val s = raw.trim()
    val low = s.lowercase()
    if (s.isEmpty()) return OwnerCommand.None
    if (Regex("(pdf|file|docx|csv)").containsMatchIn(low) && Regex("(add|import|daal)").containsMatchIn(low)) {
        return OwnerCommand.NeedFile("File chat se save nahi hoti. Knowledge screen se import karein.")
    }
    if (Regex("(yaad rakh|instruction add|note kar|save karo|rule banao)").containsMatchIn(low)) {
        val text = s.replace(Regex("(?i)^(please\\s+)?(is baat ko\\s+)?(yaad rakhna|yaad rakh|note karo|save karo)\\s*[:\\-]?\\s*"), "").trim()
        return if (text.length < 4) OwnerCommand.None else OwnerCommand.Add(text)
    }
    if (Regex("(hata do|hatao|delete instruction|remove instruction)").containsMatchIn(low)) {
        val q = s.replace(Regex("(?i).*(hata do|hatao|delete instruction|remove instruction)"), "").trim()
        return OwnerCommand.Delete(q.ifBlank { s })
    }
    if (Regex("(sirf hindi|hindi mein jawab|only hindi)").containsMatchIn(low)) return OwnerCommand.Pref(language = "hi")
    if (low.contains("hinglish")) return OwnerCommand.Pref(language = "hinglish")
    if (Regex("(english mein|only english)").containsMatchIn(low)) return OwnerCommand.Pref(language = "en")
    if (Regex("emoji").containsMatchIn(low) && Regex("(mat|nahi|band|no)").containsMatchIn(low)) return OwnerCommand.Pref(emoji = false)
    if (Regex("(chhote|short reply|jawab chhote)").containsMatchIn(low)) return OwnerCommand.Pref(length = "short")
    if (Regex("(detailed|lamba jawab)").containsMatchIn(low)) return OwnerCommand.Pref(length = "detailed")
    if (Regex("(dikhao|list karo)").containsMatchIn(low)) return OwnerCommand.ListRules
    return OwnerCommand.None
}

fun dedupeKey(pkg: String, sender: String, text: String) = "$pkg|$sender|${text.trim().take(180)}"

fun skipReason(text: String, seen: Set<String>, key: String, ownMark: String): String? {
    if (seen.contains(key)) return "duplicate"
    if (ownMark.isNotBlank() && text.contains(ownMark)) return "self"
    if (text.isBlank()) return "empty"
    return null
}

val KNOWN_MESSAGING = setOf("com.whatsapp", "com.whatsapp.w4b")

fun shouldSkipPackage(pkg: String, enabled: Set<String>): Boolean {
    if (enabled.isEmpty()) return true
    return pkg !in enabled
}

fun isGroupNotification(
    pkg: String,
    title: String,
    body: String,
    extrasKeys: Map<String, Any?> = emptyMap(),
): Boolean {
    if (pkg !in KNOWN_MESSAGING) {
        return extrasKeys["android.isGroupConversation"] == true ||
            extrasKeys["android.isGroupConversation"] == "true"
    }
    if (extrasKeys["android.isGroupConversation"] == true ||
        extrasKeys["android.isGroupConversation"] == "true"
    ) {
        return true
    }
    val t = title.trim()
    if (t.count { it == ',' } >= 2) return true
    if (Regex("\\b(group|grp|family|team)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t) &&
        !Regex("\\b(you|tum|aap)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)
    ) {
        if (Regex("^[^:]{2,40}:\\s+.+").containsMatchIn(body)) return true
    }
    if (Regex("^[\\p{L}\\p{N} ._-]{2,40}:\\s+\\S").containsMatchIn(body) &&
        t.isNotBlank() &&
        !body.startsWith(t)
    ) {
        return true
    }
    return false
}

fun parseCsv(raw: String): String {
    val lines = raw.lines().filter { it.isNotBlank() }
    if (lines.size < 2) return raw
    val headers = lines.first().split(",").map { it.trim().trim('"') }
    return lines.drop(1).joinToString("\n") { line ->
        val cells = line.split(",").map { it.trim().trim('"') }
        headers.mapIndexed { i, h -> "$h: ${cells.getOrElse(i) { "" }}" }.joinToString("; ")
    }
}

fun buildPrompt(instructions: List<Instruction>, knowledge: String, message: String, prefs: ReplyPrefs): String = """
You are Sara, the marriage bureau assistant. Reply in ${prefs.language}. Tone ${prefs.tone}. Length ${prefs.length}. Emoji ${prefs.emoji}.
Never invent fees, phone numbers, addresses or profiles. If the knowledge block does not contain the fact, say you do not have it in bureau records.
Owner rules:
${instructions.joinToString("\n") { "- ${it.text}" }}
Knowledge:
$knowledge
Customer message:
$message
Assistant:
""".trim()

private fun tokens(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").split(Regex("\\s+")).filter { it.length > 1 && it !in stop }
private fun chunk(text: String, size: Int = 420): List<String> {
    val clean = text.replace(Regex("\\s+"), " ").trim()
    if (clean.isEmpty()) return emptyList()
    val out = mutableListOf<String>()
    var i = 0
    while (i < clean.length) {
        var end = minOf(clean.length, i + size)
        if (end < clean.length) {
            val cut = clean.lastIndexOf(' ', end)
            if (cut > i + 80) end = cut
        }
        out += clean.substring(i, end).trim()
        i = end
    }
    return out.filter { it.isNotEmpty() }
}
private fun sensitive(m: String) = Regex("(phone|mobile|number|fees|fee|price|₹|link|http|address|pata)", RegexOption.IGNORE_CASE).containsMatchIn(m)
private fun hasFact(body: String) = Regex("(\\d{6,}|₹|https?://|@)").containsMatchIn(body)
private fun resolveLang(message: String, pref: String) = when (pref) {
    "hi", "en", "hinglish" -> pref
    else -> when {
        message.any { it.code in 0x0900..0x097F } -> "hi"
        Regex("\\b(kya|hai|nahi|shadi|rishta|kundli)\\b", RegexOption.IGNORE_CASE).containsMatchIn(message) -> "hinglish"
        else -> "en"
    }
}
private fun gap(lang: String, prefs: ReplyPrefs): String {
    val core = when (lang) {
        "en" -> "I do not have this in the bureau records. I will not guess fees, phone numbers or profiles."
        "hinglish" -> "Is baare mein bureau records mein jaankari nahi hai. Fees, phone ya profile guess nahi karunga."
        else -> "इस बारे में ब्यूरो के रिकॉर्ड में जानकारी नहीं है। फीस, फोन नंबर या प्रोफाइल का अनुमान नहीं लगाऊँगा।"
    }
    return if (prefs.greeting.isNotBlank()) "${prefs.greeting}. $core" else core
}
