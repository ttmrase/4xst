package com.ttmrase.crownfall.core.data

import com.ttmrase.crownfall.core.feature.Feature

/** features.json の検査: 全機能がちょうど 1 回ずつ、城 Lv は 2 以上（Lv1 の機能は表に載せない）。 */
internal object FeatureValidator {
    private const val MIN_LV = 2
    private const val MAX_LV = 25

    fun validate(data: GameData) {
        val ids = data.features.features.map { it.id }

        fun fail(msg: String): Nothing = throw GameDataException("invalid features.json: $msg")
        for (f in Feature.entries) {
            if (ids.count { it == f.id } != 1) fail("feature ${f.id} must appear exactly once")
        }
        ids.firstOrNull { Feature.fromId(it) == null }?.let { fail("unknown feature $it") }
        data.features.features.firstOrNull { it.lv !in MIN_LV..MAX_LV }?.let { fail("lv out of range: ${it.id}") }
    }
}
