package ir.amirab.downloader.downloaditem.ytdlp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YtDlpProgressTest {
    @Test
    fun `normal line`() {
        val p = YtDlpOutputParser.parseProgress("ABDM|137|1048576|10485760|NA|524288.5|18")!!
        assertEquals("137", p.phaseKey)
        assertEquals(1048576L, p.downloadedBytes)
        assertEquals(10485760L, p.totalBytes)
        assertNull(p.totalBytesEstimate)
        assertEquals(524288.5, p.speed)
        assertEquals(18L, p.eta)
    }

    @Test
    fun `NA values`() {
        val p = YtDlpOutputParser.parseProgress("ABDM|NA|NA|NA|NA|NA|NA")!!
        assertNull(p.phaseKey)
        assertNull(p.downloadedBytes)
        assertNull(p.bestTotal)
        assertNull(p.speed)
        assertNull(p.eta)
    }

    @Test
    fun `estimated total is used when total is missing`() {
        val p = YtDlpOutputParser.parseProgress("ABDM|251|2000|NA|8000.0|NA|NA")!!
        assertEquals(8000L, p.bestTotal)
    }

    @Test
    fun `non progress lines are ignored`() {
        assertNull(YtDlpOutputParser.parseProgress("[youtube] BaW_jenozKc: Downloading webpage"))
        assertNull(YtDlpOutputParser.parseProgress("ABDM|1|2"))
        assertEquals("C:\\Downloads\\a b.mp4", YtDlpOutputParser.parseFilePath("ABDMFILE|C:\\Downloads\\a b.mp4\r"))
        assertNull(YtDlpOutputParser.parseFilePath("ABDM|1|2|3|4|5|6"))
    }

    @Test
    fun `two phase download is merged and never goes backwards`() {
        // no size estimate available: percent is split per phase
        val agg = YtDlpProgressAggregator(expectedPhases = 2, expectedTotalBytes = null)
        val percents = mutableListOf<Int>()
        val downloaded = mutableListOf<Long>()
        fun feed(line: String) {
            val s = agg.onProgress(YtDlpOutputParser.parseProgress(line)!!)
            percents += s.percent
            downloaded += s.downloadedBytes
        }
        feed("ABDM|137|0|1000|NA|NA|NA")
        feed("ABDM|137|500|1000|NA|NA|NA")
        feed("ABDM|137|1000|1000|NA|NA|NA")
        assertEquals(50, percents.last()) // video done = half of the job
        feed("ABDM|140|0|200|NA|NA|NA")   // audio starts: must not drop
        feed("ABDM|140|100|200|NA|NA|NA")
        feed("ABDM|140|200|200|NA|NA|NA")
        assertEquals(percents.sorted(), percents, "percent went backwards: $percents")
        assertEquals(downloaded.sorted(), downloaded, "bytes went backwards: $downloaded")
        assertEquals(1200L, downloaded.last())
        assertTrue(percents.last() in 95..99)
        assertEquals(1200L, agg.snapshot.totalBytes)
        assertEquals(100, agg.onFinished(1150).percent)
    }

    @Test
    fun `size estimate from the quality window drives the percent`() {
        val agg = YtDlpProgressAggregator(expectedPhases = 2, expectedTotalBytes = 1200)
        val s1 = agg.onProgress(YtDlpOutputParser.parseProgress("ABDM|137|600|1000|NA|NA|NA")!!)
        assertEquals(50, s1.percent)
        assertEquals(1200L, s1.totalBytes)
    }

    @Test
    fun `resume keeps persisted progress`() {
        val agg = YtDlpProgressAggregator(expectedPhases = 1, expectedTotalBytes = null, initialDownloaded = 700, initialPercent = 70)
        // yt-dlp reports a smaller number at first while it reconnects
        val s = agg.onProgress(YtDlpOutputParser.parseProgress("ABDM|140|NA|1000|NA|NA|NA")!!)
        assertEquals(70, s.percent)
        assertEquals(700L, s.downloadedBytes)
        val s2 = agg.onProgress(YtDlpOutputParser.parseProgress("ABDM|140|800|1000|NA|NA|NA")!!)
        assertEquals(80, s2.percent)
    }
}
