package tech.classisland.mobile.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import tech.classisland.mobile.BuildConfig
import tech.classisland.mobile.R
import tech.classisland.mobile.alert.AlertNotifier
import tech.classisland.mobile.databinding.ViewIslandBinding
import tech.classisland.mobile.engine.Phase
import tech.classisland.mobile.engine.ScheduleEngine
import tech.classisland.mobile.engine.Store
import tech.classisland.mobile.engine.SubjectColors
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/**
 * 上课状态服务：
 *  - 悬浮窗（灵动岛）——需要悬浮窗权限；
 *  - 常驻状态通知——不需要任何权限，锁屏/无悬浮窗时也能看到当前科目与倒计时。
 *
 * 灵动岛适配版（island 变体）：窗口贴合屏幕顶部挖孔/灵动岛区域绘制，
 * 黑底白字，看起来就是系统灵动岛的一部分（Android 不开放第三方直接写入
 * 系统灵动岛，等效做法是把顶层窗口放到 cutout 区域并使用相同配色）。
 */
class OverlayService : Service() {

    companion object {
        /** app/build.gradle 中 island 变体的名字 */
        private const val ISLAND_FLAVOR = "island"
        private const val SERVICE_CHANNEL = "classisland_island_service"
        private const val NOTIF_ID_SERVICE = 10

        fun start(ctx: Context) {
            val i = Intent(ctx, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }

        fun canDrawOverlays(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

        /** 只要开启「状态通知」或「悬浮窗」其一，服务就应存活 */
        fun shouldRun(ctx: Context): Boolean =
            Store.statusNotify || (Store.islandEnabled && canDrawOverlays(ctx))

        fun startIfWanted(ctx: Context) {
            if (shouldRun(ctx)) start(ctx) else stop(ctx)
        }
    }

    private lateinit var wm: WindowManager
    private var island: View? = null
    private var b: ViewIslandBinding? = null
    private val handler = Handler(Looper.getMainLooper())
    private var expanded = false
    private var dragging = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var positionSet = false
    private var cutoutApplied = false

    /** 当前已应用的圆角（dp）与大小倍率 */
    private var lastCornerDp: Int = -1
    private var lastScale: Float = -1f

    /** 上一次推送给通知栏的内容指纹，避免每秒重复 notify */
    private var lastNotifKey = ""

    private val fired = HashSet<String>()
    private var firedDate: LocalDate? = null
    private var lastPhase: Phase? = null

    private val ticker = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID_SERVICE, buildServiceNotification("", "正在读取课表…", -1))
        maybeAddIsland()
        handler.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 由设置页开关变化驱动：需要则补上悬浮窗，不需要则移除
        if (Store.islandEnabled && canDrawOverlays(this)) maybeAddIsland() else removeIsland()
        if (!shouldRun(this)) stopSelf()
        return START_STICKY
    }

    private fun islandFlavorActive(): Boolean = BuildConfig.FLAVOR == ISLAND_FLAVOR

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

    private fun maybeAddIsland() {
        if (island != null) return
        if (!(Store.islandEnabled && canDrawOverlays(this))) return
        addIsland()
    }

    private fun removeIsland() {
        island?.let { runCatching { wm.removeView(it) } }
        island = null
        b = null
    }

