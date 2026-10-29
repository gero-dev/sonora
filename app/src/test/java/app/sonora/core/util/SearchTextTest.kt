package app.sonora.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextTest {

    @Test
    fun normalizeLowercasesAndCollapsesSpaces() {
        assertEquals(
            "неизвестный исполнитель",
            SearchText.normalize("  НЕИЗВЕСТНЫЙ   Исполнитель "),
        )
        assertEquals("a b", SearchText.normalize("a\t\n b"))
        assertEquals("", SearchText.normalize("   "))
    }

    @Test
    fun normalizeReplacesYoWithYe() {
        assertEquals("черемуха", SearchText.normalize("Черёмуха"))
        assertEquals("пойдем", SearchText.normalize("пойдём"))
    }

    @Test
    fun tokensSplitOnPunctuation() {
        assertEquals(listOf("nirvana", "nevermind"), SearchText.tokens("Nirvana - Nevermind"))
        assertEquals(listOf("ac", "dc"), SearchText.tokens("AC/DC"))
        assertEquals(listOf("earth", "wind", "fire"), SearchText.tokens("Earth, Wind & Fire"))
        assertEquals(listOf("черемуха"), SearchText.tokens("Черёмуха"))
    }

    @Test
    fun tokensOfPunctuationOnlyAreEmpty() {
        assertTrue(SearchText.tokens("!?—").isEmpty())
        assertTrue(SearchText.tokens("   ").isEmpty())
    }

    @Test
    fun tokensOfNormalizedDoesNotRenormalize() {
        // Уже нормализованная строка: пунктуация режется, регистр не трогаем.
        assertEquals(listOf("a", "b"), SearchText.tokensOfNormalized("a-b"))
        assertEquals(listOf("a", "b"), SearchText.tokensOfNormalized("a b"))
    }

    @Test
    fun transliteratesCyrillicToLatin() {
        assertEquals("nirvana", SearchText.transliterate("нирвана"))
        assertEquals("nirvana", SearchText.transliterate("НИРВАНА"))
        assertEquals("cheremukha", SearchText.transliterate("черемуха"))
        assertEquals("mumiy troll", SearchText.transliterate("мумий тролль"))
    }

    @Test
    fun transliterationKeepsLatinAndDigits() {
        assertEquals("the beatles", SearchText.transliterate("the beatles"))
        assertEquals("track 01", SearchText.transliterate("track 01"))
        assertEquals("ac/dc", SearchText.transliterate("ac/dc"))
    }

    @Test
    fun transliterationHandlesUkrainianLetters() {
        assertEquals("ukraina", SearchText.transliterate("україна"))
        assertEquals("gurt", SearchText.transliterate("ґурт"))
    }

    @Test
    fun detectsCyrillic() {
        assertTrue(SearchText.containsCyrillic("Нирвана"))
        assertTrue(SearchText.containsCyrillic("чё"))
        assertFalse(SearchText.containsCyrillic("Nirvana"))
        assertFalse(SearchText.containsCyrillic("track 01"))
    }
}
