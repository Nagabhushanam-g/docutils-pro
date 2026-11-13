# DocUtils Pro — Google Play release checklist (November 2026)

This document describes **work remaining**; a debug APK success does NOT mean the app is published or compliant.

## Already implemented in the repository
- Android package: `com.docutility.pro`; target/compile Android API 36.
- Offline image-to-PDF, automatic and optional manual cropping, saved document management, Office-to-PDF basic conversion.
- AdMob integration with the Google UMP consent request before ad loading, and a privacy-options control when required.
- One-time ad-free Play Billing 8 product handling: `docutils_remove_ads_lifetime`, ownership restoration, pending transactions, acknowledgement.
- Debug testing APKs are signed with the stable test keystore. This key is **not** the Play upload key.
- `Build Play Store AAB` is a manual GitHub Actions workflow; it does not publish to Play.

## Required owner setup (before invoking the release workflow)
1. Verify your **Google Play Console developer account** and create the DocUtils Pro app using package `com.docutility.pro`.
2. Create a dedicated *upload key*, **not** the debug/test signing key. Securely store its keystore, passwords and alias. Do not put them in Git or the app.
3. In GitHub Repository Settings → Secrets and variables → Actions, add these **secrets**:
   - `DOCUTILS_RELEASE_KEYSTORE_BASE64`: Base64 of the binary upload keystore (one line).
   - `DOCUTILS_RELEASE_STORE_PASSWORD`
   - `DOCUTILS_RELEASE_KEY_ALIAS`
   - `DOCUTILS_RELEASE_KEY_PASSWORD`
   Never share keystore bytes or secret values in chat, logs, screenshots or public files.
4. The production AdMob App ID and two ad-unit IDs supplied by the owner have been configured as **release-workflow fallbacks** (commit `6e1e258`). GitHub Actions variables may override them if explicitly configured:
   - `DOCUTILS_ADMOB_APP_ID`: production app ID (uses `~`)
   - `DOCUTILS_ADMOB_BANNER_ID`: production banner unit (uses `/`)
   - `DOCUTILS_ADMOB_INTERSTITIAL_ID`: production interstitial unit (uses `/`)
   - `DOCUTILS_PRIVACY_POLICY_URL`: **required**, public, finalized HTTPS webpage linked from the in-app **More → Privacy policy** menu.
   The workflow rejects Google's test IDs, checks the ad ID format/publisher, and refuses a release if required settings are missing. Confirm that the first supplied unit was created with the **Banner** format and the second with the **Interstitial** format in AdMob.
5. Configure and publish a Google-certified consent message in AdMob Privacy & messaging, test it for affected regions and verify the in-app Privacy button when required.
6. Create and activate the non-consumable **one-time product** `docutils_remove_ads_lifetime` in Play Console. Test pending and completed purchase, restore and acknowledgement using license testers on an internal-test track. Do not charge real users until this is verified.
7. Publish a **public, HTTPS privacy policy** linked from the Play listing and accessible via **More → Privacy policy** inside the release app. Review and customize the draft in `docs/PRIVACY_POLICY_DRAFT.md`, inserting a real publisher name and contact email.
8. Prepare screenshots, app icon, high-res icon, feature graphic, short and full descriptions. Complete App content, data safety, target audience, ads declaration, content rating, and contact details. Submit for closed/internal testing as required by your account.
9. Verify APIs/SDK behavior on Android 16 devices, crash-free conversion, document cropping, file sharing and no UI overlap. Check that all media processing works on real devices; complex Office file layout is explicitly a basic conversion.
10. Run **Actions → Build Play Store AAB → Run workflow**. Download `DocUtils-Play-Release-AAB`; inspect it and upload it manually to a Play Console internal testing release. This workflow does not publish automatically.

## Signing-key example (run locally on a trusted computer)
Create a dedicated upload key and back it up offline. Choose strong values and a validity beyond October 22, 2033:

```sh
keytool -genkeypair -v -keystore docutils-play-upload.jks -storetype PKCS12 -keyalg RSA -keysize 3072 -validity 10000 -alias docutils_upload
base64 -w 0 docutils-play-upload.jks > upload-keystore-base64.txt
```

For Windows PowerShell, replace the second command with `[Convert]::ToBase64String([IO.File]::ReadAllBytes("docutils-play-upload.jks")) | Set-Content -NoNewline upload-keystore-base64.txt`. Copy the output to the encrypted GitHub secret, then delete any temporary plaintext files stored on shared computers. Store a secure backup of the keystore and passwords separately.

## Versioning and install compatibility
- GitHub debug workflow: versionCode tracks its run number, and the debug signing fingerprint is pinned.
- Play release workflow: versionCode starts at 100001 and increases per manual release workflow run.
- Google Play App Signing uses Google-managed app signing and your dedicated upload key. **A Play-installed build may not update a debug-signed APK directly.** Keep debug APK testing separate from store rollout.

## Known publication blockers
- Production AdMob IDs supplied and configured in the Play release workflow; confirm the Banner/Interstitial unit types, AdMob app status, `app-ads.txt`, and live UMP consent message in the AdMob dashboard.
- Play Console account created; DocUtils Pro app entry and dedicated Play upload key still need confirmation.
- A public finalized privacy policy and contact email not supplied.
- Ad-free one-time product has not been tested on Play's internal testing track.
- Bundle hasn't yet been generated or uploaded for Play's pre-launch report; additional policy review may be necessary.

## Current authoritative references
- Google Play target API policy: https://support.google.com/googleplay/android-developer/answer/11926878
- Play App Signing: https://support.google.com/googleplay/android-developer/answer/9842756
- AdMob UMP: https://developers.google.com/admob/android/privacy
- Play Billing: https://developer.android.com/google/play/billing/integrate
