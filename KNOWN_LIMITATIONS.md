# Known Limitations — Sara AI Offline Marriage Bureau

## CI vs Device

| Area | CI (GitHub Actions) | Real phone |
|------|---------------------|------------|
| `:bureau-core:test` unit tests | Yes | — |
| ARM64 native `libsara_llama.so` compile | Yes (llama.cpp b3962) | — |
| `assembleDebug` APK | Yes | Install & run |
| GGUF load / inference | **No** | Required |
| WhatsApp / WA Business RemoteInput reply | **No** | Required |
| Group / duplicate notification behaviour | Logic unit-tested only | Required |
| Low-RAM OOM on 6GB device | **No** | Required |

## Technical limits

1. **Models**: User must import GGUF (LFM 2.5 / V3B style). App downloads nothing. Magic bytes `GGUF` checked.
2. **RAM**: `n_ctx` clamped 256–2048, threads ≤4, CPU only (`n_gpu_layers=0`). 6GB phones: prefer Q4 ≤1–2B parameter models.
3. **Retrieval**: Keyword chunk score only — no embeddings / vector DB (by design for offline RAM).
4. **Group chats**: Heuristic filter; may miss some WA layouts or false-positive rare 1:1 patterns.
5. **RemoteInput**: Only works when the notification exposes a free-form reply action. Some OEM / WA versions strip it.
6. **Package visibility**: App list capped at 80 launchable apps; WA + WA Business always offered if installed.
7. **DOCX**: Not extracted yet (PDF/TXT/CSV/JSON path exists).
8. **No cloud**: No API keys, no analytics, no customer message persistence.

## Privacy (intentional)

- Customer WhatsApp text is used only in-memory to build one reply, then discarded.
- Room DB holds **owner instructions** and **owner knowledge documents** only.

## Phase 1 fixes applied (this release)

- `Thread.sleep` → cancellable `delay`
- Thread-safe notification dedupe
- Proper coroutine cancellation in `onDestroy`
- WhatsApp Business package support
- Group / self / duplicate filters
- Model auto-reload on Activity + Listener connect
- Hindi error messages for missing/invalid/OOM model
- Generation timeout (45s) + OOM catch
- Safe n_ctx / threads / maxTokens clamps
