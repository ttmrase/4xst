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
 * ズームが近いとき（[hi]）は 1 タイル 32px・飾りあり、遠いときは 12px・飾りなし（LOD）。
 * 焼くのはバックグラウンド。まだ無いチャンクは null を返し、焼けたら [version] を進めて再描画させる。
 * 容量は [maxBytes]（アクセス順の LRU。既定 20MB。スプライトの 56MB とは別に、マップ画面を開いている間だけ使う）。
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

    private fun key(
        cx: Int,
        cy: Int,
        hi: Boolean,
    ): Long = (cx.toLong() shl KEY_X_SHIFT) or (cy.toLong() shl 1) or (if (hi) 1L else 0L)

    fun get(
        cx: Int,
        cy: Int,
        hi: Boolean,
    ): ImageBitmap? {
        val k = key(cx, cy, hi)
        synchronized(images) { images[k] }?.let { return it }
        if (loading.add(k)) {
            scope.launch(Dispatchers.Default) {
                val made = render(cx, cy, hi)
                synchronized(images) {
                    images[k] = made
                    bytes += made.width.toLong() * made.height * BYTES_PER_PIXEL
                    trim()
                }
                loading.remove(k)
                version++
            }
        }
        return null
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
        hi: Boolean,
    ): ImageBitmap {
        val px = if (hi) HI_PX else LO_PX
        val side = MapArt.CHUNK * px
        val image = ImageBitmap(side, side)
        val scopeDraw = CanvasDrawScope()
        scopeDraw.draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(side.toFloat(), side.toFloat())) {
            with(MapArt) { drawChunk(world, cx, cy, px.toFloat(), seed, roads, hi) }
        }
        return image
    }

    /** 道の折れ線（タイル座標）。ライブ層で橋や標識を足すときに使う。 */
    fun roadLines(): List<List<Offset>> = roads

    companion object {
        const val HI_PX = 32
        const val LO_PX = 12
        private const val BYTES_PER_PIXEL = 4L
        private const val KEY_X_SHIFT = 33
        const val DEFAULT_MAX_BYTES = 20L * 1024 * 1024

        /** 画面上の 1 タイルがこれ以上（px）なら hi を使う。 */
        const val HI_MIN_TILE_PX = 32f

        fun newScope(): CoroutineScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    }
}
