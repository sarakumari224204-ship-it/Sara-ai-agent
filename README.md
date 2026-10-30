# Sara AI — Offline Marriage Bureau Agent v2.1

Package: `com.saraai.offline`

Native Kotlin + Compose + llama.cpp (CPU, ARM64) + optional cloud AI providers.

## Highlights
- **Crash-safe Models screen** (fixed DownloadManager `file://` private path crash)
- Real HTTP model download with % / MB / speed / pause / cancel / GGUF validation
- RAM-aware catalog including Qwen2.5 0.5B–3B, Llama 3.2 1B, **LFM2.5 2.6B**, **LFM2.5-VL 3B**
- AI providers: Local, Google Gemini, NVIDIA NIM, OpenAI-compatible
- API keys: Android Keystore AES-GCM (never logged / never full display)
- Chat + WhatsApp auto-reply share the same provider pipeline
- Customer messages never persisted as knowledge

## Build
```bash
./gradlew :bureau-core:test :app:assembleDebug
```

## Install (Hindi)
1. Debug APK install karein
2. Notification access ON karein
3. Models → recommended Qwen 0.5B Q4 download → Load
4. Settings → AI mode Local (offline) ya API
5. Master Auto Reply ON

See DEVICE_TEST_CHECKLIST.md and KNOWN_LIMITATIONS.md.
