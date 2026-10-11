package com.ttmrase.crownfall.ui.shop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.feature.Feature
import com.ttmrase.crownfall.core.feature.FeatureGate
import com.ttmrase.crownfall.core.model.Offer
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.shop.OfferEngine
import com.ttmrase.crownfall.core.shop.ProductIds
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.data.SessionCoordinator
import com.ttmrase.crownfall.ui.common.collectCalmState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val REFRESH_MS = 5_000L
private const val CLOSE_DELAY_MS = 2_000L
private const val CLOSE_ALPHA = 0.35f
private const val STEP_NONE = 0
private const val STEP_ANNOUNCE = 1
private const val STEP_GIFT = 2

/**
 * 全画面のオファー表示（T6.4）。キューの先頭（閉じていない・期限内）を全画面ポップで出す。
 * 閉じる「×」は小さく薄く、2 秒遅れて出る（04 §4 の確認優位 UI）。「戻る」はいつでも閉じられ、閉じたあとは
 * 一定時間は次を出さず、「戻る」で閉じたらそのセッションの自動ポップアップを止める。購入は [onBuy]（決済シート）へ。
 * 1 日の最初のログインでは、本日限定 → イベント告知 → 同盟ギフトの 3 連ポップを出す。
 * [busy] が true（決済シートが開いている）の間は出さない。
 */
@Composable
fun OfferPresenter(
    session: GameSession,
    data: GameData,
    coordinator: SessionCoordinator,
    busy: Boolean,
    onBuy: (String) -> Unit,
    onIdle: () -> Unit,
) {
    val state by session.collectCalmState()
    val chain by coordinator.loginChain.collectAsStateWithLifecycle()
    var step by remember { mutableIntStateOf(STEP_NONE) }
    val scope = rememberCoroutineScope()
    val s = state ?: return

    // 前景（STARTED）の間だけ期限切れを片付ける。背景では dispatch（進行と保存）を走らせない。
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(session, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(REFRESH_MS)
                session.dispatch { Actions.refreshOffers(it, data) }
            }
        }
    }

    // 閉じた直後（間隔内）や「戻る」で止めたあとは自動では出さない。棚とバッジには残る
    val offer = OfferEngine.nextPopup(s, data)
    // ギフトの段は、同盟が解放済みで、開けるギフトがあるときだけ（未解放だと openGift が断って無反応になる）
    val giftOpen = s.alliance.gifts.isNotEmpty() && FeatureGate.unlocked(s, data, Feature.ALLIANCE)
    val stopped = s.shop.popupsStopped
    // 閉じた直後に残りが無くなったら、ログインの連鎖の続きや退出の確認へ進める
    LaunchedEffect(offer == null, chain, busy, stopped) {
        if (offer == null && !busy) {
            if (chain && step == STEP_NONE) {
                // 「戻る」で閉じたあとは、ログインの連鎖ごと終える
                if (stopped) {
                    coordinator.consumeLoginChain()
                } else if (FeatureGate.unlocked(s, data, Feature.EVENTS)) {
                    step = STEP_ANNOUNCE
                } else if (giftOpen) {
                    // イベントの告知は EVENTS の解放後だけ。ギフトの段だけ出す
                    step = STEP_GIFT
                } else {
                    coordinator.consumeLoginChain()
                }
            }
            onIdle()
        }
    }

    if (offer != null && !busy) {
        OfferPopup(
            offer = offer,
            data = data,
            nowEpochSec = s.nowEpochSec,
            onBuy = { onBuy(ProductIds.offer(offer.id)) },
            onClose = { scope.launch { session.dispatch { Actions.dismissOffer(it, offer.id) } } },
            onBack = { scope.launch { session.dispatch { Actions.dismissOffer(it, offer.id, byBack = true) } } },
        )
    } else if (!busy) {
        when (step) {
            STEP_ANNOUNCE ->
                AlertDialog(
                    onDismissRequest = {
                        // 戻る・外のタップで連鎖ごと終える
                        step = STEP_NONE
                        coordinator.consumeLoginChain()
                    },
                    title = { Text(stringResource(R.string.login_event_title)) },
                    text = { Text(stringResource(R.string.login_event_body)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (giftOpen) {
                                    step = STEP_GIFT
                                } else {
                                    step = STEP_NONE
                                    coordinator.consumeLoginChain()
                                }
                            },
                        ) { Text(stringResource(R.string.common_ok)) }
                    },
                )
            STEP_GIFT ->
                AlertDialog(
                    onDismissRequest = {
                        step = STEP_NONE
                        coordinator.consumeLoginChain()
                    },
                    title = { Text(stringResource(R.string.login_gift_title)) },
                    text = { Text(stringResource(R.string.login_gift_body, s.alliance.gifts.size)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                s.alliance.gifts.firstOrNull()?.let { g ->
                                    scope.launch { session.dispatch { Actions.openGift(it, g.id, data) } }
                                }
                                step = STEP_NONE
                                coordinator.consumeLoginChain()
                            },
                        ) { Text(stringResource(R.string.login_gift_open)) }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                step = STEP_NONE
                                coordinator.consumeLoginChain()
                            },
                        ) { Text(stringResource(R.string.login_gift_later)) }
                    },
                )
        }
    }
}

@Composable
private fun OfferPopup(
    offer: Offer,
    data: GameData,
    nowEpochSec: Long,
    onBuy: () -> Unit,
    onClose: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var closeVisible by remember(offer.id) { mutableStateOf(false) }
    LaunchedEffect(offer.id) {
        delay(CLOSE_DELAY_MS)
        closeVisible = true
    }
    val closeAlpha by animateFloatAsState(if (closeVisible) CLOSE_ALPHA else 0f, tween(400), label = "closeAlpha")
    // 「戻る」はいつでも効く。閉じるのが遅く出る「×」と、外のタップで閉じない作りだけが量産型の演出
    Dialog(
        onDismissRequest = onBack,
        properties =
            DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.92f)) {
            Box(Modifier.fillMaxSize().padding(16.dp)) {
                Column(
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    HeroPortrait(heroId = offer.contents.heroShards.keys.firstOrNull())
                    Text(
                        stringResource(headline(offer)),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.surface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val name = productName(context, offer.sku)
                    OfferCard(
                        title = if (offer.revived) stringResource(R.string.offer_revived, name) else name,
                        tagline = offerTagline(context, offer),
                        contents = offer.contents,
                        data = data,
                        priceYen = offer.priceYen,
                        listValueYen = offer.listValueYen,
                        badge = offer.badge,
                        expiresEpochSec = offer.expiresEpochSec,
                        nowEpochSec = nowEpochSec,
                        stockLeft = null,
                        oneTime = true,
                        onBuy = onBuy,
                    )
                    Text(
                        stringResource(R.string.offer_close_warning),
                        color = MaterialTheme.colorScheme.surface,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(
                    onClick = onClose,
                    enabled = closeVisible,
                    modifier = Modifier.align(Alignment.TopEnd).alpha(closeAlpha),
                ) {
                    val closeLabel = stringResource(R.string.common_close)
                    Text(
                        "×",
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.semantics { contentDescription = closeLabel },
                    )
                }
            }
        }
    }
}

private fun headline(offer: Offer): Int =
    when {
        offer.revived -> R.string.headline_revived
        offer.trigger == "exitIntent" -> R.string.headline_wait
        offer.trigger == "login" -> R.string.headline_today
        else -> R.string.headline_special
    }
