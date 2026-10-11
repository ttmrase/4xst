package com.ttmrase.crownfall.core.feature

import com.ttmrase.crownfall.core.data.FeatureDef
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameState

/** 段階解放の判定（純関数）。解放は城 Lv だけで決まる（M11 T11.1）。 */
object FeatureGate {
    /** 城の Lv（城が無ければ 0）。 */
    fun castleLevel(state: GameState): Int =
        state.slots.filter { it.type == BuildingType.CASTLE }.maxOfOrNull { it.level } ?: 0

    private fun def(
        data: GameData,
        feature: Feature,
    ): FeatureDef = data.features.features.first { it.id == feature.id }

    /** [feature] が解放される城 Lv。 */
    fun requiredLv(
        data: GameData,
        feature: Feature,
    ): Int = def(data, feature).lv

    /** [feature] の報酬。 */
    fun reward(
        data: GameData,
        feature: Feature,
    ) = def(data, feature).reward

    /** 城 Lv [castleLv] で [feature] が解放済みか。 */
    fun unlockedAt(
        data: GameData,
        castleLv: Int,
        feature: Feature,
    ): Boolean = castleLv >= requiredLv(data, feature)

    fun unlocked(
        state: GameState,
        data: GameData,
        feature: Feature,
    ): Boolean = unlockedAt(data, castleLevel(state), feature)

    /** いま解放済みの機能。 */
    fun unlockedSet(
        state: GameState,
        data: GameData,
    ): Set<Feature> = Feature.entries.filterTo(LinkedHashSet()) { unlocked(state, data, it) }

    /** 城 Lv が [from] から [to] になって新しく解放される機能（解放 Lv の昇順、同じ Lv は列挙順）。 */
    fun newlyUnlocked(
        data: GameData,
        from: Int,
        to: Int,
    ): List<Feature> =
        Feature.entries.filter { requiredLv(data, it) in (from + 1)..to }
            .sortedBy { requiredLv(data, it) }

    /** まだ解放されていない最初の機能（HUD の「次の解放」）。全部解放済みなら null。 */
    fun next(
        state: GameState,
        data: GameData,
    ): Feature? {
        val lv = castleLevel(state)
        return Feature.entries.filter { requiredLv(data, it) > lv }.minByOrNull { requiredLv(data, it) }
    }

    /** 最初の成功した行動をした印を付ける（報酬が受け取り可能になる）。 */
    fun markUsed(
        state: GameState,
        feature: Feature,
    ): GameState {
        val f = state.features
        if (feature.id in f.firstUsed) return state
        return state.copy(features = f.copy(firstUsed = f.firstUsed + feature.id))
    }

    /** 報酬を受け取れるか（最初の行動をしていて、未受け取りで、報酬が空でない）。 */
    fun rewardClaimable(
        state: GameState,
        data: GameData,
        feature: Feature,
    ): Boolean {
        val f = state.features
        return feature.id in f.firstUsed && feature.id !in f.firstUseClaimed && !reward(data, feature).isEmpty
    }

    /** 旧セーブの初期化: いま解放済みの機能をすべて演出済み・使用済み・受け取り済みにする。 */
    fun initialize(
        state: GameState,
        data: GameData,
    ): GameState {
        if (state.features.initialized) return state
        val ids = unlockedSet(state, data).map { it.id }.toSet()
        val f = state.features
        return state.copy(
            features =
                f.copy(
                    celebrated = f.celebrated + ids,
                    firstUsed = f.firstUsed + ids,
                    firstUseClaimed = f.firstUseClaimed + ids,
                    initialized = true,
                ),
        )
    }
}
