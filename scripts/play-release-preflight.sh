#!/usr/bin/env bash
# Non-destructive Play release readiness audit. NEVER print credentials.
set -euo pipefail

SUMMARY="${GITHUB_STEP_SUMMARY:-/dev/stdout}"
failures=0
warnings=0
pass() { printf -- '- ✅ %s\n' "$1" >> "$SUMMARY"; }
fail() { printf -- '- ❌ %s\n' "$1" >> "$SUMMARY"; failures=$((failures+1)); }
warn() { printf -- '- ⚠️ %s\n' "$1" >> "$SUMMARY"; warnings=$((warnings+1)); }
have() { [ -n "${!1:-}" ]; }

printf '## DocUtils Pro — Play release preflight\n\n' >> "$SUMMARY"
printf 'This audit checks repository configuration and public web endpoints; it does **not** publish a release or verify private Play Console/AdMob dashboards.\n\n' >> "$SUMMARY"

printf '### Release signing\n' >> "$SUMMARY"
if have DOCUTILS_RELEASE_KEYSTORE_BASE64 && have DOCUTILS_RELEASE_STORE_PASSWORD &&
   have DOCUTILS_RELEASE_KEY_ALIAS && have DOCUTILS_RELEASE_KEY_PASSWORD; then
  pass 'All four required Play upload-signing secrets are present (contents not logged)'
  expected_cert="${DOCUTILS_RELEASE_UPLOAD_CERT_SHA256:-}"
  if [[ ! "$expected_cert" =~ ^[[:xdigit:]]{64}$ ]]; then
    fail 'Missing or invalid DOCUTILS_RELEASE_UPLOAD_CERT_SHA256 repository variable'
  else
    certdir="$(mktemp -d)"
    if printf '%s' "$DOCUTILS_RELEASE_KEYSTORE_BASE64" | base64 --decode > "$certdir/upload.p12" &&
       keytool -exportcert -keystore "$certdir/upload.p12" -storepass "$DOCUTILS_RELEASE_STORE_PASSWORD" -alias "$DOCUTILS_RELEASE_KEY_ALIAS" > "$certdir/upload.cer" 2>/dev/null; then
      actual_cert="$(sha256sum "$certdir/upload.cer" | cut -d' ' -f1)"
      if [[ "${actual_cert,,}" = "${expected_cert,,}" ]] &&
         [ "$actual_cert" != "518360924041db59d45ca761cf4babaf484dc978756d8f5fd7446d27872af85b" ]; then
        pass 'Play upload certificate matches its pinned SHA-256 and is distinct from debug signing'
      else
        fail 'Play upload certificate differs from pinned SHA-256 (or is the test/debug key)'
      fi
    else
      fail 'Play upload keystore is unreadable or its alias/password is invalid'
    fi
    rm -rf "$certdir"
  fi
else
  fail 'Missing one or more Play upload-signing secrets in GitHub Actions (see docs/PLAY_RELEASE_CHECKLIST.md)'
fi

printf '\n### AdMob\n' >> "$SUMMARY"
app="${DOCUTILS_ADMOB_APP_ID:-}"
banner="${DOCUTILS_ADMOB_BANNER_ID:-}"
interstitial="${DOCUTILS_ADMOB_INTERSTITIAL_ID:-}"
if [[ "$app" =~ ^ca-app-pub-[0-9]{16}~[0-9]{10}$ ]] &&
   [[ "$banner" =~ ^ca-app-pub-[0-9]{16}/[0-9]{10}$ ]] &&
   [[ "$interstitial" =~ ^ca-app-pub-[0-9]{16}/[0-9]{10}$ ]] &&
   [ "$banner" != "$interstitial" ] &&
   [ "${app%%~*}" = "${banner%%/*}" ] &&
   [ "${app%%~*}" = "${interstitial%%/*}" ]; then
  pass 'Production App ID, Banner ID and Interstitial ID have consistent valid formats'
else
  fail 'Invalid/missing production AdMob IDs or inconsistent publisher; verify ad-unit type in AdMob'
fi
warn 'AdMob app approval, unit type and UMP consent-message publication require manual checks in the AdMob dashboard'

printf '\n### Public privacy policy\n' >> "$SUMMARY"
policy="${DOCUTILS_PRIVACY_POLICY_URL:-}"
if [[ "$policy" =~ ^https://[^[:space:]]+ ]]; then
  policy_body="$(mktemp)"
  if curl --fail --location --silent --show-error --max-time 20 "$policy" > "$policy_body" &&
     [ -s "$policy_body" ]; then
    if grep -Eiq '\[INSERT|\[PUBLISH|NOT READY FOR PUBLICATION' "$policy_body"; then
      fail 'The published privacy-policy URL appears to contain unfinished draft placeholders'
    elif grep -Eiq 'DocUtils Pro' "$policy_body"; then
      pass 'Public HTTPS privacy-policy URL returns a non-empty page mentioning DocUtils Pro'
      warn 'Publisher must still verify legal contact details, disclosures, and Play data-safety answers'
    else
      fail 'Privacy-policy page does not identify DocUtils Pro'
    fi
  else
    fail 'Privacy-policy URL is not publicly accessible (or request timed out)'
  fi
  rm -f "$policy_body"
else
  fail 'Set DOCUTILS_PRIVACY_POLICY_URL GitHub Actions variable to your final public HTTPS privacy policy'
fi

printf '\n### Developer website and app-ads.txt\n' >> "$SUMMARY"
site="${DOCUTILS_DEVELOPER_SITE_URL:-}"
root="${site%/}"
if [[ "$root" =~ ^https://[A-Za-z0-9][A-Za-z0-9.-]*$ ]]; then
  sellers="$(mktemp)"
  if curl --fail --location --silent --show-error --max-time 20 "$root/app-ads.txt" > "$sellers" &&
    tr -d '\r' < "$sellers" | grep -Eq '^google\.com,[[:space:]]*pub-5641577537743477,[[:space:]]*DIRECT,[[:space:]]*f08c47fec0942fa0[[:space:]]*$'; then
    pass 'Website root /app-ads.txt is publicly accessible and includes this AdMob publisher'
  else
    fail 'app-ads.txt missing, inaccessible, or does not match the publisher at the website root'
  fi
  rm -f "$sellers"
else
  fail 'Set DOCUTILS_DEVELOPER_SITE_URL to the public HTTPS domain root, e.g. https://example.com'
fi
warn 'Confirm the same developer website is set in Google Play and AdMob verifies the app-ads.txt file'

printf '\n### Play Console — manual checks\n' >> "$SUMMARY"
warn 'Confirm Play Console app entry com.docutility.pro, separate upload key, billing product, store listing, Data safety, screenshots, internal testing, and any required closed testing'

printf '\n**Automated checks: %s blocker(s); %s manual-review reminder(s).**\n' "$failures" "$warnings" >> "$SUMMARY"
if [ "$failures" -gt 0 ]; then
  echo "::error::Play release is not ready: $failures automated preflight checks failed. See the job summary."
  exit 1
fi
echo "Automated preflight passed. Review manual Play Console and AdMob items before shipping."
