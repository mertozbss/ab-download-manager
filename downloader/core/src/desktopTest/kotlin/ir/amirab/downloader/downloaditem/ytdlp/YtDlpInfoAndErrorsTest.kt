package ir.amirab.downloader.downloaditem.ytdlp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YtDlpInfoAndErrorsTest {
    private val sampleJson = """
        {"id":"BaW_jenozKc","title":"youtube-dl test video","channel":"Philipp Hagemeister","duration":10,
         "is_live":false,
         "formats":[
           {"format_id":"140","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","filesize":160000,"tbr":129.5},
           {"format_id":"251","ext":"webm","vcodec":"none","acodec":"opus","filesize_approx":150000,"tbr":140.1},
           {"format_id":"136","ext":"mp4","height":720,"vcodec":"avc1","acodec":"none","filesize":900000,"tbr":700},
           {"format_id":"137","ext":"mp4","height":1080,"vcodec":"avc1","acodec":"none","filesize":2000000,"tbr":1500},
           {"format_id":"313","ext":"webm","height":2160,"vcodec":"vp9","acodec":"none","filesize":9000000,"tbr":9000},
           {"format_id":"sb0","ext":"mhtml","vcodec":"none","acodec":"none"}
         ]}
    """.trimIndent()

    @Test
    fun `info json is parsed`() {
        val info = YtDlpInfoParser.parse(sampleJson)
        assertEquals("BaW_jenozKc", info.id)
        assertEquals("youtube-dl test video", info.title)
        assertEquals("Philipp Hagemeister", info.channel)
        assertEquals(10.0, info.duration)
        assertEquals(6, info.formats.size)
    }

    @Test
    fun `quality options with size estimates`() {
        val options = YtDlpInfoParser.qualityOptions(YtDlpInfoParser.parse(sampleJson)).associateBy { it.quality }
        assertEquals(YtDlpQuality.Best1080, options.keys.first())
        assertEquals(2_160_000L, options[YtDlpQuality.Best1080]!!.estimatedSize)
        assertEquals(9_160_000L, options[YtDlpQuality.P2160]!!.estimatedSize)
        assertEquals(1_060_000L, options[YtDlpQuality.P720]!!.estimatedSize)
        assertFalse(YtDlpQuality.P1440 in options)
        assertFalse(YtDlpQuality.P480 in options)
        assertEquals(160_000L, options[YtDlpQuality.AudioM4a]!!.estimatedSize)
        assertTrue(YtDlpQuality.AudioMp3 in options)
    }

    @Test
    fun `size is estimated from bitrate when youtube gives no filesize`() {
        val json = """{"id":"BaW_jenozKc","duration":100,"formats":[
            {"format_id":"140","ext":"m4a","vcodec":"none","acodec":"mp4a","tbr":128},
            {"format_id":"137","ext":"mp4","height":1080,"vcodec":"avc1","acodec":"none","tbr":4000}]}"""
        val best = YtDlpInfoParser.qualityOptions(YtDlpInfoParser.parse(json)).first { it.quality == YtDlpQuality.Best1080 }
        // (4000 + 128) kbit/s * 100 s / 8
        assertEquals(51_600_000L, best.estimatedSize)
    }

    @Test
    fun `info without a valid id is rejected`() {
        assertFailsWith<IllegalArgumentException> { YtDlpInfoParser.parse("""{"id":"--exec","formats":[]}""") }
    }

    @Test
    fun `errors are classified with update hint`() {
        fun k(vararg lines: String) = YtDlpErrorClassifier.classify(lines.toList(), 1).kind
        assertEquals(YtDlpErrorKind.PrivateVideo, k("ERROR: [youtube] abc: Private video. Sign in if you've been granted access"))
        assertEquals(YtDlpErrorKind.AgeRestricted, k("ERROR: [youtube] abc: Sign in to confirm your age. This video may be inappropriate"))
        assertEquals(YtDlpErrorKind.NeedsUpdate, k("ERROR: [youtube] abc: Sign in to confirm you’re not a bot."))
        assertEquals(YtDlpErrorKind.NeedsUpdate, k("WARNING: [youtube] n challenge solving failed", "ERROR: unable to download video data: HTTP Error 403: Forbidden"))
        assertEquals(YtDlpErrorKind.Unavailable, k("ERROR: [youtube] abc: Video unavailable"))
        assertEquals(YtDlpErrorKind.Unavailable, k("ERROR: [youtube] BaW_jenozKc: This video is unavailable"))
        assertEquals(YtDlpErrorKind.Network, k("ERROR: unable to download video data: <urlopen error timed out>"))
        assertEquals(YtDlpErrorKind.Unknown, k("something odd"))
        val e = YtDlpErrorClassifier.classify(listOf("ERROR: HTTP Error 403: Forbidden"), 1)
        assertTrue(e.suggestUpdate)
        assertTrue("yt-dlp" in e.message!!)
        assertTrue(e.isCritical())
        assertFalse(YtDlpErrorClassifier.classify(listOf("ERROR: timed out"), 1).isCritical())
    }
}
