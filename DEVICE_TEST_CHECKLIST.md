# Sara AI v2 — Device Test Checklist (Redmi 12 5G)

CI verifies unit tests + ARM64 APK only. Mark PASS only after real device verification.

1. [ ] Install debug APK
2. [ ] Open app (Home dashboard loads)
3. [ ] Grant notification access
4. [ ] Models → Download recommended Qwen2.5 0.5B Q4_K_M (or import local GGUF)
5. [ ] Load model — status “Model load ho gaya”
6. [ ] Open Chat
7. [ ] Send Hindi question (e.g. “नमस्ते, आपके पास शादी के लिए प्रोफाइल हैं?”)
8. [ ] Verify real AI answer appears (not hardcoded)
9. [ ] Knowledge → Add manual entry + Confirm
10. [ ] Preview / Enable / Disable / Delete knowledge
11. [ ] Add instruction (“हमेशा हिंदी में जवाब दो”)
12. [ ] Chat again — instruction affects reply
13. [ ] Apps → WhatsApp installed YES, Auto Reply ON
14. [ ] Send WhatsApp message from another account
15. [ ] Notification detected
16. [ ] Local AI generates answer
17. [ ] RemoteInput sends answer into WhatsApp chat
18. [ ] Repeat for WhatsApp Business
19. [ ] Duplicate message — no second reply
20. [ ] Group message — skipped when Allow Groups OFF
21. [ ] Restart phone
22. [ ] Model auto-reload (or Reload saved model)
23. [ ] Auto-reply still works after restart
24. [ ] Confirm customer messages are NOT in Knowledge / Room

**Real-device WhatsApp testing pending** until physical Redmi 12 5G verification.
