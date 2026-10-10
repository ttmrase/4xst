package com.ttmrase.crownfall.ui.territory

import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.model.BuildingType

/** tools/artgen/gen_decor.py が生成する（手で編集しない）。建物・装飾のスプライトの ID 表。 */
object SpriteIds {
    /** 建物の見た目 [tier]（1..5）の drawable。 */
    fun building(
        type: BuildingType,
        tier: Int,
    ): Int = BUILDINGS.getValue(type)[(tier - 1).coerceIn(0, 4)]

    /** 城壁の部品の drawable（[tier] は 1..5）。 */
    fun wallX(tier: Int): Int = WALL_X[(tier - 1).coerceIn(0, 4)]

    fun wallY(tier: Int): Int = WALL_Y[(tier - 1).coerceIn(0, 4)]

    fun wallTower(tier: Int): Int = WALL_TOWER[(tier - 1).coerceIn(0, 4)]

    fun gate(tier: Int): Int = GATE[(tier - 1).coerceIn(0, 4)]

    val TREE_OAK_A: Int = R.drawable.deco_tree_oak_a

    val TREE_OAK_B: Int = R.drawable.deco_tree_oak_b

    val TREE_OAK_C: Int = R.drawable.deco_tree_oak_c

    val TREE_PINE_A: Int = R.drawable.deco_tree_pine_a

    val TREE_PINE_B: Int = R.drawable.deco_tree_pine_b

    val TREE_BIRCH: Int = R.drawable.deco_tree_birch

    val BUSH: Int = R.drawable.deco_bush

    val ROCK_A: Int = R.drawable.deco_rock_a

    val ROCK_B: Int = R.drawable.deco_rock_b

    val HAY: Int = R.drawable.deco_hay

    val LAMP: Int = R.drawable.deco_lamp

    val WELL: Int = R.drawable.deco_well

    val SIGNPOST: Int = R.drawable.deco_signpost

    val FOUNTAIN: Int = R.drawable.deco_fountain

    val HOUSE_A: Int = R.drawable.deco_house_a

    val HOUSE_B: Int = R.drawable.deco_house_b

    val HOUSE_C: Int = R.drawable.deco_house_c

    val STALL: Int = R.drawable.deco_stall

    val FIELD: Int = R.drawable.deco_field

    val MANOR: Int = R.drawable.deco_manor

    val STATUE: Int = R.drawable.deco_statue

    val SITE: Int = R.drawable.deco_site

    private val WALL_X =
        intArrayOf(
            R.drawable.deco_wall_x_t1,
            R.drawable.deco_wall_x_t2,
            R.drawable.deco_wall_x_t3,
            R.drawable.deco_wall_x_t4,
            R.drawable.deco_wall_x_t5,
        )

    private val WALL_Y =
        intArrayOf(
            R.drawable.deco_wall_y_t1,
            R.drawable.deco_wall_y_t2,
            R.drawable.deco_wall_y_t3,
            R.drawable.deco_wall_y_t4,
            R.drawable.deco_wall_y_t5,
        )

    private val WALL_TOWER =
        intArrayOf(
            R.drawable.deco_wall_tower_t1,
            R.drawable.deco_wall_tower_t2,
            R.drawable.deco_wall_tower_t3,
            R.drawable.deco_wall_tower_t4,
            R.drawable.deco_wall_tower_t5,
        )

    private val GATE =
        intArrayOf(
            R.drawable.deco_gate_t1,
            R.drawable.deco_gate_t2,
            R.drawable.deco_gate_t3,
            R.drawable.deco_gate_t4,
            R.drawable.deco_gate_t5,
        )

