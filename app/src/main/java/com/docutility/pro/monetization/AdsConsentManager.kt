package com.docutility.pro.monetization

import android.app.Activity
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Requests updated privacy choices on every launch before any AdMob ad request. */
class AdsConsentManager(private val activity: Activity, private val onConsentChanged: () -> Unit) {
    private val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
    private val _canRequestAds = MutableStateFlow(false)
    val canRequestAds: StateFlow<Boolean> = _canRequestAds
    private val _privacyOptionsRequired = MutableStateFlow(false)
    val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptionsRequired

    fun refresh() {
        val request = ConsentRequestParameters.Builder().build()
        consentInformation.requestConsentInfoUpdate(
            activity,
            request,
            {
                updateState()
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    updateState()
                }
            },
            {
                // Offline/temporary failures may still allow previously saved consent.
                updateState()
            }
        )
    }

    fun showPrivacyOptions() {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) {
            updateState()
        }
    }

    private fun updateState() {
        _privacyOptionsRequired.value =
            consentInformation.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        _canRequestAds.value = consentInformation.canRequestAds()
        onConsentChanged()
    }
}
