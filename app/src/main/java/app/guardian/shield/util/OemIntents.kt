package app.guardian.shield.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Deep links into the settings screens this phone needs, including the vivo and
 * OriginOS ones that have no public API.
 *
 * OriginOS keeps its own background-start allowlist and its own power manager,
 * separate from stock Android's battery optimisation. An app can be exempt from
 * Android's battery optimisation and still be killed by OriginOS, which is the
 * usual reason a blocker quietly stops working on a vivo phone. Each entry below
 * is a candidate component; the first one that resolves on this ROM is used, and
 * if none do, the user is sent to the top-level settings screen with
 * instructions.
 */
object OemIntents {

    // ---------------- stock Android ----------------

    fun isIgnoringBatteryOptimisation(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

    @Suppress("BatteryLife")
    fun batteryOptimisationIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun overlayPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            .setData(Uri.parse("package:${context.packageName}"))

    fun notificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun privateDnsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent("android.settings.PRIVATE_DNS_SETTINGS")
        } else {
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        }

    fun vpnSettingsIntent(): Intent = Intent("android.net.vpn.SETTINGS")

    fun hasOverlayPermission(context: Context): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    // ---------------- vivo / OriginOS ----------------

    /** OriginOS background-start ("Auto-start") allowlist. */
    private val autoStartCandidates = listOf(
        ComponentName(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
        ),
        ComponentName(
            "com.iqoo.secure",
            "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
        ),
        ComponentName(
            "com.iqoo.secure",
            "com.iqoo.secure.safeguard.SoftPermissionDetailActivity"
        ),
        ComponentName(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.PurviewTabActivity"
        ),
        ComponentName(
            "com.iqoo.secure",
            "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
        ),
    )

    /** OriginOS high-background-power-consumption allowlist. */
    private val backgroundPowerCandidates = listOf(
        ComponentName(
            "com.vivo.abe",
            "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"
        ),
        ComponentName(
            "com.iqoo.secure",
            "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
        ),
        ComponentName(
            "com.iqoo.secure",
            "com.iqoo.secure.ui.powersaving.PowerSavingManagerActivity"
        ),
    )

    /** OriginOS per-app battery / "allow high background power usage". */
    private val batteryManagerCandidates = listOf(
        ComponentName("com.iqoo.secure", "com.iqoo.secure.MainActivity"),
        ComponentName("com.vivo.secure", "com.vivo.secure.MainActivity"),
    )

    fun autoStartIntent(context: Context): Intent? = firstResolvable(context, autoStartCandidates)

    fun backgroundPowerIntent(context: Context): Intent? =
        firstResolvable(context, backgroundPowerCandidates)

    fun batteryManagerIntent(context: Context): Intent? =
        firstResolvable(context, batteryManagerCandidates)

    /** True when this looks like a vivo/iQOO ROM, so the extra steps are shown. */
    fun isVivoRom(): Boolean {
        val fields = listOf(Build.MANUFACTURER, Build.BRAND, Build.PRODUCT)
            .joinToString(" ") { it.lowercase() }
        return fields.contains("vivo") || fields.contains("iqoo")
    }

    private fun firstResolvable(context: Context, candidates: List<ComponentName>): Intent? {
        val pm = context.packageManager
        for (component in candidates) {
            val intent = Intent().setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolved = runCatching {
                pm.resolveActivity(intent, 0) != null
            }.getOrDefault(false)
            if (resolved) return intent
        }
        return null
    }

    /**
     * Opens a screen the user needs in order to *strengthen* protection, and
     * grants the on-screen guard a short grace period so it does not bounce the
     * user straight back out of the vendor settings it also watches.
     *
     * Never use this for the app info, uninstall or VPN screens.
     */
    fun launchSetupScreen(context: Context, intent: Intent?): Boolean {
        app.guardian.shield.guard.GuardBypass.grant()
        return launch(context, intent)
    }

    /** Opens [intent], falling back to the generic settings screen. */
    fun launch(context: Context, intent: Intent?): Boolean {
        val target = intent ?: Intent(Settings.ACTION_SETTINGS)
        return runCatching {
            context.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }
}
