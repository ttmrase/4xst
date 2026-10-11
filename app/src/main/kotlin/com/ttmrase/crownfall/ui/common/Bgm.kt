package com.ttmrase.crownfall.ui.common

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.RawRes
import com.ttmrase.crownfall.R
import java.io.File

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

    private class Slot(val player: MediaPlayer, val key: String) {
        var level = 0f
    }

    private val handler = Handler(Looper.getMainLooper())
    private var current: Slot? = null
    private var focusRequest: AudioFocusRequest? = null
    private var wantedScene: BgmScene? = null
    private var inForeground = false
    private var fadeTicker: Runnable? = null

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
        current?.let { setLevel(context, it, it.level) }
    }

    /** 自分の曲を使っているときのファイル名（無ければ null）。[scene] が null なら全体用。 */
    fun customName(
        context: Context,
        scene: BgmScene?,
    ): String? = prefs(context).getString(KEY_CUSTOM + (scene?.name ?: GLOBAL), null)

    /** 自分の曲を [uri]（ACTION_OPEN_DOCUMENT の結果）から filesDir/bgm/ にコピーして登録する。成功なら true。 */
    fun importCustom(
        context: Context,
        scene: BgmScene?,
        uri: Uri,
        displayName: String,
    ): Boolean =
        try {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val key = scene?.name ?: GLOBAL
            val target = File(dir, "$key.dat")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: error("no stream")
            prefs(context).edit().putString(KEY_CUSTOM + key, displayName).apply()
            apply(context)
            true
        } catch (e: Exception) {
            false
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
        if (foreground) apply(context) else stop(context)
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
        val audio = audioManager(context)
        // 他のアプリが音楽を流しているときは割り込まない（自分の曲を流している最中は対象外）
        if (current == null && audio.isMusicActive) return
        if (!requestFocus(context)) return
        val next = open(context, scene, key) ?: return
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
            customName(context, scene) != null -> "custom:${scene.name}:${customName(context, scene)}"
            customName(context, null) != null -> "custom:all:${customName(context, null)}"
            else -> "raw:${scene.name}"
        }

    private fun open(
        context: Context,
        scene: BgmScene,
        key: String,
    ): Slot? =
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            val custom = customFile(context, scene)
            if (custom != null && key.startsWith("custom")) {
                mp.setDataSource(custom.absolutePath)
            } else {
                val afd: AssetFileDescriptor = context.resources.openRawResourceFd(scene.res)
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
            }
            mp.isLooping = true
            mp.setOnErrorListener { p, _, _ ->
                handler.post { if (current?.player === p) stop(context) }
                true
            }
            mp.prepare()
            val slot = Slot(mp, key)
            setLevel(context, slot, 0f)
            slot
        } catch (e: Exception) {
            null
        }

    private fun customFile(
        context: Context,
        scene: BgmScene,
    ): File? {
        val dir = File(context.filesDir, DIR)
        val perScene = File(dir, "${scene.name}.dat")
        if (customName(context, scene) != null && perScene.exists()) return perScene
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
                        old?.let { release(it) }
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
        val steps = (FADE_MS / STEP_MS).toInt()
        var i = 0
        val start = slot.level
        val ticker =
            object : Runnable {
                override fun run() {
                    i++
                    setLevel(context, slot, start * (1f - i.toFloat() / steps))
                    if (i >= steps) {
                        release(slot)
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

    private fun cancelFade() {
        fadeTicker?.let { handler.removeCallbacks(it) }
        fadeTicker = null
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

    private fun silentRinger(context: Context): Boolean =
        audioManager(context).ringerMode != AudioManager.RINGER_MODE_NORMAL

    private fun audioManager(context: Context): AudioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
