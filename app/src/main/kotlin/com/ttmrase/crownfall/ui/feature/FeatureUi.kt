package com.ttmrase.crownfall.ui.feature

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.feature.Feature
import com.ttmrase.crownfall.core.feature.FeatureGate
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.ui.common.Sfx
import com.ttmrase.crownfall.ui.common.SfxId
import com.ttmrase.crownfall.ui.reward.LocalRewardFly
import com.ttmrase.crownfall.ui.reward.dispatchFly
import com.ttmrase.crownfall.ui.shop.Confetti
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val GOLD = Color(0xFFE8C766)
private val NAVY = Color(0xE6121A2E)
private const val LOCKED_CARD_ALPHA = 0.6f
private const val FLY_MS = 650
private const val FLY_START_SCALE = 2.4f
private const val FLY_END_SCALE = 0.45f
private const val NAV_TABS = 5
private const val ARROW_BOUNCE_DP = 8f
private const val ARROW_MS = 600
private const val ARROW_WIDTH_DP = 88f
private const val ARROW_BASE_DP = 84f

/** 未解放の機能をタップしたときのダイアログ: あと何 Lv かと「城をアップグレード」への導線。 */
@Composable
fun LockedFeatureDialog(
    feature: Feature,
    data: GameData,
    castleLv: Int,
    onUpgrade: () -> Unit,
    onDismiss: () -> Unit,
) {
    val need = FeatureGate.requiredLv(data, feature)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Text("🔒", fontSize = 28.sp) },
        title = { Text(stringResource(R.string.feature_locked_title, stringResource(feature.nameRes()))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(feature.descRes()))
                Text(
                    stringResource(R.string.feature_locked_remaining, need, (need - castleLv).coerceAtLeast(1)),
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        confirmButton = { TextButton(onClick = onUpgrade) { Text(stringResource(R.string.feature_upgrade_castle)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}

/** HUD の「次の解放: 同盟（城 Lv4）」のチップ。全部解放済みなら何も出さない。 */
@Composable
fun NextUnlockChip(
    state: GameState,
    data: GameData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val next = FeatureGate.next(state, data) ?: return
    val lv = FeatureGate.requiredLv(data, next)
    val label = stringResource(R.string.feature_next_chip, stringResource(next.nameRes()), lv)
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
        Text(
            "🔒 $label",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFFFE9A0),
            modifier =
                Modifier
                    .background(Color(0xAA121A2E), RoundedCornerShape(50))
                    .clickable(onClick = onClick)
                    .semantics { contentDescription = label }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * 解放の全画面演出（「新機能解放！」）。アイコンが飛び先（下部ナビのタブ位置）へ縮みながら飛ぶ。
 * [target] は飛び先のタブの番号（無ければ右端中央）。戻るで全部スキップ（[onSkipAll]）、OK で 1 つ進む（[onDone]）。
 */
@Composable
fun FeatureUnlockDialog(
    feature: Feature,
    target: Int?,
    onDone: () -> Unit,
    onSkipAll: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fly = remember(feature) { Animatable(0f) }
    var flying by remember(feature) { mutableStateOf(false) }
    LaunchedEffect(feature) { Sfx.play(context, SfxId.FANFARE) }
    Dialog(
        onDismissRequest = onSkipAll,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xCC000000))) {
            val density = LocalDensity.current
            val w = with(density) { maxWidth.toPx() }
            val h = with(density) { maxHeight.toPx() }
            val toX = if (target != null) w * (target + 0.5f) / NAV_TABS else w - with(density) { 28.dp.toPx() }
            val toY = if (target != null) h - with(density) { 28.dp.toPx() } else h / 2
            val p = fly.value
            if (!flying) Confetti(Modifier.fillMaxSize())
            Column(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                        .alpha(1f - p)
                        .background(NAVY, RoundedCornerShape(20.dp))
                        .border(2.dp, GOLD, RoundedCornerShape(20.dp))
                        .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.feature_unlock_title),
                    color = GOLD,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(64.dp))
                Text(
                    stringResource(feature.nameRes()),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(feature.descRes()),
                    color = Color(0xFFCFD6E6),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    enabled = !flying,
                    onClick = {
                        flying = true
                        scope.launch {
                            fly.animateTo(1f, tween(FLY_MS, easing = FastOutSlowInEasing))
                            onDone()
                        }
                    },
                ) { Text(stringResource(R.string.feature_unlock_ok)) }
            }
            // アイコン（カードの上から、ナビのタブ位置へ飛ぶ）
            val startX = w / 2
            val startY = h / 2 - with(density) { 70.dp.toPx() }
            val x = startX + (toX - startX) * p
            val y = startY + (toY - startY) * p
            val sc = FLY_START_SCALE + (FLY_END_SCALE - FLY_START_SCALE) * p
            Text(
                feature.emoji(),
                fontSize = 40.sp,
                modifier =
                    Modifier
                        .offset {
                            IntOffset(
                                (x - with(density) { 24.dp.toPx() }).roundToInt(),
                                (y - with(density) { 24.dp.toPx() }).roundToInt(),
                            )
                        }
                        .scale(sc),
            )
        }
    }
}

