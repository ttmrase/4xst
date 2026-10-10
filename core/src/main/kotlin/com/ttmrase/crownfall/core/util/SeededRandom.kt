package com.ttmrase.crownfall.core.util

/**
 * SplitMix64 による決定論的乱数。`kotlin.random` は版をまたいだ再現性が保証されないため自前実装。
 * 状態は [state]（Long 1 個）だけなので GameState.rngState にそのまま保存できる。
 */
class SeededRandom(var state: Long) {
    /** 次の 64bit 乱数。 */
    fun nextLong(): Long {
        state += GOLDEN_GAMMA
        var z = state
        z = (z xor (z ushr 30)) * MIX1
        z = (z xor (z ushr 27)) * MIX2
        return z xor (z ushr 31)
    }

    /** `[0, bound)` の一様乱数。[bound] は正。 */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive: $bound" }
        return Math.floorMod(nextLong(), bound.toLong()).toInt()
    }

    private companion object {
        const val GOLDEN_GAMMA = -0x61c8864680b583ebL
        const val MIX1 = -0x40a7b892e31b1a47L
        const val MIX2 = -0x6b2fb644ecceee15L
    }
}
