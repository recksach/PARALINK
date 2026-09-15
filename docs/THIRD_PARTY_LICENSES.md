# THIRD-PARTY LICENSES

PARALINK bundles or depends on the following components. All are open source.

## Runtime dependencies

| Component | License |
|---|---|
| Jetpack Compose / Material 3 | Apache-2.0 |
| AndroidX Core / Activity / Lifecycle | Apache-2.0 |
| Material Icons (extended) | Apache-2.0 |
| Kotlin standard library | Apache-2.0 |
| Android SDK platform libraries | Apache-2.0 (framework) |

## Bundled offline language resources

The bundled offline translation resources (`assets/smt/uk-de.txt`,
`assets/offline-corpus.json`, `assets/language-manifest.json`) were authored for this
project. They are MIT-licensed as part of the PARALINK repository unless noted otherwise.

The Ukrainian ↔ German and related bilingual glossary content was hand-compiled for the
PARALINK emergency-messaging use case and contains only general-purpose vocabulary.

## Future neural models

If larger neural models (e.g. Bergamot / Argos / NLLB class) are added later, they must be
included only under their own licenses (typically CC-BY or the respective model license)
and this file must be updated with the exact attribution and license text for each
packaged model. No such models are currently bundled.

## Third-party terms

Android and Google trademarks remain the property of their respective owners.