package com.saraai.offline.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BureauEngineTest {
    private val doc = KnowledgeDoc("1", "Bureau rules", "text", "Rishta tabhi suggest hota hai jab kundli aur umar range office register se match karein.")

    @Test fun searchFindsTopic() {
        assertTrue(chunkScore("kundli match kaise", listOf(doc)).isNotEmpty())
    }

    @Test fun noInventedPhone() {
        val r = composeGroundedFallback("aapka phone number kya hai", listOf(doc), ReplyPrefs())
        assertTrue(r.honestGap)
    }

    @Test fun groundsRule() {
        val r = composeGroundedFallback("kundli aur umar ka rule", listOf(doc), ReplyPrefs())
        assertTrue(r.grounded)
    }

    @Test fun ownerAdd() {
        val cmd = interpretOwner("Is baat ko yaad rakhna: customer se izzat se baat karna")
        assertTrue(cmd is OwnerCommand.Add)
    }

    @Test fun dedupeAndCsv() {
        val key = dedupeKey("com.whatsapp", "Ravi", "hello")
        assertEquals("duplicate", skipReason("hello", setOf(key), key, "SaraBureau"))
        assertEquals("self", skipReason("ok SaraBureau", emptySet(), "k", "SaraBureau"))
        assertTrue(parseCsv("name,city\nAsha,Jaipur").contains("Asha"))
    }
}
