package app.guardian.shield.ui

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import app.guardian.shield.R
import app.guardian.shield.admin.DeviceOwnerManager
import app.guardian.shield.core.Prefs
import app.guardian.shield.data.EventStore
import app.guardian.shield.data.EventType
import app.guardian.shield.dns.Blocklist
import app.guardian.shield.guard.GuardAccessibilityService
import app.guardian.shield.lock.LockdownManager
import app.guardian.shield.service.Watchdog
import app.guardian.shield.util.OemIntents
import app.guardian.shield.vpn.VpnController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The dashboard: what is protected right now, what is not, and the one button
 * that fixes each gap.
 *
 * Rebuilt on every resume rather than kept in sync, because the user will come
 * back to this screen straight after changing a system setting elsewhere.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
    }

    override fun onResume() {
        super.onResume()

        if (!Prefs.onboardingComplete) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        Blocklist.ensureLoaded(this)
        Watchdog.ensureEverything(this)
        render()
    }

    private fun render() {
        val events = EventStore.get(this)
        val root = verticalRoot()

        root.addView(titleText(getString(R.string.app_name)))
        root.addView(mutedText(overallSummary()))
        root.addView(spacer(16))

        root.addView(progressCard(events))
        root.addView(lockdownCard())

        root.addView(spacer(4))
        root.addView(headingText("Protection layers"))
        root.addView(spacer(8))

        addProtectionCards(root)

        root.addView(spacer(8))
        root.addView(headingText("Tools"))
        root.addView(spacer(8))

        root.addView(primaryButton("I'm having an urge — help me now") {
            startActivity(
                Intent(this, UrgeActivity::class.java)
                    .putExtra(UrgeActivity.EXTRA_TRIGGER, "SELF")
            )
        })
        root.addView(secondaryButton("Report something that got through") {
            startActivity(Intent(this, ReportActivity::class.java))
        })
        root.addView(secondaryButton("My progress") {
            startActivity(Intent(this, StatsActivity::class.java))
        })
        root.addView(secondaryButton("Block specific apps") {
            startActivity(Intent(this, BlockedAppsActivity::class.java))
        })
        root.addView(secondaryButton("Re-check setup") {
            startActivity(Intent(this, OnboardingActivity::class.java))
        })
        root.addView(secondaryButton("What this cannot block (read this)") {
            startActivity(Intent(this, LimitationsActivity::class.java))
        })

        root.addView(spacer(16))
        root.addView(mutedText(
            "Everything on this screen is stored on this phone only. " +
                "There is no account and no server."
        ))

        setScrollingContent(root)
    }

    private fun overallSummary(): String {
        val gaps = countGaps()
        return when {
            gaps == 0 -> "All layers active. Protection is as strong as this phone allows."
            gaps == 1 -> "1 layer needs attention — there is a gap in your protection."
            else -> "$gaps layers need attention — there are gaps in your protection."
        }
    }

    private fun countGaps(): Int {
        var gaps = 0
        if (!VpnController.isRunning) gaps++
        if (!GuardAccessibilityService.isEnabled(this)) gaps++
        if (!DeviceOwnerManager.isAdminActive(this)) gaps++
        if (!OemIntents.isIgnoringBatteryOptimisation(this)) gaps++
        if (!OemIntents.hasOverlayPermission(this)) gaps++
        if (!Prefs.partnerConfigured) gaps++
        return gaps
    }

    // ---------------- cards ----------------

    private fun progressCard(events: EventStore): LinearLayout = card().apply {
        val streak = events.cleanDayStreak()
        val todayBlocks = events.countToday(EventType.DOMAIN_BLOCKED) +
            events.countToday(EventType.SCREEN_BLOCKED) +
            events.countToday(EventType.APP_BLOCKED)

        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(bigNumber(streak.toString()))
                addView(mutedText(if (streak == 1) "clean day" else "clean days"))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(bigNumber(todayBlocks.toString()))
                addView(mutedText("blocked today"))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })

        if (streak == 0 && todayBlocks > 0) {
            addView(spacer(8))
            addView(mutedText(
                "Today counts again from tomorrow. A blocked attempt is the system " +
                    "working, not a failure."
            ))
        }
    }

    private fun lockdownCard(): LinearLayout {
        val state = LockdownManager.state()

        return when (state) {
            LockdownManager.State.LOCKED -> card().apply {
                addView(headingText("Locked"))
                addView(spacer(6))
                addView(mutedText(
                    "Blocking cannot be switched off directly. A request takes " +
                        "${Prefs.cooldownHours} hours" +
                        if (Prefs.partnerConfigured) " and needs your partner's code." else "."
                ))
                addView(secondaryButton("Request to unlock") {
                    startActivity(Intent(this@MainActivity, UnlockActivity::class.java))
                })
            }

            LockdownManager.State.COOLING_OFF -> card(accentRes = R.color.warn).apply {
                addView(headingText("Unlock request pending"))
                addView(spacer(6))
                addView(bodyText(
                    "Unlocks in ${formatDuration(LockdownManager.remainingCooldownMillis())}."
                ))
                addView(mutedText("You can cancel this at any time, instantly."))
                addView(primaryButton("Cancel the request") {
                    LockdownManager.cancelUnlock(this@MainActivity)
                    render()
                })
                addView(secondaryButton("View request") {
                    startActivity(Intent(this@MainActivity, UnlockActivity::class.java))
                })
            }

            LockdownManager.State.AWAITING_APPROVAL -> card(accentRes = R.color.warn).apply {
                addView(headingText("Waiting for approval"))
                addView(spacer(6))
                addView(bodyText(
                    if (Prefs.partnerConfigured) {
                        "The waiting period is over. Enter the code from " +
                            Prefs.partnerName.ifBlank { "your partner" } + " to unlock."
                    } else {
                        "The waiting period is over. Open the unlock screen to confirm."
                    }
                ))
                addView(primaryButton("Cancel the request") {
                    LockdownManager.cancelUnlock(this@MainActivity)
                    render()
                })
                addView(secondaryButton("Enter approval") {
                    startActivity(Intent(this@MainActivity, UnlockActivity::class.java))
                })
            }

            LockdownManager.State.UNLOCKED -> card(accentRes = R.color.bad).apply {
                addView(headingText("Unlocked — protection can be changed"))
                addView(spacer(6))
                addView(bodyText(
                    "Closes in ${formatDuration(LockdownManager.remainingWindowMillis())}."
                ))
                addView(primaryButton("Re-lock now") {
                    LockdownManager.relock(this@MainActivity)
                    VpnController.ensureRunning(this@MainActivity)
                    render()
                })
            }
        }
    }

    private fun addProtectionCards(root: LinearLayout) {
        // 1. The DNS filter.
        root.addView(
            statusCard(
                label = "Content filter (all apps)",
                detail = if (VpnController.isRunning) {
                    "Blocking adult domains in every app, including private tabs. " +
                        "${Prefs.blocklistDomainCount} domains loaded."
                } else {
                    "Not running. No domain filtering is happening right now."
                },
                level = if (VpnController.isRunning) StatusLevel.OK else StatusLevel.BAD,
                actionLabel = if (VpnController.isRunning) null else "Turn the filter on",
                onAction = if (VpnController.isRunning) null else {
                    {
                        val consent = VpnController.consentIntent(this)
                        if (consent != null) {
                            startActivity(consent)
                        } else {
                            Prefs.filterEnabled = true
                            VpnController.start(this)
                        }
                    }
                }
            )
        )

        // 2. The on-screen guard.
        val guardOn = GuardAccessibilityService.isEnabled(this)
        root.addView(
            statusCard(
                label = "On-screen guard",
                detail = if (guardOn) {
                    "Watching for adult content inside apps and for attempts to " +
                        "switch protection off."
                } else {
                    "Off. In-app blocking and tamper protection are not working."
                },
                level = if (guardOn) StatusLevel.OK else StatusLevel.BAD,
                actionLabel = if (guardOn) null else "Turn the guard on",
                onAction = if (guardOn) null else {
                    { OemIntents.launchSetupScreen(this, OemIntents.accessibilitySettingsIntent()) }
                }
            )
        )

        // 3. Uninstall protection.
        val isOwner = DeviceOwnerManager.isDeviceOwner(this)
        val isAdmin = DeviceOwnerManager.isAdminActive(this)
        root.addView(
            statusCard(
                label = "Uninstall protection",
                detail = DeviceOwnerManager.describeLevel(this),
                level = when {
                    isOwner -> StatusLevel.OK
                    isAdmin -> StatusLevel.WARN
                    else -> StatusLevel.BAD
                },
                actionLabel = when {
                    isOwner -> null
                    isAdmin -> "How to make this unbreakable"
                    else -> "Set up uninstall protection"
                },
                onAction = when {
                    isOwner -> null
                    else -> {
                        { startActivity(Intent(this, LimitationsActivity::class.java)) }
                    }
                }
            )
        )

        // 4. Survives OriginOS background killing.
        val batteryExempt = OemIntents.isIgnoringBatteryOptimisation(this)
        root.addView(
            statusCard(
                label = "Always-on (battery settings)",
                detail = if (batteryExempt) {
                    "Exempt from battery optimisation. On this phone you should also " +
                        "check OriginOS auto-start, which has its own list."
                } else {
                    "OriginOS may kill the filter in the background, leaving gaps."
                },
                level = if (batteryExempt) StatusLevel.OK else StatusLevel.WARN,
                actionLabel = "Open battery and auto-start settings",
                onAction = { startActivity(Intent(this, OnboardingActivity::class.java)) }
            )
        )

        // 5. Overlay permission, needed for the urge screen.
        val overlay = OemIntents.hasOverlayPermission(this)
        root.addView(
            statusCard(
                label = "Urge support screen",
                detail = if (overlay) {
                    "Can appear over other apps when something is blocked."
                } else {
                    "Without \"display over other apps\", blocking still works but the " +
                        "calming screen cannot appear."
                },
                level = if (overlay) StatusLevel.OK else StatusLevel.WARN,
                actionLabel = if (overlay) null else "Allow display over other apps",
                onAction = if (overlay) null else {
                    { OemIntents.launchSetupScreen(this, OemIntents.overlayPermissionIntent(this)) }
                }
            )
        )

        // 6. Accountability partner.
        root.addView(
            statusCard(
                label = "Accountability partner",
                detail = if (Prefs.partnerConfigured) {
                    "${Prefs.partnerName.ifBlank { "Partner" }} must approve any unlock" +
                        if (Prefs.partnerSmsAlerts) " and is alerted to tampering." else "."
                } else {
                    "Not set up. Without a partner you can unlock on your own after " +
                        "the waiting period, which is much easier to do in a weak moment."
                },
                level = if (Prefs.partnerConfigured) StatusLevel.OK else StatusLevel.WARN,
                actionLabel = if (Prefs.partnerConfigured) "Partner settings" else "Add a partner",
                onAction = { startActivity(Intent(this, PartnerSetupActivity::class.java)) }
            )
        )

        // 7. Blocklist freshness.
        val updatedAt = Prefs.blocklistUpdatedAt
        val ageDays = if (updatedAt == 0L) -1
        else TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - updatedAt).toInt()
        root.addView(
            statusCard(
                label = "Block list",
                detail = buildString {
                    append("${Prefs.blocklistDomainCount} domains. ")
                    append(
                        when {
                            updatedAt == 0L -> "Using the built-in list; no update downloaded yet."
                            ageDays <= 0 -> "Updated today."
                            ageDays == 1 -> "Updated yesterday."
                            else -> "Updated $ageDays days ago."
                        }
                    )
                },
                level = when {
                    updatedAt == 0L -> StatusLevel.WARN
                    ageDays > 7 -> StatusLevel.WARN
                    else -> StatusLevel.OK
                },
                actionLabel = "Update now",
                onAction = {
                    Watchdog.updateBlocklistNow(this)
                    android.widget.Toast.makeText(
                        this, "Updating in the background…", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            )
        )
    }

    companion object {
        fun formatDuration(millis: Long): String {
            if (millis <= 0) return "0 minutes"
            val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(millis)
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return when {
                hours >= 1 && minutes > 0 -> "${hours}h ${minutes}m"
                hours >= 1 -> "${hours}h"
                totalMinutes >= 1 -> "$totalMinutes min"
                else -> "less than a minute"
            }
        }

        fun formatTimestamp(millis: Long): String =
            SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(millis))
    }
}
