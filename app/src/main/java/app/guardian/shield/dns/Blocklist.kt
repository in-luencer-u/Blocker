package app.guardian.shield.dns

import android.content.Context
import app.guardian.shield.core.Prefs
import java.io.File

/**
 * The domain decision table.
 *
 * Held as immutable sets behind volatile references: the VPN thread reads this on
 * every DNS query and must never block, while updates happen rarely on a worker
 * thread and swap whole sets in atomically.
 *
 * The matching rules themselves live in [DomainMatcher], which has no Android
 * dependency and is unit tested directly.
 */
object Blocklist {

    private const val SEED_ASSET = "seed_blocklist.txt"
    private const val FILE_DOWNLOADED = "blocklist_downloaded.txt"
    private const val FILE_CUSTOM = "blocklist_custom.txt"
    private const val FILE_ALLOW = "allowlist_custom.txt"

    @Volatile private var blocked: Set<String> = emptySet()
    @Volatile private var custom: Set<String> = emptySet()
    @Volatile private var allowed: Set<String> = emptySet()
    @Volatile private var loaded = false

    val domainCount: Int get() = blocked.size + custom.size

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            reload(context)
        }
    }

    fun reload(context: Context) {
        synchronized(this) {
            val downloaded = readDomainFile(File(context.filesDir, FILE_DOWNLOADED))
            // Fall back to the bundled seed list until a refresh has succeeded, so
            // the app is never running with nothing loaded.
            blocked = if (downloaded.isEmpty()) readSeedAsset(context) else downloaded
            custom = readDomainFile(File(context.filesDir, FILE_CUSTOM))
            allowed = readDomainFile(File(context.filesDir, FILE_ALLOW))
            loaded = true
            Prefs.blocklistDomainCount = domainCount
        }
    }

    /**
     * True if [host] should be refused.
     *
     * Order matters: the user's allowlist wins over everything, then their own
     * reported domains, then the downloaded list, then the built-in patterns.
     */
    fun isBlocked(host: String, keywordGuard: Boolean = Prefs.keywordGuardEnabled): Boolean {
        if (host.isEmpty()) return false
        val name = DomainMatcher.canonical(host)

        if (DomainMatcher.matchesAnyParent(name, allowed)) return false
        if (DomainMatcher.matchesAnyParent(name, custom)) return true
        if (DomainMatcher.matchesAnyParent(name, blocked)) return true
        if (DomainMatcher.matchesSubstringPattern(name)) return true
        if (keywordGuard && DomainMatcher.matchesTokenPattern(name)) return true

        return false
    }

    fun normalise(raw: String): String? = DomainMatcher.normalise(raw)

    // ---------------- user edits ----------------

    /** Adds a domain the user reported. Tightening, so it applies immediately. */
    fun addCustomBlocked(context: Context, domain: String): Boolean {
        val clean = DomainMatcher.normalise(domain) ?: return false
        synchronized(this) {
            if (custom.contains(clean)) return false

            File(context.filesDir, FILE_CUSTOM).appendText("$clean\n")
            custom = custom + clean

            // A reported domain must override any earlier allowlist entry, or
            // re-blocking something you previously allowed would silently fail.
            if (allowed.contains(clean)) {
                allowed = allowed - clean
                writeDomainFile(File(context.filesDir, FILE_ALLOW), allowed)
            }
            Prefs.blocklistDomainCount = domainCount
        }
        return true
    }

    /** Allowlisting is loosening, so callers must already hold an unlock window. */
    fun addAllowed(context: Context, domain: String): Boolean {
        val clean = DomainMatcher.normalise(domain) ?: return false
        synchronized(this) {
            if (allowed.contains(clean)) return false
            allowed = allowed + clean
            writeDomainFile(File(context.filesDir, FILE_ALLOW), allowed)
        }
        return true
    }

    fun customDomains(): List<String> = custom.sorted()
    fun allowedDomains(): List<String> = allowed.sorted()

    /** Replaces the downloaded set after a successful refresh. */
    fun replaceDownloaded(context: Context, domains: Collection<String>) {
        synchronized(this) {
            writeDomainFile(File(context.filesDir, FILE_DOWNLOADED), domains)
            blocked = domains.toHashSet()
            loaded = true
            Prefs.blocklistDomainCount = domainCount
        }
    }

    // ---------------- loading ----------------

    private fun readSeedAsset(context: Context): Set<String> = runCatching {
        context.assets.open(SEED_ASSET).bufferedReader().useLines { lines ->
            lines.mapNotNull { DomainMatcher.normalise(it) }.toHashSet()
        }
    }.getOrDefault(emptySet())

    private fun readDomainFile(file: File): Set<String> = runCatching {
        if (!file.exists()) return emptySet()
        file.bufferedReader().useLines { lines ->
            lines.mapNotNull { DomainMatcher.normalise(it) }.toHashSet()
        }
    }.getOrDefault(emptySet())

    private fun writeDomainFile(file: File, domains: Collection<String>) {
        runCatching {
            file.bufferedWriter().use { writer ->
                for (domain in domains) {
                    writer.write(domain)
                    writer.newLine()
                }
            }
        }
    }
}
