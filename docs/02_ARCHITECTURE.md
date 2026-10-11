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
- `GameRepository`: `:core` の `GameSession`（§8）をライフサイクルにつなぐ薄い層。状態の変更は `GameSession.dispatch { Actions.* }` で一元化し、保存はスロットリング（§8）+ `onStop`。
- `SaveStore`: `filesDir/save/slot0.json` を tmp → rename でアトミック書込、`slot0.bak` を 1 世代保持。破損時は bak へフォールバック。
- `ChatCoordinator`: `GameEvent` → `ChatEvent` → `AiRouter` → `ChatMessage` 追加。タイピング表示用の遅延を挿入。
- `OfferPresenter`: `OfferEngine` の出力をダイアログ／ボトムシートとして表示。連鎖キュー管理。
- `FakeBillingSheet`: 擬似決済ボトムシート（04 参照）。**実決済 SDK 依存ゼロ**。`:app` の依存グラフに `billing` を含む artifact が無いことを CI でチェック（`./gradlew :app:dependencies | grep -i billing` が空）。
- `notify/NoticeScheduler`（+ `NotificationPermission` / `NotificationPrefs`）: `NotificationPlanner.plan` の結果を WorkManager で予約する（§13）。
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
- 署名と release ビルドは §14 で決定済み。

## 5. コーディング規約
- Kotlin 公式スタイル。ktlint 準拠。
- 公開クラス・関数に KDoc（日本語可）。
- `:core` に `android.*` / `java.io.File` 依存を持ち込まない（ファイル I/O は `:app`）。
- UI 文字列は `strings.xml`（`values/` = 日本語既定、`values-en/`）。
- 1 コミット 1 目的。コミットメッセージ形式は 06 参照。

## 6. パフォーマンス目標
- tick 1 回 < 2ms（JVM ベンチ）。M8 の実測は約 8µs。
- OfflineCatchUp 24h 分 < 300ms。M8 の実測は約 24ms（マップ・行軍・同盟・イベントを含む）。
- マップ描画 60fps（可視タイル 400 枚程度）。
- セーブ JSON < 2MB。M8 の実測は約 19KB（encode 1.5ms / decode 2.7ms）。

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
- オフライン上限を超えた時間は、生産もタイマーも進めない。`nowEpochSec` を現在時刻にそろえ、タイマー・Boost・2 本目ビルダーの期限は超過した分だけ後ろへずらす（残り時間を保つ。M1 で確定）。
- GemSkip は `speedup.json` の区分テーブル（`gemsPerHour`、区分ごとに切り上げ）で計算する。性質として保証するのは、単調非減少・区分単価の非増加・劣加法性（区分ごとの切り上げがあるため、秒あたりの単価は厳密には単調にならない）。
- StorageFull イベントは、cap に達した正確な時刻の順（同時刻は ResourceType の順）に出す（R3 を events まで成り立たせるため）。
- kover: `:core:koverVerify` は、対象パッケージ（M1 時点では sim と economy）ごとに行カバレッジ 80% を要求する。M3 で combat、M6 で shop を対象に加える。
- データ生成: 大きなテーブル（`buildings.json` など）は `tools/datagen/*.py`（標準ライブラリのみ）で生成し、JSON をコミットする。ゲームが読むのは JSON だけ。`tools/**` は Implementer の書き込み領域。

