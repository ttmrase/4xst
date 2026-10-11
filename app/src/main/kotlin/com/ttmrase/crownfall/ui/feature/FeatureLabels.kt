package com.ttmrase.crownfall.ui.feature

import androidx.annotation.StringRes
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.feature.Feature

/** 段階解放の機能名。 */
@StringRes
fun Feature.nameRes(): Int =
    when (this) {
        Feature.TRAINING -> R.string.feature_name_training
        Feature.QUESTS -> R.string.feature_name_quests
        Feature.SHOP -> R.string.feature_name_shop
        Feature.ALLIANCE -> R.string.feature_name_alliance
        Feature.WORLD_MAP -> R.string.feature_name_world_map
        Feature.RAIDS -> R.string.feature_name_raids
        Feature.RESEARCH -> R.string.feature_name_research
        Feature.WHEEL -> R.string.feature_name_wheel
        Feature.PIGGY -> R.string.feature_name_piggy
        Feature.HEROES -> R.string.feature_name_heroes
        Feature.WORLD_CHAT -> R.string.feature_name_world_chat
        Feature.EVENTS -> R.string.feature_name_events
        Feature.RALLY -> R.string.feature_name_rally
    }

/** 段階解放の機能の説明（解放の演出とロックのダイアログ）。 */
@StringRes
fun Feature.descRes(): Int =
    when (this) {
        Feature.TRAINING -> R.string.feature_desc_training
        Feature.QUESTS -> R.string.feature_desc_quests
        Feature.SHOP -> R.string.feature_desc_shop
        Feature.ALLIANCE -> R.string.feature_desc_alliance
        Feature.WORLD_MAP -> R.string.feature_desc_world_map
        Feature.RAIDS -> R.string.feature_desc_raids
        Feature.RESEARCH -> R.string.feature_desc_research
        Feature.WHEEL -> R.string.feature_desc_wheel
        Feature.PIGGY -> R.string.feature_desc_piggy
        Feature.HEROES -> R.string.feature_desc_heroes
        Feature.WORLD_CHAT -> R.string.feature_desc_world_chat
        Feature.EVENTS -> R.string.feature_desc_events
        Feature.RALLY -> R.string.feature_desc_rally
    }

/** 機能のアイコン（絵文字）。 */
fun Feature.emoji(): String =
    when (this) {
        Feature.TRAINING -> "⚔"
        Feature.QUESTS -> "📜"
        Feature.SHOP -> "🛒"
        Feature.ALLIANCE -> "🤝"
        Feature.WORLD_MAP -> "🗺"
        Feature.RAIDS -> "🛡"
        Feature.RESEARCH -> "🔬"
        Feature.WHEEL -> "🎡"
        Feature.PIGGY -> "🐷"
        Feature.HEROES -> "⭐"
        Feature.WORLD_CHAT -> "💬"
        Feature.EVENTS -> "🏆"
        Feature.RALLY -> "🏰"
    }
