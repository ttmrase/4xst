package com.ttmrase.crownfall

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ttmrase.crownfall.data.Startup
import com.ttmrase.crownfall.navigation.CrownfallNavHost
import com.ttmrase.crownfall.notify.NotificationPermissionEffect
import com.ttmrase.crownfall.ui.theme.CrownfallTheme

/** 単一 Activity。前景・背景の遷移を GameRepository に伝える。 */
class MainActivity : ComponentActivity() {
    private val container: AppContainer
        get() = (application as CrownfallApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CrownfallTheme {
                val startup by container.repository.startup.collectAsStateWithLifecycle()
                NotificationPermissionEffect((startup as? Startup.Ready)?.session)
                CrownfallNavHost(container.repository, container.ai, container.chat, container.session)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        container.notices.cancelAll()
        container.repository.onForeground()
        container.session.onForeground()
        container.chat.onForeground()
    }

    override fun onStop() {
        container.chat.onBackground()
        container.ai.releaseOnDevice()
        container.repository.onBackground()
        (container.repository.startup.value as? Startup.Ready)?.let { ready ->
            ready.session.state.value?.let { container.notices.schedule(it, ready.data) }
        }
        super.onStop()
    }
}
