package com.ttmrase.crownfall.core.sim

import com.ttmrase.crownfall.core.alliance.DonationKind
import com.ttmrase.crownfall.core.buff.BuffAggregator
import com.ttmrase.crownfall.core.buff.BuffKey
import com.ttmrase.crownfall.core.chat.SpokenText
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.ResearchPrereq
import com.ttmrase.crownfall.core.economy.CostCheck
import com.ttmrase.crownfall.core.economy.Costs
import com.ttmrase.crownfall.core.economy.GemSkip
import com.ttmrase.crownfall.core.hero.HeroGrowth
import com.ttmrase.crownfall.core.hero.SummonKind
import com.ttmrase.crownfall.core.hero.Summoner
import com.ttmrase.crownfall.core.map.TileRef
import com.ttmrase.crownfall.core.march.MailBox
import com.ttmrase.crownfall.core.march.MarchKind
import com.ttmrase.crownfall.core.military.Military
import com.ttmrase.crownfall.core.model.Boost
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Prereq
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.model.ShopSettings
import com.ttmrase.crownfall.core.model.TimerJob
import com.ttmrase.crownfall.core.model.TimerKind
import com.ttmrase.crownfall.core.model.satAdd
import com.ttmrase.crownfall.core.model.satMul
import com.ttmrase.crownfall.core.research.ResearchRules
import com.ttmrase.crownfall.core.shop.ShopHooks
import com.ttmrase.crownfall.core.shop.VipCalculator

/** アクション失敗の理由（ID と数値のみ。表示文言は :app）。 */
sealed interface ActionFailure {
    data object SlotNotFound : ActionFailure

    data object MaxLevel : ActionFailure

    data object AlreadyUpgrading : ActionFailure

    data class PrereqNotMet(val missing: List<Prereq>) : ActionFailure

    data object QueueFull : ActionFailure

    data class NotEnoughResources(val missing: Resources) : ActionFailure

    data object JobNotFound : ActionFailure

    data object ItemNotOwned : ActionFailure

    data object CategoryMismatch : ActionFailure

    data class NotEnoughGems(val missing: Long) : ActionFailure

    data object InvalidArgument : ActionFailure

    /** 兵種の訓練建物の Lv が足りない。[requiredLevel] は必要な建物 Lv。 */
    data class TroopLocked(val requiredLevel: Int) : ActionFailure

    data object HeroNotOwned : ActionFailure

    data object MaxStar : ActionFailure

    /** 星上げの欠片が足りない。[missing] は不足数。 */
    data class NotEnoughShards(val missing: Int) : ActionFailure

    data object NodeNotFound : ActionFailure

    data object AlreadyResearching : ActionFailure

    /** このタイマーはすでにヘルプを求めている。 */
    data object HelpAlreadyRequested : ActionFailure

    /** ヘルプできる要請が無い。 */
    data object NothingToHelp : ActionFailure

    /** 1 日の上限に達した。 */
    data object DailyLimit : ActionFailure

    /** 要請が見つからない（期限切れを含む）。 */
    data object RequestNotFound : ActionFailure

    /** 同盟テックが見つからない。 */
    data object TechNotFound : ActionFailure

    /** 同盟ショップの品が見つからない。 */
    data object ShopItemNotFound : ActionFailure

    /** 同盟ポイントが足りない。 */
    data class NotEnoughAlliancePoints(val missing: Long) : ActionFailure

    /** ギフト箱が見つからない（期限切れを含む）。 */
    data object GiftNotFound : ActionFailure

    /** ラリーが見つからない（期限切れを含む）。 */
    data object RallyNotFound : ActionFailure

    /** チャットのきっかけが見つからない。 */
    data object ChatNotFound : ActionFailure

    /** クイズが見つからない。 */
    data object QuizNotFound : ActionFailure

    /** クイズにはすでに答えた。 */
    data object QuizAlreadyAnswered : ActionFailure

    /** そのチャンネルには書き込めない。 */
    data object ChannelNotWritable : ActionFailure

    /** オファー / 商品が見つからない・期限切れ・今日のディールに無い。 */
    data object OfferUnavailable : ActionFailure

    /** 今日の残数が無い。 */
    data object SoldOut : ActionFailure

