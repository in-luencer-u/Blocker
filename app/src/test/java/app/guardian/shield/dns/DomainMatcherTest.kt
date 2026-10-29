package app.guardian.shield.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainMatcherTest {

    private val list = setOf("example.com", "badsite.net")

    @Test
    fun `exact domain matches`() {
        assertTrue(DomainMatcher.matchesAnyParent("example.com", list))
    }

    @Test
    fun `subdomains of a listed domain match`() {
        assertTrue(DomainMatcher.matchesAnyParent("www.example.com", list))
        assertTrue(DomainMatcher.matchesAnyParent("cdn.media.example.com", list))
        assertTrue(DomainMatcher.matchesAnyParent("a.b.c.d.badsite.net", list))
    }

    @Test
    fun `unrelated domains do not match`() {
        assertFalse(DomainMatcher.matchesAnyParent("example.org", list))
        assertFalse(DomainMatcher.matchesAnyParent("notexample.com", list))
        assertFalse(DomainMatcher.matchesAnyParent("example.com.evil.co", list))
        assertFalse(DomainMatcher.matchesAnyParent("", list))
    }

    @Test
    fun `parent walk does not match a suffix that is not a label boundary`() {
        // "ample.com" must not be matched by a list entry of "example.com".
        assertFalse(DomainMatcher.matchesAnyParent("ample.com", list))
    }

    @Test
    fun `substring patterns catch unseen adult domains`() {
        assertTrue(DomainMatcher.matchesSubstringPattern("some-new-pornsite.xyz"))
        assertTrue(DomainMatcher.matchesSubstringPattern("fresh-hentai-mirror.io"))
        assertTrue(DomainMatcher.matchesSubstringPattern("xhamster17.desi"))
    }

    @Test
    fun `substring patterns leave ordinary domains alone`() {
        for (host in listOf(
            "google.com", "github.com", "wikipedia.org", "nhs.uk",
            "stackoverflow.com", "amazon.in", "paytm.com", "vivo.com",
        )) {
            assertFalse(host, DomainMatcher.matchesSubstringPattern(host))
        }
    }

    @Test
    fun `token matching does not fire on innocent words containing risk substrings`() {
        // This is the case that makes naive keyword filters unusable.
        for (host in listOf(
            "sussex.ac.uk",          // contains "sex"
            "analytics.google.com",  // contains "anal"
            "expo.dev",
            "essexlive.news",
            "cumbria.gov.uk",        // contains "cum"
            "scunthorpe.gov.uk",
            "dickens.org",           // "dick" is not a whole token here
            "cocktail.com",
        )) {
            assertFalse(host, DomainMatcher.matchesTokenPattern(host))
        }
    }

    @Test
    fun `token matching fires on whole-label risk words`() {
        assertTrue(DomainMatcher.matchesTokenPattern("free-sex.tv"))
        assertTrue(DomainMatcher.matchesTokenPattern("xxx.movie"))
        assertTrue(DomainMatcher.matchesTokenPattern("adult.site.co"))
        assertTrue(DomainMatcher.matchesTokenPattern("my_nude_pics.net"))
    }

    @Test
    fun `normalise reads hosts-file lines`() {
        assertEquals("example.com", DomainMatcher.normalise("0.0.0.0 example.com"))
        assertEquals("example.com", DomainMatcher.normalise("127.0.0.1   example.com"))
        assertEquals("example.com", DomainMatcher.normalise("0.0.0.0 example.com # a comment"))
        assertEquals("example.com", DomainMatcher.normalise("::1 example.com"))
        assertEquals("example.com", DomainMatcher.normalise("192.168.10.4\texample.com"))
    }

    @Test
    fun `normalise reads plain and adblock style entries`() {
        assertEquals("example.com", DomainMatcher.normalise("example.com"))
        assertEquals("example.com", DomainMatcher.normalise("||example.com^"))
        assertEquals("example.com", DomainMatcher.normalise("*.example.com"))
        assertEquals("example.com", DomainMatcher.normalise("  EXAMPLE.com.  "))
        assertEquals("example.com", DomainMatcher.normalise("https://example.com/some/path?q=1"))
        assertEquals("example.com", DomainMatcher.normalise("example.com:8080"))
    }

    @Test
    fun `normalise rejects junk`() {
        assertNull(DomainMatcher.normalise(""))
        assertNull(DomainMatcher.normalise("   "))
        assertNull(DomainMatcher.normalise("# comment"))
        assertNull(DomainMatcher.normalise("! adblock comment"))
        assertNull(DomainMatcher.normalise("localhost"))
        assertNull(DomainMatcher.normalise("0.0.0.0 localhost"))
        assertNull(DomainMatcher.normalise("broadcasthost"))
        assertNull(DomainMatcher.normalise("nodot"))
        assertNull(DomainMatcher.normalise("192.168.0.1"))
        assertNull(DomainMatcher.normalise("bad..domain.com"))
        assertNull(DomainMatcher.normalise("has space.com"))
        assertNull(DomainMatcher.normalise("<html>"))
    }

    @Test
    fun `canonical strips case and trailing dot`() {
        assertEquals("example.com", DomainMatcher.canonical("EXAMPLE.CoM."))
    }
}
