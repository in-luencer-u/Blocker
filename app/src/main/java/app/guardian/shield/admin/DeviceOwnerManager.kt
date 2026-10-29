package app.guardian.shield.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import app.guardian.shield.core.Prefs
import app.guardian.shield.data.EventStore
import app.guardian.shield.data.EventType
import app.guardian.shield.vpn.ShieldVpnService

/**
 * Device owner mode — the only configuration on a non-rooted Android phone that
 * makes this app genuinely impossible to remove in a weak moment.
 *
 * A plain device admin can be deactivated by tapping through Settings. A device
 * *owner* can:
 *
 *  - block its own uninstall outright,
 *  - forbid changing VPN settings at all,
 *  - pin itself as an always-on VPN with lockdown, so no traffic flows unless
 *    the filter is up,
 *  - disable safe mode, which otherwise disables the accessibility guard,
 *  - disable factory reset and USB debugging,
 *  - force Private DNS to a family-filtering resolver.
 *
 * Device owner cannot be granted from inside the app. It has to be set once over
 * ADB on a device with no accounts added, which in practice means straight after
 * a factory reset. See SETUP.md for the exact command. Everything below is a
 * no-op until that has been done, so the app works either way.
 */
object DeviceOwnerManager {

    /** CleanBrowsing's family DoT endpoint: filters even outside our own VPN. */
    private const val PRIVATE_DNS_HOST = "family-filter-dns.cleanbrowsing.org"

    fun adminComponent(context: Context): ComponentName =
        ComponentName(context, ShieldDeviceAdminReceiver::class.java)

    private fun dpm(context: Context): DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)

    fun isAdminActive(context: Context): Boolean =
        dpm(context)?.isAdminActive(adminComponent(context)) == true

    fun isDeviceOwner(context: Context): Boolean =
        runCatching { dpm(context)?.isDeviceOwnerApp(context.packageName) == true }
            .getOrDefault(false)

    /**
     * Applies every restriction available at the current privilege level.
     * Safe to call repeatedly; each step is independent and failures are ignored
     * so one unsupported restriction cannot stop the rest.
     */
    fun applyPolicies(context: Context) {
        val dpm = dpm(context) ?: return
        val admin = adminComponent(context)

        if (!dpm.isAdminActive(admin)) return
        if (!isDeviceOwner(context)) return

        var applied = 0

        // Cannot be uninstalled, by anyone, including via adb for this user.
        applied += attempt { dpm.setUninstallBlocked(admin, context.packageName, true) }

        // The VPN cannot be switched off or replaced.
        applied += attempt { dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN) }

        // Safe mode would start the phone with accessibility services disabled.
        applied += attempt { dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT) }

        // Removes the "wipe it and start again" escape hatch.
        applied += attempt { dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET) }

        // A second user or guest session would not have the filter.
        applied += attempt { dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER) }

        // No adb, so nothing can be disabled from a computer.
        applied += attempt { dpm.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES) }

        // Stops sideloading a VPN or proxy app to take the VPN slot.
        applied += attempt {
            dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        }

        applied += attempt { dpm.addUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS) }

        // Always-on with lockdown: if the filter is not running, nothing routes.
        // This is the single most valuable policy here — it removes the gap.
        applied += attempt {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.setAlwaysOnVpnPackage(admin, context.packageName, true)
            }
        }

        // Only our guard may run as an accessibility service, so no other app can
        // be used to automate tapping through our own guard.
        applied += attempt {
            dpm.setPermittedAccessibilityServices(admin, listOf(context.packageName))
        }

        // Filter DNS even in the unlikely event the VPN is down.
        applied += attempt {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, PRIVATE_DNS_HOST)
            }
        }

        if (applied > 0 && !Prefs.onboardingComplete) {
            EventStore.get(context).log(
                EventType.PROTECTION_UP,
                "device owner policies applied ($applied)"
            )
        }
    }

    /**
     * Lifts the restrictions that would otherwise make an approved unlock
     * impossible. Only called from inside an open unlock window.
     */
    fun relaxForUnlockWindow(context: Context) {
        val dpm = dpm(context) ?: return
        val admin = adminComponent(context)
        if (!isDeviceOwner(context)) return

        attempt { dpm.setUninstallBlocked(admin, context.packageName, false) }
        attempt { dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN) }
        attempt { dpm.clearUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS) }
        attempt {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.setAlwaysOnVpnPackage(admin, null, false)
            }
        }
    }

    /** A short description of the current privilege level, for the status screen. */
    fun describeLevel(context: Context): String = when {
        isDeviceOwner(context) -> "Device owner — uninstall and VPN changes are blocked by the system"
        isAdminActive(context) -> "Device admin — uninstall requires deactivating admin first"
        else -> "Not registered — the app can be uninstalled from Settings"
    }

    private inline fun attempt(block: () -> Unit): Int =
        runCatching { block() }.fold(onSuccess = { 1 }, onFailure = { 0 })
}
