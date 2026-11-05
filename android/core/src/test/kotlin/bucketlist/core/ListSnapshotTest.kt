package bucketlist.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ListSnapshotTest {
    private fun item(id: String, createdAt: Long, doneAt: Long? = null) = BucketItem(id, ItemFields(id, "", createdAt, doneAt))

    @Test
    fun toDoShowsTheNewestFirstAndDoneShowsTheMostRecentlyDoneFirst() {
        val snapshot = ListSnapshot(listOf(item("old", 1), item("new", 3), item("doneEarly", 2, 10), item("doneLate", 0, 20)), 0)
        assertEquals(listOf("new", "old"), snapshot.toDo.map { it.id })
        assertEquals(listOf("doneLate", "doneEarly"), snapshot.done.map { it.id })
    }

    @Test
    fun withItemReplacesAnItemWithTheSameId() {
        val snapshot = ListSnapshot(listOf(item("a", 1), item("b", 2)), 1).withItem(item("a", 1, 5))
        assertEquals(2, snapshot.items.size)
        assertEquals(listOf("a"), snapshot.done.map { it.id })
        assertEquals(1, snapshot.unreadableCount)
    }

    @Test
    fun withItemAddsANewItem() {
        assertEquals(listOf("b", "a"), ListSnapshot.EMPTY.withItem(item("a", 1)).withItem(item("b", 2)).toDo.map { it.id })
    }

    @Test
    fun withoutRemovesOnlyThatItem() {
        val snapshot = ListSnapshot(listOf(item("a", 1), item("b", 2)), 0).without("a").without("missing")
        assertEquals(listOf("b"), snapshot.items.map { it.id })
    }
}
