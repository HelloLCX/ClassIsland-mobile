package tech.classisland.mobile.ui

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import tech.classisland.mobile.R
import tech.classisland.mobile.alert.AlertScheduler
import tech.classisland.mobile.databinding.ActivityEditBinding
import tech.classisland.mobile.engine.Store
import tech.classisland.mobile.model.ClassPlan
import tech.classisland.mobile.model.Profile
import tech.classisland.mobile.model.ProfileWriter
import tech.classisland.mobile.model.Subject
import tech.classisland.mobile.model.TimeItem
import tech.classisland.mobile.model.TimeLayout
import tech.classisland.mobile.model.TimeRule
import tech.classisland.mobile.overlay.OverlayService
import java.time.LocalTime

/**
 * 档案编辑页。
 *
 * 历史问题（v1.6.0 之前）：时间表/课表页签会「先建 Spinner → 监听回调里
 * removeAllViews 再建新 Spinner」，而 Spinner 在 attach 后必然回调一次
 * onItemSelected，于是形成无限重建循环，界面卡死、编辑功能完全不可用。
 *
 * 现在的约定：
 *  1. 每个页签的页头（星期/时间表 Spinner 等）只在进入页签时构建一次；
 *  2. 交互回调只重建下方的 [contentBox] 内容区，绝不触碰页头；
 *  3. [Spinner.latch] 吞掉系统 attach 后的首次回调，杜绝自触发循环。
 */
class EditActivity : AppCompatActivity() {

    private lateinit var b: ActivityEditBinding
    private lateinit var p: Profile
    private var tab = 0

    /** 当前页签的内容区（页头之下可反复重建的部分） */
    private var contentBox: LinearLayout? = null

    private val dayNames = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    private val dayCis = intArrayOf(1, 2, 3, 4, 5, 6, 0)
    private val typeNames = arrayOf("上课", "课间", "分割")
    private val divNames = arrayOf("每周", "单周", "双周")

    private val createDoc = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            contentResolver.openOutputStream(uri)?.use {
                it.write(ProfileWriter.toJson(p).toByteArray())
            }
            toast("档案已导出")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityEditBinding.inflate(layoutInflater)
        setContentView(b.root)
        title = "编辑档案"

        p = Store.loadProfile() ?: Profile(HashMap(), HashMap(), HashMap())

