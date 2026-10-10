package com.ttmrase.crownfall

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** エミュレータで実際のアプリが起動して、タイトル画面が出ること（ui ジョブの土台）。 */
@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun titleScreenAppearsAndCanBeCaptured() {
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.title_new_game)
        rule.waitUntil(timeoutMillis = 30_000) {
            rule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        }
        val shot = Screenshots.save("title")
        assertTrue("the screenshot must be written", shot != null && shot.length() > 0)
    }
}
