package app.guardian.shield.guard

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Pulls URLs and risk signals out of whatever is on screen.
 *
 * Traversal is strictly bounded. This runs on the accessibility callback thread
 * on every content change, so an unbounded walk of a long feed would make the
 * whole phone stutter — which is how blockers end up being uninstalled.
 */
object TextScanner {

    private const val MAX_NODES = 350
    private const val MAX_DEPTH = 22
    private const val MAX_TEXT_CHARS = 4000

    /** Address-bar view ids for the browsers that expose one. */
    private val urlBarIds = listOf(
        "com.android.chrome:id/url_bar",
        "com.chrome.beta:id/url_bar",
        "com.chrome.dev:id/url_bar",
        "com.chrome.canary:id/url_bar",
        "com.brave.browser:id/url_bar",
        "com.microsoft.emmx:id/url_bar",
        "com.vivaldi.browser:id/url_bar",
        "com.kiwibrowser.browser:id/url_bar",
        "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
        "org.mozilla.focus:id/mozac_browser_toolbar_url_view",
        "org.mozilla.fenix:id/mozac_browser_toolbar_url_view",
        "com.opera.browser:id/url_field",
        "com.opera.mini.native:id/url_field",
        "com.opera.gx:id/url_field",
        "com.sec.android.app.sbrowser:id/location_bar_edit_text",
        "com.duckduckgo.mobile.android:id/omnibarTextInput",
        "com.vivo.browser:id/search_input",
        "com.vivo.browser:id/title_bar_url",
        "com.android.browser:id/url",
        "com.UCMobile.intl:id/address_bar_text",
        "com.transsion.phoenix:id/address_bar_text",
        "mark.via:id/url",
        "acr.browser.lightning:id/search",
    )

    /** Rough URL/host matcher. Deliberately loose — the blocklist does the deciding. */
    private val urlPattern = Regex(
        "\\b((?:https?://)?(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24})\\b",
        RegexOption.IGNORE_CASE
    )

    /**
     * Phrases that indicate adult content on a page the domain filter allowed —
     * a social feed, a file host, an aggregator. Each is explicit enough that a
     * false positive is unlikely in ordinary use.
     */
    private val riskPhrases = listOf(
        "porn", "xxx video", "hentai", "nsfw", "sex video", "sex videos",
        "nude photo", "nude video", "nudes", "naked girls", "naked women",
        "onlyfans leak", "camgirl", "live sex", "sex cam", "webcam girls",
        "adult video", "adult movies", "escort service", "milf", "bdsm",
        "blowjob", "handjob", "creampie", "gangbang", "anal sex", "oral sex",
        "big tits", "big boobs", "pussy", "deepthroat", "cumshot",
        "fuck video", "xvideos", "pornhub", "xhamster", "xnxx", "redtube",
    )

    /** Host names found in address bars or visible text. */
    fun extractHosts(root: AccessibilityNodeInfo?): List<String> {
        if (root == null) return emptyList()
        val hosts = LinkedHashSet<String>()

        // Address bar first: it is the authoritative URL for the current page.
        for (id in urlBarIds) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(id) }
                .getOrNull() ?: continue
            for (node in nodes) {
                val text = node.text?.toString() ?: node.contentDescription?.toString()
                hostOf(text)?.let { hosts.add(it) }
                runCatching { node.recycle() }
            }
            if (hosts.isNotEmpty()) return hosts.toList()
        }

        // No recognised address bar: fall back to scanning visible text for URLs.
        val text = collectText(root)
        for (match in urlPattern.findAll(text).take(40)) {
            hostOf(match.groupValues[1])?.let { hosts.add(it) }
        }
        return hosts.toList()
    }

    private fun hostOf(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var value = raw.trim().lowercase()
        if (value.length > 300) return null

        value = value.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        value = value.substringBefore('/').substringBefore('?').substringBefore('#')
            .substringBefore(' ').substringBefore(':').trimEnd('.')

        if (!value.contains('.')) return null
        if (value.any { !(it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') }) return null
        if (value.length < 4 || value.length > 253) return null
        return value
    }

    /**
     * How strongly the visible text suggests adult content.
     *
     * Two or more distinct phrases are required before anything is blocked, so a
     * single word in a news article or a chat message does not trip the guard.
     */
    fun riskScore(root: AccessibilityNodeInfo?): Int {
        if (root == null) return 0
        val text = collectText(root).lowercase()
        if (text.isEmpty()) return 0

        var hits = 0
        for (phrase in riskPhrases) {
            if (text.contains(phrase)) {
                hits++
                if (hits >= 3) break
            }
        }
        return hits
    }

    /** Depth- and node-bounded text collection. */
    fun collectText(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        val sb = StringBuilder(512)
        var visited = 0

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null) return
            if (depth > MAX_DEPTH) return
            if (visited >= MAX_NODES) return
            if (sb.length >= MAX_TEXT_CHARS) return
            visited++

            node.text?.let { if (it.isNotEmpty()) sb.append(it).append(' ') }
            node.contentDescription?.let { if (it.isNotEmpty()) sb.append(it).append(' ') }

            val count = runCatching { node.childCount }.getOrDefault(0)
            for (i in 0 until count) {
                if (visited >= MAX_NODES || sb.length >= MAX_TEXT_CHARS) return
                val child = runCatching { node.getChild(i) }.getOrNull()
                walk(child, depth + 1)
                if (child != null && child != root) runCatching { child.recycle() }
            }
        }

        walk(root, 0)
        return sb.toString()
    }
}
