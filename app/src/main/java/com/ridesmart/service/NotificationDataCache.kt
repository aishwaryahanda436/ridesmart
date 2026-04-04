package com.ridesmart.service

object NotificationDataCache {

    private const val ENTRY_TTL_MS = 30_000L

    private data class CachedEntry(val nodes: List<String>, val writtenAtMs: Long)

    private val store = mutableMapOf<String, CachedEntry>()

    @Synchronized
    fun store(pkg: String, title: String, body: String) {
        val nodes = buildList {
            if (title.isNotBlank()) add(title.trim())
            body.lines().map { it.trim() }.filter { it.isNotBlank() }.forEach { add(it) }
        }
        if (nodes.isEmpty()) return
        store[pkg] = CachedEntry(nodes, System.currentTimeMillis())
    }

    @Synchronized
    fun getFreshNodes(pkg: String): List<String>? {
        val entry = store[pkg] ?: return null
        return if (System.currentTimeMillis() - entry.writtenAtMs <= ENTRY_TTL_MS)
            entry.nodes else null
    }

    @Synchronized
    fun invalidate(pkg: String) { store.remove(pkg) }

    @Synchronized
    fun clear() { store.clear() }
}
