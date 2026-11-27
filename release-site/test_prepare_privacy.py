"""Check release website preparation without generating or publishing real user details."""
import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("prepare_privacy", ROOT / "prepare-privacy.py")
privacy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(privacy)


class PrivacyPreparationTests(unittest.TestCase):
    def test_draft_is_not_in_published_folder(self):
        self.assertFalse((ROOT / "public" / "privacy.html").exists())
        self.assertTrue((ROOT / "privacy.template.html").exists())

    def test_app_ads_line_matches_configured_publisher(self):
        self.assertEqual(
            (ROOT / "public" / "app-ads.txt").read_text(encoding="utf-8").strip(),
            "google.com, pub-5641577537743477, DIRECT, f08c47fec0942fa0",
        )

    def test_valid_reviewed_policy_has_no_draft_markers(self):
        html = privacy.build_policy(
            "Example Individual", "example@example.com",
            "Intended for adults 18 and older.", "2026-11-27",
        )
        self.assertIn("DocUtils Pro — Privacy Policy", html)
        self.assertIn("example@example.com", html)
        self.assertNotIn("DRAFT — DO NOT PUBLISH", html)
        self.assertNotIn("[INSERT", html)

    def test_escaping_and_invalid_inputs(self):
        html = privacy.build_policy(
            "Example & Co", "help@example.com",
            "Adults > 18", "2026-11-27",
        )
        self.assertIn("Example &amp; Co", html)
        self.assertIn("Adults &gt; 18", html)
        with self.assertRaises(ValueError):
            privacy.build_policy("Example", "not-an-email", "Adults", "2026-11-27")
        with self.assertRaises(ValueError):
            privacy.build_policy("", "help@example.com", "Adults", "2026-11-27")
        with self.assertRaises(ValueError):
            privacy.build_policy("Example", "help@example.com", "", "2026-11-27")


if __name__ == "__main__":
    unittest.main()
