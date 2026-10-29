package app.guardian.shield.vpn

import android.net.VpnService
import app.guardian.shield.dns.DnsMessage
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Forwards queries to the family-filtering resolvers over sockets that are
 * [VpnService.protect]ed, so they leave through the real network instead of
 * looping back into our own tun.
 */
class UpstreamResolver(private val vpn: VpnService) {

    private class CachedAddress(val address: ByteArray, val expiresAt: Long)

    private val addressCache = ConcurrentHashMap<String, CachedAddress>()

    /** Sends [query] upstream and returns the raw response, or null on failure. */
    fun forward(query: ByteArray): ByteArray? {
        for (server in Upstreams.FAMILY_RESOLVERS) {
            queryServer(server, query)?.let { return it }
        }
        return null
    }

    private fun queryServer(server: String, query: ByteArray): ByteArray? = runCatching {
        DatagramSocket().use { socket ->
            if (!vpn.protect(socket)) return null
            socket.soTimeout = Upstreams.QUERY_TIMEOUT_MS

            val address = InetAddress.getByName(server)
            socket.send(DatagramPacket(query, query.size, address, Upstreams.DNS_PORT))

            val buffer = ByteArray(Upstreams.MAX_UDP_PAYLOAD)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)

            // Reject responses whose transaction id doesn't match the query.
            if (response.length < 12) return null
            if (DnsMessage.transactionId(buffer) != DnsMessage.transactionId(query)) return null

            buffer.copyOf(response.length)
        }
    }.getOrNull()

    /**
     * Resolves [host] to a raw address of the requested family, for SafeSearch
     * rewriting. Cached, because these few hostnames are looked up constantly.
     */
    fun resolveAddress(host: String, wantIpv6: Boolean): ByteArray? {
        val key = "$host/${if (wantIpv6) "6" else "4"}"
        addressCache[key]?.let { cached ->
            if (cached.expiresAt > System.currentTimeMillis()) return cached.address
            addressCache.remove(key)
        }

        val type = if (wantIpv6) DnsMessage.TYPE_AAAA else DnsMessage.TYPE_A
        val response = forward(buildQuery(host, type)) ?: return null
        val address = extractFirstAddress(response, type) ?: return null

        addressCache[key] = CachedAddress(address, System.currentTimeMillis() + CACHE_TTL_MS)
        return address
    }

    private fun buildQuery(host: String, type: Int): ByteArray {
        val labels = host.split('.').filter { it.isNotEmpty() }
        var size = 12 + 1 + 4
        for (label in labels) size += 1 + label.length

        val q = ByteArray(size)
        val id = Random.nextInt(0, 0xFFFF)
        q[0] = ((id shr 8) and 0xFF).toByte()
        q[1] = (id and 0xFF).toByte()
        q[2] = 0x01 // recursion desired
        q[3] = 0x00
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
        q[pos] = 1 // class IN

        return q
    }

    /**
     * Pulls the first A/AAAA record out of a response, following the answer
     * section past any CNAMEs.
     */
    private fun extractFirstAddress(response: ByteArray, wantType: Int): ByteArray? {
        val answerCount = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
        if (answerCount <= 0) return null

        var pos = skipName(response, 12) ?: return null
        pos += 4 // qtype + qclass
        if (pos > response.size) return null

        val wantLength = if (wantType == DnsMessage.TYPE_AAAA) 16 else 4

        repeat(answerCount) {
            pos = skipName(response, pos) ?: return null
            if (pos + 10 > response.size) return null

            val type = ((response[pos].toInt() and 0xFF) shl 8) or (response[pos + 1].toInt() and 0xFF)
            val rdLength = ((response[pos + 8].toInt() and 0xFF) shl 8) or
                (response[pos + 9].toInt() and 0xFF)
            pos += 10

            if (pos + rdLength > response.size) return null
            if (type == wantType && rdLength == wantLength) {
                return response.copyOfRange(pos, pos + rdLength)
            }
            pos += rdLength
        }
        return null
    }

    /** Returns the offset just past a (possibly compressed) name. */
    private fun skipName(buf: ByteArray, start: Int): Int? {
        var pos = start
        var guard = 0
        while (pos < buf.size) {
            val len = buf[pos].toInt() and 0xFF
            when {
                len == 0 -> return pos + 1
                len and 0xC0 == 0xC0 -> return pos + 2 // pointer ends the name
                else -> pos += 1 + len
            }
            if (++guard > 128) return null
        }
        return null
    }

    fun clearCache() = addressCache.clear()

    private companion object {
        const val CACHE_TTL_MS = 10 * 60 * 1000L
    }
}
