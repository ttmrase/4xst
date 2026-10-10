package com.ttmrase.crownfall.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ttmrase.crownfall.MainActivity
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.notify.NoticeChannel
import com.ttmrase.crownfall.core.notify.NoticeType
import com.ttmrase.crownfall.core.notify.PlannedNotice
import com.ttmrase.crownfall.navigation.Screen
import com.ttmrase.crownfall.ui.nameRes

/** 通知の作成と表示（配送の仕方に依らない）。チャンネルは襲撃 / 同盟 / オファー / タイマー / お知らせ。 */
object NoticePoster {
    const val EXTRA_ROUTE = "route"
    private const val ID_STRIDE = 100
    private const val SUMMARY_BASE = 9_000
    private val RAID_VIBRATION = longArrayOf(0, 600, 200, 600, 200, 900)
    private val SOFT_VIBRATION = longArrayOf(0, 200, 150, 200)
    private val COMEBACK_TEXTS =
        listOf(
            R.string.notify_comeback_0,
            R.string.notify_comeback_1,
            R.string.notify_comeback_2,
            R.string.notify_comeback_3,
        )

    fun channelId(channel: NoticeChannel) = "crownfall_" + channel.name.lowercase()

    private fun channelNameRes(c: NoticeChannel): Int =
        when (c) {
            NoticeChannel.RAID -> R.string.notify_channel_raid
            NoticeChannel.ALLIANCE -> R.string.notify_channel_alliance
            NoticeChannel.OFFER -> R.string.notify_channel_offer
            NoticeChannel.TIMER -> R.string.notify_channel_timer
            NoticeChannel.NOTICE -> R.string.notify_channel_notice
        }

