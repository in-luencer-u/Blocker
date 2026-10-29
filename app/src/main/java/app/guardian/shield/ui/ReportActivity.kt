package app.guardian.shield.ui

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.guardian.shield.R
import app.guardian.shield.core.Prefs
import app.guardian.shield.data.EventStore
import app.guardian.shield.data.EventType
import app.guardian.shield.dns.Blocklist
import app.guardian.shield.service.Watchdog

/**
 * Reporting something that slipped through.
 *
 * Tightening protection is the one direction that needs no approval and no
 * waiting, so a report takes effect on the next lookup. This matters: the gap
 * between noticing a hole and closing it is exactly when a relapse happens.
 */
class ReportActivity : AppCompatActivity() {

    private var field: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Blocklist.ensureLoaded(this)
        render()
    }

    private fun render() {
        val root = verticalRoot()

        root.addView(titleText("Report a site"))
        root.addView(spacer(8))
        root.addView(bodyText(
            "If something got through, add it here. It is blocked immediately, on " +
                "every app, with no waiting period."
        ))
        root.addView(spacer(16))

        root.addView(card().apply {
            addView(headingText("Domain that got through"))
            addView(spacer(6))
            addView(mutedText(
                "Just the site address — for example example.com. You do not need the " +
                    "full link, and nothing you type here leaves the phone."
            ))
            val input = editField("example.com")
            field = input
            addView(input)
            addView(primaryButton("Block this now") { submit() })
        })

        val custom = Blocklist.customDomains()
        root.addView(card().apply {
            addView(headingText("Sites you have blocked"))
            addView(spacer(8))
            if (custom.isEmpty()) {
                addView(mutedText("None yet."))
            } else {
                addView(mutedText(custom.joinToString("\n")))
                addView(spacer(8))
                addView(mutedText(
                    "These stay blocked permanently. Removing one needs an approved " +
                        "unlock window."
                ))
            }
        })

        val allowed = Blocklist.allowedDomains()
        if (allowed.isNotEmpty()) {
            root.addView(card(accentRes = R.color.warn).apply {
                addView(headingText("Sites you have allowed"))
                addView(spacer(8))
                addView(mutedText(allowed.joinToString("\n")))
                addView(spacer(8))
                addView(mutedText("Re-blocking any of these is instant — just report it above."))
            })
        }

        root.addView(card().apply {
            addView(headingText("Keeping the list fresh"))
            addView(spacer(6))
            addView(mutedText(
                "The main list refreshes itself daily from public filtering lists " +
                    "over Wi-Fi. That is a download only: no information about you is " +
                    "sent with it."
            ))
            addView(secondaryButton("Update the list now") {
                Watchdog.updateBlocklistNow(this@ReportActivity)
                Toast.makeText(this@ReportActivity, "Updating in the background…", Toast.LENGTH_SHORT).show()
            })
        })

        root.addView(spacer(12))
        root.addView(secondaryButton("Back") { finish() })
        setScrollingContent(root)
    }

    private fun submit() {
        val raw = field?.text?.toString().orEmpty()
        val normalised = Blocklist.normalise(raw)

        if (normalised == null) {
            Toast.makeText(
                this,
                "That does not look like a site address. Try something like example.com",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val added = Blocklist.addCustomBlocked(this, normalised)
        EventStore.get(this).log(EventType.USER_REPORT, normalised)

        Toast.makeText(
            this,
            if (added) "$normalised is now blocked everywhere."
            else "$normalised was already blocked.",
            Toast.LENGTH_LONG
        ).show()

        field?.setText("")
        render()
    }
}
