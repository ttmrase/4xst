package com.ttmrase.crownfall.ui

import android.content.Context
import androidx.annotation.StringRes
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.ItemDef
import com.ttmrase.crownfall.core.data.SpeedupCategory
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.session.BuildingSection
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.util.ShortNumber

/** :core が返す ID と数値を表示文字列へ対応づける表（D4）。 */

@StringRes
fun BuildingType.nameRes(): Int =
    when (this) {
        BuildingType.CASTLE -> R.string.building_castle
        BuildingType.FARM -> R.string.building_farm
        BuildingType.SAWMILL -> R.string.building_sawmill
        BuildingType.QUARRY -> R.string.building_quarry
        BuildingType.GOLD_MINE -> R.string.building_gold_mine
        BuildingType.WAREHOUSE -> R.string.building_warehouse
        BuildingType.BARRACKS -> R.string.building_barracks
        BuildingType.ARCHERY_RANGE -> R.string.building_archery_range
        BuildingType.STABLE -> R.string.building_stable
        BuildingType.SIEGE_WORKSHOP -> R.string.building_siege_workshop
        BuildingType.HOSPITAL -> R.string.building_hospital
        BuildingType.ACADEMY -> R.string.building_academy
        BuildingType.WALL -> R.string.building_wall
        BuildingType.WATCHTOWER -> R.string.building_watchtower
        BuildingType.TRADING_POST -> R.string.building_trading_post
        BuildingType.ALLIANCE_CENTER -> R.string.building_alliance_center
        BuildingType.TAVERN -> R.string.building_tavern
        BuildingType.BUILDERS_HUT -> R.string.building_builders_hut
    }

@StringRes
fun BuildingType.descRes(): Int =
    when (this) {
        BuildingType.CASTLE -> R.string.building_castle_desc
        BuildingType.FARM -> R.string.building_farm_desc
        BuildingType.SAWMILL -> R.string.building_sawmill_desc
        BuildingType.QUARRY -> R.string.building_quarry_desc
        BuildingType.GOLD_MINE -> R.string.building_gold_mine_desc
        BuildingType.WAREHOUSE -> R.string.building_warehouse_desc
        BuildingType.BARRACKS -> R.string.building_barracks_desc
        BuildingType.ARCHERY_RANGE -> R.string.building_archery_range_desc
        BuildingType.STABLE -> R.string.building_stable_desc
        BuildingType.SIEGE_WORKSHOP -> R.string.building_siege_workshop_desc
        BuildingType.HOSPITAL -> R.string.building_hospital_desc
        BuildingType.ACADEMY -> R.string.building_academy_desc
        BuildingType.WALL -> R.string.building_wall_desc
        BuildingType.WATCHTOWER -> R.string.building_watchtower_desc
        BuildingType.TRADING_POST -> R.string.building_trading_post_desc
        BuildingType.ALLIANCE_CENTER -> R.string.building_alliance_center_desc
        BuildingType.TAVERN -> R.string.building_tavern_desc
        BuildingType.BUILDERS_HUT -> R.string.building_builders_hut_desc
    }

@StringRes
fun ResourceType.nameRes(): Int =
    when (this) {
        ResourceType.FOOD -> R.string.res_food
        ResourceType.WOOD -> R.string.res_wood
        ResourceType.STONE -> R.string.res_stone
        ResourceType.GOLD -> R.string.res_gold
    }

@StringRes
fun BuildingSection.titleRes(): Int =
    when (this) {
        BuildingSection.CORE -> R.string.section_core
        BuildingSection.RESOURCE -> R.string.section_resource
        BuildingSection.MILITARY -> R.string.section_military
        BuildingSection.OTHER -> R.string.section_other
    }

/** 効果キーの表示ラベル。表示しないキー（builder_hut など）は null。 */
@StringRes
fun effectLabelRes(key: String): Int? =
    when (key) {
        "production_per_hour" -> R.string.effect_production_per_hour
        "storage_cap" -> R.string.effect_storage_cap
        "storage_cap_base" -> R.string.effect_storage_cap_base
        "protect" -> R.string.effect_protect
        "troop_capacity" -> R.string.effect_troop_capacity
        "march_slots" -> R.string.effect_march_slots
        "train_batch" -> R.string.effect_train_batch
        "hospital_capacity" -> R.string.effect_hospital_capacity
        "research_tier" -> R.string.effect_research_tier
        "wall_hp" -> R.string.effect_wall_hp
        "scout_accuracy_bp" -> R.string.effect_scout_accuracy_bp
        "trade_rate_bp" -> R.string.effect_trade_rate_bp
        "help_count" -> R.string.effect_help_count
        "hero_cap" -> R.string.effect_hero_cap
        else -> null
    }

