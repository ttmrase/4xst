package com.ttmrase.crownfall

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ttmrase.crownfall.navigation.CrownfallNavHost
import com.ttmrase.crownfall.ui.theme.CrownfallTheme

/** 単一 Activity。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CrownfallTheme {
                CrownfallNavHost()
            }
        }
    }
}
