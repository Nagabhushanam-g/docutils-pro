"""Source-level regression checks for the all-ages startup boundaries.

These checks complement Kotlin compilation; they cannot replace device-level
network inspection, SDK certification, or Play Families policy review.
"""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "app/src/main/java/com/docutility/pro/MainActivity.kt").read_text(encoding="utf-8")
PREFLIGHT = (ROOT / "scripts/play-release-preflight.sh").read_text(encoding="utf-8")


class FamiliesStartupGuardTests(unittest.TestCase):
    def test_age_screen_precedes_optional_sdk_creation(self):
        startup = SOURCE.split("override fun onCreate(savedInstanceState: Bundle?)", 1)[1].split(
            "private fun initializeAdultOnlyServices()", 1
        )[0]
        self.assertIn("null -> NeutralAgeScreen", startup)
        self.assertIn("if (selected == AgeTier.ADULT)", startup)
        self.assertIn("initializeAdultOnlyServices()", startup)
        self.assertNotIn("BillingManager(this)", startup)
        self.assertNotIn("AdsConsentManager(this)", startup)
        self.assertNotIn("AdManager(this)", startup)

    def test_underage_branch_disables_monetization(self):
        underage = SOURCE.split("else -> {\n                    MainScreen(", 1)[1].split("\n                }", 1)[0]
        for config in ("purchaseAvailable = false", "adsAllowed = false", "showPurchaseControls = false"):
            self.assertIn(config, underage)
        self.assertIn("onShowAd = { onDismissed -> onDismissed() }", underage)

    def test_adult_ad_content_rating_limit(self):
        self.assertIn("setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_G)", SOURCE)
        self.assertLess(
            SOURCE.index("setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_G)"),
            SOURCE.index("MobileAds.initialize(this)")
        )

    def test_release_remains_blocked(self):
        status = (ROOT / "docs/FAMILIES_RELEASE_STATUS.txt").read_text(encoding="utf-8").strip()
        self.assertEqual(status, "PENDING")
        self.assertIn('families_status', PREFLIGHT)
        self.assertIn('Families compliance is PENDING', PREFLIGHT)

    def test_unknown_age_not_stored_as_adult(self):
        self.assertIn("selectedAgeTier by mutableStateOf<AgeTier?>(null)", SOURCE)
        self.assertFalse(re.search(r"selectedAgeTier\s*=\s*AgeTier.ADULT", SOURCE))


if __name__ == "__main__":
    unittest.main()
