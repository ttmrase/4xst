package com.ttmrase.crownfall.navigation

import androidx.annotation.StringRes
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.feature.Feature

/** 全画面のルート定義。 */
enum class Screen(
    val route: String,
    @StringRes val titleRes: Int,
) {
    Title("title", R.string.screen_title),
    City("city", R.string.screen_city),
    WorldMap("world_map", R.string.screen_world_map),
    Alliance("alliance", R.string.screen_alliance),
    Chat("chat", R.string.screen_chat),
    Heroes("heroes", R.string.screen_heroes),
    Research("research", R.string.screen_research),
    Barracks("barracks", R.string.screen_barracks),
    Quests("quests", R.string.screen_quests),
    Shop("shop", R.string.screen_shop),
    Mail("mail", R.string.screen_mail),
    Settings("settings", R.string.screen_settings),
    Summon("summon", R.string.screen_summon),
    Event("event", R.string.screen_event),
}

/** この画面を使うのに解放が要る機能（無ければ null）。未解放のあいだは、画面の代わりにロックの案内を出す。 */
fun Screen.requiredFeature(): Feature? =
    when (this) {
        Screen.WorldMap -> Feature.WORLD_MAP
        Screen.Alliance, Screen.Chat -> Feature.ALLIANCE
        Screen.Heroes, Screen.Summon -> Feature.HEROES
        Screen.Research -> Feature.RESEARCH
        Screen.Barracks -> Feature.TRAINING
        Screen.Quests -> Feature.QUESTS
        Screen.Shop -> Feature.SHOP
        Screen.Event -> Feature.EVENTS
        else -> null
    }
