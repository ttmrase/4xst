package com.ttmrase.crownfall.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.navigation.Screen

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
