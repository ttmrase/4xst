package com.ttmrase.crownfall.core.economy

import com.ttmrase.crownfall.core.model.Resources

/** コスト判定の結果。 */
sealed interface CostCheck {
    /** 足りている。 */
    data object Ok : CostCheck

    /** 不足あり。[missing] は成分ごとの正確な不足量（M6 の「不足分ちょうど」パックの入力）。 */
    data class Missing(val missing: Resources) : CostCheck
}

/** コスト判定。 */
object Costs {
    /** [have] で [cost] を払えるか。 */
    fun check(
        have: Resources,
        cost: Resources,
    ): CostCheck {
        val missing = cost.missingFrom(have)
        return if (missing.isZero) CostCheck.Ok else CostCheck.Missing(missing)
    }
}
