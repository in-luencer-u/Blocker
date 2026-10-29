package app.guardian.shield.guard

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import app.guardian.shield.core.Prefs
import app.guardian.shield.ui.UrgeActivity

/**
 * Decides when to put the urge-support screen in front of the user.
 *
 * Any layer can report a block, but only one screen should appear, and only when
 * the user is actually looking at the phone. A page load can fire dozens of
 * blocked lookups; showing a calming screen thirty times would be the opposite
 * of calming.
 */
object BlockSignal {

    enum class Trigger { DOMAIN, SCREEN, APP }

    private const val MIN_GAP_MS = 20_000L

    @Volatile
    var lastBlockedLabel: String = ""
        private set

    @Volatile
    private var lastShownAt: Long = 0L

    fun publishDomainBlock(context: Context, host: String) {
        lastBlockedLabel = host
        maybeShowUrge(context, host, Trigger.DOMAIN)
    }

    fun maybeShowUrge(context: Context, label: String, trigger: Trigger) {
        if (!Prefs.onboardingComplete) return

        val now = System.currentTimeMillis()
        if (now - lastShownAt < MIN_GAP_MS) return
        if (!isScreenInteractive(context)) return

        // Launching an activity from the background needs the overlay permission
        // on modern Android. Without it the block still happens; the user just
        // sees the page fail instead of a calming screen.
        if (!canLaunchFromBackground(context)) return

        lastShownAt = now
        lastBlockedLabel = label

        runCatching {
            context.startActivity(
                Intent(context, UrgeActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                    putExtra(UrgeActivity.EXTRA_LABEL, label)
                    putExtra(UrgeActivity.EXTRA_TRIGGER, trigger.name)
                }
            )
        }
    }

    private fun isScreenInteractive(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isInteractive == true
    }.getOrDefault(false)

    private fun canLaunchFromBackground(context: Context): Boolean = runCatching {
        Settings.canDrawOverlays(context)
    }.getOrDefault(false)
}