    /** 通知チャンネルを作る（何度呼んでもよい）。襲撃は重要度 HIGH・強いバイブ・ロック画面に表示。 */
    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        NoticeChannel.entries.forEach { c ->
            val name = context.getString(channelNameRes(c))
            val ch =
                if (c == NoticeChannel.RAID) {
                    NotificationChannel(channelId(c), name, NotificationManager.IMPORTANCE_HIGH)
                        .apply {
                            enableVibration(true)
                            vibrationPattern = RAID_VIBRATION
                            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                            val attrs =
                                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), attrs)
                        }
                } else {
                    NotificationChannel(channelId(c), name, NotificationManager.IMPORTANCE_DEFAULT)
                        .apply {
                            enableVibration(c == NoticeChannel.ALLIANCE)
                            if (c == NoticeChannel.ALLIANCE) vibrationPattern = SOFT_VIBRATION
                        }
                }
            nm.createNotificationChannel(ch)
        }
    }

    /** 通知を 1 件出す。権限が無い・途中で取り消されたときは何もしない。 */
    fun post(
        context: Context,
        n: PlannedNotice,
    ) {
        if (!NoticeScheduler.canPost(context)) return
        ensureChannels(context)
        val text = text(context, n)
        val channel = channelId(n.type.channel)
        val open =
            PendingIntent.getActivity(
                context,
                n.type.ordinal,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_ROUTE, route(n.type)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val urgent = n.type.channel == NoticeChannel.RAID
        val built =
            NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_crown)
                .setContentTitle(title(context, n))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setGroup(channel)
                .setCategory(if (urgent) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
        val summary =
            NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_crown)
                .setContentTitle(context.getString(R.string.app_name))
                .setGroup(channel)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
        try {
            val nm = NotificationManagerCompat.from(context)

            @Suppress("MissingPermission")
            nm.notify(n.type.ordinal * ID_STRIDE + (n.param % ID_STRIDE), built)

            @Suppress("MissingPermission")
            nm.notify(SUMMARY_BASE + n.type.channel.ordinal, summary)
        } catch (e: SecurityException) {
            // 権限が途中で取り消された。何もしない
        }
    }

    /** タップで開く画面。襲撃は城内（結果はメール）、同盟は同盟、オファーはショップ、イベントはイベント画面。 */
    private fun route(type: NoticeType): String =
        when (type.channel) {
            NoticeChannel.ALLIANCE -> Screen.Alliance.route
            NoticeChannel.OFFER -> Screen.Shop.route
            NoticeChannel.NOTICE ->
                if (type == NoticeType.EVENT_ENDING || type == NoticeType.EVENT_DROPPED) {
                    Screen.Event.route
                } else {
                    Screen.City.route
                }
            NoticeChannel.RAID ->
                if (type == NoticeType.RAID_DEFENDED || type == NoticeType.RAID_DEFEATED) {
                    Screen.Mail.route
                } else {
                    Screen.City.route
                }
            NoticeChannel.TIMER -> Screen.City.route
        }

    private fun title(
        context: Context,
        n: PlannedNotice,
    ): String =
        when (n.type.channel) {
            NoticeChannel.RAID -> context.getString(R.string.notify_title_raid)
            NoticeChannel.OFFER -> context.getString(R.string.notify_title_offer)
            NoticeChannel.ALLIANCE -> context.getString(R.string.notify_title_alliance)
            else -> context.getString(R.string.app_name)
        }

    /** 通知の文面。2 件以上まとまっているときは件数を添える。 */
    internal fun text(
        context: Context,
        n: PlannedNotice,
    ): String {
        val res = textRes(n.type)
        val base =
            when (n.type) {
                NoticeType.STORAGE_FULL -> {
                    val type = ResourceType.entries.getOrNull(n.param)
                    context.getString(res, type?.let { context.getString(it.nameRes()) }.orEmpty())
                }
                NoticeType.EVENT_DROPPED -> context.getString(res, n.param)
                NoticeType.SUMMARY -> context.getString(res, n.count)
                NoticeType.COMEBACK -> context.getString(COMEBACK_TEXTS[n.param.coerceIn(0, COMEBACK_TEXTS.lastIndex)])
                else -> context.getString(res)
            }
        if (n.count <= 1 || n.type == NoticeType.SUMMARY) return base
        return base + context.getString(R.string.notify_count_suffix, n.count)
    }

    private fun textRes(type: NoticeType): Int =
        when (type) {
            NoticeType.BUILD_DONE -> R.string.notify_build_done
            NoticeType.RESEARCH_DONE -> R.string.notify_research_done
            NoticeType.TRAIN_DONE -> R.string.notify_train_done
            NoticeType.HEAL_DONE -> R.string.notify_heal_done
            NoticeType.MARCH_RETURN -> R.string.notify_march_return
            NoticeType.STORAGE_FULL -> R.string.notify_storage_full
            NoticeType.OFFER_EXPIRING -> R.string.notify_offer_expiring
            NoticeType.OFFER_LAST_CALL -> R.string.notify_offer_last_call
            NoticeType.RAID_WARNING -> R.string.notify_raid_warning
            NoticeType.RAID_ARRIVAL -> R.string.notify_raid_arrival
            NoticeType.RAID_SCOUTED -> R.string.notify_raid_scouted
            NoticeType.RAID_MARCHING -> R.string.notify_raid_marching
            NoticeType.RAID_1H -> R.string.notify_raid_1h
            NoticeType.RAID_15M -> R.string.notify_raid_15m
            NoticeType.RAID_STARTED -> R.string.notify_raid_started
            NoticeType.RAID_DEFENDED -> R.string.notify_raid_defended
            NoticeType.RAID_DEFEATED -> R.string.notify_raid_defeated
            NoticeType.RALLY_DEADLINE -> R.string.notify_rally_deadline
            NoticeType.REQUEST_EXPIRING -> R.string.notify_request_expiring
            NoticeType.ALLIANCE_HELP -> R.string.notify_alliance_help
            NoticeType.ALLIANCE_DM -> R.string.notify_alliance_dm
            NoticeType.EVENT_ENDING -> R.string.notify_event_ending
            NoticeType.EVENT_DROPPED -> R.string.notify_event_dropped
            NoticeType.DAILY_LEFT -> R.string.notify_daily_left
            NoticeType.LOGIN_BONUS -> R.string.notify_login_bonus
            NoticeType.FREE_WHEEL -> R.string.notify_free_wheel
            NoticeType.COMEBACK -> R.string.notify_comeback_0
            NoticeType.SUMMARY -> R.string.notify_summary
        }
}
