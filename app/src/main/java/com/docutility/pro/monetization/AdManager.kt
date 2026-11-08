package com.docutility.pro.monetization

import com.docutility.pro.BuildConfig
import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

class AdManager(private val context: Context) {

    private var interstitialAd: InterstitialAd? = null
    /** Attempt a preloaded ad after every successful conversion.
     * Ad loading is asynchronous and never delays access to the saved PDF.
     */
    fun afterCompletedAction(activity: Activity, onContinue: () -> Unit) {
        showInterstitial(activity, onContinue)
    }

    // AdMob test interstitial ID
    private val interstitialId = BuildConfig.ADMOB_INTERSTITIAL_ID

    fun loadInterstitial() {
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(context, interstitialId, adRequest, object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) {
                interstitialAd = ad
            }
            override fun onAdFailedToLoad(error: LoadAdError) {
                interstitialAd = null
            }
        })
    }

    fun showInterstitial(activity: Activity, onAdDismissed: () -> Unit) {
        val ad = interstitialAd
        if (ad != null) {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    interstitialAd = null
                    loadInterstitial() // Pre-load next
                    onAdDismissed()
                }
                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    interstitialAd = null
                    onAdDismissed()
                }
            }
            ad.show(activity)
        } else {
            loadInterstitial()
            onAdDismissed()
        }
    }
}

