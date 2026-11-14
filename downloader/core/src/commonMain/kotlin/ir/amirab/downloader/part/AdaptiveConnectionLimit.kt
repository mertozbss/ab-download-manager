package ir.amirab.downloader.part

import kotlin.math.min

/**
 * Tracks how many concurrent connections a host currently accepts for one download.
 *
 * Many hosts cap concurrent connections per client and refuse the surplus (503, 429, reset...).
 * When a part gets refused while other parts are receiving data, [onConnectionRefused] lowers the
 * limit to the connections that are still open. The download then stops opening connections the
 * host refuses, and the healthy connections take over the refused ranges.
 *
 * The limit is not permanent: [baseProbeIntervalMs] after the last refusal [tryProbeUp] allows one
 * more connection. A refused probe connection is noticed within a second (it yields after two
 * quick failures), so if nothing is refused for [confirmProbeIntervalMs] the probe worked and the
 * next one follows right away: the limit climbs back by one every [confirmProbeIntervalMs] while
 * the host accepts. If a probe gets refused, the next one waits twice as long (up to
 * [maxProbeIntervalMs]), so a host that keeps refusing gets about two refused requests a minute.
 */
class AdaptiveConnectionLimit(
    private val baseProbeIntervalMs: Long = DEFAULT_PROBE_INTERVAL_MS,
    private val confirmProbeIntervalMs: Long = CONFIRM_PROBE_INTERVAL_MS,
    private val maxProbeIntervalMs: Long = MAX_PROBE_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** null = no limit learned, use what the user asked for */
    @Volatile
    var limit: Int? = null
        private set

    private var lastChangeAt = 0L
    private var probePending = false
    private var failedProbes = 0

    fun effective(requested: Int): Int {
        val l = limit ?: return requested
        return l.coerceIn(1, requested.coerceAtLeast(1))
    }

    /**
     * A connection was refused while others work.
     * @param stillOpen connections that are still open (not counting the refused one)
     */
    @Synchronized
    fun onConnectionRefused(stillOpen: Int, requested: Int) {
        val current = limit ?: requested
        limit = min(current, stillOpen.coerceAtLeast(1))
        if (probePending) {
            // the extra connection we allowed was refused too: back off before probing again
            probePending = false
            failedProbes++
        }
        lastChangeAt = clock()
    }

    /** how long to stay quiet (no refusal) before the next probe */
    @Synchronized
    fun probeIntervalMs(): Long {
        if (probePending) return confirmProbeIntervalMs
        val shift = min(failedProbes, 16)
        return min(baseProbeIntervalMs shl shift, maxProbeIntervalMs)
    }

    /**
     * Raise the limit by one if it was quiet long enough.
     * @return true if the limit changed (the caller should try to open a new connection)
     */
    @Synchronized
    fun tryProbeUp(requested: Int): Boolean {
        val current = limit ?: return false
        val now = clock()
        if (now - lastChangeAt < probeIntervalMs()) return false
        if (probePending) {
            // the previous probe was not refused: it worked
            failedProbes = 0
        }
        val next = current + 1
        limit = if (next >= requested) null else next
        probePending = limit != null
        if (limit == null) failedProbes = 0
        lastChangeAt = now
        return true
    }

    @Synchronized
    fun reset() {
        limit = null
        probePending = false
        failedProbes = 0
        lastChangeAt = 0L
    }

    companion object {
        const val DEFAULT_PROBE_INTERVAL_MS = 5_000L
        const val CONFIRM_PROBE_INTERVAL_MS = 2_000L
        const val MAX_PROBE_INTERVAL_MS = 60_000L
    }
}
