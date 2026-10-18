package tech.classisland.mobile.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import tech.classisland.mobile.R
import tech.classisland.mobile.alert.AlertScheduler
import tech.classisland.mobile.databinding.ActivityProfilesBinding
import tech.classisland.mobile.engine.Store
import tech.classisland.mobile.overlay.OverlayService
import tech.classisland.mobile.widget.ScheduleWidgetProvider

/**
 * 档案管理（对应官方「档案」功能）。
 *
 * 支持：新建（以当前档案为模板）、切换、重命名、删除。
 * 每个档案独立保存为 profile_<id>.json，与官方档案格式一致，
 * 导出/导入仍走 EditActivity 的官方 JSON 通道。
 */
class ProfileActivity : AppCompatActivity() {

    private lateinit var b: ActivityProfilesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityProfilesBinding.inflate(layoutInflater)
        setContentView(b.root)
        title = "档案管理"
        b.btnNew.setOnClickListener { newProfile() }
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /** 档案切换后，所有依赖档案的功能都要跟着刷新 */
    private fun afterSwitch() {
        AlertScheduler.scheduleAll(this)
        OverlayService.startIfWanted(this)
        ScheduleWidgetProvider.refresh(this)
    }

    private fun render() {
        b.list.removeAllViews()
        val list = Store.profileList()
        val current = Store.currentProfileId()

        if (list.isEmpty()) {
            b.list.addView(tv("还没有档案，点下方「新建档案」", 15f))
            return
        }

        list.forEach { e ->
            val isCurrent = e.id == current
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(10)
                layoutParams = lp
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }

            val title = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            title.addView(tv(e.name, 16f, true).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            if (isCurrent) {
                title.addView(tv("使用中", 12f).apply {
                    setTextColor(0xFF22C55E.toInt())
                    paint.isFakeBoldText = true
                })
            }
            card.addView(title)

            // 每个档案的规模概览
            val detail = runCatching {
                if (e.id == current) {
                    val p = Store.loadProfile()
                    if (p != null) "科目 ${p.subjectCount} · 课表 ${p.planList.size} 份" else ""
                } else ""
            }.getOrDefault("")
            if (detail.isNotEmpty()) card.addView(tv(detail, 13f))

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(8), 0, 0)
            }

            if (!isCurrent) {
                row.addView(btn("切换") {
                    if (Store.switchProfile(e.id)) {
                        afterSwitch()
                        render()
                        toast("已切换到「${e.name}」")
                    } else {
                        toast("切换失败")
                    }
                })
            }

            row.addView(btn("重命名") {
                val et = EditText(this).apply { setText(e.name) }
                AlertDialog.Builder(this)
                    .setTitle("重命名档案")
                    .setView(et)
                    .setPositiveButton("保存") { _, _ ->
                        val n = et.text.toString().trim()
                        if (n.isEmpty()) return@setPositiveButton
                        if (e.id == current) {
                            Store.renameCurrentProfile(n)
                            afterSwitch()
                        } else {
                            // 非当前档案：读-改-写回其独立文件
                            renameInactive(e.id, n)
                        }
                        render()
                        toast("已重命名")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            })

            if (list.size > 1) {
                row.addView(btn("删除") {
                    AlertDialog.Builder(this)
                        .setTitle("删除档案")
                        .setMessage("确定删除「${e.name}」吗？此操作不可撤销。")
                        .setPositiveButton("删除") { _, _ ->
                            if (Store.deleteProfile(e.id)) {
                                afterSwitch()
                                render()
                                toast("已删除")
                            } else toast("删除失败")
                        }
                        .setNegativeButton("取消", null)
                        .show()
                })
            }
            card.addView(row)
            b.list.addView(card)
        }
    }

    /** 重命名非当前档案（直接改其 JSON 的 Name 与索引条目） */
    private fun renameInactive(id: String, newName: String) {
        runCatching {
            val f = java.io.File(filesDir, "profile_$id.json")
            if (!f.exists()) return@runCatching
            val p = tech.classisland.mobile.model.ProfileParser.parse(f.readText())
            p.name = newName
            f.writeText(tech.classisland.mobile.model.ProfileWriter.toJson(p))
            Store.renameProfileName(id, newName)
        }
    }

    private fun newProfile() {
        val et = EditText(this).apply {
            hint = "例如：下学期课表"
            setText("新档案 ${Store.profileList().size + 1}")
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(et)
            addView(tv("新档案会以当前课表为模板创建，之后可独立编辑。", 12f).apply {
                setTextColor(0xFF94A3B8.toInt())
            })
        }
        AlertDialog.Builder(this)
            .setTitle("新建档案")
            .setView(box)
            .setPositiveButton("创建") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isEmpty()) return@setPositiveButton
                val e = Store.duplicateCurrentProfile(n)
                if (e != null) {
                    afterSwitch()
                    render()
                    toast("已创建「${e.name}」")
                } else toast("创建失败：请先导入一个课表档案")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun tv(s: String, sp: Float, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(if (bold) 0xFF1F2937.toInt() else 0xFF334155.toInt())
        if (bold) paint.isFakeBoldText = true
    }

    private fun btn(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        textSize = 12f
        val lp = LinearLayout.LayoutParams(dp(76), dp(40))
        lp.marginStart = dp(8)
        layoutParams = lp
        setOnClickListener { onClick() }
    }
}
