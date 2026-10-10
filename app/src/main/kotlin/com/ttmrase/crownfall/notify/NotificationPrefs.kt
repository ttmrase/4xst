package com.ttmrase.crownfall.notify

import android.content.Context
import androidx.core.content.edit
import com.ttmrase.crownfall.core.notify.Intensity
import com.ttmrase.crownfall.core.notify.NoticeCategory
import com.ttmrase.crownfall.core.notify.NotifySettings

/**
 * 通知の端末側の設定（強度・カテゴリごとの ON/OFF・おやすみ時間・権限を尋ねたか）。ゲームのセーブには入れない。
 * 強度の既定は「量産型」。オファー通知は、明示的に選ばれていなければ「オファー頻度が低」のとき OFF。
 */
class NotificationPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var intensity: Intensity
        get() = Intensity.entries.firstOrNull { it.name == prefs.getString(INTENSITY, null) } ?: Intensity.MASS
        set(value) = prefs.edit { putString(INTENSITY, value.name) }

    /** [category] が有効か。[lowFrequency] はオファー頻度が LOW かどうか（オファー通知の既定値に使う）。 */
    fun enabled(
        category: NoticeCategory,
        lowFrequency: Boolean,
    ): Boolean {
        val key = key(category)
        if (prefs.contains(key)) return prefs.getBoolean(key, true)
        return !(category == NoticeCategory.OFFER && lowFrequency)
    }

    fun set(
        category: NoticeCategory,
        value: Boolean,
    ) = prefs.edit { putBoolean(key(category), value) }

    var quietEnabled: Boolean
        get() = prefs.getBoolean(QUIET, false)
        set(value) = prefs.edit { putBoolean(QUIET, value) }

    /** おやすみ時間の開始 / 終了（現地の時、0〜23）。 */
    var quietStartHour: Int
        get() = prefs.getInt(QUIET_START, DEFAULT_QUIET_START)
        set(value) = prefs.edit { putInt(QUIET_START, value.coerceIn(0, MAX_HOUR)) }

    var quietEndHour: Int
        get() = prefs.getInt(QUIET_END, DEFAULT_QUIET_END)
        set(value) = prefs.edit { putInt(QUIET_END, value.coerceIn(0, MAX_HOUR)) }

    /** 襲撃はおやすみ時間も通知する（既定 ON）。 */
    var raidThroughQuiet: Boolean
        get() = prefs.getBoolean(RAID_QUIET, true)
        set(value) = prefs.edit { putBoolean(RAID_QUIET, value) }

    /** 通知の権限をすでに尋ねたか（拒否されても二度は尋ねない）。 */
    var permissionAsked: Boolean
        get() = prefs.getBoolean(ASKED, false)
        set(value) = prefs.edit { putBoolean(ASKED, value) }

    /** :core の選別に渡す設定。 */
    fun toCore(
        utcOffsetSec: Int,
        lowFrequency: Boolean,
    ): NotifySettings =
        NotifySettings(
            intensity = intensity,
            disabled = NoticeCategory.entries.filter { !enabled(it, lowFrequency) }.toSet(),
            quietEnabled = quietEnabled,
            quietStartMinute = quietStartHour * MINUTES_PER_HOUR,
            quietEndMinute = quietEndHour * MINUTES_PER_HOUR,
            raidThroughQuiet = raidThroughQuiet,
            utcOffsetSec = utcOffsetSec,
        )

    private fun key(category: NoticeCategory) = "category_" + category.name.lowercase()

    private companion object {
        const val FILE = "notify_prefs"
        const val ASKED = "permission_asked"
        const val INTENSITY = "intensity"
        const val QUIET = "quiet_enabled"
        const val QUIET_START = "quiet_start_hour"
        const val QUIET_END = "quiet_end_hour"
        const val RAID_QUIET = "raid_through_quiet"
        const val DEFAULT_QUIET_START = 23
        const val DEFAULT_QUIET_END = 7
        const val MAX_HOUR = 23
        const val MINUTES_PER_HOUR = 60
    }
}
