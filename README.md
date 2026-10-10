# Crownfall: Rise of Realms（仮題）

Android 向けの「量産型 4X ストラテジー」風ゲーム。**個人利用専用**。

- 中世ファンタジー王国を発展させ、兵を鍛え、ワールドマップで野蛮人や NPC 王国と戦う、見慣れた 4X モバイルの再現
- 同盟には **AI が演じる仲間** がいて、ヘルプ要請・お礼・雑談・DM を通じて「役に立てた」「繋がっている」感がある（無料 API またはローカル LLM、どちらも無しでも動く）
- ショップ・限定パック・ダークパターン風オファーが大量に出てくるが、**決済は 100% 擬似**。購入画面のラグ、「購入が完了しました」演出、レシートメールまで再現しつつ、実際にお金は 1 円も動かない

## 重要な注意
- 本物の決済 SDK（Google Play Billing 等）は一切組み込まない。
- Google / Play / カード会社などのロゴ・名称・固有の配色を模倣しない。決済シートは汎用デザイン。
- 擬似課金 UI はストアポリシーに抵触し得るため **Google Play には公開しない**。サイドロード（APK 直インストール）専用。
- 著作権のあるアセットは使わない。アイコン・図形は自作。

## ドキュメント
| ファイル | 内容 |
| --- | --- |
| [docs/01_GAME_DESIGN.md](docs/01_GAME_DESIGN.md) | ゲームデザイン（コアループ、画面、資源、建物、兵種、英雄、マップ、戦闘、クエスト、同盟） |
| [docs/02_ARCHITECTURE.md](docs/02_ARCHITECTURE.md) | 技術構成（モジュール、スタック、保存形式、CI、開発環境の制約） |
| [docs/03_AI_COMPANIONS.md](docs/03_AI_COMPANIONS.md) | AI 同盟メンバー（ペルソナ、イベント駆動チャット、プロバイダ、フォールバック、予算） |
| [docs/04_FAKE_MONETIZATION.md](docs/04_FAKE_MONETIZATION.md) | 擬似課金（ショップ、オファーエンジン、ダークパターン・カタログ、擬似決済フロー、台帳） |
| [docs/05_MILESTONES.md](docs/05_MILESTONES.md) | マイルストーンとチケット（M0〜M8）、完了条件 |
| [docs/06_TEAM_WORKFLOW.md](docs/06_TEAM_WORKFLOW.md) | 3 セッション体制（Director / Implementer / Debugger）の分担・ブランチ・メッセージ規約 |
| [docs/STATUS.md](docs/STATUS.md) | 現在の進捗（Director が更新） |

## ビルド
M0 完了後に追記（`./gradlew :core:test :ai:test` はローカル、APK は GitHub Actions のアーティファクト）。
