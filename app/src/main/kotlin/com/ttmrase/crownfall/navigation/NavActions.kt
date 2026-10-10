package com.ttmrase.crownfall.navigation

import androidx.navigation.NavController
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 下部ナビのタブへ移る。城内は常にスタックの底（City）まで戻る。他のタブは、すでにスタックにあればそこまで戻り
 * （子画面からタブのルートへ。タブの再選択もここ）、無ければ City の上に積む。saveState / restoreState は使わない
 * （子画面を含むスタックが復元されて城内に戻れなくなるため）。戻るを繰り返すと
 * 「子画面 → タブのルート → 城内 → 退出の確認」になる。
 */
internal fun NavController.navigateBottom(target: BottomDestination) {
    val route = target.screen.route
    if (popBackStack(route, inclusive = false)) return
    navigate(route) {
        if (target == BottomDestination.City) {
            popUpTo(graph.id) { inclusive = false }
        } else {
            popUpTo(Screen.City.route) { inclusive = false }
        }
        launchSingleTop = true
    }
}

/**
 * 通知のタップで開く画面（[pending]）を一度だけ消費する。知っている route なら開いて true、そうでなければ捨てて false。
 */
internal fun NavController.openPendingRoute(pending: MutableStateFlow<String?>): Boolean {
    val target = pending.value ?: return false
    pending.value = null
    if (Screen.entries.none { it.route == target }) return false
    navigate(target) { launchSingleTop = true }
    return true
}
