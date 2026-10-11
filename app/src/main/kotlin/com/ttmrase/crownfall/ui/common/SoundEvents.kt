package com.ttmrase.crownfall.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.session.GameSession

/**
 * ゲームの出来事に効果音をつなぐ（M11 T11.3）。建物の完成・メールや助けの到着・購入の完了。
 * 城の昇格の音は演出側、機能の解放の音は解放の演出側で鳴らす。ミュート・マナーモードは [Sfx] が見る。
 */
@Composable
fun SoundEvents(session: GameSession?) {
    val context = LocalContext.current
    LaunchedEffect(session) {
        session?.events?.collect { e ->
            when (e) {
                is GameEvent.BuildingLevelUp -> if (e.type != BuildingType.CASTLE) Sfx.play(context, SfxId.BUILD_DONE)
                is GameEvent.MailReceived, is GameEvent.HelpReceived -> Sfx.play(context, SfxId.NOTIFY)
                is GameEvent.Purchased -> Sfx.play(context, SfxId.PURCHASE)
                else -> Unit
            }
        }
    }
}
