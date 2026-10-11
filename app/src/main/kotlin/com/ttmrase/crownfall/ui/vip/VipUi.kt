package com.ttmrase.crownfall.ui.vip

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.VipLevelDef
import com.ttmrase.crownfall.core.feature.Feature
import com.ttmrase.crownfall.core.feature.FeatureGate
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.shop.VipCalculator
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.ui.common.Sfx
import com.ttmrase.crownfall.ui.common.SfxId
import com.ttmrase.crownfall.ui.common.collectCalmState
import com.ttmrase.crownfall.ui.shop.CoinShower
import com.ttmrase.crownfall.ui.theme.CrownFonts
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val PERK_STAGGER_MS = 450L
private const val PERK_FIRST_MS = 900L
private const val HOUR_SEC = 3600L
private const val TAU = (2 * PI).toFloat()
private const val TIER_SILVER = 5
private const val TIER_GOLD = 10
private const val TIER_MAX = 15
private const val BP_PER_PERCENT = 100L

/** VIP Lv に応じた枠の色（銅 → 銀 → 金 → 最高位は虹）。 */
internal fun vipColors(lv: Int): List<Color> =
    when {
        lv >= TIER_MAX ->
            listOf(
                Color(0xFFFF6B6B),
                Color(0xFFFFD86B),
                Color(0xFF6BFF9C),
                Color(0xFF6BC8FF),
                Color(0xFFB57BE0),
            )
        lv >= TIER_GOLD -> listOf(Color(0xFFFFE9A0), Color(0xFFFFC107), Color(0xFFD4A017))
        lv >= TIER_SILVER -> listOf(Color(0xFFF4F7FB), Color(0xFFC6CFDB), Color(0xFFA5B0BF))
        else -> listOf(Color(0xFFF3D2AE), Color(0xFFE0A56B), Color(0xFFCF8A4A))
    }

/** チャットなどに付ける VIP バッジ（金属質の小さな札）。VIP でなければ何も出さない。 */
@Composable
fun VipBadge(
    lv: Int,
    modifier: Modifier = Modifier,
) {
    if (lv <= 0) return
    Text(
        stringResource(R.string.alliance_vip, lv),
        color = Color(0xFF2A1B00),
        fontFamily = CrownFonts.Numeric,
        fontWeight = FontWeight.Black,
        style = MaterialTheme.typography.labelSmall,
        modifier =
            modifier
                .background(Brush.horizontalGradient(vipColors(lv)), RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 1.dp),
    )
}

/** 名前の頭文字の丸いアバター。VIP なら Lv の色の枠が付き、高位には王冠が乗る。 */
@Composable
fun VipAvatar(
    name: String,
    lv: Int,
    modifier: Modifier = Modifier,
) {
    val colors = vipColors(lv)
    Box(modifier = modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier =
                Modifier
                    .size(34.dp)
                    .drawBehind {
                        val c = Offset(size.width / 2f, size.height / 2f)
                        if (lv > 0) {
                            drawCircle(
                                Brush.sweepGradient(colors + colors.first(), c),
                                size.minDimension / 2f + 3.dp.toPx(),
                                c,
                                style = Stroke(3.dp.toPx()),
                            )
                        }
                    }
                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1), color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold)
        }
        if (lv >= TIER_GOLD) Text("👑", fontSize = 12.sp, modifier = Modifier.align(Alignment.TopCenter))
    }
}

/**
 * VIP 昇格の演出を出す（全画面。金貨の雨と後光、巨大な「VIP n」、解放された特典が順に現れる）。
 * 「最後に演出した Lv」は状態（ShopState）に保存するので、ログインボーナスでの昇格も、城内に入ったときに演出され、
 * 再起動しても二重には出ない。[hold] の間（決済シートが開いている間など）は出さずに待つ。
 * 表示中は [onActive] で知らせる（オファーを上に重ねないため）。戻る・OK・タップで閉じる。
 */
@Composable
fun VipCinematicHost(
    session: GameSession,
    data: GameData,
    hold: Boolean,
    onActive: (Boolean) -> Unit,
) {
    val state by session.collectCalmState()
    val scope = rememberCoroutineScope()
    val s = state
    val level = if (s == null) 0 else VipCalculator.level(s, data)
    val celebrated = s?.shop?.vipCelebrated ?: 0
    // 初回は現在の Lv にそろえるだけ（過去の昇格を遡って祝わない）
    LaunchedEffect(s != null, celebrated) {
        if (s != null && celebrated < 0) session.dispatch { Actions.markVipCelebrated(it, level) }
    }
    // 保存の反映は 5 秒サンプリングの state より早いので、閉じた Lv はここでも覚えておく
    var dismissedLv by remember(session) { mutableIntStateOf(-1) }
    // 段階解放: ショップ（SHOP）が解放されるまでは、VIP の演出は出さずに黙って追従させる
    val shopOpen = s != null && FeatureGate.unlocked(s, data, Feature.SHOP)
    LaunchedEffect(s != null, shopOpen, level, celebrated) {
        if (s != null && !shopOpen && celebrated in 0 until level) {
            session.dispatch {
                Actions.markVipCelebrated(
                    it,
                    level,
                )
            }
        }
    }
    val show = s != null && shopOpen && celebrated >= 0 && level > maxOf(celebrated, dismissedLv) && !hold
    LaunchedEffect(show) { onActive(show) }
    // 画面から外れたときに「表示中」を残さない（オファーが止まったままにならない）
    DisposableEffect(Unit) { onDispose { onActive(false) } }
    if (!show) return
    VipCinematic(
        lv = level,
        def = data.vip.levels.getOrNull(level - 1),
        onDismiss = {
            dismissedLv = level
            scope.launch { session.dispatch { Actions.markVipCelebrated(it, level) } }
        },
    )
}

