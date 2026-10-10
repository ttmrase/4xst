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
    private const val SETTLE_MS = 300L

    fun save(name: String): File? {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // 領地の描画は毎秒 24 回の tick なので、直前の操作の結果が画面に載るまで少し待つ（待たないと 1 操作ぶん遅れた画像になる）
        Thread.sleep(SETTLE_MS)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return null
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), DIR).apply { mkdirs() }
        val file = File(dir, "${name.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }
}
