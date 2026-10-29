package app.guardian.shield.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import app.guardian.shield.core.Prefs
import app.guardian.shield.lock.LockdownManager

object VpnController {

    /** True when the user has already granted VPN consent, so we can start silently. */
    fun hasConsent(context: Context): Boolean =
        runCatching { VpnService.prepare(context) == null }.getOrDefault(false)

    /** The intent that asks for VPN consent, or null if it is already granted. */
    fun consentIntent(context: Context): Intent? =
        runCatching { VpnService.prepare(context) }.getOrNull()

    fun start(context: Context) {
        val intent = Intent(context, ShieldVpnService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    /**
     * Stops the filter. Refuses unless a partner-approved unlock window is open —
     * there is no code path that turns protection off without passing the gate.
     */
    fun stop(context: Context): Boolean {
        if (!LockdownManager.canWeakenProtection()) return false

        Prefs.filterEnabled = false
        runCatching {
            context.startService(
                Intent(context, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_STOP)
            )
        }
        return true
    }

    /** Starts the filter if it should be running but isn't. Safe to call often. */
    fun ensureRunning(context: Context) {
        if (!Prefs.onboardingComplete) return
        if (!Prefs.filterEnabled) return
        if (ShieldVpnService.isActive) return
        if (!hasConsent(context)) return
        start(context)
    }

    val isRunning: Boolean get() = ShieldVpnService.isActive
}
