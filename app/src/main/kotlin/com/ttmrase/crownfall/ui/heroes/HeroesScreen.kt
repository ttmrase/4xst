package com.ttmrase.crownfall.ui.heroes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.HeroDef
import com.ttmrase.crownfall.core.data.ItemKind
import com.ttmrase.crownfall.core.hero.HeroGrowth
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.HeroState
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.navigation.Screen
import com.ttmrase.crownfall.ui.common.showReplacing
import com.ttmrase.crownfall.ui.failureMessage
import com.ttmrase.crownfall.ui.heroDescRes
import com.ttmrase.crownfall.ui.heroNameRes
import com.ttmrase.crownfall.ui.itemName
import com.ttmrase.crownfall.ui.nameRes
import com.ttmrase.crownfall.ui.rarityColor
import com.ttmrase.crownfall.ui.skillEffectText
import kotlinx.coroutines.launch

/** 英雄画面（T3.5）。一覧（未所持はシルエット）・詳細シート（育成）・召喚への導線。 */
@Composable
fun HeroesScreen(
    session: GameSession,
    data: GameData,
    snackbarHostState: SnackbarHostState,
    onNavigate: (Screen) -> Unit,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val s = state ?: return
    val scope = rememberCoroutineScope()

    fun run(action: (GameState) -> ActionResult) {
        scope.launch {
            val r = session.dispatch(action)
            if (r is ActionResult.Failure) snackbarHostState.showReplacing(failureMessage(context, r.reason))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.screen_heroes), style = MaterialTheme.typography.titleLarge)
            Button(onClick = { onNavigate(Screen.Summon) }) { Text(stringResource(R.string.hero_go_summon)) }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(data.heroes.values.toList(), key = { it.id }) { def ->
                val owned = s.heroes.firstOrNull { it.heroId == def.id }
                HeroCard(def = def, owned = owned, onClick = { selected = def.id })
            }
        }
    }

    val heroId = selected
    if (heroId != null) {
        val def = data.heroes[heroId]
        if (def == null) {
            selected = null
        } else {
            HeroDetailSheet(
                state = s,
                data = data,
                def = def,
                onUseExp = { itemId -> run { Actions.useHeroExp(it, heroId, itemId, 1, data) } },
                onSkillUp = { index -> run { Actions.skillUp(it, heroId, index, data) } },
                onStarUp = { run { Actions.starUp(it, heroId, data) } },
                onDismiss = { selected = null },
            )
        }
    }
}

