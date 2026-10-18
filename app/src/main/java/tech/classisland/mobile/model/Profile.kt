package tech.classisland.mobile.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.time.LocalTime
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * 与 ClassIsland 官方档案 JSON 完全兼容的数据模型。
 *
 * 关键设计：每个实体都携带 [raw]（解析时的原始 JSON 副本）。写出时以 raw 为底、
 * 只覆盖本 App 管理的字段，因此官方档案里我们不认得的字段（如根级 `Name`、
 * `IsActive`、`IsHideDefault`、`DefaultClassId`、`OverlaySourceId`、`SortIndex`…）
 * 会原样保留，做到「读进来—编辑—存出去」零丢失，导出的文件官方版也能直接打开。
 */

/** 科目 */
data class Subject(
    var id: String,
    var name: String,
    var initial: String,
    var teacher: String,
    var outdoor: Boolean,
    var raw: JSONObject? = null
)

/** 时间段。type: 0=上课 1=课间 2=分割 */
data class TimeItem(
    var start: LocalTime,
    var end: LocalTime,
    var type: Int,
    var raw: JSONObject? = null
) {
    val isClass: Boolean get() = type == 0
}

data class TimeLayout(
    var id: String,
    var name: String,
    var items: MutableList<TimeItem>,
    var raw: JSONObject? = null
)

/** 课表生效规则。weekDay: 0=周日 1..6=周一..周六；div: 0全部 1单周 2双周 3第三周 4第四周 */
data class TimeRule(
    var weekDay: Int,
    var div: Int,
    var divTotal: Int,
    var raw: JSONObject? = null
)

data class ClassPlan(
    var id: String,
    var name: String,
    var layoutId: String,
    var rule: TimeRule,
    /** 按节次排列的科目 id，null 表示空课 */
    var classes: MutableList<String?>,
    var enabled: Boolean,
    var overlay: Boolean,
    var raw: JSONObject? = null
)

data class Profile(
    val subjects: MutableMap<String, Subject>,
    val layouts: MutableMap<String, TimeLayout>,
    val plans: MutableMap<String, ClassPlan>,
    /** 档案根对象的原始 JSON（含 Name、TempClassPlanId 等顶层字段） */
    var rootRaw: JSONObject? = null
) {
    val layoutList get() = layouts.values.toList()
    val planList get() = plans.values.toList()
    val subjectCount: Int get() = subjects.size

    fun subjectName(id: String?): String =
        if (id.isNullOrEmpty()) "空课" else subjects[id]?.name ?: "未知课程"

    fun subjectInitial(id: String?): String =
        if (id.isNullOrEmpty()) "--" else subjects[id]?.initial?.takeIf { it.isNotEmpty() }
            ?: (subjects[id]?.name?.take(1) ?: "?")

    /** 档案名（官方根字段 Name），未设置时返回默认名 */
    var name: String
        get() = rootRaw?.optString("Name")?.takeIf { it.isNotEmpty() } ?: "我的课表"
        set(value) {
            if (rootRaw == null) rootRaw = JSONObject()
            rootRaw?.put("Name", value)
        }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}

object ProfileParser {

    /** 去除 JSON 中的 // 行注释（ClassIsland 范例档案带注释） */
    private fun stripComments(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (line in raw.lineSequence()) {
            val idx = line.indexOf("//")
            sb.append(if (idx >= 0) line.substring(0, idx) else line)
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun parseTime(s: String): LocalTime {
        val t = s.substringAfter('T').take(8)
        return LocalTime.parse(t)
    }

    /** 逐实体保留原始 JSON（深拷贝），供写出时合并 */
    private fun keep(o: JSONObject?): JSONObject? =
        if (o == null) null else runCatching { JSONObject(o.toString()) }.getOrNull()

    fun parse(rawJson: String): Profile {
        val root = JSONObject(stripComments(rawJson))

        val subjects = HashMap<String, Subject>()
        root.optJSONObject("Subjects")?.let { obj ->
            for (id in obj.keys()) {
                val o = obj.getJSONObject(id)
                subjects[id] = Subject(
                    id = id,
                    name = o.optString("Name", "未命名"),
                    initial = o.optString("Initial", ""),
                    teacher = o.optString("TeacherName", ""),
                    outdoor = o.optBoolean("IsOutDoor", false),
                    raw = keep(o)
                )
            }
        }

        val layouts = HashMap<String, TimeLayout>()
        root.optJSONObject("TimeLayouts")?.let { obj ->
            for (id in obj.keys()) {
                val o = obj.getJSONObject(id)
                val arr = o.optJSONArray("Layouts") ?: continue
                val items = (0 until arr.length()).map { i ->
                    val it = arr.getJSONObject(i)
                    TimeItem(
                        start = parseTime(it.optString("StartSecond", "T00:00:00")),
                        end = parseTime(it.optString("EndSecond", "T00:00:00")),
                        type = it.optInt("TimeType", 0),
                        raw = keep(it)
                    )
                }.sortedBy { it.start }.toMutableList()
                layouts[id] = TimeLayout(
                    id, o.optString("Name", "时间表"), items, keep(o)
                )
            }
        }

        val plans = HashMap<String, ClassPlan>()
        root.optJSONObject("ClassPlans")?.let { obj ->
            for (id in obj.keys()) {
                val o = obj.getJSONObject(id)
                val r = o.optJSONObject("TimeRule")
                val arr = o.optJSONArray("Classes")
                val classes = if (arr != null) (0 until arr.length()).map { i ->
                    arr.getJSONObject(i).optString("SubjectId", "").ifEmpty { null }
                }.toMutableList() else mutableListOf()
                plans[id] = ClassPlan(
                    id = id,
                    name = o.optString("Name", "课表"),
                    layoutId = o.optString("TimeLayoutId", ""),
                    rule = TimeRule(
                        weekDay = r?.optInt("WeekDay", 1) ?: 1,
                        div = r?.optInt("WeekCountDiv", 0) ?: 0,
                        divTotal = r?.optInt("WeekCountDivTotal", 0) ?: 0,
                        raw = keep(r)
                    ),
                    classes = classes,
                    enabled = o.optBoolean("IsEnabled", true),
                    overlay = o.optBoolean("IsOverlay", false),
                    raw = keep(o)
                )
            }
        }

        return Profile(subjects, layouts, plans, keep(root))
    }

    /** 解析任意导入内容：zip 档案或纯 JSON */
    fun parseAny(bytes: ByteArray): Profile {
        val head = bytes.take(4).toByteArray()
        val isZip = head.size == 4 &&
            head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
            (head[2] == 0x03.toByte() || head[2] == 0x05.toByte())
        if (!isZip) return parse(String(bytes, Charsets.UTF_8))

        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".json", ignoreCase = true)) {
                    val content = zis.readBytes()
                    val text = String(content, Charsets.UTF_8)
                    if (text.contains("ClassPlans") || text.contains("TimeLayouts")) {
                        return parse(text)
                    }
                }
                entry = zis.nextEntry
            }
        }
        throw IllegalArgumentException("压缩包中未找到 ClassIsland 档案 JSON")
    }
}

