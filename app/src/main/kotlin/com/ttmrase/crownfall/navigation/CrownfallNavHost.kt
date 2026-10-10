package com.ttmrase.crownfall.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ttmrase.crownfall.data.GameRepository
import com.ttmrase.crownfall.data.Startup
import com.ttmrase.crownfall.offers.OfferHooks
import com.ttmrase.crownfall.ui.barracks.BarracksScreen
import com.ttmrase.crownfall.ui.city.CityScreen
import com.ttmrase.crownfall.ui.heroes.HeroesScreen
import com.ttmrase.crownfall.ui.heroes.SummonScreen
import com.ttmrase.crownfall.ui.research.ResearchScreen
import com.ttmrase.crownfall.ui.screens.StubScreen
import com.ttmrase.crownfall.ui.title.TitleScreen

/** 単一 Activity 用の NavHost と下部ナビ（Title では非表示）。 */
@Composable
fun CrownfallNavHost(
    repository: GameRepository,
    hooks: OfferHooks,
) {
    val navController = rememberNavController()
    val startup by repository.startup.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (destination != null && destination.route != Screen.Title.route) {
                NavigationBar {
                    BottomDestination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = destination.hierarchy.any { it.route == item.screen.route },
                            onClick = {
                                navController.navigate(item.screen.route) {
                                    popUpTo(Screen.City.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Title.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Screen.Title.route) {
                TitleScreen(
                    repository = repository,
                    onEnterGame = {
                        navController.navigate(Screen.City.route) {
                            popUpTo(Screen.Title.route) { inclusive = true }
                        }
                    },
                )
            }
            composable(Screen.City.route) {
                val ready = startup as? Startup.Ready
                if (ready != null) {
                    CityScreen(
                        session = ready.session,
                        data = ready.data,
                        hooks = hooks,
                        snackbarHostState = snackbarHostState,
                        onNavigate = { navController.navigate(it.route) },
                        onOpenBarracks = { tab -> navController.navigate("${Screen.Barracks.route}?tab=$tab") },
                    )
                }
            }
            composable(
                route = "${Screen.Barracks.route}?tab={tab}",
                arguments =
                    listOf(
                        navArgument("tab") {
                            type = NavType.IntType
                            defaultValue = 0
                        },
                    ),
            ) { entry ->
                val ready = startup as? Startup.Ready
                if (ready != null) {
                    BarracksScreen(
                        ready.session,
                        ready.data,
                        hooks,
                        snackbarHostState,
                        entry.arguments?.getInt("tab") ?: 0,
                    )
                }
            }
            composable(Screen.Research.route) {
                val ready = startup as? Startup.Ready
                if (ready != null) ResearchScreen(ready.session, ready.data, hooks, snackbarHostState)
            }
            composable(Screen.Heroes.route) {
                val ready = startup as? Startup.Ready
                if (ready != null) {
                    HeroesScreen(ready.session, ready.data, snackbarHostState) { navController.navigate(it.route) }
                }
            }
            composable(Screen.Summon.route) {
                val ready = startup as? Startup.Ready
                if (ready != null) SummonScreen(ready.session, ready.data, hooks, snackbarHostState)
            }
            val implemented =
                setOf(Screen.Title, Screen.City, Screen.Barracks, Screen.Research, Screen.Heroes, Screen.Summon)
            Screen.entries
                .filter { it !in implemented }
                .forEach { screen ->
                    composable(screen.route) { StubScreen(screen) }
                }
        }
    }
}
