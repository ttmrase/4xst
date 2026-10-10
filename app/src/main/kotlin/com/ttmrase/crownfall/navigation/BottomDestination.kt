package com.ttmrase.crownfall.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import com.ttmrase.crownfall.R

/** 下部ナビ（Material3 推奨の 5 項目）。 */
enum class BottomDestination(
    val screen: Screen,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    City(Screen.City, R.string.nav_city, Icons.Filled.Home),
    Map(Screen.WorldMap, R.string.nav_map, Icons.Filled.Place),
    Alliance(Screen.Alliance, R.string.nav_alliance, Icons.Filled.Favorite),
    Heroes(Screen.Heroes, R.string.nav_heroes, Icons.Filled.Star),
    Shop(Screen.Shop, R.string.nav_shop, Icons.Filled.ShoppingCart),
}
