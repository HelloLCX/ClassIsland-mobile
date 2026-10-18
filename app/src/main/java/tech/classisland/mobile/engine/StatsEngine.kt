package tech.classisland.mobile.engine

import tech.classisland.mobile.model.Profile
import tech.classisland.mobile.model.TimeItem
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * 本周课时统计（对应官方「统计」面板）。
 *
 * 统计口径与官方一致：
 *  - 每节课按实际时长折算课时（默认 45 分钟 = 1 课时）；
 *  - 单双周（WeekCountDiv）只在对应周次计入，避免重复统计；
 *  - 临时调课（override）按当天实际生效的课表统计，与今日页显示保持一致。
 */
object StatsEngine {

    /** 某科目在本周的节数与课时 */
    data class SubjectStat(
        val subjectId: String?,
        val name: String,
        val lessons: Int,
        val classHours: Double
    )

    data class DayStat(val date: LocalDate, val lessons: Int, val classHours: Double)

    data class WeekStats(
        val monday: LocalDate,
        val weekIndex: Int,
        val days: List<DayStat>,
        val subjects: List<SubjectStat>,
        val totalLessons: Int,
        val totalClassHours: Double,
        val totalMinutes: Int
    ) {
        val hasData: Boolean get() = totalLessons > 0
    }

    private class Acc(var lessons: Int = 0, var minutes: Int = 0)

    /** 统计指定日期所在自然周（自动对齐到周一） */
    fun weekStats(
        profile: Profile,
        anyDateInWeek: LocalDate,
        firstWeekDate: LocalDate,
        tempPlans: Map<String, String> = emptyMap()
    ): WeekStats {
        val mondayOf = anyDateInWeek.with(DayOfWeek.MONDAY)
        val wi = ScheduleEngine.weekIndex(mondayOf, firstWeekDate)

        val bySubject = LinkedHashMap<String, Acc>()
        var totalLessons = 0
        var totalMinutes = 0

        val days = (0..6).map { offset ->
            val date = mondayOf.plusDays(offset.toLong())
            val dateWi = ScheduleEngine.weekIndex(date, firstWeekDate)
            val plan = ScheduleEngine.effectivePlan(
                profile, date, dateWi, tempPlans[date.toString()]
            )
            val layout = plan?.let { profile.layouts[it.layoutId] }
            var dayLessons = 0
            var dayMinutes = 0

            if (plan != null && layout != null) {
                layout.items.filter { it.isClass }.forEachIndexed { i, item ->
                    val sid = plan.classes.getOrNull(i)
                    if (sid.isNullOrEmpty()) return@forEachIndexed   // 空课不计入
                    val minutes = minutesOf(item)
                    dayLessons++
                    dayMinutes += minutes
                    val acc = bySubject.getOrPut(sid) { Acc() }
                    acc.lessons++
                    acc.minutes += minutes
                }
            }
            if (dayLessons > 0) {
                totalLessons += dayLessons
                totalMinutes += dayMinutes
            }
            DayStat(date, dayLessons, toHours(dayMinutes))
        }

        val subjects = bySubject.entries
            .map { (sid, acc) ->
                SubjectStat(sid, profile.subjectName(sid), acc.lessons, toHours(acc.minutes))
            }
            .sortedByDescending { it.lessons }

        return WeekStats(
            monday = mondayOf,
            weekIndex = wi,
            days = days,
            subjects = subjects,
            totalLessons = totalLessons,
            totalClassHours = toHours(totalMinutes),
            totalMinutes = totalMinutes
        )
    }

    private fun minutesOf(item: TimeItem): Int =
        ChronoUnit.MINUTES.between(item.start, item.end).toInt().coerceAtLeast(0)

    /** 45 分钟 = 1 课时（官方常用口径） */
    private fun toHours(minutes: Int): Double = minutes / 45.0

    /** 今日已过去的课堂时间（分钟），用于「今日进度」旁的时长概览 */
    fun elapsedMinutes(
        profile: Profile,
        date: LocalDate,
        now: LocalTime,
        firstWeekDate: LocalDate,
        tempPlanId: String? = null
    ): Int {
        val wi = ScheduleEngine.weekIndex(date, firstWeekDate)
        val plan = ScheduleEngine.effectivePlan(profile, date, wi, tempPlanId) ?: return 0
        val layout = profile.layouts[plan.layoutId] ?: return 0
        var sum = 0
        for (item in layout.items) {
            if (!item.isClass) continue
            if (now > item.end) sum += minutesOf(item)
            else if (now > item.start) sum += ChronoUnit.MINUTES.between(item.start, now).toInt()
        }
        return sum.coerceAtLeast(0)
    }
}
