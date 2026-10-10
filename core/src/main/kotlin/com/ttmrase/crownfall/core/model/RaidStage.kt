package com.ttmrase.crownfall.core.model

/**
 * 襲撃の段階（偵察された → 進軍中 → 1 時間前 → 15 分前 → 開始 → 結果）。時刻は襲撃時刻からのオフセットで、
 * notification_rules.json に置く。物見の塔の Lv が低いと、前の段階は見えない（開始と結果は常に見える）。
 */
enum class RaidStage { SCOUTED, MARCHING, T_MINUS_1H, T_MINUS_15M, STARTED, RESULT }
