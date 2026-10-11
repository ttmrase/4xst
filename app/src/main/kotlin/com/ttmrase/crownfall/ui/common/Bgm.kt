package com.ttmrase.crownfall.ui.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.RawRes
import com.ttmrase.crownfall.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** BGM の場面。曲は [res]（tools/bgmgen が生成した MIDI）。 */
enum class BgmScene(
    @RawRes val res: Int,
) {
    TITLE(R.raw.bgm_title),
    CITY(R.raw.bgm_city),
    MAP(R.raw.bgm_map),
    BATTLE(R.raw.bgm_battle),
    SHOP(R.raw.bgm_shop),
    SUMMON(R.raw.bgm_summon),
}

/**
 * BGM の再生（M11 T11.3）。MediaPlayer を 2 つ使って、場面が変わるときに 800 ms のクロスフェードでつなぐ。
 * - 他のアプリが音楽を流している間（[AudioManager.isMusicActive]）は始めない。音声フォーカスを失ったら止める。
 * - 画面が裏に回ったら止める（[stop]）。BGM / 効果音の音量と ON/OFF は別の設定（BGM は既定 ON・60%）。
 * - 自分の音楽ファイルを、全体用か場面ごとに指定できる（filesDir/bgm/ にコピーして使う）。
 * 失敗しても画面操作は止めない。すべてメインスレッドから呼ぶ。
 */
object Bgm {
    private const val PREFS = "crownfall_ui_prefs"
    private const val KEY_ENABLED = "bgm_enabled"
    private const val KEY_VOLUME = "bgm_volume"
    private const val KEY_CUSTOM = "bgm_custom_"
    const val DEFAULT_VOLUME = 60
    const val FADE_MS = 800L
    private const val STEP_MS = 40L
    private const val DIR = "bgm"
    private const val GLOBAL = "all"
    private const val COPY_BUFFER = 64 * 1024
    private const val SPACE_MARGIN = 8L * 1024 * 1024

    private class Slot(val player: MediaPlayer, val key: String) {
        var level = 0f
    }

    private val handler = Handler(Looper.getMainLooper())
    private var current: Slot? = null
    private var focusRequest: AudioFocusRequest? = null
    private var wantedScene: BgmScene? = null
    private var inForeground = false
    private var fadeTicker: Runnable? = null

    /** フェードアウト中の曲。途中で切り替わっても取りこぼさず、cancelFade / stop でまとめて解放する。 */
    private val fading = mutableListOf<Slot>()
    private var externalMusic = false
    private var ringerReceiver: BroadcastReceiver? = null

    const val MAX_IMPORT_BYTES = 30L * 1024 * 1024

    /** 取り込みの結果。 */
    enum class ImportResult { OK, TOO_BIG, NO_SPACE, FAILED }

