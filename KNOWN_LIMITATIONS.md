# Known Limitations — Sara AI v2

## CI vs Device
- Unit tests + ARM64 native + assembleDebug: CI
- GGUF download/load/inference on phone: device only
- WhatsApp / WA Business RemoteInput: device only

## Technical
- Model download needs INTERNET; inference is offline after download
- Group filter is heuristic
- RemoteInput depends on OEM/WhatsApp notification actions
- DOCX extraction is lightweight XML strip (not full Apache POI)
- Profile matching is keyword/field based (no embeddings)
- Instagram/Messenger reply support varies by notification shape
- One model in memory; 6 GB: prefer ≤0.5–1B Q4

## Privacy
- Customer chat never written to Room
- Owner knowledge + instructions + profiles persist
- No cloud LLM APIs
