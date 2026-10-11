package com.ttmrase.crownfall.ui.reward

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.session.GameSession

/**
 * ゲームの出来事から獲得アニメをつなぐ（M11 T11.2）。採集隊が戻ったら、持ち帰った資源が（城なら HUD へ、
 * 他の画面なら下のナビの「城」タブへ）飛ぶ。メール経由の獲得は含めない。
 */
@Composable
fun RewardEvents(session: GameSession?) {
    val fly = LocalRewardFly.current
    LaunchedEffect(session, fly) {
        session?.events?.collect { e ->
            if (e is GameEvent.GatherCompleted && e.loot > 0) fly.flyAll(fly.originOrDefault(), e.resources)
        }
    }
}
