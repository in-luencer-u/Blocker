package app.guardian.shield.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import app.guardian.shield.R
import app.guardian.shield.core.Notifications
import app.guardian.shield.core.Prefs
import app.guardian.shield.data.EventStore
import app.guardian.shield.data.EventType
import app.guardian.shield.dns.Blocklist
import app.guardian.shield.dns.DnsDecision
import app.guardian.shield.dns.DnsFilter
import app.guardian.shield.dns.DnsMessage
import app.guardian.shield.guard.BlockSignal
import app.guardian.shield.partner.PartnerNotifier
import app.guardian.shield.service.Watchdog
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The always-on DNS filter.
 *
 * Rather than tunnelling all traffic (slow, battery-hungry, and easy to break),
 * this captures only DNS: the tun gets a /32 address, and the only routes added
 * are the virtual DNS server plus the well-known public resolvers. Everything
 * else goes out over the normal network untouched, so there is no throughput
 * cost and no app compatibility risk — but no app can resolve a blocked name.
 */
class ShieldVpnService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)

    /** Set when the user legitimately stopped the filter inside an unlock window. */
    private var stoppedDeliberately = false

    private lateinit var resolver: UpstreamResolver
    private val queryPool: ThreadPoolExecutor =
        Executors.newFixedThreadPool(QUERY_THREADS) as ThreadPoolExecutor

    private val writeLock = Any()
    private var tunOut: FileOutputStream? = null

    override fun onCreate() {
        super.onCreate()
        resolver = UpstreamResolver(this)
        Notifications.ensureChannels(this)
        Blocklist.ensureLoaded(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Only honoured while a partner-approved unlock window is open.
                stoppedDeliberately = true
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForeground(
            Notifications.ID_PROTECTION,
            Notifications.buildProtectionNotification(this, getString(R.string.notif_protection_on))
        )

        if (running.compareAndSet(false, true)) {
            startTunnel()
        }

        Watchdog.schedule(this)
        return START_STICKY
    }

    private fun startTunnel() {
        val fd = establish() ?: run {
            running.set(false)
            EventStore.get(this).log(EventType.PROTECTION_DOWN, "could not establish tunnel")
            return
        }
        tunnel = fd
        tunOut = FileOutputStream(fd.fileDescriptor)
        isActive = true
        EventStore.get(this).log(EventType.PROTECTION_UP, "DNS filter active")

        worker = Thread({ pumpPackets(fd) }, "shield-dns").apply {
            priority = Thread.NORM_PRIORITY + 1
            isDaemon = true
            start()
        }
    }

    private fun establish(): ParcelFileDescriptor? = runCatching {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(MTU)
            .addAddress(VIRTUAL_CLIENT_V4, 32)
            .addDnsServer(VIRTUAL_DNS_V4)
            .addRoute(VIRTUAL_DNS_V4, 32)
            .setBlocking(true)

        // Capture apps that talk to a public resolver directly instead of using
        // the system one. Their DoH/DoT ports simply get no answer, so encrypted
        // DNS fails closed and the app falls back to us.
        for (ip in Upstreams.CAPTURED_RESOLVERS) {
            runCatching { builder.addRoute(ip, 32) }
        }

        // IPv6, so a dual-stack network can't leak lookups around the filter.
        runCatching {
            builder.addAddress(VIRTUAL_CLIENT_V6, 128)
                .addDnsServer(VIRTUAL_DNS_V6)
                .addRoute(VIRTUAL_DNS_V6, 128)
            for (ip in Upstreams.CAPTURED_RESOLVERS_V6) {
                runCatching { builder.addRoute(ip, 128) }
            }
        }

        // Our own traffic must not be captured, or upstream lookups would loop.
        runCatching { builder.addDisallowedApplication(packageName) }

        builder.establish()
    }.onFailure { Log.w(TAG, "establish failed", it) }.getOrNull()

    private fun pumpPackets(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val buffer = ByteArray(MTU)

        while (running.get()) {
            val read = try {
                input.read(buffer)
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "tun read failed", e)
                break
            }

            if (read <= 0) continue

            val datagram = IpPacket.parseUdp(buffer, read) ?: continue
            if (datagram.dstPort != Upstreams.DNS_PORT) continue

            // Copy out of the shared buffer before handing to the pool.
            val query = datagram.payload
            if (query.isEmpty()) continue

            try {
                queryPool.execute { handleQuery(datagram, query) }
            } catch (_: Exception) {
                // Pool saturated: drop rather than stall the read loop. The client
                // will retry, and a dropped query never fails open.
            }
        }

        isActive = false
        if (running.get()) {
            // The loop died while we still wanted to be up: ask the watchdog to retry.
            EventStore.get(this).log(EventType.PROTECTION_DOWN, "packet loop ended unexpectedly")
            Watchdog.requestRestart(this)
        }
    }

    private fun handleQuery(datagram: UdpDatagram, query: ByteArray) {
        val question = DnsMessage.parseQuestion(query, query.size)

        if (question == null) {
            // Not something we can read (EDNS oddity, unusual opcode): forward as-is.
            resolver.forward(query)?.let { reply(datagram, it) }
            return
        }

        when (val decision = DnsFilter.decide(question)) {
            is DnsDecision.Allow -> {
                val upstream = resolver.forward(query)
                if (upstream != null) {
                    reply(datagram, upstream)
                } else {
                    // Fail closed: refuse rather than let the client try its own resolver.
                    reply(datagram, DnsMessage.buildRefusal(query, question, DnsMessage.RCODE_NXDOMAIN))
                }
            }

            is DnsDecision.Block -> {
                reply(datagram, DnsMessage.buildRefusal(query, question, DnsMessage.RCODE_NXDOMAIN))
                onBlocked(decision)
            }

            is DnsDecision.Rewrite -> {
                val wantIpv6 = question.type == DnsMessage.TYPE_AAAA
                val address = resolver.resolveAddress(decision.targetHost, wantIpv6)
                if (address != null) {
                    reply(datagram, DnsMessage.buildAddressAnswer(query, question, address, REWRITE_TTL))
                } else if (wantIpv6) {
                    // No AAAA for the safe endpoint: answer empty so the client
                    // uses IPv4, which we do rewrite.
                    reply(datagram, DnsMessage.buildRefusal(query, question, DnsMessage.RCODE_NOERROR))
                } else {
                    reply(datagram, DnsMessage.buildRefusal(query, question, DnsMessage.RCODE_NXDOMAIN))
                }
            }
        }
    }

    private var lastBlockedHost: String = ""
    private var lastBlockedAt: Long = 0

    private fun onBlocked(decision: DnsDecision.Block) {
        val now = System.currentTimeMillis()

        // A single page load fires dozens of lookups. Only record and surface the
        // first one per host per window, or the log and the UI become noise.
        val isRepeat = decision.host == lastBlockedHost && now - lastBlockedAt < BLOCK_DEBOUNCE_MS
        lastBlockedHost = decision.host
        lastBlockedAt = now
        if (isRepeat) return

        EventStore.get(this).log(EventType.DOMAIN_BLOCKED, "${decision.host} (${decision.reason})")

        // Ask the on-screen guard to show the urge screen, if it is running and
        // the user is actually looking at a browser right now.
        BlockSignal.publishDomainBlock(this, decision.host)
    }

    private fun reply(request: UdpDatagram, payload: ByteArray) {
        val packet = IpPacket.buildUdpReply(request, payload)
        synchronized(writeLock) {
            runCatching { tunOut?.write(packet) }
        }
    }

    /** Called when the user revokes the VPN or another VPN app replaces us. */
    override fun onRevoke() {
        EventStore.get(this).log(EventType.TAMPER_ATTEMPT, "VPN permission revoked or taken over")
        PartnerNotifier.alert(
            this,
            PartnerNotifier.Kind.VPN_REVOKED,
            getString(R.string.alert_vpn_revoked)
        )
        Notifications.alert(
            this,
            getString(R.string.alert_protection_off_title),
            getString(R.string.alert_protection_off_body)
        )
        shutdown()
        Watchdog.requestRestart(this)
        super.onRevoke()
    }

    override fun onDestroy() {
        val wasDeliberate = stoppedDeliberately
        shutdown()
        if (!wasDeliberate) {
            // Killed by the OS or by battery management: come straight back.
            Watchdog.requestRestart(this)
        }
        super.onDestroy()
    }

    private fun shutdown() {
        running.set(false)
        isActive = false
        runCatching { worker?.interrupt() }
        worker = null
        synchronized(writeLock) {
            runCatching { tunOut?.close() }
            tunOut = null
        }
        runCatching { tunnel?.close() }
        tunnel = null
        runCatching { queryPool.shutdownNow() }
        runCatching { queryPool.awaitTermination(500, TimeUnit.MILLISECONDS) }
    }

    companion object {
        private const val TAG = "ShieldVpn"

        const val ACTION_STOP = "app.guardian.shield.STOP_FILTER"

        private const val MTU = 1500
        private const val QUERY_THREADS = 8
        private const val REWRITE_TTL = 300
        private const val BLOCK_DEBOUNCE_MS = 1500L

        // Addresses from ranges reserved for private use, so they cannot collide
        // with anything the device actually needs to reach.
        private const val VIRTUAL_CLIENT_V4 = "10.111.222.1"
        private const val VIRTUAL_DNS_V4 = "10.111.222.2"
        private const val VIRTUAL_CLIENT_V6 = "fd00:0:0:5a1e::1"
        private const val VIRTUAL_DNS_V6 = "fd00:0:0:5a1e::2"

        /** True while the packet loop is live. Read by the UI and the watchdog. */
        @Volatile
        var isActive: Boolean = false
            private set
    }
}
