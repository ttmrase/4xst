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
| tools/artgen/iso.py | （共通ライブラリ） | アイソメトリックの図形・色・VectorDrawable の書き出し |
| tools/artgen/gen_buildings.py | app/src/main/res/drawable/bld_<type>_t<1..5>.xml（18 種 × 5 = 90） | 建物。段階ごとに屋根・旗・塔・装飾・材質（木 → 石 → 大理石 + 金）が増える |
| tools/artgen/gen_decor.py | res/drawable/deco_*.xml（38）、ui/territory/SpriteIds.kt（生成） | 木・岩・家・街灯・井戸・噴水・看板・城壁 / 塔 / 門 × 5 段階・工事現場 |
| tools/artgen/preview.py | （開発用。Pillow があるときだけ。成果物ではない） | 見本の PNG（Director のレビューに使用） |

## フォント
- Compose の Downloadable Fonts（`androidx.compose.ui:ui-text-google-fonts`、Google Fonts のプロバイダ）を使う。ライセンスは SIL Open Font License 1.1（OFL）。
- 採用（M10）: Noto Serif JP（見出しと本文の一部）、Cinzel（欧文の見出し）。取得できなければ端末の serif。
- `res/values/font_certs.xml` は、フォントプロバイダの証明書（AOSP のサンプルと同じ公開値）。UI には表示しない。端末で取得できなければ既定のフォントにフォールバックする。
- APK にフォントファイルは含めない。

## 効果音
- `tools/sfxgen/*.py`（標準ライブラリの wave で合成）で自作する: コイン、ファンファーレ、召喚のチャージ、レア確定のジングル、警報、ボタン。
- 短い OGG / WAV。設定でミュートでき、既定は効果音 ON / BGM なし。

| 生成スクリプト | 出力 | 内容 |
| --- | --- | --- |
| tools/sfxgen/gen_sfx.py | res/raw/sfx_{button,coin,fanfare,charge,flip,rare,ssr,alarm,vip,chest}.wav（計 636KB） | 標準ライブラリの wave で合成 |
