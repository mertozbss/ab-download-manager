package ir.amirab.downloader.downloaditem.ytdlp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YouTubeUrlNormalizerTest {
    private val expected = "https://www.youtube.com/watch?v=BaW_jenozKc"

    private fun n(url: String) = YouTubeUrlNormalizer.normalize(url)

    @Test
    fun watch() {
        assertEquals(expected, n("https://www.youtube.com/watch?v=BaW_jenozKc"))
        assertEquals(expected, n("https://youtube.com/watch?v=BaW_jenozKc"))
        assertEquals(expected, n("http://www.youtube.com/watch?feature=share&v=BaW_jenozKc"))
    }

    @Test
    fun `youtu be`() {
        assertEquals(expected, n("https://youtu.be/BaW_jenozKc"))
        assertEquals(expected, n("https://youtu.be/BaW_jenozKc?si=XyZ123"))
    }

    @Test
    fun shorts() {
        assertEquals(expected, n("https://www.youtube.com/shorts/BaW_jenozKc"))
        assertEquals(expected, n("https://m.youtube.com/shorts/BaW_jenozKc?feature=share"))
    }

    @Test
    fun `music and mobile`() {
        assertEquals(expected, n("https://music.youtube.com/watch?v=BaW_jenozKc&feature=share"))
        assertEquals(expected, n("https://m.youtube.com/watch?v=BaW_jenozKc"))
    }

    @Test
    fun `list t and si parameters are dropped`() {
        assertEquals(expected, n("https://www.youtube.com/watch?v=BaW_jenozKc&list=PLabc&index=3"))
        assertEquals(expected, n("https://www.youtube.com/watch?v=BaW_jenozKc&t=42s"))
        assertEquals(expected, n("https://www.youtube.com/watch?list=PLabc&v=BaW_jenozKc&t=1&si=zz"))
    }

    @Test
    fun `invalid ids are rejected`() {
        assertNull(n("https://www.youtube.com/watch?v=short"))
        assertNull(n("https://www.youtube.com/watch?v=BaW_jenozKc1")) // 12 chars
        assertNull(n("https://www.youtube.com/watch?v=BaW_jen%3Bzc"))
        assertNull(n("https://youtu.be/BaW_jen\$zKc"))
        assertNull(n("https://www.youtube.com/watch"))
        assertNull(n("https://www.youtube.com/playlist?list=PLabc"))
        assertNull(n("https://www.youtube.com/watch?v=BaW_jenozKc&v=aaaaaaaaaaa"))
    }

    @Test
    fun `foreign hosts and schemes are rejected`() {
        assertNull(n("https://evil.example/watch?v=BaW_jenozKc"))
        assertNull(n("https://notyoutube.com/watch?v=BaW_jenozKc"))
        assertNull(n("https://user:pass@www.youtube.com/watch?v=BaW_jenozKc"))
        assertNull(n("file:///watch?v=BaW_jenozKc"))
        assertNull(n("javascript:alert(1)"))
        assertNull(n("-https://www.youtube.com/watch?v=BaW_jenozKc"))
        assertNull(n("https://www.youtube.com:8443/watch?v=BaW_jenozKc"))
    }
}
