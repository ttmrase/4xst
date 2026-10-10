# STATUS（Director が更新）

- 現在のマイルストーン: **M0 基盤**（発行済み・実装中）
- 統合ブランチ: `claude/4x-strategy-game-planning-oxo80w`
- 最終更新: 2026-10-10 Director

## 進行中チケット
| ID | 担当 | 状態 | SHA |
| --- | --- | --- | --- |
| T0.1 Gradle 骨格 | Implementer | 発行済み | - |
| T0.2 :core/:ai | Implementer | 発行済み | - |
| T0.3 :app Compose 骨格 | Implementer | 発行済み | - |
| T0.4 CI workflow | Implementer（M0 例外） | 発行済み | - |
| T0.5 README ビルド手順 | Implementer（M0 例外） | 発行済み | - |

推奨の実施順: T0.1 → T0.2 → T0.4 → T0.3 → T0.5

## 直近の決定事項
- 技術構成: Kotlin / Compose。`:core` `:ai` は JVM、`:app` は CI でのみビルド（02 §0）
- **ゲーム名: Crownfall（ユーザー確定 2026-10-10。サブタイトル無し。パッケージ `com.ttmrase.crownfall`、アプリ表示名 `Crownfall`）**
- **AI 既定プロバイダ: Gemini（ユーザー確定 2026-10-10）**。AiRouter の既定順は Gemini → Scripted。他プロバイダは設定で追加
- `:app` の include 判定: `-PwithAndroid=true/false` を環境変数より優先する。プロパティが無いときは ANDROID_HOME か ANDROID_SDK_ROOT が実在するかで判定。CI の jvm ジョブは `false` を明示する
- JVM ターゲットは 17 に統一し、toolchain による JDK 自動取得は使わない
- KGP と AGP の読み込み: まずは各モジュールで plugin を適用する形にする。それで CI が通らない場合に限り、ルートで withAndroid のときだけ AGP を classpath に載せる構成を事前承認済み（採用したら 02 §0 を改訂する）
- 下部ナビは 城内/マップ/同盟/英雄/ショップ の 5 項目（01 §3 を改訂）
- テーマ: Material3、常にダーク。金 #C9A227 / 紺 #1F2F5C / 赤 #A4161A
- M0 に限り、Implementer が `.github/**` と README を作成してよい（06 §8）

## ユーザー確認待ち（Planner 経由）
- なし（ゲーム名・AI 既定プロバイダは確定済み）

## 既知の問題
- なし
