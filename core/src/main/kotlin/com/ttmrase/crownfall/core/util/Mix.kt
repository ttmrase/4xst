package com.ttmrase.crownfall.core.util

/** 状態を持たないハッシュ乱数（SplitMix64 の finalizer）。同じ入力なら常に同じ値で、分割 tick に依存しない。 */
object Mix {
    private const val GAMMA = -0x61c8864680b583ebL
    private const val MIX1 = -0x40a7b892e31b1a47L
    private const val MIX2 = -0x6b2fb644ecceee15L
    private const val PRIME_A = 0x9E3779B1L
    private const val PRIME_B = 0x85EBCA6BL

    /** 64bit 値を混ぜる。 */
    fun mix(x: Long): Long {
        var z = x + GAMMA
        z = (z xor (z ushr 30)) * MIX1
        z = (z xor (z ushr 27)) * MIX2
        return z xor (z ushr 31)
    }

    /** `[0, bound)` の値。[seed]・[a]・[b] の組で一意に決まる。 */
    fun roll(
        seed: Long,
        a: Long,
        b: Long,
        bound: Int,
    ): Int {
        require(bound > 0) { "bound must be positive: $bound" }
        return Math.floorMod(mix(mix(seed xor (a * PRIME_A)) xor (b * PRIME_B)), bound.toLong()).toInt()
    }
}
