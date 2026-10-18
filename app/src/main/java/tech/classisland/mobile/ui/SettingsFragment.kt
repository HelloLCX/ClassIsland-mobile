package tech.classisland.mobile.ui

import android.app.DatePickerDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.fragment.app.Fragment
import tech.classisland.mobile.BuildConfig
import tech.classisland.mobile.R
import tech.classisland.mobile.alert.AlertNotifier
import tech.classisland.mobile.alert.AlertReceiver
import tech.classisland.mobile.alert.AlertScheduler
import tech.classisland.mobile.databinding.FragmentSettingsBinding
import tech.classisland.mobile.engine.ScheduleEngine
import tech.classisland.mobile.engine.Store
import tech.classisland.mobile.overlay.OverlayService
import java.time.LocalDate
import java.util.Calendar

class SettingsFragment : Fragment() {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) runCatching { importUri(uri) }
            .onSuccess { refresh(); toast("档案导入成功") }
            .onFailure { toast("导入失败：${it.message}") }
    }

    private val pickRingtone = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.getParcelableExtra<Uri>(
            RingtoneManager.EXTRA_RINGTONE_PICKED_URI
        )
        Store.ringtoneUri = uri?.toString() ?: ""
        refreshRingtoneLabel()
        toast("铃声已设置")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentSettingsBinding.inflate(inflater, container, false)

        b.btnImport.setOnClickListener { pickFile.launch(arrayOf("*/*")) }
        b.btnEdit.setOnClickListener {
            startActivity(Intent(requireContext(), EditActivity::class.java))
        }
        b.btnTemp.setOnClickListener {
            startActivity(Intent(requireContext(), TempOverrideActivity::class.java))
        }
        b.btnSample.setOnClickListener {
            val sample = requireContext().assets.open("default-profile.json").use { it.readBytes() }
            Store.saveProfile(sample.toString(Charsets.UTF_8))
            AlertScheduler.scheduleAll(requireContext())
            tech.classisland.mobile.widget.ScheduleWidgetProvider.refresh(requireContext())
            refresh()
            toast("已恢复内置示例档案")
        }
        b.btnFirstWeek.setOnClickListener { pickFirstWeek() }

        b.swIsland.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!OverlayService.canDrawOverlays(requireContext())) {
                    b.swIsland.isChecked = false
                    toast("请先授予悬浮窗权限")
                    openOverlaySettings()
                } else {
                    Store.islandEnabled = true
                    OverlayService.start(requireContext())
                }
            } else {
                Store.islandEnabled = false
                OverlayService.startIfWanted(requireContext())
            }
        }
        b.btnOverlayPerm.setOnClickListener { openOverlaySettings() }
        b.btnResetPos.setOnClickListener {
            Store.islandX = -1
            Store.islandY = -1
            if (Store.islandEnabled) {
                OverlayService.stop(requireContext())
                OverlayService.start(requireContext())
            }
            toast("悬浮窗位置已重置")
        }
        b.swAfterSchool.setOnCheckedChangeListener { _, v -> Store.afterSchoolNotify = v }
        b.swVibrate.setOnCheckedChangeListener { _, v -> Store.vibrate = v }
        b.btnRingtone.setOnClickListener {
            val current = Store.ringtoneUri.let { if (it.isEmpty()) null else Uri.parse(it) }
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择提醒铃声")
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
            }
            runCatching { pickRingtone.launch(intent) }
                .onFailure { toast("本机没有可用的铃声选择器") }
        }

        b.swAlerts.setOnCheckedChangeListener { _, checked ->
            Store.alertsEnabled = checked
            if (checked) {
                AlertScheduler.scheduleAll(requireContext())
            } else {
                AlertScheduler.cancelAll(requireContext())
            }
        }
        b.btnPrepMinus.setOnClickListener {
            Store.prepMinutes = Store.prepMinutes - 1; refresh()
        }
        b.btnPrepPlus.setOnClickListener {
            Store.prepMinutes = Store.prepMinutes + 1; refresh()
        }
        b.btnTestNotify.setOnClickListener { sendTestNotification() }

        refresh()
        return b.root
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun importUri(uri: Uri) {
        val bytes = requireContext().contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        Store.saveProfileBytes(bytes)
        AlertScheduler.scheduleAll(requireContext())
        // 导入后同步到桌面小组件
        tech.classisland.mobile.widget.ScheduleWidgetProvider.refresh(requireContext())
    }

    private fun openOverlaySettings() {
        val i = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${requireContext().packageName}")
        )
        runCatching { startActivity(i) }
            .onFailure {
                // 部分鸿蒙/定制 ROM 没有该 Action，退回应用详情页
                runCatching {
                    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${requireContext().packageName}"))
                    startActivity(fallback)
                }
            }
    }

    private fun pickFirstWeek() {
        val cur = Store.firstWeekDate
        val cal = Calendar.getInstance().apply {
            set(cur.year, cur.monthValue - 1, cur.dayOfMonth)
        }
        DatePickerDialog(
            requireContext(),
            { _, y, m, d ->
                Store.firstWeekDate = LocalDate.of(y, m + 1, d)
                AlertScheduler.scheduleAll(requireContext())
                refresh()
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun sendTestNotification() {
        AlertNotifier.notify(
            requireContext(),
            "测试通知",
            "如果你能看到这条通知（并听到铃声），说明上下课提醒可以正常工作。"
        )
    }

    private fun toast(s: String) {
        android.widget.Toast.makeText(requireContext(), s, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun refresh() {
        val p = Store.loadProfile()
        b.tvProfileInfo.text = if (p != null)
            "科目 ${p.subjectCount} 个 · 时间表 ${p.layoutList.size} 个 · 课表 ${p.planList.size} 份"
        else "尚未导入任何档案"
        val wi = ScheduleEngine.weekIndex(LocalDate.now(), Store.firstWeekDate)
        b.tvCurWeek.text = "今天是第 $wi 周（基准：${Store.firstWeekDate}）"
        b.swAlerts.isChecked = Store.alertsEnabled
        b.tvPrep.text = "${Store.prepMinutes}"
        b.swAfterSchool.isChecked = Store.afterSchoolNotify
        b.swVibrate.isChecked = Store.vibrate
        refreshRingtoneLabel()

        val perm = OverlayService.canDrawOverlays(requireContext())
        b.tvIslandPerm.text = when {
            !perm -> "未授予悬浮窗权限，点击下方按钮授予"
            Store.islandEnabled -> "权限已授予，悬浮窗运行中。长按可拖动，点击展开，展开后可点 × 关闭"
            else -> "权限已授予，打开上方开关即可显示悬浮窗（状态通知可独立开启）"
        }
        // 避免监听回调循环
        b.swIsland.setOnCheckedChangeListener(null)
        b.swIsland.isChecked = Store.islandEnabled && perm
        b.swIsland.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!OverlayService.canDrawOverlays(requireContext())) {
                    b.swIsland.isChecked = false
                    toast("请先授予悬浮窗权限")
                    openOverlaySettings()
                } else {
                    Store.islandEnabled = true
                    OverlayService.start(requireContext())
                    refresh()
                }
            } else {
                Store.islandEnabled = false
                OverlayService.startIfWanted(requireContext())
                refresh()
            }
        }

        // 上课状态常驻通知（新功能：无悬浮窗权限也能看到倒计时）
        b.tvIslandMode.text = if (BuildConfig.FLAVOR == "island")
            "灵动岛适配版：开启后信息贴合屏幕顶部挖孔/灵动岛显示（黑底白字）"
        else
            "标准版：使用应用自带的顶部悬浮窗（长按拖动、点击展开）"
        b.tvCorner.text = "${Store.islandCornerDp}dp"
        b.tvScale.text = String.format("%.1fx", Store.islandScale)
        b.btnProfiles.text = "档案管理（当前：${Store.currentProfileName()}）"
        b.btnBreakLabel.text = "课间显示文字：${Store.breakLabel}"

        b.swStatus.setOnCheckedChangeListener(null)
        b.swStatus.isChecked = Store.statusNotify
        b.swStatus.setOnCheckedChangeListener { _, checked ->
            Store.statusNotify = checked
            OverlayService.startIfWanted(requireContext())
            refresh()
        }

        // 悬浮窗圆角（0~40dp，步长 2）
        b.btnCornerMinus.setOnClickListener {
            Store.islandCornerDp = Store.islandCornerDp - 2
            restartIsland(); refresh()
        }
        b.btnCornerPlus.setOnClickListener {
            Store.islandCornerDp = Store.islandCornerDp + 2
            restartIsland(); refresh()
        }

        // 课间显示文字（课间不显示下节课名，改显示此状态词）
        b.btnBreakLabel.setOnClickListener { editBreakLabel() }

        // 档案管理（官方「档案」功能）
        b.btnProfiles.setOnClickListener {
            startActivity(Intent(requireContext(), ProfileActivity::class.java))
        }

        // 悬浮窗大小（0.7~1.6 倍，步长 0.1）
        b.btnScaleMinus.setOnClickListener {
            Store.islandScale = (Store.islandScale - 0.1f).coerceIn(0.7f, 1.6f)
            restartIsland(); refresh()
        }
        b.btnScalePlus.setOnClickListener {
            Store.islandScale = (Store.islandScale + 0.1f).coerceIn(0.7f, 1.6f)
            restartIsland(); refresh()
        }
        b.btnScaleReset.setOnClickListener {
            Store.islandScale = 1f
            restartIsland(); refresh()
            toast("悬浮窗大小已复位")
        }
    }

    /** 悬浮窗圆角等外观改动后立即重建悬浮窗 */
    private fun restartIsland() {
        if (Store.islandEnabled && OverlayService.canDrawOverlays(requireContext())) {
            OverlayService.stop(requireContext())
            OverlayService.start(requireContext())
        }
    }

    private fun editBreakLabel() {
        val et = android.widget.EditText(requireContext()).apply {
            setText(Store.breakLabel)
            setSelection(text.length)
            hint = "例如：课间休息 / 下课了 / 休息一下"
        }
        val box = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(et)
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("课间时显示的文字")
            .setMessage("课间休息时，悬浮窗与今日页的大字区域只显示这个状态词（不显示下节课名）。")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val v = et.text.toString().trim()
                Store.breakLabel = if (v.isEmpty()) "课间休息" else v
                refresh()
                toast("已保存")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshRingtoneLabel() {
        val uri = Store.ringtoneUri.let { if (it.isEmpty()) null else Uri.parse(it) }
        val name = if (uri == null) "系统默认"
        else runCatching {
            RingtoneManager.getRingtone(requireContext(), uri)?.getTitle(requireContext())
        }.getOrNull() ?: "自定义铃声"
        b.btnRingtone.text = "提醒铃声：$name"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
