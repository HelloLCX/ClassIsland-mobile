package tech.classisland.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import tech.classisland.mobile.engine.Phase
import tech.classisland.mobile.engine.ScheduleEngine
import tech.classisland.mobile.model.ProfileParser
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

class LogicTest {

    private fun profile() = ProfileParser.parse(
        File("src/main/assets/default-profile.json").readText()
    )

    private val monday = LocalDate.of(2026, 9, 28) // 已知是周一

    @Test
    fun parsesProfile() {
        val p = profile()
        assertEquals(14, p.subjectCount)
        assertEquals(1, p.layoutList.size)
        assertEquals(5, p.planList.size)
        assertEquals("语文", p.subjects["aaaaaaaa-0001-0000-0000-000000000001"]!!.name)
    }

    @Test
    fun firstLessonInClass() {
        val p = profile()
        val st = ScheduleEngine.statusAt(p, monday, LocalTime.of(8, 10), monday)
        assertEquals(Phase.IN_CLASS, st.phase)
        assertEquals(1, st.classIndex)
        assertEquals("语文", p.subjectName(st.currentSubjectId))
    }

    @Test
    fun bigBreakBeforeThirdLesson() {
        val p = profile()
        val st = ScheduleEngine.statusAt(p, monday, LocalTime.of(10, 0), monday)
        assertEquals(Phase.BREAK, st.phase)
        assertEquals(-1, st.classIndex)
        assertEquals(3, st.nextClassIndex)
        assertEquals("英语", p.subjectName(st.nextSubjectId))
    }

    @Test
    fun afterSchool() {
        val p = profile()
        val st = ScheduleEngine.statusAt(p, monday, LocalTime.of(18, 0), monday)
        assertEquals(Phase.AFTER_SCHOOL, st.phase)
    }

    @Test
    fun weekendNoClass() {
        val p = profile()
        val sat = monday.plusDays(5)
        val st = ScheduleEngine.statusAt(p, sat, LocalTime.NOON, monday)
        assertEquals(Phase.NO_CLASS_TODAY, st.phase)
    }

    @Test
    fun weekIndexMath() {
        assertEquals(1, ScheduleEngine.weekIndex(monday, monday))
        assertEquals(1, ScheduleEngine.weekIndex(monday.plusDays(4), monday))
        assertEquals(2, ScheduleEngine.weekIndex(monday.plusDays(7), monday))
        assertEquals(3, ScheduleEngine.weekIndex(monday.plusDays(15), monday))
    }

    @Test
    fun fridayPlan() {
        val p = profile()
        val fri = monday.plusDays(4)
        val plan = ScheduleEngine.planFor(p, fri, ScheduleEngine.weekIndex(fri, monday))
        assertNotNull(plan)
        assertEquals("周五课表", plan!!.name)
    }
}

class TempOverrideTest {
    private fun profile() = ProfileParser.parse(
        File("src/main/assets/default-profile.json").readText()
    )

    private val monday = LocalDate.of(2026, 9, 7) // 周一

    @Test
    fun overridePlanWins() {
        val p = profile()
        val wi = ScheduleEngine.weekIndex(monday, monday)
        val normal = ScheduleEngine.planFor(p, monday, wi)
        assertEquals("周一课表", normal!!.name)
        // 用周二课表覆盖周一
        val tuesday = p.plans.values.first { it.name == "周二课表" }
        val eff = ScheduleEngine.effectivePlan(p, monday, wi, tuesday.id)
        assertEquals("周二课表", eff!!.name)
    }

    @Test
    fun invalidOverrideFallsBack() {
        val p = profile()
        val wi = ScheduleEngine.weekIndex(monday, monday)
        val eff = ScheduleEngine.effectivePlan(p, monday, wi, "not-exist-id")
        assertEquals("周一课表", eff!!.name)
    }

    @Test
    fun overrideEmptyMeansSuspension() {
        val p = profile()
        val wi = ScheduleEngine.weekIndex(monday, monday)
        // 空串表示当天临时停课
        assertEquals(null, ScheduleEngine.effectivePlan(p, monday, wi, ""))
        // null 表示无临时设置，按常规课表
        assertEquals("周一课表", ScheduleEngine.effectivePlan(p, monday, wi, null)!!.name)
    }
}
