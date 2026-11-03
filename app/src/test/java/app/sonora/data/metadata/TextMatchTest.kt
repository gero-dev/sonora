package app.sonora.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты нормализации и сравнения строк.
 *
 * Поводом послужила жалоба: 10–15 % своих mp3 показывали чужие исполнителя и
 * обложку. Причина — сравнение подстрокой без снятия диакритики и пометок.
 */
class TextMatchTest {

    // ------------------------------------------------------------ normalize

    @Test
    fun `diacritics are folded`() {
        assertEquals("beyonce", TextMatch.normalize("Beyoncé"))
        assertEquals("motley crue", TextMatch.normalize("Mötley Crüe"))
        assertEquals("bjork", TextMatch.normalize("Björk"))
    }

    /**
     * Типографские тире должны сходиться с ASCII-дефисом. Проверяем именно
     * равенство вариантов между собой, а не конкретную строку: значимо то, что
     * тег файла и строка каталога дают одну основу, а не то, как она выглядит.
     */
    @Test
    fun `typographic dashes and quotes collapse to the same form`() {
        val ascii = TextMatch.normalize("Тире - тест")
        assertEquals(ascii, TextMatch.normalize("Тире – тест"))
        assertEquals(ascii, TextMatch.normalize("Тире — тест"))
        assertEquals(ascii, TextMatch.normalize("Тире − тест"))
        assertTrue(TextMatch.titlesMatch("Тире – тест", "Тире - тест"))
    }

    @Test
    fun `bracketed groups are removed`() {
        assertEquals("song", TextMatch.normalize("Song (Remastered 2011)"))
        assertEquals("song", TextMatch.normalize("Song [Official Video]"))
        assertEquals("song", TextMatch.normalize("Song (feat. Someone)"))
    }

    @Test
    fun `feat tails are removed`() {
        assertEquals("artist", TextMatch.normalize("Artist feat. Someone"))
        assertEquals("artist", TextMatch.normalize("Artist ft. Someone"))
        assertEquals("artist", TextMatch.normalize("Artist featuring Someone"))
        assertEquals("artist", TextMatch.normalize("Artist при уч. Someone"))
    }

    @Test
    fun `edition tails after a dash are removed`() {
        assertEquals("song", TextMatch.normalize("Song - Remastered 2011"))
        assertEquals("song", TextMatch.normalize("Song - Radio Edit"))
        assertEquals("song", TextMatch.normalize("Song – Official Video"))
    }

    @Test
    fun `yo is folded to ye`() {
        assertEquals(TextMatch.normalize("Ёлка"), TextMatch.normalize("Елка"))
    }

    /**
     * Кириллические двойники латинских букв приводятся к латинице. Это НЕ
     * транслитерация: «Ария» и «Aria» не станут одной строкой (для этого нужен
     * словарь), но «Аriа» с латинскими `A` и кириллическими `р`,`и`,`а` —
     * станет, а именно такие смешанные написания и встречаются в тегах.
     */
    @Test
    fun `cyrillic lookalikes map to latin`() {
        // Слово целиком из двойников: «Аос» (кир.) → «Aoc» (лат.).
        assertEquals(TextMatch.normalize("Aoc"), TextMatch.normalize("Аос"))
        // Смешанное написание: латинская A + кириллические «ос».
        assertEquals(TextMatch.normalize("Aoc"), TextMatch.normalize("Aос"))
        // Буквы без латинских двойников остаются как есть.
        assertTrue(TextMatch.normalize("Ария").contains("и"))
    }

    @Test
    fun `blank input normalizes to empty`() {
        assertEquals("", TextMatch.normalize(null))
        assertEquals("", TextMatch.normalize(""))
        assertEquals("", TextMatch.normalize("   "))
        assertEquals("", TextMatch.normalize("(...)"))
    }

    // ------------------------------------------------------------ titlesMatch

    @Test
    fun `remastered suffix still matches`() {
        assertTrue(TextMatch.titlesMatch("Song (Remastered 2011)", "Song"))
        assertTrue(TextMatch.titlesMatch("Song", "Song (Remastered 2011)"))
    }

    @Test
    fun `exact titles match`() {
        assertTrue(TextMatch.titlesMatch("Bohemian Rhapsody", "Bohemian Rhapsody"))
        assertTrue(TextMatch.titlesMatch("bohemian rhapsody", "Bohemian Rhapsody"))
    }

