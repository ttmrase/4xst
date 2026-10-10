# セーブ互換 fixture

`mN.json` は、マイルストーン N の完了時点のコミットの `NewGame` / `Actions` / `SaveCodec` で生成した実際のセーブ JSON
（新規開始 → 1 時間 → 農場アップグレード開始 → 1 日進める）。最新の `SaveCodec.decode` と `GameSession.load` で
読めること、tick してもクラッシュしないことを `SaveCompatibilityTest` で検証する。

| ファイル | 生成元のコミット | 版 |
| --- | --- | --- |
| m1.json | 110d51d（M1 完了） | version 1 |
| m2.json | b317112（M2 完了） | version 1 |
| m3.json | 83aa60e（M3 完了） | version 1 |
| m4.json | 6b8c932（M4 完了） | version 1 |
| m5.json | 71c9f8f（M5 実装完了。268f08c は M6 実装の後に積まれた docs コミットなので使わない） | version 1 |
| m6.json | 5755b80（M6 完了） | version 1 |
| m7.json | 0eef925（M7 時点の現行） | version 1 |

`version` を上げる変更（削除・改名・意味の変更）をするときは、その時点の fixture を足し、`Migrations` のテストを書くこと
（docs/02 §3、06 §5。Director の承認が必要）。fixture は編集しない（過去の形そのものが価値）。
