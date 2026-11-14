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
 * - 間に合っていないときは、半分の間合い（約 30fps）に落とす。毎フレーム間に合わずに不規則にカクつくより、
 *   等間隔の 30fps のほうがなめらかに見える。間に合っていないかは 2 通りで見る:
 *   1. 描画の記録（Canvas のラムダ）にかかる時間の移動平均が [COST_HIGH_MS] を超えた（CPU 側が重い）。
 *      [COST_LOW_MS] を下回ったら戻す（しきい値を 2 つにして、行ったり来たりしない）。
 *   2. フレームの合図の間隔が、画面の 1 フレームより長い状態が続いた（= フレームが落ちている。GPU の塗りが重いときは、
 *      記録の時間は短いままなので 1 では分からない）。落としている間は余裕が測れないので、しばらくしてから試し直し、
 *      すぐまた落ちるなら試す間隔を倍に延ばす。
 */
internal class FramePacer(
    private val minGapNanos: Long = MIN_GAP_NANOS,
    private val slowGapNanos: Long = SLOW_GAP_NANOS,
) {
    private var last = NEVER
    private var seen = NEVER
    private var costMs = 0f
    private var costSlow = false

    /** 画面の 1 フレームの長さ（ns）。合図の間隔の、最近の最短。 */
    private var baseGap = 0f

    /** 合図の間隔 / [baseGap] の移動平均（1 = 落ちていない、2 = 1 つおきに落ちている）。 */
    private var dropRatio = 1f
    private var steady = 0
    private var jankSlow = false
    private var retryAt = 0L
    private var backoff = RETRY_MIN_NANOS
    private var fastSince = NEVER

    /** 重くて、半分の間合いに落としているか。 */
    val slowed: Boolean get() = costSlow || jankSlow

    /** 描画の記録にかかる時間の移動平均（ms）。 */
    val averageCostMs: Float get() = costMs

    /** フレームの時刻 [frameNanos] に描き直すなら true（そのフレームを「描いた」として覚える）。毎フレームの合図ごとに呼ぶ。 */
    fun accept(frameNanos: Long): Boolean {
        observe(frameNanos)
        val gap = if (slowed) slowGapNanos else minGapNanos
        // 時計が巻き戻ったとき（テストの仮想時計の作り直しなど）は、待たずに描く
        if (last != NEVER && frameNanos >= last && frameNanos - last < gap) return false
        last = frameNanos
        return true
    }

    /** 合図の間隔から、フレームが落ちているかを見る。 */
    private fun observe(now: Long) {
        val prev = seen
        seen = now
        if (prev == NEVER || now <= prev) return
        val gap = (now - prev).toFloat()
        if (gap > PAUSE_NANOS) {
            // 画面が隠れていた・止まっていた。測り直す
            dropRatio = 1f
            steady = 0
            return
        }
        // リフレッシュレートが下がったときに追従できるよう、最短の記録を少しずつ緩める
        baseGap = if (baseGap == 0f) gap else minOf(baseGap * BASE_DRIFT, gap)
        if (jankSlow) {
            if (now >= retryAt) {
                jankSlow = false
                dropRatio = 1f
                steady = 0
                fastSince = now
            }
            return
        }
        // 画面を開いた直後（絵の読み込みなど）の乱れは数えない。1 回の引っかかりで落とさないよう、1 回ぶんの重みも抑える
        if (steady < SETTLE_FRAMES) {
            steady++
            return
        }
        dropRatio += ((gap / baseGap).coerceAtMost(DROP_CLAMP) - dropRatio) * SMOOTHING
        if (dropRatio > DROP_HIGH) {
            jankSlow = true
            val again = fastSince != NEVER && now - fastSince < backoff
            backoff = if (again) minOf(backoff * 2, RETRY_MAX_NANOS) else RETRY_MIN_NANOS
            retryAt = now + backoff
        }
    }

    /** 1 回の描画の記録にかかった時間を知らせる。 */
    fun reportCost(nanos: Long) {
        val ms = nanos.coerceAtLeast(0L) / NANOS_PER_MS
        costMs += (ms - costMs) * SMOOTHING
        if (!costSlow && costMs > COST_HIGH_MS) {
            costSlow = true
        } else if (costSlow && costMs < COST_LOW_MS) {
            costSlow = false
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

        /** 合図の間隔の平均が 1 フレームの 1.4 倍を超えたら（およそ 3 回に 1 回より多く落ちている）、落ちているとみなす。 */
        const val DROP_HIGH = 1.4f

        /** 落としてから試し直すまでの時間。すぐまた落ちるたびに倍にする。 */
        const val RETRY_MIN_NANOS = 20_000_000_000L
        const val RETRY_MAX_NANOS = 320_000_000_000L
        private const val DROP_CLAMP = 3f
        private const val SETTLE_FRAMES = 90
        private const val PAUSE_NANOS = 250_000_000f
        private const val BASE_DRIFT = 1.0005f
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
