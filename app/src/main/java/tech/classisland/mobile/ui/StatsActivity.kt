package tech.classisland.mobile.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import tech.classisland.mobile.R
import tech.classisland.mobile.databinding.ActivityStatsBinding
import tech.classisland.mobile.engine.ScheduleEngine
import tech.classisland.mobile.engine.StatsEngine
import tech.classisland.mobile.engine.Store
import tech.classisland.mobile.engine.SubjectColors
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 本周课时统计（对应官方「统计」面板）。
 *
 * 展示：
 *  - 每周总课时 / 总节数 / 总时长；
 *  - 逐日课时；
 *  - 各科目课时排行（按官方口径：空课不计入，单双周只在对应周计入）；
 *  - 逐日课表明细。
 */
class StatsActivity : AppCompatActivity() {

    private lateinit var b: ActivityStatsBinding
    private var monday: LocalDate = LocalDate.now().with(DayOfWeek.MONDAY)

    private val dayNames = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(b.root)
        title = "课时统计"
        b.btnPrevWeek.setOnClickListener { monday = monday.minusWeeks(1); render() }
        b.btnNextWeek.setOnClickListener { monday = monday.plusWeeks(1); render() }
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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

    private fun txt(s: String, sp: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(if (bold) 0xFF1F2937.toInt() else 0xFF334155.toInt())
        if (bold) paint.isFakeBoldText = true
    }

    private fun render() {
        val profile = Store.loadProfile()
        b.content.removeAllViews()
        if (profile == null) {
            b.content.addView(txt("还没有课表档案，请先导入。", 15f))
            return
        }
        val stats = StatsEngine.weekStats(
            profile, monday, Store.firstWeekDate, Store.allTempPlans()
        )

        val sun = stats.monday.plusDays(6)
        b.tvTitle.text = "第 ${stats.weekIndex} 周  ${stats.monday.monthValue}/${stats.monday.dayOfMonth}-${sun.monthValue}/${sun.dayOfMonth}"

        // ---- 概览 ----
        val head = card()
        head.addView(txt("总课时 ${stats.totalClassHours}", 22f, true))
        head.addView(txt("共 ${stats.totalLessons} 节 · 总时长 ${stats.totalMinutes} 分钟", 14f))
        if (!stats.hasData) {
            head.addView(txt("本周没有安排课程", 14f).apply {
                setTextColor(Color.parseColor("#94A3B8"))
            })
        }
        b.content.addView(head)

        // ---- 逐日 ----
        val dayCard = card()
        dayCard.addView(txt("每日课时", 16f, true))
        stats.days.forEachIndexed { i, d ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(txt(dayNames[i], 14f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(txt(
                if (d.lessons > 0) "${d.lessons} 节 · ${d.classHours} 课时" else "无课",
                14f
            ).apply {
                setTextColor(
                    if (d.lessons > 0) 0xFF1E88E5.toInt() else Color.parseColor("#94A3B8")
                )
            })
            dayCard.addView(row)
        }
        b.content.addView(dayCard)

        // ---- 科目排行 ----
        if (stats.subjects.isNotEmpty()) {
            val subjCard = card()
            subjCard.addView(txt("科目课时排行", 16f, true))
            val max = stats.subjects.first().lessons.toDouble().coerceAtLeast(1.0)
            val barWidth = (resources.displayMetrics.widthPixels * 0.62).toInt()
            stats.subjects.forEach { s ->
                subjCard.addView(txt(
                    "${s.name}   ${s.lessons} 节 · ${s.classHours} 课时", 14f
                ).apply { setPadding(0, dp(6), 0, 0) })
                val bar = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
                    ).apply { topMargin = dp(3); bottomMargin = dp(3) }
                    setBackgroundColor(0xFFE2E8F0.toInt())
                }
                val fill = android.view.View(this@StatsActivity).apply {
                    setBackgroundColor(SubjectColors.deep(s.subjectId, s.name))
                }
                bar.addView(fill, LinearLayout.LayoutParams(
                    (barWidth * (s.lessons / max)).toInt().coerceAtLeast(dp(6)),
                    LinearLayout.LayoutParams.MATCH_PARENT
                ))
                subjCard.addView(bar)
            }
            b.content.addView(subjCard)
        }

        // ---- 逐日明细 ----
        val detail = card()
        detail.addView(txt("课时明细", 16f, true))
        if (stats.days.none { it.lessons > 0 }) {
            detail.addView(txt("本周无课。", 14f))
        } else {
            stats.days.forEachIndexed { i, d ->
                if (d.lessons <= 0) return@forEachIndexed
                val date = d.date
                val wi = ScheduleEngine.weekIndex(date, Store.firstWeekDate)
                val plan = ScheduleEngine.effectivePlan(profile, date, wi, Store.tempPlan(date))
                val layout = plan?.let { profile.layouts[it.layoutId] }
                val classItems = layout?.items?.filter { it.isClass } ?: emptyList()
                detail.addView(txt(
                    "${dayNames[i]} ${date.monthValue}/${date.dayOfMonth}", 14f, true
                ).apply { setPadding(0, dp(8), 0, 0) })
                classItems.forEachIndexed { k, item ->
                    val sid = plan!!.classes.getOrNull(k)
                    if (sid.isNullOrEmpty()) return@forEachIndexed
                    detail.addView(txt(
                        "  第${k + 1}节  ${profile.subjectName(sid)}  ${item.start}-${item.end}", 13f
                    ))
                }
            }
        }
        b.content.addView(detail)

        b.content.addView(txt(
            "统计口径：1 节课按 45 分钟折算为 1 课时；空课不计入；单双周课仅在对应周次计入；已应用临时调课。",
            12f
        ).apply {
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(4), dp(4), dp(4), dp(10))
        })
    }
}