@Composable
private fun HeroCard(
    def: HeroDef,
    owned: HeroState?,
    onClick: () -> Unit,
) {
    val color = rarityColor(def.rarity)
    Card(
        modifier = Modifier.fillMaxWidth().alpha(if (owned == null) 0.5f else 1f).clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(2.dp, color),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(modifier = Modifier.padding(4.dp), contentAlignment = Alignment.Center) {
                Text(if (owned == null) "？" else "⚔", style = MaterialTheme.typography.headlineMedium, color = color)
            }
            Text(def.rarity.name, style = MaterialTheme.typography.labelSmall, color = color)
            Text(
                if (owned == null) "？？？" else heroNameRes(def.id)?.let { stringResource(it) } ?: def.id,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            if (owned != null) {
                Text(
                    stringResource(R.string.hero_star, owned.star) + "  " +
                        stringResource(R.string.hero_level, owned.level),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeroDetailSheet(
    state: GameState,
    data: GameData,
    def: HeroDef,
    onUseExp: (String) -> Unit,
    onSkillUp: (Int) -> Unit,
    onStarUp: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val owned = state.heroes.firstOrNull { it.heroId == def.id }
    val shards = state.heroShards[def.id] ?: 0
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier =
                Modifier.fillMaxWidth().verticalScroll(
                    rememberScrollState(),
                ).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val name = heroNameRes(def.id)?.let { stringResource(it) } ?: def.id
            Text(name, style = MaterialTheme.typography.headlineSmall, color = rarityColor(def.rarity))
            heroDescRes(def.id)?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
            Text(
                def.rarity.name + "  " +
                    stringResource(R.string.hero_affinity, stringResource(def.troopAffinity.nameRes())),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                stringResource(
                    R.string.hero_stats,
                    ShortNumber.format(def.attack),
                    ShortNumber.format(def.defense),
                    ShortNumber.format(def.hp),
                ),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(stringResource(R.string.hero_shards, shards), style = MaterialTheme.typography.labelMedium)
            HorizontalDivider()
            if (owned == null) {
                Text(stringResource(R.string.hero_not_owned), color = MaterialTheme.colorScheme.error)
            } else {
                OwnedSection(state, data, def, owned, shards, onUseExp, onSkillUp, onStarUp)
            }
            Column(modifier = Modifier.padding(bottom = 24.dp)) {}
        }
    }
}

@Composable
private fun OwnedSection(
    state: GameState,
    data: GameData,
    def: HeroDef,
    owned: HeroState,
    shards: Int,
    onUseExp: (String) -> Unit,
    onSkillUp: (Int) -> Unit,
    onStarUp: () -> Unit,
) {
    val context = LocalContext.current
    val rules = data.heroRules
    val cap = HeroGrowth.levelCap(owned.star, data)
    Text(
        stringResource(R.string.hero_star, owned.star) + "  " +
            stringResource(R.string.hero_level, owned.level) + " / $cap",
        style = MaterialTheme.typography.titleMedium,
    )
    if (owned.level >= cap) {
        Text(stringResource(R.string.hero_exp_max), style = MaterialTheme.typography.labelMedium)
    } else {
        val need = rules.expToNext[owned.level - 1]
        LinearProgressIndicator(progress = {
            (owned.exp.toFloat() / need.toFloat()).coerceIn(0f, 1f)
        }, modifier = Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.hero_exp, ShortNumber.format(owned.exp), ShortNumber.format(need)),
            style = MaterialTheme.typography.labelMedium,
        )
    }
    // 経験値アイテム
    val expItems =
        state.items.entries.mapNotNull {
                (id, n) ->
            data.items[id]?.takeIf { it.kind == ItemKind.HERO_EXP && n > 0 }?.let { it to n }
        }
            .sortedBy { it.first.value }
    if (expItems.isNotEmpty() && owned.level < cap) {
        Text(stringResource(R.string.hero_use_exp), style = MaterialTheme.typography.titleSmall)
        expItems.forEach { (item, n) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.hero_use_exp_row, itemName(context, item), n))
                OutlinedButton(onClick = { onUseExp(item.id) }) { Text(stringResource(R.string.common_use)) }
            }
        }
    }
    // 星
    if (owned.star < rules.starLevelCaps.size) {
        val need = rules.starUpShards[owned.star - 1]
        Button(onClick = onStarUp, enabled = shards >= need, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.hero_star_up, shards, need))
        }
    }
    HorizontalDivider()
    // スキル
    Text(stringResource(R.string.hero_skills), style = MaterialTheme.typography.titleSmall)
    val books = state.items["skill_book"] ?: 0
    def.skills.forEachIndexed { index, skill ->
        val level = owned.skillLevels.getOrElse(index) { 1 }
        val value = skill.values.getOrElse(level - 1) { 0 }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(
                        if (skill.slot == "active") R.string.hero_skill_active else R.string.hero_skill_passive,
                    ) +
                        "  " + stringResource(R.string.hero_skill_level, level, rules.maxSkillLevel),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    skillEffectText(context, skill.effectKey, value) ?: skill.effectKey,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            OutlinedButton(onClick = { onSkillUp(index) }, enabled = books > 0 && level < rules.maxSkillLevel) {
                Text("＋" + books.toString())
            }
        }
    }
}
