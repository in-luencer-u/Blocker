package app.guardian.shield.dns

/**
 * The pure domain-matching rules, with no Android or file-system dependency.
 *
 * Split out from [Blocklist] so the matching behaviour — which decides what the
 * user can and cannot reach — can be tested directly and exhaustively on the
 * JVM. [Blocklist] adds loading, persistence and settings on top of this.
 */
object DomainMatcher {

    /**
     * Hostname fragments unambiguous enough to block on sight, which catches
     * domains registered after the last list refresh.
     */
    val substringPatterns = listOf(
        "porn", "xvideo", "xhamster", "xnxx", "redtube", "youporn", "brazzers",
        "onlyfans", "chaturbate", "camsoda", "bongacams", "stripchat", "livejasmin",
        "hentai", "doujin", "nhentai", "rule34", "e-hentai", "fakku",
        "nudes", "nudity", "camgirl", "camwhore", "escort-", "milf", "bdsm",
        "erotic", "eroti", "fetish", "hardcore-", "adultfriend", "fuq", "tnaflix",
        "spankbang", "eporner", "txxx", "hqporner", "javhd", "jav-", "javfinder",
        "pornhub", "sexvid", "sexcam", "sexchat", "sexdate", "sexshop",
    )

    /**
     * Words that only signal adult content as a whole label or token. Substring
     * matching on these would break innocent domains: "sussex.ac.uk" contains
     * "sex", "expo.dev" contains "xp", "analytics.google.com" contains "anal".
     */
    val tokenPatterns = setOf(
        "sex", "xxx", "nsfw", "adult", "nude", "naked", "boobs", "tits",
        "anal", "cum", "dick", "cock", "pussy", "slut", "whore", "orgy",
        "incest", "bukkake", "creampie", "gangbang", "handjob", "blowjob",
        "cam4", "camsite", "18plus", "xx", "pron",
    )

    /** Normalises a host for comparison: lowercase, no trailing dot. */
    fun canonical(host: String): String = host.trim().trimEnd('.').lowercase()

    /**
     * True if [name] or any of its parent domains is in [set].
     *
     * Walks `a.b.example.com` -> `b.example.com` -> `example.com` -> `com`, so a
     * list entry of `example.com` also blocks every subdomain of it.
     */
    fun matchesAnyParent(name: String, set: Set<String>): Boolean {
        if (set.isEmpty() || name.isEmpty()) return false
        if (set.contains(name)) return true

        var idx = name.indexOf('.')
        while (idx != -1 && idx + 1 < name.length) {
            if (set.contains(name.substring(idx + 1))) return true
            idx = name.indexOf('.', idx + 1)
        }
        return false
    }

    /** True if [name] matches the built-in substring patterns. */
    fun matchesSubstringPattern(name: String): Boolean =
        substringPatterns.any { name.contains(it) }

    /** True if any dot/hyphen/underscore-separated token of [name] is a risk word. */
    fun matchesTokenPattern(name: String): Boolean =
        name.split('.', '-', '_').any { it.length >= 2 && tokenPatterns.contains(it) }

    /**
     * Parses one line of a block list into a bare domain, or null to skip it.
     *
     * Accepts hosts-file lines (`0.0.0.0 example.com`), plain domain lists,
     * AdBlock-style `||example.com^` rules, wildcard prefixes, and full URLs.
     */
    fun normalise(raw: String): String? {
        var line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return null

        line = line.substringBefore('#').substringBefore('!').trim()
        if (line.isEmpty()) return null

        if (line.startsWith("||")) line = line.removePrefix("||").trimEnd('^')
        if (line.startsWith("*.")) line = line.removePrefix("*.")

        // A hosts-file line is "<address> <hostname>". Requiring the first token
        // to actually be an address keeps malformed lines out of the list rather
        // than silently taking the last word of a sentence.
        val parts = line.split(Regex("\\s+"))
        line = when {
            parts.size == 1 -> parts[0]
            looksLikeAddress(parts[0]) -> parts[1]
            else -> return null
        }

        line = line.removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore('?').substringBefore('#')
            .substringBefore(':')
            .trim().trimEnd('.').lowercase()

        if (line.isEmpty() || line.length > 253) return null
        if (line in nonDomains) return null
        if (!line.contains('.')) return null
        if (line.startsWith('.') || line.contains("..")) return null
        if (!line.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) return null
        // Reject bare IP addresses: they are sinkhole targets, not block entries.
        if (line.all { it.isDigit() || it == '.' }) return null

        return line
    }

    /** True for an IPv4 or IPv6 literal, the sinkhole target in a hosts file. */
    private fun looksLikeAddress(token: String): Boolean {
        if (token.isEmpty()) return false
        if (token.contains(':')) {
            return token.all { it.isDigit() || it == ':' || it.lowercaseChar() in 'a'..'f' }
        }
        return token.all { it.isDigit() || it == '.' } && token.contains('.')
    }

    private val nonDomains = setOf(
        "localhost", "localhost.localdomain", "broadcasthost", "local", "ip6-localhost",
        "ip6-loopback", "ip6-localnet", "ip6-mcastprefix", "ip6-allnodes", "ip6-allrouters",
    )
}
