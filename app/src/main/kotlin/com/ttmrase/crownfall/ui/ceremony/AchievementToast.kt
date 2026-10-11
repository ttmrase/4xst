package com.ttmrase.crownfall.ui.ceremony

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.QuestDef
import com.ttmrase.crownfall.core.quest.QuestLogic
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.ui.common.Sfx
import com.ttmrase.crownfall.ui.common.SfxId
import com.ttmrase.crownfall.ui.common.collectCalmState
import com.ttmrase.crownfall.ui.quest.condText
import kotlinx.coroutines.delay

private const val SHOW_MS = 3200L
private const val GAP_MS = 350L
private val NAVY = Color(0xE612203F)
private val GOLD = Color(0xFFE8C547)

/**
 * 実績の達成トースト（M11 T11.3）。実績が新しく達成されたら、画面上部に帯を 3 秒出す（重なったら順番に）。
 * 起動直後に最初に見た時点で達成済みのものは出さない（旧セーブで洪水にならない）。タップを妨げない。
 */
@Composable
fun AchievementToastHost(
    session: GameSession,
    data: GameData,
    modifier: Modifier = Modifier,
) {
    val state by session.collectCalmState()
    val context = LocalContext.current
    var known by remember { mutableStateOf<Set<String>?>(null) }
    var queue by remember { mutableStateOf(listOf<QuestDef>()) }
    var current by remember { mutableStateOf<QuestDef?>(null) }
    val s = state
    val ids = if (s == null) emptyList() else QuestLogic.claimableOneShots(s, data).map { it.id }
    LaunchedEffect(ids) {
        val seen = known
        if (seen == null) {
            if (s != null) known = ids.toSet()
        } else {
            val fresh = data.quests.achievements.filter { it.id in ids && it.id !in seen }
            known = seen + ids
            if (fresh.isNotEmpty()) queue = queue + fresh
        }
    }
    LaunchedEffect(queue, current) {
        if (current == null && queue.isNotEmpty()) {
            current = queue.first()
            queue = queue.drop(1)
            Sfx.play(context, SfxId.NOTIFY)
            delay(SHOW_MS)
            current = null
            delay(GAP_MS)
        }
    }
    Box(modifier) {
        AnimatedVisibility(
            visible = current != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            val def = current
            if (def != null) {
                Text(
                    stringResource(R.string.achievement_toast, condText(context, def.cond)),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier =
                        Modifier
                            .padding(12.dp)
                            .background(NAVY, RoundedCornerShape(14.dp))
                            .border(1.5.dp, GOLD, RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}
