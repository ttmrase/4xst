package com.ttmrase.crownfall.ai

import android.content.Context
import com.ttmrase.crownfall.ai.router.UsageRecord
import com.ttmrase.crownfall.ai.router.UsageStore

/** 使用記録（時刻・プロバイダ・成否のみ。本文・キーは含まない）を SharedPreferences に保存する。 */
class PrefsUsageStore(context: Context) : UsageStore {
    private val prefs = context.applicationContext.getSharedPreferences("ai_usage", Context.MODE_PRIVATE)

    override fun load(): List<UsageRecord> =
        (prefs.getString(KEY, "") ?: "").lineSequence().mapNotNull { line ->
            val f = line.split(',')
            val at = f.getOrNull(0)?.toLongOrNull()
            if (f.size == FIELDS && at != null) UsageRecord(at, f[1], f[2] == "1") else null
        }.toList()

    override fun save(records: List<UsageRecord>) {
        val text = records.joinToString("\n") { "${it.epochSec},${it.provider},${if (it.ok) 1 else 0}" }
        prefs.edit().putString(KEY, text).apply()
    }

    private companion object {
        const val KEY = "records"
        const val FIELDS = 3
    }
}
