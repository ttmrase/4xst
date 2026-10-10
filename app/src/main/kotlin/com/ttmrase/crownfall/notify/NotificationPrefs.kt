package com.ttmrase.crownfall.notify

import android.content.Context
import androidx.core.content.edit
import com.ttmrase.crownfall.core.notify.NoticeChannel

/**
 * 通知の端末側の設定（チャンネルごとの ON/OFF と、権限を一度尋ねたか）。ゲームのセーブには入れない。
 * オファー通知は、明示的に選ばれていなければ「頻度が低」のとき OFF、それ以外は ON。
 */
class NotificationPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** [channel] が有効か。[lowFrequency] はオファー頻度が LOW かどうか（オファー通知の既定値に使う）。 */
    fun enabled(
        channel: NoticeChannel,
        lowFrequency: Boolean,
    ): Boolean {
        val key = key(channel)
        if (prefs.contains(key)) return prefs.getBoolean(key, true)
        return !(channel == NoticeChannel.OFFER && lowFrequency)
    }

    fun set(
        channel: NoticeChannel,
        value: Boolean,
    ) = prefs.edit { putBoolean(key(channel), value) }

    /** 通知の権限をすでに尋ねたか（拒否されても二度は尋ねない）。 */
    var permissionAsked: Boolean
        get() = prefs.getBoolean(ASKED, false)
        set(value) = prefs.edit { putBoolean(ASKED, value) }

    private fun key(channel: NoticeChannel) = "channel_" + channel.name.lowercase()

    private companion object {
        const val FILE = "notify_prefs"
        const val ASKED = "permission_asked"
    }
}
