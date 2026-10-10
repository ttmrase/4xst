package com.ttmrase.crownfall.core.shop

import com.ttmrase.crownfall.core.alliance.Personas
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.PackContents
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.model.ShopState
import com.ttmrase.crownfall.core.model.satAdd
import com.ttmrase.crownfall.core.util.Mix

/** ショップの共通部品（日付の区切り、報酬の付与）。 */
object ShopLogic {
    private const val SECONDS_PER_DAY = 86_400L
    private const val SHOP_SALT = 0x5107_C0DEL
    private const val MAX_ITEM = Int.MAX_VALUE

    /** 現地日（settings.utcOffsetSec）。 */
    fun localDay(state: GameState): Long = Personas.localDay(state.nowEpochSec, state.settings.utcOffsetSec)

    /** デイリーディールなど決定論の種（マップの種から決まり、セーブ後も変わらない）。 */
    fun seed(state: GameState): Long = Mix.mix(state.map.seed xor SHOP_SALT)

    /** 日付が変わっていれば、1 日の上限カウンタ（残数）をリセットした状態にする。 */
    fun rollDay(state: GameState): GameState {
        val day = localDay(state)
        if (state.shop.day == day) return state
        return state.copy(shop = state.shop.copy(day = day, stockUsed = emptyMap()))
    }

    internal fun withShop(
        state: GameState,
        shop: ShopState,
    ): GameState = state.copy(shop = shop)

    /** 現在の城 Lv（城は 1 つ）。 */
    fun castleLevel(state: GameState): Int =
        state.slots.filter { it.type == BuildingType.CASTLE }
            .maxOfOrNull { it.level } ?: 0

    /** [c] を付与する（飽和加算）。2 本目ビルダーは日数ぶん延長する。 */
    fun grant(
        state: GameState,
        c: PackContents,
        data: GameData,
    ): GameState {
        require(data.items.keys.containsAll(c.items.keys)) { "unknown item in contents" }
        var s = state
        if (c.gems > 0) s = s.copy(gems = satAdd(s.gems, c.gems))
        if (c.resources != Resources.ZERO) {
            val r = s.resources
            s =
                s.copy(
                    resources =
                        Resources(
                            satAdd(r.food, c.resources.food),
                            satAdd(r.wood, c.resources.wood),
                            satAdd(r.stone, c.resources.stone),
                            satAdd(r.gold, c.resources.gold),
                        ),
                )
        }
        if (c.items.isNotEmpty()) {
            val items = s.items.toMutableMap()
            for ((id, n) in c.items) items[id] = minOf(MAX_ITEM.toLong(), (items[id] ?: 0).toLong() + n).toInt()
            s = s.copy(items = items)
        }
        if (c.heroShards.isNotEmpty()) {
            val shards = s.heroShards.toMutableMap()
            for ((id, n) in c.heroShards) shards[id] = minOf(MAX_ITEM.toLong(), (shards[id] ?: 0).toLong() + n).toInt()
            s = s.copy(heroShards = shards)
        }
        if (c.eventPoints > 0 && s.event.weekId >= 0) {
            s = s.copy(event = s.event.copy(bonusScore = satAdd(s.event.bonusScore, c.eventPoints)))
        }
        if (c.secondBuilderDays > 0) {
            val base = maxOf(s.secondBuilderUntilEpochSec, s.nowEpochSec)
            s = s.copy(secondBuilderUntilEpochSec = satAdd(base, c.secondBuilderDays * SECONDS_PER_DAY))
        }
        return s
    }

    /** 内容を [factor] 倍にする（城 Lv 記念など）。 */
    fun scale(
        c: PackContents,
        factor: Int,
    ): PackContents =
        c.copy(
            gems = c.gems * factor,
            resources =
                Resources(
                    c.resources.food * factor,
                    c.resources.wood * factor,
                    c.resources.stone * factor,
                    c.resources.gold * factor,
                ),
            items = c.items.mapValues { it.value * factor },
            heroShards = c.heroShards.mapValues { it.value * factor },
        )
}
