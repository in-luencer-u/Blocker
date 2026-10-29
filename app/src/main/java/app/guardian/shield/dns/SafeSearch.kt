package app.guardian.shield.dns

/**
 * Forced SafeSearch.
 *
 * Search engines expose a special hostname whose results are permanently
 * filtered. If we answer a lookup for `www.google.com` with the address of
 * `forcesafesearch.google.com`, every app on the device — browser, incognito
 * tab, in-app WebView, widget — gets filtered results, because the engine keys
 * the restriction off the address the request arrived on.
 *
 * This closes the biggest hole a pure domain blocklist leaves: image search.
 */
object SafeSearch {

    /** Also block type-65 lookups for these hosts, or ECH can route around the rewrite. */
    private const val GOOGLE_SAFE = "forcesafesearch.google.com"
    private const val YOUTUBE_MODERATE = "restrictmoderate.youtube.com"
    private const val YOUTUBE_STRICT = "restrict.youtube.com"
    private const val BING_SAFE = "strict.bing.com"
    private const val DUCKDUCKGO_SAFE = "safe.duckduckgo.com"
    private const val YANDEX_SAFE = "familysearch.yandex.ru"
    private const val PIXIV_SAFE = "" // no official endpoint; handled by blocklist instead

    private val youtubeHosts = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
        "youtubei.googleapis.com", "youtube.googleapis.com", "www.youtube-nocookie.com",
    )

    private val bingHosts = setOf("bing.com", "www.bing.com", "cn.bing.com")

    private val duckHosts = setOf(
        "duckduckgo.com", "www.duckduckgo.com", "start.duckduckgo.com", "html.duckduckgo.com",
    )

    private val yandexHosts = setOf(
        "yandex.com", "www.yandex.com", "yandex.ru", "www.yandex.ru",
        "yandex.com.tr", "www.yandex.com.tr",
    )

    /**
     * The hostname whose address should be returned for [host], or null if this
     * host is not a search engine we can pin.
     *
     * @param strict use YouTube's strict channel rather than moderate.
     */
    fun safeTargetFor(host: String, strict: Boolean): String? {
        val name = host.trimEnd('.').lowercase()

        if (youtubeHosts.contains(name)) {
            return if (strict) YOUTUBE_STRICT else YOUTUBE_MODERATE
        }
        if (bingHosts.contains(name)) return BING_SAFE
        if (duckHosts.contains(name)) return DUCKDUCKGO_SAFE
        if (yandexHosts.contains(name)) return YANDEX_SAFE

        // Google runs ~190 country domains (google.co.in, google.de, ...). Match the
        // shape rather than enumerating them, but never match google-owned service
        // domains such as googleapis.com or googleusercontent.com.
        if (isGoogleSearchHost(name)) return GOOGLE_SAFE

        return null
    }

    private fun isGoogleSearchHost(name: String): Boolean {
        val core = name.removePrefix("www.").removePrefix("images.").removePrefix("ipv4.")
        if (!core.startsWith("google.")) return false
        val tld = core.removePrefix("google.")
        if (tld.isEmpty()) return false
        // Accept "com", "de", "co.in", "com.br"; reject anything with extra labels.
        val labels = tld.split('.')
        if (labels.size > 2) return false
        return labels.all { it.isNotEmpty() && it.all { c -> c.isLetter() } }
    }

    /** True when this host is one we rewrite, so SVCB/HTTPS records must be refused. */
    fun isRewritten(host: String, strict: Boolean): Boolean = safeTargetFor(host, strict) != null
}
