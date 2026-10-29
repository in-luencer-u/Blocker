package app.guardian.shield.guard

/**
 * A short, narrow grace period for setup screens the app itself sends the user to.
 *
 * Without this the guard fights its own instructions: the setup checklist opens
 * OriginOS's auto-start and battery screens, which live in the same packages the
 * guard watches for tampering, so the user would be bounced straight back out
 * and could never finish setting up.
 *
 * It is deliberately limited. Only the screens in
 * [GuardAccessibilityService.bypassableDangers] can be reached this way — the
 * uninstall page, app info, VPN settings, device admin and developer options are
 * never bypassable, so walking from a permitted screen to a dangerous one still
 * gets stopped.
 */
object GuardBypass {

    private const val DEFAULT_SECONDS = 90

    @Volatile
    private var activeUntil: Long = 0L

    /** Called just before the app opens a settings screen on the user's behalf. */
    fun grant(seconds: Int = DEFAULT_SECONDS) {
        activeUntil = System.currentTimeMillis() + seconds * 1000L
    }

    val isActive: Boolean
        get() = System.currentTimeMillis() < activeUntil

    fun clear() {
        activeUntil = 0L
    }
}
