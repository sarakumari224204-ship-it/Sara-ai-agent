package com.saraai.offline.llm

object LlamaBridge {
    var available: Boolean = false
        private set
    var lastError: String = ""
        private set

    init {
        try {
            System.loadLibrary("sara_llama")
            available = true
        } catch (t: Throwable) {
            available = false
            lastError = t.message ?: "native library missing"
        }
    }

    fun load(path: String, nCtx: Int, threads: Int): String {
        if (!available) return lastError.ifBlank { "native library missing" }
        return try {
            nativeLoad(path, nCtx, threads)
        } catch (t: Throwable) {
            t.message ?: "load failed"
        }
    }

    fun generate(prompt: String, maxTokens: Int): String {
        if (!available || !nativeLoaded()) return ""
        return nativeGenerate(prompt, maxTokens)
    }

    fun unload() {
        if (available) nativeUnload()
    }

    fun loaded(): Boolean = available && nativeLoaded()

    private external fun nativeLoad(path: String, nCtx: Int, threads: Int): String
    private external fun nativeGenerate(prompt: String, maxTokens: Int): String
    private external fun nativeUnload()
    private external fun nativeLoaded(): Boolean
}
