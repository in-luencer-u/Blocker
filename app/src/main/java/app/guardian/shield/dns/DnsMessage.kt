package app.guardian.shield.dns

import java.io.ByteArrayOutputStream

/** A single parsed DNS question. */
data class DnsQuestion(
    val name: String,
    val type: Int,
    val qClass: Int,
    /** Offset just past the question section, used when appending answers. */
    val endOffset: Int,
)

/**
 * Minimal DNS wire-format reader/writer.
 *
 * Only what a filtering resolver needs: read the question, and synthesise either
 * a refusal or a rewritten A/AAAA answer. Everything else is forwarded upstream
 * untouched, so we never have to re-encode a full response.
 */
object DnsMessage {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TYPE_CNAME = 5

    /**
     * Type 65 (HTTPS/SVCB). Chrome and Safari use these records to discover
     * Encrypted Client Hello keys. Refusing them keeps TLS handshakes readable
     * to the SNI-based checks and stops ECH from hiding a blocked hostname.
     */
    const val TYPE_HTTPS = 65

    const val RCODE_NOERROR = 0
    const val RCODE_NXDOMAIN = 3
    const val RCODE_REFUSED = 5

    private const val HEADER_LEN = 12
    private const val MAX_NAME_LEN = 255

    fun transactionId(buf: ByteArray): Int =
        if (buf.size < 2) 0 else ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)

    /** Reads the first question, or null if the packet is not a parsable query. */
    fun parseQuestion(buf: ByteArray, length: Int): DnsQuestion? {
        if (length < HEADER_LEN + 5) return null

        val flags = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
        val isResponse = (flags and 0x8000) != 0
        if (isResponse) return null

        val opcode = (flags shr 11) and 0x0F
        if (opcode != 0) return null // only standard queries

        val qdCount = readU16(buf, 4)
        if (qdCount < 1) return null

        val sb = StringBuilder()
        var pos = HEADER_LEN
        var guard = 0

        while (true) {
            if (pos >= length) return null
            val len = buf[pos].toInt() and 0xFF

            if (len == 0) {
                pos++
                break
            }

            // Compression pointer: legal but unusual in a question. Refuse rather
            // than risk a loop, the caller will just forward the packet.
            if (len and 0xC0 == 0xC0) return null

            pos++
            if (pos + len > length) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until len) {
                sb.append(((buf[pos + i].toInt() and 0xFF).toChar()).lowercaseChar())
            }
            pos += len

            if (sb.length > MAX_NAME_LEN) return null
            if (++guard > 64) return null
        }

        if (pos + 4 > length) return null
        val qType = readU16(buf, pos)
        val qClass = readU16(buf, pos + 2)
        pos += 4

        return DnsQuestion(sb.toString(), qType, qClass, pos)
    }

    /** A response with no answers and the given rcode. */
    fun buildRefusal(query: ByteArray, question: DnsQuestion, rcode: Int): ByteArray {
        val out = ByteArray(question.endOffset)
        System.arraycopy(query, 0, out, 0, question.endOffset)
        writeResponseHeader(out, query, rcode, answerCount = 0)
        return out
    }

    /**
     * A response that answers the original name with [address] — a 4-byte IPv4
     * or 16-byte IPv6 value. Used for SafeSearch redirection and for sinkholing.
     */
    fun buildAddressAnswer(
        query: ByteArray,
        question: DnsQuestion,
        address: ByteArray,
        ttlSeconds: Int,
    ): ByteArray {
        val type = if (address.size == 4) TYPE_A else TYPE_AAAA
        val body = ByteArrayOutputStream()
        body.write(query, 0, question.endOffset)

        // NAME as a pointer back to the question name at offset 12.
        body.write(0xC0)
        body.write(0x0C)
        writeU16(body, type)
        writeU16(body, 1) // IN
        body.write((ttlSeconds ushr 24) and 0xFF)
        body.write((ttlSeconds ushr 16) and 0xFF)
        body.write((ttlSeconds ushr 8) and 0xFF)
        body.write(ttlSeconds and 0xFF)
        writeU16(body, address.size)
        body.write(address)

        val out = body.toByteArray()
        writeResponseHeader(out, query, RCODE_NOERROR, answerCount = 1)
        return out
    }

    private fun writeResponseHeader(out: ByteArray, query: ByteArray, rcode: Int, answerCount: Int) {
        // Transaction id is already copied along with the question bytes.
        val queryFlags = ((query[2].toInt() and 0xFF) shl 8) or (query[3].toInt() and 0xFF)
        val rd = queryFlags and 0x0100 // preserve "recursion desired"
        val flags = 0x8000 or rd or 0x0080 or (rcode and 0x0F) // QR | RD | RA | rcode
        out[2] = ((flags shr 8) and 0xFF).toByte()
        out[3] = (flags and 0xFF).toByte()
        out[4] = 0; out[5] = 1                                   // qdcount = 1
        out[6] = ((answerCount shr 8) and 0xFF).toByte()
        out[7] = (answerCount and 0xFF).toByte()
        out[8] = 0; out[9] = 0                                   // nscount
        out[10] = 0; out[11] = 0                                 // arcount
    }

    private fun readU16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}
