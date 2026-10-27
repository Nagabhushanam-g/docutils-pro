package com.docutility.pro.monetization

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

class AdManager(private val context: Context) {

    private var interstitialAd: InterstitialAd? = null
    private val prefs = context.getSharedPreferences("docutils_ads", Context.MODE_PRIVATE)

    /** Display opportunities only after completed operations, never mid-conversion. */
    fun afterCompletedAction(activity: Activity, onContinue: () -> Unit) {
        val count = prefs.getInt("completed_actions", 0) + 1
        prefs.edit().putInt("completed_actions", count).apply()
        val now = System.currentTimeMillis()
        val lastShown = prefs.getLong("last_interstitial", 0L)
        if (count % 3 != 0 || now - lastShown < 90_000L) {
            onContinue()
            return
        }
        showInterstitial(activity, onContinue)
    }

    // AdMob test interstitial ID
    private val interstitialId = "ca-app-pub-3940256099942544/1033173712"

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
            onAdDismissed()
        }
    }
}

