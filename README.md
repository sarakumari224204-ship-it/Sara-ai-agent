# Sara AI — Offline Marriage Bureau

Package: `com.saraai.offline`

Native Kotlin + Jetpack Compose app. Debug APK is built by GitHub Actions (`.github/workflows/android-debug-apk.yml`) because this repo vendors llama.cpp at tag `b3962` only during CI.

## Install (Hindi)

1. Actions tab se latest **Android debug APK** run kholen.
2. Artifact `sara-ai-offline-debug-apk` download karke Redmi par install karein (unknown sources).
3. App → Notification access allow karein.
4. Model screen se apni **LFM 2.5** ya **V3B** GGUF file import karein. App koi doosra model download nahi karti.
5. Apps screen par installed apps on karein. Reply tabhi jaata hai jab us notification mein RemoteInput reply action ho.

## Privacy

Customer messages Room ya logs mein save nahi hote. Sirf owner instructions aur knowledge documents Room database mein rehte hain. Bina loaded model ke chatbot fake jawab nahi deta.

## Tests

`:bureau-core:test` JVM unit tests (retrieval, no invented phone, owner commands, dedupe). Device notification and GGUF inference are not claimed from CI.
