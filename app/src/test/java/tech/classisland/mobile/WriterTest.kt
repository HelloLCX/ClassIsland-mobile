package tech.classisland.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.classisland.mobile.model.ProfileParser
import tech.classisland.mobile.model.ProfileWriter
import tech.classisland.mobile.model.Subject
import java.io.File

class WriterTest {

    private fun profile() = ProfileParser.parse(
        File("src/main/assets/default-profile.json").readText()
    )

    @Test
    fun roundTripKeepsData() {
        val p = profile()
        val json = ProfileWriter.toJson(p)
        val q = ProfileParser.parse(json)
        assertEquals(p.subjectCount, q.subjectCount)
        assertEquals(p.layoutList.size, q.layoutList.size)
        assertEquals(p.planList.size, q.planList.size)
        val mondayPlan = q.plans.values.first { it.name == "周一课表" }
        assertEquals(8, mondayPlan.classes.size)
        assertEquals("语文", q.subjectName(mondayPlan.classes[0]))
    }

    @Test
    fun editAndSave() {
        val p = profile()
        val newSubject = Subject(
            "test-id-1", "通用技术", "通", "张老师", false
        )
        p.subjects[newSubject.id] = newSubject
        val q = ProfileParser.parse(ProfileWriter.toJson(p))
        assertTrue(q.subjects.containsKey("test-id-1"))
        assertEquals("张老师", q.subjects["test-id-1"]!!.teacher)
    }

    @Test
    fun timeLayoutSerialized() {
        val p = profile()
        val q = ProfileParser.parse(ProfileWriter.toJson(p))
        val layout = q.layoutList.first()
        assertEquals(15, layout.items.size)
        assertEquals(8, layout.items.count { it.isClass })
    }
}
