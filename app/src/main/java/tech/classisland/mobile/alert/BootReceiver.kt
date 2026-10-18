package tech.classisland.mobile.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tech.classisland.mobile.engine.Store
import tech.classisland.mobile.overlay.OverlayService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            AlertScheduler.scheduleAll(context)
            OverlayService.startIfWanted(context)
        }
    }
}
