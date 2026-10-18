package tech.classisland.mobile.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import tech.classisland.mobile.R
import tech.classisland.mobile.engine.Phase
import tech.classisland.mobile.engine.ScheduleEngine
import tech.classisland.mobile.engine.Store
import tech.classisland.mobile.ui.MainActivity
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/**
 * 桌面课表小组件（对应官方桌面版的课表组件）。
 *
 * - 每分钟更新一次，倒计时按分钟粒度显示，避免高频唤醒；
 * - 数据来源与 App 内完全一致（同一 ScheduleEngine / Store），切换档案即时生效；
 * - 点击组件直接打开 App。
 */
class ScheduleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Store 依赖 Application 初始化；进程被杀后重建时兜底再 init 一次
        runCatching { Store.init(context.applicationContext) }
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, buildViews(context, it)) }
        scheduleNextUpdate(context, appWidgetManager)
    }

    override fun onEnabled(context: Context) {
        runCatching { Store.init(context.applicationContext) }
        updateAll(context)
    }

    override fun onDisabled(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, REQ_TICK, tickIntent(context, IntArray(0)),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) { am.cancel(pi); pi.cancel() }
    }

    companion object {
        private const val REQ_TICK = 777
        private const val PERIOD_MIN = 1L

        private fun tickIntent(context: Context, ids: IntArray): Intent =
            Intent(context, ScheduleWidgetProvider::class.java)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)

        /** 刷新全部已添加的组件 */
        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, ScheduleWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            runCatching { Store.init(context.applicationContext) }
            ids.forEach { mgr.updateAppWidget(it, buildViews(context, it)) }
            scheduleNextUpdate(context, mgr)
        }

        /** 档案切换/编辑/导入后主动刷新 */
        fun refresh(context: Context) = updateAll(context)

        private fun scheduleNextUpdate(context: Context, mgr: AppWidgetManager) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, ScheduleWidgetProvider::class.java)
            )
            val pi = PendingIntent.getBroadcast(
                context, REQ_TICK, tickIntent(context, ids),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val millis = System.currentTimeMillis() + PERIOD_MIN * 60_000L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC, millis, pi)
            } else {
                am.set(AlarmManager.RTC, millis, pi)
            }
        }

        private fun fmt(d: Duration): String {
            val s = d.seconds.coerceAtLeast(0)
            val h = s / 3600
            val m = (s % 3600) / 60
            return if (h > 0) String.format("%d:%02d", h, m) else String.format("%02d", m)
        }

        fun buildViews(context: Context, @Suppress("UNUSED_PARAMETER") id: Int): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_schedule)

            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.widgetRoot, open)

            val profile = Store.loadProfile()
            if (profile == null) {
                v.setTextViewText(R.id.widgetPhase, "课表岛")
                v.setTextViewText(R.id.widgetSubject, "未导入档案")
                v.setTextViewText(R.id.widgetCount, "")
                v.setTextViewText(R.id.widgetNext, "点击打开 App 导入课表")
                v.setProgressBar(R.id.widgetProgress, 100, 0, false)
                return v
            }

            val now = LocalTime.now()
            val today = LocalDate.now()
            val st = ScheduleEngine.statusAt(
                profile, today, now, Store.firstWeekDate, Store.tempPlan(today)
            )

            v.setTextViewText(R.id.widgetPhase, when (st.phase) {
                Phase.IN_CLASS -> "上课中"
                Phase.BREAK -> "课间"
                Phase.BEFORE_SCHOOL -> "待上课"
                Phase.AFTER_SCHOOL -> "已放学"
                Phase.NO_CLASS_TODAY -> "今日无课"
            })

            v.setTextViewText(R.id.widgetSubject, when (st.phase) {
                Phase.IN_CLASS -> profile.subjectName(st.currentSubjectId)
                Phase.BREAK -> Store.breakLabel
                Phase.BEFORE_SCHOOL -> profile.subjectName(st.nextSubjectId)
                Phase.AFTER_SCHOOL -> "今日课程已结束"
                Phase.NO_CLASS_TODAY -> "休息一天"
            })

            val cur = st.currentItem
            val pct = if (cur != null) {
                val total = Duration.between(cur.start, cur.end).seconds
                val passed = Duration.between(cur.start, now).seconds
                if (total > 0) (passed * 100 / total).toInt().coerceIn(0, 100) else 0
            } else 0
            v.setProgressBar(R.id.widgetProgress, 100, pct, false)

            val countText = when (st.phase) {
                Phase.IN_CLASS -> fmt(Duration.between(now, st.currentItem!!.end))
                Phase.BREAK -> {
                    val ns = st.lessons.getOrNull(st.nextClassIndex - 1)?.second?.start
                    if (ns != null) fmt(Duration.between(now, ns)) else "--"
                }
                Phase.BEFORE_SCHOOL -> {
                    val f = st.lessons.firstOrNull()?.second?.start
                    if (f != null) fmt(Duration.between(now, f)) else "--"
                }
                else -> ""
            }
            v.setTextViewText(R.id.widgetCount, countText)

            v.setTextViewText(R.id.widgetNext, if (st.nextClassIndex > 0) {
                val ni = st.lessons.getOrNull(st.nextClassIndex - 1)?.second
                "第${st.nextClassIndex}节 ${profile.subjectName(st.nextSubjectId)} ${ni?.start ?: ""}"
            } else "今天的课程已全部结束")

            return v
        }
    }
}
