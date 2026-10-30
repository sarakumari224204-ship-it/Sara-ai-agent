# Known Limitations — Sara AI v2.1

- CI: unit tests + APK only; device for GGUF/WA/API
- Keyword retrieval (no embeddings)
- LFM2.5-VL is vision model; text path only in-app
- 3B on 6GB needs low n_ctx; may OOM with clear error
- Download: HTTP to app-private models/ (not broken file:// DM)
- API keys: Keystore AES-GCM; never logged
- Gemini: generativelanguage.googleapis.com v1beta
- NIM: OpenAI-compatible integrate.api.nvidia.com/v1 default
- API WhatsApp auto-reply requires explicit confirmation
