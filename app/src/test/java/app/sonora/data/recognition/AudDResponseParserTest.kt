package app.sonora.data.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор ответов AudD. Тесты не ходят в сеть: проверяем только чистый парсер.
 */
class AudDResponseParserTest {

    @Test
    fun `parses found track`() {
        val body = """
            {"status":"success","result":{
              "artist":"Imagine Dragons","title":"Warriors",
              "album":"Warriors","release_date":"2014-09-18",
              "label":"Universal Music","timecode":"02:32",
              "song_link":"https://lis.tn/Warriors"}}
        """.trimIndent()

        val outcome = AudDParser.parse(body)
        assertTrue(outcome is AudDOutcome.Found)
        val track = (outcome as AudDOutcome.Found).track
        assertEquals("Imagine Dragons", track.artist)
        assertEquals("Warriors", track.title)
        assertEquals("Warriors", track.album)
        assertEquals("2014-09-18", track.releaseDate)
        assertEquals("02:32", track.timecode)
        assertEquals("https://lis.tn/Warriors", track.songLink)
        assertEquals("AudD", track.provider)
        assertEquals("Imagine Dragons — Warriors", track.displayTitle)
        assertEquals("Imagine Dragons Warriors", track.searchQuery)
    }

    @Test
    fun `null result means not found`() {
        assertEquals(AudDOutcome.NotFound, AudDParser.parse("""{"status":"success","result":null}"""))
    }

    @Test
    fun `empty array result means not found`() {
        assertEquals(AudDOutcome.NotFound, AudDParser.parse("""{"status":"success","result":[]}"""))
    }

    @Test
    fun `missing artist and title means not found`() {
        assertEquals(
            AudDOutcome.NotFound,
            AudDParser.parse("""{"status":"success","result":{"album":"X"}}"""),
        )
    }

    @Test
    fun `error response exposes server message`() {
        val body = """
            {"status":"error","error":{"error_code":901,
            "error_message":"Invalid api_token"}}
        """.trimIndent()
        val outcome = AudDParser.parse(body)
        assertTrue(outcome is AudDOutcome.Failure)
        assertEquals("Invalid api_token", (outcome as AudDOutcome.Failure).message)
    }

    @Test
    fun `error without message still returns failure`() {
        val outcome = AudDParser.parse("""{"status":"error"}""")
        assertTrue(outcome is AudDOutcome.Failure)
    }

    @Test
    fun `malformed json is failure`() {
        assertTrue(AudDParser.parse("<html>oops</html>") is AudDOutcome.Failure)
    }

    @Test
    fun `apple music artwork template is upscaled`() {
        val body = """
            {"status":"success","result":{
              "artist":"A","title":"T",
              "apple_music":{"artwork":{"url":"https://is1.mzstatic.com/image/{w}x{h}bb.jpg"}}}}
        """.trimIndent()
        val track = (AudDParser.parse(body) as AudDOutcome.Found).track
        assertEquals("https://is1.mzstatic.com/image/600x600bb.jpg", track.artworkUrl)
    }

    @Test
    fun `spotify artwork is used when apple missing`() {
        val body = """
            {"status":"success","result":{
              "artist":"A","title":"T",
              "spotify":{"album":{"images":[{"url":"https://i.scdn.co/image/abc"}]}}}}
        """.trimIndent()
        val track = (AudDParser.parse(body) as AudDOutcome.Found).track
        assertEquals("https://i.scdn.co/image/abc", track.artworkUrl)
    }

    @Test
    fun `blank strings are treated as absent`() {
        val body = """
            {"status":"success","result":{"artist":"","title":"","album":"  "}}
        """.trimIndent()
        assertEquals(AudDOutcome.NotFound, AudDParser.parse(body))
    }
}
