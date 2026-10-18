package tech.classisland.mobile.engine

import tech.classisland.mobile.model.ClassPlan
import tech.classisland.mobile.model.Profile
import tech.classisland.mobile.model.TimeItem
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

enum class Phase { BEFORE_SCHOOL, IN_CLASS, BREAK, AFTER_SCHOOL, NO_CLASS_TODAY }

data class TodayStatus(
    val date: LocalDate,
    val weekIndex: Int,
    val plan: ClassPlan?,
    val phase: Phase,
    val currentItem: TimeItem?,
    /** 正在上的节次，1 起；-1 表示非上课 */
    val classIndex: Int,
    /** 下一节节次，1 起；-1 表示今天没有下一节 */
    val nextClassIndex: Int,
    /** 今日每节课：节次、时间段、科目 id */
    val lessons: List<Triple<Int, TimeItem, String?>>
) {
    val nextSubjectId: String? get() = plan?.classes?.getOrNull(nextClassIndex - 1)
    val currentSubjectId: String? get() = plan?.classes?.getOrNull(classIndex - 1)
}

object ScheduleEngine {

    /**
     * 计算今天是第几周（1 起）。
     * @param firstWeekDate 第 1 周内任意一天（通常填开学日/第 1 周周一）
     */
    fun weekIndex(date: LocalDate, firstWeekDate: LocalDate): Int {
        val m1 = date.with(DayOfWeek.MONDAY)
        val m2 = firstWeekDate.with(DayOfWeek.MONDAY)
        return (ChronoUnit.WEEKS.between(m2, m1).toInt() + 1).coerceAtLeast(1)
    }

    /** ClassIsland 星期表示：周日=0，周一..周六=1..6 */
    private fun ciDayOfWeek(date: LocalDate): Int {
        val j = date.dayOfWeek.value // 周一=1..周日=7
        return if (j == 7) 0 else j
    }

    private fun weekMatches(ruleDiv: Int, ruleTotal: Int, weekIndex: Int): Boolean {
        if (ruleDiv == 0) return true
        val total = if (ruleTotal >= 2) ruleTotal else 2
        val slot = (weekIndex - 1).mod(total) + 1
        return slot == ruleDiv
    }

    /** 选出指定日期生效的普通课表（临时层不参与自动选择） */
    fun planFor(profile: Profile, date: LocalDate, weekIndex: Int): ClassPlan? {
        val dow = ciDayOfWeek(date)
        val candidates = profile.plans.values.filter {
            it.enabled && !it.overlay &&
                it.rule.weekDay == dow &&
                weekMatches(it.rule.div, it.rule.divTotal, weekIndex)
        }
        return candidates.sortedBy { it.name }.firstOrNull()
    }

    /** 指定日期实际生效的课表：临时调课覆盖优先，空串表示停课，否则按星期/周次自动选择 */
    fun effectivePlan(
        profile: Profile,
        date: LocalDate,
        weekIndex: Int,
        overridePlanId: String?
    ): ClassPlan? {
        if (overridePlanId != null) {
            if (overridePlanId.isEmpty()) return null // 明确停课
            val op = profile.plans[overridePlanId]
            if (op != null && op.enabled) return op
        }
        return planFor(profile, date, weekIndex)
    }

    fun statusAt(
        profile: Profile,
        date: LocalDate,
        now: LocalTime,
        firstWeekDate: LocalDate,
        overridePlanId: String? = null
    ): TodayStatus {
        val wi = weekIndex(date, firstWeekDate)
        val plan = effectivePlan(profile, date, wi, overridePlanId)
        val layout = plan?.let { profile.layouts[it.layoutId] }

        if (plan == null || layout == null || layout.items.isEmpty()) {
            return TodayStatus(date, wi, plan, Phase.NO_CLASS_TODAY, null, -1, -1, emptyList())
        }

        val items = layout.items
        val lessons = items.filter { it.isClass }.mapIndexed { i, t ->
            Triple(i + 1, t, plan.classes.getOrNull(i))
        }

        if (now.isBefore(items.first().start)) {
            val firstNext = lessons.firstOrNull()?.first ?: -1
            return TodayStatus(date, wi, plan, Phase.BEFORE_SCHOOL, null, -1, firstNext, lessons)
        }
        if (now >= items.last().end) {
            return TodayStatus(date, wi, plan, Phase.AFTER_SCHOOL, null, -1, -1, lessons)
        }

        val curIdx = items.indexOfFirst { now >= it.start && now < it.end }
        val current = items.getOrNull(curIdx)

        // 已上过（含当前）的上课节数
        val passedClassCount = if (curIdx < 0) 0 else
            items.subList(0, curIdx + 1).count { it.isClass }
        // 下一节节次：从当前时间段起，第一个尚未开始的上课项
        val nextClassCount = run {
            val from = if (curIdx < 0) 0 else curIdx
            val tail = items.subList(from, items.size)
            val rel = tail.indexOfFirst { it.isClass && now < it.start }
            if (rel < 0) -1
            else items.subList(0, from).count { it.isClass } +
                tail.subList(0, rel + 1).count { it.isClass }
        }

        val phase = when {
            current?.isClass == true -> Phase.IN_CLASS
            else -> Phase.BREAK
        }
        return TodayStatus(
            date, wi, plan, phase, current,
            classIndex = if (phase == Phase.IN_CLASS) passedClassCount else -1,
            nextClassIndex = nextClassCount,
            lessons = lessons
        )
    }
}
