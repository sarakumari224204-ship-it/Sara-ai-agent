# Sara AI — Offline Marriage Bureau Agent v2.0

Package: `com.saraai.offline`

Fully offline Android Marriage Bureau AI assistant.

- Local GGUF via llama.cpp (CPU, ARM64 only)
- WhatsApp + WhatsApp Business auto-reply via NotificationListenerService + RemoteInput
- Owner knowledge (TXT/PDF/CSV/JSON/DOCX) + structured marriage profiles
- Owner instructions that actually shape the LLM prompt
- Customer messages are **never** stored permanently
- No cloud AI, no OpenAI/Gemini, no analytics

## Target device

Redmi 12 5G · 6 GB RAM · ARM64 · Android 13–15

Recommended model: **Qwen2.5-0.5B-Instruct Q4_K_M** (~491 MB) — downloadable in-app from Hugging Face.

## Screens

| Tab | Purpose |
|-----|---------|
| Home | Status dashboard (model, auto-reply, knowledge counts) |
| Chat | Real local-LLM test console (same pipeline as WA replies) |
| Apps | Dynamic messaging-app scan (WA / WA Business / Telegram / …) |
| Knowledge | Manual entry, paste, PDF/TXT/CSV/JSON/DOCX import, instructions, What Sara Knows |
| Models | Catalog + real DownloadManager download + load/unload |
| Settings | Master switch, groups, privacy notice, diagnostics |

## Privacy

Customer chat text exists only in memory while generating a reply. It is never written to Room or exported.

## Build

```bash
git clone --depth 1 --branch b3962 https://github.com/ggml-org/llama.cpp.git third_party/llama.cpp
./gradlew :bureau-core:test :app:assembleDebug
```

GitHub Actions uploads `sara-ai-offline-debug-apk` on every push to `main`.

## Hindi install (device)

1. APK install karein
2. Notification access ON karein (Settings → Sara AI)
3. Models tab → recommended Qwen download → Load
4. Knowledge / Instructions add karein
5. Chat se test karein
6. Apps → WhatsApp / WA Business Auto Reply ON
7. Dusre phone se message bhejkar auto-reply verify karein

See `DEVICE_TEST_CHECKLIST.md` and `KNOWN_LIMITATIONS.md`.
