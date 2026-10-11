package com.ttmrase.crownfall.core.model

import com.ttmrase.crownfall.core.data.OfferBadge
import com.ttmrase.crownfall.core.data.PackContents
import kotlinx.serialization.Serializable

/** オファーの頻度設定（04 §3）。既定は量産型。 */
@Serializable
enum class OfferFrequency { LOW, STANDARD, MASS }

/** ショップまわりの設定（決済演出は :app が読む）。 */
@Serializable
data class ShopSettings(
    val frequency: OfferFrequency = OfferFrequency.MASS,
    /** レシートに「（擬似・実支払い ¥0）」を付けるか。既定 OFF（没入優先）。 */
    val receiptNote: Boolean = false,
    /** 2% の「エラー: もう一度お試しください」演出。 */
    val errorAnimation: Boolean = true,
    /** 処理中の待ちを長め（1.2〜2.8 秒、10% で 4〜6 秒）にするか。false なら短縮。 */
    val longProcessing: Boolean = true,
)

/**
 * キューに積まれたオファー。[sku] はパック ID / ジェム SKU / 不足ちょうどの "shortage"。
 * 中身と価格はここに持つ（不足量から動的に作るものがあるため）。[dismissed] は閉じた印（期限までは棚に残る）。
 */
@Serializable
data class Offer(
    val id: Long,
    val sku: String,
    val trigger: String,
    val createdEpochSec: Long,
    val expiresEpochSec: Long,
    val priceYen: Long,
    val listValueYen: Long,
    val badge: OfferBadge,
    val contents: PackContents,
    val dismissed: Boolean = false,
    /** 期限切れ後に別名で出し直したもの（「復活！」）。量産型だけ。 */
    val revived: Boolean = false,
    /** 文言用の数値（lv / food / wood / stone / gold / missing / rank / gap / pt / vip など）。 */
    val params: Map<String, Long> = emptyMap(),
)

/** 擬似課金の 1 件。 */
@Serializable
data class LedgerRecord(
    val epochSec: Long,
    val sku: String,
    val priceYen: Long,
    val trigger: String,
    val context: Map<String, Long> = emptyMap(),
)

/**
 * 擬似課金台帳。ゲームのロジックからは読まない（04 §6）。
 * [records] は古い順（末尾が最新）で上限まで。あふれた古い記録は消えるが、集計値（総額・月別・トリガー別・SKU 別）は保つ。
 */
@Serializable
data class Ledger(
    val records: List<LedgerRecord> = emptyList(),
    val count: Long = 0,
    val totalYen: Long = 0,
    /** キーは "YYYY-MM"（現地）。 */
    val byMonth: Map<String, Long> = emptyMap(),
    val byTrigger: Map<String, Long> = emptyMap(),
    val bySku: Map<String, Long> = emptyMap(),
    /** 上限であふれて記録から外れた件数。 */
    val folded: Long = 0,
)

/** ショップ全体の状態。すべて既定値付き（セーブの版は上げない）。 */
@Serializable
data class ShopState(
    val settings: ShopSettings = ShopSettings(),
    val vipPoints: Long = 0,
    val loginStreak: Int = 0,
    val lastLoginDay: Long = -1,
    val firstPurchased: Set<String> = emptySet(),
    val offerQueue: List<Offer> = emptyList(),
    val lastShownEpochSec: Map<String, Long> = emptyMap(),
    /** 現在のセッションでキューに積んだ数（頻度の上限判定用）。 */
    val sessionOffers: Int = 0,
    /** 直近にオファーのポップアップを閉じた時刻（次の自動ポップアップまでの間隔の起点）。 */
    val lastPopupClosedEpochSec: Long = 0,
    /** 「戻る」で閉じられたので、このセッションの残りの自動ポップアップを止める（棚とバッジには残る）。 */
    val popupsStopped: Boolean = false,
    /** プレイヤーがタップして呼んだオファーの id。自動ポップアップの抑制を受けず、最優先で出す（閉じたら null）。 */
    val requestedOfferId: Long? = null,
    /** 連鎖の現在の段（直近の購入が連鎖パックならその tier、それ以外は 0）。 */
    val chainRun: Int = 0,
    val nextOfferId: Long = 1,
    /** 1 日の上限カウンタの対象日（現地日）。 */
    val day: Long = -1,
    val stockUsed: Map<String, Int> = emptyMap(),
    val loginPopupDay: Long = -1,
    val exitOfferDay: Long = -1,
    val vipChestDay: Long = -1,
    /** 昇格の演出を見せた最大の VIP Lv（-1 は未初期化。最初に UI が開いたとき現在の Lv にそろえる）。 */
    val vipCelebrated: Int = -1,
    val piggyGems: Long = 0,
    val wheelFreeDay: Long = -1,
    val wheelExtra: Int = 0,
    val wheelSpins: Long = 0,
    val monthPassUntilEpochSec: Long = 0,
    val monthPassClaimedDay: Long = -1,
    val growthFundOwned: Boolean = false,
    val growthFundClaimed: Set<Int> = emptySet(),
    val purchaseCount: Long = 0,
    val ledger: Ledger = Ledger(),
)

/** レシート（RECEIPT メールの本文）。[orderA]/[orderB] は注文番号 ORD-XXXX-XXXX の 2 つの 4 桁（36 進）。 */
@Serializable
data class Receipt(
    val sku: String,
    val priceYen: Long,
    val contents: PackContents,
    val orderA: Int,
    val orderB: Int,
    val firstDouble: Boolean = false,
    /** レシートの「擬似」表記を付けるか（購入時の設定を写す）。 */
    val note: Boolean = false,
)
