# DocUtils Pro — Google Play Data safety audit (pre-submission)

**Working audit, NOT a ready-to-submit form.** This review covers the app source currently on `main`, not all merged Android manifests, transitive SDK behavior, user-device network traffic, or the final Play release AAB.

## What the Android source currently does

| Area | What we can confirm from source | Play Console review action |
|---|---|---|
| Image-to-PDF and crop | Images are selected via Android photo/file pickers; `AutoDocumentCropper` and `PdfProcessor` work locally. | Do not claim the app uploads document content merely because Android grants a local URI; check all SDK and scanner processing. |
| Office-to-PDF | `OfficePdfConverter` uses an on-device OOXML renderer. An optional server-side prototype is **not** connected to the Android app. | If remote conversion is enabled in a future release, revise privacy policy and Data safety form and obtain explicit upload consent. |
| Local file storage | PDFs and scan images are saved under public `Documents/DocUtils Pro` and `Pictures/DocUtils Pro`. | Explain user deletion and public file persistence accurately in the privacy policy. |
| Camera | App declares `android.permission.CAMERA` for its local camera flow; camera is optional in the manifest. | Confirm camera permission is requested only for user-invoked scanning. |
| Photos and file selection | Android picker is used for user-selected media/documents. | Confirm final merged manifest does not unexpectedly include broad media/storage permissions. |
| Internet access | Declared in Android manifest for AdMob, Google Play Billing, consent, Google services, and external privacy link. | Do not label the entire app as completely offline. |
| Google AdMob | Google Mobile Ads SDK is included; UMP consent is requested before starting ads. | Disclose actual ad SDK data collection/sharing. Review vendor's SDK disclosures for the installed SDK version. |
| Google Play Billing | One-time ad-free product status is queried and purchases acknowledged by Play Billing. | Verify purchase flows and any identifiers handled by Google Play; do not claim card data is collected by DocUtils Pro itself. |
| Google ML Kit document scanner | Google Play services delivers scanner UI, ML models and resources; document scanner is designed to run on-device. | Review Google's SDK disclosures and confirm the final scanner behavior on device. |
| App-owned backend | The optional conversion-service prototype is not connected to the Android app. | Recheck before every release if backend integration changes. |

## Important: AdMob data categories must not be omitted

Google's Android Mobile Ads SDK data-disclosure guidance describes automatic handling of:
- IP address / approximate device location inferred from IP;
- user interactions with the app and advertisements;
- SDK/app diagnostic and performance information;
- device and other identifiers, including advertising ID where permitted.

**Do not answer `No data collected` solely because document conversion is performed locally.** Google Play's collection definition includes data transmitted by embedded third-party SDKs, even if the app developer does not receive the raw data.

Data categories, purposes, data sharing, encryption, deletion and optionality **must be selected based on actual app behavior and current SDK disclosures**, not copied blindly from this document. A release using a different SDK version or consent configuration may change the answers.

## Owner decisions needed before completing the Play Data safety form

- Actual public publisher name, privacy-contact email and published HTTPS privacy policy URL;
- target age group (confirm whether the product is directed at children; ad serving and consent requirements can differ);
- confirmation of which AdMob ad formats and any mediation partners are enabled;
- confirmation that the final signed AAB contains only the expected merged permissions and SDKs;
- results from Play internal testing / pre-launch report and a release device network check;
- whether user-saved documents are ever sent to a third-party service (current core Android converter says no).

## Play Console steps

Open **DocUtils Pro → Policy and programs → App content → Data safety** (menu wording may vary). Complete every item consistently with the privacy policy and final SDK behavior; submit only after the signed release AAB is available for review.

## Authoritative references

- Play data collection includes third-party SDKs: https://support.google.com/googleplay/android-developer/answer/10787469
- Google Mobile Ads SDK disclosure: https://developers.google.com/admob/android/privacy/play-data-disclosure
- ML Kit SDK disclosure: https://developers.google.com/ml-kit/android-data-disclosure
- ML Kit on-device document scanner: https://developers.google.com/ml-kit/vision/doc-scanner
