package tech.classisland.mobile

import android.app.Application
import android.content.Intent
import android.os.Process
import tech.classisland.mobile.alert.AlertScheduler
import tech.classisland.mobile.engine.Store
import tech.classisland.mobile.overlay.OverlayService
import java.io.PrintWriter
import java.io.StringWriter

class ClassIslandApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()

        Store.init(this)
        if (!Store.hasProfile()) {
            runCatching {
                val sample = assets.open("default-profile.json").use { it.readBytes() }
                Store.saveProfile(sample.toString(Charsets.UTF_8))
            }
        }
        runCatching { AlertScheduler.scheduleAll(this) }
        // 档案索引：把已有的 profile.json 登记为第一个档案（多档案功能）
        runCatching { Store.ensureProfileIndex() }
        // 首次把示例档案写入时同步建立索引
        if (!Store.hasProfile()) runCatching { Store.ensureProfileIndex() }

        // 悬浮窗或状态通知任一开启即启动服务
        runCatching { OverlayService.startIfWanted(this) }

        // 桌面小组件
        runCatching { tech.classisland.mobile.widget.ScheduleWidgetProvider.updateAll(this) }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            val sw = StringWriter()
            sw.append("线程：${thread.name}\n")
            sw.append("设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
            sw.append("系统 API：${android.os.Build.VERSION.SDK_INT} " +
                "(${android.os.Build.VERSION.RELEASE})\n\n")
            ex.printStackTrace(PrintWriter(sw))
            val log = sw.toString()
            runCatching {
                openFileOutput("crash.log", MODE_PRIVATE).use {
                    it.write(log.toByteArray(Charsets.UTF_8))
                }
                val intent = Intent(this, CrashActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    )
                }
                startActivity(intent)
            }
            Process.killProcess(Process.myPid())
            previous?.uncaughtException(thread, ex)
        }
    }
}
