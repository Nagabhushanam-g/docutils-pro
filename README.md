# DocUtils Pro

Android/Kotlin Jetpack Compose document utility. Image-to-PDF, conservative automatic image cropping with optional manual adjustments, local PDF/file management, camera scanning, and basic offline Word/Excel conversion. Advanced Office layouts may differ from the originals.

## CI builds

- **Build Debug APK** (push and manual): creates `DocUtils-Pro-Build-N.apk`. Uses a pinned permanent **test** signing key stored in GitHub Actions secret `DOCUTILS_DEBUG_KEYSTORE_BASE64`; APK certificate is verified before upload. Debug APKs from build #169 onward are intended for in-place updates with the same key.
- **Build Play Store AAB** (manual only): requires a **separate** Play upload key and production AdMob IDs. Builds `DocUtils-Pro-Play-N.aab` for manual upload to Google Play internal testing. The workflow does NOT publish to Play.
- The project targets Android 16 (API 36) for Play submission and uses Google Play Billing 8 and Google UMP consent handling.

## Before Google Play publication

Read **[Play release checklist](docs/PLAY_RELEASE_CHECKLIST.md)** and review the **[privacy policy draft](docs/PRIVACY_POLICY_DRAFT.md)**. Play setup, production ads, completed privacy declarations, a real publisher contact, and purchase testing are required. Do not publish the privacy draft with placeholders.

AdMob is intentionally configured with Google test IDs for development when production IDs are not supplied; the Play bundle workflow refuses to build with test IDs. The `docutils_remove_ads_lifetime` Play product must exist in Play Console for purchasing to work.

## Security and documents

Files are processed locally for the core converter and saved into app-visible Android media collections. Some optional Google services (AdMob, Play Billing, Google scanner) use network services. Never commit credentials or keystore files. Release and test signing keys are intentionally separate.
