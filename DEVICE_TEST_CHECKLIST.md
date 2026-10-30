# Sara AI — Device Test Checklist (Redmi 12 5G / Android 15)

CI sirf unit tests + ARM64 APK build verify karta hai.
Neeche **real phone** par manually check karein. Kisi item ko PASS tabhi mark karein jab aapne khud dekha ho.

## Install

- [ ] GitHub Actions se latest `sara-ai-offline-debug-apk` download
- [ ] Unknown sources se install
- [ ] App open → crash nahi
- [ ] Notification access allow (system settings)
- [ ] POST_NOTIFICATIONS permission (Android 13+)

## Model (GGUF)

- [ ] Model tab → Import GGUF (LFM 2.5 ya V3B Q4 recommended)
- [ ] Load success message Hindi mein dikhe
- [ ] Invalid file (JPG/TXT) → clear Hindi error, crash nahi
- [ ] App force-stop → reopen → **auto-reload** saved model
- [ ] Unload → Loaded: false
- [ ] Reload saved model button kaam kare
- [ ] Free RAM ~1GB se kam ho to load fail → graceful Hindi message (no crash)

## Local chat

- [ ] Bina model: "Model load nahi hai…" — koi fake jawab nahi
- [ ] Model loaded: Hindi / Hinglish / English sawal → jawab aaye
- [ ] Knowledge import (TXT) ke baad grounded jawab
- [ ] Phone/fees sawal jab knowledge mein nahi → invent nahi, gap message

## Owner instructions

- [ ] "Is baat ko yaad rakhna: …" → save
- [ ] List / delete instruction
- [ ] "sirf hindi" / "emoji mat" prefs

## WhatsApp auto-reply (real device only)

- [ ] Apps tab: WhatsApp + WhatsApp Business ON
- [ ] Auto reply master ON
- [ ] Dusre phone se **1:1** message → notification → RemoteInput reply aaye
- [ ] Same message dubara → **duplicate reply nahi**
- [ ] Group chat notification → **reply nahi**
- [ ] WhatsApp Business 1:1 → reply try
- [ ] RemoteInput action nahi ho to service crash nahi
- [ ] Customer message Room DB / files mein **save nahi** (Privacy)

## Low RAM / stability

- [ ] Generation ke dauran app ANR nahi
- [ ] Empty model output → silent skip (galat reply nahi)
- [ ] Notification listener disable/enable → service recover

## Privacy spot-check

- [ ] `adb shell run-as com.saraai.offline` → DB mein sirf instructions/documents
- [ ] Koi customer chat table nahi
- [ ] Koi network call AI ke liye nahi (airplane mode mein bhi local chat)

**Note:** Jo checkbox aapne tick nahi kiya, usse "verified" mat likhna.
