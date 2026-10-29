package app.guardian.shield.ui

import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import app.guardian.shield.R
import app.guardian.shield.core.Prefs
import app.guardian.shield.data.EventStore
import app.guardian.shield.data.EventType
import java.util.concurrent.TimeUnit

/**
 * Private progress.
 *
 * Framed around what the protection did rather than around failure: a blocked
 * attempt is the system working. There is no network code in this screen's path
 * and no identifier anywhere in the database.
 */
class StatsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        render()
    }

    private fun render() {
        val events = EventStore.get(this)
        val root = verticalRoot()

        root.addView(titleText("My progress"))
        root.addView(mutedText("Stored on this phone only. Nothing is uploaded."))
        root.addView(spacer(18))

        val streak = events.cleanDayStreak()
        root.addView(card(accentRes = R.color.accent).apply {
            addView(bigNumber(streak.toString()))
            addView(headingText(if (streak == 1) "clean day" else "clean days in a row"))
            addView(spacer(8))
            addView(mutedText(
                "A clean day is a day with no blocked attempt and no tampering. " +
                    "The count restarts the day after one happens."
            ))
        })

        root.addView(card().apply {
            addView(headingText("Totals"))
            addView(spacer(10))
            addStatRow(this, "Attempts blocked, all time", Prefs.totalBlocked.toString())
            addStatRow(this, "Tamper attempts", Prefs.totalTamperAttempts.toString())
            addStatRow(
                this, "Blocked in the last 7 days",
                events.countSince(
                    EventType.DOMAIN_BLOCKED,
                    System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
                ).toString()
            )
            addStatRow(this, "Domains on the block list", Prefs.blocklistDomainCount.toString())
        })

        root.addView(card().apply {
            addView(headingText("Last 14 days"))
            addView(spacer(10))
            val counts = events.dailyBlockCounts(14)
            val peak = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
            for ((index, count) in counts.withIndex()) {
                val daysAgo = counts.size - 1 - index
                val label = when (daysAgo) {
                    0 -> "today"
                    1 -> "1 day ago"
                    else -> "$daysAgo days ago"
                }
                val barLength = if (count == 0) 0 else (count * 20 / peak).coerceAtLeast(1)
                val bar = "\u2588".repeat(barLength)
                addView(mutedText(
                    label.padEnd(12) + " " + (if (count == 0) "·" else "$bar  $count")
                ))
            }
        })

        root.addView(card().apply {
            addView(headingText("Recent activity"))
            addView(spacer(10))
            val recent = events.recent(40)
            if (recent.isEmpty()) {
                addView(mutedText("Nothing recorded yet."))
            } else {
                for (event in recent) {
                    addView(mutedText(
                        MainActivity.formatTimestamp(event.atMillis) + "  " +
                            describe(event.type) +
                            if (event.detail.isNotBlank()) " — ${event.detail}" else ""
                    ))
                    addView(spacer(4))
                }
            }
        })

        root.addView(spacer(12))
        root.addView(secondaryButton("Back") { finish() })
        setScrollingContent(root)
    }

    private fun addStatRow(parent: LinearLayout, label: String, value: String) {
        parent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                mutedText(label),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(headingText(value))
        })
        parent.addView(spacer(8))
    }

    private fun describe(type: EventType): String = when (type) {
        EventType.DOMAIN_BLOCKED -> "site blocked"
        EventType.SCREEN_BLOCKED -> "content closed"
        EventType.APP_BLOCKED -> "app blocked"
        EventType.TAMPER_ATTEMPT -> "tamper attempt"
        EventType.PROTECTION_DOWN -> "protection dropped"
        EventType.PROTECTION_UP -> "protection active"
        EventType.UNLOCK_REQUESTED -> "unlock requested"
        EventType.UNLOCK_CANCELLED -> "unlock cancelled"
        EventType.UNLOCK_GRANTED -> "unlock granted"
        EventType.UNLOCK_DENIED -> "unlock refused"
        EventType.USER_REPORT -> "you reported a site"
        EventType.BLOCKLIST_UPDATED -> "block list updated"
    }
}
