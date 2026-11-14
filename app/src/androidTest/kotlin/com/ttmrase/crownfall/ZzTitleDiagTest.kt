package com.ttmrase.crownfall

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ttmrase.crownfall.ui.title.TitleDiag
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 診断用（一時的）: CI のエミュレータが、新しいタイトル画面を描いた直後にプロセスごと落ちる（ui run 120〜134）。
 * タイトルの絵を 1 部品ずつ有効にして数秒描かせ、直前に logcat へ印を残す。最後に残った印が、落とした部品を示す。
 * クラス名を Zz で始めて最後に走らせる（ここで端末が落ちても、ほかのテストの結果は残る）。原因を直したら消す。
 */
@RunWith(AndroidJUnit4::class)
class ZzTitleDiagTest {
    @After
    fun restore() {
        TitleDiag.mask = 0
    }

    private fun show(
        label: String,
        mask: Int,
    ) {
        Log.w(TAG, "title part BEGIN $label mask=$mask")
        TitleDiag.mask = mask
        ActivityScenario.launch(MainActivity::class.java).use { Thread.sleep(SHOW_MS) }
        Log.w(TAG, "title part OK $label")
    }

    @Test
    fun eachTitlePartRendersWithoutKillingTheDevice() {
        show("none", 0)
        show("flourish", TitleDiag.FLOURISH)
        show("gold_text", TitleDiag.GOLD_TEXT)
        show("crown", TitleDiag.CROWN)
        show("castle", TitleDiag.CASTLE)
        show("sparks", TitleDiag.SPARKS)
        show("rays", TitleDiag.RAYS)
        show("scene", TitleDiag.SCENE)
        show("outline", TitleDiag.OUTLINE)
        show("all", TitleDiag.ALL)
    }

    /**
     * 新しいプロセスの最初の描画で、タイトルを全部描く（もとの落ち方と同じ条件）。CI のスクリプトが、ほかのテストを終えて
     * 成果物を集めたあとに、このテストだけを別の計測プロセスで走らせる。
     */
    @Test
    fun coldAll() {
        show("cold all", TitleDiag.ALL)
    }

    private companion object {
        const val TAG = "CrownfallDiag"
        const val SHOW_MS = 3000L
    }
}
