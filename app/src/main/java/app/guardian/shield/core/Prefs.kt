package app.guardian.shield.core

import android.content.Context
import android.content.SharedPreferences

/**
 * All persistent configuration.
 *
 * Deliberate design rule: every setter that *weakens* protection goes through
 * [app.guardian.shield.lock.LockdownManager], never directly from the UI. The
 * setters here are dumb storage; the gate lives one layer up.
 */
object Prefs {

    private const val FILE = "shield_prefs"
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun prefs(): SharedPreferences = sp

    // ---------------- onboarding ----------------
    var onboardingComplete: Boolean
        get() = prefs().getBoolean("onboarding_complete", false)
        set(v) = prefs().edit().putBoolean("onboarding_complete", v).apply()

    /** The user's own words about why they started. Shown back to them on the urge screen. */
    var motivation: String
        get() = prefs().getString("motivation", "") ?: ""
        set(v) = prefs().edit().putString("motivation", v).apply()

    // ---------------- protection ----------------
    var filterEnabled: Boolean
        get() = prefs().getBoolean("filter_enabled", true)
        set(v) = prefs().edit().putBoolean("filter_enabled", v).apply()

    /** Strict mode also blocks apps that mix adult content with normal content. */
    var strictMode: Boolean
        get() = prefs().getBoolean("strict_mode", true)
        set(v) = prefs().edit().putBoolean("strict_mode", v).apply()

    var safeSearchEnabled: Boolean
        get() = prefs().getBoolean("safe_search", true)
        set(v) = prefs().edit().putBoolean("safe_search", v).apply()

    var keywordGuardEnabled: Boolean
        get() = prefs().getBoolean("keyword_guard", true)
        set(v) = prefs().edit().putBoolean("keyword_guard", v).apply()

    var blockedApps: Set<String>
        get() = prefs().getStringSet("blocked_apps", emptySet()) ?: emptySet()
        set(v) = prefs().edit().putStringSet("blocked_apps", v).apply()

    // ---------------- lockdown ----------------
    /** Cooling-off period before any loosening takes effect. Can be raised, never lowered. */
    var cooldownHours: Int
        get() = prefs().getInt("cooldown_hours", 24).coerceAtLeast(MIN_COOLDOWN_HOURS)
        set(v) {
            val current = prefs().getInt("cooldown_hours", 24)
            val safe = v.coerceAtLeast(maxOf(current, MIN_COOLDOWN_HOURS))
            prefs().edit().putInt("cooldown_hours", safe).apply()
        }

    /** 0 = no request pending. Otherwise epoch millis when the request was made. */
    var unlockRequestedAt: Long
        get() = prefs().getLong("unlock_requested_at", 0L)
        set(v) = prefs().edit().putLong("unlock_requested_at", v).apply()

    var unlockReason: String
        get() = prefs().getString("unlock_reason", "") ?: ""
        set(v) = prefs().edit().putString("unlock_reason", v).apply()

    /** Epoch millis until which settings may be changed. 0 = closed. */
    var unlockWindowUntil: Long
        get() = prefs().getLong("unlock_window_until", 0L)
        set(v) = prefs().edit().putLong("unlock_window_until", v).apply()

    /** Guards against replaying the same partner TOTP code twice. */
    var lastUsedTotpCounter: Long
        get() = prefs().getLong("last_totp_counter", 0L)
        set(v) = prefs().edit().putLong("last_totp_counter", v).apply()

    // ---------------- partner ----------------
    var partnerName: String
        get() = prefs().getString("partner_name", "") ?: ""
        set(v) = prefs().edit().putString("partner_name", v).apply()

    var partnerPhone: String
        get() = prefs().getString("partner_phone", "") ?: ""
        set(v) = prefs().edit().putString("partner_phone", v).apply()

    var partnerSmsAlerts: Boolean
        get() = prefs().getBoolean("partner_sms", false)
        set(v) = prefs().edit().putBoolean("partner_sms", v).apply()

    /** Encrypted TOTP seed. Written once at setup, never displayed again. */
    var partnerSecretEnc: String?
        get() = prefs().getString("partner_secret", null)
        set(v) = prefs().edit().putString("partner_secret", v).apply()

    val partnerConfigured: Boolean
        get() = !partnerSecretEnc.isNullOrBlank()

    // ---------------- stats ----------------
    var streakStartDay: Int
        get() = prefs().getInt("streak_start_day", 0)
        set(v) = prefs().edit().putInt("streak_start_day", v).apply()

    var lastSeenDay: Int
        get() = prefs().getInt("last_seen_day", 0)
        set(v) = prefs().edit().putInt("last_seen_day", v).apply()

    var totalBlocked: Long
        get() = prefs().getLong("total_blocked", 0L)
        set(v) = prefs().edit().putLong("total_blocked", v).apply()

    var totalTamperAttempts: Long
        get() = prefs().getLong("total_tamper", 0L)
        set(v) = prefs().edit().putLong("total_tamper", v).apply()

    // ---------------- blocklist ----------------
    var blocklistUpdatedAt: Long
        get() = prefs().getLong("blocklist_updated_at", 0L)
        set(v) = prefs().edit().putLong("blocklist_updated_at", v).apply()

    var blocklistDomainCount: Int
        get() = prefs().getInt("blocklist_count", 0)
        set(v) = prefs().edit().putInt("blocklist_count", v).apply()

    var autoUpdateBlocklist: Boolean
        get() = prefs().getBoolean("auto_update", true)
        set(v) = prefs().edit().putBoolean("auto_update", v).apply()

    const val MIN_COOLDOWN_HOURS = 24
    /** How long settings stay editable once a partner approves. Intentionally short. */
    const val UNLOCK_WINDOW_MINUTES = 10
}
