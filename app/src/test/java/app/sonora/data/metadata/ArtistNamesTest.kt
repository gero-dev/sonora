package app.sonora.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistNamesTest {

    @Test
    fun stripsYouTubeChannelSuffixes() {
        assertEquals("Nirvana", ArtistNames.clean("Nirvana - Topic"))
        assertEquals("Nirvana", ArtistNames.clean("NirvanaVEVO"))
        assertEquals("Nirvana", ArtistNames.clean("Nirvana - Official"))
        assertEquals("Nirvana", ArtistNames.clean("Nirvana Official"))
    }

    @Test
    fun keepsRealArtistNames() {
        assertEquals("Earth, Wind & Fire", ArtistNames.clean("Earth, Wind & Fire"))
        assertEquals("Tyler, The Creator", ArtistNames.clean("Tyler, The Creator"))
        assertEquals("The Beatles", ArtistNames.clean("The Beatles"))
    }

    @Test
    fun groupsDiacriticsAndArticles() {
        assertTrue(ArtistNames.matches("Beyoncé", "Beyonce"))
        assertTrue(ArtistNames.matches("The Beatles", "Beatles"))
        assertEquals(ArtistNames.key("The Beatles"), ArtistNames.key("Beatles"))
        assertFalse(ArtistNames.matches("Nirvana", "Pearl Jam"))
    }

    @Test
    fun detectsPlaceholdersAndChannels() {
        assertTrue(ArtistNames.isPlaceholder("Unknown Artist"))
        assertTrue(ArtistNames.isPlaceholder("YouTube Music"))
        assertTrue(ArtistNames.isSuspicious("Nirvana - Topic"))
        assertFalse(ArtistNames.isSuspicious("Nirvana"))
    }
}
