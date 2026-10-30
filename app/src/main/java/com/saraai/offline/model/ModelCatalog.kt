package com.saraai.offline.model

data class ModelSpec(
    val id: String,
    val name: String,
    val family: String,
    val params: String,
    val quant: String,
    val sizeMb: Int,
    val minRamGb: Int,
    val recommendedRamGb: Int,
    val hindiOk: Boolean,
    val hinglishOk: Boolean,
    val englishOk: Boolean,
    val recommended: Boolean,
    val url: String,
    val fileName: String,
    val sha256: String? = null,
    val contextHint: Int = 1024,
    val note: String = "",
)

object ModelCatalog {
    val models: List<ModelSpec> = listOf(
        ModelSpec("qwen25-05b-q4km", "Qwen2.5 0.5B Instruct", "Qwen2.5", "0.5B", "Q4_K_M", 491, 4, 4, true, true, true, true,
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            "qwen2.5-0.5b-instruct-q4_k_m.gguf", contextHint = 1024, note = "Best for 4–6 GB devices"),
        ModelSpec("qwen25-05b-q8", "Qwen2.5 0.5B Instruct", "Qwen2.5", "0.5B", "Q8_0", 665, 4, 6, true, true, true, false,
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q8_0.gguf",
            "qwen2.5-0.5b-instruct-q8_0.gguf"),
        ModelSpec("lfm25-350m-q4km", "LFM2.5 350M", "LFM2.5", "350M", "Q4_K_M", 229, 4, 4, false, false, true, false,
            "https://huggingface.co/mradermacher/LFM2.5-350M-GGUF/resolve/main/LFM2.5-350M.Q4_K_M.gguf",
            "LFM2.5-350M.Q4_K_M.gguf", contextHint = 512, note = "Very small; limited Hindi"),
        ModelSpec("llama32-1b-q4km", "Llama 3.2 1B Instruct", "Llama 3.2", "1B", "Q4_K_M", 770, 4, 6, true, true, true, false,
            "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            "Llama-3.2-1B-Instruct-Q4_K_M.gguf", contextHint = 512),
        ModelSpec("qwen25-15b-q4km", "Qwen2.5 1.5B Instruct", "Qwen2.5", "1.5B", "Q4_K_M", 986, 6, 6, true, true, true, false,
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            "qwen2.5-1.5b-instruct-q4_k_m.gguf", contextHint = 512),
        ModelSpec("lfm25-26b-q4km", "LFM2.5 2.6B", "LFM2.5", "2.6B", "Q4_K_M", 1670, 6, 8, true, true, true, false,
            "https://huggingface.co/LiquidAI/LFM2.5-2.6B-GGUF/resolve/main/LFM2.5-2.6B-Q4_K_M.gguf",
            "LFM2.5-2.6B-Q4_K_M.gguf", contextHint = 512, note = "Official LiquidAI text GGUF (~3B-class). Prefer 8 GB."),
        ModelSpec("lfm25-vl-3b-q4km", "LFM2.5-VL 3B (text backbone)", "LFM2.5", "3B", "Q4_K_M", 1680, 6, 8, true, true, true, false,
            "https://huggingface.co/bartowski/LiquidAI_LFM2.5-VL-3B-GGUF/resolve/main/LiquidAI_LFM2.5-VL-3B-Q4_K_M.gguf",
            "LiquidAI_LFM2.5-VL-3B-Q4_K_M.gguf", contextHint = 512, note = "Vision model; text-only use OK. Needs ~8 GB free for comfort."),
        ModelSpec("qwen25-3b-q4km", "Qwen2.5 3B Instruct", "Qwen2.5", "3B", "Q4_K_M", 1920, 6, 8, true, true, true, false,
            "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/Qwen2.5-3B-Instruct-Q4_K_M.gguf",
            "Qwen2.5-3B-Instruct-Q4_K_M.gguf", contextHint = 512, note = "Heavy on 6 GB — use low context."),
    )

    fun byId(id: String): ModelSpec? = models.find { it.id == id }

    fun filter(
        ramGb: Int? = null,
        hindi: Boolean = false,
        hinglish: Boolean = false,
        english: Boolean = false,
        downloadedOnly: Boolean = false,
        isDownloaded: (ModelSpec) -> Boolean = { false },
        smallest: Boolean = false,
        bestQuality: Boolean = false,
    ): List<ModelSpec> {
        var list = models.toList()
        if (ramGb != null) list = list.filter { it.minRamGb <= ramGb }
        if (hindi) list = list.filter { it.hindiOk }
        if (hinglish) list = list.filter { it.hinglishOk }
        if (english) list = list.filter { it.englishOk }
        if (downloadedOnly) list = list.filter { isDownloaded(it) }
        if (smallest) list = list.sortedBy { it.sizeMb }
        if (bestQuality) list = list.sortedByDescending { quantRank(it.quant) * 10000 + it.sizeMb }
        return list
    }

    private fun quantRank(q: String): Int = when {
        q.contains("Q8") -> 8
        q.contains("Q6") -> 6
        q.contains("Q5") -> 5
        q.contains("Q4") -> 4
        q.contains("Q3") -> 3
        else -> 0
    }

    fun recommendForRam(deviceRamGb: Int): ModelSpec {
        return models.filter { it.minRamGb <= deviceRamGb && it.recommended }
            .minByOrNull { it.sizeMb }
            ?: models.filter { it.minRamGb <= deviceRamGb }.minByOrNull { it.sizeMb }
            ?: models.first()
    }
}
