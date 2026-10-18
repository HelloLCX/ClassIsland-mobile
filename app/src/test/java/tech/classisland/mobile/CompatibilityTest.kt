package tech.classisland.mobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.classisland.mobile.model.ProfileParser
import tech.classisland.mobile.model.ProfileWriter
import java.io.File

/**
 * 与 ClassIsland 官方档案的兼容性测试：
 * 官方档案里存在、但本 App 不解析的字段必须在「读入—编辑—写出」后原样保留，
 * 保证导出的档案官方版可以直接打开，官方档案导入后再保存也不丢数据。
 */
class CompatibilityTest {

    private val officialRaw: String
        get() = File("src/main/assets/default-profile.json").readText()

    /** 递归按 key 排序，得到与 key 顺序无关的规范 JSON 文本 */
    private fun canonical(v: Any?): String = when (v) {
        is JSONObject -> {
            val ks = ArrayList<String>()
            val itr = v.keys()
            while (itr.hasNext()) ks.add(itr.next())
            ks.sorted().joinToString(",", "{", "}") { "\"$it\":${canonical(v.get(it))}" }
        }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.get(it)) }
        is String -> JSONObject.quote(v)
        null -> "null"
        else -> v.toString()
    }

    @Test
    fun rootNameSurvivesRoundTrip() {
        val before = JSONObject(officialRaw)
        val after = JSONObject(ProfileWriter.toJson(ProfileParser.parse(officialRaw)))
        assertEquals(before.getString("Name"), after.getString("Name"))
        assertTrue(after.has("IsOverlayClassPlanEnabled"))
        assertTrue(after.has("TempClassPlanId"))
        assertTrue(after.has("IsActive"))
    }

    @Test
    fun officialSampleRoundTripIsIdempotent() {
        val once = ProfileWriter.toJson(ProfileParser.parse(officialRaw))
        val twice = ProfileWriter.toJson(ProfileParser.parse(once))
        assertEquals(canonical(JSONObject(once)), canonical(JSONObject(twice)))
    }

    @Test
    fun unknownOfficialFieldsSurviveEditing() {
        // 模拟官方版会写、而本 App 不解析的字段
        val json = """
        {
          "Name": "高一3班",
          "TempClassPlanId": "keep-me",
          "CustomRootField": {"any": 1},
          "Subjects": {
            "s1": {"Name":"数学","Initial":"数","TeacherName":"张老师",
                   "IsOutDoor":false,"Color":"#FF0000","SortIndex":7}
          },
          "TimeLayouts": {
            "l1": {"Name":"标准作息","IsActive":true,"Layouts":[
              {"StartSecond":"2024-01-01T08:00:00","EndSecond":"2024-01-01T08:45:00",
               "TimeType":0,"IsHideDefault":true,"DefaultClassId":"keep-class"}]}
          },
          "ClassPlans": {
            "c1": {"Name":"周一课表","TimeLayoutId":"l1",
                   "TimeRule":{"WeekDay":1,"WeekCountDiv":1,"WeekCountDivTotal":2},
                   "Classes":[{"SubjectId":"s1","IsActive":false,"Note":"keep-note"}],
                   "IsEnabled":true,"IsOverlay":false,"SortIndex":3}
          }
        }
        """.trimIndent()

        val p = ProfileParser.parse(json)
        // 模拟用户在编辑页里改了科目名与课表名
        p.subjects["s1"]!!.name = "高等数学"
        p.plans["c1"]!!.name = "周一课表(单周)"

        val out = JSONObject(ProfileWriter.toJson(p))

        // 根级
        assertEquals("高一3班", out.getString("Name"))
        assertEquals("keep-me", out.getString("TempClassPlanId"))
        assertTrue(out.getJSONObject("CustomRootField").has("any"))

        // 科目：改动生效 + 官方扩展字段保留
        val sub = out.getJSONObject("Subjects").getJSONObject("s1")
        assertEquals("高等数学", sub.getString("Name"))
        assertEquals("#FF0000", sub.getString("Color"))
        assertEquals(7, sub.getInt("SortIndex"))

        // 时间段：IsHideDefault / DefaultClassId 不被清空
        val item = out.getJSONObject("TimeLayouts").getJSONObject("l1")
            .getJSONArray("Layouts").getJSONObject(0)
        assertEquals("2024-01-01T08:00:00", item.getString("StartSecond"))
        assertTrue(item.getBoolean("IsHideDefault"))
        assertEquals("keep-class", item.getString("DefaultClassId"))

        // 课表：改动生效 + SortIndex 与单项 Note 保留 + 单双周规则正确
        val plan = out.getJSONObject("ClassPlans").getJSONObject("c1")
        assertEquals("周一课表(单周)", plan.getString("Name"))
        assertEquals(3, plan.getInt("SortIndex"))
        assertEquals("keep-note", plan.getJSONArray("Classes").getJSONObject(0).getString("Note"))
        assertEquals(1, plan.getJSONObject("TimeRule").getInt("WeekCountDiv"))
        assertEquals(2, plan.getJSONObject("TimeRule").getInt("WeekCountDivTotal"))
    }

    @Test
    fun scheduleAndWeekRuleStillWorkAfterRewrite() {
        val p = ProfileParser.parse(ProfileWriter.toJson(ProfileParser.parse(officialRaw)))
        assertEquals(14, p.subjectCount)
        assertEquals(1, p.layoutList.size)
        assertEquals(5, p.planList.size)
        val monday = p.plans.values.first { it.name == "周一课表" }
        assertEquals(1, monday.rule.weekDay)
        assertEquals(0, monday.rule.div)
        assertEquals(8, monday.classes.size)
        assertEquals("语文", p.subjectName(monday.classes[0]))
    }
}
