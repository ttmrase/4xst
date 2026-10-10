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
    const val PAN_X = 900f
    const val PAN_Y_MIN = -400f
    const val PAN_Y_MAX = 540f

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
    ): CamState {
        if (viewW <= 0f || viewH <= 0f || cam.sc <= 0f) return cam
        val old = cam.sc
        val target = (old * zoom).coerceIn(minScale(viewW), maxScale(viewW))
        val dx = focusX - viewW / 2f
        val dy = focusY - viewH / 2f
        val wx = cam.cx + dx / old
        val wy = cam.cy + dy / old
        return CamState(
            cx = (wx - dx / target - panX / target).coerceIn(-PAN_X, PAN_X),
            cy = (wy - dy / target - panY / target).coerceIn(PAN_Y_MIN, PAN_Y_MAX),
            sc = target,
        )
    }
}
