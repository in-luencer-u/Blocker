package app.guardian.shield.service

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import app.guardian.shield.core.Prefs
import app.guardian.shield.data.EventStore
import app.guardian.shield.data.EventType
import app.guardian.shield.dns.Blocklist
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Refreshes the domain list from public, free, community-maintained sources.
 *
 * This is the only outbound network request the app makes, and it is a download:
 * a plain GET of a public text file, with no identifiers, no query string, and
 * nothing about the user attached. Nothing is ever uploaded.
 */
class BlocklistUpdateWorker(
    private val context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        if (!Prefs.autoUpdateBlocklist) return Result.success()

        val merged = HashSet<String>(INITIAL_CAPACITY)
        var sourcesFetched = 0

        for (source in SOURCES) {
            val added = runCatching { fetchInto(source, merged) }
                .onFailure { Log.w(TAG, "source failed: $source", it) }
                .getOrDefault(0)
            if (added > 0) sourcesFetched++
            if (merged.size >= MAX_DOMAINS) break
        }

        // Never replace a working list with a truncated or empty download.
        if (sourcesFetched == 0 || merged.size < MIN_PLAUSIBLE_DOMAINS) {
            Log.w(TAG, "refusing to apply update: only ${merged.size} domains")
            return Result.retry()
        }

        Blocklist.replaceDownloaded(context, merged)
        Prefs.blocklistUpdatedAt = System.currentTimeMillis()

        EventStore.get(context).log(
            EventType.BLOCKLIST_UPDATED,
            "${merged.size} domains from $sourcesFetched source(s)"
        )
        return Result.success()
    }

    private fun fetchInto(source: String, into: MutableSet<String>): Int {
        val connection = (URL(source).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("User-Agent", "Shield-Blocker/1.0 (+local content filter)")
        }

        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "$source returned HTTP ${connection.responseCode}")
                return 0
            }

            val raw = connection.inputStream
            val stream = if (connection.contentEncoding?.contains("gzip", true) == true) {
                GZIPInputStream(raw)
            } else {
                raw
            }

            var added = 0
            stream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (into.size >= MAX_DOMAINS) break
                    val domain = Blocklist.normalise(line) ?: continue
                    if (into.add(domain)) added++
                }
            }
            return added
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private companion object {
        const val TAG = "BlocklistUpdate"

        /**
         * Public adult-content blocklists. All are free, openly licensed, and
         * widely used by Pi-hole and AdGuard Home users.
         *
         *  - StevenBlack "porn-only" hosts: github.com/StevenBlack/hosts
         *  - The Blocklist Project "porn" list: github.com/blocklistproject/Lists
         *  - OISD NSFW: oisd.nl
         */
        val SOURCES = listOf(
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts",
            "https://raw.githubusercontent.com/blocklistproject/Lists/master/porn.txt",
            "https://nsfw.oisd.nl/domainswild",
        )

        const val INITIAL_CAPACITY = 200_000
        /** Caps memory: a HashSet this size costs roughly 25-30 MB. */
        const val MAX_DOMAINS = 400_000
        const val MIN_PLAUSIBLE_DOMAINS = 5_000
    }
}
