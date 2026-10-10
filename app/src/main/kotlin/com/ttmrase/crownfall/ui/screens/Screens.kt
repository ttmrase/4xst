package com.ttmrase.crownfall.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.navigation.Screen

/** タイトル画面。「開始」で城内へ。 */
@Composable
fun TitleScreen(onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayMedium)
        Button(onClick = onStart, modifier = Modifier.padding(top = 24.dp)) {
            Text(stringResource(R.string.start_game))
        }
    }
}

/** 城内スタブ。下部ナビに無い画面への仮の導線を持つ。 */
@Composable
fun CityScreen(onNavigate: (Screen) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(Screen.City.titleRes), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.city_shortcuts), style = MaterialTheme.typography.labelMedium)
        listOf(Screen.Mail, Screen.Quests, Screen.Research, Screen.Barracks, Screen.Chat, Screen.Settings)
            .forEach { screen ->
                OutlinedButton(onClick = { onNavigate(screen) }) {
                    Text(stringResource(screen.titleRes))
                }
            }
    }
}

/** タイトルのみを表示するスタブ画面。 */
@Composable
fun StubScreen(screen: Screen) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(screen.titleRes), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.stub_coming_soon))
    }
}
