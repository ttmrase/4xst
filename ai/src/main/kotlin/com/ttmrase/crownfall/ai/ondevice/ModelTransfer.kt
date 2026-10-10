package com.ttmrase.crownfall.ai.ondevice

/** モデルファイルの扱いの純関数（取り込み・ダウンロードの判定。Android に依存しない）。 */
object ModelTransfer {
    /** 読み込めるファイルの拡張子。 */
    val EXTENSIONS: List<String> = listOf(".litertlm", ".task")

    /** 空き容量に残す余裕（コピー中に他の書き込みがあっても詰まらないように）。 */
    const val STORAGE_MARGIN_BYTES: Long = 64L * 1024 * 1024

    /** 推奨 RAM（目安）。 */
    const val RECOMMENDED_RAM_BYTES: Long = 6L * 1024 * 1024 * 1024

    /** [name] が対応する拡張子か（大文字小文字は問わない）。 */
    fun isSupportedName(name: String): Boolean = EXTENSIONS.any { name.lowercase().endsWith(it) }

    /** 保存先のファイル名にしてよい文字だけにする（パスの区切りや制御文字を落とす）。空なら null。 */
    fun safeFileName(name: String): String? {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = base.filter { it.isLetterOrDigit() || it in "._-+() " }.trim().trimStart('.')
        return cleaned.takeIf { it.isNotEmpty() && isSupportedName(it) }
    }

    /** ダウンロード元は https のみ（ユーザー情報付き・ホスト無しは不可）。 */
    fun validateUrl(url: String): String? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true)) return "https_only"
        val rest = trimmed.substring("https://".length)
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return "no_host"
        if ('@' in authority) return "userinfo"
        return null
    }

    /** 空き容量が足りるか。[needBytes] が不明（0 以下）のときは余裕分だけ要求する。 */
    fun hasEnoughSpace(
        freeBytes: Long,
        needBytes: Long,
        marginBytes: Long = STORAGE_MARGIN_BYTES,
    ): Boolean = freeBytes >= (needBytes.coerceAtLeast(0) + marginBytes)

    /** ダウンロードの続きの決め方。 */
    sealed interface Resume {
        /** 続きから（[offset] バイト目から追記）。 */
        data class From(val offset: Long) : Resume

        /** 最初からやり直す。 */
        data object Restart : Resume

        /** すでに全部ある。 */
        data object Complete : Resume
    }

    /**
     * 既に [partialBytes] 受け取っているときの、Range 要求への応答 [code] / [contentRange]（`bytes 100-199/200`）の解釈。
     * 206 で開始位置が一致すれば続きから、200（Range 非対応）なら最初から、416 で全体が一致すれば完了。
     */
    fun resumeFor(
        partialBytes: Long,
        code: Int,
        contentRange: String?,
    ): Resume {
        if (partialBytes <= 0) return Resume.Restart
        return when (code) {
            HTTP_PARTIAL -> {
                val start = contentRange?.let { RANGE.matchEntire(it.trim()) }?.groupValues?.get(1)?.toLongOrNull()
                if (start == partialBytes) Resume.From(partialBytes) else Resume.Restart
            }
            HTTP_RANGE_NOT_SATISFIABLE -> {
                val total = contentRange?.let { TOTAL.matchEntire(it.trim()) }?.groupValues?.get(1)?.toLongOrNull()
                if (total == partialBytes) Resume.Complete else Resume.Restart
            }
            else -> Resume.Restart
        }
    }

    /** 完了後の検査結果。 */
    enum class Verdict { OK, SIZE_MISMATCH, SHA_MISMATCH }

    /**
     * サイズ（[expectedSize] が 0 以下なら照合しない）と SHA-256（[expectedSha] が空なら照合しない。大文字小文字・空白・
     * コロンは無視）を検査する。
     */
    fun verify(
        expectedSize: Long,
        actualSize: Long,
        expectedSha: String,
        actualSha: String,
    ): Verdict {
        if (expectedSize > 0 && expectedSize != actualSize) return Verdict.SIZE_MISMATCH
        val want = normalizeHex(expectedSha)
        if (want.isNotEmpty() && want != normalizeHex(actualSha)) return Verdict.SHA_MISMATCH
        return Verdict.OK
    }

    private fun normalizeHex(s: String): String = s.filter { it.isLetterOrDigit() }.lowercase()

    /** 端末要件の注意。 */
    enum class Advice { LOW_RAM, LOW_STORAGE, MODEL_BIGGER_THAN_RAM_SHARE }

    /** 総 RAM・空き容量・モデルのサイズから出す注意（出ても使える。警告のみ）。 */
    fun assess(
        totalRamBytes: Long,
        freeBytes: Long,
        modelBytes: Long,
    ): List<Advice> =
        buildList {
            if (totalRamBytes in 1 until RECOMMENDED_RAM_BYTES) add(Advice.LOW_RAM)
            if (!hasEnoughSpace(freeBytes, modelBytes)) add(Advice.LOW_STORAGE)
            if (totalRamBytes > 0 && modelBytes * RAM_SHARE_DIVISOR > totalRamBytes) {
                add(
                    Advice.MODEL_BIGGER_THAN_RAM_SHARE,
                )
            }
        }

    private const val HTTP_PARTIAL = 206
    private const val HTTP_RANGE_NOT_SATISFIABLE = 416
    private const val RAM_SHARE_DIVISOR = 2
    private val RANGE = Regex("bytes (\\d+)-\\d+/(?:\\d+|\\*)")
    private val TOTAL = Regex("bytes \\*/(\\d+)")
}
