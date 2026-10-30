package com.saraai.offline.core

data class KnowledgeDoc(
    val id: String,
    val title: String,
    val kind: String,
    val text: String,
    val category: String = "other",
    val enabled: Boolean = true,
)

data class Profile(
    val id: String,
    val name: String,
    val gender: String = "",
    val age: Int = 0,
    val city: String = "",
    val state: String = "",
    val education: String = "",
    val occupation: String = "",
    val religion: String = "",
    val caste: String = "",
    val maritalStatus: String = "",
    val height: String = "",
    val income: String = "",
    val expectations: String = "",
    val family: String = "",
    val contact: String = "",
    val raw: String = "",
    val enabled: Boolean = true,
) {
    fun toText(): String = buildString {
        append("Profile $id: $name")
        if (gender.isNotBlank()) append(", $gender")
        if (age > 0) append(", age $age")
        if (city.isNotBlank()) append(", $city")
        if (state.isNotBlank()) append(", $state")
        if (education.isNotBlank()) append(", $education")
        if (occupation.isNotBlank()) append(", $occupation")
    }
}

data class Instruction(
    val id: String,
    val text: String,
    val version: Int,
    val enabled: Boolean = true,
    val priority: Int = 0,
)

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

private val stop = setOf("hai","hain","the","a","of","to","for","in","on","is","are","se","ka","ki","ko","and","or","mein","ke","ek")

val KNOWN_MESSAGING = setOf(
    "com.whatsapp", "com.whatsapp.w4b",
    "org.telegram.messenger", "org.telegram.messenger.web",
    "com.facebook.orca", "com.instagram.android",
    "org.thoughtcrime.securesms", "com.google.android.apps.messaging",
    "com.android.mms", "com.samsung.android.messaging",
    "com.skype.raider", "com.viber.voip", "com.discord", "jp.naver.line.android",
)

fun shouldSkipPackage(pkg: String, enabled: Set<String>): Boolean {
    if (enabled.isEmpty()) return true
    return pkg !in enabled
}

