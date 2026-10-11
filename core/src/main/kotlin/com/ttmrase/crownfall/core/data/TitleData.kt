package com.ttmrase.crownfall.core.data

import kotlinx.serialization.Serializable

/**
 * 称号 1 件（M11 T11.3）。[cond] を満たすとプレイヤーが名乗れる（クエストと同じ条件。減らない条件だけを使う）。
 * [cond] が無い称号はプレイヤーは得られない（AI メンバー専用。personas.json の `title` から参照する）。
 * 表示名は文字列リソース `title_<id>`。
 */
@Serializable
data class TitleDef(
    val id: String,
    val cond: QuestCond? = null,
)

/** titles.json。並びは「格の低い順」で、既定の称号は満たしている最後のもの。 */
@Serializable
data class TitlesFile(val titles: List<TitleDef>)