    private fun addIsland() {
        val binding = ViewIslandBinding.inflate(LayoutInflater.from(this))
        b = binding
        val view = binding.root
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        if (islandFlavorActive()) {
            // —— 灵动岛适配：贴合顶部挖孔区域绘制 ——
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.x = 0
            params.y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        } else {
            params.gravity = Gravity.TOP or Gravity.START
            params.x = Store.islandX
            params.y = if (Store.islandY >= 0) Store.islandY else statusBarHeight() + 6
        }

        view.setOnClickListener {
            expanded = !expanded
            binding.islandExpanded.visibility = if (expanded) View.VISIBLE else View.GONE
            binding.btnIslandClose.visibility = if (expanded) View.VISIBLE else View.GONE
        }
        view.setOnLongClickListener {
            if (islandFlavorActive()) return@setOnLongClickListener true
            dragging = true
            true
        }
        binding.btnIslandClose.setOnClickListener {
            Store.islandEnabled = false
            removeIsland()
            if (!Store.statusNotify) stopSelf()
        }
        view.setOnTouchListener { v, e ->
            if (islandFlavorActive()) return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    initialTouchX = e.rawX; initialTouchY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> if (dragging) {
                    params.x = initialX + (e.rawX - initialTouchX).toInt()
                    params.y = initialY + (e.rawY - initialTouchY).toInt()
                    wm.updateViewLayout(v, params)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        Store.islandX = params.x
                        Store.islandY = params.y
                    }
                    dragging = false
                }
            }
            false
        }

        applyCorner(binding)

        island = view
        wm.addView(view, params)

        view.post {
            if (islandFlavorActive()) {
                applyCutoutPosition(view, params)
            } else if (!positionSet && Store.islandX < 0) {
                val dm = resources.displayMetrics
                params.x = (dm.widthPixels - view.width) / 2
                wm.updateViewLayout(view, params)
                positionSet = true
            }
        }
    }

    /** 让胶囊覆盖住挖孔（灵动岛）中心 */
    private fun applyCutoutPosition(view: View, params: WindowManager.LayoutParams) {
        if (cutoutApplied) return
        val cutout = runCatching { view.rootWindowInsets?.displayCutout }.getOrNull()
        val rect = cutout?.boundingRects?.firstOrNull()
        if (rect != null) {
            val y = rect.top + rect.height() / 2 - view.height / 2
            params.y = if (y < 0) 0 else y
            runCatching { wm.updateViewLayout(view, params) }
            cutoutApplied = true
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && cutout == null) {
            // 没有挖孔（或拿不到 insets）：贴顶居中即可
            params.y = 0
            runCatching { wm.updateViewLayout(view, params) }
            cutoutApplied = true
        }
    }

    /**
     * 悬浮窗外观：纯黑 + 可调圆角 + 可调整体大小。
     * 大小倍率按「基准尺寸 × scale」重算文字字号、内边距、进度条高度与圆角，
     * 使滑块调整时整个灵动岛等比缩放（设置页可改，运行中改动会自动同步）。
     */
    private fun applyCorner(binding: ViewIslandBinding) {
        val d = resources.displayMetrics.density
        val scale = Store.islandScale.coerceIn(0.7f, 1.6f)
        val corner = Store.islandCornerDp * d * scale

        val gd = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = corner
            setStroke((1f * d).toInt().coerceAtLeast(1), 0x2EFFFFFF)
        }
        binding.root.background = gd

        fun sp(base: Float) = (base * scale).coerceAtLeast(6f)
        fun dpInt(base: Float) = (base * d * scale).toInt().coerceAtLeast(1)

        // 容器内边距
        val hPad = (18 * d * scale).toInt()
        val vPad = (10 * d * scale).toInt()
        binding.root.setPadding(hPad, vPad, hPad, vPad)

        // 各文字与状态点
        binding.tvIslandPhase.textSize = sp(12f)
        binding.tvIslandSubject.textSize = sp(16f)
        binding.tvIslandCount.textSize = sp(16f)
        binding.islandDot.layoutParams = binding.islandDot.layoutParams.also {
            it.width = dpInt(9f); it.height = dpInt(9f)
        }
        binding.tvIslandPhase.setPadding(dpInt(7f), 0, 0, 0)
        binding.tvIslandSubject.setPadding(dpInt(10f), 0, 0, 0)
        binding.tvIslandCount.setPadding(dpInt(10f), 0, 0, 0)
        binding.btnIslandClose.setPadding(dpInt(6f), 0, dpInt(6f), 0)
        binding.btnIslandClose.textSize = sp(18f)

        // 进度条：高度与上下间距
        binding.islandProgress.layoutParams =
            (binding.islandProgress.layoutParams as android.widget.LinearLayout.LayoutParams).also {
                it.height = dpInt(4f)
                it.topMargin = dpInt(9f)
            }
        binding.tvIslandNext.textSize = sp(13f)
        binding.tvIslandHint.textSize = sp(11f)
        binding.tvIslandNext.setPadding(0, dpInt(7f), 0, 0)
        binding.tvIslandHint.setPadding(0, dpInt(4f), 0, 0)

        lastCornerDp = Store.islandCornerDp
        lastScale = scale
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 24
    }

    private fun setDot(color: Int) {
        val d = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        b?.islandDot?.background = d
    }

    private fun fmt(d: Duration): String {
        val s = d.seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, ss)
        else String.format("%02d:%02d", m, ss)
    }

    private fun update() {
        val binding = b
        val profile = Store.loadProfile()
        val today = LocalDate.now()
        if (firedDate != today) { firedDate = today; fired.clear() }

        if (profile == null) {
            setDot(Color.parseColor("#94A3B8"))
            binding?.tvIslandPhase?.text = "课表岛"
            binding?.tvIslandSubject?.text = "未导入档案"
            binding?.tvIslandCount?.text = ""
            binding?.islandProgress?.progress = 0
            pushNotification("课表岛", "尚未导入课表档案", -1)
            return
        }

        val now = LocalTime.now()
        val st = ScheduleEngine.statusAt(
            profile, today, now, Store.firstWeekDate, Store.tempPlan(today)
        )

        val phaseColor = when (st.phase) {
            Phase.IN_CLASS -> Color.parseColor("#22C55E")
            Phase.BREAK -> Color.parseColor("#F59E0B")
            else -> Color.parseColor("#94A3B8")
        }
        setDot(phaseColor)

        val phaseText = when (st.phase) {
            Phase.IN_CLASS -> "上课中"
            Phase.BREAK -> "课间"
            Phase.BEFORE_SCHOOL -> "待上课"
            Phase.AFTER_SCHOOL -> "已放学"
            Phase.NO_CLASS_TODAY -> "无课"
        }
        // 课间时主区域显示可自定义的“课间休息”，不显示下节课名（与官方一致）
        val breakLabel = Store.breakLabel

        // 当前时间段进度（上课与课间都显示进度条）
        val cur = st.currentItem
        val pct = if (cur != null) {
            val total = Duration.between(cur.start, cur.end).seconds
            val passed = Duration.between(cur.start, now).seconds
            if (total > 0) (passed * 100 / total).toInt().coerceIn(0, 100) else 0
        } else 0

        val subjectId = if (st.phase == Phase.IN_CLASS) st.currentSubjectId else st.nextSubjectId
        val subjectName = profile.subjectName(subjectId)
        val countdown = when (st.phase) {
            Phase.IN_CLASS -> fmt(Duration.between(now, st.currentItem!!.end))
            Phase.BREAK -> {
                val nextStart = st.lessons.getOrNull(st.nextClassIndex - 1)?.second?.start
                if (nextStart != null) fmt(Duration.between(now, nextStart)) else "--:--"
            }
            Phase.BEFORE_SCHOOL -> {
                val first = st.lessons.firstOrNull()?.second
                if (first != null) fmt(Duration.between(now, first.start)) else "--:--"
            }
            else -> ""
        }

        // —— 黑底白字（背景由 applyCorner 按设置的圆角绘制，此处不再覆盖） ——
        binding?.let {
            it.tvIslandPhase.text = phaseText
            it.tvIslandPhase.setTextColor(0xFFB3B3B3.toInt())
            it.tvIslandSubject.setTextColor(Color.WHITE)
            it.tvIslandCount.setTextColor(Color.WHITE)
            it.tvIslandNext.setTextColor(0xFFE5E7EB.toInt())
            it.tvIslandSubject.text = when (st.phase) {
                Phase.AFTER_SCHOOL, Phase.NO_CLASS_TODAY -> ""
                Phase.BREAK -> breakLabel
                else -> subjectName
            }
            it.tvIslandCount.text = countdown
            it.islandProgress.progress = pct
            // 进度条着色：上课用科目色，其余用青色
            it.islandProgress.progressTintList = android.content.res.ColorStateList.valueOf(
                if (st.phase == Phase.IN_CLASS && st.currentSubjectId != null)
                    SubjectColors.deep(st.currentSubjectId, subjectName)
                else Color.parseColor("#26C6DA")
            )
        }

        // 圆角/大小设置若在运行中被修改，同步刷新（无需重启服务）
        if (lastCornerDp != Store.islandCornerDp || lastScale != Store.islandScale) {
            binding?.let { applyCorner(it) }
        }

        // —— 常驻状态通知 ——
        val notifTitle = when (st.phase) {
            Phase.IN_CLASS -> "上课中 · $subjectName"
            Phase.BREAK -> "$breakLabel · 距上课 $countdown"
            Phase.BEFORE_SCHOOL -> "待上课 · $countdown"
            Phase.AFTER_SCHOOL -> "今日课程已结束"
            Phase.NO_CLASS_TODAY -> "今日无课"
        }
        val notifText = buildString {
            if (st.phase == Phase.IN_CLASS) {
                append("第${st.classIndex}节 ${st.currentItem!!.start} - ${st.currentItem.end}")
                append(" · 剩 $countdown")
            } else if (st.phase == Phase.BREAK && st.nextClassIndex > 0) {
                append("第${st.nextClassIndex}节 ${profile.subjectName(st.nextSubjectId)}")
                val nx = st.lessons.getOrNull(st.nextClassIndex - 1)?.second
                if (nx != null) append(" ${nx.start} 上课")
            } else {
                append("第${st.weekIndex}周 · ${today.monthValue}/${today.dayOfMonth}")
            }
        }
        pushNotification(notifTitle, notifText, pct)

        // —— 铃声触发（与 AlarmManager 提醒共用去重表，避免重复通知） ——
        if (st.phase == Phase.IN_CLASS && st.currentSubjectId != null) {
            fireAlert(today, st.classIndex, "on", st.currentItem!!.start,
                "上课", "第${st.classIndex}节：$subjectName")
        } else if (st.phase == Phase.BREAK) {
            val nextItem = st.lessons.getOrNull(st.nextClassIndex - 1)?.second
            if (nextItem != null && st.nextClassIndex > 0) {
                val prep = Store.prepMinutes.toLong()
                if (prep > 0) {
                    fireAlert(today, st.nextClassIndex, "prep",
                        nextItem.start.minusMinutes(prep),
                        "预备铃", "还有 $prep 分钟上${profile.subjectName(st.nextSubjectId)}")
                }
                fireAlert(today, st.nextClassIndex, "on", nextItem.start,
                    "上课", "第${st.nextClassIndex}节：${profile.subjectName(st.nextSubjectId)}")
            }
        }

        // 下课铃：对已结束的课，在结束后 60 秒容差内触发
        val prevLesson = st.lessons.lastOrNull { now >= it.second.end }
        if (prevLesson != null && Duration.between(prevLesson.second.end, now).seconds in 0..60) {
            fireAlert(today, prevLesson.first, "off", prevLesson.second.end,
                "下课", "第${prevLesson.first}节 ${profile.subjectName(prevLesson.third)} 已下课")
        }

        // 放学提醒
        val lastLessonEnd = st.lessons.lastOrNull()?.second?.end
        if (st.phase == Phase.AFTER_SCHOOL && lastLessonEnd != null &&
            lastPhase in listOf(Phase.IN_CLASS, Phase.BREAK, Phase.BEFORE_SCHOOL) &&
            Store.afterSchoolNotify
        ) {
            fireAlert(today, 99, "afterschool", lastLessonEnd,
                "放学", "今天的课程已结束，请同学们有序离开。")
        }
        lastPhase = st.phase

        // 展开区文案
        if (binding != null) {
            val sb = StringBuilder()
            if (st.phase == Phase.IN_CLASS && st.currentSubjectId != null) {
                val curSub = profile.subjects[st.currentSubjectId]
                if (curSub != null) {
                    sb.append("本节：${curSub.name}")
                    if (curSub.teacher.isNotEmpty()) sb.append("（${curSub.teacher} 老师）")
                    if (curSub.outdoor) sb.append(" · 室外课")
                    sb.append('\n')
                }
            }
            if (st.nextClassIndex > 0) {
                val nextItem = st.lessons.getOrNull(st.nextClassIndex - 1)?.second
                val teacher = profile.subjects[st.nextSubjectId]?.teacher
                sb.append("下一节：第${st.nextClassIndex}节 ${profile.subjectName(st.nextSubjectId)}")
                if (!teacher.isNullOrEmpty()) sb.append("（$teacher 老师）")
                sb.append("（${nextItem?.start} 上课）")
            } else {
                sb.append("今天的课程已全部结束")
            }
            binding.tvIslandNext.text = sb.toString()
        }
    }

    /** 内容指纹变化时才真正调用 notify，避免每秒打断系统 */
    private fun pushNotification(title: String, text: String, pct: Int) {
        val key = "$title|$text|${pct / 5}"
        if (key == lastNotifKey) return
        lastNotifKey = key
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID_SERVICE, buildServiceNotification(title, text, pct))
    }

    private fun fireAlert(day: LocalDate, idx: Int, kind: String, point: LocalTime,
                          title: String, text: String) {
        if (!Store.alertsEnabled) return
        val key = "$day|$idx|$kind"
        if (fired.contains(key)) return
        val delta = Duration.between(point, LocalTime.now()).seconds
        if (delta in -5..60) {
            if (Store.tryFireAlert(key)) {
                fired.add(key)
                AlertNotifier.notify(this, title, text)
            }
        }
    }

    private fun buildServiceNotification(title: String, text: String, pct: Int): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(SERVICE_CHANNEL, "上课状态", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (title.isEmpty()) {
            builder.setContentTitle("课表岛运行中").setContentText("正在读取课表…")
        } else {
            builder.setContentTitle(title).setContentText(text)
            if (pct in 0..100) builder.setProgress(100, pct, false)
        }
        return builder.build()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(ticker)
        island?.let { runCatching { wm.removeView(it) } }
        island = null
        b = null
    }
}
