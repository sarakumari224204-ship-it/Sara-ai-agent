# Known limitations (v2.1)

- Live cloud API calls require valid user-supplied keys; CI has no credentials.
- Local GGUF speed depends on device RAM/CPU; 6GB phones should prefer ≤1–1.5B Q4 models.
- WhatsApp reply uses notification RemoteInput; UI changes in WhatsApp may break reply actions.
- Cloud providers process the full prompt on their servers when selected.
- No automatic fallback from cloud to Local unless user switches provider.
