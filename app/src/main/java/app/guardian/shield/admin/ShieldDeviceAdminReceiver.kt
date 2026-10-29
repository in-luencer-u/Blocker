package app.guardian.shield.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import app.guardian.shield.R
import app.guardian.shield.core.Prefs
import app.guardian.shield.data.EventStore
import app.guardian.shield.data.EventType
import app.guardian.shield.lock.LockdownManager
import app.guardian.shield.partner.PartnerNotifier
import app.guardian.shield.service.Watchdog

/**
 * Device admin registration.
 *
 * As a plain admin this mostly buys one thing: Android refuses to uninstall an
 * app while it is an active device administrator, so removal requires visiting
 * the admin screen first — which the on-screen guard watches. As device owner
 * (see [DeviceOwnerManager]) it becomes genuinely binding.
 */
class ShieldDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Prefs.init(context)
        EventStore.get(context).log(EventType.PROTECTION_UP, "device admin enabled")
        DeviceOwnerManager.applyPolicies(context)
    }

    /**
     * Shown in the system confirmation dialog when the user tries to deactivate
     * admin. The text is the last chance to make the rule explicit.
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Prefs.init(context)
        LockdownManager.recordTamperAttempt(context, "tried to deactivate device admin")
        return context.getString(R.string.admin_disable_warning)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Prefs.init(context)
        EventStore.get(context).log(EventType.TAMPER_ATTEMPT, "device admin was deactivated")
        PartnerNotifier.alert(
            context,
            PartnerNotifier.Kind.TAMPER,
            "Shield: device admin was turned off, so the app can now be uninstalled."
        )
        // The filter itself is independent of admin, so keep it alive.
        Watchdog.ensureEverything(context)
    }
}
