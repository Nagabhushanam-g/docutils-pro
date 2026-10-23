# DocUtils Pro — Android starter

Native Kotlin/Jetpack Compose app. Image-to-PDF with optional watermark and JPEG recompression; AdMob test IDs only. PDF merge/page-count from the earlier starter are not in this revised UI.

## Build
Upload the contents of this folder into a GitHub repository. The Actions workflow builds a debug APK. This ZIP contains source, not a compiled APK. The workflow bootstraps the Gradle wrapper JAR if missing.

## Important
- AdMob uses Google's **test** app/unit IDs. Replace only after account setup and policy compliance.
- The purchase ID `docutils_remove_ads_lifetime` must be created in Play Console. Purchases are NOT fully implemented: purchase acknowledgement and entitlement validation must be completed before launch.
- ML Kit scanner and Coil dependencies are included, but the scanner UI is not implemented.
- The current picker allows up to 100 images, but free users are limited to five after selection.
- Test PDF generation, file viewing, memory use on large images, billing and ads before production release.
