package com.ttmrase.crownfall.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ttmrase.crownfall.core.notify.NoticeType
import com.ttmrase.crownfall.core.notify.PlannedNotice

/** exact / inexact alarm で届いた通知を出す。 */
class NoticeReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val name = intent.getStringExtra(NoticeScheduler.KEY_TYPE)
        val type = NoticeType.entries.firstOrNull { it.name == name } ?: return
        val count = intent.getIntExtra(NoticeScheduler.KEY_COUNT, 1)
        val param = intent.getIntExtra(NoticeScheduler.KEY_PARAM, 0)
        NoticePoster.post(context, PlannedNotice(0, type, type.channel, count, param))
    }
}

/** 端末の再起動後に、保存した計画のうち未来のものを再予約する。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        NoticePoster.ensureChannels(context)
        NoticeScheduler.arm(context, NoticeScheduler.loadPlan(context), System.currentTimeMillis())
    }
}
