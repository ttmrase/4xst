package com.ttmrase.crownfall.ondevice

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import com.ttmrase.crownfall.ai.ondevice.ModelTransfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** 取り込み・ダウンロードの進み具合。 */
sealed interface TransferState {
    data object Idle : TransferState

    /** [total] が 0 以下なら不明。 */
    data class Running(val label: String, val done: Long, val total: Long) : TransferState

    /** [code] は文言を選ぶための識別子（`no_space`、`size_mismatch` など）。 */
    data class Failed(val code: String) : TransferState

    data class Done(val name: String) : TransferState
}

/** 端末の状況（モデルを使えるかの目安）。 */
data class DeviceInfo(val totalRamBytes: Long, val freeBytes: Long)

/**
 * 端末内 LLM のモデルファイルの置き場（`filesDir/models/`）。アプリはモデルを配布しない。ユーザーが選んだファイルをコピー
 * するか、https の URL からダウンロード（中断から再開、サイズ・任意の SHA-256 の照合）して置く。置けるのは 1 つだけ。
 */
class OnDeviceModelStore(
    context: Context,
    private val scope: CoroutineScope,
    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_SEC, TimeUnit.SECONDS)
            .build(),
) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "models").apply { mkdirs() }
    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)
    private val _revision = MutableStateFlow(0)
    private var job: Job? = null

    /** 進み具合。 */
    val state: StateFlow<TransferState> = _state.asStateFlow()

    /** モデルが入れ替わるたびに増える（UI の再読み込み用）。 */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** いま使うモデル（無ければ null）。 */
    fun current(): File? =
        dir.listFiles()
            ?.filter { it.isFile && ModelTransfer.isSupportedName(it.name) }
            ?.maxByOrNull { it.lastModified() }

    /** 端末の総 RAM と、保存先の空き容量。 */
    fun deviceInfo(): DeviceInfo {
        val mem = ActivityManager.MemoryInfo()
        app.getSystemService(ActivityManager::class.java)?.getMemoryInfo(mem)
        return DeviceInfo(mem.totalMem, freeBytes())
    }

    private fun freeBytes(): Long = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(0L)

    /** 転送を中止する（ダウンロードの途中ファイルは再開のために残す）。 */
    fun cancel() {
        job?.cancel()
    }

    /** モデルを消す。 */
    fun delete() {
        dir.listFiles()?.forEach { it.delete() }
        _revision.value++
    }

    fun resetState() {
        if (job?.isActive != true) _state.value = TransferState.Idle
    }

    /** [uri]（ファイル選択の結果）をアプリの領域へコピーする。 */
    fun import(uri: Uri) {
        if (job?.isActive == true) return
        job =
            scope.launch(Dispatchers.IO) {
                runTransfer { doImport(uri) }
            }
    }

    /** [url]（https のみ）からダウンロードする。[sha256] が空でなければ完了後に照合する。 */
    fun download(
        url: String,
        sha256: String,
    ) {
        if (job?.isActive == true) return
        job =
            scope.launch(Dispatchers.IO) {
                runTransfer { doDownload(url.trim(), sha256) }
            }
    }

    private suspend fun runTransfer(block: suspend () -> String) {
        _state.value = TransferState.Running("", 0, 0)
        _state.value =
            try {
                TransferState.Done(block())
            } catch (e: CancellationException) {
                _state.value = TransferState.Idle
                throw e
            } catch (e: TransferException) {
                TransferState.Failed(e.code)
            } catch (e: IOException) {
                TransferState.Failed("io")
            }
        _revision.value++
    }

    private suspend fun doImport(uri: Uri): String {
        val resolver = app.contentResolver
        var name: String? = null
        var size = 0L
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        val safe =
            ModelTransfer.safeFileName(name ?: uri.lastPathSegment.orEmpty()) ?: throw TransferException("bad_name")
        if (!ModelTransfer.hasEnoughSpace(freeBytes(), size)) throw TransferException("no_space")
        val part = File(dir, "$safe.part").apply { delete() }
        try {
            val input = resolver.openInputStream(uri) ?: throw TransferException("io")
            input.use { src ->
                part.outputStream().use { out -> copy(src, out, safe, size) }
            }
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
        return finish(part, safe, size, "")
    }

    private suspend fun doDownload(
        url: String,
        sha256: String,
    ): String {
        ModelTransfer.validateUrl(url)?.let { throw TransferException("bad_url") }
        val safe =
            ModelTransfer.safeFileName(Uri.parse(url).lastPathSegment.orEmpty()) ?: throw TransferException("bad_name")
        val part = File(dir, "$safe.part")
        val have = if (part.exists()) part.length() else 0L
        val request =
            Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        var total = 0L
        val http = client.newBuilder().followRedirects(true).followSslRedirects(false).build()
        http.newCall(request).execute().use { res ->
            val contentRange = res.header("Content-Range")
            val resume = ModelTransfer.resumeFor(have, res.code, contentRange)
            if (resume is ModelTransfer.Resume.Complete) return finish(part, safe, part.length(), sha256)
            if (!res.isSuccessful) throw TransferException("http_${res.code}")
            val body = res.body ?: throw TransferException("io")
            val append = resume is ModelTransfer.Resume.From
            val offset = if (append) have else 0L
            total = if (body.contentLength() > 0) offset + body.contentLength() else 0L
            val need = (total - offset).coerceAtLeast(0)
            if (!ModelTransfer.hasEnoughSpace(freeBytes(), need)) throw TransferException("no_space")
            if (!append) part.delete()
            java.io.FileOutputStream(part, append).use { out ->
                body.byteStream().use { src -> copy(src, out, safe, total, offset) }
            }
        }
        return finish(part, safe, total, sha256)
    }

    private suspend fun copy(
        src: java.io.InputStream,
        out: java.io.OutputStream,
        label: String,
        total: Long,
        start: Long = 0L,
    ) {
        val buf = ByteArray(BUFFER)
        var done = start
        var lastReport = 0L
        while (true) {
            coroutineContext.ensureActive()
            val n = src.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            if (done - lastReport >= REPORT_STEP) {
                lastReport = done
                _state.value = TransferState.Running(label, done, total)
            }
        }
        _state.value = TransferState.Running(label, done, total)
    }

    /** 検査して確定する（古いモデルは消す）。 */
    private suspend fun finish(
        part: File,
        name: String,
        expectedSize: Long,
        sha256: String,
    ): String {
        val actual = part.length()
        val sha = if (sha256.isBlank()) "" else withContext(Dispatchers.IO) { sha256Of(part) }
        when (ModelTransfer.verify(expectedSize, actual, sha256, sha)) {
            ModelTransfer.Verdict.OK -> Unit
            ModelTransfer.Verdict.SIZE_MISMATCH -> {
                part.delete()
                throw TransferException("size_mismatch")
            }
            ModelTransfer.Verdict.SHA_MISMATCH -> {
                part.delete()
                throw TransferException("sha_mismatch")
            }
        }
        dir.listFiles()?.filter { it != part }?.forEach { it.delete() }
        val target = File(dir, name)
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        return name
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class TransferException(val code: String) : Exception(code)

    private companion object {
        const val CONNECT_SEC = 15L
        const val READ_SEC = 60L
        const val BUFFER = 64 * 1024
        const val REPORT_STEP = 512L * 1024
    }
}
