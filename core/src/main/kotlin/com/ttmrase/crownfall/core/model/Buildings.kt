package com.ttmrase.crownfall.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 建物の種類（01 §5 の 18 種）。JSON では snake_case。 */
@Serializable
enum class BuildingType {
    @SerialName("castle")
    CASTLE,

    @SerialName("farm")
    FARM,

    @SerialName("sawmill")
    SAWMILL,

    @SerialName("quarry")
    QUARRY,

    @SerialName("gold_mine")
    GOLD_MINE,

    @SerialName("warehouse")
    WAREHOUSE,

    @SerialName("barracks")
    BARRACKS,

    @SerialName("archery_range")
    ARCHERY_RANGE,

    @SerialName("stable")
    STABLE,

    @SerialName("siege_workshop")
    SIEGE_WORKSHOP,

    @SerialName("hospital")
    HOSPITAL,

    @SerialName("academy")
    ACADEMY,

    @SerialName("wall")
    WALL,

    @SerialName("watchtower")
    WATCHTOWER,

    @SerialName("trading_post")
    TRADING_POST,

    @SerialName("alliance_center")
    ALLIANCE_CENTER,

    @SerialName("tavern")
    TAVERN,

    @SerialName("builders_hut")
    BUILDERS_HUT,
    ;

    /** 資源生産建物ならその資源、それ以外は null。 */
    val producedResource: ResourceType?
        get() =
            when (this) {
                FARM -> ResourceType.FOOD
                SAWMILL -> ResourceType.WOOD
                QUARRY -> ResourceType.STONE
                GOLD_MINE -> ResourceType.GOLD
                else -> null
            }
}

/** 建物スロット。[level] 0 は未建設（0→1 もアップグレードとして扱う）。 */
@Serializable
data class BuildingSlot(val slotId: Int, val type: BuildingType, val level: Int)

/** 建物の前提条件（[type] が [level] 以上）。 */
@Serializable
data class Prereq(val type: BuildingType, val level: Int)
