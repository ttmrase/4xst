package com.ttmrase.crownfall.core

import com.ttmrase.crownfall.core.util.BuildInfo
import com.ttmrase.crownfall.core.util.DefaultBuildInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildInfoTest {
    @Test
    fun jsonRoundTripKeepsValues() {
        val json = Json.encodeToString(DefaultBuildInfo)
        assertEquals("""{"name":"crownfall","saveVersion":1}""", json)
        assertEquals(DefaultBuildInfo, Json.decodeFromString<BuildInfo>(json))
    }
}
