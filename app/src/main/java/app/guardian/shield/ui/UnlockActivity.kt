package app.guardian.shield.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.guardian.shield.R
import app.guardian.shield.admin.DeviceOwnerManager
import app.guardian.shield.core.Prefs
import app.guardian.shield.dns.Blocklist
import app.guardian.shield.lock.LockdownManager
import app.guardian.shield.util.OemIntents
import app.guardian.shield.vpn.VpnController

/**
 * The only route out of the lock, and deliberately a slow one.
 *
 * Four states, each a different screen: ask, wait, get approved, and a short
 * window in which changes are actually possible. Cancelling is a single tap at
 * every stage, because making it easy to change your mind back towards
 * protection is as important as making it hard to change it away.
 */
class UnlockActivity : AppCompatActivity() {

    private val ticker = Handler(Looper.getMainLooper())
    private var reasonField: EditText? = null
    private var codeField: EditText? = null

    private val tick = object : Runnable {
        override fun run() {
            render()
            ticker.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
    }

    override fun onResume() {
        super.onResume()
        ticker.post(tick)
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
        super.onPause()
    }

    private fun render() {
        val root = verticalRoot()

        intent.getStringExtra(EXTRA_BLOCKED_REASON)?.let { reason ->
            root.addView(card(accentRes = R.color.bad).apply {
                addView(headingText("That screen is blocked"))
                addView(spacer(6))
                addView(bodyText(
                    "Shield noticed you $reason. Your accountability partner has been " +
                        "told. If you really need to change something, the request " +
                        "process below is the only way."
                ))
            })
        }

        when (LockdownManager.state()) {
            LockdownManager.State.LOCKED -> renderRequestForm(root)
            LockdownManager.State.COOLING_OFF -> renderCountdown(root)
            LockdownManager.State.AWAITING_APPROVAL -> renderApproval(root)
            LockdownManager.State.UNLOCKED -> renderUnlockWindow(root)
        }

        root.addView(spacer(12))
        root.addView(secondaryButton("Back") { finish() })

        setScrollingContent(root)
    }

    // ---------------- 1. ask ----------------

    private fun renderRequestForm(root: android.widget.LinearLayout) {
        root.addView(titleText("Request to unlock"))
        root.addView(spacer(8))
        root.addView(bodyText(
            "Nothing changes for ${Prefs.cooldownHours} hours. After that" +
                if (Prefs.partnerConfigured) {
                    ", ${Prefs.partnerName.ifBlank { "your partner" }} still has to give " +
                        "you a code before anything can be switched off."
                } else {
                    ", you will be able to confirm it yourself."
                }
        ))
        root.addView(spacer(16))

        root.addView(card().apply {
            addView(headingText("Why do you want to unlock?"))
            addView(spacer(6))
            addView(mutedText(
                "Your partner sees this. Writing it down honestly is often enough to " +
                    "change your mind."
            ))
            val field = editField("e.g. a site I need for work is blocked", multiline = true)
            reasonField = field
            addView(field)
        })

        root.addView(primaryButton("Start the ${Prefs.cooldownHours}-hour wait") {
            val reason = reasonField?.text?.toString()?.trim().orEmpty()
            if (LockdownManager.requestUnlock(this, reason)) {
                Toast.makeText(this, "The wait has started.", Toast.LENGTH_SHORT).show()
            }
            render()
        })

        root.addView(spacer(8))
        root.addView(card().apply {
            addView(headingText("Blocked something you actually need?"))
            addView(spacer(6))
            addView(mutedText(
                "Reporting a false positive does not need an unlock. Most legitimate " +
                    "problems are fixed instantly that way."
            ))
            addView(secondaryButton("Report a wrongly blocked site") {
                startActivity(android.content.Intent(this@UnlockActivity, ReportActivity::class.java))
            })
        })
    }

    // ---------------- 2. wait ----------------

    private fun renderCountdown(root: android.widget.LinearLayout) {
        val remaining = LockdownManager.remainingCooldownMillis()

        root.addView(titleText("Waiting"))
        root.addView(spacer(8))
        root.addView(card(accentRes = R.color.warn).apply {
            addView(bigNumber(MainActivity.formatDuration(remaining)))
            addView(mutedText("until the request can be approved"))
            addView(spacer(10))
            if (Prefs.unlockReason.isNotBlank()) {
                addView(mutedText("Your reason: \u201C${Prefs.unlockReason}\u201D"))
            }
        })

        root.addView(bodyText(
            "Protection stays fully on until the wait ends. Closing the app, " +
                "restarting the phone, or changing the clock will not shorten it."
        ))

        root.addView(primaryButton("Cancel — I don't want to unlock") {
            LockdownManager.cancelUnlock(this)
            Toast.makeText(this, "Request cancelled. Protection stays on.", Toast.LENGTH_SHORT).show()
            render()
        })
    }

    // ---------------- 3. approve ----------------

    private fun renderApproval(root: android.widget.LinearLayout) {
        root.addView(titleText("Approval needed"))
        root.addView(spacer(8))

        if (Prefs.partnerConfigured) {
            root.addView(bodyText(
                "The wait is over. Ask ${Prefs.partnerName.ifBlank { "your partner" }} " +
                    "to open their authenticator app and read you the current 6-digit " +
                    "Shield code."
            ))
            root.addView(card().apply {
                addView(headingText("Partner's code"))
                val field = editField("6 digits", numeric = true)
                codeField = field
                addView(field)
            })
            root.addView(primaryButton("Unlock for ${Prefs.UNLOCK_WINDOW_MINUTES} minutes") {
                submitPartnerCode()
            })
            root.addView(spacer(8))
            root.addView(mutedText(
                "Each code works once. A code read out earlier will not work again."
            ))
        } else {
            root.addView(bodyText(
                "The wait is over. You have no accountability partner, so you can " +
                    "confirm this yourself — type the sentence below exactly."
            ))
            root.addView(card(accentRes = R.color.warn).apply {
                addView(headingText("Type this to confirm"))
                addView(spacer(6))
                addView(bodyText("\u201C${LockdownManager.SELF_CONFIRM_PHRASE}\u201D"))
                val field = editField("Type the sentence", multiline = true)
                codeField = field
                addView(field)
            })
            root.addView(primaryButton("Unlock for ${Prefs.UNLOCK_WINDOW_MINUTES} minutes") {
                submitSelfConfirmation()
            })
        }

        root.addView(primaryButton("Cancel — keep protection on") {
            LockdownManager.cancelUnlock(this)
            render()
        })
    }

    private fun submitPartnerCode() {
        val code = codeField?.text?.toString().orEmpty()
        when (val result = LockdownManager.approveWithPartnerCode(this, code)) {
            is LockdownManager.ApprovalResult.Granted ->
                Toast.makeText(this, "Unlocked briefly.", Toast.LENGTH_SHORT).show()
            is LockdownManager.ApprovalResult.BadCode ->
                Toast.makeText(this, "That code is not valid right now.", Toast.LENGTH_LONG).show()
            is LockdownManager.ApprovalResult.StillCoolingOff ->
                Toast.makeText(
                    this,
                    "Still ${MainActivity.formatDuration(result.remainingMillis)} to wait.",
                    Toast.LENGTH_LONG
                ).show()
            is LockdownManager.ApprovalResult.NoRequest ->
                Toast.makeText(this, "No request is pending.", Toast.LENGTH_SHORT).show()
            is LockdownManager.ApprovalResult.NoPartnerConfigured ->
                Toast.makeText(this, "Partner details could not be read.", Toast.LENGTH_LONG).show()
        }
        render()
    }

    private fun submitSelfConfirmation() {
        val typed = codeField?.text?.toString().orEmpty()
        when (LockdownManager.approveWithoutPartner(this, typed)) {
            is LockdownManager.ApprovalResult.Granted ->
                Toast.makeText(this, "Unlocked briefly.", Toast.LENGTH_SHORT).show()
            else ->
                Toast.makeText(this, "That does not match the sentence.", Toast.LENGTH_LONG).show()
        }
        render()
    }

    // ---------------- 4. the window ----------------

    private fun renderUnlockWindow(root: android.widget.LinearLayout) {
        root.addView(titleText("Unlocked"))
        root.addView(spacer(8))
        root.addView(card(accentRes = R.color.bad).apply {
            addView(bigNumber(MainActivity.formatDuration(LockdownManager.remainingWindowMillis())))
            addView(mutedText("left in this window — it closes on its own"))
        })

        root.addView(primaryButton("Re-lock now, I've changed my mind") {
            LockdownManager.relock(this)
            VpnController.ensureRunning(this)
            Toast.makeText(this, "Locked again.", Toast.LENGTH_SHORT).show()
            render()
        })

        root.addView(spacer(12))
        root.addView(headingText("What you can change"))
        root.addView(spacer(8))

        root.addView(card().apply {
            addView(headingText("Allow one site"))
            addView(spacer(6))
            addView(mutedText(
                "Adds a single domain to your personal allowlist. Narrower than " +
                    "switching the filter off."
            ))
            val field = editField("example.com")
            addView(field)
            addView(secondaryButton("Allow this domain") {
                val domain = field.text?.toString().orEmpty()
                if (Blocklist.addAllowed(this@UnlockActivity, domain)) {
                    Toast.makeText(this@UnlockActivity, "Allowed.", Toast.LENGTH_SHORT).show()
                    field.setText("")
                } else {
                    Toast.makeText(this@UnlockActivity, "Could not read that domain.", Toast.LENGTH_SHORT).show()
                }
            })
        })

        root.addView(card().apply {
            addView(headingText("Pause the filter"))
            addView(spacer(6))
            addView(mutedText(
                "Stops all domain filtering until you turn it back on. The on-screen " +
                    "guard keeps running."
            ))
            addView(dangerButton("Pause filtering") {
                if (VpnController.stop(this@UnlockActivity)) {
                    Toast.makeText(this@UnlockActivity, "Filtering paused.", Toast.LENGTH_SHORT).show()
                }
                render()
            })
        })

        root.addView(card().apply {
            addView(headingText("Uninstall Shield"))
            addView(spacer(6))
            addView(mutedText(
                "Lifts the uninstall block for this window and opens the app's " +
                    "settings page."
            ))
            addView(dangerButton("Open uninstall settings") {
                DeviceOwnerManager.relaxForUnlockWindow(this@UnlockActivity)
                OemIntents.launch(this@UnlockActivity, OemIntents.appDetailsIntent(this@UnlockActivity))
            })
        })
    }

    companion object {
        const val EXTRA_BLOCKED_REASON = "blocked_reason"
    }
}
