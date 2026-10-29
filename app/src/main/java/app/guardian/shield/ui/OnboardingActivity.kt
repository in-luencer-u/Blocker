package app.guardian.shield.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.guardian.shield.R
import app.guardian.shield.admin.DeviceOwnerManager
import app.guardian.shield.core.Prefs
import app.guardian.shield.data.EventStore
import app.guardian.shield.dns.Blocklist
import app.guardian.shield.guard.GuardAccessibilityService
import app.guardian.shield.service.Watchdog
import app.guardian.shield.util.OemIntents
import app.guardian.shield.vpn.VpnController

/**
 * Guided setup.
 *
 * Written as a checklist rather than a wizard on purpose: on this phone some
 * steps open vendor screens that do not report a result, so the user has to come
 * back and see for themselves what is still outstanding. Each item shows its own
 * live state, so the list is also the permanent "re-check setup" screen.
 */
class OnboardingActivity : AppCompatActivity() {

    private var motivationField: EditText? = null

    private val vpnConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Prefs.filterEnabled = true
            VpnController.start(this)
        } else {
            Toast.makeText(
                this,
                "Without this permission no filtering can happen at all.",
                Toast.LENGTH_LONG
            ).show()
        }
        render()
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Blocklist.ensureLoaded(this)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val root = verticalRoot()

        root.addView(titleText(if (Prefs.onboardingComplete) "Setup check" else "Set up Shield"))
        root.addView(mutedText(
            "Work down the list. Each item says what it does and why it matters. " +
                "The filter is only as strong as the weakest item here."
        ))
        root.addView(spacer(18))

        addMotivationStep(root)
        addFilterStep(root)
        addGuardStep(root)
        addOverlayStep(root)
        addNotificationStep(root)
        addBatteryStep(root)
        if (OemIntents.isVivoRom()) addVivoSteps(root)
        addPrivateDnsStep(root)
        addAdminStep(root)
        addPartnerStep(root)

        root.addView(spacer(12))

        val missing = essentialStepsMissing()
        if (missing.isEmpty()) {
            root.addView(primaryButton(
                if (Prefs.onboardingComplete) "Done" else "Finish setup and lock it in"
            ) {
                saveMotivation()
                completeSetup()
            })
        } else {
            root.addView(card(accentRes = R.color.warn).apply {
                addView(headingText("Still needed"))
                addView(spacer(6))
                addView(mutedText(missing.joinToString("\n") { "• $it" }))
            })
            if (Prefs.onboardingComplete) {
                root.addView(secondaryButton("Back to dashboard") { finish() })
            }
        }

        setScrollingContent(root)
    }

    // ---------------- steps ----------------

    private fun addMotivationStep(root: LinearLayout) {
        root.addView(card().apply {
            addView(headingText("1. Why you are doing this"))
            addView(spacer(6))
            addView(mutedText(
                "Written in your own words and shown back to you on the urge screen, " +
                    "when you will least want to read it. Stays on this phone."
            ))
            val field = editField(
                hint = "e.g. I want to be present with the people I love",
                initial = Prefs.motivation,
                multiline = true
            )
            motivationField = field
            addView(field)
            addView(secondaryButton("Save") {
                saveMotivation()
                Toast.makeText(this@OnboardingActivity, "Saved", Toast.LENGTH_SHORT).show()
            })
        })
    }

    private fun addFilterStep(root: LinearLayout) {
        val running = VpnController.isRunning
        val hasConsent = VpnController.hasConsent(this)

        root.addView(statusCard(
            label = "2. Content filter — required",
            detail = when {
                running -> "Active. Every app on the phone resolves names through " +
                    "Shield, so blocked sites fail to load in any browser, including " +
                    "private and incognito tabs, and inside other apps."
                hasConsent -> "Permission granted but the filter is not running."
                else -> "Shield needs to run as a local VPN. It does not send your " +
                    "traffic anywhere: it answers name lookups on the device and " +
                    "refuses the ones on the block list."
            },
            level = if (running) StatusLevel.OK else StatusLevel.BAD,
            actionLabel = if (running) null else "Turn on the filter",
            onAction = if (running) null else {
                {
                    val intent = VpnController.consentIntent(this)
                    if (intent != null) {
                        vpnConsent.launch(intent)
                    } else {
                        Prefs.filterEnabled = true
                        VpnController.start(this)
                        render()
                    }
                }
            }
        ))
    }

    private fun addGuardStep(root: LinearLayout) {
        val on = GuardAccessibilityService.isEnabled(this)

        root.addView(statusCard(
            label = "3. On-screen guard — required",
            detail = if (on) {
                "Active. Closes adult content that appears inside apps and blocks " +
                    "the settings screens that could switch protection off."
            } else {
                "Off. This is the layer that makes the app hard to remove, and the " +
                    "only one that can see content inside apps that host their own."
            },
            level = if (on) StatusLevel.OK else StatusLevel.BAD,
            actionLabel = if (on) null else "Open accessibility settings",
            onAction = if (on) null else {
                { OemIntents.launchSetupScreen(this, OemIntents.accessibilitySettingsIntent()) }
            }
        ))

        // Android 13 and later hide accessibility for sideloaded apps behind a
        // second confirmation. Without this note the switch simply appears greyed
        // out and most people give up here.
        if (!on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            root.addView(card(accentRes = R.color.warn).apply {
                addView(headingText("If the switch is greyed out"))
                addView(spacer(6))
                addView(bodyText(
                    "Android blocks accessibility for apps installed outside the Play " +
                        "Store until you allow it explicitly:\n\n" +
                        "1. Open this app's info page (button below)\n" +
                        "2. Tap the three dots at the top right\n" +
                        "3. Tap \"Allow restricted settings\"\n" +
                        "4. Go back to accessibility settings and turn the guard on"
                ))
                addView(secondaryButton("Open this app's info page") {
                    OemIntents.launch(this@OnboardingActivity, OemIntents.appDetailsIntent(this@OnboardingActivity))
                })
            })
        }
    }

    private fun addOverlayStep(root: LinearLayout) {
        val granted = OemIntents.hasOverlayPermission(this)
        root.addView(statusCard(
            label = "4. Display over other apps",
            detail = if (granted) {
                "The urge support screen can appear on top of whatever was blocked."
            } else {
                "Blocking works without this, but the calming screen cannot appear " +
                    "over another app."
            },
            level = if (granted) StatusLevel.OK else StatusLevel.WARN,
            actionLabel = if (granted) null else "Allow",
            onAction = if (granted) null else {
                { OemIntents.launchSetupScreen(this, OemIntents.overlayPermissionIntent(this)) }
            }
        ))
    }

    private fun addNotificationStep(root: LinearLayout) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        root.addView(statusCard(
            label = "5. Notifications",
            detail = if (granted) {
                "Shield can show the status notice and warn you if protection drops."
            } else {
                "Android needs this to let the filter run permanently in the background."
            },
            level = if (granted) StatusLevel.OK else StatusLevel.WARN,
            actionLabel = if (granted) null else "Allow notifications",
            onAction = if (granted) null else {
                { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        ))
    }

    private fun addBatteryStep(root: LinearLayout) {
        val exempt = OemIntents.isIgnoringBatteryOptimisation(this)
        root.addView(statusCard(
            label = "6. Ignore battery optimisation",
            detail = if (exempt) {
                "Android will not stop the filter to save power."
            } else {
                "Android may stop the filter in the background, which creates gaps " +
                    "in protection."
            },
            level = if (exempt) StatusLevel.OK else StatusLevel.WARN,
            actionLabel = if (exempt) null else "Allow unrestricted battery use",
            onAction = if (exempt) null else {
                { OemIntents.launchSetupScreen(this, OemIntents.batteryOptimisationIntent(this)) }
            }
        ))
    }

    /**
     * OriginOS keeps its own kill lists, separate from Android's battery
     * optimisation. These two screens are the usual reason a blocker stops
     * working on a vivo phone after a day or two, and neither reports a result,
     * so they are shown as manual steps the user confirms themselves.
     */
    private fun addVivoSteps(root: LinearLayout) {
        root.addView(card(accentRes = R.color.warn).apply {
            addView(headingText("7. OriginOS auto-start — important on this phone"))
            addView(spacer(6))
            addView(bodyText(
                "Your vivo phone keeps a separate allowlist that Android cannot see. " +
                    "If Shield is not on it, OriginOS will close the filter when the " +
                    "screen is off and protection will silently stop.\n\n" +
                    "On OriginOS 6 the setting is here:\n" +
                    "Settings > More settings > Applications > Autostart > turn Shield on\n\n" +
                    "Then allow high background power use:\n" +
                    "Settings > Battery > Background power consumption > Shield > Allow"
            ))
            addView(secondaryButton("Open auto-start settings") {
                val intent = OemIntents.autoStartIntent(this@OnboardingActivity)
                if (intent == null) {
                    Toast.makeText(
                        this@OnboardingActivity,
                        "Could not open it directly. Go to Settings > More settings > " +
                            "Applications > Autostart and turn Shield on.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                OemIntents.launchSetupScreen(this@OnboardingActivity, intent)
            })
            addView(secondaryButton("Open background power settings") {
                OemIntents.launchSetupScreen(
                    this@OnboardingActivity,
                    OemIntents.backgroundPowerIntent(this@OnboardingActivity)
                )
            })
            addView(spacer(8))
            addView(mutedText(
                "Also recommended, in Recent apps: swipe down on the Shield card to " +
                    "lock it, so clearing recents does not close it."
            ))
        })
    }

    private fun addPrivateDnsStep(root: LinearLayout) {
        val mode = runCatching {
            Settings.Global.getString(contentResolver, "private_dns_mode")
        }.getOrNull()

        // "hostname" means the user has pinned their own DoT resolver, which the
        // system uses ahead of our VPN's DNS and would bypass filtering.
        val risky = mode.equals("hostname", ignoreCase = true)

        root.addView(statusCard(
            label = "8. Private DNS",
            detail = when {
                risky -> "Private DNS is set to a custom provider. That sends name " +
                    "lookups straight out of the phone, around Shield. Set it to " +
                    "Automatic or Off."
                mode == null -> "Could not read this setting. Check that Private DNS " +
                    "is set to Automatic or Off, not a custom hostname."
                else -> "Set to \"$mode\", which keeps lookups inside the filter."
            },
            level = if (risky) StatusLevel.BAD else StatusLevel.OK,
            actionLabel = "Open Private DNS settings",
            onAction = { OemIntents.launchSetupScreen(this, OemIntents.privateDnsIntent()) }
        ))
    }

    private fun addAdminStep(root: LinearLayout) {
        val owner = DeviceOwnerManager.isDeviceOwner(this)
        val admin = DeviceOwnerManager.isAdminActive(this)

        root.addView(statusCard(
            label = "9. Uninstall protection",
            detail = when {
                owner -> "Device owner. The system itself refuses to uninstall Shield " +
                    "or change VPN settings."
                admin -> "Device admin. Uninstalling requires turning this off first, " +
                    "which the guard blocks and reports to your partner."
                else -> "Not set up. Shield can currently be uninstalled from Settings."
            },
            level = when {
                owner -> StatusLevel.OK
                admin -> StatusLevel.WARN
                else -> StatusLevel.BAD
            },
            actionLabel = if (admin || owner) null else "Turn on uninstall protection",
            onAction = if (admin || owner) null else {
                {
                    val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(
                            android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                            DeviceOwnerManager.adminComponent(this)
                        )
                        .putExtra(
                            android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            getString(R.string.device_admin_description)
                        )
                    runCatching { startActivity(intent) }
                    Unit
                }
            }
        ))

        if (!owner) {
            root.addView(card().apply {
                addView(headingText("The strongest option"))
                addView(spacer(6))
                addView(mutedText(
                    "Device admin can still be switched off by tapping through " +
                        "Settings. Device owner cannot — but it can only be set from a " +
                        "computer on a phone with no accounts signed in. If you want " +
                        "protection you genuinely cannot undo, read this."
                ))
                addView(secondaryButton("How to set device owner") {
                    startActivity(Intent(this@OnboardingActivity, LimitationsActivity::class.java))
                })
            })
        }
    }

    private fun addPartnerStep(root: LinearLayout) {
        root.addView(statusCard(
            label = "10. Accountability partner",
            detail = if (Prefs.partnerConfigured) {
                "${Prefs.partnerName.ifBlank { "Your partner" }} holds the approval code. " +
                    "You cannot unlock without them."
            } else {
                "Strongly recommended. Without a partner, you can unlock on your own " +
                    "once the waiting period ends — which is exactly the moment you " +
                    "will want to."
            },
            level = if (Prefs.partnerConfigured) StatusLevel.OK else StatusLevel.WARN,
            actionLabel = if (Prefs.partnerConfigured) "Partner settings" else "Add a partner",
            onAction = { startActivity(Intent(this, PartnerSetupActivity::class.java)) }
        ))
    }

    // ---------------- completion ----------------

    private fun essentialStepsMissing(): List<String> {
        val missing = ArrayList<String>()
        if (!VpnController.isRunning) missing.add("Turn on the content filter")
        if (!GuardAccessibilityService.isEnabled(this)) missing.add("Turn on the on-screen guard")
        if (Prefs.motivation.isBlank() && motivationField?.text?.isBlank() != false) {
            missing.add("Write down why you are doing this")
        }
        return missing
    }

    private fun saveMotivation() {
        val typed = motivationField?.text?.toString()?.trim()
        if (!typed.isNullOrBlank()) Prefs.motivation = typed
    }

    private fun completeSetup() {
        val firstTime = !Prefs.onboardingComplete
        Prefs.onboardingComplete = true

        if (Prefs.streakStartDay == 0) {
            Prefs.streakStartDay = EventStore.dayIndex(System.currentTimeMillis())
        }

        DeviceOwnerManager.applyPolicies(this)
        Watchdog.schedule(this)
        Watchdog.ensureEverything(this)
        if (firstTime) Watchdog.updateBlocklistNow(this)

        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
