package tech.classisland.mobile.alert

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import tech.classisland.mobile.engine.ScheduleEngine
import tech.classisland.mobile.engine.Store
import tech.classisland.mobile.model.Profile
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

object AlertScheduler {
    const val EXTRA_KIND = "kind"
    const val EXTRA_DATE = "date"
    const val EXTRA_LESSON = "lesson"
    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXT = "text"
    const val KIND_RESCHEDULE = 0
    const val KIND_PREP = 1
    const val KIND_ON = 2
    const val KIND_OFF = 3

    private fun intent(
        ctx: Context, kind: Int, title: String, text: String,
        date: LocalDate? = null, lesson: Int = -1
    ): Intent =
        Intent(ctx, AlertReceiver::class.java).apply {
            putExtra(EXTRA_KIND, kind)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_TEXT, text)
            if (date != null) putExtra(EXTRA_DATE, date.toString())
            putExtra(EXTRA_LESSON, lesson)
        }

    private fun requestCode(date: LocalDate, lessonIndex: Int, kind: Int): Int {
        // 日期 + 节次 + 类型 → 稳定且不冲突的整数
        val day = date.toEpochDay().toInt()
        return ((day and 0x000FFFFF) shl 8) or ((lessonIndex and 0x1F) shl 3) or kind
    }

    private fun setExact(am: AlarmManager, time: LocalDateTime, pi: PendingIntent) {
        val millis = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }

    /** 安排今天与明天的全部提醒，并挂一个每日 6:00 的重排闹钟 */
    fun scheduleAll(context: Context) {
        if (!Store.alertsEnabled) return
        val profile: Profile = Store.loadProfile() ?: return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val prepMin = Store.prepMinutes.toLong()
        val firstWeek = Store.firstWeekDate

        for (dayOffset in 0..1) {
            val date = LocalDate.now().plusDays(dayOffset.toLong())
            val wi = ScheduleEngine.weekIndex(date, firstWeek)
            val plan = ScheduleEngine.effectivePlan(
                profile, date, wi, Store.tempPlan(date)
            ) ?: continue
            val layout = profile.layouts[plan.layoutId] ?: continue
            val lessons = layout.items.filter { it.isClass }

            lessons.forEachIndexed { i, item ->
                val idx = i + 1
                val subject = profile.subjectName(plan.classes.getOrNull(i))
                val base = date.atTime(item.start)

                if (prepMin > 0) {
                    val t = base.minusMinutes(prepMin)
                    if (t.isAfter(LocalDateTime.now())) {
                        val pi = PendingIntent.getBroadcast(
                            context, requestCode(date, idx, KIND_PREP),
                            intent(context, KIND_PREP, "预备铃", "还有 $prepMin 分钟上$subject（第${idx}节）", date, idx),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        setExact(am, t, pi)
                    }
                }
                run {
                    val t = base
                    if (t.isAfter(LocalDateTime.now())) {
                        val pi = PendingIntent.getBroadcast(
                            context, requestCode(date, idx, KIND_ON),
                            intent(context, KIND_ON, "上课", "第${idx}节：$subject", date, idx),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        setExact(am, t, pi)
                    }
                }
                run {
                    val t = date.atTime(item.end)
                    if (t.isAfter(LocalDateTime.now())) {
                        val pi = PendingIntent.getBroadcast(
                            context, requestCode(date, idx, KIND_OFF),
                            intent(context, KIND_OFF, "下课", "第${idx}节 $subject 已下课", date, idx),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        setExact(am, t, pi)
                    }
                }
            }
        }

        // 每日 6:05 兜底重排
        val six = LocalDate.now().plusDays(1).atTime(6, 5)
        val pi = PendingIntent.getBroadcast(
            context, 999_000,
            intent(context, KIND_RESCHEDULE, "", ""),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        setExact(am, six, pi)
    }

    fun cancelAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // 覆盖未来 14 天可能已排的 requestCode
        val today = LocalDate.now()
        for (d in 0..14) {
            val date = today.plusDays(d.toLong())
            for (lesson in 1..16) {
                for (kind in intArrayOf(KIND_PREP, KIND_ON, KIND_OFF)) {
                    val pi = PendingIntent.getBroadcast(
                        context, requestCode(date, lesson, kind),
                        intent(context, kind, "", ""),
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                    )
                    if (pi != null) { am.cancel(pi); pi.cancel() }
                }
            }
        }
        PendingIntent.getBroadcast(
            context, 999_000, intent(context, KIND_RESCHEDULE, "", ""),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )?.let { am.cancel(it); it.cancel() }
    }
}
