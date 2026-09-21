package com.maslarski.iptv.domain.license

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Store-side state of the one-time lifetime purchase. */
data class BillingState(
    val available: Boolean = false,
    /** Localized store price (e.g. "€9.99"); falls back to [BillingManager.FALLBACK_PRICE] when the store is unreachable. */
    val price: String = BillingManager.FALLBACK_PRICE,
    val owned: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Google Play Billing for the single in-app product [LIFETIME_PRODUCT_ID]. Connection failures (no Play
 * Store, e.g. on Fire TV or sideloaded builds) are not fatal: the UI keeps the fixed price and the
 * manual activation key path. A completed purchase is acknowledged and unlocks [LicensePlan.LIFETIME].
 */
@Singleton
class BillingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val license: LicenseRepository,
) : PurchasesUpdatedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(BillingState())
    val state: StateFlow<BillingState> = _state.asStateFlow()

    private var details: ProductDetails? = null

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    fun connect() {
        if (client.isReady) return
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    scope.launch { refresh() }
                } else {
                    _state.value = _state.value.copy(available = false)
                }
            }

            override fun onBillingServiceDisconnected() {
                _state.value = _state.value.copy(available = false)
            }
        })
    }

    private suspend fun refresh() {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(LIFETIME_PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        val found = suspendCancellableCoroutine<ProductDetails?> { cont ->
            client.queryProductDetailsAsync(params) { result, queryResult ->
                val list = if (result.responseCode == BillingClient.BillingResponseCode.OK) queryResult.productDetailsList else emptyList()
                cont.resume(list.firstOrNull { it.productId == LIFETIME_PRODUCT_ID })
            }
        }
        details = found
        val price = found?.oneTimePurchaseOfferDetails?.formattedPrice ?: FALLBACK_PRICE
        _state.value = _state.value.copy(available = found != null, price = price)
        restorePurchases()
    }

    private suspend fun restorePurchases() {
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        val purchases = suspendCancellableCoroutine<List<Purchase>> { cont ->
            client.queryPurchasesAsync(params) { result, list ->
                cont.resume(if (result.responseCode == BillingClient.BillingResponseCode.OK) list else emptyList())
            }
        }
        purchases.forEach { handle(it) }
    }

    /** Launches the Play purchase sheet; returns false when the store is not available. */
    fun purchase(activity: Activity): Boolean {
        val product = details ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).build()),
            )
            .build()
        _state.value = _state.value.copy(busy = true, error = null)
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _state.value = _state.value.copy(busy = false, error = result.debugMessage)
            return false
        }
        return true
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases.orEmpty().forEach { scope.launch { handle(it) } }
            BillingClient.BillingResponseCode.USER_CANCELED -> _state.value = _state.value.copy(busy = false)
            else -> _state.value = _state.value.copy(busy = false, error = result.debugMessage)
        }
    }

    private suspend fun handle(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED || LIFETIME_PRODUCT_ID !in purchase.products) {
            _state.value = _state.value.copy(busy = false)
            return
        }
        if (!purchase.isAcknowledged) {
            val ack = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
            suspendCancellableCoroutine<Unit> { cont -> client.acknowledgePurchase(ack) { cont.resume(Unit) } }
        }
        license.activatePurchase(purchase.purchaseToken)
        _state.value = _state.value.copy(owned = true, busy = false, error = null)
    }

    companion object {
        /** Play Console in-app product id for the one-time lifetime unlock. */
        const val LIFETIME_PRODUCT_ID = "maxtv_lifetime"
        const val FALLBACK_PRICE = "€9.99"
    }
}
