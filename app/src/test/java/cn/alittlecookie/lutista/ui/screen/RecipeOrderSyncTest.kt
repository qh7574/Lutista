package cn.alittlecookie.lutista.ui.screen

import androidx.compose.runtime.mutableStateListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [syncRecipeItems], the function that decides whether a database snapshot is
 * allowed to overwrite what the user currently sees in the recipe room.
 */
class RecipeOrderSyncTest {

    private fun filterItem(id: Long, name: String, isEnabled: Boolean = true) = RecipeItem(
        id = id,
        name = name,
        isEnabled = isEnabled,
        type = RecipeTab.FILTER,
        isBuiltIn = false
    )

    private fun watermarkItem(id: Long, name: String, isEnabled: Boolean = true) = RecipeItem(
        id = id,
        name = name,
        isEnabled = isEnabled,
        type = RecipeTab.WATERMARK,
        isBuiltIn = false
    )

    @Test
    fun `same rows keep the dragged order instead of the database order`() {
        val target = mutableStateListOf(
            filterItem(2L, "b"),
            filterItem(1L, "a")
        )
        val incoming = listOf(
            filterItem(1L, "a"),
            filterItem(2L, "b")
        )

        syncRecipeItems(target, incoming)

        assertEquals(listOf(2L, 1L), target.map { it.id })
    }

    @Test
    fun `structural change adopts the database order`() {
        val target = mutableStateListOf(
            filterItem(2L, "b"),
            filterItem(1L, "a")
        )
        val incoming = listOf(
            filterItem(1L, "a"),
            filterItem(3L, "c")
        )

        syncRecipeItems(target, incoming)

        assertEquals(listOf(1L, 3L), target.map { it.id })
    }

    @Test
    fun `first emission fills an empty list`() {
        val target = mutableStateListOf<RecipeItem>()
        val incoming = listOf(
            filterItem(1L, "a"),
            filterItem(2L, "b")
        )

        syncRecipeItems(target, incoming)

        assertEquals(listOf(1L, 2L), target.map { it.id })
    }

    @Test
    fun `row fields are refreshed without disturbing the local order`() {
        val target = mutableStateListOf(
            watermarkItem(2L, "边框", isEnabled = true),
            watermarkItem(1L, "旧名字", isEnabled = true)
        )
        val incoming = listOf(
            watermarkItem(1L, "新名字", isEnabled = false),
            watermarkItem(2L, "边框", isEnabled = true)
        )

        syncRecipeItems(target, incoming)

        assertEquals(listOf(2L, 1L), target.map { it.id })
        assertEquals("新名字", target[1].name)
        assertFalse(target[1].isEnabled)
        assertTrue(target[0].isEnabled)
    }

    @Test
    fun `identical snapshot is a no-op`() {
        val target = mutableStateListOf(
            filterItem(1L, "a"),
            filterItem(2L, "b")
        )
        val incoming = listOf(
            filterItem(1L, "a"),
            filterItem(2L, "b")
        )

        syncRecipeItems(target, incoming)

        assertEquals(listOf(1L, 2L), target.map { it.id })
        assertEquals(2, target.size)
    }

    @Test
    fun `added row adopts the database order even if the user just dragged`() {
        // A row inserted while the list is on screen (create / import / copy) changes the id set,
        // so the database order wins. Losing the in-progress drag here is intended: the new row
        // must land where the database says it belongs.
        val target = mutableStateListOf(
            filterItem(2L, "b"),
            filterItem(1L, "a")
        )
        val incoming = listOf(
            filterItem(3L, "new"),
            filterItem(1L, "a"),
            filterItem(2L, "b")
        )

        syncRecipeItems(target, incoming)

        assertEquals(listOf(3L, 1L, 2L), target.map { it.id })
    }

    @Test
    fun `deleted row adopts the database order`() {
        val target = mutableStateListOf(
            filterItem(3L, "c"),
            filterItem(1L, "a"),
            filterItem(2L, "b")
        )
        val incoming = listOf(
            filterItem(1L, "a"),
            filterItem(2L, "b")
        )

        syncRecipeItems(target, incoming)

        assertEquals(listOf(1L, 2L), target.map { it.id })
    }

    @Test
    fun `emptying the database clears the mirrored list`() {
        val target = mutableStateListOf(
            watermarkItem(1L, "边框"),
            watermarkItem(2L, "边框2")
        )

        syncRecipeItems(target, emptyList())

        assertTrue(target.isEmpty())
    }

    @Test
    fun `empty target and empty snapshot stay empty`() {
        val target = mutableStateListOf<RecipeItem>()

        syncRecipeItems(target, emptyList())

        assertTrue(target.isEmpty())
    }

    @Test
    fun `same ids in a new order keeps the dragged order while refreshing every field`() {
        // The write-back itself produces this shape: identical ids, different `sortOrder`. If the
        // mirror adopted it, the list would jump back under the user's finger — the exact symptom
        // of the reported bug.
        val target = mutableStateListOf(
            filterItem(3L, "c", isEnabled = true),
            filterItem(1L, "a", isEnabled = true),
            filterItem(2L, "b", isEnabled = true)
        )
        val incoming = listOf(
            filterItem(1L, "a-renamed"),
            filterItem(2L, "b", isEnabled = false),
            filterItem(3L, "c")
        )

        syncRecipeItems(target, incoming)

        assertEquals(listOf(3L, 1L, 2L), target.map { it.id })
        assertEquals("a-renamed", target[1].name)
        assertFalse(target[2].isEnabled)
        assertTrue(target[0].isEnabled)
    }

    @Test
    fun `single row list is stable across snapshots`() {
        val target = mutableStateListOf(filterItem(7L, "only"))
        val incoming = listOf(filterItem(7L, "only"))

        syncRecipeItems(target, incoming)

        assertEquals(listOf(7L), target.map { it.id })
    }

    @Test
    fun `empty current order is never persisted`() {
        assertFalse(shouldPersistOrder(emptyList(), emptyList()))
        assertFalse(shouldPersistOrder(emptyList(), listOf(1L, 2L)))
    }

    @Test
    fun `unchanged order is not persisted again`() {
        assertFalse(shouldPersistOrder(listOf(1L, 2L, 3L), listOf(1L, 2L, 3L)))
    }

    @Test
    fun `reordered but same id set must be persisted`() {
        assertTrue(shouldPersistOrder(listOf(3L, 1L, 2L), listOf(1L, 2L, 3L)))
    }

    @Test
    fun `first drag after the first snapshot is persisted`() {
        // Right after the rows are loaded the marker equals the DB order; the very first drag has to
        // be written even though the marker was just filled.
        assertTrue(shouldPersistOrder(listOf(2L, 1L), listOf(1L, 2L)))
        // ...and a marker that was never filled (no snapshot yet) also allows the write.
        assertTrue(shouldPersistOrder(listOf(2L, 1L), emptyList()))
    }

    @Test
    fun `row removed locally is persisted`() {
        assertTrue(shouldPersistOrder(listOf(1L, 2L), listOf(1L, 2L, 3L)))
    }
}
