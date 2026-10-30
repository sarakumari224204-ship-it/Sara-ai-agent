# Sara AI — Offline Marriage Bureau Agent v2.1

Package: `com.saraai.offline`

Native Kotlin + Compose + llama.cpp (CPU, ARM64) + optional cloud AI providers.

## Highlights
- **Crash-safe Models screen** (fixed DownloadManager `file://` private path crash)
- Real HTTP model download with % / MB / speed / pause / cancel / GGUF validation
- RAM-aware catalog including Qwen2.5 0.5B–3B, Llama 3.2 1B, **LFM2.5 2.6B**, **LFM2.5-VL 3B**
- AI providers: Local, Google Gemini, OpenAI, xAI Grok, NVIDIA NIM, Custom OpenAI-compatible
- Shared **AiRouter** for Chat + WhatsApp auto-reply
- API keys: Android Keystore AES-GCM (never logged / never full display)
- Customer messages never persisted as knowledge
- Cloud only used when explicitly selected; Local does not auto-load when cloud is active

## Build
```bash
./gradlew :bureau-core:test :app:assembleDebug
```

## Install (Hindi)
1. Debug APK install karein
2. Notification access ON karein
3. Models → recommended Qwen 0.5B Q4 download → Load (Local mode)
4. Settings → AI Provider select (Local / Gemini / OpenAI / Grok / NVIDIA / Custom)
5. Cloud ke liye API key paste → Save → Test connection
6. Master Auto Reply ON

See DEVICE_TEST_CHECKLIST.md and KNOWN_LIMITATIONS.md.
