# Sara AI v2.1 — Device Test Checklist

CI verifies unit tests + ARM64 APK only. Mark PASS only after real device verification.

## Models crash fix
1. [ ] Open Models — no crash
2. [ ] Empty catalog / offline still opens
3. [ ] Filter 4/6/8GB + Hindi
4. [ ] LFM2.5 2.6B and VL-3B listed

## Download
5. [ ] Real progress % MB speed
6. [ ] Pause / Resume / Cancel
7. [ ] GGUF validation after complete
8. [ ] Load / Unload / Delete (unload if loaded)
9. [ ] Restart recovers file

## API
10. [ ] Mode Local / API / Auto
11. [ ] Save key (masked)
12. [ ] Test Connection real
13. [ ] Chat API + fallback
14. [ ] WhatsApp API requires confirmation

## Messaging / privacy
15. [ ] WA + WA Business local reply
16. [ ] Customer msgs not in knowledge