## 8. セッションと UI の設計（M2 で Director が決定）
- 状態ホルダ `core.session.GameSession` は `:core` に置く（coroutines だけを使う。コンテナでテストできるようにするため）。`:app` の `GameRepository` は、ライフサイクルとファイル I/O をつなぐ薄い層にとどめる。
- 保存は `SaveStorage` インターフェース経由で行う（`:app` の実装は `FileSaveStorage`: tmp に書いて fsync → 既存を bak へ rename → tmp を本体へ rename。壊れたファイルは `slot0.corrupt-<epoch>.json` へ退避）。TooNew のセーブは決して上書きしない。
- 保存のタイミング: dirty かつ前回の保存から 5 秒以上たったら保存する（スロットリング）＋ onStop で flush。
- 現在時刻への追従（`advanceToNow`）は、常に `OfflineCatchUp.run` を使う。時計が巻き戻ったときは何もしない。アクションの適用前には必ず現在時刻まで進める。
- DI は手動（`CrownfallApp` / AppContainer）。
- GameState への追加フィールド（デフォルト値付きなので版は上げない）: `playerName`、`starterHeroId`、`tutorialStep`。
- オファー誘導のフックは `OfferHooks`（M2 では何もしない実装。中身は M6）。
- 城内のレイアウトは、セクション見出しで区切った 3 列の固定グリッド。数値の短縮表示は `core.util.ShortNumber`（K/M/B/T、小数 1 桁、切り捨て）。
- M2 で確定した実装上の判断:
  - 保存のスロットリングは Clock の秒で判定する（前景では約 5 秒ごとに保存される）。
  - 不在サマリーはロード時だけでなく、前景に戻ったとき（ON_START）にも経過が 300 秒以上なら出す。
  - TooNew を検出したら、`newGame` は呼べない（例外を投げる。UI 側でもボタンを出さない）。
  - オファー誘導のフックは、構図のたびではなく、ユーザーが操作したときに 1 回だけ呼ぶ（M2 REVIEW）。
  - 保存に失敗しても（容量不足など）セッションは落とさない。dirty のまま残して次の間隔で再試行する。時計が巻き戻ったときは即保存する（M2 QA 46cdc42）。

## 9. 軍事・研究・英雄の設計（M3 で Director が決定）
- **バフは加算で集約する**: `core.buff.BuffAggregator` が、すべての出所（研究・Boost・将来の VIP や同盟テック）の bp を BuffKey ごとに加算し、対象の値に 1 回だけ掛ける。全体用（例 PRODUCTION_ALL）と個別用（例 PRODUCTION_FOOD）も加算する。乗算の連鎖はしない。速度系は `base*10000/(10000+Σbp)`（切り上げ、最小 1 秒）。
- 英雄のスキルは BuffAggregator に入れない（行軍・戦闘の文脈でだけ効く。M4）。
- キュー: 訓練は訓練建物ごとに 1 本、研究は 1 本、治療は 1 本。いずれも建設のキューとは独立。
- Food の維持費は「Food の正味レート = 生産 − 維持費」として tick に組み込む。正味が負なら 0 で止まり、`FoodDepleted` を到達時刻に 1 回だけ出す。脱走は無し。
- 召喚: PREMIUM は SSR 2% / SR 13% / R 35% / N 50%、天井 100（SSR が出たら 0 に戻す）、10 連は SR 以上を 1 枠保証。NORMAL は SSR なし。乱数は rngState を使い、使用後に書き戻す。
- 初期英雄は NewGame で付与する。M2 のセーブは、load の後に `HeroBootstrap.ensureStarter`（冪等）で補完する。
- GameState と TimerJob への追加は、すべてデフォルト値付き（版は上げない）。
- kover の対象パッケージ: sim / economy / military / research / hero / buff / session（各 80%）。
- M3 で確定した実装上の判断:
  - 研究の前提不足は専用の `ResearchPrereqNotMet` を使う。
  - 治療中の兵は wounded から外す。
  - `ItemDef.kind` を追加した。
  - `GameData` は data class にした。
  - :core と :ai は `-Xjdk-release=17` でコンパイルする（CI の JDK 17 で使えない API をローカルで弾くため）。
  - :app の JVM ユニットテスト（`:app:testDebugUnitTest`）を CI の apk ジョブで実行する。

