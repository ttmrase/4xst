package com.ttmrase.crownfall.navigation

import androidx.annotation.StringRes
import com.ttmrase.crownfall.R

/** 全 12 画面のルート定義。 */
enum class Screen(val route: String, @StringRes val titleRes: Int) {
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
}
