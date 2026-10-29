package app.guardian.shield.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import app.guardian.shield.R
import app.guardian.shield.core.Prefs

/**
 * What the user sees instead of the thing they tried to open.
 *
 * The delay is the point. An urge is short-lived, and the gap between the
 * impulse and acting on it is where the decision actually gets made — so this
 * screen refuses to go away for a while, says something calm, and offers
 * something concrete to do instead.
 *
 * Back is disabled until the countdown finishes, so the screen cannot be
 * dismissed reflexively.
 */
class UrgeActivity : AppCompatActivity() {

    private var timer: CountDownTimer? = null
    private var continueButton: Button? = null
    private var countdownLabel: TextView? = null
    private var breathLabel: TextView? = null
    private var unlocked = false

    private val breathCycle = listOf(
        "Breathe in…" to 4,
        "Hold" to 4,
        "Breathe out slowly…" to 6,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)

        // Keep the screen on for the countdown, and keep this screen out of
        // screenshots and the recents thumbnail.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (unlocked) finish()
                // Otherwise: deliberately ignored until the countdown ends.
            }
        })

        buildUi()
        startCountdown()
    }

    private fun buildUi() {
        val trigger = intent.getStringExtra(EXTRA_TRIGGER) ?: "SCREEN"
        val root = verticalRoot(padding = 24)

        root.addView(spacer(24))
        root.addView(titleText(
            if (trigger == "SELF") "You reached for help" else "Blocked"
        ))
        root.addView(spacer(8))
        root.addView(bodyText(
            if (trigger == "SELF") {
                "That took more strength than giving in would have. Stay here for a minute."
            } else {
                "Shield stopped that. Nothing was loaded. Stay here for a minute — " +
                    "the pull you feel right now will fade on its own."
            }
        ))

        root.addView(spacer(28))

        breathLabel = headingText("Breathe in…").also { root.addView(it) }
        root.addView(spacer(6))
        countdownLabel = bigNumber(DELAY_SECONDS.toString()).also { root.addView(it) }
        root.addView(mutedText("seconds"))

        root.addView(spacer(24))

        if (Prefs.motivation.isNotBlank()) {
            root.addView(card(accentRes = R.color.accent).apply {
                addView(headingText("Why you started this"))
                addView(spacer(8))
                addView(bodyText("\u201C${Prefs.motivation}\u201D"))
            })
        }

        root.addView(card().apply {
            addView(headingText("Do one of these instead"))
            addView(spacer(8))
            addView(bodyText(
                "• Stand up and walk out of the room\n" +
                    "• Splash cold water on your face\n" +
                    "• 20 push-ups or 30 seconds of shaking out your arms\n" +
                    "• Put your shoes on and step outside\n" +
                    "• Message your accountability partner\n" +
                    "• Write down what you were feeling a minute ago"
            ))
        })

        if (Prefs.partnerPhone.isNotBlank()) {
            root.addView(primaryButton(
                "Call ${Prefs.partnerName.ifBlank { "my partner" }}"
            ) {
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Prefs.partnerPhone}"))
                    )
                }
            })
        }

        continueButton = secondaryButton("Wait…") {
            if (unlocked) finish()
        }.also {
            it.isEnabled = false
            it.alpha = 0.5f
            root.addView(it)
        }

        root.addView(spacer(8))
        root.addView(mutedText(
            "This screen cannot be dismissed until the timer ends. That is the point."
        ))

        setScrollingContent(root)
    }

    private fun startCountdown() {
        var elapsed = 0
        timer = object : CountDownTimer(DELAY_SECONDS * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val remaining = (millisUntilFinished / 1000L).toInt()
                countdownLabel?.text = remaining.toString()
                breathLabel?.text = breathPromptAt(elapsed)
                elapsed++
            }

            override fun onFinish() {
                unlocked = true
                countdownLabel?.text = "0"
                breathLabel?.text = "You got through it"
                continueButton?.apply {
                    text = "Close this screen"
                    isEnabled = true
                    alpha = 1f
                }
            }
        }.also { it.start() }
    }

    /** Walks the 4-4-6 breathing cycle, repeating for as long as the timer runs. */
    private fun breathPromptAt(second: Int): String {
        val cycleLength = breathCycle.sumOf { it.second }
        var position = second % cycleLength
        for ((prompt, duration) in breathCycle) {
            if (position < duration) return prompt
            position -= duration
        }
        return breathCycle.first().first
    }

    override fun onDestroy() {
        timer?.cancel()
        timer = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_LABEL = "label"
        const val EXTRA_TRIGGER = "trigger"

        /** Long enough for an urge to crest, short enough to be bearable. */
        private const val DELAY_SECONDS = 60
    }
}
