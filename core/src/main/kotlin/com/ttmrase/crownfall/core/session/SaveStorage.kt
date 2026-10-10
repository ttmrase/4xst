package com.ttmrase.crownfall.core.session

/** セーブの保存先（実装は :app。ブロッキング I/O なので IO ディスパッチャから呼ぶ）。 */
interface SaveStorage {
    /** 現行セーブ。無ければ null。 */
    fun readPrimary(): String?

    /** 1 世代前のバックアップ。無ければ null。 */
    fun readBackup(): String?

    /** [text] をアトミックに書く（現行を backup へ回してから置き換える）。 */
    fun writeAtomic(text: String)

    /** 壊れた現行ファイルを別名へ退避する（上書きしない）。 */
    fun quarantinePrimary()
}
