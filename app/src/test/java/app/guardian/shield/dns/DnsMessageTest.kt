package app.guardian.shield.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DNS codec is the single most load-bearing piece of code in the app: if it
 * misreads a question the wrong site gets through, and if it writes a malformed
 * answer the whole device loses name resolution.
 */
class DnsMessageTest {

    /** Builds a standard recursive query, the way a client would. */
    private fun query(name: String, type: Int = DnsMessage.TYPE_A, id: Int = 0x1234): ByteArray {
        val labels = name.split('.').filter { it.isNotEmpty() }
        var size = 12 + 1 + 4
        for (label in labels) size += 1 + label.length

        val q = ByteArray(size)
        q[0] = ((id shr 8) and 0xFF).toByte()
        q[1] = (id and 0xFF).toByte()
        q[2] = 0x01 // recursion desired
        q[5] = 0x01 // qdcount = 1

        var pos = 12
        for (label in labels) {
            q[pos++] = label.length.toByte()
            for (c in label) q[pos++] = c.code.toByte()
        }
        q[pos++] = 0
        q[pos++] = ((type shr 8) and 0xFF).toByte()
        q[pos++] = (type and 0xFF).toByte()
        q[pos++] = 0
        q[pos] = 1
        return q
    }

    private fun u16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    @Test
    fun `parses a simple question`() {
        val parsed = DnsMessage.parseQuestion(query("www.example.com"), query("www.example.com").size)
        assertNotNull(parsed)
        assertEquals("www.example.com", parsed!!.name)
        assertEquals(DnsMessage.TYPE_A, parsed.type)
        assertEquals(1, parsed.qClass)
    }

    @Test
    fun `lowercases the question name`() {
        val q = query("WWW.ExAmPlE.CoM")
        assertEquals("www.example.com", DnsMessage.parseQuestion(q, q.size)!!.name)
    }

    @Test
    fun `parses AAAA and HTTPS question types`() {
        val aaaa = query("example.com", DnsMessage.TYPE_AAAA)
        assertEquals(DnsMessage.TYPE_AAAA, DnsMessage.parseQuestion(aaaa, aaaa.size)!!.type)

        val https = query("example.com", DnsMessage.TYPE_HTTPS)
        assertEquals(DnsMessage.TYPE_HTTPS, DnsMessage.parseQuestion(https, https.size)!!.type)
    }

    @Test
    fun `reads the transaction id`() {
        assertEquals(0xABCD, DnsMessage.transactionId(query("example.com", id = 0xABCD)))
    }

    @Test
    fun `rejects responses and truncated packets`() {
        val response = query("example.com").also { it[2] = 0x80.toByte() } // QR bit set
        assertNull(DnsMessage.parseQuestion(response, response.size))

        val q = query("example.com")
        assertNull(DnsMessage.parseQuestion(q, 8))            // shorter than a header
        assertNull(DnsMessage.parseQuestion(q, q.size - 3))   // question cut short
    }

    @Test
    fun `rejects a question with no entries`() {
        val q = query("example.com").also { it[5] = 0 } // qdcount = 0
        assertNull(DnsMessage.parseQuestion(q, q.size))
    }

    @Test
    fun `rejects a compression pointer in the question`() {
        val q = query("example.com").also { it[12] = 0xC0.toByte() }
        assertNull(DnsMessage.parseQuestion(q, q.size))
    }

    @Test
    fun `refusal keeps the id and question and reports the rcode`() {
        val q = query("blocked.example")
        val parsed = DnsMessage.parseQuestion(q, q.size)!!
        val response = DnsMessage.buildRefusal(q, parsed, DnsMessage.RCODE_NXDOMAIN)

        assertEquals(0x1234, DnsMessage.transactionId(response))

        val flags = u16(response, 2)
        assertTrue("QR bit must be set", flags and 0x8000 != 0)
        assertTrue("RA bit should be set", flags and 0x0080 != 0)
        assertTrue("RD should be preserved", flags and 0x0100 != 0)
        assertEquals(DnsMessage.RCODE_NXDOMAIN, flags and 0x0F)

        assertEquals(1, u16(response, 4)) // qdcount
        assertEquals(0, u16(response, 6)) // ancount
        assertEquals(0, u16(response, 8)) // nscount
        assertEquals(0, u16(response, 10)) // arcount

        // The question section must come back byte-for-byte.
        assertEquals(parsed.endOffset, response.size)
        for (i in 12 until parsed.endOffset) {
            assertEquals("byte $i", q[i], response[i])
        }
    }

    @Test
    fun `address answer is well formed`() {
        val q = query("www.google.com")
        val parsed = DnsMessage.parseQuestion(q, q.size)!!
        val address = byteArrayOf(216.toByte(), 239.toByte(), 38, 120)
        val response = DnsMessage.buildAddressAnswer(q, parsed, address, 300)

        assertEquals(1, u16(response, 6))                       // one answer
        assertEquals(DnsMessage.RCODE_NOERROR, u16(response, 2) and 0x0F)

        var pos = parsed.endOffset
        assertEquals(0xC0, response[pos].toInt() and 0xFF)      // name is a pointer
        assertEquals(0x0C, response[pos + 1].toInt() and 0xFF)  // ...to offset 12
        pos += 2

        assertEquals(DnsMessage.TYPE_A, u16(response, pos)); pos += 2
        assertEquals(1, u16(response, pos)); pos += 2           // class IN

        val ttl = ((response[pos].toInt() and 0xFF) shl 24) or
            ((response[pos + 1].toInt() and 0xFF) shl 16) or
            ((response[pos + 2].toInt() and 0xFF) shl 8) or
            (response[pos + 3].toInt() and 0xFF)
        assertEquals(300, ttl); pos += 4

        assertEquals(4, u16(response, pos)); pos += 2           // rdlength
        assertEquals(address.toList(), response.copyOfRange(pos, pos + 4).toList())
        assertEquals(pos + 4, response.size)
    }

    @Test
    fun `ipv6 address answer uses the AAAA type and a 16 byte record`() {
        val q = query("www.google.com", DnsMessage.TYPE_AAAA)
        val parsed = DnsMessage.parseQuestion(q, q.size)!!
        val address = ByteArray(16) { it.toByte() }
        val response = DnsMessage.buildAddressAnswer(q, parsed, address, 60)

        val typeOffset = parsed.endOffset + 2
        assertEquals(DnsMessage.TYPE_AAAA, u16(response, typeOffset))
        assertEquals(16, u16(response, typeOffset + 8))
    }

    @Test
    fun `handles the longest practical hostname`() {
        val name = (1..6).joinToString(".") { "label$it".repeat(5) } + ".com"
        val q = query(name)
        assertEquals(name.lowercase(), DnsMessage.parseQuestion(q, q.size)!!.name)
    }
}
