# STATUS（Director が更新）

- 現在のマイルストーン: **M10 見た目と演出の大幅強化**（T10.1 から）。M0〜M9 と 1.0.1 ホットフィックスは ✅ 完了
- 統合ブランチ: `claude/4x-strategy-game-planning-oxo80w`
- 最終更新: 2026-10-10 Director（M10 の画像レビュー 2 回目）

## 完了
| マイルストーン | 完了 | 根拠 |
| --- | --- | --- |
| 1.0.1 ホットフィックス（S1 × 2: ポップアップから戻れない、城内へ戻れない） | 2026-10-10 | e85e283 / d2b41e4 + レビュー修正 ac75fb4（退出待ちでも戻るが効く）、HEAD 4986231 で CI run 111（38061992582）が緑（jvm / apk。APK は debug 36.5MB / release 32.5MB で M9 と同等）、Debugger QA-OK（QaPopupTest 4、NavActionsTest 9、BackPressTest 4。Robolectric を導入）、Director Approve |
| M0 基盤 | 2026-10-10 | HEAD 00f5f9b、CI run 38028345848（jvm と apk が緑、crownfall-debug-apk あり）、Debugger QA-OK、Director Approve |
| M9 拡張（オンデバイス LLM・うるさい通知） | 2026-10-10 | 7d5ed68 + 修正 ec6bcc3、CI run 93 が緑（LiteRT-LM 0.18.0、Kotlin 2.4.0、APK は debug 36.5MB / release 32.5MB）、Debugger QA-OK（先読みと実際の catch-up の一致、通知の選別、襲撃の段階の R3、48h 先読み 28ms）、Director Approve |
| M8 QA・リリース | 2026-10-10 | f3261e6、CI run 78 が緑（debug と release（debugsigned）の APK、証明書の照合、billing の検査）、Debugger QA-OK（スモークチェックリスト、M1〜M7 のセーブ互換、ベンチ）、docs の同期（T8.3）、Director Approve |
| M7 クエスト・イベント・通知・仕上げ | 2026-10-10 | 495aa3e + 修正 39e268f / 99f573c / ad01a44、Debugger QA-OK（週末・日付をまたぐ catch-up の一致、逆転パック後の 1 位）、Director Approve。後追い 2 件は依頼済み |
| M6 ショップ・擬似課金 | 2026-10-10 | 13a1b86 + 修正 60f5b54、CI run 38043634212 が緑（billing の検査を含む）、Debugger QA-OK（購入の原子性、頻度の性質テスト、ブランド名の検査 check-no-payment-brands.sh を CI に追加、2aa10b1）、Director Approve |
| M5 同盟・AI チャット | 2026-10-10 | 9a427d9 + 修正 71c9f8f（Gemini の既定を GA の gemini-3.1-flash-lite に変更、例外安全性など）、Debugger QA-OK（分のグリッドの R3、24h 分 11〜13ms、リダイレクトと UrlPolicy の修正 2dc11e1）、Director Approve |
| M4 ワールドマップ・戦闘 | 2026-10-10 | 59f6176 + 修正 d5bfb9c / e316217、CI run 38036914082 が緑、Debugger QA-OK（QaWorldTest 10 本。兵の保存則、略奪、R3 を 6 シナリオ × 40 分割、b09c35e）、Director Approve |
| M3 軍事・研究・英雄 | 2026-10-10 | d74a23d + 修正 d939afc、CI run 38033161525 が緑、kover の対象 7 パッケージが 98〜100%、Debugger QA-OK（QaMilitaryTest、OfflineSummary の負数を 81afd4f で修正）、Director Approve |
| M2 城内 UI | 2026-10-10 | 7a3dcd4 + 修正 b22934e / 4924700 / 705ad2c、CI run 38030769719 が緑、Debugger QA-OK（QaSessionTest、GameSession の S3 不具合 2 件を 46cdc42 で修正）、Director Approve |
| M1 コアシミュレーション | 2026-10-10 | d100d48 + 小修正 31c92d0 / 65462b2、CI run 38029693916 が緑、kover の sim / economy が 100%、Debugger QA-OK（境界テスト 795d5fe、CI に koverVerify を追加）、Director Approve |

## 進行中チケット
| ID | 担当 | 状態 | SHA |
| --- | --- | --- | --- |
| README の細部修正（台帳の項目、頻度の表記、LAN の許可範囲）、Gemini モデルのヒント文言 | Implementer | ✅ 完了 | 403b52a |
| 実機でのスモークチェック | ユーザー | ✅ 実施済み（S1 × 2 と見た目への不満） | - |
| M10 T10.1〜T10.7（見た目と演出） | Implementer | 画像レビュー 2 回目（runs/25）: R1・V2・V5・V6 は OK。残りは H1（HUD の崩れ。8c03b15 で修正済み、画像で確認待ち）、C1（初期カメラの構図と壁の内側の空き地）、C2（パンの境界）。ui は 11 本中 6 本が緑（残りはテスト側の起動待ちを Debugger が修正中）。T10.8 は ui が全部緑になってから | d31c18a〜8c03b15 |
| M10 の QA: ui ワークフロー（ui.yml、継続ジョブ）、スクリーンショットの qa-screenshots ブランチ、回帰テスト 3 本、SMOKE 第 12 章 | Debugger | 進行中 | d31c18a, 540fa8f |
| 1.0.1 ホットフィックスと回帰テスト | Implementer / Debugger | ✅ 完了 | e85e283〜4986231 |
| T9.1・T9.2a・T9.2b + 修正 | Implementer | ✅ 完了 | 6857b81〜7d5ed68, ec6bcc3 |
| M9 の QA・SMOKE_CHECKLIST 第 11 章・CI での APK サイズの出力 | Debugger | ✅ 完了（:core の QA-OK。実機の項目は未実施） | ec79280, c96fb0e ほか |

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
- コアシミュレーションの設計規則 R1〜R9（02 §7）。M2 のセッションと UI の設計（02 §8: GameSession を :core に置く、保存はスロットリング、TooNew は上書きしない）。M3 の設計（02 §9: バフは加算で集約、キューは種類ごとに独立、維持費は正味レート、召喚の確率と天井）。M4 の設計（02 §10: マップは静的部分 + 動的オーバーレイ、行軍と襲撃も tick の境界、戦闘は整数演算）。M7 の設計（02 §13: クエストはイベント駆動、NPC ランキングの追従とボーナスの除外、通知は予測できるものだけ）。M6 の設計（02 §12: オファーのキュー、頻度の制御、購入の原子性、台帳は参照しない）。M5 の設計（02 §11: チャットのきっかけまでを :core が決定論的に生成、分のグリッドのスケジューラ、Keystore に暗号化したキー、平文 HTTP はプライベート IP だけ）。`tools/**` を Implementer の書き込み領域に追加

## ユーザー確認待ち（Planner 経由）
- 1.0.1 の APK での再スモーク（戻るの原則、SMOKE_CHECKLIST の共通章）

## ユーザー決定（2026-10-10）
- M9 に進む（オンデバイス LLM + 量産型にうるさい通知・警報）
- release の本格署名は不要（debug 鍵署名で確定）
- オファー頻度の既定は量産型のまま

## 既知の問題
- 実機でのみ確認できる戻るの項目（予測型の戻るジェスチャー、通知タップからの復帰）は、M10 T10.1 のエミュレータ CI で一部を自動化する
- 未来の AI のメンション・DM の通知は、M9 の T9.2 の先読み計画で対応中
- コンテナから Maven Central が断続的に 429 を返す。再実行で進む（S4・環境要因）