fun isMessagingPackage(pkg: String): Boolean = pkg in KNOWN_MESSAGING

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
    ) return true
    val t = title.trim()
    if (t.count { it == ',' } >= 2) return true
    if (Regex("\\b(group|grp|family|team)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t) &&
        !Regex("\\b(you|tum|aap)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)
    ) {
        if (Regex("^[^:]{2,40}:\\s+.+").containsMatchIn(body)) return true
    }
    if (Regex("^[\\p{L}\\p{N} ._-]{2,40}:\\s+\\S").containsMatchIn(body) &&
        t.isNotBlank() && !body.startsWith(t)
    ) return true
    return false
}

fun dedupeKey(pkg: String, sender: String, text: String) = "$pkg|$sender|${text.trim().take(180)}"

fun skipReason(text: String, seen: Set<String>, key: String, ownMark: String): String? {
    if (seen.contains(key)) return "duplicate"
    if (ownMark.isNotBlank() && text.contains(ownMark)) return "self"
    if (text.isBlank()) return "empty"
    return null
}

fun chunkScore(query: String, docs: List<KnowledgeDoc>, limit: Int = 3): List<ChunkHit> {
    val q = tokens(query)
    if (q.isEmpty()) return emptyList()
    val hits = mutableListOf<ChunkHit>()
    for (doc in docs.filter { it.enabled }) {
        val parts = chunk(doc.text)
        for (part in parts) {
            val hay = tokens(part + " " + doc.title + " " + doc.category)
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

fun matchProfiles(query: String, profiles: List<Profile>, limit: Int = 4): List<Profile> {
    val q = query.lowercase()
    val ageWanted = Regex("(\\d{2})\\s*(saal|sal|year|yrs|yr)").find(q)?.groupValues?.get(1)?.toIntOrNull()
    val genderWanted = when {
        Regex("(ladki|girl|female|beti)").containsMatchIn(q) -> "female"
        Regex("(ladka|boy|male|beta)").containsMatchIn(q) -> "male"
        else -> null
    }
    return profiles.filter { it.enabled }.map { p ->
        var score = 0
        val blob = p.toText().lowercase()
        for (t in tokens(query)) if (blob.contains(t)) score += 2
        if (ageWanted != null && p.age > 0) {
            val d = kotlin.math.abs(p.age - ageWanted)
            if (d <= 2) score += 5 else if (d <= 5) score += 2
        }
        if (genderWanted != null) {
            val g = p.gender.lowercase()
            if (genderWanted == "female" && (g.contains("f") || g.contains("female") || g.contains("girl"))) score += 4
            if (genderWanted == "male" && (g.contains("m") || g.contains("male") || g.contains("boy"))) score += 4
        }
        if (q.contains("lucknow") || q.contains("\u0932\u0916\u0928\u090a")) {
            if (p.city.lowercase().contains("lucknow")) score += 4
        }
        p to score
    }.filter { it.second > 0 }.sortedByDescending { it.second }.take(limit).map { it.first }
}

fun retrievalContext(message: String, docs: List<KnowledgeDoc>, profiles: List<Profile> = emptyList()): String {
    val hits = chunkScore(message, docs, 3).filter { it.score >= 2 }
    val matched = matchProfiles(message, profiles, 4)
    val parts = mutableListOf<String>()
    if (matched.isNotEmpty()) parts += matched.joinToString("\n") { "[profile] ${it.toText()}" }
    if (hits.isNotEmpty()) parts += hits.joinToString("\n") { "[${it.title}] ${it.text}" }
    return parts.joinToString("\n")
}

fun composeGroundedFallback(
    message: String,
    docs: List<KnowledgeDoc>,
    prefs: ReplyPrefs,
    profiles: List<Profile> = emptyList(),
): Reply {
    val hits = chunkScore(message, docs, 2).filter { it.score >= 2 }
    val matched = matchProfiles(message, profiles, 3)
    val lang = resolveLang(message, prefs.language)
    val bodyParts = mutableListOf<String>()
    if (matched.isNotEmpty()) bodyParts += matched.joinToString(" ") { it.toText() }
    if (hits.isNotEmpty()) bodyParts += hits.joinToString(" ") { it.text }
    if (bodyParts.isEmpty() || (sensitive(message) && !hasFact(bodyParts.joinToString(" ")))) {
        return Reply(gap(lang, prefs), emptyList(), false, true)
    }
    var body = bodyParts.joinToString(" ")
    if (prefs.length == "short") body = body.split(Regex("(?<=[\u0964.!?])\\s+")).take(2).joinToString(" ").ifBlank { body.take(220) }
    else body = body.take(700)
    var text = if (prefs.greeting.isNotBlank()) "${prefs.greeting}. $body" else body
    if (!prefs.emoji) text = text.replace(Regex("\\p{So}|\\p{Cs}"), "").replace(Regex("\\s+"), " ").trim()
    return Reply(text, (matched.map { "profile:${it.id}" } + hits.map { it.title }).distinct(), true, false)
}

fun interpretOwner(raw: String): OwnerCommand {
    val s = raw.trim()
    val low = s.lowercase()
    if (s.isEmpty()) return OwnerCommand.None
    if (Regex("(pdf|file|docx|csv)").containsMatchIn(low) && Regex("(add|import|daal)").containsMatchIn(low))
        return OwnerCommand.NeedFile("File chat se save nahi hoti. Knowledge screen se import karein.")
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

fun parseCsv(raw: String): String {
    val lines = raw.lines().filter { it.isNotBlank() }
    if (lines.size < 2) return raw
    val headers = lines.first().split(",").map { it.trim().trim('"') }
    return lines.drop(1).joinToString("\n") { line ->
        val cells = line.split(",").map { it.trim().trim('"') }
        headers.mapIndexed { i, h -> "$h: ${cells.getOrElse(i) { "" }}" }.joinToString("; ")
    }
}

fun parseCsvProfiles(raw: String): List<Profile> {
    val lines = raw.lines().filter { it.isNotBlank() }
    if (lines.size < 2) return emptyList()
    val headers = lines.first().split(",").map { it.trim().trim('"').lowercase() }
    fun idx(vararg names: String) = headers.indexOfFirst { h -> names.any { h.contains(it) } }
    val iName = idx("name", "naam")
    val iGender = idx("gender", "sex")
    val iAge = idx("age", "umar")
    val iCity = idx("city", "shahar")
    return lines.drop(1).mapIndexedNotNull { i, line ->
        val cells = line.split(",").map { it.trim().trim('"') }
        fun cell(ix: Int) = if (ix >= 0) cells.getOrElse(ix) { "" } else ""
        val name = cell(iName).ifBlank { return@mapIndexedNotNull null }
        Profile(id = "csv-$i", name = name, gender = cell(iGender), age = cell(iAge).filter { it.isDigit() }.toIntOrNull() ?: 0, city = cell(iCity), raw = line)
    }
}

fun parseJsonProfiles(raw: String): List<Profile> {
    val objs = Regex("\\{([^{}]*)\\}").findAll(raw).map { it.value }.toList()
    return objs.mapIndexed { i, obj ->
        fun field(key: String): String {
            val m = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(obj)
                ?: Regex("\"$key\"\\s*:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(obj)
            return m?.groupValues?.getOrNull(1).orEmpty()
        }
        Profile(id = field("id").ifBlank { "json-$i" }, name = field("name").ifBlank { field("naam") }, gender = field("gender"), age = field("age").toIntOrNull() ?: 0, city = field("city"), raw = obj)
    }.filter { it.name.isNotBlank() }
}

fun sanitizeReply(raw: String, maxLen: Int = 500): String {
    var t = raw.trim()
    if (t.isEmpty()) return ""
    t = t.replace(Regex("(?m)^(Assistant:|Sara:|AI:)\\s*"), "")
    t = t.replace(Regex("</?s>"), "")
    t = t.replace(Regex("\\s+"), " ").trim()
    return t.take(maxLen)
}

fun buildPrompt(instructions: List<Instruction>, knowledge: String, message: String, prefs: ReplyPrefs): String {
    val active = instructions.filter { it.enabled }.sortedByDescending { it.priority }
    return """
You are Sara, the marriage bureau assistant. Reply in ${prefs.language}. Tone ${prefs.tone}. Length ${prefs.length}. Emoji ${prefs.emoji}.
Never invent fees, phone numbers, addresses or profiles. If the knowledge block does not contain the fact, say you do not have it in bureau records.
Owner rules:
${active.joinToString("\n") { "- ${it.text}" }}
Knowledge:
$knowledge
Customer message:
$message
Assistant:
""".trim()
}

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
private fun sensitive(m: String) = Regex("(phone|mobile|number|fees|fee|price|\u20b9|link|http|address|pata)", RegexOption.IGNORE_CASE).containsMatchIn(m)
private fun hasFact(body: String) = Regex("(\\d{6,}|\u20b9|https?://|@)").containsMatchIn(body)
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
        else -> "\u0907\u0938 \u092c\u093e\u0930\u0947 \u092e\u0947\u0902 \u092c\u094d\u092f\u0942\u0930\u094b \u0915\u0947 \u0930\u093f\u0915\u0949\u0930\u094d\u0921 \u092e\u0947\u0902 \u091c\u093e\u0928\u0915\u093e\u0930\u0940 \u0928\u0939\u0940\u0902 \u0939\u0948\u0964"
    }
    return if (prefs.greeting.isNotBlank()) "${prefs.greeting}. $core" else core
}
