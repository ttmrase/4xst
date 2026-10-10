package com.ttmrase.crownfall

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream

/**
 * 画面全体（ダイアログやシートを含む）のスクリーンショットを、アプリの外部ファイル領域の screenshots/ に PNG で残す。
 * CI の ui ジョブが `adb pull` して artifact `ui-screenshots` にする（.github/scripts/run-ui-tests.sh）。
 */
object Screenshots {
    const val DIR = "screenshots"

    fun save(name: String): File? {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return null
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), DIR).apply { mkdirs() }
        val file = File(dir, "${name.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }
}
