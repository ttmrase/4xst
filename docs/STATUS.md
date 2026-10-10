# STATUS（Director が更新）

- 現在のマイルストーン: **M6 ショップ・擬似課金**（実装済み・Director レビュー中）。M0〜M5 は ✅ 完了
- 統合ブランチ: `claude/4x-strategy-game-planning-oxo80w`
- 最終更新: 2026-10-10 Director

## 完了
| マイルストーン | 完了 | 根拠 |
| --- | --- | --- |
| M0 基盤 | 2026-10-10 | HEAD 00f5f9b、CI run 38028345848（jvm と apk が緑、crownfall-debug-apk あり）、Debugger QA-OK、Director Approve |
| M5 同盟・AI チャット | 2026-10-10 | 9a427d9 + 修正 71c9f8f（Gemini の既定を GA の gemini-3.1-flash-lite に変更、例外安全性など）、Debugger QA-OK（分のグリッドの R3、24h 分 11〜13ms、リダイレクトと UrlPolicy の修正 2dc11e1）、Director Approve |
| M4 ワールドマップ・戦闘 | 2026-10-10 | 59f6176 + 修正 d5bfb9c / e316217、CI run 38036914082 が緑、Debugger QA-OK（QaWorldTest 10 本。兵の保存則、略奪、R3 を 6 シナリオ × 40 分割、b09c35e）、Director Approve |
| M3 軍事・研究・英雄 | 2026-10-10 | d74a23d + 修正 d939afc、CI run 38033161525 が緑、kover の対象 7 パッケージが 98〜100%、Debugger QA-OK（QaMilitaryTest、OfflineSummary の負数を 81afd4f で修正）、Director Approve |
| M2 城内 UI | 2026-10-10 | 7a3dcd4 + 修正 b22934e / 4924700 / 705ad2c、CI run 38030769719 が緑、Debugger QA-OK（QaSessionTest、GameSession の S3 不具合 2 件を 46cdc42 で修正）、Director Approve |
| M1 コアシミュレーション | 2026-10-10 | d100d48 + 小修正 31c92d0 / 65462b2、CI run 38029693916 が緑、kover の sim / economy が 100%、Debugger QA-OK（境界テスト 795d5fe、CI に koverVerify を追加）、Director Approve |

## 進行中チケット
| ID | 担当 | 状態 | SHA |
| --- | --- | --- | --- |
| M0 後追い（下部ナビの popUpTo、:ai の api(core)） | Implementer | ✅ 完了 | f9d0162, 88b1f42 |
| T1.1〜T1.7 + 小修正 | Implementer | ✅ 完了 | 17d55b3〜d100d48, 31c92d0, 65462b2 |
| T2.1〜T2.5 + 修正 | Implementer | ✅ 完了 | bd71065〜7a3dcd4, b22934e, 4924700, 705ad2c |
| T3.1〜T3.5 + 修正 | Implementer | ✅ 完了 | 973f65e〜d74a23d, d939afc |
| T4.1〜T4.4 + 修正 | Implementer | ✅ 完了 | 6f3616f〜59f6176, d5bfb9c, e316217 |
| T5.1〜T5.6 + 修正 | Implementer | ✅ 完了 | 12f38e7〜9a427d9, 71c9f8f |
| T6.1〜T6.5 | Implementer | DONE 受領・Director レビュー中 | 10ed532, d966afb, 13a1b86 |
| CI に koverVerify を追加・M1 の境界テスト | Debugger | ✅ 完了 | 795d5fe |
| M2 の QA・GameSession の境界テスト | Debugger | ✅ 完了 | 46cdc42 |
| FileSaveStorage の :app ユニットテスト（CI に testDebugUnitTest を追加） | Debugger | ✅ 完了 | 425d9d5 |
| M3 の QA | Debugger | ✅ 完了 | 81afd4f |
| M4 の R3 拡張テストの準備 | Debugger | ✅ 完了 | 3c8942f |
| M4 の QA | Debugger | ✅ 完了 | b09c35e |
| CI に :ai の koverVerify と billing 依存の検査を追加・M5 の QA | Debugger | ✅ 完了 | 2dc11e1, 13c046b, 0665c70 |
| M6 の QA | Debugger | 依頼済み | - |

## 直近の決定事項
- 技術構成: Kotlin / Compose。`:core` `:ai` は JVM、`:app` は CI でのみビルド（02 §0）
- **ゲーム名: Crownfall（ユーザー確定 2026-10-10。サブタイトル無し。パッケージ `com.ttmrase.crownfall`、アプリ表示名 `Crownfall`）**
- **AI 既定プロバイダ: Gemini（ユーザー確定 2026-10-10）**。AiRouter の既定順は Gemini → Scripted。他プロバイダは設定で追加
- `:app` の include 判定: `-PwithAndroid=true/false` を環境変数より優先する。プロパティが無いときは ANDROID_HOME か ANDROID_SDK_ROOT が実在するかで判定。CI の jvm ジョブは `false` を明示する
- JVM ターゲットは 17 に統一し、toolchain による JDK 自動取得は使わない
- KGP と AGP の読み込み: まずは各モジュールで plugin を適用する形にする。それで CI が通らない場合に限り、ルートで withAndroid のときだけ AGP を classpath に載せる構成を事前承認済み（採用したら 02 §0 を改訂する）
- 下部ナビは 城内/マップ/同盟/英雄/ショップ の 5 項目（01 §3 を改訂）
- テーマ: Material3、常にダーク。金 #C9A227 / 紺 #1F2F5C / 赤 #A4161A
- M0 に限り、Implementer が `.github/**` と README を作成してよい（06 §8）。M0 完了により `.github/**` は Debugger の担当に戻った
- KGP と AGP の読み込みは (a) 方式（各モジュールで適用）を採用。CI は setup-android を使わずランナー同梱の SDK を使う（02 §4）
- コアシミュレーションの設計規則 R1〜R9（02 §7）。M2 のセッションと UI の設計（02 §8: GameSession を :core に置く、保存はスロットリング、TooNew は上書きしない）。M3 の設計（02 §9: バフは加算で集約、キューは種類ごとに独立、維持費は正味レート、召喚の確率と天井）。M4 の設計（02 §10: マップは静的部分 + 動的オーバーレイ、行軍と襲撃も tick の境界、戦闘は整数演算）。M6 の設計（02 §12: オファーのキュー、頻度の制御、購入の原子性、台帳は参照しない）。M5 の設計（02 §11: チャットのきっかけまでを :core が決定論的に生成、分のグリッドのスケジューラ、Keystore に暗号化したキー、平文 HTTP はプライベート IP だけ）。`tools/**` を Implementer の書き込み領域に追加

## ユーザー確認待ち（Planner 経由）
- なし（ゲーム名・AI 既定プロバイダは確定済み）

## 既知の問題
- コンテナから Maven Central が断続的に 429 を返す。再実行で進む（S4・環境要因）