/** 効果値の数値表記（bp キーはパーセント、それ以外は短縮表示）。 */
fun effectValueText(
    key: String,
    value: Long,
): String = if (key.endsWith("_bp")) (value / BP_PER_PERCENT).toString() else ShortNumber.format(value)

private const val BP_PER_PERCENT = 100L

/** 初期英雄の表示名・説明（M3 の heroes.json が入るまでの仮対応）。 */
@StringRes
fun heroNameRes(id: String): Int? =
    when (id) {
        "hero_aldric" -> R.string.hero_aldric_name
        "hero_seren" -> R.string.hero_seren_name
        "hero_brann" -> R.string.hero_brann_name
        else -> null
    }

@StringRes
fun heroDescRes(id: String): Int? =
    when (id) {
        "hero_aldric" -> R.string.hero_aldric_desc
        "hero_seren" -> R.string.hero_seren_desc
        "hero_brann" -> R.string.hero_brann_desc
        else -> null
    }

/** アクション失敗の表示文言。 */
fun failureMessage(
    context: Context,
    failure: ActionFailure,
): String =
    when (failure) {
        ActionFailure.SlotNotFound -> context.getString(R.string.failure_slot_not_found)
        ActionFailure.MaxLevel -> context.getString(R.string.failure_max_level)
        ActionFailure.AlreadyUpgrading -> context.getString(R.string.failure_already_upgrading)
        is ActionFailure.PrereqNotMet -> context.getString(R.string.failure_prereq_not_met)
        ActionFailure.QueueFull -> context.getString(R.string.failure_queue_full)
        is ActionFailure.NotEnoughResources -> context.getString(R.string.failure_not_enough_resources)
        ActionFailure.JobNotFound -> context.getString(R.string.failure_job_not_found)
        ActionFailure.ItemNotOwned -> context.getString(R.string.failure_item_not_owned)
        ActionFailure.CategoryMismatch -> context.getString(R.string.failure_category_mismatch)
        is ActionFailure.NotEnoughGems ->
            context.getString(R.string.failure_not_enough_gems, ShortNumber.format(failure.missing))
        ActionFailure.InvalidArgument -> context.getString(R.string.failure_invalid_argument)
    }

/** イベントの通知文言（量産型翻訳調）。表示しないイベントは null。 */
fun eventMessage(
    context: Context,
    event: GameEvent,
): String? =
    when (event) {
        is GameEvent.BuildingLevelUp ->
            context.getString(R.string.event_level_up, context.getString(event.type.nameRes()), event.newLevel)
        is GameEvent.StorageFull ->
            context.getString(R.string.event_storage_full, context.getString(event.resource.nameRes()))
        is GameEvent.TimerCompleted,
        is GameEvent.BoostExpired,
        is GameEvent.TroopsTrained,
        is GameEvent.TroopsHealed,
        is GameEvent.ResearchCompleted,
        is GameEvent.FoodDepleted,
        is GameEvent.HeroSummoned,
        -> null
    }

/** 加速アイテムの表示名（例「建設加速 5 分」）。 */
fun speedupItemName(
    context: Context,
    item: ItemDef,
): String {
    val category =
        when (item.category ?: return item.id) {
            SpeedupCategory.GENERAL -> R.string.speedup_category_general
            SpeedupCategory.BUILD -> R.string.speedup_category_build
            SpeedupCategory.RESEARCH -> R.string.speedup_category_research
            SpeedupCategory.TRAIN -> R.string.speedup_category_train
            SpeedupCategory.HEAL -> R.string.speedup_category_heal
        }
    val duration =
        if (item.seconds % SECONDS_PER_HOUR == 0L) {
            context.getString(R.string.duration_hours, (item.seconds / SECONDS_PER_HOUR).toInt())
        } else {
            context.getString(R.string.duration_minutes, (item.seconds / SECONDS_PER_MINUTE).toInt())
        }
    return context.getString(R.string.speedup_item_name, context.getString(category), duration)
}

private const val SECONDS_PER_HOUR = 3600L
private const val SECONDS_PER_MINUTE = 60L
