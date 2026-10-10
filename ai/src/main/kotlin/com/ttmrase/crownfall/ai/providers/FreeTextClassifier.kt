package com.ttmrase.crownfall.ai.providers

/** プレイヤーの自由入力をキーワードで分類する（Scripted の返答テンプレ選択用）。 */
object FreeTextClassifier {
    private val thanks = listOf("ありがとう", "ありがと", "感謝", "助かった", "助かりました", "thx", "thanks", "thank")
    private val resource =
        listOf("資源", "食料", "木材", "石", "金", "足りない", "足りなく", "不足", "送って", "倉庫", "採集", "food", "wood", "stone", "gold")
    private val battle = listOf("攻撃", "戦", "野蛮人", "砦", "ラリー", "偵察", "襲撃", "兵", "戦力", "負け", "勝", "敵", "rally", "attack")
    private val research = listOf("研究", "アカデミー", "強化", "テック", "tech", "research")
    private val question = listOf("？", "?", "どう", "なぜ", "教えて", "どうやって", "なんで", "how", "why")

    /** 分類名（[com.ttmrase.crownfall.core.chat.ChatKeys.FREE_TEXT_CATEGORIES] のどれか）。 */
    fun classify(text: String): String {
        val t = text.lowercase()
        return when {
            thanks.any { t.contains(it) } -> "thanks"
            resource.any { t.contains(it) } -> "resource"
            battle.any { t.contains(it) } -> "battle"
            research.any { t.contains(it) } -> "research"
            question.any { t.contains(it) } -> "question"
            else -> "chat"
        }
    }
}
