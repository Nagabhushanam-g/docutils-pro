# DocUtils Pro — publisher website deployment assets

These files are **prepared**, not automatically hosted or verified. Google AdMob cannot crawl a file that only exists in GitHub source control.

## 1. AdMob authorized sellers (`app-ads.txt`)

The adjoining `app-ads.txt` contains the standard Google AdMob authorized-seller entry for the publisher ID associated with the owner's AdMob app:

```text
google.com, pub-5641577537743477, DIRECT, f08c47fec0942fa0
```

Before publishing, compare that line against the personalized snippet under **AdMob → Apps → View all apps → app-ads.txt → How to set up app-ads.txt**. If Google's snippet differs, use the AdMob-provided version.

Upload the file to the **root of the actual developer website**, for example:

```text
https://YOUR-DEVELOPER-DOMAIN.example/app-ads.txt
```

Do **not** assume that a repository page such as `https://username.github.io/docutils-pro/app-ads.txt` works: the AdMob crawler looks at the hostname root (`https://username.github.io/app-ads.txt`). For GitHub Pages, use a custom domain configured to route to the correct website root, or the owner-level GitHub Pages website at the required root.

Open the live URL in an incognito browser: it must return the line as **plain text**, without login, 404, or an HTML wrapper. Use that same website as the **Developer website** on the public Google Play listing.

Once the app is publicly listed on Google Play, link the matching store entry under **AdMob → DocUtils Pro → App settings**, and request an app-ads.txt recheck/verification. The AdMob readiness review may be limited until the app is available publicly.

## 2. Public privacy policy

Review `docs/PRIVACY_POLICY_DRAFT.md` with the account owner. Insert the verified **legal publisher name**, **contact email**, **effective date**, target-audience statement, SDK disclosures and deletion/retention details. Ensure its factual statements match the Android release and the Play Console **Data safety** responses.

Publish the final policy to a public HTTPS URL such as `https://YOUR-DEVELOPER-DOMAIN.example/privacy-policy` and add it to:
- Google Play Console → DocUtils Pro → Privacy policy
- GitHub Actions variable `DOCUTILS_PRIVACY_POLICY_URL`

The release app includes **More → Privacy policy**, using the configured URL. Do not publish the placeholder policy or put a made-up company name/contact email into it.

## 3. Google Play and AdMob checks

- Confirm the ad units have the correct AdMob **format**: the unit ending `2085382400` must be **Banner**, and the one ending `2972068609` must be **Interstitial**. The ID alone cannot prove its unit type.
- Publish an appropriate UMP consent message under **AdMob → Privacy & messaging** and test region-appropriate consent dialogs.
- Keep debug APK builds on test ads. Live IDs are used in the manually triggered signed Play **AAB** workflow only.
- Confirm DocUtils Pro app entry, data-safety form, billing product, signing key, and internal testing prerequisites in `docs/PLAY_RELEASE_CHECKLIST.md`.

Official guidance:
- https://support.google.com/admob/answer/9363762
- https://support.google.com/admob/answer/9776740
- https://support.google.com/admob/answer/10564477
