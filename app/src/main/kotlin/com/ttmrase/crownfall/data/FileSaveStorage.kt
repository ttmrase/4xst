package com.ttmrase.crownfall.data

import com.ttmrase.crownfall.core.session.SaveStorage
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * `filesDir/save/slot0.json` への保存。書き込みは tmp → fsync → 既存を slot0.bak へ → tmp を置換 の順で、
 * 途中でクラッシュしても現行か backup のどちらかが必ず残る。
 */
class FileSaveStorage(private val dir: File) : SaveStorage {
    private val primary = File(dir, "slot0.json")
    private val backup = File(dir, "slot0.bak")
    private val tmp = File(dir, "slot0.json.tmp")

    override fun readPrimary(): String? = primary.takeIf { it.isFile }?.readText(Charsets.UTF_8)

    override fun readBackup(): String? = backup.takeIf { it.isFile }?.readText(Charsets.UTF_8)

    override fun writeAtomic(text: String) {
        dir.mkdirs()
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (primary.exists()) {
            Files.move(primary.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        Files.move(tmp.toPath(), primary.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    override fun quarantinePrimary() {
        if (!primary.exists()) return
        // 同一秒に複数回退避しても先の破損ファイルを上書きしない（連番サフィックスで衝突を避ける）
        val stamp = System.currentTimeMillis() / MILLIS
        var target = File(dir, "slot0.corrupt-$stamp.json")
        var n = 1
        while (target.exists()) target = File(dir, "slot0.corrupt-$stamp-${n++}.json")
        Files.move(primary.toPath(), target.toPath())
    }

    private companion object {
        const val MILLIS = 1000L
    }
}
