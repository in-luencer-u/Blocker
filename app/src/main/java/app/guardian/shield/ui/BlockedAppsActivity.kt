package app.guardian.shield.ui

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.guardian.shield.R
import app.guardian.shield.core.Prefs
import app.guardian.shield.guard.GuardAccessibilityService
import app.guardian.shield.lock.LockdownManager

/**
 * Whole-app blocking.
 *
 * Domain filtering cannot separate the adult parts of an app from the rest: a
 * social feed, an image board or a messaging app serves everything from the same
 * few hostnames. Where that is a problem, the only honest answer is to block the
 * app itself, which the on-screen guard does by sending it to the background
 * whenever it comes to the foreground.
 */
class BlockedAppsActivity : AppCompatActivity() {

    /** Apps commonly used to reach adult content, offered as one-tap suggestions. */
    private val suggestions = listOf(
        "com.reddit.frontpage" to "Reddit",
        "com.twitter.android" to "X (Twitter)",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.instagram.android" to "Instagram",
        "org.telegram.messenger" to "Telegram",
        "com.snapchat.android" to "Snapchat",
        "com.tumblr" to "Tumblr",
        "com.discord" to "Discord",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        render()
    }

    private fun render() {
        val root = verticalRoot()
        val blocked = Prefs.blockedApps

        root.addView(titleText("Block whole apps"))
        root.addView(spacer(8))
        root.addView(bodyText(
            "Some apps mix adult content in with everything else, so no filter can " +
                "separate them. Blocking the app closes it the moment it opens."
        ))

        if (!GuardAccessibilityService.isEnabled(this)) {
            root.addView(card(accentRes = R.color.bad).apply {
                addView(headingText("The on-screen guard is off"))
                addView(spacer(6))
                addView(mutedText("App blocking does nothing until the guard is switched on."))
            })
        }

        root.addView(spacer(12))

        if (blocked.isNotEmpty()) {
            root.addView(card(accentRes = R.color.ok).apply {
                addView(headingText("Currently blocked"))
                addView(spacer(8))
                for (packageName in blocked.sorted()) {
                    addView(appRow(packageName, labelFor(packageName), isBlocked = true))
                }
            })
        }

        root.addView(card().apply {
            addView(headingText("Suggestions"))
            addView(spacer(8))
            var shown = 0
            for ((packageName, label) in suggestions) {
                if (blocked.contains(packageName)) continue
                if (!isInstalled(packageName)) continue
                addView(appRow(packageName, label, isBlocked = false))
                shown++
            }
            if (shown == 0) addView(mutedText("Nothing else to suggest."))
        })

        root.addView(card().apply {
            addView(headingText("Other installed apps"))
            addView(spacer(8))
            val others = launchableApps()
                .filterNot { blocked.contains(it.first) }
                .filterNot { pair -> suggestions.any { it.first == pair.first } }
                .sortedBy { it.second.lowercase() }

            if (others.isEmpty()) {
                addView(mutedText("No other apps found."))
            } else {
                for ((packageName, label) in others) {
                    addView(appRow(packageName, label, isBlocked = false))
                }
            }
        })

        root.addView(spacer(12))
        root.addView(secondaryButton("Back") { finish() })
        setScrollingContent(root)
    }

    private fun appRow(packageName: String, label: String, isBlocked: Boolean): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                bodyText(label),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                if (isBlocked) {
                    secondaryButton("Unblock") { unblock(packageName, label) }
                } else {
                    secondaryButton("Block") { block(packageName, label) }
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
            )
        }

    /** Blocking is tightening, so it is immediate. */
    private fun block(packageName: String, label: String) {
        Prefs.blockedApps = Prefs.blockedApps + packageName
        Toast.makeText(this, "$label is now blocked.", Toast.LENGTH_SHORT).show()
        render()
    }

    /** Unblocking is loosening, so it needs the gate. */
    private fun unblock(packageName: String, label: String) {
        if (!LockdownManager.canWeakenProtection()) {
            Toast.makeText(
                this,
                "Unblocking needs an approved unlock window: ${Prefs.cooldownHours} hours, " +
                    "then your partner's code.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        Prefs.blockedApps = Prefs.blockedApps - packageName
        Toast.makeText(this, "$label is no longer blocked.", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun launchableApps(): List<Pair<String, String>> = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        packageManager.queryIntentActivities(intent, 0)
            .asSequence()
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.packageName != packageName }
            .map { it.packageName to it.loadLabel(packageManager).toString() }
            .distinctBy { it.first }
            .toList()
    }.getOrDefault(emptyList())

    private fun isInstalled(target: String): Boolean = runCatching {
        packageManager.getApplicationInfo(target, 0)
        true
    }.getOrDefault(false)

    private fun labelFor(target: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(target, 0)).toString()
    }.getOrDefault(target)
}
