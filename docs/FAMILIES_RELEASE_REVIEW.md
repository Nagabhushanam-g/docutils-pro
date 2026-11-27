# DocUtils Pro — Families / All-Ages Release Review

**Target audience decision (27 November 2026):** Intended audience includes children and adults. This is a product decision, **not** a statement that Play Console's target-audience form has already been submitted or that the app is Families compliant.

**Release status: NOT APPROVED.** This check must remain blocking until each requirement is verified against the actual production AAB and Play Console disclosures.

## Current source-code findings

- `app/src/main/java/com/docutility/pro/monetization/AdManager.kt` requests interstitial ads using `AdRequest.Builder().build()` without child/unknown-age treatment. `MainActivity.kt` also loads banner ads.
- `AdsConsentManager.kt` initializes the Google UMP consent flow without age-based configuration.
- `BillingManager.kt` exposes a non-consumable remove-ads purchase; parental-gate / child-specific purchase flow has not been validated.
- `app/build.gradle.kts` currently pins Google Mobile Ads SDK `23.0.0`; **verify the exact shipped SDK version and its Families certification in the current Play SDK Index** before serving ads to children or unknown-age users.
- No neutral, non-incentivizing age screen or verified child/unknown-age ad gating has been found.

## Release acceptance criteria

1. **Target-age declaration:** Specify the intended Google Play age groups accurately in Target audience and content. Don't confuse this with the app's content rating.
2. **Neutral age screen:** Implement a non-leading age screen BEFORE any ads or nonessential data collection. Treat missing/unknown age conservatively. Test reopening, resets, and consent changes; no adult mode should be reachable through an incentivized choice.
3. **Advertising SDK:** Verify that the SDK version and any mediation are permitted under the current Families Self-Certified Ads SDK policy. Child and unknown-age ad requests must follow Google's current age-treatment requirements, use G-rated creatives and avoid interest-based/personalized ads. For a mixed-audience app, restrict noncertified networks to verified adults only. Check banner placement, interstitial timing, accidental taps and the Families ad-format rules.
4. **SDK privacy:** Audit AdMob, UMP, ML Kit / Play services, billing and all permissions for child/unknown-age data transmission, advertising identifiers, consent and regional COPPA/GDPR obligations. Don't assume that suppressing visible ads alone prevents SDK activity.
5. **Purchases:** Design and test an appropriate parent/guardian gate for child-initiated purchases or remove purchase entry points from the child path. Verify Play Billing compliance and restoration.
6. **Privacy:** Publish an accurate and readable child-specific privacy notice explaining handling of files, camera/scans, SDK data, advertising, parental choices and retention; obtain any legally required parent consent. Do not claim all data stays on-device without verifying SDK flows.
7. **Play listing:** Complete the truthful Families target-audience, Ads, Content rating, Data safety and app access declarations, with store art and copy suitable for each selected age group.
8. **QA:** Test the final signed release AAB as a child, adult and unknown-age user on real devices, including offline behavior, clean install, app restart, ads, scanning, PDF export, file sharing, privacy choices and purchase attempts.
9. **Owner sign-off:** Publisher reviews the final app, policy, AdMob settings and Play Console responses, and records the approved versions/date.

## Release guard

`docs/FAMILIES_RELEASE_STATUS.txt` intentionally contains `PENDING`. The Play Release Preflight and production AAB workflow must fail until compliance work is complete and the publisher has verified the above criteria. Changing the marker to `APPROVED` without evidence is not an acceptable shortcut.

References (recheck before signing off):
- https://support.google.com/googleplay/android-developer/answer/9893335
- https://support.google.com/googleplay/android-developer/answer/9867159
- https://support.google.com/admob/answer/6223431
- https://support.google.com/googleplay/android-developer/answer/9900633