    /** すでに持っている（成長ファンドなど）。 */
    data object AlreadyOwned : ActionFailure

    /** 今日はもう受け取った。 */
    data object AlreadyClaimed : ActionFailure

    /** まだ持っていない（月パスなど）。 */
    data object NotOwned : ActionFailure

    /** 受け取れるものが無い。 */
    data object NothingToClaim : ActionFailure

    /** クエストが見つからない。 */
    data object QuestNotFound : ActionFailure

    /** 行軍の枠が埋まっている。 */
    data object NoMarchSlot : ActionFailure

    /** 1 隊の兵数上限を超えている。 */
    data object OverCapacity : ActionFailure

    /** 英雄がほかの行軍に出ている。 */
    data object HeroBusy : ActionFailure

    /** 行軍の目的地が不正（対象が無い・範囲外）。 */
    data object InvalidTarget : ActionFailure

    /** 出撃できる兵が無い（空・所持数超過）。 */
    data object NoTroops : ActionFailure

    /** 行軍が見つからない。 */
    data object MarchNotFound : ActionFailure

    /** 研究の前提不足。[missingAcademy] は必要な Academy Lv（足りているなら null）、[missingNodes] は不足している先行ノード。 */
    data class ResearchPrereqNotMet(
        val missingAcademy: Int?,
        val missingNodes: List<ResearchPrereq>,
    ) : ActionFailure
}

/** アクション結果。例外は投げず、成功か失敗かで返す。 */
sealed interface ActionResult {
    /**
     * [tracked] が true なら [events] は tick の中でクエスト / イベントの集計済み（`Simulation.tick` を通ったもの）で、
     * 呼び出し側で再び数えない。
     */
    data class Success(
        val state: GameState,
        val events: List<GameEvent> = emptyList(),
        val tracked: Boolean = false,
    ) : ActionResult

    data class Failure(val reason: ActionFailure) : ActionResult
}

/** プレイヤー操作・報酬の受け口（すべて純関数）。 */
object Actions {
    /** チュートリアル完了を表すステップ番号。 */
    const val TUTORIAL_DONE = 3

    /** 10 連の回数。 */
    const val TEN_ROLL = 10

    /** スキル強化に使うアイテム ID。 */
    const val SKILL_BOOK = "skill_book"

    private fun started(
        next: GameState,
        job: TimerJob,
        data: GameData,
    ): ActionResult {
        val withOffers = ShopHooks.onTimerStarted(next, job.endEpochSec - job.startEpochSec, data)
        return ActionResult.Success(Simulation.syncStorageFlags(withOffers, data))
    }

    /** スロット [slotId] のアップグレードを開始する。検査は宣言順で、最初の失敗を返す。 */
    fun startUpgrade(
        state: GameState,
        slotId: Int,
        data: GameData,
    ): ActionResult {
        val slot = state.slots.firstOrNull { it.slotId == slotId } ?: return fail(ActionFailure.SlotNotFound)
        val def = data.building(slot.type)
        if (slot.level >= def.maxLevel) return fail(ActionFailure.MaxLevel)
        if (state.timers.any { it.kind == TimerKind.BUILD && it.slotId == slotId }) {
            return fail(
                ActionFailure.AlreadyUpgrading,
            )
        }
        val target = def.level(slot.level + 1)
        val missingPrereqs =
            target.prereqs.filter { p ->
                p.level > 0 && (state.slots.filter { it.type == p.type }.maxOfOrNull { it.level } ?: 0) < p.level
            }
        if (missingPrereqs.isNotEmpty()) return fail(ActionFailure.PrereqNotMet(missingPrereqs))
        val busy = state.timers.count { it.kind == TimerKind.BUILD }
        if (busy >= Simulation.buildQueueCapacity(state, data)) return fail(ActionFailure.QueueFull)
        val rest =
            when (val check = Costs.check(state.resources, target.cost)) {
                is CostCheck.Missing -> return fail(ActionFailure.NotEnoughResources(check.missing))
                CostCheck.Ok -> state.resources.minusOrNull(target.cost) ?: return fail(ActionFailure.InvalidArgument)
            }
        val duration = Buffs.speedSeconds(target.seconds, BuffAggregator.compute(state, data)[BuffKey.BUILD_SPEED])
        val job =
            TimerJob(
                id = state.nextJobId,
                kind = TimerKind.BUILD,
                slotId = slotId,
                targetLevel = slot.level + 1,
                startEpochSec = state.nowEpochSec,
                endEpochSec = satAdd(state.nowEpochSec, duration),
            )
        val next = state.copy(resources = rest, timers = state.timers + job, nextJobId = state.nextJobId + 1)
        return started(next, job, data)
    }

