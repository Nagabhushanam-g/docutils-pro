#!/usr/bin/env python3
"""Prepare a REVIEWED DocUtils Pro privacy page for Firebase Hosting.

This does not deploy anything. Require the publisher to supply real details and
explicitly confirm third-party SDK / Play Data safety disclosures before output.
"""
import argparse
from datetime import date
from html import escape
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent
TEMPLATE = ROOT / "privacy.template.html"
DESTINATION = ROOT / "public" / "privacy.html"
HOME = ROOT / "public" / "index.html"
PLACEHOLDERS = re.compile(r"\\[INSERT[^]]*\\]|\\[PUBLISH[^]]*\\]|DRAFT — DO NOT PUBLISH|REVIEW REQUIRED", re.I)


def build_policy(publisher: str, email: str, audience: str, effective: str) -> str:
    if not publisher.strip() or len(publisher) > 150:
        raise ValueError("Provide the true legal publisher name (maximum 150 characters).")
    if not re.fullmatch(r"[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+", email):
        raise ValueError("Provide a valid public support email address.")
    if not audience.strip() or len(audience) > 1200:
        raise ValueError("Provide a verified target-audience statement (maximum 1200 characters).")
    date.fromisoformat(effective)
    html = TEMPLATE.read_text(encoding="utf-8")
    html = html.replace("Privacy Policy — DocUtils Pro (DRAFT)", "Privacy Policy — DocUtils Pro")
    html = html.replace("DocUtils Pro — Privacy Policy (DRAFT — DO NOT PUBLISH)", "DocUtils Pro — Privacy Policy")
    html = html.replace("[INSERT LEGAL DEVELOPER OR COMPANY NAME]", escape(publisher.strip()))
    html = html.replace("[INSERT SUPPORT EMAIL]", escape(email.strip()))
    html = html.replace("[INSERT EFFECTIVE DATE]", escape(effective))
    html = html.replace("[INSERT VERIFIED TARGET AUDIENCE, AGE POLICY AND REGIONAL DISCLOSURES]", escape(audience.strip()))
    html = re.sub(r"<p><em>REVIEW REQUIRED:.*?</em></p>", "", html, flags=re.S)
    if PLACEHOLDERS.search(html):
        raise ValueError("The template still contains an unreviewed placeholder.")
    return html


def main():
    parser = argparse.ArgumentParser(description="Generate reviewed local privacy page; no deployment")
    parser.add_argument("--publisher", required=True, help="Legal person or organization publishing the app")
    parser.add_argument("--email", required=True, help="Public privacy/support email")
    parser.add_argument("--audience", required=True, help="Verified audience and regional age policy text")
    parser.add_argument("--effective", default=date.today().isoformat(), help="YYYY-MM-DD")
    parser.add_argument("--confirm-reviewed", action="store_true", help="I have reviewed SDK disclosures and Play Data safety")
    args = parser.parse_args()
    if not args.confirm_reviewed:
        parser.error("Review the policy against the app and Play Data safety, then supply --confirm-reviewed.")
    if DESTINATION.exists():
        parser.error("privacy.html already exists; do not overwrite a published policy implicitly.")
    try:
        html = build_policy(args.publisher, args.email, args.audience, args.effective)
    except (ValueError, OSError) as exc:
        parser.error(str(exc))
    DESTINATION.write_text(html, encoding="utf-8")
    print("Prepared release-site/public/privacy.html (NOT deployed).")
    print("Verify the full HTML, then deploy Firebase Hosting and check its public URL.")
    print("Set DOCUTILS_PRIVACY_POLICY_URL only after public verification.")


if __name__ == "__main__":
    main()
