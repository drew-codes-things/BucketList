package bucketlist.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

const val MAX_TITLE_LENGTH = 120
const val MAX_NOTES_LENGTH = 4000

private const val ITEM_VERSION = 1
private const val ITEM_LABEL = "bucket-item-v1"
private const val MARKER_LABEL = "bucket-marker-v1"
private const val NOT_DONE = -1L

class ItemFields(
    val title: String,
    val notes: String,
    val createdAtMillis: Long,
    val doneAtMillis: Long?,
) {
    val isDone: Boolean get() = doneAtMillis != null
}

class BucketItem(val id: String, val fields: ItemFields) {
    val title: String get() = fields.title
    val notes: String get() = fields.notes
    val createdAtMillis: Long get() = fields.createdAtMillis
    val doneAtMillis: Long? get() = fields.doneAtMillis
    val isDone: Boolean get() = fields.isDone
}

fun cleanTitle(input: String): String {
    val title = input.trim()
    require(title.isNotEmpty()) { "Give it a title" }
    require(title.length <= MAX_TITLE_LENGTH) { "Keep the title under $MAX_TITLE_LENGTH characters" }
    return title
}

fun cleanNotes(input: String): String {
    val notes = input.trim()
    require(notes.length <= MAX_NOTES_LENGTH) { "Keep the notes under $MAX_NOTES_LENGTH characters" }
    return notes
}

fun sealItem(listKey: ByteArray, fields: ItemFields): ByteArray {
    val buffer = ByteArrayOutputStream()
    DataOutputStream(buffer).use { out ->
        out.writeByte(ITEM_VERSION)
        out.writeUTF(fields.title)
        out.writeUTF(fields.notes)
        out.writeLong(fields.createdAtMillis)
        out.writeLong(fields.doneAtMillis ?: NOT_DONE)
    }
    return sealBlob(listKey, ITEM_LABEL, buffer.toByteArray())
}

fun openItem(listKey: ByteArray, sealed: ByteArray): ItemFields {
    val plain = openBlob(listKey, ITEM_LABEL, sealed)
    try {
        DataInputStream(ByteArrayInputStream(plain)).use { input ->
            val version = input.readUnsignedByte()
            if (version != ITEM_VERSION) throw DecryptionException("unsupported item version $version")
            val title = input.readUTF()
            val notes = input.readUTF()
            val createdAt = input.readLong()
            val doneAt = input.readLong().takeIf { it != NOT_DONE }
            return ItemFields(title, notes, createdAt, doneAt)
        }
    } catch (error: IOException) {
        throw DecryptionException("item is malformed", error)
    }
}

fun sealMarker(listKey: ByteArray): ByteArray = sealBlob(listKey, MARKER_LABEL, ByteArray(0))