    /** 研究ノード [nodeId] を 1 レベル上げる研究を始める（Academy の研究キューは 1 本）。 */
    fun startResearch(
        state: GameState,
        nodeId: String,
        data: GameData,
    ): ActionResult {
        val node = data.research[nodeId] ?: return fail(ActionFailure.NodeNotFound)
        val current = state.research[nodeId] ?: 0
        if (current >= node.maxLevel) return fail(ActionFailure.MaxLevel)
        val running = state.timers.filter { it.kind == TimerKind.RESEARCH }
        if (running.any { it.researchId == nodeId }) return fail(ActionFailure.AlreadyResearching)
        val target = node.level(current + 1)
        val missingNodes = ResearchRules.missingNodes(state, target)
        val missingAcademy = ResearchRules.missingAcademy(state, target)
        if (missingAcademy != null || missingNodes.isNotEmpty()) {
            return fail(ActionFailure.ResearchPrereqNotMet(missingAcademy, missingNodes))
        }
        if (running.isNotEmpty()) return fail(ActionFailure.QueueFull)
        val rest =
            when (val check = Costs.check(state.resources, target.cost)) {
                is CostCheck.Missing -> return fail(ActionFailure.NotEnoughResources(check.missing))
                CostCheck.Ok -> state.resources.minusOrNull(target.cost) ?: return fail(ActionFailure.InvalidArgument)
            }
        val duration = Buffs.speedSeconds(target.seconds, BuffAggregator.compute(state, data)[BuffKey.RESEARCH_SPEED])
        val job =
            TimerJob(
                id = state.nextJobId,
                kind = TimerKind.RESEARCH,
                targetLevel = current + 1,
                startEpochSec = state.nowEpochSec,
                endEpochSec = satAdd(state.nowEpochSec, duration),
                researchId = nodeId,
            )
        val next = state.copy(resources = rest, timers = state.timers + job, nextJobId = state.nextJobId + 1)
        return started(next, job, data)
    }

    /**
     * 兵 [troopId] を [count] 体訓練する。訓練は訓練建物ごとに 1 本。コストは即時に差し引く。
     * 検査順: 不正な引数 → TroopLocked → 上限超過（InvalidArgument）→ QueueFull → NotEnoughResources。
     */
    fun startTraining(
        state: GameState,
        troopId: String,
        count: Long,
        data: GameData,
    ): ActionResult {
        val troop = data.troops[troopId] ?: return fail(ActionFailure.InvalidArgument)
        if (count <= 0) return fail(ActionFailure.InvalidArgument)
        val slot = Military.trainingSlot(state, troop)
        if (slot == null || slot.level < troop.unlockBuildingLevel) {
            return fail(ActionFailure.TroopLocked(troop.unlockBuildingLevel))
        }
        if (count > Military.trainCapacity(state, troop, data)) return fail(ActionFailure.InvalidArgument)
        if (state.timers.any { it.kind == TimerKind.TRAIN && it.slotId == slot.slotId }) {
            return fail(
                ActionFailure.QueueFull,
            )
        }
        val cost = Military.trainingCost(troop, count)
        val rest =
            when (val check = Costs.check(state.resources, cost)) {
                is CostCheck.Missing -> return fail(ActionFailure.NotEnoughResources(check.missing))
                CostCheck.Ok -> state.resources.minusOrNull(cost) ?: return fail(ActionFailure.InvalidArgument)
            }
        val duration =
            Buffs.speedSeconds(
                Military.baseTrainSeconds(troop, count),
                BuffAggregator.compute(state, data)[BuffKey.TRAIN_SPEED],
            )
        val job =
            TimerJob(
                id = state.nextJobId,
                kind = TimerKind.TRAIN,
                slotId = slot.slotId,
                startEpochSec = state.nowEpochSec,
                endEpochSec = satAdd(state.nowEpochSec, duration),
                troopId = troopId,
                count = count,
            )
        val next = state.copy(resources = rest, timers = state.timers + job, nextJobId = state.nextJobId + 1)
        return started(next, job, data)
    }

