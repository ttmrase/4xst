package com.ttmrase.crownfall.navigation

import androidx.annotation.StringRes
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.ui.kit.Glyph

/** 下部ナビ（5 項目）。アイコンは自作の [Glyph]。 */
enum class BottomDestination(
    val screen: Screen,
    @StringRes val labelRes: Int,
    val glyph: Glyph,
) {
    City(Screen.City, R.string.nav_city, Glyph.NAV_CITY),
    Map(Screen.WorldMap, R.string.nav_map, Glyph.NAV_MAP),
    Alliance(Screen.Alliance, R.string.nav_alliance, Glyph.NAV_ALLIANCE),
    Heroes(Screen.Heroes, R.string.nav_heroes, Glyph.NAV_HEROES),
    Shop(Screen.Shop, R.string.nav_shop, Glyph.NAV_SHOP),
    ;

    /** このタブが要る機能（無ければ null）。 */
    val feature get() = screen.requiredFeature()
}
