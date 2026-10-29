package app.guardian.shield.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Partner approval is the one thing standing between the user and switching the
 * blocking off, so the code generator is checked against the RFC 6238 reference
 * vectors rather than only against itself.
 */
class TotpTest {

    /** The RFC 6238 SHA-1 test seed, "12345678901234567890" as bytes. */
    private val referenceSecret = "12345678901234567890".toByteArray()

    @Test
    fun `matches RFC 6238 reference vectors`() {
        // The RFC publishes 8-digit values; the low 6 digits are what we generate.
        val vectors = mapOf(
            59L to "287082",
            1111111109L to "081804",
            1111111111L to "050471",
            1234567890L to "005924",
            2000000000L to "279037",
            20000000000L to "353130",
        )

        for ((epochSeconds, expected) in vectors) {
            val counter = Totp.counterFor(epochSeconds * 1000L)
            assertEquals(
                "at t=$epochSeconds",
                expected,
                Totp.codeAt(referenceSecret, counter)
            )
        }
    }

    @Test
    fun `counter advances every thirty seconds`() {
        assertEquals(0L, Totp.counterFor(0L))
        assertEquals(0L, Totp.counterFor(29_999L))
        assertEquals(1L, Totp.counterFor(30_000L))
        assertEquals(2L, Totp.counterFor(60_000L))
    }

    @Test
    fun `codes are always six digits`() {
        val secret = Totp.generateSecret()
        for (counter in 0L until 400L) {
            val code = Totp.codeAt(secret, counter)
            assertEquals(6, code.length)
            assertTrue(code.all { it.isDigit() })
        }
    }

    @Test
    fun `generated secrets are 160 bits and not repeated`() {
        val first = Totp.generateSecret()
        val second = Totp.generateSecret()
        assertEquals(20, first.size)
        assertEquals(20, second.size)
        assertTrue("two generated secrets must differ", !first.contentEquals(second))
    }

    @Test
    fun `verify accepts the current code`() {
        val secret = Totp.generateSecret()
        val now = 1_700_000_000_000L
        val code = Totp.codeAt(secret, Totp.counterFor(now))
        assertNotNull(Totp.verify(secret, code, atMillis = now))
    }

    @Test
    fun `verify tolerates clock drift within the window`() {
        val secret = Totp.generateSecret()
        val now = 1_700_000_000_000L
        val counter = Totp.counterFor(now)

        // One step either side must still work: the partner reads the code aloud
        // and the user takes a few seconds to type it.
        assertNotNull(Totp.verify(secret, Totp.codeAt(secret, counter - 1), atMillis = now))
        assertNotNull(Totp.verify(secret, Totp.codeAt(secret, counter + 1), atMillis = now))
    }

    @Test
    fun `verify rejects codes outside the drift window`() {
        val secret = Totp.generateSecret()
        val now = 1_700_000_000_000L
        val counter = Totp.counterFor(now)
        assertNull(Totp.verify(secret, Totp.codeAt(secret, counter - 5), atMillis = now))
        assertNull(Totp.verify(secret, Totp.codeAt(secret, counter + 5), atMillis = now))
    }

    @Test
    fun `verify rejects malformed input`() {
        val secret = Totp.generateSecret()
        assertNull(Totp.verify(secret, ""))
        assertNull(Totp.verify(secret, "12345"))
        assertNull(Totp.verify(secret, "1234567"))
        assertNull(Totp.verify(secret, "abcdef"))
    }

    @Test
    fun `verify refuses to replay a counter that was already used`() {
        val secret = Totp.generateSecret()
        val now = 1_700_000_000_000L
        val counter = Totp.counterFor(now)
        val code = Totp.codeAt(secret, counter)

        val accepted = Totp.verify(secret, code, atMillis = now)
        assertEquals(counter, accepted)

        // Replaying the same code once its counter has been recorded must fail,
        // so a code obtained "just to check" cannot be reused to unlock.
        assertNull(Totp.verify(secret, code, atMillis = now, minCounter = accepted!!))
    }

    @Test
    fun `verify ignores spaces and dashes in a code read aloud`() {
        val secret = Totp.generateSecret()
        val now = 1_700_000_000_000L
        val code = Totp.codeAt(secret, Totp.counterFor(now))
        val spaced = "${code.substring(0, 3)} ${code.substring(3)}"
        assertNotNull(Totp.verify(secret, spaced, atMillis = now))
    }

    @Test
    fun `a different secret does not validate`() {
        val now = 1_700_000_000_000L
        val code = Totp.codeAt(Totp.generateSecret(), Totp.counterFor(now))
        assertNull(Totp.verify(Totp.generateSecret(), code, atMillis = now))
    }
}

class Base32Test {

    @Test
    fun `matches RFC 4648 vectors`() {
        assertEquals("MY", Base32.encode("f".toByteArray()))
        assertEquals("MZXQ", Base32.encode("fo".toByteArray()))
        assertEquals("MZXW6", Base32.encode("foo".toByteArray()))
        assertEquals("MZXW6YQ", Base32.encode("foob".toByteArray()))
        assertEquals("MZXW6YTB", Base32.encode("fooba".toByteArray()))
        assertEquals("MZXW6YTBOI", Base32.encode("foobar".toByteArray()))
    }

    @Test
    fun `encodes the RFC 6238 seed the way authenticator apps expect`() {
        assertEquals(
            "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
            Base32.encode("12345678901234567890".toByteArray())
        )
    }

    @Test
    fun `round trips arbitrary bytes`() {
        val data = ByteArray(20) { (it * 7 + 3).toByte() }
        assertTrue(data.contentEquals(Base32.decode(Base32.encode(data))))
    }

    @Test
    fun `decode is forgiving about formatting`() {
        val data = "foobar".toByteArray()
        val encoded = Base32.encode(data)
        assertTrue(data.contentEquals(Base32.decode(encoded.lowercase())))
        assertTrue(data.contentEquals(Base32.decode(Base32.formatForDisplay(encoded))))
        assertTrue(data.contentEquals(Base32.decode("$encoded======")))
    }

    @Test
    fun `decode rejects invalid characters`() {
        assertEquals(null, Base32.decode("MZXW6!!!"))
        assertEquals(null, Base32.decode("1908"))   // 0, 1 and 8 are not in the alphabet
        assertEquals(null, Base32.decode(""))
    }

    @Test
    fun `display grouping is readable in blocks of four`() {
        assertEquals("MZXW 6YTB OI", Base32.formatForDisplay("MZXW6YTBOI"))
    }
}
