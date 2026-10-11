package com.ttmrase.crownfall.core.data

import kotlinx.serialization.Serializable

/** 解放表の 1 行。[lv] は解放される城 Lv、[reward] は最初の成功した行動のあとに受け取れる報酬。 */
@Serializable
data class FeatureDef(
    val id: String,
    val lv: Int,
    val reward: PackContents = PackContents(),
)

/** features.json。 */
@Serializable
data class FeaturesFile(val features: List<FeatureDef>)
