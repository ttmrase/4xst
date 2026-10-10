package com.ttmrase.crownfall.offers

import com.ttmrase.crownfall.core.model.Resources

/** オファー誘導のフック（M6 で実装する。M2 では何もしない）。 */
interface OfferHooks {
    /** 建設・研究などで資源が足りないとき。[missing] は不足量。 */
    fun onResourceShortage(missing: Resources)

    /** ジェムが足りないとき。[missing] は不足ジェム数。 */
    fun onGemShortage(missing: Long)

    /** 2 本目のビルダー枠が未解放のままタップされたとき。 */
    fun onSecondBuilderLocked()
}

/** 何もしない実装。 */
object NoOpOfferHooks : OfferHooks {
    override fun onResourceShortage(missing: Resources) = Unit

    override fun onGemShortage(missing: Long) = Unit

    override fun onSecondBuilderLocked() = Unit
}
