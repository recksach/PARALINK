# PARALINK

Standalone-first Android communication mesh with a built-in offline Language Engine.

## What this is

A real, buildable Kotlin + Jetpack Compose application — not a UI mockup:

- Android target SDK 36 / min SDK 26
- persistent local identity via Android Keystore
- Wi-Fi Direct peer discovery + capability detection
- local P2P TCP transport on Wi-Fi Direct group networks
- end-to-end encryption (ECDH P-256 + HKDF + AES-GCM), relay nodes never see plaintext
- local persistent chat + voice message history
- radar / network UI, offline wallet ledger foundation
- **Language Engine**: 15 UI languages, offline language detection,
  bundled offline Statistical Machine Translation (phrase-based SMT + target
  language model) for uk↔de (and messaging vocabulary), PhraseStore used as
  fast-fallback/cache, translation cache, PTT recorder + WAV playback,
  STT/TTS platform hooks
- GitHub Actions build workflow (clean → unit tests → APKs → AAB → GitHub Release)

## Build on GitHub Actions (recommended)

The repository builds automatically on every push. Open the **Actions** tab and pick the
**Android Build** workflow run; APKs + AAB are attached as artifacts and also published to
the **Releases** page when a tag is pushed.

The workflow:

1. `./gradlew clean`
2. `./gradlew test`
3. `./gradlew :app:assembleDebug :app:assembleRelease :app:bundleRelease`

A release keystore is generated during the build so the release APK is signed and installable.

## Build locally

Requires JDK 17 and the Android SDK (compile SDK 36).

```bash
./gradlew clean
./gradlew test
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Expected APKs (two flavors):

- `app/build/outputs/apk/global/debug/app-global-debug.apk`
- `app/build/outputs/apk/full/debug/app-full-debug.apk`
- `app/build/outputs/apk/global/release/app-global-release.apk`
- `app/build/outputs/apk/full/release/app-full-release.apk`

## Install

1. Download `app-global-debug.apk` (signed, ready to install) from the build artifacts or Release.
2. Enable “Install unknown apps” for your browser/file manager.
3. Install on two or more devices.
4. Open PARALINK on both, grant the Nearby devices / Bluetooth permission, and keep the Network screen open.
5. Pair them over Bluetooth (Network → “Scan Bluetooth” → tap the phone) or over the same Wi-Fi
   network (“Create own network” on one, “Join” on the other). Devices then establish a direct
   local TCP/BT session with E2E encryption.

## Offline translation test (INTERNET OFF)

> Note: the app itself needs no internet, but with internet OFF the phones must be on the same
> Wi-Fi network or paired via Bluetooth — otherwise there is no route between them.

Set Phone A UI language to **Ukrainian**, Phone B to **German**. With Internet and mobile
data OFF, A sends a free-form Ukrainian phrase (not present in the phrase store):

**A:** `Я вчера встретил человека, который сказал, что приедет завтра.`

**B** shows:

```
Я вчера встретил человека, который сказал, что приедет завтра.
[der gesagt hat, dass er morgen kommt …]  ← offline translation (original kept)
```

If a particular language pair has no bundled model, the app honestly shows
**Offline translation unavailable** — chat, voice and the mesh keep working.

## Implementation status

### Implemented (in source)
- Localization: 15 locales (en, uk, ru, pl, de, fr, es, it, pt, tr, ar, hi, ja, ko, zh), 64 keys each
- Offline language detection (character n-gram profiles)
- Offline translation cascade: StatisticalMTEngine (uk→de, phrase-based SMT + German LM)
  → OfflinePhraseTranslationEngine (bundled phrase/word corpus, EN pivot)
- Translation cache (SHA-256 keyed, LRU, SharedPreferences-backed)
- LanguageManager: app/communication/target language, auto-detect, display mode, voice/PTT/captions switches, first-run language dialog
- Chat rendering: original always kept, translation below, per-message Original button
- Real PTT: AudioRecord → WAV → mesh VOICE packet → WavPlayer playback
- Mesh E2E: HELLO public-key flooding (TTL 3), pairwise ECDH+HKDF session keys, AES-GCM
- Relay: opaque packet forward with TTL, broadcast dedup, never decrypts foreign payloads
- Voice messages persisted and playable

### Unit-tested (JVM tests in `./gradlew test`)
- LanguageDetectorTest (uk/de/en detection, short-input uncertainty)
- StatisticalMTEngineTest (free-form uk→de sentence, unknown→UNAVAILABLE, unsupported pair)
- OfflinePhraseTranslationEngineTest (corpus load, blank, phrase fallback)
- TranslationCacheTest (store/fetch, LRU eviction, key isolation)
- PacketCodecTest (round-trip incl. `|` in payload, malformed rejection, relay/broadcast/TTL)
- WavCodecTest (PCM↔WAV round-trip, durations)
- HkdfTest (RFC 5869 Test Case 1 vector)

### TODO / not yet verified
- Physically tested on two real Android phones (required: discovery, connect, E2E chat,
  relay A→B→C with isolation, PTT, voice, translations, INTERNET OFF run)
- Instrumented tests on device (`androidTest`)
- Full neural MT models (Bergamot/Argos/NLLB class) — current bundled engine is a real
  statistical phrase-based SMT engine + LM; broader pair coverage requires larger models
  that must respect their upstream licenses if added
- Actual STT transcription and TTS synthesis (platform hooks exist, packaged corpora do not)
- File transfer, live calls, world-map/census screens
- Interactive language-mesh settings reactivity (switches persist; UI refresh on tab switch)

## Core principle

The core communication and translation layers do not depend on internet, cloud APIs,
Firebase, a license server, or a central backend. Phones connect directly over Bluetooth
or a shared Wi-Fi network; internet is only an optional transport when devices are reachable.
The mesh keeps working as long as any route between devices exists.

## Third-party licenses

See [docs/THIRD_PARTY_LICENSES.md](docs/THIRD_PARTY_LICENSES.md).

## License

MIT — see [LICENSE](LICENSE).