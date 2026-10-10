# 02 アーキテクチャ

## 0. 開発環境の制約（最重要・全員読む）
開発セッションはクラウドコンテナで動く。実測した制約:
- Android SDK **無し**。`dl.google.com`（SDK 配布・Google Maven の実体）**到達不可**。`maven.google.com` も `dl.google.com` へ 301 するため **AGP（Android Gradle Plugin）はコンテナで解決できない**。
- Maven Central と Gradle Plugin Portal は到達可。JDK 21、Gradle 8.14.3 はプリインストール。
- `generativelanguage.googleapis.com`（Gemini）は到達可、`api.groq.com` は不可（コンテナからの疎通。端末は無関係）。

結論:
1. ゲームロジックと AI 層は **純 Kotlin/JVM モジュール**（`:core`, `:ai`）に置き、コンテナで `./gradlew :core:test :ai:test` を回す。
2. Android モジュール `:app` は **GitHub Actions でのみビルド**し、APK をアーティファクトとして出力する。
3. `settings.gradle.kts` は `:app` を **条件付き include**（`ANDROID_HOME`/`ANDROID_SDK_ROOT` が設定済み、または `-PwithAndroid=true` のとき）。ルート `build.gradle.kts` には AGP を一切書かない（`apply false` でも解決が走るため NG）。AGP は `app/build.gradle.kts` 内の `alias(libs.plugins.android.application)` だけで参照する。
4. Compose UI のコンパイル検証はコンテナでできないため、Implementer は **:app の変更後、必ず CI 結果（GitHub MCP の `actions_list` / `get_job_logs`）を確認**する。

## 1. 技術スタック
| 領域 | 選定 | 備考 |
| --- | --- | --- |
| 言語 | Kotlin 2.1.x | K2 |
| ビルド | Gradle 8.14.3（wrapper 必須）, AGP 8.9.x, JDK 17（CI）/ 21（コンテナ） | Version Catalog（`gradle/libs.versions.toml`） |
| Android | minSdk 26 / targetSdk 35 / compileSdk 35 | |
| UI | Jetpack Compose（BOM）, Material3, Navigation Compose, Lifecycle ViewModel | 単一 Activity |
| 直列化 | kotlinx.serialization (JSON) | セーブ・データテーブル・AI 応答 |
| 非同期 | kotlinx.coroutines + Flow | |
| 設定保存 | DataStore Preferences | API キーは Android Keystore で暗号化（`EncryptedSharedPreferences` 相当。Jetpack Security が deprecated なら Tink or 自前 Keystore ラップで可） |
| HTTP | OkHttp 4.x（`:ai`） | MockWebServer でテスト |
| 通知 | WorkManager + NotificationManager | |
| テスト | JUnit 5 (Jupiter), kotlin-test, MockWebServer | `:core` のシミュレーション層は行カバレッジ 80% 目標（kover） |
| 静的解析 | ktlint（gradle plugin） | CI で `ktlintCheck` |

## 2. モジュール構成
```
settings.gradle.kts          # :core, :ai を常時 include。:app は条件付き
build.gradle.kts             # 共通設定のみ（AGP 参照禁止）
gradle/libs.versions.toml
gradle/wrapper/
core/                        # kotlin("jvm")  ゲームロジック。Android 依存ゼロ
ai/                          # kotlin("jvm")  AiProvider と実装。:core に依存
app/                         # com.android.application  Compose UI。:core :ai に依存
docs/
.github/workflows/android.yml
```
依存方向: `app → ai → core`。`core` は何にも依存しない（kotlinx.* のみ）。

### 2.1 `:core` パッケージ（`com.ttmrase.crownfall.core`）
| パッケージ | 役割 |
| --- | --- |
| `model` | `GameState`, `Resources`, `Building`, `BuildingSlot`, `TimerJob`, `TroopStack`, `Hero`, `MapTile`, `March`, `Alliance`, `Persona`, `ChatMessage`, `Quest`, `Offer`, `Mail`, `Vip` … すべて `@Serializable` な不変データ |
| `data` | JSON データテーブルのローダ（`src/main/resources/data/*.json`）と型付きアクセサ。`buildings.json`, `research.json`, `troops.json`, `heroes.json`, `quests.json`, `events.json`, `offers.json`, `personas.json`, `templates.json`, `map_rules.json` |
| `sim` | `Simulation.tick(state, dtSeconds): GameState`（純関数）。生産・倉庫上限・タイマー・キュー・行軍・NPC スケジューラを順に適用。`OfflineCatchUp` は長い dt を分割して tick し、サマリーを返す |
| `economy` | 生産計算、コスト判定、加速・ジェムスキップ換算 |
| `combat` | `CombatResolver`（決定論）、`BattleReport` |
| `map` | `MapGenerator(seed)`, 距離・行軍時間、タイル検索 |
| `quest` | 進捗判定、報酬付与 |
| `alliance` | `AllianceScheduler`（AI メンバーのヘルプ・ラリー・購入・発言イベントを時間帯確率で発火）、好感度 |
| `shop` | `OfferEngine`（トリガー評価、クールダウン、キュー）、`FakeLedger`、`VipCalculator`、`PiggyBank`、`LuckyWheel` |
| `chat` | `ChatEvent`、`PromptContextBuilder`（ゲーム状態 → LLM 用要約。文字列のみ） |
| `save` | `SaveGame`（`version`, `savedAtEpochSec`, `seed`, `state`）、`Migrations` |
| `util` | `Clock` インターフェース、`SeededRandom`、数値短縮フォーマッタ |

