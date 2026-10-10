package com.ttmrase.crownfall.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.ttmrase.crownfall.MainActivity
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.OfferFrequency
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.notify.NoticeChannel
import com.ttmrase.crownfall.core.notify.NoticeType
import com.ttmrase.crownfall.core.notify.NotificationPlanner
import com.ttmrase.crownfall.core.notify.PlannedNotice
import com.ttmrase.crownfall.ui.nameRes
import java.util.concurrent.TimeUnit

/**
 * 通知のスケジュール（T7.3）。背景に入る（onStop）ときに確定している未来の時刻だけを WorkManager に登録し、
 * 前景に戻る（onStart）ときに全部取り消す。背景でシミュレーションは回さないので、未来の AI の発言は通知しない。
 */
class NoticeScheduler(context: Context, private val prefs: NotificationPrefs) {
    private val app = context.applicationContext

    /** [state] の時点で確定している通知を登録する（以前の登録は消す）。 */
    fun schedule(
        state: GameState,
        data: GameData,
    ) {
        val wm = WorkManager.getInstance(app)
        wm.cancelAllWorkByTag(TAG)
        if (!canPost(app)) return
        val low = state.shop.settings.frequency == OfferFrequency.LOW
        val nowMs = System.currentTimeMillis()
        NotificationPlanner.plan(state, data)
            .filter { prefs.enabled(it.channel, low) }
            .forEach { n ->
                val delay = n.epochSec * MILLIS - nowMs
                if (delay <= 0) return@forEach
                val request =
                    OneTimeWorkRequestBuilder<NoticeWorker>()
                        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                        .setInputData(
                            Data.Builder()
                                .putString(KEY_TYPE, n.type.name)
                                .putInt(KEY_COUNT, n.count)
                                .putInt(KEY_PARAM, n.param)
                                .build(),
                        )
                        .addTag(TAG)
                        .build()
                wm.enqueue(request)
            }
    }

    /** 前景に戻ったので、登録済みの通知をすべて取り消す。 */
    fun cancelAll() {
        WorkManager.getInstance(app).cancelAllWorkByTag(TAG)
        NotificationManagerCompat.from(app).cancelAll()
    }

    companion object {
        const val TAG = "crownfall-notice"
        const val KEY_TYPE = "type"
        const val KEY_COUNT = "count"
        const val KEY_PARAM = "param"
        private const val MILLIS = 1000L

        /** 通知を出せるか（Android 13 以上は権限が要る）。 */
        fun canPost(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        fun channelId(channel: NoticeChannel) = "crownfall_" + channel.name.lowercase()

        /** 通知チャンネルを作る（何度呼んでもよい）。 */
        fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            NoticeChannel.entries.forEach { c ->
                val name = context.getString(channelNameRes(c))
                val importance =
                    if (c == NoticeChannel.RAID) {
                        NotificationManager.IMPORTANCE_HIGH
                    } else {
                        NotificationManager.IMPORTANCE_DEFAULT
                    }
                nm.createNotificationChannel(NotificationChannel(channelId(c), name, importance))
            }
        }

        fun channelNameRes(c: NoticeChannel): Int =
            when (c) {
                NoticeChannel.TIMER -> R.string.notify_channel_timer
                NoticeChannel.OFFER -> R.string.notify_channel_offer
                NoticeChannel.ALLIANCE -> R.string.notify_channel_alliance
                NoticeChannel.RAID -> R.string.notify_channel_raid
            }
    }
}

/** 予定の時刻に通知を 1 件出す。 */
class NoticeWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val type =
            NoticeType.entries.firstOrNull { it.name == inputData.getString(NoticeScheduler.KEY_TYPE) }
                ?: return Result.success()
        val count = inputData.getInt(NoticeScheduler.KEY_COUNT, 1)
        val param = inputData.getInt(NoticeScheduler.KEY_PARAM, 0)
        if (!NoticeScheduler.canPost(applicationContext)) return Result.success()
        NoticeScheduler.ensureChannels(applicationContext)
        val notice = PlannedNotice(0, type, channelOf(type), count, param)
        post(applicationContext, notice)
        return Result.success()
    }

    private fun channelOf(type: NoticeType): NoticeChannel =
        when (type) {
            NoticeType.OFFER_EXPIRING -> NoticeChannel.OFFER
            NoticeType.RAID_WARNING, NoticeType.RAID_ARRIVAL -> NoticeChannel.RAID
            NoticeType.RALLY_DEADLINE, NoticeType.REQUEST_EXPIRING -> NoticeChannel.ALLIANCE
            else -> NoticeChannel.TIMER
        }

    private fun post(
        context: Context,
        n: PlannedNotice,
    ) {
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val resource = ResourceType.entries.getOrNull(n.param)?.let { context.getString(it.nameRes()) }
        val text = noticeText(context, n, resource)
        val built =
            NotificationCompat.Builder(context, NoticeScheduler.channelId(n.channel))
                .setSmallIcon(R.drawable.ic_stat_crown)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        // 権限は doWork の入口で確認済み
        try {
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(context).notify(n.type.ordinal * ID_STRIDE + n.param, built)
        } catch (e: SecurityException) {
            // 権限が途中で取り消された。何もしない
        }
    }

    private companion object {
        const val ID_STRIDE = 100
    }
}

/** 通知の文面。2 件以上まとまっているときは件数を添える。 */
internal fun noticeText(
    context: Context,
    n: PlannedNotice,
    resource: String?,
): String {
    val res =
        when (n.type) {
            NoticeType.BUILD_DONE -> R.string.notify_build_done
            NoticeType.RESEARCH_DONE -> R.string.notify_research_done
            NoticeType.TRAIN_DONE -> R.string.notify_train_done
            NoticeType.HEAL_DONE -> R.string.notify_heal_done
            NoticeType.MARCH_RETURN -> R.string.notify_march_return
            NoticeType.STORAGE_FULL -> R.string.notify_storage_full
            NoticeType.OFFER_EXPIRING -> R.string.notify_offer_expiring
            NoticeType.RAID_WARNING -> R.string.notify_raid_warning
            NoticeType.RAID_ARRIVAL -> R.string.notify_raid_arrival
            NoticeType.RALLY_DEADLINE -> R.string.notify_rally_deadline
            NoticeType.REQUEST_EXPIRING -> R.string.notify_request_expiring
        }
    val base =
        if (n.type == NoticeType.STORAGE_FULL) context.getString(res, resource.orEmpty()) else context.getString(res)
    return if (n.count > 1) base + context.getString(R.string.notify_count_suffix, n.count) else base
}
