# Sara AI v2 — Device Test Checklist (Redmi 12 5G)

CI verifies unit tests + ARM64 APK only. Mark PASS only after real device verification.

1. [ ] Install debug APK
2. [ ] Open app — Home dashboard loads
3. [ ] Grant notification access
4. [ ] Models → Download Qwen2.5 0.5B Q4 (or Import GGUF)
5. [ ] Download progress 0–100% visible
6. [ ] Load model → success Hindi status
7. [ ] Chat → send Hindi question → **real LLM answer** visible
8. [ ] Generating indicator works
9. [ ] Knowledge → manual title/content save
10. [ ] Import PDF/TXT/CSV with preview confirm
11. [ ] Instructions add/enable/disable
12. [ ] Instruction affects Chat reply
13. [ ] Apps → only messaging apps (no Camera)
14. [ ] WhatsApp + WhatsApp Business listed with installed status
15. [ ] Enable WhatsApp auto-reply
16. [ ] External account sends WhatsApp 1:1 → reply via RemoteInput
17. [ ] WhatsApp Business same test
18. [ ] Duplicate message → no second reply
19. [ ] Group message → no reply (if groups off)
20. [ ] Restart phone → model auto-reload
21. [ ] Privacy: no customer messages in Room

**Real-device WhatsApp testing pending unless you mark the boxes above.**
