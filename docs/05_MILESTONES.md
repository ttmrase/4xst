# 05 マイルストーンとチケット

各チケットは Director が Implementer に `TICKET` メッセージで発行する。完了条件（DoD）を満たし、Debugger の確認と Director の `REVIEW: Approve` で閉じる。
Director はここを唯一のバックログとして更新してよい（分割・追加・順序変更）。

共通 DoD:
- コンテナで `./gradlew :core:test :ai:test ktlintCheck` が緑
- `:app` に触れたら GitHub Actions が緑で APK アーティファクトが出ている
- 新ロジックにユニットテストがある
- 数値は JSON、UI 文字列は strings.xml
- `docs/` に影響する設計変更は同じコミットで docs を更新

---

## M0 基盤（目標: 最初の 1〜2 時間）— ✅ 完了 2026-10-10（HEAD 00f5f9b、CI run 38028345848）
| ID | 内容 |
| --- | --- |
| T0.1 | Gradle マルチモジュール骨格。`settings.gradle.kts`（`:core` `:ai` 常時、`:app` は `ANDROID_HOME`/`ANDROID_SDK_ROOT`/`-PwithAndroid=true` で条件 include）、`gradle/libs.versions.toml`、wrapper 8.14.3、ルート build は AGP 非参照 |
| T0.2 | `:core` `:ai` を kotlin("jvm") + kotlinx.serialization + coroutines + JUnit5 で作成。サンプルテスト 1 本ずつ。kover、ktlint 導入 |
| T0.3 | `:app` Compose 骨格: 単一 Activity、NavHost、12 画面のスタブ（タイトル表示のみ）、下部ナビ、Material3 テーマ（量産型らしい金・紺・赤） |
| T0.4 | `.github/workflows/android.yml`（02 §4 の構成）。`concurrency` 設定。アーティファクト名 `crownfall-debug-apk` |
| T0.5 | README にビルド手順・APK 入手手順（Actions → Artifacts）を追記 |
DoD: コンテナでテスト緑、CI で APK 生成確認（Debugger が `actions_list` で確認して報告）。

## M1 コアシミュレーション — ✅ 完了 2026-10-10（HEAD d100d48 と小修正 31c92d0・65462b2、QA 795d5fe。CI run 38029693916。設計規則は 02 §7）
| ID | 内容 |
| --- | --- |
| T1.1 | `model`: `Resources`, `Building`/`BuildingSlot`, `TimerJob`, `GameState`（最小構成）、`Clock`, `SeededRandom` |
| T1.2 | `data`: `buildings.json`（全建物 Lv1〜25 のコスト・秒・前提・効果・戦力）とローダ。`DataTables` シングルトンではなく注入可能なクラス |
| T1.3 | `Simulation.tick`: 生産（バフ込み）、倉庫上限、タイマー進行・完了・ビルダーキュー（1 本 + 任意の 2 本目）、`GameEvent` 出力 |
| T1.4 | `OfflineCatchUp`: 上限（24h、VIP 延長）、分割 tick、`OfflineSummary` |
| T1.5 | `save`: `SaveGame` v1、`Migrations` 骨格、JSON ラウンドトリップ |
| T1.6 | `economy`: 加速アイテム適用、ジェムスキップ換算（`speedup.json` の非線形テーブル）、コスト判定（不足量を返す） |
| T1.7 | 戦力（Power）計算 |
テスト: 決定論（同 seed 同結果）、オーバーフロー（Long 範囲）、24h catch-up、タイマー境界、migration no-op。
DoD: `sim` `economy` の kover 行カバレッジ 80% 以上。

## M2 城内 UI — 実装済み・Director Approve 2026-10-10（HEAD 7a3dcd4、CI run 38030769719。QA と要修正 1 件の待ち。設計は 02 §8）
| ID | 内容 |
| --- | --- |
| T2.1 | `GameRepository`（StateFlow、1 秒 tick、reduce）、`SaveStore`（アトミック保存・bak・debounce・onStop） |
| T2.2 | 城内画面: スロット配置（スクロール可能な 2D レイアウト or 固定グリッド）、建物タップ → 詳細シート（効果・次 Lv・コスト・不足表示・アップグレード）、資源バー、タイマーバー、戦力表示 |
| T2.3 | 加速ダイアログ（所持アイテム一覧、ジェムスキップ、不足時はオファー誘導のフック） |
| T2.4 | 不在サマリーダイアログ、最小チュートリアル（3 ステップの矢印ガイド） |
| T2.5 | 新規ゲーム開始フロー（名前入力、初期英雄 3 択）、タイトル画面 |
DoD: CI の APK で Castle Lv1→5 が遊べる。Debugger がエミュレータ無しのためコードレビュー + ユニットテスト（ViewModel ロジック）で確認。

