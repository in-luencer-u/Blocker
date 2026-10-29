package app.guardian.shield.dns

import app.guardian.shield.core.Prefs

sealed class DnsDecision {
    /** Forward upstream unchanged. */
    object Allow : DnsDecision()

    /** Refuse the lookup. [host] is logged so the user can see what was stopped. */
    data class Block(val host: String, val reason: String) : DnsDecision()

    /** Answer with the address of [targetHost] instead. */
    data class Rewrite(val host: String, val targetHost: String) : DnsDecision()
}

/**
 * Known DNS-over-HTTPS and DNS-over-TLS endpoints.
 *
 * An app with its own encrypted resolver would bypass this filter entirely, so
 * their hostnames are refused. Combined with the VPN capturing the well-known
 * resolver IPs, a browser's "Secure DNS" setting fails closed and falls back to
 * the system resolver, which is us.
 */
private val encryptedResolverHosts = setOf(
    "dns.google", "dns.google.com", "dns64.dns.google",
    "cloudflare-dns.com", "one.one.one.one", "mozilla.cloudflare-dns.com",
    "dns.quad9.net", "dns10.quad9.net", "dns11.quad9.net",
    "doh.opendns.com", "doh.familyshield.opendns.com", "dns.opendns.com",
    "dns.nextdns.io", "doh.dns.sb", "dns.sb", "doh.sb",
    "dns.adguard.com", "dns-unfiltered.adguard.com", "unfiltered.adguard-dns.com",
    "doh.cleanbrowsing.org", "doh.libredns.gr", "dns.digitale-gesellschaft.ch",
    "dns.mullvad.net", "doh.mullvad.net", "dns.controld.com",
    "freedns.controld.com", "doh.dnswarden.com", "dns.decloudus.com",
    "doh-de.blahdns.com", "doh-jp.blahdns.com", "dnsforge.de",
    "resolver.dnscrypt.info", "dns.njal.la", "doh.ffmuc.net",
    "basic.rethinkdns.com", "sky.rethinkdns.com", "max.rethinkdns.com",
)

/**
 * Hosts used to reach VPN and proxy tooling. These are the fastest route around
 * a DNS filter, so strict mode refuses them too.
 */
private val circumventionHosts = setOf(
    "torproject.org", "www.torproject.org", "bridges.torproject.org",
    "psiphon.ca", "psiphon3.com", "getlantern.org", "lantern.io",
    "ultrasurf.us", "hotspotshield.com", "tunnelbear.com", "windscribe.com",
    "protonvpn.com", "nordvpn.com", "expressvpn.com", "surfshark.com",
    "hola.org", "hide.me", "zenmate.com", "betternet.co",
    "opera.com/vpn", "proxysite.com", "croxyproxy.com", "croxyproxy.rocks",
    "hidemyass.com", "kproxy.com", "4everproxy.com", "whateverorigin.org",
    "blockaway.net", "proxyium.com", "unblockit.dev", "piratebay-proxylist.se",
)

/**
 * Turns a DNS question into an action. Pure and allocation-light: this runs on
 * the packet thread for every lookup the device makes.
 */
object DnsFilter {

    fun decide(question: DnsQuestion): DnsDecision {
        val host = question.name

        if (host.isEmpty()) return DnsDecision.Allow
        if (!Prefs.filterEnabled) return DnsDecision.Allow

        // Refuse encrypted-DNS bootstrap so nothing can resolve around us.
        if (matchesSuffix(host, encryptedResolverHosts)) {
            return DnsDecision.Block(host, "encrypted DNS bypass")
        }

        if (Prefs.strictMode && matchesSuffix(host, circumventionHosts)) {
            return DnsDecision.Block(host, "VPN or proxy bypass")
        }

        if (Blocklist.isBlocked(host)) {
            return DnsDecision.Block(host, "adult content")
        }

        if (Prefs.safeSearchEnabled) {
            val target = SafeSearch.safeTargetFor(host, Prefs.strictMode)
            if (target != null) {
                // Refuse HTTPS/SVCB for pinned hosts: an ECH-enabled handshake
                // could otherwise reach the unfiltered front end.
                return if (question.type == DnsMessage.TYPE_HTTPS) {
                    DnsDecision.Block(host, "SafeSearch pinning")
                } else {
                    DnsDecision.Rewrite(host, target)
                }
            }
        }

        return DnsDecision.Allow
    }

    private fun matchesSuffix(host: String, set: Set<String>): Boolean {
        if (set.contains(host)) return true
        var idx = host.indexOf('.')
        while (idx != -1 && idx + 1 < host.length) {
            if (set.contains(host.substring(idx + 1))) return true
            idx = host.indexOf('.', idx + 1)
        }
        return false
    }
}
