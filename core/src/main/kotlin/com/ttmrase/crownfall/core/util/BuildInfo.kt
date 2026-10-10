package com.ttmrase.crownfall.core.util

import kotlinx.serialization.Serializable

/** ビルド骨格の疎通確認用。M1 以降で不要になれば削除してよい。 */
@Serializable
data class BuildInfo(val name: String, val saveVersion: Int)

/** プロジェクト既定の [BuildInfo]。 */
val DefaultBuildInfo = BuildInfo(name = "crownfall", saveVersion = 1)