    /**
     * Главная регрессия: раньше `contains` считал эти названия одинаковыми, и
     * файлу «The Man» подставлялись метаданные «The Man Who Sold the World».
     */
    @Test
    fun `a title that merely starts with another does not match`() {
        assertFalse(TextMatch.titlesMatch("The Man Who Sold the World", "The Man"))
        assertFalse(TextMatch.titlesMatch("The Man", "The Man Who Sold the World"))
    }

    @Test
    fun `substring inside a word does not match`() {
        assertFalse(TextMatch.titlesMatch("Woman", "Man"))
        assertFalse(TextMatch.titlesMatch("Everything", "Thing"))
    }

    @Test
    fun `artist prefixed title matches the bare title`() {
        // «Artist - Song» против «Song»: совпадение настоящее, хвост по словам.
        assertTrue(TextMatch.titlesMatch("Nirvana - Smells Like Teen Spirit", "Smells Like Teen Spirit"))
    }

    @Test
    fun `different titles do not match`() {
        assertFalse(TextMatch.titlesMatch("Yesterday", "Let It Be"))
    }

    @Test
    fun `blank titles never match`() {
        assertFalse(TextMatch.titlesMatch("", "Song"))
        assertFalse(TextMatch.titlesMatch("Song", ""))
        assertFalse(TextMatch.titlesMatch(null, "Song"))
        assertFalse(TextMatch.titlesMatch("(Live)", "Song"))
    }

    // ------------------------------------------------------------ artistsMatch

    @Test
    fun `diacritic artist names match`() {
        assertTrue(TextMatch.artistsMatch("Beyonce", "Beyoncé"))
        assertTrue(TextMatch.artistsMatch("Beyoncé", "Beyonce"))
    }

    @Test
    fun `the prefix is optional`() {
        assertTrue(TextMatch.artistsMatch("Beatles", "The Beatles"))
        assertTrue(TextMatch.artistsMatch("The Beatles", "Beatles"))
    }

    @Test
    fun `feat tail does not break artist matching`() {
        assertTrue(TextMatch.artistsMatch("Artist feat. Someone", "Artist"))
    }

    /**
     * Отсутствие исполнителя — это «неизвестно», а не «не совпало»: иначе
     * файлы без тега исполнителя вообще перестали бы находить метаданные.
     */
    @Test
    fun `blank artist is treated as unknown and does not reject`() {
        assertTrue(TextMatch.artistsMatch(null, "Artist"))
        assertTrue(TextMatch.artistsMatch("", "Artist"))
        assertTrue(TextMatch.artistsMatch("Artist", null))
        assertTrue(TextMatch.artistsMatch("Artist", ""))
    }

    @Test
    fun `different artists do not match`() {
        assertFalse(TextMatch.artistsMatch("The Killers", "Nirvana"))
        assertFalse(TextMatch.artistsMatch("Matty Hinds", "The Killers"))
    }

    @Test
    fun `too short stems are not treated as word matches`() {
        // «The» — служебное слово, а не доказательство совпадения.
        assertFalse(TextMatch.artistsMatch("The", "The Killers"))
    }

    // ------------------------------------------------------------ durationMatches

    @Test
    fun `lenient tolerance applies when the artist agrees`() {
        assertTrue(TextMatch.durationMatches(200_000L, 205_000L, artistAgrees = true))
        assertTrue(TextMatch.durationMatches(200_000L, 209_000L, artistAgrees = true))
        assertFalse(TextMatch.durationMatches(200_000L, 215_000L, artistAgrees = true))
    }

    /**
     * Без совпадения исполнителя мягкий порог пропускал каверы и живые версии —
     * именно так в файл попадали чужие метаданные.
     */
    @Test
    fun `strict tolerance applies when the artist does not agree`() {
        assertTrue(TextMatch.durationMatches(200_000L, 202_000L, artistAgrees = false))
        assertFalse(TextMatch.durationMatches(200_000L, 205_000L, artistAgrees = false))
        assertFalse(TextMatch.durationMatches(200_000L, 220_000L, artistAgrees = false))
    }

    @Test
    fun `unknown duration never rejects`() {
        assertTrue(TextMatch.durationMatches(null, 200_000L, artistAgrees = false))
        assertTrue(TextMatch.durationMatches(0L, 200_000L, artistAgrees = false))
        assertTrue(TextMatch.durationMatches(200_000L, 0L, artistAgrees = false))
        assertTrue(TextMatch.durationMatches(200_000L, null, artistAgrees = true))
    }
}
