package com.maslarski.iptv.domain.license

import android.app.Activity
import android.content.Context
import android.util.Log
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
import io.sentry.Sentry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

    private enum class SetupState { IDLE, CONNECTING, CONNECTED }

    private var setupState = SetupState.IDLE
    private var setupTimeout: Job? = null

    fun connect() {
        if (setupState != SetupState.IDLE) {
            Log.i(TAG, "Billing connect skipped: setup $setupState")
            return
        }
        setupState = SetupState.CONNECTING
        Log.i(TAG, "Billing connect: starting setup")
        setupTimeout?.cancel()
        setupTimeout = scope.launch {
            delay(SETUP_TIMEOUT_MS)
            Log.w(TAG, "Billing setup timed out after ${SETUP_TIMEOUT_MS}ms without onBillingSetupFinished")
            setupState = SetupState.IDLE
            _state.value = _state.value.copy(available = false, busy = false)
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                setupTimeout?.cancel()
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    setupState = SetupState.CONNECTED
                    Log.i(TAG, "Billing setup finished OK (response=${result.responseCode})")
                    scope.launch { guarded("refresh") { refresh() } }
                } else {
                    setupState = SetupState.IDLE
                    logFailure("setup", result)
                    _state.value = _state.value.copy(available = false, busy = false)
                }
            }

            override fun onBillingServiceDisconnected() {
                setupState = SetupState.IDLE
                Log.w(TAG, "Billing service disconnected")
                _state.value = _state.value.copy(available = false, busy = false)
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
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    logFailure("queryProductDetails", result)
                    cont.resume(null)
                    return@queryProductDetailsAsync
                }
                queryResult.unfetchedProductList.forEach {
                    Log.w(TAG, "Billing queryProductDetails: product ${it.productId} unfetched, status=${it.statusCode}")
                }
                cont.resume(queryResult.productDetailsList.firstOrNull { it.productId == LIFETIME_PRODUCT_ID })
            }
        }
        if (found == null) Log.w(TAG, "Billing queryProductDetails: $LIFETIME_PRODUCT_ID not returned by Play")
        details = found
        val price = found?.oneTimePurchaseOfferDetails?.formattedPrice ?: FALLBACK_PRICE
        _state.value = _state.value.copy(available = found != null, price = price)
        restorePurchases()
    }

    private suspend fun restorePurchases() {
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        val purchases = suspendCancellableCoroutine<List<Purchase>> { cont ->
            client.queryPurchasesAsync(params) { result, list ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) logFailure("queryPurchases", result)
                cont.resume(if (result.responseCode == BillingClient.BillingResponseCode.OK) list else emptyList())
            }
        }
        purchases.forEach { handle(it) }
    }

    /**
     * Launches the Play purchase sheet; returns false when the store is not available or the host activity
     * can no longer start the Play proxy activity. Play's `ProxyBillingActivity` crashes with an NPE when it
     * is handed a null PendingIntent, which happens if the flow is launched from a finishing/destroyed
     * activity, before the client is connected, or while another purchase is in flight.
     */
    fun purchase(activity: Activity): Boolean {
        if (activity.isFinishing || activity.isDestroyed || activity.window == null) return false
        if (setupState != SetupState.CONNECTED) {
            connect()
            return false
        }
        if (_state.value.busy) return false
        val product = details ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).build()),
            )
            .build()
        _state.value = _state.value.copy(busy = true, error = null)
        val result = try {
            client.launchBillingFlow(activity, params)
        } catch (e: RuntimeException) {
            reportBillingFailure("launchBillingFlow", e)
            _state.value = _state.value.copy(busy = false, error = e.message)
            return false
        }
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            logFailure("launchBillingFlow", result)
            _state.value = _state.value.copy(busy = false, error = result.debugMessage)
            return false
        }
        return true
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        try {
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    val list = purchases.orEmpty()
                    if (list.isEmpty()) _state.value = _state.value.copy(busy = false)
                    list.forEach { purchase -> scope.launch { guarded("handlePurchase") { handle(purchase) } } }
                }
                BillingClient.BillingResponseCode.USER_CANCELED -> _state.value = _state.value.copy(busy = false)
                else -> {
                    logFailure("purchasesUpdated", result)
                    _state.value = _state.value.copy(busy = false, error = result.debugMessage)
                }
            }
        } catch (e: RuntimeException) {
            reportBillingFailure("onPurchasesUpdated", e)
            _state.value = _state.value.copy(busy = false, error = e.message)
        }
    }

    private fun logFailure(stage: String, result: BillingResult) {
        Log.w(
            TAG,
            "Billing $stage failed: response=${result.responseCode} " +
                "subResponse=${result.onPurchasesUpdatedSubResponseCode} debug=\"${result.debugMessage}\"",
        )
    }

    private suspend fun guarded(stage: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            reportBillingFailure(stage, e)
            _state.value = _state.value.copy(busy = false, error = e.message)
        }
    }

    private fun reportBillingFailure(stage: String, e: RuntimeException) {
        Log.w(TAG, "Billing $stage failed", e)
        Sentry.captureException(e)
    }

    private suspend fun handle(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED || purchase.products.none { it in LIFETIME_PRODUCT_IDS }) {
            Log.w(TAG, "Billing purchase ignored: state=${purchase.purchaseState} products=${purchase.products}")
            _state.value = _state.value.copy(busy = false)
            return
        }
        license.activatePurchase(purchase.purchaseToken)
        _state.value = _state.value.copy(owned = true, busy = false, error = null)
        if (!purchase.isAcknowledged) {
            val ack = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
            val ackResult = suspendCancellableCoroutine<BillingResult> { cont -> client.acknowledgePurchase(ack) { cont.resume(it) } }
            // Entitlement is already granted; an unacknowledged purchase is returned again by queryPurchases
            // on the next refresh, which retries the acknowledgement.
            if (ackResult.responseCode != BillingClient.BillingResponseCode.OK) logFailure("acknowledgePurchase", ackResult)
        }
    }

    companion object {
        private const val TAG = "BillingManager"
        private const val SETUP_TIMEOUT_MS = 10_000L
        /** Play Console in-app product id for the one-time lifetime unlock. */
        const val LIFETIME_PRODUCT_ID = "maxtv_lifetime_unlock"
        /** Product ids whose purchase unlocks the app, including ids sold by earlier releases. */
        val LIFETIME_PRODUCT_IDS = setOf(LIFETIME_PRODUCT_ID, "maxtv_lifetime")
        const val FALLBACK_PRICE = "€9.99"
    }
}
