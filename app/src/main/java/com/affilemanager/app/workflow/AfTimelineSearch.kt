package com.affilemanager.app.workflow

/** Follows AF-created source/destination links instead of stopping at one matching receipt. */
object AfTimelineSearch {
    private const val MAX_INDEXED_ITEMS = 200_000

    fun trace(receipts: List<AfOperationReceipt>, query: String): List<AfOperationReceipt> {
        val needle = searchable(query)
        if (needle.isBlank()) return receipts

        data class Edge(val receiptIndex: Int, val locations: Set<String>)
        val edges = ArrayList<Edge>()
        val edgesByLocation = HashMap<String, MutableList<Int>>()
        val initialLocations = LinkedHashSet<String>()
        var indexed = 0
        receipts.forEachIndexed { receiptIndex, receipt ->
            receipt.items.forEach { item ->
                if (indexed >= MAX_INDEXED_ITEMS) return@forEach
                val references = listOfNotNull(item.source, item.destination)
                val keys = references.map(AfLocationRef::identityKey).toSet()
                if (references.any { location ->
                        searchable(location.path).contains(needle) ||
                            searchable(location.displayLabel).contains(needle)
                    }
                ) {
                    initialLocations += keys
                }
                val edgeIndex = edges.size
                edges += Edge(receiptIndex, keys)
                keys.forEach { key -> edgesByLocation.getOrPut(key) { ArrayList() } += edgeIndex }
                indexed += 1
            }
        }
        if (initialLocations.isEmpty()) return emptyList()

        val queue = ArrayDeque(initialLocations)
        val visitedLocations = initialLocations.toMutableSet()
        val visitedEdges = HashSet<Int>()
        val matchingReceipts = HashSet<Int>()
        while (queue.isNotEmpty()) {
            val location = queue.removeFirst()
            edgesByLocation[location].orEmpty().forEach { edgeIndex ->
                if (!visitedEdges.add(edgeIndex)) return@forEach
                val edge = edges[edgeIndex]
                matchingReceipts += edge.receiptIndex
                edge.locations.forEach { connected ->
                    if (visitedLocations.add(connected)) queue.addLast(connected)
                }
            }
        }
        return receipts.filterIndexed { index, _ -> index in matchingReceipts }
    }

    private fun searchable(value: String): String = value.trim().replace('\\', '/').lowercase()
}