/**
 * 将内存中的 Profile 序列化为 ClassIsland 档案 JSON。
 *
 * 采用「原始 JSON + 定点覆盖」策略：
 *  - 实体原始 JSON 存在 -> 深拷贝后只更新我们管理的字段，官方特有字段原样保留；
 *  - 实体原始 JSON 不存在（本 App 新建）-> 按官方字段结构生成；
 *  - 顶层字段（Name / IsOverlayClassPlanEnabled / TempClassPlanId / IsActive …）
 *    仅在原档案缺失时补默认值，绝不覆盖官方已有取值。
 */
object ProfileWriter {

    private fun t(t: LocalTime): String = "2024-01-01T" +
        String.format("%02d:%02d:%02d", t.hour, t.minute, t.second)

    private fun copy(raw: JSONObject?): JSONObject =
        if (raw == null) JSONObject() else runCatching { JSONObject(raw.toString()) }.getOrNull() ?: JSONObject()

    fun toJson(p: Profile): String {
        // ---- 根对象：保留未知顶层字段 ----
        val root = copy(p.rootRaw)

        // ---- 科目 ----
        val subjects = JSONObject()
        for ((id, s) in p.subjects) {
            val o = copy(s.raw)
            o.put("Name", s.name)
            o.put("Initial", s.initial)
            o.put("TeacherName", s.teacher)
            o.put("IsOutDoor", s.outdoor)
            if (!o.has("IsActive")) o.put("IsActive", true)
            subjects.put(id, o)
        }
        root.put("Subjects", subjects)

        // ---- 时间表 ----
        val layouts = JSONObject()
        for ((id, l) in p.layouts) {
            val o = copy(l.raw)
            o.put("Name", l.name)
            val arr = JSONArray()
            l.items.sortedBy { it.start }.forEach { item ->
                val io = copy(item.raw)
                io.put("StartSecond", t(item.start))
                io.put("EndSecond", t(item.end))
                io.put("TimeType", item.type)
                if (!io.has("IsHideDefault")) io.put("IsHideDefault", false)
                if (!io.has("DefaultClassId")) io.put("DefaultClassId", "")
                if (!io.has("IsActive")) io.put("IsActive", true)
                arr.put(io)
            }
            o.put("Layouts", arr)
            if (!o.has("IsActive")) o.put("IsActive", true)
            layouts.put(id, o)
        }
        root.put("TimeLayouts", layouts)

        // ---- 课表 ----
        val plans = JSONObject()
        for ((id, plan) in p.plans) {
            val o = copy(plan.raw)
            o.put("Name", plan.name)
            o.put("TimeLayoutId", plan.layoutId)

            val rule = copy(plan.rule.raw)
            rule.put("WeekDay", plan.rule.weekDay)
            rule.put("WeekCountDiv", plan.rule.div)
            rule.put("WeekCountDivTotal", plan.rule.divTotal)
            if (!rule.has("IsActive")) rule.put("IsActive", true)
            o.put("TimeRule", rule)

            // Classes 每一项也基于原始项合并，官方在单项里存的字段不丢
            val oldClasses = plan.raw?.optJSONArray("Classes")
            val classes = JSONArray()
            plan.classes.forEachIndexed { i, sid ->
                val co = copy(oldClasses?.optJSONObject(i))
                co.put("SubjectId", sid ?: "")
                if (!co.has("IsActive")) co.put("IsActive", false)
                classes.put(co)
            }
            o.put("Classes", classes)

            o.put("IsOverlay", plan.overlay)
            if (!o.has("OverlaySourceId")) o.put("OverlaySourceId", JSONObject.NULL)
            o.put("IsEnabled", plan.enabled)
            if (!o.has("IsActive")) o.put("IsActive", true)
            plans.put(id, o)
        }
        root.put("ClassPlans", plans)

        // ---- 顶层控制字段：只补缺、不覆盖 ----
        if (!root.has("IsOverlayClassPlanEnabled")) root.put("IsOverlayClassPlanEnabled", false)
        if (!root.has("OverlayClassPlanId")) root.put("OverlayClassPlanId", JSONObject.NULL)
        if (!root.has("TempClassPlanId")) root.put("TempClassPlanId", JSONObject.NULL)
        if (!root.has("IsActive")) root.put("IsActive", true)
        if (!root.has("Name")) root.put("Name", p.name)

        return root.toString(2)
    }
}