    /**
     * 負傷兵 [batch]（兵種 ID → 数）の治療を始める（Hospital の治療キューは 1 本）。負傷兵は開始時に wounded から外れ、
     * 完了で troops へ戻る。コスト = 訓練コスト × healCostPct / 100、時間 = trainSec × healTimePct / 100 × 数。
     */
    fun startHeal(
        state: GameState,
        batch: Map<String, Long>,
        data: GameData,
    ): ActionResult {
        if (batch.isEmpty() || batch.any { (id, n) -> n <= 0 || id !in data.troops || n > (state.wounded[id] ?: 0L) }) {
            return fail(ActionFailure.InvalidArgument)
        }
        if (state.slots.none { it.type == BuildingType.HOSPITAL && it.level >= 1 }) {
            return fail(ActionFailure.PrereqNotMet(listOf(Prereq(BuildingType.HOSPITAL, 1))))
        }
        if (state.timers.any { it.kind == TimerKind.HEAL }) return fail(ActionFailure.QueueFull)
        val cost = Military.healCost(batch, data)
        val rest =
            when (val check = Costs.check(state.resources, cost)) {
                is CostCheck.Missing -> return fail(ActionFailure.NotEnoughResources(check.missing))
                CostCheck.Ok -> state.resources.minusOrNull(cost) ?: return fail(ActionFailure.InvalidArgument)
            }
        val duration =
            Buffs.speedSeconds(
                Military.baseHealSeconds(batch, data),
                BuffAggregator.compute(state, data)[BuffKey.HEAL_SPEED],
            )
        val sorted = batch.toSortedMap().toMap()
        val job =
            TimerJob(
                id = state.nextJobId,
                kind = TimerKind.HEAL,
                startEpochSec = state.nowEpochSec,
                endEpochSec = satAdd(state.nowEpochSec, duration),
                healBatch = sorted,
            )
        val wounded = state.wounded.mapValues { (id, n) -> n - (sorted[id] ?: 0L) }.filterValues { it > 0 }
        val next =
            state.copy(
                resources = rest,
                wounded = wounded,
                timers = state.timers + job,
                nextJobId = state.nextJobId + 1,
            )
        return started(next, job, data)
    }

    /** 2 本目ビルダーを [durationSec] 秒付与する（期限が残っていれば延長）。 */
    fun grantSecondBuilder(
        state: GameState,
        durationSec: Long,
    ): ActionResult {
        if (durationSec <= 0) return fail(ActionFailure.InvalidArgument)
        val base = maxOf(state.secondBuilderUntilEpochSec, state.nowEpochSec)
        return ActionResult.Success(state.copy(secondBuilderUntilEpochSec = satAdd(base, durationSec)))
    }

    /** 資源を加算する（報酬・購入。上限を超えてよい。飽和）。負の成分は R5（負数を作らない）のため拒否する。 */
    fun addResources(
        state: GameState,
        amount: Resources,
    ): ActionResult {
        if (amount.food < 0 || amount.wood < 0 || amount.stone < 0 || amount.gold < 0) {
            return fail(ActionFailure.InvalidArgument)
        }
        return ActionResult.Success(state.copy(resources = state.resources + amount))
    }

    /** ジェムを加算する（飽和）。 */
    fun addGems(
        state: GameState,
        amount: Long,
    ): ActionResult {
        if (amount < 0) return fail(ActionFailure.InvalidArgument)
        return ActionResult.Success(state.copy(gems = satAdd(state.gems, amount)))
    }

    /** アイテムを [count] 個加算する。 */
    fun addItems(
        state: GameState,
        itemId: String,
        count: Int,
    ): ActionResult {
        if (count <= 0) return fail(ActionFailure.InvalidArgument)
        val have = state.items[itemId] ?: 0
        val sum = have.toLong() + count
        return ActionResult.Success(
            state.copy(items = state.items + (itemId to minOf(sum, Int.MAX_VALUE.toLong()).toInt())),
        )
    }

