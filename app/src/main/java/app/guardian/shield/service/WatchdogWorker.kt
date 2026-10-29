package app.guardian.shield.service

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import app.guardian.shield.R
import app.guardian.shield.core.Prefs
import app.guardian.shield.data.EventStore
import app.guardian.shield.data.EventType
import app.guardian.shield.guard.GuardAccessibilityService
import app.guardian.shield.partner.PartnerNotifier
import app.guardian.shield.vpn.VpnController

/**
 * Periodic health check. Restores whatever is missing and makes any gap visible
 * in the log instead of letting it pass unnoticed.
 */
class WatchdogWorker(
    private val context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        if (!Prefs.onboardingComplete) return Result.success()

        val events = EventStore.get(context)

        // The filter should be running unless the user is inside an approved window.
        if (Prefs.filterEnabled && !VpnController.isRunning) {
            if (VpnController.hasConsent(context)) {
                events.log(EventType.PROTECTION_DOWN, "filter was not running; restarting")
                VpnController.start(context)
            } else {
                // Consent was revoked, which needs the user to act. Make noise.
                events.log(EventType.TAMPER_ATTEMPT, "VPN consent missing")
                PartnerNotifier.alert(
                    context,
                    PartnerNotifier.Kind.VPN_REVOKED,
                    context.getString(R.string.alert_vpn_revoked)
                )
            }
        }

        if (!GuardAccessibilityService.isEnabled(context)) {
            events.log(EventType.PROTECTION_DOWN, "on-screen guard is disabled")
            PartnerNotifier.alert(
                context,
                PartnerNotifier.Kind.ACCESSIBILITY_OFF,
                context.getString(R.string.alert_accessibility_off)
            )
        }

        Watchdog.ensureEverything(context)
        return Result.success()
    }
}
