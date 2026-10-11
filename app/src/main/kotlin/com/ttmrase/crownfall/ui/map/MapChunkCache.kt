package com.ttmrase.crownfall.ui.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.ttmrase.crownfall.core.map.WorldMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * ワールドマップの地面を 8×8 タイルのチャンクごとにビットマップへ焼いて貯める（M11 T11.4）。
 * 画面上のタイルの大きさに合わせて 12 / 32 / 64 / 96px の段階で焼く（12px は飾りなしの LOD）。
 * 焼くのはバックグラウンド。まだ無いチャンクは null を返し、焼けたら [version] を進めて再描画させる。
 * 容量は [maxBytes]（アクセス順の LRU。既定 32MB。スプライトの 56MB とは別に、マップ画面を開いている間だけ使う）。
 */
internal class MapChunkCache(
    private val world: WorldMap,
    private val seed: Long,
    private val scope: CoroutineScope,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val roads = MapArt.roads(world)
    private val images = LinkedHashMap<Long, ImageBitmap>(64, 0.75f, true)
    private var bytes = 0L
    private val loading = ConcurrentHashMap.newKeySet<Long>()

    /** 焼き終わるたびに増える（描画が読んで再描画の目印にする）。 */
    var version by mutableIntStateOf(0)
        private set

    val usedBytes: Long get() = synchronized(images) { bytes }
    val chunkCount: Int get() = synchronized(images) { images.size }

    /** 解像度（1 タイルの px）が違うものは別のビットマップ。levels の添字を鍵に入れる。 */
    private fun key(
        cx: Int,
        cy: Int,
        px: Int,
    ): Long = (cx.toLong() shl KEY_X_SHIFT) or (cy.toLong() shl LEVEL_BITS) or levelIndex(px).toLong()

    private val failed = ConcurrentHashMap.newKeySet<Long>()

    /**
     * チャンク (cx, cy) の、1 タイル [px] の絵。まだ焼けていなければ null を返して焼き始める
     * （解像度を切り替えた直後は、焼けるまで別の解像度の絵があればそれを返す＝拡大縮小して描く）。
     */
    fun get(
        cx: Int,
        cy: Int,
        px: Int,
    ): ImageBitmap? {
        val k = key(cx, cy, px)
        synchronized(images) { images[k] }?.let { return it }
        if (k !in failed && loading.add(k)) {
            scope.launch(Dispatchers.Default) {
                try {
                    val made = render(cx, cy, px)
                    synchronized(images) {
                        images[k] = made
                        bytes += made.width.toLong() * made.height * BYTES_PER_PIXEL
                        trim()
                    }
                    version++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // メモリ不足など。落とさず、平らな色のままにして、このチャンクは再試行しない
                    failed.add(k)
                } finally {
                    loading.remove(k)
                }
            }
        }
        return synchronized(images) { LEVELS.firstNotNullOfOrNull { images[key(cx, cy, it)] } }
    }

    private fun trim() {
        val it = images.entries.iterator()
        while (bytes > maxBytes && it.hasNext()) {
            val e = it.next()
            bytes -= e.value.width.toLong() * e.value.height * BYTES_PER_PIXEL
            it.remove()
        }
    }

    private fun render(
        cx: Int,
        cy: Int,
        px: Int,
    ): ImageBitmap {
        val side = MapArt.CHUNK * px
        val image = ImageBitmap(side, side)
        val scopeDraw = CanvasDrawScope()
        scopeDraw.draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(side.toFloat(), side.toFloat())) {
            with(MapArt) { drawChunk(world, cx, cy, px.toFloat(), seed, roads, px >= DETAIL_MIN_PX) }
        }
        return image
    }

    /** 道の折れ線（タイル座標）。ライブ層で橋や標識を足すときに使う。 */
    fun roadLines(): List<List<Offset>> = roads

    companion object {
        /** 1 タイルの px の段階（低い → 高い）。画面上のタイルの大きさに合わせて選ぶ。 */
        val LEVELS = intArrayOf(12, 32, 64, 96)
        const val LO_PX = 12

        /** この px 以上で飾り（木・花・岩など）を焼く。 */
        const val DETAIL_MIN_PX = 32
        private const val BYTES_PER_PIXEL = 4L
        private const val KEY_X_SHIFT = 34
        private const val LEVEL_BITS = 2
        private const val MAX_UPSCALE = 2f
        const val DEFAULT_MAX_BYTES = 32L * 1024 * 1024

        private fun levelIndex(px: Int): Int = LEVELS.indexOf(px).coerceAtLeast(0)

        /**
         * 画面上の 1 タイルが [tilePx] px のときに焼く解像度。拡大が [MAX_UPSCALE] 倍までに収まる最小の段階
         * （最大は 96px。端末の密度に依らず、見た目の大きさで決める）。
         */
        fun resolutionFor(tilePx: Float): Int = LEVELS.firstOrNull { it * MAX_UPSCALE >= tilePx } ?: LEVELS.last()

        fun newScope(): CoroutineScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    }
}