    /** チュートリアルの進行を [step]（0..3）へ進める。後戻りはしない（現在以下なら何も変えず成功）。 */
    fun setTutorialStep(
        state: GameState,
        step: Int,
    ): ActionResult {
        if (step < 0 || step > TUTORIAL_DONE) return fail(ActionFailure.InvalidArgument)
        if (step <= state.tutorialStep) return ActionResult.Success(state)
        return ActionResult.Success(state.copy(tutorialStep = step))
    }

    /**
     * 英雄を [times]（1 か 10）回召喚する。Tavern が Lv1 以上必要。チケットを優先し、足りなければ通常は Gold、
     * プレミアムはジェムを払う（10 連は一括価格）。確率・天井・SR 保証は heroes.json（[Summoner]）。
     */
    fun summon(
        state: GameState,
        kind: SummonKind,
        times: Int,
        data: GameData,
    ): ActionResult {
        if (times != 1 && times != TEN_ROLL) return fail(ActionFailure.InvalidArgument)
        if (state.slots.none { it.type == BuildingType.TAVERN && it.level >= 1 }) {
            return fail(ActionFailure.PrereqNotMet(listOf(Prereq(BuildingType.TAVERN, 1))))
        }
        val table = Summoner.table(data, kind)
        val tickets = state.items[table.ticketItem] ?: 0
        var paid = state
        if (tickets >= times) {
            val left = tickets - times
            val items = if (left > 0) paid.items + (table.ticketItem to left) else paid.items - table.ticketItem
            paid = paid.copy(items = items)
        } else if (kind == SummonKind.NORMAL) {
            val cost = Resources(gold = if (times == 1) table.gold else table.goldTen)
            val rest =
                when (val check = Costs.check(paid.resources, cost)) {
                    is CostCheck.Missing -> return fail(ActionFailure.NotEnoughResources(check.missing))
                    CostCheck.Ok -> paid.resources.minusOrNull(cost) ?: return fail(ActionFailure.InvalidArgument)
                }
            paid = paid.copy(resources = rest)
        } else {
            val gems = if (times == 1) table.gems else table.gemsTen
            if (paid.gems < gems) return fail(ActionFailure.NotEnoughGems(gems - paid.gems))
            paid = paid.copy(gems = paid.gems - gems)
        }
        val drawn = Summoner.draw(paid, kind, times, data)
        return ActionResult.Success(drawn.state, listOf(GameEvent.HeroSummoned(drawn.result)))
    }

    /** 英雄 [heroId] に経験値アイテム [itemId] を [count] 個使う。上限レベルで止め、余った経験値は捨てる。 */
    fun useHeroExp(
        state: GameState,
        heroId: String,
        itemId: String,
        count: Int,
        data: GameData,
    ): ActionResult {
        if (count <= 0) return fail(ActionFailure.InvalidArgument)
        val hero = state.heroes.firstOrNull { it.heroId == heroId } ?: return fail(ActionFailure.HeroNotOwned)
        if (!HeroGrowth.isExpItem(itemId, data) || (state.items[itemId] ?: 0) < count) {
            return fail(ActionFailure.ItemNotOwned)
        }
        if (hero.level >= HeroGrowth.levelCap(hero.star, data)) return fail(ActionFailure.MaxLevel)
        val grown = HeroGrowth.addExp(hero, satMul(data.items.getValue(itemId).value, count.toLong()), data)
        val left = state.items.getValue(itemId) - count
        return ActionResult.Success(
            state.copy(
                heroes = state.heroes.map { if (it.heroId == heroId) grown else it },
                items = if (left > 0) state.items + (itemId to left) else state.items - itemId,
            ),
        )
    }

    /** 英雄 [heroId] の星を 1 つ上げる（欠片を消費。星 5 が上限）。 */
    fun starUp(
        state: GameState,
        heroId: String,
        data: GameData,
    ): ActionResult {
        val hero = state.heroes.firstOrNull { it.heroId == heroId } ?: return fail(ActionFailure.HeroNotOwned)
        if (hero.star >= data.heroRules.starLevelCaps.size) return fail(ActionFailure.MaxStar)
        val need = data.heroRules.starUpShards[hero.star - 1]
        val have = state.heroShards[heroId] ?: 0
        if (have < need) return fail(ActionFailure.NotEnoughShards(need - have))
        val shards = if (have - need > 0) state.heroShards + (heroId to have - need) else state.heroShards - heroId
        return ActionResult.Success(
            state.copy(
                heroes = state.heroes.map { if (it.heroId == heroId) it.copy(star = it.star + 1) else it },
                heroShards = shards,
            ),
        )
    }

