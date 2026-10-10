package com.ttmrase.crownfall.ai.router

/** 1 回の LLM 呼び出しの記録（[ok] は成功したか）。 */
data class UsageRecord(val epochSec: Long, val provider: String, val ok: Boolean)

/** 直近 24h の集計（設定画面に表示する）。 */
data class UsageStats(val requests: Int, val failures: Int)

/** 使用記録の保存先（:app が DataStore で実装する。テストでは [InMemoryUsageStore]）。 */
interface UsageStore {
    fun load(): List<UsageRecord>

    fun save(records: List<UsageRecord>)
}

class InMemoryUsageStore : UsageStore {
    private var records: List<UsageRecord> = emptyList()

    override fun load(): List<UsageRecord> = records

    override fun save(records: List<UsageRecord>) {
        this.records = records
    }
}

/** 予算（毎時・日次）と 24h 集計のための使用記録。 */
class UsageLog(
    private val store: UsageStore,
    private val nowSec: () -> Long,
) {
    private var records: List<UsageRecord> = prune(store.load())

    private fun prune(list: List<UsageRecord>): List<UsageRecord> = list.filter { it.epochSec > nowSec() - DAY }

    /** 直近 [windowSec] 秒の呼び出し回数。 */
    @Synchronized
    fun countSince(
        windowSec: Long,
        excluding: Set<String> = emptySet(),
    ): Int = records.count { it.epochSec > nowSec() - windowSec && it.provider !in excluding }

    /** 呼び出し結果を記録する。 */
    @Synchronized
    fun record(
        provider: String,
        ok: Boolean,
    ) {
        records = prune(records + UsageRecord(nowSec(), provider, ok))
        store.save(records)
    }

    /** 直近 24h の回数と失敗数。 */
    @Synchronized
    fun stats(): UsageStats {
        val r = prune(records)
        return UsageStats(r.size, r.count { !it.ok })
    }

    companion object {
        const val HOUR = 3600L
        const val DAY = 86_400L
    }
}
