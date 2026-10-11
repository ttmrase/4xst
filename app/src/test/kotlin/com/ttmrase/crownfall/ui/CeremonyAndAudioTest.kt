package com.ttmrase.crownfall.ui

import com.ttmrase.crownfall.core.data.ClasspathDataSource
import com.ttmrase.crownfall.core.feature.Feature
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.ui.ceremony.CeremonyLine
import com.ttmrase.crownfall.ui.ceremony.castleCeremonyLines
import com.ttmrase.crownfall.ui.ceremony.freshAchievementIds
import com.ttmrase.crownfall.ui.common.BgmScene
import com.ttmrase.crownfall.ui.common.bgmSceneFor
import com.ttmrase.crownfall.ui.portrait.PortraitKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CeremonyAndAudioTest {
    private val data = ClasspathDataSource.load()

    @Test
    fun castleLv3CeremonyListsTheShopTheNewBuildingsAndTheBiggerLimits() {
        val lines = castleCeremonyLines(data, 3)
        assertTrue(CeremonyLine.Feature(Feature.SHOP) in lines)
        assertTrue(CeremonyLine.Feature(Feature.QUESTS) in lines)
        assertTrue(CeremonyLine.Building(BuildingType.ARCHERY_RANGE) in lines)
        assertTrue(lines.any { it is CeremonyLine.Storage })
        assertTrue(lines.any { it is CeremonyLine.Troops })
        // 機能 → 建物 → 上限の順
        val firstBuilding = lines.indexOfFirst { it is CeremonyLine.Building }
        assertTrue(lines.indexOfLast { it is CeremonyLine.Feature } < firstBuilding)
        assertTrue(firstBuilding < lines.indexOfFirst { it is CeremonyLine.Storage })
    }

    @Test
    fun ceremonyLinesAreDeterministicAndEveryLevelHasStats() {
        for (lv in 2..data.building(BuildingType.CASTLE).maxLevel) {
            assertEquals(castleCeremonyLines(data, lv), castleCeremonyLines(data, lv))
            assertTrue("Lv$lv has a storage line", castleCeremonyLines(data, lv).any { it is CeremonyLine.Storage })
        }
    }

    @Test
    fun bgmScenesFollowTheRoute() {
        assertEquals(BgmScene.TITLE, bgmSceneFor(null))
        assertEquals(BgmScene.TITLE, bgmSceneFor("title"))
        assertEquals(BgmScene.CITY, bgmSceneFor("city"))
        assertEquals(BgmScene.CITY, bgmSceneFor("research"))
        assertEquals(BgmScene.MAP, bgmSceneFor("world_map"))
        assertEquals(BgmScene.SHOP, bgmSceneFor("shop"))
        assertEquals(BgmScene.SUMMON, bgmSceneFor("summon"))
    }

    @Test
    fun portraitKeysStayInTheGeneratedRange() {
        assertEquals("gen_m_03", PortraitKeys.filler("m", 3))
        assertEquals("avatar_00", PortraitKeys.avatar(0))
        assertEquals("avatar_00", PortraitKeys.avatar(12))
        assertEquals("avatar_11", PortraitKeys.avatar(-1))
        assertTrue(PortraitKeys.byName("Sora").startsWith("gen_n_"))
        assertEquals(PortraitKeys.byName("Sora"), PortraitKeys.byName("Sora"))
    }

    @Test
    fun achievementsAlreadyDoneAtFirstSightAreKnownAndOnlyLaterOnesAreFresh() {
        // 状態が読めるまでは何も出さず、既知にもしない
        assertEquals(null to emptyList<String>(), freshAchievementIds(null, listOf("a"), false))
        // 最初に読めた時点の達成済みは既知（出さない）
        val (known, none) = freshAchievementIds(null, listOf("a", "b"), true)
        assertEquals(setOf("a", "b"), known)
        assertTrue(none.isEmpty())
        // 以降に増えた分だけが新しい。既に出したものは二度出さない
        val (known2, fresh) = freshAchievementIds(known, listOf("a", "b", "c"), true)
        assertEquals(listOf("c"), fresh)
        assertTrue(freshAchievementIds(known2, listOf("a", "b", "c"), true).second.isEmpty())
    }
}
