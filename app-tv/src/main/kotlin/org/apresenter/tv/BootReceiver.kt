package org.apresenter.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Optionally starts the TV Signage receiver automatically after TV Box boot when enabled in settings.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("apresenter_tv_signage", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("boot_start", false)) return
        val launch = Intent(context, TvMainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(launch)
    }
}