/** 最初の使用の報酬を受け取るカード（上部に重ねる）。 */
@Composable
fun FirstUseRewardCard(
    feature: Feature,
    onClaim: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .background(NAVY, RoundedCornerShape(14.dp))
                .border(1.dp, GOLD, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("🎁", fontSize = 22.sp)
        Text(
            stringResource(R.string.feature_reward_card, stringResource(feature.nameRes())),
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onClaim) { Text(stringResource(R.string.feature_reward_claim)) }
    }
}

/** 解放前の機能（ショップのピギーバンクなど）の場所に出す、鍵つきの小さなカード。 */
@Composable
fun LockedFeatureCard(
    feature: Feature,
    data: GameData,
    modifier: Modifier = Modifier,
) {
    val need = FeatureGate.requiredLv(data, feature)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .alpha(LOCKED_CARD_ALPHA)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("🔒", fontSize = 22.sp)
        Column(Modifier.weight(1f)) {
            Text(stringResource(feature.nameRes()), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.feature_locked_short, need), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 未解放の画面の代わりに出す案内（鍵・説明・あと何 Lv・「城をアップグレード」）。 */
@Composable
fun LockedScreenBody(
    feature: Feature,
    data: GameData,
    castleLv: Int,
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val need = FeatureGate.requiredLv(data, feature)
    Box(modifier.background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("🔒", fontSize = 48.sp)
            Text(
                stringResource(R.string.feature_locked_title, stringResource(feature.nameRes())),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(stringResource(feature.descRes()), textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.feature_locked_remaining, need, (need - castleLv).coerceAtLeast(1)),
                fontWeight = FontWeight.Bold,
            )
            Button(onClick = onUpgrade) { Text(stringResource(R.string.feature_upgrade_castle)) }
        }
    }
}

/**
 * 解放の演出（順番に 1 つずつ。戻るで全部スキップ）と、最初の使用の報酬カードを出す。
 * 旧セーブの初期化が済む前（initialized が false）は何も出さない。
 */
@Composable
fun FeatureOverlays(
    session: GameSession,
    data: GameData,
    target: (Feature) -> Int?,
    modifier: Modifier = Modifier,
    /** 解放の演出が出ている間 true（オファーを上に重ねないため。VIP のシネマと同じ扱い）。 */
    onActive: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val fly = LocalRewardFly.current
    val pendingFlow =
        remember(session, data) {
            session.state.map { s -> if (s == null) emptyList() else pendingUnlocks(s, data) }.distinctUntilChanged()
        }
    val pending by pendingFlow.collectAsStateWithLifecycle(emptyList())
    val active = pending.isNotEmpty()
    LaunchedEffect(active) { onActive(active) }
    DisposableEffect(Unit) { onDispose { onActive(false) } }
    val claimFlow =
        remember(session, data) {
            session.state.map { s -> if (s == null) emptyList() else claimableRewards(s, data) }.distinctUntilChanged()
        }
    val claimable by claimFlow.collectAsStateWithLifecycle(emptyList())
    pending.firstOrNull()?.let { feature ->
        FeatureUnlockDialog(
            feature = feature,
            target = target(feature),
            onDone = { scope.launch { session.dispatch { Actions.markFeatureCelebrated(it, feature, data) } } },
            onSkipAll = {
                scope.launch {
                    pending.forEach { f -> session.dispatch { Actions.markFeatureCelebrated(it, f, data) } }
                }
            },
        )
    }
    // 解放の演出が終わって、まだ使っていない機能には、下部ナビのタブを指す矢印を出す（初回の使用で消える）
    val arrowFlow =
        remember(session, data) {
            session.state.map { s -> if (s == null) null else tabArrowFeature(s, data, target) }.distinctUntilChanged()
        }
    val arrow by arrowFlow.collectAsStateWithLifecycle(null)
    arrow?.let { f ->
        val index = target(f)
        if (index != null && pending.isEmpty()) TabArrow(f, index, modifier)
    }
    val reward = claimable.firstOrNull()
    if (reward != null && pending.isEmpty()) {
        Box(modifier) {
            FirstUseRewardCard(
                feature = reward,
                onClaim = {
                    scope.launch {
                        session.dispatchFly(
                            fly,
                        ) { Actions.claimFeatureReward(it, reward, data) }
                    }
                },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

/** 解放済みで、演出をまだ見せていない機能（解放 Lv の昇順）。 */
internal fun pendingUnlocks(
    s: GameState,
    data: GameData,
): List<Feature> =
    if (!s.features.initialized) {
        emptyList()
    } else {
        Feature.entries
            .filter { FeatureGate.unlocked(s, data, it) && it.id !in s.features.celebrated }
            .sortedBy { FeatureGate.requiredLv(data, it) }
    }

/** 最初の使用の報酬を受け取れる機能。 */
internal fun claimableRewards(
    s: GameState,
    data: GameData,
): List<Feature> =
    Feature.entries.filter { FeatureGate.unlocked(s, data, it) && FeatureGate.rewardClaimable(s, data, it) }

/** 演出を見せ終えて、まだ使っていない機能のうち、下部ナビにタブがあるもの（最初の 1 つ）。 */
internal fun tabArrowFeature(
    s: GameState,
    data: GameData,
    target: (Feature) -> Int?,
): Feature? =
    if (!s.features.initialized) {
        null
    } else {
        Feature.entries.firstOrNull {
            FeatureGate.unlocked(s, data, it) &&
                it.id in s.features.celebrated &&
                it.id !in s.features.firstUsed &&
                target(it) != null
        }
    }

/** タブの上で跳ねる「▼ 機能名」の矢印（チュートリアル）。 */
@Composable
private fun TabArrow(
    feature: Feature,
    index: Int,
    modifier: Modifier = Modifier,
) {
    val bounce = rememberInfiniteTransition(label = "tabArrow")
    val dy by bounce.animateFloat(
        initialValue = 0f,
        targetValue = ARROW_BOUNCE_DP,
        animationSpec = infiniteRepeatable(tween(ARROW_MS), RepeatMode.Reverse),
        label = "tabArrowDy",
    )
    BoxWithConstraints(modifier) {
        val half = ARROW_WIDTH_DP / 2
        Text(
            "▼ " + stringResource(feature.nameRes()),
            color = Color(0xFFFFE9A0),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = maxWidth * (index + 0.5f) / NAV_TABS - half.dp, y = (-ARROW_BASE_DP + dy).dp)
                    .width(ARROW_WIDTH_DP.dp)
                    .background(NAVY, RoundedCornerShape(50))
                    .padding(vertical = 2.dp),
        )
    }
}
