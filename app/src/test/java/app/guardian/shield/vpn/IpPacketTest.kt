package app.guardian.shield.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A wrong checksum here means the kernel silently drops every reply we write and
 * the device appears to have no DNS at all, so the checksums are verified
 * arithmetically rather than just eyeballed.
 */
class IpPacketTest {

    private val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

    private fun ipv4UdpPacket(
        src: ByteArray = byteArrayOf(10, 0, 0, 2),
        dst: ByteArray = byteArrayOf(10, 111, 222.toByte(), 2),
        srcPort: Int = 40000,
        dstPort: Int = 53,
        body: ByteArray = payload,
    ): ByteArray {
        val total = 20 + 8 + body.size
        val p = ByteArray(total)
        p[0] = 0x45
        p[2] = ((total shr 8) and 0xFF).toByte()
        p[3] = (total and 0xFF).toByte()
        p[8] = 64
        p[9] = 17
        System.arraycopy(src, 0, p, 12, 4)
        System.arraycopy(dst, 0, p, 16, 4)
        p[20] = ((srcPort shr 8) and 0xFF).toByte()
        p[21] = (srcPort and 0xFF).toByte()
        p[22] = ((dstPort shr 8) and 0xFF).toByte()
        p[23] = (dstPort and 0xFF).toByte()
        val udpLength = 8 + body.size
        p[24] = ((udpLength shr 8) and 0xFF).toByte()
        p[25] = (udpLength and 0xFF).toByte()
        System.arraycopy(body, 0, p, 28, body.size)
        return p
    }

    private fun ipv6UdpPacket(body: ByteArray = payload): ByteArray {
        val udpLength = 8 + body.size
        val p = ByteArray(40 + udpLength)
        p[0] = 0x60
        p[4] = ((udpLength shr 8) and 0xFF).toByte()
        p[5] = (udpLength and 0xFF).toByte()
        p[6] = 17
        p[7] = 64
        p[8] = 0xFD.toByte(); p[23] = 1   // fd00::1
        p[24] = 0xFD.toByte(); p[39] = 2  // fd00::2
        p[40] = 0x9C.toByte(); p[41] = 0x40.toByte() // src port 40000
        p[43] = 53
        p[44] = ((udpLength shr 8) and 0xFF).toByte()
        p[45] = (udpLength and 0xFF).toByte()
        System.arraycopy(body, 0, p, 48, body.size)
        return p
    }

    /** Standard internet checksum. Over a correct header it must come to zero. */
    private fun ones(buf: ByteArray, offset: Int, length: Int, initial: Long = 0): Int {
        var sum = initial
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

    @Test
    fun `parses an ipv4 udp datagram`() {
        val parsed = IpPacket.parseUdp(ipv4UdpPacket(), ipv4UdpPacket().size)
        assertNotNull(parsed)
        parsed!!
        assertEquals(false, parsed.isIpv6)
        assertEquals(40000, parsed.srcPort)
        assertEquals(53, parsed.dstPort)
        assertArrayEquals(byteArrayOf(10, 0, 0, 2), parsed.srcAddr)
        assertArrayEquals(byteArrayOf(10, 111, 222.toByte(), 2), parsed.dstAddr)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun `parses an ipv6 udp datagram`() {
        val packet = ipv6UdpPacket()
        val parsed = IpPacket.parseUdp(packet, packet.size)
        assertNotNull(parsed)
        parsed!!
        assertTrue(parsed.isIpv6)
        assertEquals(40000, parsed.srcPort)
        assertEquals(53, parsed.dstPort)
        assertEquals(16, parsed.srcAddr.size)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun `ignores non-udp, fragments and malformed input`() {
        val tcp = ipv4UdpPacket().also { it[9] = 6 }
        assertNull(IpPacket.parseUdp(tcp, tcp.size))

        val fragment = ipv4UdpPacket().also { it[7] = 1 } // non-zero fragment offset
        assertNull(IpPacket.parseUdp(fragment, fragment.size))

        assertNull(IpPacket.parseUdp(ByteArray(0), 0))
        assertNull(IpPacket.parseUdp(ByteArray(10), 10))
        assertNull(IpPacket.parseUdp(byteArrayOf(0x75), 1)) // version 7
    }

    @Test
    fun `ipv4 reply swaps addresses and ports`() {
        val request = IpPacket.parseUdp(ipv4UdpPacket(), ipv4UdpPacket().size)!!
        val answer = byteArrayOf(9, 9, 9, 9, 9)
        val reply = IpPacket.buildUdpReply(request, answer)

        val parsedReply = IpPacket.parseUdp(reply, reply.size)!!
        assertArrayEquals(request.dstAddr, parsedReply.srcAddr)
        assertArrayEquals(request.srcAddr, parsedReply.dstAddr)
        assertEquals(request.dstPort, parsedReply.srcPort)
        assertEquals(request.srcPort, parsedReply.dstPort)
        assertArrayEquals(answer, parsedReply.payload)
    }

    @Test
    fun `ipv4 reply has a valid header checksum`() {
        val request = IpPacket.parseUdp(ipv4UdpPacket(), ipv4UdpPacket().size)!!
        val reply = IpPacket.buildUdpReply(request, payload)
        assertEquals("IPv4 header checksum", 0, ones(reply, 0, 20))
        assertEquals("total length", reply.size, ((reply[2].toInt() and 0xFF) shl 8) or (reply[3].toInt() and 0xFF))
    }

    @Test
    fun `ipv4 reply has a valid udp checksum`() {
        val request = IpPacket.parseUdp(ipv4UdpPacket(), ipv4UdpPacket().size)!!
        val reply = IpPacket.buildUdpReply(request, payload)

        val udpLength = ((reply[24].toInt() and 0xFF) shl 8) or (reply[25].toInt() and 0xFF)
        // Pseudo-header: src, dst, zero, protocol, UDP length.
        var sum = 0L
        for (i in 12 until 20 step 2) {
            sum += ((reply[i].toInt() and 0xFF) shl 8) or (reply[i + 1].toInt() and 0xFF)
        }
        sum += 17
        sum += udpLength
        assertEquals("UDP checksum", 0, ones(reply, 20, udpLength, sum))
    }

    @Test
    fun `ipv6 reply has a valid udp checksum`() {
        val packet = ipv6UdpPacket()
        val request = IpPacket.parseUdp(packet, packet.size)!!
        val reply = IpPacket.buildUdpReply(request, payload)

        val udpLength = ((reply[44].toInt() and 0xFF) shl 8) or (reply[45].toInt() and 0xFF)
        var sum = 0L
        for (i in 8 until 40 step 2) {
            sum += ((reply[i].toInt() and 0xFF) shl 8) or (reply[i + 1].toInt() and 0xFF)
        }
        sum += 17
        sum += udpLength
        assertEquals("IPv6 UDP checksum", 0, ones(reply, 40, udpLength, sum))
    }

    @Test
    fun `handles an empty payload`() {
        val packet = ipv4UdpPacket(body = ByteArray(0))
        val parsed = IpPacket.parseUdp(packet, packet.size)!!
        assertEquals(0, parsed.payload.size)

        val reply = IpPacket.buildUdpReply(parsed, ByteArray(0))
        assertEquals(28, reply.size)
        assertEquals(0, ones(reply, 0, 20))
    }
}
