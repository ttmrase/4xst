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

    /** 領地の端（現在の城壁）から、画面の中心がはみ出してよい余白（グリッド単位）。 */
    const val PAN_MARGIN = 3f

    fun minScale(viewW: Float): Float = viewW / ZOOM_OUT_SPAN

    fun maxScale(viewW: Float): Float = viewW / ZOOM_IN_SPAN

    fun initial(
        viewW: Float,
        centerX: Float,
        centerY: Float,
    ): CamState = CamState(centerX, centerY, (viewW / INITIAL_SPAN).coerceIn(minScale(viewW), maxScale(viewW)))

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
        limitHalf: Float = PlotLayout.WALL_HALF.last() + PAN_MARGIN,
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
        )
    }

    /**
     * 画面の中心を、領地（[PlotLayout.MID] を中心とする一辺 2 x [limitHalf] の正方形）の内側に収める。
     * 城下町の外の森だけが映る場所へはパンできない。
     */
    fun clampToTerritory(
        cam: CamState,
        limitHalf: Float,
    ): CamState {
        val u = cam.cx / PlotLayout.U
        val v = cam.cy / PlotLayout.V
        val mid = PlotLayout.MID
        val gx = ((u + v) / 2f).coerceIn(mid - limitHalf, mid + limitHalf)
        val gy = ((v - u) / 2f).coerceIn(mid - limitHalf, mid + limitHalf)
        return cam.copy(cx = PlotLayout.wx(gx, gy), cy = PlotLayout.wy(gx, gy))
    }
}
