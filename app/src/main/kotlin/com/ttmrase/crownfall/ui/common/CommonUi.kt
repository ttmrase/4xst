package com.ttmrase.crownfall.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.ui.glyph
import com.ttmrase.crownfall.ui.kit.Cf
import com.ttmrase.crownfall.ui.kit.FrameStyle
import com.ttmrase.crownfall.ui.kit.GlyphIcon
import com.ttmrase.crownfall.ui.kit.gameFrame
import com.ttmrase.crownfall.ui.nameRes
import com.ttmrase.crownfall.ui.theme.CrownFonts

/**
 * コストを資源ごとの札で並べる（アイコン・必要量・手持ち。不足は赤い縁と「あと n」）。コストが 0 の資源は出さない。
 * 建設・訓練・研究・治療の詳細で共通に使う。
 */
@Composable
fun CostChips(
    have: Resources,
    cost: Resources,
    missing: Resources,
    modifier: Modifier = Modifier,
) {
    val types = ResourceType.entries.filter { cost[it] > 0 }
    if (types.isEmpty()) return
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        types.forEach { type ->
            val short = missing[type]
            val name = stringResource(type.nameRes())
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .gameFrame(FrameStyle.Inset, corner = 10.dp, accent = if (short > 0) ShortRed else null)
                        .semantics(mergeDescendants = true) { contentDescription = name }
                        .padding(horizontal = 4.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GlyphIcon(type.glyph(), size = 26.dp)
                Text(
                    ShortNumber.format(cost[type]),
                    color = if (short > 0) ShortText else Cf.Ivory,
                    fontWeight = FontWeight.Bold,
                    fontFamily = CrownFonts.Numeric,
                    fontSize = 14.sp,
                    lineHeight = 17.sp,
                )
                Text(
                    if (short > 0) {
                        stringResource(R.string.sheet_missing, ShortNumber.format(short))
                    } else {
                        stringResource(R.string.cost_have, ShortNumber.format(have[type]))
                    },
                    color = if (short > 0) ShortText else Cf.Dim,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    maxLines = 1,
                )
            }
        }
        // 4 種に満たないときも札の幅をそろえる
        repeat(ResourceType.entries.size - types.size) { Box(Modifier.weight(1f)) }
    }
}

private val ShortRed = Color(0xFFE5484D)
private val ShortText = Color(0xFFFFA79B)

/** コストの行表示（所持 / 必要。不足は赤字で「あと n」）。コストが 0 の資源は出さない。 */
@Composable
fun CostRows(
    have: Resources,
    cost: Resources,
    missing: Resources,
    modifier: Modifier = Modifier,
) = CostChips(have, cost, missing, modifier)

/** 直前のスナックバーを閉じて [message] を出す。 */
suspend fun SnackbarHostState.showReplacing(message: String) {
    currentSnackbarData?.dismiss()
    showSnackbar(message)
}

/** [ActionFailure.NotEnoughResources] なら不足量、それ以外は null。 */
fun ActionFailure.missingResources(): Resources? = (this as? ActionFailure.NotEnoughResources)?.missing
