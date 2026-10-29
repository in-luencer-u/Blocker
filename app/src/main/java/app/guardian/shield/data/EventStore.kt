package app.guardian.shield.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.guardian.shield.core.Prefs
import java.util.Calendar
import java.util.concurrent.TimeUnit

enum class EventType {
    /** A DNS lookup for adult content was refused. */
    DOMAIN_BLOCKED,

    /** An on-screen guard closed a page or app. */
    SCREEN_BLOCKED,

    /** A blocked app was opened and sent to the background. */
    APP_BLOCKED,

    /** The user tried to reach a screen that could weaken protection. */
    TAMPER_ATTEMPT,

    /** A protection layer went down (VPN stopped, accessibility disabled, ...). */
    PROTECTION_DOWN,

    /** A protection layer came back up. */
    PROTECTION_UP,

    UNLOCK_REQUESTED,
    UNLOCK_CANCELLED,
    UNLOCK_GRANTED,
    UNLOCK_DENIED,

    /** The user reported something that slipped through. */
    USER_REPORT,

    BLOCKLIST_UPDATED,
}

data class ShieldEvent(
    val id: Long,
    val type: EventType,
    val detail: String,
    val atMillis: Long,
)

/**
 * Local-only event log. Nothing in here is ever uploaded; there is no network
 * code anywhere near this class.
 */
class EventStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                detail TEXT NOT NULL DEFAULT '',
                at_millis INTEGER NOT NULL,
                day_index INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_events_day ON events(day_index)")
        db.execSQL("CREATE INDEX idx_events_type ON events(type)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Protection history is worth keeping; nothing destructive on upgrade yet.
    }

    fun log(type: EventType, detail: String = "") {
        runCatching {
            val now = System.currentTimeMillis()
            writableDatabase.insert("events", null, ContentValues().apply {
                put("type", type.name)
                put("detail", detail.take(300))
                put("at_millis", now)
                put("day_index", dayIndex(now))
            })
        }
        when (type) {
            EventType.DOMAIN_BLOCKED, EventType.SCREEN_BLOCKED, EventType.APP_BLOCKED ->
                Prefs.totalBlocked = Prefs.totalBlocked + 1
            EventType.TAMPER_ATTEMPT ->
                Prefs.totalTamperAttempts = Prefs.totalTamperAttempts + 1
            else -> Unit
        }
        pruneIfNeeded()
    }

    fun recent(limit: Int = 100): List<ShieldEvent> = runCatching {
        readableDatabase.query(
            "events", arrayOf("id", "type", "detail", "at_millis"),
            null, null, null, null, "at_millis DESC", limit.toString()
        ).use { c ->
            val out = ArrayList<ShieldEvent>(c.count)
            while (c.moveToNext()) {
                val type = runCatching { EventType.valueOf(c.getString(1)) }.getOrNull() ?: continue
                out.add(ShieldEvent(c.getLong(0), type, c.getString(2), c.getLong(3)))
            }
            out
        }
    }.getOrDefault(emptyList())

    fun countSince(type: EventType, sinceMillis: Long): Int = runCatching {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM events WHERE type = ? AND at_millis >= ?",
            arrayOf(type.name, sinceMillis.toString())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }.getOrDefault(0)

    fun countToday(type: EventType): Int = countSince(type, startOfToday())

    /** Days, counting back from today, that had no block or tamper event at all. */
    fun cleanDayStreak(): Int = runCatching {
        val today = dayIndex(System.currentTimeMillis())
        val dirty = HashSet<Int>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT day_index FROM events WHERE type IN (?, ?, ?, ?)",
            arrayOf(
                EventType.DOMAIN_BLOCKED.name,
                EventType.SCREEN_BLOCKED.name,
                EventType.APP_BLOCKED.name,
                EventType.TAMPER_ATTEMPT.name,
            )
        ).use { c -> while (c.moveToNext()) dirty.add(c.getInt(0)) }

        val firstDay = if (Prefs.streakStartDay == 0) today else Prefs.streakStartDay
        var streak = 0
        var day = today
        while (day >= firstDay) {
            if (dirty.contains(day)) break
            streak++
            day--
        }
        streak
    }.getOrDefault(0)

    /** Block counts for the last [days] days, oldest first. */
    fun dailyBlockCounts(days: Int): IntArray = runCatching {
        val today = dayIndex(System.currentTimeMillis())
        val out = IntArray(days)
        readableDatabase.rawQuery(
            """
            SELECT day_index, COUNT(*) FROM events
            WHERE type IN (?, ?, ?) AND day_index > ?
            GROUP BY day_index
            """.trimIndent(),
            arrayOf(
                EventType.DOMAIN_BLOCKED.name,
                EventType.SCREEN_BLOCKED.name,
                EventType.APP_BLOCKED.name,
                (today - days).toString()
            )
        ).use { c ->
            while (c.moveToNext()) {
                val idx = c.getInt(0) - (today - days + 1)
                if (idx in 0 until days) out[idx] = c.getInt(1)
            }
        }
        out
    }.getOrDefault(IntArray(days))

    private fun pruneIfNeeded() {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETAIN_DAYS)
        runCatching {
            writableDatabase.delete("events", "at_millis < ?", arrayOf(cutoff.toString()))
        }
    }

    companion object {
        private const val DB_NAME = "shield_events.db"
        private const val DB_VERSION = 1
        private const val RETAIN_DAYS = 400L

        @Volatile
        private var instance: EventStore? = null

        fun get(context: Context): EventStore =
            instance ?: synchronized(this) {
                instance ?: EventStore(context).also { instance = it }
            }

        /** Local-day number since the epoch, using the device's time zone. */
        fun dayIndex(millis: Long): Int {
            val cal = Calendar.getInstance()
            cal.timeInMillis = millis
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return (cal.timeInMillis / TimeUnit.DAYS.toMillis(1)).toInt()
        }

        fun startOfToday(): Long {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }
}