    private val BUILDINGS: Map<BuildingType, IntArray> =
        mapOf(
            BuildingType.CASTLE to
                intArrayOf(
                    R.drawable.bld_castle_t1,
                    R.drawable.bld_castle_t2,
                    R.drawable.bld_castle_t3,
                    R.drawable.bld_castle_t4,
                    R.drawable.bld_castle_t5,
                ),
            BuildingType.FARM to
                intArrayOf(
                    R.drawable.bld_farm_t1,
                    R.drawable.bld_farm_t2,
                    R.drawable.bld_farm_t3,
                    R.drawable.bld_farm_t4,
                    R.drawable.bld_farm_t5,
                ),
            BuildingType.SAWMILL to
                intArrayOf(
                    R.drawable.bld_sawmill_t1,
                    R.drawable.bld_sawmill_t2,
                    R.drawable.bld_sawmill_t3,
                    R.drawable.bld_sawmill_t4,
                    R.drawable.bld_sawmill_t5,
                ),
            BuildingType.QUARRY to
                intArrayOf(
                    R.drawable.bld_quarry_t1,
                    R.drawable.bld_quarry_t2,
                    R.drawable.bld_quarry_t3,
                    R.drawable.bld_quarry_t4,
                    R.drawable.bld_quarry_t5,
                ),
            BuildingType.GOLD_MINE to
                intArrayOf(
                    R.drawable.bld_gold_mine_t1,
                    R.drawable.bld_gold_mine_t2,
                    R.drawable.bld_gold_mine_t3,
                    R.drawable.bld_gold_mine_t4,
                    R.drawable.bld_gold_mine_t5,
                ),
            BuildingType.WAREHOUSE to
                intArrayOf(
                    R.drawable.bld_warehouse_t1,
                    R.drawable.bld_warehouse_t2,
                    R.drawable.bld_warehouse_t3,
                    R.drawable.bld_warehouse_t4,
                    R.drawable.bld_warehouse_t5,
                ),
            BuildingType.BARRACKS to
                intArrayOf(
                    R.drawable.bld_barracks_t1,
                    R.drawable.bld_barracks_t2,
                    R.drawable.bld_barracks_t3,
                    R.drawable.bld_barracks_t4,
                    R.drawable.bld_barracks_t5,
                ),
            BuildingType.ARCHERY_RANGE to
                intArrayOf(
                    R.drawable.bld_archery_range_t1,
                    R.drawable.bld_archery_range_t2,
                    R.drawable.bld_archery_range_t3,
                    R.drawable.bld_archery_range_t4,
                    R.drawable.bld_archery_range_t5,
                ),
            BuildingType.STABLE to
                intArrayOf(
                    R.drawable.bld_stable_t1,
                    R.drawable.bld_stable_t2,
                    R.drawable.bld_stable_t3,
                    R.drawable.bld_stable_t4,
                    R.drawable.bld_stable_t5,
                ),
            BuildingType.SIEGE_WORKSHOP to
                intArrayOf(
                    R.drawable.bld_siege_workshop_t1,
                    R.drawable.bld_siege_workshop_t2,
                    R.drawable.bld_siege_workshop_t3,
                    R.drawable.bld_siege_workshop_t4,
                    R.drawable.bld_siege_workshop_t5,
                ),
            BuildingType.HOSPITAL to
                intArrayOf(
                    R.drawable.bld_hospital_t1,
                    R.drawable.bld_hospital_t2,
                    R.drawable.bld_hospital_t3,
                    R.drawable.bld_hospital_t4,
                    R.drawable.bld_hospital_t5,
                ),
            BuildingType.ACADEMY to
                intArrayOf(
                    R.drawable.bld_academy_t1,
                    R.drawable.bld_academy_t2,
                    R.drawable.bld_academy_t3,
                    R.drawable.bld_academy_t4,
                    R.drawable.bld_academy_t5,
                ),
            BuildingType.WALL to
                intArrayOf(
                    R.drawable.bld_wall_t1,
                    R.drawable.bld_wall_t2,
                    R.drawable.bld_wall_t3,
                    R.drawable.bld_wall_t4,
                    R.drawable.bld_wall_t5,
                ),
            BuildingType.WATCHTOWER to
                intArrayOf(
                    R.drawable.bld_watchtower_t1,
                    R.drawable.bld_watchtower_t2,
                    R.drawable.bld_watchtower_t3,
                    R.drawable.bld_watchtower_t4,
                    R.drawable.bld_watchtower_t5,
                ),
            BuildingType.TRADING_POST to
                intArrayOf(
                    R.drawable.bld_trading_post_t1,
                    R.drawable.bld_trading_post_t2,
                    R.drawable.bld_trading_post_t3,
                    R.drawable.bld_trading_post_t4,
                    R.drawable.bld_trading_post_t5,
                ),
            BuildingType.ALLIANCE_CENTER to
                intArrayOf(
                    R.drawable.bld_alliance_center_t1,
                    R.drawable.bld_alliance_center_t2,
                    R.drawable.bld_alliance_center_t3,
                    R.drawable.bld_alliance_center_t4,
                    R.drawable.bld_alliance_center_t5,
                ),
            BuildingType.TAVERN to
                intArrayOf(
                    R.drawable.bld_tavern_t1,
                    R.drawable.bld_tavern_t2,
                    R.drawable.bld_tavern_t3,
                    R.drawable.bld_tavern_t4,
                    R.drawable.bld_tavern_t5,
                ),
            BuildingType.BUILDERS_HUT to
                intArrayOf(
                    R.drawable.bld_builders_hut_t1,
                    R.drawable.bld_builders_hut_t2,
                    R.drawable.bld_builders_hut_t3,
                    R.drawable.bld_builders_hut_t4,
                    R.drawable.bld_builders_hut_t5,
                ),
        )

    /** すべての建物の drawable（先読み用）。 */
    val ALL_BUILDINGS: List<Int> get() = BUILDINGS.values.flatMap { it.toList() }
}
