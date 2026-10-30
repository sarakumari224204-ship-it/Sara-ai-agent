# Known Limitations — Sara AI v2

## CI vs Device
- Unit tests + ARM64 native + assembleDebug: CI
- GGUF download/load/inference on phone: device only
- WhatsApp / WA Business end-to-end RemoteInput: **real-device only** (cannot be simulated in CI)

## Model / RAM
- 6 GB RAM: prefer 0.5B–1B Q4 models; larger models risk OOM
- Default n_ctx 1024, max threads 4, max tokens 120
- One model loaded at a time

## Notifications
- Only apps that expose free-form RemoteInput can be auto-replied
- Instagram / some OEM SMS may show “reply capability limited”
- Media-only notifications without text are ignored
- Group detection is heuristic (extras + title/body patterns)

## Knowledge
- Keyword retrieval (no embeddings) — keeps RAM safe
- DOCX extraction is best-effort (word/document.xml strip)
- CSV/JSON profile parsers are tolerant but not full schema validators

## Privacy (by design)
- Customer messages never persisted
- No cloud APIs
- Owner knowledge + instructions only

## Not supported
- Cloud LLM fallback
- GPU / Vulkan inference
- Multi-model concurrent load
- Fake hard-coded AI replies
