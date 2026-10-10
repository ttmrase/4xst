package com.ttmrase.crownfall.core.sim

import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.hero.HeroBootstrap
import com.ttmrase.crownfall.core.map.MapState
import com.ttmrase.crownfall.core.model.BuildingSlot
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameState

/** 新規ゲームの初期状態を作る。 */
object NewGame {
    /** rules.json の newGame に従う初期状態。[seed] は乱数状態の初期値。[starterHeroId] の英雄を付与する。 */
    fun create(
        data: GameData,
        nowEpochSec: Long,
        seed: Long,
        playerName: String = "",
        starterHeroId: String? = null,
    ): GameState {
        val ng = data.rules.newGame
        var nextId = 0
        val slots =
            BuildingType.entries.flatMap { type ->
                val def = data.building(type)
                val levels = ng.initialLevels[type].orEmpty()
                List(def.count) { i -> BuildingSlot(nextId++, type, levels.getOrElse(i) { 0 }) }
            }
        return HeroBootstrap.ensureStarter(
            GameState(
                nowEpochSec = nowEpochSec,
                resources = ng.resources,
                gems = ng.gems,
                slots = slots,
                items = ng.items,
                rngState = seed,
                map = MapState(seed = seed, rngState = seed),
                playerName = playerName,
                starterHeroId = starterHeroId,
            ),
        )
    }
}
