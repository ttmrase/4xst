package com.ttmrase.crownfall.core.model

import kotlinx.serialization.Serializable

/**
 * 段階解放の進行（機能の id の集合。M11 T11.1）。
 * [celebrated] は解放の演出を見せた機能、[firstUsed] は最初の成功した行動をした機能、
 * [firstUseClaimed] はその報酬を受け取った機能。[initialized] が false の旧セーブは、最初の tick で
 * 「いま解放済みの機能」をすべて見せた・受け取った扱いにする（演出の洪水と報酬の二重取りを防ぐ）。
 */
@Serializable
data class FeatureProgress(
    val celebrated: Set<String> = emptySet(),
    val firstUsed: Set<String> = emptySet(),
    val firstUseClaimed: Set<String> = emptySet(),
    val initialized: Boolean = false,
)