## M3 軍事・研究・英雄 — 発行済み 2026-10-10（設計は 02 §9）
| ID | 内容 |
| --- | --- |
| T3.1 | `troops.json` `research.json` `heroes.json` + model |
| T3.2 | 訓練キュー、Food 維持費、病院（容量・治療タイマー） |
| T3.3 | 研究ツリー（前提、Lv、効果の集約 `BuffAggregator`） |
| T3.4 | 英雄召喚（確率表、天井、重複→欠片）、レベル/星/スキル |
| T3.5 | UI: 兵舎、研究、英雄一覧/詳細、召喚演出（簡易: カード反転） |
テスト: seed 固定の召喚結果、天井保証、Buff 集約の加算/乗算順序。

## M4 ワールドマップ・戦闘
| ID | 内容 |
| --- | --- |
| T4.1 | `MapGenerator(seed)`: 64×64、タイル分布、同盟領土、NPC 王国、資源ノード/野蛮人の再出現 |
| T4.2 | 行軍（出発/到着/帰還タイマー）、採集（滞在・積載）、偵察、攻撃、ラリー参加 |
| T4.3 | `CombatResolver` + `BattleReport`、負傷/死亡、略奪、NPC 襲撃イベント |
| T4.4 | UI: Canvas マップ（ズーム/パン、可視範囲描画）、タイル詳細、部隊編成（兵・英雄・数量）、行軍アイコン、報告メール |
テスト: 戦闘決定論、三すくみ係数、距離/時間、積載上限。

## M5 同盟・AI チャット
| ID | 内容 |
| --- | --- |
| T5.1 | `personas.json`、`AllianceScheduler`（活動時間帯・確率・オフライン生成）、好感度 |
| T5.2 | ヘルプ（送受）、寄付と同盟テック、同盟ショップ、ラリー主催（AI）と参加、同盟ギフト |
| T5.3 | `:ai` `ScriptedProvider` + `templates.json`（全イベント × 8 ペルソナ × 5 本以上）+ `StyleApplier` + 直近回避 |
| T5.4 | `:ai` `GeminiProvider`、`OpenAiCompatProvider`、`AiRouter`（優先順・タイムアウト・予算・フォールバック。**既定順は Gemini → Scripted**、他プロバイダは設定で追加する任意扱い［ユーザー確定］）、`PromptContextBuilder` |
| T5.5 | UI: 同盟画面、チャット画面（タブ、タイピング表示、1 行ずつ放出）、設定（プロバイダ順・キー・base URL・モデル名・予算、使用回数表示。**初期選択は Gemini**） |
| T5.6 | ぽんずの「質問 3 択」ミニイベント、Yuki の過去行動参照、好感度マイルストーン DM |
テスト: Scripted のみで全イベントに応答がある、MockWebServer で 200/429/timeout/壊れ JSON、予算超過でフォールバック。
DoD: キー無しで完全動作。キー有りは Debugger が設定画面経路をコードレビュー（実キーは使わない）。

## M6 ショップ・擬似課金
| ID | 内容 |
| --- | --- |
| T6.1 | `offers.json`（全 SKU・パック・トリガー DSL）、`OfferEngine`（トリガー評価・クールダウン・キュー・頻度設定・復活）、`FakeLedger`、`VipCalculator` + `vip.json` |
| T6.2 | `FakeBillingSheet`（04 §5 のフロー全演出）、レシートメール、連鎖オファー |
| T6.3 | ショップ画面全タブ、デイリーディール、月パス、成長ファンド、ピギーバンク、ラッキーホイール |
| T6.4 | ポップアップ連鎖（ログイン 3 連）、退出前オファー、資源/ジェム不足フック、社会的証明（AI 購入通知 → ギフト箱） |
| T6.5 | 設定: オファー頻度、演出、台帳表示、注意書き |
テスト: 全トリガーのユニットテスト、クールダウン、頻度上限、不足量ぴったり生成、台帳集計。CI で `billing` を含む依存が無いことを assert するタスク。

## M7 クエスト・イベント・通知・仕上げ
| ID | 内容 |
| --- | --- |
| T7.1 | `quests.json`（チャプター 20、デイリー 10、同盟、実績）、進捗判定、宝箱 |
| T7.2 | 週替わりイベント + NPC ランキング（「惜しくも 2〜4 位」調整）+ 逆転パック連動 |
| T7.3 | WorkManager 通知（タイマー、満杯、オファー期限、メンション、襲撃予告）、通知チャンネル |
| T7.4 | アプリアイコン、スプラッシュ、効果音（任意・自作 or CC0）、ローカライズ点検、量産型翻訳調の文言統一 |
| T7.5 | オンデバイス LLM（MediaPipe）の実装検討。工数次第で M9 へ繰り越し可 |

## M8 QA・リリース
| ID | 内容 |
| --- | --- |
| T8.1 | 全画面スモークチェックリスト（Debugger 作成）、tick/catch-up ベンチ、セーブ互換テスト（v1 → 最新） |
| T8.2 | 署名付き release ビルド（CI）、README のインストール手順・既知の問題・AI 設定手順（スクショ不要、手順のみ） |
| T8.3 | 最終ドキュメント同期（docs と実装の差分解消） |
