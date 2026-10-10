package com.ttmrase.crownfall.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.buff.BuffKey
import com.ttmrase.crownfall.core.data.ItemDef
import com.ttmrase.crownfall.core.data.ItemKind
import com.ttmrase.crownfall.core.data.SpeedupCategory
import com.ttmrase.crownfall.core.data.TroopDef
import com.ttmrase.crownfall.core.data.TroopType
import com.ttmrase.crownfall.core.map.Terrain
import com.ttmrase.crownfall.core.march.MarchKind
import com.ttmrase.crownfall.core.march.MarchPhase
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.HeroRarity
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
        "train_batch", "train_capacity" -> R.string.effect_train_batch
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

/** 英雄の表示名・説明（strings.xml の対応）。 */
@StringRes
fun heroNameRes(id: String): Int? =
    when (id) {
        "hero_tobias" -> R.string.hero_tobias_name
        "hero_mira" -> R.string.hero_mira_name
        "hero_dunstan" -> R.string.hero_dunstan_name
        "hero_aldric" -> R.string.hero_aldric_name
        "hero_seren" -> R.string.hero_seren_name
        "hero_brann" -> R.string.hero_brann_name
        "hero_ilsa" -> R.string.hero_ilsa_name
        "hero_corvin" -> R.string.hero_corvin_name
        "hero_yselda" -> R.string.hero_yselda_name
        "hero_thorne" -> R.string.hero_thorne_name
        "hero_valeria" -> R.string.hero_valeria_name
        "hero_ostrik" -> R.string.hero_ostrik_name
        else -> null
    }

@StringRes
fun heroDescRes(id: String): Int? =
    when (id) {
        "hero_tobias" -> R.string.hero_tobias_desc
        "hero_mira" -> R.string.hero_mira_desc
        "hero_dunstan" -> R.string.hero_dunstan_desc
        "hero_aldric" -> R.string.hero_aldric_desc
        "hero_seren" -> R.string.hero_seren_desc
        "hero_brann" -> R.string.hero_brann_desc
        "hero_ilsa" -> R.string.hero_ilsa_desc
        "hero_corvin" -> R.string.hero_corvin_desc
        "hero_yselda" -> R.string.hero_yselda_desc
        "hero_thorne" -> R.string.hero_thorne_desc
        "hero_valeria" -> R.string.hero_valeria_desc
        "hero_ostrik" -> R.string.hero_ostrik_desc
        else -> null
    }

/** 兵種の表示名。 */
@StringRes
fun TroopType.nameRes(): Int =
    when (this) {
        TroopType.INFANTRY -> R.string.troop_type_infantry
        TroopType.CAVALRY -> R.string.troop_type_cavalry
        TroopType.ARCHER -> R.string.troop_type_archer
        TroopType.SIEGE -> R.string.troop_type_siege
    }

/** 兵の表示名（例「歩兵 T2」）。 */
fun troopName(
    context: Context,
    troop: TroopDef,
): String = context.getString(R.string.troop_name, context.getString(troop.type.nameRes()), troop.tier)

/** 研究ノードの表示名。未知の ID は ID そのまま。 */
fun researchNodeName(
    context: Context,
    id: String,
): String {
    val resId = context.resources.getIdentifier("research_node_$id", "string", context.packageName)
    return if (resId != 0) context.getString(resId) else id
}

/** バフ効果の 1 行表示（例「食料の生産 +4%」）。 */
fun buffText(
    context: Context,
    key: BuffKey,
    bp: Long,
): String {
    val resId = context.resources.getIdentifier("buff_" + key.name.lowercase(), "string", context.packageName)
    val fraction = if (bp % BP_PER_PERCENT != 0L) "." + (bp % BP_PER_PERCENT / 10) else ""
    val percent = (bp / BP_PER_PERCENT).toString() + fraction
    return if (resId != 0) context.getString(resId, percent) else "${key.name} +$percent%"
}

/** レアリティごとの色（一覧の枠・文字色）。 */
fun rarityColor(rarity: HeroRarity): Color =
    when (rarity) {
        HeroRarity.N -> Color(0xFF9AA0A6)
        HeroRarity.R -> Color(0xFF4F9DDE)
        HeroRarity.SR -> Color(0xFFB57BE0)
        HeroRarity.SSR -> Color(0xFFE8B730)
    }

