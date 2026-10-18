package tech.classisland.mobile.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tech.classisland.mobile.engine.Store

class AlertReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 每次唤醒都重新排程，保证后续提醒不断档
        AlertScheduler.scheduleAll(context)

        val kind = intent.getIntExtra(AlertScheduler.EXTRA_KIND, AlertScheduler.KIND_RESCHEDULE)
        if (kind == AlertScheduler.KIND_RESCHEDULE) return

        val title = intent.getStringExtra(AlertScheduler.EXTRA_TITLE) ?: "提醒"
        val text = intent.getStringExtra(AlertScheduler.EXTRA_TEXT) ?: ""

        // 与悬浮窗的兜底提醒共用去重表：同一节课只发一次
        val date = intent.getStringExtra(AlertScheduler.EXTRA_DATE)
        val lesson = intent.getIntExtra(AlertScheduler.EXTRA_LESSON, -1)
        if (date != null && lesson >= 0) {
            val label = when (kind) {
                AlertScheduler.KIND_PREP -> "prep"
                AlertScheduler.KIND_ON -> "on"
                AlertScheduler.KIND_OFF -> "off"
                else -> "other"
            }
            if (!Store.tryFireAlert("$date|$lesson|$label")) return
        }
        AlertNotifier.notify(context, title, text)
    }
}
