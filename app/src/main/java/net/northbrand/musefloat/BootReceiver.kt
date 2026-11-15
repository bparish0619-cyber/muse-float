package net.northbrand.musefloat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Brings the bubble back after a reboot, but only when the user left it
 * enabled and the boot toggle on the main screen is on.
 *
 * Android is allowed to refuse a background foreground-service start; when
 * it does, this receiver simply gives up quietly instead of crashing, and
 * the bubble comes back the next time Muse Float is opened and started.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.enabled(context) || !Prefs.bootEnabled(context)) return
        // Without the overlay permission the service could not draw anyway.
        if (!Settings.canDrawOverlays(context)) return
        try {
            val service = Intent(context, FloatingService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(service)
            } else {
                context.startService(service)
            }
        } catch (e: Exception) {
            Log.i("MuseFloat", "Boot restart refused by the platform; skipping.", e)
        }
    }
}
