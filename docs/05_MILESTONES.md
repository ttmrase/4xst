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

## M2 城内 UI — ✅ 完了 2026-10-10（HEAD 7a3dcd4 と修正 b22934e / 4924700 / 705ad2c、QA 46cdc42。CI run 38030769719。設計は 02 §8）
| ID | 内容 |
| --- | --- |
| T2.1 | `GameRepository`（StateFlow、1 秒 tick、reduce）、`SaveStore`（アトミック保存・bak・debounce・onStop） |
| T2.2 | 城内画面: スロット配置（スクロール可能な 2D レイアウト or 固定グリッド）、建物タップ → 詳細シート（効果・次 Lv・コスト・不足表示・アップグレード）、資源バー、タイマーバー、戦力表示 |
| T2.3 | 加速ダイアログ（所持アイテム一覧、ジェムスキップ、不足時はオファー誘導のフック） |
| T2.4 | 不在サマリーダイアログ、最小チュートリアル（3 ステップの矢印ガイド） |
| T2.5 | 新規ゲーム開始フロー（名前入力、初期英雄 3 択）、タイトル画面 |
DoD: CI の APK で Castle Lv1→5 が遊べる。Debugger がエミュレータ無しのためコードレビュー + ユニットテスト（ViewModel ロジック）で確認。

## M3 軍事・研究・英雄 — ✅ 完了 2026-10-10（HEAD d74a23d と修正 d939afc、QA 81afd4f。CI run 38033161525。設計は 02 §9）
| ID | 内容 |
| --- | --- |
| T3.1 | `troops.json` `research.json` `heroes.json` + model |
| T3.2 | 訓練キュー、Food 維持費、病院（容量・治療タイマー） |
| T3.3 | 研究ツリー（前提、Lv、効果の集約 `BuffAggregator`） |
| T3.4 | 英雄召喚（確率表、天井、重複→欠片）、レベル/星/スキル |
| T3.5 | UI: 兵舎、研究、英雄一覧/詳細、召喚演出（簡易: カード反転） |
テスト: seed 固定の召喚結果、天井保証、Buff 集約の加算/乗算順序。

## M4 ワールドマップ・戦闘 — ✅ 完了 2026-10-10（HEAD 59f6176 と修正 d5bfb9c / e316217、QA b09c35e。CI run 38036914082。設計は 02 §10）
| ID | 内容 |
| --- | --- |
| T4.1 | `MapGenerator(seed)`: 64×64、タイル分布、同盟領土、NPC 王国、資源ノード/野蛮人の再出現 |
| T4.2 | 行軍（出発/到着/帰還タイマー）、採集（滞在・積載）、偵察、攻撃、ラリー参加 |
| T4.3 | `CombatResolver` + `BattleReport`、負傷/死亡、略奪、NPC 襲撃イベント |
| T4.4 | UI: Canvas マップ（ズーム/パン、可視範囲描画）、タイル詳細、部隊編成（兵・英雄・数量）、行軍アイコン、報告メール |
テスト: 戦闘決定論、三すくみ係数、距離/時間、積載上限。

## M5 同盟・AI チャット — ✅ 完了 2026-10-10（HEAD 9a427d9 と修正 71c9f8f、QA 2dc11e1 / 13c046b。設計は 02 §11）
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

## M6 ショップ・擬似課金 — ✅ 完了 2026-10-10（HEAD 13a1b86 と修正 60f5b54、QA 2aa10b1。CI run 38043634212。設計は 02 §12）
| ID | 内容 |
| --- | --- |
| T6.1 | `offers.json`（全 SKU・パック・トリガー DSL）、`OfferEngine`（トリガー評価・クールダウン・キュー・頻度設定・復活）、`FakeLedger`、`VipCalculator` + `vip.json` |
| T6.2 | `FakeBillingSheet`（04 §5 のフロー全演出）、レシートメール、連鎖オファー |
| T6.3 | ショップ画面全タブ、デイリーディール、月パス、成長ファンド、ピギーバンク、ラッキーホイール |
| T6.4 | ポップアップ連鎖（ログイン 3 連）、退出前オファー、資源/ジェム不足フック、社会的証明（AI 購入通知 → ギフト箱） |
| T6.5 | 設定: オファー頻度、演出、台帳表示、注意書き |
テスト: 全トリガーのユニットテスト、クールダウン、頻度上限、不足量ぴったり生成、台帳集計。CI で `billing` を含む依存が無いことを assert するタスク。

## M7 クエスト・イベント・通知・仕上げ — ✅ 完了 2026-10-10（HEAD 495aa3e と修正 39e268f / 99f573c / ad01a44、QA 0eef925。設計は 02 §13。T7.5 は M9 へ繰り越し。後追い: 逆転パックのボーナスを週末時点の NPC 最高スコア基準にする件、襲撃到着の通知）
| ID | 内容 |
| --- | --- |
| T7.1 | `quests.json`（チャプター 20、デイリー 10、同盟、実績）、進捗判定、宝箱 |
| T7.2 | 週替わりイベント + NPC ランキング（「惜しくも 2〜4 位」調整）+ 逆転パック連動 |
| T7.3 | WorkManager 通知（タイマー、満杯、オファー期限、メンション、襲撃予告）、通知チャンネル |
| T7.4 | アプリアイコン、スプラッシュ、効果音（任意・自作 or CC0）、ローカライズ点検、量産型翻訳調の文言統一 |
| T7.5 | オンデバイス LLM（MediaPipe）の実装検討 → **M9 へ繰り越し（Director 決定）** |

