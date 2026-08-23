package com.yashikota.omaigenzo.data

/** Thread-safe LRU bounded by actual byte cost instead of entry count. */
class ByteBudgetLruCache<K : Any, V : Any>(
    private val maxBytes: Long,
    private val sizeOf: (V) -> Long,
) {
    private val entries = LinkedHashMap<K, V>(16, 0.75f, true)
    var sizeBytes: Long = 0L
        private set

    @Synchronized
    operator fun get(key: K): V? = entries[key]

    @Synchronized
    fun put(key: K, value: V) {
        entries.put(key, value)?.let { sizeBytes -= sizeOf(it) }
        sizeBytes += sizeOf(value)
        val iterator = entries.entries.iterator()
        while (sizeBytes > maxBytes && iterator.hasNext()) {
            sizeBytes -= sizeOf(iterator.next().value)
            iterator.remove()
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        sizeBytes = 0L
    }

    @Synchronized
    fun keysInLruOrder(): List<K> = entries.keys.toList()
}
