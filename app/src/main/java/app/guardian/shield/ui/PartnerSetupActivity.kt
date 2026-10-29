package app.guardian.shield.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.guardian.shield.R
import app.guardian.shield.core.KeystoreCrypto
import app.guardian.shield.core.Prefs
import app.guardian.shield.lock.Base32
import app.guardian.shield.lock.LockdownManager
import app.guardian.shield.lock.Totp
import app.guardian.shield.partner.PartnerNotifier

/**
 * Adds the person who holds the key.
 *
 * The approval mechanism is a standard TOTP seed held by the partner in any
 * authenticator app. That choice does three useful things at once: it needs no
 * server and no account, it works with no internet on either phone, and the
 * secret only ever exists on the partner's device plus inside this phone's
 * hardware keystore — so the user cannot read it back out later and generate
 * their own codes.
 *
 * The seed is shown exactly once, on a screen that blocks screenshots, and the
 * partner must prove they stored it by reading back a working code before setup
 * is accepted.
 */
class PartnerSetupActivity : AppCompatActivity() {

    private enum class Step { DETAILS, SHOW_SECRET, MANAGE }

    private var step = Step.DETAILS
    private var pendingSecret: ByteArray? = null

    private var nameField: EditText? = null
    private var phoneField: EditText? = null
    private var verifyField: EditText? = null

