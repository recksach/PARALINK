# PARALINK Language Engine

## Goal

Every phrase a user types in the mesh chat must be translatable locally, with the original
text always preserved, and no mandatory internet, server, or post-install model download.
When a translation for a specific language pair is not covered by the bundled offline
model, the app honestly reports that offline translation is unavailable — communication
never breaks.

## Architecture

```
Incoming text
   │
   ▼
LanguageManager.processIncoming(text)
   ├─ LanguageDetector            (character n-gram profiles → Language)
   ├─ TranslationCache            (SHA-256 key, LRU, SharedPreferences store)
   └─ CascadeTranslationEngine    (ordered delegates)
        ├─ StatisticalMTEngine        ← PRIMARY: real phrase-based SMT + LM (uk→de)
        └─ OfflinePhraseTranslationEngine ← FALLBACK: bundled phrase/word corpus, EN pivot
```

- `StatisticalMTEngine` is a genuine offline statistical machine-translation engine
  (pure Kotlin): a bilingual phrase table with log-probabilities, a German bigram
  language model with unigram backoff, greedy longest-phrase segmentation and a beam
  decoder. It is **not** a phrase lookup. Model: `app/src/main/assets/smt/uk-de.txt`.
- `OfflinePhraseTranslationEngine` (PhraseStore) is deliberately kept as a
  fallback/cache, not the only translator: it handles exact stored phrases and word
  glosses, including EN-pivot for pairs without a direct dictionary.
- `TranslationCache` avoids re-translating identical incoming texts.

## Model format (`assets/smt/uk-de.txt`)

TSV model file (avoids runtime JSON so the engine is trivially testable on the JVM):

```
#pair uk	de
#lex
я	ich	-0.4
я вчера встретил	ich habe gestern	-1.0
...
#bigram
ich habe	-1.1
...
#unigram
ich	-2.4
...
```

- `#lex`: source phrase (1–4 words) → target phrase, `logP` (log probability).
  Longer source phrases encode word-order movement (e.g. `что приедет завтра` →
  `dass er morgen kommt`).
- `#bigram`: target-side bigram `logP`. `#unigram`: target-side unigram `logP`
  (unknown word fallback `-8.0`).
- Decoding: beam search over phrase segmentation maximizing
  `Σ phrase-logP + Σ target-LM(bigram, unigram-backoff)`.
- Decision thresholds: `avgLog >= -1.7` and full coverage → `TRANSLATED`;
  `>= -3.2` with ≥50% coverage → `PARTIAL`; otherwise `UNAVAILABLE`.

## Honesty paths

- Pair without a bundled model → `UNAVAILABLE` → UI shows *Offline translation unavailable*.
- Model covers pair only partially → `PARTIAL` is rendered as best effort; full transcript
  of what happened is surfaced in the Tests column of the README.
- Speech: `AndroidSpeechRecognitionEngine` reports `available=false` (no packaged
  acoustic model), `AndroidTextToSpeechEngine` hooks the platform engine at runtime.
  Voice translations use the same local engine when the text is recoverable.

## Extending

To add a new pair: author a `smt/<src>-<tgt>.txt` model (phrases + LM), put it in
`assets/smt/`, and register it in `MainActivity.buildLanguageManager()`. Larger models
(Bergamot/Argos-size) may be added later under their respective licenses — see
`THIRD_PARTY_LICENSES.md`.

## Files

- `app/src/main/assets/smt/uk-de.txt` — bundled SMT model
- `app/src/main/assets/offline-corpus.json` — phrase/word corpus (fallback)
- `app/src/main/assets/language-manifest.json` — per-language capability manifest
- `app/src/main/res/values*/strings.xml` — 15 locales, 64 keys each
- `app/src/main/java/com/paralink/app/core/language/*` — engine, detector, cache, manager
- `app/src/test/java/com/paralink/app/*Test.kt` — JVM tests