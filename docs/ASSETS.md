# ASSETS（Director が管理）

Crownfall の画像・フォント・効果音の出所とライセンスを記録する。M10 で決定（docs/02 §18）。

## 方針
- **すべて自作する。** 開発コンテナから外部の素材サイト（Kenney、OpenGameArt、Google Fonts のサイト）に届かず、取り込んだ素材をレビューできず、ライセンスの記録もできないため（2026-10-10 に確認）。
- 例外はフォントの「ダウンロード方式」だけ（下記）。APK には同梱しない。
- 実在のブランド・決済事業者・他社ゲームのロゴや固有のデザインは使わない（04 §1）。

## グラフィック
- 自作のベクター（VectorDrawable、Compose の Canvas / Path）。
- 量が多いもの（建物の種類 × レベル帯 5 段階: 1–5 / 6–10 / 11–15 / 16–20 / 21–25）は `tools/artgen/*.py`（標準ライブラリのみ）で VectorDrawable の XML を生成し、生成物をコミットする。スクリプトを再実行すれば同じ結果になること。
- 生成スクリプトと出力先の一覧は、T10.2 の DONE で Implementer が報告し、Director がこの表に追記する。

| 生成スクリプト | 出力 | 内容 |
| --- | --- | --- |
| （T10.2 の DONE で追記） | | |

## フォント
- Compose の Downloadable Fonts（`androidx.compose.ui:ui-text-google-fonts`、Google Fonts のプロバイダ）を使う。ライセンスは SIL Open Font License 1.1（OFL）。
- 候補: 見出しは Cinzel、本文は Noto Serif JP / Noto Sans JP。端末で取得できなければ既定のフォントにフォールバックする。
- APK にフォントファイルは含めない。

## 効果音
- `tools/sfxgen/*.py`（標準ライブラリの wave で合成）で自作する: コイン、ファンファーレ、召喚のチャージ、レア確定のジングル、警報、ボタン。
- 短い OGG / WAV。設定でミュートでき、既定は効果音 ON / BGM なし。

| 生成スクリプト | 出力 | 内容 |
| --- | --- | --- |
| （T10.2 の DONE で追記） | | |
