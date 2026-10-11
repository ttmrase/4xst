package com.ttmrase.crownfall.core.feature

/**
 * 城 Lv で段階解放される機能（Lv1 の建設・資源・倉庫・城壁・メール・設定は常に使えるので含めない）。
 * [id] は features.json のキーと UI の文言キーに使う。
 */
enum class Feature(val id: String) {
    TRAINING("training"),
    QUESTS("quests"),
    SHOP("shop"),
    ALLIANCE("alliance"),
    WORLD_MAP("world_map"),
    RAIDS("raids"),
    RESEARCH("research"),
    WHEEL("wheel"),
    PIGGY("piggy"),
    HEROES("heroes"),
    WORLD_CHAT("world_chat"),
    EVENTS("events"),
    RALLY("rally"),
    ;

    companion object {
        private val byId = entries.associateBy { it.id }

        fun fromId(id: String): Feature? = byId[id]
    }
}