設計規則:
- 全ロジックは **決定論的**。乱数は `SeededRandom` 注入、時刻は `Clock` 注入。グローバル状態禁止。
- `Simulation.tick` は純関数（入力 `GameState` を変更せず新しい `GameState` を返す）。副作用（通知・UI イベント）は `List<GameEvent>` として返す。
- マジックナンバー禁止。数値は全て `data/*.json`。
- 文字列（UI 表示）は `:core` に置かない。`:core` は ID と数値を返し、表示は `:app` の `strings.xml`。例外: ペルソナのチャットテンプレ（`templates.json`）はコンテンツなので `:core` のデータとして持つ。

### 2.2 `:ai` パッケージ（`com.ttmrase.crownfall.ai`）
```kotlin
interface AiProvider {
    val id: String
    suspend fun complete(request: ChatRequest): AiResult   // Success(ChatResponse) | Failure(reason, retryable)
}
data class ChatRequest(val system: String, val messages: List<ChatTurn>, val maxTokens: Int, val temperature: Double, val jsonSchemaHint: String?)
data class ChatResponse(val lines: List<SpokenLine>)      // speakerId + text
```
| 実装 | 用途 |
| --- | --- |
| `GeminiProvider` | Gemini API（Generative Language API）。モデル名は設定値（既定候補は 03 参照） |
| `OpenAiCompatProvider` | OpenAI 互換 `/v1/chat/completions`。Groq / OpenRouter / Ollama / LM Studio を base URL 切替で |
| `ScriptedProvider` | 常時利用可。`templates.json` ベース。LLM 無しでも全機能が動く |
| `OnDeviceProvider` | インターフェースのみ `:ai` に。実装は `:app`（MediaPipe LLM Inference, Gemma 3 1B）。Phase 2 |
| `AiRouter` | 設定順にプロバイダを試行。429/5xx/タイムアウト（8 秒）で次へ。予算（毎時・日次）超過時は Scripted。応答 JSON パース失敗時はテキストを 1 行として扱う |

### 2.3 `:app`（`com.ttmrase.crownfall`）
- `MainActivity` + `NavHost`。画面毎 `ViewModel`。
- `GameRepository`: `StateFlow<GameState>` を保持。フォアグラウンドで 1 秒 tick。変更は `reduce { Simulation.apply(...) }` で一元化。保存は 5 秒 debounce + `onStop`。
- `SaveStore`: `filesDir/save/slot0.json` を tmp → rename でアトミック書込、`slot0.bak` を 1 世代保持。破損時は bak へフォールバック。
- `ChatCoordinator`: `GameEvent` → `ChatEvent` → `AiRouter` → `ChatMessage` 追加。タイピング表示用の遅延を挿入。
- `OfferPresenter`: `OfferEngine` の出力をダイアログ／ボトムシートとして表示。連鎖キュー管理。
- `FakeBillingSheet`: 擬似決済ボトムシート（04 参照）。**実決済 SDK 依存ゼロ**。`:app` の依存グラフに `billing` を含む artifact が無いことを CI でチェック（`./gradlew :app:dependencies | grep -i billing` が空）。
- `NotificationScheduler`: WorkManager で次回タイマー完了・オファー期限の通知を予約。
- マップ描画: `Canvas` + `Modifier.transformable`。可視範囲のみ描画。タイルは図形＋ベクターアイコン。
- アセット: 自作ベクター（`res/drawable/*.xml`）。画像素材は使わない（著作権・容量）。

## 3. セーブ形式
```json
{
  "version": 1,
  "savedAtEpochSec": 1760000000,
  "seed": 123456789,
  "state": { "...GameState..." }
}
```
- `Migrations.migrate(json, fromVersion)` のチェーンで前方互換。版を上げる変更は Director の承認を要する。
- デバッグ用に `adb pull` でそのまま読める JSON を維持。

