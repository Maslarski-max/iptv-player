package com.maslarski.iptv.ui.license

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.maslarski.iptv.R
import com.maslarski.iptv.domain.license.BillingManager
import com.maslarski.iptv.domain.license.BillingState
import com.maslarski.iptv.domain.license.LicensePlan
import com.maslarski.iptv.domain.license.LicenseRepository
import com.maslarski.iptv.domain.license.LicenseState
import com.maslarski.iptv.domain.license.SubscriptionStatus
import com.maslarski.iptv.ui.components.Badge
import com.maslarski.iptv.ui.components.GlowButton
import com.maslarski.iptv.ui.components.dpadTextField
import com.maslarski.iptv.ui.components.focusGlow
import com.maslarski.iptv.ui.components.rememberFocusState
import com.maslarski.iptv.ui.components.rememberInteractionSource
import com.maslarski.iptv.ui.settings.label
import com.maslarski.iptv.ui.theme.Palette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

sealed interface ActivationResult {
    data class Success(val plan: LicensePlan) : ActivationResult
    data object Invalid : ActivationResult
}

@HiltViewModel
class ActivationViewModel @Inject constructor(
    private val license: LicenseRepository,
    private val billing: BillingManager,
) : ViewModel() {
    val state: StateFlow<LicenseState?> = license.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val billingState: StateFlow<BillingState> = billing.state

    init { billing.connect() }

    /** Returns false when Google Play is unavailable (e.g. Fire TV); the UI then points to the manual key. */
    fun purchase(activity: Activity): Boolean = billing.purchase(activity)

    private val _result = MutableStateFlow<ActivationResult?>(null)
    val result: StateFlow<ActivationResult?> = _result.asStateFlow()

    fun activate(key: String) {
        viewModelScope.launch {
            val plan = license.activate(key)
            _result.value = if (plan != null) ActivationResult.Success(plan) else ActivationResult.Invalid
        }
    }

    fun clearResult() { _result.value = null }
}

@Composable
fun ActivationScreen(onDone: () -> Unit, viewModel: ActivationViewModel = hiltViewModel()) {
    val license by viewModel.state.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val billing by viewModel.billingState.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    var storeUnavailable by remember { mutableStateOf(false) }
    val activateFocus = remember { FocusRequester() }
    val activity = LocalActivity.current
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.LONG) }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Palette.NeonPurple.copy(alpha = 0.25f), Palette.Background, Palette.Background)),
        ),
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.WorkspacePremium, null, tint = Palette.Gold, modifier = Modifier.height(56.dp).width(56.dp))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.activation_title), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            val current = license
            val subtitle = when {
                current == null -> ""
                current.status == SubscriptionStatus.EXPIRED -> stringResource(R.string.activation_expired_body)
                current.status == SubscriptionStatus.TRIAL -> stringResource(R.string.activation_trial_body, current.daysLeft() ?: 0)
                current.isLifetime -> stringResource(R.string.account_lifetime)
                else -> stringResource(R.string.activation_active_body, current.expiresAt?.let { dateFormat.format(Date(it)) } ?: "")
            }
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = Palette.Muted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 720.dp))
            Spacer(Modifier.height(28.dp))

            Row(
                Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TrialCard(current, Modifier.weight(1f))
                LifetimeCard(
                    price = billing.price,
                    owned = current?.isLifetime == true || billing.owned,
                    busy = billing.busy,
                    storeUnavailable = storeUnavailable || billing.error != null,
                    onBuy = { if (activity == null || !viewModel.purchase(activity)) storeUnavailable = true },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(28.dp))

            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.SurfaceElevated.copy(alpha = 0.9f)).padding(24.dp),
            ) {
                Text(stringResource(R.string.activation_how_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.activation_how_body), style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.account_device_id), style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, modifier = Modifier.weight(1f))
                    Text(
                        current?.deviceId ?: "",
                        style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp),
                        color = Palette.ElectricBlue,
                    )
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.uppercase(); viewModel.clearResult() },
                    modifier = Modifier.fillMaxWidth().dpadTextField(activateFocus),
                    singleLine = true,
                    label = { Text(stringResource(R.string.activation_key)) },
                    placeholder = { Text("XXXX-XXXX-XXXX-XXXX", color = Palette.Muted) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    isError = result is ActivationResult.Invalid,
                    supportingText = {
                        when (val r = result) {
                            is ActivationResult.Invalid -> Text(stringResource(R.string.activation_invalid), color = Palette.Danger)
                            is ActivationResult.Success -> Text(stringResource(R.string.activation_success, stringResource(r.plan.label())), color = Palette.Success)
                            null -> Unit
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Palette.Gold,
                        unfocusedBorderColor = Palette.SurfaceHighest,
                        cursorColor = Palette.Gold,
                    ),
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlowButton(stringResource(R.string.activation_activate), { viewModel.activate(key) }, icon = Icons.Filled.CheckCircle, modifier = Modifier.focusRequester(activateFocus))
                    GlowButton(
                        stringResource(if (result is ActivationResult.Success || current?.isUnlocked == true) R.string.action_done else R.string.account_details_open),
                        onDone,
                        primary = false,
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private val CardShape = RoundedCornerShape(20.dp)

/** Read-only status of the 7-day free trial. */
@Composable
private fun TrialCard(current: LicenseState?, modifier: Modifier = Modifier) {
    val trial = current?.status == SubscriptionStatus.TRIAL
    Column(
        modifier.clip(CardShape).background(Palette.SurfaceElevated.copy(alpha = 0.85f)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Badge(
            stringResource(if (trial) R.string.account_status_trial else if (current?.status == SubscriptionStatus.ACTIVE) R.string.account_status_active else R.string.account_status_expired),
            color = if (trial) Palette.ElectricBlue else if (current?.status == SubscriptionStatus.ACTIVE) Palette.Success else Palette.Danger,
            textColor = Palette.Background,
        )
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.plan_trial_title, LicenseRepository.TRIAL_DAYS), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            if (trial) stringResource(R.string.account_days_left, current?.daysLeft() ?: 0) else stringResource(R.string.plan_trial_over),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = if (trial) Palette.OnSurface else Palette.Muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.plan_free), style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
    }
}

/** The single one-time purchase; the whole card is the Play Billing buy button. */
@Composable
private fun LifetimeCard(
    price: String,
    owned: Boolean,
    busy: Boolean,
    storeUnavailable: Boolean,
    onBuy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = rememberInteractionSource()
    val focused by rememberFocusState(interaction)
    Column(
        modifier
            .focusGlow(interaction, CardShape, focusedScale = 1.03f, borderWidth = 2.dp, glowColor = Palette.Gold)
            .clip(CardShape)
            .background(if (focused) Palette.SurfaceHighest else Palette.SurfaceElevated.copy(alpha = 0.85f))
            .clickable(interactionSource = interaction, indication = null, enabled = !owned && !busy, onClick = onBuy)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Badge(stringResource(R.string.plan_best_value), color = Palette.Gold, textColor = Palette.Background)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.plan_lifetime_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(price, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = Palette.Gold)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.plan_once), style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ShoppingCart, null, tint = if (owned) Palette.Success else Palette.Gold, modifier = Modifier.height(18.dp).width(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(if (owned) R.string.plan_owned else R.string.plan_buy_google_play),
                style = MaterialTheme.typography.labelLarge,
                color = if (owned) Palette.Success else Palette.OnSurface,
            )
        }
        if (storeUnavailable && !owned) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.plan_store_unavailable), style = MaterialTheme.typography.labelSmall, color = Palette.Muted, textAlign = TextAlign.Center)
        }
    }
}