## 10. マップ・行軍・戦闘の設計（M4 で Director が決定）
- マップは「静的部分 + 動的オーバーレイ」に分ける。静的部分（地形と固定の拠点）は `MapGenerator.generate(seed)` で再生成し、保存しない。動的部分（ノードの残量、野蛮人）は `GameState.map` に保存する。再出現は、一定周期の一括補充を tick の境界として行う。
- 行軍のフェーズ終了（到着・採集完了・集結完了・帰還）と NPC 襲撃（予告と本番）も tick の境界にし、R3 を維持する。
- 戦闘は整数演算のみ（bp と Long）。最大 10 ラウンドで、両軍が同時にダメージを与える。三すくみは有利 ×1.2 / 不利 ×0.8。Siege は対兵 ×0.6・対壁 ×1.5。損害は 70% 負傷 / 30% 死亡。病院の容量を超えた分は死亡。
- 英雄の効果は行軍単位で効かせる（`BuffAggregator.forMarch`）。アクティブスキルは「第 N ラウンドに追加ダメージ」。
- メールは `GameState.mails`（上限 200 件）。種類は BATTLE / SCOUT / SYSTEM（RECEIPT / ALLIANCE は予約）。
- ラリーは M4 ではプレイヤーが主催し、援軍は仮のもの。AI が主催するラリーへの参加は M5 で追加する。
- kover の対象に map / march / combat を追加する。
- M4 で確定した実装上の判断:
  - 補充は「不足があり、予定時刻を過ぎている」ときだけ境界にする（満杯なら境界にしない）。
  - 行軍中の兵は troops から外す（維持費と戦力に含めない）。
  - 襲撃のレポートは attacker = NPC として保存する。
  - 行軍枠と兵数の上限は map_rules.json の march に置く。
  - 英雄 2 人のアクティブスキル（ラウンド限定の効果）は乗算で重なる（バフの加算規則とは別枠）。
  - 行軍の戦利品と採集分は報酬として扱い、cap を適用しない（M4 REVIEW）。
  - ラリーは敗北時も参加報酬を出す。
  - MapGenerator のキャッシュはスレッド安全にする。

## 11. 同盟・AI チャットの設計（M5 で Director が決定）
- **決定論の境界**: `:core` はチャットのきっかけ（ChatEvent）までを決定論的に生成し、`alliance.pendingChat`（上限 50、あふれたら Ambient から捨てる）に積む。文面（LLM または Scripted）は `:app` の ChatCoordinator が非同期に作り、`Actions.appendChat` で書き戻す。tick は LLM を呼ばない。
- **AllianceScheduler** は毎分 0 秒のグリッドで評価する（tick の境界）。乱数は状態を持たないハッシュ（`mix(allianceSeed xor minuteIndex)`）で引くので、R3 は自明に成り立つ。
- 活動時間帯は `settings.utcOffsetSec`（`:app` が端末の TZ から設定する）で現地時刻にして判定する。
- 同盟テックは BuffAggregator の出所として加算する。personas.json の並び順を、マップの persona_slot に対応させる。
- **AI プロバイダ**: 既定の順は Gemini → Scripted（ユーザー確定）。タイムアウト 8 秒。429・5xx・ネットワーク失敗なら次へ。予算（毎時 20 / 日 200）を超えたら Scripted。壊れた JSON は 1 行の発話として扱う。
- **セキュリティ**: API キーは Android Keystore の AES/GCM で暗号化し、DataStore に保存する。ログやプロンプトに出さない。平文 HTTP はプライベート IP・localhost・*.local だけを AiRouter が許可する（network_security_config では IP の範囲を指定できないため、アプリ側の検査で守る）。
- kover の対象: `:core` の alliance と chat。`:ai` の router と providers。
- M5 で確定した実装上の判断:
  - Gemini の既定モデルは `gemini-3.1-flash-lite`（GA。2026-10 時点で無料枠の対象。preview 版は 2026-05-25 に提供終了。出典: ai.google.dev の changelog と pricing）。設定画面で変更できる。
  - OkHttp はリダイレクトに追従しない（UrlPolicy をすり抜けないため）。
  - 応答 JSON の扱い: プレーンテキストの応答は 1 行の発話にする。`{` で始まる壊れた JSON は "text" の値を抜き出し、できなければ失敗扱い（次のプロバイダへ）にする。
  - Keystore と ChatCoordinator の例外でアプリを落とさない（CancellationException 以外は捕まえる）。
  - DM は活動時間外でも返信する。ワールドは読み取り専用。プロンプトにはゲーム内のプレイヤー名だけを含める。

## 12. ショップ・擬似課金の設計（M6 で Director が決定）
- 絶対条件は 04 §1 のとおり（実決済 SDK と billing 依存は無し、ブランドを模倣しない、通信しない）。CI で billing 依存を検査する。
- `core.shop`（OfferEngine / FakeLedger / VipCalculator / PiggyBank / LuckyWheel / DailyDeals / MonthPass / GrowthFund）は純関数。状態は `GameState.shop`。
- オファーは `shop.offerQueue` に積み、`:app` の OfferPresenter が先頭から表示する。
  - 頻度（LOW 2 / STANDARD 5 / MASS 無制限、1 セッションあたり）、クールダウン、連鎖は最大 3、復活は MASS のときだけ。いずれも `:core` で判定する。
  - セッションは前景になったときの `beginSession` で始まる。
