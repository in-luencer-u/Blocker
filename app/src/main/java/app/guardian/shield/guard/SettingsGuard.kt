package app.guardian.shield.guard

import android.content.Context

/**
 * Recognises the screens a user would have to reach to weaken or remove
 * protection.
 *
 * This is the layer that converts "I could just uninstall it" into "I have to
 * wait 24 hours and ask my partner". It cannot be perfect — see the limitations
 * screen — but it covers every route that is reachable by tapping.
 */
object SettingsGuard {

    /** Packages that can host app-management or network screens. */
    private val settingsPackages = setOf(
        "com.android.settings",
        "com.android.settings.intelligence",
        "com.vivo.settings",
        "com.vivo.permissionmanager",
        "com.vivo.securedaemonservice",
        "com.iqoo.secure",                     // vivo "i Manager"
        "com.vivo.abe",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.bbk.launcher2",                   // uninstall from the home screen
        "com.vivo.appstore",
        "com.android.vending",                 // Play Store app page
    )

    /** What the user is trying to do, used for the log and the partner alert. */
    enum class Danger(val description: String) {
        UNINSTALL("tried to open the uninstall screen"),
        APP_INFO("tried to open this app's settings page (force stop / clear data)"),
        ACCESSIBILITY("tried to open accessibility settings, where the guard can be switched off"),
        VPN_SETTINGS("tried to open VPN settings, where the filter can be switched off"),
        DEVICE_ADMIN("tried to open device admin settings"),
        PRIVATE_DNS("tried to change Private DNS, which would route around the filter"),
        DEVELOPER_OPTIONS("tried to open developer options"),
        PERMISSIONS("tried to revoke this app's permissions"),
    }

    private val uninstallWords = listOf("uninstall", "remove app", "delete app")
    private val appInfoWords = listOf("force stop", "clear data", "clear storage", "storage & cache")
    private val accessibilityWords = listOf(
        "accessibility", "installed apps", "downloaded apps", "installed services"
    )
    private val vpnWords = listOf("vpn", "always-on vpn", "block connections without vpn")
    private val adminWords = listOf("device admin", "device administrators", "deactivate")
    private val privateDnsWords = listOf("private dns", "private dns provider")
    private val developerWords = listOf("developer options", "usb debugging", "oem unlocking")
    private val permissionWords = listOf("permissions", "allowed", "denied", "app permissions")

    fun isSettingsLike(packageName: String?): Boolean {
        val pkg = packageName ?: return false
        if (settingsPackages.contains(pkg)) return true
        // Vendor ROMs rename these components freely; match the family too.
        return pkg.startsWith("com.vivo.") && pkg.contains("settings") ||
            pkg.startsWith("com.bbk.") && pkg.contains("settings")
    }

    /**
     * Classifies the current screen, or returns null if it is harmless.
     *
     * @param screenText all visible text, lowercased by the caller.
     * @param className the foreground window's class name, when known.
     */
    fun classify(
        context: Context,
        packageName: String?,
        className: String?,
        screenText: String,
    ): Danger? {
        if (!isSettingsLike(packageName)) return null

        val text = screenText.lowercase()
        val cls = (className ?: "").lowercase()
        val appLabel = appLabel(context).lowercase()
        val mentionsUs = text.contains(appLabel) || text.contains("shield")

        // The uninstall confirmation dialog, wherever it is hosted.
        if (mentionsUs && uninstallWords.any { text.contains(it) }) return Danger.UNINSTALL

        // Our own App Info page: force stop, clear data, or the restricted-settings menu.
        if (mentionsUs && appInfoWords.any { text.contains(it) }) return Danger.APP_INFO
        if (mentionsUs && cls.contains("installedappdetails")) return Danger.APP_INFO
        if (mentionsUs && cls.contains("appinfodashboard")) return Danger.APP_INFO

        if (mentionsUs && permissionWords.count { text.contains(it) } >= 2) return Danger.PERMISSIONS

        // Accessibility settings. Guarded whenever our service is named, because
        // that screen is one tap away from switching the guard off.
        if (mentionsUs && accessibilityWords.any { text.contains(it) }) return Danger.ACCESSIBILITY
        if (cls.contains("accessibilitysettings") || cls.contains("accessibilitydetail")) {
            return Danger.ACCESSIBILITY
        }

        if (privateDnsWords.any { text.contains(it) }) return Danger.PRIVATE_DNS

        if (cls.contains("vpnsettings") || cls.contains("vpnapp")) return Danger.VPN_SETTINGS
        if (vpnWords.count { text.contains(it) } >= 2) return Danger.VPN_SETTINGS

        if (mentionsUs && adminWords.any { text.contains(it) }) return Danger.DEVICE_ADMIN
        if (cls.contains("deviceadmin")) return Danger.DEVICE_ADMIN

        if (developerWords.count { text.contains(it) } >= 2) return Danger.DEVELOPER_OPTIONS

        return null
    }

    private var cachedLabel: String? = null

    private fun appLabel(context: Context): String {
        cachedLabel?.let { return it }
        val label = runCatching {
            context.applicationInfo.loadLabel(context.packageManager).toString()
        }.getOrDefault("shield")
        cachedLabel = label
        return label
    }
}