@Composable
private fun VipCinematic(
    lv: Int,
    def: VipLevelDef?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val colors = vipColors(lv)
    LaunchedEffect(lv) {
        Sfx.play(context, SfxId.VIP)
        Sfx.vibrate(context, 180L)
        delay(PERK_FIRST_MS)
        Sfx.play(context, SfxId.FANFARE)
    }
    val transition = rememberInfiniteTransition(label = "vip")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = TAU,
        animationSpec = infiniteRepeatable(tween(16_000, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )
    val pop = remember(lv) { Animatable(0f) }
    LaunchedEffect(lv) { pop.animateTo(1f, tween(800)) }
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF08060F)).clickable(onClick = onDismiss)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2f, size.height * 0.3f)
                val unit = min(size.width, size.height)
                for (i in 0 until 14) {
                    val a = i * TAU / 14 + spin
                    drawLine(
                        colors[1].copy(alpha = 0.28f),
                        c,
                        c + Offset(cos(a) * unit * 1.2f, sin(a) * unit * 1.2f),
                        unit * 0.06f,
                    )
                }
                drawCircle(
                    Brush.radialGradient(listOf(colors[0].copy(alpha = 0.7f), Color.Transparent), c, unit * 0.55f),
                    unit * 0.55f,
                    c,
                )
            }
            CoinShower(Modifier.fillMaxSize())
            Column(
                modifier =
                    Modifier.align(
                        Alignment.TopCenter,
                    ).fillMaxWidth().padding(top = 110.dp, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "👑",
                    fontSize = 56.sp,
                    modifier =
                        Modifier.graphicsLayer {
                            scaleX = pop.value
                            scaleY = pop.value
                        },
                )
                Text(
                    stringResource(R.string.vip_promoted_title),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "VIP $lv",
                    style =
                        TextStyle(
                            brush = Brush.verticalGradient(colors),
                            fontFamily = CrownFonts.Numeric,
                            fontWeight = FontWeight.Black,
                            fontSize = 72.sp,
                        ),
                    modifier =
                        Modifier.graphicsLayer {
                            scaleX = 0.6f + 0.4f * pop.value
                            scaleY = 0.6f + 0.4f * pop.value
                        },
                )
            }
            Column(
                modifier =
                    Modifier.align(
                        Alignment.Center,
                    ).fillMaxWidth().padding(top = 220.dp, start = 32.dp, end = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (def != null) {
                    val lines = perkLines(def)
                    lines.forEachIndexed { i, text -> PerkReveal(text, delayMs = PERK_FIRST_MS + i * PERK_STAGGER_MS) }
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text(stringResource(R.string.common_ok)) }
            }
        }
    }
}

@Composable
private fun perkLines(def: VipLevelDef): List<String> {
    val lines = mutableListOf<String>()
    lines += stringResource(R.string.vip_perk_gather, percentText(def.gatherBp))
    if (def.offlineBonusSec > 0) {
        lines += stringResource(R.string.vip_perk_offline, (def.offlineBonusSec / HOUR_SEC).toInt())
    }
    if (def.gemSkipBp > 0) lines += stringResource(R.string.vip_perk_gem_skip, percentText(def.gemSkipBp))
    if (def.secondBuilder) lines += stringResource(R.string.vip_perk_builder)
    return lines
}

private fun percentText(bp: Long): String =
    if (bp % BP_PER_PERCENT == 0L) {
        (bp / BP_PER_PERCENT).toString()
    } else {
        "%.1f".format(
            bp / 100.0,
        )
    }

@Composable
private fun PerkReveal(
    text: String,
    delayMs: Long,
) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs)
        a.animateTo(1f, tween(400))
    }
    Text(
        "✦ $text",
        color = Color(0xFFFFE9A0),
        style = MaterialTheme.typography.titleSmall,
        textAlign = TextAlign.Center,
        modifier =
            Modifier.graphicsLayer {
                alpha = a.value
                translationY = (1f - a.value) * 24f
            },
    )
}
