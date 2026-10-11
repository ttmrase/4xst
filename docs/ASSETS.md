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
- 短い OGG / WAV。設定でミュートでき、効果音の音量は BGM と別（既定は効果音 ON・100%）。M11 で BGM を追加（既定 ON・60%）。

| 生成スクリプト | 出力 | 内容 |
| --- | --- | --- |
| tools/sfxgen/gen_sfx.py | res/raw/sfx_{button,coin,fanfare,charge,flip,rare,ssr,alarm,vip,chest}.wav（M10、計 636KB）と、M11 追加の sfx_{collect,build_start,build_done,levelup,unlock,purchase,notify,tap}.wav | 標準ライブラリの wave で合成 |

## ポートレート（M11 T11.5）
- `tools/artgen/gen_portraits.py`（標準ライブラリのみ。パーツ＝輪郭・肌・髪・目・口・ひげ・兜や冠・鎧・背景の紋章を組み合わせた胸像の VectorDrawable）。
- 英雄 12 とペルソナ 8 は SPECS で 1 体ずつ設計（`pt_<id>.xml`）。名前だけの同盟メンバー 22 は look（m 8 / f 8 / n 6、personas.json の fillerMembers.look）ごとに ID のハッシュで組み合わせ（`pt_gen_<look>_<nn>.xml`）、プレイヤーのアバター 12 は m 5 / f 5 / n 2（`pt_avatar_<nn>.xml`）。合計 54 枚、各約 6.5 KB。
- 枠（レアリティ N 灰 / R 青 / SR 紫 / SSR 金＋流れる光、VIP のリング）はアプリ側の PortraitView で重ねる。リソース名で引くので `res/raw/keep.xml` で縮小から守る。
- 見本シート: `python3 tools/artgen/preview.py portraits`（`tools/artgen/previews/portraits_sheet.png`）。

## BGM（M11 T11.3）
- `tools/bgmgen/gen_bgm.py`（標準ライブラリのみ。Standard MIDI File・GM 音色。固定シードで決定論的）で 6 曲を生成し `res/raw/bgm_*.mid` に書き出す（6 曲で約 52 KB）。Android 標準の MediaPlayer の MIDI 再生で鳴らす。
- 曲（60〜90 秒・和音進行 1 周でループ）: title 68.6 秒 84 bpm / city 80.0 秒 96 bpm / map 76.8 秒 100 bpm / battle 64.0 秒 150 bpm / shop 68.6 秒 112 bpm / summon 80.0 秒 72 bpm。`python3 tools/bgmgen/gen_bgm.py --list` で一覧（長さ・テンポ・楽器）。
- 確認用: `python3 tools/bgmgen/preview.py`（ピアノロールの PNG。Pillow が要る。成果物ではない）。
- 再生: 800 ms のクロスフェード、他のアプリが音楽を流している間は始めない、音声フォーカスを失う・裏に回る・マナーモードで止める。自分の音楽ファイル（ACTION_OPEN_DOCUMENT、audio/*）は filesDir/bgm/ にコピーして、全体用か場面ごとに使える。
