package com.ttmrase.crownfall.core.data

/** offers.json / shop_rules.json / vip.json の整合検証（違反は [GameDataException]）。 */
internal object ShopValidator {
    private const val VIP_LEVELS = 15
    private const val PERCENT = 100
    private val KINDS =
        setOf(
            "login", "castleUp", "resourceShortage", "gemShortage", "defeated", "longTimer", "afterPurchase",
            "rankingNear", "exitIntent", "vipNear", "comeback", "piggyFull",
        )

    private fun fail(message: String): Nothing = throw GameDataException(message)

    fun validate(data: GameData) {
        validateOffers(data)
        validateRules(data)
        validateVip(data)
    }

    private fun validateContents(
        where: String,
        c: PackContents,
        data: GameData,
    ) {
        if (c.gems < 0 || c.secondBuilderDays < 0) fail("$where: negative contents")
        for ((id, n) in c.items) if (id !in data.items || n <= 0) fail("$where: bad item $id")
        for ((id, n) in c.heroShards) if (id !in data.heroes || n <= 0) fail("$where: bad hero $id")
    }

    private fun validateTrigger(
        where: String,
        t: TriggerDef,
        packs: Set<String>,
    ) {
        if (t.kind !in KINDS) fail("$where: unknown trigger ${t.kind}")
        when (t.kind) {
            "castleUp" -> if (t.minLv < 1) fail("$where: castleUp needs minLv")
            "longTimer" -> if (t.minSec <= 0) fail("$where: longTimer needs minSec")
            "vipNear" -> if (t.withinPt <= 0) fail("$where: vipNear needs withinPt")
            "comeback" -> if (t.minAwaySec <= 0) fail("$where: comeback needs minAwaySec")
            "afterPurchase" -> {
                val of = t.chainOf ?: fail("$where: afterPurchase needs chainOf")
                if (of != "any" && of !in packs) fail("$where: chainOf $of is not a pack")
            }
            else -> Unit
        }
    }

    private fun validateOffers(data: GameData) {
        val o = data.offers
        val ids = o.gemSkus.map { it.sku } + o.packs.map { it.id }
        if (ids.toSet().size != ids.size) fail("offers.json: duplicate sku")
        if (o.gemSkus.size != 6) fail("offers.json: expected 6 gem skus")
        if (o.gemSkus.any { it.priceYen <= 0 || it.gems <= 0 || it.bonus < 0 }) fail("offers.json: gem sku numbers")
        val packIds = o.packs.map { it.id }.toSet()
        for (p in o.packs) {
            val w = "offers.json: ${p.id}"
            if (p.priceYen <= 0 || p.listValueYen < p.priceYen) fail("$w: price must be positive and <= list value")
            if (p.durationSec <= 0 || p.dailyStock <= 0 || p.cooldownSec < 0) fail("$w: duration/stock/cooldown")
            if (p.contents.isEmpty) fail("$w: empty contents")
            if (p.tier !in 0..data.shopRules.frequency.chainMax) fail("$w: tier out of range")
            validateContents(w, p.contents, data)
            p.trigger?.let { validateTrigger(w, it, packIds) }
            if (p.deal != null && p.deal != "normal" && p.deal != "super") fail("$w: bad deal kind")
            if (p.tier > 0 && p.trigger?.kind != "afterPurchase") fail("$w: chain tiers need afterPurchase")
        }
    }

    private fun validateRules(data: GameData) {
        val r = data.shopRules
        val o = data.offers
        if (r.yenPerVipPoint <= 0 || r.ledgerCap <= 0) fail("shop_rules.json: yenPerVipPoint/ledgerCap")
        val f = r.frequency
        if (f.sessionCapLow <= 0 || f.sessionCapStandard < f.sessionCapLow || f.chainMax <= 0 || f.queueCap <= 0 ||
            f.popupGapSec < 0
        ) {
            fail("shop_rules.json: frequency")
        }
        if (r.login.pointsByStreak.isEmpty() || r.login.pointsByStreak.any { it <= 0 }) fail("shop_rules.json: login")
        for (sku in listOf(r.loginPackSku, r.exitPackSku) + r.socialProofSkus) {
            if (o.pack(sku) == null) fail("shop_rules.json: unknown pack $sku")
        }
        val table = r.shortage.resourceYenTable
        if (table.isEmpty() || table.any { it.size != 2 || it[0] <= 0 || it[1] <= 0 } ||
            table.zipWithNext().any { (a, b) -> b[0] <= a[0] || b[1] < a[1] }
        ) {
            fail("shop_rules.json: shortage table must be increasing")
        }
        val d = r.dailyDeals
        val normals = o.packs.count { it.deal == "normal" }
        val supers = o.packs.count { it.deal == "super" }
        if (d.normalCount > normals || d.superCount > supers || d.superOffPct !in 1 until PERCENT) {
            fail("shop_rules.json: dailyDeals needs enough candidate packs")
        }
        val mp = r.monthPass
        if (mp.days <= 0 || mp.priceYen <= 0 || mp.dailyItems.any { (id, n) -> id !in data.items || n <= 0 }) {
            fail("shop_rules.json: monthPass")
        }
        val gf = r.growthFund
        if (gf.milestones.isEmpty() || gf.milestones.any { it.size != 2 || it[0] <= 0 || it[1] <= 0 } ||
            gf.milestones.zipWithNext().any { (a, b) -> b[0] <= a[0] }
        ) {
            fail("shop_rules.json: growthFund milestones")
        }
        if (r.piggy.cap <= 0 || r.piggy.openYen <= 0) fail("shop_rules.json: piggy")
        val w = r.wheel
        if (w.segments.isEmpty() || w.segments.any { it.weight <= 0 } || w.segments.none { it.tease } ||
            w.segments.map { it.id }.toSet().size != w.segments.size
        ) {
            fail("shop_rules.json: wheel segments")
        }
        for (s in w.segments) {
            validateContents(
                "shop_rules.json: wheel ${s.id}",
                PackContents(s.gems, s.resources, s.items, s.heroShards),
                data,
            )
        }
        val skus = o.gemSkus.map { it.sku } + o.packs.map { it.id } + listOf(mp.sku, gf.sku, r.piggy.sku, w.extraSku)
        if (skus.toSet().size != skus.size) fail("shop_rules.json: special sku collides with offers.json")
    }

    private fun validateVip(data: GameData) {
        val levels = data.vip.levels
        if (levels.size != VIP_LEVELS || levels.mapIndexed { i, l -> l.lv == i + 1 }.any { !it }) {
            fail("vip.json: expected levels 1..$VIP_LEVELS in order")
        }
        if (levels.first().pt <= 0 || levels.zipWithNext().any { (a, b) -> b.pt <= a.pt }) {
            fail("vip.json: pt must increase")
        }
        if (levels.zipWithNext().any { (a, b) -> b.gatherBp < a.gatherBp || b.gemSkipBp < a.gemSkipBp }) {
            fail("vip.json: perks must not decrease")
        }
        if (levels.any { it.gatherBp < 0 || it.offlineBonusSec < 0 || it.gemSkipBp !in 0..BP_MAX }) {
            fail("vip.json: perk range")
        }
        levels.forEach { validateContents("vip.json: ${it.lv}", it.chest, data) }
    }

    private const val BP_MAX = 10_000
}