        b.tabSubject.setOnClickListener { tab = 0; render() }
        b.tabLayout.setOnClickListener { tab = 1; render() }
        b.tabPlan.setOnClickListener { tab = 2; render() }
        b.btnSave.setOnClickListener { save() }
        b.btnExport.setOnClickListener {
            save(silent = true)
            createDoc.launch("ClassIsland-profile.json")
        }
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * 绑定 Spinner 并吞掉系统在 attach/layout 后自动触发的首次回调。
     * 这是避免「回调里重建 Spinner → 再触发回调」死循环的关键。
     */
    private fun Spinner.latch(onSelect: (Int) -> Unit) {
        var first = true
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (first) { first = false; return }
                onSelect(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun newBody(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        b.container.addView(c)
        contentBox = c
        return c
    }

    private fun resetPage() {
        b.container.removeAllViews()
        contentBox = null
    }

    private fun card(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setBackgroundResource(R.drawable.bg_card)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(10)
        l.layoutParams = lp
        l.setPadding(dp(14), dp(12), dp(14), dp(12))
        return l
    }

    private fun rowText(text: String, sp: Float = 15f, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sp
            setTextColor(if (bold) 0xFF1F2937.toInt() else 0xFF334155.toInt())
            if (bold) paint.isFakeBoldText = true
        }

    private fun smallBtn(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 12f
            val lp = LinearLayout.LayoutParams(dp(72), dp(40))
            lp.marginStart = dp(6)
            layoutParams = lp
            setOnClickListener { onClick() }
        }

    private fun render() {
        resetPage()
        when (tab) {
            0 -> renderSubjects()
            1 -> renderLayouts()
            else -> rebuildPlanTab()
        }
    }

    // ---------------- 科目 ----------------

    private fun renderSubjects() {
        val body = newBody()
        val list = p.subjects.values.sortedBy { it.name }
        if (list.isEmpty()) body.addView(rowText("还没有科目，点下方按钮新增", 15f))
        for (s in list) {
            val c = card()
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            info.addView(rowText("${s.initial.ifEmpty { "·" }}  ${s.name}", 16f, true))
            val subtitle = buildString {
                if (s.teacher.isNotEmpty()) append("老师：${s.teacher}")
                if (s.outdoor) append(" · 室外课")
            }
            if (subtitle.isNotEmpty()) info.addView(rowText(subtitle, 13f))
            r.addView(info)
            r.addView(smallBtn("编辑") { subjectDialog(s) })
            r.addView(smallBtn("删除") {
                p.subjects.remove(s.id)
                p.plans.values.forEach { plan ->
                    for (i in plan.classes.indices) {
                        if (plan.classes[i] == s.id) plan.classes[i] = null
                    }
                }
                render()
            })
            c.addView(r)
            body.addView(c)
        }
        body.addView(Button(this).apply {
            text = "＋ 新增科目"
            setOnClickListener { subjectDialog(null) }
        })
    }

    private fun subjectDialog(existing: Subject?) {
        val s = existing ?: Subject(Profile.newId(), "", "", "", false)
        var chosenColor: Int? = Store.subjectColor(s.id)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
        }
        val etName = EditText(this).apply { hint = "科目名称（如：物理）"; setText(s.name) }
        val etInitial = EditText(this).apply { hint = "简写（如：物，一个字）"; setText(s.initial) }
        val etTeacher = EditText(this).apply { hint = "授课老师（可不填）"; setText(s.teacher) }
        box.addView(etName); box.addView(etInitial); box.addView(etTeacher)

        box.addView(rowText("科目颜色：", 13f))
        val colorRow = android.widget.HorizontalScrollView(this)
        val colorBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        colorRow.addView(colorBox)
        box.addView(colorRow)

        fun refreshColorSelection() {
            colorBox.removeAllViews()
            val auto = android.widget.TextView(this).apply {
                text = "自动"
                gravity = Gravity.CENTER
                textSize = 11f
                setTextColor(0xFF475569.toInt())
                val lp = LinearLayout.LayoutParams(dp(40), dp(40))
                lp.marginEnd = dp(6)
                layoutParams = lp
                val gd = android.graphics.drawable.GradientDrawable()
                gd.setColor(0xFFE2E8F0.toInt())
                gd.cornerRadius = dp(8).toFloat()
                if (chosenColor == null) gd.setStroke(dp(3), 0xFF475569.toInt())
                background = gd
                setOnClickListener { chosenColor = null; refreshColorSelection() }
            }
            colorBox.addView(auto)
            tech.classisland.mobile.engine.SubjectColors.PALETTE.forEachIndexed { i, c ->
                val v = android.view.View(this).apply {
                    setBackgroundColor(c)
                    val lp = LinearLayout.LayoutParams(dp(36), dp(36))
                    lp.marginEnd = dp(6)
                    layoutParams = lp
                    val deep = tech.classisland.mobile.engine.SubjectColors.PALETTE_DEEP[i]
                    val gd = android.graphics.drawable.GradientDrawable()
                    gd.setColor(c)
                    gd.cornerRadius = dp(8).toFloat()
                    if (chosenColor == c) gd.setStroke(dp(3), deep)
                    background = gd
                    setOnClickListener { chosenColor = c; refreshColorSelection() }
                }
                colorBox.addView(v)
            }
        }
        refreshColorSelection()

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新增科目" else "编辑科目")
            .setView(box)
            .setMultiChoiceItems(arrayOf("室外课（如体育）"), booleanArrayOf(s.outdoor)) { _, _, v -> s.outdoor = v }
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim()
                if (name.isEmpty()) { toast("名称不能为空"); return@setPositiveButton }
                s.name = name
                s.initial = etInitial.text.toString().trim().ifEmpty { name.take(1) }
                s.teacher = etTeacher.text.toString().trim()
                p.subjects[s.id] = s
                Store.setSubjectColor(s.id, chosenColor)
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 时间表 ----------------

    private var curLayoutId: String? = null

    private fun renderLayouts() {
        val layouts = p.layouts.values.sortedBy { it.name }

        // ---- 页头：时间表选择 + 管理按钮（每次进入页签只构建一次） ----
        if (layouts.isEmpty()) {
            b.container.addView(rowText("还没有时间表，点下方「新增时间表」开始", 15f))
        } else {
            if (curLayoutId == null || p.layouts[curLayoutId] == null) curLayoutId = layouts.first().id
            val spinner = Spinner(this)
            spinner.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item,
                layouts.map { it.name }
            )
            spinner.setSelection(layouts.indexOfFirst { it.id == curLayoutId }.coerceAtLeast(0))
            spinner.latch { pos ->
                curLayoutId = layouts[pos].id
                fillLayoutItems(layouts[pos])
            }
            b.container.addView(spinner)
        }

        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r.addView(Button(this).apply {
            text = "＋ 新增"
            setOnClickListener {
                val l = TimeLayout(Profile.newId(), "时间表${p.layouts.size + 1}", mutableListOf())
                p.layouts[l.id] = l
                curLayoutId = l.id
                render()
            }
        })
        r.addView(Button(this).apply {
            text = "复制当前"
            setOnClickListener {
                val cur = curLayoutId?.let { p.layouts[it] }
                if (cur == null) { toast("当前没有可复制的时间表"); return@setOnClickListener }
                val copy = TimeLayout(
                    Profile.newId(), "${cur.name} 副本",
                    cur.items.map { TimeItem(it.start, it.end, it.type) }.toMutableList()
                )
                p.layouts[copy.id] = copy
                curLayoutId = copy.id
                render()
                toast("已复制，请记得保存")
            }
        })
        r.addView(Button(this).apply {
            text = "删除"
            setOnClickListener {
                curLayoutId?.let {
                    if (p.plans.values.any { pl -> pl.layoutId == it }) {
                        toast("有课表正在使用该时间表，不能删除")
                    } else { p.layouts.remove(it); curLayoutId = null; render() }
                }
            }
        })
        b.container.addView(r)

        // ---- 内容区 ----
        newBody()
        val cur = curLayoutId?.let { p.layouts[it] }
        if (cur != null) fillLayoutItems(cur)
        else b.container.addView(rowText("选中的时间表不存在或已被删除", 14f))
    }

    /** 只重建内容区（页头 Spinner 保持不变，避免重建循环） */
    private fun fillLayoutItems(layout: TimeLayout) {
        val body = contentBox ?: return
        body.removeAllViews()
        val items = layout.items.sortedBy { it.start }
        if (items.isEmpty()) {
            body.addView(rowText("该时间表还没有时间段", 14f))
        }
        items.forEachIndexed { idx, item ->
            val c = card()
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            info.addView(rowText("${typeNames[item.type]} · 第 ${idx + 1} 项", 15f, true))
            info.addView(rowText("${item.start} - ${item.end}", 13f))
            r.addView(info)
            r.addView(smallBtn("编辑") { timeItemDialog(layout, item) { fillLayoutItems(layout) } })
            r.addView(smallBtn("删除") {
                layout.items.remove(item)
                fillLayoutItems(layout)
                toast("已删除时间段（记得点保存并生效）")
            })
            c.addView(r); body.addView(c)
        }
        body.addView(Button(this).apply {
            text = "＋ 新增时间段"
            setOnClickListener { timeItemDialog(layout, null) { fillLayoutItems(layout) } }
        })
        body.addView(rowText("提示：修改完成后请点底部「保存并生效」", 12f))
    }

    private fun timeItemDialog(layout: TimeLayout, existing: TimeItem?, onDone: () -> Unit) {
        val item = existing ?: TimeItem(LocalTime.of(8, 0), LocalTime.of(8, 45), 0)
        var start = item.start
        var end = item.end
        var type = item.type
        val tv = TextView(this).apply {
            setPadding(dp(20), dp(16), dp(20), 0)
            textSize = 16f
            text = "${typeNames[type]}\n$start - $end"
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun refresh() { tv.text = "${typeNames[type]}\n$start - $end" }
        val btnStart = Button(this).apply {
            text = "开始时间：$start"
            setOnClickListener {
                TimePickerDialog(this@EditActivity, { _, h, m ->
                    start = LocalTime.of(h, m); this.text = "开始时间：$start"; refresh()
                }, start.hour, start.minute, true).show()
            }
        }
        val btnEnd = Button(this).apply {
            text = "结束时间：$end"
            setOnClickListener {
                TimePickerDialog(this@EditActivity, { _, h, m ->
                    end = LocalTime.of(h, m); this.text = "结束时间：$end"; refresh()
                }, end.hour, end.minute, true).show()
            }
        }
        val btnType = Button(this).apply {
            text = "类型：${typeNames[type]}"
            setOnClickListener {
                AlertDialog.Builder(this@EditActivity)
                    .setTitle("选择类型")
                    .setSingleChoiceItems(typeNames, type) { d, w ->
                        type = w; this.text = "类型：${typeNames[type]}"; refresh(); d.dismiss()
                    }.show()
            }
        }
        box.addView(tv); box.addView(btnStart); box.addView(btnEnd); box.addView(btnType)
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新增时间段" else "编辑时间段")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                if (!end.isAfter(start)) { toast("结束时间必须晚于开始时间"); return@setPositiveButton }
                item.start = start; item.end = end; item.type = type
                if (existing == null) layout.items.add(item)
                layout.items.sortBy { it.start }
                onDone()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 课表 ----------------

    private var curDay = 0
    private var curPlanId: String? = null

    /** 某星期生效的全部常规课表（单双周会存在多份） */
    private fun plansForDay(ciDow: Int): List<ClassPlan> =
        p.plans.values.filter { it.rule.weekDay == ciDow && !it.overlay && it.enabled }
            .sortedBy { it.name }

    private fun divLabel(rule: TimeRule): String = when (rule.div) {
        1 -> "单周"
        2 -> "双周"
        else -> "每周"
    }

    private fun rebuildPlanTab() {
        // ---- 页头：星期 + 该天的课表选择 ----
        val daySpinner = Spinner(this)
        daySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, dayNames)
        daySpinner.setSelection(curDay)
        daySpinner.latch { pos ->
            curDay = pos
            curPlanId = null
            render()               // 换天后课表列表变化，重建本页签
        }
        b.container.addView(daySpinner)

        val plans = plansForDay(dayCis[curDay])
        if (plans.isNotEmpty()) {
            if (curPlanId == null || plans.none { it.id == curPlanId }) curPlanId = plans.first().id
            if (plans.size > 1) {
                val planSpinner = Spinner(this)
                planSpinner.adapter = ArrayAdapter(
                    this, android.R.layout.simple_spinner_dropdown_item,
                    plans.map { "${it.name}（${divLabel(it.rule)}）" }
                )
                planSpinner.setSelection(plans.indexOfFirst { it.id == curPlanId }.coerceAtLeast(0))
                planSpinner.latch { pos -> curPlanId = plans[pos].id; render() }
                b.container.addView(planSpinner)
            } else {
                curPlanId = plans.first().id
            }
        } else {
            curPlanId = null
        }

        val body = newBody()
        fillPlanDay(body)
    }

    private fun fillPlanDay(body: LinearLayout) {
        val ciDow = dayCis[curDay]
        val plan = curPlanId?.let { id -> p.plans.values.firstOrNull { it.id == id } }

        if (plan == null) {
            body.addView(Button(this).apply {
                text = "为${dayNames[curDay]}新建课表"
                setOnClickListener { createPlanDialog(ciDow) }
            })
            body.addView(rowText("该星期还没有课表。可以新建多份课表实现单双周轮换。", 13f))
            return
        }

        val c = card()
        c.addView(rowText(plan.name + "（${divLabel(plan.rule)}）", 16f, true))

        // 课表名称
        c.addView(Button(this).apply {
            text = "修改课表名称"
            setOnClickListener {
                val et = EditText(this@EditActivity).apply { setText(plan.name) }
                AlertDialog.Builder(this@EditActivity).setTitle("课表名称")
                    .setView(et)
                    .setPositiveButton("保存") { _, _ ->
                        plan.name = et.text.toString().ifEmpty { "${dayNames[curDay]}课表" }
                        render()
                    }.setNegativeButton("取消", null).show()
            }
        })

        // 时间表选择
        val layouts = p.layouts.values.sortedBy { it.name }
        if (layouts.isNotEmpty()) {
            c.addView(rowText("使用的时间表：", 13f))
            val layoutSpinner = Spinner(this)
            layoutSpinner.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item, layouts.map { it.name }
            )
            layoutSpinner.setSelection(layouts.indexOfFirst { it.id == plan.layoutId }.coerceAtLeast(0))
            layoutSpinner.latch { pos ->
                val l = layouts[pos]
                if (l.id != plan.layoutId) {
                    plan.layoutId = l.id
                    val n = l.items.count { it.isClass }
                    while (plan.classes.size < n) plan.classes.add(null)
                    while (plan.classes.size > n) plan.classes.removeAt(plan.classes.size - 1)
                    fillPlanDay(body)
                }
            }
            c.addView(layoutSpinner)
        } else {
            c.addView(rowText("（还没有时间表，请先到「时间表」页签创建）", 13f))
        }

        // 单双周
        c.addView(rowText("生效周：", 13f))
        val divSpinner = Spinner(this)
        divSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, divNames)
        divSpinner.setSelection(plan.rule.div.coerceIn(0, 2))
        divSpinner.latch { pos ->
            plan.rule.div = pos
            plan.rule.divTotal = if (pos == 0) 0 else 2
        }
        c.addView(divSpinner)

        c.addView(Button(this).apply {
            text = "停用 / 启用该课表"
            setOnClickListener {
                plan.enabled = !plan.enabled
                toast(if (plan.enabled) "已启用" else "已停用")
                render()
            }
        })
        body.addView(c)

        // 每节排课
        val layout = p.layouts[plan.layoutId]
        val lessonItems = layout?.items?.filter { it.isClass } ?: emptyList()
        val subjectList = p.subjects.values.sortedBy { it.name }
        val names = mutableListOf("空课").apply { addAll(subjectList.map { it.name }) }
        val ids = mutableListOf<String?>(null).apply { addAll(subjectList.map { it.id }) }

        if (lessonItems.isEmpty()) {
            body.addView(rowText("所选时间表里还没有「上课」时间段，无法排课。", 13f))
        }

        lessonItems.forEachIndexed { i, item ->
            val rc = card()
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(rowText("第${i + 1}节\n${item.start}", 13f, true).apply {
                layoutParams = LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            val sp = Spinner(this)
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            sp.setSelection(ids.indexOf(plan.classes.getOrNull(i)).coerceAtLeast(0))
            sp.latch { pos ->
                while (plan.classes.size <= i) plan.classes.add(null)
                plan.classes[i] = ids[pos]
            }
            r.addView(sp.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            rc.addView(r)
            body.addView(rc)
        }

        body.addView(Button(this).apply {
            text = "＋ 为${dayNames[curDay]}再建一份课表（单双周用）"
            setOnClickListener { createPlanDialog(ciDow) }
        })
        body.addView(Button(this).apply {
            text = "删除该天课表"
            setOnClickListener {
                AlertDialog.Builder(this@EditActivity)
                    .setTitle("删除课表")
                    .setMessage("确定删除「${plan.name}」吗？该操作在点保存前不会生效。")
                    .setPositiveButton("删除") { _, _ ->
                        p.plans.remove(plan.id)
                        curPlanId = null
                        render()
                    }.setNegativeButton("取消", null).show()
            }
        })
    }

    private fun createPlanDialog(ciDow: Int) {
        val firstLayout = p.layouts.values.firstOrNull()
        if (firstLayout == null) {
            toast("请先在「时间表」页签新建作息时间表")
            return
        }
        val dayName = dayNames[dayCis.indexOf(ciDow).coerceAtLeast(0)]
        AlertDialog.Builder(this)
            .setTitle("新建 $dayName 课表 — 生效周")
            .setItems(divNames) { _, which ->
                val lessonCount = firstLayout.items.count { it.isClass }
                val np = ClassPlan(
                    Profile.newId(),
                    "${dayName}课表" + if (which == 0) "" else "（${divNames[which]}）",
                    firstLayout.id,
                    TimeRule(ciDow, which, if (which == 0) 0 else 2),
                    MutableList(lessonCount) { null }, true, false
                )
                p.plans[np.id] = np
                curPlanId = np.id
                render()
                toast("已创建，点「保存并生效」后生效")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 保存 ----------------

    private fun save(silent: Boolean = false) {
        Store.saveProfile(ProfileWriter.toJson(p))
        AlertScheduler.scheduleAll(this)
        if (Store.islandEnabled && OverlayService.canDrawOverlays(this)) {
            OverlayService.stop(this)
            OverlayService.start(this)
        }
        // 档案名可能被改动 → 同步档案索引
        runCatching { Store.syncCurrentProfileName(p.name) }
        // 桌面小组件同步
        runCatching { tech.classisland.mobile.widget.ScheduleWidgetProvider.refresh(this) }
        if (!silent) toast("已保存并生效")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
