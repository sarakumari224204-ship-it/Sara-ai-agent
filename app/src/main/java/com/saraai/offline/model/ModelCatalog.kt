package com.saraai.offline.model

/**
 * Curated GGUF models with real HTTPS download URLs (Hugging Face resolve links).
 * Recommended for Redmi 12 5G (6 GB RAM): Qwen2.5 0.5B Q4_K_M (~491 MB).
 */
data class ModelSpec(
    val id: String,
    val name: String,
    val quant: String,
    val sizeMb: Int,
    val ramNote: String,
    val hindiOk: Boolean,
    val recommended: Boolean,
    val url: String,
    val fileName: String,
)

object ModelCatalog {
    val models: List<ModelSpec> = listOf(
        ModelSpec(
            id = "qwen25-05b-q4km",
            name = "Qwen2.5 0.5B Instruct",
            quant = "Q4_K_M",
            sizeMb = 491,
            ramNote = "6 GB RAM ke liye BEST choice",
            hindiOk = true,
            recommended = true,
            url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        ),
        ModelSpec(
            id = "qwen25-05b-q8",
            name = "Qwen2.5 0.5B Instruct",
            quant = "Q8_0",
            sizeMb = 665,
            ramNote = "6 GB par chalega, zyada quality",
            hindiOk = true,
            recommended = false,
            url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q8_0.gguf",
            fileName = "qwen2.5-0.5b-instruct-q8_0.gguf",
        ),
        ModelSpec(
            id = "llama32-1b-q4km",
            name = "Llama 3.2 1B Instruct",
            quant = "Q4_K_M",
            sizeMb = 770,
            ramNote = "6 GB par tight — n_ctx=512 rakhein",
            hindiOk = true,
            recommended = false,
            url = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        ),
    )
}
