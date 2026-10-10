package com.ttmrase.crownfall.ui.territory

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** ラスタライズ済みのスプライト。[ax] [ay] は viewport 上の原点（足元の中心）。 */
internal class Sprite(
    val image: ImageBitmap,
    val vw: Float,
    val vh: Float,
    val ax: Float,
    val ay: Float,
)

/**
 * VectorDrawable を 1 度だけビットマップにしておく（毎フレーム Path を組み立てないため）。
 * 読み込みはバックグラウンド。[get] は未読み込みなら null を返し、読み込み完了で [version] を進めて再描画させる。
 */
internal class SpriteCache private constructor(
    context: Context,
    private val scope: CoroutineScope,
    private val rasterScale: Float,
    private val maxBytes: Long,
) {
    private val app = context.applicationContext

    /** アクセス順の LRU。[bytes] が [maxBytes] を超えたら古いものから捨てる（再び描くときに読み直す）。 */
    private val sprites = LinkedHashMap<Int, Sprite>(64, 0.75f, true)
    private var bytes = 0L
    private val loading = ConcurrentHashMap.newKeySet<Int>()

    /** 読み込みが終わるたびに増える（描画が読んで再描画の目印にする）。 */
    var version by mutableIntStateOf(0)
        private set

    fun get(id: Int): Sprite? {
        synchronized(sprites) { sprites[id] }?.let { return it }
        if (loading.add(id)) {
            scope.launch(Dispatchers.Default) {
                val made = rasterize(id)
                if (made != null) {
                    synchronized(sprites) {
                        sprites[id] = made
                        bytes += made.image.width.toLong() * made.image.height * BYTES_PER_PIXEL
                        trim()
                    }
                }
                loading.remove(id)
                if (made != null) version++
            }
        }
        return null
    }

    private fun trim() {
        val it = sprites.entries.iterator()
        while (bytes > maxBytes && it.hasNext()) {
            val e = it.next()
            bytes -= e.value.image.width.toLong() * e.value.image.height * BYTES_PER_PIXEL
            it.remove()
        }
    }

    /** 先に読み込んでおく（画面を開いたとき）。 */
    fun preload(ids: Collection<Int>) {
        ids.forEach { get(it) }
    }

    private fun rasterize(id: Int): Sprite? {
        val drawable = ContextCompat.getDrawable(app, id) ?: return null
        val density = app.resources.displayMetrics.density
        val vw = drawable.intrinsicWidth / density
        val vh = drawable.intrinsicHeight / density
        val w = (vw * rasterScale).roundToInt().coerceAtLeast(1)
        val h = (vh * rasterScale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawable.mutate().setBounds(0, 0, w, h)
        drawable.draw(Canvas(bitmap))
        val (ax, ay) =
            when {
                vw > WIDE_VIEWPORT -> BUILDING_ANCHOR_X to BUILDING_ANCHOR_Y
                vh < SHORT_VIEWPORT -> SMALL_ANCHOR_X to WALL_ANCHOR_Y
                else -> SMALL_ANCHOR_X to DECOR_ANCHOR_Y
            }
        return Sprite(bitmap.asImageBitmap(), vw, vh, ax, ay)
    }

    companion object {
        private const val BYTES_PER_PIXEL = 4L
        private const val MAX_BYTES = 56L * 1024 * 1024

        @Volatile private var instance: SpriteCache? = null

        /** アプリ全体で 1 つ。城内に戻るたびに作り直さない（ラスタライズ済みを使い回す）。 */
        fun shared(context: Context): SpriteCache =
            instance ?: synchronized(this) {
                instance ?: SpriteCache(
                    context,
                    CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    RASTER_SCALE,
                    MAX_BYTES,
                ).also { instance = it }
            }

        /** viewport 1 単位あたりのビットマップ px。最大ズームで画面 px とほぼ等倍になる値。 */
        const val RASTER_SCALE = 2.5f
        private const val WIDE_VIEWPORT = 100f
        private const val SHORT_VIEWPORT = 80f
        private const val BUILDING_ANCHOR_X = 64f
        private const val BUILDING_ANCHOR_Y = 120f
        private const val SMALL_ANCHOR_X = 32f
        private const val WALL_ANCHOR_Y = 48f
        private const val DECOR_ANCHOR_Y = 80f
    }
}
