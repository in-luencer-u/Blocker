package app.guardian.shield.guard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import app.guardian.shield.R
import app.guardian.shield.core.Prefs
import app.guardian.shield.data.EventStore
import app.guardian.shield.data.EventType
import app.guardian.shield.dns.Blocklist
import app.guardian.shield.lock.LockdownManager
import app.guardian.shield.partner.PartnerNotifier
import app.guardian.shield.service.Watchdog
import app.guardian.shield.ui.UnlockActivity

/**
 * The on-screen half of the protection.
 *
 * The DNS filter stops a page from loading, but it cannot see a URL typed into
 * an app that proxies its own content, and it cannot stop someone walking to
 * Settings to switch the filter off. This service watches what is actually on
 * screen and handles both.
 *
 * It is also the most reliable persistence anchor on this phone: Android
 * restarts a bound accessibility service on its own, so every screen change is
 * a free opportunity to check that the filter is still up.
 */
class GuardAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastContentCheckAt = 0L
    private var lastTamperAt = 0L
    private var lastHealthCheckAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.init(this)
        Blocklist.ensureLoaded(this)

        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 120
        }

        isConnected = true
        EventStore.get(this).log(EventType.PROTECTION_UP, "on-screen guard connected")
        Watchdog.ensureEverything(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!Prefs.onboardingComplete) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName == this.packageName) return

        // Every screen change is a chance to notice the filter died, at no cost.
        maybeHealthCheck()

        val isWindowChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val now = System.currentTimeMillis()
        if (!isWindowChange) {
            if (now - lastContentCheckAt < CONTENT_THROTTLE_MS) return
        }
        lastContentCheckAt = now

        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        try {
            val className = event.className?.toString()
            val screenText = TextScanner.collectText(root)

            if (guardSettings(packageName, className, screenText)) return
            if (guardBlockedApp(packageName)) return
            if (guardBrowsing(root, packageName)) return
            guardKeywords(root, packageName)
        } finally {
            runCatching { @Suppress("DEPRECATION") root.recycle() }
        }
    }

    // ---------------- tamper resistance ----------------

    /** @return true if this screen was handled and no further checks apply. */
    private fun guardSettings(packageName: String, className: String?, screenText: String): Boolean {
        if (!SettingsGuard.isSettingsLike(packageName)) return false

        // During an approved unlock window the user is allowed in. Blocking here
        // would make legitimate changes impossible.
        if (LockdownManager.isUnlockWindowOpen()) return false

        val danger = SettingsGuard.classify(this, packageName, className, screenText) ?: return false

        // Setup screens the app sent the user to itself. The dangerous screens are
        // not in this set, so this cannot be used as a route to the uninstall page.
        if (GuardBypass.isActive && danger in bypassableDangers) return false

        val now = System.currentTimeMillis()
        if (now - lastTamperAt < TAMPER_THROTTLE_MS) {
            backOut()
            return true
        }
        lastTamperAt = now

        LockdownManager.recordTamperAttempt(this, danger.description)
        backOut()

        // Explain the rule rather than just bouncing the user out.
        handler.postDelayed({
            runCatching {
                startActivity(
                    Intent(this, UnlockActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra(UnlockActivity.EXTRA_BLOCKED_REASON, danger.description)
                    }
                )
            }
        }, 450)

        return true
    }

    /** Navigate away, then fall back to the home screen if that was not enough. */
    private fun backOut() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 220)
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 520)
    }

    // ---------------- content blocking ----------------

    private fun guardBlockedApp(packageName: String): Boolean {
        if (!Prefs.blockedApps.contains(packageName)) return false

        EventStore.get(this).log(EventType.APP_BLOCKED, packageName)
        performGlobalAction(GLOBAL_ACTION_HOME)
        BlockSignal.maybeShowUrge(this, appLabelFor(packageName), BlockSignal.Trigger.APP)
        return true
    }

    private fun guardBrowsing(
        root: android.view.accessibility.AccessibilityNodeInfo,
        packageName: String,
    ): Boolean {
        val hosts = TextScanner.extractHosts(root)
        if (hosts.isEmpty()) return false

        val blocked = hosts.firstOrNull { Blocklist.isBlocked(it) } ?: return false

        EventStore.get(this).log(EventType.SCREEN_BLOCKED, "$blocked in $packageName")
        backOut()
        BlockSignal.maybeShowUrge(this, blocked, BlockSignal.Trigger.SCREEN)
        return true
    }

    private fun guardKeywords(
        root: android.view.accessibility.AccessibilityNodeInfo,
        packageName: String,
    ) {
        if (!Prefs.keywordGuardEnabled || !Prefs.strictMode) return

        // Needs several independent signals before acting, so ordinary pages and
        // conversations are not disrupted.
        if (TextScanner.riskScore(root) < KEYWORD_THRESHOLD) return

        EventStore.get(this).log(EventType.SCREEN_BLOCKED, "explicit text in $packageName")
        backOut()
        BlockSignal.maybeShowUrge(this, packageName, BlockSignal.Trigger.SCREEN)
    }

    // ---------------- housekeeping ----------------

    private fun maybeHealthCheck() {
        val now = System.currentTimeMillis()
        if (now - lastHealthCheckAt < HEALTH_CHECK_MS) return
        lastHealthCheckAt = now
        Watchdog.ensureEverything(this)
    }

    private fun appLabelFor(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(packageName, 0)
        ).toString()
    }.getOrDefault(packageName)

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        isConnected = false
        // Being unbound means the guard was switched off, which is exactly the
        // kind of change the partner should hear about.
        runCatching {
            EventStore.get(this).log(EventType.TAMPER_ATTEMPT, "on-screen guard was disabled")
            PartnerNotifier.alert(
                this,
                PartnerNotifier.Kind.ACCESSIBILITY_OFF,
                getString(R.string.alert_accessibility_off)
            )
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isConnected = false
        super.onDestroy()
    }

    companion object {
        private const val CONTENT_THROTTLE_MS = 400L
        private const val TAMPER_THROTTLE_MS = 2_500L
        private const val HEALTH_CHECK_MS = 60_000L
        private const val KEYWORD_THRESHOLD = 2

        /**
         * Screens a [GuardBypass] may open. Each one is needed to *strengthen*
         * protection — granting a permission, allowing auto-start, checking
         * Private DNS. Uninstall, app info, VPN settings, device admin and
         * developer options are absent on purpose and stay blocked always.
         */
        val bypassableDangers = setOf(
            SettingsGuard.Danger.PERMISSIONS,
            SettingsGuard.Danger.PRIVATE_DNS,
            SettingsGuard.Danger.ACCESSIBILITY,
        )

        @Volatile
        var isConnected: Boolean = false
            private set

        /** Reads the system setting, which is authoritative even if our process died. */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, GuardAccessibilityService::class.java)
            val enabled = runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            }.getOrNull() ?: return false

            return enabled.split(':').any {
                val component = ComponentName.unflattenFromString(it)
                component == expected ||
                    (component?.packageName == context.packageName &&
                        component?.className?.contains("GuardAccessibilityService") == true)
            }
        }
    }
}
