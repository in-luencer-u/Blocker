package app.guardian.shield.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SafeSearch pinning is what covers image and video search, which a domain
 * blocklist cannot. Over-matching here would break Gmail or Google Docs, so the
 * negative cases matter as much as the positive ones.
 */
class SafeSearchTest {

    @Test
    fun `pins google search across country domains`() {
        for (host in listOf(
            "google.com", "www.google.com", "images.google.com",
            "google.co.in", "www.google.co.in", "google.de", "google.com.br",
        )) {
            assertEquals(host, "forcesafesearch.google.com", SafeSearch.safeTargetFor(host, false))
        }
    }

    @Test
    fun `leaves other google services alone`() {
        // Rewriting any of these would break mail, documents and app updates.
        for (host in listOf(
            "mail.google.com", "docs.google.com", "drive.google.com",
            "googleapis.com", "www.googleapis.com", "fonts.googleapis.com",
            "googleusercontent.com", "play.google.com", "accounts.google.com",
            "google.com.evil.co", "notgoogle.com", "google",
        )) {
            assertNull(host, SafeSearch.safeTargetFor(host, false))
        }
    }

    @Test
    fun `pins youtube to restricted mode`() {
        assertEquals(
            "restrictmoderate.youtube.com",
            SafeSearch.safeTargetFor("www.youtube.com", false)
        )
        assertEquals(
            "restrict.youtube.com",
            SafeSearch.safeTargetFor("www.youtube.com", true)
        )
        assertEquals(
            "restrict.youtube.com",
            SafeSearch.safeTargetFor("m.youtube.com", true)
        )
        // The app talks to the API host rather than the website.
        assertEquals(
            "restrict.youtube.com",
            SafeSearch.safeTargetFor("youtubei.googleapis.com", true)
        )
    }

    @Test
    fun `pins the other major search engines`() {
        assertEquals("strict.bing.com", SafeSearch.safeTargetFor("www.bing.com", false))
        assertEquals("safe.duckduckgo.com", SafeSearch.safeTargetFor("duckduckgo.com", false))
        assertEquals("familysearch.yandex.ru", SafeSearch.safeTargetFor("yandex.com", false))
    }

    @Test
    fun `ignores unrelated hosts`() {
        for (host in listOf("example.com", "github.com", "wikipedia.org", "")) {
            assertNull(host, SafeSearch.safeTargetFor(host, false))
        }
    }

    @Test
    fun `case and trailing dots do not defeat pinning`() {
        assertEquals(
            "forcesafesearch.google.com",
            SafeSearch.safeTargetFor("WWW.GOOGLE.COM.", false)
        )
    }

    @Test
    fun `rewritten hosts are reported so HTTPS records can be refused`() {
        assertEquals(true, SafeSearch.isRewritten("www.google.com", false))
        assertEquals(false, SafeSearch.isRewritten("example.com", false))
    }
}
