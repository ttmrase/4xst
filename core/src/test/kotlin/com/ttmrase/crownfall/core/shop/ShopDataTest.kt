package com.ttmrase.crownfall.core.shop

import com.ttmrase.crownfall.core.data.ClasspathDataSource
import com.ttmrase.crownfall.core.data.GameDataException
import com.ttmrase.crownfall.core.data.GameDataLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ShopDataTest {
    private val data = ShopTestSupport.data

    private fun parseWith(
        name: String,
        edit: (String) -> String,
    ) = GameDataLoader.parse(ClasspathDataSource.readAll().toMutableMap().also { it[name] = edit(it.getValue(name)) })

    @Test
    fun catalogMatchesTheDocumentedPriceTable() {
        val gems = data.offers.gemSkus
        assertEquals(listOf(160L, 610L, 1220L, 3060L, 6100L, 12000L), gems.map { it.priceYen })
        assertEquals(listOf(100L, 500L, 1000L, 2500L, 5000L, 10000L), gems.map { it.gems })
        assertEquals(listOf(0L, 50L, 150L, 500L, 1200L, 3000L), gems.map { it.bonus })
        assertTrue(gems.all { it.firstDouble })
        val prices =
            data.offers.packs.map { it.priceYen } + data.shopRules.monthPass.priceYen +
                data.shopRules.growthFund.priceYen + data.shopRules.piggy.openYen + data.shopRules.wheel.extraYen
        assertTrue(prices.all { it in 120L..12_000L }, prices.toString())
        assertEquals(15, data.vip.levels.size)
    }

    @Test
    fun everyTriggerKindIsCoveredByAPackOrAHook() {
        val kinds = data.offers.packs.mapNotNull { it.trigger?.kind }.toSet()
        // shortage 系は不足量から動的に作るので、パックのトリガーには現れない。
        val expected =
            setOf(
                "login", "castleUp", "defeated", "longTimer", "afterPurchase", "rankingNear", "exitIntent",
                "vipNear", "comeback", "piggyFull",
            )
        assertEquals(expected, kinds)
    }

    @Test
    fun loaderRejectsBrokenShopData() {
        assertFailsWith<GameDataException> {
            parseWith("offers.json") { it.replace("\"when\": \"login\"", "\"when\": \"nope\"") }
        }
        assertFailsWith<GameDataException> { parseWith("offers.json") { it.replace("\"minLv\": 2", "\"minLv\": 0") } }
        assertFailsWith<GameDataException> {
            parseWith(
                "offers.json",
            ) { it.replace("speedup_general_60m", "no_such_item") }
        }
        assertFailsWith<GameDataException> { parseWith("offers.json") { it.replace("hero_valeria", "hero_nobody") } }
        assertFailsWith<GameDataException> {
            parseWith("offers.json") { it.replace("\"chainOf\": \"pack_chain_1\"", "\"chainOf\": \"ghost\"") }
        }
        assertFailsWith<GameDataException> { parseWith("shop_rules.json") { it.replace("pack_daily", "ghost_pack") } }
        assertFailsWith<GameDataException> {
            parseWith("shop_rules.json") { it.replace(Regex("\\[\\s*10000,\\s*160\\s*\\]"), "[2000, 160]") }
        }
        assertFailsWith<GameDataException> {
            parseWith("shop_rules.json") { it.replace("\"tease\": true", "\"tease\": false") }
        }
        assertFailsWith<GameDataException> { parseWith("vip.json") { it.replace("\"pt\": 300", "\"pt\": 50") } }
    }

    @Test
    fun fractionsArePricedLikeMassProducedOffers() {
        // 価格が「量産型の端数」の組から外れていない（決済ロジックを変えたときの退行検知）
        val allowed = setOf(120L, 160L, 610L, 1220L, 3060L, 6100L, 12000L)
        assertTrue(data.offers.packs.all { it.priceYen in allowed }, "pack prices")
        assertTrue(data.shopRules.shortage.resourceYenTable.all { it[1] in allowed })
    }
}
