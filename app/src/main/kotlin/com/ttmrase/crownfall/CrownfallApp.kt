package com.ttmrase.crownfall

import android.app.Application
import android.content.Context
import android.util.Log
import com.ttmrase.crownfall.ai.AiService
import com.ttmrase.crownfall.chat.ChatCoordinator
import com.ttmrase.crownfall.data.GameRepository
import com.ttmrase.crownfall.data.SessionCoordinator
import com.ttmrase.crownfall.notify.NoticeScheduler
import com.ttmrase.crownfall.notify.NotificationPrefs
import com.ttmrase.crownfall.offers.NoOpOfferHooks
import com.ttmrase.crownfall.offers.OfferHooks
import com.ttmrase.crownfall.ui.common.Sfx
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** 手動 DI のコンテナ。Hilt/Koin は使わない（docs/02 §8）。 */
class AppContainer(context: Context) {
    // 想定外の例外でプロセスを落とさない（クラス名だけ記録。本文・キーは出さない）。
    private val appScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                CoroutineExceptionHandler { _, e -> Log.e("Crownfall", "uncaught: ${e::class.java.simpleName}") },
        )

    /** ゲームデータとセッションの保持者。GameData は起動時に IO スレッドで 1 回だけ読む。 */
    val repository = GameRepository(context.applicationContext, appScope)

    /** AI 設定・プロバイダ・ルーター（API キーが無くても Scripted で動く）。 */
    val ai = AiService(context.applicationContext, appScope)

    /** 前景になったときのセッション開始（TZ、ログイン pt、本日限定オファー）。 */
    val session = SessionCoordinator(repository, appScope)

    /** チャットの文面づくりと 1 行ずつの表示。前景の間だけ動く。 */
    val chat = ChatCoordinator(repository, ai, appScope)

    /** 通知のスケジュール。背景に入るときに登録し、前景に戻るときに取り消す。 */
    val notices = NoticeScheduler(context, NotificationPrefs(context))

    /** 通知のタップで開く画面（ルート）。NavHost が受け取って開き、null に戻す。 */
    val pendingRoute = MutableStateFlow<String?>(null)

    /** オファー誘導のフック。M6 で実装に差し替える。 */
    val offerHooks: OfferHooks = NoOpOfferHooks
}

/** アプリケーション。 */
class CrownfallApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NoticeScheduler.ensureChannels(this)
        Sfx.init(this)
    }
}