- `Actions.fakePurchase` は、内容の付与・初回 2 倍・VIP pt・台帳・レシートメール・連鎖をまとめて原子的に行う。決済シートの演出（処理時間、2% のエラー、紙吹雪）は UI だけで行い、「完了」を表示する瞬間に dispatch する。
- FakeLedger はゲームのロジックから参照しない（上限 5000 件、あふれたら集計値に畳む）。VIP は `shop.vipPoints` で管理する。
- VIP の特典は既存の仕組みに接続する: 採集バフは BuffAggregator、オフライン上限は capSec、ジェムスキップは割引 bp、VIP6 の 2 本目ビルダーは buildQueueCapacity。
- 日付の区切りは `settings.utcOffsetSec` の現地日付。デイリーディールは (seed, localDay) のハッシュで決める。
- kover の対象に shop を追加する。
- M6 で確定した実装上の判断:
  - 閉じたオファーは期限まで「おすすめ」の棚に残す（dismissed）。
  - 初回 2 倍は全ジェム SKU の基本ジェム分に適用する。
  - 注文番号は (seed, 購入回数) から決定的に作る ORD-XXXX-XXXX。
  - 決済演出の設定は「処理時間の長短」とエラー演出の ON/OFF。
  - オファーの定期的な再評価（refreshOffers）は前景でだけ行う（M6 REVIEW）。

## 13. クエスト・イベント・通知の設計（M7 で Director が決定）
- クエストのカウンタは GameEvent からだけ加算する（`QuestTracker.track`）。状態から判定できる条件はカウンタを持たない。tick の分は tick のセグメントの内側で、アクションの分は GameSession で track する（M7 QA で改訂）。
- デイリーと週の区切りは現地時刻（`utcOffsetSec`）で、週は月曜 0 時に始まる。イベントの開始・終了・集計・終了 24h 前の時刻は tick の境界にする。
- NPC ランキング: 上位の NPC は、プレイヤーの自力スコアに対して +3〜12% 先行するよう決定的に追従する（保存しない）。逆転パックのポイントはボーナスとして追従計算から除外するので、パックを買えば 1 位を取れる（「買っても勝てない」設計にはしない）。
- 通知は、onStop の時点で確定している未来の時刻だけを WorkManager で予約する（`NotificationPlanner.plan`。純関数）。背景でシミュレーションは回さない。**既知の制約**: 未来の AI のメンション・DM は予測できないので通知しない。
- オンデバイス LLM（T7.5）は M9 へ繰り越す。
- kover の対象に quest / event / notify を追加する。
- M7 で確定した実装上の判断:
  - 逆転パックを購入した後は、リーダーの追従を購入時点の自力スコアで固定する（`followCapSelf`。M7 REVIEW。購入後の自力の伸びはそのままリードになる）。
  - 逆転パックのボーナスは、週末時点（frac=1000）の NPC の最高スコアとの差 + margin で計算する（購入後に自力が伸びなくても 1 位を保証する。M7 QA）。
  - ~~週をまたいだオフライン分のイベントは新しい週に計上する~~ → 撤回（M7 QA の S3）。Tracking は Simulation.tick のセグメントの内側で行い、週・日の境界の集計はその時点までのスコアで確定する。GameSession はアクションのイベントにだけ Tracking を適用する。
  - 戦力成長祭の自力スコアは、週の開始時からの戦力の増加分。

## 14. リリース（M8 で Director が決定）
- 署名の安定化: 固定の debug keystore（`app/debug.keystore`。秘密ではない）をコミットし、CI の run ごとに署名が変わって上書きインストールできなくなる問題を防ぐ。
- release: GitHub Secrets（RELEASE_KEYSTORE_B64 / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD）があればそれで署名し、無ければ debug 鍵で署名して `crownfall-release-debugsigned.apk` にする。鍵とパスワードはリポジトリにもログにも出さない。
- R8（minify）は無効（実機で検証できない環境での、シリアライズまわりの実行時クラッシュを避けるため）。
- 1.0.0（versionCode 100）。CI のログに、両 APK の署名証明書の SHA-256 を出す。

