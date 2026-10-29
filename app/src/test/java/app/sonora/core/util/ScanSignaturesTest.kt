package app.sonora.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanSignaturesTest {

    private fun signature(
        path: String,
        size: Long = 1L,
        dateModified: Long = 1L,
        folder: String = "/storage/emulated/0/Music",
    ): ScanSignature = ScanSignature(path, size, dateModified, folder)

    @Test
    fun reusesUnchangedFiles() {
        val first = signature("a.mp3")
        val second = signature("b.flac", size = 10, dateModified = 20)
        val previous = mapOf(first.key to first, second.key to second)

        val diff = diffScanSignatures(listOf(first, second), previous)

        assertEquals(setOf(first.key, second.key), diff.unchanged)
        assertTrue(diff.changed.isEmpty())
        assertTrue(diff.removed.isEmpty())
    }

    @Test
    fun detectsSizeChange() {
        val current = signature("a.mp3", size = 100)
        val previous = mapOf(current.key to current.copy(size = 99))

        assertEquals(setOf(current.key), diffScanSignatures(listOf(current), previous).changed)
    }

    @Test
    fun detectsDateModifiedChange() {
        val current = signature("a.mp3", dateModified = 200)
        val previous = mapOf(current.key to current.copy(dateModified = 100))

        assertEquals(setOf(current.key), diffScanSignatures(listOf(current), previous).changed)
    }

    @Test
    fun detectsNewAndDeletedFiles() {
        val existing = signature("a.mp3")
        val added = signature("b.mp3")
        val previous = mapOf(existing.key to existing)

        val diff = diffScanSignatures(listOf(added), previous)

        assertEquals(setOf(added.key), diff.changed)
        assertEquals(setOf(existing.key), diff.removed)
        assertTrue(diff.unchanged.isEmpty())
    }

    @Test
    fun keyIncludesFolder() {
        val inFirstFolder = signature("a.mp3", folder = "/music/one")
        val inSecondFolder = signature("a.mp3", folder = "/music/two")
        assertNotEquals(inFirstFolder.key, inSecondFolder.key)

        val previous = mapOf(inFirstFolder.key to inFirstFolder)
        val diff = diffScanSignatures(listOf(inSecondFolder), previous)

        assertEquals(setOf(inSecondFolder.key), diff.changed)
        assertEquals(setOf(inFirstFolder.key), diff.removed)
    }

    @Test
    fun emptyPreviousMeansEverythingIsChanged() {
        val first = signature("a.mp3")
        val second = signature("b.mp3")

        val diff = diffScanSignatures(listOf(first, second), emptyMap())

        assertEquals(setOf(first.key, second.key), diff.changed)
        assertTrue(diff.unchanged.isEmpty())
        assertTrue(diff.removed.isEmpty())
    }

    @Test
    fun duplicateCurrentEntriesAreCountedOnce() {
        val first = signature("a.mp3")
        val duplicate = signature("a.mp3")

        val diff = diffScanSignatures(listOf(first, duplicate), emptyMap())

        assertEquals(setOf(first.key), diff.changed)
        assertEquals(1, diff.changed.size)
    }

    @Test
    fun samePathInAnotherFolderIsNotTreatedAsUnchanged() {
        val current = signature("a.mp3", folder = "/music/one")
        val other = signature("a.mp3", folder = "/music/two")

        val diff = diffScanSignatures(listOf(current, other), mapOf(current.key to current))

        assertEquals(setOf(current.key), diff.unchanged)
        assertEquals(setOf(other.key), diff.changed)
    }
}
