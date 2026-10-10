package com.ttmrase.crownfall.ui.shop

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.shop.FakePurchase
import com.ttmrase.crownfall.core.shop.ProductView
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.ui.failureMessage
import kotlinx.coroutines.delay
import kotlin.random.Random

private enum class Stage { Confirm, Processing, Error, Done }

private const val NORMAL_MIN_MS = 1200L
private const val NORMAL_MAX_MS = 2800L
private const val SLOW_MIN_MS = 4000L
private const val SLOW_MAX_MS = 6000L
private const val FAST_MIN_MS = 500L
private const val FAST_MAX_MS = 1000L
private const val SLOW_ONE_IN = 10
private const val ERROR_PERCENT = 2
private const val COUNT_UP_MS = 1200
private const val PROCESSING_GUARD_MS = 9_000L

/**
 * 擬似決済シート（04 §5）。汎用デザインで、ブランド・ロゴ・実在の注文番号形式は使わない。通信は一切しない。
 * 流れ: お支払い → 処理中…（キャンセル不可。10% で長め）→ 2% でエラー（再試行は必ず成功）→ 購入完了（紙吹雪）。
 * 「購入が完了しました」を出す瞬間に [Actions.fakePurchase] を dispatch する。演出の乱数はこの UI だけで使う。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FakeBillingSheet(
    productId: String,
    session: GameSession,
    data: GameData,
    notify: (String) -> Unit,
    onClose: () -> Unit,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    // 商品の表示は開いた時点のものに固定する（購入後は残数などで変わるため）
    val view: ProductView? = remember(productId) { state?.let { FakePurchase.describe(it, productId, data) } }
    var stage by remember { mutableStateOf(Stage.Confirm) }
    var slowText by remember { mutableStateOf(false) }
    var retried by remember { mutableStateOf(false) }
    var dispatched by remember { mutableStateOf(false) }
    var order by remember { mutableStateOf<String?>(null) }
    var gemsBefore by remember { mutableLongStateOf(0L) }
    var gemsAfter by remember { mutableLongStateOf(0L) }
    val settings = state?.shop?.settings

    if (view == null) {
        LaunchedEffect(Unit) {
            notify(failureMessage(context, com.ttmrase.crownfall.core.sim.ActionFailure.OfferUnavailable))
            onClose()
        }
        return
    }

    BackHandler(enabled = stage == Stage.Processing) { /* 処理中（最長 6 秒）だけは戻れない */ }

    // 万一、処理中のまま進まなくなっても閉じられなくならないようにする
    LaunchedEffect(stage) {
        if (stage != Stage.Processing) return@LaunchedEffect
        delay(PROCESSING_GUARD_MS)
        if (stage == Stage.Processing) {
            if (dispatched) onClose() else stage = Stage.Error
        }
    }

    LaunchedEffect(stage) {
        if (stage != Stage.Processing) return@LaunchedEffect
        val long = settings?.longProcessing != false
        val slow = long && Random.nextInt(SLOW_ONE_IN) == 0
        slowText = slow
        val (lo, hi) =
            when {
                slow -> SLOW_MIN_MS to SLOW_MAX_MS
                long -> NORMAL_MIN_MS to NORMAL_MAX_MS
                else -> FAST_MIN_MS to FAST_MAX_MS
            }
        delay(Random.nextLong(lo, hi + 1))
        val canFail = settings?.errorAnimation != false && !retried
        if (canFail && Random.nextInt(100) < ERROR_PERCENT) {
            stage = Stage.Error
            return@LaunchedEffect
        }
        if (dispatched) return@LaunchedEffect
        dispatched = true
        gemsBefore = state?.gems ?: 0L
        when (val r = session.dispatch { Actions.fakePurchase(it, productId, data) }) {
            is ActionResult.Success -> {
                val p = r.events.filterIsInstance<GameEvent.Purchased>().firstOrNull()
                order = p?.let { FakePurchase.orderNumber(it.orderA, it.orderB) }
                gemsAfter = r.state.gems
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                stage = Stage.Done
            }
            is ActionResult.Failure -> {
                notify(failureMessage(context, r.reason))
                onClose()
            }
        }
    }

    val sheetState =
        rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { stage != Stage.Processing })
    ModalBottomSheet(
        onDismissRequest = { if (stage != Stage.Processing) onClose() },
        sheetState = sheetState,
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(
                modifier =
                    Modifier.fillMaxWidth().verticalScroll(
                        rememberScrollState(),
                    ).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (stage) {
                    Stage.Confirm ->
                        ConfirmBody(view, data) {
                            if (stage == Stage.Confirm) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                stage = Stage.Processing
                            }
                        }
                    Stage.Processing -> ProcessingBody(slowText)
                    Stage.Error ->
                        ErrorBody {
                            retried = true
                            stage = Stage.Processing
                        }
                    Stage.Done -> DoneBody(view, order, gemsBefore, gemsAfter, onClose = onClose)
                }
                Box(Modifier.height(16.dp))
            }
            if (stage == Stage.Done) Confetti(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ConfirmBody(
    view: ProductView,
    data: GameData,
    onBuy: () -> Unit,
) {
    val context = LocalContext.current
    Text(
        stringResource(R.string.billing_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
    HorizontalDivider()
    Text(productName(context, view.sku), style = MaterialTheme.typography.titleMedium)
    ContentsLines(view.contents, data, extraGems = view.firstDoubleGems)
    if (view.firstDoubleGems > 0) {
        Text(
            stringResource(R.string.billing_first_double),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelLarge,
        )
    }
    HorizontalDivider()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.billing_method))
        Text(stringResource(R.string.billing_card_last4))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.billing_total), fontWeight = FontWeight.Bold)
        Text(formatYen(view.priceYen), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
    }
    Button(onClick = onBuy, modifier = Modifier.fillMaxWidth().height(56.dp).pulsing()) {
        Text(stringResource(R.string.billing_one_tap), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ProcessingBody(slow: Boolean) {
    Box(Modifier.height(24.dp))
    CircularProgressIndicator()
    Text(
        stringResource(if (slow) R.string.billing_confirming else R.string.billing_processing),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(stringResource(R.string.billing_do_not_close), style = MaterialTheme.typography.bodySmall)
    Box(Modifier.height(24.dp))
}

@Composable
private fun ErrorBody(onRetry: () -> Unit) {
    Text(
        stringResource(R.string.billing_error),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.titleMedium,
    )
    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text(stringResource(R.string.billing_retry))
    }
}

@Composable
private fun DoneBody(
    view: ProductView,
    order: String?,
    gemsBefore: Long,
    gemsAfter: Long,
    onClose: () -> Unit,
) {
    AnimatedCheck(color = MaterialTheme.colorScheme.primary)
    Text(
        stringResource(R.string.billing_done),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
    order?.let { Text(stringResource(R.string.billing_order, it), style = MaterialTheme.typography.bodySmall) }
    Text(formatYen(view.priceYen), style = MaterialTheme.typography.titleMedium)
    if (gemsAfter != gemsBefore) {
        val count = remember { Animatable(gemsBefore.toFloat()) }
        LaunchedEffect(gemsAfter) { count.animateTo(gemsAfter.toFloat(), tween(COUNT_UP_MS)) }
        Text(
            stringResource(R.string.billing_gems_now, ShortNumber.format(count.value.toLong())),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
    }
    Text(stringResource(R.string.billing_receipt_sent), style = MaterialTheme.typography.bodySmall)
    OutlinedButton(
        onClick = onClose,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.common_close)) }
}
