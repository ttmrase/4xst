# STATUS（Director が更新）

- 現在のマイルストーン: **M2 城内 UI**（発行済み）。M1 は実装済み・Director Approve・QA-OK 待ち。M0 は ✅ 完了
- 統合ブランチ: `claude/4x-strategy-game-planning-oxo80w`
- 最終更新: 2026-10-10 Director

## 完了
| マイルストーン | 完了 | 根拠 |
| --- | --- | --- |
| M0 基盤 | 2026-10-10 | HEAD 00f5f9b、CI run 38028345848（jvm と apk が緑、crownfall-debug-apk あり）、Debugger QA-OK、Director Approve |

## 進行中チケット
| ID | 担当 | 状態 | SHA |
| --- | --- | --- | --- |
| M0 後追い（下部ナビの popUpTo、:ai の api(core)） | Implementer | ✅ 完了 | f9d0162, 88b1f42 |
| T1.1〜T1.7 | Implementer | Director Approve・QA-OK 待ち | 17d55b3〜d100d48 |
| M1 小修正（addResources の負数、newGame.items の検証） | Implementer | 依頼済み | - |
| T2.1〜T2.5 | Implementer | 発行済み（T2.1 は着手済み） | - |
| CI に koverVerify を追加・M1/M2 の境界テスト | Debugger | 依頼済み | - |

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
- コアシミュレーションの設計規則 R1〜R9（02 §7）。M2 のセッションと UI の設計（02 §8: GameSession を :core に置く、保存はスロットリング、TooNew は上書きしない）。`tools/**` を Implementer の書き込み領域に追加

## ユーザー確認待ち（Planner 経由）
- なし（ゲーム名・AI 既定プロバイダは確定済み）

## 既知の問題
- コンテナから Maven Central が断続的に 429 を返す。再実行で進む（S4・環境要因）