    /** 英雄 [heroId] のスキル [skillIndex]（0..2）を 1 Lv 上げる（skill_book を 1 冊消費。上限は maxSkillLevel）。 */
    fun skillUp(
        state: GameState,
        heroId: String,
        skillIndex: Int,
        data: GameData,
    ): ActionResult {
        val hero = state.heroes.firstOrNull { it.heroId == heroId } ?: return fail(ActionFailure.HeroNotOwned)
        if (skillIndex !in hero.skillLevels.indices) return fail(ActionFailure.InvalidArgument)
        if (hero.skillLevels[skillIndex] >= data.heroRules.maxSkillLevel) return fail(ActionFailure.MaxLevel)
        val books = state.items[SKILL_BOOK] ?: 0
        if (books < 1) return fail(ActionFailure.ItemNotOwned)
        val levels = hero.skillLevels.mapIndexed { i, lv -> if (i == skillIndex) lv + 1 else lv }
        return ActionResult.Success(
            state.copy(
                heroes = state.heroes.map { if (it.heroId == heroId) it.copy(skillLevels = levels) else it },
                items = if (books > 1) state.items + (SKILL_BOOK to books - 1) else state.items - SKILL_BOOK,
            ),
        )
    }

    /** 期限付きバフを追加する（同 id は置き換え）。 */
    fun addBoost(
        state: GameState,
        boost: Boost,
    ): ActionResult = ActionResult.Success(state.copy(boosts = state.boosts.filter { it.id != boost.id } + boost))

    /** 加速アイテム [itemId] を [count] 個、タイマー [jobId] に使う。残り時間を超える分は捨てる。 */
    fun applySpeedupItem(
        state: GameState,
        jobId: Long,
        itemId: String,
        count: Int,
        data: GameData,
    ): ActionResult {
        if (count <= 0) return fail(ActionFailure.InvalidArgument)
        val job = state.timers.firstOrNull { it.id == jobId } ?: return fail(ActionFailure.JobNotFound)
        val item = data.items[itemId]
        if ((state.items[itemId] ?: 0) < count || item == null) return fail(ActionFailure.ItemNotOwned)
        if (item.category?.matches(job.kind) != true) return fail(ActionFailure.CategoryMismatch)
        val reduction = satMul(item.seconds, count.toLong())
        val newEnd = maxOf(state.nowEpochSec, job.endEpochSec - reduction)
        val left = (state.items[itemId] ?: 0) - count
        val items = if (left > 0) state.items + (itemId to left) else state.items - itemId
        val next =
            state.copy(
                items = items,
                timers = state.timers.map { if (it.id == jobId) it.copy(endEpochSec = newEnd) else it },
            )
        val settled = Simulation.tick(next, 0, data)
        return ActionResult.Success(settled.state, settled.events, tracked = true)
    }

    /** ジェムでタイマー [jobId] を即時完了させる。足りなければ [ActionFailure.NotEnoughGems]。 */
    fun gemSkip(
        state: GameState,
        jobId: Long,
        data: GameData,
    ): ActionResult {
        val job = state.timers.firstOrNull { it.id == jobId } ?: return fail(ActionFailure.JobNotFound)
        val cost =
            VipCalculator.discountedGemCost(
                GemSkip.cost(maxOf(0L, job.endEpochSec - state.nowEpochSec), data),
                state,
                data,
            )
        if (state.gems < cost) return fail(ActionFailure.NotEnoughGems(cost - state.gems))
        val next =
            state.copy(
                gems = state.gems - cost,
                timers = state.timers.map { if (it.id == jobId) it.copy(endEpochSec = state.nowEpochSec) else it },
            )
        val settled = Simulation.tick(next, 0, data)
        return ActionResult.Success(settled.state, settled.events, tracked = true)
    }

    /** [kind] の行軍を [target] へ出す（兵は城から外れる）。 */
    fun startMarch(
        state: GameState,
        kind: MarchKind,
        target: TileRef,
        troops: Map<String, Long>,
        heroIds: List<String>,
        data: GameData,
    ): ActionResult = MarchActions.start(state, kind, target, troops, heroIds, data)