/** 非加速アイテムの表示名（経験値・スキル書・チケット）。加速は [speedupItemName]。 */
fun itemName(
    context: Context,
    item: ItemDef,
): String =
    when (item.kind) {
        ItemKind.SPEEDUP -> speedupItemName(context, item)
        ItemKind.HERO_EXP -> context.getString(R.string.item_hero_exp, ShortNumber.format(item.value))
        ItemKind.SKILL_BOOK -> context.getString(R.string.item_skill_book)
        ItemKind.SUMMON_TICKET_NORMAL -> context.getString(R.string.item_summon_ticket_normal)
        ItemKind.SUMMON_TICKET_PREMIUM -> context.getString(R.string.item_summon_ticket_premium)
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
        is ActionFailure.TroopLocked -> context.getString(R.string.failure_prereq_not_met)
        ActionFailure.NodeNotFound -> context.getString(R.string.failure_invalid_argument)
        ActionFailure.AlreadyResearching -> context.getString(R.string.research_running)
        is ActionFailure.ResearchPrereqNotMet -> context.getString(R.string.research_locked)
        ActionFailure.HeroNotOwned -> context.getString(R.string.hero_not_owned)
        ActionFailure.MaxStar -> context.getString(R.string.failure_max_level)
        is ActionFailure.NotEnoughShards -> context.getString(R.string.failure_not_enough_resources)
        ActionFailure.NoMarchSlot -> context.getString(R.string.failure_no_march_slot)
        ActionFailure.OverCapacity -> context.getString(R.string.failure_over_capacity)
        ActionFailure.HeroBusy -> context.getString(R.string.failure_hero_busy)
        ActionFailure.InvalidTarget -> context.getString(R.string.failure_invalid_target)
        ActionFailure.NoTroops -> context.getString(R.string.failure_no_troops)
        ActionFailure.MarchNotFound -> context.getString(R.string.failure_march_not_found)
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
        is GameEvent.TroopsTrained ->
            context.getString(
                R.string.event_troops_trained,
                troopNameById(context, event.troopId),
                ShortNumber.format(event.count),
            )
        is GameEvent.TroopsHealed -> context.getString(R.string.event_troops_healed)
        is GameEvent.ResearchCompleted ->
            context.getString(R.string.event_research_done, researchNodeName(context, event.nodeId), event.level)
        GameEvent.FoodDepleted -> context.getString(R.string.event_food_depleted)
        is GameEvent.MailReceived -> context.getString(R.string.event_mail)
        is GameEvent.MarchReturned -> context.getString(R.string.event_march_returned)
        is GameEvent.RaidIncoming -> context.getString(R.string.event_raid_incoming)
        is GameEvent.PlayerDefendedRaid -> context.getString(R.string.event_raid_defended)
        is GameEvent.PlayerDefeated -> context.getString(R.string.event_raid_defeated)
        is GameEvent.TimerCompleted,
        is GameEvent.BoostExpired,
        is GameEvent.HeroSummoned,
        -> null
    }

@StringRes
fun MarchKind.nameRes(): Int =
    when (this) {
        MarchKind.GATHER -> R.string.march_kind_gather
        MarchKind.ATTACK -> R.string.march_kind_attack
        MarchKind.SCOUT -> R.string.march_kind_scout
        MarchKind.RALLY -> R.string.march_kind_rally
    }

@StringRes
fun MarchPhase.nameRes(): Int =
    when (this) {
        MarchPhase.OUTBOUND -> R.string.march_phase_outbound
        MarchPhase.WORKING -> R.string.march_phase_working
        MarchPhase.WAITING -> R.string.march_phase_waiting
        MarchPhase.RETURNING -> R.string.march_phase_returning
    }

@StringRes
fun Terrain.nameRes(): Int =
    when (this) {
        Terrain.PLAIN -> R.string.terrain_plain
        Terrain.FOREST -> R.string.terrain_forest
        Terrain.MOUNTAIN -> R.string.terrain_mountain
        Terrain.WATER -> R.string.terrain_water
    }

/** 兵種 ID（例 `infantry_t2`）から表示名を作る。未知の ID はそのまま返す。 */
fun troopNameById(
    context: Context,
    troopId: String,
): String {
    val tier = troopId.substringAfterLast("_t", "").toIntOrNull()
    val type = TroopType.entries.firstOrNull { troopId.startsWith(it.name.lowercase() + "_") }
    return if (tier != null && type != null) {
        context.getString(R.string.troop_name, context.getString(type.nameRes()), tier)
    } else {
        troopId
    }
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

/** スキル効果の 1 行表示（例「兵の攻撃 +3%」）。値は bp。未知のキーは null。 */
fun skillEffectText(
    context: Context,
    effectKey: String,
    bpValue: Int,
): String? {
    val resId = context.resources.getIdentifier("skill_effect_$effectKey", "string", context.packageName)
    if (resId == 0) return null
    return context.getString(resId, (bpValue / BP_PER_PERCENT.toInt()).toString())
}