## M8 QA・リリース — ✅ 完了 2026-10-10（HEAD f3261e6、CI run 78。1.0.0。T8.1 は Debugger、T8.2 と T8.4 は Implementer、T8.3（docs 同期）は Director。設計は 02 §14）
| ID | 内容 |
| --- | --- |
| T8.1 | 全画面スモークチェックリスト（Debugger 作成）、tick/catch-up ベンチ、セーブ互換テスト（v1 → 最新） |
| T8.2 | 署名付き release ビルド（CI）、README のインストール手順・既知の問題・AI 設定手順（スクショ不要、手順のみ） |
| T8.3 | 最終ドキュメント同期（docs と実装の差分解消） |
| T8.4 | 性能パス: 毎秒の state 更新による全画面の再構図を、部分的な購読で減らす（Director が追加） |

## M9 拡張（ユーザー承認 2026-10-10）— ✅ 完了 2026-10-10（HEAD 7d5ed68 と修正 ec6bcc3、CI run 93。QA は QaNotifyTest。実機の検証は SMOKE_CHECKLIST 第 11 章。設計は 02 §16）
| ID | 内容 |
| --- | --- |
| T9.1 | オンデバイス LLM（LiteRT-LM が第一候補、tasks-genai がフォールバック）。モデルの読み込み（ファイル選択・URL ダウンロード）、端末要件の表示、AiRouter の順は gemini → onDevice → openai_compat → Scripted |
| T9.2a | :core: 襲撃の段階化（6 段階、Watchtower の Lv で可視性）、AI の反応、先読みの通知計画（オフライン上限の範囲内）、強度・上限・まとめ・おやすみ時間の選別 |
| T9.2b | :app: exact alarm と WorkManager の配送、チャンネルの再編（襲撃は高い重要度）、BOOT_COMPLETED での復元、アプリ内のうるささ（最前面のポップアップ、緊急ティッカー、赤いバナー、バイブ）、設定 |

## 1.0.1 ホットフィックス（S1、2026-10-10）— ✅ 完了 2026-10-10（HEAD 4986231、CI run 111）
- BUG 1: オファーと擬似決済のポップアップから抜けられない（戻るが効かず、閉じても次が即座に出る）。
- BUG 2: チャットやメールなどから城内へ戻れない（下部ナビの City で restoreState が保存された子画面を復元してしまう）。
- 修正方針は 02 §17。回帰テスト（NavController の遷移、ポップアップの戻る）は Debugger が担当。
- 結果: 下部ナビは saveState / restoreState を廃止（NavActions.navigateBottom）。オファーは戻るで閉じ、閉じたあとは popupGapSec（20 秒）空け、戻るで閉じたらそのセッションの自動ポップアップを止める。退出待ち（exitStage 1）でも戻るで確認へ進む。

## M10 見た目と演出の大幅強化（ユーザー要望 2026-10-10）— ✅ 完了 2026-10-10（1.1.0、HEAD 6f5c1cf、android run 164 と ui run 42。設計は 02 §18、アセットは docs/ASSETS.md）
- T10.1 検証基盤（CI にエミュレータとスクリーンショット）、T10.2 アセット方針（すべて自作。生成スクリプト tools/artgen・tools/sfxgen、フォントは Downloadable Fonts）、T10.3 城内を領地マップに作り直す、T10.4 オファーとショップの課金圧の演出、T10.5 ガチャの射幸心の演出、T10.6 VIP など課金で得たものの贅沢な演出、T10.7 全体の UI の磨き込み、T10.8 性能とサイズ（APK の増加は 20MB 程度まで）。順序は T10.1 → T10.2 → T10.3 → T10.5 → T10.4 → T10.6 → T10.7 / T10.8。各 DONE にスクリーンショット（ui-screenshots）を添付する。


## 1.1.1 ホットフィックス（2026-10-11）— ✅ 完了（1.1.2 と合わせて配布）
- BUG 1（S3）: 弓兵と騎馬のアイコンが逆（TroopType の並びと、index で引くグリフの表がずれていた）。兵種と資源のグリフは enum の when で網羅する。
- BUG 2（S2）: 「2 本目の建設キューを購入」が無反応（オファーのキュー経由で、閉じた同じ SKU・クールダウン・popupGapSec・popupsStopped に止められていた）。ユーザー起点の導線は抑制の対象外（02 §17）。

## 1.1.2 ホットフィックス（2026-10-11）— ✅ 完了（b53a3c3、android run 179 / ui run 57）
- 端末内 LLM の返事が壊れる（S2）: Gemini 向けの長いプロンプトを 700 文字で切り落として JSON を強制していた。小型モデル専用のプロンプト（400 文字以内、1 回 1 話者、表示名と口調の例、ロケールに従う）、1 話者ずつの逐次生成、プレーンテキストの後処理、サンプラーの明示、設定のデバッグ（直近 5 件の入出力・テスト発話）。
- 変更（Planner 経由のユーザー情報）: ユーザーのモデルは Gemma 3n E2B。プロファイルを 2 段にする: standard（既定。E2B / E4B 向け、システム指示 400〜700 文字、複数行の生成とフォールバック）と tiny（1B / 270m 向け、上記）。ファイル名で自動判定し、設定で上書きできる。バックエンドは GPU を先に試して CPU に戻す（設定で自動 / CPU / GPU）。

## M11 段階解放・獲得演出・音と BGM・マップとスキン・ポートレート（ユーザー要望 2026-10-11）— ✅ 完了 2026-10-11（1.2.0、HEAD a9078c8、android run 238 / ui run 116。設計は 02 §20、解放表は 01）
- T11.1 段階解放（城 Lv による機能ゲート）、T11.5 ポートレート、T11.2 獲得アニメーション（HUD へ飛ぶ、収穫バブル）、T11.3 音・BGM・達成感、T11.4 ワールドマップの本格化とスキン（擬似課金）、T11.6 仕上げ（1.2.0）。順序はこのとおり。DONE はチケットごと。
