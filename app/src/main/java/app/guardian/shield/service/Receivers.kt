package app.guardian.shield.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.guardian.shield.core.Prefs
import app.guardian.shield.data.EventStore
import app.guardian.shield.data.EventType
import app.guardian.shield.dns.Blocklist

/**
 * Brings protection back after a reboot or an app update.
 *
 * Registered for more actions than strictly necessary, including the vendor
 * quick-boot broadcasts some OEM ROMs send instead of BOOT_COMPLETED.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.init(context)
        if (!Prefs.onboardingComplete) return

        Blocklist.ensureLoaded(context)

        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED ->
                EventStore.get(context).log(EventType.PROTECTION_UP, "restarted after app update")
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED ->
                EventStore.get(context).log(EventType.PROTECTION_UP, "restarted after reboot")
        }

        Watchdog.ensureEverything(context)
        Watchdog.schedule(context)
    }
}

/** Fires from the AlarmManager heartbeat. */
class WatchdogAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.init(context)
        if (!Prefs.onboardingComplete) return
        Blocklist.ensureLoaded(context)
        Watchdog.ensureEverything(context)
    }
}

/** Explicit restart request, used by in-app "fix this" buttons. */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.init(context)
        Blocklist.ensureLoaded(context)
        Watchdog.ensureEverything(context)
    }
}
