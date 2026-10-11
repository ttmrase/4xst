package com.ttmrase.crownfall.core.data

/** skins.json の整合検証（違反は [GameDataException]）。 */
internal object SkinValidator {
    private val UNLOCK_TYPES = setOf("vip", "claimed", "event_rank")

    private const val BP_TOTAL = 10_000
    private const val MIN_PRICE = 1_220L
    private const val MAX_PRICE = 6_100L

    private fun fail(message: String): Nothing = throw GameDataException(message)

    fun validate(data: GameData) {
        val f = data.skins
        val ids = f.skins.map { it.id }
        if (ids.toSet().size != ids.size) fail("skins.json: duplicate id")
        if (f.featuredPerWeek < 1) fail("skins.json: featuredPerWeek must be >= 1")
        for (s in f.skins) {
            if (SkinCategory.fromId(s.category) == null) fail("skins.json: ${s.id} unknown category ${s.category}")
            if (s.priceYen != 0L && s.priceYen !in MIN_PRICE..MAX_PRICE) fail("skins.json: ${s.id} price out of range")
            if (s.limited && s.priceYen == 0L) fail("skins.json: ${s.id} is limited but not for sale")
            if (s.limited && s.bag) fail("skins.json: ${s.id} cannot be both limited and in the bag")
            if (s.priceYen == 0L && !s.bag && s.unlock == null) fail("skins.json: ${s.id} cannot be obtained")
            s.unlock?.let { u ->
                if (u.type !in UNLOCK_TYPES) fail("skins.json: ${s.id} unknown unlock ${u.type}")
                if (u.type == "event_rank" && u.lv < 1) fail("skins.json: ${s.id} event_rank unlock needs lv")
                if (u.type == "vip" && u.lv < 1) fail("skins.json: ${s.id} vip unlock needs lv")
                if (u.type == "claimed" && u.id.isBlank()) fail("skins.json: ${s.id} claimed unlock needs id")
            }
        }
        if (f.skins.count { it.limited } < f.featuredPerWeek) fail("skins.json: not enough limited skins")
        val b = f.bag
        if (b.rates.values.sum() != BP_TOTAL ||
            SkinRarity.entries.any {
                it !in b.rates
            }
        ) {
            fail("skins.json: bag rates must total 10000")
        }
        if (b.pityAt < 1 || b.gems < 1 || b.tenGems < b.gems) fail("skins.json: bad bag costs")
        for (r in SkinRarity.entries) {
            if (r !in b.dupShards || r !in b.exchange) fail("skins.json: bag shards/exchange must cover every rarity")
            if (f.skins.none { it.bag && it.rarity == r }) fail("skins.json: no bag skin of rarity $r")
        }
    }
}
