# DocUtils Pro — Families QA APK testing

The independent GitHub Actions workflow **Build Isolated Families QA APK** builds
a debug-only side-by-side app with the package `com.docutility.pro.familiesqa`.
It uses Google test ad unit IDs and a disposable test key created only on
GitHub's runner. Do **not** upload this APK to Google Play.

Workflow: https://github.com/Nagabhushanam-g/docutils-pro/actions/workflows/build-families-qa-apk.yml

## Install

1. Open the latest successful workflow run and download the
   `DocUtils-Families-QA-APK` artifact ZIP.
2. Extract `DocUtils-Families-QA-N.apk` and install it on the Android phone
   with install permission enabled for the selected file manager.
3. The QA app can be installed beside the existing DocUtils Pro app. **Do not
   uninstall the original app.**
4. Each workflow run uses a different disposable key. To install a new QA build
   over a prior QA build, uninstall **only** the separate QA package
   (`com.docutility.pro.familiesqa`) first after exporting any QA documents
   you need to retain.

## Tests to record (manual, real devices)

- [ ] On first open, the neutral age screen appears before the document workspace.
- [ ] On the initial age screen, no ad, consent form or purchase prompt appears.
- [ ] Under 13: scan, crop, image-to-PDF, Word/Excel import, export, open and share.
- [ ] Under 13: no banner/interstitial, ad privacy prompt or purchase action.
- [ ] 13–17: repeat the same checks; advertising and purchases remain absent.
- [ ] 18+: consent flow and **test ads only** work where appropriate, and
      purchase UI is available only if configured (do not make real purchases).
- [ ] With no selection, no document workspace, ads or purchases are opened.
- [ ] Close and reopen the app: age choice must not be assumed to be adult.
- [ ] Airplane mode/offline: verify file tools remain usable as expected.
- [ ] Check scanner integration and all relevant app permissions on real devices.
- [ ] For each age range, capture traffic/network and SDK initialization for
      independent review; source-code checks cannot prove no child data leaves
      the device.

## Not an approval

This QA test is **not** proof of compliance with Google Play's Families Policy,
ad SDK restrictions, parental consent requirements or data-safety declarations.
The production preflight stays blocked with
`docs/FAMILIES_RELEASE_STATUS.txt = PENDING` until those requirements are
independently verified and the publisher approves the final release.