    // ---- 設定 ----
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun volume(context: Context): Int = prefs(context).getInt(KEY_VOLUME, DEFAULT_VOLUME).coerceIn(0, 100)

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) apply(context) else fadeOutAndStop(context)
    }

    fun setVolume(
        context: Context,
        percent: Int,
    ) {
        prefs(context).edit().putInt(KEY_VOLUME, percent.coerceIn(0, 100)).apply()
        // 0 なら止めてフォーカスも手放す。上げ直したら（止まっていれば）鳴らし始める
        apply(context)
        current?.let { setLevel(context, it, it.level) }
    }

    /** 自分の曲を使っているときのファイル名（無ければ null）。[scene] が null なら全体用。 */
    fun customName(
        context: Context,
        scene: BgmScene?,
    ): String? = prefs(context).getString(KEY_CUSTOM + (scene?.name ?: GLOBAL), null)

    /**
     * 自分の曲を [uri]（ACTION_OPEN_DOCUMENT の結果）から filesDir/bgm/ にコピーして登録する。
     * IO スレッドで読む（呼び出し側は coroutine から）。30MB まで・空き容量を確認・一時ファイルに書いてから置き換える。
     * キャンセルされたら一時ファイルを消して CancellationException を投げる。[onProgress] は読んだバイト数。
     */
    suspend fun importCustom(
        context: Context,
        scene: BgmScene?,
        uri: Uri,
        displayName: String,
        onProgress: (Long) -> Unit = {},
    ): ImportResult =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val key = scene?.name ?: GLOBAL
            val tmp = File(dir, "$key.tmp")
            val target = File(dir, "$key.dat")
            try {
                val size =
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                if (size > MAX_IMPORT_BYTES) return@withContext ImportResult.TOO_BIG
                if (size > 0 && dir.usableSpace < size + SPACE_MARGIN) return@withContext ImportResult.NO_SPACE
                val input = context.contentResolver.openInputStream(uri) ?: return@withContext ImportResult.FAILED
                input.use { src ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(COPY_BUFFER)
                        var total = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = src.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_IMPORT_BYTES) return@withContext ImportResult.TOO_BIG
                            out.write(buf, 0, n)
                            onProgress(total)
                        }
                    }
                }
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                }
                target.setLastModified(System.currentTimeMillis())
                prefs(context).edit().putString(KEY_CUSTOM + key, displayName).apply()
                withContext(Dispatchers.Main) { apply(context) }
                ImportResult.OK
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ImportResult.FAILED
            } finally {
                tmp.delete()
            }
        }

    /** 自分の曲の登録を外し、元の曲に戻す。 */
    fun clearCustom(
        context: Context,
        scene: BgmScene?,
    ) {
        val key = scene?.name ?: GLOBAL
        File(File(context.filesDir, DIR), "$key.dat").delete()
        prefs(context).edit().remove(KEY_CUSTOM + key).apply()
        apply(context)
    }

    // ---- 再生 ----

    /** 場面が変わった。前景のあいだだけ鳴らす。 */
    fun setScene(
        context: Context,
        scene: BgmScene,
    ) {
        wantedScene = scene
        apply(context)
    }

    /** アプリが前景に来た / 裏に回った。裏に回ったら止める。 */
    fun setForeground(
        context: Context,
        foreground: Boolean,
    ) {
        inForeground = foreground
        if (foreground) {
            // 他のアプリの音楽が鳴っているかは、自分の音を出す前のこの一度だけ確かめる（自分の再生を誤検出しない）
            if (current == null) externalMusic = audioManager(context).isMusicActive
            watchRinger(context)
            apply(context)
        } else {
            unwatchRinger(context)
            stop(context)
        }
    }

    private fun watchRinger(context: Context) {
        if (ringerReceiver != null) return
        val app = context.applicationContext
        val r =
            object : BroadcastReceiver() {
                override fun onReceive(
                    c: Context?,
                    intent: Intent?,
                ) {
                    apply(app)
                }
            }
        app.registerReceiver(r, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION))
        ringerReceiver = r
    }

    private fun unwatchRinger(context: Context) {
        val r = ringerReceiver ?: return
        ringerReceiver = null
        try {
            context.applicationContext.unregisterReceiver(r)
        } catch (e: IllegalArgumentException) {
            // 登録されていない
        }
    }

    /** すぐ止める（フォーカスも手放す）。 */
    fun stop(context: Context) {
        cancelFade()
        current?.let { release(it) }
        current = null
        abandonFocus(context)
    }

    private fun apply(context: Context) {
        val scene = wantedScene
        if (scene == null || !inForeground || !isEnabled(context) || volume(context) == 0 || silentRinger(context)) {
            if (current != null) fadeOutAndStop(context)
            return
        }
        val key = sourceKey(context, scene)
        if (current?.key == key) return
        // 他のアプリが音楽を流しているときは割り込まない（前景に来た時点の確認。自分の再生中は見ない）
        if (current == null && externalMusic) return
        var next = open(context, scene, key)
        if (next == null && key.startsWith("custom")) {
            // 自分の曲が開けない。標準の曲に戻して、読み込めなかったことを伝える
            Toast.makeText(context.applicationContext, R.string.settings_bgm_import_failed, Toast.LENGTH_SHORT).show()
            next = open(context, scene, "raw:${scene.name}")
        }
        if (next == null) return
        // 音声フォーカスは、曲が開けてから取る
        if (!requestFocus(context)) {
            release(next)
            return
        }
        val old = current
        current = next
        next.player.start()
        crossfade(context, old, next)
    }

    private fun sourceKey(
        context: Context,
        scene: BgmScene,
    ): String =
        when {
            customName(context, scene) != null ->
                "custom:${scene.name}:${customName(context, scene)}:${customFile(context, scene)?.lastModified()}"
            customName(context, null) != null ->
                "custom:all:${customName(context, null)}:${customFile(context, null)?.lastModified()}"
            else -> "raw:${scene.name}"
        }

    private fun open(
        context: Context,
        scene: BgmScene,
        key: String,
    ): Slot? {
        var mp: MediaPlayer? = null
        return try {
            mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            val custom = customFile(context, scene)
            if (key.startsWith("custom")) {
                if (custom == null) error("no custom file")
                mp.setDataSource(custom.absolutePath)
            } else {
                val afd: AssetFileDescriptor = context.resources.openRawResourceFd(scene.res)
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
            }
            mp.isLooping = true
            val player = mp
            player.setOnErrorListener { p, _, _ ->
                handler.post { if (current?.player === p) stop(context) }
                true
            }
            player.prepare()
            val slot = Slot(player, key)
            setLevel(context, slot, 0f)
            slot
        } catch (e: Exception) {
            // prepare に失敗したプレーヤーは解放する
            mp?.let {
                try {
                    it.release()
                } catch (ignored: Exception) {
                    // 解放済み
                }
            }
            null
        }
    }

    private fun customFile(
        context: Context,
        scene: BgmScene?,
    ): File? {
        val dir = File(context.filesDir, DIR)
        val perScene = File(dir, "${scene?.name}.dat")
        if (scene != null && customName(context, scene) != null && perScene.exists()) return perScene
        val all = File(dir, "$GLOBAL.dat")
        return if (customName(context, null) != null && all.exists()) all else null
    }

    private fun setLevel(
        context: Context,
        slot: Slot,
        level: Float,
    ) {
        slot.level = level.coerceIn(0f, 1f)
        val v = slot.level * volume(context) / 100f
        try {
            slot.player.setVolume(v, v)
        } catch (e: Exception) {
            // 解放済み
        }
    }

    private fun crossfade(
        context: Context,
        old: Slot?,
        next: Slot,
    ) {
        cancelFade()
        old?.let { fading.add(it) }
        val steps = (FADE_MS / STEP_MS).toInt()
        var i = 0
        val ticker =
            object : Runnable {
                override fun run() {
                    i++
                    val u = i.toFloat() / steps
                    setLevel(context, next, u)
                    old?.let { setLevel(context, it, 1f - u) }
                    if (i >= steps) {
                        releaseFading()
                        fadeTicker = null
                    } else {
                        handler.postDelayed(this, STEP_MS)
                    }
                }
            }
        fadeTicker = ticker
        handler.post(ticker)
    }

    private fun fadeOutAndStop(context: Context) {
        val slot = current ?: return
        cancelFade()
        current = null
        fading.add(slot)
        val steps = (FADE_MS / STEP_MS).toInt()
        var i = 0
        val start = slot.level
        val ticker =
            object : Runnable {
                override fun run() {
                    i++
                    setLevel(context, slot, start * (1f - i.toFloat() / steps))
                    if (i >= steps) {
                        releaseFading()
                        abandonFocus(context)
                        fadeTicker = null
                    } else {
                        handler.postDelayed(this, STEP_MS)
                    }
                }
            }
        fadeTicker = ticker
        handler.post(ticker)
    }

    /** フェードを打ち切る。フェードアウト中だった曲は、取りこぼさずここで解放する。 */
    private fun cancelFade() {
        fadeTicker?.let { handler.removeCallbacks(it) }
        fadeTicker = null
        releaseFading()
    }

    private fun releaseFading() {
        fading.forEach { release(it) }
        fading.clear()
    }

    private fun release(slot: Slot) {
        try {
            slot.player.stop()
        } catch (e: Exception) {
            // 再生前
        }
        try {
            slot.player.release()
        } catch (e: Exception) {
            // 解放済み
        }
    }

    private fun requestFocus(context: Context): Boolean {
        if (focusRequest != null) return true
        val audio = audioManager(context)
        val req =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setOnAudioFocusChangeListener { change ->
                    // 通話・他の音楽アプリなどにフォーカスを奪われたら止める（戻っても勝手には再開しない）
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        handler.post { stop(context) }
                    }
                }
                .build()
        val ok = audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (ok) focusRequest = req
        return ok
    }

    private fun abandonFocus(context: Context) {
        focusRequest?.let { audioManager(context).abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /** BGM はマナー（バイブ）でも鳴らし、サイレントでだけ止める。 */
    private fun silentRinger(context: Context): Boolean =
        audioManager(context).ringerMode == AudioManager.RINGER_MODE_SILENT

    private fun audioManager(context: Context): AudioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
