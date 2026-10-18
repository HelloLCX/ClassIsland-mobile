package tech.classisland.mobile

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 极简崩溃展示页：只依赖系统框架，避免自身也崩溃 */
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val log = runCatching {
            openFileInput("crash.log").use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrDefault("（未读取到崩溃日志）")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(36, 48, 36, 36)
        }
        val tv = TextView(this).apply {
            text = "应用发生崩溃，请把本页截图（或点复制后粘贴）发给开发者：\n\n$log"
            textSize = 13f
            setTextColor(Color.parseColor("#1F2937"))
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0
            ).apply { weight = 1f }
            addView(tv)
        }
        val btn = Button(this).apply {
            text = "复制崩溃日志"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash", log))
                Toast.makeText(this@CrashActivity, "已复制", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(scroll)
        root.addView(btn)
        setContentView(root)
    }
}
