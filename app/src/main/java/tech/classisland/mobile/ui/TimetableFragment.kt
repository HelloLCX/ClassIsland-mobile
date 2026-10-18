package tech.classisland.mobile.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import tech.classisland.mobile.R
import tech.classisland.mobile.alert.AlertScheduler
import tech.classisland.mobile.databinding.FragmentTimetableBinding
import tech.classisland.mobile.databinding.ItemPlanDayBinding
import tech.classisland.mobile.databinding.ItemPlanLessonBinding
import tech.classisland.mobile.engine.ScheduleEngine
import tech.classisland.mobile.engine.Store
import tech.classisland.mobile.model.Profile
import java.time.DayOfWeek
import java.time.LocalDate

class TimetableFragment : Fragment() {

    private var _b: FragmentTimetableBinding? = null
    private val b get() = _b!!
    private var weekOffset = 0L

    /** 与今日页同理：adapter 只创建一次，避免整表重置导致滚动位置丢失 */
    private var adapter: WeekAdapter? = null

    private val dayNames = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** ClassIsland 星期编号：0=周日 1..6=周一..周六 */
    private val ciDayNames = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentTimetableBinding.inflate(inflater, container, false)
        b.rvWeek.layoutManager = LinearLayoutManager(requireContext())
        b.rvWeek.isNestedScrollingEnabled = false
        val a = WeekAdapter()
        adapter = a
        b.rvWeek.adapter = a

        b.btnPrevWeek.setOnClickListener { weekOffset--; render() }
        b.btnNextWeek.setOnClickListener { weekOffset++; render() }
        b.btnEdit.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), EditActivity::class.java))
        }
        b.btnTemp.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), TempOverrideActivity::class.java))
        }
        render()
        return b.root
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val profile = Store.loadProfile() ?: return
        val thisMonday = LocalDate.now().with(DayOfWeek.MONDAY)
        val monday = thisMonday.plusWeeks(weekOffset)
        val sunday = monday.plusDays(6)
        val wi = ScheduleEngine.weekIndex(monday, Store.firstWeekDate)

        b.tvWeekTitle.text = "第 $wi 周  ${monday.monthValue}/${monday.dayOfMonth} - ${sunday.monthValue}/${sunday.dayOfMonth}"

        val days = (0..6).map { monday.plusDays(it.toLong()) }
        adapter?.submit(profile, days)
    }

    /**
     * 长按某天：把这一天临时换成别的课表。
     * 这是桌面版「临时调课」功能在课表页的快捷入口。
     */
    private fun quickTempPlan(date: LocalDate) {
        val profile = Store.loadProfile() ?: return
        val plans = profile.plans.values.filter { it.enabled && !it.overlay }
            .sortedWith(compareBy({ it.rule.weekDay }, { it.name }))
        if (plans.isEmpty()) {
            Toast.makeText(requireContext(), "还没有可用课表", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = mutableListOf("无课（当天停课）")
        labels.addAll(plans.map {
            "${it.name}（${ciDayNames[it.rule.weekDay.coerceIn(0, 6)]}）"
        })
        val ids = mutableListOf<String?>("")
        ids.addAll(plans.map { it.id })
        val cur = Store.tempPlan(date)
        val checked = ids.indexOf(cur).coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle("${date.monthValue}/${date.dayOfMonth} 临时使用哪份课表？")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                Store.setTempPlan(date, ids[which])
                AlertScheduler.scheduleAll(requireContext())
                d.dismiss()
                render()
                Toast.makeText(requireContext(), "已设置临时课表", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("清除临时调课") { _, _ ->
                Store.setTempPlan(date, null)
                AlertScheduler.scheduleAll(requireContext())
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        adapter = null
        _b = null
    }

    private inner class WeekAdapter :
        androidx.recyclerview.widget.RecyclerView.Adapter<WeekAdapter.VH>() {

        private var profile: Profile? = null
        private var days: List<LocalDate> = emptyList()

        inner class VH(val b: ItemPlanDayBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemPlanDayBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount() = days.size

        fun submit(profile: Profile, days: List<LocalDate>) {
            val changed = this.days != days
            this.profile = profile
            this.days = days
            if (changed) notifyDataSetChanged()
            else notifyItemRangeChanged(0, days.size)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = profile ?: return
            val date = days[position]
            val wi = ScheduleEngine.weekIndex(date, Store.firstWeekDate)
            val overrideId = Store.tempPlan(date)
            val plan = ScheduleEngine.effectivePlan(p, date, wi, overrideId)
            val layout = plan?.let { p.layouts[it.layoutId] }

            holder.b.tvDayTitle.text = "${dayNames[position]}  ${date.monthValue}/${date.dayOfMonth}" +
                (plan?.let { " · ${it.name}" } ?: " · 无课") +
                when {
                    overrideId != null && overrideId.isNotEmpty() -> "（临时调课）"
                    overrideId != null -> "（临时停课）"
                    else -> ""
                }

            holder.b.root.setOnLongClickListener {
                quickTempPlan(date)
                true
            }

            holder.b.lessonContainer.removeAllViews()
            val lessons = layout?.items?.filter { it.isClass } ?: emptyList()
            if (lessons.isEmpty()) {
                val tv = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_plan_lesson, holder.b.lessonContainer, false)
                val row = ItemPlanLessonBinding.bind(tv)
                row.tvPName.text = "—"
                holder.b.lessonContainer.addView(tv)
                return
            }
            lessons.forEachIndexed { i, item ->
                val tv = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_plan_lesson, holder.b.lessonContainer, false)
                val row = ItemPlanLessonBinding.bind(tv)
                row.tvPIndex.text = "${i + 1}"
                row.tvPTime.text = "${item.start} - ${item.end}"
                val sid = plan!!.classes.getOrNull(i)
                val nm = p.subjectName(sid)
                row.tvPName.text = nm
                val bg = tech.classisland.mobile.engine.SubjectColors.bg(sid, nm)
                val deep = tech.classisland.mobile.engine.SubjectColors.deep(sid, nm)
                val gd = android.graphics.drawable.GradientDrawable()
                gd.setColor(bg)
                gd.cornerRadius = 18f * row.tvPIndex.resources.displayMetrics.density
                row.tvPIndex.background = gd
                row.tvPIndex.setTextColor(deep)
                holder.b.lessonContainer.addView(tv)
            }
        }
    }
}
