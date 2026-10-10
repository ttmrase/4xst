package com.ttmrase.crownfall.ai

import com.ttmrase.crownfall.core.util.BuildInfo
import com.ttmrase.crownfall.core.util.DefaultBuildInfo

/** :ai から :core の型を参照できることの疎通確認用。M5 で不要になれば削除してよい。 */
fun aiBuildInfo(): BuildInfo = DefaultBuildInfo.copy(name = "${DefaultBuildInfo.name}-ai")
