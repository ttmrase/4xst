package com.ttmrase.crownfall.core.util

/** 現在時刻（エポック秒）の供給元。シミュレーション本体（tick）は参照せず、呼び出し側だけが使う。 */
fun interface Clock {
    /** 現在のエポック秒。 */
    fun nowEpochSec(): Long
}

/** テスト用の固定（手動で進める）時計。 */
class FixedClock(var epochSec: Long) : Clock {
    override fun nowEpochSec(): Long = epochSec

    /** [sec] 秒進める。 */
    fun advance(sec: Long) {
        epochSec += sec
    }
}

/** 端末の実時計。 */
object SystemClock : Clock {
    override fun nowEpochSec(): Long = System.currentTimeMillis() / 1000
}
