package ir.amirab.downloader.part

import kotlin.math.min
import kotlin.random.Random

/**
 * Delay before a part downloader retries after a failed attempt.
 *
 * Exponential (250 ms, 500 ms, 1 s, 2 s, then capped at 3 s) with up to 20% jitter, so a dropped
 * connection is re-opened almost immediately, but a part the server keeps refusing does not
 * hammer it once per second, and parts that failed together do not retry in lockstep.
 */
object PartRetryBackoff {
    const val BASE_DELAY_MS = 250L
    const val MAX_DELAY_MS = 3_000L

    /**
     * @param failedAttempts number of consecutive failed attempts so far (1 for the first retry)
     */
    fun delayFor(failedAttempts: Int, random: Random = Random.Default): Long {
        if (failedAttempts <= 0) return 0
        val exponent = min(failedAttempts - 1, 20)
        val nominal = min(BASE_DELAY_MS shl exponent, MAX_DELAY_MS)
        // jitter: [0.8 * nominal, nominal]
        return nominal - random.nextLong(nominal / 5 + 1)
    }
}
