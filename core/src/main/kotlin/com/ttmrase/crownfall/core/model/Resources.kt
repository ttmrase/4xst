package com.ttmrase.crownfall.core.model

import kotlinx.serialization.Serializable

/** 資源の種類。ジェムは資源ではなく別管理。 */
enum class ResourceType { FOOD, WOOD, STONE, GOLD }

/** Long 加算の飽和版。 */
internal fun satAdd(
    a: Long,
    b: Long,
): Long {
    val r = a + b
    return if (((a xor r) and (b xor r)) < 0) (if (a < 0) Long.MIN_VALUE else Long.MAX_VALUE) else r
}

/** Long 乗算の飽和版（符号つき）。 */
internal fun satMul(
    a: Long,
    b: Long,
): Long {
    val hi = Math.multiplyHigh(a, b)
    val lo = a * b
    return when {
        hi == 0L && lo >= 0 -> lo
        hi == -1L && lo < 0 -> lo
        (a < 0) != (b < 0) -> Long.MIN_VALUE
        else -> Long.MAX_VALUE
    }
}

/** `a × b / c`（中間オーバーフローなし、切り捨て）。[c] は正。 */
internal fun mulDiv(
    a: Long,
    b: Long,
    c: Long,
): Long =
    java.math.BigInteger.valueOf(a).multiply(java.math.BigInteger.valueOf(b))
        .divide(java.math.BigInteger.valueOf(c)).toLong()

/** 4 資源の量。不変。加算・乗算は Long.MAX_VALUE で飽和し、負数は作らない。 */
@Serializable
data class Resources(
    val food: Long = 0,
    val wood: Long = 0,
    val stone: Long = 0,
    val gold: Long = 0,
) {
    /** 資源 [type] の量。 */
    operator fun get(type: ResourceType): Long =
        when (type) {
            ResourceType.FOOD -> food
            ResourceType.WOOD -> wood
            ResourceType.STONE -> stone
            ResourceType.GOLD -> gold
        }

    /** [type] だけ [value] にした複製。 */
    fun with(
        type: ResourceType,
        value: Long,
    ): Resources =
        when (type) {
            ResourceType.FOOD -> copy(food = value)
            ResourceType.WOOD -> copy(wood = value)
            ResourceType.STONE -> copy(stone = value)
            ResourceType.GOLD -> copy(gold = value)
        }

    /** 成分ごとの加算（飽和）。 */
    operator fun plus(other: Resources): Resources =
        Resources(
            satAdd(food, other.food),
            satAdd(wood, other.wood),
            satAdd(stone, other.stone),
            satAdd(gold, other.gold),
        )

    /** 全成分を [k] 倍（飽和）。 */
    operator fun times(k: Long): Resources =
        Resources(satMul(food, k), satMul(wood, k), satMul(stone, k), satMul(gold, k))

    /** 成分ごとの減算。どれか足りなければ null。 */
    fun minusOrNull(other: Resources): Resources? =
        if (food < other.food || wood < other.wood || stone < other.stone || gold < other.gold) {
            null
        } else {
            Resources(food - other.food, wood - other.wood, stone - other.stone, gold - other.gold)
        }

    /** 本クラスをコストと見たとき、[have] に対して不足している量（各成分 ≥ 0）。 */
    fun missingFrom(have: Resources): Resources =
        Resources(
            maxOf(0, food - have.food),
            maxOf(0, wood - have.wood),
            maxOf(0, stone - have.stone),
            maxOf(0, gold - have.gold),
        )

    /** 全成分が 0 か。 */
    val isZero: Boolean get() = food == 0L && wood == 0L && stone == 0L && gold == 0L

    companion object {
        /** 全成分 0。 */
        val ZERO = Resources()
    }
}
