package com.ttmrase.crownfall.core.shop

import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Ledger
import com.ttmrase.crownfall.core.model.LedgerRecord
import com.ttmrase.crownfall.core.model.satAdd
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * 擬似課金台帳（04 §6）。**ゲームのロジックは読まない**（読むのは設定画面の表示とエクスポートだけ）。
 * 記録は上限まで残し、あふれたら古いものを外す。集計値は記録時に積み上げているので外れても保たれる。
 */
object FakeLedger {
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    /** 「実際の支払い」は常に 0 円（表示側の固定文言の元）。 */
    const val ACTUAL_PAYMENT_YEN = 0L

    /** [record] を台帳に加える。月は現地時間（UTC オフセット）で数える。 */
    fun record(
        ledger: Ledger,
        record: LedgerRecord,
        utcOffsetSec: Int,
        cap: Int,
    ): Ledger {
        val month = monthKey(record.epochSec, utcOffsetSec)
        val all = ledger.records + record
        val over = (all.size - cap).coerceAtLeast(0)
        return ledger.copy(
            records = if (over > 0) all.drop(over) else all,
            count = ledger.count + 1,
            totalYen = satAdd(ledger.totalYen, record.priceYen),
            byMonth = ledger.byMonth.plus(month to satAdd(ledger.byMonth[month] ?: 0L, record.priceYen)),
            byTrigger =
                ledger.byTrigger.plus(
                    record.trigger to satAdd(ledger.byTrigger[record.trigger] ?: 0L, record.priceYen),
                ),
            bySku = ledger.bySku.plus(record.sku to satAdd(ledger.bySku[record.sku] ?: 0L, record.priceYen)),
            folded = ledger.folded + over,
        )
    }

    /** 現地の "YYYY-MM"。 */
    fun monthKey(
        epochSec: Long,
        utcOffsetSec: Int,
    ): String {
        val day = Math.floorDiv(epochSec + utcOffsetSec, 86_400L)
        val d = LocalDate.ofEpochDay(day)
        return "%04d-%02d".format(d.year, d.monthValue)
    }

    /** 台帳の JSON（共有用）。 */
    fun exportJson(state: GameState): String = json.encodeToString(Export(state.shop.ledger))

    @kotlinx.serialization.Serializable
    private data class Export(val ledger: Ledger, val actualPaymentYen: Long = ACTUAL_PAYMENT_YEN)
}
