package tech.classisland.mobile.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import tech.classisland.mobile.R
import tech.classisland.mobile.engine.Store

/** 统一的上下课通知构建：负责渠道、铃声、震动 */
object AlertNotifier {

    const val BASE_CHANNEL = "classisland_alerts"

    fun notify(context: Context, title: String, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = ensureChannel(context, nm)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setSound(soundUri())
            if (Store.vibrate) builder.setVibrate(longArrayOf(0, 300, 200, 300))
        }
        nm.notify((System.currentTimeMillis() and 0x7FFFFFFF).toInt(), builder.build())
    }

    private fun soundUri(): Uri {
        val custom = Store.ringtoneUri
        return if (custom.isEmpty())
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        else runCatching { Uri.parse(custom) }.getOrNull()
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }

    /** 渠道 id 随铃声/震动设置变化；更换后删除旧渠道 */
    private fun ensureChannel(context: Context, nm: NotificationManager): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return BASE_CHANNEL
        val uriStr = Store.ringtoneUri
        val vib = Store.vibrate
        val id = "ca_${uriStr.hashCode()}_$vib"
        if (nm.getNotificationChannel(id) == null) {
            val ch = NotificationChannel(id, "上下课提醒", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "上课、预备与下课提醒"
            ch.setSound(soundUri(), null)
            ch.enableVibration(vib)
            if (vib) ch.vibrationPattern = longArrayOf(0, 300, 200, 300)
            nm.createNotificationChannel(ch)
            val old = Store.lastAlertChannel
            if (old.isNotEmpty() && old != id) nm.deleteNotificationChannel(old)
            Store.lastAlertChannel = id
        }
        return id
    }
}
