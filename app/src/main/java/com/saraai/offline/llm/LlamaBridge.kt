package com.saraai.offline.llm

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin JNI bridge to llama.cpp (CPU only).
 * One model at a time. Load/generate must never run on main thread.
 */
object LlamaBridge {
    var available: Boolean = false
        private set
    var lastError: String = ""
        private set
    /** Hindi-friendly status for UI */
    var lastStatusHi: String = ""
        private set

    private val loadLock = Any()
    private val generating = AtomicBoolean(false)

    init {
        try {
            System.loadLibrary("sara_llama")
            available = true
        } catch (t: Throwable) {
            available = false
            lastError = t.message ?: "native library missing"
            lastStatusHi = "Native library load nahi hui: $lastError"
        }
    }

    fun safeCtx(requested: Int): Int = requested.coerceIn(256, 2048)
    fun safeThreads(requested: Int): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return requested.coerceIn(1, minOf(4, cores))
    }
    fun safeMaxTokens(requested: Int): Int = requested.coerceIn(16, 256)

    fun load(path: String, nCtx: Int, threads: Int): String {
        if (!available) {
            lastStatusHi = "Native library missing. APK mein arm64 lib nahi mili."
            return lastError.ifBlank { "native library missing" }
        }
        if (path.isBlank()) {
            lastError = "empty path"
            lastStatusHi = "Model path khali hai. GGUF file import karein."
            return lastError
        }
        val file = File(path)
        if (!file.exists()) {
            lastError = "file missing"
            lastStatusHi = "Model file nahi mili: $path. Dubara import karein."
            return lastError
        }
        if (file.length() < 1024) {
            lastError = "file too small"
            lastStatusHi = "File bahut chhoti hai, valid GGUF nahi lagti."
            return lastError
        }
        try {
            file.inputStream().use { ins ->
                val magic = ByteArray(4)
                if (ins.read(magic) == 4) {
                    val sig = String(magic, Charsets.US_ASCII)
                    if (sig != "GGUF") {
                        lastError = "not gguf"
                        lastStatusHi = "Yeh GGUF file nahi hai (magic=$sig). LFM 2.5 / V3B GGUF import karein."
                        return lastError
                    }
                }
            }
        } catch (t: Throwable) {
            lastError = t.message ?: "read failed"
            lastStatusHi = "Model file padhne mein error: $lastError"
            return lastError
        }

        val ctx = safeCtx(nCtx)
        val th = safeThreads(threads)
        synchronized(loadLock) {
            return try {
                if (nativeLoaded()) {
                    nativeUnload()
                }
                val result = nativeLoad(path, ctx, th)
                if (result == "ok") {
                    lastError = ""
                    lastStatusHi = "Model load ho gaya (ctx=$ctx, threads=$th)."
                } else {
                    lastError = result
                    lastStatusHi = when {
                        result.contains("could not be loaded", true) ->
                            "Model load fail. File corrupt ho sakti hai ya RAM kam hai."
                        result.contains("context", true) ->
                            "Context nahi bana. n_ctx kam karke try karein (256-1024)."
                        else -> "Model load error: $result"
                    }
                }
                result
            } catch (oom: OutOfMemoryError) {
                lastError = "OutOfMemoryError"
                lastStatusHi = "RAM kam hai. Chhota model (Q4) use karein ya n_ctx=512 try karein."
                try { nativeUnload() } catch (_: Throwable) {}
                lastError
            } catch (t: Throwable) {
                lastError = t.message ?: "load failed"
                lastStatusHi = "Model load exception: $lastError"
                lastError
            }
        }
    }

    fun generate(prompt: String, maxTokens: Int): String {
        if (!available || !nativeLoaded()) return ""
        if (!generating.compareAndSet(false, true)) {
            return ""
        }
        return try {
            synchronized(loadLock) {
                if (!nativeLoaded()) return ""
                nativeGenerate(prompt, safeMaxTokens(maxTokens))
            }
        } catch (_: OutOfMemoryError) {
            lastError = "OutOfMemoryError"
            lastStatusHi = "Generation mein RAM khatam. Model unload karke chhota ctx try karein."
            ""
        } catch (t: Throwable) {
            lastError = t.message ?: "generate failed"
            ""
        } finally {
            generating.set(false)
        }
    }

    fun unload() {
        synchronized(loadLock) {
            if (available) {
                try { nativeUnload() } catch (_: Throwable) {}
            }
            lastStatusHi = "Model unload ho gaya."
        }
    }

    fun loaded(): Boolean = available && nativeLoaded()

    fun tryReload(path: String, nCtx: Int, threads: Int): String {
        if (path.isBlank()) {
            lastStatusHi = "Koi saved model path nahi hai."
            return "no path"
        }
        return load(path, nCtx, threads)
    }

    private external fun nativeLoad(path: String, nCtx: Int, threads: Int): String
    private external fun nativeGenerate(prompt: String, maxTokens: Int): String
    private external fun nativeUnload()
    private external fun nativeLoaded(): Boolean
}
