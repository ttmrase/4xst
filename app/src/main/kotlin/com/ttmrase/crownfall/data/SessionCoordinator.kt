package com.ttmrase.crownfall.data

import com.ttmrase.crownfall.core.shop.OfferEngine
import com.ttmrase.crownfall.core.sim.Actions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * 前景になったときのセッション開始処理。端末のタイムゾーンを :core に伝え（現地日付の判定に使う）、
 * `Actions.beginSession`（ログイン pt・本日限定オファー）を呼ぶ。1 日の最初なら [loginChain] を true にして、
 * ログイン時の 3 連ポップアップ（本日限定 → イベント告知 → 同盟ギフト）を OfferPresenter に始めさせる。
 */
class SessionCoordinator(
    private val repository: GameRepository,
    private val scope: CoroutineScope,
) {
    private val _loginChain = MutableStateFlow(false)

    /** ログイン時のポップアップ連鎖を出すべきか（その日の最初のセッションだけ true）。 */
    val loginChain: StateFlow<Boolean> = _loginChain.asStateFlow()

    /** 連鎖を出し終えた。 */
    fun consumeLoginChain() {
        _loginChain.value = false
    }

    /** 前景に来た。 */
    fun onForeground() {
        scope.launch {
            val ready = repository.startup.first { it is Startup.Ready } as Startup.Ready
            val session = ready.session
            session.state.first { it != null }
            val offset = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / MILLIS
            session.dispatch { Actions.setUtcOffset(it, offset) }
            val before = session.state.value?.shop?.loginPopupDay
            session.dispatch { Actions.beginSession(it, ready.data) }
            val after = session.state.value
            val popup = after?.let { OfferEngine.nextPopup(it, ready.data) }
            if (after != null && after.shop.loginPopupDay != before && popup != null) {
                _loginChain.value = true
            }
        }
    }

    private companion object {
        const val MILLIS = 1000
    }
}
