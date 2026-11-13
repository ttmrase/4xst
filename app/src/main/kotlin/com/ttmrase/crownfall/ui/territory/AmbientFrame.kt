package com.ttmrase.crownfall.ui.territory

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember

/**
 * 飾りの動き（住民・煙・旗・雲の影など）の再描画の間合いを決める。Compose に依存しない（JVM でテストできる）。
 * - ふだんは画面のリフレッシュに合わせて毎フレーム描く。ただし [MIN_GAP_NANOS] より短い間隔では描かない
 *   （120Hz の端末では 60fps、90Hz では 90fps。ゆっくりした飾りの動きに 120fps は要らず、電池を使うだけ）。
 * - 描画の記録（Canvas のラムダ）にかかる時間の移動平均が [COST_HIGH_MS] を超えたら、半分の間合い（約 30fps）に落とす。
 *   毎フレーム間に合わずに不規則にカクつくより、等間隔の 30fps のほうがなめらかに見える。[COST_LOW_MS] を下回ったら戻す
 *   （しきい値を 2 つにして、行ったり来たりしない）。
 */
internal class FramePacer(
    private val minGapNanos: Long = MIN_GAP_NANOS,
    private val slowGapNanos: Long = SLOW_GAP_NANOS,
) {
    private var last = NEVER
    private var costMs = 0f

    /** 描画が重くて、半分の間合いに落としているか。 */
    var slowed = false
        private set

    /** 描画の記録にかかる時間の移動平均（ms）。 */
    val averageCostMs: Float get() = costMs

    /** フレームの時刻 [frameNanos] に描き直すなら true（そのフレームを「描いた」として覚える）。 */
    fun accept(frameNanos: Long): Boolean {
        val gap = if (slowed) slowGapNanos else minGapNanos
        // 時計が巻き戻ったとき（テストの仮想時計の作り直しなど）は、待たずに描く
        if (last != NEVER && frameNanos >= last && frameNanos - last < gap) return false
        last = frameNanos
        return true
    }

    /** 1 回の描画の記録にかかった時間を知らせる。 */
    fun reportCost(nanos: Long) {
        val ms = nanos.coerceAtLeast(0L) / NANOS_PER_MS
        costMs += (ms - costMs) * SMOOTHING
        if (!slowed && costMs > COST_HIGH_MS) {
            slowed = true
        } else if (slowed && costMs < COST_LOW_MS) {
            slowed = false
        }
    }

    companion object {
        /** これより短い間隔では描き直さない（10.5ms: 60Hz と 90Hz は毎フレーム、120Hz と 144Hz は 1 つおき）。 */
        const val MIN_GAP_NANOS = 10_500_000L

        /** 重いときの間合い（27ms: 60 / 90 / 120Hz のどれでも 約 30fps の等間隔になる）。 */
        const val SLOW_GAP_NANOS = 27_000_000L

        /** 描画の記録だけで 1 フレーム（16.6ms）の 4 割を超えたら重いとみなす。 */
        const val COST_HIGH_MS = 7f
        const val COST_LOW_MS = 3.5f
        private const val SMOOTHING = 0.05f
        private const val NANOS_PER_MS = 1_000_000f
        private const val NEVER = Long.MIN_VALUE
    }
}

/**
 * 飾りの動きの「再描画の合図」。描画フェーズで読むと、[pacer] が認めたフレームごとに再描画だけが走る（再構図は走らない）。
 * [enabled] が false（端末のアニメーション無効）なら合図を出さず、静止画になる。
 * 無限アニメーションと同じ待ち方（withInfiniteAnimationFrameNanos）なので、Compose のテストは idle になれる
 * （autoAdvance のテストでは止まり、画面が見えていない間はフレームが来ないので何もしない）。
 */
@Composable
internal fun rememberAmbientFrame(
    pacer: FramePacer,
    enabled: Boolean,
): LongState {
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(pacer, enabled) {
        if (!enabled) return@LaunchedEffect
        // ラムダはループの外で 1 度だけ作る（毎フレーム確保しない）
        val onFrame: (Long) -> Unit = { now -> if (pacer.accept(now)) frame.longValue = now }
        while (true) withInfiniteAnimationFrameNanos(onFrame)
    }
    return frame
}

/**
 * 飾りの動きの時計（秒）。単調増加の時計を [AMBIENT_LOOP_MS] で折り返して Float にする
 * （起動からの経過時間をそのまま Float にすると、数日で 1 フレームより粗くなって動きが段になる）。
 * 折り返しの瞬間（1 時間に 1 度）だけ、周期の合わない動きが 1 フレーム跳ぶ。
 */
internal fun ambientSeconds(nowMs: Long): Float = (nowMs % AMBIENT_LOOP_MS) / AMBIENT_MS_PER_SEC

/** アニメーション無効のときに描く静止画の時刻（煙や旗が中ほどの形になる値）。 */
internal const val AMBIENT_STILL_SECONDS = 12.5f
private const val AMBIENT_LOOP_MS = 3_600_000L
private const val AMBIENT_MS_PER_SEC = 1000f
