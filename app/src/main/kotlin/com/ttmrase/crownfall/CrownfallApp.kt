package com.ttmrase.crownfall

import android.app.Application
import android.content.Context
import com.ttmrase.crownfall.data.GameRepository
import com.ttmrase.crownfall.offers.NoOpOfferHooks
import com.ttmrase.crownfall.offers.OfferHooks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 手動 DI のコンテナ。Hilt/Koin は使わない（docs/02 §8）。 */
class AppContainer(context: Context) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** ゲームデータとセッションの保持者。GameData は起動時に IO スレッドで 1 回だけ読む。 */
    val repository = GameRepository(context.applicationContext, appScope)

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
    }
}
