package cn.alittlecookie.lutista.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SafDirectoryPathTest {
    @Test fun encodedTreeUriRemainsUnchanged() {
        val uri = "content://documents/tree/primary%3ADCIM%2Finput"
        assertEquals(SafDirectoryPath(uri, emptyList()), SafDirectoryPath.parse(uri))
    }

    @Test fun optionalRelativeDirectoriesDoNotChangeTheTreeGrant() {
        val uri = "content://documents/tree/primary%3ADCIM%2Finput"
        assertEquals(SafDirectoryPath(uri, listOf("nested", "photos")), SafDirectoryPath.parse("$uri|nested//photos/"))
    }

    @Test fun traversalAndMalformedSegmentsAreRejected() {
        for (suffix in listOf("..", "nested/../other", ".", "nested\\other", "nested|other", "bad\u0000name")) {
            assertThrows(IllegalArgumentException::class.java) { SafDirectoryPath.parse("content://documents/tree/root|$suffix") }
        }
    }

    @Test fun ordinaryPathsCannotBeMistakenForSafTrees() {
        assertThrows(IllegalArgumentException::class.java) { SafDirectoryPath.parse("/storage/emulated/0/DCIM/input") }
    }
}
