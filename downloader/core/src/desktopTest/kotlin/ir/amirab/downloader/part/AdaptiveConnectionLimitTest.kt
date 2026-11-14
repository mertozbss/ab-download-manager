package ir.amirab.downloader.part

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdaptiveConnectionLimitTest {
    private var now = 1_000_000L
    private val limit = AdaptiveConnectionLimit(
        baseProbeIntervalMs = 10_000,
        confirmProbeIntervalMs = 3_000,
        maxProbeIntervalMs = 40_000,
        clock = { now },
    )

    @Test
    fun noLimitUntilARefusal() {
        assertNull(limit.limit)
        assertEquals(8, limit.effective(8))
        assertFalse(limit.tryProbeUp(8), "nothing to probe without a limit")
    }

    @Test
    fun refusalsLowerTheLimitToTheConnectionsStillOpen() {
        // 8 started, host accepts 5: the three refused parts yield one after another
        limit.onConnectionRefused(stillOpen = 7, requested = 8)
        limit.onConnectionRefused(stillOpen = 6, requested = 8)
        limit.onConnectionRefused(stillOpen = 5, requested = 8)
        assertEquals(5, limit.effective(8))
    }

    @Test
    fun aRefusalNeverRaisesTheLimit() {
        limit.onConnectionRefused(stillOpen = 3, requested = 8)
        limit.onConnectionRefused(stillOpen = 6, requested = 8)
        assertEquals(3, limit.effective(8))
    }

    @Test
    fun limitIsAtLeastOneAndAtMostRequested() {
        limit.onConnectionRefused(stillOpen = 0, requested = 8)
        assertEquals(1, limit.effective(8))
        limit.reset()
        limit.onConnectionRefused(stillOpen = 6, requested = 8)
        // user lowered the connection count below the learned limit
        assertEquals(4, limit.effective(4))
    }

    @Test
    fun probesUpAfterAQuietPeriod() {
        limit.onConnectionRefused(stillOpen = 5, requested = 8)
        now += 9_999
        assertFalse(limit.tryProbeUp(8))
        now += 1
        assertTrue(limit.tryProbeUp(8))
        assertEquals(6, limit.effective(8))
        // the probe was not refused -> it worked, the next one follows quickly
        assertEquals(3_000, limit.probeIntervalMs())
        now += 2_999
        assertFalse(limit.tryProbeUp(8))
        now += 1
        assertTrue(limit.tryProbeUp(8))
        assertEquals(7, limit.effective(8))
        now += 3_000
        assertTrue(limit.tryProbeUp(8))
        assertNull(limit.limit, "back at the requested count -> no limit")
        assertEquals(8, limit.effective(8))
    }

    @Test
    fun failedProbesBackOff() {
        limit.onConnectionRefused(stillOpen = 3, requested = 8)
        assertEquals(10_000, limit.probeIntervalMs())

        now += 10_000
        assertTrue(limit.tryProbeUp(8)) // allow 4
        limit.onConnectionRefused(stillOpen = 3, requested = 8) // the 4th was refused
        assertEquals(3, limit.effective(8))
        assertEquals(20_000, limit.probeIntervalMs())

        now += 19_999
        assertFalse(limit.tryProbeUp(8))
        now += 1
        assertTrue(limit.tryProbeUp(8))
        limit.onConnectionRefused(stillOpen = 3, requested = 8)
        assertEquals(40_000, limit.probeIntervalMs())

        now += 40_000
        assertTrue(limit.tryProbeUp(8))
        limit.onConnectionRefused(stillOpen = 3, requested = 8)
        assertEquals(40_000, limit.probeIntervalMs(), "capped")

        // finally a probe survives -> the climb continues and the back-off is forgotten
        now += 40_000
        assertTrue(limit.tryProbeUp(8)) // allow 4
        now += 3_000
        assertTrue(limit.tryProbeUp(8)) // 4 worked, allow 5
        assertEquals(5, limit.effective(8))
        limit.onConnectionRefused(stillOpen = 4, requested = 8) // the 5th was refused
        assertEquals(4, limit.effective(8))
        assertEquals(20_000, limit.probeIntervalMs(), "one failed probe since the last success")
    }

    @Test
    fun resetForgetsEverything() {
        limit.onConnectionRefused(stillOpen = 2, requested = 8)
        limit.reset()
        assertNull(limit.limit)
        assertEquals(8, limit.effective(8))
        assertEquals(10_000, limit.probeIntervalMs())
    }
}

class PartRetryBackoffTest {
    @Test
    fun firstRetryIsFastThenGrowsAndIsCapped() {
        val noJitter = object : Random() {
            override fun nextBits(bitCount: Int) = 0
            override fun nextLong(until: Long) = 0L
        }
        val delays = (1..8).map { PartRetryBackoff.delayFor(it, noJitter) }
        assertEquals(listOf(250L, 500L, 1000L, 2000L, 3000L, 3000L, 3000L, 3000L), delays)
        assertEquals(0L, PartRetryBackoff.delayFor(0, noJitter))
        assertEquals(3000L, PartRetryBackoff.delayFor(Int.MAX_VALUE, noJitter))
    }

    @Test
    fun jitterStaysWithinTwentyPercentBelowNominal() {
        val random = Random(42)
        repeat(1000) {
            val attempt = it % 7 + 1
            val nominal = minOf(250L shl (attempt - 1), 3000L)
            val d = PartRetryBackoff.delayFor(attempt, random)
            assertTrue(d in (nominal * 4 / 5)..nominal, "attempt=$attempt delay=$d")
        }
    }
}
