package com.ttmrase.crownfall.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.ui.nameRes

/** コストの行表示（所持 / 必要。不足は赤字で「あと n」）。コストが 0 の資源は出さない。 */
@Composable
fun CostRows(
    have: Resources,
    cost: Resources,
    missing: Resources,
    modifier: Modifier = Modifier,
) {
    ResourceType.entries.forEach { type ->
        val need = cost[type]
        if (need <= 0) return@forEach
        val short = missing[type]
        Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(type.nameRes()))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (short > 0) {
                    Text(
                        stringResource(R.string.sheet_missing, ShortNumber.format(short)),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "${ShortNumber.format(have[type])} / ${ShortNumber.format(need)}",
                    color = if (short > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** 直前のスナックバーを閉じて [message] を出す。 */
suspend fun SnackbarHostState.showReplacing(message: String) {
    currentSnackbarData?.dismiss()
    showSnackbar(message)
}

/** [ActionFailure.NotEnoughResources] なら不足量、それ以外は null。 */
fun ActionFailure.missingResources(): Resources? = (this as? ActionFailure.NotEnoughResources)?.missing