## 15. 実装との同期メモ（M8 T8.3）
- **§1 の版**（`gradle/libs.versions.toml`）: Kotlin 2.1.21、AGP 8.9.3、Compose BOM 2025.05.01、Navigation 2.9.0、Lifecycle 2.9.0、DataStore 1.1.7、OkHttp 4.12.0、WorkManager 2.10.1、core-splashscreen 1.0.1、kover 0.9.1、ktlint-gradle 12.3.0、JUnit 5.13.4。
- **§2.1 `:core` の実際のパッケージ**: model / data / sim / economy / session / buff / military / research / hero / map / march / combat / alliance / chat / shop / quest / event / notify / save / util。
  - 主なモデル名: `BuildingSlot`、`HeroState`、`AllianceState`、`QuestState`、`EventState`、`ShopState`、`Ledger`、`Boost`。マップのモデルは `core/map/MapModels.kt`、`March` は `core/march`。
  - データ JSON: buildings, rules, items, speedup, troops, research, heroes, map_rules, personas, alliance_rules, templates, offers, shop_rules, vip, quests, events。
  - shop: OfferEngine / FakePurchase / FakeLedger / VipCalculator / ShopFeatures（DailyDeals・MonthPass・GrowthFund・PiggyBank・LuckyWheel）/ ShopHooks / ShopLogic。
- **§2.2 `:ai`**: `ChatRequest` には event・speakers・seed・recent・vars・knownSpeakers も含まれる。ほかに ChatRequestFactory、router（AiRouter / OutputFilter / Usage）、providers（Gemini / OpenAiCompat / Scripted / StyleApplier / FreeTextClassifier / ResponseParser / UrlPolicy）がある。
  - 既定のプロバイダの順は gemini → openai_compat（どちらもキーや URL が無ければスキップ）→ Scripted。
