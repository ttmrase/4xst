package com.ttmrase.crownfall.ui.territory

/** 領地ビューのカメラ（ワールド中心 [cx] [cy] と倍率 [sc]）。ジェスチャの計算を純関数に切り出したもの。 */
internal data class CamState(
    val cx: Float,
    val cy: Float,
    val sc: Float,
)

/** ズームとパンの境界つき計算（JVM でテストできるよう Compose に依存しない）。 */
internal object CameraMath {
    /** 画面幅がこの幅のワールド範囲に見える倍率が、ズームイン側の上限。 */
    const val ZOOM_IN_SPAN = 300f

    /** 画面幅がこの幅のワールド範囲に見える倍率が、ズームアウト側の下限。 */
    const val ZOOM_OUT_SPAN = 1000f

    /** 初期表示の幅。 */
    const val INITIAL_SPAN = 640f

    /** 領地の外接矩形（現在の城壁）から、画面の端がはみ出してよい余白（ワールド単位。森が少し見える程度）。 */
    const val EDGE_MARGIN = 28f

    /** 画面の上端から HUD が覆う割合。初期カメラはこれを除いた可視領域の中央に領地を置く。 */
    const val HUD_FRACTION = 0.15f

    fun minScale(viewW: Float): Float = viewW / ZOOM_OUT_SPAN

    fun maxScale(viewW: Float): Float = viewW / ZOOM_IN_SPAN

    /** 領地の中心 ([centerX], [centerY]) が、HUD を除いた可視領域の中央に来る初期カメラ。 */
    fun initial(
        viewW: Float,
        viewH: Float,
        centerX: Float,
        centerY: Float,
    ): CamState {
        val sc = (viewW / INITIAL_SPAN).coerceIn(minScale(viewW), maxScale(viewW))
        return CamState(centerX, centerY - viewH * HUD_FRACTION / 2f / sc, sc)
    }

    /**
     * ピンチ [zoom] とパン [panX] [panY]（画面 px）を、重心 ([focusX], [focusY]) を保ったまま適用する。
     * 倍率は [minScale]..[maxScale]、中心は PAN の範囲に収める。画面サイズが 0 以下のときは何もしない。
     */
    fun transform(
        cam: CamState,
        viewW: Float,
        viewH: Float,
        focusX: Float,
        focusY: Float,
        panX: Float,
        panY: Float,
        zoom: Float,
        limitHalf: Float = PlotLayout.WALL_HALF.last(),
    ): CamState {
        if (viewW <= 0f || viewH <= 0f || cam.sc <= 0f) return cam
        val old = cam.sc
        val target = (old * zoom).coerceIn(minScale(viewW), maxScale(viewW))
        val dx = focusX - viewW / 2f
        val dy = focusY - viewH / 2f
        val wx = cam.cx + dx / old
        val wy = cam.cy + dy / old
        return clampToTerritory(
            CamState(wx - dx / target - panX / target, wy - dy / target - panY / target, target),
            limitHalf,
            viewW,
            viewH,
        )
    }

    /**
     * 画面に映る範囲が、領地（[PlotLayout.MID] を中心とする一辺 2 x [limitHalf] の正方形＝城壁と区画と城下町の外接矩形）に
     * [EDGE_MARGIN] を足した範囲からはみ出さないよう、中心を収める。領地が画面より小さい軸では中心に置く。
     * 森と川ばかりが映る場所へはパンできない。
     */
    fun clampToTerritory(
        cam: CamState,
        limitHalf: Float,
        viewW: Float,
        viewH: Float,
    ): CamState {
        val mid = PlotLayout.MID
        val halfW = viewW / (2f * cam.sc)
        val halfH = viewH / (2f * cam.sc)
        val minX = PlotLayout.wx(mid - limitHalf, mid + limitHalf) - EDGE_MARGIN
        val maxX = PlotLayout.wx(mid + limitHalf, mid - limitHalf) + EDGE_MARGIN
        val minY = PlotLayout.wy(mid - limitHalf, mid - limitHalf) - EDGE_MARGIN
        val maxY = PlotLayout.wy(mid + limitHalf, mid + limitHalf) + EDGE_MARGIN
        // 領地が画面より小さい軸では、建物の高さと HUD ぶんだけずらして、初期カメラと同じ位置に置く（最初の操作で跳ばない）
        val restY = (minY + maxY) / 2f - PlotLayout.VISUAL_LIFT - viewH * HUD_FRACTION / 2f / cam.sc
        return cam.copy(
            cx = fit(cam.cx, minX, maxX, halfW, (minX + maxX) / 2f),
            cy = fit(cam.cy, minY, maxY, halfH, restY),
        )
    }

    private fun fit(
        v: Float,
        lo: Float,
        hi: Float,
        half: Float,
        rest: Float,
    ): Float = if (hi - lo <= 2f * half) rest else v.coerceIn(lo + half, hi - half)
}
