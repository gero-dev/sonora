package app.sonora.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sonora играет только музыку: видео/клипы/подкасты не должны попадать в плеер. */
class TrackVideoTest {

    @Test
    fun `official video and podcast are treated as video content`() {
        assertTrue(remote("Song (Official Video)").isVideoContent)
        assertTrue(remote("Interview with the band").isVideoContent)
        assertTrue(remote("Подкаст: выпуск 42").isVideoContent)
    }

    @Test
    fun `normal songs and live albums are not filtered`() {
        assertFalse(remote("Smells Like Teen Spirit").isVideoContent)
        assertFalse(remote("Bohemian Rhapsody (Live at Wembley)").isVideoContent)
        assertFalse(remote("Piano Concerto No. 1").isVideoContent)
    }

    @Test
    fun `local file with video track is filtered`() {
        val local = Track(
            id = "file:/music/clip.mp4",
            title = "Clip",
            artist = "Artist",
            source = Source.LOCAL,
            extra = mapOf("hasVideo" to "1"),
        )
        assertTrue(local.isVideoContent)
    }

    private fun remote(title: String) = Track(
        id = "youtube:abc",
        title = title,
        artist = "Artist",
        source = Source.YOUTUBE,
    )
}
