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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.PackContents
import com.ttmrase.crownfall.ui.reward.LocalRewardFly
import com.ttmrase.crownfall.ui.theme.CrownFonts

private const val FLY_FROM_Y = 0.55f

/**
 * 報酬の受け取り演出（宝箱が揺れ、ひびが入り、光がはじけ、金貨が降り、中身が HUD へ飛ぶ）。VIP デイリーチェスト・月パス・成長ファンド・
 * クエスト（チャプター・デイリー宝箱・実績）で共通に使う。
 * [paidBack] が true のときは「元が取れた！」を大きく出す。戻る・OK で閉じられる（全画面なので外側のタップは無い）。
 */
@Composable
fun RewardDialog(
    title: String,
    contents: PackContents,
    data: GameData,
    paidBack: Boolean,
    onDismiss: () -> Unit,
) {
    // 閉じたら、中身の資源とジェムが HUD へ飛ぶ（ダイアログは別ウィンドウなので、閉じたあとに画面中央から）
    val fly = LocalRewardFly.current
    val metrics = LocalContext.current.resources.displayMetrics
    val finish = {
        fly.flyAll(
            Offset(metrics.widthPixels / 2f, metrics.heightPixels * FLY_FROM_Y),
            contents.resources,
            contents.gems,
        )
        onDismiss()
    }
    Dialog(
        onDismissRequest = finish,
        properties =
            DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
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
                Button(onClick = finish) { Text(stringResource(R.string.common_ok)) }
            }
        }
    }
}
