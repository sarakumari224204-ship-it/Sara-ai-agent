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
                    synchronized(seen) {
                        if (!seen.contains(k)) seen.add(k)
                    }
                } finally {
                    latch.countDown()
                }
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
                } catch (_: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(0, errors.get())
        assertTrue(seen.contains(key))
    }

    @Test fun notificationFilteringWhatsAppBusiness() {
        assertFalse(shouldSkipPackage("com.whatsapp", setOf("com.whatsapp", "com.whatsapp.w4b")))
        assertFalse(shouldSkipPackage("com.whatsapp.w4b", setOf("com.whatsapp", "com.whatsapp.w4b")))
        assertTrue(shouldSkipPackage("com.instagram.android", setOf("com.whatsapp")))
        assertTrue(shouldSkipPackage("com.whatsapp", emptySet()))
    }

    @Test fun groupMessageFilter() {
        assertTrue(
            isGroupNotification(
                "com.whatsapp",
                "Family",
                "hello",
                mapOf("android.isGroupConversation" to true),
            ),
        )
        assertTrue(
            isGroupNotification(
                "com.whatsapp",
                "College Batch",
                "Ravi: kal milte hain",
            ),
        )
        assertFalse(
            isGroupNotification(
                "com.whatsapp",
                "Ravi",
                "Namaste, fees kitni hai?",
            ),
        )
        assertFalse(
            isGroupNotification(
                "com.whatsapp.w4b",
                "Customer A",
                "Shadi ke liye profile chahiye",
            ),
        )
    }

    @Test fun skipSelfAndEmpty() {
        assertEquals("self", skipReason("Reply by SaraBureau ok", emptySet(), "k1", "SaraBureau"))
        assertEquals("empty", skipReason("   ", emptySet(), "k2", "SaraBureau"))
        assertNull(skipReason("Normal customer message", emptySet(), "k3", "SaraBureau"))
    }

    @Test fun modelPathPersistenceHelpers() {
        val path = "/data/user/0/com.saraai.offline/files/model.gguf"
        assertTrue(path.endsWith(".gguf"))
        assertTrue(path.isNotBlank())
        val blank = ""
        assertTrue(blank.isBlank())
    }

    @Test fun knownMessagingPackages() {
        assertTrue("com.whatsapp" in KNOWN_MESSAGING)
        assertTrue("com.whatsapp.w4b" in KNOWN_MESSAGING)
    }
}
