package com.ttmrase.crownfall.ui.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.PackContents
import com.ttmrase.crownfall.ui.theme.CrownFonts

/**
 * 報酬の受け取り演出（宝箱が開き、金貨が降り、アイコンが所持品へ飛ぶ）。VIP デイリーチェスト・月パス・成長ファンドで使う。
 * [paidBack] が true のときは「元が取れた！」を大きく出す。戻る・OK・外側のタップで閉じられる。
 */
@Composable
fun RewardDialog(
    title: String,
    contents: PackContents,
    data: GameData,
    paidBack: Boolean,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                usePlatformDefaultWidth = false,
            ),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xE60B0A12))) {
            CoinShower(Modifier.fillMaxSize())
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    title,
                    color = FoilGold,
                    fontFamily = CrownFonts.Display,
                    fontWeight = FontWeight.Black,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                PurchaseCelebration(contents)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ContentsLines(contents, data)
                }
                if (paidBack) {
                    Text(
                        stringResource(R.string.fund_payback),
                        color = FoilGold,
                        fontFamily = CrownFonts.Display,
                        fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.displaySmall,
                        modifier = Modifier.shaking(),
                    )
                    Text(
                        stringResource(R.string.fund_payback_sub),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Button(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) }
            }
        }
    }
}
