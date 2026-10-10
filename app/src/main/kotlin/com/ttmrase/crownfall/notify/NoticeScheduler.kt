package com.ttmrase.crownfall.notify

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.OfferFrequency
import com.ttmrase.crownfall.core.notify.NoticeCategory
import com.ttmrase.crownfall.core.notify.NoticeType
import com.ttmrase.crownfall.core.notify.NotificationPlanner
import com.ttmrase.crownfall.core.notify.NotificationPolicy
import com.ttmrase.crownfall.core.notify.PlannedNotice
import java.util.concurrent.TimeUnit

/**
 * 通知の配送（T9.2b）。onStop のときに :core の先読みで計画を作り、強度・おやすみ時間・上限で選別して予約する。
 * 時刻の精度が要るもの（襲撃の各段階・オファーの終了）は AlarmManager の exact alarm（許可が無ければ inexact）、
 * それ以外は WorkManager。計画は SharedPreferences にも保存し、端末の再起動後に BootReceiver が再予約する。
 * 前景に戻る（onStart）ときはすべて取り消す。
 */
class NoticeScheduler(context: Context, private val prefs: NotificationPrefs) {
    private val app = context.applicationContext

    /** [state] の時点の計画を予約する（以前の予約は消す）。 */
    fun schedule(
        state: GameState,
        data: GameData,
    ) {
        cancelAll(clearNotifications = false)
        if (!canPost(app)) return
        val low = state.shop.settings.frequency == OfferFrequency.LOW
        val settings = prefs.toCore(state.settings.utcOffsetSec, low)
        val plan = NotificationPolicy.apply(NotificationPlanner.lookahead(state, data), settings, data.notifyRules)
        savePlan(plan)
        arm(app, plan, System.currentTimeMillis())
    }

    /** 前景に戻ったので、予約をすべて取り消す（表示中の通知も消す）。 */
    fun cancelAll(clearNotifications: Boolean = true) {
        cancelArmed(app, loadPlan())
        WorkManager.getInstance(app).cancelAllWorkByTag(TAG)
        savePlan(emptyList())
        if (clearNotifications) androidx.core.app.NotificationManagerCompat.from(app).cancelAll()
    }

    private fun savePlan(plan: List<PlannedNotice>) = savePlan(app, plan)

    private fun loadPlan(): List<PlannedNotice> = loadPlan(app)

    companion object {
        const val TAG = "crownfall-notice"
        const val KEY_TYPE = "type"
        const val KEY_COUNT = "count"
        const val KEY_PARAM = "param"
        private const val STORE = "notice_plan"
        private const val PLAN = "plan"
        private const val MILLIS = 1000L
        private const val SEP = "|"
        private val OFFER_ENDINGS = setOf(NoticeType.OFFER_EXPIRING, NoticeType.OFFER_LAST_CALL)

        /** 通知を出せるか（Android 13 以上は権限が要る）。 */
        fun canPost(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        /** exact alarm を使えるか（Android 12 以上は許可が要る。13 以上は USE_EXACT_ALARM で自動）。 */
        fun canScheduleExact(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

        /** 時刻の精度が要る通知か。 */
        internal fun isPrecise(type: NoticeType): Boolean =
            type.category == NoticeCategory.RAID || type in OFFER_ENDINGS

        /** 計画のうち未来のものを予約する（再起動後の復元にも使う）。 */
        fun arm(
            context: Context,
            plan: List<PlannedNotice>,
            nowMs: Long,
        ) {
            val am = context.getSystemService(AlarmManager::class.java)
            val wm = WorkManager.getInstance(context)
            val exact = canScheduleExact(context)
            for (n in plan) {
                val atMs = n.epochSec * MILLIS
                if (atMs <= nowMs) continue
                if (isPrecise(n.type) && am != null) {
                    val pi = alarmIntent(context, n, PendingIntent.FLAG_UPDATE_CURRENT) ?: continue
                    try {
                        if (exact) {
                            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
                        } else {
                            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
                        }
                    } catch (e: SecurityException) {
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
                    }
                } else {
                    val request =
                        OneTimeWorkRequestBuilder<NoticeWorker>()
                            .setInitialDelay(atMs - nowMs, TimeUnit.MILLISECONDS)
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
        }

        private fun cancelArmed(
            context: Context,
            plan: List<PlannedNotice>,
        ) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            for (n in plan) {
                if (!isPrecise(n.type)) continue
                alarmIntent(context, n, PendingIntent.FLAG_NO_CREATE)?.let {
                    am.cancel(it)
                    it.cancel()
                }
            }
        }

        private fun alarmIntent(
            context: Context,
            n: PlannedNotice,
            flag: Int,
        ): PendingIntent? {
            val intent =
                Intent(context, NoticeReceiver::class.java)
                    .setAction("com.ttmrase.crownfall.NOTICE.${n.epochSec}.${n.type.name}.${n.param}")
                    .putExtra(KEY_TYPE, n.type.name)
                    .putExtra(KEY_COUNT, n.count)
                    .putExtra(KEY_PARAM, n.param)
            return PendingIntent.getBroadcast(context, 0, intent, flag or PendingIntent.FLAG_IMMUTABLE)
        }

        internal fun savePlan(
            context: Context,
            plan: List<PlannedNotice>,
        ) {
            val text =
                plan.joinToString("\n") { n -> listOf(n.epochSec, n.type.name, n.count, n.param).joinToString(SEP) }
            context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit { putString(PLAN, text) }
        }

        internal fun loadPlan(context: Context): List<PlannedNotice> {
            val text = context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString(PLAN, "").orEmpty()
            return text.lineSequence().mapNotNull { line ->
                val f = line.split(SEP)
                val type = NoticeType.entries.firstOrNull { it.name == f.getOrNull(1) } ?: return@mapNotNull null
                val at = f.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                val count = f.getOrNull(2)?.toIntOrNull() ?: 1
                PlannedNotice(at, type, type.channel, count, f.getOrNull(3)?.toIntOrNull() ?: 0)
            }.toList()
        }

        /** 通知チャンネルを作る。 */
        fun ensureChannels(context: Context) = NoticePoster.ensureChannels(context)
    }
}

/** 予定の時刻に通知を 1 件出す（WorkManager 経由）。 */
class NoticeWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val name = inputData.getString(NoticeScheduler.KEY_TYPE)
        val type = NoticeType.entries.firstOrNull { it.name == name } ?: return Result.success()
        val count = inputData.getInt(NoticeScheduler.KEY_COUNT, 1)
        val param = inputData.getInt(NoticeScheduler.KEY_PARAM, 0)
        NoticePoster.post(applicationContext, PlannedNotice(0, type, type.channel, count, param))
        return Result.success()
    }
}
