# DocUtils Pro developer website (Firebase Hosting starter)

**Prepared only. Not deployed.** The site must be owned and deployed under the Play Console developer account. It does not publish an unfinished privacy policy.

## What is in this folder

- `public/index.html`: simple informational developer website
- `public/app-ads.txt`: Google authorized seller line for publisher `pub-5641577537743477`
- `firebase.json`: Firebase Hosting config to publish the content at the site **root**
- `privacy.template.html`: private draft; deliberately **outside** `public/`. Do not publish it until all placeholders have been replaced and the disclosures verified.


## Prepare the privacy page (before deployment)

The source policy template is deliberately excluded from the public folder. From the repository root, use Python 3 to generate `release-site/public/privacy.html` **only after reviewing its text** and confirming that the app's actual Google AdMob, UMP, Play Billing, document-processing and ML Kit behavior matches the policy:

```powershell
python .\\release-site\\prepare-privacy.py --publisher "YOUR LEGAL PUBLISHER NAME" --email "YOUR PUBLIC SUPPORT EMAIL" --audience "YOUR VERIFIED TARGET AUDIENCE AND AGE POLICY" --confirm-reviewed
```

Do not use placeholder details, private email addresses you don't want publicly displayed, or an audience statement you have not verified in Google Play Console. The tool does **not** deploy anything, refuses to overwrite an existing privacy page, and leaves the owner responsible for reviewing regional legal obligations and Play Data safety declarations. After generating the page, inspect it thoroughly, add a privacy policy link to the website homepage, and only then publish via Firebase Hosting. If the page already exists, review it and deliberately manage its updates rather than regenerating over it.

## Deployment from a trusted computer

1. Create/choose your **own** Firebase project and enable Firebase Hosting.
2. Install Firebase CLI: `npm install -g firebase-tools`, then `firebase login`.
3. From inside the `release-site` directory, run `firebase deploy --only hosting --project YOUR_FIREBASE_PROJECT_ID`.
4. Visit `https://YOUR_FIREBASE_PROJECT_ID.web.app/app-ads.txt`; the publisher line must appear exactly as plain text.
5. Update the **developer website** in your DocUtils Pro Play Console listing with `https://YOUR_FIREBASE_PROJECT_ID.web.app` — without a path. Also add this URL to the GitHub Actions variable `DOCUTILS_DEVELOPER_SITE_URL`.
6. Review `docs/PRIVACY_POLICY_DRAFT.md` and `privacy.template.html` carefully. Fill all publisher and legal-policy placeholders, then *copy the reviewed page* to `public/privacy.html`. Redeploy, verify `https://YOUR_FIREBASE_PROJECT_ID.web.app/privacy.html`, and set GitHub Actions variable `DOCUTILS_PRIVACY_POLICY_URL` to that **exact URL**.
7. In AdMob, verify the app-ads.txt authorization after the developer website appears in your public app listing.

> Firebase Hosting is one possible host; any HTTPS website serving `/app-ads.txt` from its **domain root** can work. Google Play must show the website as your developer contact URL.

**Safety:** A Google Play listing privacy policy must not contain placeholder contacts or statements that have not been verified against the final app. The starter intentionally does not include a published `privacy.html` file.
