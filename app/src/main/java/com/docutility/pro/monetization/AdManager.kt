package com.docutility.pro.monetization

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import com.docutility.pro.BuildConfig
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * Full-screen advertising is optional, and always occurs at a natural break
 * after a completed conversion, before opening its resulting PDF.
 *
 * Limit frequency to protect the editing experience and avoid repeat ads.
 * No network/ad timeout can hold the user's completed document hostage.
 */
class AdManager(private val context: Context) {
    companion object {
        private const val MIN_COMPLETED_ACTIONS_BETWEEN_ADS = 3
        private const val MIN_INTERVAL_BETWEEN_ADS_MS = 120_000L
        private const val MAX_AD_AGE_MS = 55 * 60_000L
    }

    private val interstitialId = BuildConfig.ADMOB_INTERSTITIAL_ID
    private var interstitialAd: InterstitialAd? = null
    private var isLoading = false
    private var adLoadedAtMs = 0L
    private var lastAdShownAtMs = 0L
    private var completedActionsSinceAd = 0

    fun afterCompletedAction(activity: Activity, onContinue: () -> Unit) {
        completedActionsSinceAd++
        val now = SystemClock.elapsedRealtime()
        if (completedActionsSinceAd < MIN_COMPLETED_ACTIONS_BETWEEN_ADS ||
            (lastAdShownAtMs > 0L && now - lastAdShownAtMs < MIN_INTERVAL_BETWEEN_ADS_MS)
        ) {
            loadInterstitial()
            onContinue()
            return
        }

        val ad = interstitialAd
        if (ad == null || now - adLoadedAtMs > MAX_AD_AGE_MS) {
            interstitialAd = null
            loadInterstitial()
            onContinue()
            return
        }

        // Consume the cached ad only once, whether display succeeds or fails.
        interstitialAd = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                lastAdShownAtMs = SystemClock.elapsedRealtime()
                completedActionsSinceAd = 0
            }

            override fun onAdDismissedFullScreenContent() {
                loadInterstitial()
                onContinue()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                loadInterstitial()
                onContinue()
            }
        }
        ad.show(activity)
    }

    fun loadInterstitial() {
        if (isLoading || interstitialAd != null) return
        isLoading = true
        InterstitialAd.load(
            context,
            interstitialId,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    isLoading = false
                    interstitialAd = ad
                    adLoadedAtMs = SystemClock.elapsedRealtime()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoading = false
                    interstitialAd = null
                }
            }
        )
    }
}
