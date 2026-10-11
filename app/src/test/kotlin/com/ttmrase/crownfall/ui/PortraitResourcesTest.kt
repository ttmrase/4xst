package com.ttmrase.crownfall.ui

import com.ttmrase.crownfall.core.data.ClasspathDataSource
import com.ttmrase.crownfall.ui.portrait.PortraitKeys
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** ポートレートの描画リソース（pt_<key>）が、英雄・ペルソナ・名前だけのメンバー・アバターのぶん全部ある。 */
class PortraitResourcesTest {
    private val data = ClasspathDataSource.load()
    private val drawables = File("src/main/res/drawable")

    private fun has(key: String) = File(drawables, "pt_$key.xml").exists()

    @Test
    fun everyHeroAndPersonaHasAPortrait() {
        val missing =
            data.heroes.keys.filterNot(::has) + data.personas.personas.map { it.id }.filterNot(::has)
        assertTrue("missing portraits: $missing", missing.isEmpty())
    }

    @Test
    fun everyFillerLookAndAvatarHasAPortrait() {
        val fillers =
            data.personas.fillerMembers.groupBy { it.look }.flatMap { (look, list) ->
                list.indices.map { PortraitKeys.filler(look, it) }
            }
        val avatars = (0 until com.ttmrase.crownfall.core.sim.Actions.AVATAR_COUNT).map(PortraitKeys::avatar)
        val missing = (fillers + avatars).filterNot(::has)
        assertTrue("missing portraits: $missing", missing.isEmpty())
    }
}
