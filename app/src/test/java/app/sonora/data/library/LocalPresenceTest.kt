package app.sonora.data.library

import app.sonora.core.model.Source
import app.sonora.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты логики «трек удалили вне приложения».
 *
 * Проверяется чистая часть [LocalPresence] — разбор URI и решение о пропаже.
 * Курсор MediaStore и `File.list()` сюда не входят: они лишь поставляют данные,
 * а всё решение принимается в `missingIds`, который тестируется на JVM.
 */
class LocalPresenceTest {

    private fun mediaTrack(id: Long, trackId: String = "local:$id") = Track(
        id = trackId,
        title = "Track $id",
        artist = "Artist",
        uri = "content://media/external/audio/media/$id",
        source = Source.LOCAL,
    )

    private fun fileTrack(path: String) = Track(
        id = "file:$path",
        title = "File",
        artist = "Artist",
        uri = "file://$path",
        source = Source.LOCAL,
    )

    private fun streamingTrack(id: String = "yt:abc") = Track(
        id = id,
        title = "Stream",
        artist = "Artist",
        uri = null,
        source = Source.YOUTUBE,
    )

    // ---------- разбор URI ----------

    @Test
    fun `media id извлекается из content uri`() {
        assertEquals(
            42L,
            LocalPresence.mediaIdOf("content://media/external/audio/media/42"),
        )
    }

    @Test
    fun `media id с хвостом после номера тоже читается`() {
        // MediaStore иногда отдаёт uri вида .../media/42/children
        assertEquals(
            42L,
            LocalPresence.mediaIdOf("content://media/external/audio/media/42/children"),
        )
    }

    @Test
    fun `чужой content uri не считается media id`() {
        assertNull(LocalPresence.mediaIdOf("content://com.example.provider/audio/7"))
    }

    @Test
    fun `file uri разбирается в путь`() {
        assertEquals(
            "/storage/emulated/0/Music/a.mp3",
            LocalPresence.filePathOf("file:///storage/emulated/0/Music/a.mp3"),
        )
    }

    @Test
    fun `file uri с пробелами декодируется`() {
        assertEquals(
            "/storage/emulated/0/Music/my song.mp3",
            LocalPresence.filePathOf("file:///storage/emulated/0/Music/my%20song.mp3"),
        )
    }

    @Test
    fun `content uri не даёт путь файла`() {
        assertNull(LocalPresence.filePathOf("content://media/external/audio/media/42"))
    }

    // ---------- решение о пропаже ----------

    @Test
    fun `трек из media store которого нет в курсоре считается пропавшим`() {
        val tracks = listOf(mediaTrack(1), mediaTrack(2), mediaTrack(3))
        val missing = LocalPresence.missingIds(
            tracks = tracks,
            presentMediaIds = setOf(1L, 3L),
            isFilePresent = { true },
        )
        assertEquals(setOf("local:2"), missing)
    }

    @Test
    fun `все треки на месте - ничего не пропало`() {
        val tracks = listOf(mediaTrack(1), mediaTrack(2))
        val missing = LocalPresence.missingIds(
            tracks = tracks,
            presentMediaIds = setOf(1L, 2L, 99L),
            isFilePresent = { true },
        )
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `неудачный запрос к media store не вычищает библиотеку`() {
        // presentMediaIds == null означает «проверить не удалось». Считать в этом
        // случае все треки пропавшими — худший из возможных исходов: библиотека
        // обнулилась бы из-за отозванного разрешения.
        val tracks = listOf(mediaTrack(1), mediaTrack(2))
        val missing = LocalPresence.missingIds(
            tracks = tracks,
            presentMediaIds = null,
            isFilePresent = { true },
        )
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `удалённый файл из папки приложения считается пропавшим`() {
        val tracks = listOf(
            fileTrack("/storage/emulated/0/Android/data/app.sonora/files/Music/Sonora/a.mp3"),
            fileTrack("/storage/emulated/0/Android/data/app.sonora/files/Music/Sonora/b.mp3"),
        )
        val alive = "/storage/emulated/0/Android/data/app.sonora/files/Music/Sonora/a.mp3"
        val missing = LocalPresence.missingIds(
            tracks = tracks,
            presentMediaIds = emptySet(),
            isFilePresent = { path -> path == alive },
        )
        assertEquals(
            setOf("file:/storage/emulated/0/Android/data/app.sonora/files/Music/Sonora/b.mp3"),
            missing,
        )
    }

    @Test
    fun `сетевые треки не проверяются`() {
        val tracks = listOf(streamingTrack(), mediaTrack(5))
        val missing = LocalPresence.missingIds(
            tracks = tracks,
            presentMediaIds = emptySet(),
            isFilePresent = { false },
        )
        // Пропал только локальный; стриминговый без файла — это норма.
        assertEquals(setOf("local:5"), missing)
    }

    @Test
    fun `трек без uri не считается пропавшим`() {
        val noUri = Track(id = "local:9", title = "X", artist = "Y", source = Source.LOCAL)
        val missing = LocalPresence.missingIds(
            tracks = listOf(noUri),
            presentMediaIds = emptySet(),
            isFilePresent = { false },
        )
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `пустой список не даёт пропавших`() {
        assertTrue(
            LocalPresence.missingIds(emptyList(), emptySet(), { false }).isEmpty(),
        )
    }

    @Test
    fun `смешанная библиотека - пропадает только отсутствующее`() {
        val keepMedia = mediaTrack(10, "local:10")
        val goneMedia = mediaTrack(11, "local:11")
        val keepFile = fileTrack("/data/x/keep.mp3")
        val goneFile = fileTrack("/data/x/gone.mp3")
        val missing = LocalPresence.missingIds(
            tracks = listOf(keepMedia, goneMedia, keepFile, goneFile),
            presentMediaIds = setOf(10L),
            isFilePresent = { path -> path == "/data/x/keep.mp3" },
        )
        assertEquals(setOf("local:11", "file:/data/x/gone.mp3"), missing)
    }

    @Test
    fun `схема file в верхнем регистре распознаётся`() {
        assertEquals(
            "/tmp/a.mp3",
            LocalPresence.filePathOf("FILE:///tmp/a.mp3"),
        )
    }
}
