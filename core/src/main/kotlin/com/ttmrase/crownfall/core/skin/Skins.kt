package com.ttmrase.crownfall.core.skin

import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.SkinCategory
import com.ttmrase.crownfall.core.data.SkinDef
import com.ttmrase.crownfall.core.data.SkinRarity
import com.ttmrase.crownfall.core.event.WeeklyEvents
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.shop.VipCalculator
import com.ttmrase.crownfall.core.util.SeededRandom

/** 福袋 1 回の結果。[isNew] が false なら重複で、[shards] が欠片になった量。 */
data class BagResult(val skinId: String, val rarity: SkinRarity, val isNew: Boolean, val shards: Int)

/**
 * スキン（M11 T11.4）。所持・装備・週替わりの限定・福袋・欠片の交換。すべて純関数で決定論的（乱数は `rngState`）。
 */
object Skins {
    private const val BP = 10_000

    fun def(
        data: GameData,
        id: String,
    ): SkinDef? = data.skins.skins.firstOrNull { it.id == id }

    /** いま持っているスキンの id（購入・福袋・交換で得たもの＋ VIP・クエストの解放ぶん）。 */
    fun owned(
        state: GameState,
        data: GameData,
    ): Set<String> {
        val vip by lazy { VipCalculator.level(state, data) }
        val derived =
            data.skins.skins.filter { s ->
                when (s.unlock?.type) {
                    "vip" -> vip >= s.unlock.lv
                    "claimed" -> s.unlock.id in state.quest.claimed
                    else -> false
                }
            }.map { it.id }
        return state.shop.skins.owned + derived
    }

    /** 今週の週番号（週替わりイベントと同じ、現地の月曜始まり）。 */
    fun week(state: GameState): Long = WeeklyEvents.weekId(state.nowEpochSec, state.settings.utcOffsetSec)

    /** [week] 週に買える限定スキン（並び順どおりに巡回する。同じ週は常に同じ。数週で一巡して復刻する）。 */
    fun featured(
        data: GameData,
        week: Long,
    ): List<SkinDef> {
        val limited = data.skins.skins.filter { it.limited }
        val n = limited.size
        val per = minOf(data.skins.featuredPerWeek, n)
        return (0 until per).map { i -> limited[Math.floorMod(week * per + i, n.toLong()).toInt()] }
    }

    /** 今買えるか（限定は今週の分だけ。¥ で売っていないものは買えない）。 */
    fun purchasable(
        state: GameState,
        skin: SkinDef,
        data: GameData,
    ): Boolean =
        skin.priceYen > 0 &&
            (!skin.limited || featured(data, week(state)).any { it.id == skin.id })

    /** スキンを装備する（持っているものだけ）。null で外す。 */
    fun equip(
        state: GameState,
        category: SkinCategory,
        id: String?,
        data: GameData,
    ): GameState? {
        val skins = state.shop.skins
        if (id == null) {
            return state.copy(
                shop = state.shop.copy(skins = skins.copy(equipped = skins.equipped - category.id)),
            )
        }
        val d = def(data, id) ?: return null
        if (d.category != category.id || id !in owned(state, data)) return null
        return state.copy(shop = state.shop.copy(skins = skins.copy(equipped = skins.equipped + (category.id to id))))
    }

    /** 装備中のスキン（部位 → 定義）。持っていなくなった（ありえないが）ものは外す。 */
    fun equipped(
        state: GameState,
        data: GameData,
    ): Map<SkinCategory, SkinDef> {
        val own = owned(state, data)
        return state.shop.skins.equipped.mapNotNull { (cat, id) ->
            val c = SkinCategory.fromId(cat)
            val d = def(data, id)
            if (c != null && d != null && id in own) c to d else null
        }.toMap()
    }

    /** 購入で [id] を手に入れる（擬似購入から呼ぶ）。部位が空いていれば自動で装備する。 */
    fun grant(
        state: GameState,
        id: String,
        data: GameData,
    ): GameState {
        val d = def(data, id) ?: return state
        val skins = state.shop.skins
        val equipped =
            if (skins.equipped.containsKey(
                    d.category,
                )
            ) {
                skins.equipped
            } else {
                skins.equipped + (d.category to id)
            }
        return state.copy(shop = state.shop.copy(skins = skins.copy(owned = skins.owned + id, equipped = equipped)))
    }

    /** 福袋の排出表示（レア度ごとの確率 bp と、そのレア度に入っているスキン）。UI の確率表示に使う。 */
    fun bagTable(data: GameData): List<Triple<SkinRarity, Int, List<SkinDef>>> =
        SkinRarity.entries.map { r ->
            Triple(r, data.skins.bag.rates.getValue(r), data.skins.skins.filter { it.bag && it.rarity == r })
        }

    /**
     * 福袋を [count] 回引く（ジェムは呼び出し側で引く）。乱数は `rngState`。SR 以上が [pityAt] 連続で出なければ次は SR 以上確定。
     * 持っているスキンが出たら欠片（レア度ごとの量）になる。結果と、進んだ状態を返す。
     */
    fun pullBag(
        state: GameState,
        count: Int,
        data: GameData,
    ): Pair<GameState, List<BagResult>> {
        val rules = data.skins.bag
        val rng = SeededRandom(state.rngState)
        var skins = state.shop.skins
        val have = owned(state, data).toMutableSet()
        val results = ArrayList<BagResult>()
        repeat(count) {
            var r = rollRarity(rng, rules.rates)
            if (skins.bagSinceRare + 1 >= rules.pityAt && r < SkinRarity.SR) r = SkinRarity.SR
            val pool = data.skins.skins.filter { it.bag && it.rarity == r }
            val skin = pool[rng.nextInt(pool.size)]
            val fresh = skin.id !in have
            val gain = if (fresh) 0 else rules.dupShards.getValue(r)
            if (fresh) {
                have += skin.id
                skins = skins.copy(owned = skins.owned + skin.id)
            } else {
                skins = skins.copy(shards = skins.shards + gain)
            }
            val dry = if (r >= SkinRarity.SR) 0 else skins.bagSinceRare + 1
            skins = skins.copy(bagSinceRare = dry, bagPulls = skins.bagPulls + 1)
            results += BagResult(skin.id, r, fresh, gain)
        }
        return state.copy(rngState = rng.state, shop = state.shop.copy(skins = skins)) to results
    }

    private fun rollRarity(
        rng: SeededRandom,
        rates: Map<SkinRarity, Int>,
    ): SkinRarity {
        var n = rng.nextInt(BP)
        for (r in SkinRarity.entries) {
            n -= rates.getValue(r)
            if (n < 0) return r
        }
        return SkinRarity.N
    }

    /** 欠片で [id]（福袋に入っていて、まだ持っていないスキン）と交換できる欠片の数。交換できなければ null。 */
    fun exchangeCost(
        state: GameState,
        id: String,
        data: GameData,
    ): Int? {
        val d = def(data, id) ?: return null
        if (!d.bag || id in owned(state, data)) return null
        return data.skins.bag.exchange.getValue(d.rarity)
    }

    /** 欠片で交換する。足りない・交換できないなら null。 */
    fun exchange(
        state: GameState,
        id: String,
        data: GameData,
    ): GameState? {
        val cost = exchangeCost(state, id, data) ?: return null
        if (state.shop.skins.shards < cost) return null
        val skins = state.shop.skins
        return grant(state.copy(shop = state.shop.copy(skins = skins.copy(shards = skins.shards - cost))), id, data)
    }
}
