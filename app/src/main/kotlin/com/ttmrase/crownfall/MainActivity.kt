package com.ttmrase.crownfall

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ttmrase.crownfall.navigation.CrownfallNavHost
import com.ttmrase.crownfall.ui.theme.CrownfallTheme

/** 単一 Activity。前景・背景の遷移を GameRepository に伝える。 */
class MainActivity : ComponentActivity() {
    private val container: AppContainer
        get() = (application as CrownfallApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CrownfallTheme {
                CrownfallNavHost(container.repository, container.offerHooks, container.ai, container.chat)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        container.repository.onForeground()
        container.chat.onForeground()
    }

    override fun onStop() {
        container.chat.onBackground()
        container.repository.onBackground()
        super.onStop()
    }
}
