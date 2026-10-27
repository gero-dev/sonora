package app.sonora.data.wave

import app.sonora.core.model.Source
import app.sonora.core.model.Track
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Совместимость истории с файлами, записанными до 0.9.9. */
class WaveHistoryEntryTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `legacy history entry decodes with safe defaults`() {
        val legacy = """
            {"trackId":"local:1","artist":"Nirvana","title":"Lithium",
             "playedAtMs":1700000000000,"completed":true}
        """.trimIndent()

        val entry = json.decodeFromString(WaveHistoryEntry.serializer(), legacy)

        assertEquals("local:1", entry.trackId)
        assertTrue(entry.completed)
        assertEquals(0L, entry.listenedMs)
        assertEquals(0L, entry.durationMs)
        assertEquals("", entry.source)
    }

    @Test
    fun `entry round trips new statistics fields`() {
        val entry = WaveHistoryEntry(
            trackId = "youtube:abc",
            artist = "Nirvana",
            title = "Smells Like Teen Spirit",
            playedAtMs = 1700000000000L,
            completed = true,
            listenedMs = 182_000L,
            durationMs = 301_000L,
            source = Source.YOUTUBE.name,
        )

        val decoded = json.decodeFromString(
            WaveHistoryEntry.serializer(),
            json.encodeToString(WaveHistoryEntry.serializer(), entry),
        )

        assertEquals(entry, decoded)
    }

    @Test
    fun `savedFromStreaming distinguishes downloaded copies from plain local files`() {
        val downloaded = Track(
            id = "file:/music/song.m4a",
            title = "Song",
            artist = "Artist",
            source = Source.LOCAL,
            extra = mapOf("downloadedTrackId" to "youtube:abc", "youtubeId" to "abc"),
        )
        val plainLocal = Track(
            id = "local:42",
            title = "Local",
            artist = "Artist",
            source = Source.LOCAL,
        )
        val remote = Track(
            id = "youtube:abc",
            title = "Song",
            artist = "Artist",
            source = Source.YOUTUBE,
        )

        assertTrue(downloaded.savedFromStreaming)
        assertFalse(plainLocal.savedFromStreaming)
        assertFalse(remote.savedFromStreaming)
        assertTrue(remote.streamingOnly)
    }
}
