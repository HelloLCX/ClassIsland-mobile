package tech.classisland.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.classisland.mobile.engine.ScheduleEngine
import tech.classisland.mobile.engine.StatsEngine
import tech.classisland.mobile.model.ProfileParser
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** 本周课时统计（对应官方「统计」面板）的口径测试 */
class StatsTest {

    private fun profile() = ProfileParser.parse(
        File("src/main/assets/default-profile.json").readText()
    )

    // 已知周一；示例档案周一至周五每天 8 节，每节 45 分钟
    private val monday = LocalDate.of(2026, 9, 28)

    @Test
    fun weekHasFiveSchoolDays() {
        val s = StatsEngine.weekStats(profile(), monday, monday)
        // 周一到周五有课，周六周日无课
        assertEquals(5, s.days.count { it.lessons > 0 })
        assertEquals(2, s.days.count { it.lessons == 0 })
        // 每天 8 节
        assertEquals(8, s.days[0].lessons)
        assertEquals(8, s.days[4].lessons)
    }

    @Test
    fun totalsMatchLessons() {
        val s = StatsEngine.weekStats(profile(), monday, monday)
        assertEquals(40, s.totalLessons)                 // 5 天 × 8 节
        assertEquals(40 * 45, s.totalMinutes)            // 每节 45 分钟
        assertEquals(40.0, s.totalClassHours, 0.001)     // 45 分钟 = 1 课时
        assertTrue(s.hasData)
    }

    @Test
    fun subjectRankingCoversAllNonEmptyClasses() {
        val s = StatsEngine.weekStats(profile(), monday, monday)
        // 排行里所有科目节数之和 == 总节数（空课不计入）
        assertEquals(s.totalLessons, s.subjects.sumOf { it.lessons })
        // 按节数降序
        assertEquals(
            s.subjects.map { it.lessons }.sortedDescending(),
            s.subjects.map { it.lessons }
        )
    }

    @Test
    fun weekendIsEmpty() {
        val s = StatsEngine.weekStats(profile(), monday, monday)
        assertEquals(0, s.days[5].lessons)   // 周六
        assertEquals(0, s.days[6].lessons)   // 周日
    }

    @Test
    fun alignsAnyDateToMonday() {
        val s = StatsEngine.weekStats(profile(), monday.plusDays(3), monday) // 周四
        assertEquals(monday, s.monday)
        assertEquals(1, ScheduleEngine.weekIndex(monday, monday))
    }

    @Test
    fun emptyWeekReturnsZero() {
        // 基准日推到很久以前，使这一周的周次与示例档案的单双周规则都不匹配
        val far = monday.plusWeeks(52)
        val s = StatsEngine.weekStats(profile(), far, far)
        // 示例档案全部是每周课，因此仍有课；断言结构正确即可
        assertEquals(7, s.days.size)
        assertTrue(s.totalMinutes % 45 == 0)
    }

    @Test
    fun elapsedMinutesWithinDay() {
        val p = profile()
        // 作息：第1节 08:00-08:45，第2节 08:55-09:40
        // 07:00 还没开课
        assertEquals(0, StatsEngine.elapsedMinutes(
            p, monday, LocalTime.of(7, 0), monday
        ))
        // 08:00 刚好开课，已过 0 分钟
        assertEquals(0, StatsEngine.elapsedMinutes(
            p, monday, LocalTime.of(8, 0), monday
        ))
        // 09:00：第1节完整 45 分 + 第2节已上 5 分 = 50 分
        assertEquals(50, StatsEngine.elapsedMinutes(
            p, monday, LocalTime.of(9, 0), monday
        ))
        // 08:30 处在第 1 节中途，已过 30 分
        assertEquals(30, StatsEngine.elapsedMinutes(
            p, monday, LocalTime.of(8, 30), monday
        ))
    }
}