    /** 行軍 [marchId] を呼び戻す。 */
    fun recallMarch(
        state: GameState,
        marchId: Long,
        data: GameData,
    ): ActionResult = MarchActions.recall(state, marchId, data)

    /** メール [mailId] を既読にする。 */
    fun markMailRead(
        state: GameState,
        mailId: Long,
    ): ActionResult = ActionResult.Success(MailBox.markRead(state, mailId))

    /** 端末の UTC オフセット（秒）を設定する（活動時間帯の判定に使う）。 */
    fun setUtcOffset(
        state: GameState,
        sec: Int,
    ): ActionResult = AllianceActions.setUtcOffset(state, sec)

    /** 自由入力の最大文字数。 */
    const val MAX_CHAT_TEXT = AllianceActions.MAX_TEXT

    /** この種類のタイマーはヘルプを求められるか。 */
    fun canRequestHelp(kind: com.ttmrase.crownfall.core.model.TimerKind): Boolean = AllianceActions.canRequestHelp(kind)

    /** タイマー [jobId] にヘルプを求める（同盟メンバーがヘルプしてくれる）。 */
    fun requestHelp(
        state: GameState,
        jobId: Long,
    ): ActionResult = AllianceActions.requestHelp(state, jobId)

    /** 同盟ヘルプ一覧の全件にヘルプする（1 日の上限あり）。 */
    fun helpAll(
        state: GameState,
        data: GameData,
    ): ActionResult = AllianceActions.helpAll(state, data)

    /** 資源の要請 [requestId] に応える（Trading Post の Lv で手数料が下がる）。 */
    fun fulfillRequest(
        state: GameState,
        requestId: Long,
        data: GameData,
    ): ActionResult = AllianceActions.fulfillRequest(state, requestId, data)

    /** 同盟テック [techId] に寄付する。 */
    fun donate(
        state: GameState,
        techId: String,
        kind: DonationKind,
        data: GameData,
    ): ActionResult = AllianceActions.donate(state, techId, kind, data)

    /** 同盟ショップで [shopId] を買う。 */
    fun buyAllianceShop(
        state: GameState,
        shopId: String,
        data: GameData,
    ): ActionResult = AllianceActions.buyShop(state, shopId, data)

    /** ギフト箱 [giftId] を開ける。 */
    fun openGift(
        state: GameState,
        giftId: Long,
        data: GameData,
    ): ActionResult = AllianceActions.openGift(state, giftId, data)

    /** AI 主催のラリー [rallyId] に参加する。 */
    fun joinRally(
        state: GameState,
        rallyId: Long,
        troops: Map<String, Long>,
        heroIds: List<String>,
        data: GameData,
    ): ActionResult = AllianceActions.joinRally(state, rallyId, troops, heroIds, data)

    /** プレイヤーの自由入力を [channel]（alliance / dm_<persona>）に書き込む。 */
    fun submitFreeText(
        state: GameState,
        text: String,
        channel: String,
        data: GameData,
    ): ActionResult = AllianceActions.submitFreeText(state, text, channel, data)

    /**
     * 文面待ちのチャット [pendingId] に [lines] を書き戻す。[done] が true ならキューから外す
     * （false の間は 1 行ずつ出すためキューに残す）。[at] で時刻、[firstLine] で先頭行か（クイズの選択肢用）を指定できる。
     */
    fun appendChat(
        state: GameState,
        pendingId: Long,
        lines: List<SpokenText>,
        data: GameData,
        done: Boolean = true,
        at: Long? = null,
        firstLine: Boolean = true,
    ): ActionResult = AllianceActions.appendChat(state, pendingId, lines, data, done, at, firstLine)

    /** ぽんずのクイズ（メッセージ [messageId]）に [option] 番で答える。 */
    fun answerQuiz(
        state: GameState,
        messageId: Long,
        option: Int,
        data: GameData,
    ): ActionResult = AllianceActions.answerQuiz(state, messageId, option, data)

    /** チャンネルを既読にする。 */
    fun markChannelRead(
        state: GameState,
        channel: String,
    ): ActionResult = AllianceActions.markChannelRead(state, channel)

