package com.ttmrase.crownfall.data

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FileSaveStorage のクラッシュ窓とバックアップ・退避の検証（JVM のみ、実ファイル）。 */
class FileSaveStorageTest {
    @TempDir
    lateinit var dir: File

    private fun storage() = FileSaveStorage(File(dir, "save"))

    private fun file(name: String) = File(File(dir, "save"), name)

    private fun corruptFiles() = File(dir, "save").listFiles { f -> f.name.startsWith("slot0.corrupt-") }.orEmpty()

    @Test
    fun emptyDirectoryHasNothing() {
        val s = storage()
        assertNull(s.readPrimary())
        assertNull(s.readBackup())
    }

    @Test
    fun firstWriteCreatesPrimaryOnly() {
        val s = storage()
        s.writeAtomic("one")
        assertEquals("one", s.readPrimary())
        assertNull(s.readBackup())
        assertFalse(file("slot0.json.tmp").exists(), "tmp must not linger")
    }

    @Test
    fun secondWriteRotatesPreviousIntoBackup() {
        val s = storage()
        s.writeAtomic("one")
        s.writeAtomic("two")
        s.writeAtomic("three")
        assertEquals("three", s.readPrimary())
        assertEquals("two", s.readBackup())
    }

    @Test
    fun utf8RoundTripAndShrinkingWriteLeavesNoTail() {
        val s = storage()
        s.writeAtomic("名前:" + "あ".repeat(500))
        s.writeAtomic("短い")
        assertEquals("短い", s.readPrimary())
    }

    @Test
    fun crashAfterPartialTmpKeepsPrimaryReadableAndNextWriteRecovers() {
        val s = storage()
        s.writeAtomic("good")
        // tmp を書いている途中でクラッシュした状態（primary は無傷）
        file("slot0.json.tmp").writeText("half-writt")
        assertEquals("good", s.readPrimary())
        s.writeAtomic("next")
        assertEquals("next", s.readPrimary())
        assertEquals("good", s.readBackup())
        assertFalse(file("slot0.json.tmp").exists())
    }

    @Test
    fun crashBetweenRotateAndReplaceLeavesBackupAsLatestGoodSave() {
        val s = storage()
        s.writeAtomic("old")
        s.writeAtomic("latest")
        // primary を bak へ回した直後、tmp を primary へ置換する前にクラッシュ
        file("slot0.json").renameTo(file("slot0.bak"))
        file("slot0.json.tmp").writeText("complete-new")
        assertNull(s.readPrimary())
        assertEquals("latest", s.readBackup())
        // 復帰後の書き込みは通常どおり完了する
        s.writeAtomic("after")
        assertEquals("after", s.readPrimary())
        assertEquals("latest", s.readBackup().also { assertTrue(it != null) })
    }

    @Test
    fun quarantineMovesPrimaryAndDoesNotTouchBackup() {
        val s = storage()
        s.writeAtomic("a")
        s.writeAtomic("b")
        s.quarantinePrimary()
        assertNull(s.readPrimary())
        assertEquals("a", s.readBackup())
        val moved = corruptFiles()
        assertEquals(1, moved.size)
        assertEquals("b", moved.single().readText())
    }

    @Test
    fun quarantineWithoutPrimaryIsNoOp() {
        val s = storage()
        s.quarantinePrimary()
        assertEquals(0, corruptFiles().size)
    }

    @Test
    fun repeatedQuarantineNeverOverwritesEarlierCorruptFiles() {
        val s = storage()
        val contents = (1..5).map { "corrupt-$it" }
        for (c in contents) {
            // primary を直接壊れた内容で置き、同じ秒の中で連続して退避する
            File(dir, "save").mkdirs()
            file("slot0.json").writeText(c)
            s.quarantinePrimary()
        }
        assertEquals(contents.toSet(), corruptFiles().map { it.readText() }.toSet())
    }
}
