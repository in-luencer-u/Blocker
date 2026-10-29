package app.guardian.shield.partner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.guardian.shield.core.Notifications
import app.guardian.shield.core.Prefs

/**
 * Tells the accountability partner what happened.
 *
 * Alerts go out by SMS, straight from the phone's radio to the partner's number.
 * There is no server in the path and no account to create, which is what keeps
 * the promise that none of the user's data leaves the device — an SMS carries
 * only the single sentence below, and only when the user has opted in.
 *
 * If SMS is unavailable or not permitted, the alert still lands in the local log
 * and as an on-device notification, so nothing is silently dropped.
 */
object PartnerNotifier {

    enum class Kind(val throttleMinutes: Int) {
        TAMPER(10),
        VPN_REVOKED(10),
        ACCESSIBILITY_OFF(10),
        UNLOCK_REQUESTED(0),
        UNLOCK_CANCELLED(0),
        UNLOCK_GRANTED(0),
        PROTECTION_DISABLED(5),
    }

    private const val TAG = "PartnerNotifier"
    private const val PREF_FILE = "partner_throttle"

    fun alert(context: Context, kind: Kind, message: String) {
        // Always visible locally, regardless of SMS availability.
        Notifications.alert(context, "Accountability alert", message)

        if (!Prefs.partnerSmsAlerts) return
        val number = Prefs.partnerPhone.trim()
        if (number.isBlank()) return
        if (!throttleAllows(context, kind)) return
        if (!hasSmsPermission(context)) {
            Log.i(TAG, "SMS alert skipped: permission not granted")
            return
        }

        sendSms(context, number, message.take(300))
    }

    private fun hasSmsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private fun sendSms(context: Context, number: String, body: String) {
        runCatching {
            val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            } ?: return

            // Long alerts must be split or the send silently fails.
            val parts = manager.divideMessage(body)
            if (parts.size <= 1) {
                manager.sendTextMessage(number, null, body, null, null)
            } else {
                manager.sendMultipartTextMessage(number, null, parts, null, null)
            }
        }.onFailure { Log.w(TAG, "SMS alert failed", it) }
    }

    /**
     * Stops an alert storm. A user repeatedly poking at the uninstall screen
     * should cost the partner one message, not forty.
     */
    private fun throttleAllows(context: Context, kind: Kind): Boolean {
        if (kind.throttleMinutes <= 0) return true

        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(kind.name, 0L)
        val gap = kind.throttleMinutes * 60_000L

        if (now - last < gap) return false
        prefs.edit().putLong(kind.name, now).apply()
        return true
    }

    /** The message the partner gets when they are first added. */
    fun sendIntroduction(context: Context) {
        val name = Prefs.partnerName.ifBlank { "there" }
        alert(
            context,
            Kind.UNLOCK_REQUESTED,
            "Hi $name — you are now the accountability partner for Shield on this phone. " +
                "You will get a message if blocking is tampered with, and your 6-digit code " +
                "is required to unlock it."
        )
    }
}
