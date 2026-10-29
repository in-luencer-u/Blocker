package app.guardian.shield.lock

import android.content.Context
import app.guardian.shield.core.KeystoreCrypto
import app.guardian.shield.core.Prefs
import app.guardian.shield.data.EventStore
import app.guardian.shield.data.EventType
import app.guardian.shield.partner.PartnerNotifier
import java.util.concurrent.TimeUnit

/**
 * The single gate every weakening action must pass through.
 *
 * The rule the whole app is built around: tightening protection is instant,
 * loosening it is slow and needs someone else. Concretely, turning the filter
 * off, allowlisting a domain, unblocking an app, or lowering the cooldown all
 * require an open unlock window, and a window only opens after
 *
 *   1. a request that has sat for the full cooling-off period, and
 *   2. a fresh approval code from the accountability partner.
 *
 * Cancelling a request is always allowed, because that direction is safe.
 */
object LockdownManager {

    /** Confirmation sentence required when no partner is configured. */
    const val SELF_CONFIRM_PHRASE = "I am choosing to lower my own protection"

    enum class State {
        /** Fully locked, nothing pending. */
        LOCKED,

        /** A request is counting down. */
        COOLING_OFF,

        /** Cooldown finished, waiting for the partner's code. */
        AWAITING_APPROVAL,

        /** Settings are editable until [Prefs.unlockWindowUntil]. */
        UNLOCKED,
    }

    fun state(): State {
        if (isUnlockWindowOpen()) return State.UNLOCKED
        val requestedAt = Prefs.unlockRequestedAt
        if (requestedAt == 0L) return State.LOCKED
        return if (remainingCooldownMillis() > 0) State.COOLING_OFF else State.AWAITING_APPROVAL
    }

    fun isUnlockWindowOpen(): Boolean = System.currentTimeMillis() < Prefs.unlockWindowUntil

    /** True when the caller may change a protection setting right now. */
    fun canWeakenProtection(): Boolean = isUnlockWindowOpen()

    fun cooldownMillis(): Long = TimeUnit.HOURS.toMillis(Prefs.cooldownHours.toLong())

    fun remainingCooldownMillis(): Long {
        val requestedAt = Prefs.unlockRequestedAt
        if (requestedAt == 0L) return 0
        val elapsed = System.currentTimeMillis() - requestedAt
        // A backwards clock change must not shorten the wait.
        if (elapsed < 0) return cooldownMillis()
        return (cooldownMillis() - elapsed).coerceAtLeast(0)
    }

    fun remainingWindowMillis(): Long =
        (Prefs.unlockWindowUntil - System.currentTimeMillis()).coerceAtLeast(0)

    // ---------------- requesting ----------------

    /** Starts the cooling-off clock. Restarting an existing request is refused. */
    fun requestUnlock(context: Context, reason: String): Boolean {
        if (Prefs.unlockRequestedAt != 0L) return false

        Prefs.unlockRequestedAt = System.currentTimeMillis()
        Prefs.unlockReason = reason.take(200)

        EventStore.get(context).log(EventType.UNLOCK_REQUESTED, reason.take(200))
        PartnerNotifier.alert(
            context,
            PartnerNotifier.Kind.UNLOCK_REQUESTED,
            "Shield: a request to disable blocking was made. Reason: " +
                reason.take(120).ifBlank { "(none given)" } +
                ". It unlocks in ${Prefs.cooldownHours}h and still needs your code."
        )
        return true
    }

    /** Always permitted — backing out is the safe direction. */
    fun cancelUnlock(context: Context) {
        if (Prefs.unlockRequestedAt == 0L && Prefs.unlockWindowUntil == 0L) return

        Prefs.unlockRequestedAt = 0L
        Prefs.unlockReason = ""
        Prefs.unlockWindowUntil = 0L

        EventStore.get(context).log(EventType.UNLOCK_CANCELLED)
        PartnerNotifier.alert(
            context,
            PartnerNotifier.Kind.UNLOCK_CANCELLED,
            "Shield: the unlock request was cancelled. Protection stays on."
        )
    }

