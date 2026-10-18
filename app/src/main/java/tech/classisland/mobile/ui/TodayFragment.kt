package tech.classisland.mobile.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import tech.classisland.mobile.databinding.FragmentTodayBinding
import tech.classisland.mobile.databinding.ItemTodayLessonBinding
import tech.classisland.mobile.engine.Phase
import tech.classisland.mobile.engine.ScheduleEngine
import tech.classisland.mobile.engine.Store
import tech.classisland.mobile.engine.SubjectColors
import tech.classisland.mobile.model.Profile
import tech.classisland.mobile.model.TimeItem
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class TodayFragment : Fragment() {

    private var _b: FragmentTodayBinding? = null
    private val b get() = _b!!
    private val handler = Handler(Looper.getMainLooper())
    private var adapter: LessonAdapter? = null

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentTodayBinding.inflate(inflater, container, false)
        b.rvToday.layoutManager = LinearLayoutManager(requireContext())
        // 列表在 ScrollView 内部，禁用自身嵌套滚动，交给外层 ScrollView 统一滚动
        b.rvToday.isNestedScrollingEnabled = false
        // 关键修复：adapter 只创建一次。
        // 原实现每秒 setAdapter() 一次，RecyclerView 会丢弃全部子 View 并把
        // 锚点重置到位置 0，导致“今日课程”列表每秒跳回顶部、后面的课程永远看不到。
        val la = LessonAdapter()
        adapter = la
        b.rvToday.adapter = la
        b.btnStats.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), StatsActivity::class.java))
        }
        return b.root
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(ticker)
        adapter = null
        _b = null
    }

    private fun fmtClock(t: LocalTime): String = t.format(DateTimeFormatter.ofPattern("HH:mm"))

    private fun fmtCountdown(d: Duration): String {
        val total = d.seconds.coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    private fun setDot(color: Int) {
        val d = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        b.dotPhase.background = d
    }

    private fun render() {
        val binding = _b ?: return
        val profile = Store.loadProfile()
        if (profile == null) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.rvToday.visibility = View.GONE
            binding.tvStats.visibility = View.GONE
            return
        }
        binding.tvEmpty.visibility = View.GONE
        binding.rvToday.visibility = View.VISIBLE

        val now = LocalTime.now()
        val today = LocalDate.now()
        val st = ScheduleEngine.statusAt(
            profile, today, now, Store.firstWeekDate, Store.tempPlan(today)
        )

        binding.tvIslandWeek.text = "第${st.weekIndex}周 · ${today.monthValue}/${today.dayOfMonth}"

        val (phaseText, phaseColor) = when (st.phase) {
            Phase.IN_CLASS -> "上课中" to Color.parseColor("#22C55E")
            Phase.BREAK -> "课间" to Color.parseColor("#F59E0B")
            Phase.BEFORE_SCHOOL -> "尚未开始" to Color.parseColor("#94A3B8")
            Phase.AFTER_SCHOOL -> "已放学" to Color.parseColor("#94A3B8")
            Phase.NO_CLASS_TODAY -> "今日无课" to Color.parseColor("#94A3B8")
        }
        binding.tvPhase.text = phaseText
        setDot(phaseColor)

        // 大科目与倒计时
        var teacherLine = ""
        when (st.phase) {
            Phase.IN_CLASS -> {
                binding.tvBigSubject.text = profile.subjectName(st.currentSubjectId)
                binding.tvCountLabel.text = "距离下课"
                val end = st.currentItem!!.end
                binding.tvCountdown.text = fmtCountdown(Duration.between(now, end))
                teacherLine = profile.subjects[st.currentSubjectId]?.teacher.orEmpty()
            }
            Phase.BREAK -> {
                // 课间：主区域只显示当前状态（可自定义），不显示下节课名
                binding.tvBigSubject.text = Store.breakLabel
                binding.tvCountLabel.text = "距离上课"
                val nextStart = st.lessons.getOrNull(st.nextClassIndex - 1)?.second?.start
                binding.tvCountdown.text = if (nextStart != null)
                    fmtCountdown(Duration.between(now, nextStart)) else "--:--"
                teacherLine = ""
            }
            Phase.BEFORE_SCHOOL -> {
                binding.tvBigSubject.text = profile.subjectName(st.nextSubjectId)
                binding.tvCountLabel.text = "距离第一节上课"
                val nextStart = st.lessons.firstOrNull()?.second?.start
                binding.tvCountdown.text = if (nextStart != null)
                    fmtCountdown(Duration.between(now, nextStart)) else "--:--"
                teacherLine = profile.subjects[st.nextSubjectId]?.teacher.orEmpty()
            }
            Phase.AFTER_SCHOOL -> {
                binding.tvBigSubject.text = "今日课程已结束"
                binding.tvCountLabel.text = ""
                binding.tvCountdown.text = "放学啦"
            }
            Phase.NO_CLASS_TODAY -> {
                binding.tvBigSubject.text = "今日无课"
                binding.tvCountLabel.text = ""
                binding.tvCountdown.text = "--:--"
            }
        }
        binding.tvTeacher.text = if (teacherLine.isEmpty()) "" else "老师：$teacherLine"
        binding.tvTeacher.visibility = if (teacherLine.isEmpty()) View.GONE else View.VISIBLE

        // 进度条
        val item: TimeItem? = st.currentItem
        if (item != null) {
            val total = Duration.between(item.start, item.end).seconds
            val passed = Duration.between(item.start, now).seconds
            binding.progressTime.progress = if (total > 0)
                (passed * 100 / total).toInt().coerceIn(0, 100) else 0
            binding.progressTime.visibility = View.VISIBLE
        } else {
            binding.progressTime.visibility = View.INVISIBLE
        }

        // 今日进度（移植自桌面版状态卡片）：已上节次 + 距放学
        binding.tvStats.visibility = View.VISIBLE
        val totalLessons = st.lessons.size
        val doneLessons = st.lessons.count { now >= it.second.end }
        val lastEnd = st.lessons.lastOrNull()?.second?.end
        binding.tvStats.text = when {
            totalLessons == 0 -> "今日没有安排课程"
            st.phase == Phase.AFTER_SCHOOL || (lastEnd != null && !now.isBefore(lastEnd)) ->
                "今日已上 $doneLessons/$totalLessons 节 · 已放学"
            else -> "已上 $doneLessons/$totalLessons 节 · 距放学 " +
                fmtCountdown(Duration.between(now, lastEnd!!))
        }

        // 下节预告
        binding.tvNext.text = if (st.nextClassIndex > 0) {
            val nextItem = st.lessons.getOrNull(st.nextClassIndex - 1)?.second
            val teacher = profile.subjects[st.nextSubjectId]?.teacher
            "下一节：第${st.nextClassIndex}节 ${profile.subjectName(st.nextSubjectId)}" +
                (if (teacher.isNullOrEmpty()) "" else "（$teacher 老师）") +
                "（${nextItem?.let { fmtClock(it.start) }} 上课）"
        } else "今天的课程已全部结束"

        // 只提交数据，永不重复 setAdapter，滚动位置得以保留
        adapter?.submit(profile, st.lessons, st.classIndex, now)
    }

    private class LessonAdapter : RecyclerView.Adapter<LessonAdapter.VH>() {

        private var profile: Profile? = null
        private var lessons: List<Triple<Int, TimeItem, String?>> = emptyList()
        private var states: List<String> = emptyList()
        private var sig: String = ""

        /** 科目与课程的显示相关数据签名 */
        private fun dataSignature(
            p: Profile, ls: List<Triple<Int, TimeItem, String?>>
        ): String = buildString {
            p.subjects.forEach { (id, sub) ->
                append(id).append('=').append(sub.name).append('/')
                append(sub.initial).append('/').append(sub.teacher).append('/').append(sub.outdoor).append(';')
            }
            append('#')
            ls.forEach { (idx, it, sid) ->
                append(idx).append('@').append(it.start).append('-').append(it.end)
                append('/').append(it.type).append('/').append(sid ?: "").append(';')
            }
        }

        class VH(val b: ItemTodayLessonBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemTodayLessonBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = lessons.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (idx, item, sid) = lessons[position]
            val state = states.getOrNull(position) ?: "未开始"
            val p = profile ?: return
            with(holder.b) {
                tvIndex.text = "$idx"
                val name = p.subjectName(sid)
                tvLessonName.text = name
                tvLessonTime.text = "${item.start} - ${item.end}"
                val bg = SubjectColors.bg(sid, name)
                val deep = SubjectColors.deep(sid, name)
                val gd = GradientDrawable()
                gd.setColor(bg)
                gd.cornerRadius = 19f * root.resources.displayMetrics.density
                tvIndex.background = gd
                tvIndex.setTextColor(deep)
                tvLessonState.text = state
                tvLessonState.setTextColor(
                    Color.parseColor(
                        when (state) {
                            "上课中" -> "#22C55E"
                            "已结束" -> "#94A3B8"
                            else -> "#1E88E5"
                        }
                    )
                )
            }
        }

        private fun computeStates(now: LocalTime, currentIndex: Int): List<String> =
            lessons.map { (idx, item, _) ->
                when {
                    idx == currentIndex -> "上课中"
                    now >= item.end -> "已结束"
                    else -> "未开始"
                }
            }

        /** 提交新数据；仅在真正变化时刷新受影响的行，避免整表重排导致滚动跳动 */
        fun submit(
            profile: Profile,
            lessons: List<Triple<Int, TimeItem, String?>>,
            currentIndex: Int,
            now: LocalTime
        ) {
            // raw 字段不参与结构比较，这里改用显式签名
            val newSig = dataSignature(profile, lessons)
            val listChanged = newSig != sig
            if (listChanged) {
                this.profile = profile
                this.lessons = lessons
                this.sig = newSig
                this.states = computeStates(now, currentIndex)
                notifyDataSetChanged()
                return
            }
            this.profile = profile
            val newStates = computeStates(now, currentIndex)
            val changed = newStates.indices.filter { newStates[it] != states[it] }
            if (changed.isNotEmpty()) {
                states = newStates
                changed.forEach { notifyItemChanged(it) }
            }
        }
    }
}
