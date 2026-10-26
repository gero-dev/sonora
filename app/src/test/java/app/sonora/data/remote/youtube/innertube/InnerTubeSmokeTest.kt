package app.sonora.data.remote.youtube.innertube

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Смоук-тест InnerTube: ходит в реальный YouTube Music.
 * Запускается вручную (`gradlew testDebugUnitTest`), в CI не нужен.
 */
class InnerTubeSmokeTest {

    private val api = InnerTubeApi()

    @Test
    fun searchReturnsPlayableTracks() = runBlocking {
        val tracks = api.search("nirvana smells like teen spirit", 10)
        println("SEARCH: ${tracks.size}")
        tracks.take(3).forEach {
            println("  ${it.title} — ${it.artist} — ${it.album} — ${it.durationMs} — ${it.uri} — ${it.artworkUrl}")
        }
        assertTrue(tracks.isNotEmpty())
        assertTrue(tracks.all { it.playable && it.uri?.startsWith("innertube:") == true })
        assertTrue(tracks.all { it.title.isNotBlank() && it.artist.isNotBlank() })
    }

    @Test
    fun playlistParsesTracks() = runBlocking {
        val playlist = api.playlist("PLdEiuPxBVWSiUBirMsRAVE066fTq3IFkT", 50)
        println("PLAYLIST: ${playlist.title} / ${playlist.subtitle} / ${playlist.tracks.size}")
        assertTrue(playlist.tracks.isNotEmpty())
        assertTrue(playlist.title.isNotBlank())
    }

    @Test
    fun streamAndRelatedAndLyrics() = runBlocking {
        val stream = InnerTubeStreams.resolve("ljUtuoFt-8c")
        println("STREAM: ${stream.itag} ${stream.mimeType} ${stream.bitrate} ${stream.url.take(80)}")
        assertTrue(stream.url.startsWith("https://"))

        val related = api.related("ljUtuoFt-8c", 10)
        println("RELATED: ${related.size}")
        related.take(3).forEach { println("  ${it.title} — ${it.artist}") }
        assertTrue(related.isNotEmpty())

        val lyrics = api.lyrics("ljUtuoFt-8c")
        println("LYRICS: ${lyrics?.take(120)}")
        assertTrue(!lyrics.isNullOrBlank())
    }

    @Test
    fun streamUrlIsDownloadable() = runBlocking {
        val stream = InnerTubeStreams.resolve("dQw4w9WgXcQ")
        val connection = java.net.URL(stream.url).openConnection() as java.net.HttpURLConnection
        connection.setRequestProperty("Range", "bytes=0-4095")
        connection.setRequestProperty("User-Agent", InnerTube.USER_AGENT_WEB)
        connection.connectTimeout = 20_000
        connection.readTimeout = 20_000
        val code = connection.responseCode
        val bytes = connection.inputStream.use { it.readBytes() }
        println("DOWNLOAD: http=$code bytes=${bytes.size} mime=${connection.contentType}")
        assertTrue("ожидали 200/206, получили $code", code == 200 || code == 206)
        assertTrue(bytes.isNotEmpty())
        connection.disconnect()
    }

    @Test
    fun uriAndPlaylistParsing() {
        org.junit.Assert.assertEquals("dQw4w9WgXcQ", InnerTubeStreams.videoIdOf("innertube:dQw4w9WgXcQ"))
        org.junit.Assert.assertNull(InnerTubeStreams.videoIdOf("https://example.com/a.mp3"))
        org.junit.Assert.assertEquals(
            "PLdEiuPxBVWSiUBirMsRAVE066fTq3IFkT",
            api.extractPlaylistId("https://music.youtube.com/playlist?list=PLdEiuPxBVWSiUBirMsRAVE066fTq3IFkT"),
        )
        org.junit.Assert.assertEquals(
            "PLdEiuPxBVWSiUBirMsRAVE066fTq3IFkT",
            api.extractPlaylistId("PLdEiuPxBVWSiUBirMsRAVE066fTq3IFkT"),
        )
    }

    @Test
    fun mp4StreamIsDownloadable() = runBlocking {
        val stream = InnerTubeStreams.resolveDownload("ljUtuoFt-8c")
        println("DOWNLOAD_STREAM: itag=${stream.itag} mime=${stream.mimeType} bitrate=${stream.bitrate}")
        assertTrue(stream.mimeType.contains("mp4", ignoreCase = true) || stream.mimeType.contains("webm", ignoreCase = true))
        val connection = java.net.URL(stream.url).openConnection() as java.net.HttpURLConnection
        connection.setRequestProperty("Range", "bytes=0-2047")
        stream.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        if (stream.headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            connection.setRequestProperty("User-Agent", InnerTube.USER_AGENT_WEB)
        }
        connection.connectTimeout = 20_000
        connection.readTimeout = 20_000
        val code = connection.responseCode
        val bytes = connection.inputStream.use { it.readBytes() }
        println("DOWNLOAD_STREAM_HTTP: $code ${bytes.size} bytes")
        assertTrue(code == 200 || code == 206)
        assertTrue(bytes.isNotEmpty())
        connection.disconnect()
    }

    @Test
    fun innerTubeXExtractsStream() = runBlocking {
        val stream = InnerTubeXEngine.resolve("ljUtuoFt-8c")
        println("INNERTUBEX: itag=${stream.itag} mime=${stream.mimeType} bitrate=${stream.bitrate} " +
            "url=${stream.url.take(90)} headers=${stream.headers.keys}")
        assertTrue(stream.url.startsWith("https://"))
        assertTrue(stream.itag > 0)
    }
}
