package app.guardian.shield.lock

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 time-based one-time passwords.
 *
 * This is what makes partner approval work with no server and no internet. The
 * partner stores the seed in any standard authenticator app (Google
 * Authenticator, Aegis, FreeOTP). When the user asks to be let out, the partner
 * reads out the current six digits. The phone verifies them offline.
 */
object Totp {

    private const val STEP_SECONDS = 30L
    private const val DIGITS = 6
    private const val ALGORITHM = "HmacSHA1"

    fun generateSecret(): ByteArray = ByteArray(20).also { SecureRandom().nextBytes(it) }

    fun counterFor(timeMillis: Long): Long = timeMillis / 1000L / STEP_SECONDS

    fun codeAt(secret: ByteArray, counter: Long): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(secret, ALGORITHM))
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())

        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)

        var modulus = 1
        repeat(DIGITS) { modulus *= 10 }
        return (binary % modulus).toString().padStart(DIGITS, '0')
    }

    /**
     * Verifies [code] against [secret], tolerating [driftSteps] of clock skew
     * either way.
     *
     * @param minCounter counters at or below this are rejected, so a code that
     *   has already been used cannot be replayed within its validity window.
     * @return the counter the code matched, or null if it did not match.
     */
    fun verify(
        secret: ByteArray,
        code: String,
        atMillis: Long = System.currentTimeMillis(),
        driftSteps: Int = 2,
        minCounter: Long = 0,
    ): Long? {
        val cleaned = code.filter { it.isDigit() }
        if (cleaned.length != DIGITS) return null

        val current = counterFor(atMillis)
        for (delta in -driftSteps..driftSteps) {
            val counter = current + delta
            if (counter <= minCounter) continue
            if (constantTimeEquals(codeAt(secret, counter), cleaned)) return counter
        }
        return null
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    /** The standard URI an authenticator app can import, usually via QR code. */
    fun provisioningUri(secret: ByteArray, accountLabel: String, issuer: String): String {
        val encodedIssuer = android.net.Uri.encode(issuer)
        val encodedLabel = android.net.Uri.encode(accountLabel)
        return "otpauth://totp/$encodedIssuer:$encodedLabel" +
            "?secret=${Base32.encode(secret)}" +
            "&issuer=$encodedIssuer&algorithm=SHA1&digits=$DIGITS&period=$STEP_SECONDS"
    }
}

/** Base32 (RFC 4648, no padding) — the encoding authenticator apps expect. */
object Base32 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val out = StringBuilder()
        var buffer = 0
        var bitsLeft = 0

        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                out.append(ALPHABET[(buffer shr (bitsLeft - 5)) and 0x1F])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            out.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        return out.toString()
    }

    fun decode(encoded: String): ByteArray? {
        val clean = encoded.trim().replace(" ", "").replace("-", "")
            .uppercase().trimEnd('=')
        if (clean.isEmpty()) return null

        var buffer = 0
        var bitsLeft = 0
        val out = ArrayList<Byte>(clean.length * 5 / 8 + 1)

        for (c in clean) {
            val value = ALPHABET.indexOf(c)
            if (value < 0) return null
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.add(((buffer shr (bitsLeft - 8)) and 0xFF).toByte())
                bitsLeft -= 8
            }
        }
        return out.toByteArray()
    }

    /** Groups into blocks of four so a human can read it aloud without losing place. */
    fun formatForDisplay(encoded: String): String =
        encoded.chunked(4).joinToString(" ")
}
