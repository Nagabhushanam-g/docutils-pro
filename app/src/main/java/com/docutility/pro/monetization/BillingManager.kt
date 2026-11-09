package com.docutility.pro.monetization

import android.app.Activity
import android.content.Context
import android.widget.Toast
import com.android.billingclient.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A one-time, non-consumable Play product. Never unlock from a pending
 * transaction or from the purchase callback alone: query Play and acknowledge.
 * Configure docutils_remove_ads_lifetime in Play Console before release.
 */
class BillingManager(private val context: Context) : PurchasesUpdatedListener {
    companion object {
        private const val AD_FREE_PRODUCT = "docutils_remove_ads_lifetime"
    }

    private val _isProUser = MutableStateFlow(false)
    val isProUser: StateFlow<Boolean> = _isProUser

    private val billingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    private var connected = false
    private var productDetails: ProductDetails? = null

    init { startConnection() }

    private fun startConnection() {
        if (connected || billingClient.isReady) return
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    connected = true
                    queryProducts()
                    queryPurchases()
                }
            }

            override fun onBillingServiceDisconnected() {
                connected = false
            }
        })
    }

    private fun queryProducts() {
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(AD_FREE_PRODUCT)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            )
        ).build()
        billingClient.queryProductDetailsAsync(params) { result, products ->
            productDetails = if (result.responseCode == BillingClient.BillingResponseCode.OK)
                products.productDetailsList.firstOrNull { it.productId == AD_FREE_PRODUCT }
            else null
        }
    }

    fun launchPurchaseFlow(activity: Activity) {
        if (!billingClient.isReady) {
            startConnection()
            Toast.makeText(activity, "Google Play is not connected yet. Try again.", Toast.LENGTH_LONG).show()
            return
        }
        val details = productDetails
        if (details == null) {
            queryProducts()
            Toast.makeText(
                activity,
                "Ad-free purchase is not available yet. Please try again after Play Store setup.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            ).build()
        val result = billingClient.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Toast.makeText(activity, "Google Play purchase is unavailable.", Toast.LENGTH_LONG).show()
        }
    }

    /** Called on app resume to restore purchases or detect completed pending purchases. */
    fun refreshPurchases() {
        if (billingClient.isReady) queryPurchases() else startConnection()
    }

    private fun queryPurchases() {
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val owned = purchases.filter { AD_FREE_PRODUCT in it.products }
                // Never grant access just because a purchase is pending.
                _isProUser.value = owned.any {
                    it.purchaseState == Purchase.PurchaseState.PURCHASED && it.isAcknowledged
                }
                owned.forEach(::processPurchase)
            }
        }
    }

    private fun processPurchase(purchase: Purchase) {
        if (AD_FREE_PRODUCT !in purchase.products ||
            purchase.purchaseState != Purchase.PurchaseState.PURCHASED
        ) return

        if (purchase.isAcknowledged) {
            _isProUser.value = true
            return
        }
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { result ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                _isProUser.value = true
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases?.forEach(::processPurchase)
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refreshPurchases()
        }
    }

    fun close() { billingClient.endConnection() }
}
