package app.guardian.shield.vpn

/** A UDP datagram lifted out of a raw IP packet read from the tun device. */
class UdpDatagram(
    val isIpv6: Boolean,
    val srcAddr: ByteArray,
    val dstAddr: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val payload: ByteArray,
)

/**
 * Just enough IPv4/IPv6 + UDP to read DNS queries off a tun interface and write
 * replies back. Anything that is not UDP is ignored by the caller and therefore
 * never needs to be re-encoded.
 */
object IpPacket {

    private const val PROTO_UDP = 17

    fun parseUdp(buf: ByteArray, length: Int): UdpDatagram? {
        if (length < 1) return null
        return when ((buf[0].toInt() and 0xF0) shr 4) {
            4 -> parseIpv4Udp(buf, length)
            6 -> parseIpv6Udp(buf, length)
            else -> null
        }
    }

    private fun parseIpv4Udp(buf: ByteArray, length: Int): UdpDatagram? {
        if (length < 20) return null
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl + 8) return null
        if ((buf[9].toInt() and 0xFF) != PROTO_UDP) return null

        // Fragmented packets can't be interpreted on their own; DNS queries fit
        // in one datagram in practice, so dropping fragments is safe.
        val fragOffset = (((buf[6].toInt() and 0x1F) shl 8) or (buf[7].toInt() and 0xFF))
        if (fragOffset != 0) return null

        val totalLength = readU16(buf, 2).coerceAtMost(length)
        val src = buf.copyOfRange(12, 16)
        val dst = buf.copyOfRange(16, 20)

        val srcPort = readU16(buf, ihl)
        val dstPort = readU16(buf, ihl + 2)
        val udpLength = readU16(buf, ihl + 4)
        if (udpLength < 8) return null

        val payloadLength = (udpLength - 8).coerceAtMost(totalLength - ihl - 8)
        if (payloadLength < 0) return null

        val payload = buf.copyOfRange(ihl + 8, ihl + 8 + payloadLength)
        return UdpDatagram(false, src, dst, srcPort, dstPort, payload)
    }

    private fun parseIpv6Udp(buf: ByteArray, length: Int): UdpDatagram? {
        if (length < 40) return null
        if ((buf[6].toInt() and 0xFF) != PROTO_UDP) return null // no extension-header walk

        val payloadLength = readU16(buf, 4)
        if (length < 40 + 8) return null

        val src = buf.copyOfRange(8, 24)
        val dst = buf.copyOfRange(24, 40)

        val srcPort = readU16(buf, 40)
        val dstPort = readU16(buf, 42)
        val udpLength = readU16(buf, 44)
        if (udpLength < 8) return null

        val available = minOf(payloadLength, length - 40)
        val bodyLength = (udpLength - 8).coerceAtMost(available - 8)
        if (bodyLength < 0) return null

        val payload = buf.copyOfRange(48, 48 + bodyLength)
        return UdpDatagram(true, src, dst, srcPort, dstPort, payload)
    }

    /** Builds a reply to [request], swapping source and destination. */
    fun buildUdpReply(request: UdpDatagram, payload: ByteArray): ByteArray =
        if (request.isIpv6) {
            buildIpv6Udp(request.dstAddr, request.srcAddr, request.dstPort, request.srcPort, payload)
        } else {
            buildIpv4Udp(request.dstAddr, request.srcAddr, request.dstPort, request.srcPort, payload)
        }

    private fun buildIpv4Udp(
        src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray,
    ): ByteArray {
        val udpLength = 8 + payload.size
        val total = 20 + udpLength
        val p = ByteArray(total)

        p[0] = 0x45                        // IPv4, 20-byte header
        p[1] = 0
        writeU16(p, 2, total)
        writeU16(p, 4, 0)                  // identification
        writeU16(p, 6, 0x4000)             // don't fragment
        p[8] = 64                          // TTL
        p[9] = PROTO_UDP.toByte()
        writeU16(p, 10, 0)                 // checksum placeholder
        System.arraycopy(src, 0, p, 12, 4)
        System.arraycopy(dst, 0, p, 16, 4)
        writeU16(p, 10, checksum(p, 0, 20))

        writeU16(p, 20, srcPort)
        writeU16(p, 22, dstPort)
        writeU16(p, 24, udpLength)
        writeU16(p, 26, 0)
        System.arraycopy(payload, 0, p, 28, payload.size)
        writeU16(p, 26, udpChecksum(p, src, dst, 20, udpLength, isIpv6 = false))

        return p
    }

    private fun buildIpv6Udp(
        src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray,
    ): ByteArray {
        val udpLength = 8 + payload.size
        val total = 40 + udpLength
        val p = ByteArray(total)

        p[0] = 0x60                        // IPv6, traffic class 0
        writeU16(p, 4, udpLength)          // payload length
        p[6] = PROTO_UDP.toByte()          // next header
        p[7] = 64                          // hop limit
        System.arraycopy(src, 0, p, 8, 16)
        System.arraycopy(dst, 0, p, 24, 16)

        writeU16(p, 40, srcPort)
        writeU16(p, 42, dstPort)
        writeU16(p, 44, udpLength)
        writeU16(p, 46, 0)
        System.arraycopy(payload, 0, p, 48, payload.size)

        var sum = udpChecksum(p, src, dst, 40, udpLength, isIpv6 = true)
        // UDP forbids a zero checksum; 0xFFFF is the equivalent encoding.
        if (sum == 0) sum = 0xFFFF
        writeU16(p, 46, sum)

        return p
    }

    private fun checksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (buf[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun udpChecksum(
        packet: ByteArray,
        src: ByteArray,
        dst: ByteArray,
        udpOffset: Int,
        udpLength: Int,
        isIpv6: Boolean,
    ): Int {
        var sum = 0L

        // Pseudo-header: addresses, protocol, UDP length.
        var i = 0
        while (i + 1 < src.size) {
            sum += ((src[i].toInt() and 0xFF) shl 8) or (src[i + 1].toInt() and 0xFF)
            i += 2
        }
        i = 0
        while (i + 1 < dst.size) {
            sum += ((dst[i].toInt() and 0xFF) shl 8) or (dst[i + 1].toInt() and 0xFF)
            i += 2
        }
        sum += PROTO_UDP.toLong()
        if (isIpv6) {
            sum += (udpLength.toLong() shr 16) and 0xFFFF
        }
        sum += (udpLength.toLong() and 0xFFFF)

        // UDP header and data.
        i = udpOffset
        val end = udpOffset + udpLength
        while (i + 1 < end) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (packet[i].toInt() and 0xFF) shl 8

        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun readU16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private fun writeU16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }
}