    private val smsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Prefs.partnerSmsAlerts = granted
        if (!granted) {
            Toast.makeText(
                this,
                "Without SMS permission your partner cannot be alerted automatically.",
                Toast.LENGTH_LONG
            ).show()
        }
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)

        // The seed must never end up in a screenshot or the recents preview.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        step = if (Prefs.partnerConfigured) Step.MANAGE else Step.DETAILS
        render()
    }

    private fun render() {
        val root = verticalRoot()
        when (step) {
            Step.DETAILS -> renderDetails(root)
            Step.SHOW_SECRET -> renderSecret(root)
            Step.MANAGE -> renderManage(root)
        }
        root.addView(spacer(12))
        root.addView(secondaryButton("Back") { finish() })
        setScrollingContent(root)
    }

    // ---------------- step 1 ----------------

    private fun renderDetails(root: LinearLayout) {
        root.addView(titleText("Add your partner"))
        root.addView(spacer(8))
        root.addView(bodyText(
            "Pick someone you would find it genuinely awkward to call at 2am. " +
                "They will hold the code that unlocks blocking, and they will be told " +
                "if you try to tamper with it."
        ))
        root.addView(spacer(16))

        root.addView(card().apply {
            addView(headingText("Their details"))
            val name = editField("Their name", Prefs.partnerName)
            nameField = name
            addView(name)
            val phone = editField("Their phone number (for alerts)", Prefs.partnerPhone)
            phoneField = phone
            addView(phone)
            addView(spacer(8))
            addView(mutedText(
                "The number is used to send alert texts straight from this phone. " +
                    "No server is involved and no account is created."
            ))
        })

        root.addView(primaryButton("Next — generate their code") {
            val name = nameField?.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                Toast.makeText(this, "Please enter their name.", Toast.LENGTH_SHORT).show()
                return@primaryButton
            }
            Prefs.partnerName = name
            Prefs.partnerPhone = phoneField?.text?.toString()?.trim().orEmpty()
            pendingSecret = Totp.generateSecret()
            step = Step.SHOW_SECRET
            render()
        })
    }

    // ---------------- step 2 ----------------

    private fun renderSecret(root: LinearLayout) {
        val secret = pendingSecret ?: run {
            step = Step.DETAILS
            render()
            return
        }

        root.addView(titleText("Give this to ${Prefs.partnerName}"))
        root.addView(spacer(8))
        root.addView(bodyText(
            "Hand them the phone, or read this out while they type it into an " +
                "authenticator app — Google Authenticator, Aegis, FreeOTP, any of them."
        ))
        root.addView(spacer(16))

        root.addView(card(accentRes = R.color.accent).apply {
            addView(headingText("Setup key"))
            addView(spacer(8))
            addView(bodyText(Base32.formatForDisplay(Base32.encode(secret))))
            addView(spacer(10))
            addView(mutedText("Account name: Shield  •  Type: time-based  •  6 digits"))
        })

        root.addView(card(accentRes = R.color.warn).apply {
            addView(headingText("This is shown once"))
            addView(spacer(6))
            addView(bodyText(
                "After this screen the key is locked into this phone's secure " +
                    "hardware and cannot be displayed again — not even by you. That is " +
                    "what stops you generating your own approval codes later.\n\n" +
                    "If the key is lost, the only way back is the waiting period plus a " +
                    "typed confirmation, or a factory reset."
            ))
        })

        root.addView(card().apply {
            addView(headingText("Confirm they have it"))
            addView(spacer(6))
            addView(mutedText(
                "Ask them for the 6 digits their app is showing now. This proves the " +
                    "key was stored correctly before we lock it away."
            ))
            val field = editField("6 digits from their app", numeric = true)
            verifyField = field
            addView(field)
        })

        root.addView(primaryButton("Verify and finish") { verifyAndSave(secret) })
        root.addView(secondaryButton("Start over") {
            pendingSecret = null
            step = Step.DETAILS
            render()
        })
    }

    private fun verifyAndSave(secret: ByteArray) {
        val code = verifyField?.text?.toString().orEmpty()
        val matched = Totp.verify(secret, code)

        if (matched == null) {
            Toast.makeText(
                this,
                "That code does not match. Check the key was typed correctly.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val encrypted = KeystoreCrypto.encrypt(secret)
        if (encrypted == null) {
            Toast.makeText(
                this,
                "This phone's secure keystore is unavailable, so the key cannot be " +
                    "stored safely. Partner approval cannot be set up.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        Prefs.partnerSecretEnc = encrypted
        // Burn the code that was just used, so it cannot double as an unlock.
        Prefs.lastUsedTotpCounter = matched

        secret.fill(0)
        pendingSecret = null
        verifyField = null

        if (Prefs.partnerPhone.isNotBlank()) requestSmsPermission()

        step = Step.MANAGE
        render()
        Toast.makeText(this, "${Prefs.partnerName} is now your partner.", Toast.LENGTH_LONG).show()
    }

    // ---------------- managing ----------------

    private fun renderManage(root: LinearLayout) {
        root.addView(titleText("Your partner"))
        root.addView(spacer(8))

        root.addView(card(accentRes = R.color.ok).apply {
            addView(headingText(Prefs.partnerName.ifBlank { "Partner" }))
            addView(spacer(6))
            addView(mutedText(
                if (Prefs.partnerPhone.isBlank()) "No phone number saved."
                else "Alerts go to ${Prefs.partnerPhone}"
            ))
            addView(spacer(8))
            addView(bodyText("Their code is required for every unlock."))
        })

        val smsGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        root.addView(statusCard(
            label = "Text alerts",
            detail = when {
                !smsGranted -> "SMS permission not granted, so alerts stay on this phone only."
                Prefs.partnerSmsAlerts -> "Tamper attempts and unlock requests are texted to them."
                else -> "Turned off. Alerts are only shown on this phone."
            },
            level = if (smsGranted && Prefs.partnerSmsAlerts) StatusLevel.OK else StatusLevel.WARN,
            actionLabel = if (smsGranted && Prefs.partnerSmsAlerts) "Turn off alerts" else "Turn on alerts",
            onAction = {
                if (Prefs.partnerSmsAlerts && smsGranted) {
                    // Switching alerts off weakens accountability, so it is gated.
                    if (LockdownManager.canWeakenProtection()) {
                        Prefs.partnerSmsAlerts = false
                        render()
                    } else {
                        Toast.makeText(
                            this,
                            "Turning alerts off needs an approved unlock window.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    requestSmsPermission()
                }
            }
        ))

        root.addView(secondaryButton("Send them a test message") {
            PartnerNotifier.sendIntroduction(this)
            Toast.makeText(this, "Test alert sent.", Toast.LENGTH_SHORT).show()
        })

        root.addView(spacer(12))
        root.addView(card(accentRes = R.color.warn).apply {
            addView(headingText("Change partner"))
            addView(spacer(6))
            addView(mutedText(
                "Replacing your partner would let you pick someone who always says " +
                    "yes, so it needs an approved unlock window first."
            ))
            addView(dangerButton("Replace partner") {
                if (LockdownManager.canWeakenProtection()) {
                    Prefs.partnerSecretEnc = null
                    step = Step.DETAILS
                    render()
                } else {
                    Toast.makeText(
                        this@PartnerSetupActivity,
                        "Request an unlock first — ${Prefs.cooldownHours} hours, then their code.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
        })
    }

    private fun requestSmsPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            Prefs.partnerSmsAlerts = true
            render()
        } else {
            smsPermission.launch(Manifest.permission.SEND_SMS)
        }
    }
}
