package app.guardian.shield.vpn

/**
 * Upstream resolvers and the resolver addresses we capture.
 *
 * Two independent layers:
 *
 *  1. [FAMILY_RESOLVERS] are public resolvers that already refuse adult
 *     categories server-side. Anything our local list has never heard of still
 *     has a good chance of being refused here, and the lists are maintained by
 *     someone else for free.
 *
 *  2. [CAPTURED_RESOLVERS] are the well-known public resolvers an app or browser
 *     might talk to directly to skip the system resolver. We route them into the
 *     tun and answer them ourselves, so a hardcoded `8.8.8.8` is filtered too.
 *     Their DoH/DoT ports are not served, so encrypted DNS to them fails closed.
 */
object Upstreams {

    /** Tried in order; the first to answer wins. */
    val FAMILY_RESOLVERS = listOf(
        "185.228.168.168", // CleanBrowsing Family Filter
        "1.1.1.3",         // Cloudflare for Families (malware + adult)
        "94.140.14.15",    // AdGuard Family
        "208.67.222.123",  // OpenDNS FamilyShield
        "185.228.169.168", // CleanBrowsing secondary
        "1.0.0.3",         // Cloudflare for Families secondary
    )

    /**
     * Deliberately excludes every address in [FAMILY_RESOLVERS]: capturing our
     * own upstream would be pointless, and the protected socket already bypasses
     * the tun.
     */
    val CAPTURED_RESOLVERS = listOf(
        "8.8.8.8", "8.8.4.4",                       // Google
        "1.1.1.1", "1.0.0.1", "1.1.1.2", "1.0.0.2", // Cloudflare (incl. malware-only)
        "9.9.9.9", "149.112.112.112", "9.9.9.10",   // Quad9
        "208.67.222.222", "208.67.220.220",         // OpenDNS unfiltered
        "94.140.14.14", "94.140.15.15",             // AdGuard default
        "94.140.14.140", "94.140.14.141",           // AdGuard unfiltered
        "77.88.8.8", "77.88.8.1",                   // Yandex
        "76.76.2.0", "76.76.10.0",                  // ControlD
        "45.90.28.0", "45.90.30.0",                 // NextDNS
        "84.200.69.80", "84.200.70.40",             // DNS.WATCH
        "64.6.64.6", "64.6.65.6",                   // Verisign
        "8.26.56.26", "8.20.247.20",                // Comodo
        "195.46.39.39", "195.46.39.40",             // SafeDNS
        "74.82.42.42",                              // Hurricane Electric
        "216.146.35.35", "216.146.36.36",           // Dyn
        "156.154.70.1", "156.154.71.1",             // Neustar
        "185.228.168.10", "185.228.169.11",         // CleanBrowsing adult-only/security
        "223.5.5.5", "223.6.6.6",                   // AliDNS
        "180.76.76.76",                             // Baidu
        "119.29.29.29",                             // DNSPod
        "101.101.101.101",                          // Quad101
        "80.80.80.80", "80.80.81.81",               // Freenom
        "38.132.106.139", "194.187.251.67",         // CyberGhost
    )

    /** IPv6 resolvers worth capturing for the same reason. */
    val CAPTURED_RESOLVERS_V6 = listOf(
        "2001:4860:4860::8888", "2001:4860:4860::8844", // Google
        "2606:4700:4700::1111", "2606:4700:4700::1001", // Cloudflare
        "2620:fe::fe", "2620:fe::9",                    // Quad9
        "2a0d:2a00:1::", "2a0d:2a00:2::",               // AdGuard
        "2620:119:35::35", "2620:119:53::53",           // OpenDNS
    )

    const val DNS_PORT = 53
    const val QUERY_TIMEOUT_MS = 4000
    const val MAX_UDP_PAYLOAD = 4096
}
