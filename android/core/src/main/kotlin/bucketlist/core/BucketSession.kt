package bucketlist.core

import java.io.IOException

private const val READ_ATTEMPTS = 3
private const val RETRY_BASE_DELAY_MS = 1_000L

class ListSnapshot(val items: List<BucketItem>, val unreadableCount: Int) {
    val toDo: List<BucketItem> get() = items.filter { !it.isDone }.sortedByDescending { it.createdAtMillis }
    val done: List<BucketItem> get() = items.filter { it.isDone }.sortedByDescending { it.doneAtMillis }

    fun withItem(item: BucketItem): ListSnapshot = ListSnapshot(items.filterNot { it.id == item.id } + item, unreadableCount)

    fun without(id: String): ListSnapshot = ListSnapshot(items.filterNot { it.id == id }, unreadableCount)

    companion object {
        val EMPTY = ListSnapshot(emptyList(), 0)
    }
}

class UnlockResult(val session: BucketSession, val authKey: ByteArray)

class BucketSession private constructor(val api: BucketApi, listKey: ByteArray) {
    private val listKey: ByteArray = listKey.copyOf()

    @Volatile
    var isLocked: Boolean = false
        private set

    @Synchronized
    private fun copyOfKey(): ByteArray {
        check(!isLocked) { "the list is locked" }
        return listKey.copyOf()
    }

    private inline fun <T> withKey(work: (ByteArray) -> T): T {
        val key = copyOfKey()
        try {
            return work(key)
        } finally {
            key.fill(0)
        }
    }

    fun load(): ListSnapshot = withKey { key ->
        val entries = retryIo(READ_ATTEMPTS, RETRY_BASE_DELAY_MS) { api.listEntries() }
        val opened = entries.map { entry -> runCatching { BucketItem(entry.id, openItem(key, entry.sealedMeta)) }.getOrNull() }
        ListSnapshot(opened.filterNotNull(), opened.count { it == null })
    }

    fun add(title: String, notes: String, nowMillis: Long = System.currentTimeMillis()): BucketItem {
        val fields = ItemFields(cleanTitle(title), cleanNotes(notes), nowMillis, null)
        return withKey { key ->
            val id = api.createEntry(sealItem(key, fields))
            val marker = sealMarker(key)
            api.appendContent(id, 0, marker)
            api.finalize(id, marker.size.toLong())
            BucketItem(id, fields)
        }
    }

    fun edit(item: BucketItem, title: String, notes: String): BucketItem =
        save(item, ItemFields(cleanTitle(title), cleanNotes(notes), item.createdAtMillis, item.doneAtMillis))

    fun setDone(item: BucketItem, done: Boolean, nowMillis: Long = System.currentTimeMillis()): BucketItem {
        if (done == item.isDone) return item
        return save(item, ItemFields(item.title, item.notes, item.createdAtMillis, if (done) nowMillis else null))
    }

    private fun save(item: BucketItem, fields: ItemFields): BucketItem {
        withKey { key -> api.updateMeta(item.id, sealItem(key, fields)) }
        return BucketItem(item.id, fields)
    }

    fun delete(item: BucketItem) = api.delete(item.id)

    fun exportListKey(): ByteArray = copyOfKey()

    @Synchronized
    fun lock() {
        isLocked = true
        listKey.fill(0)
    }

    fun logout() {
        try {
            if (api.token != null) api.logout()
        } finally {
            lock()
        }
    }

    companion object {
        fun createAccount(api: BucketApi, setupToken: String, passphrase: CharArray, params: KdfParams = newKdfParams()): UnlockResult {
            val keys = deriveKeys(passphrase, params)
            api.createAccount(setupToken, params, keys.authKey, wrapListKey(keys.masterKey, randomBytes(KEY_BYTES)))
            return unlock(api, passphrase)
        }

        fun unlock(api: BucketApi, passphrase: CharArray): UnlockResult {
            val keys = deriveKeys(passphrase, api.getKdf())
            val login = api.login(keys.authKey)
            val listKey = try {
                unwrapListKey(keys.masterKey, login.wrappedListKey)
            } catch (error: DecryptionException) {
                throw IOException("server returned a list key that does not match this passkey", error)
            }
            return UnlockResult(BucketSession(api, listKey), keys.authKey)
        }

        fun withListKey(api: BucketApi, listKey: ByteArray): BucketSession {
            require(listKey.size == KEY_BYTES) { "invalid list key" }
            return BucketSession(api, listKey)
        }
    }
}
