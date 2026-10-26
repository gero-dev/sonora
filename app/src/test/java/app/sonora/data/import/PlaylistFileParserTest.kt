package app.sonora.data.import

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistFileParserTest {

    @Test
    fun parsesSpotifyCsv() {
        val csv = """
            Track Name,Artist Name(s),Album Name,Duration (ms),Video ID
            Smells Like Teen Spirit,Nirvana,Nevermind,302000,ljUtuoFt-8c
            "Song, with comma",Artist Two,Album Two,215000,
        """.trimIndent()
        val entries = PlaylistFileParser.parse("spotify.csv", csv)
        assertEquals(2, entries.size)
        assertEquals("Smells Like Teen Spirit", entries[0].title)
        assertEquals("Nirvana", entries[0].artist)
        assertEquals("Nevermind", entries[0].album)
        assertEquals(302_000L, entries[0].durationMs)
        assertEquals("ljUtuoFt-8c", entries[0].videoId)
        assertEquals("Song, with comma", entries[1].title)
    }

    @Test
    fun parsesSpotifyJson() {
        val json = """
            {
              "items": [
                {
                  "track": {
                    "name": "Smells Like Teen Spirit",
                    "artists": [{"name": "Nirvana"}],
                    "album": {"name": "Nevermind"},
                    "duration_ms": 302000,
                    "external_urls": {"spotify": "https://open.spotify.com/track/abc"}
                  }
                }
              ]
            }
        """.trimIndent()
        val entries = PlaylistFileParser.parse("playlist.json", json)
        assertEquals(1, entries.size)
        assertEquals("Smells Like Teen Spirit", entries[0].title)
        assertEquals("Nirvana", entries[0].artist)
        assertEquals("Nevermind", entries[0].album)
        assertEquals(302_000L, entries[0].durationMs)
    }

    @Test
    fun parsesAppleMusicJsonWithoutAlbumDuplicates() {
        val json = """
            {
              "data": [
                {
                  "id": "1440857781",
                  "type": "songs",
                  "attributes": {
                    "name": "Smells Like Teen Spirit",
                    "artistName": "Nirvana",
                    "albumName": "Nevermind",
                    "durationInMillis": 301000
                  }
                }
              ]
            }
        """.trimIndent()
        val entries = PlaylistFileParser.parse("apple.json", json)
        assertEquals(1, entries.size)
        assertEquals("Smells Like Teen Spirit", entries[0].title)
        assertEquals("Nirvana", entries[0].artist)
        assertEquals("Nevermind", entries[0].album)
        assertEquals(301_000L, entries[0].durationMs)
    }

    @Test
    fun skipsAlbumObjectsWithArtists() {
        val json = """
            {
              "items": [
                {
                  "track": {
                    "name": "Song",
                    "artists": [{"name": "Artist"}],
                    "album": {
                      "name": "Album",
                      "album_type": "album",
                      "artists": [{"name": "Artist"}]
                    }
                  }
                }
              ]
            }
        """.trimIndent()
        val entries = PlaylistFileParser.parse("spotify.json", json)
        assertEquals(1, entries.size)
        assertEquals("Song", entries[0].title)
        assertEquals("Artist", entries[0].artist)
    }

    @Test
    fun parsesM3u() {
        val m3u = """
            #EXTM3U
            #EXTINF:302,Nirvana - Smells Like Teen Spirit
            https://music.youtube.com/watch?v=ljUtuoFt-8c
            #EXTINF:354,Queen - Bohemian Rhapsody
            https://open.spotify.com/track/xyz
        """.trimIndent()
        val entries = PlaylistFileParser.parse("playlist.m3u8", m3u)
        assertEquals(2, entries.size)
        assertEquals("Nirvana", entries[0].artist)
        assertEquals("Smells Like Teen Spirit", entries[0].title)
        assertEquals("ljUtuoFt-8c", entries[0].videoId)
        assertEquals(302_000L, entries[0].durationMs)
        assertEquals("Queen", entries[1].artist)
    }

    @Test
    fun parsesPlainText() {
        val text = """
            # мой список
            Nirvana - Smells Like Teen Spirit
            Queen – Bohemian Rhapsody
            Just A Title
        """.trimIndent()
        val entries = PlaylistFileParser.parse("list.txt", text)
        assertEquals(3, entries.size)
        assertEquals("Nirvana", entries[0].artist)
        assertEquals("Smells Like Teen Spirit", entries[0].title)
        assertEquals("Queen", entries[1].artist)
        assertEquals("Just A Title", entries[2].title)
    }

    @Test
    fun limitsAndDeduplicates() {
        val text = (1..10).joinToString("\n") { "Artist - Same Song" }
        val entries = PlaylistFileParser.parse("list.txt", text)
        assertEquals(1, entries.size)
        assertTrue(entries.all { it.title == "Same Song" })
    }
}
