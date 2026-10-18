package tech.classisland.mobile.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import tech.classisland.mobile.alert.AlertScheduler
import tech.classisland.mobile.databinding.ActivityTempBinding
import tech.classisland.mobile.engine.ScheduleEngine
import tech.classisland.mobile.engine.Store
import tech.classisland.mobile.model.Profile
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

class TempOverrideActivity : AppCompatActivity() {

    private lateinit var b: ActivityTempBinding
    private var profile: Profile? = null

    private val weekCn = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTempBinding.inflate(layoutInflater)
        setContentView(b.root)
        title = "临时调课"
        profile = Store.loadProfile()
        if (profile == null) {
            Toast.makeText(this, "请先导入或编辑课表档案", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun render() {
        val p = profile ?: return
        b.listContainer.removeAllViews()
        val today = LocalDate.now()

        for (offset in 0..20) {
            val date = today.plusDays(offset.toLong())
            val wi = ScheduleEngine.weekIndex(date, Store.firstWeekDate)
            val overrideId = Store.tempPlan(date)
            val normal = ScheduleEngine.planFor(p, date, wi)
            val effective = ScheduleEngine.effectivePlan(p, date, wi, overrideId)
            val dowCn = weekCn[(date.dayOfWeek.value - 1).coerceIn(0, 6)]

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(tech.classisland.mobile.R.drawable.bg_card)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(8)
                layoutParams = lp
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }

            val title = TextView(this).apply {
                text = "$dowCn  ${date.monthValue}/${date.dayOfMonth}（第$wi 周）" +
                    if (offset == 0) " · 今天" else ""
                textSize = 15f
                setTextColor(0xFF1F2937.toInt())
                paint.isFakeBoldText = true
            }
            val desc = TextView(this).apply {
                textSize = 13f
                setTextColor(0xFF334155.toInt())
                text = when {
                    overrideId != null ->
                        "临时：${effective?.name ?: "无课"}（常规：${normal?.name ?: "无课"}）"
                    else -> "常规：${normal?.name ?: "无课"}"
                }
            }
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            val btnSet = Button(this).apply {
                text = if (overrideId != null) "修改临时课表" else "设为临时调课"
                textSize = 12f
                setOnClickListener { pickPlan(date) }
            }
            val btnClear = Button(this).apply {
                text = "清除"
                textSize = 12f
                visibility = if (overrideId != null) View.VISIBLE else View.GONE
                setOnClickListener {
                    Store.setTempPlan(date, null)
                    AlertScheduler.scheduleAll(this@TempOverrideActivity)
                    render()
                    toast("已清除临时调课")
                }
            }
            actions.addView(btnSet)
            actions.addView(btnClear)

            card.addView(title)
            card.addView(desc)
            card.addView(actions)
            b.listContainer.addView(card)
        }
    }

    private fun pickPlan(date: LocalDate) {
        val p = profile ?: return
        // 可选课表：全部已启用的常规课表 + 无课
        val plans = p.plans.values.filter { it.enabled && !it.overlay }
            .sortedWith(compareBy({ it.rule.weekDay }, { it.name }))
        val labels = mutableListOf("无课（当天停课）")
        labels.addAll(plans.map { it.name })
        val ids = mutableListOf<String?>("")
        ids.addAll(plans.map { it.id })

        AlertDialog.Builder(this)
            .setTitle("${date.monthValue}/${date.dayOfMonth} 使用哪份课表？")
            .setItems(labels.toTypedArray()) { _, which ->
                Store.setTempPlan(date, ids[which])
                AlertScheduler.scheduleAll(this)
                render()
                toast("已设置，当天提醒与悬浮窗会自动按临时课表执行")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(s: String) =
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