## 4. CI（`.github/workflows/android.yml`）
```yaml
on: [push, pull_request]
jobs:
  jvm:
    runs-on: ubuntu-latest
    steps: checkout → setup-java 17 (temurin) → gradle/actions/setup-gradle → ./gradlew :core:test :ai:test ktlintCheck koverXmlReport
  apk:
    needs: jvm
    runs-on: ubuntu-latest
    steps: checkout → setup-java 17 → ランナー同梱 SDK（$ANDROID_HOME）のライセンス承認 → setup-gradle → ./gradlew :app:assembleDebug :app:lintDebug :app:ktlintCheck -PwithAndroid=true
           → actions/upload-artifact (app/build/outputs/apk/debug/app-debug.apk, retention 14 days)
```
- `concurrency` でブランチ毎に古い実行をキャンセル。
- jvm ジョブは `-PwithAndroid=false` を明示する（ubuntu ランナーには ANDROID_HOME があるため。AGP を解決させない）。
- `android-actions/setup-android@v3` は `sdkmanager tools` で失敗したため使わない（M0 実測）。ubuntu-latest に同梱の SDK を使う。
- ジョブレベルの `if:` で `hashFiles()` は使えない（workflow のパースに失敗する）。
- KGP と AGP の読み込みは、各モジュールで alias を使って適用する方式（ルートでは何も読み込まない）。CI に出る「Kotlin plugin loaded multiple times」の警告は許容する（M0 で決定）。
- コンテナから Maven Central（repo.maven.apache.org）が断続的に 429 を返す。キャッシュがあるので、再実行で進む。
- 署名: 個人利用のため debug keystore で可。M8 で release 署名（リポジトリ内の専用 keystore + パスワードは Secrets）を検討。

## 5. コーディング規約
- Kotlin 公式スタイル。ktlint 準拠。
- 公開クラス・関数に KDoc（日本語可）。
- `:core` に `android.*` / `java.io.File` 依存を持ち込まない（ファイル I/O は `:app`）。
- UI 文字列は `strings.xml`（`values/` = 日本語既定、`values-en/`）。
- 1 コミット 1 目的。コミットメッセージ形式は 06 参照。

## 6. パフォーマンス目標
- tick 1 回 < 2ms（JVM ベンチ）。
- OfflineCatchUp 24h 分 < 300ms。
- マップ描画 60fps（可視タイル 400 枚程度）。
- セーブ JSON < 2MB。

## 7. コアシミュレーション設計規則（M1 で Director が決定）
- **R1 時間**: `GameState.nowEpochSec` がシミュレーション時刻。`Simulation.tick(state, dtSec, data)` はこれを dtSec 進める純関数で、Clock は見ない。Clock を参照するのは `OfflineCatchUp` の呼び出し側（`:app`）だけ。
- **R2 タイマー**: `TimerJob.endEpochSec` は絶対時刻。`now >= end` で完了する。加速は end を減らすだけ。
- **R3 分割不変性**: `tick(tick(s,a),b) == tick(s,a+b)`（state と、events の連結の両方）。tick の内部では、タイマー完了・Boost 期限・2 本目ビルダーの期限を境界にセグメントへ区切って処理する。
- **R4 整数演算のみ**: 生産は「毎時レート × 秒」を 3600 で割り、余りを `productionRemainder` に繰り越す。バフは basis point（`rate*(10000+bp)/10000`）。
- **R5 オーバーフロー**: Resources の加算・乗算は `Long.MAX_VALUE` で飽和させ、負数は作らない。
- **R6 乱数**: 自前の SplitMix64 を使い、状態は `GameState.rngState` に保存する。`kotlin.random` は使わない。
- **R7 アクション**: プレイヤー操作は純関数 `Actions.*` で、戻り値は `ActionResult = Success(state, events) | Failure(reason)`。例外は投げない。
- **R8 セーブ互換**: デフォルト値付きのフィールド追加は版を上げなくてよい。削除・改名・意味の変更は version を上げて Migration を書き、Director の承認を取る。
- **R9**: `:core` は ID と数値だけを返す（表示用の文字列は `:app`）。
- 資源の上限（cap）= Castle の `storage_cap_base` + Warehouse の `storage_cap`。cap で止まるのは生産だけで、報酬や購入は cap を超えてよい。アップグレード中の建物も旧レベルで生産を続ける。
- 2 本目ビルダーの期限が切れても、進行中のジョブは最後まで進む（新規の開始だけができなくなる）。
- オフライン上限を超えた時間は、生産もタイマーも進めない。`nowEpochSec` だけを現在時刻にそろえる。
- データ生成: 大きなテーブル（`buildings.json` など）は `tools/datagen/*.py`（標準ライブラリのみ）で生成し、JSON をコミットする。ゲームが読むのは JSON だけ。`tools/**` は Implementer の書き込み領域。
