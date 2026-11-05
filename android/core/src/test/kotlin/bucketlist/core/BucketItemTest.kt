package bucketlist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BucketItemTest {
    private val key = randomBytes(KEY_BYTES)

    @Test
    fun anItemSurvivesSealingAndOpening() {
        val fields = ItemFields("See the northern lights", "Tromsø in February\nbook early", 1_700_000_000_000, null)
        val opened = openItem(key, sealItem(key, fields))
        assertEquals(fields.title, opened.title)
        assertEquals(fields.notes, opened.notes)
        assertEquals(fields.createdAtMillis, opened.createdAtMillis)
        assertNull(opened.doneAtMillis)
        assertFalse(opened.isDone)
    }

    @Test
    fun aDoneItemKeepsWhenItWasDone() {
        val opened = openItem(key, sealItem(key, ItemFields("Skydive", "", 1L, 1_800_000_000_000)))
        assertTrue(opened.isDone)
        assertEquals(1_800_000_000_000, opened.doneAtMillis)
    }

    @Test
    fun theSealedItemDoesNotContainTheTitle() {
        val sealed = sealItem(key, ItemFields("Swim with sharks", "", 1L, null))
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("sharks"))
    }

    @Test
    fun anotherKeyCannotOpenAnItem() {
        val sealed = sealItem(key, ItemFields("Paris", "", 1L, null))
        assertFailsWith<DecryptionException> { openItem(randomBytes(KEY_BYTES), sealed) }
    }

    @Test
    fun aTamperedItemIsRejected() {
        val sealed = sealItem(key, ItemFields("Paris", "", 1L, null))
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertFailsWith<DecryptionException> { openItem(key, sealed) }
    }

    @Test
    fun theMarkerCannotBeReadAsAnItem() {
        assertFailsWith<DecryptionException> { openItem(key, sealMarker(key)) }
    }

    @Test
    fun theMarkerIsNeverEmptySoTheServerAcceptsIt() {
        assertTrue(sealMarker(key).isNotEmpty())
    }

    @Test
    fun titlesAreTrimmedAndRequired() {
        assertEquals("Learn to surf", cleanTitle("  Learn to surf \n"))
        assertFailsWith<IllegalArgumentException> { cleanTitle("   ") }
        assertFailsWith<IllegalArgumentException> { cleanTitle("x".repeat(MAX_TITLE_LENGTH + 1)) }
        assertEquals(MAX_TITLE_LENGTH, cleanTitle("x".repeat(MAX_TITLE_LENGTH)).length)
    }

    @Test
    fun notesAreOptionalButLimited() {
        assertEquals("", cleanNotes("   "))
        assertFailsWith<IllegalArgumentException> { cleanNotes("x".repeat(MAX_NOTES_LENGTH + 1)) }
    }

    @Test
    fun theLongestNotesInAnyScriptStillSeal() {
        val notes = "🌍".repeat(MAX_NOTES_LENGTH / 2)
        assertEquals(notes, openItem(key, sealItem(key, ItemFields("Travel", cleanNotes(notes), 1L, null))).notes)
    }

    @Test
    fun theListKeyOnlyUnwrapsWithTheRightMasterKey() {
        val master = randomBytes(KEY_BYTES)
        val listKey = randomBytes(KEY_BYTES)
        val wrapped = wrapListKey(master, listKey)
        assertTrue(listKey.contentEquals(unwrapListKey(master, wrapped)))
        assertFailsWith<DecryptionException> { unwrapListKey(randomBytes(KEY_BYTES), wrapped) }
    }

    @Test
    fun theSamePasskeyAndSaltGiveTheSameKeys() {
        val params = KdfParams(ByteArray(16) { 7 }, 1, 64, 1)
        val first = deriveKeys("correct horse".toCharArray(), params)
        val second = deriveKeys("correct horse".toCharArray(), params)
        val other = deriveKeys("correct horsf".toCharArray(), params)
        assertTrue(first.masterKey.contentEquals(second.masterKey))
        assertTrue(first.authKey.contentEquals(second.authKey))
        assertFalse(first.masterKey.contentEquals(first.authKey))
        assertFalse(first.masterKey.contentEquals(other.masterKey))
    }
}
