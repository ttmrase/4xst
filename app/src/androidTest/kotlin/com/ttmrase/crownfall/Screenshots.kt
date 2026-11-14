package com.ttmrase.crownfall

import android.app.Instrumentation
import android.graphics.Bitmap
import android.util.Log
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
    private const val IDLE_TIMEOUT_MS = 10_000L
    private const val TAG = "CrownfallTest"

    fun save(name: String): File? {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        waitForIdle(instrumentation)
        // 領地の描画は毎秒 24 回の tick なので、直前の操作の結果が画面に載るまで少し待つ（待たないと 1 操作ぶん遅れた画像になる）
        Thread.sleep(SETTLE_MS)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return null
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), DIR).apply { mkdirs() }
        val file = File(dir, "${name.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    /**
     * メインスレッドが暇になるのを待つ。上限つき: Instrumentation.waitForIdleSync は、画面が描き直しを予約し続けていると
     * 永久に戻らない（予約のたびにメッセージの列に仕切りが置かれ、暇の通知が出ない）。待ちきれなければ、そのまま撮る。
     */
    private fun waitForIdle(instrumentation: Instrumentation) {
        val waiter = Thread({ instrumentation.waitForIdleSync() }, "screenshot-idle").apply { isDaemon = true }
        waiter.start()
        waiter.join(IDLE_TIMEOUT_MS)
        if (waiter.isAlive) Log.w(TAG, "the main thread did not go idle in $IDLE_TIMEOUT_MS ms before a screenshot")
    }
}
