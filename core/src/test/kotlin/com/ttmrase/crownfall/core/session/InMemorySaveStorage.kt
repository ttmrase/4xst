package com.ttmrase.crownfall.core.session

/** テスト用のメモリ上 SaveStorage。書き込み回数などを数える。 */
class InMemorySaveStorage(
    var primary: String? = null,
    var backup: String? = null,
) : SaveStorage {
    var writes = 0
    var quarantined = 0

    override fun readPrimary(): String? = primary

    override fun readBackup(): String? = backup

    override fun writeAtomic(text: String) {
        backup = primary ?: backup
        primary = text
        writes++
    }

    override fun quarantinePrimary() {
        primary = null
        quarantined++
    }
}