    /** 新しいセッションを始める（前景になったとき）。ログイン pt と本日限定。 */
    fun beginSession(
        state: GameState,
        data: GameData,
    ): ActionResult = ShopActions.beginSession(state, data)

    /** 期限切れのオファーを片付ける（量産型は一度だけ「復活」）。 */
    fun refreshOffers(
        state: GameState,
        data: GameData,
    ): ActionResult = ShopActions.refreshOffers(state, data)

    /** オファー [offerId] を閉じる（期限までは棚に残る）。[byBack] は「戻る」で閉じたとき（自動ポップアップを止める）。 */
    fun dismissOffer(
        state: GameState,
        offerId: Long,
        byBack: Boolean = false,
    ): ActionResult = ShopActions.dismissOffer(state, offerId, byBack)

    /** 資源不足のタップ。不足ちょうどのパックを積む。 */
    fun offerForShortage(
        state: GameState,
        missing: Resources,
        data: GameData,
    ): ActionResult = ShopActions.offerForShortage(state, missing, data)

    /** ジェム不足のタップ。不足以上で最小の SKU を積む。 */
    fun offerForGemShortage(
        state: GameState,
        missing: Long,
        data: GameData,
    ): ActionResult = ShopActions.offerForGemShortage(state, missing, data)

    /** 2 本目ビルダー枠のタップ。 */
    fun offerForSecondBuilder(
        state: GameState,
        data: GameData,
    ): ActionResult = ShopActions.offerForSecondBuilder(state, data)

    /** イベント集計で 2〜4 位（1 位との差 [gapPt]）。 */
    fun offerForRanking(
        state: GameState,
        rank: Int,
        gapPt: Long,
        data: GameData,
    ): ActionResult = ShopActions.offerForRanking(state, rank, gapPt, data)

    /** 退出前の「待って！」を 1 日 1 回積む。 */
    fun exitOffer(
        state: GameState,
        data: GameData,
    ): ActionResult = ShopActions.exitOffer(state, data)

    /** 擬似購入。[offerId] は [com.ttmrase.crownfall.core.shop.ProductIds] かカタログの SKU。実通信なし。 */
    fun fakePurchase(
        state: GameState,
        offerId: String,
        data: GameData,
    ): ActionResult = ShopActions.fakePurchase(state, offerId, data)

    /** VIP デイリーチェストを受け取る。 */
    fun claimVipChest(
        state: GameState,
        data: GameData,
    ): ActionResult = ShopActions.claimVipChest(state, data)

    /** 月パスの今日の分を受け取る。 */
    fun claimMonthPass(
        state: GameState,
        data: GameData,
    ): ActionResult = ShopActions.claimMonthPass(state, data)

    /** 成長ファンドの到達済みの分をまとめて受け取る。 */
    fun claimGrowthFund(
        state: GameState,
        data: GameData,
    ): ActionResult = ShopActions.claimGrowthFund(state, data)

    /** ラッキーホイールを回す（無料 → 追加分 → [payGems] ならジェム）。 */
    fun spinWheel(
        state: GameState,
        payGems: Boolean,
        data: GameData,
    ): ActionResult = ShopActions.spinWheel(state, payGems, data)

    /** ショップの設定を保存する。 */
    fun setShopSettings(
        state: GameState,
        settings: ShopSettings,
    ): ActionResult = ShopActions.setShopSettings(state, settings)

    /** クエスト [questId]（チャプター / デイリー項目 / 同盟 / 実績）の報酬を受け取る。 */
    fun claimQuest(
        state: GameState,
        questId: String,
        data: GameData,
    ): ActionResult = QuestActions.claimQuest(state, questId, data)

    /** デイリー宝箱の段 [tier] を受け取る。 */
    fun claimDailyChest(
        state: GameState,
        tier: Int,
        data: GameData,
    ): ActionResult = QuestActions.claimDailyChest(state, tier, data)

    /** ショップ画面を開いた（デイリーの進捗用）。 */
    fun recordShopOpened(state: GameState): ActionResult = QuestActions.recordShopOpened(state)

    /** すべてのメールを既読にする。 */
    fun markAllMailRead(state: GameState): ActionResult = ActionResult.Success(MailBox.markAllRead(state))

    private fun fail(reason: ActionFailure) = ActionResult.Failure(reason)
}
