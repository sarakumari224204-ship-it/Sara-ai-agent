package com.saraai.offline.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    @Test fun threadSafeDedupeSet() {
        val seen = Collections.synchronizedSet(mutableSetOf<String>())
        val pool = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(200)
        val keys = (1..50).map { dedupeKey("com.whatsapp", "User$it", "msg $it") }
        repeat(200) { i ->
            pool.execute {
                try {
                    val k = keys[i % keys.size]
                    synchronized(seen) { if (!seen.contains(k)) seen.add(k) }
                } finally { latch.countDown() }
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(50, seen.size)
    }

    @Test fun concurrentSkipReasonNoCrash() {
        val key = dedupeKey("com.whatsapp", "A", "hello")
        val seen = ConcurrentHashMap.newKeySet<String>()
        val pool = Executors.newFixedThreadPool(4)
        val errors = AtomicInteger(0)
        val latch = CountDownLatch(100)
        repeat(100) {
            pool.execute {
                try {
                    val r = skipReason("hello", seen, key, "SaraBureau")
                    if (r == null) seen.add(key)
                } catch (_: Exception) { errors.incrementAndGet() }
                finally { latch.countDown() }
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(0, errors.get())
        assertTrue(seen.contains(key))
    }

    @Test fun whatsappPackages() {
        assertTrue("com.whatsapp" in KNOWN_MESSAGING)
        assertTrue("com.whatsapp.w4b" in KNOWN_MESSAGING)
        assertFalse(shouldSkipPackage("com.whatsapp", setOf("com.whatsapp", "com.whatsapp.w4b")))
        assertTrue(shouldSkipPackage("com.android.camera", setOf("com.whatsapp")))
        assertFalse(isMessagingPackage("com.android.camera2"))
        assertTrue(isMessagingPackage("org.telegram.messenger"))
    }

    @Test fun groupMessageFilter() {
        assertTrue(isGroupNotification("com.whatsapp", "Family", "hello", mapOf("android.isGroupConversation" to true)))
        assertFalse(isGroupNotification("com.whatsapp", "Ravi", "Namaste, fees kitni hai?"))
        assertFalse(isGroupNotification("com.whatsapp.w4b", "Customer A", "Shadi ke liye profile chahiye"))
    }

    @Test fun skipSelfAndEmpty() {
        assertEquals("self", skipReason("Reply by SaraBureau ok", emptySet(), "k1", "SaraBureau"))
        assertEquals("empty", skipReason("   ", emptySet(), "k2", "SaraBureau"))
        assertNull(skipReason("Normal customer message", emptySet(), "k3", "SaraBureau"))
    }

    @Test fun profileMatchLucknow() {
        val profiles = listOf(
            Profile("1", "Asha", gender = "female", age = 28, city = "Lucknow", education = "MBA"),
            Profile("2", "Ravi", gender = "male", age = 30, city = "Delhi"),
        )
        val hit = matchProfiles("lucknow 28 girl profile", profiles)
        assertTrue(hit.any { it.name == "Asha" })
    }

    @Test fun csvProfiles() {
        val csv = "name,gender,age,city\nNeha,female,26,Jaipur\nAmit,male,29,Kanpur"
        val ps = parseCsvProfiles(csv)
        assertEquals(2, ps.size)
        assertEquals("Neha", ps[0].name)
    }

    @Test fun jsonProfiles() {
        val json = "{ \"name\": \"Pooja\", \"age\": 27, \"city\": \"Noida\", \"gender\": \"female\" }"
        val ps = parseJsonProfiles(json)
        assertTrue(ps.isNotEmpty())
        assertEquals("Pooja", ps[0].name)
    }

    @Test fun sanitizeEmpty() {
        assertEquals("", sanitizeReply("   "))
        assertTrue(sanitizeReply("Assistant: Namaste ji").contains("Namaste"))
    }

    @Test fun instructionInPrompt() {
        val p = buildPrompt(
            listOf(Instruction("1", "Always reply in Hindi", 1)),
            "Bureau in Lucknow",
            "hello",
            ReplyPrefs(),
        )
        assertTrue(p.contains("Always reply in Hindi"))
        assertTrue(p.contains("Lucknow"))
    }
}