    // ---------------- approving ----------------

    sealed class ApprovalResult {
        object Granted : ApprovalResult()
        object NoRequest : ApprovalResult()
        data class StillCoolingOff(val remainingMillis: Long) : ApprovalResult()
        object BadCode : ApprovalResult()
        object NoPartnerConfigured : ApprovalResult()
    }

    /**
     * Opens the unlock window if [code] is a currently valid partner code.
     *
     * The matched TOTP counter is recorded, so the same six digits cannot be
     * entered twice — the user cannot talk the partner into reading a code
     * "just to check" and then reuse it.
     */
    fun approveWithPartnerCode(context: Context, code: String): ApprovalResult {
        val requestedAt = Prefs.unlockRequestedAt
        if (requestedAt == 0L) return ApprovalResult.NoRequest

        val remaining = remainingCooldownMillis()
        if (remaining > 0) return ApprovalResult.StillCoolingOff(remaining)

        val secret = KeystoreCrypto.decrypt(Prefs.partnerSecretEnc)
            ?: return ApprovalResult.NoPartnerConfigured

        val counter = Totp.verify(
            secret = secret,
            code = code,
            minCounter = Prefs.lastUsedTotpCounter,
        )
        secret.fill(0)

        if (counter == null) {
            EventStore.get(context).log(EventType.UNLOCK_DENIED, "wrong partner code")
            return ApprovalResult.BadCode
        }

        Prefs.lastUsedTotpCounter = counter
        openWindow(context, "partner code")
        return ApprovalResult.Granted
    }

    /**
     * Fallback when no partner is set up: the cooling-off period plus a typed
     * sentence. Weaker by design, and the UI says so.
     */
    fun approveWithoutPartner(context: Context, typedPhrase: String): ApprovalResult {
        if (Prefs.partnerConfigured) return ApprovalResult.BadCode
        if (Prefs.unlockRequestedAt == 0L) return ApprovalResult.NoRequest

        val remaining = remainingCooldownMillis()
        if (remaining > 0) return ApprovalResult.StillCoolingOff(remaining)

        val normalise = { s: String -> s.trim().lowercase().replace(Regex("\\s+"), " ") }
        if (normalise(typedPhrase) != normalise(SELF_CONFIRM_PHRASE)) {
            return ApprovalResult.BadCode
        }

        openWindow(context, "self-confirmed (no partner)")
        return ApprovalResult.Granted
    }

    private fun openWindow(context: Context, how: String) {
        Prefs.unlockWindowUntil =
            System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(Prefs.UNLOCK_WINDOW_MINUTES.toLong())
        Prefs.unlockRequestedAt = 0L
        Prefs.unlockReason = ""

        EventStore.get(context).log(EventType.UNLOCK_GRANTED, how)
        PartnerNotifier.alert(
            context,
            PartnerNotifier.Kind.UNLOCK_GRANTED,
            "Shield: blocking is now unlocked for ${Prefs.UNLOCK_WINDOW_MINUTES} minutes ($how)."
        )
    }

    /** Closes the window early. Always allowed. */
    fun relock(context: Context) {
        if (Prefs.unlockWindowUntil == 0L) return
        Prefs.unlockWindowUntil = 0L
        Prefs.filterEnabled = true
        EventStore.get(context).log(EventType.PROTECTION_UP, "re-locked by user")
    }

    /**
     * Records an attempt to reach a screen that could weaken protection, and
     * tells the partner. Called by the on-screen guard.
     */
    fun recordTamperAttempt(context: Context, what: String) {
        EventStore.get(context).log(EventType.TAMPER_ATTEMPT, what)
        PartnerNotifier.alert(
            context,
            PartnerNotifier.Kind.TAMPER,
            "Shield: tamper attempt — $what."
        )
    }
}
