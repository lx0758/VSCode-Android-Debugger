package com.liux.android.debugger_adapter.source

class SourceIndexer {
    val index = SourceIndex()

    fun set(entries: List<Map<String, String>>) {
        index.clear()
        add(entries)
    }

    fun add(entries: List<Map<String, String>>) {
        for (entry in entries) {
            val fqn = entry["fqn"] ?: continue
            val file = entry["file"] ?: continue
            if (fqn.isEmpty() || file.isEmpty()) continue
            val packageName = fqn.substringBeforeLast('.', "")
            index.add(FileEntry(file, packageName, listOf(fqn), entry["sourceUri"]))
        }
    }

    fun sourceIndex(): SourceIndex = index
}
