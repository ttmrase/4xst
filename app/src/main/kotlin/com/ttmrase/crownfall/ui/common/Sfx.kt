package com.ttmrase.crownfall.ui.common

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RawRes
import com.ttmrase.crownfall.R

/** 効果音の種類。素材は tools/sfxgen が合成した WAV（res/raw）。 */
enum class SfxId(
    @RawRes val res: Int,
) {
    BUTTON(R.raw.sfx_button),
    COIN(R.raw.sfx_coin),
    FANFARE(R.raw.sfx_fanfare),
    CHARGE(R.raw.sfx_charge),
    FLIP(R.raw.sfx_flip),
    RARE(R.raw.sfx_rare),
    SSR(R.raw.sfx_ssr),
    ALARM(R.raw.sfx_alarm),
    VIP(R.raw.sfx_vip),
    CHEST(R.raw.sfx_chest),
}

/**
 * 効果音とバイブの再生。設定でミュート可能（既定は ON）、端末がマナー/サイレントのときは鳴らさない。
 * SoundPool は最初の再生時に一度だけ作る。再生に失敗しても画面操作は止めない。
 */
object Sfx {
    private const val PREFS = "crownfall_ui_prefs"
    private const val KEY_ENABLED = "sfx_enabled"
    private const val MAX_STREAMS = 6

    private var pool: SoundPool? = null
    private val loaded = mutableMapOf<SfxId, Int>()

    /** 効果音が有効か。 */
    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    /** 効果音の ON/OFF を保存する。 */
    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** [id] を鳴らす。ミュート中・マナー中は何もしない。 */
    fun play(
        context: Context,
        id: SfxId,
    ) {
        val app = context.applicationContext
        if (!isEnabled(app) || ringerMode(app) != AudioManager.RINGER_MODE_NORMAL) return
        try {
            val p = pool ?: createPool().also { pool = it }
            val sample = loaded.getOrPut(id) { p.load(app, id.res, 1) }
            p.play(sample, 1f, 1f, 1, 0, 1f)
        } catch (e: Exception) {
            // 音が出せなくてもゲームは続ける
        }
    }

    /** 短い振動。サイレント時は震わせない（マナーモード＝振動のみは許可）。 */
    fun vibrate(
        context: Context,
        millis: Long,
    ) {
        val app = context.applicationContext
        if (ringerMode(app) == AudioManager.RINGER_MODE_SILENT) return
        try {
            val vibrator =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    app.getSystemService(VibratorManager::class.java)?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
            vibrator?.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            // 振動できなくても続ける
        }
    }

    private fun ringerMode(context: Context): Int =
        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.ringerMode
            ?: AudioManager.RINGER_MODE_NORMAL

    private fun createPool(): SoundPool =
        SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
}