- **§2.3 `:app` の主なパッケージ**: data（GameRepository、FileSaveStorage、SessionCoordinator）、ai（AiService、AppSettings、SecretCipher、PrefsUsageStore、ChatCoordinator）、offers（OfferHooks の実装）、notify、ui/*。
- **§4 の CI（実際の構成）**:
  - jvm ジョブ: `:core:test :ai:test ktlintCheck koverXmlReport :core:koverVerify :ai:koverVerify -PwithAndroid=false`、billing 依存の検査、check-no-billing-refs.sh、check-no-payment-brands.sh、失敗時のテストレポート。
  - apk ジョブ: `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:ktlintCheck`、billing 依存の検査、check-apk-no-billing.sh、release の署名の準備（Secrets）、`assembleRelease`、証明書の SHA-256 の照合、成果物 crownfall-debug-apk と crownfall-release-apk（14 日）、lint レポート。
  - setup-android は使わず、ランナー同梱の SDK を使う。

## 16. M9: オンデバイス LLM と「量産型にうるさい」通知（Director が決定、ユーザー承認済み）
- **ランタイム**: 第一候補は LiteRT-LM（`com.google.ai.edge.litertlm:litertlm-android`）。MediaPipe の LLM Inference API（tasks-genai）は Android 版が保守のみのモードで、公式に LiteRT-LM への移行が推奨されているため。要件を満たさない、または CI で解決できない場合だけ tasks-genai にフォールバックする。いずれも `:app` の `OnDeviceLlmEngine` の後ろに閉じ込める。
- AiRouter の既定の順: gemini → onDevice → openai_compat → Scripted。
- モデルはアプリがホストしない。ファイル選択（SAF）か、https の URL からのダウンロード（再開・サイズ確認・任意の SHA-256）で `filesDir/models/` に置く。
- 推論は単一スレッドで 1 件ずつ、遅延ロード、onStop で解放、タイムアウト 30 秒。abiFilters は arm64-v8a のみ。
- **通知**:
  - 襲撃は段階化する（偵察された → 進軍中 → 1 時間前 → 15 分前 → 開始 → 結果）。各段階は tick の境界で、Watchtower の Lv で見える段階が変わる。
  - onStop の時点で、オフライン上限の範囲内を先読み（`NotificationPlanner.lookahead`、純関数）して予約する。背景サービスは使わない。上限を超える事件は予告しない。復帰の催促（4h / 24h / 72h / 7d）だけはゲーム外の通知として予約してよい。
  - 強度 LOW / STANDARD / MASS（既定 MASS）、1 時間あたりの上限（襲撃は対象外）、まとめ通知、おやすみ時間（既定 OFF、襲撃は例外）。いずれも notification_rules.json と `NotificationPolicy`（純関数）で決める。
  - 配送: 時刻精度が要るものは exact alarm（13 以降は USE_EXACT_ALARM。サイドロード専用のため宣言する。12 は SCHEDULE_EXACT_ALARM）。許可が無ければ inexact、それ以外は WorkManager。BOOT_COMPLETED で予約を復元する。
- release の本格署名は不要（ユーザー決定）。debug 鍵署名の release APK で確定。Secrets の経路は任意の補足として残す。
- M9 で確定した実装上の判断:
  - LiteRT-LM 0.18.0 を採用した。その AAR が Kotlin 2.4 のメタデータを必要とするため、**Kotlin 2.1.21 → 2.4.0、kover 0.9.1 → 0.9.11** に更新した（§1 と §15 の版は、これで置き換える）。
  - abiFilters は arm64-v8a のみで、APK は debug 約 36.5MB / release 約 32.5MB。
  - litertlm に生成のキャンセル API が無いので、生成中の新しい要求は即座に失敗させ（fail-fast）、タイムアウト時は呼び出し側だけ先に戻る（M9 REVIEW）。
  - 襲撃の段階（notification_rules.json）: 偵察された 12h 前 / Watchtower Lv6、進軍中 4h 前 / Lv4、1 時間前 / Lv2、15 分前 / Lv1、開始と結果 / Lv0。
  - 強度ごとの 1 時間あたりの上限: LOW 2、STANDARD 6、MASS 12（襲撃は対象外）。先読みは 48h（ゲーム内のイベントはオフライン上限の範囲内だけ）。  - M9 REVIEW で確定した点:
    - 生成は専用の executor で行い、呼び出し側は CompletableDeferred を待つ（タイムアウトで先に戻る）。
    - 読み込み中のモデルのキーを保存し、起動時にそれが残っていれば失敗扱いにする（ネイティブのクラッシュで毎回落ちるのを防ぐ）。
    - WorkManager は enqueueUniqueWork（REPLACE）で予約し、再起動後に二重にならないようにする。
    - ダウンロードの再開は、.part と一緒にダウンロード元の URL を保存し、URL が違えば最初から取り直す。
    - kotlinx-serialization を 1.10.0 に上げた。
    - 先読みの対象に「兵の待機」と「オファーの復活」を追加した。

## 17. 戻るの原則（1.0.1 の S1 で Director が決定。全画面に適用）
- どの状態でも、システムの戻るは必ず効く。ダークパターンとして許すのは「× を 2 秒遅らせて出す」と「擬似決済の処理中（最長 6 秒）はキャンセル不可」の 2 つだけ。処理が終わったら、戻る・シートの外のタップ・× のどれでも閉じられる。
- オファーを閉じたら、次の自動ポップアップまで最低 `popupGapSec`（shop_rules.json、既定 20 秒）空ける。戻るで閉じた場合は、そのセッションの残りの自動ポップアップを止める（棚とバッジには残す）。
- 下部ナビの「城内」は、saveState と restoreState を使わずに城内まで戻す（popBackStack(City)）。他のタブを再選択したら、そのタブのルートへ戻す。
- 戻るを繰り返すと、子画面 → タブのルート → 城内 → 退出の確認、の順になる。

- ユーザー起点の導線は抑制の対象外（1.1.1 で決定）: ユーザーが自分でタップした購入・不足の導線（2 本目ビルダー、資源不足、ジェム不足、各購入ボタン）は、自動ポップアップの抑制（popupGapSec、popupsStopped、SKU のクールダウン、セッション上限）を受けず、必ず押したものを表示する（`OfferEngine.request` と `ShopState.requestedOfferId`）。タップして何も起きない状態は作らない。出せない場合も理由を表示する。
- 実装（1.0.1）: `navigation/NavActions.kt` の `navigateBottom` / `openPendingRoute`、`OfferEngine.nextPopup`、`ShopState.lastPopupClosedEpochSec` / `popupsStopped`（beginSession で解除）。退出待ち（exitStage 1）でも城内の BackHandler は有効で、戻るで退出の確認へ進む（オファーの Dialog が出ている間は Dialog が先に戻るを受け取る）。決済シートには処理中の 9 秒ガード（進まなくなっても閉じられる）。

## 18. M10: 見た目と演出（Director が決定）
- 見た目の層だけを差し替える。GameState・スロット・レベル・決定論（R1〜R9）は変えない。演出の分岐は :core の結果から決め、演出用の乱数は結果に影響させない。
- アニメは描画フェーズの時計で動かし、毎秒の state 更新と分ける（§15 の LocalClock の方式）。スプライトはアトラス化して事前にラスタライズし、Path を毎フレーム組み立てない。中級機で 60fps が目標。
- 新しい全画面の演出（召喚、VIP 昇格、テイクオーバー、購入完了）も §17 に従う。戻るでいつでもスキップできるか閉じられる。
- ガチャのフェイクアウト（昇格演出、惜しい演出）の割合は heroes.json の presentation に置き、Summoner の結果だけから決定論的に分岐する。確率表示と天井は変えない。
- 検証: CI の `ui` ジョブ（エミュレータ API 34、x86_64）で androidTest とスクリーンショット（artifact `ui-screenshots` / `ui-logs`）を取る。状態は debug 専用の `DebugStateSeeder`（src/debug）で用意する。x86_64 は debug の abiFilters にだけ追加し、release は arm64-v8a のみのまま。
- アセットはすべて自作（docs/ASSETS.md）。APK の増加は 20MB 程度までを目安にし、超える場合は Director に相談する。
- 領地の拡大（M10 REVIEW で決定）: ルール（01）は変えない。霧と森で覆うのは、建物の前提条件のために今は建設できない区画だけにし、「城 Lv n で開放」の札（n は前提条件から計算）を立てる。城壁の線は区画のフットプリントを横切らない。現データでは全区画が城 Lv1 で建設できるので、拡大の見た目は「城壁の材質と規模（木の柵 → 石 → 塔つき → 大理石と金）」と「壁の外の城下町（建設できない装飾）が帯ごとに外へ広がる」で表す。「城 Lv n で領地が広がる」の札は城下町の外縁に立てる。
- スクリーンショットの受け渡し: コンテナから Actions の artifact に届かないので、ui ワークフローが縮小した画像を専用ブランチ `qa-screenshots`（`runs/<run_number>-<sha>/`）に通常のコミットで追記する（force push はしない）。統合ブランチには画像を置かない。
- 城内の画面構成（M10 の画像レビューで決定）: 領地ビューを全画面の背景にし（高さの 60% 以上）、HUD は重ねる帯にする。キューは横 1 列のアイコン、イベントはチップ、クイックリンクは右端の縦の列。Lv のバッジは一定以上のズームでだけ出し、初期ズームでは建設中・アップグレード可能の印だけにする。パンは領地の外接矩形から画面の中心が出ない範囲。
- ui ワークフローのエミュレータは一般的なスマホの画面（pixel_6 相当）。シードの状態は、資源を上限の 90% 程度、チュートリアルは完了済みにする（スナックバーやチュートリアルの帯で画面が隠れないように）。
- 実装メモ（M10 完了時）: 領地のアンビエントのアニメは毎秒 24 回の階段状の再描画（電池への配慮。ジェスチャー中はカメラの変化で即時に再描画）。SpriteCache はプロセス共通の LRU（上限 56MB、実測 30.6MB / 82 枚）。エミュレータ（swiftshader）のフレーム時間は実機の指標にしない。release APK は 33.5MB（arm64-v8a のみ）。

## 19. 端末内 LLM のプロンプト（1.1.2 で Director が決定）
- プロファイルは standard（既定。Gemma 3n E2B / E4B 向け）と tiny（1B / 270m 向け）。モデルのファイル名で自動判定し、設定で上書きできる。Gemini / OpenAI 互換の JSON プロファイルとは別物。
- 長いプロンプトを切り落として流用しない。小型モデルには、自然な文章の人物設定・表示名（内部 ID は出さない）・口調の例・短い状況・柔らかい文体の指示を与え、出力はセリフだけ（JSON を強制しない）。出力の形式の指示は予算で削らない。
- バックエンドは GPU を先に試し、初期化や最初の推論の失敗・ネイティブのクラッシュの記録があれば CPU にする（設定が GPU でも、記録があれば CPU。ユーザーの明示の再試行でだけ記録を消す）。
- タイムアウトは standard 45 秒・tiny 30 秒（暫定）。モデルはチャットを開いたときに先に読み込み、時間切れでも生成できた行は使う。

## 20. M11: 段階解放・ポートレート・獲得演出・音と BGM・マップとスキン（Director が決定）
- 段階解放は :core の規則（`core.feature`、`FeatureGate.unlocked` は城 Lv だけから決まる純関数）。未解放の機能の Actions は FeatureLocked を返し、同盟の AI（Lv4 まで止める）・襲撃（Lv5 まで予定しない）・週替わりイベント（Lv8 まで得点も報酬も無し）・オファー（Lv3 まで自動は出さない、未解放の機能のパックは積まない）・デイリー（解放済みの項目だけ、宝箱の閾値は比例）・通知も、解放に従う。建物の解放は buildings.json の前提条件で表す。
- `GameState.features`（celebrated / firstUseClaimed / initialized）。既存のセーブは、読み込み時に解放済みの機能を演出済み・受け取り済みにする（洪水と二重取りを防ぐ）。解放は城 Lv のタイマーの完了で起き、tick の境界なので R3 を保つ。
- 収穫バブルは少量のボーナスで、:core の規則（最後の回収時刻だけを持ち、出現は時刻から導出する。tick にイベントを足さない）。
- BGM は生成した Standard MIDI File を Android 標準の MIDI 再生で鳴らす（APK がほぼ増えない）。他のアプリが音楽を流していれば始めず、音声フォーカスを失ったら止める。自分の音楽ファイルも読み込める。
- ポートレートは、英雄とペルソナの 20 体を個別指定の生成画像、名前だけのメンバーをパーツの決定論の合成にする。見本を Director が確認してから組み込む。
- ワールドマップのバイオームは見た目だけ（Terrain・位置・距離・ノードは変えない）。8×8 タイルのチャンクを事前にラスタライズする。
- スキンは擬似課金（fakePurchase の経路）。スキンの福袋は、確率の表示・天井・重複の欠片の交換を付ける（召喚と同じ倫理の線）。
- T11.1 のレビューで決定: 「最初の使用」の印は、ユーザーの意図的な行動（画面を開く・購入・受け取り）でだけ付ける（自動の処理では付けない）。ラリーは Lv10、ワールドチャットは Lv8 の解放まで、AI も作らず通知もしない。ログインの日数と VIP pt は Lv1 から積むが、VIP の演出は SHOP の解放後、VipUp のチャットは ALLIANCE の解放後。購入済みの権利（月パス・VIP チェスト・成長ファンド）の受け取りはゲートの対象外。ピギーは解放から貯める。旧セーブで EVENTS を解放するときは、古い週を集計せずにリセットする。
- 解放の間隔の目標（現実的なプレイヤーの試算。データだけで調整）: Lv2 約 2 分、Lv3 約 5 分、Lv4 約 12 分、Lv5 約 25 分、Lv6 約 45 分、Lv7 約 1.5〜2 時間、Lv8 約 4 時間、Lv9 約 8 時間、Lv10 約 12〜24 時間。
- M11 のまとめレビューで決定: BGM は通常とバイブのモードで鳴らし、サイレントで止める（効果音は通常のときだけ）。城のスキンは色替えでなく、各スキンに専用の装飾 2 つ以上と城内の環境演出を持たせる（高い商品が安い商品と同じ見た目にならない）。福袋と召喚のボタンは in-flight の判定で二重課金を防ぐ。期間限定スキンの週は WeeklyEvents（月曜始まり）に揃える。城レベルアップの演出は `celebratedCastleLv` で順番待ちを保存する。ロック区画の札は、次の解放の Lv の区画にだけ出す。
- 解放の演出は、城のレベルアップの演出 1 枚に統一する（解放の行をその中に出し、閉じたらアイコンがタブへ飛び、矢印）。ワールドマップの城・砦・野営地・資源地は城内と同じアイソメのスプライトで描き（遠いズームは簡易アイコン）、道は最小全域木 + カーブ（見た目だけ。:core の行軍は直線のまま）。
