package com.ttmrase.crownfall.core.chat

/** 文面（:ai / :app が作った 1 行）。[speaker] はペルソナ ID か `world:<名前>`。 */
data class SpokenText(val speaker: String, val text: String)
